import {
  type FieldRefreshPayload,
  fieldLongSessionStatus,
  listFieldLongSessionDevices,
  revokeFieldLongSessionDevice,
  clearFieldLongSessionWithRefresh,
  hasFieldLongSessionCookie,
  isBackendFieldLongSessionCookie,
  clearCurrentFieldLongSession,
  refreshPublicFieldLongSession,
  isFieldLongSessionEnabled,
  fieldLongLivedSessionsUnavailableResponse,
  establishBackendFieldLongSession,
  establishFieldLongSession
} from "./field-long-session.js";
import {
  withGatewayLoginLock,
  gatewayLoginRateLimitResponse,
  recordGatewayLoginAttempt,
  gatewayLoginBusyResponse,
  recordGatewayLoginFailure,
  type GatewayFieldLongSessionBinding,
  gatewayFieldLongSessionBinding,
  gatewayUnavailableResponse,
  gatewaySessionScope,
  isGatewayAccessConfigured,
  gatewaySessionStatus,
  isGatewaySessionAuthorized,
  gatewaySessionActor,
  gatewaySessionAccountGeneration,
  gatewayUnauthorizedResponse,
  listBackendDeviceGatewaySessionDevices,
  revokeBackendDeviceGatewaySession,
  clearGatewaySession,
  gatewayTrustedClientIp,
  isGatewayActorConfigured,
  revokeShortFieldSessionsForActor,
  establishBackendGatewaySession,
  verifyGatewayCredential,
  establishGatewaySession
} from "./auth.js";
import { endFieldWalkForRevokedSession } from "./field-walk-ledger.js";
import { type GatewayRouteMatch } from "./route-registry.js";
import { type GatewayFetch, checkBackendAccountSessionCurrent } from "./backend.js";
import { defaultServerCapacityRuntime, type ServerCapacityRuntime } from "./server-capacity.js";
import { readBoundedJsonBody } from "./request-body.js";
import { defaultRefreshAdmission, type RefreshAdmissionController } from "./refresh-admission.js";
import { acquireSharedFileLock } from "./exclusive-file-lock.js";
import path from "node:path";
import { resolveGatewayStateDirectory } from "./config.js";
import { parsePasswordGrant, authenticateBackendAccount } from "./account-relay.js";
import {
  bindBackendActorGenerationAsync,
  PrivacyRightsLedgerError,
  activateActorGenerationAsync
} from "./privacy-rights.js";
import { isAccountGenerationFencedV2 } from "./privacy-deletion-v2.js";
import { backendAccountSessionGate, requestHasEntityBody, objectPayload } from "./route-responses.js";
import {
  withFieldActorOperation,
  privacyLedgerUnavailable,
  privacyOperationState,
  privacyOperationInactive
} from "./request-scope.js";
import { sessionRecentlyAuthenticated } from "./session-authentication.js";

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

export async function fieldSession(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch,
  refreshController: RefreshAdmissionController = defaultRefreshAdmission,
  capacity: ServerCapacityRuntime = defaultServerCapacityRuntime
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
      return capacity.mergeServerCapacityIntoFieldSessionResponse(
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
    const refreshAdmission = refreshController.admitFieldSessionRefresh(
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
    const { session_mode: sessionMode, purpose, ...passwordPayload } = payload;
    if ("purpose" in payload && purpose !== "account_deletion_recovery") return fieldSessionRequestInvalid();
    const recoveryPassword = purpose === "account_deletion_recovery";
    if (recoveryPassword && (
      "device_id" in payload || "session_mode" in payload || payload.remember_me !== false
    )) return fieldSessionRequestInvalid();
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
          grant.rememberMe,
          recoveryPassword ? "account_deletion_recovery" : "general"
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

