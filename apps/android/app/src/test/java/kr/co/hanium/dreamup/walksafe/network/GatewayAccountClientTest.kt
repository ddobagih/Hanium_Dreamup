package kr.co.hanium.dreamup.walksafe.network

import java.util.Base64
import java.io.IOException
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import org.json.JSONObject
import org.json.JSONArray
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// S01-9: fixtures expire at 2026-08-29T10:10:00Z; the client now checks them against a clock.
private const val OTP_FIXTURE_NOW_MS = 1_787_997_600_000L // 2026-08-29T10:00:00Z

class GatewayAccountClientTest {
    @Test
    fun restoredPasswordLoginRetainsProofAcrossOfflineAndTemporaryServerFailures() {
        val deviceId = "android-device-account-01"
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                200, """{"session_scope":"general"}""",
                mapOf("Set-Cookie" to
                    "walksafe_field_session=${v7CookieValue(ACTOR_ID, deviceId)}; Path=/; HttpOnly; SameSite=Strict; Secure"),
            ),
            getResponse = GatewayHttpResponse(200, accountStatusWithCapacity()),
        )
        val client = GatewayFieldSessionClient(transport)
        val loggedIn = client.loginWithPassword(
            "https://gateway.example", "person@example.com", "correct horse battery",
            false, deviceId, 1_000L,
        )
        val persisted = requireNotNull(loggedIn.backendDevicePersistenceSnapshotOrNull(2_000L))
        val restored = requireNotNull(GatewayFieldSession.restoreBackendAccountDevice(
            persisted, "https://gateway.example", ACTOR_ID, deviceId, 2_000L,
        ))

        transport.getFailure = IOException("offline")
        val offline = assertThrows(GatewaySessionHttpException::class.java) {
            client.revalidateBackendAccountDeviceSession(restored, 2_000L)
        }
        assertEquals("gateway_backend_device_restore_unavailable", offline.reason)
        transport.getFailure = null
        listOf(GatewayHttpResponse(408, "{}"), GatewayHttpResponse(429, "{}"), GatewayHttpResponse(503, "{}"),
            GatewayHttpResponse(200, "invalid-json")).forEach { unavailable ->
            transport.getResponse = unavailable
            val failure = assertThrows(GatewaySessionHttpException::class.java) {
                client.revalidateBackendAccountDeviceSession(restored, 2_000L)
            }
            assertEquals("gateway_backend_device_restore_unavailable", failure.reason)
            assertFalse(restored.isUsableFor(ACTOR_ID, 2_000L))
            assertEquals(persisted, restored.backendDeviceRevalidationSnapshotOrNull(2_000L))
        }
        transport.getResponse = GatewayHttpResponse(200, accountStatusWithCapacity())
        assertTrue(client.revalidateBackendAccountDeviceSession(restored, 2_000L)
            .isUsableFor(ACTOR_ID, 2_000L))
    }

    @Test
    fun restoredPasswordLoginRejectedByServerCannotBeVerifiedAgain() {
        val deviceId = "android-device-account-01"
        val session = GatewayFieldSession.restoreBackendAccountDevice(
            GatewayBackendDeviceSessionPersistence(
                "https://gateway.example", ACTOR_ID, 7L, 3L, deviceId, "i".repeat(43),
                "walksafe_field_session=${v7CookieValue(ACTOR_ID, deviceId)}", 44_000L,
            ), "https://gateway.example", ACTOR_ID, deviceId, 2_000L,
        )!!
        val transport = AccountTransport(GatewayHttpResponse(200, "{}"), GatewayHttpResponse(401, "{}"))
        val failure = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport).revalidateBackendAccountDeviceSession(session, 2_000L)
        }
        assertEquals("gateway_backend_device_restore_rejected", failure.reason)
        assertNull(session.backendDeviceRevalidationSnapshotOrNull(2_000L))
    }

    @Test
    fun emailOtpRequestUsesExactContractAndParsesOnlyExactResponse() {
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                202,
                """{"schema_version":"walksafe.account-enrollment-email-otp-response.v1","enrollment_handle":"$HANDLE","expires_at":"2026-08-29T10:10:00Z","resend_available_at":"2026-08-29T10:01:00Z"}""",
            ),
        )

        val result = GatewayAccountClient(transport, clockEpochMs = { OTP_FIXTURE_NOW_MS }).requestEmailOtp(
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
            GatewayAccountClient(transport, clockEpochMs = { OTP_FIXTURE_NOW_MS }).requestEmailOtp(
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
    fun passwordLoginAndRestoredSessionAcceptOfficialOptionalCapacityStatusField() {
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
            getResponse = GatewayHttpResponse(200, accountStatusWithCapacity()),
        )
        val client = GatewayFieldSessionClient(transport)

        val session = client.loginWithPassword(
            gatewayBaseUrl = "https://gateway.example",
            email = "person@example.com",
            password = "correct horse battery",
            rememberMe = false,
            deviceId = deviceId,
            nowEpochMs = 1_000L,
        )

        assertTrue(session.isUsableFor(ACTOR_ID, 2_000L))
        val snapshot = requireNotNull(session.backendDevicePersistenceSnapshotOrNull(2_000L))
        val restored = requireNotNull(
            GatewayFieldSession.restoreBackendAccountDevice(
                snapshot = snapshot,
                expectedGatewayBaseUrl = "https://gateway.example",
                expectedActorId = ACTOR_ID,
                expectedDeviceId = deviceId,
                nowEpochMs = 2_000L,
            ),
        )
        val revalidated = client.revalidateBackendAccountDeviceSession(restored, 2_000L)
        assertTrue(revalidated.isUsableFor(ACTOR_ID, 3_000L))
    }

    @Test
    fun passwordLoginStatusStillRejectsUnknownFieldAlongsideCapacity() {
        val deviceId = "android-device-account-01"
        val status = JSONObject(accountStatusWithCapacity())
            .put("unexpected", true)
            .toString()
        val transport = AccountTransport(
            postResponse = GatewayHttpResponse(
                200,
                """{"session_scope":"general"}""",
                mapOf(
                    "Set-Cookie" to
                        "walksafe_field_session=${v7CookieValue(ACTOR_ID, deviceId)}; Path=/; HttpOnly; SameSite=Strict; Secure",
                ),
            ),
            getResponse = GatewayHttpResponse(200, status),
        )

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport).loginWithPassword(
                gatewayBaseUrl = "https://gateway.example",
                email = "person@example.com",
                password = "correct horse battery",
                rememberMe = false,
                deviceId = deviceId,
                nowEpochMs = 1_000L,
            )
        }

        assertEquals("gateway_actor_binding_failed", error.reason)
    }

    @Test
    fun passwordLoginAndRestoredSessionRejectMalformedOptionalCapacity() {
        val deviceId = "android-device-account-01"
        val postResponse = GatewayHttpResponse(
            200,
            """{"session_scope":"general"}""",
            mapOf(
                "Set-Cookie" to
                    "walksafe_field_session=${v7CookieValue(ACTOR_ID, deviceId)}; Path=/; HttpOnly; SameSite=Strict; Secure",
            ),
        )
        val malformedCapacityValues = listOf(
            JSONObject.NULL,
            "invalid",
            JSONObject().put("version", 1),
        )
        malformedCapacityValues.forEach { malformedCapacity ->
            val status = JSONObject(accountStatusWithCapacity())
                .put("capacity", malformedCapacity)
                .toString()
            val error = assertThrows(GatewaySessionHttpException::class.java) {
                GatewayFieldSessionClient(
                    AccountTransport(postResponse, GatewayHttpResponse(200, status)),
                ).loginWithPassword(
                    gatewayBaseUrl = "https://gateway.example",
                    email = "person@example.com",
                    password = "correct horse battery",
                    rememberMe = false,
                    deviceId = deviceId,
                    nowEpochMs = 1_000L,
                )
            }
            assertEquals("gateway_actor_binding_failed", error.reason)
        }

        val transport = AccountTransport(
            postResponse = postResponse,
            getResponse = GatewayHttpResponse(200, accountStatusWithCapacity()),
        )
        val client = GatewayFieldSessionClient(transport)
        val session = client.loginWithPassword(
            gatewayBaseUrl = "https://gateway.example",
            email = "person@example.com",
            password = "correct horse battery",
            rememberMe = false,
            deviceId = deviceId,
            nowEpochMs = 1_000L,
        )
        val snapshot = requireNotNull(session.backendDevicePersistenceSnapshotOrNull(2_000L))
        val restored = requireNotNull(
            GatewayFieldSession.restoreBackendAccountDevice(
                snapshot = snapshot,
                expectedGatewayBaseUrl = "https://gateway.example",
                expectedActorId = ACTOR_ID,
                expectedDeviceId = deviceId,
                nowEpochMs = 2_000L,
            ),
        )
        transport.getResponse = GatewayHttpResponse(
            200,
            JSONObject(accountStatusWithCapacity())
                .put("capacity", JSONObject().put("version", 1))
                .toString(),
        )

        val restoreError = assertThrows(GatewaySessionHttpException::class.java) {
            client.revalidateBackendAccountDeviceSession(restored, 2_000L)
        }
        assertEquals("gateway_backend_device_restore_rejected", restoreError.reason)
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

    @Test
    fun releaseAccountRequestsRejectOtherHttpsBeforeTransport() {
        val transport = AccountTransport(GatewayHttpResponse(500, "{}"))
        val accountClient = GatewayAccountClient(
            transport,
            approvedReleaseOrigin = "https://approved.example",
            allowDebugOrigin = false,
        )
        val otpError = assertThrows(GatewayAccountHttpException::class.java) {
            accountClient.requestEmailOtp(
                "https://other.example", "person@example.com", "2000-01-01", "request_1234567890",
            )
        }
        assertEquals("invalid_gateway_url", otpError.reason)
        val createError = assertThrows(GatewayAccountHttpException::class.java) {
            accountClient.createAccount(
                "https://other.example", HANDLE, "012345", "correct horse battery",
                SignupConsentSelections(true, true, true),
            )
        }
        assertEquals("invalid_gateway_url", createError.reason)
        val loginError = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(
                transport,
                approvedAccountOrigin = "https://approved.example",
                allowDebugAccountOrigin = false,
            ).loginWithPassword(
                "https://other.example", "person@example.com", "correct horse battery", false,
            )
        }
        assertEquals("invalid_gateway_url", loginError.reason)
        assertEquals("", transport.url)
        assertEquals(0, transport.getCount)
    }

    @Test
    fun debugAccountClientKeepsLoopbackRequestPath() {
        val transport = AccountTransport(
            GatewayHttpResponse(
                202,
                """{"schema_version":"walksafe.account-enrollment-email-otp-response.v1","enrollment_handle":"$HANDLE","expires_at":"2026-08-29T10:10:00Z","resend_available_at":"2026-08-29T10:01:00Z"}""",
            ),
        )
        GatewayAccountClient(
            transport,
            approvedReleaseOrigin = "https://approved.example",
            allowDebugOrigin = true,
            clockEpochMs = { OTP_FIXTURE_NOW_MS },
        ).requestEmailOtp(
            "http://127.0.0.1:8081", "person@example.com", "2000-01-01", "request_1234567890",
        )
        assertEquals("http://127.0.0.1:8081/api/account-enrollments/email-otp", transport.url)
        assertEquals(70_000L, transport.totalTimeoutMs)
    }

    @Test
    fun signupPolicyMetadataUsesTheApprovedOriginAndExactPublicContract() {
        val documents = JSONObject()
        SIGNUP_DOCUMENT_VERSIONS.entries.forEachIndexed { index, (key, version) ->
            documents.put(key, JSONObject().put("version", version).put("required", index < 3)
                .put("equivalent_versions", JSONArray()).put("prior_versions", JSONArray()))
        }
        val body = JSONObject().put("schema_version", "walksafe.account-signup-policy.v1")
            .put("documents", documents).toString()
        val transport = AccountTransport(GatewayHttpResponse(500, "{}"), GatewayHttpResponse(200, body))
        val client = GatewayAccountClient(transport, "https://approved.example", false)
        assertTrue(client.signupUnavailableOptionalDocuments("https://approved.example").isEmpty())
        assertEquals("https://approved.example/api/account-signup-policy", transport.getUrl)
        assertThrows(GatewayAccountHttpException::class.java) {
            client.signupUnavailableOptionalDocuments("https://other.example")
        }
        assertEquals(1, transport.getCount)
        transport.getResponse = GatewayHttpResponse(503, "{}")
        assertThrows(GatewayAccountHttpException::class.java) {
            client.signupUnavailableOptionalDocuments("https://approved.example")
        }
    }

    @Test
    fun serverCurrentVersionCannotReplaceTheDocumentsActuallyDisplayedByTheApp() {
        val transport = AccountTransport(GatewayHttpResponse(500, "{}"))
        val client = GatewayAccountClient(transport)
        val changed = SIGNUP_DOCUMENT_VERSIONS + ("terms_of_service" to "walksafe.terms-of-service.v2")
        assertThrows(GatewayAccountHttpException::class.java) {
            client.createAccount("https://gateway.example", HANDLE, "012345", "a-long-safe-password",
                SignupConsentSelections(true, true, true), changed)
        }
        assertEquals("", transport.url)
    }

    private class AccountTransport(
        private val postResponse: GatewayHttpResponse,
        var getResponse: GatewayHttpResponse = GatewayHttpResponse(500, "{}"),
    ) : GatewaySessionTransport {
        var url: String = ""
        var body: String = ""
        var getCount: Int = 0
        var getUrl: String = ""
        var totalTimeoutMs: Long? = null
        var getFailure: Exception? = null

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            this.url = url
            this.body = body
            return postResponse
        }

        override fun postJson(url: String, body: String, totalTimeoutMs: Long): GatewayHttpResponse {
            this.totalTimeoutMs = totalTimeoutMs
            return postJson(url, body)
        }

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse {
            getCount += 1
            getUrl = url
            getFailure?.let { throw it }
            return getResponse
        }

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

    private fun accountStatusWithCapacity(): String = JSONObject()
        .put("required", true)
        .put("authenticated", true)
        .put("actor_id", ACTOR_ID)
        .put("session_scope", "general")
        .put(
            "capacity",
            JSONObject()
                .put("version", 1)
                .put("observed_at", "2026-08-29T10:00:00Z")
                .put("expires_at", "2026-08-29T10:10:00Z")
                .put("level", "NORMAL")
                .put("reason", "STORAGE_UTILIZATION"),
        )
        .toString()

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
