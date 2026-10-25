package kr.co.hanium.dreamup.walksafe.network

import java.time.LocalDate
import java.time.ZoneId
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
    approvedReleaseOrigin: String = kr.co.hanium.dreamup.walksafe.BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
    allowDebugOrigin: Boolean = kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG,
    private val clockEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val originGuard = GatewayAccountOriginGuard(approvedReleaseOrigin, allowDebugOrigin)

    fun signupUnavailableOptionalDocuments(gatewayBaseUrl: String): Set<String> {
        requireApprovedRequestOrigin(gatewayBaseUrl)
        val response = transport.get(gatewayBaseUrl.trimEnd('/') + "/api/account-signup-policy", emptyMap())
        if (response.statusCode != 200) throw response.toAccountException("signup_policy_unavailable")
        val value = response.exactJsonOrNull(setOf("schema_version", "documents"))
            ?: throw GatewayAccountHttpException(0, "invalid_signup_policy")
        return kr.co.hanium.dreamup.walksafe.account.SignupPolicyPreflight.unavailableOptional(value)
    }

    fun requestEmailOtp(
        gatewayBaseUrl: String,
        email: String,
        dateOfBirth: String,
        requestId: String,
    ): EmailOtpEnrollmentResponse {
        requireApprovedRequestOrigin(gatewayBaseUrl)
        val normalizedDateOfBirth = GatewayAccountInputPolicy.normalizedDateOfBirth(dateOfBirth)
        val canonicalEmail = GatewayAccountInputPolicy.canonicalEmailOrNull(email)
        if (
            canonicalEmail == null ||
            normalizedDateOfBirth == null ||
            !REQUEST_ID.matches(requestId)
        ) throw GatewayAccountHttpException(0, "invalid_account_enrollment_input")
        val body = JSONObject()
            .put("schema_version", EMAIL_ENROLLMENT_SCHEMA)
            .put("email", canonicalEmail)
            .put("date_of_birth", normalizedDateOfBirth)
            .put("request_id", requestId)
        val response = transport.postJson(
            gatewayBaseUrl.trimEnd('/') + "/api/account-enrollments/email-otp",
            body.toString(),
            totalTimeoutMs = EMAIL_OTP_REQUEST_TOTAL_TIMEOUT_MS,
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
            resendAt > expiresAt ||
            // S01-9: server TTL cap and clock skew, not only format and order.
            !kr.co.hanium.dreamup.walksafe.account.EmailOtpResponseBounds.accepts(
                clockEpochMs(), expiresAt, resendAt,
            )
        ) throw GatewayAccountHttpException(0, "invalid_email_otp_response")
        return EmailOtpEnrollmentResponse(handle, expiresAt, resendAt)
    }

    fun createAccount(
        gatewayBaseUrl: String,
        enrollmentHandle: String,
        otpCode: String,
        password: String,
        selections: SignupConsentSelections,
        documentVersions: Map<String, String> = SIGNUP_DOCUMENT_VERSIONS,
    ): CreatedEmailAccount {
        requireApprovedRequestOrigin(gatewayBaseUrl)
        if (
            !validEnrollmentHandle(enrollmentHandle) ||
            !OTP.matches(otpCode) ||
            !GatewayAccountInputPolicy.validNewPassword(password) ||
            !selections.requiredGranted || documentVersions != SIGNUP_DOCUMENT_VERSIONS
        ) throw GatewayAccountHttpException(0, "invalid_account_create_input")
        val consent = JSONObject()
            .put("schema_version", SIGNUP_CONSENT_SCHEMA)
            .put("document_versions", JSONObject(documentVersions))
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
        if (originGuard.originOrNull(gatewayBaseUrl) == null) {
            throw GatewayAccountHttpException(0, "invalid_gateway_url")
        }
    }
}

internal class GatewayAccountOriginGuard(
    private val approvedReleaseOrigin: String,
    private val allowDebugOrigin: Boolean,
) {
    fun originOrNull(raw: String): String? {
        val origin = if (allowDebugOrigin) {
            GatewayEndpointPolicy.debugOriginOrNull(raw)
        } else {
            GatewayEndpointPolicy.approvedReleaseOriginOrNull(raw, approvedReleaseOrigin)
        }
        return origin?.takeIf { it == raw.trimEnd('/') }
    }
}

internal object GatewayAccountInputPolicy {
    private val DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT)
    private val COMPACT_DATE = Regex("^[0-9]{8}$")
    private val ISO_DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val ACCOUNT_DATE_ZONE = ZoneId.of("Asia/Seoul")

    fun validEmail(value: String): Boolean = canonicalEmailOrNull(value) != null

    fun canonicalEmailOrNull(value: String): String? =
        kr.co.hanium.dreamup.walksafe.security.AccountEmailNormalizer.canonicalOrNull(value)

    fun validDateOfBirth(value: String): Boolean =
        normalizedDateOfBirth(value) != null

    fun normalizedDateOfBirth(value: String): String? {
        val canonical = when {
            COMPACT_DATE.matches(value) ->
                "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
            ISO_DATE.matches(value) -> value
            else -> return null
        }
        val date = runCatching { LocalDate.parse(canonical, DATE) }.getOrNull() ?: return null
        // The server remains authoritative for the minimum age, using the Seoul calendar date.
        if (date.year < 1 || date.isAfter(LocalDate.now(ACCOUNT_DATE_ZONE))) return null
        return canonical
    }

    // Login keeps the existing 10..128 range for accounts created before FP-010-DETAIL-02.
    fun validPassword(value: String): Boolean =
        value.codePointCount(0, value.length) in 10..128 && '\u0000' !in value

    // FP-010-DETAIL-02 (S01-13): new passwords need 12..128 code points. Common or
    // leaked values are refused by the Backend (account_password_too_common).
    fun validNewPassword(value: String): Boolean =
        value.codePointCount(0, value.length) in 12..128 && '\u0000' !in value
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

// SMTP 30s < Gateway upstream 40s < Gateway adapter 60s < this OTP-only call budget.
internal const val EMAIL_OTP_REQUEST_TOTAL_TIMEOUT_MS = 70_000L
