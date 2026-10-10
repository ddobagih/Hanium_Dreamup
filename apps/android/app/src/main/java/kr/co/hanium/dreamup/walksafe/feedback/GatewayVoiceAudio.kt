package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioRouting
import kr.co.hanium.dreamup.walksafe.voice.VoiceInputRouteGuard
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.Build
import java.io.File

internal class ForegroundAacRecorder(
    private val cacheDirectory: File,
    private val onInputSilenced: (() -> Unit)? = null,
    private val onInputRouteChanged: (() -> Unit)? = null,
    private val onCaptureLimitExceeded: (() -> Unit)? = null,
    private val onSpeechEnded: () -> Unit,
) {
    private data class ActiveRecording(
        val recorder: MediaRecorder,
        val file: File,
        val startedAtElapsedRealtimeMs: Long,
        val routeGuard: VoiceInputRouteGuard = VoiceInputRouteGuard(),
        var unwatchRoute: () -> Unit = {},
    )

    private var active: ActiveRecording? = null
    private val handler = Handler(Looper.getMainLooper())
    private var silencePoll: Runnable? = null

    private fun watchSpeechEnd(recording: ActiveRecording) {
        val endpoint = SpeechEndpointPolicy(recording.startedAtElapsedRealtimeMs)
        val poll = object : Runnable {
            override fun run() {
                if (active !== recording || rejectChangedRoute(recording)) return
                if (endpoint.captureLimitExceeded(SystemClock.elapsedRealtime())) {
                    rejectCaptureLimit(recording)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    runCatching { recording.recorder.activeRecordingConfiguration?.isClientSilenced }.getOrNull() == true
                ) {
                    // Never upload OS-silenced audio as if the user simply did not speak.
                    cancel()
                    onInputSilenced?.invoke()
                    return
                }
                val amplitude = runCatching { recording.recorder.maxAmplitude }.getOrDefault(0)
                if (endpoint.shouldStop(SystemClock.elapsedRealtime(), amplitude)) {
                    silencePoll = null
                    onSpeechEnded()
                } else handler.postDelayed(this, 100L)
            }
        }
        silencePoll = poll
        handler.postDelayed(poll, 100L)
    }

    private fun rejectChangedRoute(recording: ActiveRecording): Boolean {
        if (active !== recording || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val deviceId = runCatching { recording.recorder.routedDevice?.id }.getOrNull()
        if (!recording.routeGuard.observe(deviceId)) return false
        cancel()
        onInputRouteChanged?.invoke()
        return true
    }

    private fun watchInputRoute(recording: ActiveRecording) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        recording.routeGuard.observe(recording.recorder.routedDevice?.id)
        val callback = AudioRouting.OnRoutingChangedListener {
            if (active === recording) rejectChangedRoute(recording)
        }
        recording.recorder.addOnRoutingChangedListener(callback, handler)
        recording.unwatchRoute = {
            runCatching { recording.recorder.removeOnRoutingChangedListener(callback) }
        }
    }

    private fun cancelSilencePoll() {
        silencePoll?.let(handler::removeCallbacks)
        silencePoll = null
    }

    private fun rejectCaptureLimit(recording: ActiveRecording) {
        if (active !== recording) return
        cancel()
        onCaptureLimitExceeded?.invoke()
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
                ) {
                    val recording = active
                    if (recording?.recorder === recorder && !rejectChangedRoute(recording)) rejectCaptureLimit(recording)
                }
            }
            recorder.prepare()
            recorder.start()
            val recording = ActiveRecording(recorder, file, SystemClock.elapsedRealtime())
            active = recording
            watchInputRoute(recording)
            watchSpeechEnd(recording)
            true
        }.getOrElse {
            active?.unwatchRoute?.invoke()
            active = null
            cancelSilencePoll()
            runCatching { recorder.release() }
            file.delete()
            false
        }
    }

    /** Caller owns the returned cache file and must delete it after upload. */
    fun stop(): File? {
        val recording = active ?: return null
        if (rejectChangedRoute(recording)) return null
        if (SpeechEndpointPolicy(recording.startedAtElapsedRealtimeMs).captureLimitExceeded(SystemClock.elapsedRealtime())) {
            rejectCaptureLimit(recording)
            return null
        }
        active = null
        cancelSilencePoll()
        recording.unwatchRoute()
        recording.recorder.setOnInfoListener(null)
        val stopped = runCatching { recording.recorder.stop() }.isSuccess
        runCatching { recording.recorder.release() }
        val elapsed = SystemClock.elapsedRealtime() - recording.startedAtElapsedRealtimeMs
        if (
            !stopped ||
            elapsed !in 1L until MAX_RECORDING_DURATION_MS.toLong() ||
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
        recording.unwatchRoute()
        recording.recorder.setOnInfoListener(null)
        runCatching { recording.recorder.stop() }
        runCatching { recording.recorder.release() }
        recording.file.delete()
    }

    private companion object {
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val AUDIO_BIT_RATE = 64_000
        const val MAX_RECORDING_DURATION_MS = SpeechEndpointPolicy.MAX_RECORDING_DURATION_MS
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
