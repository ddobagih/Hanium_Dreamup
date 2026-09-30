package kr.co.hanium.dreamup.walksafe.debuglog

import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only, best-effort frame uploader. Network work stays on its single worker and rejected or
 * failed captures are dropped instead of being retried on the camera/render path.
 */
class HttpFrameCaptureUploader(
    private val endpointUrl: String,
    private val sessionId: String,
    private val deviceModel: String?,
    private val androidVersion: String?,
    private val appVersionName: String?,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-frame-capture-uploader").apply {
            priority = Thread.NORM_PRIORITY - 2
        }
    },
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val onStatus: (Status) -> Unit = {},
) : FrameCaptureUploader {
    data class Status(
        val succeeded: Long,
        val failed: Long,
        val cancelled: Long,
        val dropped: Long,
        val pendingBytes: Long,
        val lastFailureReason: String?,
        val lastHttpStatus: Int?,
    )

    private class Upload(val image: ByteArray, val metadata: String, val generation: Long) {
        var terminal = false
        var connection: HttpURLConnection? = null
        val retainedBytes = image.size.toLong() + metadata.length.toLong() * 3L
    }

    private enum class Outcome { SUCCEEDED, FAILED, CANCELLED, DROPPED }
    private val lock = Any()
    private val cancellationGeneration = AtomicLong(0L)
    private var activeUpload: Upload? = null
    private var closed = false
    private var succeeded = 0L
    private var failed = 0L
    private var cancelled = 0L
    private var dropped = 0L
    private var lastFailureReason: String? = null
    private var lastHttpStatus: Int? = null

    val status: Status get() = synchronized(lock) { snapshotLocked() }

    override fun upload(imageJpeg: ByteArray, metadataJson: String) {
        val upload = synchronized(lock) {
            val rejection = when {
                closed -> "closed"
                !MetadataLogEndpointPolicy.isAllowed(endpointUrl) -> "invalid_endpoint"
                imageJpeg.isEmpty() -> "empty_image"
                imageJpeg.size > MAX_RETAINED_BYTES ||
                    metadataJson.length.toLong() * 3L + imageJpeg.size > MAX_RETAINED_BYTES -> "payload_too_large"
                activeUpload != null -> "busy"
                else -> null
            }
            if (rejection != null) {
                dropped++
                lastFailureReason = rejection
                null
            } else {
                val metadata = wrapMetadata(metadataJson)
                if (metadata.length.toLong() * 3L + imageJpeg.size > MAX_RETAINED_BYTES) {
                    dropped++
                    lastFailureReason = "payload_too_large"
                    null
                } else {
                    Upload(imageJpeg.copyOf(), metadata, cancellationGeneration.get()).also {
                        activeUpload = it
                    }
                }
            }
        }
        publishStatus()
        if (upload == null) return
        try {
            executor.execute(UploadTask(upload))
        } catch (_: RejectedExecutionException) {
            finish(upload, Outcome.DROPPED, "executor_rejected")
            release(upload)
        }
    }

    private inner class UploadTask(private val upload: Upload) : Runnable {
        override fun run() {
                try {
                    if (cancellationGeneration.get() != upload.generation) {
                        finish(upload, Outcome.CANCELLED)
                    } else {
                        val code = postMultipart(upload)
                        when {
                            code == null || cancellationGeneration.get() != upload.generation ->
                                finish(upload, Outcome.CANCELLED)
                            code in 200..299 -> finish(upload, Outcome.SUCCEEDED, httpStatus = code)
                            else -> finish(upload, Outcome.FAILED, "http_$code", code)
                        }
                    }
                } catch (failure: IOException) {
                    val reason = when (failure) {
                        is ConnectException -> "connection_refused"
                        is SocketTimeoutException -> "network_timeout"
                        else -> "network_io"
                    }
                    finishFailureOrCancellation(upload, reason)
                } catch (failure: RuntimeException) {
                    finishFailureOrCancellation(upload, "runtime_${failure.javaClass.simpleName}")
                } finally {
                    release(upload)
                }
        }

        fun discard() {
            finish(upload, Outcome.CANCELLED)
            release(upload)
        }
    }

    private fun release(upload: Upload) {
        upload.image.fill(0)
        synchronized(lock) { if (activeUpload === upload) activeUpload = null }
        publishStatus()
    }

    override fun cancelActiveUpload() {
        val connection = synchronized(lock) {
            cancellationGeneration.incrementAndGet()
            activeUpload?.let { upload ->
                if (!upload.terminal) {
                    upload.terminal = true
                    cancelled++
                }
                upload.connection.also { upload.connection = null }
            }
        }
        // Never fetch the current connection after releasing the ownership lock: it may
        // already belong to a later capture admitted under the next generation.
        runCatching { connection?.disconnect() }
        publishStatus()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        cancelActiveUpload()
        executor.shutdownNow().filterIsInstance<UploadTask>().forEach { it.discard() }
        // A running worker still owns its bytes until its finally block actually finishes.
        publishStatus()
    }

    private fun finishFailureOrCancellation(upload: Upload, reason: String) {
        if (cancellationGeneration.get() != upload.generation) finish(upload, Outcome.CANCELLED)
        else finish(upload, Outcome.FAILED, reason)
    }

    private fun finish(upload: Upload, outcome: Outcome, reason: String? = null, httpStatus: Int? = null) {
        synchronized(lock) {
            if (upload.terminal) return
            upload.terminal = true
            when (outcome) {
                Outcome.SUCCEEDED -> succeeded++
                Outcome.FAILED -> failed++
                Outcome.CANCELLED -> cancelled++
                Outcome.DROPPED -> dropped++
            }
            if (reason != null) lastFailureReason = reason
            if (httpStatus != null) lastHttpStatus = httpStatus
        }
        publishStatus()
    }

    private fun snapshotLocked() = Status(
        succeeded, failed, cancelled, dropped, activeUpload?.retainedBytes ?: 0L,
        lastFailureReason, lastHttpStatus,
    )

    private fun publishStatus() {
        val snapshot = status
        // Diagnostics cannot turn a best-effort debug capture into an application crash.
        runCatching { onStatus(snapshot) }
    }

    private fun wrapMetadata(metadataJson: String): String {
        return """
            {
              "schema_version":"android.frame_capture.v1",
              "session_id":${sessionId.jsonStringOrNull()},
              "device_model":${deviceModel.jsonStringOrNull()},
              "android_version":${androidVersion.jsonStringOrNull()},
              "app_version_name":${appVersionName.jsonStringOrNull()},
              "entry":$metadataJson
            }
        """.trimIndent()
    }

    private fun postMultipart(upload: Upload): Int? {
        val boundary = "walksafe-${UUID.randomUUID()}"
        val connection = openConnection(endpointUrl).apply {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 4_000
            doOutput = true
            setChunkedStreamingMode(16 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        val current = synchronized(lock) {
            if (closed || upload.terminal || activeUpload !== upload ||
                cancellationGeneration.get() != upload.generation
            ) false else {
                upload.connection = connection
                true
            }
        }
        if (!current) {
            connection.disconnect()
            return null
        }
        try {
            connection.outputStream.use { output ->
                if (cancellationGeneration.get() != upload.generation) return null
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"metadata\"\r\n")
                    writer.write("Content-Type: application/json; charset=utf-8\r\n\r\n")
                    writer.write(upload.metadata)
                    writer.write("\r\n--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"image\"; filename=\"frame.jpg\"\r\n")
                    writer.write("Content-Type: image/jpeg\r\n\r\n")
                    writer.flush()
                    if (cancellationGeneration.get() != upload.generation) return null
                    output.write(upload.image)
                    output.flush()
                    writer.write("\r\n--$boundary--\r\n")
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.close()
            return code
        } finally {
            synchronized(lock) { if (upload.connection === connection) upload.connection = null }
            connection.disconnect()
        }
    }

    private fun String?.jsonStringOrNull(): String {
        val value = this ?: return "null"
        return buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else append(character)
                }
            }
            append('"')
        }
    }

    companion object {
        // One accepted capture at a time, bounded by the backend's default image budget.
        // Three UTF-8 bytes per UTF-16 code unit conservatively covers retained metadata.
        const val MAX_RETAINED_BYTES = 8 * 1024 * 1024
    }
}
