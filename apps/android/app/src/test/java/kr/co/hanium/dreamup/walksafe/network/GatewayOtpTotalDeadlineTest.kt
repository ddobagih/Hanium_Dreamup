package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayOtpTotalDeadlineTest {
    @Test
    fun ordinaryRequestsKeepTheirExistingTimeoutsAndAlwaysDisconnect() {
        val connection = Connection()
        val transport = HttpUrlConnectionGatewaySessionTransport { connection }
        assertEquals(202, transport.postJson("http://127.0.0.1:1/otp", "{}").statusCode)
        assertEquals(8_000, connection.connectTimeout)
        assertEquals(12_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(1, connection.disconnections.get())
    }

    @Test
    fun otpUsesOneDecreasingBudgetAndClosesStreamsAndConnection() {
        val connection = Connection()
        val transport = HttpUrlConnectionGatewaySessionTransport { connection }
        assertEquals(202, transport.postJson("http://127.0.0.1:1/otp", "{}", 450L).statusCode)
        assertEquals(8_000, connection.connectTimeout)
        assertTrue(connection.readTimeout in 1..450)
        assertTrue(connection.inputClosed)
        assertTrue(connection.outputClosed)
        assertEquals(1, connection.disconnections.get())
    }

    @Test
    fun blockingPlatformResolutionReturnsAtDeadlineAndCannotDispatchLate() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = Connection()
        val transport = HttpUrlConnectionGatewaySessionTransport {
            entered.countDown()
            // Model an OS resolver that does not obey the thread's interruption.
            while (true) {
                try { if (release.await(5L, TimeUnit.SECONDS)) break } catch (_: InterruptedException) { }
            }
            connection
        }
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            transport.postJson("http://127.0.0.1:1/otp", "{}", 120L)
        }
        assertTrue(entered.await(1L, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 700L)
        assertEquals(0, connection.outputs.get())
        assertEquals(0, connection.disconnections.get()) // caller deadline is not an I/O-termination proof
        release.countDown()
        assertTrue(connection.disconnected.await(2L, TimeUnit.SECONDS))
        assertEquals(0, connection.outputs.get())
    }

    @Test
    fun realLoopbackSlowResponseCannotExtendTheTotalBudgetWithPartialReads() {
        val closed = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/otp") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(202, 10L)
                exchange.responseBody.use { output ->
                    repeat(10) {
                        output.write('x'.code); output.flush(); Thread.sleep(40L)
                    }
                }
            } catch (_: Exception) { } finally { exchange.close(); closed.countDown() }
        }
        server.start()
        try {
            val started = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                HttpUrlConnectionGatewaySessionTransport().postJson(
                    "http://127.0.0.1:${server.address.port}/otp", "{}", 130L,
                )
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 700L)
        } finally { server.stop(0) }
        assertTrue(closed.await(2L, TimeUnit.SECONDS))
    }

    @Test
    fun callerInterruptionCancelsWithoutTreatingTheRequestAsSuccess() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread {
            val deadline = GatewayRequestDeadline(5_000L)
            try {
                deadline.await(cancelOperation = { cancelled.countDown() }) {
                    entered.countDown()
                    CountDownLatch(1).await(5L, TimeUnit.SECONDS)
                    "late-success"
                }
            } catch (error: Throwable) { result.set(error) }
        }
        thread.start()
        assertTrue(entered.await(1L, TimeUnit.SECONDS))
        thread.interrupt(); thread.join(1_000L)
        assertFalse(thread.isAlive)
        assertTrue(result.get() is java.io.InterruptedIOException)
        assertTrue(cancelled.await(1L, TimeUnit.SECONDS))
    }

    private class Connection : HttpURLConnection(URL("http://127.0.0.1:1")) {
        val outputs = AtomicInteger()
        val disconnections = AtomicInteger()
        val disconnected = CountDownLatch(1)
        var inputClosed = false
        var outputClosed = false
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { disconnections.incrementAndGet(); disconnected.countDown() }
        override fun getOutputStream(): OutputStream {
            outputs.incrementAndGet()
            return object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        }
        override fun getResponseCode() = 202
        override fun getInputStream(): InputStream = object : ByteArrayInputStream("{}".toByteArray()) {
            override fun close() { inputClosed = true; super.close() }
        }
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
    }
}
