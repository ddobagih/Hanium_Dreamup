import {
  type AccountDeletionStatusV2,
  PrivacyDeletionV2Error,
  DELETION_ACCESS_SECRET_HEADER,
  validDeletionAccessSecretV2,
  parseAccountDeletionRequestV2,
  acceptOrReplayAccountDeletionV2,
  forwardAccountDeletionRequestV2,
  refreshBackendAccountDeletionStatusV2,
  parseDeviceDeletionEvidenceV2,
  queueDeviceDeletionEvidenceV2,
  forwardDeviceDeletionEvidenceV2
} from "./privacy-deletion-v2.js";
import { GatewayStorageOutcomeUnknownError } from "./encrypted-json-store.js";
import { type GatewayRouteMatch } from "./route-registry.js";
import { type GatewayFetch } from "./backend.js";
import { readBoundedJsonBody } from "./request-body.js";
import { isGatewaySessionAuthorized, gatewaySessionActor, gatewaySessionAccountGeneration } from "./auth.js";
import { currentActorGeneration, abortPrivacyOperationsForAccountDeletion } from "./privacy-rights.js";
import { privacyLedgerUnavailable } from "./request-scope.js";
import { sessionRecentlyAuthenticated } from "./session-authentication.js";

export function deletionNotFound(): Response {
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

export function accountDeletionRouteV2(matched: GatewayRouteMatch | null): AccountDeletionRouteV2 | null {
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

function accountDeletionSessionRecentlyAuthenticated(
  request: Request,
  nowEpochMs: number
): boolean {
  // GWBE-10: evaluated for every POST, replays included. A failed auth-time read
  // counts as "not recent": a new acceptance gets 401 and a capability replay (which
  // ignores this flag) is not turned into a storage error.
  return sessionRecentlyAuthenticated(request, nowEpochMs, "not_recent");
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

export async function accountDeletionRequestV2(
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

export async function accountDeletionStatusV2(
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

export async function accountDeletionEvidenceV2(
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

