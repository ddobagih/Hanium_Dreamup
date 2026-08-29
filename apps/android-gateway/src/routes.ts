import {
  clearGatewaySession,
  establishGatewaySession,
  gatewayLoginBusyResponse,
  gatewayLoginRateLimitResponse,
  gatewayFieldLongSessionBinding,
  gatewayFieldLongSessionBindingMatches,
  gatewaySessionActor,
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
  acquireImageUploadAdmission,
  authorizedProxyActor,
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
  handleIntegratedConsentRequest,
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
import {
  DEV_FIRST_RUN_EVIDENCE_PATH,
  handleDevFirstRunEvidenceRequest
} from "./dev-first-run-evidence.js";

const ALLOWED_METHODS = new Map<string, readonly string[]>([
  ["/api/field-session", ["GET", "POST", "DELETE"]],
  ["/api/field-walk", ["GET", "POST"]],
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

function startPrivacyOperation(actorId: string):
  | { lease: PrivacyOperationLease; error?: never }
  | { lease?: never; error: Response } {
  try {
    const lease = beginPrivacyOperation(actorId);
    if (!lease) return { error: privacyOperationInactive() };
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
  operation: () => Response | Promise<Response>
): Promise<Response> {
  const privacy = startPrivacyOperation(actorId);
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
        ? await withFieldActorOperation(actorId, status)
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
        ? withFieldActorOperation(actorId, () => listFieldLongSessionDevices(request))
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
            () => revokeFieldLongSessionDevice(request, queryEntries[0]![1])
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
    return actorId ? withFieldActorOperation(actorId, clear) : clear();
  }

  if (queryEntries.length > 0) return fieldSessionQueryInvalid();
  const bounded = await readBoundedJsonBody(request, 4 * 1024);
  if (bounded.error) return bounded.error;
  const payload = objectPayload(bounded.value);
  if (!payload) return fieldSessionRequestInvalid();

  if (!isGatewayAccessConfigured()) return gatewayUnavailableResponse();
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

  const admission = await withGatewayLoginLock(
    async () => {
      const rateLimited = await gatewayLoginRateLimitResponse(request);
      if (rateLimited) return rateLimited;
      await recordGatewayLoginAttempt(request);
      return null;
    },
    gatewayLoginBusyResponse
  );
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
    return withFieldActorOperation(binding.actorId, execute);
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
          current.sessionRotation === binding.sessionRotation;
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
  });
}

async function walkingRoute(request: Request, fetchImpl?: GatewayFetch): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const actorId = authorizedProxyActor(request);
  if (!actorId) return gatewayUnauthorizedResponse();
  const operation = startPrivacyOperation(actorId);
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
  const operation = startPrivacyOperation(actorId);
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
  const operation = startPrivacyOperation(fieldActorId);
  if (operation.error) return operation.error;
  const headers = proxyRequestHeaders(
    request,
    undefined,
    operation.lease.accountGeneration
  );
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
    const accountGeneration = actorId ? currentActorGeneration(actorId) : null;
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
  dependencies: GatewayDependencies = {}
): Promise<Response> {
  const requestUrl = new URL(request.url);
  const pathname = requestUrl.pathname;
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
    if (requestUrl.searchParams.get("control") === INTEGRATED_CONSENT_CONTROL) {
      if (request.method !== "PUT") {
        return noStore(await handleIntegratedConsentRequest(request));
      }
      const denied = authorizeProxyRequest(request);
      if (denied) return noStore(denied);
      const actorId = authorizedProxyActor(request);
      if (!actorId) return noStore(gatewayUnauthorizedResponse());
      const operation = startPrivacyOperation(actorId);
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
  // 개발 전용. 운영에서는 스스로 404를 돌려주므로 세션 검사 앞에 두어도 경로가 열리지 않는다.
  // 4~8단계는 로그인 전이라 여기서 세션을 요구하면 목적을 잃는다.
  if (pathname === DEV_FIRST_RUN_EVIDENCE_PATH) {
    return noStore(await handleDevFirstRunEvidenceRequest(request));
  }
  const methods = ALLOWED_METHODS.get(pathname);
  if (!methods) return routeNotFound();
  if (!methods.includes(request.method)) return methodNotAllowed(methods);
  if (
    pathname !== "/api/field-session" &&
    gatewaySessionScope(request) === "account_deletion_recovery"
  ) {
    return noStore(gatewayUnauthorizedResponse(true));
  }

  let response: Response;
  if (pathname === "/api/field-session") {
    response = await fieldSession(request, dependencies.fetchImpl);
  }
  else if (pathname === "/api/field-walk") {
    response = await fieldWalk(
      request,
      dependencies.fieldLongSessionBindingResolver
        ?? gatewayFieldLongSessionBinding
    );
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
  const response = await dispatchGatewayRequest(request, dependencies);
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
