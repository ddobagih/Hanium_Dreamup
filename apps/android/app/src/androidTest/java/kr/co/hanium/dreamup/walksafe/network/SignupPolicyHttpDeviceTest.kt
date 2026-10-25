package kr.co.hanium.dreamup.walksafe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import kr.co.hanium.dreamup.walksafe.account.*
import kr.co.hanium.dreamup.walksafe.testing.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native HTTP, bundled-document authority and exact submitted bytes; synthetic peer only. */
@RunWith(AndroidJUnit4::class)
class SignupPolicyHttpDeviceTest {
    private val required = setOf("terms_of_service", "privacy_notice", "location_terms")
    private val choices = SignupConsentSelections(true, true, true, true, true, true)

    @Test
    fun currentAndEquivalentPolicyRemainUsableOnNativeHttp() {
        syntheticBoundaryContext()
        val equivalent = policy().apply {
            getJSONObject("documents").getJSONObject("terms_of_service")
                .put("version", "terms-equivalent-next")
                .put("equivalent_versions", JSONArray().put(SIGNUP_DOCUMENT_VERSIONS.getValue("terms_of_service")))
        }
        NativeLoopbackServer(listOf(NativeHttpReply(200, policy().toString()), NativeHttpReply(200, equivalent.toString()))).use { peer ->
            val client = GatewayAccountClient(approvedReleaseOrigin = peer.baseUrl, allowDebugOrigin = true)
            assertTrue(client.signupUnavailableOptionalDocuments(peer.baseUrl).isEmpty())
            assertTrue(client.signupUnavailableOptionalDocuments(peer.baseUrl).isEmpty())
            peer.finished()
            peer.requests.forEach { assertEquals("GET", it.method); assertEquals("/api/account-signup-policy", it.path) }
        }
    }

    @Test
    fun oldOptionalDocumentCannotBeGrantedOrRelabelledInSubmittedBody() {
        syntheticBoundaryContext()
        val oldOptional = policy().apply {
            getJSONObject("documents").getJSONObject("raw_original")
                .put("version", "raw-next").put("prior_versions", JSONArray().put(SIGNUP_DOCUMENT_VERSIONS.getValue("raw_original")))
        }
        val created = JSONObject().put("schema_version", "walksafe.account.v1")
            .put("actor_id", "44444444-4444-4444-8444-444444444444").put("account_generation", 7)
            .put("signup_receipt_sha256", "a".repeat(64))
        NativeLoopbackServer(listOf(NativeHttpReply(200, oldOptional.toString()), NativeHttpReply(201, created.toString()))).use { peer ->
            val client = GatewayAccountClient(approvedReleaseOrigin = peer.baseUrl, allowDebugOrigin = true)
            val unavailable = client.signupUnavailableOptionalDocuments(peer.baseUrl)
            assertEquals(setOf("raw_original"), unavailable)
            val submitted = SignupPolicyPreflight.restrict(choices, unavailable)
            assertFalse(submitted.rawOriginal)
            assertTrue(submitted.automaticReporting)
            assertTrue(submitted.trainingReuse)
            assertEquals(7L, client.createAccount(peer.baseUrl, ENROLLMENT_HANDLE, "123456",
                "synthetic-native-password-20", submitted).accountGeneration)
            peer.finished()
            val body = JSONObject(peer.requests[1].body)
            assertEquals("POST", peer.requests[1].method)
            assertEquals("/api/accounts", peer.requests[1].path)
            val consent = body.getJSONObject("consent")
            assertFalse(consent.getJSONObject("selections").getBoolean("raw_original"))
            SIGNUP_DOCUMENT_VERSIONS.forEach { (key, version) ->
                assertEquals("Only actually bundled versions may be submitted", version, consent.getJSONObject("document_versions").getString(key))
            }
        }
    }

    @Test
    fun requiredMismatchMalformedAndServerFailureAreRejectedOnNativeHttp() {
        syntheticBoundaryContext()
        val mismatch = policy().apply { getJSONObject("documents").getJSONObject("privacy_notice").put("version", "unshown-new-version") }
        val malformed = policy().apply { getJSONObject("documents").getJSONObject("terms_of_service").put("prior_versions", JSONArray().put("obsolete")) }
        val overlap = policy().apply {
            getJSONObject("documents").getJSONObject("raw_original")
                .put("equivalent_versions", JSONArray().put("overlap-version"))
                .put("prior_versions", JSONArray().put("overlap-version"))
        }
        NativeLoopbackServer(listOf(NativeHttpReply(200, mismatch.toString()), NativeHttpReply(200, malformed.toString()),
            NativeHttpReply(200, overlap.toString()), NativeHttpReply(503, "{\"detail\":{\"code\":\"signup_policy_unavailable\"}}"))).use { peer ->
            val client = GatewayAccountClient(approvedReleaseOrigin = peer.baseUrl, allowDebugOrigin = true)
            val outdated = assertThrows(GatewayAccountHttpException::class.java) { client.signupUnavailableOptionalDocuments(peer.baseUrl) }
            assertEquals(409, outdated.statusCode)
            assertEquals("signup_policy_outdated", outdated.reason)
            repeat(2) { assertEquals("invalid_signup_policy", assertThrows(GatewayAccountHttpException::class.java) {
                client.signupUnavailableOptionalDocuments(peer.baseUrl)
            }.reason) }
            assertEquals(503, assertThrows(GatewayAccountHttpException::class.java) { client.signupUnavailableOptionalDocuments(peer.baseUrl) }.statusCode)
            peer.finished()
        }
    }

    @Test
    fun nativeOtpParsesValidReceiptAndRejectsExpiredOrMalformedSuccess() {
        syntheticBoundaryContext()
        val now = System.currentTimeMillis() / 1_000L * 1_000L
        val valid = JSONObject().put("schema_version", "walksafe.account-enrollment-email-otp-response.v1")
            .put("enrollment_handle", ENROLLMENT_HANDLE)
            .put("expires_at", Instant.ofEpochMilli(now + 300_000L).toString())
            .put("resend_available_at", Instant.ofEpochMilli(now + 60_000L).toString())
        val expired = JSONObject(valid.toString())
            .put("expires_at", Instant.ofEpochMilli(now - 300_000L).toString())
            .put("resend_available_at", Instant.ofEpochMilli(now - 360_000L).toString())
        val malformed = JSONObject(valid.toString()).put("extra", true)
        NativeLoopbackServer(listOf(NativeHttpReply(202, valid.toString()), NativeHttpReply(202, expired.toString()),
            NativeHttpReply(202, malformed.toString()))).use { peer ->
            val client = GatewayAccountClient(approvedReleaseOrigin = peer.baseUrl, allowDebugOrigin = true, clockEpochMs = { now })
            assertEquals(ENROLLMENT_HANDLE, client.requestEmailOtp(peer.baseUrl, "synthetic@example.com", "2000-01-01",
                "synthetic-otp-request-0001").enrollmentHandle)
            repeat(2) { assertEquals("invalid_email_otp_response", assertThrows(GatewayAccountHttpException::class.java) {
                client.requestEmailOtp(peer.baseUrl, "synthetic@example.com", "2000-01-01", "synthetic-otp-request-0001")
            }.reason) }
            peer.finished()
            peer.requests.forEach { assertEquals("/api/account-enrollments/email-otp", it.path) }
        }
    }

    @Test(timeout = 15_000L)
    fun explicitNativeTransportDeadlineClosesAnUnacknowledgedRequest() {
        syntheticBoundaryContext()
        val release = CountDownLatch(1)
        NativeLoopbackServer(listOf(NativeHttpReply(202, "{}", release = release))).use { peer ->
            try {
                assertThrows(java.net.SocketTimeoutException::class.java) {
                    HttpUrlConnectionGatewaySessionTransport().postJson(peer.baseUrl + "/synthetic-deadline", "{}", totalTimeoutMs = 2_000L)
                }
                assertEquals("Native request must reach the loopback peer", 0L, peer.received.count)
            } finally { release.countDown() }
            try { peer.finished() } catch (error: ExecutionException) {
                assertTrue("Only expected peer disconnect is allowed", error.cause is java.io.IOException)
            }
        }
    }

    private fun policy() = JSONObject().put("schema_version", "walksafe.account-signup-policy.v1")
        .put("documents", JSONObject().apply {
            SIGNUP_DOCUMENT_VERSIONS.forEach { (key, version) -> put(key, JSONObject()
                .put("version", version).put("required", key in required)
                .put("equivalent_versions", JSONArray()).put("prior_versions", JSONArray())) }
        })

    private companion object {
        val ENROLLMENT_HANDLE = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { 0x65.toByte() })
    }
}
