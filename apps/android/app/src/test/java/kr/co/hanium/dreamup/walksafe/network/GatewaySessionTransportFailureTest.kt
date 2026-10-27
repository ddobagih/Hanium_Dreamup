package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class GatewaySessionTransportFailureTest {
    @Test fun temporaryEmptyReadDoesNotBecomeAnEmptyResponse() {
        val connection = Connection(Body("{}".toByteArray(), zeroAt = 0))
        assertEquals("{}", get(connection).responseBody)
        assertClosed(connection)
    }

    @Test fun temporaryEmptyReadDoesNotAcceptOnlyTheValidJsonPrefix() {
        val connection = Connection(Body("{} unexpected".toByteArray(), zeroAt = 2))
        assertEquals("{} unexpected", get(connection).responseBody)
        assertClosed(connection)
    }

    @Test fun stalledReadFailsInsteadOfReturningAnEmptySuccess() {
        val connection = Connection(Body(byteArrayOf(), stall = true))
        protocolFailure(connection, "response_body_no_progress")
        assertTrue(connection.body!!.reads <= 4)
    }

    @Test fun validJsonWithTruncatedHttpLengthIsRejected() {
        protocolFailure(Connection(Body("{}".toByteArray()), headers = length(5)), "content_length_mismatch")
    }

    @Test fun extraBodyBytesAreRejected() {
        protocolFailure(Connection(Body("{} ".toByteArray()), headers = length(2)), "content_length_mismatch")
    }

    @Test fun invalidUtf8IsNotReplacedWithAUsableString() {
        protocolFailure(Connection(Body(byteArrayOf(0x22, 0xc3.toByte(), 0x28, 0x22))), "response_utf8_invalid")
    }

    @Test fun duplicateLengthsAreRejectedBeforeReadingTheBody() {
        val connection = Connection(Body("{}".toByteArray()), headers = mapOf("Content-Length" to listOf("2", "2")))
        protocolFailure(connection, "content_length_invalid", bodyOpened = false)
    }

    @Test fun chunkedAndLengthTogetherAreRejected() {
        val connection = Connection(Body("{}".toByteArray()), headers = length(2) + ("Transfer-Encoding" to listOf("chunked")))
        protocolFailure(connection, "response_framing_ambiguous", bodyOpened = false)
    }

    @Test fun declaredOversizeIsRejectedWithoutOpeningBody() {
        val connection = Connection(Body("{}".toByteArray()), headers = length(32_769))
        assertTrue(runCatching { get(connection) }.exceptionOrNull() is NetworkResponseTooLargeException)
        assertEquals(0, connection.bodyOpens)
        assertTrue(connection.disconnected)
    }

    @Test fun actualOversizeIsStillRejectedAndClosed() {
        val connection = Connection(Body(ByteArray(32_769) { 32 }))
        assertTrue(runCatching { get(connection) }.exceptionOrNull() is IllegalStateException)
        assertClosed(connection)
    }

    @Test fun declaredNonEmptyBodyCannotBeMissing() {
        protocolFailure(Connection(null, headers = length(2)), "content_length_mismatch", bodyOpened = false)
    }

    @Test fun noContentReplyRemainsValid() {
        val connection = Connection(null, status = 204)
        val response = get(connection)
        assertEquals(204, response.statusCode)
        assertEquals("", response.responseBody)
        assertTrue(connection.disconnected)
    }

    @Test fun validChunkedReplyAndSessionCookieArePreserved() {
        val connection = Connection(Body("{}".toByteArray()), headers = mapOf(
            "Transfer-Encoding" to listOf("chunked"),
            "Set-Cookie" to listOf("unrelated=other", "${GatewayFieldSession.COOKIE_NAME}=test; HttpOnly"),
        ))
        val response = get(connection)
        assertEquals("{}", response.responseBody)
        assertEquals("${GatewayFieldSession.COOKIE_NAME}=test; HttpOnly", response.header("Set-Cookie"))
        assertClosed(connection)
    }

    @Test fun retryAfterAndErrorStatusArePreserved() {
        val connection = Connection(Body("{\"code\":\"rate_limited\"}".toByteArray()), status = 429,
            headers = mapOf("Retry-After" to listOf("30")))
        val response = get(connection)
        assertEquals(429, response.statusCode)
        assertEquals("30", response.header("Retry-After"))
        assertEquals("{\"code\":\"rate_limited\"}", response.responseBody)
        assertClosed(connection)
    }

    @Test fun disconnectFailureDoesNotDiscardAnAlreadyCompleteReply() {
        val connection = Connection(Body("{}".toByteArray()), disconnectFailure = IOException("disconnect"))
        assertEquals("{}", get(connection).responseBody)
        assertClosed(connection)
    }

    @Test fun disconnectFailureDoesNotReplaceTheOriginalReadTimeout() {
        val timeout = SocketTimeoutException("test read timeout")
        val connection = Connection(Body(byteArrayOf(), failure = timeout), disconnectFailure = IOException("disconnect"))
        assertSame(timeout, runCatching { get(connection) }.exceptionOrNull())
        assertClosed(connection)
    }

    @Test fun setupFailureStillDisconnectsWithoutStartingTheRequest() {
        val failure = IllegalStateException("test property failure")
        val connection = Connection(Body("{}".toByteArray()), setupFailure = failure)
        assertSame(failure, runCatching { get(connection) }.exceptionOrNull())
        assertEquals(0, connection.bodyOpens)
        assertTrue(connection.disconnected)
    }

    @Test fun writeFailurePreservesTheCauseAndDisconnects() {
        val failure = IOException("test write failure")
        val connection = Connection(Body("{}".toByteArray()), writeFailure = failure)
        val transport = HttpUrlConnectionGatewaySessionTransport { connection }
        assertSame(failure, runCatching { transport.postJson("https://gateway.example/api/accounts", "{}") }.exceptionOrNull())
        assertEquals(0, connection.bodyOpens)
        assertTrue(connection.disconnected)
    }

    private fun get(connection: Connection): GatewayHttpResponse =
        HttpUrlConnectionGatewaySessionTransport { connection }.get("https://gateway.example/api/session-current", emptyMap())

    private fun protocolFailure(connection: Connection, reason: String, bodyOpened: Boolean = true) {
        val failure = runCatching { get(connection) }.exceptionOrNull()
        assertTrue("expected $reason, got $failure", failure is NetworkResponseProtocolException)
        assertEquals(reason, (failure as NetworkResponseProtocolException).reason)
        assertTrue(connection.disconnected)
        if (bodyOpened) assertClosed(connection)
    }

    private fun assertClosed(connection: Connection) {
        assertTrue(connection.disconnected)
        assertTrue(connection.body!!.closed)
    }

    private fun length(size: Int) = mapOf("Content-Length" to listOf(size.toString()))

    private class Connection(
        val body: Body?,
        val status: Int = 200,
        val headers: Map<String, List<String>> = emptyMap(),
        val disconnectFailure: Exception? = null,
        val setupFailure: Exception? = null,
        val writeFailure: IOException? = null,
    ) : HttpURLConnection(URL("https://gateway.example")) {
        var disconnected = false
        var bodyOpens = 0
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; disconnectFailure?.let { throw it } }
        override fun setRequestProperty(key: String, value: String) {
            setupFailure?.let { throw it }
            super.setRequestProperty(key, value)
        }
        override fun getOutputStream(): ByteArrayOutputStream {
            writeFailure?.let { throw it }
            return ByteArrayOutputStream()
        }
        override fun getResponseCode() = status
        override fun getHeaderFields() = headers
        override fun getHeaderField(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        override fun getInputStream(): InputStream? { bodyOpens++; return body }
        override fun getErrorStream(): InputStream? { bodyOpens++; return body }
    }

    private class Body(
        private val data: ByteArray,
        private val zeroAt: Int? = null,
        private val stall: Boolean = false,
        private val failure: IOException? = null,
    ) : InputStream() {
        var closed = false
        var reads = 0
        private var offset = 0
        private var zeroEmitted = false
        override fun read(): Int = error("bulk read required")
        override fun read(target: ByteArray, start: Int, length: Int): Int {
            check(++reads <= 20) { "test stream read bound exceeded" }
            failure?.let { throw it }
            if (stall) return 0
            if (!zeroEmitted && offset == zeroAt) { zeroEmitted = true; return 0 }
            if (offset == data.size) return -1
            val untilZero = if (!zeroEmitted && zeroAt != null) zeroAt - offset else data.size - offset
            val count = minOf(length, data.size - offset, untilZero)
            data.copyInto(target, start, offset, offset + count)
            offset += count
            return count
        }
        override fun close() { closed = true }
    }
}
