
export const FIELD_TEST_TOKEN_HEADER = "x-walksafe-field-test-token";

export const ACTOR_ID_HEADER = "x-walksafe-actor-id";

export const ACTOR_ASSERTION_HEADER = "x-walksafe-actor-assertion";

export const ACCOUNT_GENERATION_HEADER = "x-walksafe-account-generation";

export const RAW_REQUEST_PROOF_HEADER = "x-walksafe-raw-request-proof";

export const RAW_PURPOSE_HEADER = "x-walksafe-raw-purpose";

export const RAW_WALK_ID_HEADER = "x-walksafe-raw-walk-id";

export const RAW_MANIFEST_SHA256_HEADER = "x-walksafe-raw-manifest-sha256";

export const RAW_CHUNK_SHA256_HEADER = "x-walksafe-chunk-sha256";

export const RAW_COMMIT_SHA256_HEADER = "x-walksafe-raw-commit-sha256";

export const RAW_CONSENT_RECEIPT_SHA256_HEADER =
  "x-walksafe-consent-receipt-sha256";

export const DELETION_ACCESS_PRE_DIGEST_HEADER =
  "x-walksafe-deletion-access-pre-digest";

export const DELETION_TOMBSTONE_HEADER =
  "x-walksafe-deletion-tombstone-id";

export const DEFAULT_PROXY_TIMEOUT_MS = 15_000;

/**
 * S19-59: a report v2 upload's own limits, the multipart read and the Backend proxy call
 * (both DEFAULT_PROXY_TIMEOUT_MS); the adapter's whole-request deadline is set above this.
 */
export const REPORT_V2_OWN_LIMITS_MS = 2 * DEFAULT_PROXY_TIMEOUT_MS;

/**
 * gw-F6 (S19-59): the per-request Backend account-session check (routes.ts
 * backendAccountSessionGate) that a v7 account session passes before its route runs. It is
 * a small bodyless POST, so it has its own short limit ("unavailable" → 503, never a logout),
 * and the adapter's whole-request deadline adds it to every route's own limits.
 */
export const BACKEND_SESSION_GATE_TIMEOUT_MS = 5_000;

/** gw-F6: an ordinary route's own limits: bounded JSON body read 10 s + one Backend call. */
export const ORDINARY_ROUTE_OWN_LIMITS_MS = 10_000 + DEFAULT_PROXY_TIMEOUT_MS;

export type GatewayFetch = (
  input: string | URL | Request,
  init?: RequestInit
) => Promise<Response>;

