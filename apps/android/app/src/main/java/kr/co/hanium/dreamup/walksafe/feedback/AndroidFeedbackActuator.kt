package kr.co.hanium.dreamup.walksafe.feedback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.selectInstalledOfflineKoreanVoice
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
 */
class AndroidFeedbackActuator private constructor(
    context: Context?,
    private val onOfflineKoreanSpeechUnavailable: () -> Unit = {},
    private val speechAllowed: () -> Boolean = { true },
    private val hapticAllowed: () -> Boolean = { true },
    private val onOfflineKoreanSpeechReady: () -> Unit = {},
    private val speechRuntime: FeedbackSpeechRuntime?,
    private val ttsRecoveryBudget: TtsEngineRecoveryBudget = TtsEngineRecoveryBudget(),
) : TextToSpeech.OnInitListener, Closeable {
    constructor(
        context: Context,
        onOfflineKoreanSpeechUnavailable: () -> Unit = {},
        speechAllowed: () -> Boolean = { true },
        hapticAllowed: () -> Boolean = { true },
        onOfflineKoreanSpeechReady: () -> Unit = {},
    ) : this(
        context, onOfflineKoreanSpeechUnavailable, speechAllowed, hapticAllowed,
        onOfflineKoreanSpeechReady, null,
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
        null, onOfflineKoreanSpeechUnavailable, speechAllowed, hapticAllowed,
        onOfflineKoreanSpeechReady, runtime, recoveryBudget,
    )

    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    private val ready = AtomicBoolean(false)
    private val pendingSpeechQueue = PendingSpeechQueue()
    private val speechUnavailableNotified = AtomicBoolean(false)
    @Volatile
    private var speechUnavailableVibrationRetained = false
    @Volatile
    private var ttsState = TtsState.INITIALIZING
    private val mainHandler = speechRuntime?.scheduler ?: AndroidFeedbackSpeechScheduler()
    private val outputGate = FeedbackOutputGate(::elapsedRealtime)
    private lateinit var textToSpeech: TextToSpeech
    private var ttsEngineGeneration = 0L
    private var ttsInitializationWatchdog: Runnable? = null
    private val vibrator: Vibrator? = if (appContext == null) {
        null
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audioFocusLock = Any()
    private var hasAudioFocus = false
    private var activeAudioFocus = AudioManager.AUDIOFOCUS_LOSS
    private var activeAudioFocusRequest: AudioFocusRequest? = null
    private var activeAudioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioFocusGeneration = 0L
    private val pendingUtterances = mutableSetOf<String>()
    private val speechEngineOperationLock = Any()
    @Volatile
    private var priorityTrainingStopInProgress = false
    private var speechRecognitionBlockedUntilMs = 0L
    private var speechRecognitionWaitLogged = false
    private val pendingUtteranceOrder = mutableListOf<String>()
    private val startedUtterances = mutableSetOf<String>()
    private val utteranceCallbacks = UtteranceCallbackRegistry()
    private val utteranceWatchdogs = mutableMapOf<String, Runnable>()
    private val utteranceMessageLengths = mutableMapOf<String, Int>()
    private val utteranceRiskRanks = mutableMapOf<String, Int>()
    private val utteranceStartDeadlines = mutableMapOf<String, Long>()
    private val utteranceStartValidators = mutableMapOf<String, () -> Boolean>()
    private val explicitTerminalRequiredUtterances = mutableSetOf<String>()
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
            if (speechRuntime != null) {
                speechRuntime.initialize(initialized)
            } else {
                textToSpeech = TextToSpeech(requireNotNull(appContext)) { status -> initialized(status) }
            }
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
            speechRuntime?.setLanguage(Locale.KOREAN) ?: textToSpeech.setLanguage(Locale.KOREAN)
        }.getOrElse {
            recoverTextToSpeechOrFail()
            return
        }
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            failOfflineKoreanLanguage()
            return
        }
        val voiceSelection = runCatching {
            speechRuntime?.selectOfflineKoreanVoice() ?: selectInstalledOfflineKoreanVoice(textToSpeech)
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
        ready.set(true)
        ttsState = TtsState.READY
        speechUnavailableNotified.set(false)
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
        emitSpeechOutcome(
            outcome,
            utteranceId,
            errorCode.takeIf { outcome == SpeechOutputOutcome.ENGINE_ERROR },
        )
        val pending = utteranceId != null && synchronized(pendingUtterances) { utteranceId in pendingUtterances }
        when (ttsRuntimeFailureDisposition(errorCode, pending, ttsState == TtsState.READY)) {
            TtsRuntimeFailureDisposition.IGNORE -> Unit
            TtsRuntimeFailureDisposition.UTTERANCE ->
                markUtteranceFinished(utteranceId, completed = false, notifyFailure = notifyFailure)
            TtsRuntimeFailureDisposition.ENGINE -> {
                if (!notifyFailure) markUtteranceFinished(utteranceId, completed = false, notifyFailure = false)
                recoverTextToSpeechOrFail()
            }
            TtsRuntimeFailureDisposition.LANGUAGE -> failOfflineKoreanLanguage()
        }
    }

    private fun recoverTextToSpeechOrFail() {
        if (ttsState == TtsState.CLOSED) return
        val recover = ttsRecoveryBudget.claimRetry()
        val engineGeneration = ++ttsEngineGeneration
        ready.set(false)
        if (recover) ttsState = TtsState.INITIALIZING else ttsState = TtsState.FAILED
        ttsInitializationWatchdog?.let(mainHandler::removeCallbacks)
        ttsInitializationWatchdog = null
        pendingSpeechQueue.clear()
        val interrupted = synchronized(pendingUtterances) { pendingUtterances.toList() }
        if (isSpeechEngineInitialized()) runCatching { stopSpeechEngine() }
        failPendingSpeech(interrupted, stopTts = false)
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

    private fun failOfflineKoreanLanguage() {
        if (ttsState == TtsState.CLOSED) return
        ttsEngineGeneration += 1L
        ready.set(false)
        ttsState = TtsState.LANGUAGE_UNSUPPORTED
        pendingSpeechQueue.clear()
        val interrupted = synchronized(pendingUtterances) { pendingUtterances.toList() }
        if (isSpeechEngineInitialized()) runCatching { stopSpeechEngine() }
        failPendingSpeech(interrupted, stopTts = false)
        notifyOfflineKoreanSpeechUnavailable()
    }

    fun emit(
        action: FeedbackAction,
        onSpeechCompleted: (() -> Unit)?,
        onSpeechFailed: (() -> Unit)?,
        isStillValidAtStart: () -> Boolean,
        onSpeechCancelled: (() -> Unit)? = null,
        distanceAfterWarning: (() -> ObstacleDistanceSpeech?)? = null,
    ): RiskFeedbackDispatchResult {
        val generation = outputGate.generation()
        val current = { outputGate.isCurrent(generation, action.validUntilMs, isStillValidAtStart) }
        if (!current()) return RiskFeedbackDispatchResult(NavigationSpeechDispatchResult.SUPPRESSED, false)
        val speech = speak(
            message = action.message,
            priority = SpeechPriority.RISK,
            onCompleted = {
                // Delivery accounting belongs to the warning, not to its optional distance.
                onSpeechCompleted?.invoke()
                if (outputGate.isCurrent(generation)) {
                    val distance = distanceAfterWarning?.invoke()
                    if (distance != null) speak(
                        message = distance.message,
                        priority = SpeechPriority.RISK,
                        // Any new obstacle warning can interrupt this detail, including another STOP.
                        riskRank = MessageLevel.AWARE.ordinal,
                        deduplicate = false,
                        advisoryStartDeadlineMs = distance.validUntilMs,
                        advisoryStartValidator = {
                            outputGate.isCurrent(generation, distance.validUntilMs, distance.isStillValidAtStart)
                        },
                        requiresExplicitTerminalCallback = true,
                        protectsFromFollowingSpeech = false,
                    )
                }
            },
            onFailed = onSpeechFailed,
            riskRank = action.level.ordinal,
            advisoryStartDeadlineMs = action.validUntilMs,
            advisoryStartValidator = current,
            requiresExplicitTerminalCallback = true,
            protectsFromFollowingSpeech = false,
            onCancelled = onSpeechCancelled,
        )
        val vibrationAccepted = vibrate(action.vibrationPatternMs, rank = action.level.ordinal,
            validUntilMs = action.validUntilMs, isStillValid = current, generation = generation)
        return RiskFeedbackDispatchResult(speech, vibrationAccepted)
    }

    fun vibrateRiskOnly(action: FeedbackAction, isStillValidAtStart: () -> Boolean): Boolean =
        vibrate(action.vibrationPatternMs, rank = action.level.ordinal,
            validUntilMs = action.validUntilMs, isStillValid = isStillValidAtStart)

    /** Short, distinct haptics announce when voice recognition starts and stops listening. */
    fun playVoiceListeningStartVibration(): Boolean = vibrate(longArrayOf(0L, 35L))

    fun playVoiceListeningEndVibration(): Boolean = vibrate(longArrayOf(0L, 35L, 45L, 35L))

    fun playRouteGuidancePausedVibration(): Boolean = vibrate(longArrayOf(0L, 120L))

    fun playRouteDeviationConfirmedVibration(): Boolean =
        vibrate(checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)), rank = MessageLevel.STOP.ordinal)

    /** Adds a short haptic cue to the separate spoken and on-screen mounting correction. */
    fun playPhoneMountingCorrectionVibration(): Boolean =
        vibrate(longArrayOf(0L, 120L))

    fun playPhoneMountingSafetyStopVibration(): Boolean =
        vibrate(checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)), rank = MessageLevel.STOP.ordinal)

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
        val commandUtterances = synchronized(pendingUtterances) {
            if (pendingUtterances.any { !it.startsWith("$ANNOUNCE_INTERACTION_PREFIX-") }) return
            val owned = utteranceCallbacks.exclusiveCommandUtteranceIds(
                pendingUtteranceIds = pendingUtterances,
                explicitTerminalRequiredIds = explicitTerminalRequiredUtterances,
            )
            if (owned.isEmpty()) return
            // Keep the ownership check and engine stop atomic with utterance registration.
            if (ttsState == TtsState.READY) stopSpeechEngine()
            lastInteractionMessage = ""
            lastInteractionMessageAtMs = 0L
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
                    priorityTrainingStopInProgress = true
                    try {
                        stopSpeechEngine()
                    } finally {
                        priorityTrainingStopInProgress = false
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
        synchronized(pendingUtterances) { progressToneGeneration += 1 }
        pendingSpeechQueue.clear()
        val watchdogs: List<Runnable>
        val advisoryFailures: List<() -> Unit>
        val strictTrainingFailures: List<() -> Unit>
        synchronized(pendingUtterances) {
            advisoryFailures = pendingUtterances
                .filter { it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true) }
            strictTrainingFailures = explicitTerminalRequiredUtterances
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true) }
            if (pendingUtterances.isNotEmpty()) {
                speechRecognitionBlockedUntilMs = elapsedRealtime() + SPEECH_RECOGNITION_QUIET_MS
            }
            pendingUtterances.clear()
            pendingUtteranceOrder.clear()
            startedUtterances.clear()
            utteranceCallbacks.clear()
            watchdogs = utteranceWatchdogs.values.toList()
            utteranceWatchdogs.clear()
            utteranceMessageLengths.clear()
            utteranceRiskRanks.clear()
            utteranceStartDeadlines.clear()
            utteranceStartValidators.clear()
            explicitTerminalRequiredUtterances.clear()
        }
        watchdogs.forEach(mainHandler::removeCallbacks)
        if (ttsState == TtsState.READY) stopSpeechEngine()
        advisoryFailures.forEach { callback ->
            runCatching { callback() }
                .onFailure { logSpeechEvent("terminal_callback_error") }
        }
        strictTrainingFailures.forEach { callback ->
            runCatching { callback() }
                .onFailure { logSpeechEvent("terminal_callback_error") }
        }
        stopProgressToneSafely { progressTone?.stopTone() }
        lastRiskMessage = ""
        lastRiskMessageAtMs = 0L
        lastNavMessage = ""
        lastNavMessageAtMs = 0L
        lastInteractionMessage = ""
        lastInteractionMessageAtMs = 0L
        abandonAudioFocus()
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
                it in explicitTerminalRequiredUtterances
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
        onCancelled: (() -> Unit)? = onFailed,
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        if (ttsState != TtsState.READY) return NavigationSpeechDispatchResult.UNAVAILABLE
        return speakReady(
            message = message,
            priority = SpeechPriority.INTERACTION,
            onCompleted = onCompleted,
            onFailed = onFailed,
            onCancelled = onCancelled,
            requiresExplicitTerminalCallback = true,
            protectsFromFollowingSpeech = false,
        )
    }

    fun speakInteraction(message: String): Boolean =
        speak(message, SpeechPriority.INTERACTION) == NavigationSpeechDispatchResult.ACCEPTED

    fun speakInteraction(
        message: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        onCancelled: (() -> Unit)? = onFailed,
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.INTERACTION,
        onCompleted = onCompleted,
        onFailed = onFailed,
        onCancelled = onCancelled,
        requiresExplicitTerminalCallback = true,
        protectsFromFollowingSpeech = false,
    )

    fun speakExplicitConfirmation(
        message: String,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.INTERACTION,
        onCompleted = onCompleted,
        onFailed = onFailed,
        requiresExplicitTerminalCallback = true,
    )

    /** Explicit consent reading can precede startup checks, but still requires a ready offline voice. */
    fun speakConsentClause(
        message: String,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        if (ttsState != TtsState.READY) return NavigationSpeechDispatchResult.UNAVAILABLE
        return speakReady(
            message = message,
            priority = SpeechPriority.INTERACTION,
            onCompleted = onCompleted,
            onFailed = onFailed,
            requiresExplicitTerminalCallback = true,
        )
    }

    fun speakPriorityUserTraining(
        message: String,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        val chunks = trainingSpeechChunks(message)
        var ended = false
        fun fail() {
            if (ended) return
            ended = true
            onFailed()
        }
        fun play(index: Int): NavigationSpeechDispatchResult = speak(
            message = chunks[index],
            priority = SpeechPriority.INTERACTION,
            onCompleted = {
                if (!ended) {
                    if (index == chunks.lastIndex) {
                        ended = true
                        onCompleted()
                    } else if (play(index + 1) != NavigationSpeechDispatchResult.ACCEPTED) fail()
                }
            },
            onFailed = ::fail,
            requiresExplicitTerminalCallback = true,
        )
        return play(0)
    }

    /** Only an explicit user retry may reopen an engine that exhausted automatic recovery. */
    fun retryFailedSpeechInitialization() {
        if (ttsState == TtsState.FAILED || ttsState == TtsState.LANGUAGE_UNSUPPORTED) {
            initializeTextToSpeech()
        }
    }

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
                speechRuntime?.createProgressTone(volume) ?: AndroidProgressToneEffects(volume)
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
        releaseAudioFocusRegistration(registration.first, registration.second)
    }

    fun isSpeechInitializing(): Boolean = ttsState == TtsState.INITIALIZING

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
        deduplicate: Boolean = true,
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
            deduplicate,
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
        deduplicate: Boolean = true,
    ): NavigationSpeechDispatchResult {
        if (!outputGate.isCurrent()) return NavigationSpeechDispatchResult.SUPPRESSED
        // A synchronous native stop callback must not reenter TTS speak before stop returns.
        if (priorityTrainingStopInProgress) return NavigationSpeechDispatchResult.UNAVAILABLE
        val dispatchEngineGeneration = ttsEngineGeneration
        val dispatchOutputGeneration = outputGate.generation()
        fun submissionOwnerIsCurrent(): Boolean =
            ttsState == TtsState.READY &&
                ttsEngineGeneration == dispatchEngineGeneration &&
                outputGate.isCurrent(dispatchOutputGeneration)
        val isRisk = priority == SpeechPriority.RISK
        val isInteraction = priority == SpeechPriority.INTERACTION
        val isAdvisory = priority == SpeechPriority.ADVISORY
        val strictTrainingUtterance = synchronized(pendingUtterances) {
            utteranceCallbacks.protectedUtteranceIds().firstOrNull()
        }
        if (strictTrainingUtterance != null) {
            if (!isRisk) return NavigationSpeechDispatchResult.SUPPRESSED
        }
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
        if (isRisk) {
            val activeRiskRank = synchronized(pendingUtterances) {
                pendingUtterances.mapNotNull(utteranceRiskRanks::get).maxOrNull()
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
        if (deduplicate && !isAdvisory && isDuplicateMessage(message, priority, messagePriorityMs)) {
            return NavigationSpeechDispatchResult.SUPPRESSED
        }
        if (isAdvisory && synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }) {
            return NavigationSpeechDispatchResult.SUPPRESSED
        }
        val riskPending = synchronized(pendingUtterances) {
            pendingUtterances.any { it.startsWith("$ANNOUNCE_ASSERTIVE_PREFIX-") }
        }
        val isCommandInteraction = isInteraction && requiresExplicitTerminalCallback &&
            !protectsFromFollowingSpeech
        // Do not queue cancellable commands behind risk speech: stopping that queue would stop risk.
        if (isCommandInteraction && riskPending) return NavigationSpeechDispatchResult.SUPPRESSED
        // TextToSpeech.stop() cannot cancel a training utterance without stopping the preceding
        // risk. Keep all interactions outside that engine queue, including protected training.
        if (isInteraction && riskPending) return NavigationSpeechDispatchResult.SUPPRESSED
        if (priority == SpeechPriority.NAVIGATION) {
            val pendingIds = synchronized(pendingUtterances) { pendingUtterances.toList() }
            if (pendingIds.isNotEmpty()) {
                if (pendingIds.any { !it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }) {
                    return NavigationSpeechDispatchResult.SUPPRESSED
                }
                cancelQueuedCompletionCallbacks(keepRisk = false)
                if (!submissionOwnerIsCurrent()) return NavigationSpeechDispatchResult.UNAVAILABLE
                stopSpeechEngine()
            }
        }
        val queueMode = when (priority) {
            SpeechPriority.RISK -> TextToSpeech.QUEUE_FLUSH
            SpeechPriority.INTERACTION -> if (riskPending) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            SpeechPriority.NAVIGATION -> TextToSpeech.QUEUE_ADD
            SpeechPriority.ADVISORY -> TextToSpeech.QUEUE_ADD
        }
        val utterancePrefix = when (priority) {
            SpeechPriority.RISK -> ANNOUNCE_ASSERTIVE_PREFIX
            SpeechPriority.INTERACTION -> ANNOUNCE_INTERACTION_PREFIX
            SpeechPriority.NAVIGATION -> ANNOUNCE_NAV_PREFIX
            SpeechPriority.ADVISORY -> ANNOUNCE_ADVISORY_PREFIX
        }
        val utteranceId = "$utterancePrefix-$nowMs-${utteranceSequence.incrementAndGet()}"
        val registerUtterance = {
            markUtteranceStarted(
                utteranceId,
                message.length,
                riskRank,
                onCompleted,
                onFailed,
                advisoryStartDeadlineMs,
                advisoryStartValidator,
                requiresExplicitTerminalCallback,
                protectsFromFollowingSpeech,
                onCancelled,
            )
        }
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            logSpeechEvent("queue_flush priority=$priority")
            cancelQueuedCompletionCallbacks(
                keepRisk = priority != SpeechPriority.RISK && riskPending,
                // Publish a risk replacement before old owners' cancellation callbacks can
                // reenter dispatch; otherwise a same-rank risk may speak and then be flushed.
                replacement = if (isRisk) registerUtterance else null,
            )
        }
        // Consumer cancellation callbacks may close this actuator or replace its engine.
        // Do not acquire new ownership/focus after returning from such a callback.
        if (!submissionOwnerIsCurrent()) return NavigationSpeechDispatchResult.UNAVAILABLE
        if (isRisk) {
            if (synchronized(pendingUtterances) { utteranceId !in pendingUtterances }) {
                return NavigationSpeechDispatchResult.SUPPRESSED
            }
        } else {
            registerUtterance()
        }
        if (!requestAudioFocus(isRisk)) {
            emitSpeechOutcome(SpeechOutputOutcome.FOCUS_DENIED, utteranceId)
            val stillOwnsFailedRequest = submissionOwnerIsCurrent() && synchronized(pendingUtterances) {
                pendingUtterances.size == 1 && utteranceId in pendingUtterances
            }
            if (queueMode == TextToSpeech.QUEUE_FLUSH && stillOwnsFailedRequest) stopSpeechEngine()
            markUtteranceFinished(utteranceId, completed = false, notifyFailure = false)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        // Focus acquisition/cancellation can invalidate a request after it was registered.
        // The same registered validator is checked again when the engine releases its queue.
        if (!isUtteranceStillValidAtStart(utteranceId)) return NavigationSpeechDispatchResult.SUPPRESSED
        val speakResult: Int? = try {
            synchronized(speechEngineOperationLock) {
                if (priorityTrainingStopInProgress ||
                    synchronized(pendingUtterances) { utteranceId !in pendingUtterances }
                ) {
                    null
                } else if (speechRuntime != null) {
                    speechRuntime.speak(message, queueMode, utteranceId)
                } else {
                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    textToSpeech.speak(message, queueMode, params, utteranceId)
                }
            }
        } catch (_: RuntimeException) {
            // A rejected synchronous call belongs to the caller, not a second async failure.
            failRequiredSpeechRuntime(utteranceId, notifyFailure = false)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        if (speakResult == null) {
            markUtteranceFinished(utteranceId, completed = false, notifyFailure = false)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        if (speakResult == TextToSpeech.ERROR) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) stopSpeechEngine()
            failRequiredSpeechRuntime(utteranceId, notifyFailure = false)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        val stillOwned = synchronized(pendingUtterances) {
            if (utteranceId !in pendingUtterances) {
                false
            } else {
                if (isRisk && deduplicate) {
                    lastRiskMessage = message
                    lastRiskMessageAtMs = nowMs
                } else if (isInteraction) {
                    lastInteractionMessage = message
                    lastInteractionMessageAtMs = nowMs
                } else if (!isAdvisory && deduplicate) {
                    lastNavMessage = message
                    lastNavMessageAtMs = nowMs
                }
                // Distance details must not replace the warning duplicate identity.
                if (deduplicate) duplicateOwnerByPriority[utterancePrefix] = utteranceId
                true
            }
        }
        return if (stillOwned) NavigationSpeechDispatchResult.ACCEPTED
        else NavigationSpeechDispatchResult.SUPPRESSED
    }

    private fun vibrate(
        patternMs: LongArray?,
        cancelPriorityUserTraining: Boolean = true,
        rank: Int = 0,
        validUntilMs: Long = Long.MAX_VALUE,
        isStillValid: () -> Boolean = { true },
        generation: Long = outputGate.generation(),
        dedupe: Boolean = true,
    ): Boolean {
        if (!hapticAllowed()) return false
        return outputGate.vibrate(patternMs, rank, generation, validUntilMs, isStillValid) { pattern ->
            val currentVibrator = vibrator
            if (speechRuntime == null) {
                if (currentVibrator == null || !currentVibrator.hasVibrator()) return@vibrate false
            }
            val nowMs = elapsedRealtime()
            if (dedupe && pattern.contentEquals(lastVibrationPattern ?: longArrayOf()) &&
                nowMs - lastVibrationAtMs < VIBRATION_DUP_WINDOW_MS
            ) return@vibrate false
            if (speechRuntime != null) {
                if (!speechRuntime.vibrate(pattern.copyOf())) return@vibrate false
            } else {
                checkNotNull(currentVibrator).vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
            if (cancelPriorityUserTraining) {
                cancelPriorityUserTrainingVibration(notifyFailure = true)
            }
            lastVibrationPattern = pattern.copyOf()
            lastVibrationAtMs = nowMs
            true
        }
    }

    private fun cancelPriorityUserTrainingVibration(notifyFailure: Boolean) {
        val pending = synchronized(priorityUserTrainingVibrationLock) {
            val completion = priorityUserTrainingVibrationCompletion
            val failure = priorityUserTrainingVibrationFailure
            priorityUserTrainingVibrationCompletion = null
            priorityUserTrainingVibrationFailure = null
            completion to failure
        }
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
        val manager = audioManager
        if (manager == null && speechRuntime == null) return false
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
        previousRegistration?.let { releaseAudioFocusRegistration(it.first, it.second) }
        if (!isAudioFocusRequestCurrent(generation, outputGeneration)) return false
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleAudioFocusChange(focusChange, generation)
        }
        var request: AudioFocusRequest? = null
        val requested = try {
            if (speechRuntime != null) {
                speechRuntime.requestAudioFocus(isRisk, listener)
            } else {
                val nativeManager = checkNotNull(manager)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    request = AudioFocusRequest.Builder(requestedGain)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(listener)
                        .build()
                    nativeManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else {
                    @Suppress("DEPRECATION")
                    val requestedFocus = nativeManager.requestAudioFocus(
                        listener,
                        AudioManager.STREAM_MUSIC,
                        requestedGain,
                    )
                    requestedFocus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
            }
        } catch (_: RuntimeException) {
            // The effect may have submitted B before throwing. Never abandon B's registration.
            releaseAudioFocusRegistration(request, listener)
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
                activeAudioFocusRequest = request
                activeAudioFocusListener = listener
                true
            }
        }
        if (!accepted) releaseAudioFocusRegistration(request, listener)
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
        val releasedRegistration = synchronized(audioFocusLock) {
            if (!hasAudioFocus || audioFocusGeneration != generation) {
                null
            } else {
                val registration = activeAudioFocusRequest to activeAudioFocusListener
                hasAudioFocus = false
                activeAudioFocus = AudioManager.AUDIOFOCUS_LOSS
                activeAudioFocusRequest = null
                activeAudioFocusListener = null
                audioFocusGeneration += 1L
                registration
            }
        }
        if (releasedRegistration == null) return
        logSpeechEvent("audio_focus_lost change=$focusChange")
        emitSpeechOutcome(SpeechOutputOutcome.FOCUS_LOST, utteranceId = null)
        releaseAudioFocusRegistration(releasedRegistration.first, releasedRegistration.second)
        val interrupted = synchronized(pendingUtterances) { pendingUtterances.toList() }
        val toneGeneration = synchronized(pendingUtterances) { ++progressToneGeneration }
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
        val registration = synchronized(audioFocusLock) {
            if (hasAudioFocus) {
                takeAudioFocusRegistration()
            } else {
                // Explicit cancellation also retires a request whose grant has not returned.
                audioFocusGeneration += 1L
                null
            }
        } ?: return
        releaseAudioFocusRegistration(registration.first, registration.second)
    }

    private fun takeAudioFocusRegistration(
        expectedGeneration: Long? = null,
        expectedGain: Int? = null,
    ): Pair<AudioFocusRequest?, AudioManager.OnAudioFocusChangeListener?>? =
        synchronized(audioFocusLock) {
            if (!hasAudioFocus ||
                (expectedGeneration != null && audioFocusGeneration != expectedGeneration) ||
                (expectedGain != null && activeAudioFocus != expectedGain)
            ) return@synchronized null
            val current = activeAudioFocusRequest to activeAudioFocusListener
            hasAudioFocus = false
            activeAudioFocus = AudioManager.AUDIOFOCUS_LOSS
            activeAudioFocusRequest = null
            activeAudioFocusListener = null
            audioFocusGeneration += 1L
            current
        }

    private fun releaseAudioFocusRegistration(
        request: AudioFocusRequest?,
        listener: AudioManager.OnAudioFocusChangeListener?,
    ) {
        try {
            if (speechRuntime != null) {
                listener?.let { speechRuntime.abandonAudioFocus(it) }
                return
            }
            val manager = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                request?.let { manager.abandonAudioFocusRequest(it) }
                return
            }
            listener?.let {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(it)
            }
        } catch (_: RuntimeException) {
            // Ownership is already retired; failure must not skip terminal callbacks or close.
            logSpeechEvent("audio_focus_release_error")
        }
    }

    private fun markUtteranceStarted(
        utteranceId: String,
        messageLength: Int,
        riskRank: Int?,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        startDeadlineMs: Long?,
        startValidator: (() -> Boolean)?,
        requiresExplicitTerminalCallback: Boolean,
        protectsFromFollowingSpeech: Boolean,
        onCancelled: (() -> Unit)?,
    ) {
        synchronized(pendingUtterances) {
            pendingUtterances.add(utteranceId)
            pendingUtteranceOrder.add(utteranceId)
            utteranceCallbacks.register(utteranceId, onCompleted, onFailed, protectsFromFollowingSpeech, onCancelled)
            utteranceMessageLengths[utteranceId] = messageLength
            riskRank?.let { utteranceRiskRanks[utteranceId] = it }
            startDeadlineMs?.let { utteranceStartDeadlines[utteranceId] = it }
            startValidator?.let { utteranceStartValidators[utteranceId] = it }
            if (requiresExplicitTerminalCallback) {
                explicitTerminalRequiredUtterances.add(utteranceId)
            }
            armNextUtteranceStartWatchdogLocked()
        }
    }

    private fun isUtteranceStillValidAtStart(utteranceId: String?): Boolean {
        if (utteranceId == null) return false
        val deadlineAndValidator = synchronized(pendingUtterances) {
            if (utteranceId !in pendingUtterances) return false
            val deadline = utteranceStartDeadlines[utteranceId] ?: return@synchronized null
            val validator = utteranceStartValidators[utteranceId] ?: return@synchronized null
            deadline to validator
        } ?: return true
        val isValid = speechStartIsCurrent(
            elapsedRealtime(), deadlineAndValidator.first, deadlineAndValidator.second,
        )
        if (isValid) return true
        val shouldStop = synchronized(pendingUtterances) {
            if (
                utteranceId !in pendingUtterances ||
                utteranceStartValidators[utteranceId] !== deadlineAndValidator.second
            ) {
                false
            } else {
                if (ttsState == TtsState.READY) stopSpeechEngine()
                true
            }
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
            val predecessors = pendingUtteranceOrder.takeWhile { it != utteranceId }
            predecessors.forEach { predecessor ->
                clearDuplicateSuppressionOwnedBy(predecessor)
                pendingUtterances.remove(predecessor)
                pendingUtteranceOrder.remove(predecessor)
                startedUtterances.remove(predecessor)
                utteranceWatchdogs.remove(predecessor)?.let(inferredWatchdogs::add)
                utteranceMessageLengths.remove(predecessor)
                utteranceRiskRanks.remove(predecessor)
                utteranceStartDeadlines.remove(predecessor)
                utteranceStartValidators.remove(predecessor)
                explicitTerminalRequiredUtterances.remove(predecessor)
                utteranceCallbacks.takeTerminalCallback(
                    utteranceId = predecessor,
                    completed = false,
                    notifyFailure = true,
                )?.let(inferredTerminalCallbacks::add)
            }
            startedUtterances.add(utteranceId)
            scheduleUtteranceWatchdogLocked(
                utteranceId,
                utteranceTerminalTimeoutMs(utteranceMessageLengths[utteranceId] ?: 0),
            )
        }
        inferredWatchdogs.forEach(mainHandler::removeCallbacks)
        inferredTerminalCallbacks.forEach { it() }
    }

    private fun scheduleUtteranceWatchdogLocked(utteranceId: String, delayMs: Long) {
        utteranceWatchdogs.remove(utteranceId)?.let(mainHandler::removeCallbacks)
        val engineGeneration = ttsEngineGeneration
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            val started = synchronized(pendingUtterances) {
                if (utteranceWatchdogs[utteranceId] !== watchdog ||
                    utteranceId !in pendingUtterances ||
                    engineGeneration != ttsEngineGeneration || ttsState != TtsState.READY
                ) return@Runnable
                utteranceId in startedUtterances
            }
            // Expiry/revocation before start is cancellation, not evidence of engine failure.
            // After onStart, this is the terminal watchdog: start TTL no longer revokes completion.
            if (!started && !isUtteranceStillValidAtStart(utteranceId)) return@Runnable
            logSpeechEvent("watchdog_timeout", utteranceId)
            // Keep pending ownership until the existing error classifier has classified it.
            failRequiredSpeechRuntime(utteranceId, outcome = SpeechOutputOutcome.TERMINAL_TIMEOUT)
        }
        utteranceWatchdogs[utteranceId] = watchdog
        mainHandler.postDelayed(watchdog, delayMs)
    }

    private fun armNextUtteranceStartWatchdogLocked() {
        val next = pendingUtteranceOrder.firstOrNull() ?: return
        if (next in startedUtterances || next in utteranceWatchdogs) return
        val nowMs = elapsedRealtime()
        val deadlineDelayMs = utteranceStartDeadlines[next]?.let { deadline ->
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
        var terminalCallback: (() -> Unit)? = null
        var watchdog: Runnable? = null
        var shouldAbandonFocus = false
        synchronized(pendingUtterances) {
            if (utteranceId != null) {
                val removed = pendingUtterances.remove(utteranceId)
                if (removed) {
                    if (!completed) clearDuplicateSuppressionOwnedBy(utteranceId)
                    pendingUtteranceOrder.remove(utteranceId)
                    startedUtterances.remove(utteranceId)
                    watchdog = utteranceWatchdogs.remove(utteranceId)
                    utteranceMessageLengths.remove(utteranceId)
                    utteranceRiskRanks.remove(utteranceId)
                    utteranceStartDeadlines.remove(utteranceId)
                    utteranceStartValidators.remove(utteranceId)
                    explicitTerminalRequiredUtterances.remove(utteranceId)
                    terminalCallback = utteranceCallbacks.takeTerminalCallback(
                        utteranceId = utteranceId,
                        completed = completed,
                        notifyFailure = notifyFailure,
                        cancelled = cancelled,
                    )
                    shouldAbandonFocus = pendingUtterances.isEmpty()
                    if (shouldAbandonFocus) {
                        speechRecognitionBlockedUntilMs = elapsedRealtime() + SPEECH_RECOGNITION_QUIET_MS
                    }
                    armNextUtteranceStartWatchdogLocked()
                }
            }
        }
        watchdog?.let(mainHandler::removeCallbacks)
        if (shouldAbandonFocus) abandonAudioFocus()
        runCatching { terminalCallback?.invoke() }
            .onFailure { logSpeechEvent("terminal_callback_error", utteranceId) }
    }

    private fun failPendingSpeech(
        interrupted: List<String> = synchronized(pendingUtterances) { pendingUtterances.toList() },
        stopTts: Boolean = true,
        cancelled: Boolean = false,
    ) {
        if (interrupted.isEmpty()) return
        if (stopTts && ttsState == TtsState.READY) stopSpeechEngine()
        interrupted.forEach { markUtteranceFinished(it, completed = false, cancelled = cancelled) }
    }

    private fun emitSpeechOutcome(
        outcome: SpeechOutputOutcome,
        utteranceId: String?,
        errorCode: Int? = null,
    ) {
        val listener = speechOutputListener ?: return
        runCatching {
            listener(SpeechOutputEvent(speechOutputKindOf(utteranceId), outcome, elapsedRealtime(), errorCode))
        }
    }

    private fun logSpeechEvent(event: String, utteranceId: String? = null) {
        if (speechRuntime != null || !BuildConfig.DEBUG) return
        // IDs contain only priority, monotonic time and sequence; never record spoken text.
        Log.d("WalkSafeVoiceOutput", "event=$event utterance=${utteranceId ?: "none"}")
    }

    private fun cancelQueuedCompletionCallbacks(
        keepRisk: Boolean,
        replacement: (() -> Unit)? = null,
    ) {
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
                .filter(explicitTerminalRequiredUtterances::contains)
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true) }
            cancelled.forEach(::clearDuplicateSuppressionOwnedBy)
            pendingUtterances.removeAll(cancelled.toSet())
            pendingUtteranceOrder.removeAll(cancelled.toSet())
            startedUtterances.removeAll(cancelled.toSet())
            utteranceCallbacks.cancel(cancelled)
            watchdogs = cancelled.mapNotNull(utteranceWatchdogs::remove)
            cancelled.forEach(utteranceMessageLengths::remove)
            cancelled.forEach(utteranceRiskRanks::remove)
            cancelled.forEach(utteranceStartDeadlines::remove)
            cancelled.forEach(utteranceStartValidators::remove)
            cancelled.forEach(explicitTerminalRequiredUtterances::remove)
            replacement?.invoke()
            armNextUtteranceStartWatchdogLocked()
        }
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

    override fun close() {
        outputGate.close()
        synchronized(audioFocusLock) { audioFocusGeneration += 1L }
        logSpeechEvent("engine_closed", null)
        ready.set(false)
        ttsState = TtsState.CLOSED
        ttsEngineGeneration += 1L
        pendingSpeechQueue.clear()
        cancelPriorityUserTrainingVibration(notifyFailure = false)
        val watchdogs: List<Runnable>
        synchronized(pendingUtterances) {
            pendingUtterances.clear()
            pendingUtteranceOrder.clear()
            startedUtterances.clear()
            utteranceCallbacks.clear()
            watchdogs = utteranceWatchdogs.values.toList()
            utteranceWatchdogs.clear()
            utteranceMessageLengths.clear()
            utteranceRiskRanks.clear()
            utteranceStartDeadlines.clear()
            utteranceStartValidators.clear()
            explicitTerminalRequiredUtterances.clear()
        }
        watchdogs.forEach(mainHandler::removeCallbacks)
        val tone = synchronized(pendingUtterances) {
            progressToneGeneration += 1
            detachProgressToneLocked()?.first
        }
        mainHandler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        releaseProgressTone(tone)
        if (!speechUnavailableVibrationRetained) {
            runCatching { if (speechRuntime != null) speechRuntime.cancelVibration() else vibrator?.cancel() }
        }
        if (isSpeechEngineInitialized()) {
            stopSpeechEngine()
            shutdownSpeechEngine()
        }
    }

    private fun elapsedRealtime(): Long =
        speechRuntime?.elapsedRealtime() ?: SystemClock.elapsedRealtime()

    private fun isSpeechEngineInitialized(): Boolean =
        speechRuntime?.engineInitialized ?: ::textToSpeech.isInitialized

    private fun stopSpeechEngine() {
        try {
            if (speechRuntime != null) speechRuntime.stop() else textToSpeech.stop()
        } catch (_: RuntimeException) {
            // Cancellation remains cancellation; a stop exception cannot trigger fallback speech.
            logSpeechEvent("engine_stop_error")
        }
    }

    private fun shutdownSpeechEngine() {
        try {
            if (speechRuntime != null) speechRuntime.shutdown() else textToSpeech.shutdown()
        } catch (_: RuntimeException) {
            logSpeechEvent("engine_shutdown_error")
        }
    }

    private fun configureSpeechAttributes(): Boolean {
        try {
            if (speechRuntime != null) {
                speechRuntime.configureAudioAttributes()
            } else {
                textToSpeech.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            return true
        } catch (_: RuntimeException) {
            recoverTextToSpeechOrFail()
            return false
        }
    }

    private fun installSpeechListener(listener: FeedbackSpeechRuntime.Listener): Int = try {
        speechRuntime?.setListener(listener) ?: textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = listener.onStart(utteranceId)
                override fun onDone(utteranceId: String?) = listener.onDone(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = listener.onError(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) =
                    listener.onError(utteranceId, errorCode)
                override fun onStop(utteranceId: String?, interrupted: Boolean) =
                    listener.onStop(utteranceId, interrupted)
            },
        )
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

private class AndroidProgressToneEffects(volumePercent: Int) : ProgressToneEffects {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent)
    override fun startTone(toneType: Int, durationMs: Int): Boolean =
        tone.startTone(toneType, durationMs)
    override fun stopTone() = tone.stopTone()
    override fun release() = tone.release()
}

internal interface FeedbackSpeechScheduler {
    fun post(task: Runnable): Boolean
    fun postDelayed(task: Runnable, delayMs: Long): Boolean
    fun removeCallbacks(task: Runnable)
    fun removeCallbacksAndMessages(token: Any?)
}

private class AndroidFeedbackSpeechScheduler : FeedbackSpeechScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(task: Runnable): Boolean = handler.post(task)
    override fun postDelayed(task: Runnable, delayMs: Long): Boolean = handler.postDelayed(task, delayMs)
    override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
    override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
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
