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
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel

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
class AndroidFeedbackActuator(
    context: Context,
    private val onOfflineKoreanSpeechUnavailable: () -> Unit = {},
    private val speechAllowed: () -> Boolean = { true },
    private val hapticAllowed: () -> Boolean = { true },
) : TextToSpeech.OnInitListener, Closeable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val ready = AtomicBoolean(false)
    private val pendingSpeechQueue = PendingSpeechQueue()
    private val speechUnavailableNotified = AtomicBoolean(false)
    @Volatile
    private var ttsState = TtsState.INITIALIZING
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textToSpeech = TextToSpeech(appContext, this)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
    private var progressTone: ToneGenerator? = null
    private var progressToneVolumePercent = -1
    private var progressToneGeneration = 0

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
    }

    override fun onInit(status: Int) {
        if (ttsState == TtsState.CLOSED) return
        if (status != TextToSpeech.SUCCESS) {
            ttsState = TtsState.FAILED
            pendingSpeechQueue.clear()
            notifyOfflineKoreanSpeechUnavailable()
            return
        }
        val languageStatus = textToSpeech.setLanguage(Locale.KOREAN)
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsState = TtsState.LANGUAGE_UNSUPPORTED
            pendingSpeechQueue.clear()
            notifyOfflineKoreanSpeechUnavailable()
            return
        }
        val offlineKoreanVoice = textToSpeech.voices.orEmpty()
            .filter { voice ->
                voice.locale.language.equals(Locale.KOREAN.language, ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired
            }
            .sortedBy { it.name }
            .firstOrNull()
        if (offlineKoreanVoice == null || textToSpeech.setVoice(offlineKoreanVoice) == TextToSpeech.ERROR) {
            ttsState = TtsState.LANGUAGE_UNSUPPORTED
            pendingSpeechQueue.clear()
            notifyOfflineKoreanSpeechUnavailable()
            return
        }
        textToSpeech.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (!isUtteranceStillValidAtStart(utteranceId)) return
                    armUtteranceTerminalWatchdog(utteranceId)
                }

                override fun onDone(utteranceId: String?) {
                    markUtteranceFinished(utteranceId, completed = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    failRequiredSpeechRuntime(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    failRequiredSpeechRuntime(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    markUtteranceFinished(utteranceId, completed = false)
                }
            },
        )
        ready.set(true)
        ttsState = TtsState.READY
        flushPendingSpeech()
    }

    private fun notifyOfflineKoreanSpeechUnavailable() {
        if (speechUnavailableNotified.compareAndSet(false, true)) {
            mainHandler.post(onOfflineKoreanSpeechUnavailable)
        }
    }

    private fun failRequiredSpeechRuntime(utteranceId: String?) {
        if (ttsState == TtsState.CLOSED) return
        ready.set(false)
        ttsState = TtsState.FAILED
        pendingSpeechQueue.clear()
        textToSpeech.stop()
        val interrupted = synchronized(pendingUtterances) {
            pendingUtterances.toList().let { pending ->
                if (utteranceId != null && utteranceId in pending) {
                    listOf(utteranceId) + pending.filterNot { it == utteranceId }
                } else {
                    pending
                }
            }
        }
        failPendingSpeech(interrupted, stopTts = false)
        notifyOfflineKoreanSpeechUnavailable()
    }

    fun emit(
        action: FeedbackAction,
        onSpeechCompleted: (() -> Unit)?,
        onSpeechFailed: (() -> Unit)?,
    ): RiskFeedbackDispatchResult {
        val speech = speak(
            message = action.message,
            priority = SpeechPriority.RISK,
            onCompleted = onSpeechCompleted,
            onFailed = onSpeechFailed,
            riskRank = action.level.ordinal,
        )
        val vibrationAccepted = vibrate(action.vibrationPatternMs)
        return RiskFeedbackDispatchResult(speech, vibrationAccepted)
    }

    fun vibrateRiskOnly(action: FeedbackAction): Boolean = vibrate(action.vibrationPatternMs)

    /** Short, distinct haptics announce when voice recognition starts and stops listening. */
    fun playVoiceListeningStartVibration(): Boolean = vibrate(longArrayOf(0L, 35L))

    fun playVoiceListeningEndVibration(): Boolean = vibrate(longArrayOf(0L, 35L, 45L, 35L))

    fun playRouteGuidancePausedVibration(): Boolean = vibrate(longArrayOf(0L, 120L))

    fun playRouteDeviationConfirmedVibration(): Boolean =
        vibrate(checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)))

    /** Adds a short haptic cue to the separate spoken and on-screen mounting correction. */
    fun playPhoneMountingCorrectionVibration(): Boolean =
        vibrate(longArrayOf(0L, 120L))

    fun playPhoneMountingSafetyStopVibration(): Boolean =
        vibrate(checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)))

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

    fun cancelPriorityUserTrainingFeedback() {
        val strictUtterances = synchronized(pendingUtterances) {
            explicitTerminalRequiredUtterances.toList()
        }
        if (strictUtterances.isNotEmpty() && ttsState == TtsState.READY) {
            textToSpeech.stop()
        }
        strictUtterances.forEach {
            markUtteranceFinished(it, completed = false)
        }
        cancelPriorityUserTrainingVibration(notifyFailure = true)
    }

    /** Gives a spoken accessibility service sole ownership before it announces a risk. */
    fun prepareForExternalRiskAnnouncement() {
        progressToneGeneration += 1
        pendingSpeechQueue.clear()
        val watchdogs: List<Runnable>
        val advisoryFailures: List<() -> Unit>
        val strictTrainingFailures: List<() -> Unit>
        synchronized(pendingUtterances) {
            advisoryFailures = pendingUtterances
                .filter { it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true) }
            strictTrainingFailures = explicitTerminalRequiredUtterances
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true) }
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
        if (ttsState == TtsState.READY) textToSpeech.stop()
        advisoryFailures.forEach { it() }
        strictTrainingFailures.forEach { it() }
        progressTone?.stopTone()
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
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.NAVIGATION,
        onCompleted = onCompleted,
        onFailed = onFailed,
    )

    /** Cancels every stale route utterance; dispatch rules prevent navigation/risk coexistence. */
    fun cancelNavigationSpeech(): Boolean {
        val queuedNavigationRemoved = pendingSpeechQueue.removePriority(SpeechPriority.NAVIGATION)
        val snapshot = synchronized(pendingUtterances) { pendingUtterances.toList() }
        val navigation = snapshot.filter { it.startsWith("$ANNOUNCE_NAV_PREFIX-") }
        if (navigation.isEmpty()) return queuedNavigationRemoved
        if (ttsState == TtsState.READY) textToSpeech.stop()
        navigation.forEach {
            markUtteranceFinished(it, completed = false, notifyFailure = false)
        }
        lastNavMessage = ""
        lastNavMessageAtMs = 0L
        return true
    }

    fun speakInteraction(message: String): Boolean =
        speak(message, SpeechPriority.INTERACTION) == NavigationSpeechDispatchResult.ACCEPTED

    fun speakInteraction(
        message: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
    ): NavigationSpeechDispatchResult = speak(
        message = message,
        priority = SpeechPriority.INTERACTION,
        onCompleted = onCompleted,
        onFailed = onFailed,
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

    fun speakPriorityUserTraining(
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

    /** Stops ordinary queued speech before STT, but never lets STT interrupt an active risk alert. */
    fun prepareForSpeechRecognition(): Boolean {
        val riskPending = pendingSpeechQueue.hasPriority(SpeechPriority.RISK) || synchronized(pendingUtterances) {
            pendingUtterances.any { it.startsWith("$ANNOUNCE_ASSERTIVE_PREFIX-") }
        }
        if (riskPending) return false
        progressToneGeneration += 1
        pendingSpeechQueue.clear()
        val watchdogs: List<Runnable>
        val advisoryFailures: List<() -> Unit>
        val strictTrainingFailures: List<() -> Unit>
        synchronized(pendingUtterances) {
            advisoryFailures = pendingUtterances
                .filter { it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true) }
            strictTrainingFailures = explicitTerminalRequiredUtterances
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true) }
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
        if (ttsState == TtsState.READY) textToSpeech.stop()
        advisoryFailures.forEach { it() }
        strictTrainingFailures.forEach { it() }
        progressTone?.stopTone()
        lastNavMessage = ""
        lastNavMessageAtMs = 0L
        abandonAudioFocus()
        return true
    }

    fun playProgressBeep(volumePercent: Int) {
        val volume = volumePercent.coerceIn(0, 100)
        if (volume <= 0) return
        val speechPending = synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }
        if (speechPending || !requestAudioFocus(isRisk = false)) return
        val tone = if (progressTone == null || progressToneVolumePercent != volume) {
            progressTone?.release()
            ToneGenerator(AudioManager.STREAM_MUSIC, volume).also {
                progressTone = it
                progressToneVolumePercent = volume
            }
        } else {
            progressTone
        }
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, PROGRESS_BEEP_DURATION_MS)
        val generation = ++progressToneGeneration
        mainHandler.postDelayed(
            {
                if (generation != progressToneGeneration) return@postDelayed
                val speechPending = synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }
                val progressFocusActive = synchronized(audioFocusLock) {
                    hasAudioFocus && activeAudioFocus == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                }
                if (!speechPending && progressFocusActive) {
                    abandonAudioFocus()
                }
            },
            PROGRESS_BEEP_DURATION_MS + PROGRESS_BEEP_FOCUS_MARGIN_MS,
        )
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
    ): NavigationSpeechDispatchResult {
        if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED
        if (!speechAllowed()) return NavigationSpeechDispatchResult.UNAVAILABLE
        when (ttsState) {
            TtsState.INITIALIZING -> {
                // Navigation owns an explicit speech-ack state machine and must be retried there
                // after initialization. Queuing it here would speak once without acknowledging
                // the guide, then repeat the same instruction on the next location update.
                // Automatic risk feedback is retried by WalkSafeFeedbackPolicy as long as the
                // candidate remains current; queuing it here could speak after that risk vanished.
                if (
                    priority == SpeechPriority.INTERACTION &&
                    onCompleted == null &&
                    onFailed == null
                ) {
                    pendingSpeechQueue.offer(message, priority, riskRank)
                }
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
        )
    }

    private fun flushPendingSpeech() {
        pendingSpeechQueue.drainPriorityOrder().forEach { pending ->
            if (speechAllowed()) {
                speakReady(pending.message, pending.priority, riskRank = pending.riskRank)
            }
        }
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
    ): NavigationSpeechDispatchResult {
        val isRisk = priority == SpeechPriority.RISK
        val isInteraction = priority == SpeechPriority.INTERACTION
        val isAdvisory = priority == SpeechPriority.ADVISORY
        val strictTrainingUtterance = synchronized(pendingUtterances) {
            explicitTerminalRequiredUtterances.firstOrNull()
        }
        if (strictTrainingUtterance != null) {
            if (!isRisk) return NavigationSpeechDispatchResult.SUPPRESSED
            markUtteranceFinished(strictTrainingUtterance, completed = false)
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (
            isAdvisory &&
            (
                advisoryStartDeadlineMs == null ||
                    advisoryStartValidator == null ||
                    nowMs > advisoryStartDeadlineMs ||
                    !advisoryStartValidator()
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
        if (!isAdvisory && isDuplicateMessage(message, priority, messagePriorityMs)) {
            return NavigationSpeechDispatchResult.SUPPRESSED
        }
        if (isAdvisory && synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }) {
            return NavigationSpeechDispatchResult.SUPPRESSED
        }
        val riskPending = synchronized(pendingUtterances) {
            pendingUtterances.any { it.startsWith("$ANNOUNCE_ASSERTIVE_PREFIX-") }
        }
        if (priority == SpeechPriority.NAVIGATION) {
            val pendingIds = synchronized(pendingUtterances) { pendingUtterances.toList() }
            if (pendingIds.isNotEmpty()) {
                if (pendingIds.any { !it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }) {
                    return NavigationSpeechDispatchResult.SUPPRESSED
                }
                cancelQueuedCompletionCallbacks(keepRisk = false)
                textToSpeech.stop()
            }
        }
        val queueMode = when (priority) {
            SpeechPriority.RISK -> TextToSpeech.QUEUE_FLUSH
            SpeechPriority.INTERACTION -> if (riskPending) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            SpeechPriority.NAVIGATION -> TextToSpeech.QUEUE_ADD
            SpeechPriority.ADVISORY -> TextToSpeech.QUEUE_ADD
        }
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            cancelQueuedCompletionCallbacks(
                keepRisk = priority != SpeechPriority.RISK && riskPending,
            )
        }
        val utterancePrefix = when (priority) {
            SpeechPriority.RISK -> ANNOUNCE_ASSERTIVE_PREFIX
            SpeechPriority.INTERACTION -> ANNOUNCE_INTERACTION_PREFIX
            SpeechPriority.NAVIGATION -> ANNOUNCE_NAV_PREFIX
            SpeechPriority.ADVISORY -> ANNOUNCE_ADVISORY_PREFIX
        }
        val utteranceId = "$utterancePrefix-$nowMs-${utteranceSequence.incrementAndGet()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        markUtteranceStarted(
            utteranceId,
            message.length,
            riskRank,
            onCompleted,
            onFailed,
            advisoryStartDeadlineMs,
            advisoryStartValidator,
            requiresExplicitTerminalCallback,
        )
        if (!requestAudioFocus(isRisk)) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) textToSpeech.stop()
            markUtteranceFinished(utteranceId, completed = false, notifyFailure = false)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
        val speakResult = textToSpeech.speak(message, queueMode, params, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) textToSpeech.stop()
            failRequiredSpeechRuntime(utteranceId)
            return NavigationSpeechDispatchResult.UNAVAILABLE
        }
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
        return NavigationSpeechDispatchResult.ACCEPTED
    }

    private fun vibrate(
        patternMs: LongArray?,
        cancelPriorityUserTraining: Boolean = true,
    ): Boolean {
        if (!hapticAllowed()) return false
        if (cancelPriorityUserTraining) {
            cancelPriorityUserTrainingVibration(notifyFailure = true)
        }
        val pattern = patternMs ?: return false
        val currentVibrator = vibrator ?: return false
        if (!currentVibrator.hasVibrator()) return false
        val nowMs = SystemClock.elapsedRealtime()
        if (pattern.contentEquals(lastVibrationPattern ?: longArrayOf()) && nowMs - lastVibrationAtMs < VIBRATION_DUP_WINDOW_MS) {
            return false
        }
        currentVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        lastVibrationPattern = pattern.copyOf()
        lastVibrationAtMs = nowMs
        return true
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
        val nowMs = SystemClock.elapsedRealtime()
        return when (priority) {
            SpeechPriority.RISK -> message == lastRiskMessage && nowMs - lastRiskMessageAtMs < cooldownMs
            SpeechPriority.INTERACTION ->
                message == lastInteractionMessage && nowMs - lastInteractionMessageAtMs < cooldownMs
            SpeechPriority.NAVIGATION -> message == lastNavMessage && nowMs - lastNavMessageAtMs < cooldownMs
            SpeechPriority.ADVISORY -> false
        }
    }

    private fun requestAudioFocus(isRisk: Boolean): Boolean {
        val manager = audioManager ?: return false
        val requestedGain = if (isRisk) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val currentFocusSatisfiesRequest = synchronized(audioFocusLock) {
            hasAudioFocus &&
                (activeAudioFocus == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT || activeAudioFocus == requestedGain)
        }
        if (currentFocusSatisfiesRequest) return true
        abandonAudioFocus()
        val generation = synchronized(audioFocusLock) { audioFocusGeneration + 1L }
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleAudioFocusChange(focusChange, generation)
        }
        var request: AudioFocusRequest? = null
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val requestedFocus = manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                requestedGain,
            )
            requestedFocus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (requested) {
            synchronized(audioFocusLock) {
                audioFocusGeneration = generation
                hasAudioFocus = true
                activeAudioFocus = requestedGain
                activeAudioFocusRequest = request
                activeAudioFocusListener = listener
            }
        }
        return requested
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
        releaseAudioFocusRegistration(releasedRegistration.first, releasedRegistration.second)
        val interrupted = synchronized(pendingUtterances) { pendingUtterances.toList() }
        val toneGeneration = ++progressToneGeneration
        mainHandler.post {
            if (toneGeneration == progressToneGeneration) progressTone?.stopTone()
            val replacementFocusActive = synchronized(audioFocusLock) { hasAudioFocus }
            failPendingSpeech(interrupted, stopTts = !replacementFocusActive)
        }
    }

    private fun abandonAudioFocus() {
        val registration = synchronized(audioFocusLock) {
            if (!hasAudioFocus) return
            val current = activeAudioFocusRequest to activeAudioFocusListener
            hasAudioFocus = false
            activeAudioFocus = AudioManager.AUDIOFOCUS_LOSS
            activeAudioFocusRequest = null
            activeAudioFocusListener = null
            audioFocusGeneration += 1L
            current
        }
        releaseAudioFocusRegistration(registration.first, registration.second)
    }

    private fun releaseAudioFocusRegistration(
        request: AudioFocusRequest?,
        listener: AudioManager.OnAudioFocusChangeListener?,
    ) {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { manager.abandonAudioFocusRequest(it) }
            return
        }
        listener?.let {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(it)
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
    ) {
        synchronized(pendingUtterances) {
            pendingUtterances.add(utteranceId)
            pendingUtteranceOrder.add(utteranceId)
            utteranceCallbacks.register(utteranceId, onCompleted, onFailed)
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
            val deadline = utteranceStartDeadlines[utteranceId] ?: return@synchronized null
            val validator = utteranceStartValidators[utteranceId] ?: return@synchronized null
            deadline to validator
        } ?: return true
        val isValid = SystemClock.elapsedRealtime() <= deadlineAndValidator.first &&
            runCatching(deadlineAndValidator.second).getOrDefault(false)
        if (isValid) return true
        val shouldStop = synchronized(pendingUtterances) {
            if (
                utteranceId !in pendingUtterances ||
                utteranceStartValidators[utteranceId] !== deadlineAndValidator.second
            ) {
                false
            } else {
                if (ttsState == TtsState.READY) textToSpeech.stop()
                true
            }
        }
        if (shouldStop) markUtteranceFinished(utteranceId, completed = false)
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
                pendingUtterances.remove(predecessor)
                pendingUtteranceOrder.remove(predecessor)
                startedUtterances.remove(predecessor)
                utteranceWatchdogs.remove(predecessor)?.let(inferredWatchdogs::add)
                utteranceMessageLengths.remove(predecessor)
                utteranceRiskRanks.remove(predecessor)
                utteranceStartDeadlines.remove(predecessor)
                utteranceStartValidators.remove(predecessor)
                val explicitTerminalRequired =
                    explicitTerminalRequiredUtterances.remove(predecessor)
                utteranceCallbacks.takeTerminalCallback(
                    utteranceId = predecessor,
                    completed = !explicitTerminalRequired,
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
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            val timedOut = synchronized(pendingUtterances) {
                if (utteranceWatchdogs[utteranceId] !== watchdog || utteranceId !in pendingUtterances) {
                    emptyList()
                } else {
                    pendingUtterances.toList()
                }
            }
            if (timedOut.isNotEmpty()) failPendingSpeech(timedOut)
        }
        utteranceWatchdogs[utteranceId] = watchdog
        mainHandler.postDelayed(watchdog, delayMs)
    }

    private fun armNextUtteranceStartWatchdogLocked() {
        val next = pendingUtteranceOrder.firstOrNull() ?: return
        if (next in startedUtterances || next in utteranceWatchdogs) return
        val deadlineDelayMs = utteranceStartDeadlines[next]?.let { deadline ->
            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
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
    ) {
        var terminalCallback: (() -> Unit)? = null
        var watchdog: Runnable? = null
        var shouldAbandonFocus = false
        synchronized(pendingUtterances) {
            if (utteranceId != null) {
                val removed = pendingUtterances.remove(utteranceId)
                if (removed) {
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
                    )
                    shouldAbandonFocus = pendingUtterances.isEmpty()
                    armNextUtteranceStartWatchdogLocked()
                }
            }
        }
        watchdog?.let(mainHandler::removeCallbacks)
        if (shouldAbandonFocus) abandonAudioFocus()
        terminalCallback?.invoke()
    }

    private fun failPendingSpeech(
        interrupted: List<String> = synchronized(pendingUtterances) { pendingUtterances.toList() },
        stopTts: Boolean = true,
    ) {
        if (interrupted.isEmpty()) return
        if (stopTts && ttsState == TtsState.READY) textToSpeech.stop()
        interrupted.forEach { markUtteranceFinished(it, completed = false) }
    }

    private fun cancelQueuedCompletionCallbacks(keepRisk: Boolean) {
        var watchdogs: List<Runnable> = emptyList()
        var advisoryFailures: List<() -> Unit> = emptyList()
        var strictTrainingFailures: List<() -> Unit> = emptyList()
        synchronized(pendingUtterances) {
            val cancelled = pendingUtterances.filter { utteranceId ->
                !keepRisk || !utteranceId.startsWith("$ANNOUNCE_ASSERTIVE_PREFIX-")
            }
            advisoryFailures = cancelled
                .filter { it.startsWith("$ANNOUNCE_ADVISORY_PREFIX-") }
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true) }
            strictTrainingFailures = cancelled
                .filter(explicitTerminalRequiredUtterances::contains)
                .mapNotNull { utteranceCallbacks.takeTerminalCallback(it, completed = false, notifyFailure = true) }
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
            armNextUtteranceStartWatchdogLocked()
        }
        watchdogs.forEach(mainHandler::removeCallbacks)
        advisoryFailures.forEach { it() }
        strictTrainingFailures.forEach { it() }
    }

    override fun close() {
        ready.set(false)
        ttsState = TtsState.CLOSED
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
        progressToneGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        progressTone?.release()
        progressTone = null
        vibrator?.cancel()
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private enum class TtsState {
        INITIALIZING,
        READY,
        FAILED,
        LANGUAGE_UNSUPPORTED,
        CLOSED,
    }
}
