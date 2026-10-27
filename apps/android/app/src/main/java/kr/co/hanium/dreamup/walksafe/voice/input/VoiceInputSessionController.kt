package kr.co.hanium.dreamup.walksafe.voice.input

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.output.CommandPromptEffect

/** Owns one-shot recognition and input arbitration; hands-free resources have one runtime owner. */
internal class VoiceInputSessionController(
    private val environment: VoiceInputEnvironment,
    private val commands: VoiceInputCommandEffects,
    private val presentation: VoiceInputPresentation,
    private val applicationEffects: VoiceInputApplicationEffects,
    private val output: VoiceInputOutput,
    private val scheduler: VoiceInputScheduler,
    private val platform: VoiceInputPlatform,
    wakePlatform: ForegroundWakePlatform,
    gatewayPlatform: GatewayVoicePlatform,
    gatewayEnvironment: GatewayVoiceEnvironment,
    private val diagnostics: VoiceInputDiagnostics = AndroidVoiceInputDiagnostics,
) {
    private var speechRecognizer: VoiceCommandRecognizer? = null
    private var voiceRecognitionActive = false
    private var voiceRecognitionGeneration = 0
    private data class RecognitionSource(
        val generation: Int,
        val identity: VoiceInputIdentity,
        val lifecycleGeneration: Int,
    )
    @Volatile private var recognitionSource: RecognitionSource? = null
    private var voiceCommandPromptPending = false
    private var voiceCommandPromptReadyGeneration: Int? = null
    private var deferredVoiceRecognitionStart: Runnable? = null
    private var deferredVoiceRecognitionFailure: (() -> Unit)? = null
    private var voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
    private var handsFreeNavigationDecisionCapture: HandsFreeNavigationDecisionCapture? = null
    private var handsFreeCommandWork: HandsFreeCommandWork? = null
    private var handsFreeVoiceOwnsCommandPage = false
    private var oneShotSpeechRecognitionLimited = false

    private data class HandsFreeNavigationDecisionCapture(
        val actorId: String?,
        val sessionGeneration: Long,
        val walkEpoch: WalkRuntimeEpoch,
        val decisionToken: RouteNavigatorDecisionToken?,
    )

    /** S04-11: the search/route request one hands-free command started, bound to that command's id. */
    private data class HandsFreeCommandWork(
        val commandId: Long,
        val searchGeneration: Int?,
        val routeGeneration: Int?,
        val locationGeneration: Long?,
        val searchBeforeLocation: Int,
    )
    private val handsFreeRuntime: HandsFreeRuntimeSession = HandsFreeRuntimeSession(
        environment, object : HandsFreeRuntimeInput {
            override fun refreshWakeAndHasProbe(): Boolean {
                wake.refreshForegroundHomeWakeListening()
                return wake.hasProbe()
            }
            override fun otherInputBusy() = voiceRecognitionActive || voiceCommandPromptPending ||
                deferredVoiceRecognitionStart != null || gateway.isRecording() || gateway.hasInteraction()
            override fun outputActive() = isHandsFreeVoiceOutputActive()
            override fun recognitionBlocked() = isHandsFreeVoiceRecognitionBlocked()
            override fun modelPrepared() { oneShotSpeechRecognitionLimited = false }
            override fun recognizedCommand(text: String, confidence: Float?) = handleHandsFreeCommand(text, confidence)
            override fun commandListeningStarted() = handleHandsFreeVoiceCommandListeningStarted()
            override fun clearCommandCapture() { handsFreeNavigationDecisionCapture = null }
            override fun commandWorkPending(commandId: Long) = isHandsFreeCommandWorkPending(commandId)
            override fun commandSpeechRevision(commandId: Long) = output.commandSpeechRevision(commandId)
            override fun commandSpeechProgress(commandId: Long) = output.commandSpeechProgress(commandId)
            override fun lowConfidenceReprompt(onFinished: (WakeAcknowledgementResult) -> Unit) =
                speakHandsFreeLowConfidenceReprompt(onFinished)
            override fun runtimeRetired() {
                handsFreeVoiceOwnsCommandPage = false
                wake.closeAcknowledgement()
            }
        }, presentation, applicationEffects, output, scheduler, platform, diagnostics,
    )

    private fun handleHandsFreeCommand(text: String, confidence: Float?) {
        val capture = handsFreeNavigationDecisionCapture ?: return
        handsFreeNavigationDecisionCapture = null
        if (!environment.runtimeActive() ||
            capture.actorId != environment.identity().actorId ||
            capture.sessionGeneration != environment.identity().processGeneration ||
            capture.walkEpoch != requireNotNull(environment.walkOrNull()).epoch
        ) return
        handsFreeRuntime.dispatchingCommandId()?.let(output::beginCommandSpeech)
        val workBefore = handsFreeCommandWorkGenerations()
        commands.recognized(RecognizedVoiceInput(listOf(text), listOf(confidence ?: Float.NaN),
            capture.decisionToken, false, "on_device_hands_free"))
        bindHandsFreeCommandWork(workBefore)
    }

    val state: VoiceInputState get() = VoiceInputState(
        voiceRecognitionActive, voiceRecognitionGeneration, voiceRecognitionPurpose,
        voiceCommandPromptPending, voiceCommandPromptReadyGeneration,
        deferredVoiceRecognitionStart != null, handsFreeRuntime.modelDirectory,
        handsFreeRuntime.modelPreparing, handsFreeRuntime.modelPreparationFailed,
        handsFreeRuntime.destroyed, oneShotSpeechRecognitionLimited, handsFreeVoiceOwnsCommandPage,
    )

    private val wake: ForegroundWakeInputController = ForegroundWakeInputController(environment, object : ForegroundWakeSession {
        override fun state() = WakeInputState(handsFreeRuntime.destroyed, voiceRecognitionActive,
            voiceRecognitionGeneration, voiceCommandPromptPending, deferredVoiceRecognitionStart != null,
            handsFreeRuntime.modelPreparationFailed, gateway.isRecording(), gateway.hasInteraction())
        override fun homeCommandAvailable() = homeVoiceCommandAvailable()
        override fun speechActive() = isHandsFreeVoiceSpeechOutputActive()
        override fun recognitionBlocked() = isHandsFreeVoiceRecognitionBlocked()
        override fun modelDirectoryOrNull() = foregroundVoiceModelDirectoryOrNull()
        override fun stopHandsFree() = stopHandsFreeVoiceService()
        override fun scheduleRestart() = scheduleHandsFreeVoiceRestart()
        override fun cueCompletedForCurrentRecognition() = markPromptReadyAfterCue()
        override fun startCommandRecognition() = startVoiceCommandRecognition()
        override fun recognized(result: RecognizedVoiceInput) = commands.recognized(result)
    }, presentation, wakePlatform, diagnostics)

    val gateway: GatewayVoiceCaptureController = GatewayVoiceCaptureController(environment, gatewayEnvironment, object : GatewayVoiceInputSession {
        override fun inputFailed(failure: VoiceInputFailure) { reportWalkVoiceStartFailure(WalkVoiceStartFailure.INPUT_SILENCED) }
        override fun recognitionActive() = voiceRecognitionActive
        override fun recognitionBlocked() = isHandsFreeVoiceRecognitionBlocked()
        override fun stopHandsFree() = stopHandsFreeVoiceService()
        override fun setRecordingInputActive(active: Boolean) {
            voiceRecognitionActive = active
            if (active) voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
        }
        override fun invalidateHomeWakeInput() = wake.invalidateForegroundHomeWakeInput()
        override fun scheduleRestart() = scheduleHandsFreeVoiceRestart()
        override fun recognized(result: RecognizedVoiceInput) = commands.recognized(result)
    }, output, presentation, gatewayPlatform, scheduler)

    /** Process events retire only their captured local input owner; newer requests keep their lease. */
    fun onProcessSessionChanged(snapshot: kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot) {
        gateway.onProcessSessionChanged(snapshot)
        val source = recognitionSource ?: return
        if (snapshot.generation != environment.identity().processGeneration) return
        if (source.identity.processGeneration == snapshot.generation &&
            source.identity.actorId == snapshot.session?.actorId) return
        scheduler.runOnMain {
            if (recognitionSource === source && voiceRecognitionGeneration == source.generation) {
                cancelVoiceCommandRecognition()
            }
        }
    }

    private fun recognitionSourceIsCurrent(source: RecognitionSource): Boolean =
        voiceRecognitionGeneration == source.generation &&
            environment.identity() == source.identity &&
            environment.feedbackLifetimeGeneration() == source.lifecycleGeneration &&
            environment.foreground() && environment.microphoneGranted()

    private fun retireRecognitionSourceIfOwned(source: RecognitionSource, recognizer: VoiceCommandRecognizer? = speechRecognizer) {
        if (recognitionSource !== source || voiceRecognitionGeneration != source.generation) return
        recognitionSource = null
        voiceRecognitionGeneration += 1
        voiceRecognitionActive = false
        voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
        recognizer?.cancel()
        presentation.refreshControls(active = false)
    }

    fun clearTranscriberFailureHistory() = handsFreeRuntime.clearTranscriberFailureHistory()
    fun commandInputActive() = handsFreeRuntime.commandInputActive()
    fun releaseCommandPageOwnership() { handsFreeVoiceOwnsCommandPage = false }
    fun applyCommandPromptEffect(effect: CommandPromptEffect) {
        when (effect) {
            CommandPromptEffect.PREPARING -> voiceCommandPromptPending = true
            CommandPromptEffect.ORDINARY_RESPONSE, CommandPromptEffect.CLEAR -> {
                voiceCommandPromptPending = false
                voiceCommandPromptReadyGeneration = null
            }
        }
    }
    /** Invoked only by the existing dialog owner after its completion lease passes. */
    fun completePreparedPrompt(readyGeneration: Int?) {
        voiceCommandPromptPending = false
        voiceCommandPromptReadyGeneration = readyGeneration
    }
    fun clearPromptReady() { voiceCommandPromptReadyGeneration = null }
    fun markPromptReadyAfterCue() { voiceCommandPromptReadyGeneration = voiceRecognitionGeneration }
    fun cancelForegroundHomeWakeListening(resetFailure: Boolean = false) = wake.cancelForegroundHomeWakeListening(resetFailure)
    fun cancelForegroundHomeWakeAcknowledgement() = wake.cancelForegroundHomeWakeAcknowledgement()
    fun invalidateForegroundHomeWakeInput() = wake.invalidateForegroundHomeWakeInput()
    fun refreshForegroundHomeWakeListening() = wake.refreshForegroundHomeWakeListening()
    fun onBeforeForegroundReturn() = handsFreeRuntime.onBeforeForegroundReturn()
    fun onStopped() = handsFreeRuntime.onStopped()
    fun destroyRecognizer() { speechRecognizer?.destroy(); speechRecognizer = null }
    fun isHandsFreeVoiceOutputActive(): Boolean {
        if (wake.acknowledgementPlaying()) return true
        return isHandsFreeVoiceSpeechOutputActive()
    }
    fun isHandsFreeVoiceSpeechOutputActive(): Boolean =
        output.commandResponsePending() || gateway.hasPlayer() || output.nativeOrAccessibilitySpeechActive()
    fun isHandsFreeVoiceRecognitionBlocked(): Boolean =
        isHandsFreeVoiceOutputActive() || output.nativeRecognitionBlocked()


    fun homeVoiceCommandAvailable(): Boolean =
        OneShotVoiceInputPolicy.isAvailable(
            contextAvailable = environment.homeFeatureContextAvailable(),
            microphoneGranted = environment.microphoneGranted(),
            capabilityDecision = environment.capabilityDecision(),
        )

    fun voiceResumeConfirmationAvailable(): Boolean {
        val decision = environment.capabilityDecision() ?: return false
        return environment.microphoneGranted() &&
            handsFreeRuntime.modelDirectory != null && !handsFreeRuntime.destroyed &&
            !oneShotSpeechRecognitionLimited &&
            environment.voiceGuidanceFeatureEnabled() &&
            environment.capabilityAllows(decision, WalkSafeStartupRequirement.MICROPHONE) &&
            environment.capabilityAllows(decision, WalkSafeStartupRequirement.ON_DEVICE_STT) &&
            environment.capabilityAllows(decision, WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)
    }

    fun prepareOneShotVoiceCommandPrompt(): Boolean {
        val preparationGeneration = voiceRecognitionGeneration
        stopHandsFreeVoiceService()
        return output.speakPreparingInput(
            message = "안내가 끝나면 말씀해 주세요.",
            onCompleted = {
                if (voiceCommandPromptPending && voiceRecognitionGeneration == preparationGeneration) {
                    voiceCommandPromptPending = false
                    voiceCommandPromptReadyGeneration = preparationGeneration
                    // Exactly the explicit user's pending request continues after real TTS onDone.
                    if (!startVoiceCommandRecognition()) presentation.refreshControls(active = false)
                }
            },
            onFailed = {
                if (voiceRecognitionGeneration == preparationGeneration) {
                    voiceCommandPromptPending = false
                    voiceCommandPromptReadyGeneration = null
                    presentation.refreshControls(active = voiceRecognitionActive)
                }
            },
        )
    }

    /** Returns only a directory already verified by the model worker; never scans its contents. */
    fun foregroundVoiceModelDirectoryOrNull(
        requestPreparation: () -> Unit = ::prepareHandsFreeVoiceModel,
    ): File? = handsFreeRuntime.foregroundVoiceModelDirectoryOrNull(requestPreparation)

    fun prepareHandsFreeVoiceModel() = handsFreeRuntime.prepareHandsFreeVoiceModel()

    fun attachHandsFreeVoiceController(modelDirectory: File) = handsFreeRuntime.attachHandsFreeVoiceController(modelDirectory)

    /** S04-30: the notification's "음성 입력 중지", delivered before the service stops the controller. */
    fun handleHandsFreeVoiceUserStop(stopped: WalkVoiceSessionController) = handsFreeRuntime.handleHandsFreeVoiceUserStop(stopped)

    fun handsFreeCommandWorkGenerations(): VoiceInputCommandWork = environment.commandWork()

    fun bindHandsFreeCommandWork(before: VoiceInputCommandWork) {
        val commandId = handsFreeRuntime.dispatchingCommandId()
        val current = environment.commandWork()
        val search = current.searchGeneration.takeIf { current.searchInFlight && it != before.searchGeneration }
        val route = current.routeGeneration.takeIf { current.routeInFlight && it != before.routeGeneration }
        val location = current.locationGeneration.takeIf { current.locationInFlight && it != before.locationGeneration }
        handsFreeCommandWork = if (commandId != null && (search != null || route != null || location != null)) {
            HandsFreeCommandWork(commandId, search, route, location, before.searchGeneration)
        } else null
    }

    // The same logical GPS consumer may transfer its work to the HTTP search after its fix arrives.
    fun isHandsFreeCommandWorkPending(commandId: Long): Boolean {
        var work = handsFreeCommandWork?.takeIf { it.commandId == commandId } ?: return false
        val current = environment.commandWork()
        val ownedLocation = work.locationGeneration != null && current.locationGeneration == work.locationGeneration
        if (ownedLocation && !current.locationInFlight && work.searchGeneration == null &&
            current.searchInFlight && current.searchGeneration != work.searchBeforeLocation) {
            work = work.copy(searchGeneration = current.searchGeneration)
            handsFreeCommandWork = work
        }
        val pending = (ownedLocation && current.locationInFlight) ||
            (work.searchGeneration != null && current.searchInFlight && current.searchGeneration == work.searchGeneration) ||
            (work.routeGeneration != null && current.routeInFlight && current.routeGeneration == work.routeGeneration)
        if (!pending) handsFreeCommandWork = null
        return pending
    }

    fun handleHandsFreeVoiceCommandListeningStarted() {
        handsFreeRuntime.clearTranscriberFailureHistory()
        handsFreeNavigationDecisionCapture = if (environment.runtimeActive()) {
            HandsFreeNavigationDecisionCapture(
                actorId = environment.identity().actorId,
                sessionGeneration = environment.identity().processGeneration,
                walkEpoch = requireNotNull(environment.walkOrNull()).epoch,
                decisionToken = environment.pendingNavigationDecisionToken(),
            )
        } else null
        if (environment.serverCommandRecognitionEnabled()) {
            handsFreeVoiceOwnsCommandPage = false
            stopHandsFreeVoiceService()
            startVoiceCommandRecognition()
            return
        }
        if (environment.runtimeActive()) {
            handsFreeVoiceOwnsCommandPage = true
            // S05-33: waking the assistant does not move the walking guidance page either; only a
            // command whose result is chosen on another screen moves the page (executeVoiceAction).
            if (!environment.page().destinationSearch && !environment.page().guidance) {
                presentation.showCommandPage()
            }
        }
        runCatching { output.playListeningStarted() }
        presentation.voiceStatus("호출어를 인식했습니다. 음성 명령을 듣고 있습니다.")
        presentation.navigationStatus("voice_hands_free=listening_command")
    }

    /**
     * R18 (S04-19, FP-026 "낮은 신뢰도는 no-op 후 재질문"): a hands-free command rejected for low
     * confidence is asked for once again aloud; the controller reopens the command window when this
     * re-ask has ended (and counts a second doubtful command in a row as the end of the exchange).
     */
    fun speakHandsFreeLowConfidenceReprompt(
        onFinished: (kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult) -> Unit,
    ) {
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { result: kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult ->
            scheduler.runOnMain { if (delivered.compareAndSet(false, true)) onFinished(result) }
        }
        presentation.navigationStatus("voice_hands_free=reprompt_low_confidence")
        presentation.lastResult(kr.co.hanium.dreamup.walksafe.voice.HANDS_FREE_LOW_CONFIDENCE_REPROMPT_KO)
        val accepted = runCatching {
            output.speakLowConfidenceReprompt(
                message = kr.co.hanium.dreamup.walksafe.voice.HANDS_FREE_LOW_CONFIDENCE_REPROMPT_KO,
                onCompleted = { finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.COMPLETED) },
                onFailed = { finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.FAILED) },
            )
        }.getOrDefault(false)
        if (!accepted) finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.FAILED)
    }

    fun handleHandsFreeVoiceSessionEnded() = handsFreeRuntime.handleHandsFreeVoiceSessionEnded()

    fun handleHandsFreeVoiceTerminalFailure(failure: HandsFreeVoiceTerminalFailure) = handsFreeRuntime.handleHandsFreeVoiceTerminalFailure(failure)

    fun maybeStartHandsFreeVoiceService() = handsFreeRuntime.maybeStartHandsFreeVoiceService()

    fun handleWalkVoiceStartFailure(
        failedId: Long,
        failedController: WalkVoiceSessionController,
        failure: WalkVoiceStartFailure,
    ) = handsFreeRuntime.handleWalkVoiceStartFailure(failedId, failedController, failure)

    fun reportWalkVoiceStartFailure(failure: WalkVoiceStartFailure) = handsFreeRuntime.reportWalkVoiceStartFailure(failure)

    fun showPendingHandsFreeVoiceStartFailure() = handsFreeRuntime.showPendingHandsFreeVoiceStartFailure()

    fun isCurrentHandsFreeVoiceController(controller: HandsFreeVoiceController?): Boolean =
        handsFreeRuntime.isCurrentHandsFreeVoiceController(controller)

    fun clearHandsFreeVoiceStartRequest(onSessionEnded: Boolean = false) = handsFreeRuntime.clearHandsFreeVoiceStartRequest(onSessionEnded)

    fun scheduleHandsFreeVoiceRestart() = handsFreeRuntime.scheduleHandsFreeVoiceRestart()

    fun stopHandsFreeVoiceService(): Boolean = handsFreeRuntime.stopHandsFreeVoiceService()

    fun closeHandsFreeVoice() = handsFreeRuntime.closeHandsFreeVoice()

    fun handleOneShotSpeechRecognitionFailure(
        detail: String,
        permanentlyLimit: Boolean,
    ) {
        if (permanentlyLimit) oneShotSpeechRecognitionLimited = true
        voiceRecognitionGeneration += 1
        voiceRecognitionActive = false
        voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
        commands.clearWalkEndConfirmation()
        speechRecognizer?.destroy()
        speechRecognizer = null
        presentation.voiceStatus(
            if (permanentlyLimit) {
                "기기 내 화면 음성 명령을 사용할 수 없습니다."
            } else {
                "기기 내 화면 음성 듣기가 종료됐습니다. 다시 시도할 수 있습니다."
            },
        )
        presentation.refreshControls(active = false)
        scheduleHandsFreeVoiceRestart()
        output.speakInteraction(
            if (permanentlyLimit) detail else "$detail 음성 명령을 다시 눌러 주세요.",
        )
        if (permanentlyLimit) {
            presentation.status(
                "화면 음성 명령 기능 제한",
                "$detail 기기 내 호출어와 보행의 다른 기능은 계속 사용할 수 있습니다.",
            )
        } else {
            presentation.status(
                "화면 음성 명령 종료",
                "$detail 다시 시도할 수 있으며 호출어와 보행의 다른 기능은 계속 사용할 수 있습니다.",
            )
        }
    }

    fun ensureVoicePermissionThenListen() {
        val snapshot = requireNotNull(environment.walkOrNull())
        if (
            !snapshot.isForeground ||
            (snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED) &&
                !(environment.page().voiceInteraction && homeVoiceCommandAvailable()) && !environment.walkAreaCommandAvailable())
        ) {
            presentation.voiceStatus("화면 음성 명령은 초기 설정과 기기 점검을 완료한 뒤 앱 화면에서 사용할 수 있습니다.")
            return
        }
        if (!environment.microphoneGranted()) {
            presentation.navigationStatus("voice_command=microphone_permission_missing feature_limited")
            applicationEffects.showMicrophoneDenial()
            presentation.refreshControls(active = false)
            return
        }
        if (commands.handleDestinationPromptIfPresent()) return
        startVoiceCommandRecognition()
    }

    fun startVoiceCommandRecognition(
        purpose: VoiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND,
        expectedGatewayWalkOperationId: String? = null,
    ): Boolean {
        handsFreeVoiceOwnsCommandPage = false
        wake.cancelForegroundHomeWakeAcknowledgement()
        wake.cancelForegroundHomeWakeListening()
        val promptCompletedForThisRequest = voiceCommandPromptReadyGeneration == voiceRecognitionGeneration
        if (!environment.microphoneGranted()) {
            presentation.navigationStatus("voice_command=microphone_permission_missing")
            return false
        }
        if (
            !OneShotVoiceInputPolicy.isAvailable(
                contextAvailable = true,
                microphoneGranted = environment.microphoneGranted(),
                capabilityDecision = environment.capabilityDecision(),
            )
        ) {
            presentation.navigationStatus("voice_command=microphone_limited")
            return false
        }
        val sessionSnapshot = requireNotNull(environment.walkOrNull())
        val expectedWalkEpoch = sessionSnapshot.epoch
        val expectedResumeToken = if (purpose == VoiceRecognitionPurpose.WALK_SESSION_RESUME) {
            environment.confirmation().resumeToken
        } else {
            null
        }
        val expectedNavigationDecisionToken = if (purpose == VoiceRecognitionPurpose.COMMAND) {
            environment.pendingNavigationDecisionToken()
        } else {
            null
        }
        val mayListen = when (purpose) {
            VoiceRecognitionPurpose.COMMAND -> {
                sessionSnapshot.isForeground &&
                    sessionSnapshot.epoch == expectedWalkEpoch &&
                    when (sessionSnapshot.state) {
                        WalkSessionState.ACTIVE -> applicationEffects.requireStartupCapabilityConfirmation()
                        WalkSessionState.PAUSED -> voiceResumeConfirmationAvailable()
                        WalkSessionState.READY, WalkSessionState.SAFE_STOP, WalkSessionState.ENDED ->
                            (environment.page().voiceInteraction && homeVoiceCommandAvailable()) ||
                                environment.walkAreaCommandAvailable()
                        else -> false
                    }
            }
            VoiceRecognitionPurpose.WALK_SESSION_RESUME -> {
                sessionSnapshot.state == WalkSessionState.PAUSED &&
                    sessionSnapshot.recoveryStage ==
                    WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION &&
                    sessionSnapshot.isForeground &&
                    environment.confirmation().resumePromptPending &&
                    expectedResumeToken != null &&
                    sessionSnapshot.confirmationToken == expectedResumeToken &&
                    environment.startupCapabilityConfirmed()
            }
            VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER -> {
                sessionSnapshot.state == WalkSessionState.READY &&
                    sessionSnapshot.isForeground &&
                    environment.confirmation().takeoverPromptPending &&
                    expectedGatewayWalkOperationId != null &&
                    environment.confirmation().takeoverOperationId ==
                    expectedGatewayWalkOperationId &&
                    environment.confirmation().takeoverStartToken ==
                    sessionSnapshot.confirmationToken &&
                    environment.takeoverConflictAvailable(expectedWalkEpoch)
            }
        }
        if (!mayListen) return false
        if (voiceRecognitionActive || voiceCommandPromptPending) return false
        if (isHandsFreeVoiceRecognitionBlocked() ||
            !output.canSpeakNavigation(scheduler.nowMs()) ||
            output.prepareForRecognition() == false
        ) {
            return deferVoiceRecognitionUntilOutputIdle(purpose, expectedGatewayWalkOperationId)
        }
        clearDeferredVoiceRecognitionStart(notifyFailure = false)
        voiceCommandPromptReadyGeneration = null
        if (environment.serverCommandRecognitionEnabled() && purpose == VoiceRecognitionPurpose.COMMAND) {
            if (!promptCompletedForThisRequest) return prepareOneShotVoiceCommandPrompt()
            val started = gateway.startGatewayVoiceCapture()
            if (!started) output.speakInteraction("서버 음성 입력을 시작할 수 없습니다. 로그인과 데이터 사용 설정을 확인해 주세요.")
            return started
        }
        fun unavailable(detail: String, permanentlyLimit: Boolean): Boolean {
            presentation.navigationStatus("voice=recognizer_unavailable")
            handleOneShotSpeechRecognitionFailure(detail, permanentlyLimit)
            return false
        }
        val preferPlatform = purpose == VoiceRecognitionPurpose.COMMAND &&
            environment.homeFeatureContextAvailable()
        if (handsFreeRuntime.modelDirectory == null) prepareHandsFreeVoiceModel()
        if (handsFreeRuntime.modelDirectory == null && !preferPlatform) {
            presentation.voiceStatus(
                if (handsFreeRuntime.modelPreparationFailed) "음성 모델을 준비하지 못했습니다. 다시 눌러 재시도하세요."
                else "음성 모델을 준비하고 있습니다. 준비가 끝나면 음성 명령을 다시 눌러 주세요.",
            )
            presentation.refreshControls(active = false)
            output.speakInteraction(
                if (handsFreeRuntime.modelPreparationFailed) "음성 모델 준비에 실패했습니다. 다시 시도해 주세요."
                else "음성 모델을 준비하고 있습니다. 잠시 뒤 다시 눌러 주세요.",
            )
            return false
        }
        oneShotSpeechRecognitionLimited = false
        if (purpose == VoiceRecognitionPurpose.COMMAND && !promptCompletedForThisRequest) {
            return prepareOneShotVoiceCommandPrompt()
        }
        stopHandsFreeVoiceService()
        val generation = ++voiceRecognitionGeneration
        val source = RecognitionSource(generation, environment.identity(), environment.feedbackLifetimeGeneration())
        recognitionSource = source
        val recognizer = speechRecognizer ?: runCatching {
            platform.createRecognizer { handsFreeRuntime.modelDirectory }
        }.getOrElse {
            if (!recognitionSourceIsCurrent(source)) {
                retireRecognitionSourceIfOwned(source)
                return false
            }
            presentation.navigationStatus("voice=on_device_recognizer_creation_failed")
            return unavailable(
                "기기 내 음성 인식기를 만들 수 없습니다.",
                permanentlyLimit = false,
            )
        }.also {
            speechRecognizer = it
        }
        if (!recognitionSourceIsCurrent(source)) {
            retireRecognitionSourceIfOwned(source, recognizer)
            return false
        }
        voiceRecognitionPurpose = purpose
        recognizer.setRecognitionListener(
            buildVoiceCommandRecognitionListener(
                generation = generation,
                purpose = purpose,
                expectedWalkEpoch = expectedWalkEpoch,
                expectedResumeToken = expectedResumeToken,
                expectedGatewayWalkOperationId = expectedGatewayWalkOperationId,
                expectedNavigationDecisionToken = expectedNavigationDecisionToken,
            ),
        )
        wake.invalidateForegroundHomeWakeInput()
        voiceRecognitionActive = true
        presentation.refreshControls(active = true)
        presentation.navigationStatus(
            when (purpose) {
                VoiceRecognitionPurpose.COMMAND -> "voice=listening command"
                VoiceRecognitionPurpose.WALK_SESSION_RESUME ->
                    "voice=listening walk_session_resume"
                VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER ->
                    "voice=listening walk_session_takeover"
            },
        )
        val intent = platform.commandIntent()
        return runCatching {
            recognizer.startListening(
                intent,
                preferPlatform = preferPlatform,
                isRequestCurrent = {
                    recognitionSourceIsCurrent(source) && isVoiceRecognitionLeaseCurrent(
                        generation,
                        purpose,
                        expectedWalkEpoch,
                        expectedResumeToken,
                        expectedGatewayWalkOperationId,
                    )
                },
            )
            if (recognitionSource === source && voiceRecognitionGeneration == generation &&
                !recognitionSourceIsCurrent(source)) {
                retireRecognitionSourceIfOwned(source, recognizer)
                false
            } else true
        }.getOrElse {
            if (!recognitionSourceIsCurrent(source)) {
                retireRecognitionSourceIfOwned(source, recognizer)
                return false
            }
            unavailable(
                "기기 내 음성 인식을 시작할 수 없습니다.",
                permanentlyLimit = false,
            )
        }
    }

    fun cancelDeferredVoiceRecognitionStart() {
        clearDeferredVoiceRecognitionStart(notifyFailure = true)
    }

    fun clearDeferredVoiceRecognitionStart(notifyFailure: Boolean) {
        val pending = deferredVoiceRecognitionStart ?: return
        val failure = deferredVoiceRecognitionFailure
        deferredVoiceRecognitionStart = null
        deferredVoiceRecognitionFailure = null
        scheduler.removeDeferred(pending)
        if (notifyFailure) failure?.invoke()
        presentation.refreshControls(active = voiceRecognitionActive)
    }

    fun deferVoiceRecognitionUntilOutputIdle(
        purpose: VoiceRecognitionPurpose,
        expectedGatewayWalkOperationId: String?,
    ): Boolean {
        if (deferredVoiceRecognitionStart != null) return true
        stopHandsFreeVoiceService()
        val generation = voiceRecognitionGeneration
        val responseGeneration = output.commandGeneration()
        val lifecycleGeneration = environment.feedbackLifetimeGeneration()
        val epoch = requireNotNull(environment.walkOrNull()).epoch
        val actor = environment.identity().actorId
        val sessionGeneration = environment.identity().processGeneration
        val page = environment.page().owner
        val routeGeneration = environment.commandWork().routeGeneration
        val deadline = scheduler.nowMs() + DEFERRED_VOICE_INPUT_MAX_WAIT_MS
        val resumeToken = environment.confirmation().resumeToken
        val inputNotStarted: () -> Unit = {
            if (environment.identity().actorId == actor &&
                environment.identity().processGeneration == sessionGeneration &&
                requireNotNull(environment.walkOrNull()).epoch == epoch
            ) {
                // Output/input generations fence execution; confirmation tokens own cleanup.
                // A newer status message must not leave the old confirmation permanently pending.
                if (voiceRecognitionGeneration == generation &&
                    output.commandGeneration() == responseGeneration
                ) voiceCommandPromptReadyGeneration = null
                when (purpose) {
                    VoiceRecognitionPurpose.COMMAND -> {
                        if (voiceRecognitionGeneration == generation &&
                            output.commandGeneration() == responseGeneration
                        ) {
                            commands.clearWalkEndConfirmation()
                            commands.invalidateGuidanceStopConfirmation()
                        }
                    }
                    VoiceRecognitionPurpose.WALK_SESSION_RESUME -> {
                        if (resumeToken != null && environment.confirmation().resumeToken == resumeToken) {
                            commands.resumeRecognizerNotStarted()
                        }
                    }
                    VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER -> {
                        if (expectedGatewayWalkOperationId != null &&
                            environment.confirmation().takeoverOperationId == expectedGatewayWalkOperationId
                        ) {
                            commands.cancelTakeover(epoch, "takeover_voice_not_started")
                        }
                    }
                }
            }
        }
        presentation.voiceStatus("현재 음성 안내가 끝나면 명령을 듣겠습니다.")
        presentation.navigationStatus("voice=input_waiting_for_output")
        lateinit var pending: Runnable
        pending = Runnable {
            if (deferredVoiceRecognitionStart !== pending) return@Runnable
            val current = voiceRecognitionGeneration == generation &&
                output.commandGeneration() == responseGeneration &&
                (environment.foreground() && environment.feedbackLifetimeGeneration() == lifecycleGeneration) && environment.page().owner == page &&
                environment.identity().actorId == actor && environment.commandWork().routeGeneration == routeGeneration &&
                environment.identity().processGeneration == sessionGeneration &&
                requireNotNull(environment.walkOrNull()).epoch == epoch &&
                !voiceRecognitionActive && !voiceCommandPromptPending
            if (!current) {
                cancelDeferredVoiceRecognitionStart()
                scheduleHandsFreeVoiceRestart()
                return@Runnable
            }
            if (scheduler.nowMs() >= deadline) {
                cancelDeferredVoiceRecognitionStart()
                presentation.voiceStatus("음성 안내가 끝난 뒤 음성 명령을 다시 눌러 주세요.")
                presentation.navigationStatus("voice=input_wait_expired")
                scheduleHandsFreeVoiceRestart()
                return@Runnable
            }
            if (isHandsFreeVoiceRecognitionBlocked() ||
                !output.canSpeakNavigation(scheduler.nowMs())
            ) {
                scheduler.postDeferred(pending, DEFERRED_VOICE_INPUT_POLL_MS)
                return@Runnable
            }
            deferredVoiceRecognitionStart = null
            deferredVoiceRecognitionFailure = null
            if (!startVoiceCommandRecognition(purpose, expectedGatewayWalkOperationId)) {
                inputNotStarted()
                presentation.refreshControls(active = false)
                scheduleHandsFreeVoiceRestart()
            }
        }
        deferredVoiceRecognitionStart = pending
        deferredVoiceRecognitionFailure = inputNotStarted
        presentation.refreshControls(active = false)
        scheduler.postDeferred(pending, DEFERRED_VOICE_INPUT_POLL_MS)
        return true
    }

    fun cancelVoiceCommandRecognition() {
        recognitionSource = null
        commands.invalidateGuidanceStopConfirmation()
        wake.cancelForegroundHomeWakeAcknowledgement()
        gateway.cancelGatewaySpeechInteraction("voice_cancelled")
        voiceRecognitionGeneration += 1
        output.cancelCommandResponse()
        voiceRecognitionActive = false
        voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
        commands.clearWalkEndConfirmation()
        speechRecognizer?.cancel()
        presentation.refreshControls(active = false)
        scheduleHandsFreeVoiceRestart()
    }

    fun buildVoiceCommandRecognitionListener(
        generation: Int,
        purpose: VoiceRecognitionPurpose,
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedResumeToken: WalkSessionConfirmationToken?,
        expectedGatewayWalkOperationId: String?,
        expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
    ): RecognitionListener {
        val source = recognitionSource?.takeIf { it.generation == generation }
            ?: RecognitionSource(generation, environment.identity(), environment.feedbackLifetimeGeneration())
        fun currentLease(): Boolean = recognitionSourceIsCurrent(source) &&
            isVoiceRecognitionLeaseCurrent(generation, purpose, expectedWalkEpoch,
                expectedResumeToken, expectedGatewayWalkOperationId)
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (
                    currentLease()
                ) {
                    diagnostics.input(VoiceInputDiagnosticEvent.ONE_SHOT_UI_READY)
                    output.playListeningStarted()
                    presentation.recognitionSignal("말씀하세요")
                }
            }

            override fun onBeginningOfSpeech() {
                if (
                    currentLease()
                ) presentation.recognitionSignal("말씀하세요")
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (
                    currentLease()
                ) presentation.recognitionSignal("음성 처리 중")
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onError(error: Int) {
                if (
                    !currentLease()
                ) return
                voiceRecognitionGeneration += 1
                voiceRecognitionActive = false
                voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
                presentation.refreshControls(active = false)
                presentation.navigationStatus("voice=recognition_failed code=$error")
                diagnostics.input(VoiceInputDiagnosticEvent.ONE_SHOT_UI_ERROR, errorCode = error)
                if (
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    if (purpose == VoiceRecognitionPurpose.COMMAND) {
                        commands.clearWalkEndConfirmation()
                    }
                    when (purpose) {
                        VoiceRecognitionPurpose.WALK_SESSION_RESUME ->
                            commands.resumeRecognized(emptyList())
                        VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER ->
                            commands.takeoverRecognized(emptyList())
                        VoiceRecognitionPurpose.COMMAND -> {
                            commands.commandInputMiss(error)
                        }
                    }
                    scheduleHandsFreeVoiceRestart()
                    return
                }
                speechRecognizer?.destroy()
                speechRecognizer = null
                handleOneShotSpeechRecognitionFailure(
                    when (error) {
                        SPEECH_ERROR_INPUT_SILENCED -> VoiceInputFailure.SILENCED.guidance
                        SPEECH_ERROR_INPUT_ROUTE_CHANGED -> VoiceInputFailure.ROUTE_CHANGED.guidance
                        else -> "휴대전화 음성 인식에 오류가 발생했습니다. 음성 명령 버튼을 눌러 다시 시도해 주세요."
                    },
                    // One backend failure cannot permanently disable the offline fallback.
                    permanentlyLimit = false,
                )
                when (purpose) {
                    VoiceRecognitionPurpose.WALK_SESSION_RESUME ->
                        commands.resumeRecognizerNotStarted()
                    VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER ->
                        commands.takeoverRecognized(emptyList())
                    VoiceRecognitionPurpose.COMMAND -> Unit
                }
            }

            override fun onResults(results: Bundle?) {
                if (
                    !currentLease()
                ) return
                voiceRecognitionGeneration += 1
                voiceRecognitionActive = false
                voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
                presentation.refreshControls(active = false)
                presentation.voiceStatus("기기 내 음성 명령을 처리했습니다.")
                val phrases = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                diagnostics.input(
                    VoiceInputDiagnosticEvent.ONE_SHOT_UI_FINAL,
                    confidence = confidenceScores?.getOrNull(0),
                    resultCount = phrases.size,
                )
                when (purpose) {
                    VoiceRecognitionPurpose.WALK_SESSION_RESUME ->
                        commands.resumeRecognized(phrases, confidenceScores)
                    VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER ->
                        commands.takeoverRecognized(phrases)
                    VoiceRecognitionPurpose.COMMAND ->
                        commands.recognized(RecognizedVoiceInput(phrases.toList(), confidenceScores?.toList(),
                            expectedNavigationDecisionToken, backend = offlineSpeechResultEngine(results),
                            hasAdditionalAlternatives = hasAdditionalOfflineSpeechAlternatives(results)))
                }
                scheduleHandsFreeVoiceRestart()
            }
        }
    }

    fun isVoiceRecognitionLeaseCurrent(
        expectedGeneration: Int,
        expectedPurpose: VoiceRecognitionPurpose,
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedResumeToken: WalkSessionConfirmationToken?,
        expectedGatewayWalkOperationId: String?,
    ): Boolean {
        if (
            expectedGeneration != voiceRecognitionGeneration ||
            expectedPurpose != voiceRecognitionPurpose
        ) return false
        recognitionSource?.takeIf { it.generation == expectedGeneration }?.let {
            if (!recognitionSourceIsCurrent(it)) return false
        }
        return when (expectedPurpose) {
            VoiceRecognitionPurpose.COMMAND ->
                requireNotNull(environment.walkOrNull()).let { snapshot ->
                    snapshot.epoch == expectedWalkEpoch &&
                        snapshot.isForeground &&
                        (snapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED) ||
                            (environment.page().voiceInteraction && homeVoiceCommandAvailable()))
                }
            VoiceRecognitionPurpose.WALK_SESSION_RESUME -> {
                val snapshot = requireNotNull(environment.walkOrNull())
                expectedResumeToken != null &&
                    snapshot.epoch == expectedWalkEpoch &&
                    snapshot.state == WalkSessionState.PAUSED &&
                    snapshot.recoveryStage ==
                    WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION &&
                    snapshot.confirmationToken == expectedResumeToken &&
                    environment.confirmation().resumeToken == expectedResumeToken
            }
            VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER -> {
                val snapshot = requireNotNull(environment.walkOrNull())
                expectedGatewayWalkOperationId != null &&
                    snapshot.epoch == expectedWalkEpoch &&
                    snapshot.state == WalkSessionState.READY &&
                    environment.confirmation().takeoverPromptPending &&
                    environment.confirmation().takeoverOperationId ==
                    expectedGatewayWalkOperationId &&
                    environment.confirmation().takeoverStartToken ==
                    snapshot.confirmationToken &&
                    environment.takeoverConflictAvailable(expectedWalkEpoch)
            }
        }
    }
    private companion object {
        const val DEFERRED_VOICE_INPUT_POLL_MS = 100L
        const val DEFERRED_VOICE_INPUT_MAX_WAIT_MS = 30_000L
    }

}
