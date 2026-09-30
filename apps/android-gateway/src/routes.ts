import path from "node:path";
import { MIMEType } from "node:util";

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
  gatewaySessionAuthEpoch,
  gatewaySessionAuthenticatedAtEpochMs,
  gatewaySessionScope,
  gatewaySessionStatus,
  gatewayTrustedClientIp,
  gatewayUnauthorizedResponse,
  gatewayUnavailableResponse,
  isGatewayAccessConfigured,
  isGatewayActorConfigured,
  isGatewaySessionAuthorized,
  listBackendDeviceGatewaySessionDevices,
  recordGatewayLoginAttempt,
  recordGatewayLoginFailure,
  revokeBackendDeviceGatewaySession,
  revokeShortFieldSessionsForActor,
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
  imageUploadRateLimitedResponse,
  authorizedProxyActor,
  authorizedProxyDeviceBinding,
  authorizeProxyRequest,
  BACKEND_SESSION_GATE_TIMEOUT_MS,
  backendUrl,
  checkBackendAccountSessionCurrent,
  fetchBackend,
  guardWalkingBackendResponse,
  IMAGE_MULTIPART_LIMIT_BYTES,
  proxyRequestHeaders,
  readBoundedMultipartFormData,
  toBackendResponse,
  type GatewayFetch,
  type ImageUploadAdmission
} from "./backend.js";
import {
  duplicateJsonObjectKeysError,
  readBoundedJsonBody,
  readBoundedTextBody
} from "./request-body.js";
import {
  resolveFieldWalkV1CommandMode,
  resolveGatewayStateDirectory,
  resolvePrivacyRightsRequestUrl
} from "./config.js";
import { acquireSharedFileLock } from "./exclusive-file-lock.js";
import {
  authorizeIntegratedConsentRequest,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  handleIntegratedConsentBootstrapRequest,
  handleIntegratedConsentRequest,
  INTEGRATED_CONSENT_POLICY_VERSION
} from "./integrated-consent.js";
import {
  clearCurrentFieldLongSession,
  clearFieldLongSessionWithRefresh,
  establishBackendFieldLongSession,
  establishFieldLongSession,
  FieldLongSessionStorageUnavailableError,
  fieldLongLivedSessionsUnavailableResponse,
  fieldLongSessionStatus,
  fieldLongSessionStorageOutcomeUnknownResponse,
  fieldLongSessionStorageUnavailableResponse,
  hasFieldLongSessionCookie,
  isBackendFieldLongSessionCookie,
  isFieldLongSessionEnabled,
  listFieldLongSessionDevices,
  refreshPublicFieldLongSession,
  revokeFieldLongSessionDevice,
  type FieldRefreshPayload
} from "./field-long-session.js";
import {
  commandFieldWalk,
  endFieldWalkForRevokedSession,
  FieldWalkLedgerStorageOutcomeUnknownError,
  getFieldWalk,
  parseFieldWalkCommand
} from "./field-walk-ledger.js";
import type { GatewayFieldLongSessionBinding } from "./auth.js";
import { admitFieldSessionRefresh } from "./refresh-admission.js";
import { GatewayStorageOutcomeUnknownError } from "./encrypted-json-store.js";
import {
  activateActorGenerationAsync,
  bindBackendActorGenerationAsync,
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
  observeGatewayResponseDelivery,
  recordGatewayRequestFinished,
  recordGatewayRequestPrepared,
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
import { matchGatewayRoute, type GatewayRouteMatch } from "./route-registry.js";

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

const ACCOUNT_DELETION_PATH = "/privacy/account-deletions";
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
const USER_REPORT_BODY_LIMIT_BYTES = 4 * 1024;
const USER_REPORT_RESPONSE_LIMIT_BYTES = 128 * 1024;
const USER_REPORT_REQUEST_HISTORY_RESPONSE_LIMIT_BYTES = 48 * 1024;
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
const USER_REPORT_EXTERNAL_COPY_DELETION_STATES = new Set([
  "NOT_REQUESTED",
  "REQUEST_SENT",
  "REPLY_ACKNOWLEDGED",
  "REPLY_DELETION_CONFIRMED",
  "REPLY_DECLINED"
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

function clientClosedResponse(): Response {
  return Response.json(
    { detail: { code: "gateway_client_closed", message: "client request was cancelled" } },
    { status: 499, headers: { "cache-control": "no-store" } }
  );
}

async function backendAccountSessionGate(
  request: Request,
  fetchImpl?: GatewayFetch
): Promise<Response | null> {
  if (request.signal.aborted) return clientClosedResponse();
  if (!isGatewaySessionAuthorized(request)) return null;
  const actorId = gatewaySessionActor(request);
  const accountGeneration = gatewaySessionAccountGeneration(request);
  const authEpoch = gatewaySessionAuthEpoch(request);
  if (accountGeneration === null && authEpoch === null) return null;
  if (!actorId || accountGeneration === null || authEpoch === null) {
    return noStore(gatewayUnauthorizedResponse());
  }
  try {
    // gw-F6 (S19-59): its own short limit, counted in the adapter's request deadline.
    const current = await checkBackendAccountSessionCurrent(
      { actorId, accountGeneration, authEpoch },
      fetchImpl,
      BACKEND_SESSION_GATE_TIMEOUT_MS,
      request.signal
    );
    if (request.signal.aborted) return clientClosedResponse();
    if (current === "current") return null;
    return noStore(current === "denied"
      ? gatewayUnauthorizedResponse()
      : gatewayUnavailableResponse());
  } catch {
    return request.signal.aborted
      ? clientClosedResponse()
      : noStore(gatewayUnavailableResponse());
  }
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

function privacyUnsupportedMethod(match: GatewayRouteMatch, method: string): Response | null {
  if (match.methods.includes(method)) return null;
  if (match.unsupportedMethod === "bootstrap-validation") {
    return Response.json(
      { detail: {
        code: "integrated_consent_bootstrap_query_invalid",
        message: "Consent bootstrap query is invalid.",
        required_policy_version: INTEGRATED_CONSENT_POLICY_VERSION
      } },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  if (match.unsupportedMethod === "integrated-handler") {
    return methodNotAllowed(match.methods);
  }
  return null;
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

function navigationRequestInvalid(): Response {
  return Response.json(
    { code: "navigation_request_invalid" },
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

/**
 * S19-27: storage failed after the atomic rename, so the command may be committed.
 * Retrying the same request_id is safe (it replays the stored result); a new
 * request_id is not.
 */
function fieldWalkLedgerOutcomeUnknown(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "field_walk_ledger_outcome_unknown",
      retry: "same_request_id"
    },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

/** S19-17: v1 intake is an explicit opt-in; an unreadable setting fails closed. */
function fieldWalkV1Accepted(): boolean {
  try {
    return resolveFieldWalkV1CommandMode() === "accept";
  } catch {
    return false;
  }
}

/** S19-17: v1 commands (no issue time) are retired by default; nothing was executed. */
function fieldWalkCommandVersionRetired(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "walk_command_version_retired",
      supported_schema_versions: ["walksafe.field-walk-command.v2"]
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

function fieldWalkLedgerFailure(error: unknown): Response {
  return error instanceof FieldWalkLedgerStorageOutcomeUnknownError
    ? fieldWalkLedgerOutcomeUnknown()
    : fieldWalkLedgerUnavailable();
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

type GatewayPrivacyFaultPointForTests =
  | "after_lease"
  | "refresh_pre_operation"
  | "refresh_before_commit"
  | "refresh_post_operation";
let privacyFaultPointForTests: GatewayPrivacyFaultPointForTests | null = null;

export function setGatewayPrivacyFaultForTests(
  point: GatewayPrivacyFaultPointForTests | null
): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("gateway privacy fault injection is test-only");
  }
  privacyFaultPointForTests = point;
}

function injectGatewayPrivacyFaultForTests(point: GatewayPrivacyFaultPointForTests): void {
  if (process.env.NODE_ENV !== "test" || privacyFaultPointForTests !== point) return;
  privacyFaultPointForTests = null;
  throw new Error(`injected gateway privacy ledger fault: ${point}`);
}

function startPrivacyOperation(
  actorId: string,
  expectedAccountGeneration: number | null = null
):
  | { lease: PrivacyOperationLease; error?: never }
  | { lease?: never; error: Response } {
  let lease: PrivacyOperationLease | null = null;
  try {
    lease = beginPrivacyOperation(actorId);
    if (!lease) return { error: privacyOperationInactive() };
    injectGatewayPrivacyFaultForTests("after_lease");
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
    if (lease) finishPrivacyOperation(lease);
    return { error: privacyLedgerUnavailable() };
  }
}

function privacyOperationState(
  lease: PrivacyOperationLease,
  faultPoint?: GatewayPrivacyFaultPointForTests
): "current" | "inactive" | "unavailable" {
  try {
    if (faultPoint) injectGatewayPrivacyFaultForTests(faultPoint);
    return revalidatePrivacyOperation(lease) &&
      !isAccountGenerationFencedV2(lease.actorId, lease.accountGeneration)
      ? "current" : "inactive";
  } catch {
    return "unavailable";
  }
}

function privacyOperationIsCurrent(lease: PrivacyOperationLease): boolean {
  return privacyOperationState(lease) === "current";
}

function cancelUpstream(response: Response): void {
  void response.body?.cancel().catch(() => undefined);
}

function responseWithPrivacyOperation(
  response: Response,
  lease: PrivacyOperationLease,
  requestSignal: AbortSignal
): Response {
  if (!response.body) {
    finishPrivacyOperation(lease);
    return response;
  }

  const reader = response.body.getReader();
  const signal = AbortSignal.any([lease.controller.signal, requestSignal]);
  let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
  let terminal = false;
  let finished = false;
  let readPending = false;
  let readerReleased = false;
  const releaseReader = (): void => {
    if (readerReleased || readPending) return;
    try {
      reader.releaseLock();
      readerReleased = true;
    } catch { /* a pending cancellation can retain the lock */ }
  };
  const finish = (): void => {
    if (finished) return;
    finished = true;
    signal.removeEventListener("abort", abort);
    finishPrivacyOperation(lease);
  };
  const cancelReader = (reason: unknown): void => {
    void reader.cancel(reason).catch(() => undefined).finally(releaseReader);
  };
  const abort = (): void => {
    if (terminal) return;
    terminal = true;
    const reason = signal.reason ?? new Error("privacy operation was cancelled");
    try { controller?.error(reason); } catch { /* already terminal */ }
    finish();
    cancelReader(reason);
  };

  try {
    const guarded = new ReadableStream<Uint8Array>({
      start(streamController) {
        controller = streamController;
        signal.addEventListener("abort", abort, { once: true });
        if (signal.aborted) abort();
      },
      async pull(streamController) {
        if (terminal) return;
        readPending = true;
        try {
          const chunk = await reader.read();
          readPending = false;
          if (terminal) {
            releaseReader();
            return;
          }
          if (signal.aborted) {
            abort();
            releaseReader();
            return;
          }
          if (chunk.done) {
            terminal = true;
            streamController.close();
            releaseReader();
            finish();
            return;
          }
          streamController.enqueue(chunk.value);
        } catch (error) {
          readPending = false;
          if (!terminal) {
            terminal = true;
            try { streamController.error(error); } catch { /* already terminal */ }
            finish();
            cancelReader(error);
          }
          releaseReader();
        }
      },
      async cancel(reason) {
        if (terminal) return;
        terminal = true;
        finish();
        try {
          await reader.cancel(reason);
        } finally {
          releaseReader();
        }
      }
    }, { highWaterMark: 0 });
    return new Response(guarded, {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers
    });
  } catch (error) {
    terminal = true;
    finish();
    cancelReader(error);
    throw error;
  }
}

async function withFieldActorOperation(
  actorId: string,
  operation: (lease: PrivacyOperationLease) => Response | Promise<Response>,
  expectedAccountGeneration: number | null = null,
  refreshRotationCommitted?: () => boolean
): Promise<Response> {
  const privacy = startPrivacyOperation(actorId, expectedAccountGeneration);
  if (privacy.error) return privacy.error;
  try {
    const before = refreshRotationCommitted
      ? privacyOperationState(privacy.lease, "refresh_pre_operation")
      : privacyOperationIsCurrent(privacy.lease) ? "current" : "inactive";
    if (before !== "current") {
      return before === "unavailable"
        ? privacyLedgerUnavailable() : privacyOperationInactive();
    }
    const response = await operation(privacy.lease);
    const after = refreshRotationCommitted
      ? privacyOperationState(privacy.lease, "refresh_post_operation")
      : privacyOperationIsCurrent(privacy.lease) ? "current" : "inactive";
    if (after !== "current") {
      if (after === "unavailable" && response.status === 500) return response;
      cancelUpstream(response);
      if (after === "unavailable") {
        return refreshRotationCommitted?.()
          ? fieldLongSessionStorageOutcomeUnknownResponse()
          : privacyLedgerUnavailable();
      }
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
  | { value: ReportTransportHeaders; error?: never }
  | { value?: never; error: Response } {
  const names = [
    REPORT_ID_HEADER,
    REPORT_PAYLOAD_SHA256_HEADER,
    REPORT_PAYLOAD_BYTES_HEADER
  ] as const;
  const present = names.filter(name => request.headers.has(name));
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
  transport: ReportTransportHeaders
): void {
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

async function gatewayLoginFailure(request: Request): Promise<Response | null> {
  return withGatewayLoginLock(
    async () => {
      await recordGatewayLoginFailure(request);
      return null;
    },
    gatewayLoginBusyResponse
  );
}

function currentWalkHolderBinding(request: Request): GatewayFieldLongSessionBinding | null {
  try {
    return gatewayFieldLongSessionBinding(request);
  } catch {
    return null;
  }
}

/**
 * FP-011/FP-012: a logged-out or remotely revoked session loses its walk lease at once.
 * Best effort after the session revocation committed: a ledger failure cannot undo the
 * logout, and the orphaned lease still expires because the revoked session cannot renew.
 */
async function endWalkAfterSessionRevocation(
  response: Response | Promise<Response>,
  holder: Parameters<typeof endFieldWalkForRevokedSession>[0] | null
): Promise<Response> {
  const settled = await response;
  if (holder && settled.status === 204) {
    try {
      endFieldWalkForRevokedSession(holder);
    } catch {
      // Lease expiry remains the fallback; see above.
    }
  }
  return settled;
}

async function fieldSession(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  if (match.body !== "by-method" || match.query !== "session") {
    return gatewayUnavailableResponse();
  }
  const url = new URL(request.url);
  const queryEntries = [...url.searchParams.entries()];
  const recoveryScoped = gatewaySessionScope(request) === "account_deletion_recovery";
  if (request.method === "GET") {
    if (queryEntries.length === 0) {
      if (!isGatewayAccessConfigured()) return gatewaySessionStatus(request);
      const backendGate = await backendAccountSessionGate(request, fetchImpl);
      if (backendGate) return backendGate;
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
      const backendGate = await backendAccountSessionGate(request, fetchImpl);
      if (backendGate) return backendGate;
      const actorId = gatewaySessionActor(request);
      return actorId
        ? withFieldActorOperation(
            actorId,
            () => listBackendDeviceGatewaySessionDevices(request)
              ?? listFieldLongSessionDevices(request),
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
      const backendGate = await backendAccountSessionGate(request, fetchImpl);
      if (backendGate) return backendGate;
      const actorId = gatewaySessionActor(request);
      const requester = currentWalkHolderBinding(request);
      const revokedDeviceId = queryEntries[0]![1];
      // S19-4-F1 (FP-011): revoking another device's session is a sensitive account change,
      // so it needs the same recent credential login as a new account deletion. Revoking the
      // current device itself is a logout and stays ungated; sessions without a device
      // binding keep their existing auth-required answer from the revoke helpers.
      if (
        actorId &&
        requester !== null &&
        requester.deviceId !== revokedDeviceId &&
        !sessionRecentlyAuthenticated(request, Date.now(), "storage_error")
      ) {
        return fieldSessionReauthenticationRequired();
      }
      return actorId
        ? withFieldActorOperation(
            actorId,
            () => endWalkAfterSessionRevocation(
              revokeBackendDeviceGatewaySession(request, revokedDeviceId) ??
                revokeFieldLongSessionDevice(request, revokedDeviceId),
              requester
                ? {
                    accountId: requester.accountId,
                    actorId: requester.actorId,
                    deviceId: revokedDeviceId
                  }
                : null
            ),
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
            () => clearFieldLongSessionWithRefresh(request, parsed, Date.now(), () => {
              try {
                // Legacy long-session families use the actor id as walk account id.
                endFieldWalkForRevokedSession({
                  accountId: parsed.actorId,
                  actorId: parsed.actorId,
                  deviceId: parsed.deviceId,
                  familyId: parsed.familyId
                });
              } catch {
                // Lease expiry remains the fallback (see endWalkAfterSessionRevocation).
              }
            })
          )
        : fieldSessionRequestInvalid();
    }
    const actorId = gatewaySessionActor(request);
    const holder = currentWalkHolderBinding(request);
    const clear = (): Promise<Response> => endWalkAfterSessionRevocation(
      hasFieldLongSessionCookie(request) || isBackendFieldLongSessionCookie(request)
        ? clearCurrentFieldLongSession(request)
        : clearGatewaySession(request),
      holder
        ? {
            accountId: holder.accountId,
            actorId: holder.actorId,
            deviceId: holder.deviceId,
            familyId: holder.familyId
          }
        : null
    );
    return actorId
      ? withFieldActorOperation(
          actorId,
          clear,
          gatewaySessionAccountGeneration(request)
        )
      : clear();
  }

  if (queryEntries.length > 0) {
    const admission = await gatewayLoginAdmission(request);
    return admission ?? fieldSessionQueryInvalid();
  }
  const bounded = await readBoundedJsonBody(request, 4 * 1024);
  if (bounded.error) {
    const admission = await gatewayLoginAdmission(request);
    return admission ?? bounded.error;
  }
  const payload = objectPayload(bounded.value);
  if (!payload) {
    const admission = await gatewayLoginAdmission(request);
    return admission ?? fieldSessionRequestInvalid();
  }

  if (payload.grant_type === "refresh_token") {
    if (!isGatewayAccessConfigured()) return gatewayUnavailableResponse();
    if (recoveryScoped) return gatewayUnauthorizedResponse(true);
    const parsed = refreshPayload(
      payload,
      ["grant_type", "actor_id", "device_id", "family_id", "rotation", "refresh_token"]
    );
    if (!parsed) return fieldSessionRequestInvalid();
    // S19-10: bounded before any session state is read or locked; refusals are counted per
    // claimed (actor, family) (R12).
    const refreshAdmission = admitFieldSessionRefresh(
      gatewayTrustedClientIp(request),
      parsed.actorId,
      parsed.familyId
    );
    if (!refreshAdmission.admitted) return refreshAdmission.response;
    let refreshStatus = 500;
    try {
      const refreshed = await refreshPublicFieldLongSession(
        request,
        parsed,
        isGatewayActorConfigured(parsed.actorId),
        (binding) => checkBackendAccountSessionCurrent(binding, fetchImpl, undefined, request.signal),
        (operation) => {
          let rotationCommitted = false;
          return withFieldActorOperation(
            parsed.actorId,
            (lease) => operation((commit) => {
              // Deletion accepts under this ledger's exclusive lock, then releases it
              // before revoking actor sessions. Hold a shared lock through the write.
              let deletionLock;
              try {
                deletionLock = acquireSharedFileLock(path.join(
                  resolveGatewayStateDirectory(), "privacy-deletion-v2", "ledger.lock"
                ));
              } catch {
                return privacyLedgerUnavailable();
              }
              try {
                const state = privacyOperationState(lease, "refresh_before_commit");
                if (state === "unavailable") return privacyLedgerUnavailable();
                if (state === "inactive") return privacyOperationInactive();
                commit();
                rotationCommitted = true;
                return null;
              } finally {
                deletionLock.release();
              }
            }),
            null,
            () => rotationCommitted
          );
        },
        // Decision 1: a reuse that revoked every long-session family ends short sessions too.
        revokeShortFieldSessionsForActor
      );
      refreshStatus = refreshed.status;
      return refreshed;
    } finally {
      refreshAdmission.finish(refreshStatus);
    }
  }

  const admission = await gatewayLoginAdmission(request);
  if (admission) return admission;
  if (!isGatewayAccessConfigured()) return gatewayUnavailableResponse();
  if (payload.grant_type === "password") {
    // Owner decision 2 / S19-1: `session_mode: "long_lived"` opts a Backend email login into a
    // device-bound rotating refresh family; without it the v7 short session stays unchanged.
    const { session_mode: sessionMode, ...passwordPayload } = payload;
    if ("session_mode" in payload && sessionMode !== "long_lived") return fieldSessionRequestInvalid();
    const longLived = sessionMode === "long_lived";
    const grant = parsePasswordGrant(passwordPayload);
    if (!grant) return fieldSessionRequestInvalid();
    if (longLived && (grant.deviceId === null || !grant.rememberMe)) return fieldSessionRequestInvalid();
    // login-F11: decide the disabled long-lived mode before any Backend credential check.
    if (longLived && !isFieldLongSessionEnabled()) return fieldLongLivedSessionsUnavailableResponse();
    const authenticated = await authenticateBackendAccount(request, grant, fetchImpl);
    if (authenticated.error) {
      if (authenticated.error.status === 401) {
        const failurePayload = objectPayload(await authenticated.error.clone().json());
        const failureDetail = objectPayload(failurePayload?.detail);
        if (failureDetail?.code === "invalid_account_credentials") {
          const failure = await gatewayLoginFailure(request);
          if (failure) return failure;
        }
      }
      return authenticated.error;
    }
    try {
      // S19-25: contention on the privacy ledger lock must not stall the event loop.
      const generation = await bindBackendActorGenerationAsync(
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
      () => longLived && grant.deviceId !== null
        ? establishBackendFieldLongSession(request, { ...authenticated.value, deviceId: grant.deviceId })
        : establishBackendGatewaySession(
          request,
          { ...authenticated.value, deviceId: grant.deviceId },
          grant.rememberMe
        ),
      authenticated.value.accountGeneration
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
    const failure = await gatewayLoginFailure(request);
    if (failure) return failure;
    return Response.json(
      { code: "invalid_field_test_token" },
      { status: 401, headers: { "cache-control": "no-store" } }
    );
  }
  let activatedGeneration: number;
  try {
    activatedGeneration = await activateActorGenerationAsync(verifiedActor);
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
  match: GatewayRouteMatch,
  resolveBinding: (
    request: Request
  ) => GatewayFieldLongSessionBinding | null = gatewayFieldLongSessionBinding,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  if (match.body !== "by-method") return gatewayUnavailableResponse();
  const url = new URL(request.url);
  if (match.query === "none" && [...url.searchParams].length > 0) {
    return fieldWalkRequestInvalid();
  }
  const binding = resolveBinding(request);
  if (!binding) return fieldWalkUnauthorized();
  const backendGate = await backendAccountSessionGate(request, fetchImpl);
  if (backendGate) return backendGate;
  const execute = (): Response => {
    try {
      const result = getFieldWalk(binding);
      return Response.json(result.body, { status: result.status });
    } catch (error) {
      return fieldWalkLedgerFailure(error);
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
  if (command.schema_version === "walksafe.field-walk-command.v1" && !fieldWalkV1Accepted()) {
    return fieldWalkCommandVersionRetired();
  }
  return withFieldActorOperation(binding.actorId, () => {
    try {
      const result = commandFieldWalk(binding, command);
      return Response.json(result.body, { status: result.status });
    } catch (error) {
      return fieldWalkLedgerFailure(error);
    }
  }, binding.accountGeneration ?? null);
}

const NAVIGATION_PRIORITIES = new Set([
  "RECOMMEND", "MAIN_STREET", "DISTANCE", "STAIR_AVOID"
]);
const DESTINATION_SEARCH_KEYS = new Set([
  "query", "limit", "origin_lat", "origin_lng"
]);

function finiteNavigationNumber(value: unknown): number | null {
  if (typeof value !== "number") {
    if (typeof value !== "string" ||
        !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim())) {
      return null;
    }
  }
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function navigationPointValid(value: unknown): boolean {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const point = value as Record<string, unknown>;
  const latitude = finiteNavigationNumber(point.latitude);
  const longitude = finiteNavigationNumber(point.longitude);
  return latitude !== null && latitude >= -90 && latitude <= 90 &&
    longitude !== null && longitude >= -180 && longitude <= 180;
}

function walkingRoutePayloadValid(value: unknown): boolean {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const route = value as Record<string, unknown>;
  if (!navigationPointValid(route.origin) || !navigationPointValid(route.destination)) return false;
  if (route.waypoints !== undefined &&
      (!Array.isArray(route.waypoints) || route.waypoints.length > 5 ||
       !route.waypoints.every(navigationPointValid))) return false;
  if (route.priority !== undefined &&
      (typeof route.priority !== "string" || !NAVIGATION_PRIORITIES.has(route.priority))) {
    return false;
  }
  if (route.radius_m !== undefined) {
    const radius = finiteNavigationNumber(route.radius_m);
    if (radius === null || !Number.isInteger(radius) || radius < 1 || radius > 12000) return false;
  }
  if (route.default_speed !== undefined && route.default_speed !== null) {
    const speed = finiteNavigationNumber(route.default_speed);
    if (speed === null || speed <= 0 || speed > 20) return false;
  }
  return true;
}

function navigationJsonContentType(request: Request): boolean {
  const contentType = request.headers.get("content-type");
  if (!contentType) return false;
  try {
    return new MIMEType(contentType).essence === "application/json";
  } catch {
    return false;
  }
}

function destinationSearchQueryValid(request: Request): boolean {
  const params = new URL(request.url).searchParams;
  const seen = new Set<string>();
  for (const [key] of params) {
    if (!DESTINATION_SEARCH_KEYS.has(key) || seen.has(key)) return false;
    seen.add(key);
  }
  const query = params.get("query");
  if (query === null || [...query].length < 1 || [...query].length > 80) return false;
  const limitValue = params.get("limit");
  if (limitValue !== null) {
    const limit = finiteNavigationNumber(limitValue);
    if (limit === null || !Number.isInteger(limit) || limit < 1 || limit > 10) return false;
  }
  const hasLatitude = params.has("origin_lat");
  const hasLongitude = params.has("origin_lng");
  if (hasLatitude !== hasLongitude) return false;
  if (hasLatitude) {
    const latitude = finiteNavigationNumber(params.get("origin_lat"));
    const longitude = finiteNavigationNumber(params.get("origin_lng"));
    if (latitude === null || latitude < -90 || latitude > 90 ||
        longitude === null || longitude < -180 || longitude > 180) return false;
  }
  return true;
}

/**
 * FP-012-DETAIL-02 interim: while one device holds the account's walk lease, route and
 * report requests from any other session are refused; with no active walk nothing
 * changes. Unreadable walk state fails closed (FP-012/FP-047: never skip the check).
 * Owner decision 3(b) (2026-09-29): reports are sent only after the walk has ended, so
 * `holderMayProceed: false` also refuses the holder's own session during its walk.
 */
function walkOwnershipGate(
  request: Request,
  { holderMayProceed }: { holderMayProceed: boolean } = { holderMayProceed: true }
): Response | null {
  const actorId = gatewaySessionActor(request);
  if (!actorId) return null;
  let binding: GatewayFieldLongSessionBinding | null;
  try {
    binding = gatewayFieldLongSessionBinding(request);
  } catch {
    return fieldWalkLedgerUnavailable();
  }
  const accountGeneration = gatewaySessionAccountGeneration(request);
  // Sessions without a device binding (v5/v6) can never be the walk holder.
  const probe: GatewayFieldLongSessionBinding = binding ?? {
    actorId,
    accountId: accountGeneration === null ? actorId : `${actorId}:generation:${accountGeneration}`,
    deviceId: "",
    familyId: "",
    sessionRotation: 0,
    accountGeneration
  };
  let result: ReturnType<typeof getFieldWalk>;
  try {
    result = getFieldWalk(probe);
  } catch {
    return fieldWalkLedgerUnavailable();
  }
  if (result.status !== 200) return fieldWalkLedgerUnavailable();
  if (result.body.result !== "ACTIVE") return null;
  if (result.body.held_by_current_device === true) {
    if (holderMayProceed) return null;
    return Response.json(
      {
        detail: {
          code: "report_walk_in_progress",
          message: "Reports are sent after this device's walk has ended."
        }
      },
      { status: 409, headers: { "cache-control": "no-store" } }
    );
  }
  return Response.json(
    {
      detail: {
        code: "walk_owned_by_other_device",
        message: "Another device holds this account's active walk."
      }
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

// The Backend provider ends first (4 s); the complete upstream response has 5 s.
// The navigation handler includes the account gate, body read, and upstream work.
const NAVIGATION_PROXY_TIMEOUT_MS = 5_000;
const NAVIGATION_WALKING_DEADLINE_MS = 7_500;
const NAVIGATION_SEARCH_DEADLINE_MS = 6_500;
const NAVIGATION_PROXY_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

function navigationDeadlineResponse(): Response {
  return Response.json(
    { detail: { code: "gateway_navigation_timeout", message: "navigation request exceeded its deadline" } },
    { status: 504, headers: { "cache-control": "no-store" } }
  );
}

async function walkingRoute(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const actorId = authorizedProxyActor(request);
  if (!actorId) return gatewayUnauthorizedResponse();
  const walkOwned = walkOwnershipGate(request);
  if (walkOwned) return walkOwned;
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  let responseOwnsOperation = false;
  try {
    if (match.body !== "json") {
      return gatewayUnavailableResponse();
    }
    if (!navigationJsonContentType(request)) return navigationRequestInvalid();
    const bounded = await readBoundedTextBody(request, 16 * 1024);
    if (bounded.error) return bounded.error;
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    let payload: unknown;
    try {
      payload = JSON.parse(bounded.text);
    } catch {
      return navigationRequestInvalid();
    }
    const duplicateError = duplicateJsonObjectKeysError(bounded.text);
    if (duplicateError) return duplicateError;
    if (!walkingRoutePayloadValid(payload)) return navigationRequestInvalid();
    const init: RequestInit = {
      method: "POST",
      headers: proxyRequestHeaders(request, {
        "content-type": request.headers.get("content-type") ?? "application/json"
      }, operation.lease.accountGeneration),
      body: bounded.text,
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const fetched = await fetchBackend(
      request, backendUrl("/navigation/walking"), init,
      NAVIGATION_PROXY_TIMEOUT_MS, fetchImpl ?? globalThis.fetch,
      NAVIGATION_PROXY_MAX_RESPONSE_BYTES
    );
    const response = await guardWalkingBackendResponse(fetched);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    const protectedResponse = responseWithPrivacyOperation(
      await toBackendResponse(response),
      operation.lease,
      request.signal
    );
    responseOwnsOperation = true;
    return protectedResponse;
  } finally {
    if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
  }
}

async function destinationSearch(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const actorId = authorizedProxyActor(request);
  if (!actorId) return gatewayUnauthorizedResponse();
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  let responseOwnsOperation = false;
  try {
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (match.query === "validated" &&
        !destinationSearchQueryValid(request)) return navigationRequestInvalid();
    const init: RequestInit = {
      headers: proxyRequestHeaders(request, undefined, operation.lease.accountGeneration),
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const response = await fetchBackend(
      request, backendUrl("/navigation/destinations/search", request), init,
      NAVIGATION_PROXY_TIMEOUT_MS, fetchImpl ?? globalThis.fetch,
      NAVIGATION_PROXY_MAX_RESPONSE_BYTES
    );
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    const protectedResponse = responseWithPrivacyOperation(
      await toBackendResponse(response),
      operation.lease,
      request.signal
    );
    responseOwnsOperation = true;
    return protectedResponse;
  } finally {
    if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
  }
}

function reportTransportOutcomeUnknown(): Response {
  return Response.json(
    {
      detail: {
        code: "report_transport_outcome_unknown",
        message: "The report result is unknown; verify its status before retrying.",
        retry: "status_query_before_retry"
      }
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

async function reportV2(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const fieldActorId = authorizedProxyActor(request);
  if (!fieldActorId) return gatewayUnauthorizedResponse();
  const walkOwned = walkOwnershipGate(request, { holderMayProceed: false });
  if (walkOwned) return walkOwned;
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
  // S19-32: rate limits first (no slot); the single global upload slot is taken only after
  // the first consent check, and still before the body is opened.
  const rateLimited = imageUploadRateLimitedResponse(request, headers);
  if (rateLimited) {
    finishPrivacyOperation(operation.lease);
    return rateLimited;
  }
  let admission: ImageUploadAdmission | null = null;
  let responseOwnsOperation = false;
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
    > = [];
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
    const acquired = acquireImageUploadAdmission(request, headers);
    if (acquired.error) return acquired.error;
    admission = acquired;
    if (match.body !== "multipart") {
      return gatewayUnavailableResponse();
    }
    const multipart = await readBoundedMultipartFormData(request, IMAGE_MULTIPART_LIMIT_BYTES);
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (multipart.error) return multipart.error;
    const metadataText = multipart.formData.get("metadata");
    try {
      const metadata = typeof metadataText === "string"
        ? objectPayload(JSON.parse(metadataText) as unknown)
        : null;
      if (typeof metadataText === "string") {
        const duplicateError = duplicateJsonObjectKeysError(metadataText);
        if (duplicateError) return duplicateError;
      }
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
    // A 2xx report or a server-side failure can follow a durable commit. A later
    // local consent check cannot roll that commit back; the queued report must use
    // its stable ID and the existing status route before any retry.
    const backendOutcomeUncertain = response.ok || response.status >= 500;
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return backendOutcomeUncertain
        ? reportTransportOutcomeUnknown() : privacyOperationInactive();
    }
    const finalConsent = await authorizeIntegratedConsentRequest(
      request,
      initialRequired,
      consentBindingId
    );
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return backendOutcomeUncertain
        ? reportTransportOutcomeUnknown() : privacyOperationInactive();
    }
    if (finalConsent.error) {
      cancelUpstream(response);
      return backendOutcomeUncertain
        ? reportTransportOutcomeUnknown() : finalConsent.error;
    }
    const protectedResponse = responseWithPrivacyOperation(
      await toBackendResponse(response),
      operation.lease,
      request.signal
    );
    responseOwnsOperation = true;
    return protectedResponse;
  } finally {
    if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
    admission?.release?.();
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
  const backendGate = await backendAccountSessionGate(request, fetchImpl);
  if (backendGate) return backendGate.status === 401
    ? reportTransportStatusNotFound() : backendGate;
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
  | Readonly<{ kind: "request-history" }>
  | Readonly<{ kind: "detail" | "request" | "content" | "correction"; reportId: string }>
  | Readonly<{ kind: "request-detail"; reportId: string; requestId: string }>
  | Readonly<{ kind: "deletion"; requestId: string }>;

function userReportRoute(matched: GatewayRouteMatch | null): UserReportRoute | null {
  if (matched?.family !== "user-report") return null;
  const { reportId, requestId } = matched.params;
  switch (matched.template) {
    case "/api/reports/mine": return { kind: "list" };
    case "/api/reports/mine/requests/history": return { kind: "request-history" };
    case "/api/reports/mine/deletions/{request_id}":
      return { kind: "deletion", requestId: requestId! };
    case "/api/reports/mine/{report_id}": return { kind: "detail", reportId: reportId! };
    case "/api/reports/mine/{report_id}/content":
      return { kind: "content", reportId: reportId! };
    case "/api/reports/mine/{report_id}/corrections":
      return { kind: "correction", reportId: reportId! };
    case "/api/reports/mine/{report_id}/requests":
      return { kind: "request", reportId: reportId! };
    case "/api/reports/mine/{report_id}/requests/{request_id}":
      return { kind: "request-detail", reportId: reportId!, requestId: requestId! };
    default: return null;
  }
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
    typeof value === "string" &&
    Array.from(value).length >= 1 && Array.from(value).length <= 500
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
    "request_status_version", "external_copy_count", "external_copies", "updated_at"
  ]) ||
    value.schema_version !== "walksafe.report-deletion-status.v2" ||
    value.request_id !== requestId ||
    typeof value.report_id !== "string" || !CANONICAL_REPORT_UUID.test(value.report_id) ||
    typeof value.state !== "string" || !USER_REPORT_DELETION_STATES.has(value.state) ||
    typeof value.request_status_version !== "number" ||
    !Number.isSafeInteger(value.request_status_version) ||
    value.request_status_version < 1 ||
    typeof value.external_copy_count !== "number" ||
    !Number.isSafeInteger(value.external_copy_count) ||
    value.external_copy_count < 0 || !Array.isArray(value.external_copies) ||
    value.external_copy_count !== value.external_copies.length ||
    (value.state !== "DELETED" && value.external_copies.length > 0) ||
    !validAwareDateTime(value.updated_at)
  ) return null;
  const externalCopies: Record<string, unknown>[] = [];
  for (const candidate of value.external_copies) {
    const item = objectPayload(candidate);
    if (!item || !exactObjectKeys(item, [
      "institution", "state", "status_recorded_at"
    ]) ||
      typeof item.institution !== "string" ||
      [...item.institution].length < 1 || [...item.institution].length > 160 ||
      item.institution.trim() !== item.institution ||
      typeof item.state !== "string" ||
      !USER_REPORT_EXTERNAL_COPY_DELETION_STATES.has(item.state) ||
      (item.status_recorded_at !== null && !validAwareDateTime(item.status_recorded_at))
    ) return null;
    externalCopies.push({
      institution: item.institution,
      state: item.state,
      status_recorded_at: item.status_recorded_at
    });
  }
  return {
    schema_version: value.schema_version,
    request_id: value.request_id,
    report_id: value.report_id,
    state: value.state,
    request_status_version: value.request_status_version,
    external_copy_count: value.external_copy_count,
    external_copies: externalCopies,
    updated_at: value.updated_at
  };
}

function safeHistoryInteger(value: unknown, minimum: number): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= minimum;
}

function sanitizeUserReportRequestHistoryItem(
  value: unknown,
  expectedReportId: string | null
): Record<string, unknown> | null {
  const item = objectPayload(value);
  if (!item || !exactObjectKeys(item, [
    "revision", "source", "report_id", "request_id", "request", "deletion_status"
  ]) ||
    !safeHistoryInteger(item.revision, 1) ||
    (item.source !== "ACTIVE_REQUEST" && item.source !== "DELETION_TOMBSTONE") ||
    typeof item.report_id !== "string" || !CANONICAL_REPORT_UUID.test(item.report_id) ||
    (expectedReportId !== null && item.report_id !== expectedReportId) ||
    typeof item.request_id !== "string" || !CANONICAL_REPORT_UUID.test(item.request_id)
  ) return null;

  const requestPayload = item.request === null ? null : objectPayload(item.request);
  if (item.request !== null && requestPayload === null) return null;
  const request = requestPayload === null ? null : sanitizeRequestSummary(requestPayload);
  if (requestPayload !== null && (
    request === null ||
    request.request_id !== item.request_id ||
    !validAwareDateTime(requestPayload.created_at) ||
    !validAwareDateTime(requestPayload.updated_at)
  )) return null;

  const deletionPayload = item.deletion_status === null
    ? null : objectPayload(item.deletion_status);
  if (item.deletion_status !== null && deletionPayload === null) return null;
  const deletion = deletionPayload === null
    ? null : sanitizeReportDeletionStatus(deletionPayload, item.request_id);
  if (deletionPayload !== null && (
    deletion === null || deletion.report_id !== item.report_id
  )) return null;

  if (item.source === "ACTIVE_REQUEST") {
    if (request === null) return null;
    if (request.request_type === "CORRECTION" && deletion !== null) return null;
    if (request.request_type === "DELETE" && (
      deletion === null ||
      deletion.state === "DELETED" ||
      deletion.request_status_version !== request.status_version
    )) return null;
  } else if (
    request !== null || deletion === null || deletion.state !== "DELETED"
  ) {
    return null;
  }

  return {
    revision: item.revision,
    source: item.source,
    report_id: item.report_id,
    request_id: item.request_id,
    request,
    deletion_status: deletion
  };
}

function sanitizeUserReportRequestHistory(
  value: unknown,
  expectedReportId: string | null,
  requestCursor: string | null,
  expectedLimit: number
): Record<string, unknown> | null {
  const requestHadCursor = requestCursor !== null;
  const payload = objectPayload(value);
  if (!payload || !exactObjectKeys(payload, [
    "schema_version", "report_id", "snapshot_revision", "total_count", "items", "next_cursor"
  ]) ||
    payload.schema_version !== "walksafe.user-report-request-history-page.v1" ||
    !(
      payload.report_id === null ||
      (typeof payload.report_id === "string" && CANONICAL_REPORT_UUID.test(payload.report_id))
    ) ||
    payload.report_id !== expectedReportId ||
    !safeHistoryInteger(payload.snapshot_revision, 0) ||
    !safeHistoryInteger(payload.total_count, 0) ||
    !Array.isArray(payload.items) || payload.items.length > expectedLimit ||
    !(
      payload.next_cursor === null ||
      (typeof payload.next_cursor === "string" && USER_REPORT_CURSOR.test(payload.next_cursor))
    )
  ) return null;

  const items: Record<string, unknown>[] = [];
  const requestIds = new Set<string>();
  let previousRevision: number | null = null;
  for (const candidate of payload.items) {
    const item = sanitizeUserReportRequestHistoryItem(candidate, expectedReportId);
    if (!item) return null;
    const revision = item.revision as number;
    const requestId = item.request_id as string;
    if (
      revision > payload.snapshot_revision ||
      (previousRevision !== null && revision >= previousRevision) ||
      requestIds.has(requestId)
    ) return null;
    previousRevision = revision;
    requestIds.add(requestId);
    items.push(item);
  }
  if (
    payload.total_count < items.length ||
    ((payload.total_count === 0) !== (payload.snapshot_revision === 0)) ||
    (items.length === 0 && (payload.total_count !== 0 || payload.next_cursor !== null)) ||
    (requestHadCursor && items.length >= payload.total_count) ||
    (requestHadCursor && items.length > 0 &&
      (items[0]!.revision as number) >= payload.snapshot_revision) ||
    (requestHadCursor && payload.next_cursor !== null &&
      payload.total_count < items.length + 2) ||
    (requestCursor !== null && payload.next_cursor === requestCursor) ||
    (!requestHadCursor && items.length > 0 && items[0]!.revision !== payload.snapshot_revision) ||
    (!requestHadCursor && (
      (payload.total_count === items.length) !== (payload.next_cursor === null)
    ))
  ) return null;
  return {
    schema_version: payload.schema_version,
    report_id: payload.report_id,
    snapshot_revision: payload.snapshot_revision,
    total_count: payload.total_count,
    items,
    next_cursor: payload.next_cursor
  };
}

function sanitizeUserReportResponse(
  value: unknown,
  route: UserReportRoute,
  correctionRequest: UserReportCorrectionRequest | null,
  expectedHistoryReportId: string | null,
  historyRequestCursor: string | null,
  expectedHistoryLimit: number
): Record<string, unknown> | null {
  if (route.kind === "request-history") {
    return sanitizeUserReportRequestHistory(
      value, expectedHistoryReportId, historyRequestCursor, expectedHistoryLimit
    );
  }
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
  if (route.kind === "request-detail") {
    const sanitized = sanitizeRequestSummary(payload);
    return sanitized?.request_id === route.requestId ? sanitized : null;
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
    return sanitized && sanitized.report_id === route.reportId ? {
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

function validJsonMediaType(value: string | null): boolean {
  if (value === null) return false;
  try {
    return new MIMEType(value).essence === "application/json";
  } catch {
    return false;
  }
}

async function boundedUserReportPayload(
  response: Response,
  route: UserReportRoute,
  correctionRequest: UserReportCorrectionRequest | null,
  expectedHistoryReportId: string | null,
  historyRequestCursor: string | null,
  expectedHistoryLimit: number
): Promise<Record<string, unknown> | null> {
  const responseLimitBytes = route.kind === "request-history"
    ? USER_REPORT_REQUEST_HISTORY_RESPONSE_LIMIT_BYTES
    : USER_REPORT_RESPONSE_LIMIT_BYTES;
  const contentType = response.headers.get("content-type");
  const contentLength = response.headers.get("content-length");
  if (
    !validJsonMediaType(contentType) || response.body === null ||
    (contentLength !== null && (
      !/^\d+$/.test(contentLength) || Number(contentLength) > responseLimitBytes
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
    boundedRequest, responseLimitBytes, 5_000
  );
  return bounded.error
    ? null
    : sanitizeUserReportResponse(
      bounded.value,
      route,
      correctionRequest,
      expectedHistoryReportId,
      historyRequestCursor,
      expectedHistoryLimit
    );
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

function validUserReportRequestHistoryQuery(url: URL): boolean {
  const seen = new Set<string>();
  for (const [key, value] of url.searchParams) {
    if (seen.has(key) || !["limit", "cursor", "report_id"].includes(key)) return false;
    seen.add(key);
    if (key === "limit" && (!/^[1-9][0-9]?$/.test(value) || Number(value) > 25)) {
      return false;
    }
    if (key === "cursor" && !USER_REPORT_CURSOR.test(value)) return false;
    if (key === "report_id" && !CANONICAL_REPORT_UUID.test(value)) return false;
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
    Array.from(payload.request_text).length < 1 ||
    Array.from(payload.request_text).length > 500
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
  const backendGate = await backendAccountSessionGate(request, fetchImpl);
  if (backendGate) return backendGate.status === 401
    ? userReportNotFound() : backendGate;
  const requestUrl = new URL(request.url);
  const expectedHistoryReportId = route.kind === "request-history"
    ? requestUrl.searchParams.get("report_id")
    : null;
  const historyRequestCursor = route.kind === "request-history"
    ? requestUrl.searchParams.get("cursor")
    : null;
  const expectedHistoryLimit = route.kind === "request-history"
    ? Number(requestUrl.searchParams.get("limit") ?? "10")
    : 0;
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
      case "request-history":
        backendPath = "/reports/mine/requests/history";
        break;
      case "detail":
        backendPath = `/reports/mine/${route.reportId}`;
        break;
      case "request":
        backendPath = `/reports/mine/${route.reportId}/requests`;
        break;
      case "request-detail":
        backendPath = `/reports/mine/${route.reportId}/requests/${route.requestId}`;
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
      route.kind === "list" || route.kind === "request-history" ? request : undefined
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
    if (route.kind === "request-history" && upstream.status === 422) {
      cancelUpstream(upstream);
      return Response.json(
        { detail: { code: "report_request_history_query_invalid" } },
        { status: 422, headers: { "cache-control": "no-store" } }
      );
    }
    if (upstream.status === 429) {
      const headers: Record<string, string> = { "cache-control": "no-store" };
      const retryAfter = upstream.headers.get("retry-after");
      if (retryAfter && /^\d+$/.test(retryAfter)) {
        headers["retry-after"] = retryAfter;
      }
      cancelUpstream(upstream);
      return Response.json(
        { detail: { code: "report_rate_limited" } },
        { status: 429, headers }
      );
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
      [413, 415, 422].includes(upstream.status)
    ) {
      const headers: Record<string, string> = { "cache-control": "no-store" };
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
    const payload = await boundedUserReportPayload(
      upstream,
      route,
      correctionRequest,
      expectedHistoryReportId,
      historyRequestCursor,
      expectedHistoryLimit
    );
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

/**
 * S19-27: the deletion ledger may already hold the change. The exact retry of the same
 * request (same request_id and capability) replays it; the status route re-reads it.
 */
function deletionOutcomeUnknown(): Response {
  return Response.json(
    { code: "privacy_deletion_outcome_unknown", retry: "same_request_id" },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

function deletionError(error: unknown): Response {
  if (error instanceof GatewayStorageOutcomeUnknownError) return deletionOutcomeUnknown();
  if (!(error instanceof PrivacyDeletionV2Error)) return privacyLedgerUnavailable();
  if (error.code === "not_found") return deletionNotFound();
  if (error.code === "conflict" || error.code === "inactive") return deletionConflict();
  return privacyLedgerUnavailable();
}

type AccountDeletionRouteV2 =
  | { kind: "request" }
  | { kind: "status" | "evidence"; requestId: string };

function accountDeletionRouteV2(matched: GatewayRouteMatch | null): AccountDeletionRouteV2 | null {
  if (matched?.family !== "account-deletion") return null;
  if (matched.template === "/privacy/account-deletions") return { kind: "request" };
  const requestId = matched.params.requestId!;
  if (matched.template === "/privacy/account-deletions/{request_id}/status") {
    return { kind: "status", requestId };
  }
  if (matched.template === "/privacy/account-deletions/{request_id}/device-evidence") {
    return { kind: "evidence", requestId };
  }
  return null;
}

/** S19-44: a new account deletion needs a credential check within this window. */
export const ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS = 5 * 60 * 1000;
const ACCOUNT_DELETION_AUTH_TIME_FUTURE_SKEW_MS = 60 * 1000;

/**
 * S19-44 / S19-4-F1: true when the credential login behind the session happened within the
 * sensitive-operation window. `onStorageFailure` decides a failed auth-time read: account
 * deletion counts it as "not recent" (GWBE-10), remote session revocation rethrows the
 * long-session storage error so it is answered as a storage failure, never as a pass.
 */
function sessionRecentlyAuthenticated(
  request: Request,
  nowEpochMs: number,
  onStorageFailure: "not_recent" | "storage_error"
): boolean {
  let authenticatedAt: number | null;
  try {
    authenticatedAt = gatewaySessionAuthenticatedAtEpochMs(request, nowEpochMs);
  } catch (error) {
    if (
      onStorageFailure === "storage_error" &&
      error instanceof FieldLongSessionStorageUnavailableError
    ) throw error;
    return false;
  }
  return authenticatedAt !== null &&
    authenticatedAt <= nowEpochMs + ACCOUNT_DELETION_AUTH_TIME_FUTURE_SKEW_MS &&
    nowEpochMs - authenticatedAt <= ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS;
}

function accountDeletionSessionRecentlyAuthenticated(
  request: Request,
  nowEpochMs: number
): boolean {
  // GWBE-10: evaluated for every POST, replays included. A failed auth-time read
  // counts as "not recent": a new acceptance gets 401 and a capability replay (which
  // ignores this flag) is not turned into a storage error.
  return sessionRecentlyAuthenticated(request, nowEpochMs, "not_recent");
}

/** S19-4-F1: registered S01-20 row (401, REAUTHENTICATE, reauthentication_required). */
function fieldSessionReauthenticationRequired(): Response {
  return Response.json(
    {
      code: "field_session_reauthentication_required",
      message: "다른 기기의 세션을 해제하려면 비밀번호로 다시 로그인해야 합니다.",
      reauthentication_required: true
    },
    { status: 401, headers: { "cache-control": "no-store" } }
  );
}

function accountDeletionReauthenticationRequired(): Response {
  return Response.json(
    {
      code: "account_deletion_reauthentication_required",
      message: "계정 삭제를 접수하려면 비밀번호로 다시 로그인해야 합니다.",
      reauthentication_required: true
    },
    { status: 401, headers: { "cache-control": "no-store" } }
  );
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
    const nowEpochMs = Date.now();
    const accepted = acceptOrReplayAccountDeletionV2(
      actorId,
      accountGeneration,
      input,
      accessSecret,
      nowEpochMs,
      actorId !== null && accountDeletionSessionRecentlyAuthenticated(request, nowEpochMs)
    );
    if (accepted.kind === "reauthentication_required") {
      return accountDeletionReauthenticationRequired();
    }
    if (accepted.kind === "not_found") return deletionNotFound();
    if (accepted.kind === "conflict" || accepted.kind === "inactive") {
      return deletionConflict();
    }
    if (accepted.kind === "accepted" || accepted.kind === "replay") {
      abortPrivacyOperationsForAccountDeletion(
        accepted.actorId,
        accepted.accountGeneration
      );
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
  const matched = matchGatewayRoute(requestUrl);
  if (pathname === "/api/raw-collections" || pathname.startsWith("/api/raw-collections/")) {
    if (matched?.auth === "raw-consent-device" && isGatewaySessionAuthorized(request) &&
        (gatewaySessionAccountGeneration(request) !== null ||
          gatewaySessionAuthEpoch(request) !== null)) {
      // The null resolver stops after route, method, framing and header validation.
      const preflight = await relayRawCollection(request, {
        bindingResolver: () => null
      });
      if (preflight.status !== 401) return noStore(preflight);
      const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
      if (backendGate) return backendGate;
    }
    return noStore(await relayRawCollection(request, {
      ...(dependencies.fetchImpl ? { fetchImpl: dependencies.fetchImpl } : {}),
      bindingResolver: dependencies.fieldLongSessionBindingResolver
        ?? gatewayFieldLongSessionBinding
    }));
  }
  const userReport = userReportRoute(matched);
  if (userReport) {
    const allowed = matched!.methods;
    if (!allowed.includes(request.method)) return methodNotAllowed(allowed);
    if (matched!.auth === "field-concealed" &&
        gatewaySessionScope(request) === "account_deletion_recovery") {
      return userReportNotFound();
    }
    if (matched!.query === "validated" && userReport.kind === "request-history") {
      if (!validUserReportRequestHistoryQuery(requestUrl) || requestHasEntityBody(request)) {
        return Response.json(
          { detail: { code: "report_request_history_query_invalid" } },
          { status: 422, headers: { "cache-control": "no-store" } }
        );
      }
    } else if (matched!.query === "validated" && userReport.kind === "list") {
      if (!validUserReportQuery(requestUrl) || requestHasEntityBody(request)) {
        return Response.json(
          { detail: { code: "report_query_invalid" } },
          { status: 400, headers: { "cache-control": "no-store" } }
        );
      }
    } else if (
      (matched!.query === "none" && [...requestUrl.searchParams].length > 0) ||
      (request.method === "GET" && matched!.body === "none" && requestHasEntityBody(request))
    ) {
      return userReportNotFound();
    }
    if (matched!.auth === "field-concealed") {
      const denied = authorizeProxyRequest(request);
      if (denied) return denied.status === 401 || denied.status === 403
        ? userReportNotFound() : denied;
    }
    if (request.method === "POST" && matched!.body !== "json") {
      return gatewayUnavailableResponse();
    }
    return noStore(await userReportProxy(request, userReport, dependencies.fetchImpl));
  }
  if (matched?.family === "report-status") {
    if (matched.query === "none" && [...requestUrl.searchParams].length > 0) {
      return reportTransportStatusNotFound();
    }
    if (!matched.methods.includes(request.method)) return methodNotAllowed(matched.methods);
    if (matched.body === "none" && requestHasEntityBody(request)) {
      return Response.json(
        { detail: { code: "report_transport_status_request_invalid" } },
        { status: 400, headers: { "cache-control": "no-store" } }
      );
    }
    if (matched.auth === "field-concealed" &&
        gatewaySessionScope(request) === "account_deletion_recovery") {
      return reportTransportStatusNotFound();
    }
    if (matched.auth === "field-concealed") {
      const denied = authorizeProxyRequest(request);
      if (denied) return denied.status === 401 || denied.status === 403
        ? reportTransportStatusNotFound() : denied;
    }
    return noStore(await reportTransportStatus(
      request,
      matched.params.reportId!,
      dependencies.fetchImpl
    ));
  }
  const deletionRoute = accountDeletionRouteV2(matched);
  if (deletionRoute) {
    if (matched!.query === "none" && [...requestUrl.searchParams].length > 0) {
      return deletionNotFound();
    }
    const allowed = matched!.methods;
    if (!allowed.includes(request.method)) return methodNotAllowed(allowed);
    if (matched!.body === "none" && requestHasEntityBody(request)) {
      return Response.json(
        { code: "account_deletion_status_request_invalid" },
        { status: 400, headers: { "cache-control": "no-store" } }
      );
    }
    if (request.method === "POST" && matched!.body !== "json") {
      return gatewayUnavailableResponse();
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
  if (matched?.family === "privacy") {
    if (matched.query !== "privacy-control") return gatewayUnavailableResponse();
    if (gatewaySessionScope(request) === "account_deletion_recovery") {
      return noStore(gatewayUnauthorizedResponse(true));
    }
    if (matched.unsupportedMethod === "retired") {
      return Response.json(
        {
          code: "account_deletion_v1_retired",
          replacement: ACCOUNT_DELETION_PATH
        },
        { status: 410, headers: { "cache-control": "no-store" } }
      );
    }
    if (matched.auth === "field-session" || matched.auth === "consent-bound") {
      const denied = authorizeProxyRequest(request);
      if (denied) return noStore(denied);
    }
    if (matched.privacyVariant === "bootstrap") {
      const actorId = authorizedProxyActor(request);
      if (!actorId) return noStore(gatewayUnauthorizedResponse());
      if (request.method === "GET") {
        const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
        if (backendGate) return backendGate;
      }
      const operation = startPrivacyOperation(
        actorId,
        gatewaySessionAccountGeneration(request)
      );
      if (operation.error) return noStore(operation.error);
      try {
        if (!privacyOperationIsCurrent(operation.lease)) {
          return noStore(privacyOperationInactive());
        }
        const unsupported = privacyUnsupportedMethod(matched, request.method);
        if (unsupported) return noStore(unsupported);
        if (matched.body !== "none") return noStore(gatewayUnavailableResponse());
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
    if (matched.privacyVariant === "integrated") {
      const actorId = authorizedProxyActor(request);
      if (!actorId) return noStore(gatewayUnauthorizedResponse());
      if (request.method === "GET" || request.method === "PUT") {
        const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
        if (backendGate) return backendGate;
      }
      const operation = startPrivacyOperation(
        actorId,
        gatewaySessionAccountGeneration(request)
      );
      if (operation.error) return noStore(operation.error);
      try {
        if (!privacyOperationIsCurrent(operation.lease)) {
          return noStore(privacyOperationInactive());
        }
        const unsupported = privacyUnsupportedMethod(matched, request.method);
        if (unsupported) return noStore(unsupported);
        if (matched.body !== "by-method") return noStore(gatewayUnavailableResponse());
        const response = await handleIntegratedConsentRequest(request, {
          accountGeneration: operation.lease.accountGeneration,
          fieldActorBindingId: consentActorBindingId(operation.lease),
          signal: operation.lease.controller.signal,
          isCurrent: () => authorizedProxyActor(request) === actorId &&
            privacyOperationIsCurrent(operation.lease),
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
    const methods = matched.methods;
    if (!methods.includes(request.method)) {
      return methodNotAllowed(methods);
    }
    if (matched.body !== "none") return gatewayUnavailableResponse();
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
  if (matched?.family !== "static") return routeNotFound();
  const methods = matched.methods;
  if (!methods.includes(request.method)) return methodNotAllowed(methods);
  const accountRelayRoute = pathname === "/api/account-enrollments/email-otp" ||
    pathname === "/api/accounts";
  const exactNoQueryRoute = accountRelayRoute ||
    pathname === "/api/speech/stt" || pathname === "/api/speech/tts";
  if (exactNoQueryRoute && matched.query === "none" && [...requestUrl.searchParams].length > 0) {
    return Response.json(
      { detail: { code: "account_request_query_invalid" } },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  if (matched.auth !== "session-lifecycle" && matched.auth !== "public" &&
      gatewaySessionScope(request) === "account_deletion_recovery") {
    return noStore(gatewayUnauthorizedResponse(true));
  }
  if (matched.auth !== "public" && matched.auth !== "session-lifecycle" &&
      matched.auth !== "field-device") {
    const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
    if (backendGate) return backendGate;
  }
  if (matched.auth === "field-session" || matched.auth === "consent-bound") {
    const denied = authorizeProxyRequest(request);
    if (denied) return noStore(denied);
  }

  let response: Response;
  if (pathname === "/api/account-enrollments/email-otp") {
    response = matched.body === "json"
      ? await relayAccountEnrollment(request, dependencies.fetchImpl)
      : gatewayUnavailableResponse();
  }
  else if (pathname === "/api/accounts") {
    response = matched.body === "json"
      ? await relayAccountCreation(request, dependencies.fetchImpl)
      : gatewayUnavailableResponse();
  }
  else if (pathname === "/api/field-session") {
    response = await fieldSession(request, matched, dependencies.fetchImpl);
  }
  else if (pathname === "/api/field-walk") {
    response = await fieldWalk(
      request,
      matched,
      dependencies.fieldLongSessionBindingResolver
        ?? gatewayFieldLongSessionBinding,
      dependencies.fetchImpl
    );
  }
  else if (pathname === "/api/speech/stt" || pathname === "/api/speech/tts") {
    const relay = () => matched.body === "stream"
      ? relaySpeechStt(request, correlationId, dependencies.fetchImpl)
      : matched.body === "json"
      ? relaySpeechTts(request, correlationId, dependencies.fetchImpl)
      : gatewayUnavailableResponse();
    const actorId = gatewaySessionActor(request);
    const accountGeneration = gatewaySessionAccountGeneration(request);
    const deletionFence = (): Response | null => {
      if (!actorId || accountGeneration === null) return null;
      try {
        return isAccountGenerationFencedV2(actorId, accountGeneration)
          ? privacyOperationInactive()
          : null;
      } catch {
        return privacyLedgerUnavailable();
      }
    };
    const beforeRelay = deletionFence();
    if (beforeRelay) {
      response = beforeRelay;
    } else {
      const relayed = await relay();
      const afterRelay = deletionFence();
      if (afterRelay) cancelUpstream(relayed);
      response = afterRelay ?? relayed;
    }
  }
  else if (pathname === "/api/navigation/walking") {
    response = await walkingRoute(request, matched, dependencies.fetchImpl);
  } else if (pathname === "/api/navigation/destinations/search") {
    response = await destinationSearch(request, matched, dependencies.fetchImpl);
  } else {
    response = await reportV2(request, matched, dependencies.fetchImpl);
  }
  return noStore(response);
}

async function dispatchNavigationWithinDeadline(
  request: Request,
  correlationId: string,
  dependencies: GatewayDependencies,
  deadlineMs: number
): Promise<Response> {
  const deadlineAbort = new AbortController();
  const scopedRequest = new Request(request, {
    signal: AbortSignal.any([request.signal, deadlineAbort.signal])
  });
  const handled = dispatchGatewayRequest(scopedRequest, correlationId, dependencies);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let onClientAbort: (() => void) | undefined;
  const deadline = new Promise<"deadline">((resolve) => {
    timer = setTimeout(() => {
      deadlineAbort.abort(new Error("navigation request deadline exceeded"));
      resolve("deadline");
    }, deadlineMs);
  });
  const clientClosed = new Promise<"client_closed">((resolve) => {
    onClientAbort = () => resolve("client_closed");
    if (request.signal.aborted) onClientAbort();
    else request.signal.addEventListener("abort", onClientAbort, { once: true });
  });
  try {
    const result = await Promise.race([handled, deadline, clientClosed]);
    if (result instanceof Response) return result;
    void handled.then(cancelUpstream, () => undefined);
    // The normal cancellation path releases privacy leases in its finally block.
    // Give it one event-loop turn to settle without trusting a stalled dependency.
    await Promise.race([
      handled.then(() => undefined, () => undefined),
      new Promise<void>((resolve) => setTimeout(resolve, 50))
    ]);
    return result === "deadline" ? navigationDeadlineResponse() : clientClosedResponse();
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    if (onClientAbort) request.signal.removeEventListener("abort", onClientAbort);
  }
}

export async function handleGatewayRequest(
  request: Request,
  dependencies: GatewayDependencies = {}
): Promise<Response> {
  const startedAt = performance.now();
  const correlationId = gatewayCorrelationId(request);
  const routeTemplate = gatewayRouteTemplate(request);
  let response: Response;
  try {
    const route = matchGatewayRoute(new URL(request.url));
    const navigationDeadlineMs =
      request.method === "POST" && route?.deadline === "walking"
        ? NAVIGATION_WALKING_DEADLINE_MS
        : request.method === "GET" && route?.deadline === "search"
          ? NAVIGATION_SEARCH_DEADLINE_MS
          : null;
    response = navigationDeadlineMs === null
      ? await dispatchGatewayRequest(request, correlationId, dependencies)
      : await dispatchNavigationWithinDeadline(
          request, correlationId, dependencies, navigationDeadlineMs
        );
  } catch (error) {
    if (!(error instanceof FieldLongSessionStorageUnavailableError)) throw error;
    response = fieldLongSessionStorageUnavailableResponse();
  }
  const responseWithRequestId = withGatewayRequestId(response, correlationId);
  const sink = dependencies.telemetrySink
    ?? (process.env.NODE_ENV === "test" ? undefined : writeGatewayTelemetry);
  if (!routeTemplate || !sink) return responseWithRequestId;
  const capacityLevel = currentServerCapacityLevelForTelemetry();
  recordGatewayRequestPrepared(
    correlationId,
    routeTemplate,
    responseWithRequestId,
    performance.now() - startedAt,
    capacityLevel,
    sink
  );
  return observeGatewayResponseDelivery(
    responseWithRequestId,
    startedAt,
    (outcome, elapsedMs, firstByteMs) => recordGatewayRequestFinished(
      correlationId,
      routeTemplate,
      responseWithRequestId,
      outcome,
      elapsedMs,
      firstByteMs,
      capacityLevel,
      sink
    )
  );
}
