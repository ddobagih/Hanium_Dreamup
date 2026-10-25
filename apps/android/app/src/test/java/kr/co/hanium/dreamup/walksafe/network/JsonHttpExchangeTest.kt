package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class JsonHttpExchangeTest {
    @Test fun constructionAndPreExecutionCancellationNeverOpenAConnection() {
        val connection = RecordingConnection()
        val call = exchange(connection)
        assertTrue(connection.events.isEmpty())
        call.cancel()
        assertThrows(CancellationException::class.java) { call.execute() }
        assertTrue(connection.events.isEmpty())
    }

    @Test fun utf8BodyAndResponseStayInsideTheConfiguredConnectionLifetime() {
        val connection = RecordingConnection(body = "응답".toByteArray(Charsets.UTF_8))
        val request = "{\"내용\":\"안내\"}"
        val call = exchange(connection, requestBody = request, configure = {
            requestMethod = "PUT"
            connectTimeout = 8_000
            readTimeout = 12_000
            doInput = true
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            setRequestProperty("X-Control", "test-control")
            setRequestProperty("Cookie", "fixture=session")
        }, consume = { response ->
            assertFalse(connection.events.contains("disconnect"))
            assertTrue(connection.events.contains("input.close"))
            assertEquals("application/json", getHeaderField("Content-Type"))
            connection.events += "consume"
            response
        })
        assertEquals(BoundedHttpResponse(200, "응답"), call.execute())
        assertArrayEquals(request.toByteArray(Charsets.UTF_8), connection.written.toByteArray())
        assertEquals("PUT", connection.requestMethod)
        assertEquals(8_000, connection.connectTimeout)
        assertEquals(12_000, connection.readTimeout)
        assertTrue(connection.doInput)
        assertTrue(connection.doOutput)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("fixture=session", connection.getRequestProperty("Cookie"))
        assertEquals("test-control", connection.getRequestProperty("X-Control"))
        assertOrdered(connection.events, "open", "output", "output.write", "output.flush", "output.close",
            "status", "input", "input.close", "consume", "disconnect")
        val completedEvents = connection.events.toList()
        call.cancel()
        assertEquals(completedEvents, connection.events)
    }

    @Test fun absentBodyDoesNotOpenOutputAndCallerCachePolicyIsPreserved() {
        val connection = RecordingConnection().apply { useCaches = false }
        exchange(connection, configure = { requestMethod = "GET" }).execute()
        assertFalse(connection.doOutput)
        assertNull(connection.getRequestProperty("Content-Type"))
        assertFalse(connection.events.contains("output"))
        assertFalse(connection.useCaches)

        val noStore = RecordingConnection().apply { useCaches = true }
        exchange(noStore, configure = {
            requestMethod = "POST"
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-store")
        }).execute()
        assertEquals(15_000, noStore.readTimeout)
        assertFalse(noStore.useCaches)
        assertEquals("no-store", noStore.getRequestProperty("Cache-Control"))
    }

    @Test fun errorStatusAndBodyReachDomainPolicyBeforeDisconnectAndItsFailureSurvives() {
        val connection = RecordingConnection(status = 404, body = "missing".toByteArray())
        val failure = IllegalStateException("domain policy")
        val call = exchange(connection, consume = { response ->
            assertEquals(BoundedHttpResponse(404, "missing"), response)
            assertFalse(connection.events.contains("disconnect"))
            connection.events += "consume"
            throw failure
        })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { call.execute() })
        assertFalse(connection.events.contains("input"))
        assertOrdered(connection.events, "error", "input.close", "consume", "disconnect")
        val completedEvents = connection.events.toList()
        call.cancel()
        assertEquals(completedEvents, connection.events)
    }

    @Test fun configureAndWriteFailuresReleaseTheirConnectionAndStream() {
        val configuring = RecordingConnection()
        val failure = IOException("fixture write failure")
        assertSame(failure, assertThrows(IOException::class.java) {
            exchange(configuring, configure = { throw failure }).execute()
        })
        assertEquals(listOf("open", "disconnect"), configuring.events)

        val writing = RecordingConnection().apply { onWrite = { throw failure } }
        val call = exchange(writing, requestBody = "{}")
        assertSame(failure, assertThrows(IOException::class.java) { call.execute() })
        assertOrdered(writing.events, "output.write", "output.close", "disconnect")
        assertFalse(writing.events.contains("status"))
        val completedEvents = writing.events.toList()
        call.cancel()
        assertEquals(completedEvents, writing.events)
    }

    @Test fun bodyLimitsApplyBeforeDomainConsumptionIncludingUnknownLengthBodies() {
        for (declared in listOf(-1L, 5L)) {
            val connection = RecordingConnection(body = "12345".toByteArray(), declaredLength = declared)
            assertThrows(NetworkResponseTooLargeException::class.java) {
                exchange(connection, maximumBytes = 4, consume = { error("domain must not consume oversized body") }).execute()
            }
            assertEquals("disconnect", connection.events.last())
            if (declared < 0) assertTrue(connection.events.contains("input.close"))
            else assertFalse(connection.events.contains("input"))
        }
    }

    @Test fun existingLenientUtf8ResponseDecodingIsNotTightenedByTheExchange() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        val connection = RecordingConnection(body = malformed)
        val response = exchange(connection, maximumBytes = malformed.size).execute()
        assertEquals(String(malformed, Charsets.UTF_8), response.body)
        assertEquals("\uFFFD(", response.body)
    }

    @Test fun cancellationDuringBodyWriteDisconnectsBeforeClosingAndCannotReadAResponse() {
        val connection = RecordingConnection()
        val call = exchange(connection, requestBody = "{}")
        connection.onWrite = call::cancel
        assertThrows(CancellationException::class.java) { call.execute() }
        assertOrdered(connection.events, "output.write", "disconnect", "output.close")
        assertFalse(connection.events.contains("status"))
    }

    @Test fun cancellationDuringResponseReadClosesTheStreamAndRejectsDomainConsumption() {
        val connection = RecordingConnection()
        val call = exchange(connection, consume = { error("cancelled response must not be consumed") })
        connection.onRead = call::cancel
        assertThrows(CancellationException::class.java) { call.execute() }
        assertOrdered(connection.events, "input.read", "disconnect", "input.close")
    }

    private fun exchange(
        connection: RecordingConnection,
        requestBody: String? = null,
        maximumBytes: Int = 128,
        configure: HttpURLConnection.() -> Unit = {},
        consume: HttpURLConnection.(BoundedHttpResponse) -> BoundedHttpResponse = { it },
    ): CancellableNetworkCall<BoundedHttpResponse> = cancellableHttpCall { cancellation ->
        withJsonHttpExchange(cancellation, "https://example.test/privacy/rights", requestBody, maximumBytes,
            configure = configure,
            openConnection = { endpoint ->
                assertEquals(connection.url.toString(), endpoint)
                connection.events += "open"
                connection
            }, consume = consume)
    }

    private fun assertOrdered(events: List<String>, vararg expected: String) {
        var cursor = 0
        expected.forEach { event ->
            val offset = events.drop(cursor).indexOf(event)
            assertTrue("Missing or out-of-order $event in $events", offset >= 0)
            cursor += offset + 1
        }
    }

    private class RecordingConnection(
        private val status: Int = 200,
        body: ByteArray = "{}".toByteArray(),
        private val declaredLength: Long = -1L,
    ) : HttpURLConnection(URL("https://example.test/privacy/rights")) {
        val events = mutableListOf<String>()
        val written = ByteArrayOutputStream()
        var onWrite: () -> Unit = {}
        var onRead: () -> Unit = {}
        private val input = object : ByteArrayInputStream(body) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                events += "input.read"
                onRead()
                return super.read(buffer, offset, length)
            }
            override fun close() { events += "input.close"; super.close() }
        }
        private val output = object : OutputStream() {
            override fun write(value: Int) { written.write(value) }
            override fun write(buffer: ByteArray) { events += "output.write"; onWrite(); written.write(buffer) }
            override fun flush() { events += "output.flush" }
            override fun close() { events += "output.close" }
        }
        override fun connect() { events += "connect" }
        override fun disconnect() { events += "disconnect" }
        override fun usingProxy() = false
        override fun getResponseCode(): Int { events += "status"; return status }
        override fun getOutputStream(): OutputStream { events += "output"; return output }
        override fun getInputStream(): InputStream { events += "input"; return input }
        override fun getErrorStream(): InputStream { events += "error"; return input }
        override fun getHeaderFieldLong(name: String?, default: Long): Long =
            if (name == "Content-Length") declaredLength else default
        override fun getHeaderField(name: String?): String? =
            if (name == "Content-Type") "application/json" else null
    }
}
