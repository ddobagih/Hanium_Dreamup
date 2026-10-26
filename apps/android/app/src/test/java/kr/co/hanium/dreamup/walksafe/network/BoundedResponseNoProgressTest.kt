package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BoundedResponseNoProgressTest(private val strict: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "strict={0}")
        fun modes() = listOf(arrayOf(false), arrayOf(true))
    }

    @Test
    fun fourthEmptyReadRejectsWithoutSpinningAndReleasesStream() {
        var reads = 0
        var closes = 0
        val stream = object : InputStream() {
            override fun read(): Int = error("unexpected single-byte read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                check(++reads <= 4) { "response kept reading without progress" }
                return 0
            }
            override fun close() { closes++ }
        }
        val cancellation = HttpConnectionCancellation()
        val failure = assertThrows(NetworkResponseProtocolException::class.java) {
            connection(stream).readBoundedResponse(32, cancellation, strict)
        }
        assertEquals("response_body_no_progress", failure.reason)
        cancellation.cancel()
        assertEquals(4, reads)
        assertEquals(1, closes)
        assertEquals("ok", connection(ByteArrayInputStream("ok".toByteArray()))
            .readBoundedResponse(32, HttpConnectionCancellation(), strict).body)
    }

    @Test
    fun actualBytesResetConsecutiveEmptyReadCount() {
        var closes = 0
        val stream = object : InputStream() {
            val bytes = "ok".toByteArray()
            var offset = 0
            var emptyReads = 0
            override fun read(): Int = error("unexpected single-byte read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (offset == bytes.size) return -1
                if (++emptyReads <= 3) return 0
                emptyReads = 0
                b[off] = bytes[offset++]
                return 1
            }
            override fun close() { closes++ }
        }
        assertEquals("ok", connection(stream).readBoundedResponse(32, HttpConnectionCancellation(), strict).body)
        assertEquals(1, closes)
    }

    @Test
    fun readFailureStillClosesAndDetachesStream() {
        var closes = 0
        val cause = IOException("synthetic truncated response")
        val stream = object : InputStream() {
            override fun read(): Int = throw cause
            override fun close() { closes++ }
        }
        val cancellation = HttpConnectionCancellation()
        val failure = assertThrows(IOException::class.java) {
            connection(stream).readBoundedResponse(32, cancellation, strict)
        }
        if (strict) {
            assertEquals("response_body_incomplete", (failure as NetworkResponseProtocolException).reason)
            assertSame(cause, failure.cause)
        } else assertSame(cause, failure)
        cancellation.cancel()
        assertEquals(1, closes)
    }

    @Test
    fun cancellationDuringStreamCloseCannotReturnSuccessfulCall() {
        lateinit var call: CancellableNetworkCall<BoundedHttpResponse>
        var closes = 0
        val stream = object : ByteArrayInputStream("ok".toByteArray()) {
            override fun close() { closes++; call.cancel() }
        }
        call = cancellableHttpCall { connection(stream).readBoundedResponse(32, it, strict) }
        assertThrows(CancellationException::class.java) { call.execute() }
        assertTrue(call.isCancelled())
        // Cancelling inside close can cause a second idempotent close; success is still forbidden.
        assertTrue(closes >= 1)
    }

    private fun connection(stream: InputStream) = object : HttpURLConnection(URL("http://127.0.0.1/synthetic")) {
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = Unit
        override fun getResponseCode() = 200
        override fun getInputStream() = stream
        override fun getHeaderFields() = mapOf("Content-Length" to listOf("2"))
        override fun getHeaderFieldLong(name: String?, default: Long) = if (name == "Content-Length") 2L else default
    }
}
