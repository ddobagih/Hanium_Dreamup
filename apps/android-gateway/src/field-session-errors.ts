/**
 * S01-20: public error contract of `/api/field-session` (login, refresh, status,
 * device list, device revoke, logout). One row per code: allowed HTTP statuses, the
 * typed next action a client must take, and the exact `retryable` /
 * `reauthentication_required` flags carried by the body (undefined = field absent).
 *
 * The same table is published as `components.schemas.FieldSessionError` in
 * openapi.json and mirrored by Android `GatewaySessionErrorContract`. A code that is
 * not listed here is not part of the contract: clients must treat it as
 * REAUTHENTICATE without trusting its text.
 */
export type FieldSessionNextAction =
  | "RETRY_SAFE"
  | "REAUTHENTICATE"
  | "OUTCOME_UNKNOWN"
  | "SESSION_REVOKED";

export type FieldSessionErrorContractEntry = Readonly<{
  code: string;
  statuses: readonly number[];
  next: FieldSessionNextAction;
  retryable?: boolean;
  reauthenticationRequired?: boolean;
}>;

function entry(
  code: string,
  statuses: readonly number[],
  next: FieldSessionNextAction,
  flags: { retryable?: boolean; reauthenticationRequired?: boolean } = {}
): FieldSessionErrorContractEntry {
  return Object.freeze({ code, statuses: Object.freeze([...statuses]), next, ...flags });
}

export const FIELD_SESSION_ERROR_CONTRACT: readonly FieldSessionErrorContractEntry[] =
  Object.freeze([
    // Password login relayed from Backend /accounts/authenticate (account-relay.ts
    // PASSWORD_GRANT_UPSTREAM_ERRORS; any other Backend 429/503 code becomes
    // gateway_account_upstream_invalid).
    entry("account_authentication_busy", [503], "RETRY_SAFE"),
    entry("account_authentication_rate_limited", [429], "RETRY_SAFE"),
    entry("account_authentication_request_invalid", [422], "REAUTHENTICATE"),
    entry("account_authentication_stale", [409], "REAUTHENTICATE"),
    entry("account_authentication_unavailable", [503], "RETRY_SAFE"),
    entry("account_crypto_key_binding_missing", [503], "RETRY_SAFE"),
    entry("account_crypto_key_mismatch", [503], "RETRY_SAFE"),
    entry("account_generation_inactive", [409], "SESSION_REVOKED"),
    entry("backend_account_binding_denied", [401], "SESSION_REVOKED", {
      reauthenticationRequired: true
    }),
    entry("backend_account_check_unavailable", [503], "RETRY_SAFE", { retryable: true }),
    entry("field_long_lived_sessions_unavailable", [503], "RETRY_SAFE"),
    entry("field_session_device_capacity_unavailable", [503], "RETRY_SAFE"),
    entry("field_session_query_invalid", [400], "REAUTHENTICATE"),
    // S19-4-F1: remote revocation of another device needs a recent credential login.
    entry("field_session_reauthentication_required", [401], "REAUTHENTICATE", {
      reauthenticationRequired: true
    }),
    // S19-10: refresh admission before any state access; the presented proof is unchanged.
    entry("field_session_refresh_rate_limited", [429], "RETRY_SAFE", { retryable: true }),
    entry("field_session_request_invalid", [400], "REAUTHENTICATE"),
    entry("field_session_revoked", [401], "SESSION_REVOKED", {
      reauthenticationRequired: true
    }),
    // Owner decision 1 (2026-09-29): this device's family was revoked because a refresh token
    // of the same account was reused; every device of the account was logged out.
    entry("field_session_revoked_reuse_detected", [401], "REAUTHENTICATE", {
      reauthenticationRequired: true
    }),
    entry("field_session_storage_busy", [503], "RETRY_SAFE", { retryable: true }),
    entry("field_session_storage_outcome_unknown", [500], "OUTCOME_UNKNOWN", {
      retryable: false,
      reauthenticationRequired: true
    }),
    // S19-11: only for failures before any commit; the credential stays valid.
    entry("field_session_storage_unavailable", [503], "RETRY_SAFE", { retryable: true }),
    entry("gateway_account_service_not_configured", [503], "RETRY_SAFE"),
    entry("gateway_account_upstream_invalid", [502], "RETRY_SAFE"),
    entry("gateway_auth_required", [401], "REAUTHENTICATE"),
    entry("gateway_client_closed", [499], "OUTCOME_UNKNOWN"),
    entry("gateway_forbidden", [403], "REAUTHENTICATE"),
    // Node adapter (node-adapter.ts), applies to every route: an unparsable request never
    // reached the handler; a handler exception may have happened after a commit.
    entry("gateway_internal_error", [500], "OUTCOME_UNKNOWN"),
    entry("gateway_invalid_request", [400], "REAUTHENTICATE"),
    entry("gateway_login_busy", [503], "RETRY_SAFE"),
    entry("gateway_login_capacity_unavailable", [503], "RETRY_SAFE"),
    entry("gateway_login_global_rate_limited", [429], "RETRY_SAFE"),
    entry("gateway_login_rate_limited", [429], "RETRY_SAFE"),
    entry("gateway_login_total_rate_limited", [429], "RETRY_SAFE"),
    // Route dispatch for a method /api/field-session does not declare (Allow header set).
    entry("gateway_method_not_allowed", [405], "REAUTHENTICATE"),
    // S19-59: Node adapter, the handler passed its whole-request deadline and was cancelled;
    // work that did not observe the cancellation may still have committed.
    entry("gateway_request_deadline_exceeded", [504], "OUTCOME_UNKNOWN"),
    entry("gateway_security_not_configured", [503], "RETRY_SAFE"),
    entry("gateway_trusted_client_ip_required", [400], "REAUTHENTICATE"),
    // S19-53: production adapter, the proxy did not set the trusted client IP header.
    entry("gateway_trusted_client_ip_unavailable", [503], "RETRY_SAFE"),
    entry("gateway_upstream_response_timeout", [504], "RETRY_SAFE"),
    entry("gateway_upstream_timeout", [504], "RETRY_SAFE"),
    entry("gateway_upstream_unavailable", [502], "RETRY_SAFE"),
    entry("invalid_account_credentials", [401], "REAUTHENTICATE"),
    entry("invalid_content_length", [400], "REAUTHENTICATE"),
    entry("invalid_field_test_token", [401], "REAUTHENTICATE"),
    entry("invalid_json_body", [400], "REAUTHENTICATE"),
    entry("invalid_refresh_token", [401], "REAUTHENTICATE", {
      reauthenticationRequired: true
    }),
    entry("json_content_type_required", [415], "REAUTHENTICATE"),
    entry("privacy_ledger_unavailable", [503], "RETRY_SAFE"),
    entry("refresh_rotation_limit_reached", [401], "REAUTHENTICATE", {
      reauthenticationRequired: true
    }),
    entry("refresh_token_absolute_expired", [401], "REAUTHENTICATE", {
      reauthenticationRequired: true
    }),
    entry("refresh_token_idle_expired", [401], "REAUTHENTICATE", {
      reauthenticationRequired: true
    }),
    entry("refresh_token_reuse_detected", [401], "SESSION_REVOKED", {
      reauthenticationRequired: true
    }),
    entry("request_body_invalid_utf8", [400], "REAUTHENTICATE"),
    entry("request_body_read_failed", [400], "REAUTHENTICATE"),
    entry("request_body_read_timeout", [408], "RETRY_SAFE"),
    entry("request_body_required", [400], "REAUTHENTICATE"),
    entry("request_body_too_large", [413], "REAUTHENTICATE")
  ]);

const BY_CODE = new Map(FIELD_SESSION_ERROR_CONTRACT.map((row) => [row.code, row]));

export function fieldSessionErrorContractEntry(
  code: string
): FieldSessionErrorContractEntry | null {
  return BY_CODE.get(code) ?? null;
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

/**
 * Returns null when a field-session error response matches the table, otherwise a
 * short reason. Used by the contract tests; production responses are not rewritten.
 */
export function fieldSessionErrorContractViolation(
  status: number,
  body: unknown
): string | null {
  const payload = objectValue(body);
  if (!payload) return "error body is not a JSON object";
  const detail = objectValue(payload.detail);
  const code = detail?.code ?? payload.code;
  if (typeof code !== "string") return "error body has no code";
  const row = BY_CODE.get(code);
  if (!row) return `unregistered field-session error code ${code}`;
  if (!row.statuses.includes(status)) return `${code} returned unregistered status ${status}`;
  const flags = detail ?? payload;
  if (flags.retryable !== row.retryable) return `${code} retryable flag differs`;
  if (flags.reauthentication_required !== row.reauthenticationRequired) {
    return `${code} reauthentication_required flag differs`;
  }
  return null;
}
