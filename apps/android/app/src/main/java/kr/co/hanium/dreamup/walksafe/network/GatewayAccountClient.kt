package kr.co.hanium.dreamup.walksafe.network

import java.net.IDN
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kr.co.hanium.dreamup.walksafe.account.ACCOUNT_CREATE_SCHEMA
import kr.co.hanium.dreamup.walksafe.account.EMAIL_ENROLLMENT_SCHEMA
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_CONSENT_SCHEMA
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import kr.co.hanium.dreamup.walksafe.account.strictInstantEpochMs
import kr.co.hanium.dreamup.walksafe.account.validEnrollmentHandle
import org.json.JSONObject

internal data class EmailOtpEnrollmentResponse(
    val enrollmentHandle: String,
    val expiresAtEpochMs: Long,
    val resendAvailableAtEpochMs: Long,
)

internal data class CreatedEmailAccount(
    val actorId: String,
    val accountGeneration: Long,
    val signupReceiptSha256: String,
)

internal class GatewayAccountHttpException(
    val statusCode: Int,
    val reason: String,
    val serverCode: String? = null,
    val retryAfterMs: Long? = null,
) : IllegalStateException("gateway account request failed: $reason status=$statusCode")

internal class GatewayAccountClient(
    private val transport: GatewaySessionTransport = HttpUrlConnectionGatewaySessionTransport(),
) {
    fun requestEmailOtp(
        gatewayBaseUrl: String,
        email: String,
        dateOfBirth: String,
        requestId: String,
    ): EmailOtpEnrollmentResponse {
        requireApprovedRequestOrigin(gatewayBaseUrl)
        if (
            !GatewayAccountInputPolicy.validEmail(email) ||
            !GatewayAccountInputPolicy.validDateOfBirth(dateOfBirth) ||
            !REQUEST_ID.matches(requestId)
        ) throw GatewayAccountHttpException(0, "invalid_account_enrollment_input")
        val body = JSONObject()
            .put("schema_version", EMAIL_ENROLLMENT_SCHEMA)
            .put("email", email)
            .put("date_of_birth", dateOfBirth)
            .put("request_id", requestId)
        val response = transport.postJson(
            gatewayBaseUrl.trimEnd('/') + "/api/account-enrollments/email-otp",
            body.toString(),
        )
        if (response.statusCode != 202) throw response.toAccountException("email_otp_request_failed")
        val value = response.exactJsonOrNull(
            setOf("schema_version", "enrollment_handle", "expires_at", "resend_available_at"),
        ) ?: throw GatewayAccountHttpException(0, "invalid_email_otp_response")
        if (
            value.optString("schema_version") !=
            "walksafe.account-enrollment-email-otp-response.v1"
        ) throw GatewayAccountHttpException(0, "invalid_email_otp_response")
        val handle = value.optString("enrollment_handle")
        val expiresAt = strictInstantEpochMs(value.optString("expires_at"))
        val resendAt = strictInstantEpochMs(value.optString("resend_available_at"))
        if (
            !validEnrollmentHandle(handle) ||
            expiresAt == null ||
            resendAt == null ||
            resendAt > expiresAt
        ) throw GatewayAccountHttpException(0, "invalid_email_otp_response")
        return EmailOtpEnrollmentResponse(handle, expiresAt, resendAt)
    }

    fun createAccount(
        gatewayBaseUrl: String,
        enrollmentHandle: String,
        otpCode: String,
        password: String,
        selections: SignupConsentSelections,
    ): CreatedEmailAccount {
        requireApprovedRequestOrigin(gatewayBaseUrl)
        if (
            !validEnrollmentHandle(enrollmentHandle) ||
            !OTP.matches(otpCode) ||
            !GatewayAccountInputPolicy.validPassword(password) ||
            !selections.requiredGranted
        ) throw GatewayAccountHttpException(0, "invalid_account_create_input")
        val consent = JSONObject()
            .put("schema_version", SIGNUP_CONSENT_SCHEMA)
            .put("document_versions", JSONObject(SIGNUP_DOCUMENT_VERSIONS))
            .put("selections", selections.toWireJson())
        val body = JSONObject()
            .put("schema_version", ACCOUNT_CREATE_SCHEMA)
            .put("enrollment_handle", enrollmentHandle)
            .put("otp_code", otpCode)
            .put("password", password)
            .put("consent", consent)
        val response = transport.postJson(
            gatewayBaseUrl.trimEnd('/') + "/api/accounts",
            body.toString(),
        )
        if (response.statusCode != 201) throw response.toAccountException("account_create_failed")
        val value = response.exactJsonOrNull(
            setOf(
                "schema_version",
                "actor_id",
                "account_generation",
                "signup_receipt_sha256",
            ),
        ) ?: throw GatewayAccountHttpException(0, "invalid_account_create_response")
        val actorId = value.optString("actor_id")
        val generation = value.strictLongOrNull("account_generation")
        val receipt = value.optString("signup_receipt_sha256")
        if (
            value.optString("schema_version") != "walksafe.account.v1" ||
            !BACKEND_ACTOR_ID.matches(actorId) ||
            generation == null ||
            generation < 1L ||
            !SHA256.matches(receipt)
        ) throw GatewayAccountHttpException(0, "invalid_account_create_response")
        return CreatedEmailAccount(actorId, generation, receipt)
    }

    private fun requireApprovedRequestOrigin(gatewayBaseUrl: String) {
        val approved = GatewayEndpointPolicy.debugOriginOrNull(gatewayBaseUrl)
        if (approved == null || approved != gatewayBaseUrl.trimEnd('/')) {
            throw GatewayAccountHttpException(0, "invalid_gateway_url")
        }
    }
}

internal object GatewayAccountInputPolicy {
    private val LOCAL =
        Regex("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$")
    private val DOMAIN_LABEL =
        Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT)

    fun validEmail(value: String): Boolean {
        if (value != value.trim() || value.length !in 3..254 || value.count { it == '@' } != 1) {
            return false
        }
        val local = value.substringBefore('@')
        val rawDomain = value.substringAfter('@')
        if (local.length !in 1..64 || !LOCAL.matches(local) || rawDomain.endsWith('.')) return false
        val domain = runCatching { IDN.toASCII(rawDomain).lowercase() }.getOrNull() ?: return false
        val labels = domain.split('.')
        return domain.length in 1..253 &&
            local.length + domain.length + 1 <= 254 &&
            labels.size >= 2 &&
            labels.all(DOMAIN_LABEL::matches)
    }

    fun validDateOfBirth(value: String): Boolean =
        value.length == 10 && runCatching { LocalDate.parse(value, DATE) }.isSuccess

    fun validPassword(value: String): Boolean =
        value.codePointCount(0, value.length) in 10..128 && '\u0000' !in value
}

private fun GatewayHttpResponse.exactJsonOrNull(fields: Set<String>): JSONObject? = runCatching {
    val value = JSONObject(responseBody)
    value.takeIf { it.jsonKeys() == fields }
}.getOrNull()

private fun GatewayHttpResponse.toAccountException(reason: String): GatewayAccountHttpException {
    val value = runCatching { JSONObject(responseBody) }.getOrNull()
    val nested = value?.optJSONObject("detail")
    val code = (nested?.optString("code") ?: value?.optString("code"))
        ?.takeIf(SAFE_SERVER_CODE::matches)
    val retryAfterMs = header("Retry-After")
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it in 1..300L }
        ?.times(1_000L)
    return GatewayAccountHttpException(statusCode, reason, code, retryAfterMs)
}

private fun JSONObject.jsonKeys(): Set<String> {
    val result = mutableSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) result += iterator.next()
    return result
}

private fun JSONObject.strictLongOrNull(name: String): Long? = when (val value = opt(name)) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
private val OTP = Regex("^[0-9]{6}$")
private val BACKEND_ACTOR_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val SAFE_SERVER_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
