package kr.co.hanium.dreamup.walksafe.voice.output

import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.CommandSpeechReadiness
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.SpeechMessageFamily
import kr.co.hanium.dreamup.walksafe.feedback.UtteranceCallbackRegistry
import kr.co.hanium.dreamup.walksafe.feedback.dispatchCurrentNavigationSpeech
import kr.co.hanium.dreamup.walksafe.feedback.shouldSuppressFeedbackDuringVoiceRecognition
import kr.co.hanium.dreamup.walksafe.feedback.utteranceTerminalTimeoutMs
import kr.co.hanium.dreamup.walksafe.voice.CurrentStateSpeechLease
import kr.co.hanium.dreamup.walksafe.voice.CommandSpeechTracker
import kr.co.hanium.dreamup.walksafe.voice.CommandSpeechProgress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns native and accessibility speech delivery, response callbacks and READY continuations.
 * Construction starts no engine, worker, timer or input. Input, route, risk and privacy owners are
 * consulted through their existing facts/effects and remain responsible for their own state.
 */
internal class SpeechOutputController(
    private val reader: SpeechOutputReader,
    private val input: SpeechOutputInputEffects,
    private val presentation: SpeechOutputPresentation,
    private val readyEffects: SpeechOutputReadyEffects,
    private val storage: SpeechOutputStorageBinding,
    private val actuatorFactory: SpeechOutputActuatorFactory,
    private val scheduler: SpeechOutputScheduler,
    /** Keeps diagnostic logging at the Android boundary without changing dispatch or completion. */
    private val debugDiagnostic: (String) -> Unit = { message ->
        android.util.Log.d("WalkSafeVoiceInput", message)
    },
) {
    private var feedbackActuator: AndroidFeedbackActuator? = null
        set(value) {
            if (field !== value) storage.invalidate()
            field = value
        }
    private val commandSpeechResponseCallbacks = UtteranceCallbackRegistry()
    private var commandSpeechResponseGeneration = 0L
    /** A reference to the registered response's existing lease, never another output authority. */
    private var pendingCommandResponseCurrent: (() -> Boolean)? = null
    private val commandSpeechTracker = CommandSpeechTracker()
    private val nextNativeSpeechObservation = AtomicLong()
    fun commandSpeechRevision(commandId: Long): Long = commandSpeechTracker.revision(commandId)
    fun beginCommandSpeech(commandId: Long) = commandSpeechTracker.begin(commandId)
    fun commandSpeechProgress(commandId: Long): CommandSpeechProgress = commandSpeechTracker.progress(commandId)

    private data class PendingStatusExplanation(
        val message: String,
        val token: CommandResponseToken,
        val actuator: AndroidFeedbackActuator,
        val isCurrent: () -> Boolean,
    )
    private var pendingStatusExplanation: PendingStatusExplanation? = null
    private data class DeferredStatusExplanation(
        val message: String,
        val poll: Runnable,
        val retire: () -> Unit,
        val isCurrent: () -> Boolean,
    )
    private var deferredStatusExplanation: DeferredStatusExplanation? = null

    private data class SpeechReadyOwner(
        val actuator: AndroidFeedbackActuator,
        val lifecycleGeneration: Int,
        val engineGeneration: Long,
    )
    private var processingSpeechReadyOwner: SpeechReadyOwner? = null
    private var completedSpeechReadyOwner: SpeechReadyOwner? = null
    private data class CommandSpeechReadyContinuation(
        val responseId: String,
        val responseGeneration: Long,
        val actuator: AndroidFeedbackActuator,
        val lifecycleGeneration: Int,
        val resume: () -> Boolean,
        val cancelPoll: () -> Unit,
        val onRetired: () -> Unit,
    )
    private var pendingCommandSpeechReadyContinuation: CommandSpeechReadyContinuation? = null

    private var lastRiskAnnouncementMs = 0L
    private var lastRiskAnnouncement = ""
    private var lastRiskAnnouncementTrackId = ""
    private var lastRiskAnnouncementRank = -1
    private var riskAnnouncementHoldUntilMs = 0L
    private var lastNavigationAnnouncementMs = 0L
    private var lastNavigationAnnouncement = ""
    private var navigationAnnouncementHoldUntilMs = 0L
    private var lastAdvisoryAnnouncementMs = 0L
    private var lastAdvisoryAnnouncement = ""
    private var lastInteractionAnnouncementMs = 0L
    private var lastInteractionAnnouncement = ""
    private var pendingTalkBackInteraction: Runnable? = null

    fun currentActuatorOrNull(): AndroidFeedbackActuator? = feedbackActuator
    fun currentCommandGeneration(): Long = commandSpeechResponseGeneration
    fun hasPendingCommandResponse(): Boolean = commandSpeechResponseCallbacks.size() > 0

    /** Keeps asynchronous command work pending without creating new speech authority. */
    fun reserveCommandSpeech(): (Boolean) -> Unit {
        val response = commandSpeechTracker.submit("pending-command-${nextNativeSpeechObservation.incrementAndGet()}", 1)
        val terminal = AtomicBoolean(false)
        return { completed -> if (terminal.compareAndSet(false, true)) commandSpeechTracker.terminal(response, completed) }
    }

    /** Observes direct native prompts while their caller retains all existing admission fences. */
    fun observeNativeCommandSpeech(onCompleted: () -> Unit, onFailed: () -> Unit,
        submit: (() -> Unit, () -> Unit) -> Boolean): Boolean {
        val response = commandSpeechTracker.submit("direct-native-${nextNativeSpeechObservation.incrementAndGet()}", 1)
        val terminal = AtomicBoolean(false)
        val done = { if (terminal.compareAndSet(false, true)) { commandSpeechTracker.terminal(response, true); onCompleted() } }
        val failed = { if (terminal.compareAndSet(false, true)) { commandSpeechTracker.terminal(response, false); onFailed() } }
        val accepted = try { submit(done, failed) } catch (error: Exception) { failed(); throw error }
        if (!accepted) failed()
        return accepted
    }

    private fun isFeedbackLifecycleCurrent(generation: Int): Boolean = reader.lifetime().let {
        it.foreground && it.generation == generation
    }

    internal class CommandSpeechResponseOwner private constructor(
        private val responseGeneration: Long,
        private val lifecycleGeneration: Int,
        private val captured: CommandSpeechContext,
        val homeResponse: Boolean,
        private val source: CurrentStateSpeechLease?,
    ) {
        fun isCurrent(controller: SpeechOutputController): Boolean {
            if (responseGeneration != controller.commandSpeechResponseGeneration) return false
            return isContextCurrent(controller)
        }

        /** A waiting status owns its context independently of the preceding sentence's terminal. */
        fun isContextCurrent(controller: SpeechOutputController): Boolean {
            if (!controller.isFeedbackLifecycleCurrent(lifecycleGeneration)) return false
            val current = controller.reader.commandContext()
            return current.recognitionGeneration == captured.recognitionGeneration &&
                current.actorId == captured.actorId &&
                (current.page == captured.page ||
                    (current.voiceInteractionPage && captured.voiceInteractionPage)) &&
                current.gatewayGeneration == captured.gatewayGeneration &&
                !current.recognitionActive && !current.gatewayRecording &&
                (captured.walkEpoch == null || current.walkEpoch == captured.walkEpoch) &&
                (!homeResponse || controller.reader.homeFeatureContextAvailable()) &&
                (source == null || controller.reader.stateSourceIsCurrent(source))
        }

        companion object {
            fun capture(controller: SpeechOutputController, source: CurrentStateSpeechLease?,
                responseGeneration: Long, lifecycleGeneration: Int): CommandSpeechResponseOwner {
                val current = controller.reader.commandContext()
                return CommandSpeechResponseOwner(responseGeneration, lifecycleGeneration, current,
                    controller.reader.homeFeatureContextAvailable() && current.visibleResponse, source)
            }
        }
    }

    fun captureCommandSpeechResponseOwner(
        source: CurrentStateSpeechLease? = null,
        responseGeneration: Long = commandSpeechResponseGeneration,
        lifecycleGeneration: Int = reader.lifetime().generation,
    ): CommandSpeechResponseOwner = CommandSpeechResponseOwner.capture(
        this, source, responseGeneration, lifecycleGeneration,
    )

    fun ensureFeedbackActuator(): AndroidFeedbackActuator {
        val current = feedbackActuator
        if (current != null) {
            storage.refresh(current)
            return current
        }
        val generation = reader.lifetime().generation
        var createdActuator: AndroidFeedbackActuator? = null
        return actuatorFactory.create(
            onUnavailable = {
                scheduler.runOnMain {
                    val actor = createdActuator
                    if (isFeedbackLifecycleCurrent(generation) && actor != null && feedbackActuator === actor) {
                        readyEffects.offlineSpeechUnavailable(generation, actor)
                    }
                }
            },
            onReady = {
                scheduler.runOnMain {
                    val actor = createdActuator ?: return@runOnMain
                    processOfflineSpeechReady(actor, generation, scheduler.elapsedRealtimeMs())
                }
            },
        ).also {
            createdActuator = it
            feedbackActuator = it
            storage.refresh(it)
        }
    }

    /** Called only after an explicit device check passes and its result is saved. */
    fun restartFailedActuatorAfterDeviceCheck(isCheckCurrent: () -> Boolean): Boolean {
        val lifecycleGeneration = reader.lifetime().generation
        fun current() = isFeedbackLifecycleCurrent(lifecycleGeneration) && isCheckCurrent()
        if (!scheduler.isMainThread() || !current()) return false
        val retired = feedbackActuator ?: return false
        if (!retired.hasTerminalSpeechFailure()) return false
        // Retire pending commands before close can publish terminal callbacks or reenter the UI.
        cancelCommandSpeechResponse()
        if (feedbackActuator !== retired || !current()) return false
        pendingStatusExplanation = null
        if (processingSpeechReadyOwner?.actuator === retired) processingSpeechReadyOwner = null
        if (completedSpeechReadyOwner?.actuator === retired) completedSpeechReadyOwner = null
        feedbackActuator = null
        retired.close()
        // Native close may change the session/lifetime or acquire another current actuator.
        if (!current()) return false
        ensureFeedbackActuator()
        return true
    }

    /** Close first, then invalidate the old storage binding, matching the original actor setter. */
    fun closeActuator() {
        val responseCurrent = pendingCommandResponseCurrent
        feedbackActuator?.close()
        feedbackActuator = null
        pendingStatusExplanation = null
        if (pendingCommandResponseCurrent === responseCurrent) pendingCommandResponseCurrent = null
        clearDeferredStatusExplanation()
    }

    fun processOfflineSpeechReady(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int,
        nowMs: Long): Boolean = handleOfflineSpeechReady(actuator, lifecycleGeneration, nowMs,
        readyEffects::refreshReadyUi, readyEffects::retryCurrentNavigation)

    fun speakNavigation(message: String, onCompleted: (() -> Unit)? = null): Boolean =
        dispatchNavigationSpeech(message, onCompleted = onCompleted)

    fun speakInteraction(message: String): Boolean = speakCommandResponse(message)

    fun speakStatusExplanation(message: String): Boolean =
        speakCommandResponse(message, statusExplanation = true)

    /** Camera evidence and its policy reservation remain with the advisory producer. */
    fun speakAdvisory(
        message: String,
        validUntilMs: Long,
        isStillDeliverable: () -> Boolean,
        onDelivered: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        val generation = reader.lifetime().generation
        if (!scheduler.isMainThread()) {
            scheduler.runOnMain {
                if (!isFeedbackLifecycleCurrent(generation)) {
                    onFailed()
                    return@runOnMain
                }
                speakAdvisory(message, validUntilMs, isStillDeliverable, onDelivered, onFailed)
            }
            return true
        }
        if (!isFeedbackLifecycleCurrent(generation) || !isStillDeliverable() ||
            reader.guidanceVoiceInputActive()) {
            onFailed()
            return false
        }
        // Rejection may synchronously publish cancellation before the actuator returns false.
        val terminal = AtomicBoolean(false)
        val delivered = { if (terminal.compareAndSet(false, true)) onDelivered() }
        val failed = { if (terminal.compareAndSet(false, true)) onFailed() }
        val accepted = ensureFeedbackActuator().speakAdvisory(
            message = message, validUntilMs = validUntilMs, isStillValid = isStillDeliverable,
            onCompleted = delivered, onFailed = failed,
        )
        if (!accepted) failed()
        return accepted
    }

    /** Route preparation cancels exactly its registration without advancing microphone fences. */
    fun cancelRegisteredCommandResponse(token: CommandResponseToken) {
        clearCommandSpeechReadyContinuationIfOwned(token.responseId, token.generation)
        commandSpeechTracker.retireResponse(token.responseId)
        commandSpeechResponseCallbacks.cancel(listOf(token.responseId))
    }

    fun failRegisteredCommandResponse(token: CommandResponseToken) {
        commandSpeechResponseCallbacks.takeTerminalCallback(
            token.responseId, completed = false, notifyFailure = true,
        )?.invoke()
    }

    fun detachPendingTalkBackInteraction(): Runnable? {
        val pending = pendingTalkBackInteraction
        pendingTalkBackInteraction = null
        return pending
    }

    fun cancelPendingTalkBackInteraction() {
        pendingTalkBackInteraction?.let(presentation::removeInteraction)
        pendingTalkBackInteraction = null
    }

    /** Pause intentionally retained interaction/navigation duplicate history in the original flow. */
    fun resetTalkBackHoldsAfterPause() {
        lastRiskAnnouncementRank = -1
        lastRiskAnnouncementTrackId = ""
        riskAnnouncementHoldUntilMs = 0L
        navigationAnnouncementHoldUntilMs = 0L
        lastAdvisoryAnnouncementMs = 0L
        lastAdvisoryAnnouncement = ""
    }

    fun resetTalkBackForNewWalk() {
        lastRiskAnnouncementMs = 0L
        lastRiskAnnouncement = ""
        lastRiskAnnouncementTrackId = ""
        lastRiskAnnouncementRank = -1
        riskAnnouncementHoldUntilMs = 0L
        lastNavigationAnnouncementMs = 0L
        lastNavigationAnnouncement = ""
        navigationAnnouncementHoldUntilMs = 0L
        lastAdvisoryAnnouncementMs = 0L
        lastAdvisoryAnnouncement = ""
        lastInteractionAnnouncementMs = 0L
        lastInteractionAnnouncement = ""
    }

    fun dispatchNavigationSpeech(
        message: String,
        onCompleted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        isInstructionStillCurrent: () -> Boolean = { true },
    ): Boolean {
        // Capture before posting to UI, not when a stale queued request is eventually run.
        val generation = reader.lifetime().generation
        val owner = reader.navigationOwner()
        val terminal = AtomicBoolean(false)
        val trackedResponse = commandSpeechTracker.submit("navigation-${nextNativeSpeechObservation.incrementAndGet()}", 1)
        val cancelled = AtomicBoolean(false)
        fun outputCurrent(): Boolean =
            !cancelled.get() && !terminal.get() &&
                isFeedbackLifecycleCurrent(generation) &&
                owner.epoch != null && reader.navigationOwnerIsCurrent(owner)
        fun failOnce() {
            if (terminal.compareAndSet(false, true)) { commandSpeechTracker.terminal(trackedResponse, false); onFailed?.invoke() }
        }
        val deliver: () -> Boolean = delivery@{
            if (!outputCurrent()) {
                failOnce()
                return@delivery false
            }
            dispatchCurrentNavigationSpeech(
                message = message,
                onCompleted = {
                    if (outputCurrent() && terminal.compareAndSet(false, true)) {
                        commandSpeechTracker.terminal(trackedResponse, true)
                        onCompleted?.invoke()
                    } else {
                        failOnce()
                    }
                },
                isStillValid = ::outputCurrent,
                speakWithTts = { text, completed, failed ->
                ensureFeedbackActuator().speakNavigation(
                    message = text,
                    isStillValidAtStart = { outputCurrent() && isInstructionStillCurrent() },
                        onCompleted = {
                            scheduler.runOnMain {
                                if (outputCurrent()) completed?.invoke() else failOnce()
                            }
                        },
                        onFailed = {
                            scheduler.runOnMain {
                                if (outputCurrent()) failed?.invoke() else failOnce()
                            }
                        },
                        // Cancellation is terminal, not an engine failure eligible for fallback.
                        onCancelled = {
                            scheduler.runOnMain {
                                cancelled.set(true)
                                failOnce()
                            }
                        },
                        requiresExplicitTerminalCallback = true,
                    )
                },
            fallBackToTalkBack = { text, _ ->
                if (!outputCurrent() || !reader.screenReaderActive() || !isInstructionStillCurrent()) {
                    false
                } else {
                    announceForTalkBack(
                        message = text,
                        priority = SpeechAnnouncementPriority.NAVIGATION,
                        isStillValid = { outputCurrent() && isInstructionStillCurrent() },
                        ).also { dispatched ->
                            // This API reports event dispatch, not TalkBack utterance completion.
                            // Release the caller's reservation without committing route progress.
                            if (dispatched) failOnce()
                        }
                    }
                },
                onFailed = ::failOnce,
            )
        }
        if (!scheduler.isMainThread()) {
            scheduler.runOnMain { deliver() }
            return true
        }
        return deliver()
    }

    fun speakCommandResponse(
        message: String,
        preparingInput: Boolean = false,
        onCompleted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        statusExplanation: Boolean = false,
        onCancelled: (() -> Unit)? = null,
        stateSource: CurrentStateSpeechLease? = null,
        additionalCurrent: () -> Boolean = { true },
        onRetired: (() -> Unit)? = null,
        speechParts: List<String>? = null,
    ): Boolean {
        val nativeParts = speechParts?.toList() ?: listOf(message)
        if (nativeParts.isEmpty() || nativeParts.any { it.isBlank() }) { onFailed?.invoke(); return false }
        val lifecycleGeneration = reader.lifetime().generation
        if (!scheduler.isMainThread()) {
            // The queued request cannot borrow a replacement actor, page, walk or response owner.
            val queuedOwner = captureCommandSpeechResponseOwner(stateSource,
                commandSpeechResponseGeneration, lifecycleGeneration)
            scheduler.runOnMain {
                if (!queuedOwner.isCurrent(this) || !additionalCurrent()) {
                    onFailed?.invoke()
                    return@runOnMain
                }
                speakCommandResponse(message, preparingInput, onCompleted, onFailed, statusExplanation, onCancelled,
                    stateSource, additionalCurrent, onRetired, nativeParts)
            }
            return true
        }
        if (!isFeedbackLifecycleCurrent(lifecycleGeneration) || stateSource?.let { !reader.stateSourceIsCurrent(it) } == true || !additionalCurrent()) {
            onFailed?.invoke()
            return false
        }
        presentation.showLastResult(message)
        val visibleResponse = reader.commandContext().visibleResponse
        if (visibleResponse) presentation.showVoiceStatus(message)
        if (shouldSuppressFeedbackDuringVoiceRecognition(
                reader.commandContext().let { it.recognitionActive || it.gatewayRecording },
                isRisk = false,
            )
        ) {
            onFailed?.invoke()
            return false
        }
        // An automatic status repeat has no independent completion consumer. Keep its
        // current registration so a duplicate cannot invalidate START or flush its own audio.
        val coalescibleStatus = statusExplanation && !preparingInput && stateSource == null &&
            onCompleted == null && onFailed == null && onCancelled == null && onRetired == null
        if (coalescibleStatus) {
            val pending = pendingStatusExplanation
            if (pending != null && pending.message == message &&
                pending.actuator === feedbackActuator &&
                commandSpeechResponseCallbacks.contains(pending.token.responseId) && pending.isCurrent()
            ) return true
            if (deferredStatusExplanation?.let { it.message == message && it.isCurrent() } == true) return true
        }
        if (statusExplanation && hasPendingCommandResponse() &&
            pendingCommandResponseCurrent?.let { runCatching(it).getOrDefault(false) } != false) {
            // Automatic state changes must not retire an unfinished command, installed-route
            // introduction or multipart response. Keep only the newest still-owned status.
            val owner = captureCommandSpeechResponseOwner(stateSource,
                commandSpeechResponseGeneration, lifecycleGeneration)
            val actor = feedbackActuator
            lateinit var deferred: DeferredStatusExplanation
            val retire = { (onRetired ?: onCancelled)?.invoke(); Unit }
            val current = { owner.isContextCurrent(this) && feedbackActuator === actor && additionalCurrent() }
            val poll = Runnable {
                if (deferredStatusExplanation !== deferred) return@Runnable
                val stillCurrent = current()
                // A caller predicate may publish a newer status while checking this one.
                if (deferredStatusExplanation !== deferred) return@Runnable
                if (!stillCurrent) {
                    deferredStatusExplanation = null
                    deferred.retire()
                } else if (hasPendingCommandResponse()) {
                    scheduler.postReadiness(deferred.poll, 100L)
                } else {
                    deferredStatusExplanation = null
                    speakCommandResponse(message, preparingInput, onCompleted, onFailed,
                        statusExplanation, onCancelled, stateSource, additionalCurrent, onRetired, nativeParts)
                }
            }
            deferred = DeferredStatusExplanation(message, poll, retire, current)
            clearDeferredStatusExplanation()
            deferredStatusExplanation = deferred
            scheduler.postReadiness(poll, 100L)
            return true
        }
        pendingStatusExplanation = null
        // New output owns the visible response; cancellation of an older TTS cannot overwrite it.
        input.invalidateHomeWakeInput()
        clearCommandSpeechReadyContinuation()
        val responseGeneration = ++commandSpeechResponseGeneration
        // registerLatest retires old callbacks silently. Preserve retry for an interrupted
        // route decision before its callback is retired, without changing other response policy.
        readyEffects.beforeCommandReplacement(responseGeneration - 1L)
        val retiredCallbacks = commandSpeechResponseCallbacks.takeRetirementCallbacks()
        val responseId = "command-response-$responseGeneration"
        val responseOwner = captureCommandSpeechResponseOwner(stateSource, responseGeneration, lifecycleGeneration)
        val homeResponse = responseOwner.homeResponse
        var responseFailureDetail = "음성 안내를 완료하지 못했습니다. 화면 안내를 확인하고 다시 시도해 주세요."
        val trackedResponse = commandSpeechTracker.submit(responseId, nativeParts.size)
        val responseToken = CommandResponseToken(responseId, responseGeneration)
        input.promptState(responseToken, if (preparingInput) CommandPromptEffect.PREPARING
            else CommandPromptEffect.ORDINARY_RESPONSE)
        fun responseCurrent(): Boolean = responseOwner.isCurrent(this) && additionalCurrent()
        pendingCommandResponseCurrent = ::responseCurrent
        commandSpeechResponseCallbacks.registerLatest(
            responseId,
            onRetired = { commandSpeechTracker.terminal(trackedResponse, false); onRetired?.invoke() },
            onCompleted = {
                if (responseGeneration == commandSpeechResponseGeneration) pendingCommandResponseCurrent = null
                val current = responseCurrent()
                commandSpeechTracker.terminal(trackedResponse, current)
                if (!current && preparingInput && responseGeneration == commandSpeechResponseGeneration) {
                    input.promptState(responseToken, CommandPromptEffect.CLEAR)
                }
                presentation.refreshVoiceControls()
                if (current) {
                    presentation.navigationStatus("voice=feedback_completed")
                    presentation.commandTerminalDiagnostic(completed = true)
                    onCompleted?.invoke()
                } else {
                    onFailed?.invoke()
                }
            },
            onFailed = {
                if (responseGeneration == commandSpeechResponseGeneration) pendingCommandResponseCurrent = null
                commandSpeechTracker.terminal(trackedResponse, false)
                if (preparingInput && responseGeneration == commandSpeechResponseGeneration) {
                    input.promptState(responseToken, CommandPromptEffect.CLEAR)
                }
                presentation.refreshVoiceControls()
                if (responseCurrent()) {
                    if (visibleResponse) presentation.showVoiceStatus(
                        "$message\n$responseFailureDetail",
                    )
                    presentation.navigationStatus("voice=feedback_retry_required")
                    presentation.commandTerminalDiagnostic(completed = false)
                }
                onFailed?.invoke()
            },
        )
        // A new response replaces waiting state information explicitly. An automatic terminal
        // of the previous response only resolves that old response; it cannot retire this status.
        clearDeferredStatusExplanation()
        // Retired owners clean up only their own request after the replacement is installed.
        retiredCallbacks.forEach { callback -> scheduler.postRetired { callback() } }
        presentation.refreshVoiceControls()
        val completed: () -> Unit = {
            scheduler.runOnMain {
                clearCommandSpeechReadyContinuationIfOwned(responseId, responseGeneration)
                commandSpeechResponseCallbacks.takeTerminalCallback(
                    responseId, completed = true, notifyFailure = true,
                )?.invoke()
                presentation.refreshVoiceControls()
                input.refreshHomeWakeListening()
            }
        }
        val failed: () -> Unit = {
            scheduler.runOnMain {
                clearCommandSpeechReadyContinuationIfOwned(responseId, responseGeneration)
                commandSpeechResponseCallbacks.takeTerminalCallback(
                    responseId, completed = false, notifyFailure = true,
                )?.invoke()
                presentation.refreshVoiceControls()
                input.refreshHomeWakeListening()
            }
        }
        val cancelled: () -> Unit = {
            scheduler.runOnMain {
                commandSpeechTracker.terminal(trackedResponse, false)
                if (retireCommandSpeechResponseIfOwned(responseId, responseGeneration, requireRegistered = true)) {
                    input.cancelDeferredInput()
                    onCancelled?.invoke()
                    presentation.refreshVoiceControls()
                }
            }
        }
        val actuator = ensureFeedbackActuator()
        if (coalescibleStatus) pendingStatusExplanation = PendingStatusExplanation(
            message, responseToken, actuator, ::responseCurrent,
        )
        val readinessDeadlineMs = scheduler.elapsedRealtimeMs() +
            AndroidFeedbackActuator.INITIALIZATION_READINESS_TIMEOUT_MS
        var waitingForReadiness = false
        var dispatchAttempted = false
        var dispatchResult: Boolean? = null
        var partIndex = 0
        lateinit var readinessPoll: Runnable
        fun dispatchWhenReady(): Boolean {
            if (dispatchAttempted) return dispatchResult ?: true
            fun waitForCurrentReadiness() {
                rememberCommandSpeechReadyContinuation(
                    responseId, responseGeneration, actuator, lifecycleGeneration,
                    resume = { dispatchWhenReady() },
                    cancelPoll = { scheduler.removeReadiness(readinessPoll) },
                    onRetired = cancelled,
                )
                scheduler.postReadiness(readinessPoll, 100L)
            }
            when (commandSpeechResponseCallbacks.commandSpeechReadiness(
                utteranceId = responseId,
                requestCurrent = responseCurrent() && feedbackActuator === actuator,
                speechInitializing = actuator.isSpeechInitializing(),
                nowMs = scheduler.elapsedRealtimeMs(),
                // Waiting behind a real sentence is not an engine initialization timeout.
                deadlineMs = if (statusExplanation && !actuator.isSpeechInitializing()) Long.MAX_VALUE
                    else readinessDeadlineMs,
            )) {
                CommandSpeechReadiness.WAITING -> {
                    if (!waitingForReadiness) {
                        waitingForReadiness = true
                        if (visibleResponse) presentation.showVoiceStatus("$message\n음성을 준비하고 있습니다.")
                        if (BuildConfig.DEBUG) debugDiagnostic("event=OUTPUT_READY_WAIT result=WAITING",
                        )
                    }
                    waitForCurrentReadiness()
                    return true
                }
                CommandSpeechReadiness.CANCELLED -> {
                    clearCommandSpeechReadyContinuationIfOwned(responseId, responseGeneration)
                    cancelled()
                    return false
                }
                CommandSpeechReadiness.TIMED_OUT -> {
                    clearCommandSpeechReadyContinuationIfOwned(responseId, responseGeneration)
                    responseFailureDetail = "한국어 음성 준비가 완료되지 않았습니다. 잠시 후 음성 명령을 다시 눌러 주세요."
                    if (BuildConfig.DEBUG) debugDiagnostic("event=OUTPUT_READY_WAIT result=TIMEOUT",
                    )
                    failed()
                    return false
                }
                CommandSpeechReadiness.DISPATCH -> Unit
            }
            // A native READY callback may still be waiting in the UI queue when this poll runs.
            if (actuator.readySpeechEngineGenerationOrNull() != null &&
                !processOfflineSpeechReady(actuator, lifecycleGeneration, scheduler.elapsedRealtimeMs())) {
                if (responseCurrent() && feedbackActuator === actuator) {
                    waitForCurrentReadiness()
                    return true
                }
                clearCommandSpeechReadyContinuationIfOwned(responseId, responseGeneration)
                cancelled()
                return false
            }
            // READY processing can consume this exact continuation and dispatch it reentrantly.
            if (dispatchAttempted) return dispatchResult ?: true
            clearCommandSpeechReadyContinuationIfOwned(responseId, responseGeneration)
            if (!responseCurrent() || feedbackActuator !== actuator) {
                cancelled()
                return false
            }
            if (externalFallbackSpeechOutputActive(scheduler.elapsedRealtimeMs(), reader.screenReaderActive())) {
                responseFailureDetail = "다른 안내가 끝난 뒤 음성 명령을 다시 눌러 주세요."
                failed()
                return false
            }
            // Status describes a persistent condition; let the current sentence finish. Keep
            // the original response/actor/currentness lease instead of queuing stale native TTS.
            if (statusExplanation && actuator.isAppSpeechActive()) {
                waitForCurrentReadiness()
                return true
            }
            input.cancelHomeWakeListening()
            dispatchAttempted = true
            if (BuildConfig.DEBUG) {
                debugDiagnostic("event=OUTPUT_CONTEXT lane=${if (homeResponse || statusExplanation) "HOME_STATUS" else "GENERAL"} ${actuator.statusText()}")
            }
            fun dispatchPart(index: Int): NavigationSpeechDispatchResult {
                if (!responseCurrent() || feedbackActuator !== actuator) { cancelled(); return NavigationSpeechDispatchResult.UNAVAILABLE }
                val partTerminal = AtomicBoolean(false)
                val dispatch = dispatchCommandSpeechResponse(actuator, nativeParts[index], homeResponse || statusExplanation,
                    stateSource, isCurrent = { partIndex == index && responseCurrent() && feedbackActuator === actuator },
                    completed = { scheduler.runOnMain {
                        if (partIndex == index && partTerminal.compareAndSet(false, true)) {
                            if (!responseCurrent() || feedbackActuator !== actuator) cancelled()
                            else {
                                commandSpeechTracker.terminalPart(trackedResponse, index, true)
                                partIndex++
                                if (partIndex == nativeParts.size) completed()
                                else dispatchPart(partIndex)
                            }
                        }
                    } },
                    failed = { if (partTerminal.compareAndSet(false, true)) failed() },
                    cancelled = { if (partTerminal.compareAndSet(false, true)) cancelled() },
                    deferToCurrentSpeech = statusExplanation)
                // A risk/navigation owner may arrive between the idle check and admission.
                // No native registration was accepted, so preserve this response and retry.
                if (dispatch == NavigationSpeechDispatchResult.SUPPRESSED && statusExplanation &&
                    responseCurrent() && feedbackActuator === actuator && actuator.isAppSpeechActive()) {
                    partTerminal.set(true)
                    dispatchAttempted = false
                    waitForCurrentReadiness()
                    return NavigationSpeechDispatchResult.ACCEPTED
                }
                if (dispatch != NavigationSpeechDispatchResult.ACCEPTED && partTerminal.compareAndSet(false, true)) failed()
                return dispatch
            }
            val dispatch = dispatchPart(partIndex)
            if (BuildConfig.DEBUG) {
                debugDiagnostic("event=OUTPUT_DISPATCH result=${dispatch.name}")
            }
            if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) failed()
            dispatchResult = dispatch == NavigationSpeechDispatchResult.ACCEPTED
            return dispatchResult == true
        }
        readinessPoll = Runnable { dispatchWhenReady() }
        return dispatchWhenReady()
    }

    fun dispatchCommandSpeechResponse(
        actuator: AndroidFeedbackActuator,
        message: String,
        homeOrStatusResponse: Boolean,
        stateSource: CurrentStateSpeechLease?,
        isCurrent: () -> Boolean,
        completed: () -> Unit,
        failed: () -> Unit,
        cancelled: () -> Unit,
        deferToCurrentSpeech: Boolean = false,
    ): NavigationSpeechDispatchResult = if (stateSource != null) {
        val startCurrent = { isCurrent() && reader.stateSourceIsCurrent(stateSource) }
        if (homeOrStatusResponse) actuator.speakGuardedHomeCommandInteraction(message, startCurrent,
            onFailed = failed, onCompleted = completed, onCancelled = cancelled,
            deferToCurrentSpeech = deferToCurrentSpeech)
        else actuator.speakGuardedInteraction(message, startCurrent,
            onCompleted = completed, onFailed = failed, onCancelled = cancelled,
            deferToCurrentSpeech = deferToCurrentSpeech)
    } else if (homeOrStatusResponse) {
        actuator.speakGuardedHomeCommandInteraction(message, isCurrent,
            onFailed = failed, onCompleted = completed, onCancelled = cancelled,
            deferToCurrentSpeech = deferToCurrentSpeech)
    } else {
        // General prompts retain the same owner fence as HOME/status responses at native START.
        actuator.speakGuardedInteraction(message, isCurrent,
            onCompleted = completed, onFailed = failed, onCancelled = cancelled,
            deferToCurrentSpeech = deferToCurrentSpeech)
    }

    fun announceForTalkBack(
        message: String,
        priority: SpeechAnnouncementPriority,
        onDelivered: (() -> Unit)? = null,
        riskRank: Int? = null,
        riskTrackId: String? = null,
        isStillValid: () -> Boolean = { true },
    ): Boolean {
        if (message.isBlank() || !isStillValid()) return false
        if (
            shouldSuppressFeedbackDuringVoiceRecognition(
                reader.commandContext().recognitionActive,
                isRisk = priority == SpeechAnnouncementPriority.RISK,
            )
        ) return false
        val target = presentation.accessibilityTargetOrNull() ?: return false
        val nowMs = scheduler.elapsedRealtimeMs()
        when (priority) {
            SpeechAnnouncementPriority.RISK -> {
                val rank = riskRank ?: 0
                val activeRiskHeld = nowMs < riskAnnouncementHoldUntilMs
                val preemptsActiveRisk = activeRiskHeld && rank > lastRiskAnnouncementRank
                if (activeRiskHeld && !preemptsActiveRisk) return false
                if (
                    !preemptsActiveRisk &&
                    message == lastRiskAnnouncement &&
                    nowMs - lastRiskAnnouncementMs < TALKBACK_RISK_DUP_WINDOW_MS
                ) return false
                recordAcceptedTalkBackAnnouncement(message, priority, nowMs, rank, riskTrackId)
            }
            SpeechAnnouncementPriority.INTERACTION -> {
                if (nowMs < riskAnnouncementHoldUntilMs) {
                    return scheduleTalkBackInteraction(
                        message,
                        riskAnnouncementHoldUntilMs - nowMs,
                        onDelivered,
                        isStillValid = isStillValid,
                    )
                }
                if (
                    message == lastInteractionAnnouncement &&
                    nowMs - lastInteractionAnnouncementMs < TALKBACK_INTERACTION_DUP_WINDOW_MS
                ) return false
                recordAcceptedTalkBackAnnouncement(message, priority, nowMs)
            }
            SpeechAnnouncementPriority.ADVISORY -> {
                if (nowMs < riskAnnouncementHoldUntilMs || nowMs < navigationAnnouncementHoldUntilMs) return false
                if (nowMs - lastInteractionAnnouncementMs < TALKBACK_INTERACTION_PRIORITY_WINDOW_MS) return false
                if (
                    message == lastAdvisoryAnnouncement &&
                    nowMs - lastAdvisoryAnnouncementMs < TALKBACK_ADVISORY_DUP_WINDOW_MS
                ) return false
                recordAcceptedTalkBackAnnouncement(message, priority, nowMs)
            }
            SpeechAnnouncementPriority.NAVIGATION -> {
                if (nowMs < riskAnnouncementHoldUntilMs) return false
                if (nowMs - lastInteractionAnnouncementMs < TALKBACK_INTERACTION_PRIORITY_WINDOW_MS) return false
                if (
                    message == lastNavigationAnnouncement &&
                    nowMs - lastNavigationAnnouncementMs < TALKBACK_NAVIGATION_DUP_WINDOW_MS
                ) return false
                recordAcceptedTalkBackAnnouncement(message, priority, nowMs)
            }
        }
        scheduler.runOnMain {
            if (!isStillValid() || !target.presentIfCurrent(message, priority)) return@runOnMain
            onDelivered?.invoke() // Request presentation, not a TalkBack reading-completion ACK.
        }
        return true
    }

    fun recordAcceptedTalkBackAnnouncement(
        message: String,
        priority: SpeechAnnouncementPriority,
        nowMs: Long,
        riskRank: Int = 0,
        riskTrackId: String? = null,
    ) {
        when (priority) {
            SpeechAnnouncementPriority.RISK -> {
                lastRiskAnnouncement = message
                lastRiskAnnouncementTrackId = riskTrackId.orEmpty()
                lastRiskAnnouncementMs = nowMs
                lastRiskAnnouncementRank = riskRank
                input.invalidateHomeWakeInput()
                riskAnnouncementHoldUntilMs = nowMs + utteranceTerminalTimeoutMs(message.length, SpeechMessageFamily.RISK)
            }
            SpeechAnnouncementPriority.INTERACTION -> {
                lastInteractionAnnouncement = message
                input.invalidateHomeWakeInput()
                lastInteractionAnnouncementMs = nowMs
            }
            SpeechAnnouncementPriority.ADVISORY -> {
                lastAdvisoryAnnouncement = message
                input.invalidateHomeWakeInput()
                lastAdvisoryAnnouncementMs = nowMs
            }
            SpeechAnnouncementPriority.NAVIGATION -> {
                lastNavigationAnnouncement = message
                lastNavigationAnnouncementMs = nowMs
                input.invalidateHomeWakeInput()
                navigationAnnouncementHoldUntilMs = nowMs + utteranceTerminalTimeoutMs(message.length, SpeechMessageFamily.NAVIGATION)
            }
        }
    }

    fun scheduleTalkBackInteraction(
        message: String,
        delayMs: Long,
        onDelivered: (() -> Unit)? = null,
        waitForVoiceRecognitionEnd: Boolean = false,
        isStillValid: () -> Boolean = { true },
    ): Boolean {
        if (!presentation.canScheduleInteraction() || !isStillValid()) return false
        pendingTalkBackInteraction?.let(presentation::removeInteraction)
        val delivery = Runnable {
            pendingTalkBackInteraction = null
            // Pause/teardown clears pendingTalkBackInteraction, which also ends this re-check.
            if (waitForVoiceRecognitionEnd && reader.commandContext().recognitionActive) {
                scheduleTalkBackInteraction(message, delayMs, onDelivered,
                    waitForVoiceRecognitionEnd = true, isStillValid = isStillValid)
                return@Runnable
            }
            announceForTalkBack(
                message,
                SpeechAnnouncementPriority.INTERACTION,
                onDelivered = onDelivered,
                isStillValid = isStillValid,
            )
        }
        input.invalidateHomeWakeInput()
        pendingTalkBackInteraction = delivery
        presentation.postInteraction(delivery, delayMs.coerceAtLeast(1L))
        return true
    }

    fun reconcileTalkBackRiskTracks(activeTrackIds: Set<String>) {
        if (lastRiskAnnouncementTrackId.isBlank() || lastRiskAnnouncementTrackId in activeTrackIds) return
        lastRiskAnnouncement = ""
        lastRiskAnnouncementMs = 0L
        lastRiskAnnouncementTrackId = ""
        lastRiskAnnouncementRank = -1
        riskAnnouncementHoldUntilMs = 0L
        if (activeTrackIds.isNotEmpty()) return
        val pendingInteraction = pendingTalkBackInteraction ?: return
        pendingTalkBackInteraction = null
        presentation.removeInteraction(pendingInteraction)
        pendingInteraction.run()
    }

    fun handleOfflineSpeechReady(
        expectedActuator: AndroidFeedbackActuator,
        expectedLifecycleGeneration: Int,
        nowMs: Long,
        refreshReadyUi: () -> Unit,
        retryCurrentNavigation: (Long) -> Unit,
    ): Boolean {
        val engineGeneration = expectedActuator.readySpeechEngineGenerationOrNull() ?: return false
        val owner = SpeechReadyOwner(expectedActuator, expectedLifecycleGeneration, engineGeneration)
        fun current(): Boolean = isFeedbackLifecycleCurrent(expectedLifecycleGeneration) &&
            feedbackActuator === expectedActuator &&
            expectedActuator.readySpeechEngineGenerationOrNull() == engineGeneration
        if (!current()) return false
        if (completedSpeechReadyOwner == owner) return true
        if (processingSpeechReadyOwner == owner) return false
        processingSpeechReadyOwner = owner
        try {
            refreshReadyUi()
            if (!current()) return false
            readyEffects.reevaluateCurrentRisk(expectedActuator, expectedLifecycleGeneration, nowMs)
            if (!current()) return false
            retryCurrentNavigation(nowMs)
            if (!current()) return false
            // Mark before resuming: the command's poll preflight may reenter this helper.
            completedSpeechReadyOwner = owner
            resumeCurrentCommandSpeechAfterReady(expectedActuator, expectedLifecycleGeneration)
            return true
        } finally {
            if (processingSpeechReadyOwner === owner) processingSpeechReadyOwner = null
        }
    }

    fun rememberCommandSpeechReadyContinuation(
        responseId: String,
        responseGeneration: Long,
        actuator: AndroidFeedbackActuator,
        lifecycleGeneration: Int,
        resume: () -> Boolean,
        cancelPoll: () -> Unit,
        onRetired: () -> Unit = {},
    ) {
        val previous = pendingCommandSpeechReadyContinuation
        pendingCommandSpeechReadyContinuation = CommandSpeechReadyContinuation(
            responseId, responseGeneration, actuator, lifecycleGeneration, resume, cancelPoll, onRetired,
        )
        previous?.cancelPoll?.invoke()
    }

    fun clearCommandSpeechReadyContinuationIfOwned(responseId: String, responseGeneration: Long) {
        val pending = pendingCommandSpeechReadyContinuation ?: return
        if (pending.responseId != responseId || pending.responseGeneration != responseGeneration) return
        pendingCommandSpeechReadyContinuation = null
        pending.cancelPoll()
    }

    fun clearCommandSpeechReadyContinuation() {
        val pending = pendingCommandSpeechReadyContinuation ?: return
        pendingCommandSpeechReadyContinuation = null
        pending.cancelPoll()
    }

    fun resumeCurrentCommandSpeechAfterReady(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int) {
        val pending = pendingCommandSpeechReadyContinuation ?: return
        // Detach this slot before any scheduler or consumer callback can reenter.
        pendingCommandSpeechReadyContinuation = null
        pending.cancelPoll()
        if (pending.actuator !== actuator || pending.lifecycleGeneration != lifecycleGeneration ||
            pending.responseGeneration != commandSpeechResponseGeneration ||
            feedbackActuator !== actuator || !isFeedbackLifecycleCurrent(lifecycleGeneration)) {
            pending.onRetired()
            return
        }
        // The original dispatch continuation still owns its deadline, registry and request checks.
        pending.resume()
    }

    fun externalFallbackSpeechOutputActive(nowElapsedRealtimeMs: Long, screenReaderActive: Boolean): Boolean {
        if (!screenReaderActive) return false
        val interactionHoldUntil = if (lastInteractionAnnouncement.isNotBlank())
            lastInteractionAnnouncementMs + utteranceTerminalTimeoutMs(lastInteractionAnnouncement.length, SpeechMessageFamily.INTERACTION) else 0L
        val advisoryHoldUntil = if (lastAdvisoryAnnouncement.isNotBlank())
            lastAdvisoryAnnouncementMs + utteranceTerminalTimeoutMs(lastAdvisoryAnnouncement.length) else 0L
        return pendingTalkBackInteraction != null ||
            nowElapsedRealtimeMs < maxOf(
                riskAnnouncementHoldUntilMs,
                navigationAnnouncementHoldUntilMs,
                interactionHoldUntil,
                advisoryHoldUntil,
            )
    }

    fun retireCommandSpeechResponseIfOwned(
        responseId: String,
        generation: Long,
        requireRegistered: Boolean = false,
    ): Boolean {
        if (requireRegistered && !commandSpeechResponseCallbacks.contains(responseId)) return false
        clearCommandSpeechReadyContinuationIfOwned(responseId, generation)
        commandSpeechResponseCallbacks.cancel(listOf(responseId))
        if (generation != commandSpeechResponseGeneration) return false
        pendingCommandResponseCurrent = null
        commandSpeechResponseGeneration += 1L
        input.promptState(CommandResponseToken(responseId, generation), CommandPromptEffect.CLEAR)
        return true
    }

    /** Input cleanup retires unfinished output without closing an already completed dialog. */
    fun cancelCommandSpeechResponseForInput() {
        cancelCommandSpeechResponse(cancelDialogs = hasPendingCommandResponse())
    }

    fun cancelCommandSpeechResponse(cancelDialogs: Boolean = true) {
        clearDeferredStatusExplanation()
        commandSpeechTracker.retirePending()
        clearCommandSpeechReadyContinuation()
        input.cancelDeferredInput()
        commandSpeechResponseGeneration += 1L
        readyEffects.beforeCommandReplacement(commandSpeechResponseGeneration - 1L)
        val retiredCallbacks = commandSpeechResponseCallbacks.takeRetirementCallbacks()
        commandSpeechResponseCallbacks.clear()
        pendingCommandResponseCurrent = null
        val token = CommandResponseToken("command-response-$commandSpeechResponseGeneration", commandSpeechResponseGeneration)
        if (cancelDialogs) input.cancelCommandDialogs(token)
        input.promptState(token, CommandPromptEffect.CLEAR)
        feedbackActuator?.cancelCommandInteraction()
        retiredCallbacks.forEach { callback -> scheduler.postRetired { callback() } }
    }

    private fun clearDeferredStatusExplanation() {
        val pending = deferredStatusExplanation ?: return
        deferredStatusExplanation = null
        scheduler.removeReadiness(pending.poll)
        pending.retire()
    }

    private companion object {
        const val TALKBACK_RISK_DUP_WINDOW_MS = 1_000L
        const val TALKBACK_INTERACTION_DUP_WINDOW_MS = 1_000L
        const val TALKBACK_INTERACTION_PRIORITY_WINDOW_MS = 750L
        const val TALKBACK_NAVIGATION_DUP_WINDOW_MS = 2_000L
        const val TALKBACK_ADVISORY_DUP_WINDOW_MS = 4_000L
    }
}
