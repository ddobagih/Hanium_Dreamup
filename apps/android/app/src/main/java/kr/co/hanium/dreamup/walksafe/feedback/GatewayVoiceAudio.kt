package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.voice.VoiceInputFailure
import kr.co.hanium.dreamup.walksafe.voice.VoiceInputRouteGuard
import kr.co.hanium.dreamup.walksafe.voice.VoiceMicrophoneLease

internal class ForegroundAacRecorder(
    private val cacheDirectory: File,
    private val onLimitReached: () -> Unit,
    private val onInputFailure: (VoiceInputFailure) -> Unit = {},
    private val microphoneLease: VoiceMicrophoneLease = VoiceMicrophoneLease.process,
) {
    private class ActiveRecording(
        val recorder: MediaRecorder,
        val file: File,
        val startedAtElapsedRealtimeMs: Long,
        val leaseOwner: Any,
    ) {
        val routeGuard = VoiceInputRouteGuard()
        var unwatch: () -> Unit = {}
        var poll: Runnable? = null
    }
    private var active: ActiveRecording? = null
    private val handler = Handler(Looper.getMainLooper())
    val isRecording: Boolean get() = active != null

    private fun rejectInvalidInput(recording: ActiveRecording): Boolean {
        if (active !== recording) return true
        val failure = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && recording.routeGuard.observe(
                runCatching { recording.recorder.routedDevice?.id }.getOrNull(),
            ) -> VoiceInputFailure.ROUTE_CHANGED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                runCatching { recording.recorder.activeRecordingConfiguration?.isClientSilenced }.getOrNull() == true ->
                VoiceInputFailure.SILENCED
            else -> return false
        }
        cancel()
        onInputFailure(failure)
        return true
    }

    private fun watchInput(recording: ActiveRecording) {
        var unwatchRoute: () -> Unit = {}
        var unwatchSilencing: () -> Unit = {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            recording.routeGuard.observe(runCatching { recording.recorder.routedDevice?.id }.getOrNull())
            val callback = AudioRouting.OnRoutingChangedListener { rejectInvalidInput(recording) }
            recording.recorder.addOnRoutingChangedListener(callback, handler)
            unwatchRoute = { runCatching { recording.recorder.removeOnRoutingChangedListener(callback) }; Unit }
        }
        recording.unwatch = { unwatchRoute(); unwatchSilencing() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    if (active === recording && configs?.any { it.isClientSilenced } == true) {
                        cancel()
                        onInputFailure(VoiceInputFailure.SILENCED)
                    }
                }
            }
            recording.recorder.registerAudioRecordingCallback(Executor { handler.post(it) }, callback)
            unwatchSilencing = { runCatching { recording.recorder.unregisterAudioRecordingCallback(callback) }; Unit }
        }
        val poll = object : Runnable {
            override fun run() {
                if (!rejectInvalidInput(recording)) handler.postDelayed(this, 100L)
            }
        }
        recording.poll = poll
        if (!rejectInvalidInput(recording)) handler.postDelayed(poll, 100L)
    }

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        check(active == null) { "voice recording is already active" }
        if (!cacheDirectory.isDirectory) return false
        val leaseOwner = Any()
        if (!microphoneLease.acquire(leaseOwner)) return false
        var file: File? = null
        var recorder: MediaRecorder? = null
        return runCatching {
            val createdFile = File.createTempFile("walksafe-command-", ".m4a", cacheDirectory)
            file = createdFile
            val createdRecorder = MediaRecorder()
            recorder = createdRecorder
            createdRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            createdRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            createdRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            createdRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE_HZ)
            createdRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            createdRecorder.setMaxDuration(MAX_RECORDING_DURATION_MS)
            createdRecorder.setMaxFileSize(MAX_RECORDING_BYTES.toLong())
            createdRecorder.setOutputFile(createdFile.absolutePath)
            createdRecorder.setOnInfoListener { source, what, _ ->
                val recording = active
                if (recording?.recorder === source && !rejectInvalidInput(recording) &&
                    (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) onLimitReached()
            }
            createdRecorder.prepare()
            createdRecorder.start()
            val recording = ActiveRecording(createdRecorder, createdFile, SystemClock.elapsedRealtime(), leaseOwner)
            active = recording
            watchInput(recording)
            active === recording
        }.getOrElse {
            val recording = active
            if (recording?.leaseOwner === leaseOwner) cancel()
            else { runCatching { recorder?.release() }; microphoneLease.release(leaseOwner); file?.delete() }
            false
        }
    }

    private fun retire(recording: ActiveRecording) {
        active = null
        recording.poll?.let(handler::removeCallbacks)
        recording.poll = null
        runCatching { recording.unwatch() }
        runCatching { recording.recorder.setOnInfoListener(null) }
    }

    /** Caller owns the returned cache file and must delete it after upload. */
    fun stop(): File? {
        val recording = active ?: return null
        if (rejectInvalidInput(recording)) return null
        retire(recording)
        val stopped = runCatching { recording.recorder.stop() }.isSuccess
        try { runCatching { recording.recorder.release() } }
        finally { microphoneLease.release(recording.leaseOwner) }
        val elapsed = SystemClock.elapsedRealtime() - recording.startedAtElapsedRealtimeMs
        if (!stopped || elapsed !in 1..MAX_RECORDING_DURATION_WITH_STOP_GRACE_MS ||
            !recording.file.isFile || recording.file.length() !in 1..MAX_RECORDING_BYTES.toLong()) {
            recording.file.delete()
            return null
        }
        return recording.file
    }

    fun cancel() {
        val recording = active ?: return
        retire(recording)
        try { runCatching { recording.recorder.stop() }; runCatching { recording.recorder.release() } }
        finally { microphoneLease.release(recording.leaseOwner); recording.file.delete() }
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
