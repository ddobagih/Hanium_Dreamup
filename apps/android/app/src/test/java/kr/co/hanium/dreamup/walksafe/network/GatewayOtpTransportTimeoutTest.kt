package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class GatewayOtpTransportTimeoutTest {
    @Test fun longerWaitIsRestrictedToOtpPostsOnTheActualConnection() {
        val connections = mutableListOf<Connection>()
        val transport = HttpUrlConnectionGatewaySessionTransport { url ->
            Connection(url).also(connections::add)
        }
        val origin = "https://gateway.example"
        transport.postJson("$origin/api/account-enrollments/email-otp", "{}")
        transport.postJson("$origin/api/accounts", "{}")
        transport.postJson("$origin/api/field-session", "{}")
        transport.get("$origin/api/account-enrollments/email-otp", emptyMap())
        transport.postJson("$origin/other?path=/api/account-enrollments/email-otp", "{}")
        assertEquals(listOf(75_000, 12_000, 12_000, 12_000, 12_000), connections.map { it.readTimeout })
        assertTrue(connections.all { it.connectTimeout == 8_000 && !it.instanceFollowRedirects && it.closed })
    }

    private class Connection(url: URL) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = 202
        override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
    }
}
