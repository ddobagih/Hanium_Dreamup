import {
  clearGatewaySession,
  establishBackendGatewaySession,
  establishGatewaySession,
  gatewayLoginBusyResponse,
  gatewayLoginRateLimitResponse,
  gatewayFieldLongSessionBinding,
  gatewayFieldLongSessionBindingMatches,
  gatewaySessionActor,
  gatewaySessionAccountGeneration,
  gatewaySessionScope,
  gatewaySessionStatus,
  gatewayUnauthorizedResponse,
  gatewayUnavailableResponse,
  isGatewayAccessConfigured,
  isGatewayActorConfigured,
  isGatewaySessionAuthorized,
  recordGatewayLoginAttempt,
  revokeFieldSessionsForSecurityEvent,
  verifyGatewayCredential,
  withGatewayLoginLock
} from "./auth.js";
import {
  authenticateBackendAccount,
  parsePasswordGrant,
  relayAccountCreation,
  relayAccountEnrollment
} from "./account-relay.js";
import {
  acquireImageUploadAdmission,
  authorizedProxyActor,
  authorizedProxyDeviceBinding,
  authorizeProxyRequest,
  backendUrl,
  fetchBackend,
  IMAGE_MULTIPART_LIMIT_BYTES,
  proxyRequestHeaders,
  readBoundedMultipartFormData,
  toBackendResponse,
  type GatewayFetch
} from "./backend.js";
import { readBoundedJsonBody, readBoundedTextBody } from "./request-body.js";
import { resolvePrivacyRightsRequestUrl } from "./config.js";
import {
  authorizeIntegratedConsentRequest,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  handleIntegratedConsentBootstrapRequest,
  handleIntegratedConsentRequest,
  INTEGRATED_CONSENT_BOOTSTRAP_CONTROL,
  INTEGRATED_CONSENT_CONTROL
} from "./integrated-consent.js";
import {
  clearCurrentFieldLongSession,
  clearFieldLongSessionWithRefresh,
  establishFieldLongSession,
  fieldLongSessionStatus,
  hasFieldLongSessionCookie,
  isFieldLongSessionEnabled,
  listFieldLongSessionDevices,
  refreshFieldLongSession,
  revokeFieldLongSessionDevice,
  type FieldRefreshPayload
} from "./field-long-session.js";
import {
  commandFieldWalk,
  getFieldWalk,
  parseFieldWalkCommand
} from "./field-walk-ledger.js";
import type { GatewayFieldLongSessionBinding } from "./auth.js";
import {
  ACCOUNT_DELETION_CONTROL,
  activateActorGeneration,
  bindBackendActorGeneration,
  abortPrivacyOperationsForAccountDeletion,
  beginPrivacyOperation,
  consentActorBindingId,
  currentActorGeneration,
  finishPrivacyOperation,
  PrivacyRightsLedgerError,
  revalidatePrivacyOperation,
  type PrivacyOperationLease
} from "./privacy-rights.js";
import {
  acceptOrReplayAccountDeletionV2,
  DELETION_ACCESS_SECRET_HEADER,
  forwardAccountDeletionRequestV2,
  forwardDeviceDeletionEvidenceV2,
  isAccountGenerationFencedV2,
  parseAccountDeletionRequestV2,
  parseDeviceDeletionEvidenceV2,
  PrivacyDeletionV2Error,
  queueDeviceDeletionEvidenceV2,
  refreshBackendAccountDeletionStatusV2,
  validDeletionAccessSecretV2,
  type AccountDeletionStatusV2
} from "./privacy-deletion-v2.js";
import {
  GATEWAY_ROUTE_TEMPLATES,
  gatewayCorrelationId,
  gatewayRouteTemplate,
  recordGatewayRequestCompleted,
  withGatewayRequestId,
  writeGatewayTelemetry,
  type GatewayTelemetrySink
} from "./telemetry.js";
import {
  currentServerCapacityLevelForTelemetry,
  mergeServerCapacityIntoFieldSessionResponse
} from "./server-capacity.js";
import { relaySpeechStt, relaySpeechTts } from "./speech-relay.js";
import { relayRawCollection } from "./raw-collection-relay.js";

const ALLOWED_METHODS = new Map<string, readonly string[]>([
  ["/api/account-enrollments/email-otp", ["POST"]],
  ["/api/accounts", ["POST"]],
  ["/api/field-session", ["GET", "POST", "DELETE"]],
  ["/api/field-walk", ["GET", "POST"]],
  ["/api/speech/stt", ["POST"]],
  ["/api/speech/tts", ["POST"]],
  ["/api/navigation/walking", ["POST"]],
  ["/api/navigation/destinations/search", ["GET"]],
  ["/api/reports/v2", ["POST"]]
]);

export const PUBLIC_GATEWAY_ROUTES = Object.freeze([
  ...GATEWAY_ROUTE_TEMPLATES
]);

export type GatewayDependencies = {
  fetchImpl?: GatewayFetch;
  privacyRightsRequestUrl?: string;
  telemetrySink?: GatewayTelemetrySink;
  fieldLongSessionBindingResolver?: (
    request: Request
  ) => GatewayFieldLongSessionBinding | null;
};

const PRIVACY_RIGHTS_PATH = "/privacy/rights";
const ACCOUNT_DELETION_PATH = "/privacy/account-deletions";
const ACCOUNT_DELETION_REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/;
export const REPORT_PURPOSE_HEADER = "x-walksafe-report-purpose";
export const REPORT_ID_HEADER = "x-walksafe-report-id";
export const REPORT_PAYLOAD_SHA256_HEADER = "x-walksafe-report-payload-sha256";
export const REPORT_PAYLOAD_BYTES_HEADER = "x-walksafe-report-payload-bytes";
const REPORT_TRANSPORT_STATUS_BODY_LIMIT_BYTES = 4096;
const CANONICAL_REPORT_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const LOWER_SHA256 = /^[0-9a-f]{64}$/;
const POSITIVE_DECIMAL = /^[1-9][0-9]{0,18}$/;
const MAX_POSTGRES_BIGINT = 9_223_372_036_854_775_807n;
const REPORT_TRANSPORT_STATUS_ROUTE =
  /^\/api\/reports\/v2\/(?<reportId>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/status$/;
const USER_REPORT_LIST_ROUTE = "/api/reports/mine";
const USER_REPORT_DETAIL_ROUTE =
  /^\/api\/reports\/mine\/(?<reportId>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;
const USER_REPORT_REQUEST_ROUTE =
  /^\/api\/reports\/mine\/(?<reportId>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/requests$/;
const USER_REPORT_CONTENT_ROUTE =
  /^\/api\/reports\/mine\/(?<reportId>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/content$/;
const USER_REPORT_CORRECTION_ROUTE =
  /^\/api\/reports\/mine\/(?<reportId>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/corrections$/;
const USER_REPORT_DELETION_ROUTE =
  /^\/api\/reports\/mine\/deletions\/(?<requestId>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;
const USER_REPORT_BODY_LIMIT_BYTES = 4 * 1024;
const USER_REPORT_RESPONSE_LIMIT_BYTES = 128 * 1024;
const USER_REPORT_CURSOR = /^[A-Za-z0-9_-]{1,1024}$/;
const USER_REPORT_STATUSES = new Set([
  "RECEIVED", "INSTITUTION_SUBMITTED", "REJECTED", "RESOLVED"
]);
const USER_REQUEST_STATUSES = new Set([
  "RECEIVED", "ACKNOWLEDGED", "RESOLVED", "REJECTED"
]);
const USER_REPORT_CONTENT_CATEGORIES = new Set([
  "SIDEWALK_OBSTRUCTION", "ROAD_DAMAGE", "ACCESSIBILITY_BARRIER", "OTHER"
]);
const USER_REPORT_DELETION_STATES = new Set([
  "PENDING", "LEGAL_HOLD", "REJECTED", "DELETED"
]);

type ReportTransportHeaders = Readonly<{
  reportId: string;
  payloadSha256: string;
  payloadBytes: string;
}>;

function noStore(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("cache-control", "no-store");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers
  });
}

function routeNotFound(): Response {
  return Response.json(
    { code: "gateway_route_not_found" },
    { status: 404, headers: { "cache-control": "no-store" } }
  );
}

function methodNotAllowed(methods: readonly string[]): Response {
  return Response.json(
    { code: "gateway_method_not_allowed" },
    {
      status: 405,
      headers: {
        allow: methods.join(", "),
        "cache-control": "no-store"
      }
    }
  );
}

function fieldSessionQueryInvalid(): Response {
  return Response.json(
    { code: "field_session_query_invalid" },
    { status: 400, headers: { "cache-control": "no-store" } }
  );
}

function fieldSessionRequestInvalid(): Response {
  return Response.json(
    { code: "field_session_request_invalid" },
    { status: 400, headers: { "cache-control": "no-store" } }
  );
}

function fieldWalkRequestInvalid(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "field_walk_request_invalid"
    },
    { status: 400, headers: { "cache-control": "no-store" } }
  );
}

function fieldWalkLedgerUnavailable(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "field_walk_ledger_unavailable"
    },
    { status: 503, headers: { "cache-control": "no-store" } }
  );
}

function fieldWalkUnauthorized(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "gateway_unauthorized"
    },
    { status: 401, headers: { "cache-control": "no-store" } }
  );
}

function privacyLedgerUnavailable(): Response {
  return Response.json(
    { code: "privacy_ledger_unavailable" },
    { status: 503, headers: { "cache-control": "no-store" } }
  );
}

function privacyOperationInactive(): Response {
  return Response.json(
    {
      detail: {
        code: "account_generation_inactive",
        message: "The account generation is no longer active."
      }
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

function startPrivacyOperation(
  actorId: string,
  expectedAccountGeneration: number | null = null
):
  | { lease: PrivacyOperationLease; error?: never }
  | { lease?: never; error: Response } {
  try {
    const lease = beginPrivacyOperation(actorId);
    if (!lease) return { error: privacyOperationInactive() };
    if (
      expectedAccountGeneration !== null &&
      lease.accountGeneration !== expectedAccountGeneration
    ) {
      finishPrivacyOperation(lease);
      return { error: privacyOperationInactive() };
    }
    if (isAccountGenerationFencedV2(actorId, lease.accountGeneration)) {
      finishPrivacyOperation(lease);
      return { error: privacyOperationInactive() };
    }
    return { lease };
  } catch {
    return { error: privacyLedgerUnavailable() };
  }
}

function privacyOperationIsCurrent(lease: PrivacyOperationLease): boolean {
  try {
    return revalidatePrivacyOperation(lease) &&
      !isAccountGenerationFencedV2(lease.actorId, lease.accountGeneration);
  } catch {
    return false;
  }
}

function cancelUpstream(response: Response): void {
  void response.body?.cancel().catch(() => undefined);
}

async function withFieldActorOperation(
  actorId: string,
  operation: () => Response | Promise<Response>,
  expectedAccountGeneration: number | null = null
): Promise<Response> {
  const privacy = startPrivacyOperation(actorId, expectedAccountGeneration);
  if (privacy.error) return privacy.error;
  try {
    if (!privacyOperationIsCurrent(privacy.lease)) return privacyOperationInactive();
    const response = await operation();
    if (!privacyOperationIsCurrent(privacy.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    return response;
  } finally {
    finishPrivacyOperation(privacy.lease);
  }
}

function objectPayload(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function reportTransportHeaders(request: Request):
  | { value: ReportTransportHeaders | null; error?: never }
  | { value?: never; error: Response } {
  const names = [
    REPORT_ID_HEADER,
    REPORT_PAYLOAD_SHA256_HEADER,
    REPORT_PAYLOAD_BYTES_HEADER
  ] as const;
  const present = names.filter(name => request.headers.has(name));
  if (present.length === 0) return { value: null };
  if (present.length !== names.length) {
    return {
      error: Response.json(
        { detail: { code: "report_transport_headers_incomplete" } },
        { status: 422, headers: { "cache-control": "no-store" } }
      )
    };
  }
  const reportId = request.headers.get(REPORT_ID_HEADER) ?? "";
  const payloadSha256 = request.headers.get(REPORT_PAYLOAD_SHA256_HEADER) ?? "";
  const payloadBytes = request.headers.get(REPORT_PAYLOAD_BYTES_HEADER) ?? "";
  let payloadByteCount = 0n;
  try {
    payloadByteCount = POSITIVE_DECIMAL.test(payloadBytes)
      ? BigInt(payloadBytes)
      : 0n;
  } catch {
    payloadByteCount = 0n;
  }
  if (
    !CANONICAL_REPORT_UUID.test(reportId) ||
    !LOWER_SHA256.test(payloadSha256) ||
    payloadByteCount < 1n ||
    payloadByteCount > MAX_POSTGRES_BIGINT
  ) {
    return {
      error: Response.json(
        { detail: { code: "report_transport_headers_invalid" } },
        { status: 422, headers: { "cache-control": "no-store" } }
      )
    };
  }
  return { value: { reportId, payloadSha256, payloadBytes } };
}

function attachReportTransportHeaders(
  headers: Headers,
  transport: ReportTransportHeaders | null
): void {
  if (!transport) return;
  headers.set(REPORT_ID_HEADER, transport.reportId);
  headers.set(REPORT_PAYLOAD_SHA256_HEADER, transport.payloadSha256);
  headers.set(REPORT_PAYLOAD_BYTES_HEADER, transport.payloadBytes);
}

function requestHasEntityBody(request: Request): boolean {
  if (request.body === null || request.headers.get("content-length") === "0") return false;
  return (
    request.headers.has("content-length") ||
    request.headers.has("transfer-encoding") ||
    request.headers.has("content-type")
  );
}

function refreshPayload(
  payload: Record<string, unknown>,
  allowedKeys: readonly string[]
): FieldRefreshPayload | null {
  if (
    Object.keys(payload).some((key) => !allowedKeys.includes(key)) ||
    ("grant_type" in payload && payload.grant_type !== "refresh_token") ||
    typeof payload.actor_id !== "string" ||
    typeof payload.device_id !== "string" ||
    typeof payload.family_id !== "string" ||
    typeof payload.rotation !== "number" ||
    typeof payload.refresh_token !== "string"
  ) {
    return null;
  }
  return {
    actorId: payload.actor_id,
    deviceId: payload.device_id,
    familyId: payload.family_id,
    rotation: payload.rotation,
    refreshToken: payload.refresh_token
  };
}

function escapedHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => {
    const replacements: Record<string, string> = {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;"
    };
    return replacements[character]!;
  });
}

function privacyRightsPage(requestUrl: string, headOnly: boolean): Response {
  const href = escapedHtml(resolvePrivacyRightsRequestUrl(requestUrl));
  const body = headOnly
    ? null
    : `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WalkSafe 개인정보 권리 요청</title>
  <style>body{max-width:44rem;margin:2rem auto;padding:0 1rem;font:1.05rem/1.6 system-ui,sans-serif}a{display:inline-block;padding:.8rem 1rem;background:#173b6c;color:#fff;border-radius:.4rem}</style>
</head>
<body>
  <main>
    <h1>WalkSafe 개인정보 권리 요청</h1>
    <p>앱 설치 여부와 로그인 상태에 관계없이 서버 자료 열람, 수집·이용 동의 철회, 서버 자료 삭제를 요청할 수 있습니다.</p>
    <p>요청 접수 뒤 본인 확인과 처리 범위·예외·예상 기한은 운영 담당자가 별도로 안내합니다.</p>
    <p><a href="${href}" rel="noreferrer noopener">권리 요청 시작</a></p>
  </main>
</body>
</html>`;
  return new Response(body, {
    status: 200,
    headers: {
      "cache-control": "no-store",
      "content-type": "text/html; charset=utf-8",
      "content-security-policy":
        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action https:",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff"
    }
  });
}

async function gatewayLoginAdmission(request: Request): Promise<Response | null> {
  return withGatewayLoginLock(
    async () => {
      const rateLimited = await gatewayLoginRateLimitResponse(request);
      if (rateLimited) return rateLimited;
      await recordGatewayLoginAttempt(request);
      return null;
    },
    gatewayLoginBusyResponse
  );
}

async function fieldSession(
  request: Request,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const url = new URL(request.url);
  const queryEntries = [...url.searchParams.entries()];
  const recoveryScoped = gatewaySessionScope(request) === "account_deletion_recovery";
  if (request.method === "GET") {
    if (queryEntries.length === 0) {
      if (!isGatewayAccessConfigured()) return gatewaySessionStatus(request);
      const status = (): Response => {
        const longStatus = fieldLongSessionStatus(request);
        if (longStatus && !isGatewaySessionAuthorized(request)) {
          return Response.json(
            { required: true, authenticated: false, actor_id: null, session_scope: null },
            { headers: { "cache-control": "no-store" } }
          );
        }
        return longStatus ?? gatewaySessionStatus(request);
      };
      const actorId = gatewaySessionActor(request);
      const response = actorId
        ? await withFieldActorOperation(
            actorId,
            status,
            gatewaySessionAccountGeneration(request)
          )
        : status();
      return mergeServerCapacityIntoFieldSessionResponse(
        request,
        response,
        fetchImpl
      );
    }
    if (
      queryEntries.length === 1 &&
      queryEntries[0]![0] === "devices" &&
      queryEntries[0]![1] === "true"
    ) {
      if (recoveryScoped) return gatewayUnauthorizedResponse(true);
      if (!isGatewayAccessConfigured()) return gatewayUnavailableResponse();
      if (!isGatewaySessionAuthorized(request)) return gatewayUnauthorizedResponse();
      const actorId = gatewaySessionActor(request);
      return actorId
        ? withFieldActorOperation(
            actorId,
            () => listFieldLongSessionDevices(request),
            gatewaySessionAccountGeneration(request)
          )
        : gatewayUnauthorizedResponse();
    }
    return fieldSessionQueryInvalid();
  }
  if (request.method === "DELETE") {
    if (recoveryScoped && (queryEntries.length > 0 || requestHasEntityBody(request))) {
      return gatewayUnauthorizedResponse(true);
    }
    if (queryEntries.length > 0) {
      if (
        queryEntries.length !== 1 ||
        queryEntries[0]![0] !== "device_id" ||
        queryEntries[0]![1] === "" ||
        requestHasEntityBody(request)
      ) {
        return fieldSessionQueryInvalid();
      }
      if (!isGatewayAccessConfigured()) return gatewayUnavailableResponse();
      if (!isGatewaySessionAuthorized(request)) return gatewayUnauthorizedResponse();
      const actorId = gatewaySessionActor(request);
      return actorId
        ? withFieldActorOperation(
            actorId,
            () => revokeFieldLongSessionDevice(request, queryEntries[0]![1]),
            gatewaySessionAccountGeneration(request)
          )
        : gatewayUnauthorizedResponse();
    }
    if (requestHasEntityBody(request)) {
      const bounded = await readBoundedJsonBody(request, 4 * 1024);
      if (bounded.error) return bounded.error;
      const payload = objectPayload(bounded.value);
      const parsed = payload
        ? refreshPayload(
            payload,
            ["grant_type", "actor_id", "device_id", "family_id", "rotation", "refresh_token"]
          )
        : null;
      return parsed
        ? withFieldActorOperation(
            parsed.actorId,
            () => clearFieldLongSessionWithRefresh(request, parsed)
          )
        : fieldSessionRequestInvalid();
    }
    const actorId = gatewaySessionActor(request);
    const clear = (): Response | Promise<Response> =>
      hasFieldLongSessionCookie(request)
        ? clearCurrentFieldLongSession(request)
        : clearGatewaySession(request);
    return actorId
      ? withFieldActorOperation(
          actorId,
          clear,
          gatewaySessionAccountGeneration(request)
        )
      : clear();
  }

  if (queryEntries.length > 0) return fieldSessionQueryInvalid();
  const bounded = await readBoundedJsonBody(request, 4 * 1024);
  if (bounded.error) return bounded.error;
  const payload = objectPayload(bounded.value);
  if (!payload) return fieldSessionRequestInvalid();

  if (!isGatewayAccessConfigured()) return gatewayUnavailableResponse();
  if (payload.grant_type === "password") {
    const grant = parsePasswordGrant(payload);
    if (!grant) return fieldSessionRequestInvalid();
    const admission = await gatewayLoginAdmission(request);
    if (admission) return admission;
    const authenticated = await authenticateBackendAccount(request, grant, fetchImpl);
    if (authenticated.error) return authenticated.error;
    try {
      const generation = bindBackendActorGeneration(
        authenticated.value.actorId,
        authenticated.value.accountGeneration
      );
      if (
        generation !== authenticated.value.accountGeneration ||
        isAccountGenerationFencedV2(authenticated.value.actorId, generation)
      ) return privacyOperationInactive();
    } catch (error) {
      if (error instanceof PrivacyRightsLedgerError &&
        (error.code === "inactive" || error.code === "conflict")) {
        return privacyOperationInactive();
      }
      return privacyLedgerUnavailable();
    }
    return withFieldActorOperation(
      authenticated.value.actorId,
      () => establishBackendGatewaySession(
        request,
        { ...authenticated.value, deviceId: grant.deviceId },
        grant.rememberMe
      ),
      authenticated.value.accountGeneration
    );
  }
  if (payload.grant_type === "refresh_token") {
    if (recoveryScoped) return gatewayUnauthorizedResponse(true);
    const parsed = refreshPayload(
      payload,
      ["grant_type", "actor_id", "device_id", "family_id", "rotation", "refresh_token"]
    );
    if (!parsed) return fieldSessionRequestInvalid();
    if (!isGatewayActorConfigured(parsed.actorId)) {
      return Response.json(
        { code: "invalid_refresh_token", reauthentication_required: true },
        { status: 401, headers: { "cache-control": "no-store" } }
      );
    }
    return withFieldActorOperation(
      parsed.actorId,
      () => refreshFieldLongSession(request, parsed)
    );
  }
  if ("grant_type" in payload) return fieldSessionRequestInvalid();
  if (Object.keys(payload).some(
    (key) => !["actor_id", "token", "device_id", "purpose"].includes(key)
  )) {
    return fieldSessionRequestInvalid();
  }
  const sessionScope = "purpose" in payload ? payload.purpose : "general";
  if (sessionScope !== "general" && sessionScope !== "account_deletion_recovery") {
    return fieldSessionRequestInvalid();
  }
  if (sessionScope === "account_deletion_recovery" && "device_id" in payload) {
    return fieldSessionRequestInvalid();
  }

  const admission = await gatewayLoginAdmission(request);
  if (admission) return admission;

  const token = "token" in payload
    ? String(payload.token)
    : "";
  const actorId = "actor_id" in payload
    ? String(payload.actor_id)
    : "";
  const verifiedActor = token.length <= 512
    ? verifyGatewayCredential(actorId, token)
    : null;
  if (!verifiedActor) {
    return Response.json(
      { code: "invalid_field_test_token" },
      { status: 401, headers: { "cache-control": "no-store" } }
    );
  }
  let activatedGeneration: number;
  try {
    activatedGeneration = activateActorGeneration(verifiedActor);
    if (isAccountGenerationFencedV2(verifiedActor, activatedGeneration)) {
      return privacyOperationInactive();
    }
  } catch (error) {
    if (error instanceof PrivacyRightsLedgerError && error.code === "inactive") {
      return privacyOperationInactive();
    }
    return privacyLedgerUnavailable();
  }
  const deviceId = typeof payload.device_id === "string" ? payload.device_id : null;
  if (isFieldLongSessionEnabled() && "device_id" in payload) {
    if (
      deviceId === null ||
      Object.keys(payload).some(
        (key) => !["actor_id", "token", "device_id", "purpose"].includes(key)
      )
    ) {
      return fieldSessionRequestInvalid();
    }
    return withFieldActorOperation(
      verifiedActor,
      () => establishFieldLongSession(request, verifiedActor, deviceId)
    );
  }
  return withFieldActorOperation(
    verifiedActor,
    () => establishGatewaySession(request, verifiedActor, sessionScope)
  );
}

async function fieldWalk(
  request: Request,
  resolveBinding: (
    request: Request
  ) => GatewayFieldLongSessionBinding | null = gatewayFieldLongSessionBinding
): Promise<Response> {
  const url = new URL(request.url);
  if ([...url.searchParams].length > 0) return fieldWalkRequestInvalid();
  const binding = resolveBinding(request);
  if (!binding) return fieldWalkUnauthorized();
  const execute = (): Response => {
    try {
      const result = getFieldWalk(binding);
      return Response.json(result.body, { status: result.status });
    } catch {
      return fieldWalkLedgerUnavailable();
    }
  };
  if (request.method === "GET") {
    return withFieldActorOperation(
      binding.actorId,
      execute,
      binding.accountGeneration ?? null
    );
  }
  const bounded = await readBoundedJsonBody(request, 4 * 1024);
  if (bounded.error) return bounded.error;
  const bindingIsCurrent = resolveBinding === gatewayFieldLongSessionBinding
    ? gatewayFieldLongSessionBindingMatches(request, binding)
    : (() => {
        const current = resolveBinding(request);
        return current !== null &&
          current.actorId === binding.actorId &&
          current.accountId === binding.accountId &&
          current.deviceId === binding.deviceId &&
          current.familyId === binding.familyId &&
          current.sessionRotation === binding.sessionRotation &&
          (current.accountGeneration ?? null) ===
            (binding.accountGeneration ?? null);
      })();
  if (!bindingIsCurrent) return fieldWalkUnauthorized();
  const command = parseFieldWalkCommand(bounded.value);
  if (!command) return fieldWalkRequestInvalid();
  return withFieldActorOperation(binding.actorId, () => {
    try {
      const result = commandFieldWalk(binding, command);
      return Response.json(result.body, { status: result.status });
    } catch {
      return fieldWalkLedgerUnavailable();
    }
  }, binding.accountGeneration ?? null);
}

async function walkingRoute(request: Request, fetchImpl?: GatewayFetch): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const actorId = authorizedProxyActor(request);
  if (!actorId) return gatewayUnauthorizedResponse();
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  try {
    const bounded = await readBoundedTextBody(request, 16 * 1024);
    if (bounded.error) return bounded.error;
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const init: RequestInit = {
      method: "POST",
      headers: proxyRequestHeaders(request, {
        "content-type": request.headers.get("content-type") ?? "application/json"
      }, operation.lease.accountGeneration),
      body: bounded.text,
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const response = fetchImpl
      ? await fetchBackend(request, backendUrl("/navigation/walking"), init, 15_000, fetchImpl)
      : await fetchBackend(request, backendUrl("/navigation/walking"), init);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    return toBackendResponse(response);
  } finally {
    finishPrivacyOperation(operation.lease);
  }
}

async function destinationSearch(request: Request, fetchImpl?: GatewayFetch): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const actorId = authorizedProxyActor(request);
  if (!actorId) return gatewayUnauthorizedResponse();
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  try {
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const init: RequestInit = {
      headers: proxyRequestHeaders(request, undefined, operation.lease.accountGeneration),
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const response = fetchImpl
      ? await fetchBackend(
          request,
          backendUrl("/navigation/destinations/search", request),
          init,
          15_000,
          fetchImpl
        )
      : await fetchBackend(request, backendUrl("/navigation/destinations/search", request), init);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    return toBackendResponse(response);
  } finally {
    finishPrivacyOperation(operation.lease);
  }
}

async function reportV2(request: Request, fetchImpl?: GatewayFetch): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const fieldActorId = authorizedProxyActor(request);
  if (!fieldActorId) return gatewayUnauthorizedResponse();
  const transport = reportTransportHeaders(request);
  if (transport.error) return transport.error;
  const operation = startPrivacyOperation(
    fieldActorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  const headers = proxyRequestHeaders(
    request,
    undefined,
    operation.lease.accountGeneration
  );
  attachReportTransportHeaders(headers, transport.value);
  const admission = acquireImageUploadAdmission(request, headers);
  if (admission.error) {
    finishPrivacyOperation(operation.lease);
    return admission.error;
  }
  try {
    const networkTransport =
      request.headers.get(CONSENT_NETWORK_TRANSPORT_HEADER)?.trim() ?? "";
    if (networkTransport !== "wifi" && networkTransport !== "cellular") {
      return Response.json(
        {
          detail: {
            code: "integrated_consent_network_transport_required",
            message: "A supported current network transport is required."
          }
        },
        { status: 428, headers: { "cache-control": "no-store" } }
      );
    }
    const reportPurpose = request.headers.get(REPORT_PURPOSE_HEADER)?.trim() ?? "";
    if (reportPurpose !== "explicit" && reportPurpose !== "automatic") {
      return Response.json(
        {
          detail: {
            code: "integrated_consent_report_purpose_invalid",
            message: "x-walksafe-report-purpose must be explicit or automatic."
          }
        },
        { status: 422, headers: { "cache-control": "no-store" } }
      );
    }
    const initialRequired: Array<
      "raw_source_collection" | "automatic_reporting" | "mobile_network_transfer"
    > = ["raw_source_collection"];
    if (reportPurpose === "automatic") initialRequired.push("automatic_reporting");
    if (networkTransport === "cellular") initialRequired.push("mobile_network_transfer");
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const consentBindingId = consentActorBindingId(operation.lease);
    const consent = await authorizeIntegratedConsentRequest(
      request,
      initialRequired,
      consentBindingId
    );
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (consent.error) return consent.error;
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const multipart = await readBoundedMultipartFormData(request, IMAGE_MULTIPART_LIMIT_BYTES);
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (multipart.error) return multipart.error;
    const metadataText = multipart.formData.get("metadata");
    try {
      const metadata = typeof metadataText === "string"
        ? objectPayload(JSON.parse(metadataText) as unknown)
        : null;
      if (
        !metadata ||
        typeof metadata.auto_reported !== "boolean" ||
        metadata.auto_reported !== (reportPurpose === "automatic")
      ) {
        throw new Error();
      }
    } catch {
      return Response.json(
        {
          detail: {
            code: "integrated_consent_report_purpose_invalid",
            message: "Multipart metadata must match x-walksafe-report-purpose."
          }
        },
        { status: 422, headers: { "cache-control": "no-store" } }
      );
    }
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const currentConsent = await authorizeIntegratedConsentRequest(
      request,
      initialRequired,
      consentBindingId
    );
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (currentConsent.error) return currentConsent.error;
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const init: RequestInit = {
      method: "POST",
      headers,
      body: multipart.formData,
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const response = fetchImpl
      ? await fetchBackend(request, backendUrl("/reports/v2"), init, 15_000, fetchImpl)
      : await fetchBackend(request, backendUrl("/reports/v2"), init);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    const finalConsent = await authorizeIntegratedConsentRequest(
      request,
      initialRequired,
      consentBindingId
    );
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    if (finalConsent.error) {
      cancelUpstream(response);
      return finalConsent.error;
    }
    return toBackendResponse(response);
  } finally {
    finishPrivacyOperation(operation.lease);
    admission.release();
  }
}

function reportTransportStatusNotFound(): Response {
  return Response.json(
    { detail: { code: "report_transport_status_not_found" } },
    { status: 404, headers: { "cache-control": "no-store" } }
  );
}

function reportTransportStatusUnavailable(): Response {
  return Response.json(
    { detail: { code: "gateway_report_transport_status_unavailable" } },
    { status: 502, headers: { "cache-control": "no-store" } }
  );
}

function exactObjectKeys(
  value: Record<string, unknown>,
  expected: readonly string[]
): boolean {
  const actual = Object.keys(value).sort();
  return actual.length === expected.length &&
    actual.every((key, index) => key === [...expected].sort()[index]);
}

function reportTransportStatusPayload(
  value: unknown,
  reportId: string
): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload || !exactObjectKeys(
    payload,
    ["persistence_state", "user_status", "transport_receipt"]
  )) return null;
  const receipt = objectPayload(payload.transport_receipt);
  if (!receipt || !exactObjectKeys(
    receipt,
    [
      "marker",
      "report_id",
      "persistence_marker",
      "payload_sha256",
      "payload_bytes"
    ]
  )) return null;
  if (
    payload.persistence_state !== "PERSISTED" ||
    typeof payload.user_status !== "string" ||
    !["RECEIVED", "IN_REVIEW", "COMPLETED"].includes(payload.user_status) ||
    receipt.marker !== "DATABASE_AND_ENCRYPTED_IMAGE_STORE" ||
    receipt.report_id !== reportId ||
    typeof receipt.persistence_marker !== "string" ||
    !CANONICAL_REPORT_UUID.test(receipt.persistence_marker) ||
    typeof receipt.payload_sha256 !== "string" ||
    !LOWER_SHA256.test(receipt.payload_sha256) ||
    typeof receipt.payload_bytes !== "number" ||
    !Number.isSafeInteger(receipt.payload_bytes) ||
    Number(receipt.payload_bytes) < 1
  ) return null;
  return {
    persistence_state: payload.persistence_state,
    user_status: payload.user_status,
    transport_receipt: {
      marker: receipt.marker,
      report_id: receipt.report_id,
      persistence_marker: receipt.persistence_marker,
      payload_sha256: receipt.payload_sha256,
      payload_bytes: receipt.payload_bytes
    }
  };
}

async function boundedReportTransportStatusPayload(
  response: Response,
  reportId: string
): Promise<Record<string, unknown> | null> {
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  const contentLength = response.headers.get("content-length");
  if (
    !contentType.startsWith("application/json") ||
    response.body === null ||
    (contentLength !== null && (
      !/^\d+$/.test(contentLength) ||
      Number(contentLength) > REPORT_TRANSPORT_STATUS_BODY_LIMIT_BYTES
    ))
  ) {
    cancelUpstream(response);
    return null;
  }
  const headers = new Headers({ "content-type": contentType });
  if (contentLength !== null) headers.set("content-length", contentLength);
  const boundedRequest = new Request("http://gateway.invalid/internal/report-status", {
    method: "POST",
    headers,
    body: response.body,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const bounded = await readBoundedJsonBody(
    boundedRequest,
    REPORT_TRANSPORT_STATUS_BODY_LIMIT_BYTES,
    5_000
  );
  if (bounded.error) {
    void boundedRequest.body?.cancel().catch(() => undefined);
    return null;
  }
  return reportTransportStatusPayload(bounded.value, reportId);
}

async function reportTransportStatus(
  request: Request,
  reportId: string,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) {
    return denied.status === 401 || denied.status === 403
      ? reportTransportStatusNotFound()
      : denied;
  }
  const actorId = authorizedProxyActor(request);
  if (!actorId) return reportTransportStatusNotFound();
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error.status === 409
    ? reportTransportStatusNotFound() : operation.error;
  try {
    if (!privacyOperationIsCurrent(operation.lease)) {
      return reportTransportStatusNotFound();
    }
    const init: RequestInit = {
      method: "GET",
      headers: proxyRequestHeaders(
        request,
        undefined,
        operation.lease.accountGeneration
      ),
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const path = `/reports/v2/${reportId}/status`;
    const response = fetchImpl
      ? await fetchBackend(request, backendUrl(path), init, 15_000, fetchImpl)
      : await fetchBackend(request, backendUrl(path), init);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return reportTransportStatusNotFound();
    }
    if ([401, 403, 404].includes(response.status)) {
      cancelUpstream(response);
      return reportTransportStatusNotFound();
    }
    if (response.status !== 200) {
      cancelUpstream(response);
      return reportTransportStatusUnavailable();
    }
    const payload = await boundedReportTransportStatusPayload(response, reportId);
    if (!privacyOperationIsCurrent(operation.lease)) {
      return reportTransportStatusNotFound();
    }
    if (!payload) return reportTransportStatusUnavailable();
    return Response.json(payload, {
      status: 200,
      headers: { "cache-control": "no-store" }
    });
  } finally {
    finishPrivacyOperation(operation.lease);
  }
}

type UserReportRoute =
  | Readonly<{ kind: "list" }>
  | Readonly<{ kind: "detail" | "request" | "content" | "correction"; reportId: string }>
  | Readonly<{ kind: "deletion"; requestId: string }>;

function userReportRoute(pathname: string): UserReportRoute | null {
  if (pathname === USER_REPORT_LIST_ROUTE) {
    return { kind: "list" };
  }
  const deletion = USER_REPORT_DELETION_ROUTE.exec(pathname);
  if (deletion?.groups?.requestId) {
    return { kind: "deletion", requestId: deletion.groups.requestId };
  }
  const request = USER_REPORT_REQUEST_ROUTE.exec(pathname);
  if (request?.groups?.reportId) {
    return { kind: "request", reportId: request.groups.reportId };
  }
  const correction = USER_REPORT_CORRECTION_ROUTE.exec(pathname);
  if (correction?.groups?.reportId) {
    return { kind: "correction", reportId: correction.groups.reportId };
  }
  const content = USER_REPORT_CONTENT_ROUTE.exec(pathname);
  if (content?.groups?.reportId) {
    return { kind: "content", reportId: content.groups.reportId };
  }
  const detail = USER_REPORT_DETAIL_ROUTE.exec(pathname);
  return detail?.groups?.reportId
    ? { kind: "detail", reportId: detail.groups.reportId }
    : null;
}

function userReportNotFound(): Response {
  return Response.json(
    { detail: { code: "report_not_found" } },
    { status: 404, headers: { "cache-control": "no-store" } }
  );
}

function userReportUnavailable(): Response {
  return Response.json(
    { detail: { code: "gateway_user_report_unavailable" } },
    { status: 502, headers: { "cache-control": "no-store" } }
  );
}

function validDateTime(value: unknown): value is string {
  return typeof value === "string" && value.length >= 20 && value.length <= 40 &&
    Number.isFinite(Date.parse(value));
}

const AWARE_DATE_TIME =
  /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$/;

function validAwareDateTime(value: unknown): value is string {
  return typeof value === "string" && value.length >= 20 && value.length <= 40 &&
    AWARE_DATE_TIME.test(value) && Number.isFinite(Date.parse(value));
}

function optionalBoundedText(value: unknown): value is string | null {
  return value === null || (
    typeof value === "string" && value.length >= 1 && value.length <= 500
  );
}

function sanitizeRequestSummary(value: unknown): Record<string, unknown> | null {
  const item = objectPayload(value);
  if (!item || !exactObjectKeys(item, [
    "request_id", "request_type", "status", "status_version",
    "public_response", "created_at", "updated_at"
  ])) return null;
  if (
    typeof item.request_id !== "string" || !CANONICAL_REPORT_UUID.test(item.request_id) ||
    (item.request_type !== "CORRECTION" && item.request_type !== "DELETE") ||
    typeof item.status !== "string" || !USER_REQUEST_STATUSES.has(item.status) ||
    typeof item.status_version !== "number" || !Number.isSafeInteger(item.status_version) ||
    item.status_version < 1 || !optionalBoundedText(item.public_response) ||
    !validDateTime(item.created_at) || !validDateTime(item.updated_at)
  ) return null;
  return {
    request_id: item.request_id,
    request_type: item.request_type,
    status: item.status,
    status_version: item.status_version,
    public_response: item.public_response,
    created_at: item.created_at,
    updated_at: item.updated_at
  };
}

function sanitizeUserReportItem(value: unknown): Record<string, unknown> | null {
  const item = objectPayload(value);
  if (!item || !exactObjectKeys(item, [
    "report_id", "created_at", "user_status",
    "public_rejection_reason", "latest_request"
  ])) return null;
  const latest = item.latest_request === null
    ? null : sanitizeRequestSummary(item.latest_request);
  if (
    typeof item.report_id !== "string" || !CANONICAL_REPORT_UUID.test(item.report_id) ||
    !validDateTime(item.created_at) || typeof item.user_status !== "string" ||
    !USER_REPORT_STATUSES.has(item.user_status) ||
    !optionalBoundedText(item.public_rejection_reason) ||
    (item.latest_request !== null && latest === null)
  ) return null;
  return {
    report_id: item.report_id,
    created_at: item.created_at,
    user_status: item.user_status,
    public_rejection_reason: item.public_rejection_reason,
    latest_request: latest
  };
}

type UserReportCorrectionRequest = Readonly<{
  expectedRevision: number;
  idempotencyKey: string;
  body: Record<string, unknown>;
}>;

function nullableContentCategory(value: unknown): boolean {
  return value === null || (
    typeof value === "string" && USER_REPORT_CONTENT_CATEGORIES.has(value)
  );
}

function canonicalCorrectionDescription(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const nfc = value.normalize("NFC");
  if (/\p{C}/u.test(nfc)) return null;
  const normalized = nfc
    .trim()
    .split(/\s+/u)
    .filter(Boolean)
    .join(" ");
  const length = Array.from(normalized).length;
  return length >= 1 && length <= 500
    ? normalized
    : null;
}

function nullableCanonicalCorrectionDescription(value: unknown): boolean {
  return value === null || canonicalCorrectionDescription(value) === value;
}

function sanitizeReportContentCurrent(
  value: Record<string, unknown>,
  reportId: string
): Record<string, unknown> | null {
  if (!exactObjectKeys(value, [
    "schema_version", "report_id", "revision", "content_sha256",
    "user_description", "category_hint", "corrected_at"
  ]) ||
    value.schema_version !== "walksafe.report-content-current.v1" ||
    value.report_id !== reportId ||
    typeof value.revision !== "number" || !Number.isSafeInteger(value.revision) ||
    value.revision < 0 || typeof value.content_sha256 !== "string" ||
    !LOWER_SHA256.test(value.content_sha256) ||
    !nullableCanonicalCorrectionDescription(value.user_description) ||
    !nullableContentCategory(value.category_hint) ||
    !(
      value.corrected_at === null || validAwareDateTime(value.corrected_at)
    ) ||
    (value.revision === 0 && (
      value.user_description !== null ||
      value.category_hint !== null ||
      value.corrected_at !== null
    )) ||
    (value.revision > 0 && value.corrected_at === null)
  ) return null;
  return {
    schema_version: value.schema_version,
    report_id: value.report_id,
    revision: value.revision,
    content_sha256: value.content_sha256,
    user_description: value.user_description,
    category_hint: value.category_hint,
    corrected_at: value.corrected_at
  };
}

function sanitizeReportContentRevision(
  value: Record<string, unknown>,
  reportId: string,
  expected: UserReportCorrectionRequest | null
): Record<string, unknown> | null {
  if (!expected || !exactObjectKeys(value, [
    "schema_version", "report_id", "revision", "expected_revision",
    "idempotency_key", "content_sha256", "user_description",
    "category_hint", "corrected_at"
  ]) ||
    value.schema_version !== "walksafe.report-content-revision.v1" ||
    value.report_id !== reportId ||
    typeof value.revision !== "number" || !Number.isSafeInteger(value.revision) ||
    value.revision < 1 || value.revision !== expected.expectedRevision + 1 ||
    value.expected_revision !== expected.expectedRevision ||
    value.idempotency_key !== expected.idempotencyKey ||
    typeof value.content_sha256 !== "string" || !LOWER_SHA256.test(value.content_sha256) ||
    !nullableCanonicalCorrectionDescription(value.user_description) ||
    !nullableContentCategory(value.category_hint) ||
    !validAwareDateTime(value.corrected_at)
  ) return null;
  return {
    schema_version: value.schema_version,
    report_id: value.report_id,
    revision: value.revision,
    expected_revision: value.expected_revision,
    idempotency_key: value.idempotency_key,
    content_sha256: value.content_sha256,
    user_description: value.user_description,
    category_hint: value.category_hint,
    corrected_at: value.corrected_at
  };
}

function sanitizeReportDeletionStatus(
  value: Record<string, unknown>,
  requestId: string
): Record<string, unknown> | null {
  if (!exactObjectKeys(value, [
    "schema_version", "request_id", "report_id", "state",
    "request_status_version", "external_copy_count", "updated_at"
  ]) ||
    value.schema_version !== "walksafe.report-deletion-status.v1" ||
    value.request_id !== requestId ||
    typeof value.report_id !== "string" || !CANONICAL_REPORT_UUID.test(value.report_id) ||
    typeof value.state !== "string" || !USER_REPORT_DELETION_STATES.has(value.state) ||
    typeof value.request_status_version !== "number" ||
    !Number.isSafeInteger(value.request_status_version) ||
    value.request_status_version < 1 ||
    typeof value.external_copy_count !== "number" ||
    !Number.isSafeInteger(value.external_copy_count) ||
    value.external_copy_count < 0 || !validAwareDateTime(value.updated_at)
  ) return null;
  return {
    schema_version: value.schema_version,
    request_id: value.request_id,
    report_id: value.report_id,
    state: value.state,
    request_status_version: value.request_status_version,
    external_copy_count: value.external_copy_count,
    updated_at: value.updated_at
  };
}

function sanitizeUserReportResponse(
  value: unknown,
  route: UserReportRoute,
  correctionRequest: UserReportCorrectionRequest | null
): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload) return null;
  if (route.kind === "content") {
    return sanitizeReportContentCurrent(payload, route.reportId);
  }
  if (route.kind === "correction") {
    return sanitizeReportContentRevision(payload, route.reportId, correctionRequest);
  }
  if (route.kind === "deletion") {
    return sanitizeReportDeletionStatus(payload, route.requestId);
  }
  if (route.kind === "request") return sanitizeRequestSummary(payload);
  if (route.kind === "detail") {
    if (!exactObjectKeys(payload, [
      "schema_version", "report_id", "created_at", "user_status",
      "public_rejection_reason", "latest_request"
    ]) || payload.schema_version !== "walksafe.user-report-detail.v1") return null;
    const item = { ...payload };
    delete item.schema_version;
    const sanitized = sanitizeUserReportItem(item);
    return sanitized ? {
      schema_version: "walksafe.user-report-detail.v1",
      ...sanitized
    } : null;
  }
  if (!exactObjectKeys(payload, ["schema_version", "items", "next_cursor"]) ||
    payload.schema_version !== "walksafe.user-report-list.v1" ||
    !Array.isArray(payload.items) || payload.items.length > 100 ||
    !(
      payload.next_cursor === null ||
      (typeof payload.next_cursor === "string" && USER_REPORT_CURSOR.test(payload.next_cursor))
    )) return null;
  const items = payload.items.map(sanitizeUserReportItem);
  if (items.some(item => item === null)) return null;
  return {
    schema_version: "walksafe.user-report-list.v1",
    items,
    next_cursor: payload.next_cursor
  };
}

async function boundedUserReportPayload(
  response: Response,
  route: UserReportRoute,
  correctionRequest: UserReportCorrectionRequest | null
): Promise<Record<string, unknown> | null> {
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  const contentLength = response.headers.get("content-length");
  if (
    !contentType.startsWith("application/json") || response.body === null ||
    (contentLength !== null && (
      !/^\d+$/.test(contentLength) || Number(contentLength) > USER_REPORT_RESPONSE_LIMIT_BYTES
    ))
  ) {
    cancelUpstream(response);
    return null;
  }
  const boundedRequest = new Request("http://gateway.invalid/internal/user-report", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: response.body,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const bounded = await readBoundedJsonBody(
    boundedRequest, USER_REPORT_RESPONSE_LIMIT_BYTES, 5_000
  );
  return bounded.error
    ? null
    : sanitizeUserReportResponse(bounded.value, route, correctionRequest);
}

function validUserReportQuery(url: URL): boolean {
  const seen = new Set<string>();
  for (const [key, value] of url.searchParams) {
    if (seen.has(key) || !["limit", "cursor", "user_status"].includes(key)) return false;
    seen.add(key);
    if (key === "limit" && (!/^[1-9][0-9]{0,2}$/.test(value) || Number(value) > 100)) return false;
    if (key === "cursor" && !USER_REPORT_CURSOR.test(value)) return false;
    if (key === "user_status" && !USER_REPORT_STATUSES.has(value)) return false;
  }
  return true;
}

function validUserRequestBody(value: unknown): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload || !exactObjectKeys(
    payload, ["client_request_id", "request_type", "request_text"]
  )) return null;
  if (
    typeof payload.client_request_id !== "string" ||
    !CANONICAL_REPORT_UUID.test(payload.client_request_id) ||
    (payload.request_type !== "CORRECTION" && payload.request_type !== "DELETE") ||
    typeof payload.request_text !== "string" ||
    payload.request_text !== payload.request_text.trim() ||
    payload.request_text.length < 1 || payload.request_text.length > 500
  ) return null;
  return payload;
}

function validUserCorrectionBody(value: unknown): UserReportCorrectionRequest | null {
  const payload = objectPayload(value);
  if (!payload) return null;
  const keys = Object.keys(payload);
  const allowed = new Set([
    "expected_revision", "idempotency_key", "user_description", "category_hint"
  ]);
  if (
    keys.some(key => !allowed.has(key)) ||
    !Object.hasOwn(payload, "expected_revision") ||
    !Object.hasOwn(payload, "idempotency_key") ||
    (!Object.hasOwn(payload, "user_description") && !Object.hasOwn(payload, "category_hint")) ||
    typeof payload.expected_revision !== "number" ||
    !Number.isSafeInteger(payload.expected_revision) || payload.expected_revision < 0 ||
    typeof payload.idempotency_key !== "string" ||
    !CANONICAL_REPORT_UUID.test(payload.idempotency_key)
  ) return null;

  const body: Record<string, unknown> = {
    expected_revision: payload.expected_revision,
    idempotency_key: payload.idempotency_key
  };
  if (Object.hasOwn(payload, "user_description")) {
    if (payload.user_description === null) {
      body.user_description = null;
    } else if (typeof payload.user_description === "string") {
      const normalized = canonicalCorrectionDescription(payload.user_description);
      if (normalized === null) return null;
      body.user_description = normalized;
    } else {
      return null;
    }
  }
  if (Object.hasOwn(payload, "category_hint")) {
    if (!nullableContentCategory(payload.category_hint)) return null;
    body.category_hint = payload.category_hint;
  }
  return {
    expectedRevision: payload.expected_revision,
    idempotencyKey: payload.idempotency_key,
    body
  };
}

async function userReportProxy(
  request: Request,
  route: UserReportRoute,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied.status === 401 || denied.status === 403
    ? userReportNotFound() : denied;
  const binding = authorizedProxyDeviceBinding(request);
  if (!binding) return userReportNotFound();
  const operation = startPrivacyOperation(
    binding.actorId,
    binding.accountGeneration
  );
  if (operation.error) return operation.error.status === 409
    ? userReportNotFound() : operation.error;
  try {
    if (!privacyOperationIsCurrent(operation.lease)) return userReportNotFound();
    let body: string | undefined;
    let correctionRequest: UserReportCorrectionRequest | null = null;
    const headers = proxyRequestHeaders(
      request,
      route.kind === "request" || route.kind === "correction"
        ? { "content-type": "application/json" }
        : undefined,
      operation.lease.accountGeneration
    );
    if (route.kind === "request" || route.kind === "correction") {
      const bounded = await readBoundedJsonBody(request, USER_REPORT_BODY_LIMIT_BYTES);
      if (bounded.error) return bounded.error;
      const payload = route.kind === "request"
        ? validUserRequestBody(bounded.value)
        : null;
      correctionRequest = route.kind === "correction"
        ? validUserCorrectionBody(bounded.value)
        : null;
      if (!payload && !correctionRequest) {
        return Response.json(
          {
            detail: {
              code: route.kind === "correction"
                ? "report_content_correction_invalid"
                : "report_request_invalid"
            }
          },
          { status: 422, headers: { "cache-control": "no-store" } }
        );
      }
      body = JSON.stringify(payload ?? correctionRequest!.body);
    }
    let backendPath: string;
    switch (route.kind) {
      case "list":
        backendPath = "/reports/mine";
        break;
      case "detail":
        backendPath = `/reports/mine/${route.reportId}`;
        break;
      case "request":
        backendPath = `/reports/mine/${route.reportId}/requests`;
        break;
      case "content":
        backendPath = `/reports/mine/${route.reportId}/content`;
        break;
      case "correction":
        backendPath = `/reports/mine/${route.reportId}/corrections`;
        break;
      case "deletion":
        backendPath = `/reports/mine/deletions/${route.requestId}`;
        break;
    }
    const init: RequestInit = {
      method: route.kind === "request" || route.kind === "correction" ? "POST" : "GET",
      headers,
      ...(body === undefined ? {} : { body }),
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const upstreamUrl = backendUrl(
      backendPath,
      route.kind === "list" ? request : undefined
    );
    const upstream = fetchImpl
      ? await fetchBackend(request, upstreamUrl, init, 15_000, fetchImpl)
      : await fetchBackend(request, upstreamUrl, init);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(upstream);
      return userReportNotFound();
    }
    if ([401, 403, 404].includes(upstream.status)) {
      cancelUpstream(upstream);
      return userReportNotFound();
    }
    if ((route.kind === "request" || route.kind === "correction") && upstream.status === 409) {
      cancelUpstream(upstream);
      return Response.json(
        {
          detail: {
            code: route.kind === "correction"
              ? "report_content_conflict"
              : "report_request_intent_conflict"
          }
        },
        { status: 409, headers: { "cache-control": "no-store" } }
      );
    }
    if (
      (route.kind === "request" || route.kind === "correction") &&
      [413, 415, 422, 429].includes(upstream.status)
    ) {
      const headers: Record<string, string> = { "cache-control": "no-store" };
      const retryAfter = upstream.headers.get("retry-after");
      if (upstream.status === 429 && retryAfter && /^\d+$/.test(retryAfter)) {
        headers["retry-after"] = retryAfter;
      }
      cancelUpstream(upstream);
      return Response.json(
        {
          detail: {
            code: route.kind === "correction"
              ? "report_content_correction_invalid"
              : "report_request_invalid"
          }
        },
        { status: upstream.status, headers }
      );
    }
    const expected = route.kind === "request" || route.kind === "correction"
      ? [200, 201]
      : [200];
    if (!expected.includes(upstream.status)) {
      cancelUpstream(upstream);
      return userReportUnavailable();
    }
    const payload = await boundedUserReportPayload(upstream, route, correctionRequest);
    const stillCurrent = privacyOperationIsCurrent(operation.lease);
    if (!stillCurrent) return userReportNotFound();
    if (!payload) return userReportUnavailable();
    return Response.json(payload, {
      status: upstream.status,
      headers: { "cache-control": "no-store" }
    });
  } finally {
    finishPrivacyOperation(operation.lease);
  }
}

function deletionNotFound(): Response {
  return Response.json(
    { code: "account_deletion_request_not_found" },
    { status: 404, headers: { "cache-control": "no-store" } }
  );
}

function deletionConflict(code = "account_deletion_request_conflict"): Response {
  return Response.json(
    { code },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

function deletionPending(retryAfterSeconds: number): Response {
  return Response.json(
    { code: "account_deletion_backend_pending" },
    {
      status: 503,
      headers: {
        "cache-control": "no-store",
        "retry-after": String(Math.max(1, retryAfterSeconds))
      }
    }
  );
}

function deletionStatusResponse(status: AccountDeletionStatusV2, code: number): Response {
  return Response.json(status, {
    status: code,
    headers: { "cache-control": "no-store" }
  });
}

function deletionError(error: unknown): Response {
  if (!(error instanceof PrivacyDeletionV2Error)) return privacyLedgerUnavailable();
  if (error.code === "not_found") return deletionNotFound();
  if (error.code === "conflict" || error.code === "inactive") return deletionConflict();
  return privacyLedgerUnavailable();
}

type AccountDeletionRouteV2 =
  | { kind: "request" }
  | { kind: "status" | "evidence"; requestId: string };

function accountDeletionRouteV2(pathname: string): AccountDeletionRouteV2 | null {
  if (pathname === ACCOUNT_DELETION_PATH) return { kind: "request" };
  const suffix = pathname.startsWith(`${ACCOUNT_DELETION_PATH}/`)
    ? pathname.slice(ACCOUNT_DELETION_PATH.length + 1)
    : "";
  const parts = suffix.split("/");
  if (
    parts.length !== 2 ||
    !ACCOUNT_DELETION_REQUEST_ID.test(parts[0] ?? "")
  ) return null;
  if (parts[1] === "status") return { kind: "status", requestId: parts[0]! };
  if (parts[1] === "device-evidence") {
    return { kind: "evidence", requestId: parts[0]! };
  }
  return null;
}

async function accountDeletionRequestV2(
  request: Request,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const accessSecret = request.headers.get(DELETION_ACCESS_SECRET_HEADER)?.trim() ?? "";
  if (!validDeletionAccessSecretV2(accessSecret)) return deletionNotFound();
  const bounded = await readBoundedJsonBody(request, 4 * 1024);
  if (bounded.error) return bounded.error;
  const input = parseAccountDeletionRequestV2(bounded.value);
  if (!input) {
    return Response.json(
      { code: "account_deletion_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  try {
    const actorId = isGatewaySessionAuthorized(request)
      ? gatewaySessionActor(request)
      : null;
    const accountGeneration = actorId
      ? gatewaySessionAccountGeneration(request) ?? currentActorGeneration(actorId)
      : null;
    const accepted = acceptOrReplayAccountDeletionV2(
      actorId,
      accountGeneration,
      input,
      accessSecret
    );
    if (accepted.kind === "not_found") return deletionNotFound();
    if (accepted.kind === "conflict" || accepted.kind === "inactive") {
      return deletionConflict();
    }
    if (accepted.kind === "accepted") {
      abortPrivacyOperationsForAccountDeletion(
        accepted.actorId,
        accepted.accountGeneration
      );
      try {
        await revokeFieldSessionsForSecurityEvent(
          accepted.actorId,
          "security_incident"
        );
      } catch {
        return deletionPending(5);
      }
    }
    const forwarded = await forwardAccountDeletionRequestV2(
      accepted.requestId,
      request,
      fetchImpl
    );
    if (forwarded.kind === "not_found") return deletionNotFound();
    if (forwarded.kind === "pending") {
      return deletionPending(forwarded.retryAfterSeconds);
    }
    if (forwarded.kind === "terminal_conflict") {
      return deletionConflict(forwarded.code);
    }
    if (forwarded.kind === "conflict") {
      return forwarded.status
        ? deletionStatusResponse(forwarded.status, 409)
        : deletionConflict();
    }
    return deletionStatusResponse(
      forwarded.status,
      accepted.kind === "accepted" ? 202 : 200
    );
  } catch (error) {
    return deletionError(error);
  }
}

async function accountDeletionStatusV2(
  request: Request,
  requestId: string,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const accessSecret = request.headers.get(DELETION_ACCESS_SECRET_HEADER)?.trim() ?? "";
  if (!validDeletionAccessSecretV2(accessSecret)) return deletionNotFound();
  try {
    const result = await refreshBackendAccountDeletionStatusV2(
      requestId,
      accessSecret,
      request,
      fetchImpl
    );
    if (result.kind === "not_found") return deletionNotFound();
    if (result.kind === "pending") return deletionPending(result.retryAfterSeconds);
    if (result.kind === "terminal_conflict") {
      return deletionConflict(result.code);
    }
    if (result.kind === "conflict") {
      return result.status
        ? deletionStatusResponse(result.status, 409)
        : deletionConflict();
    }
    return deletionStatusResponse(result.status, 200);
  } catch (error) {
    return deletionError(error);
  }
}

async function accountDeletionEvidenceV2(
  request: Request,
  requestId: string,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const accessSecret = request.headers.get(DELETION_ACCESS_SECRET_HEADER)?.trim() ?? "";
  if (!validDeletionAccessSecretV2(accessSecret)) return deletionNotFound();
  const bounded = await readBoundedJsonBody(request, 16 * 1024);
  if (bounded.error) return bounded.error;
  const evidence = parseDeviceDeletionEvidenceV2(bounded.value);
  if (!evidence) {
    return Response.json(
      { code: "device_deletion_evidence_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  if (evidence.request_id !== requestId) return deletionConflict();
  try {
    const queued = queueDeviceDeletionEvidenceV2(evidence, accessSecret);
    if (queued.kind === "not_found") return deletionNotFound();
    if (queued.kind === "conflict") return deletionConflict();
    if (queued.kind === "superseded") {
      return deletionStatusResponse(queued.status, 409);
    }
    const result = await forwardDeviceDeletionEvidenceV2(
      requestId,
      queued.operationId,
      request,
      fetchImpl
    );
    if (result.kind === "not_found") return deletionNotFound();
    if (result.kind === "pending") return deletionPending(result.retryAfterSeconds);
    if (result.kind === "terminal_conflict") {
      return deletionConflict(result.code);
    }
    if (result.kind === "conflict") {
      return result.status
        ? deletionStatusResponse(result.status, 409)
        : deletionConflict();
    }
    return deletionStatusResponse(result.status, 200);
  } catch (error) {
    return deletionError(error);
  }
}

async function dispatchGatewayRequest(
  request: Request,
  correlationId: string,
  dependencies: GatewayDependencies = {}
): Promise<Response> {
  const requestUrl = new URL(request.url);
  const pathname = requestUrl.pathname;
  if (pathname === "/api/raw-collections" || pathname.startsWith("/api/raw-collections/")) {
    return noStore(await relayRawCollection(request, {
      ...(dependencies.fetchImpl ? { fetchImpl: dependencies.fetchImpl } : {}),
      bindingResolver: dependencies.fieldLongSessionBindingResolver
        ?? gatewayFieldLongSessionBinding
    }));
  }
  const userReport = userReportRoute(pathname);
  if (userReport) {
    const allowed = userReport.kind === "request" || userReport.kind === "correction"
      ? ["POST"] as const
      : ["GET"] as const;
    if (!allowed.includes(request.method as never)) return methodNotAllowed(allowed);
    if (gatewaySessionScope(request) === "account_deletion_recovery") {
      return userReportNotFound();
    }
    if (userReport.kind === "list") {
      if (!validUserReportQuery(requestUrl) || requestHasEntityBody(request)) {
        return Response.json(
          { detail: { code: "report_query_invalid" } },
          { status: 400, headers: { "cache-control": "no-store" } }
        );
      }
    } else if (
      [...requestUrl.searchParams].length > 0 ||
      (request.method === "GET" && requestHasEntityBody(request))
    ) {
      return userReportNotFound();
    }
    return noStore(await userReportProxy(request, userReport, dependencies.fetchImpl));
  }
  const reportStatusMatch = REPORT_TRANSPORT_STATUS_ROUTE.exec(pathname);
  if (reportStatusMatch?.groups?.reportId) {
    if ([...requestUrl.searchParams].length > 0) return reportTransportStatusNotFound();
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    if (requestHasEntityBody(request)) {
      return Response.json(
        { detail: { code: "report_transport_status_request_invalid" } },
        { status: 400, headers: { "cache-control": "no-store" } }
      );
    }
    if (gatewaySessionScope(request) === "account_deletion_recovery") {
      return reportTransportStatusNotFound();
    }
    return noStore(await reportTransportStatus(
      request,
      reportStatusMatch.groups.reportId,
      dependencies.fetchImpl
    ));
  }
  const deletionRoute = accountDeletionRouteV2(pathname);
  if (deletionRoute) {
    if ([...requestUrl.searchParams].length > 0) return deletionNotFound();
    const allowed = deletionRoute.kind === "status" ? ["GET"] as const : ["POST"] as const;
    if (!allowed.includes(request.method as never)) return methodNotAllowed(allowed);
    if (deletionRoute.kind === "status" && requestHasEntityBody(request)) {
      return Response.json(
        { code: "account_deletion_status_request_invalid" },
        { status: 400, headers: { "cache-control": "no-store" } }
      );
    }
    if (deletionRoute.kind === "request") {
      return noStore(await accountDeletionRequestV2(request, dependencies.fetchImpl));
    }
    if (deletionRoute.kind === "status") {
      return noStore(await accountDeletionStatusV2(
        request,
        deletionRoute.requestId,
        dependencies.fetchImpl
      ));
    }
    return noStore(await accountDeletionEvidenceV2(
      request,
      deletionRoute.requestId,
      dependencies.fetchImpl
    ));
  }
  if (pathname === PRIVACY_RIGHTS_PATH) {
    if (gatewaySessionScope(request) === "account_deletion_recovery") {
      return noStore(gatewayUnauthorizedResponse(true));
    }
    if (requestUrl.searchParams.get("control") === ACCOUNT_DELETION_CONTROL) {
      return Response.json(
        {
          code: "account_deletion_v1_retired",
          replacement: ACCOUNT_DELETION_PATH
        },
        { status: 410, headers: { "cache-control": "no-store" } }
      );
    }
    if (
      requestUrl.searchParams.get("control") ===
      INTEGRATED_CONSENT_BOOTSTRAP_CONTROL
    ) {
      const denied = authorizeProxyRequest(request);
      if (denied) return noStore(denied);
      const actorId = authorizedProxyActor(request);
      if (!actorId) return noStore(gatewayUnauthorizedResponse());
      const operation = startPrivacyOperation(
        actorId,
        gatewaySessionAccountGeneration(request)
      );
      if (operation.error) return noStore(operation.error);
      try {
        if (!privacyOperationIsCurrent(operation.lease)) {
          return noStore(privacyOperationInactive());
        }
        const response = await handleIntegratedConsentBootstrapRequest(request, {
          accountGeneration: operation.lease.accountGeneration,
          fieldActorBindingId: consentActorBindingId(operation.lease),
          signal: operation.lease.controller.signal,
          ...(dependencies.fetchImpl
            ? { fetchImpl: dependencies.fetchImpl }
            : {})
        });
        if (!privacyOperationIsCurrent(operation.lease)) {
          cancelUpstream(response);
          return noStore(privacyOperationInactive());
        }
        return noStore(response);
      } finally {
        finishPrivacyOperation(operation.lease);
      }
    }
    if (requestUrl.searchParams.get("control") === INTEGRATED_CONSENT_CONTROL) {
      const denied = authorizeProxyRequest(request);
      if (denied) return noStore(denied);
      const actorId = authorizedProxyActor(request);
      if (!actorId) return noStore(gatewayUnauthorizedResponse());
      const operation = startPrivacyOperation(
        actorId,
        gatewaySessionAccountGeneration(request)
      );
      if (operation.error) return noStore(operation.error);
      try {
        if (!privacyOperationIsCurrent(operation.lease)) {
          return noStore(privacyOperationInactive());
        }
        const response = await handleIntegratedConsentRequest(request, {
          accountGeneration: operation.lease.accountGeneration,
          fieldActorBindingId: consentActorBindingId(operation.lease),
          signal: operation.lease.controller.signal,
          ...(dependencies.fetchImpl
            ? { fetchImpl: dependencies.fetchImpl }
            : {})
        });
        if (!privacyOperationIsCurrent(operation.lease)) {
          cancelUpstream(response);
          return noStore(privacyOperationInactive());
        }
        return noStore(response);
      } finally {
        finishPrivacyOperation(operation.lease);
      }
    }
    const methods = ["GET", "HEAD"] as const;
    if (!methods.includes(request.method as "GET" | "HEAD")) {
      return methodNotAllowed(methods);
    }
    const privacyRightsRequestUrl = dependencies.privacyRightsRequestUrl
      ?? process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
    if (!privacyRightsRequestUrl) {
      return Response.json(
        { code: "privacy_rights_channel_unavailable" },
        { status: 503, headers: { "cache-control": "no-store" } }
      );
    }
    return privacyRightsPage(
      privacyRightsRequestUrl,
      request.method === "HEAD"
    );
  }
  const methods = ALLOWED_METHODS.get(pathname);
  if (!methods) return routeNotFound();
  if (!methods.includes(request.method)) return methodNotAllowed(methods);
  const accountRelayRoute = pathname === "/api/account-enrollments/email-otp" ||
    pathname === "/api/accounts";
  const exactNoQueryRoute = accountRelayRoute ||
    pathname === "/api/speech/stt" || pathname === "/api/speech/tts";
  if (exactNoQueryRoute && [...requestUrl.searchParams].length > 0) {
    return Response.json(
      { detail: { code: "account_request_query_invalid" } },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  if (
    pathname !== "/api/field-session" &&
    !accountRelayRoute &&
    gatewaySessionScope(request) === "account_deletion_recovery"
  ) {
    return noStore(gatewayUnauthorizedResponse(true));
  }

  let response: Response;
  if (pathname === "/api/account-enrollments/email-otp") {
    response = await relayAccountEnrollment(request, dependencies.fetchImpl);
  }
  else if (pathname === "/api/accounts") {
    response = await relayAccountCreation(request, dependencies.fetchImpl);
  }
  else if (pathname === "/api/field-session") {
    response = await fieldSession(request, dependencies.fetchImpl);
  }
  else if (pathname === "/api/field-walk") {
    response = await fieldWalk(
      request,
      dependencies.fieldLongSessionBindingResolver
        ?? gatewayFieldLongSessionBinding
    );
  }
  else if (pathname === "/api/speech/stt") {
    response = await relaySpeechStt(request, correlationId, dependencies.fetchImpl);
  }
  else if (pathname === "/api/speech/tts") {
    response = await relaySpeechTts(request, correlationId, dependencies.fetchImpl);
  }
  else if (pathname === "/api/navigation/walking") {
    response = await walkingRoute(request, dependencies.fetchImpl);
  } else if (pathname === "/api/navigation/destinations/search") {
    response = await destinationSearch(request, dependencies.fetchImpl);
  } else {
    response = await reportV2(request, dependencies.fetchImpl);
  }
  return noStore(response);
}

export async function handleGatewayRequest(
  request: Request,
  dependencies: GatewayDependencies = {}
): Promise<Response> {
  const startedAt = performance.now();
  const correlationId = gatewayCorrelationId(request);
  const routeTemplate = gatewayRouteTemplate(request);
  const response = await dispatchGatewayRequest(request, correlationId, dependencies);
  const responseWithRequestId = withGatewayRequestId(response, correlationId);
  const sink = dependencies.telemetrySink
    ?? (process.env.NODE_ENV === "test" ? undefined : writeGatewayTelemetry);
  if (routeTemplate) {
    recordGatewayRequestCompleted(
      correlationId,
      routeTemplate,
      responseWithRequestId,
      performance.now() - startedAt,
      currentServerCapacityLevelForTelemetry(),
      sink
    );
  }
  return responseWithRequestId;
}
