package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.io.File

internal class ForegroundAacRecorder(
    private val cacheDirectory: File,
    private val onLimitReached: () -> Unit,
) {
    private data class ActiveRecording(
        val recorder: MediaRecorder,
        val file: File,
        val startedAtElapsedRealtimeMs: Long,
    )

    private var active: ActiveRecording? = null
    private val handler = Handler(Looper.getMainLooper())
    private var silencePoll: Runnable? = null

    private fun watchSpeechEnd(recording: ActiveRecording) {
        val endpoint = SpeechEndpointPolicy(recording.startedAtElapsedRealtimeMs)
        val poll = object : Runnable {
            override fun run() {
                if (active !== recording) return
                val amplitude = runCatching { recording.recorder.maxAmplitude }.getOrDefault(0)
                if (endpoint.shouldStop(SystemClock.elapsedRealtime(), amplitude)) {
                    silencePoll = null
                    onLimitReached()
                } else handler.postDelayed(this, 100L)
            }
        }
        silencePoll = poll
        handler.postDelayed(poll, 100L)
    }

    private fun cancelSilencePoll() {
        silencePoll?.let(handler::removeCallbacks)
        silencePoll = null
    }

    val isRecording: Boolean
        get() = active != null

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        check(active == null) { "voice recording is already active" }
        if (!cacheDirectory.isDirectory) return false
        val file = File.createTempFile("walksafe-command-", ".m4a", cacheDirectory)
        val recorder = MediaRecorder()
        return runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE_HZ)
            recorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            recorder.setMaxDuration(MAX_RECORDING_DURATION_MS)
            recorder.setMaxFileSize(MAX_RECORDING_BYTES.toLong())
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnInfoListener { _, what, _ ->
                if (
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) onLimitReached()
            }
            recorder.prepare()
            recorder.start()
            val recording = ActiveRecording(recorder, file, SystemClock.elapsedRealtime())
            active = recording
            watchSpeechEnd(recording)
            true
        }.getOrElse {
            runCatching { recorder.release() }
            file.delete()
            false
        }
    }

    /** Caller owns the returned cache file and must delete it after upload. */
    fun stop(): File? {
        val recording = active ?: return null
        active = null
        cancelSilencePoll()
        recording.recorder.setOnInfoListener(null)
        val stopped = runCatching { recording.recorder.stop() }.isSuccess
        runCatching { recording.recorder.release() }
        val elapsed = SystemClock.elapsedRealtime() - recording.startedAtElapsedRealtimeMs
        if (
            !stopped ||
            elapsed !in 1..MAX_RECORDING_DURATION_WITH_STOP_GRACE_MS ||
            !recording.file.isFile ||
            recording.file.length() !in 1..MAX_RECORDING_BYTES.toLong()
        ) {
            recording.file.delete()
            return null
        }
        return recording.file
    }

    fun cancel() {
        val recording = active ?: return
        active = null
        cancelSilencePoll()
        recording.recorder.setOnInfoListener(null)
        runCatching { recording.recorder.stop() }
        runCatching { recording.recorder.release() }
        recording.file.delete()
    }

    private companion object {
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val AUDIO_BIT_RATE = 64_000
        const val MAX_RECORDING_DURATION_MS = 12_000
        const val MAX_RECORDING_DURATION_WITH_STOP_GRACE_MS = 14_000L
        const val MAX_RECORDING_BYTES = 1 * 1024 * 1024
    }
}

internal class TemporaryGatewayWavPlayer {
    private var activePlayer: MediaPlayer? = null
    private var activeFile: File? = null

    fun play(
        temporaryFile: File,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        cancel()
        if (!temporaryFile.isFile) {
            temporaryFile.delete()
            return false
        }
        val player = MediaPlayer()
        activePlayer = player
        activeFile = temporaryFile
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        player.setOnCompletionListener {
            if (activePlayer !== player) return@setOnCompletionListener
            releaseActive(deleteFile = true)
            onCompleted()
        }
        player.setOnErrorListener { _, _, _ ->
            if (activePlayer !== player) return@setOnErrorListener true
            releaseActive(deleteFile = true)
            onFailed()
            true
        }
        return runCatching {
            player.setDataSource(temporaryFile.absolutePath)
            player.prepare()
            player.start()
            true
        }.getOrElse {
            releaseActive(deleteFile = true)
            false
        }
    }

    fun cancel() {
        releaseActive(deleteFile = true)
    }

    private fun releaseActive(deleteFile: Boolean) {
        val player = activePlayer
        val file = activeFile
        activePlayer = null
        activeFile = null
        if (player != null) {
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        if (deleteFile) file?.delete()
    }
}
