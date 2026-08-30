package kr.co.hanium.dreamup.walksafe.network

import java.util.Base64
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAccountClientTest {
    @Test
    fun emailOtpRequestUsesExactContractAndParsesOnlyExactResponse() {
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                202,
                """{"schema_version":"walksafe.account-enrollment-email-otp-response.v1","enrollment_handle":"$HANDLE","expires_at":"2026-08-29T10:10:00Z","resend_available_at":"2026-08-29T10:01:00Z"}""",
            ),
        )

        val result = GatewayAccountClient(transport).requestEmailOtp(
            gatewayBaseUrl = "https://gateway.example",
            email = "person@example.com",
            dateOfBirth = "2000-02-29",
            requestId = "request_1234567890",
        )

        assertEquals("https://gateway.example/api/account-enrollments/email-otp", transport.url)
        val request = JSONObject(transport.body)
        assertEquals(
            setOf("schema_version", "email", "date_of_birth", "request_id"),
            request.keysSet(),
        )
        assertEquals(HANDLE, result.enrollmentHandle)
        assertTrue(result.expiresAtEpochMs > result.resendAvailableAtEpochMs)
    }

    @Test
    fun extraOrNonCanonicalEnrollmentResponseFailsClosedWithoutSensitiveExceptionText() {
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                202,
                """{"schema_version":"walksafe.account-enrollment-email-otp-response.v1","enrollment_handle":"$HANDLE","expires_at":"2026-08-29T10:10:00Z","resend_available_at":"2026-08-29T10:01:00Z","extra":true}""",
            ),
        )

        val error = assertThrows(GatewayAccountHttpException::class.java) {
            GatewayAccountClient(transport).requestEmailOtp(
                "https://gateway.example",
                "secret-person@example.com",
                "2000-01-01",
                "request_1234567890",
            )
        }

        assertFalse(error.message.orEmpty().contains("secret-person"))
        assertEquals("invalid_email_otp_response", error.reason)
    }

    @Test
    fun accountCreateAllowsAllOptionalSelectionsFalseAndSendsSixExactDocuments() {
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                201,
                """{"schema_version":"walksafe.account.v1","actor_id":"$ACTOR_ID","account_generation":1,"signup_receipt_sha256":"${"a".repeat(64)}"}""",
            ),
        )
        val selections = SignupConsentSelections(
            termsOfService = true,
            privacyNotice = true,
            locationTerms = true,
        )

        val created = GatewayAccountClient(transport).createAccount(
            gatewayBaseUrl = "https://gateway.example",
            enrollmentHandle = HANDLE,
            otpCode = "012345",
            password = "correct horse battery",
            selections = selections,
        )

        val request = JSONObject(transport.body)
        assertEquals(
            setOf("schema_version", "enrollment_handle", "otp_code", "password", "consent"),
            request.keysSet(),
        )
        val consent = request.getJSONObject("consent")
        assertEquals(
            setOf("schema_version", "document_versions", "selections"),
            consent.keysSet(),
        )
        assertEquals(6, consent.getJSONObject("document_versions").length())
        assertFalse(consent.getJSONObject("selections").getBoolean("raw_original"))
        assertFalse(consent.getJSONObject("selections").getBoolean("automatic_reporting"))
        assertFalse(consent.getJSONObject("selections").getBoolean("training_reuse"))
        assertEquals(ACTOR_ID, created.actorId)
    }

    @Test
    fun accountCreateRejectsStringEncodedGeneration() {
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                201,
                """{"schema_version":"walksafe.account.v1","actor_id":"$ACTOR_ID","account_generation":"1","signup_receipt_sha256":"${"a".repeat(64)}"}""",
            ),
        )

        val error = assertThrows(GatewayAccountHttpException::class.java) {
            GatewayAccountClient(transport).createAccount(
                gatewayBaseUrl = "https://gateway.example",
                enrollmentHandle = HANDLE,
                otpCode = "012345",
                password = "correct horse battery",
                selections = SignupConsentSelections(true, true, true),
            )
        }

        assertEquals("invalid_account_create_response", error.reason)
    }

    @Test
    fun passwordLoginUsesExactGrantAndBindsActorFromVerifiedCookieStatus() {
        val deviceId = "android-device-account-01"
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                200,
                """{"session_scope":"general"}""",
                mapOf(
                    "Set-Cookie" to
                        "walksafe_field_session=${v7CookieValue(ACTOR_ID, deviceId)}; Path=/; HttpOnly; SameSite=Strict; Secure",
                ),
            ),
            getResponse = GatewayHttpResponse(
                200,
                """{"required":true,"authenticated":true,"actor_id":"$ACTOR_ID","session_scope":"general"}""",
            ),
        )

        val session = GatewayFieldSessionClient(transport).loginWithPassword(
            gatewayBaseUrl = "https://gateway.example",
            email = "person@example.com",
            password = "correct horse battery",
            rememberMe = false,
            deviceId = deviceId,
            nowEpochMs = 1_000L,
        )

        val request = JSONObject(transport.body)
        assertEquals(
            setOf("grant_type", "email", "password", "remember_me", "device_id"),
            request.keysSet(),
        )
        assertFalse(request.getBoolean("remember_me"))
        assertEquals(deviceId, request.getString("device_id"))
        assertEquals(ACTOR_ID, session.actorId)
        assertEquals(deviceId, session.deviceId)
        assertEquals(7L, session.backendAccountGeneration)
        assertTrue(session.isBackendAccountDeviceBound)
        assertFalse(session.isLongLived)
        assertTrue(session.isUsableFor(ACTOR_ID, 2_000L))
        assertFalse(session.toString().contains("correct horse"))

        val snapshot = requireNotNull(session.backendDevicePersistenceSnapshotOrNull(2_000L))
        assertFalse(snapshot.toString().contains(snapshot.accessCookiePair))
        assertNull(
            GatewayFieldSession.restoreBackendAccountDevice(
                snapshot = snapshot.copy(accountGeneration = 8L),
                expectedGatewayBaseUrl = "https://gateway.example",
                expectedActorId = ACTOR_ID,
                expectedDeviceId = deviceId,
                nowEpochMs = 2_000L,
            ),
        )
        val restored = requireNotNull(
            GatewayFieldSession.restoreBackendAccountDevice(
                snapshot = snapshot,
                expectedGatewayBaseUrl = "https://gateway.example",
                expectedActorId = ACTOR_ID,
                expectedDeviceId = deviceId,
                nowEpochMs = 2_000L,
            ),
        )
        assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, restored.verificationState)
        assertFalse(restored.isUsableFor(ACTOR_ID, 2_000L))
        assertThrows(IllegalStateException::class.java) { restored.requestHeaders(2_000L) }
        val revalidated = GatewayFieldSessionClient(transport)
            .revalidateBackendAccountDeviceSession(restored, 2_000L)
        assertTrue(revalidated.isUsableFor(ACTOR_ID, 3_000L))
        assertEquals(deviceId, revalidated.lease.deviceId)
        assertEquals(7L, revalidated.lease.backendAccountGeneration)
    }

    @Test
    fun passwordLoginRejectsLegacyOrMismatchedDeviceCookieBeforeStatus() {
        val legacy = AccountTransport(
            postResponse = GatewayHttpResponse(
                200,
                """{"session_scope":"general"}""",
                mapOf(
                    "Set-Cookie" to
                        "walksafe_field_session=v6.${base64Url(ACTOR_ID)}.7.3.general.44.${"i".repeat(43)}.${"s".repeat(43)}; Path=/; HttpOnly; SameSite=Strict; Secure",
                ),
            ),
        )
        val legacyError = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(legacy).loginWithPassword(
                gatewayBaseUrl = "https://gateway.example",
                email = "person@example.com",
                password = "correct horse battery",
                rememberMe = false,
                deviceId = "android-device-account-01",
                nowEpochMs = 1_000L,
            )
        }
        assertEquals("invalid_gateway_cookie", legacyError.reason)
        assertEquals(0, legacy.getCount)

        val mismatched = AccountTransport(
            postResponse = GatewayHttpResponse(
                200,
                """{"session_scope":"general"}""",
                mapOf(
                    "Set-Cookie" to
                        "walksafe_field_session=${v7CookieValue(ACTOR_ID, "android-device-account-02")}; Path=/; HttpOnly; SameSite=Strict; Secure",
                ),
            ),
        )
        assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(mismatched).loginWithPassword(
                gatewayBaseUrl = "https://gateway.example",
                email = "person@example.com",
                password = "correct horse battery",
                rememberMe = false,
                deviceId = "android-device-account-01",
                nowEpochMs = 1_000L,
            )
        }
        assertEquals(0, mismatched.getCount)
    }

    @Test
    fun passwordLoginRejectsUnapprovedOriginAndReadsNestedSafeErrorCode() {
        val invalidOrigin = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(
                AccountTransport(GatewayHttpResponse(500, "{}")),
            ).loginWithPassword(
                gatewayBaseUrl = "https://gateway.example/unapproved-path",
                email = "person@example.com",
                password = "correct horse battery",
                rememberMe = false,
            )
        }
        assertEquals("invalid_gateway_url", invalidOrigin.reason)

        val rejected = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(
                AccountTransport(
                    GatewayHttpResponse(
                        401,
                        """{"detail":{"code":"invalid_account_credentials"}}""",
                    ),
                ),
            ).loginWithPassword(
                gatewayBaseUrl = "https://gateway.example",
                email = "person@example.com",
                password = "correct horse battery",
                rememberMe = false,
            )
        }
        assertEquals("invalid_account_credentials", rejected.serverCode)
        assertFalse(rejected.message.orEmpty().contains("person@example.com"))
    }

    private class AccountTransport(
        private val postResponse: GatewayHttpResponse,
        private val getResponse: GatewayHttpResponse = GatewayHttpResponse(500, "{}"),
    ) : GatewaySessionTransport {
        var url: String = ""
        var body: String = ""
        var getCount: Int = 0

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            this.url = url
            this.body = body
            return postResponse
        }

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            getResponse.also { getCount += 1 }

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            GatewayHttpResponse(204, "")

        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            GatewayHttpResponse(204, "")
    }

    private fun JSONObject.keysSet(): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }

    private fun v7CookieValue(actorId: String, deviceId: String): String = listOf(
        "v7",
        base64Url(actorId),
        "7",
        "3",
        base64Url(deviceId),
        "general",
        "44",
        "i".repeat(43),
        "s".repeat(43),
    ).joinToString(".")

    private fun base64Url(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private companion object {
        val HANDLE = "A".repeat(43)
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}
