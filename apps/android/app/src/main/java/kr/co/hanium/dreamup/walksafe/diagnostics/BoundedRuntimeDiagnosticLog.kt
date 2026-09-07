package kr.co.hanium.dreamup.walksafe.diagnostics

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.voice.OfflineSpeechEngine
import kr.co.hanium.dreamup.walksafe.voice.OfflineSpeechSelection
import kr.co.hanium.dreamup.walksafe.voice.VoiceInputDiagnosticEvent
import kr.co.hanium.dreamup.walksafe.voice.VoskStreamingError
import kr.co.hanium.dreamup.walksafe.voice.VoskWakePhraseProbeFailure
import kr.co.hanium.dreamup.walksafe.voice.formatVoiceInputDiagnostic

internal enum class RuntimeDiagnosticDomain { VOICE_DIALOG, NAVIGATION }
internal enum class RuntimeDiagnosticOrigin { MICROPHONE_PATH, SYNTHETIC_FINAL, UNKNOWN }

/** Only compile-time enum names and a bounded page index enter this formatter. */
internal fun formatRuntimeDiagnosticState(
    domain: RuntimeDiagnosticDomain,
    stage: Enum<*>,
    reason: Enum<*>? = null,
    context: Enum<*>? = null,
    origin: RuntimeDiagnosticOrigin = RuntimeDiagnosticOrigin.UNKNOWN,
    pageIndex: Int? = null,
): String {
    fun token(value: Enum<*>?): String =
        value?.name?.takeIf { it.length <= 64 && it.matches(Regex("[A-Z][A-Z0-9_]*")) } ?: "NA"
    val page = pageIndex?.takeIf { it in 0..9_999 }?.toString() ?: "NA"
    return "event=" + domain.name + " stage=" + token(stage) +
        " reason=" + token(reason) + " context=" + token(context) +
        " origin=" + origin.name + " page_index=" + page
}

/**
 * Best-effort DEBUG metadata only. No arbitrary message/exception/audio/identity API.
 * Cache files can be evicted by Android; this is bounded diagnostic retention, not an audit log.
 */
internal object BoundedRuntimeDiagnosticLog {
    @Volatile private var writer: RuntimeDiagnosticFileWriter? = null

    @Synchronized
    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG || writer != null) return
        runCatching {
            writer = RuntimeDiagnosticFileWriter(
                File(context.applicationContext.cacheDir, "runtime-diagnostics"),
            )
        }
    }

    fun recordState(
        domain: RuntimeDiagnosticDomain,
        stage: Enum<*>,
        reason: Enum<*>? = null,
        context: Enum<*>? = null,
        origin: RuntimeDiagnosticOrigin = RuntimeDiagnosticOrigin.UNKNOWN,
        pageIndex: Int? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val target = writer ?: return
        runCatching {
            target.enqueue(
                SystemClock.elapsedRealtime(),
                formatRuntimeDiagnosticState(domain, stage, reason, context, origin, pageIndex),
            )
        }
    }

    fun recordVoiceInput(
        event: VoiceInputDiagnosticEvent,
        backend: OfflineSpeechEngine? = null,
        confidence: Float? = null,
        resultCount: Int? = null,
        errorCode: Int? = null,
        matched: Boolean? = null,
        selection: OfflineSpeechSelection? = null,
        failure: VoskWakePhraseProbeFailure? = null,
        streamingError: VoskStreamingError? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val target = writer ?: return
        runCatching {
            target.enqueue(
                SystemClock.elapsedRealtime(),
                formatVoiceInputDiagnostic(
                    event, backend, confidence, resultCount, errorCode, matched,
                    selection, failure, streamingError,
                ),
            )
        }
    }
}

/** All filesystem work runs on this single bounded worker, never the recognition/UI caller. */
private class RuntimeDiagnosticFileWriter(private val directory: File) {
    private val current = File(directory, "events.log")
    private val previous = File(directory, "events.previous.log")
    private val dropped = AtomicLong()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "walksafe-debug-metadata").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    // This raw string entry is private to this file and used only by the two typed formatters.
    fun enqueue(elapsedMs: Long, metadata: String) {
        try {
            executor.execute {
                try {
                    val bytes = (
                        "elapsed_ms=" + elapsedMs.coerceAtLeast(0L) +
                            " dropped_total=" + dropped.get() + " " + metadata + "\n"
                        ).toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_RECORD_BYTES) {
                        dropped.incrementAndGet()
                        return@execute
                    }
                    if (!directory.isDirectory && !directory.mkdirs()) {
                        dropped.incrementAndGet()
                        return@execute
                    }
                    // Only this logger's two fixed files are ever removed or replaced.
                    if (previous.length() > MAX_FILE_BYTES && !previous.delete()) {
                        dropped.incrementAndGet()
                        return@execute
                    }
                    if (current.length() > MAX_FILE_BYTES && !current.delete()) {
                        dropped.incrementAndGet()
                        return@execute
                    }
                    if (current.length() + bytes.size > MAX_FILE_BYTES) {
                        if (previous.exists() && !previous.delete()) {
                            dropped.incrementAndGet()
                            return@execute
                        }
                        if (current.exists() && !current.renameTo(previous)) {
                            dropped.incrementAndGet()
                            return@execute
                        }
                    }
                    current.appendBytes(bytes)
                } catch (_: Throwable) {
                    dropped.incrementAndGet()
                }
            }
        } catch (_: Throwable) {
            // A full queue or failed worker must never delay or fail the app action.
            dropped.incrementAndGet()
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 128L * 1024L
        const val MAX_RECORD_BYTES = 1024
    }
}
