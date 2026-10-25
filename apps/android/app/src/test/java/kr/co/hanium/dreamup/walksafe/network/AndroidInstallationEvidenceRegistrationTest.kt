package kr.co.hanium.dreamup.walksafe.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidInstallationEvidenceRegistrationTest {
    @Test fun confirmedOwnerRegistersOnlyPublicKeyAndAcknowledgesOnlyExact204() {
        withServer(204) { origin, requests, bodies ->
            val key = key()
            val prepared = mutableListOf<Triple<String, String, Long>>()
            val acknowledged = mutableListOf<Triple<String, String, Long>>()
            val client = AndroidIntegratedConsentClient({ id, url, actor, generation -> assertEquals(ACTOR, actor); prepared += Triple(id, url, generation); key },
                { id, url, actor, generation -> assertEquals(ACTOR, actor); acknowledged += Triple(id, url, generation) })
            client.registerInstallationEvidenceKeyCall(session(origin), confirmation()) { true }.execute()
            assertEquals(1, requests.get())
            assertEquals(listOf(Triple(INSTALLATION, origin, 7L)), prepared)
            assertEquals(prepared, acknowledged)
            val body = JSONObject(bodies.single())
            assertEquals(setOf("installation_id", "public_key"), body.keys().asSequence().toSet())
            assertEquals(INSTALLATION, body.getString("installation_id"))
            assertEquals(key, body.getString("public_key"))
        }
        withServer(409) { origin, requests, _ ->
            var acknowledgements = 0
            val client = AndroidIntegratedConsentClient({ _, _, _, _ -> key() }, { _, _, _, _ -> acknowledgements++ })
            assertThrows(IntegratedConsentHttpException::class.java) {
                client.registerInstallationEvidenceKeyCall(session(origin), confirmation()) { true }.execute()
            }
            assertEquals(1, requests.get()); assertEquals(0, acknowledgements)
        }
    }

    @Test fun changedOwnerUnknownGenerationDifferentInstallationAndMissingKeyProduceNoRegistration() {
        withServer(204) { origin, requests, _ ->
            var keyReads = 0
            val client = AndroidIntegratedConsentClient({ _, _, _, _ -> keyReads++; key() })
            assertThrows(CancellationException::class.java) {
                client.registerInstallationEvidenceKeyCall(session(origin), confirmation()) { false }.execute()
            }
            assertThrows(IllegalStateException::class.java) {
                client.registerInstallationEvidenceKeyCall(GatewayFieldSession.verified(origin, ACTOR,
                    "walksafe_field_session=legacy-generation-unknown", Long.MAX_VALUE), confirmation()) { true }
            }
            assertThrows(IllegalArgumentException::class.java) {
                client.registerInstallationEvidenceKeyCall(session(origin), confirmation().copy(installationId = "other-installation")) { true }
            }
            assertEquals(0, requests.get()); assertEquals(0, keyReads)
            val lost = AndroidIntegratedConsentClient({ _, _, _, _ -> throw IllegalStateException("controlled missing key") })
            assertThrows(IllegalStateException::class.java) {
                lost.registerInstallationEvidenceKeyCall(session(origin), confirmation()) { true }.execute()
            }
            assertEquals(0, requests.get())
        }
    }

    private fun withServer(status: Int, action: (String, AtomicInteger, MutableList<String>) -> Unit) {
        val requests = AtomicInteger()
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/privacy/rights") { exchange ->
            requests.incrementAndGet()
            assertEquals("PUT", exchange.requestMethod)
            assertEquals("control=installation-evidence-key", exchange.requestURI.rawQuery)
            assertEquals("c".repeat(64), exchange.requestHeaders.getFirst(CONSENT_CONTROL_SECRET_HEADER))
            assertTrue(exchange.requestHeaders.getFirst("Cookie").contains("walksafe_field_session="))
            bodies += exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.sendResponseHeaders(status, -1); exchange.close()
        }
        server.start()
        try { action("http://127.0.0.1:${server.address.port}", requests, bodies) } finally { server.stop(0) }
    }
    private fun key() = Base64.getUrlEncoder().withoutPadding().encodeToString(KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded)
    private fun session(origin: String) = GatewayFieldSession.backendAccountDeviceSession(origin,
        BackendAccountDeviceCookieBinding(ACTOR, 7L, 1L, INSTALLATION, "s".repeat(32), Long.MAX_VALUE),
        "walksafe_field_session=registered-device-test-cookie", Long.MAX_VALUE)
    private fun confirmation() = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        INTEGRATED_CONSENT_POLICY_VERSION, INSTALLATION, "installation_key_request_0001", IntegratedConsentItemVersions(),
        1L, 1L, IntegratedConsentSelections(), "2026-10-11T00:00:00.000Z", "a".repeat(64), "b".repeat(64), "c".repeat(64))
    private companion object {
        const val ACTOR = "11111111-1111-4111-8111-111111111111"
        const val INSTALLATION = "device-installation-key-test-0001"
    }
}
