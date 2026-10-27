package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.network.GatewaySessionNextAction.OUTCOME_UNKNOWN
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionNextAction.REAUTHENTICATE
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionNextAction.RETRY_SAFE
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionNextAction.SESSION_REVOKED
import org.json.JSONObject

/** S01-20: what a caller may do next after a field-session failure. */
enum class GatewaySessionNextAction {
    /** The same request may be sent again; nothing was applied. */
    RETRY_SAFE,

    /** Log in again (also the fail-closed answer for anything unregistered). */
    REAUTHENTICATE,

    /** The server may or may not have applied the request; do not replay old credentials. */
    OUTCOME_UNKNOWN,

    /** The server ended this session or account generation. */
    SESSION_REVOKED,
}

/**
 * S01-20: mirror of apps/android-gateway/src/field-session-errors.ts, published as
 * openapi.json components.schemas.FieldSessionError (x-walksafe-error-contract).
 * GatewaySessionErrorContractTest fails when the two differ. A code, status or flag outside
 * this table maps to REAUTHENTICATE and the code is not exposed.
 */
internal object GatewaySessionErrorContract {
    internal data class Entry(
        val statuses: Set<Int>,
        val next: GatewaySessionNextAction,
        val retryable: Boolean?,
        val reauthenticationRequired: Boolean?,
    )

    private fun entry(
        status: Int,
        next: GatewaySessionNextAction,
        retryable: Boolean? = null,
        reauthenticationRequired: Boolean? = null,
    ) = Entry(setOf(status), next, retryable, reauthenticationRequired)

    val ENTRIES: Map<String, Entry> = linkedMapOf(
        "account_authentication_busy" to entry(503, RETRY_SAFE),
        "account_authentication_rate_limited" to entry(429, RETRY_SAFE),
        "account_authentication_request_invalid" to entry(422, REAUTHENTICATE),
        "account_authentication_stale" to entry(409, REAUTHENTICATE),
        "account_authentication_unavailable" to entry(503, RETRY_SAFE),
        "account_crypto_key_binding_missing" to entry(503, RETRY_SAFE),
        "account_crypto_key_mismatch" to entry(503, RETRY_SAFE),
        "account_generation_inactive" to entry(409, SESSION_REVOKED),
        "backend_account_binding_denied" to entry(401, SESSION_REVOKED,
            reauthenticationRequired = true,
        ),
        "backend_account_check_unavailable" to entry(503, RETRY_SAFE, retryable = true),
        "field_long_lived_sessions_unavailable" to entry(503, RETRY_SAFE),
        "field_session_device_capacity_unavailable" to entry(503, RETRY_SAFE),
        "field_session_query_invalid" to entry(400, REAUTHENTICATE),
        // S19-4-F1: revoking another device's session needs a recent password login.
        "field_session_reauthentication_required" to entry(401, REAUTHENTICATE,
            reauthenticationRequired = true,
        ),
        // S19-10: refresh refused by admission before any state access; the proof is unchanged.
        "field_session_refresh_rate_limited" to entry(429, RETRY_SAFE, retryable = true),
        "field_session_request_invalid" to entry(400, REAUTHENTICATE),
        "field_session_revoked" to entry(401, SESSION_REVOKED, reauthenticationRequired = true),
        // Owner decision 1 (2026-09-29): a reused refresh token of this account logged out every device.
        "field_session_revoked_reuse_detected" to entry(401, REAUTHENTICATE,
            reauthenticationRequired = true,
        ),
        "field_session_storage_busy" to entry(503, RETRY_SAFE, retryable = true),
        "field_session_storage_outcome_unknown" to entry(500, OUTCOME_UNKNOWN,
            retryable = false, reauthenticationRequired = true,
        ),
        // S19-11: storage failed before any commit; the credential stays valid.
        "field_session_storage_unavailable" to entry(503, RETRY_SAFE, retryable = true),
        "gateway_account_service_not_configured" to entry(503, RETRY_SAFE),
        "gateway_account_upstream_invalid" to entry(502, RETRY_SAFE),
        "gateway_auth_required" to entry(401, REAUTHENTICATE),
        "gateway_client_closed" to entry(499, OUTCOME_UNKNOWN),
        "gateway_forbidden" to entry(403, REAUTHENTICATE),
        "gateway_internal_error" to entry(500, OUTCOME_UNKNOWN),
        "gateway_invalid_request" to entry(400, REAUTHENTICATE),
        "gateway_login_busy" to entry(503, RETRY_SAFE),
        "gateway_login_capacity_unavailable" to entry(503, RETRY_SAFE),
        "gateway_login_global_rate_limited" to entry(429, RETRY_SAFE),
        "gateway_login_rate_limited" to entry(429, RETRY_SAFE),
        "gateway_login_total_rate_limited" to entry(429, RETRY_SAFE),
        "gateway_method_not_allowed" to entry(405, REAUTHENTICATE),
        // S19-59: the handler passed its whole-request deadline; it may still have committed.
        "gateway_request_deadline_exceeded" to entry(504, OUTCOME_UNKNOWN),
        "gateway_security_not_configured" to entry(503, RETRY_SAFE),
        "gateway_trusted_client_ip_required" to entry(400, REAUTHENTICATE),
        // S19-53: production refused a request the proxy sent without the trusted client IP.
        "gateway_trusted_client_ip_unavailable" to entry(503, RETRY_SAFE),
        "gateway_upstream_response_timeout" to entry(504, RETRY_SAFE),
        "gateway_upstream_timeout" to entry(504, RETRY_SAFE),
        "gateway_upstream_unavailable" to entry(502, RETRY_SAFE),
        "invalid_account_credentials" to entry(401, REAUTHENTICATE),
        "invalid_content_length" to entry(400, REAUTHENTICATE),
        "invalid_field_test_token" to entry(401, REAUTHENTICATE),
        "invalid_json_body" to entry(400, REAUTHENTICATE),
        "invalid_refresh_token" to entry(401, REAUTHENTICATE, reauthenticationRequired = true),
        "json_content_type_required" to entry(415, REAUTHENTICATE),
        "privacy_ledger_unavailable" to entry(503, RETRY_SAFE),
        "refresh_rotation_limit_reached" to entry(401, REAUTHENTICATE,
            reauthenticationRequired = true,
        ),
        "refresh_token_absolute_expired" to entry(401, REAUTHENTICATE,
            reauthenticationRequired = true,
        ),
        "refresh_token_idle_expired" to entry(401, REAUTHENTICATE, reauthenticationRequired = true),
        "refresh_token_reuse_detected" to entry(401, SESSION_REVOKED,
            reauthenticationRequired = true,
        ),
        "request_body_invalid_utf8" to entry(400, REAUTHENTICATE),
        "request_body_read_failed" to entry(400, REAUTHENTICATE),
        "request_body_read_timeout" to entry(408, RETRY_SAFE),
        "request_body_required" to entry(400, REAUTHENTICATE),
        "request_body_too_large" to entry(413, REAUTHENTICATE),
    )

    /** Registered code of an error body (flat `code` or nested `detail.code`), else null. */
    fun registeredCodeOrNull(body: String): String? = parsed(body)?.first?.takeIf(ENTRIES::containsKey)

    fun nextAction(statusCode: Int, body: String): GatewaySessionNextAction {
        val (code, flags) = parsed(body) ?: return REAUTHENTICATE
        val entry = ENTRIES[code] ?: return REAUTHENTICATE
        val retryable = flag(flags, "retryable") ?: return REAUTHENTICATE
        val reauthentication = flag(flags, "reauthentication_required") ?: return REAUTHENTICATE
        return if (
            statusCode in entry.statuses &&
            retryable.value == entry.retryable &&
            reauthentication.value == entry.reauthenticationRequired
        ) {
            entry.next
        } else {
            REAUTHENTICATE
        }
    }

    private class Flag(val value: Boolean?)

    /** Absent -> Flag(null); JSON boolean -> Flag(value); anything else -> null (invalid). */
    private fun flag(source: JSONObject, name: String): Flag? = when (val value = source.opt(name)) {
        null -> Flag(null)
        is Boolean -> Flag(value)
        else -> null
    }

    private fun parsed(body: String): Pair<String, JSONObject>? = runCatching {
        if (!strictJsonObjectDocument(body)) return@runCatching null
        val root = JSONObject(body)
        val detail = root.optJSONObject("detail")
        val source = detail ?: root
        val code = source.opt("code") as? String ?: return@runCatching null
        code to source
    }.getOrNull()
}
