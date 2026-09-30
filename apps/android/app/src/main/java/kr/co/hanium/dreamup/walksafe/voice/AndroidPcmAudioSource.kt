package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal interface VoskPcmSource {
    fun start()
    fun stop()
}

/** S04-27: terminal input code for a capture the system silenced (distinct from AudioRecord errors). */
internal const val PCM_INPUT_SILENCED_CODE = Int.MIN_VALUE

/**
 * Turns recording-configuration reports into one silenced edge. A silenced client still reads
 * zero-filled buffers, so without this the stream looks exactly like a user who did not speak.
 */
internal class PcmInputSilencingGate(private val onSilenced: () -> Unit) {
    private var fired = false

    /** [clientSilenced] null means "no report" and never fires. */
    fun observe(clientSilenced: Boolean?) {
        val fire = synchronized(this) {
            if (fired || clientSilenced != true) false else {
                fired = true
                true
            }
        }
        if (fire) onSilenced()
    }
}

/**
 * Captures 16 kHz mono PCM16 in memory. The caller must grant RECORD_AUDIO before [start].
 *
 * [onPcm16] runs on the single capture thread and receives a new array owned by the callback.
 * This class never writes audio to storage.
 */
internal class AndroidPcmAudioSource(
    private val context: Context,
    private val onPcm16: (ShortArray) -> Unit,
    private val onReadError: (Int) -> Unit,
    private val audioRecordFactory: (() -> AudioRecord)? = null,
    private val microphoneLease: VoiceMicrophoneLease = VoiceMicrophoneLease.process,
) : VoskPcmSource {
    private enum class State {
        IDLE,
        RUNNING,
        STOPPING,
    }

    private class CaptureSession(
        val generation: Long,
        val record: AudioRecord,
        val releaseMicrophone: () -> Unit,
    ) {
        val running = AtomicBoolean(true)
        val released = AtomicBoolean(false)
        val releaseComplete = CountDownLatch(1)
        lateinit var worker: Thread
        @Volatile
        var unwatchSilencing: () -> Unit = {}

        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                try {
                    try {
                        record.release()
                    } finally {
                        releaseMicrophone()
                    }
                } finally {
                    releaseComplete.countDown()
                }
            } else {
                var interrupted = false
                while (true) {
                    try {
                        releaseComplete.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private val stateLock = Any()

    private var state = State.IDLE
    private var nextGeneration = 1L
    private var activeSession: CaptureSession? = null

    /** Starts capture once. Calls made while running or stopping have no effect. */
    override fun start() {
        synchronized(stateLock) {
            if (state != State.IDLE) return

            val leaseOwner = Any()
            check(microphoneLease.acquire(leaseOwner)) { "Microphone is already in use" }
            val record = try {
                audioRecordFactory?.invoke() ?: createAudioRecord(context)
            } catch (failure: Throwable) {
                microphoneLease.release(leaseOwner)
                throw failure
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                try {
                    record.release()
                } finally {
                    microphoneLease.release(leaseOwner)
                }
                error("AudioRecord failed to initialize")
            }

            try {
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord failed to start"
                }
                val session = CaptureSession(
                    generation = nextGeneration++,
                    record = record,
                    releaseMicrophone = { microphoneLease.release(leaseOwner) },
                )
                session.worker = Thread(
                    { capture(session) },
                    CAPTURE_THREAD_NAME,
                )
                watchSystemSilencing(session)
                state = State.RUNNING
                activeSession = session
                session.worker.start()
            } catch (failure: Throwable) {
                activeSession?.running?.set(false)
                state = State.IDLE
                activeSession = null
                runCatching { record.release() }
                microphoneLease.release(leaseOwner)
                throw failure
            }
        }
    }

    /**
     * Stops capture on the caller's worker thread. The native stop/release can outlast the
     * two bounded joins; return only after this session's microphone release is complete.
     */
    override fun stop() {
        val session = synchronized(stateLock) {
            if (state == State.IDLE) return
            state = State.STOPPING
            activeSession?.also { it.running.set(false) } ?: return
        }

        runCatching { session.record.stop() }
        if (Thread.currentThread() === session.worker) return

        try {
            session.worker.join(STOP_JOIN_TIMEOUT_MS)
            if (session.worker.isAlive) {
                session.releaseOnce()
                session.worker.join(RELEASE_JOIN_TIMEOUT_MS)
            } else {
                session.releaseOnce()
            }
        } catch (_: InterruptedException) {
            session.releaseOnce()
            Thread.currentThread().interrupt()
        }
    }

    /** API 29+: the system reports when it silences this client's capture. */
    private fun watchSystemSilencing(session: CaptureSession) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val gate = PcmInputSilencingGate {
            if (session.running.compareAndSet(true, false)) onReadError(PCM_INPUT_SILENCED_CODE)
        }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                gate.observe(configs?.any { it.isClientSilenced })
            }
        }
        runCatching {
            session.record.registerAudioRecordingCallback(Executor { it.run() }, callback)
            session.unwatchSilencing = {
                runCatching { session.record.unregisterAudioRecordingCallback(callback) }
            }
        }
        gate.observe(runCatching { session.record.activeRecordingConfiguration?.isClientSilenced }.getOrNull())
    }

    private fun capture(session: CaptureSession) {
        val readBuffer = ShortArray(READ_CHUNK_SAMPLES)
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (session.running.get()) {
                val read = session.record.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (!session.running.get()) break
                if (read > 0) {
                    onPcm16(readBuffer.copyOf(read))
                } else if (read < 0) {
                    session.running.set(false)
                    onReadError(read)
                    break
                }
            }
        } catch (_: Exception) {
            // A callback/AudioRecord exception has the same terminal ownership as a read error.
            if (session.running.compareAndSet(true, false)) {
                onReadError(AudioRecord.ERROR_INVALID_OPERATION)
            }
        } finally {
            try {
                session.unwatchSilencing()
                runCatching { session.record.stop() }
                session.releaseOnce()
            } finally {
                synchronized(stateLock) {
                    val current = activeSession
                    if (current === session && current.generation == session.generation) {
                        session.running.set(false)
                        state = State.IDLE
                        activeSession = null
                    }
                }
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val READ_CHUNK_SAMPLES = 320
        const val BYTES_PER_PCM16_SAMPLE = 2
        const val CAPTURE_THREAD_NAME = "walksafe-pcm-capture"
        const val STOP_JOIN_TIMEOUT_MS = 500L
        const val RELEASE_JOIN_TIMEOUT_MS = 500L

        fun createAudioRecord(context: Context): AudioRecord {
            if (
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("RECORD_AUDIO permission is required")
            }
            val minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBufferBytes > 0) {
                "16 kHz mono PCM16 is unavailable: AudioRecord error $minBufferBytes"
            }
            val bufferSizeBytes = maxOf(
                minBufferBytes,
                READ_CHUNK_SAMPLES * BYTES_PER_PCM16_SAMPLE,
            )
            return AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            )
        }
    }
}
