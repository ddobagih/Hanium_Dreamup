package kr.co.hanium.dreamup.walksafe.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
import java.util.IdentityHashMap
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import kr.co.hanium.dreamup.walksafe.voice.selectInstalledOfflineKoreanVoice

/** Android effects only. Admission, engine generations and terminal ownership stay in the actuator. */
internal class AndroidFeedbackSpeechRuntime(context: Context) : FeedbackSpeechRuntime {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private lateinit var textToSpeech: TextToSpeech
    // The listener is the runtime's registration token. Retain its exact native request until
    // the actuator releases that token, including a late grant after close or a nested request.
    private val focusRequests = IdentityHashMap<AudioManager.OnAudioFocusChangeListener, AudioFocusRequest>()
    override val scheduler: FeedbackSpeechScheduler = AndroidFeedbackSpeechScheduler()
    override val engineInitialized: Boolean get() = ::textToSpeech.isInitialized

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun initialize(onInitialized: (Int) -> Unit) {
        textToSpeech = TextToSpeech(appContext) { status -> onInitialized(status) }
    }

    override fun setLanguage(language: Locale): Int = textToSpeech.setLanguage(language)

    override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection =
        selectInstalledOfflineKoreanVoice(textToSpeech)

    override fun configureAudioAttributes() {
        textToSpeech.setAudioAttributes(speechAudioAttributes())
    }

    override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int =
        textToSpeech.setOnUtteranceProgressListener(AndroidFeedbackUtteranceListener(listener))

    override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        return textToSpeech.speak(message, queueMode, params, utteranceId)
    }

    override fun stop() { textToSpeech.stop() }
    override fun shutdown() = textToSpeech.shutdown()

    /** Real engine observation for device tests; does not substitute ledger or terminal completion. */
    internal fun isSpeaking(): Boolean = textToSpeech.isSpeaking

    override fun requestAudioFocus(
        isRisk: Boolean,
        listener: AudioManager.OnAudioFocusChangeListener,
    ): Boolean {
        val manager = audioManager ?: return false
        val gain = if (isRisk) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(speechAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
            synchronized(focusRequests) { focusRequests[listener] = request }
            // Do not hold the map lock across a native call: it may invoke or wait for a callback.
            // If this throws, the actuator releases this exact listener/request in its catch path.
            val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!granted) synchronized(focusRequests) { focusRequests.remove(listener) }
            return granted
        }
        @Suppress("DEPRECATION")
        return manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = synchronized(focusRequests) { focusRequests.remove(listener) } ?: return
            manager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(listener)
        }
    }

    override fun createProgressTone(volumePercent: Int): ProgressToneEffects =
        AndroidProgressToneEffects(volumePercent)

    override fun vibrate(patternMs: LongArray): Boolean {
        val current = vibrator ?: return false
        if (!current.hasVibrator()) return false
        current.vibrate(VibrationEffect.createWaveform(patternMs, -1))
        return true
    }

    override fun cancelVibration() { vibrator?.cancel() }

    override fun logSpeechEvent(event: String, utteranceId: String?) {
        if (!BuildConfig.DEBUG) return
        // IDs contain only priority, monotonic time and sequence; never record spoken text.
        Log.d("WalkSafeVoiceOutput", "event=$event utterance=${utteranceId ?: "none"}")
    }

    private fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

/** Keeps native START/DONE/ERROR/STOP callbacks intact for the actuator's generation guard. */
internal class AndroidFeedbackUtteranceListener(
    private val listener: FeedbackSpeechRuntime.Listener,
) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) = listener.onStart(utteranceId)
    override fun onDone(utteranceId: String?) = listener.onDone(utteranceId)
    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) = listener.onError(utteranceId)
    override fun onError(utteranceId: String?, errorCode: Int) = listener.onError(utteranceId, errorCode)
    override fun onStop(utteranceId: String?, interrupted: Boolean) = listener.onStop(utteranceId, interrupted)
}

private class AndroidProgressToneEffects(volumePercent: Int) : ProgressToneEffects {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent)
    override fun startTone(toneType: Int, durationMs: Int): Boolean = tone.startTone(toneType, durationMs)
    override fun stopTone() = tone.stopTone()
    override fun release() = tone.release()
}

private class AndroidFeedbackSpeechScheduler : FeedbackSpeechScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(task: Runnable): Boolean = handler.post(task)
    override fun postDelayed(task: Runnable, delayMs: Long): Boolean = handler.postDelayed(task, delayMs)
    override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
    override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
}
