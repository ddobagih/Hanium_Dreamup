package kr.co.hanium.dreamup.walksafe.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection

const val PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS = 370L

enum class NavigationSpeechDispatchResult {
    ACCEPTED,
    UNAVAILABLE,
    SUPPRESSED,
}

data class RiskFeedbackDispatchResult(
    val speech: NavigationSpeechDispatchResult,
    val vibrationAccepted: Boolean,
)

/**
 * Owns Android TTS, audio focus and vibration resources. Risk speech flushes queued navigation;
 * utterance callbacks release focus only after all tracked speech finishes. Call [close] at teardown.
 *
 * Public output requests may arrive on any thread. Speech admission (priority, cancellation and
 * registration) and native speak/stop/close share speechEngineOperationLock, in that order before
 * pendingUtterances. Native callbacks are posted to the main scheduler; caller validators, focus
 * effects and terminal callbacks run outside both locks so they may reenter or wait for another
 * request. Haptic effects use outputGate's separate lifetime/rank order; no speech operation lock
 * acquires that gate.
 */
class AndroidFeedbackActuator private constructor(
    private val onOfflineKoreanSpeechUnavailable: () -> Unit = {},
    private val speechAllowed: () -> Boolean = { true },
    private val hapticAllowed: () -> Boolean = { true },
    private val onOfflineKoreanSpeechReady: () -> Unit = {},
    private val speechRuntime: FeedbackSpeechRuntime,
    private val ttsRecoveryBudget: TtsEngineRecoveryBudget = TtsEngineRecoveryBudget(),
) : TextToSpeech.OnInitListener, Closeable {
    constructor(
        context: Context,
        onOfflineKoreanSpeechUnavailable: () -> Unit = {},
        speechAllowed: () -> Boolean = { true },
        hapticAllowed: () -> Boolean = { true },
        onOfflineKoreanSpeechReady: () -> Unit = {},
    ) : this(
        onOfflineKoreanSpeechUnavailable, speechAllowed, hapticAllowed,
        onOfflineKoreanSpeechReady, AndroidFeedbackSpeechRuntime(context),
    )

    /** Injects only platform effects; tests execute this actuator's real ownership state machine. */
    internal constructor(
        runtime: FeedbackSpeechRuntime,
        onOfflineKoreanSpeechUnavailable: () -> Unit = {},
        speechAllowed: () -> Boolean = { true },
        hapticAllowed: () -> Boolean = { false },
        onOfflineKoreanSpeechReady: () -> Unit = {},
        recoveryBudget: TtsEngineRecoveryBudget = TtsEngineRecoveryBudget(),
    ) : this(
        onOfflineKoreanSpeechUnavailable, speechAllowed, hapticAllowed,
        onOfflineKoreanSpeechReady, runtime, recoveryBudget,
    )

    private val ready = AtomicBoolean(false)
    private val pendingSpeechQueue = PendingSpeechQueue()
    private val speechUnavailableNotified = AtomicBoolean(false)
    @Volatile
    private var speechUnavailableVibrationRetained = false
    @Volatile
    private var ttsState = TtsState.INITIALIZING
    private val mainHandler = speechRuntime.scheduler
    private val outputGate = FeedbackOutputGate(::elapsedRealtime)
    @Volatile
    private var ttsEngineGeneration = 0L
    private var preparedSpeechEngineGeneration: Long? = null
    private var ttsInitializationWatchdog: Runnable? = null
    private val audioFocusLock = Any()
    private var hasAudioFocus = false
    private var activeAudioFocus = AudioManager.AUDIOFOCUS_LOSS
    private var activeAudioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioFocusGeneration = 0L
    private val pendingUtterances = UtteranceLedger()
    private val speechEngineOperationLock = Any()
    @Volatile
    private var speechEngineStopInProgress = false
    private var speechRecognitionBlockedUntilMs = 0L
    private var speechRecognitionWaitLogged = false
    private val utteranceCallbacks = UtteranceCallbackRegistry()
    private data class RiskStartCheck(val id: String, val deadlineMs: Long, val validator: () -> Boolean)
    private val utteranceSequence = AtomicLong(0L)
    private val priorityUserTrainingVibrationLock = Any()
    private var priorityUserTrainingVibrationCompletion: Runnable? = null
    private var priorityUserTrainingVibrationFailure: (() -> Unit)? = null
    private var lastVibrationPattern: LongArray? = null
    private var lastVibrationAtMs = 0L
    private var lastRiskMessage = ""
    private var lastRiskMessageAtMs = 0L
    private var lastNavMessage = ""
    private var lastNavMessageAtMs = 0L
    private var lastInteractionMessage = ""
    private var lastInteractionMessageAtMs = 0L
    private val duplicateOwnerByPriority = mutableMapOf<String, String>()
    private var progressTone: ProgressToneEffects? = null
    private var progressToneVolumePercent = -1
    private var progressToneGeneration = 0

    /**
     * Release-safe structured outcome hook (13.txt #38). Events carry only the output channel,
     * outcome, monotonic time and platform error code; never the utterance id or spoken text.
     * The owner decides whether consent allows keeping them.
     */
    @Volatile
    var speechOutputListener: ((SpeechOutputEvent) -> Unit)? = null
    /** Separate test diagnostics; never replaces the consented speech-storage listener. */
    @Volatile
    internal var guidanceDiagnosticListener: ((SpeechOutputEvent) -> Unit)? = null
    /**
     * S08-34 (nav-F5): true only while a start validator runs for an utterance the engine has
     * actually started (main thread). Submission and start-watchdog checks leave it false.
     */
    var validatingStartedSpeech: Boolean = false
        private set

    init {
        initializeTextToSpeech()
    }

    companion object {
        private const val ANNOUNCE_ASSERTIVE_PREFIX = "risk"
        private const val ANNOUNCE_INTERACTION_PREFIX = "interaction"
        private const val ANNOUNCE_NAV_PREFIX = "nav"
        private const val ANNOUNCE_ADVISORY_PREFIX = "advisory"
        private const val RISK_DUP_WINDOW_MS = 800L
        private const val INTERACTION_DUP_WINDOW_MS = 1_000L
        private const val NAV_DUP_WINDOW_MS = 2_000L
        private const val VIBRATION_DUP_WINDOW_MS = 700L
        private const val PROGRESS_BEEP_DURATION_MS = 80
        private const val PROGRESS_BEEP_FOCUS_MARGIN_MS = 50L
        private const val UTTERANCE_START_TIMEOUT_MS = 8_000L
        private const val TTS_INITIALIZATION_TIMEOUT_MS = 10_000L
        private const val TTS_RECOVERY_DELAY_MS = 250L
        private const val SPEECH_RECOGNITION_QUIET_MS = 500L
        // Initial attempt, at most one recovery attempt (the FP-027-DETAIL-03 interim budget
        // allows none), and room for the caller's readiness poll.
        const val INITIALIZATION_READINESS_TIMEOUT_MS =
            2 * TTS_INITIALIZATION_TIMEOUT_MS + TTS_RECOVERY_DELAY_MS + 250L
    }

    private fun initializeTextToSpeech() {
        if (ttsState == TtsState.CLOSED) return
        val engineGeneration = ++ttsEngineGeneration
        ready.set(false)
        ttsState = TtsState.INITIALIZING
        ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)
        if (isSpeechEngineInitialized()) {
            runCatching { stopSpeechEngine() }
            runCatching { shutdownSpeechEngine() }
        }
        try {
            val initialized: (Int) -> Unit = { status ->
                mainHandler.post {
                    if (engineGeneration == ttsEngineGeneration) onInit(status)
                }
            }
            speechRuntime.initialize(initialized)
        } catch (_: RuntimeException) {
            mainHandler.post {
                if (engineGeneration == ttsEngineGeneration) recoverTextToSpeechOrFail()
            }
            return
        }
        val watchdog = Runnable {
            if (engineGeneration == ttsEngineGeneration && ttsState == TtsState.INITIALIZING) {
                recoverTextToSpeechOrFail()
            }
        }
        ttsInitializationWatchdog = watchdog
        mainHandler.postDelayed(watchdog, TTS_INITIALIZATION_TIMEOUT_MS)
    }

    override fun onInit(status: Int) {
        if (ttsState == TtsState.CLOSED) return
        if (ttsState != TtsState.INITIALIZING) return
        ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)
        ttsInitializationWatchdog = null
        if (status != TextToSpeech.SUCCESS) {
            recoverTextToSpeechOrFail()
            return
        }
        val languageStatus = runCatching {
            speechRuntime.setLanguage(Locale.KOREAN)
        }.getOrElse {
            recoverTextToSpeechOrFail()
            return
        }
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            failOfflineKoreanLanguage()
            return
        }
        val voiceSelection = runCatching {
            speechRuntime.selectOfflineKoreanVoice()
        }.getOrElse {
            recoverTextToSpeechOrFail()
            return
        }
        when (voiceSelection) {
            KoreanOfflineVoiceSelection.SELECTED -> Unit
            KoreanOfflineVoiceSelection.UNAVAILABLE -> {
                failOfflineKoreanLanguage()
                return
            }
            KoreanOfflineVoiceSelection.REJECTED -> {
                recoverTextToSpeechOrFail()
                return
            }
        }
        if (!configureSpeechAttributes()) return
        val engineGeneration = ttsEngineGeneration
        val listenerResult = installSpeechListener(
            object : FeedbackSpeechRuntime.Listener {
                override fun onStart(utteranceId: String?) {
                    postUtteranceCallback(engineGeneration, utteranceId) {
                        logSpeechEvent("started", utteranceId)
                        emitSpeechOutcome(SpeechOutputOutcome.STARTED, utteranceId)
                        validatingStartedSpeech = true
                        val validAtStart = try {
                            isUtteranceStillValidAtStart(utteranceId)
                        } finally {
                            validatingStartedSpeech = false
                        }
                        if (validAtStart) {
                            armUtteranceTerminalWatchdog(utteranceId)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    postUtteranceCallback(engineGeneration, utteranceId) {
                        logSpeechEvent("done", utteranceId)
                        emitSpeechOutcome(SpeechOutputOutcome.DONE, utteranceId)
                        markUtteranceFinished(utteranceId, completed = true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    postUtteranceCallback(engineGeneration, utteranceId) {
                        failRequiredSpeechRuntime(utteranceId, TextToSpeech.ERROR)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    postUtteranceCallback(engineGeneration, utteranceId) {
                        failRequiredSpeechRuntime(utteranceId, errorCode)
                    }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    postUtteranceCallback(engineGeneration, utteranceId) {
                        logSpeechEvent("stopped interrupted=$interrupted", utteranceId)
                        emitSpeechOutcome(
                            if (interrupted) SpeechOutputOutcome.INTERRUPTED else SpeechOutputOutcome.STOPPED,
                            utteranceId,
                        )
                        markUtteranceFinished(utteranceId, completed = false, cancelled = true)
                    }
                }
            },
        )
        if (listenerResult == TextToSpeech.ERROR) {
            recoverTextToSpeechOrFail()
            return
        }
        synchronized(speechEngineOperationLock) {
            if (ttsState != TtsState.INITIALIZING || engineGeneration != ttsEngineGeneration) return
            preparedSpeechEngineGeneration = engineGeneration
            ready.set(true)
            ttsState = TtsState.READY
            speechUnavailableNotified.set(false)
        }
        runCatching(onOfflineKoreanSpeechReady)
        flushPendingSpeech()
    }

    private fun postUtteranceCallback(engineGeneration: Long, utteranceId: String?, callback: () -> Unit) {
        mainHandler.post {
            if (ttsState != TtsState.READY || engineGeneration != ttsEngineGeneration) return@post
            if (utteranceId == null || synchronized(pendingUtterances) { utteranceId !in pendingUtterances }) {
                return@post
            }
            callback()
        }
    }

    private fun notifyOfflineKoreanSpeechUnavailable() {
        if (speechUnavailableNotified.compareAndSet(false, true)) {
            // S13-12 / FP-027-DETAIL-03 interim: speech failed, so vibration and the owner's
            // screen notice carry the failure. The owner's safety stop closes this actuator next;
            // close() keeps this one stop pattern running instead of cancelling it.
            // w24 misc-F1: the notice is never absorbed by the same-pattern dedupe window. A STOP
            // risk cue whose speech fails within that window would otherwise leave nothing to
            // retain, and close() would cut the risk vibration that was still playing; the full
            // stop pattern restarts here and survives close().
            speechUnavailableVibrationRetained = vibrate(
                checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)),
                rank = MessageLevel.STOP.ordinal,
                dedupe = false,
            )
            val engineGeneration = ttsEngineGeneration
            mainHandler.post {
                if (
                    engineGeneration == ttsEngineGeneration &&
                    (ttsState == TtsState.FAILED || ttsState == TtsState.LANGUAGE_UNSUPPORTED)
                ) onOfflineKoreanSpeechUnavailable()
            }
        }
    }

    private fun failRequiredSpeechRuntime(
        utteranceId: String?,
        errorCode: Int = TextToSpeech.ERROR_SERVICE,
        notifyFailure: Boolean = true,
        outcome: SpeechOutputOutcome = SpeechOutputOutcome.ENGINE_ERROR,
    ) {
        logSpeechEvent("runtime_error code=$errorCode", utteranceId)
        val failureEffects = synchronized(speechEngineOperationLock) {
            val pending = utteranceId != null && synchronized(pendingUtterances) {
                utteranceId in pendingUtterances
            }
            when (ttsRuntimeFailureDisposition(errorCode, pending, ttsState == TtsState.READY)) {
                TtsRuntimeFailureDisposition.IGNORE -> ({})
                TtsRuntimeFailureDisposition.UTTERANCE -> ({
                    markUtteranceFinished(utteranceId, completed = false, notifyFailure = notifyFailure)
                })
                TtsRuntimeFailureDisposition.ENGINE -> {
                    val recoveryEffects = takeTextToSpeechRecoveryEffects()
                    ({
                        if (!notifyFailure) markUtteranceFinished(
                            utteranceId, completed = false, notifyFailure = false,
                        )
                        recoveryEffects()
                    })
                }
                TtsRuntimeFailureDisposition.LANGUAGE -> takeOfflineKoreanLanguageFailureEffects()
            }
        }
        // A valid engine failure retires readiness before any observer can replace its owner.
        // Consumers and native effects run after both ownership locks have been released.
        emitSpeechOutcome(
            outcome,
            utteranceId,
            errorCode.takeIf { outcome == SpeechOutputOutcome.ENGINE_ERROR },
        )
        failureEffects()
    }

    private fun recoverTextToSpeechOrFail() {
        takeTextToSpeechRecoveryEffects()()
    }

    private fun takeTextToSpeechRecoveryEffects(): () -> Unit {
        val recover: Boolean
        val engineGeneration: Long
        val interrupted: List<String>
        synchronized(speechEngineOperationLock) {
            if (ttsState == TtsState.CLOSED) return {}
            recover = ttsRecoveryBudget.claimRetry()
            engineGeneration = ++ttsEngineGeneration
            ready.set(false)
            if (recover) ttsState = TtsState.INITIALIZING else ttsState = TtsState.FAILED
            ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)
            ttsInitializationWatchdog = null
            pendingSpeechQueue.clear()
            interrupted = synchronized(pendingUtterances) { pendingUtterances.toList() }
        }
        return {
            synchronized(speechEngineOperationLock) {
                if (engineGeneration == ttsEngineGeneration && ttsState != TtsState.CLOSED &&
                    isSpeechEngineInitialized()
                ) stopSpeechEngine()
            }
            failPendingSpeech(interrupted, stopTts = false)
            // Observer, native stop and terminal consumers may have closed or replaced the engine.
            if (engineGeneration == ttsEngineGeneration && ttsState != TtsState.CLOSED) {
                if (recover) {
                    mainHandler.postDelayed(
                        {
                            if (engineGeneration == ttsEngineGeneration && ttsState == TtsState.INITIALIZING) {
                                initializeTextToSpeech()
                            }
                        },
                        TTS_RECOVERY_DELAY_MS,
                    )
                } else {
                    notifyOfflineKoreanSpeechUnavailable()
                }
            }
        }
    }

    private fun failOfflineKoreanLanguage() {
        takeOfflineKoreanLanguageFailureEffects()()
    }

    private fun takeOfflineKoreanLanguageFailureEffects(): () -> Unit {
        val engineGeneration: Long
        val interrupted: List<String>
        synchronized(speechEngineOperationLock) {
            if (ttsState == TtsState.CLOSED) return {}
            engineGeneration = ++ttsEngineGeneration
            ready.set(false)
            ttsState = TtsState.LANGUAGE_UNSUPPORTED
            pendingSpeechQueue.clear()
            interrupted = synchronized(pendingUtterances) { pendingUtterances.toList() }
        }
        return {
            synchronized(speechEngineOperationLock) {
                if (engineGeneration == ttsEngineGeneration && ttsState == TtsState.LANGUAGE_UNSUPPORTED &&
                    isSpeechEngineInitialized()
                ) stopSpeechEngine()
            }
            failPendingSpeech(interrupted, stopTts = false)
            if (engineGeneration == ttsEngineGeneration && ttsState == TtsState.LANGUAGE_UNSUPPORTED) {
                notifyOfflineKoreanSpeechUnavailable()
            }
        }
    }

    fun emit(
        action: FeedbackAction,
        onSpeechCompleted: (() -> Unit)?,
        onSpeechFailed: (() -> Unit)?,
        isStillValidAtStart: () -> Boolean,
        onSpeechCancelled: (() -> Unit)? = null,
    ): RiskFeedbackDispatchResult {
        val policyOwner = action.deliveryOwner
        if (policyOwner != null && (!policyOwner.matches(action) || !policyOwner.isClaimed())) {
            return RiskFeedbackDispatchResult(NavigationSpeechDispatchResult.SUPPRESSED, false)
        }
        val generation = outputGate.generation()
        val current = {
            outputGate.isCurrent(generation, action.validUntilMs) &&
                runCatching(isStillValidAtStart).getOrDefault(false) &&
                outputGate.isCurrent(generation, action.validUntilMs)
        }
        if (!current()) return RiskFeedbackDispatchResult(NavigationSpeechDispatchResult.SUPPRESSED, false)
        val speech = speak(
            message = action.message,
            priority = SpeechPriority.RISK,
            onCompleted = onSpeechCompleted,
            onFailed = onSpeechFailed,
            riskRank = action.level.ordinal,
            advisoryStartDeadlineMs = action.validUntilMs,
            advisoryStartValidator = current,
            requiresExplicitTerminalCallback = true,
            protectsFromFollowingSpeech = false,
            onCancelled = onSpeechCancelled,
            riskDeliveryOwner = policyOwner,
            onCloseCancelled = onSpeechCancelled,
            messageFamily = SpeechMessageFamily.RISK,
        )
        val vibrationCurrent = {
            (policyOwner == null || policyOwner.isClaimed()) && current() &&
                (policyOwner == null || policyOwner.isClaimed())
        }
        val vibrationAccepted = vibrate(action.vibrationPatternMs, rank = action.level.ordinal,
            validUntilMs = action.validUntilMs, isStillValid = vibrationCurrent, generation = generation,
            eventId = action.deliveryKey.takeIf { action.level == MessageLevel.STOP })
        return RiskFeedbackDispatchResult(speech, vibrationAccepted)
    }

    /**
     * Cancels only this issued risk's speech; the caller decides when its guidance is invalid.
     * True means the registered delivery was detached and native stop was attempted, not an
     * acknowledgement that the platform stopped playing. Stop errors use the existing log path.
     * The single CANCELLED_RISK observation has this same logical meaning.
     */
    fun cancelRiskSpeech(action: FeedbackAction): Boolean {
        val owner = action.deliveryOwner ?: return false
        if (!owner.matches(action)) return false
        val outputGeneration = outputGate.generation()
        if (!outputGate.isCurrent(outputGeneration)) return false
        val cancellation = synchronized(speechEngineOperationLock) {
            if (ttsState != TtsState.READY || speechEngineStopInProgress) return false
            val utteranceId = synchronized(pendingUtterances) {
                // Native TTS has no per-utterance stop. Never stop an unrelated queued owner.
                pendingUtterances.singleOrNull()?.takeIf { id ->
                    val registration = pendingUtterances[id]?.riskDelivery
                    registration != null && registration.owner === owner &&
                        registration.engineGeneration == ttsEngineGeneration &&
                        registration.outputGeneration == outputGeneration
                }
            } ?: return false
            // Revoke native callback ownership before stop, including synchronous onDone/onStop.
            // The policy may already have retired the owner, so isClaimed is not a cancel gate.
            val effects = takeUtteranceFinishEffects(utteranceId, completed = false, cancelled = true)
            stopSpeechEngine()
            utteranceId to effects
        }
        cancellation.second()
        // Native callbacks lost the detached registration; report this accepted cancellation once.
        // Both callbacks run outside admission/registry locks; observer exceptions are isolated.
        emitSpeechOutcome(SpeechOutputOutcome.CANCELLED_RISK, cancellation.first)
        return true
    }

    fun vibrateRiskOnly(action: FeedbackAction, isStillValidAtStart: () -> Boolean): Boolean =
        vibrate(action.vibrationPatternMs, rank = action.level.ordinal,
            validUntilMs = action.validUntilMs, isStillValid = isStillValidAtStart,
            eventId = action.deliveryKey.takeIf { action.level == MessageLevel.STOP })

    /** Short, distinct haptics announce when voice recognition starts and stops listening. */
    fun playVoiceListeningStartVibration(): Boolean = vibrate(longArrayOf(0L, 35L))

    fun playVoiceListeningEndVibration(): Boolean = vibrate(longArrayOf(0L, 35L, 45L, 35L))

    fun playRouteGuidancePausedVibration(): Boolean = vibrate(longArrayOf(0L, 120L))

    fun playRouteDeviationConfirmedVibration(): Boolean =
        vibrate(checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)), rank = MessageLevel.STOP.ordinal,
            eventId = "route_deviation_confirmed")

    /** Adds a short haptic cue to the separate spoken and on-screen mounting correction. */
    fun playPhoneMountingCorrectionVibration(): Boolean =
        vibrate(longArrayOf(0L, 120L))

    fun playPhoneMountingSafetyStopVibration(): Boolean =
        vibrate(checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)), rank = MessageLevel.STOP.ordinal,
            eventId = "phone_mounting_safety_stop")

    fun playPriorityUserTrainingVibration(
        onWindowElapsed: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        cancelPriorityUserTrainingVibration(notifyFailure = true)
        if (!vibrate(longArrayOf(0L, 140L, 90L, 140L), cancelPriorityUserTraining = false)) {
            return false
        }
        lateinit var completion: Runnable
        completion = Runnable {
            val callback = synchronized(priorityUserTrainingVibrationLock) {
                if (priorityUserTrainingVibrationCompletion !== completion) {
                    null
                } else {
                    priorityUserTrainingVibrationCompletion = null
                    priorityUserTrainingVibrationFailure = null
                    onWindowElapsed
                }
            }
            callback?.invoke()
        }
        synchronized(priorityUserTrainingVibrationLock) {
            priorityUserTrainingVibrationCompletion = completion
            priorityUserTrainingVibrationFailure = onFailed
        }
        mainHandler.postDelayed(
            completion,
            PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS,
        )
        return true
    }

    fun cancelCommandInteraction() {
        val commandUtterances = synchronized(speechEngineOperationLock) {
            val owned = synchronized(pendingUtterances) {
                if (pendingUtterances.any { !it.startsWith("$ANNOUNCE_INTERACTION_PREFIX-") }) return
                utteranceCallbacks.exclusiveCommandUtteranceIds(
                    pendingUtteranceIds = pendingUtterances,
                    explicitTerminalRequiredIds = pendingUtterances.explicitTerminalIds(),
                )
            }
            if (owned.isEmpty()) return
            if (ttsState == TtsState.READY) stopSpeechEngine()
            synchronized(pendingUtterances) {
                lastInteractionMessage = ""
                lastInteractionMessageAtMs = 0L
            }
            owned
        }
        commandUtterances.forEach { markUtteranceFinished(it, completed = false) }
    }

    fun cancelPriorityUserTrainingFeedback() {
        val strictUtterances = synchronized(pendingUtterances) {
            utteranceCallbacks.protectedUtteranceIds()
        }
        if (strictUtterances.isNotEmpty()) {
            synchronized(speechEngineOperationLock) {
                // Stop the still-owned training speech before its terminal callback can submit
                // a replacement risk. Never stop a risk that already replaced this snapshot.
                val stillOwnsStop = synchronized(pendingUtterances) {
                    ttsState == TtsState.READY &&
                        strictUtterances.any { it in pendingUtterances } &&
                        pendingUtterances.all { it in strictUtterances }
                }
                if (stillOwnsStop) {
                    speechEngineStopInProgress = true
                    try {
                        stopSpeechEngine()
                    } finally {
                        speechEngineStopInProgress = false
                    }
                }
            }
        }
        strictUtterances.forEach {
            markUtteranceFinished(it, completed = false)
        }
        cancelPriorityUserTrainingVibration(notifyFailure = true)
    }

    /** Gives a spoken accessibility service sole ownership before it announces a risk. */
    fun prepareForExternalRiskAnnouncement() {
        val watchdogs: List<Runnable>
        val advisoryFailures: List<() -> Unit>
        val strictTrainingFailures: List<() -> Unit>
        val focusRegistration: AudioManager.OnAudioFocusChangeListener?
        val tone: ProgressToneEffects?
        synchronized(speechEngineOperationLock) {
            pendingSpeechQueue.clear()
            synchronized(pendingUtterances) {
                progressToneGeneration += 1
                tone = detachProgressToneLocked()?.first
                advisoryFailures = pendingUtterances
                    .filter { it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }
                    .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true) }
                strictTrainingFailures = pendingUtterances.explicitTerminalIds()
                    .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true) }
                if (pendingUtterances.isNotEmpty()) {
                    speechRecognitionBlockedUntilMs = elapsedRealtime() + SPEECH_RECOGNITION_QUIET_MS
                }
                watchdogs = pendingUtterances.drain().mapNotNull { it.watchdog }
                utteranceCallbacks.clear()
                lastRiskMessage = ""
                lastRiskMessageAtMs = 0L
                lastNavMessage = ""
                lastNavMessageAtMs = 0L
                lastInteractionMessage = ""
                lastInteractionMessageAtMs = 0L
                focusRegistration = retireAudioFocusRegistration()
            }
            // Admission cannot publish a replacement between retiring the old owners and stop.
            if (ttsState == TtsState.READY) stopSpeechEngine()
        }
        watchdogs.forEach(mainHandler::removeCallbacks)
        advisoryFailures.forEach { callback ->
            runCatching { callback() }
                .onFailure { logSpeechEvent("terminal_callback_error") }
        }
        strictTrainingFailures.forEach { callback ->
            runCatching { callback() }
                .onFailure { logSpeechEvent("terminal_callback_error") }
        }
        stopProgressToneSafely { tone?.stopTone() }
        releaseProgressTone(tone)
        focusRegistration?.let { releaseAudioFocusRegistration(it) }
    }

    fun speakNavigation(
        message: String,
        onCompleted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        requiresExplicitTerminalCallback: Boolean = false,
        onCancelled: (() -> Unit)? = null,
        isStillValidAtStart: () -> Boolean = { true },
    ): NavigationSpeechDispatchResult {
        if (!speechStartIsCurrent(elapsedRealtime(), validator = isStillValidAtStart)) {
            onCancelled?.invoke()
            return NavigationSpeechDispatchResult.SUPPRESSED
        }
        return speak(
            message = message,
            priority = SpeechPriority.NAVIGATION,
            onCompleted = onCompleted,
            onFailed = onFailed,
            advisoryStartDeadlineMs = Long.MAX_VALUE,
            advisoryStartValidator = isStillValidAtStart,
            requiresExplicitTerminalCallback = requiresExplicitTerminalCallback,
            protectsFromFollowingSpeech = false,
            onCancelled = onCancelled,
            onCloseCancelled = onCancelled,
            messageFamily = SpeechMessageFamily.NAVIGATION,
        )
    }

    /** Cancels stale route utterances without stopping speech that replaced them. */
    fun cancelNavigationSpeech(): Boolean {
        val queuedNavigationRemoved = pendingSpeechQueue.removePriority(SpeechPriority.NAVIGATION)
        val snapshot = synchronized(pendingUtterances) { pendingUtterances.toList() }
        val navigation = snapshot.filter { it.startsWith("$ANNOUNCE_NAV_PREFIX-") }
        if (navigation.isEmpty()) return queuedNavigationRemoved
        logSpeechEvent("navigation_cancelled", navigation.firstOrNull())
        navigation.forEach { emitSpeechOutcome(SpeechOutputOutcome.CANCELLED_STALE_NAVIGATION, it) }
        synchronized(speechEngineOperationLock) {
            // A risk may replace the snapshot before this stop. Keep the ownership check
            // atomic with native speak/stop, without holding the utterance lock in TTS.
            val stillOwnsStop = synchronized(pendingUtterances) {
                ttsState == TtsState.READY &&
                    navigation.any { it in pendingUtterances } &&
                    pendingUtterances.all { it.startsWith("$ANNOUNCE_NAV_PREFIX-") }
            }
            if (stillOwnsStop) stopSpeechEngine()
        }
        navigation.forEach {
            val notifyFailure = synchronized(pendingUtterances) {
                pendingUtterances[it]?.requiresExplicitTerminalCallback == true
            }
            markUtteranceFinished(it, completed = false, notifyFailure = notifyFailure, cancelled = true)
        }
        return true
    }

    /** Explicit home command responses need a real offline voice, not walking capability approval. */
    fun speakHomeCommandInteraction(
        message: String,
        onFailed: () -> Unit,
        onCompleted: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null,
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        if (ttsState != TtsState.READY) return NavigationSpeechDispatchResult.UNAVAILABLE
        return speakReady(
            message = message,
            priority = SpeechPriority.INTERACTION,
            onCompleted = onCompleted,
            onFailed = onFailed,
            requiresExplicitTerminalCallback = true,
            protectsFromFollowingSpeech = false,
            onCancelled = onCancelled ?: onFailed,
            onCloseCancelled = onCancelled,
            messageFamily = SpeechMessageFamily.INTERACTION,
        )
    }

    fun speakInteraction(message: String): Boolean =
        speak(message, SpeechPriority.INTERACTION, messageFamily = SpeechMessageFamily.INTERACTION) ==
            NavigationSpeechDispatchResult.ACCEPTED

    /** Home response currentness is checked at admission, after focus and at native START. */
    @JvmOverloads
    fun speakGuardedHomeCommandInteraction(
        message: String,
        isStillValidAtStart: () -> Boolean,
        onFailed: () -> Unit,
        onCompleted: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null,
        deferToCurrentSpeech: Boolean = false,
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        if (ttsState != TtsState.READY) return NavigationSpeechDispatchResult.UNAVAILABLE
        return speakReady(
            message = message,
            priority = SpeechPriority.INTERACTION,
            onCompleted = onCompleted,
            onFailed = onFailed,
            advisoryStartDeadlineMs = Long.MAX_VALUE,
            advisoryStartValidator = isStillValidAtStart,
            requiresExplicitTerminalCallback = true,
            protectsFromFollowingSpeech = false,
            onCancelled = onCancelled ?: onFailed,
            onCloseCancelled = onCancelled,
            messageFamily = SpeechMessageFamily.INTERACTION,
            deferToCurrentSpeech = deferToCurrentSpeech,
        )
    }

    /** The caller owns the immutable response source; callbacks retain interaction semantics. */
    @JvmOverloads
    fun speakGuardedInteraction(
        message: String,
        isStillValidAtStart: () -> Boolean,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        onCancelled: (() -> Unit)? = null,
        deferToCurrentSpeech: Boolean = false,
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.INTERACTION,
        onCompleted = onCompleted,
        onFailed = onFailed,
        advisoryStartDeadlineMs = Long.MAX_VALUE,
        advisoryStartValidator = isStillValidAtStart,
        requiresExplicitTerminalCallback = true,
        protectsFromFollowingSpeech = false,
        onCancelled = onCancelled ?: onFailed,
        onCloseCancelled = onCancelled,
        messageFamily = SpeechMessageFamily.INTERACTION,
        deferToCurrentSpeech = deferToCurrentSpeech,
    )

    fun speakInteraction(
        message: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        onCancelled: (() -> Unit)? = null,
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.INTERACTION,
        onCompleted = onCompleted,
        onFailed = onFailed,
        requiresExplicitTerminalCallback = true,
        protectsFromFollowingSpeech = false,
        onCancelled = onCancelled ?: onFailed,
        onCloseCancelled = onCancelled,
        messageFamily = SpeechMessageFamily.INTERACTION,
    )

    fun speakExplicitConfirmation(
        message: String,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
        onCancelled: (() -> Unit)? = null,
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.INTERACTION,
        onCompleted = onCompleted,
        onFailed = onFailed,
        requiresExplicitTerminalCallback = true,
        onCancelled = onCancelled ?: onFailed,
        onCloseCancelled = onCancelled,
        messageFamily = SpeechMessageFamily.EDUCATION_OR_CONSENT,
    )

    /** Explicit consent reading can precede startup checks, but still requires a ready offline voice. */
    fun speakConsentClause(
        message: String,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
        onCancelled: (() -> Unit)? = null,
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        if (ttsState != TtsState.READY) return NavigationSpeechDispatchResult.UNAVAILABLE
        return speakReady(
            message = message,
            priority = SpeechPriority.INTERACTION,
            onCompleted = onCompleted,
            onFailed = onFailed,
            requiresExplicitTerminalCallback = true,
            onCancelled = onCancelled ?: onFailed,
            onCloseCancelled = onCancelled,
            messageFamily = SpeechMessageFamily.EDUCATION_OR_CONSENT,
        )
    }

    fun speakPriorityUserTraining(
        message: String,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
        onCancelled: (() -> Unit)? = null,
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.INTERACTION,
        onCompleted = onCompleted,
        onFailed = onFailed,
        requiresExplicitTerminalCallback = true,
        onCancelled = onCancelled ?: onFailed,
        onCloseCancelled = onCancelled,
        messageFamily = SpeechMessageFamily.EDUCATION_OR_CONSENT,
    )

    /** Queues a low-priority camera advisory without interrupting navigation or risk speech. */
    fun speakAdvisory(
        message: String,
        validUntilMs: Long,
        isStillValid: () -> Boolean,
        onCompleted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ): Boolean = speak(
        message = message,
        priority = SpeechPriority.ADVISORY,
        onCompleted = onCompleted,
        onFailed = onFailed,
        advisoryStartDeadlineMs = validUntilMs,
        advisoryStartValidator = isStillValid,
    ) == NavigationSpeechDispatchResult.ACCEPTED

    /**
     * Uses the same advisory admission as [speakAdvisory]. A registered request delivers its receipt
     * once, outside both speech locks, after REQUESTED and before focus or native speech.
     * An observer or concurrent replacement may retire it before delivery; callers must also fence
     * their source ownership and terminal state when receiving the receipt.
     * Terminal callbacks may run before this method returns; callers must keep ownership from
     * [onRegistered], rather than restoring it from the returned dispatch result. The callback is
     * exception-isolated. A receipt can already be retired when dispatch returns false.
     */
    fun speakAdvisoryWithReceipt(
        message: String,
        validUntilMs: Long,
        isStillValid: () -> Boolean,
        onRegistered: (AdvisorySpeechReceipt) -> Unit,
        onCompleted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = onFailed,
    ): Boolean {
        val receipt = AdvisorySpeechReceipt()
        return speak(
            message = message,
            priority = SpeechPriority.ADVISORY,
            onCompleted = onCompleted,
            onFailed = onFailed,
            advisoryStartDeadlineMs = validUntilMs,
            advisoryStartValidator = isStillValid,
            onCancelled = onCancelled,
            advisoryReceipt = receipt,
            onAdvisoryRegistered = { onRegistered(receipt) },
        ) == NavigationSpeechDispatchResult.ACCEPTED
    }

    /**
     * Detaches only this actuator's exact, still registered advisory, even if its source was
     * invalidated after playback began. True means logical cancellation and a native stop attempt,
     * not a platform playback acknowledgement. It preserves haptics and engine readiness.
     */
    fun cancelAdvisorySpeech(receipt: AdvisorySpeechReceipt): Boolean {
        val outputGeneration = outputGate.generation()
        if (!outputGate.isCurrent(outputGeneration)) return false
        val cancellation = synchronized(speechEngineOperationLock) {
            if (ttsState != TtsState.READY || speechEngineStopInProgress) return false
            val utteranceId = synchronized(pendingUtterances) {
                // Native stop cannot target an individual utterance in an unrelated queue.
                pendingUtterances.singleOrNull()?.takeIf { id ->
                    val registration = pendingUtterances[id]?.advisoryDelivery
                    registration != null && registration.receipt === receipt &&
                        registration.engineGeneration == ttsEngineGeneration &&
                        registration.outputGeneration == outputGeneration
                }
            } ?: return false
            val effects = takeUtteranceFinishEffects(utteranceId, completed = false, cancelled = true)
            stopSpeechEngine()
            utteranceId to effects
        }
        cancellation.second()
        emitSpeechOutcome(SpeechOutputOutcome.CANCELLED_ADVISORY, cancellation.first)
        return true
    }

    fun isAppSpeechIdleForExternalAdvisory(): Boolean =
        synchronized(pendingUtterances) { pendingUtterances.isEmpty() }

    /** Includes queued and currently playing app-owned TTS so microphone decoders can pause. */
    fun isAppSpeechActive(): Boolean =
        synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }

    /** Includes initialization-time queues and the speaker tail without delaying later TTS. */
    fun isSpeechRecognitionBlocked(): Boolean =
        SpeechPriority.entries.any(pendingSpeechQueue::hasPriority) || synchronized(pendingUtterances) {
            pendingUtterances.isNotEmpty() || elapsedRealtime() < speechRecognitionBlockedUntilMs
        }

    /** STT waits for every queued utterance and its quiet period instead of cancelling output. */
    fun prepareForSpeechRecognition(): Boolean {
        if (isSpeechRecognitionBlocked()) {
            if (!speechRecognitionWaitLogged) logSpeechEvent("recognition_waiting_for_output")
            speechRecognitionWaitLogged = true
            return false
        }
        if (speechRecognitionWaitLogged) logSpeechEvent("recognition_output_settled")
        speechRecognitionWaitLogged = false
        synchronized(pendingUtterances) { progressToneGeneration += 1 }
        stopProgressToneSafely { progressTone?.stopTone() }
        abandonAudioFocus()
        return true
    }

    fun playProgressBeep(volumePercent: Int) {
        if (!outputGate.isCurrent() || !speechAllowed()) return
        val volume = volumePercent.coerceIn(0, 100)
        if (volume <= 0) return
        val outputGeneration = outputGate.generation()
        val attempt = synchronized(pendingUtterances) {
            if (pendingUtterances.isNotEmpty()) return
            ++progressToneGeneration
        }
        val speechPending = synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }
        if (speechPending || !requestAudioFocus(isRisk = false)) return
        val focusGeneration = synchronized(audioFocusLock) { audioFocusGeneration }
        if (!isProgressAttemptCurrent(attempt, outputGeneration, focusGeneration)) {
            abandonProgressFocusIfOwned(attempt, focusGeneration)
            return
        }
        // No in-flight native effect may expose its tone for a reentrant attempt to reuse.
        val cached = synchronized(pendingUtterances) {
            if (progressToneGeneration == attempt) detachProgressToneLocked() else null
        }
        var tone = cached?.first
        if (cached != null && cached.second != volume) {
            tone = null
            val released = releaseProgressTone(cached.first)
            if (!released || !isProgressAttemptCurrent(attempt, outputGeneration, focusGeneration)) {
                abandonProgressFocusIfOwned(attempt, focusGeneration)
                return
            }
        }
        if (!isProgressAttemptCurrent(attempt, outputGeneration, focusGeneration)) {
            discardProgressTone(tone, attempt, focusGeneration)
            return
        }
        if (tone == null) {
            tone = try {
                speechRuntime.createProgressTone(volume)
            } catch (_: Exception) {
                logSpeechEvent("progress_tone_create_error")
                abandonProgressFocusIfOwned(attempt, focusGeneration)
                return
            }
        }
        val ownedTone = tone
        if (!isProgressAttemptCurrent(attempt, outputGeneration, focusGeneration)) {
            discardProgressTone(ownedTone, attempt, focusGeneration)
            return
        }
        val started = try {
            ownedTone.startTone(ToneGenerator.TONE_PROP_BEEP, PROGRESS_BEEP_DURATION_MS)
        } catch (_: Exception) {
            logSpeechEvent("progress_tone_start_error")
            false
        }
        if (!started) {
            discardProgressTone(ownedTone, attempt, focusGeneration)
            return
        }
        val retained = outputGate.isCurrent(outputGeneration) && synchronized(pendingUtterances) {
            if (!isProgressAttemptCurrentLocked(attempt, focusGeneration)) {
                false
            } else {
                progressTone = ownedTone
                progressToneVolumePercent = volume
                true
            }
        }
        if (!retained) {
            discardProgressTone(ownedTone, attempt, focusGeneration)
            return
        }
        mainHandler.postDelayed(
            { abandonProgressFocusIfOwned(attempt, focusGeneration) },
            PROGRESS_BEEP_DURATION_MS + PROGRESS_BEEP_FOCUS_MARGIN_MS,
        )
    }

    private fun detachProgressToneLocked(): Pair<ProgressToneEffects, Int>? {
        val tone = progressTone ?: return null
        val volume = progressToneVolumePercent
        progressTone = null
        progressToneVolumePercent = -1
        return tone to volume
    }

    private fun isProgressAttemptCurrent(
        attempt: Int,
        outputGeneration: Long,
        focusGeneration: Long,
    ): Boolean = outputGate.isCurrent(outputGeneration) && synchronized(pendingUtterances) {
        isProgressAttemptCurrentLocked(attempt, focusGeneration)
    }

    private fun isProgressAttemptCurrentLocked(attempt: Int, focusGeneration: Long): Boolean =
        progressToneGeneration == attempt && pendingUtterances.isEmpty() &&
            synchronized(audioFocusLock) {
                hasAudioFocus && audioFocusGeneration == focusGeneration
            }

    private fun discardProgressTone(tone: ProgressToneEffects?, attempt: Int, focusGeneration: Long) {
        releaseProgressTone(tone)
        // release() may synchronously submit B using either the same or a new focus registration.
        abandonProgressFocusIfOwned(attempt, focusGeneration)
    }

    private inline fun stopProgressToneSafely(stop: () -> Unit) {
        try {
            stop()
        } catch (_: RuntimeException) {
            // A native tone fault must not skip speech, focus or preparation cleanup.
            logSpeechEvent("progress_tone_stop_error")
        }
    }

    private fun releaseProgressTone(tone: ProgressToneEffects?): Boolean {
        if (tone == null) return true
        return try {
            tone.release()
            true
        } catch (_: Exception) {
            logSpeechEvent("progress_tone_release_error")
            false
        }
    }

    private fun abandonProgressFocusIfOwned(attempt: Int, focusGeneration: Long) {
        val registration = synchronized(pendingUtterances) {
            if (progressToneGeneration != attempt || pendingUtterances.isNotEmpty()) return
            takeAudioFocusRegistration(
                expectedGeneration = focusGeneration,
                expectedGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        } ?: return
        // Native release stays outside both locks and targets the detached registration only.
        releaseAudioFocusRegistration(registration)
    }

    fun isSpeechInitializing(): Boolean = ttsState == TtsState.INITIALIZING

    /** A failed engine needs an explicit new owner; normal readiness never spends another retry. */
    internal fun hasTerminalSpeechFailure(): Boolean = synchronized(speechEngineOperationLock) {
        ttsState == TtsState.FAILED || ttsState == TtsState.LANGUAGE_UNSUPPORTED
    }

    /** The prepared engine identity, read without queuing or starting any output. */
    internal fun readySpeechEngineGenerationOrNull(): Long? = synchronized(speechEngineOperationLock) {
        preparedSpeechEngineGeneration?.takeIf {
            ttsState == TtsState.READY && ready.get() && it == ttsEngineGeneration
        }
    }

    fun statusText(): String {
        return when (ttsState) {
            TtsState.INITIALIZING -> "tts=initializing"
            TtsState.READY -> "tts=ready"
            TtsState.FAILED -> "tts=init_failed"
            TtsState.LANGUAGE_UNSUPPORTED -> "tts=korean_unsupported"
            TtsState.CLOSED -> "tts=closed"
        }
    }

    private fun speak(
        message: String,
        priority: SpeechPriority,
        onCompleted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        riskRank: Int? = null,
        advisoryStartDeadlineMs: Long? = null,
        advisoryStartValidator: (() -> Boolean)? = null,
        requiresExplicitTerminalCallback: Boolean = false,
        protectsFromFollowingSpeech: Boolean = requiresExplicitTerminalCallback,
        onCancelled: (() -> Unit)? = onFailed,
        riskDeliveryOwner: FeedbackDeliveryOwner? = null,
        onCloseCancelled: (() -> Unit)? = null,
        advisoryReceipt: AdvisorySpeechReceipt? = null,
        onAdvisoryRegistered: (() -> Unit)? = null,
        messageFamily: SpeechMessageFamily? = null,
        deferToCurrentSpeech: Boolean = false,
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        if (!speechAllowed()) return NavigationSpeechDispatchResult.UNAVAILABLE
        when (ttsState) {
            TtsState.INITIALIZING -> {
                // UNAVAILABLE means no ownership and no deferred replay. The caller retries
                // only if its screen, request and source observation are still current.
                return NavigationSpeechDispatchResult.UNAVAILABLE
            }
            TtsState.READY -> Unit
            TtsState.FAILED,
            TtsState.LANGUAGE_UNSUPPORTED,
            TtsState.CLOSED,
            -> return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        return speakReady(
            message,
            priority,
            onCompleted,
            onFailed,
            riskRank,
            advisoryStartDeadlineMs,
            advisoryStartValidator,
            requiresExplicitTerminalCallback,
            protectsFromFollowingSpeech,
            onCancelled,
            riskDeliveryOwner,
            onCloseCancelled,
            advisoryReceipt,
            onAdvisoryRegistered,
            messageFamily,
            deferToCurrentSpeech,
        )
    }

    private fun flushPendingSpeech() {
        // Anonymous initialization-time entries have neither a deadline nor an owner.
        pendingSpeechQueue.clear()
    }

    private fun speakReady(
        message: String,
        priority: SpeechPriority,
        onCompleted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        riskRank: Int? = null,
        advisoryStartDeadlineMs: Long? = null,
        advisoryStartValidator: (() -> Boolean)? = null,
        requiresExplicitTerminalCallback: Boolean = false,
        protectsFromFollowingSpeech: Boolean = requiresExplicitTerminalCallback,
        onCancelled: (() -> Unit)? = onFailed,
        riskDeliveryOwner: FeedbackDeliveryOwner? = null,
        onCloseCancelled: (() -> Unit)? = null,
        advisoryReceipt: AdvisorySpeechReceipt? = null,
        onAdvisoryRegistered: (() -> Unit)? = null,
        messageFamily: SpeechMessageFamily? = null,
        deferToCurrentSpeech: Boolean = false,
    ): NavigationSpeechDispatchResult {
        if (!outputGate.isCurrent()) return NavigationSpeechDispatchResult.SUPPRESSED
        // A synchronous native stop callback must not reenter TTS speak before stop returns.
        if (speechEngineStopInProgress && Thread.holdsLock(speechEngineOperationLock)) {
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        val dispatchEngineGeneration = ttsEngineGeneration
        val dispatchOutputGeneration = outputGate.generation()
        fun submissionOwnerIsCurrent(): Boolean =
            ttsState == TtsState.READY &&
                ttsEngineGeneration == dispatchEngineGeneration &&
                outputGate.isCurrent(dispatchOutputGeneration) &&
                (riskDeliveryOwner == null || riskDeliveryOwner.isClaimed())
        val isRisk = priority == SpeechPriority.RISK
        val isInteraction = priority == SpeechPriority.INTERACTION
        val isAdvisory = priority == SpeechPriority.ADVISORY
        val nowMs = elapsedRealtime()
        if (
            (isAdvisory || advisoryStartDeadlineMs != null || advisoryStartValidator != null) &&
            (
                advisoryStartDeadlineMs == null ||
                    advisoryStartValidator == null ||
                    nowMs > advisoryStartDeadlineMs ||
                    !runCatching(advisoryStartValidator).getOrDefault(false)
            )
        ) return NavigationSpeechDispatchResult.SUPPRESSED
        // Only an unstarted, same-rank owner with an actual false start check may yield.
        // Caller checks can reenter or wait for another request, so take and evaluate separately.
        val invalidatedRiskStarts = if (isRisk && riskRank != null) {
            val checks = synchronized(pendingUtterances) {
                pendingUtterances.mapNotNull { id ->
                    if (pendingUtterances[id]?.started == true || pendingUtterances[id]?.riskRank != riskRank) return@mapNotNull null
                    val deadline = pendingUtterances[id]?.startDeadlineMs ?: return@mapNotNull null
                    val validator = pendingUtterances[id]?.startValidator ?: return@mapNotNull null
                    RiskStartCheck(id, deadline, validator)
                }
            }
            checks.filter { runCatching(it.validator).getOrNull() == false }.associateBy(RiskStartCheck::id)
        } else emptyMap()
        if (!submissionOwnerIsCurrent()) return NavigationSpeechDispatchResult.UNAVAILABLE
        val replacedPolicyOwner = riskDeliveryOwner?.replacesDeliveryOwner
        fun authorizedReplacementIdLocked(): String? {
            if (!isRisk || riskDeliveryOwner == null || replacedPolicyOwner == null ||
                !riskDeliveryOwner.isClaimed() || !replacedPolicyOwner.isClaimed() ||
                riskDeliveryOwner.trackId != replacedPolicyOwner.trackId
            ) return null
            return pendingUtterances.singleOrNull { id ->
                val registration = pendingUtterances[id]?.riskDelivery
                pendingUtterances[id]?.riskRank == riskRank && registration != null &&
                    registration.owner === replacedPolicyOwner &&
                    registration.engineGeneration == dispatchEngineGeneration &&
                    registration.outputGeneration == dispatchOutputGeneration
            }
        }
        fun activeRiskRankLocked(): Int? {
            val replacementId = authorizedReplacementIdLocked()
            return pendingUtterances.filterNot { id ->
                val checked = invalidatedRiskStarts[id]
                id == replacementId ||
                    (checked != null && pendingUtterances[id]?.started == false &&
                        pendingUtterances[id]?.startDeadlineMs == checked.deadlineMs &&
                        pendingUtterances[id]?.startValidator === checked.validator)
            }.mapNotNull { pendingUtterances[it]?.riskRank }.maxOrNull()
        }
        val utterancePrefix = when (priority) {
            SpeechPriority.RISK -> ANNOUNCE_ASSERTIVE_PREFIX
            SpeechPriority.INTERACTION -> ANNOUNCE_INTERACTION_PREFIX
            SpeechPriority.NAVIGATION -> ANNOUNCE_NAV_PREFIX
            SpeechPriority.ADVISORY -> ANNOUNCE_ADVISORY_PREFIX
        }
        val utteranceId = "$utterancePrefix-$nowMs-${utteranceSequence.incrementAndGet()}"
        // Select once from the actual caller meaning and keep this budget with its registration.
        val terminalBudgetMs = messageFamily?.let { utteranceTerminalTimeoutMs(message.length, it) }
            ?: utteranceTerminalTimeoutMs(message.length)
        val registerUtterance = {
            markUtteranceStarted(
                utteranceId,
                terminalBudgetMs,
                riskRank,
                onCompleted,
                onFailed,
                advisoryStartDeadlineMs,
                advisoryStartValidator,
                requiresExplicitTerminalCallback,
                protectsFromFollowingSpeech,
                onCancelled,
                riskDeliveryOwner?.let {
                    RiskDeliveryRegistration(it, dispatchEngineGeneration, dispatchOutputGeneration)
                },
                onCloseCancelled,
                advisoryReceipt?.let {
                    AdvisoryDeliveryRegistration(it, dispatchEngineGeneration, dispatchOutputGeneration)
                },
            )
        }
        var queueMode = TextToSpeech.QUEUE_ADD
        val cancellationEffects = synchronized(speechEngineOperationLock) {
            // Only admission/native effects take this lock. Never call outputGate, a caller
            // validator, focus effects or terminal callbacks while holding it.
            if (!speechEngineOwnerIsCurrent(dispatchEngineGeneration)) {
                return NavigationSpeechDispatchResult.UNAVAILABLE
            }
            if (speechEngineStopInProgress) return NavigationSpeechDispatchResult.UNAVAILABLE
            // Automatic status and nonurgent caution must never flush a sentence already owned
            // by navigation, a command or a risk. Their producer retains the current retry.
            if ((deferToCurrentSpeech || (isRisk && riskRank == MessageLevel.CAUTION.ordinal)) &&
                synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }
            ) return NavigationSpeechDispatchResult.SUPPRESSED
            val strictTrainingUtterance = synchronized(pendingUtterances) {
                utteranceCallbacks.protectedUtteranceIds().firstOrNull()
            }
            if (strictTrainingUtterance != null) {
                if (!isRisk) return NavigationSpeechDispatchResult.SUPPRESSED
            }
            if (isRisk) {
                val activeRiskRank = synchronized(pendingUtterances) {
                    if (replacedPolicyOwner != null && authorizedReplacementIdLocked() == null) {
                        return NavigationSpeechDispatchResult.SUPPRESSED
                    }
                    activeRiskRankLocked()
                }
                if (activeRiskRank != null && (riskRank ?: activeRiskRank) <= activeRiskRank) {
                    return NavigationSpeechDispatchResult.SUPPRESSED
                }
            }
            val messagePriorityMs = when (priority) {
                SpeechPriority.RISK -> RISK_DUP_WINDOW_MS
                SpeechPriority.INTERACTION -> INTERACTION_DUP_WINDOW_MS
                SpeechPriority.NAVIGATION -> NAV_DUP_WINDOW_MS
                SpeechPriority.ADVISORY -> 0L
            }
            if (!isAdvisory && isDuplicateMessage(message, priority, messagePriorityMs)) {
                return NavigationSpeechDispatchResult.SUPPRESSED
            }
            if (isAdvisory && synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }) {
                return NavigationSpeechDispatchResult.SUPPRESSED
            }
            val riskPending = synchronized(pendingUtterances) {
                pendingUtterances.any { it.startsWith("$ANNOUNCE_ASSERTIVE_PREFIX-") }
            }
            if (isInteraction && riskPending) return NavigationSpeechDispatchResult.SUPPRESSED
            var navigationReplacesAdvisory = false
            if (priority == SpeechPriority.NAVIGATION) {
                val pendingIds = synchronized(pendingUtterances) { pendingUtterances.toList() }
                if (pendingIds.any { !it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }) {
                    return NavigationSpeechDispatchResult.SUPPRESSED
                }
                navigationReplacesAdvisory = pendingIds.isNotEmpty()
            }
            queueMode = when (priority) {
                SpeechPriority.RISK -> TextToSpeech.QUEUE_FLUSH
                SpeechPriority.INTERACTION -> if (riskPending) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
                SpeechPriority.NAVIGATION -> TextToSpeech.QUEUE_ADD
                SpeechPriority.ADVISORY -> TextToSpeech.QUEUE_ADD
            }
            val effects = if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                logSpeechEvent("queue_flush priority=$priority")
                // Publish every replacement before old owners can reenter from cancellation.
                synchronized(pendingUtterances) {
                    // onStart publishes under this lock, independently of the engine lock.
                    // Recheck and retire atomically so a newly started owner cannot yield.
                    if (isRisk) {
                        if (replacedPolicyOwner != null && authorizedReplacementIdLocked() == null) {
                            return NavigationSpeechDispatchResult.SUPPRESSED
                        }
                        val activeRiskRank = activeRiskRankLocked()
                        if (activeRiskRank != null && (riskRank ?: activeRiskRank) <= activeRiskRank) {
                            return NavigationSpeechDispatchResult.SUPPRESSED
                        }
                        // An issued relationship is consumed with exact owner/rank/generation
                        // checks and registration. This receipt calls no policy/caller code.
                        if (replacedPolicyOwner != null && riskDeliveryOwner?.markReplacementAdmitted() != true) {
                            return NavigationSpeechDispatchResult.SUPPRESSED
                        }
                    }
                    cancelQueuedCompletionCallbacks(keepRisk = false, replacement = registerUtterance)
                }
            } else if (navigationReplacesAdvisory) {
                val cancelled = cancelQueuedCompletionCallbacks(keepRisk = false)
                registerUtterance()
                cancelled
            } else {
                registerUtterance()
                null
            }
            if (navigationReplacesAdvisory) stopSpeechEngine()
            effects
        }
        // Reuse the request timestamp already bound to registration; notify outside both locks.
        // Its reentry may retire this owner; the existing submission fences below must still win.
        emitSpeechOutcome(SpeechOutputOutcome.REQUESTED, utteranceId, atElapsedRealtimeMs = nowMs)
        // Receipt publication follows REQUESTED, including when its observer retired the request.
        if (onAdvisoryRegistered != null) {
            runCatching(onAdvisoryRegistered)
                .onFailure { logSpeechEvent("advisory_registration_callback_error", utteranceId) }
        }
        cancellationEffects?.invoke()
        // A callback may close, replace the engine, or publish a higher-priority owner.
        if (!submissionOwnerIsCurrent()) {
            finishUnsubmittedUtterance(utteranceId, queueMode, dispatchEngineGeneration,
                dispatchOutputGeneration, riskDeliveryOwner)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        if (synchronized(pendingUtterances) { utteranceId !in pendingUtterances }) {
            return NavigationSpeechDispatchResult.SUPPRESSED
        }
        if (!requestAudioFocus(isRisk)) {
            emitSpeechOutcome(SpeechOutputOutcome.FOCUS_DENIED, utteranceId)
            finishUnsubmittedUtterance(utteranceId, queueMode, dispatchEngineGeneration,
                dispatchOutputGeneration, riskDeliveryOwner,
                stopCurrentRequest = submissionOwnerIsCurrent())
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        // Focus acquisition/cancellation can invalidate a request after it was registered.
        // The same registered validator is checked again when the engine releases its queue.
        if (!isUtteranceStillValidAtStart(utteranceId)) return NavigationSpeechDispatchResult.SUPPRESSED
        val speakResult: Int? = try {
            synchronized(speechEngineOperationLock) {
                if (speechEngineStopInProgress || !speechEngineOwnerIsCurrent(dispatchEngineGeneration) ||
                    synchronized(pendingUtterances) {
                        utteranceId !in pendingUtterances ||
                            (riskDeliveryOwner != null && !riskDeliveryOwner.isClaimed())
                    }
                ) {
                    null
                } else {
                    speechRuntime.speak(message, queueMode, utteranceId)
                }
            }
        } catch (_: RuntimeException) {
            // A rejected synchronous call belongs to the caller, not a second async failure.
            failRequiredSpeechRuntime(utteranceId, notifyFailure = false)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        if (speakResult == null) {
            finishUnsubmittedUtterance(utteranceId, queueMode, dispatchEngineGeneration,
                dispatchOutputGeneration, riskDeliveryOwner)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        if (speakResult == TextToSpeech.ERROR) {
            synchronized(speechEngineOperationLock) {
                val stillOwned = speechEngineOwnerIsCurrent(dispatchEngineGeneration) &&
                    synchronized(pendingUtterances) { utteranceId in pendingUtterances }
                if (stillOwned) {
                    if (queueMode == TextToSpeech.QUEUE_FLUSH) stopSpeechEngine()
                }
            }
            failRequiredSpeechRuntime(utteranceId, notifyFailure = false)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        val stillOwned = synchronized(pendingUtterances) {
            if (utteranceId !in pendingUtterances) {
                false
            } else {
                if (isRisk) {
                    lastRiskMessage = message
                    lastRiskMessageAtMs = nowMs
                } else if (isInteraction) {
                    lastInteractionMessage = message
                    lastInteractionMessageAtMs = nowMs
                } else if (!isAdvisory) {
                    lastNavMessage = message
                    lastNavMessageAtMs = nowMs
                }
                duplicateOwnerByPriority[utterancePrefix] = utteranceId
                true
            }
        }
        return if (stillOwned) NavigationSpeechDispatchResult.ACCEPTED
        else NavigationSpeechDispatchResult.SUPPRESSED
    }

    private fun finishUnsubmittedUtterance(
        utteranceId: String,
        queueMode: Int,
        engineGeneration: Long,
        outputGeneration: Long,
        riskDeliveryOwner: FeedbackDeliveryOwner?,
        stopCurrentRequest: Boolean = false,
    ) {
        // Admission already cancelled the old owner, but QUEUE_FLUSH has not yet run.
        // A retired replacement must stop that old native output, never a reentrant winner.
        val outputCurrent = outputGate.isCurrent(outputGeneration)
        val retiredReplacement = synchronized(speechEngineOperationLock) {
            val retired = riskDeliveryOwner != null && !riskDeliveryOwner.isClaimed()
            val stillOwnsStop = outputCurrent && !speechEngineStopInProgress &&
                speechEngineOwnerIsCurrent(engineGeneration) && synchronized(pendingUtterances) {
                    val registration = pendingUtterances[utteranceId]?.riskDelivery
                    pendingUtterances.size == 1 && utteranceId in pendingUtterances &&
                        (riskDeliveryOwner == null ||
                            (registration != null && registration.owner === riskDeliveryOwner &&
                                registration.engineGeneration == engineGeneration &&
                                registration.outputGeneration == outputGeneration))
                }
            if (queueMode == TextToSpeech.QUEUE_FLUSH && stillOwnsStop && (stopCurrentRequest || retired)) {
                stopSpeechEngine()
            }
            retired
        }
        // Watchdog/focus effects and the one terminal consumer can reenter or join another request.
        markUtteranceFinished(utteranceId, completed = false, notifyFailure = false,
            cancelled = retiredReplacement)
    }

    private fun vibrate(
        patternMs: LongArray?,
        cancelPriorityUserTraining: Boolean = true,
        rank: Int = 0,
        validUntilMs: Long = Long.MAX_VALUE,
        isStillValid: () -> Boolean = { true },
        generation: Long = outputGate.generation(),
        dedupe: Boolean = true,
        eventId: String? = null,
    ): Boolean {
        if (!hapticAllowed()) return false
        var interruptedTraining: Pair<Runnable?, (() -> Unit)?>? = null
        val accepted = outputGate.vibrate(patternMs, rank, generation, validUntilMs, isStillValid,
            eventId = eventId) { pattern ->
            val nowMs = elapsedRealtime()
            if (dedupe && pattern.contentEquals(lastVibrationPattern ?: longArrayOf()) &&
                nowMs - lastVibrationAtMs < VIBRATION_DUP_WINDOW_MS
            ) return@vibrate false
            if (!speechRuntime.vibrate(pattern.copyOf())) return@vibrate false
            if (cancelPriorityUserTraining) {
                interruptedTraining = takePriorityUserTrainingVibration()
            }
            lastVibrationPattern = pattern.copyOf()
            lastVibrationAtMs = nowMs
            true
        }
        // A training failure may submit speech on another thread and wait for its result.
        // Retire its ownership with the haptic effect, then notify outside the output gate.
        interruptedTraining?.first?.let(mainHandler::removeCallbacks)
        interruptedTraining?.second?.invoke()
        return accepted
    }

    private fun takePriorityUserTrainingVibration(): Pair<Runnable?, (() -> Unit)?> =
        synchronized(priorityUserTrainingVibrationLock) {
            val completion = priorityUserTrainingVibrationCompletion
            val failure = priorityUserTrainingVibrationFailure
            priorityUserTrainingVibrationCompletion = null
            priorityUserTrainingVibrationFailure = null
            completion to failure
        }

    private fun cancelPriorityUserTrainingVibration(notifyFailure: Boolean) {
        val pending = takePriorityUserTrainingVibration()
        pending.first?.let(mainHandler::removeCallbacks)
        if (notifyFailure) pending.second?.invoke()
    }

    private fun isDuplicateMessage(
        message: String,
        priority: SpeechPriority,
        cooldownMs: Long,
    ): Boolean {
        val nowMs = elapsedRealtime()
        return synchronized(pendingUtterances) {
            when (priority) {
                SpeechPriority.RISK -> message == lastRiskMessage && nowMs - lastRiskMessageAtMs < cooldownMs
                SpeechPriority.INTERACTION ->
                    message == lastInteractionMessage && nowMs - lastInteractionMessageAtMs < cooldownMs
                SpeechPriority.NAVIGATION -> message == lastNavMessage && nowMs - lastNavMessageAtMs < cooldownMs
                SpeechPriority.ADVISORY -> false
            }
        }
    }

    private fun clearDuplicateSuppressionOwnedBy(utteranceId: String) {
        val prefix = utteranceId.substringBefore('-')
        if (duplicateOwnerByPriority[prefix] != utteranceId) return
        duplicateOwnerByPriority.remove(prefix)
        when (prefix) {
            ANNOUNCE_ASSERTIVE_PREFIX -> {
                lastRiskMessage = ""
                lastRiskMessageAtMs = 0L
            }
            ANNOUNCE_NAV_PREFIX -> {
                lastNavMessage = ""
                lastNavMessageAtMs = 0L
            }
            ANNOUNCE_INTERACTION_PREFIX -> {
                lastInteractionMessage = ""
                lastInteractionMessageAtMs = 0L
            }
        }
    }

    private fun requestAudioFocus(isRisk: Boolean): Boolean = requestSpeechAudioFocus(isRisk)

    private fun requestSpeechAudioFocus(isRisk: Boolean): Boolean {
        val outputGeneration = outputGate.generation()
        if (!outputGate.isCurrent(outputGeneration)) return false
        val requestedGain = if (isRisk) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val (generation, previousRegistration) = synchronized(audioFocusLock) {
            val currentFocusSatisfiesRequest = hasAudioFocus &&
                (activeAudioFocus == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT || activeAudioFocus == requestedGain)
            if (currentFocusSatisfiesRequest) return true
            val previous = takeAudioFocusRegistration()
            // Reserve before any platform effect: a nested request must receive a different token.
            val reservedGeneration = ++audioFocusGeneration
            reservedGeneration to previous
        }
        previousRegistration?.let { releaseAudioFocusRegistration(it) }
        if (!isAudioFocusRequestCurrent(generation, outputGeneration)) return false
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleAudioFocusChange(focusChange, generation)
        }
        val requested = try {
            speechRuntime.requestAudioFocus(isRisk, listener)
        } catch (_: RuntimeException) {
            // The effect may have submitted B before throwing. Never abandon B's registration.
            releaseAudioFocusRegistration(listener)
            return false
        }
        if (!requested) return false
        val outputCurrent = outputGate.isCurrent(outputGeneration)
        val accepted = synchronized(audioFocusLock) {
            if (!outputCurrent || audioFocusGeneration != generation) {
                false
            } else {
                hasAudioFocus = true
                activeAudioFocus = requestedGain
                activeAudioFocusListener = listener
                true
            }
        }
        if (!accepted) releaseAudioFocusRegistration(listener)
        return accepted
    }

    private fun isAudioFocusRequestCurrent(generation: Long, outputGeneration: Long): Boolean =
        outputGate.isCurrent(outputGeneration) && synchronized(audioFocusLock) {
            audioFocusGeneration == generation
        }

    private fun handleAudioFocusChange(focusChange: Int, generation: Long) {
        if (
            focusChange != AudioManager.AUDIOFOCUS_LOSS &&
            focusChange != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT &&
            focusChange != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) return
        val releasedRegistration: AudioManager.OnAudioFocusChangeListener
        val interrupted: List<String>
        val toneGeneration: Int
        synchronized(speechEngineOperationLock) {
            val registration = synchronized(audioFocusLock) {
                if (!hasAudioFocus || audioFocusGeneration != generation) {
                    null
                } else {
                    takeAudioFocusRegistration()
                }
            } ?: return
            releasedRegistration = registration
            synchronized(pendingUtterances) {
                interrupted = pendingUtterances.toList()
                toneGeneration = ++progressToneGeneration
            }
        }
        logSpeechEvent("audio_focus_lost change=$focusChange")
        emitSpeechOutcome(SpeechOutputOutcome.FOCUS_LOST, utteranceId = null)
        releaseAudioFocusRegistration(releasedRegistration)
        mainHandler.post {
            val tone = synchronized(pendingUtterances) {
                if (toneGeneration == progressToneGeneration) progressTone else null
            }
            stopProgressToneSafely { tone?.stopTone() }
            val replacementFocusActive = synchronized(audioFocusLock) { hasAudioFocus }
            failPendingSpeech(interrupted, stopTts = !replacementFocusActive)
        }
    }

    private fun abandonAudioFocus() {
        val registration = retireAudioFocusRegistration() ?: return
        releaseAudioFocusRegistration(registration)
    }

    /** Detach ownership now; release only this registration after dropping state locks. */
    private fun retireAudioFocusRegistration(): AudioManager.OnAudioFocusChangeListener? =
        synchronized(audioFocusLock) {
            if (hasAudioFocus) {
                takeAudioFocusRegistration()
            } else {
                // Explicit cancellation also retires a request whose grant has not returned.
                audioFocusGeneration += 1L
                null
            }
        }

    private fun takeAudioFocusRegistration(
        expectedGeneration: Long? = null,
        expectedGain: Int? = null,
    ): AudioManager.OnAudioFocusChangeListener? =
        synchronized(audioFocusLock) {
            if (!hasAudioFocus ||
                (expectedGeneration != null && audioFocusGeneration != expectedGeneration) ||
                (expectedGain != null && activeAudioFocus != expectedGain)
            ) return@synchronized null
            val current = activeAudioFocusListener
            hasAudioFocus = false
            activeAudioFocus = AudioManager.AUDIOFOCUS_LOSS
            activeAudioFocusListener = null
            audioFocusGeneration += 1L
            current
        }

    private fun releaseAudioFocusRegistration(listener: AudioManager.OnAudioFocusChangeListener) {
        try {
            speechRuntime.abandonAudioFocus(listener)
        } catch (_: RuntimeException) {
            // Ownership is already retired; failure must not skip terminal callbacks or close.
            logSpeechEvent("audio_focus_release_error")
        }
    }

    private fun markUtteranceStarted(
        utteranceId: String,
        terminalBudgetMs: Long,
        riskRank: Int?,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        startDeadlineMs: Long?,
        startValidator: (() -> Boolean)?,
        requiresExplicitTerminalCallback: Boolean,
        protectsFromFollowingSpeech: Boolean,
        onCancelled: (() -> Unit)?,
        riskDelivery: RiskDeliveryRegistration?,
        onCloseCancelled: (() -> Unit)?,
        advisoryDelivery: AdvisoryDeliveryRegistration?,
    ) {
        synchronized(pendingUtterances) {
            pendingUtterances.reserve(UtteranceRecord(
                id = utteranceId,
                terminalBudgetMs = terminalBudgetMs,
                riskRank = riskRank,
                riskDelivery = riskDelivery,
                advisoryDelivery = advisoryDelivery,
                startDeadlineMs = startDeadlineMs,
                startValidator = startValidator,
                requiresExplicitTerminalCallback = requiresExplicitTerminalCallback,
            ))
            utteranceCallbacks.register(utteranceId, onCompleted, onFailed, protectsFromFollowingSpeech,
                onCancelled, onCloseCancelled)
            armNextUtteranceStartWatchdogLocked()
        }
    }

    private fun isUtteranceStillValidAtStart(utteranceId: String?): Boolean {
        if (utteranceId == null) return false
        val deadlineAndValidator = synchronized(pendingUtterances) {
            if (utteranceId !in pendingUtterances) return false
            val deadline = pendingUtterances[utteranceId]?.startDeadlineMs ?: return@synchronized null
            val validator = pendingUtterances[utteranceId]?.startValidator ?: return@synchronized null
            deadline to validator
        } ?: return true
        val isValid = speechStartIsCurrent(
            elapsedRealtime(), deadlineAndValidator.first, deadlineAndValidator.second,
        )
        if (isValid) return true
        val shouldStop = synchronized(speechEngineOperationLock) {
            val stillOwned = synchronized(pendingUtterances) {
                utteranceId in pendingUtterances &&
                    pendingUtterances[utteranceId]?.startValidator === deadlineAndValidator.second
            }
            if (stillOwned && ttsState == TtsState.READY) stopSpeechEngine()
            stillOwned
        }
        if (shouldStop) {
            emitSpeechOutcome(SpeechOutputOutcome.EXPIRED_BEFORE_START, utteranceId)
            markUtteranceFinished(utteranceId, completed = false, cancelled = true)
        }
        return false
    }

    private fun armUtteranceTerminalWatchdog(utteranceId: String?) {
        if (utteranceId == null) return
        val inferredWatchdogs = mutableListOf<Runnable>()
        val inferredTerminalCallbacks = mutableListOf<() -> Unit>()
        synchronized(pendingUtterances) {
            if (utteranceId !in pendingUtterances) return
            val predecessors = pendingUtterances.detachPredecessors(utteranceId)
            predecessors.forEach { record ->
                val predecessor = record.id
                clearDuplicateSuppressionOwnedBy(predecessor)
                record.watchdog?.let(inferredWatchdogs::add)
                utteranceCallbacks.takeTerminalCallback(
                    utteranceId = predecessor,
                    completed = false,
                    notifyFailure = true,
                )?.let(inferredTerminalCallbacks::add)
            }
            val started = pendingUtterances.markStarted(utteranceId) ?: return
            scheduleUtteranceWatchdogLocked(
                utteranceId,
                started.terminalBudgetMs,
            )
        }
        inferredWatchdogs.forEach(mainHandler::removeCallbacks)
        inferredTerminalCallbacks.forEach { it() }
    }

    private fun scheduleUtteranceWatchdogLocked(utteranceId: String, delayMs: Long) {
        pendingUtterances.replaceWatchdog(utteranceId, null)?.let(mainHandler::removeCallbacks)
        val engineGeneration = ttsEngineGeneration
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            val started = synchronized(pendingUtterances) {
                if (pendingUtterances[utteranceId]?.watchdog !== watchdog ||
                    utteranceId !in pendingUtterances ||
                    engineGeneration != ttsEngineGeneration || ttsState != TtsState.READY
                ) return@Runnable
                pendingUtterances[utteranceId]?.started == true
            }
            // Expiry/revocation before start is cancellation, not evidence of engine failure.
            // After onStart, this is the terminal watchdog: start TTL no longer revokes completion.
            if (!started && !isUtteranceStillValidAtStart(utteranceId)) return@Runnable
            logSpeechEvent("watchdog_timeout", utteranceId)
            // Keep pending ownership until the existing error classifier has classified it.
            failRequiredSpeechRuntime(utteranceId, outcome = SpeechOutputOutcome.TERMINAL_TIMEOUT)
        }
        pendingUtterances.replaceWatchdog(utteranceId, watchdog)
        mainHandler.postDelayed(watchdog, delayMs)
    }

    private fun armNextUtteranceStartWatchdogLocked() {
        val record = pendingUtterances.firstRecord() ?: return
        if (record.started || record.watchdog != null) return
        val next = record.id
        val nowMs = elapsedRealtime()
        val deadlineDelayMs = pendingUtterances[next]?.startDeadlineMs?.let { deadline ->
            // Source validity is inclusive. At the deadline itself a request is still valid.
            if (deadline < nowMs) 0L else
                (deadline - nowMs).coerceAtMost(UTTERANCE_START_TIMEOUT_MS) + 1L
        }
        scheduleUtteranceWatchdogLocked(
            next,
            deadlineDelayMs?.coerceAtMost(UTTERANCE_START_TIMEOUT_MS) ?: UTTERANCE_START_TIMEOUT_MS,
        )
    }

    private fun markUtteranceFinished(
        utteranceId: String?,
        completed: Boolean,
        notifyFailure: Boolean = true,
        cancelled: Boolean = false,
    ) {
        takeUtteranceFinishEffects(utteranceId, completed, notifyFailure, cancelled)()
    }

    /** Detaches once under the registry lock; platform effects and consumers run after admission. */
    private fun takeUtteranceFinishEffects(
        utteranceId: String?,
        completed: Boolean,
        notifyFailure: Boolean = true,
        cancelled: Boolean = false,
    ): () -> Unit {
        var terminalCallback: (() -> Unit)? = null
        var watchdog: Runnable? = null
        var focusRegistration: AudioManager.OnAudioFocusChangeListener? = null
        synchronized(pendingUtterances) {
            if (utteranceId != null) {
                val removed = pendingUtterances.detach(utteranceId)
                if (removed != null) {
                    if (!completed) clearDuplicateSuppressionOwnedBy(utteranceId)
                    watchdog = removed.watchdog
                    terminalCallback = utteranceCallbacks.takeTerminalCallback(
                        utteranceId = utteranceId,
                        completed = completed,
                        notifyFailure = notifyFailure,
                        cancelled = cancelled,
                    )
                    if (pendingUtterances.isEmpty()) {
                        speechRecognitionBlockedUntilMs = elapsedRealtime() + SPEECH_RECOGNITION_QUIET_MS
                        focusRegistration = retireAudioFocusRegistration()
                    }
                    armNextUtteranceStartWatchdogLocked()
                }
            }
        }
        return {
            watchdog?.let(mainHandler::removeCallbacks)
            focusRegistration?.let { releaseAudioFocusRegistration(it) }
            runCatching { terminalCallback?.invoke() }
                .onFailure { logSpeechEvent("terminal_callback_error", utteranceId) }
        }
    }

    private fun failPendingSpeech(
        interrupted: List<String> = synchronized(pendingUtterances) { pendingUtterances.toList() },
        stopTts: Boolean = true,
        cancelled: Boolean = false,
    ) {
        if (interrupted.isEmpty()) return
        if (stopTts) {
            synchronized(speechEngineOperationLock) {
                val stillOwnsStop = synchronized(pendingUtterances) {
                    ttsState == TtsState.READY &&
                        interrupted.any { it in pendingUtterances } &&
                        pendingUtterances.all { it in interrupted }
                }
                if (stillOwnsStop) stopSpeechEngine()
            }
        }
        interrupted.forEach { markUtteranceFinished(it, completed = false, cancelled = cancelled) }
    }

    private fun emitSpeechOutcome(
        outcome: SpeechOutputOutcome,
        utteranceId: String?,
        errorCode: Int? = null,
        atElapsedRealtimeMs: Long? = null,
    ) {
        if (speechOutputListener == null && guidanceDiagnosticListener == null) return
        val event = SpeechOutputEvent(speechOutputKindOf(utteranceId), outcome,
            atElapsedRealtimeMs ?: elapsedRealtime(), errorCode)
        runCatching { speechOutputListener?.invoke(event) }
        runCatching { guidanceDiagnosticListener?.invoke(event) }
    }

    private fun logSpeechEvent(event: String, utteranceId: String? = null) {
        speechRuntime.logSpeechEvent(event, utteranceId)
    }

    private fun cancelQueuedCompletionCallbacks(
        keepRisk: Boolean,
        replacement: (() -> Unit)? = null,
    ): () -> Unit {
        var watchdogs: List<Runnable> = emptyList()
        var advisoryFailures: List<() -> Unit> = emptyList()
        var strictTrainingFailures: List<() -> Unit> = emptyList()
        var cancelledIds: List<String> = emptyList()
        synchronized(pendingUtterances) {
            val cancelled = pendingUtterances.filter { utteranceId ->
                !keepRisk || !utteranceId.startsWith("$ANNOUNCE_ASSERTIVE_PREFIX-")
            }
            cancelledIds = cancelled
            if (cancelled.isNotEmpty()) {
                speechRecognitionBlockedUntilMs = elapsedRealtime() + SPEECH_RECOGNITION_QUIET_MS
            }
            advisoryFailures = cancelled
                .filter { it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true) }
            strictTrainingFailures = cancelled
                .filter { pendingUtterances[it]?.requiresExplicitTerminalCallback == true }
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true) }
            cancelled.forEach(::clearDuplicateSuppressionOwnedBy)
            watchdogs = cancelled.mapNotNull { pendingUtterances.detach(it)?.watchdog }
            utteranceCallbacks.cancel(cancelled)
            replacement?.invoke()
            armNextUtteranceStartWatchdogLocked()
        }
        return {
            // Both callers preempt queued speech for a higher-priority or replacing output.
            cancelledIds.forEach { emitSpeechOutcome(SpeechOutputOutcome.CANCELLED_BY_PRIORITY, it) }
            watchdogs.forEach(mainHandler::removeCallbacks)
            advisoryFailures.forEach { callback ->
                runCatching { callback() }
                    .onFailure { logSpeechEvent("terminal_callback_error") }
            }
            strictTrainingFailures.forEach { callback ->
                runCatching { callback() }
                    .onFailure { logSpeechEvent("terminal_callback_error") }
            }
        }
    }

    override fun close() {
        outputGate.close()
        val watchdogs: List<Runnable>
        val tone: ProgressToneEffects?
        val closeCancellations: List<() -> Unit>
        synchronized(speechEngineOperationLock) {
            if (ttsState == TtsState.CLOSED) return
            synchronized(audioFocusLock) { audioFocusGeneration += 1L }
            logSpeechEvent("engine_closed", null)
            ready.set(false)
            ttsState = TtsState.CLOSED
            ttsEngineGeneration += 1L
            pendingSpeechQueue.clear()
            synchronized(pendingUtterances) {
                closeCancellations = utteranceCallbacks.takeCloseCancellationCallbacks(pendingUtterances)
                watchdogs = pendingUtterances.drain().mapNotNull { it.watchdog }
                utteranceCallbacks.clear()
                progressToneGeneration += 1
                tone = detachProgressToneLocked()?.first
            }
            // Native speak either finishes before this retirement or observes CLOSED afterward.
            if (isSpeechEngineInitialized()) {
                stopSpeechEngine()
                shutdownSpeechEngine()
            }
        }
        cancelPriorityUserTrainingVibration(notifyFailure = false)
        watchdogs.forEach(mainHandler::removeCallbacks)
        mainHandler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        releaseProgressTone(tone)
        if (!speechUnavailableVibrationRetained) {
            runCatching { speechRuntime.cancelVibration() }
        }
        if (closeCancellations.isNotEmpty()) mainHandler.post {
            // Defer even native-effect reentry: its outer engine operation has not returned yet.
            closeCancellations.forEach { callback ->
                runCatching(callback).onFailure { logSpeechEvent("terminal_callback_error") }
            }
        }
    }

    private fun elapsedRealtime(): Long =
        speechRuntime.elapsedRealtime()

    private fun isSpeechEngineInitialized(): Boolean =
        speechRuntime.engineInitialized

    private fun speechEngineOwnerIsCurrent(engineGeneration: Long): Boolean =
        ttsState == TtsState.READY && ttsEngineGeneration == engineGeneration

    private fun stopSpeechEngine() = synchronized(speechEngineOperationLock) {
        val previousStopInProgress = speechEngineStopInProgress
        speechEngineStopInProgress = true
        try {
            speechRuntime.stop()
        } catch (_: RuntimeException) {
            // Cancellation remains cancellation; a stop exception cannot trigger fallback speech.
            logSpeechEvent("engine_stop_error")
        } finally {
            speechEngineStopInProgress = previousStopInProgress
        }
    }

    private fun shutdownSpeechEngine() {
        try {
            speechRuntime.shutdown()
        } catch (_: RuntimeException) {
            logSpeechEvent("engine_shutdown_error")
        }
    }

    private fun configureSpeechAttributes(): Boolean {
        try {
            speechRuntime.configureAudioAttributes()
            return true
        } catch (_: RuntimeException) {
            recoverTextToSpeechOrFail()
            return false
        }
    }

    private fun installSpeechListener(listener: FeedbackSpeechRuntime.Listener): Int = try {
        speechRuntime.setListener(listener)
    } catch (_: RuntimeException) {
        TextToSpeech.ERROR
    }

    private enum class TtsState {
        INITIALIZING,
        READY,
        FAILED,
        LANGUAGE_UNSUPPORTED,
        CLOSED,
    }
}

internal fun speechStartIsCurrent(
    nowMs: Long,
    deadlineMs: Long = Long.MAX_VALUE,
    validator: () -> Boolean = { true },
): Boolean = nowMs <= deadlineMs && runCatching(validator).getOrDefault(false)

/** Platform effects only; no queue, ownership or recovery policy is duplicated here. */
internal interface FeedbackSpeechRuntime {
    val scheduler: FeedbackSpeechScheduler
    val engineInitialized: Boolean
    fun elapsedRealtime(): Long
    fun initialize(onInitialized: (Int) -> Unit)
    fun setLanguage(language: Locale): Int
    fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection
    fun configureAudioAttributes()
    fun setListener(listener: Listener): Int
    fun speak(message: String, queueMode: Int, utteranceId: String): Int
    fun stop()
    fun shutdown()
    fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean
    fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener)
    fun createProgressTone(volumePercent: Int): ProgressToneEffects
    /** Haptic effect only; lease, rank and duplicate policy stay in the actuator and output gate. */
    fun vibrate(patternMs: LongArray): Boolean = false
    fun cancelVibration() = Unit
    fun logSpeechEvent(event: String, utteranceId: String?) = Unit

    interface Listener {
        fun onStart(utteranceId: String?)
        fun onDone(utteranceId: String?)
        fun onError(utteranceId: String?)
        fun onError(utteranceId: String?, errorCode: Int)
        fun onStop(utteranceId: String?, interrupted: Boolean)
    }
}

internal interface ProgressToneEffects {
    fun startTone(toneType: Int, durationMs: Int): Boolean
    fun stopTone()
    fun release()
}

internal interface FeedbackSpeechScheduler {
    fun post(task: Runnable): Boolean
    fun postDelayed(task: Runnable, delayMs: Long): Boolean
    fun removeCallbacks(task: Runnable)
    fun removeCallbacksAndMessages(token: Any?)
}

internal enum class TtsRuntimeFailureDisposition {
    IGNORE,
    UTTERANCE,
    ENGINE,
    LANGUAGE,
}

internal fun ttsRuntimeFailureDisposition(
    errorCode: Int,
    utterancePending: Boolean,
    currentEngine: Boolean,
): TtsRuntimeFailureDisposition {
    if (!utterancePending || !currentEngine) return TtsRuntimeFailureDisposition.IGNORE
    return when (errorCode) {
        TextToSpeech.ERROR_SERVICE -> TtsRuntimeFailureDisposition.ENGINE
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> TtsRuntimeFailureDisposition.LANGUAGE
        else -> TtsRuntimeFailureDisposition.UTTERANCE
    }
}

/**
 * S13-12 / FP-027-DETAIL-03: retry count and time window are fixed only by the slowest-supported-
 * device integration test. Until then the interim rule applies: the first engine failure is final
 * (no silent re-initialization). The count stays a parameter so a measured value can replace the
 * interim without another failure path; tests pass one retry to keep the replacement-engine checks.
 */
internal class TtsEngineRecoveryBudget(automaticRetries: Int = INTERIM_AUTOMATIC_RETRIES) {
    private var remainingRetries = automaticRetries

    init {
        require(automaticRetries >= 0)
    }

    fun claimRetry(): Boolean {
        if (remainingRetries <= 0) return false
        remainingRetries -= 1
        return true
    }

    companion object {
        const val INTERIM_AUTOMATIC_RETRIES = 0
    }
}
