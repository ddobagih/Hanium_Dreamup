package kr.co.hanium.dreamup.walksafe.debuglog

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMetadataLogUploaderTest {
    @Test
    fun dropsConnectionFailureWithoutCrashingTheCaller() {
        var attempts = 0
        val uploader = HttpMetadataLogUploader(
            endpointUrl = "http://127.0.0.1:8000/android/debug/depth-logs",
            sessionId = "session-1",
            deviceModel = "device",
            androidVersion = "13",
            appVersionName = "0.1.0",
            transport = object : MetadataLogTransport {
                override fun post(
                    url: String,
                    body: String,
                    expectedCancellationGeneration: Long,
                ) {
                    attempts += 1
                    throw ConnectException("local debug server unavailable")
                }
            },
            executor = DirectExecutorService(),
        )
        uploader.setEnabled(true)

        uploader.enqueue(entry())

        assertEquals(1, attempts)
        uploader.close()
    }

    @Test
    fun dropsFrameCaptureConnectionFailureWithoutCrashingTheCaller() {
        val uploader = HttpFrameCaptureUploader(
            endpointUrl = "http://127.0.0.1:0/android/debug/frame-captures",
            sessionId = "session-1",
            deviceModel = "device",
            androidVersion = "13",
            appVersionName = "0.1.0",
            executor = DirectExecutorService(),
        )

        uploader.upload(byteArrayOf(1), "{}")

        uploader.close()
    }

    @Test
    fun walkingResumeCancellationPreventsQueuedMetadataUploadFromStarting() {
        var attempts = 0
        val executor = QueuedExecutorService()
        val uploader = HttpMetadataLogUploader(
            endpointUrl = "http://127.0.0.1:8000/android/debug/depth-logs",
            sessionId = "session-1",
            deviceModel = "device",
            androidVersion = "13",
            appVersionName = "0.1.0",
            transport = object : MetadataLogTransport {
                override fun post(
                    url: String,
                    body: String,
                    expectedCancellationGeneration: Long,
                ) {
                    attempts += 1
                }
            },
            executor = executor,
        )
        uploader.setEnabled(true)
        uploader.enqueue(entry())

        uploader.cancelActiveUpload()
        executor.runNext()

        assertEquals(0, attempts)
        uploader.close()
    }

    @Test
    fun cancellationAfterWorkerCheckBeforeHttpStartUsesEnqueueGeneration() {
        var connectionFactoryCalls = 0
        lateinit var uploader: HttpMetadataLogUploader
        val delegate = HttpUrlConnectionMetadataLogTransport(
            connectionFactory = {
                connectionFactoryCalls += 1
                RecordingHttpURLConnection(it)
            },
        )
        val transport = object : MetadataLogTransport {
            override fun post(
                url: String,
                body: String,
                expectedCancellationGeneration: Long,
            ) {
                uploader.cancelActiveUpload()
                delegate.post(url, body, expectedCancellationGeneration)
            }

            override fun cancelActive() {
                delegate.cancelActive()
            }
        }
        uploader = HttpMetadataLogUploader(
            endpointUrl = "http://127.0.0.1:8000/android/debug/depth-logs",
            sessionId = "session-1",
            deviceModel = "device",
            androidVersion = "13",
            appVersionName = "0.1.0",
            transport = transport,
            executor = DirectExecutorService(),
        )
        uploader.setEnabled(true)

        uploader.enqueue(entry())

        assertEquals(0, connectionFactoryCalls)
        uploader.close()
    }

    @Test
    fun cancellationImmediatelyAfterConnectionPublicationStopsBeforeRequestOutput() {
        val connection = RecordingHttpURLConnection(
            URL("http://127.0.0.1:8000/android/debug/depth-logs"),
        )
        lateinit var transport: HttpUrlConnectionMetadataLogTransport
        transport = HttpUrlConnectionMetadataLogTransport(
            connectionFactory = { connection },
            afterConnectionPublished = transport@{
                transport.cancelActive()
            },
        )

        transport.post(
            "http://127.0.0.1:8000/android/debug/depth-logs",
            "{}",
            expectedCancellationGeneration = 0L,
        )

        assertFalse(connection.outputStreamOpened)
        assertTrue(connection.disconnected)
    }

    private fun entry(): MetadataCaptureLogEntry {
        return MetadataCaptureLogEntry(
            frameTimestampMs = 1L,
            detectorFrameTimestampMs = null,
            detectorAgeMs = null,
            detectionCount = 0,
            detectionsUsedForDepth = false,
            staleReason = "detector_pending",
            topDetectionClassName = null,
            topDetectionConfidence = null,
            topDetectionBbox = null,
            bestDepthClassName = null,
            bestDepthSource = null,
            bestDepthMedianM = null,
            bestDepthValidSampleCount = null,
            bestDepthValidSampleRatio = null,
            bestDepthBbox = null,
        )
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException()
            command.run()
        }
    }

    private class QueuedExecutorService : AbstractExecutorService() {
        private var shutdown = false
        private var pending: Runnable? = null

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return listOfNotNull(pending.also { pending = null }).toMutableList()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && pending == null

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException()
            check(pending == null)
            pending = command
        }

        fun runNext() {
            val command = checkNotNull(pending)
            pending = null
            command.run()
        }
    }

    private class RecordingHttpURLConnection(url: URL) : HttpURLConnection(url) {
        var outputStreamOpened = false
            private set
        var disconnected = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream {
            outputStreamOpened = true
            return ByteArrayOutputStream()
        }
    }
}
