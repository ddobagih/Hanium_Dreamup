package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.account.EmailEnrollmentPartial
import kr.co.hanium.dreamup.walksafe.account.EmailEnrollmentPartialCodec
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class EnrollmentJsonBoundaryTest(private val form: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun forms() = listOf("canonical", "whitespace", "trailing-object", "trailing-text",
            "unquoted-key", "single-quotes", "trailing-comma", "escaped-duplicate").map { arrayOf(it) }
        private val HANDLE = "A".repeat(43)
        private val OWNER = "a".repeat(64)
        private const val ACTOR = "12345678-1234-4123-8123-123456789abc"
        private const val NOW = 1_787_997_600_000L
    }

    @Test fun otpResponseRequiresOneStrictDocument() {
        val response = JSONObject().put("schema_version", "walksafe.account-enrollment-email-otp-response.v1")
            .put("enrollment_handle", HANDLE).put("expires_at", "2026-08-29T10:10:00Z")
            .put("resend_available_at", "2026-08-29T10:01:00Z").toString()
        checkResponse(response, 202, "invalid_email_otp_response") { client ->
            assertEquals(HANDLE, client.requestEmailOtp("https://gateway.example", "person@example.com",
                "2000-02-29", "request_1234567890").enrollmentHandle)
        }
    }

    @Test fun accountCreationRequiresOneStrictDocument() {
        val response = JSONObject().put("schema_version", "walksafe.account.v1").put("actor_id", ACTOR)
            .put("account_generation", 1).put("signup_receipt_sha256", "b".repeat(64)).toString()
        checkResponse(response, 201, "invalid_account_create_response") { client ->
            assertEquals(ACTOR, client.createAccount("https://gateway.example", HANDLE, "012345",
                "correct horse battery", SignupConsentSelections(true, true, true)).actorId)
        }
    }

    @Test fun signupPolicyRequiresOneStrictDocument() {
        val documents = JSONObject()
        SIGNUP_DOCUMENT_VERSIONS.entries.forEachIndexed { index, (name, version) ->
            documents.put(name, JSONObject().put("version", version).put("required", index < 3)
                .put("equivalent_versions", JSONArray()).put("prior_versions", JSONArray()))
        }
        val response = JSONObject().put("schema_version", "walksafe.account-signup-policy.v1")
            .put("documents", documents).toString()
        checkResponse(response, 200, "invalid_signup_policy") { client ->
            assertTrue(client.signupUnavailableOptionalDocuments("https://gateway.example").isEmpty())
        }
    }

    @Test fun pendingOtpRecordRejectsAmbiguousDocumentBeforeReuse() {
        val record = PendingEmailOtpRequest("request_first_0000001", OWNER, "b".repeat(64), NOW)
        val parsed = PendingEmailOtpRequestCodec.decode(mutate(PendingEmailOtpRequestCodec.encode(record)))
        if (valid) assertEquals(record, parsed) else assertNull(parsed)
    }

    @Test fun savedEnrollmentRejectsAmbiguousDocumentBeforeRestore() {
        val record = EmailEnrollmentPartial(HANDLE, NOW + 600_000L, NOW + 60_000L, OWNER,
            selections = SignupConsentSelections(true, true, true))
        val parsed = EmailEnrollmentPartialCodec.decode(mutate(EmailEnrollmentPartialCodec.encode(record)))
        if (valid) assertEquals(record, parsed) else assertNull(parsed)
    }

    private val valid get() = form in setOf("canonical", "whitespace")
    private fun mutate(value: String): String = when (form) {
        "canonical" -> value
        "whitespace" -> " \r\n\t$value\t\r\n "
        "trailing-object" -> value + "{}"
        "trailing-text" -> value + " private-trailing-response"
        "unquoted-key" -> value.replace("\"schema_version\":", "schema_version:")
        "single-quotes" -> value.replace('"', '\'')
        "trailing-comma" -> value.dropLast(1) + ",}"
        "escaped-duplicate" -> value.dropLast(1) + ",\"schem\\u0061_version\":" +
            JSONObject.quote(JSONObject(value).getString("schema_version")) + "}"
        else -> error(form)
    }

    private fun checkResponse(body: String, status: Int, reason: String, action: (GatewayAccountClient) -> Unit) {
        val response = GatewayHttpResponse(status, mutate(body))
        val transport = object : GatewaySessionTransport {
            override fun postJson(url: String, body: String) = response
            override fun get(url: String, headers: Map<String, String>) = response
            override fun delete(url: String, headers: Map<String, String>) = error("unused")
            override fun deleteJson(url: String, body: String) = error("unused")
        }
        val client = GatewayAccountClient(transport, allowDebugOrigin = true, clockEpochMs = { NOW })
        if (valid) action(client) else {
            val failure = assertThrows(GatewayAccountHttpException::class.java) { action(client) }
            assertEquals(reason, failure.reason)
            assertEquals(0, failure.statusCode)
            assertFalse(failure.message.orEmpty().contains("private-trailing-response"))
            assertFalse(failure.message.orEmpty().contains(HANDLE))
        }
    }
}
