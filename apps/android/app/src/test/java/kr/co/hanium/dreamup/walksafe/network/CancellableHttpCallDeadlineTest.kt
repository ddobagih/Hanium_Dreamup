package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S15-23: a whole-request deadline cancels a stalled call through the existing cancellation
 * (stream closed, connection disconnected) and reports a timeout, not a user cancellation.
 */
class CancellableHttpCallDeadlineTest {
    @Test(timeout = 20_000)
    fun stalledBodyWriteEndsAtTheDeadlineAsATimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val peer = acceptSilently(server)
            val started = System.nanoTime()
            val call = cancellableHttpCall(deadlineMs = 500L) { cancellation ->
                stalledPost(server.localPort, cancellation)
            }
            val error = assertThrows(NetworkDeadlineExceededException::class.java) { call.execute() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertEquals(500L, error.deadlineMs)
            assertTrue(error is SocketTimeoutException)
            assertTrue("ended after ${elapsedMs} ms", elapsedMs in 400L..5_000L)
            runCatching { peer.get()?.close() }
        }
    }

    @Test(timeout = 10_000)
    fun normalResponseIsUnaffectedAndTheWatchdogIsReleased() {
        val scheduler = ManualScheduler()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val responder = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                    )
                }
            }.apply { isDaemon = true; start() }
            val call = cancellableHttpCall(deadlineMs = 60_000L, deadlineScheduler = scheduler) { cancellation ->
                val connection = URL("http://127.0.0.1:${server.localPort}/status").openConnection() as HttpURLConnection
                cancellation.attach(connection)
                try {
                    connection.readBoundedResponse(1_024, cancellation)
                } finally {
                    cancellation.detach(connection)
                    connection.disconnect()
                }
            }
            val response = call.execute()
            responder.join(2_000)
            assertEquals(200, response.statusCode)
            assertEquals("ok", response.body)
            assertEquals(listOf(60_000L), scheduler.delays)
            assertEquals(1, scheduler.released.get())
        }
    }

    @Test
    fun deadlineCancelsThroughTheExistingCancellation() {
        val scheduler = ManualScheduler()
        val seen = AtomicReference<HttpConnectionCancellation?>()
        val call = cancellableHttpCall(deadlineMs = 1_000L, deadlineScheduler = scheduler) { cancellation ->
            seen.set(cancellation)
            scheduler.fire()
            assertTrue("deadline must use the call's cancellation", cancellation.isCancelled())
            throw java.io.IOException("socket closed by cancellation")
        }
        val error = assertThrows(NetworkDeadlineExceededException::class.java) { call.execute() }
        assertEquals("socket closed by cancellation", error.cause?.message)
        assertTrue(seen.get()!!.isCancelled())
        assertEquals(1, scheduler.released.get())
    }

    @Test
    fun userCancellationStaysACancellationEvenWithADeadline() {
        val scheduler = ManualScheduler()
        lateinit var call: CancellableNetworkCall<Unit>
        call = cancellableHttpCall(deadlineMs = 1_000L, deadlineScheduler = scheduler) {
            call.cancel()
            scheduler.fire()
            throw java.io.IOException("closed")
        }
        assertThrows(CancellationException::class.java) { call.execute() }
    }

    @Test
    fun noDeadlineKeepsThePreviousBehaviour() {
        val scheduler = ManualScheduler()
        val call = cancellableHttpCall(deadlineScheduler = scheduler) { "done" }
        assertEquals("done", call.execute())
        assertTrue(scheduler.delays.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { cancellableHttpCall(deadlineMs = 0L) { Unit } }
    }

    @Test
    fun reportTransportUsesDeadlinesBuiltFromExistingBounds() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt").readText()
        assertTrue(source.contains("cancellableHttpCall(deadlineMs = deadlineMs) { cancellation ->"))
        assertTrue(source.contains("deadlineMs = REPORT_STATUS_DEADLINE_MS,"))
        assertEquals(2, Regex("deadlineMs = REPORT_UPLOAD_DEADLINE_MS").findAll(source).count())
        assertFalse(source.contains("connectTimeout = 8_000"))
        assertEquals(
            20_000L,
            kr.co.hanium.dreamup.walksafe.report.REPORT_STATUS_DEADLINE_MS,
        )
        assertEquals(
            35_000L,
            kr.co.hanium.dreamup.walksafe.report.REPORT_UPLOAD_DEADLINE_MS,
        )
    }

    private fun acceptSilently(server: ServerSocket): AtomicReference<Socket?> {
        val peer = AtomicReference<Socket?>()
        Thread { runCatching { peer.set(server.accept()) } }.apply { isDaemon = true; start() }
        return peer
    }

    private fun stalledPost(port: Int, cancellation: HttpConnectionCancellation): Int {
        val connection = URL("http://127.0.0.1:$port/api/reports/v2").openConnection() as HttpURLConnection
        cancellation.attach(connection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            val body = ByteArray(64 * 1024 * 1024)
            connection.setFixedLengthStreamingMode(body.size.toLong())
            connection.outputStream.use { output ->
                cancellation.attach(output)
                try {
                    output.write(body)
                } finally {
                    cancellation.detach(output)
                }
            }
            return connection.readBoundedResponse(1_024, cancellation).statusCode
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }

    private class ManualScheduler : NetworkDeadlineScheduler {
        val delays = mutableListOf<Long>()
        val released = AtomicInteger(0)
        private var action: (() -> Unit)? = null

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            delays += delayMs
            this.action = action
            return { released.incrementAndGet() }
        }

        fun fire() {
            action?.invoke()
        }
    }
}
