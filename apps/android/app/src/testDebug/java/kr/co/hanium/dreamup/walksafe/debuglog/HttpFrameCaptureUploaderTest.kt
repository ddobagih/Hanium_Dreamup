package kr.co.hanium.dreamup.walksafe.debuglog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpFrameCaptureUploaderTest {
    @Test fun multipartMetadataEscapesSessionAndDeviceControlCharacters() {
        val executor = QueuedExecutor()
        val connection = FakeConnection(201)
        val sessionId = "session\"\n\\\u0001"
        val deviceModel = "model\r\n\t\u0000"
        val androidVersion = "android\\version"
        val appVersion = "version\"one"
        val uploader = HttpFrameCaptureUploader(
            "http://127.0.0.1/capture", sessionId, deviceModel, androidVersion, appVersion,
            executor = executor, openConnection = { connection },
        )

        uploader.upload(byteArrayOf(1), "{}")
        executor.runAll()

        val body = String(connection.body.toByteArray(), Charsets.UTF_8)
        val start = body.indexOf("\r\n\r\n")
        assertTrue(start >= 0)
        val end = body.indexOf("\r\n--walksafe-", start + 4)
        assertTrue(end > start)
        val metadata = JSONObject(body.substring(start + 4, end))
        assertEquals(sessionId, metadata.getString("session_id"))
        assertEquals(deviceModel, metadata.getString("device_model"))
        assertEquals(androidVersion, metadata.getString("android_version"))
        assertEquals(appVersion, metadata.getString("app_version_name"))
        assertEquals(0, metadata.getJSONObject("entry").length())
        assertEquals(1L, uploader.status.succeeded)
    }

    @Test fun blockedWorkerRetainsOnlyOneCaptureAndFreezesItsImage() {
        val executor = QueuedExecutor()
        val connection = FakeConnection(201)
        val uploader = uploader(executor) { connection }
        val original = byteArrayOf(91, 92, 93, 94)
        uploader.upload(original, "{}")
        original.fill(0)
        repeat(20) { uploader.upload(byteArrayOf(1), "{}") }
        assertEquals(1, executor.tasks.size)
        assertEquals(20L, uploader.status.dropped)
        assertTrue(uploader.status.pendingBytes in 1L..HttpFrameCaptureUploader.MAX_RETAINED_BYTES.toLong())
        executor.runAll()
        assertTrue(connection.body.toByteArray().toList().windowed(4).contains(listOf<Byte>(91, 92, 93, 94)))
        assertEquals(1L, uploader.status.succeeded)
        assertEquals(0L, uploader.status.pendingBytes)
        assertTrue(connection.streamingChunkSize > 0)
    }

    @Test fun oversizedPayloadNeverEntersTheExecutor() {
        val executor = QueuedExecutor()
        val uploader = uploader(executor) { FakeConnection(200) }
        uploader.upload(ByteArray(HttpFrameCaptureUploader.MAX_RETAINED_BYTES + 1), "{}")
        assertTrue(executor.tasks.isEmpty())
        assertEquals("payload_too_large", uploader.status.lastFailureReason)
        assertEquals(0L, uploader.status.pendingBytes)
    }

    @Test fun httpFailuresAndConnectionRefusalRemainDistinguishable() {
        for (code in listOf(413, 500)) {
            val executor = QueuedExecutor()
            val uploader = uploader(executor) { FakeConnection(code) }
            uploader.upload(byteArrayOf(1), "{}")
            executor.runAll()
            assertEquals(1L, uploader.status.failed)
            assertEquals(code, uploader.status.lastHttpStatus)
            assertEquals("http_$code", uploader.status.lastFailureReason)
        }
        val executor = QueuedExecutor()
        val uploader = uploader(executor) { throw ConnectException("do not log endpoint details") }
        uploader.upload(byteArrayOf(1), "{}")
        executor.runAll()
        assertEquals("connection_refused", uploader.status.lastFailureReason)
        assertEquals(1L, uploader.status.failed)
    }

    @Test fun cancelledQueuedCaptureCannotSendOrCountSuccess() {
        val executor = QueuedExecutor()
        var opened = false
        val uploader = uploader(executor) { opened = true; FakeConnection(200) }
        uploader.upload(byteArrayOf(1), "{}")
        uploader.cancelActiveUpload()
        executor.runAll()
        assertFalse(opened)
        assertEquals(1L, uploader.status.cancelled)
        assertEquals(0L, uploader.status.succeeded)
        assertEquals(0L, uploader.status.pendingBytes)
    }

    @Test fun closeDropsPendingWorkAndRejectsFutureCaptures() {
        val executor = QueuedExecutor()
        val uploader = uploader(executor) { FakeConnection(200) }
        uploader.upload(byteArrayOf(1), "{}")
        uploader.close()
        uploader.upload(byteArrayOf(2), "{}")
        assertTrue(executor.tasks.isEmpty())
        assertEquals(1L, uploader.status.cancelled)
        assertEquals(1L, uploader.status.dropped)
        assertEquals("closed", uploader.status.lastFailureReason)
        assertEquals(0L, uploader.status.pendingBytes)
    }

    @Test fun rejectedExecutorAndThrowingObserverCannotCrashCapture() {
        val executor = QueuedExecutor().apply { shutdown() }
        val uploader = HttpFrameCaptureUploader(
            "http://127.0.0.1/capture", "session", null, null, null,
            executor = executor, onStatus = { error("observer failed") },
        )
        uploader.upload(byteArrayOf(1), "{}")
        assertEquals("executor_rejected", uploader.status.lastFailureReason)
        assertEquals(1L, uploader.status.dropped)
        assertEquals(0L, uploader.status.pendingBytes)
    }

    @Test fun cancellationCannotDisconnectTheNextCapturesConnection() {
        val executor = Executors.newSingleThreadExecutor()
        val first = BlockingConnection()
        val second = BlockingConnection()
        val cancelPublished = CountDownLatch(1)
        val returnFromCancel = CountDownLatch(1)
        val firstReleased = CountDownLatch(1)
        var calls = 0
        val uploader = HttpFrameCaptureUploader(
            "http://127.0.0.1/capture", "session", null, null, null, executor,
            openConnection = { if (calls++ == 0) first else second },
            onStatus = { status ->
                if (status.cancelled == 1L && status.pendingBytes == 0L) firstReleased.countDown()
                if (Thread.currentThread().name == "capture-cancel-test" && status.cancelled == 1L) {
                    cancelPublished.countDown()
                    check(returnFromCancel.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val cancelling = Thread({ uploader.cancelActiveUpload() }, "capture-cancel-test")
        try {
            uploader.upload(byteArrayOf(1), "{}")
            assertTrue(first.started.await(5, TimeUnit.SECONDS))
            cancelling.start()
            assertTrue(cancelPublished.await(5, TimeUnit.SECONDS))
            first.responseReady.countDown()
            assertTrue(firstReleased.await(5, TimeUnit.SECONDS))
            uploader.upload(byteArrayOf(2), "{}")
            assertTrue(second.started.await(5, TimeUnit.SECONDS))
            returnFromCancel.countDown()
            cancelling.join(5_000)
            assertFalse(cancelling.isAlive)
            assertFalse(second.disconnected.get())
            second.responseReady.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1L, uploader.status.cancelled)
            assertEquals(1L, uploader.status.succeeded)
        } finally {
            returnFromCancel.countDown()
            first.responseReady.countDown()
            second.responseReady.countDown()
            uploader.close()
            cancelling.join(5_000)
        }
    }

    @Test fun closeReportsRetainedBytesUntilAnUnresponsiveWorkerActuallyEnds() {
        val executor = Executors.newSingleThreadExecutor()
        val connection = BlockingConnection(disconnectReleasesResponse = false)
        val uploader = HttpFrameCaptureUploader(
            "http://127.0.0.1/capture", "session", null, null, null, executor,
            openConnection = { connection },
        )
        try {
            uploader.upload(byteArrayOf(1), "{}")
            assertTrue(connection.started.await(5, TimeUnit.SECONDS))
            uploader.close()
            assertTrue(uploader.status.pendingBytes > 0L)
            assertEquals(1L, uploader.status.cancelled)
            connection.responseReady.countDown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(0L, uploader.status.pendingBytes)
            assertEquals(0L, uploader.status.succeeded)
        } finally {
            connection.responseReady.countDown()
            uploader.close()
        }
    }

    private fun uploader(executor: QueuedExecutor, connection: (String) -> HttpURLConnection) =
        HttpFrameCaptureUploader(
            "http://127.0.0.1/capture", "session", null, null, null,
            executor = executor, openConnection = connection,
        )

    private open class FakeConnection(private val code: Int) : HttpURLConnection(URL("http://127.0.0.1/capture")) {
        val body = ByteArrayOutputStream()
        var streamingChunkSize = 0
        override fun setChunkedStreamingMode(chunklen: Int) { streamingChunkSize = chunklen }
        override fun getOutputStream() = body
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private class BlockingConnection(private val disconnectReleasesResponse: Boolean = true) : FakeConnection(200) {
        val started = CountDownLatch(1)
        val responseReady = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        override fun getResponseCode(): Int {
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                try { if (responseReady.await(50, TimeUnit.MILLISECONDS)) return 200 }
                catch (_: InterruptedException) { /* Deliberately model an uninterruptible sink. */ }
            }
            error("test response was never released")
        }
        override fun disconnect() {
            disconnected.set(true)
            if (disconnectReleasesResponse) responseReady.countDown()
        }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks += command
        }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
