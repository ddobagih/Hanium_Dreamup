package kr.co.hanium.dreamup.walksafe.feedback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AndroidFeedbackActuator(
    context: Context,
) : TextToSpeech.OnInitListener, Closeable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val ready = AtomicBoolean(false)
    private val textToSpeech = TextToSpeech(appContext, this)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    private var hasAudioFocus = false
    private var activeAudioFocus = AudioManager.AUDIOFOCUS_LOSS
    private var activeAudioFocusRequest: AudioFocusRequest? = null
    private val pendingUtterances = mutableSetOf<String>()
    private var lastVibrationPattern: LongArray? = null
    private var lastVibrationAtMs = 0L
    private var lastRiskMessage = ""
    private var lastRiskMessageAtMs = 0L
    private var lastNavMessage = ""
    private var lastNavMessageAtMs = 0L
    private var progressTone: ToneGenerator? = null
    private var progressToneVolumePercent = -1

    companion object {
        private const val ANNOUNCE_ASSERTIVE_PREFIX = "risk"
        private const val ANNOUNCE_NAV_PREFIX = "nav"
        private const val RISK_DUP_WINDOW_MS = 800L
        private const val NAV_DUP_WINDOW_MS = 2_000L
        private const val VIBRATION_DUP_WINDOW_MS = 700L
        private const val PROGRESS_BEEP_DURATION_MS = 80
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.KOREAN
            textToSpeech.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            textToSpeech.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        markUtteranceFinished(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        markUtteranceFinished(utteranceId)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        markUtteranceFinished(utteranceId)
                    }
                },
            )
            ready.set(true)
        }
    }

    fun emit(action: FeedbackAction) {
        speak(
            message = action.message,
            queueMode = TextToSpeech.QUEUE_FLUSH,
            utterancePrefix = ANNOUNCE_ASSERTIVE_PREFIX,
            isRisk = true,
            messagePriorityMs = RISK_DUP_WINDOW_MS,
        )
        vibrate(action.vibrationPatternMs)
    }

    fun vibrateRiskOnly(action: FeedbackAction) {
        vibrate(action.vibrationPatternMs)
    }

    fun speakNavigation(message: String) {
        speak(
            message = message,
            queueMode = TextToSpeech.QUEUE_ADD,
            utterancePrefix = ANNOUNCE_NAV_PREFIX,
            isRisk = false,
            messagePriorityMs = NAV_DUP_WINDOW_MS,
        )
    }

    fun playProgressBeep(volumePercent: Int) {
        val volume = volumePercent.coerceIn(0, 100)
        if (volume <= 0) return
        requestAudioFocus(isRisk = false)
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
    }

    fun statusText(): String {
        return if (ready.get()) "tts=ready" else "tts=initializing"
    }

    private fun speak(
        message: String,
        queueMode: Int,
        utterancePrefix: String,
        isRisk: Boolean,
        messagePriorityMs: Long,
    ) {
        if (!ready.get() || message.isBlank()) return
        if (isDuplicateMessage(message, isRisk, messagePriorityMs)) return
        requestAudioFocus(isRisk)
        val nowMs = SystemClock.elapsedRealtime()
        val utteranceId = "$utterancePrefix-$nowMs"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        markUtteranceStarted(utteranceId)
        val speakResult = textToSpeech.speak(message, queueMode, params, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            markUtteranceFinished(utteranceId)
            return
        }
        if (isRisk) {
            lastRiskMessage = message
            lastRiskMessageAtMs = nowMs
        } else {
            lastNavMessage = message
            lastNavMessageAtMs = nowMs
        }
    }

    private fun vibrate(patternMs: LongArray?) {
        val pattern = patternMs ?: return
        val currentVibrator = vibrator ?: return
        if (!currentVibrator.hasVibrator()) return
        val nowMs = SystemClock.elapsedRealtime()
        if (pattern.contentEquals(lastVibrationPattern ?: longArrayOf()) && nowMs - lastVibrationAtMs < VIBRATION_DUP_WINDOW_MS) {
            return
        }
        currentVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        lastVibrationPattern = pattern.copyOf()
        lastVibrationAtMs = nowMs
    }

    private fun isDuplicateMessage(message: String, isRisk: Boolean, cooldownMs: Long): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        return if (isRisk) {
            message == lastRiskMessage && nowMs - lastRiskMessageAtMs < cooldownMs
        } else {
            message == lastNavMessage && nowMs - lastNavMessageAtMs < cooldownMs
        }
    }

    private fun requestAudioFocus(isRisk: Boolean) {
        val manager = audioManager ?: return
        val requestedGain = if (isRisk) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        if (hasAudioFocus && activeAudioFocus == requestedGain) return
        if (hasAudioFocus) {
            abandonAudioFocus()
        }
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(requestedGain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) {
                activeAudioFocusRequest = request
            }
            granted
        } else {
            @Suppress("DEPRECATION")
            val requestedFocus = manager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                requestedGain,
            )
            requestedFocus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (requested) {
            hasAudioFocus = true
            activeAudioFocus = requestedGain
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (!hasAudioFocus) return
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeAudioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
                ?: manager.abandonAudioFocus(audioFocusChangeListener)
            activeAudioFocusRequest = null
            return
        }
        @Suppress("DEPRECATION")
        manager.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun markUtteranceStarted(utteranceId: String) {
        synchronized(pendingUtterances) {
            pendingUtterances.add(utteranceId)
        }
    }

    private fun markUtteranceFinished(utteranceId: String?) {
        synchronized(pendingUtterances) {
            if (utteranceId != null) {
                pendingUtterances.remove(utteranceId)
            }
            if (pendingUtterances.isNotEmpty()) return
        }
        abandonAudioFocus()
    }

    override fun close() {
        ready.set(false)
        abandonAudioFocus()
        progressTone?.release()
        progressTone = null
        textToSpeech.stop()
        textToSpeech.shutdown()
    }
}
