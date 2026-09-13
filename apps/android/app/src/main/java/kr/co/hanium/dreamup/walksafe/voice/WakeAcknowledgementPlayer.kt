package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

internal enum class WakeAcknowledgementResult { COMPLETED, FAILED, CANCELLED }

/**
 * One short wake cue, with resources released before its terminal callback.
 * COMPLETED means the bounded tone interval elapsed and the tone was stopped;
 * ToneGenerator has no playback-completion listener and cannot prove audibility.
 * [isAppSpeechActive] must describe speech only, excluding this player's own cue.
 */
internal class WakeAcknowledgementPlayer(
    context: Context,
    private val isAppSpeechActive: () -> Boolean,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : Closeable {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var tone: ToneGenerator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pending: Runnable? = null
    private var callback: ((WakeAcknowledgementResult) -> Unit)? = null
    private var generation = 0L
    private var closed = false

    fun isPlaying(): Boolean = callback != null

    /** All entry points and callbacks run on the main thread. Repeated play never replaces a cue. */
    fun play(onFinished: (WakeAcknowledgementResult) -> Unit) {
        checkMainThread()
        if (closed || isPlaying() || runCatching(isAppSpeechActive).getOrDefault(true)) {
            onFinished(WakeAcknowledgementResult.FAILED)
            return
        }
        val currentGeneration = ++generation
        callback = onFinished
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setOnAudioFocusChangeListener({ change ->
                    if (change < 0 && generation == currentGeneration) {
                        finish(WakeAcknowledgementResult.FAILED)
                    }
                }, mainHandler)
                .build()
            focusRequest = request
            if (audioManager?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                finish(WakeAcknowledgementResult.FAILED)
                return
            }
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            if (tone?.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS.toInt()) != true) {
                finish(WakeAcknowledgementResult.FAILED)
                return
            }
        } catch (_: RuntimeException) {
            finish(WakeAcknowledgementResult.FAILED)
            return
        }
        val finishAtMs = SystemClock.elapsedRealtime() + TONE_DURATION_MS + QUIET_MARGIN_MS
        lateinit var poll: Runnable
        poll = Runnable {
            if (pending !== poll || generation != currentGeneration) return@Runnable
            when {
                runCatching(isAppSpeechActive).getOrDefault(true) -> finish(WakeAcknowledgementResult.FAILED)
                SystemClock.elapsedRealtime() >= finishAtMs -> finish(WakeAcknowledgementResult.COMPLETED)
                else -> mainHandler.postDelayed(poll, POLL_MS)
            }
        }
        pending = poll
        mainHandler.postDelayed(poll, POLL_MS)
    }

    fun cancel() {
        checkMainThread()
        finish(WakeAcknowledgementResult.CANCELLED)
    }

    override fun close() {
        checkMainThread()
        closed = true
        cancel()
    }

    private fun finish(result: WakeAcknowledgementResult) {
        generation += 1L
        val completed = callback
        callback = null
        pending?.let(mainHandler::removeCallbacks)
        pending = null
        val finishedTone = tone
        tone = null
        val finishedFocus = focusRequest
        focusRequest = null
        val stopped = runCatching { finishedTone?.stopTone() }.isSuccess
        val released = runCatching { finishedTone?.release() }.isSuccess
        runCatching { finishedFocus?.let { audioManager?.abandonAudioFocusRequest(it) } }
        val terminal = if (result == WakeAcknowledgementResult.COMPLETED && (!stopped || !released)) {
            WakeAcknowledgementResult.FAILED
        } else {
            result
        }
        completed?.invoke(terminal)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WakeAcknowledgementPlayer must be called on the main thread"
        }
    }

    internal companion object {
        const val TONE_DURATION_MS = 120L
        private const val QUIET_MARGIN_MS = 60L
        private const val POLL_MS = 30L
    }
}
