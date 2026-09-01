package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    private enum class State {
        IDLE,
        RUNNING,
        STOPPING,
    }

    private class CaptureSession(
        val generation: Long,
        val record: AudioRecord,
    ) {
        val running = AtomicBoolean(true)
        val released = AtomicBoolean(false)
        lateinit var worker: Thread

        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                record.release()
            }
        }
    }

    private val stateLock = Any()

    private var state = State.IDLE
    private var nextGeneration = 1L
    private var activeSession: CaptureSession? = null

    /** Starts capture once. Calls made while running or stopping have no effect. */
    fun start() {
        synchronized(stateLock) {
            if (state != State.IDLE) return

            val record = audioRecordFactory?.invoke() ?: createAudioRecord(context)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
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
                )
                session.worker = Thread(
                    { capture(session) },
                    CAPTURE_THREAD_NAME,
                )
                state = State.RUNNING
                activeSession = session
                session.worker.start()
            } catch (failure: Throwable) {
                activeSession?.running?.set(false)
                state = State.IDLE
                activeSession = null
                runCatching { record.release() }
                throw failure
            }
        }
    }

    /**
     * Requests stop and waits at most one second. If needed, it forces one release halfway
     * through; a still-stuck session prevents restart until its worker actually exits.
     */
    fun stop() {
        val session = synchronized(stateLock) {
            if (state == State.IDLE) return
            state = State.STOPPING
            activeSession?.also { it.running.set(false) } ?: return
        }

        runCatching { session.record.stop() }
        if (Thread.currentThread() === session.worker) return

        session.worker.join(STOP_JOIN_TIMEOUT_MS)
        if (session.worker.isAlive) {
            session.releaseOnce()
            session.worker.join(RELEASE_JOIN_TIMEOUT_MS)
        }
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
        } finally {
            try {
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
