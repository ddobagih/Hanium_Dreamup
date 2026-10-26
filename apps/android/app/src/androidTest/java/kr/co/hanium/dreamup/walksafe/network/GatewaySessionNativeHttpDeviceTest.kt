package kr.co.hanium.dreamup.walksafe.network

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual Android HTTP counterpart of the host wire contract. Phone loopback only; no real account or audio. */
class GatewaySessionNativeHttpDeviceTest {
    @Test fun completeUnicodeReplyAndIdentityEncodingRequest() {
        val body = "{\"message\":\"안내 완료\"}".toByteArray()
        val exchange = exchange("200 OK", listOf("Content-Length: ${body.size}"), body)
        assertEquals(String(body, Charsets.UTF_8), exchange.result.getOrThrow().responseBody)
        assertTrue(exchange.requestHeaders.any { it.equals("Accept-Encoding: identity", true) })
    }

    @Test fun chunkedReplyIsDecodedBeforeValidation() {
        val wire = "1\r\n{\r\nA\r\n\"ok\":true}\r\n0\r\n\r\n".toByteArray()
        val exchange = exchange("200 OK", listOf("Transfer-Encoding: chunked"), wire)
        assertEquals("{\"ok\":true}", exchange.result.getOrThrow().responseBody)
    }

    @Test fun peerCloseAfterValidJsonPrefixIsNotSuccess() {
        val exchange = exchange("200 OK", listOf("Content-Length: 12"), "{}".toByteArray())
        val failure = exchange.result.exceptionOrNull()
        assertTrue(failure is NetworkResponseProtocolException)
        assertTrue((failure as NetworkResponseProtocolException).reason in setOf("content_length_mismatch", "response_body_incomplete"))
    }

    @Test fun invalidUtf8BodyIsRejectedOnRealConnection() {
        val exchange = exchange("200 OK", listOf("Content-Length: 4"), byteArrayOf(0x22, 0xc3.toByte(), 0x28, 0x22))
        val failure = exchange.result.exceptionOrNull()
        assertTrue(failure is NetworkResponseProtocolException)
        assertEquals("response_utf8_invalid", (failure as NetworkResponseProtocolException).reason)
    }

    @Test fun repeatedLengthHeaderIsRejected() {
        val exchange = exchange("200 OK", listOf("Content-Length: 2", "Content-Length: 2"), "{}".toByteArray())
        val failure = exchange.result.exceptionOrNull()
        assertTrue(failure is NetworkResponseProtocolException)
        assertEquals("content_length_invalid", (failure as NetworkResponseProtocolException).reason)
    }

    @Test fun oversizedDeclarationIsRejected() {
        val exchange = exchange("200 OK", listOf("Content-Length: 32769"), "{}".toByteArray())
        assertTrue(exchange.result.exceptionOrNull() is NetworkResponseTooLargeException)
    }

    @Test fun realRateLimitResponseKeepsRetryMetadata() {
        val body = "{\"code\":\"rate_limited\"}".toByteArray()
        val exchange = exchange("429 Too Many Requests", listOf("Content-Length: ${body.size}", "Retry-After: 30"), body)
        val response = exchange.result.getOrThrow()
        assertEquals(429, response.statusCode)
        assertEquals("30", response.header("Retry-After"))
        assertEquals(String(body, Charsets.UTF_8), response.responseBody)
    }

    @Test fun truncatedBusyErrorCannotBeUsedAsRetryEvidence() {
        val body = "{\"code\":\"field_session_storage_busy\",\"retryable\":true}".toByteArray()
        val exchange = exchange("503 Service Unavailable",
            listOf("Content-Length: ${body.size + 16}", "Retry-After: 1"), body)
        val failure = exchange.result.exceptionOrNull()
        assertTrue(failure is NetworkResponseProtocolException)
        assertTrue((failure as NetworkResponseProtocolException).reason in setOf("content_length_mismatch", "response_body_incomplete"))
    }

    @Test fun invalidUtf8InsideErrorReplyIsNotSilentlyReplaced() {
        val prefix = "{\"code\":\"field_session_storage_busy\",\"retryable\":true,\"message\":\"".toByteArray()
        val body = prefix + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        val exchange = exchange("503 Service Unavailable", listOf("Content-Length: ${body.size}"), body)
        val failure = exchange.result.exceptionOrNull()
        assertTrue(failure is NetworkResponseProtocolException)
        assertEquals("response_utf8_invalid", (failure as NetworkResponseProtocolException).reason)
    }

    @Test fun repeatedLengthInErrorReplyIsRejected() {
        val body = "{\"code\":\"field_session_storage_busy\",\"retryable\":true}".toByteArray()
        val exchange = exchange("503 Service Unavailable",
            listOf("Content-Length: ${body.size}", "Content-Length: ${body.size}"), body)
        val failure = exchange.result.exceptionOrNull()
        assertTrue(failure is NetworkResponseProtocolException)
        assertEquals("content_length_invalid", (failure as NetworkResponseProtocolException).reason)
    }

    private data class Exchange(val result: Result<GatewayHttpResponse>, val requestHeaders: List<String>)

    private fun exchange(status: String, headers: List<String>, body: ByteArray): Exchange {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val worker = executor.submit<List<String>> {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: error("request ended before headers")
                        if (line.isEmpty()) break
                        check(request.size < 100)
                        request += line
                    }
                    val response = (listOf("HTTP/1.1 $status", "Connection: close") + headers)
                        .joinToString("\r\n", postfix = "\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    socket.getOutputStream().apply { write(response); write(body); flush() }
                    request
                }
            }
            try {
                val response = runCatching {
                    HttpUrlConnectionGatewaySessionTransport().get("http://127.0.0.1:${server.localPort}/test", emptyMap())
                }
                return Exchange(response, worker.get(5, TimeUnit.SECONDS))
            } finally {
                server.close()
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
