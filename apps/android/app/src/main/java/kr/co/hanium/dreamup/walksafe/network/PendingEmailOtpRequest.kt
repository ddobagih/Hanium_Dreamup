package kr.co.hanium.dreamup.walksafe.network

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/**
 * S01-5: the minimum record that lets a restarted process resend the same OTP request ID.
 * It holds no OTP, password, email or birth date: only the wire request ID, the install owner
 * binding, a digest of the request fields and the wall-clock start time (bounded by the
 * server OTP TTL cap).
 */
internal data class PendingEmailOtpRequest(
    val requestId: String,
    val ownerBindingSha256: String,
    val payloadSha256: String,
    val startedAtEpochMs: Long,
) {
    init {
        require(REQUEST_ID.matches(requestId))
        require(SHA256.matches(ownerBindingSha256))
        require(SHA256.matches(payloadSha256))
        require(startedAtEpochMs > 0L)
    }

    override fun toString(): String = "PendingEmailOtpRequest(redacted)"

    fun reusableFor(payload: EmailOtpRequestRetryPolicy.Payload, nowEpochMs: Long): Boolean =
        ownerBindingSha256 == payload.ownerBindingSha256 &&
            payloadSha256 == pendingEmailOtpPayloadSha256(payload, requestId) &&
            nowEpochMs - startedAtEpochMs in 0L..PENDING_EMAIL_OTP_REQUEST_TTL_MS

    companion object {
        private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** Server OTP TTL cap (Backend WALKSAFE_ACCOUNT_OTP_TTL_SECONDS <= 1800). */
internal const val PENDING_EMAIL_OTP_REQUEST_TTL_MS = 30L * 60L * 1_000L

internal interface PendingEmailOtpRequestStore {
    fun loadPendingOtpRequest(): PendingEmailOtpRequest?
    fun savePendingOtpRequest(request: PendingEmailOtpRequest): Boolean
    fun clearPendingOtpRequest(): Boolean
}

/**
 * Digest of the request fields that survive a restart. The process-local signup attempt epoch is
 * deliberately excluded; an email or birth date change still yields a new request ID.
 * S01-5-F1: salted with the random [requestId] (domain tag v2), so a decrypted record cannot confirm
 * a guessed email + birth date against a precomputed digest. v1 records simply stop matching.
 */
internal fun pendingEmailOtpPayloadSha256(
    payload: EmailOtpRequestRetryPolicy.Payload,
    requestId: String,
): String =
    MessageDigest.getInstance("SHA-256").digest(
        listOf(
            "walksafe.email-otp-pending-request.v2",
            requestId,
            payload.gatewayBaseUrl,
            payload.ownerBindingSha256,
            payload.email,
            payload.dateOfBirth,
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

internal object PendingEmailOtpRequestCodec {
    private const val SCHEMA = "walksafe.android-email-otp-pending.v1"
    private val FIELDS = setOf(
        "schema_version", "request_id", "owner_binding_sha256", "payload_sha256", "started_at_epoch_ms",
    )

    fun encode(request: PendingEmailOtpRequest): String = JSONObject()
        .put("schema_version", SCHEMA)
        .put("request_id", request.requestId)
        .put("owner_binding_sha256", request.ownerBindingSha256)
        .put("payload_sha256", request.payloadSha256)
        .put("started_at_epoch_ms", request.startedAtEpochMs)
        .toString()

    fun decode(encoded: String): PendingEmailOtpRequest? = runCatching {
        if (encoded.length !in 2..1_024) return@runCatching null
        if (!strictJsonObjectDocument(encoded)) return@runCatching null
        val value = JSONObject(encoded)
        val keys = buildSet { value.keys().forEach { add(it) } }
        if (keys != FIELDS || value.opt("schema_version") != SCHEMA) return@runCatching null
        val started = when (val raw = value.opt("started_at_epoch_ms")) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> return@runCatching null
        }
        PendingEmailOtpRequest(
            requestId = value.opt("request_id") as? String ?: return@runCatching null,
            ownerBindingSha256 = value.opt("owner_binding_sha256") as? String ?: return@runCatching null,
            payloadSha256 = value.opt("payload_sha256") as? String ?: return@runCatching null,
            startedAtEpochMs = started,
        )
    }.getOrNull()
}
