import { domainToASCII } from "node:url";

import {
  backendServiceRequestHeaders,
  backendUrl,
  fetchBackend,
  type GatewayFetch
} from "./backend.js";
import { gatewayTrustedClientIp } from "./auth.js";
import { readBoundedJsonBody } from "./request-body.js";

export const ACCOUNT_ENROLLMENT_BODY_LIMIT_BYTES = 4 * 1024;
export const ACCOUNT_CREATE_BODY_LIMIT_BYTES = 16 * 1024;
export const ACCOUNT_UPSTREAM_RESPONSE_LIMIT_BYTES = 16 * 1024;

const ACCOUNT_UPSTREAM_RESPONSE_TIMEOUT_MS = 5_000;
const BACKEND_ACCOUNT_CLIENT_IP_HEADER = "x-walksafe-client-ip";
const SAFE_ERROR_STATUSES = new Set([400, 401, 409, 429, 503]);
const SAFE_ERROR_CODE = /^[a-z][a-z0-9_]{0,63}$/;
const BACKEND_ACTOR_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/;
const EMAIL_OTP = /^[0-9]{6}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const EMAIL_LOCAL = /^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$/;
const DOMAIN_LABEL = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/;

export const SIGNUP_CONSENT_DOCUMENT_KEYS = Object.freeze([
  "terms_of_service",
  "privacy_notice",
  "location_terms",
  "raw_original",
  "automatic_reporting",
  "training_reuse"
] as const);

type SignupConsentDocumentKey = typeof SIGNUP_CONSENT_DOCUMENT_KEYS[number];

export type PasswordGrant = Readonly<{
  email: string | null;
  password: string;
  rememberMe: boolean;
  deviceId: string | null;
}>;

export type BackendAccountAuthentication = Readonly<{
  actorId: string;
  accountGeneration: number;
  authEpoch: number;
}>;

type AuthenticationResult =
  | { value: BackendAccountAuthentication; error?: never }
  | { value?: never; error: Response };

type UpstreamJsonResult =
  | { value: unknown; timeout?: never; error?: never }
  | { value?: never; timeout: true; error?: never }
  | { value?: never; timeout?: never; error: true };

function objectPayload(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const required = [...expected].sort();
  return actual.length === required.length &&
    actual.every((key, index) => key === required[index]);
}

function accountError(status: number, code: string): Response {
  return Response.json(
    { detail: { code } },
    { status, headers: { "cache-control": "no-store" } }
  );
}

function invalidRequest(code: string): Response {
  return accountError(422, code);
}

function serviceHeaders(initial?: HeadersInit): Headers | Response {
  return backendServiceRequestHeaders({
    "content-type": "application/json",
    ...Object.fromEntries(new Headers(initial))
  })
    ?? accountError(503, "gateway_account_service_not_configured");
}

function trustedClientIpRequired(): Response {
  return Response.json(
    {
      code: "gateway_trusted_client_ip_required",
      message: "신뢰할 수 있는 접속 주소를 확인할 수 없습니다."
    },
    { status: 400, headers: { "cache-control": "no-store" } }
  );
}

function canonicalEmail(value: unknown): string | null {
  if (
    typeof value !== "string" ||
    value !== value.trim() ||
    value.length < 3 ||
    value.length > 254 ||
    value.split("@").length !== 2
  ) return null;
  const [local, rawDomain] = value.split("@") as [string, string];
  if (local.length < 1 || local.length > 64 || !EMAIL_LOCAL.test(local) ||
    !rawDomain || rawDomain.endsWith(".")) return null;
  const domain = domainToASCII(rawDomain);
  if (!domain || domain.endsWith(".")) return null;
  const labels = domain.split(".");
  if (!(domain.length >= 1 && domain.length <= 253 &&
    local.length + 1 + domain.length <= 254 && labels.length >= 2 &&
    labels.every(label => DOMAIN_LABEL.test(label) &&
      (label.startsWith("xn--") || label.slice(2, 4) !== "--")))) {
    return null;
  }
  return `${local}@${domain}`;
}

function validDate(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (year < 1) return false;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return parsed.getUTCFullYear() === year &&
    parsed.getUTCMonth() === month - 1 &&
    parsed.getUTCDate() === day;
}

function validPassword(value: unknown): value is string {
  if (typeof value !== "string" || value.includes("\0")) return false;
  const length = [...value].length;
  return length >= 10 && length <= 128;
}

function validBoundedVersion(value: unknown): value is string {
  return typeof value === "string" &&
    value.length >= 1 &&
    value.length <= 64 &&
    /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(value);
}

function validOpaqueHandle(value: unknown): value is string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(value)) return false;
  const decoded = Buffer.from(value, "base64url");
  return decoded.length === 32 && decoded.toString("base64url") === value;
}

function validTimestamp(value: unknown): value is string {
  if (typeof value !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value)) return false;
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime()) &&
    parsed.toISOString().replace(".000Z", "Z") === value;
}

function positiveSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 1;
}

function signupConsent(value: unknown): Record<string, unknown> | null {
  const consent = objectPayload(value);
  if (!consent || !exactKeys(consent, [
    "schema_version",
    "document_versions",
    "selections"
  ]) || consent.schema_version !== "walksafe.signup-consent.v1") return null;
  const documentVersions = objectPayload(consent.document_versions);
  const selections = objectPayload(consent.selections);
  if (
    !documentVersions ||
    !selections ||
    !exactKeys(documentVersions, SIGNUP_CONSENT_DOCUMENT_KEYS) ||
    !exactKeys(selections, SIGNUP_CONSENT_DOCUMENT_KEYS)
  ) return null;
  for (const key of SIGNUP_CONSENT_DOCUMENT_KEYS) {
    if (!validBoundedVersion(documentVersions[key]) || typeof selections[key] !== "boolean") {
      return null;
    }
  }
  if (
    selections.terms_of_service !== true ||
    selections.privacy_notice !== true ||
    selections.location_terms !== true
  ) return null;
  return {
    schema_version: "walksafe.signup-consent.v1",
    document_versions: Object.fromEntries(
      SIGNUP_CONSENT_DOCUMENT_KEYS.map((key: SignupConsentDocumentKey) => [
        key,
        documentVersions[key]
      ])
    ),
    selections: Object.fromEntries(
      SIGNUP_CONSENT_DOCUMENT_KEYS.map((key: SignupConsentDocumentKey) => [
        key,
        selections[key]
      ])
    )
  };
}

function enrollmentRequest(value: unknown): Record<string, unknown> | null {
  const payload = objectPayload(value);
  const email = canonicalEmail(payload?.email);
  if (!payload || !exactKeys(payload, [
    "schema_version", "email", "date_of_birth", "request_id"
  ]) || payload.schema_version !== "walksafe.account-enrollment-email-otp.v1" ||
    email === null || !validDate(payload.date_of_birth) ||
    typeof payload.request_id !== "string" || !REQUEST_ID.test(payload.request_id)) {
    return null;
  }
  return {
    schema_version: payload.schema_version,
    email,
    date_of_birth: payload.date_of_birth,
    request_id: payload.request_id
  };
}

function accountCreateRequest(value: unknown): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload || !exactKeys(payload, [
    "schema_version", "enrollment_handle", "otp_code", "password", "consent"
  ]) || payload.schema_version !== "walksafe.account-create.v1" ||
    !validOpaqueHandle(payload.enrollment_handle) ||
    typeof payload.otp_code !== "string" || !EMAIL_OTP.test(payload.otp_code) ||
    !validPassword(payload.password)) return null;
  const consent = signupConsent(payload.consent);
  return consent ? {
    schema_version: payload.schema_version,
    enrollment_handle: payload.enrollment_handle,
    otp_code: payload.otp_code,
    password: payload.password,
    consent
  } : null;
}

export function parsePasswordGrant(value: unknown): PasswordGrant | null {
  const payload = objectPayload(value);
  const keys = ["grant_type", "email", "password", "remember_me"];
  const hasDeviceId = payload !== null && Object.hasOwn(payload, "device_id");
  if (!payload || !exactKeys(payload, hasDeviceId ? [...keys, "device_id"] : keys) ||
    payload.grant_type !== "password" ||
    !validPassword(payload.password) || typeof payload.remember_me !== "boolean") {
    return null;
  }
  if (hasDeviceId && (typeof payload.device_id !== "string" ||
    !DEVICE_ID.test(payload.device_id))) return null;
  return {
    email: canonicalEmail(payload.email),
    password: payload.password,
    rememberMe: payload.remember_me,
    deviceId: hasDeviceId ? payload.device_id as string : null
  };
}

async function boundedUpstreamJson(response: Response): Promise<UpstreamJsonResult> {
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  const contentLength = response.headers.get("content-length");
  if (
    contentType.split(";", 1)[0]?.trim() !== "application/json" ||
    response.body === null ||
    (contentLength !== null && (
      !/^\d+$/.test(contentLength) ||
      Number(contentLength) > ACCOUNT_UPSTREAM_RESPONSE_LIMIT_BYTES
    ))
  ) {
    void response.body?.cancel().catch(() => undefined);
    return { error: true };
  }
  const headers = new Headers({ "content-type": "application/json" });
  if (contentLength !== null) headers.set("content-length", contentLength);
  const boundedRequest = new Request("http://gateway.invalid/internal/account-response", {
    method: "POST",
    headers,
    body: response.body,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const bounded = await readBoundedJsonBody(
    boundedRequest,
    ACCOUNT_UPSTREAM_RESPONSE_LIMIT_BYTES,
    ACCOUNT_UPSTREAM_RESPONSE_TIMEOUT_MS
  );
  if (bounded.error) {
    return bounded.error.status === 408 ? { timeout: true } : { error: true };
  }
  return { value: bounded.value };
}

function safeErrorCode(value: unknown): string | null {
  const payload = objectPayload(value);
  if (!payload) return null;
  const detail = objectPayload(payload.detail);
  const error = objectPayload(payload.error);
  const code = detail?.code ?? error?.code ?? payload.code;
  return typeof code === "string" && SAFE_ERROR_CODE.test(code) ? code : null;
}

function retryAfterHeaders(upstream: Response): HeadersInit | undefined {
  const value = upstream.headers.get("retry-after") ?? "";
  return /^[1-9][0-9]{0,5}$/.test(value) ? { "retry-after": value } : undefined;
}

function projectedError(upstream: Response, payload: unknown): Response {
  const code = safeErrorCode(payload);
  if (code === "gateway_upstream_timeout" && upstream.status === 504) {
    return accountError(504, code);
  }
  if (code === "gateway_client_closed" && upstream.status === 499) {
    return accountError(499, code);
  }
  if (code === "gateway_upstream_unavailable" && upstream.status === 502) {
    return accountError(502, code);
  }
  if (!SAFE_ERROR_STATUSES.has(upstream.status) || !code) {
    return accountError(502, "gateway_account_upstream_invalid");
  }
  const response = accountError(upstream.status, code);
  if (upstream.status !== 429 && upstream.status !== 503) return response;
  const headers = new Headers(response.headers);
  for (const [key, value] of new Headers(retryAfterHeaders(upstream))) headers.set(key, value);
  return new Response(response.body, { status: response.status, headers });
}

function enrollmentResponse(value: unknown): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload || !exactKeys(payload, [
    "schema_version", "enrollment_handle", "expires_at", "resend_available_at"
  ]) || payload.schema_version !== "walksafe.account-enrollment-email-otp-response.v1" ||
    !validOpaqueHandle(payload.enrollment_handle) || !validTimestamp(payload.expires_at) ||
    !validTimestamp(payload.resend_available_at)) return null;
  return {
    schema_version: payload.schema_version,
    enrollment_handle: payload.enrollment_handle,
    expires_at: payload.expires_at,
    resend_available_at: payload.resend_available_at
  };
}

function accountResponse(value: unknown): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload || !exactKeys(payload, [
    "schema_version", "actor_id", "account_generation", "signup_receipt_sha256"
  ]) || payload.schema_version !== "walksafe.account.v1" ||
    typeof payload.actor_id !== "string" || !BACKEND_ACTOR_ID.test(payload.actor_id) ||
    !positiveSafeInteger(payload.account_generation) ||
    typeof payload.signup_receipt_sha256 !== "string" ||
    !SHA256.test(payload.signup_receipt_sha256)) return null;
  return {
    schema_version: payload.schema_version,
    actor_id: payload.actor_id,
    account_generation: payload.account_generation,
    signup_receipt_sha256: payload.signup_receipt_sha256
  };
}

function authenticationResponse(value: unknown): BackendAccountAuthentication | null {
  const payload = objectPayload(value);
  if (!payload || !exactKeys(payload, [
    "schema_version", "actor_id", "account_generation", "auth_epoch"
  ]) || payload.schema_version !== "walksafe.account-authentication.v1" ||
    typeof payload.actor_id !== "string" || !BACKEND_ACTOR_ID.test(payload.actor_id) ||
    !positiveSafeInteger(payload.account_generation) ||
    !positiveSafeInteger(payload.auth_epoch)) return null;
  return {
    actorId: payload.actor_id,
    accountGeneration: payload.account_generation,
    authEpoch: payload.auth_epoch
  };
}

async function relayAccountRequest(
  request: Request,
  backendPath: string,
  body: Record<string, unknown>,
  successStatuses: ReadonlySet<number>,
  sanitizeSuccess: (value: unknown) => Record<string, unknown> | null,
  fetchImpl?: GatewayFetch,
  initialHeaders?: HeadersInit
): Promise<Response> {
  const headers = serviceHeaders(initialHeaders);
  if (headers instanceof Response) return headers;
  const init: RequestInit = {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    cache: "no-store"
  };
  const upstream = fetchImpl
    ? await fetchBackend(request, backendUrl(backendPath), init, 15_000, fetchImpl)
    : await fetchBackend(request, backendUrl(backendPath), init);
  const decoded = await boundedUpstreamJson(upstream);
  if (decoded.timeout) return accountError(504, "gateway_upstream_response_timeout");
  if (decoded.error) return accountError(502, "gateway_account_upstream_invalid");
  if (!successStatuses.has(upstream.status)) return projectedError(upstream, decoded.value);
  const sanitized = sanitizeSuccess(decoded.value);
  return sanitized
    ? Response.json(sanitized, {
        status: upstream.status,
        headers: { "cache-control": "no-store" }
      })
    : accountError(502, "gateway_account_upstream_invalid");
}

export async function relayAccountEnrollment(
  request: Request,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const bounded = await readBoundedJsonBody(request, ACCOUNT_ENROLLMENT_BODY_LIMIT_BYTES);
  if (bounded.error) return bounded.error;
  const payload = enrollmentRequest(bounded.value);
  if (!payload) return invalidRequest("account_enrollment_request_invalid");
  const clientIp = gatewayTrustedClientIp(request);
  if (!clientIp) return trustedClientIpRequired();
  return relayAccountRequest(
    request,
    "/account-enrollments/email-otp",
    payload,
    new Set([202]),
    enrollmentResponse,
    fetchImpl,
    { [BACKEND_ACCOUNT_CLIENT_IP_HEADER]: clientIp }
  );
}

export async function relayAccountCreation(
  request: Request,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const bounded = await readBoundedJsonBody(request, ACCOUNT_CREATE_BODY_LIMIT_BYTES);
  if (bounded.error) return bounded.error;
  const payload = accountCreateRequest(bounded.value);
  if (!payload) return invalidRequest("account_create_request_invalid");
  return relayAccountRequest(
    request,
    "/accounts",
    payload,
    new Set([201]),
    accountResponse,
    fetchImpl
  );
}

export async function authenticateBackendAccount(
  request: Request,
  grant: PasswordGrant,
  fetchImpl?: GatewayFetch
): Promise<AuthenticationResult> {
  if (grant.email === null) {
    return {
      error: invalidRequest("account_authentication_request_invalid")
    };
  }
  const headers = serviceHeaders();
  if (headers instanceof Response) return { error: headers };
  const init: RequestInit = {
    method: "POST",
    headers,
    body: JSON.stringify({
      schema_version: "walksafe.account-authenticate.v1",
      email: grant.email,
      password: grant.password
    }),
    cache: "no-store"
  };
  const upstream = fetchImpl
    ? await fetchBackend(request, backendUrl("/accounts/authenticate"), init, 15_000, fetchImpl)
    : await fetchBackend(request, backendUrl("/accounts/authenticate"), init);
  const decoded = await boundedUpstreamJson(upstream);
  if (decoded.timeout) {
    return { error: accountError(504, "gateway_upstream_response_timeout") };
  }
  if (decoded.error) {
    return { error: accountError(502, "gateway_account_upstream_invalid") };
  }
  if (upstream.status !== 200) {
    if ([400, 401, 409].includes(upstream.status)) {
      return { error: accountError(401, "invalid_account_credentials") };
    }
    return { error: projectedError(upstream, decoded.value) };
  }
  const value = authenticationResponse(decoded.value);
  return value
    ? { value }
    : { error: accountError(502, "gateway_account_upstream_invalid") };
}
