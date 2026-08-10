package kr.co.hanium.dreamup.walksafe.debuglog

import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry

/**
 * Debug-variant telemetry uploader with a bounded queue and one low-priority worker. Upload loss is
 * accepted by design so diagnostics can never block or fail the ARCore render path.
 */
class HttpMetadataLogUploader(
    private val endpointUrl: String,
    private val sessionId: String,
    private val deviceModel: String?,
    private val androidVersion: String?,
    private val appVersionName: String?,
    private val transport: MetadataLogTransport = HttpUrlConnectionMetadataLogTransport(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-debug-log-uploader").apply {
            priority = Thread.NORM_PRIORITY - 2
        }
    },
) : MetadataLogUploader {
    private val queue = ArrayDeque<MetadataCaptureLogEntry>()
    private val inFlight = AtomicBoolean(false)
    private val cancellationGeneration = AtomicLong(0L)
    @Volatile
    private var enabled = false
    @Volatile
    private var closed = false
    @Volatile
    private var lastUploadAttemptMs = 0L

    override fun enqueue(entry: MetadataCaptureLogEntry) {
        if (!enabled || closed || !MetadataLogEndpointPolicy.isAllowed(endpointUrl)) return
        val expectedGeneration = cancellationGeneration.get()
        val batch = synchronized(queue) {
            queue.addLast(entry)
            while (queue.size > MAX_QUEUE_SIZE) queue.removeFirst()
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastUploadAttemptMs < MIN_UPLOAD_INTERVAL_MS) return@synchronized null
            if (!inFlight.compareAndSet(false, true)) return@synchronized null
            lastUploadAttemptMs = nowMs
            drainBatchLocked()
        } ?: return
        if (batch.isEmpty()) {
            inFlight.set(false)
            return
        }
        try {
            executor.execute {
                try {
                    if (cancellationGeneration.get() != expectedGeneration) {
                        synchronized(queue) {
                            batch.asReversed().forEach(queue::addFirst)
                        }
                        return@execute
                    }
                    val body = MetadataLogJsonEncoder.encodeBatch(
                        sessionId = sessionId,
                        entries = batch,
                        deviceModel = deviceModel,
                        androidVersion = androidVersion,
                        appVersionName = appVersionName,
                    )
                    transport.post(endpointUrl, body, expectedGeneration)
                } catch (_: IOException) {
                    // A missing local debug server is a normal best-effort telemetry failure.
                } catch (_: RuntimeException) {
                    // Debug-only telemetry must never crash the ARCore render loop.
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.set(false)
        }
    }

    override fun cancelActiveUpload() {
        cancellationGeneration.incrementAndGet()
        transport.cancelActive()
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled && MetadataLogEndpointPolicy.isAllowed(endpointUrl)
        if (!this.enabled) {
            synchronized(queue) { queue.clear() }
        }
    }

    override fun isEnabled(): Boolean = enabled

    override fun statusText(): String {
        val queued = synchronized(queue) { queue.size }
        return if (enabled) "server-log=on queued=$queued" else "server-log=off"
    }

    override fun close() {
        closed = true
        enabled = false
        cancelActiveUpload()
        synchronized(queue) { queue.clear() }
        executor.shutdownNow()
    }

    private fun drainBatchLocked(): List<MetadataCaptureLogEntry> {
        val batch = mutableListOf<MetadataCaptureLogEntry>()
        while (queue.isNotEmpty() && batch.size < MAX_BATCH_SIZE) {
            batch.add(queue.removeFirst())
        }
        return batch
    }

    private companion object {
        const val MAX_QUEUE_SIZE = 30
        const val MAX_BATCH_SIZE = 5
        const val MIN_UPLOAD_INTERVAL_MS = 2_000L
    }
}

interface MetadataLogTransport {
    fun post(url: String, body: String, expectedCancellationGeneration: Long)
    fun cancelActive() = Unit
}

class HttpUrlConnectionMetadataLogTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val afterConnectionPublished: () -> Unit = {},
) : MetadataLogTransport {
    private val cancellationGeneration = AtomicLong(0L)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    override fun post(
        url: String,
        body: String,
        expectedCancellationGeneration: Long,
    ) {
        if (cancellationGeneration.get() != expectedCancellationGeneration) return
        val connection = connectionFactory(URL(url)).apply {
            requestMethod = "POST"
            connectTimeout = 1_000
            readTimeout = 2_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        activeConnection.set(connection)
        afterConnectionPublished()
        if (cancellationGeneration.get() != expectedCancellationGeneration) {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            return
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            // Trigger the request and drain/close the response stream if present.
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.close()
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    override fun cancelActive() {
        cancellationGeneration.incrementAndGet()
        activeConnection.getAndSet(null)?.disconnect()
    }
}
