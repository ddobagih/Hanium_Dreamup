import { type FieldLongSessionConfig, resolveFieldLongSessionConfig } from "./config.js";
import { createHash, timingSafeEqual, createHmac, randomBytes } from "node:crypto";
import { QueueAdmissionAbortedError, QueueAdmissionBusyError } from "./keyed-admission-queue.js";
import { ExclusiveFileLockBusyError, withExclusiveFileLockAsync } from "./exclusive-file-lock.js";
import { GatewayStorageOutcomeUnknownError } from "./encrypted-json-store.js";
import { lstatSync } from "node:fs";
import path from "node:path";
import {
  type BackendEmailAccountBinding,
  MAX_REFRESH_ROTATION_NUMBER,
  MAX_REFRESH_ROTATIONS,
  type FieldSessionFamily,
  OPAQUE_ID,
  ACTOR_ID,
  DEVICE_ID,
  isSafeEpoch,
  type FieldSessionActorState,
  backendFamilies,
  type FieldSessionRevokeReason,
  MAX_STORED_FAMILIES,
  validBackendBinding,
  type BackendFieldSessionFamily
} from "./field-long-session-codec.js";
import {
  readActorState,
  injectStorageFaultForTests,
  FieldSessionStorageOutcomeUnknownError,
  writeActorState,
  stateParentDirectory,
  withActorLock,
  actorAdmission,
  actorLockPath
} from "./field-long-session-store.js";

const FIELD_COOKIE = "walksafe_field_session";

const REFRESH_TOKEN = /^[A-Za-z0-9_-]{48,256}$/;

const REFRESH_TOKEN_RANDOM_CHARS = 43;

const PROOF_REFRESH_TOKEN = /^[A-Za-z0-9_-]{86}$/;

const MAX_ACTIVE_DEVICES = 32;

export class FieldLongSessionStorageUnavailableError extends Error {
  constructor() {
    super("field long-session authentication state is unavailable");
    this.name = "FieldLongSessionStorageUnavailableError";
  }
}

export type BackendEmailAccountCheckCurrent = (
  binding: Readonly<BackendEmailAccountBinding>
) => Promise<"current" | "denied" | "unavailable">;

export type FieldLongSessionIdentity = {
  actorId: string;
  deviceId: string;
  familyId: string;
  rotation: number;
  accessExpiresAtEpochMs: number;
  idleExpiresAtEpochMs: number | null;
  absoluteExpiresAtEpochMs: number | null;
};

export type FieldLongSessionLookup =
  | { status: "authenticated"; identity: FieldLongSessionIdentity }
  | { status: "invalid" | "expired" | "revoked" | "storage_unavailable" };

export type BackendFieldLongSessionIdentity = FieldLongSessionIdentity & {
  binding: BackendEmailAccountBinding;
};

export type BackendFieldLongSessionLookup =
  | { status: "authenticated"; identity: BackendFieldLongSessionIdentity }
  | { status: "invalid" | "expired" | "revoked" | "storage_unavailable" | "binding_denied" | "binding_unavailable" };

export type FieldRefreshPayload = {
  actorId: string;
  deviceId: string;
  familyId: string;
  rotation: number;
  refreshToken: string;
};

type FieldRotationCommitGuard = (commit: () => void) => Response | null;

type ParsedAccessToken = {
  actorId: string;
  deviceId: string;
  familyId: string;
  rotation: number;
  expiresAtEpochMs: number;
};

/** Owner decision 2: a null expiry (configured `unlimited`) never expires. */
function expiredAt(nowEpochMs: number, expiresAtEpochMs: number | null): boolean {
  return expiresAtEpochMs !== null && nowEpochMs >= expiresAtEpochMs;
}

function ttlExpiry(nowEpochMs: number, ttlSeconds: number | null): number | null {
  return ttlSeconds === null ? null : nowEpochMs + ttlSeconds * 1000;
}

/** Earliest finite expiry; null only when every input is unlimited. */
function earliestExpiry(...values: ReadonlyArray<number | null>): number | null {
  const finite = values.filter((value): value is number => value !== null);
  return finite.length === 0 ? null : Math.min(...finite);
}

function refreshRotationLimit(config: FieldLongSessionConfig): number {
  return config.refreshAbsoluteTtlSeconds === null
    ? MAX_REFRESH_ROTATION_NUMBER
    : MAX_REFRESH_ROTATIONS;
}

function sameBackendBinding(
  left: BackendEmailAccountBinding,
  right: BackendEmailAccountBinding
): boolean {
  return left.actorId === right.actorId &&
    left.accountGeneration === right.accountGeneration &&
    left.authEpoch === right.authEpoch &&
    left.deviceId === right.deviceId;
}

function sessionSecret(): string | null {
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  return secret.length >= 32 ? secret : null;
}

function configuration(): FieldLongSessionConfig | null {
  return sessionSecret() ? resolveFieldLongSessionConfig() : null;
}

export function isFieldLongSessionEnabled(): boolean {
  return configuration() !== null;
}

function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function constantTimeEqual(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left);
  const rightBytes = Buffer.from(right);
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

function refreshTokenProof(
  key: string,
  familyId: string,
  rotation: number,
  randomPart: string
): string {
  return createHmac("sha256", Buffer.from(key, "base64url"))
    .update(`walksafe-field-refresh-proof-v1\0${familyId}\0${rotation}\0${randomPart}`)
    .digest("base64url");
}

function issueRefreshToken(family: Pick<FieldSessionFamily, "family_id" | "refresh_proof_key_b64url">, rotation: number): string {
  const key = family.refresh_proof_key_b64url;
  if (!key) throw new Error("field long-session family has no refresh proof key");
  const randomPart = randomBytes(32).toString("base64url");
  return `${randomPart}${refreshTokenProof(key, family.family_id, rotation, randomPart)}`;
}

/** True only for a token this family issued for an earlier rotation. */
function isProvenPastRefreshToken(
  family: FieldSessionFamily,
  token: string,
  rotation: number
): boolean {
  const key = family.refresh_proof_key_b64url;
  if (
    !key ||
    !PROOF_REFRESH_TOKEN.test(token) ||
    !Number.isSafeInteger(rotation) ||
    rotation < 0 ||
    rotation >= family.rotation
  ) return false;
  const randomPart = token.slice(0, REFRESH_TOKEN_RANDOM_CHARS);
  return constantTimeEqual(
    token.slice(REFRESH_TOKEN_RANDOM_CHARS),
    refreshTokenProof(key, family.family_id, rotation, randomPart)
  );
}

function isConsumedRefreshToken(
  family: FieldSessionFamily,
  token: string,
  rotation: number
): boolean {
  const digest = sha256(token);
  return family.consumed_refresh_digests_sha256.some((entry) => constantTimeEqual(digest, entry)) ||
    isProvenPastRefreshToken(family, token, rotation);
}

/**
 * Next rotation's family fields. A pre-S19-7 family records its last random token digest
 * once and gets a proof key; afterwards the digest history never grows.
 */
function rotatedRefreshFields(
  family: FieldSessionFamily,
  presentedToken: string,
  nextRotation: number
): Pick<FieldSessionFamily, "consumed_refresh_digests_sha256" | "refresh_proof_key_b64url"> & {
  nextRefreshToken: string;
} {
  const proofKey = family.refresh_proof_key_b64url ?? randomBytes(32).toString("base64url");
  const presentedIsProven = family.refresh_proof_key_b64url !== undefined &&
    PROOF_REFRESH_TOKEN.test(presentedToken) &&
    constantTimeEqual(
      presentedToken.slice(REFRESH_TOKEN_RANDOM_CHARS),
      refreshTokenProof(
        proofKey,
        family.family_id,
        family.rotation,
        presentedToken.slice(0, REFRESH_TOKEN_RANDOM_CHARS)
      )
    );
  return {
    consumed_refresh_digests_sha256: presentedIsProven
      ? family.consumed_refresh_digests_sha256
      : [...family.consumed_refresh_digests_sha256, family.current_refresh_digest_sha256!],
    refresh_proof_key_b64url: proofKey,
    nextRefreshToken: issueRefreshToken(
      { family_id: family.family_id, refresh_proof_key_b64url: proofKey },
      nextRotation
    )
  };
}

function requestCookie(request: Request): string {
  let value = "";
  let found = false;
  for (const part of (request.headers.get("cookie") ?? "").split(";")) {
    const separator = part.indexOf("=");
    if (separator <= 0 || part.slice(0, separator).trim() !== FIELD_COOKIE) continue;
    if (found) return "";
    found = true;
    value = part.slice(separator + 1).trim();
  }
  return value;
}

export function hasFieldLongSessionCookie(request: Request): boolean {
  return requestCookie(request).startsWith("v4.");
}

export function hasBackendFieldLongSessionCookie(request: Request): boolean {
  return requestCookie(request).startsWith("v6.");
}

function secureRequest(request: Request): boolean {
  const forwardedProtocol = request.headers.get("x-forwarded-proto")?.split(",", 1)[0]?.trim().toLowerCase();
  return forwardedProtocol === "https" || new URL(request.url).protocol === "https:";
}

function cookieHeader(value: string, request: Request, maxAge: number): string {
  const attributes = [
    `${FIELD_COOKIE}=${value}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Strict",
    `Max-Age=${maxAge}`
  ];
  // Match auth.ts: production never depends on proxy headers to mark cookies Secure.
  if (process.env.NODE_ENV === "production" || secureRequest(request)) attributes.push("Secure");
  return attributes.join("; ");
}

function accessToken(
  actorId: string,
  deviceId: string,
  familyId: string,
  rotation: number,
  expiresAtEpochMs: number,
  version: "v4" | "v6" = "v4"
): string | null {
  const secret = sessionSecret();
  if (!secret) return null;
  const encodedActor = Buffer.from(actorId, "utf8").toString("base64url");
  const encodedDevice = Buffer.from(deviceId, "utf8").toString("base64url");
  const nonce = randomBytes(32).toString("base64url");
  const unsigned = `${version}.${encodedActor}.${encodedDevice}.${familyId}.${rotation}.${expiresAtEpochMs}.${nonce}`;
  const signature = createHmac("sha256", secret)
    .update(`walksafe-field-access-${version}:${unsigned}`)
    .digest("base64url");
  return `${unsigned}.${signature}`;
}

function parseAccessToken(
  candidate: string,
  expectedVersion: "v4" | "v6" = "v4"
): ParsedAccessToken | null {
  const secret = sessionSecret();
  if (!secret) return null;
  const [version, encodedActor, encodedDevice, familyId, rotationText, expiresText, nonce, signature, extra] =
    candidate.split(".");
  if (
    extra !== undefined ||
    version !== expectedVersion ||
    !encodedActor ||
    !encodedDevice ||
    !familyId ||
    !rotationText ||
    !expiresText ||
    !nonce ||
    !signature ||
    !OPAQUE_ID.test(familyId) ||
    !OPAQUE_ID.test(nonce) ||
    !OPAQUE_ID.test(signature)
  ) {
    return null;
  }
  let actorId = "";
  let deviceId = "";
  try {
    actorId = Buffer.from(encodedActor, "base64url").toString("utf8");
    deviceId = Buffer.from(encodedDevice, "base64url").toString("utf8");
  } catch {
    return null;
  }
  const rotation = Number(rotationText);
  const expiresAtEpochMs = Number(expiresText);
  if (
    !ACTOR_ID.test(actorId) ||
    !DEVICE_ID.test(deviceId) ||
    !Number.isSafeInteger(rotation) ||
    rotation < 0 ||
    rotation > MAX_REFRESH_ROTATION_NUMBER ||
    !isSafeEpoch(expiresAtEpochMs)
  ) {
    return null;
  }
  const unsigned = candidate.slice(0, candidate.lastIndexOf("."));
  const expectedSignature = createHmac("sha256", secret)
    .update(`walksafe-field-access-${expectedVersion}:${unsigned}`)
    .digest("base64url");
  if (!constantTimeEqual(signature, expectedSignature)) return null;
  return { actorId, deviceId, familyId, rotation, expiresAtEpochMs };
}

function identityFromCandidate(
  candidate: string,
  nowEpochMs: number,
  allowExpired: boolean
): FieldLongSessionLookup {
  if (allowExpired ? !sessionSecret() : !configuration()) return { status: "invalid" };
  const parsed = parseAccessToken(candidate);
  if (!parsed) return { status: "invalid" };
  let state: FieldSessionActorState;
  try {
    state = readActorState(parsed.actorId, true);
  } catch {
    return { status: "storage_unavailable" };
  }
  const family = state.families.find((entry) => entry.family_id === parsed.familyId);
  if (!family || family.device_id !== parsed.deviceId) return { status: "invalid" };
  if (family.revoked_at_epoch_ms !== null) return { status: "revoked" };
  if (
      family.device_id !== parsed.deviceId ||
      family.rotation !== parsed.rotation ||
      family.access_expires_at_epoch_ms !== parsed.expiresAtEpochMs ||
      !family.current_access_digest_sha256 ||
      !constantTimeEqual(sha256(candidate), family.current_access_digest_sha256)
  ) {
    return { status: "invalid" };
  }
  if (
    !allowExpired &&
    (nowEpochMs >= family.access_expires_at_epoch_ms ||
      expiredAt(nowEpochMs, family.idle_expires_at_epoch_ms) ||
      expiredAt(nowEpochMs, family.absolute_expires_at_epoch_ms))
  ) {
    return { status: "expired" };
  }
  return {
    status: "authenticated",
    identity: {
      actorId: parsed.actorId,
      deviceId: family.device_id,
      familyId: family.family_id,
      rotation: family.rotation,
      accessExpiresAtEpochMs: family.access_expires_at_epoch_ms,
      idleExpiresAtEpochMs: family.idle_expires_at_epoch_ms,
      absoluteExpiresAtEpochMs: family.absolute_expires_at_epoch_ms
    }
  };
}

export function fieldLongSessionLookup(
  request: Request,
  nowEpochMs = Date.now()
): FieldLongSessionLookup {
  return identityFromCandidate(requestCookie(request), nowEpochMs, false);
}

/**
 * S19-44: a long-session family is created only by a credential login and refresh never
 * re-authenticates, so the family creation time is the last authentication time.
 */
export function fieldLongSessionAuthenticatedAtEpochMs(
  request: Request,
  nowEpochMs = Date.now()
): number | null {
  const lookup = fieldLongSessionLookup(request, nowEpochMs);
  if (lookup.status === "storage_unavailable") {
    throw new FieldLongSessionStorageUnavailableError();
  }
  if (lookup.status !== "authenticated") return null;
  injectStorageFaultForTests("auth_time_read");
  const family = readActorState(lookup.identity.actorId, true).families
    .find((entry) => entry.family_id === lookup.identity.familyId);
  return family && isSafeEpoch(family.created_at_epoch_ms)
    ? family.created_at_epoch_ms
    : null;
}

export function fieldLongSessionIdentity(
  request: Request,
  nowEpochMs = Date.now()
): FieldLongSessionIdentity | null {
  const result = fieldLongSessionLookup(request, nowEpochMs);
  if (result.status === "storage_unavailable") {
    throw new FieldLongSessionStorageUnavailableError();
  }
  return result.status === "authenticated" ? result.identity : null;
}

function backendIdentityFromCandidate(
  candidate: string,
  nowEpochMs: number,
  allowExpired = false
): BackendFieldLongSessionLookup {
  if (allowExpired ? !sessionSecret() : !configuration()) return { status: "invalid" };
  const parsed = parseAccessToken(candidate, "v6");
  if (!parsed) return { status: "invalid" };
  let state: FieldSessionActorState;
  try {
    state = readActorState(parsed.actorId, true);
  } catch {
    return { status: "storage_unavailable" };
  }
  const family = backendFamilies(state).find((entry) => entry.family_id === parsed.familyId);
  if (!family || family.device_id !== parsed.deviceId) return { status: "invalid" };
  if (family.revoked_at_epoch_ms !== null) return { status: "revoked" };
  if (
    family.rotation !== parsed.rotation ||
    family.access_expires_at_epoch_ms !== parsed.expiresAtEpochMs ||
    !family.current_access_digest_sha256 ||
    !constantTimeEqual(sha256(candidate), family.current_access_digest_sha256)
  ) return { status: "invalid" };
  if (
    !allowExpired && (
      nowEpochMs >= family.access_expires_at_epoch_ms ||
      expiredAt(nowEpochMs, family.idle_expires_at_epoch_ms) ||
      expiredAt(nowEpochMs, family.absolute_expires_at_epoch_ms)
    )
  ) return { status: "expired" };
  return {
    status: "authenticated",
    identity: {
      actorId: parsed.actorId,
      deviceId: family.device_id,
      familyId: family.family_id,
      rotation: family.rotation,
      accessExpiresAtEpochMs: family.access_expires_at_epoch_ms,
      idleExpiresAtEpochMs: family.idle_expires_at_epoch_ms,
      absoluteExpiresAtEpochMs: family.absolute_expires_at_epoch_ms,
      binding: { ...family.binding }
    }
  };
}

/**
 * Owner decision 2 / S19-1: synchronous access check of a Backend-email long-session cookie
 * (field-long `v6.` access token). The stored binding (actor, account generation, auth epoch,
 * device) comes from the credential login; the per-request Backend currency check is the
 * caller's (routes.ts backendAccountSessionGate, as for v7). Short `v6.` Backend sessions of
 * auth.ts never parse here (different fields and HMAC domain) and return null.
 */
export function backendFieldLongSessionIdentity(
  request: Request,
  nowEpochMs = Date.now()
): BackendFieldLongSessionIdentity | null {
  if (!hasBackendFieldLongSessionCookie(request)) return null;
  const result = backendIdentityFromCandidate(requestCookie(request), nowEpochMs);
  if (result.status === "storage_unavailable") {
    throw new FieldLongSessionStorageUnavailableError();
  }
  return result.status === "authenticated" ? result.identity : null;
}

/** True when the cookie is a correctly signed Backend-email long-session access token. */
export function isBackendFieldLongSessionCookie(request: Request): boolean {
  return hasBackendFieldLongSessionCookie(request) &&
    parseAccessToken(requestCookie(request), "v6") !== null;
}

/** S19-44 for Backend-email families: the family is created only by a password login. */
export function backendFieldLongSessionAuthenticatedAtEpochMs(
  request: Request,
  nowEpochMs = Date.now()
): number | null {
  const identity = backendFieldLongSessionIdentity(request, nowEpochMs);
  if (!identity) return null;
  injectStorageFaultForTests("auth_time_read");
  const family = backendFamilies(readActorState(identity.actorId, true))
    .find((entry) => entry.family_id === identity.familyId);
  return family && isSafeEpoch(family.created_at_epoch_ms) ? family.created_at_epoch_ms : null;
}

async function checkStoredBackendBinding(
  checkCurrent: BackendEmailAccountCheckCurrent,
  binding: BackendEmailAccountBinding
): Promise<"current" | "denied" | "unavailable"> {
  try {
    const result = await checkCurrent(Object.freeze({ ...binding }));
    return result === "current" || result === "denied" ? result : "unavailable";
  } catch {
    return "unavailable";
  }
}

export async function backendFieldLongSessionLookup(
  request: Request,
  checkCurrent: BackendEmailAccountCheckCurrent,
  nowEpochMs = Date.now()
): Promise<BackendFieldLongSessionLookup> {
  const candidate = requestCookie(request);
  const initial = backendIdentityFromCandidate(candidate, nowEpochMs);
  if (initial.status !== "authenticated") return initial;
  const check = await checkStoredBackendBinding(checkCurrent, initial.identity.binding);
  if (check === "unavailable") return { status: "binding_unavailable" };
  if (check === "denied") return { status: "binding_denied" };
  const current = backendIdentityFromCandidate(candidate, nowEpochMs);
  if (current.status !== "authenticated") return current;
  if (!sameBackendBinding(initial.identity.binding, current.identity.binding)) {
    return { status: "invalid" };
  }
  return current;
}

function unavailableResponse(): Response {
  return Response.json(
    {
      code: "field_long_lived_sessions_unavailable",
      message: "장기 현장 세션 설정을 사용할 수 없습니다."
    },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

/**
 * S19-11: storage failed before anything was committed (read/decrypt failure or a write
 * that failed before its rename), so the presented credential is unchanged and still
 * valid. `retryable: true` tells the client to keep it and retry after Retry-After; a
 * failure after the rename is field_session_storage_outcome_unknown instead.
 */
export function fieldLongSessionStorageUnavailableResponse(): Response {
  return Response.json(
    {
      code: "field_session_storage_unavailable",
      message: "현장 세션 상태를 안전하게 저장할 수 없습니다.",
      retryable: true
    },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

export function fieldLongSessionStorageOutcomeUnknownResponse(): Response {
  return Response.json(
    {
      code: "field_session_storage_outcome_unknown",
      message: "현장 세션 회전 결과를 확정할 수 없어 다시 로그인해야 합니다.",
      reauthentication_required: true,
      retryable: false
    },
    { status: 500, headers: { "cache-control": "no-store" } }
  );
}

function storageFailureResponse(error: unknown): Response {
  if (error instanceof QueueAdmissionAbortedError) {
    return Response.json(
      { code: "gateway_client_closed" },
      { status: 499, headers: { "cache-control": "no-store" } }
    );
  }
  if (error instanceof ExclusiveFileLockBusyError || error instanceof QueueAdmissionBusyError) {
    return Response.json(
      {
        code: "field_session_storage_busy",
        message: "다른 현장 세션 갱신이 완료될 때까지 다시 시도해야 합니다.",
        retryable: true
      },
      {
        status: 503,
        headers: { "cache-control": "no-store", "retry-after": "1" }
      }
    );
  }
  if (error instanceof FieldSessionStorageOutcomeUnknownError) {
    return fieldLongSessionStorageOutcomeUnknownResponse();
  }
  return fieldLongSessionStorageUnavailableResponse();
}

function invalidRefreshResponse(code = "invalid_refresh_token"): Response {
  return Response.json(
    { code, reauthentication_required: true },
    { status: 401, headers: { "cache-control": "no-store" } }
  );
}

/**
 * S01-20: device list/revoke without a current long-lived access cookie (no cookie, a
 * short v5/v6 session or a family revoked meanwhile). Same body as auth.ts
 * gatewayUnauthorizedResponse(): the registered gateway_auth_required row has no flag.
 */
function deviceSessionAuthRequiredResponse(): Response {
  return Response.json(
    { code: "gateway_auth_required", message: "인증이 필요합니다." },
    { status: 401, headers: { "cache-control": "no-store" } }
  );
}

function writeRotationWithGuard(
  state: FieldSessionActorState,
  commitGuard?: FieldRotationCommitGuard,
  onCommitted: () => void = () => undefined
): Response | null {
  const write = (): void => {
    writeActorState(state);
    onCommitted();
  };
  if (commitGuard) return commitGuard(write);
  write();
  return null;
}

/**
 * S19-11: a refresh that already committed a write answers any later failure (lock or
 * guard release) as outcome unknown, never as the retryable not-committed 503.
 */
function refreshStorageFailureResponse(error: unknown, committed: boolean): Response {
  if (committed && !(error instanceof GatewayStorageOutcomeUnknownError)) {
    return fieldLongSessionStorageOutcomeUnknownResponse();
  }
  return storageFailureResponse(error);
}

function backendBindingUnavailableResponse(): Response {
  return Response.json(
    { code: "backend_account_check_unavailable", retryable: true },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

function revokeFamily(
  family: FieldSessionFamily,
  reason: FieldSessionRevokeReason,
  nowEpochMs: number
): void {
  if (family.revoked_at_epoch_ms === null) {
    family.revoked_at_epoch_ms = nowEpochMs;
    family.revoke_reason = reason;
    family.current_refresh_digest_sha256 = null;
    family.current_access_digest_sha256 = null;
  }
}

/**
 * Owner decision 1 (2026-09-29, FP-011-DETAIL-02 over design_rules[2]): a consumed refresh
 * token presented to a live family ends every device of the account at once, legacy and
 * Backend-bound families alike, all recorded as `refresh_token_reuse`. A replay against an
 * already revoked family changes nothing, so an old stolen token cannot log the account
 * out again after the user signs in anew.
 */
function revokeAllActorFamiliesForReuse(
  state: FieldSessionActorState,
  nowEpochMs: number
): number {
  let revoked = 0;
  for (const family of [...state.families, ...backendFamilies(state)]) {
    if (family.revoked_at_epoch_ms === null) {
      revokeFamily(family, "refresh_token_reuse", nowEpochMs);
      revoked += 1;
    }
  }
  // login-F3: same write as the family revocation; short sessions check it fail-closed.
  state.reuse_revoked_at_epoch_ms = Math.max(state.reuse_revoked_at_epoch_ms ?? 0, nowEpochMs);
  return revoked;
}

/**
 * login-F3: the last account-wide reuse revocation time of the actor, or null when the actor
 * never had one (including when no long-session state exists). Reading never creates state
 * directories; an unreadable existing state is a storage fault (fail closed, retryable 503).
 */
export function fieldSessionReuseRevokedAtEpochMs(actorId: string): number | null {
  if (!ACTOR_ID.test(actorId)) return null;
  const digest = createHash("sha256").update(`field-long-session\0${actorId}`).digest("hex");
  try {
    lstatSync(path.join(stateParentDirectory(), "field-long-sessions", `${digest}.json`));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw new FieldLongSessionStorageUnavailableError();
  }
  let state: FieldSessionActorState;
  try {
    state = readActorState(actorId);
  } catch {
    throw new FieldLongSessionStorageUnavailableError();
  }
  return state.reuse_revoked_at_epoch_ms ?? null;
}

/**
 * Root decision R2 (login-F2): an unlimited idle window never frees a device slot by expiry,
 * so at the cap the active family rotated least recently (oldest creation, then family id,
 * on ties) is revoked as `capacity_evicted` in the same write as the new login.
 */
function evictForCapacity(
  activeFamilies: FieldSessionFamily[],
  config: FieldLongSessionConfig,
  nowEpochMs: number
): FieldSessionFamily[] | null {
  if (activeFamilies.length < MAX_ACTIVE_DEVICES) return activeFamilies;
  if (config.refreshIdleTtlSeconds !== null) return null;
  const ordered = [...activeFamilies].sort((left, right) =>
    left.last_rotated_at_epoch_ms - right.last_rotated_at_epoch_ms ||
    left.created_at_epoch_ms - right.created_at_epoch_ms ||
    (left.family_id < right.family_id ? -1 : left.family_id > right.family_id ? 1 : 0)
  );
  const evicted = ordered.slice(0, activeFamilies.length - MAX_ACTIVE_DEVICES + 1);
  for (const family of evicted) revokeFamily(family, "capacity_evicted", nowEpochMs);
  return activeFamilies.filter((family) => !evicted.includes(family));
}

function deviceCapacityUnavailableResponse(): Response {
  return Response.json(
    { code: "field_session_device_capacity_unavailable" },
    { status: 503, headers: { "cache-control": "no-store" } }
  );
}

/** login-F11: the registered 503 for `session_mode: "long_lived"` while the mode is off. */
export function fieldLongLivedSessionsUnavailableResponse(): Response {
  return unavailableResponse();
}

/** True for any refresh token this family issued up to and including its current rotation. */
function isIssuedRefreshToken(
  family: FieldSessionFamily,
  token: string,
  rotation: number
): boolean {
  if (isConsumedRefreshToken(family, token, rotation)) return true;
  const key = family.refresh_proof_key_b64url;
  return key !== undefined &&
    rotation === family.rotation &&
    PROOF_REFRESH_TOKEN.test(token) &&
    constantTimeEqual(
      token.slice(REFRESH_TOKEN_RANDOM_CHARS),
      refreshTokenProof(key, family.family_id, rotation, token.slice(0, REFRESH_TOKEN_RANDOM_CHARS))
    );
}

/**
 * A revoked family answers with the account-wide reuse reason only to a holder of one of its
 * own refresh tokens; anything else keeps the generic revoked answer.
 */
function revokedRefreshResponse(family: FieldSessionFamily, payload: FieldRefreshPayload): Response {
  return family.revoke_reason === "refresh_token_reuse" &&
    family.device_id === payload.deviceId &&
    isIssuedRefreshToken(family, payload.refreshToken, payload.rotation)
    ? invalidRefreshResponse("field_session_revoked_reuse_detected")
    : invalidRefreshResponse("field_session_revoked");
}

function notifyAllDevicesRevoked(
  actorId: string,
  onAllDevicesRevoked: ((actorId: string) => void) | undefined
): void {
  try {
    onAllDevicesRevoked?.(actorId);
  } catch {
    // The long-session families are already revoked durably; short sessions end at expiry.
  }
}

function sessionResponse(
  request: Request,
  actorId: string,
  family: FieldSessionFamily | BackendFieldSessionFamily,
  refreshToken: string,
  accessCookie: string,
  config: FieldLongSessionConfig
): Response {
  const serialized = JSON.stringify({
    session_scope: "general",
    ...("binding" in family ? { account_generation: family.binding.accountGeneration } : {}),
    actor_id: actorId,
    device_id: family.device_id,
    family_id: family.family_id,
    rotation: family.rotation,
    refresh_token: refreshToken,
    access_expires_at_epoch_ms: family.access_expires_at_epoch_ms,
    idle_expires_at_epoch_ms: family.idle_expires_at_epoch_ms,
    absolute_expires_at_epoch_ms: family.absolute_expires_at_epoch_ms
  });
  return new Response(serialized, {
    status: 200,
    headers: {
      "cache-control": "no-store",
      "content-type": "application/json",
      "set-cookie": cookieHeader(accessCookie, request, config.accessTtlSeconds)
    }
  });
}

export async function establishFieldLongSession(
  request: Request,
  actorId: string,
  deviceId: string,
  nowEpochMs = Date.now()
): Promise<Response> {
  const config = configuration();
  if (!config) return unavailableResponse();
  if (!ACTOR_ID.test(actorId) || !DEVICE_ID.test(deviceId) || !isSafeEpoch(nowEpochMs)) {
    return Response.json(
      { code: "field_session_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  try {
    return await withActorLock(actorId, () => {
      const state = readActorState(actorId);
      let expiredFamilyChanged = false;
      for (const family of state.families) {
        if (family.revoked_at_epoch_ms !== null) continue;
        if (expiredAt(nowEpochMs, family.absolute_expires_at_epoch_ms)) {
          revokeFamily(family, "refresh_absolute_expired", nowEpochMs);
          expiredFamilyChanged = true;
        } else if (expiredAt(nowEpochMs, family.idle_expires_at_epoch_ms)) {
          revokeFamily(family, "refresh_idle_expired", nowEpochMs);
          expiredFamilyChanged = true;
        }
      }
      if (expiredFamilyChanged) writeActorState(state);
      for (const family of state.families) {
        if (family.device_id === deviceId) revokeFamily(family, "login_replaced", nowEpochMs);
      }
      const activeFamilies = evictForCapacity(
        state.families.filter((family) => family.revoked_at_epoch_ms === null),
        config,
        nowEpochMs
      );
      if (!activeFamilies) return deviceCapacityUnavailableResponse();
      const retainedFamilies = state.families.length >= MAX_STORED_FAMILIES
        ? activeFamilies
        : state.families;
      const familyId = randomBytes(24).toString("base64url");
      const refreshProofKey = randomBytes(32).toString("base64url");
      const refreshToken = issueRefreshToken(
        { family_id: familyId, refresh_proof_key_b64url: refreshProofKey }, 0
      );
      const absoluteExpiresAt = ttlExpiry(nowEpochMs, config.refreshAbsoluteTtlSeconds);
      const idleExpiresAt = earliestExpiry(
        ttlExpiry(nowEpochMs, config.refreshIdleTtlSeconds),
        absoluteExpiresAt
      );
      const accessExpiresAt = earliestExpiry(
        nowEpochMs + config.accessTtlSeconds * 1000,
        idleExpiresAt,
        absoluteExpiresAt
      )!;
      const generatedAccessToken = accessToken(actorId, deviceId, familyId, 0, accessExpiresAt);
      if (!generatedAccessToken) return unavailableResponse();
      const family: FieldSessionFamily = {
        device_id: deviceId,
        family_id: familyId,
        rotation: 0,
        current_refresh_digest_sha256: sha256(refreshToken),
        consumed_refresh_digests_sha256: [],
        refresh_proof_key_b64url: refreshProofKey,
        current_access_digest_sha256: sha256(generatedAccessToken),
        access_expires_at_epoch_ms: accessExpiresAt,
        idle_expires_at_epoch_ms: idleExpiresAt,
        absolute_expires_at_epoch_ms: absoluteExpiresAt,
        created_at_epoch_ms: nowEpochMs,
        last_rotated_at_epoch_ms: nowEpochMs,
        revoked_at_epoch_ms: null,
        revoke_reason: null
      };
      const response = sessionResponse(
        request,
        actorId,
        family,
        refreshToken,
        generatedAccessToken,
        config
      );
      writeActorState({ ...state, families: [...retainedFamilies, family] });
      return response;
    }, request.signal);
  } catch (error) {
    return storageFailureResponse(error);
  }
}

// The caller must supply a binding obtained from a successful Backend-email login.
// Refresh and access checks never use a binding supplied by a refresh client.
export async function establishBackendFieldLongSession(
  request: Request,
  binding: BackendEmailAccountBinding,
  nowEpochMs = Date.now()
): Promise<Response> {
  const config = configuration();
  if (!config) return unavailableResponse();
  if (!validBackendBinding(binding) || !isSafeEpoch(nowEpochMs)) {
    return Response.json(
      { code: "field_session_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  try {
    return await withActorLock(binding.actorId, () => {
      const state = readActorState(binding.actorId);
      const families = backendFamilies(state);
      let expiredChanged = false;
      for (const family of families) {
        if (family.revoked_at_epoch_ms !== null) continue;
        if (expiredAt(nowEpochMs, family.absolute_expires_at_epoch_ms)) {
          revokeFamily(family, "refresh_absolute_expired", nowEpochMs);
          expiredChanged = true;
        } else if (expiredAt(nowEpochMs, family.idle_expires_at_epoch_ms)) {
          revokeFamily(family, "refresh_idle_expired", nowEpochMs);
          expiredChanged = true;
        }
      }
      if (expiredChanged) writeActorState(state);
      for (const family of families) {
        if (family.device_id === binding.deviceId) revokeFamily(family, "login_replaced", nowEpochMs);
      }
      const activeFamilies = evictForCapacity(
        families.filter((family) => family.revoked_at_epoch_ms === null),
        config,
        nowEpochMs
      ) as BackendFieldSessionFamily[] | null;
      if (!activeFamilies) return deviceCapacityUnavailableResponse();
      const retainedFamilies = families.length >= MAX_STORED_FAMILIES ? activeFamilies : families;
      const familyId = randomBytes(24).toString("base64url");
      const refreshProofKey = randomBytes(32).toString("base64url");
      const refreshToken = issueRefreshToken(
        { family_id: familyId, refresh_proof_key_b64url: refreshProofKey }, 0
      );
      const absoluteExpiresAt = ttlExpiry(nowEpochMs, config.refreshAbsoluteTtlSeconds);
      const idleExpiresAt = earliestExpiry(
        ttlExpiry(nowEpochMs, config.refreshIdleTtlSeconds),
        absoluteExpiresAt
      );
      const accessExpiresAt = earliestExpiry(
        nowEpochMs + config.accessTtlSeconds * 1000,
        idleExpiresAt,
        absoluteExpiresAt
      )!;
      const generatedAccessToken = accessToken(
        binding.actorId, binding.deviceId, familyId, 0, accessExpiresAt, "v6"
      );
      if (!generatedAccessToken) return unavailableResponse();
      const family: BackendFieldSessionFamily = {
        binding: { ...binding },
        device_id: binding.deviceId,
        family_id: familyId,
        rotation: 0,
        current_refresh_digest_sha256: sha256(refreshToken),
        consumed_refresh_digests_sha256: [],
        refresh_proof_key_b64url: refreshProofKey,
        current_access_digest_sha256: sha256(generatedAccessToken),
        access_expires_at_epoch_ms: accessExpiresAt,
        idle_expires_at_epoch_ms: idleExpiresAt,
        absolute_expires_at_epoch_ms: absoluteExpiresAt,
        created_at_epoch_ms: nowEpochMs,
        last_rotated_at_epoch_ms: nowEpochMs,
        revoked_at_epoch_ms: null,
        revoke_reason: null
      };
      const response = sessionResponse(
        request, binding.actorId, family, refreshToken, generatedAccessToken, config
      );
      writeActorState({ ...state, backend_families: [...retainedFamilies, family] });
      return response;
    }, request.signal);
  } catch (error) {
    return storageFailureResponse(error);
  }
}

function validRefreshPayload(payload: FieldRefreshPayload): boolean {
  return (
    ACTOR_ID.test(payload.actorId) &&
    DEVICE_ID.test(payload.deviceId) &&
    OPAQUE_ID.test(payload.familyId) &&
    Number.isSafeInteger(payload.rotation) &&
    payload.rotation >= 0 &&
    payload.rotation <= MAX_REFRESH_ROTATION_NUMBER &&
    REFRESH_TOKEN.test(payload.refreshToken)
  );
}

export async function refreshFieldLongSession(
  request: Request,
  payload: FieldRefreshPayload,
  nowEpochMs = Date.now(),
  commitGuard?: FieldRotationCommitGuard,
  onAllDevicesRevoked?: (actorId: string) => void
): Promise<Response> {
  const config = configuration();
  if (!config) return unavailableResponse();
  if (!validRefreshPayload(payload) || !isSafeEpoch(nowEpochMs)) {
    return Response.json(
      { code: "field_session_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  let allDevicesRevoked = false;
  let committed = false;
  const commitState = (next: FieldSessionActorState): void => {
    writeActorState(next);
    committed = true;
  };
  try {
    const response = await withActorLock(payload.actorId, () => {
      const state = readActorState(payload.actorId);
      const family = state.families.find((entry) => entry.family_id === payload.familyId);
      if (!family) return invalidRefreshResponse();
      const candidateDigest = sha256(payload.refreshToken);
      if (isConsumedRefreshToken(family, payload.refreshToken, payload.rotation)) {
        // login-F7: a replay against an already revoked family gets the same answer as a
        // Backend family (the reuse reason only for its own token holder), state unchanged.
        if (family.revoked_at_epoch_ms !== null) return revokedRefreshResponse(family, payload);
        revokeAllActorFamiliesForReuse(state, nowEpochMs);
        allDevicesRevoked = true;
        commitState(state);
        return invalidRefreshResponse("refresh_token_reuse_detected");
      }
      if (family.revoked_at_epoch_ms !== null) {
        return revokedRefreshResponse(family, payload);
      }
      if (
        family.device_id !== payload.deviceId ||
        family.rotation !== payload.rotation ||
        !family.current_refresh_digest_sha256 ||
        !constantTimeEqual(candidateDigest, family.current_refresh_digest_sha256)
      ) {
        return invalidRefreshResponse();
      }
      if (expiredAt(nowEpochMs, family.absolute_expires_at_epoch_ms)) {
        revokeFamily(family, "refresh_absolute_expired", nowEpochMs);
        commitState(state);
        return invalidRefreshResponse("refresh_token_absolute_expired");
      }
      if (expiredAt(nowEpochMs, family.idle_expires_at_epoch_ms)) {
        revokeFamily(family, "refresh_idle_expired", nowEpochMs);
        commitState(state);
        return invalidRefreshResponse("refresh_token_idle_expired");
      }
      if (family.rotation >= refreshRotationLimit(config)) {
        revokeFamily(family, "refresh_rotation_limit", nowEpochMs);
        commitState(state);
        return invalidRefreshResponse("refresh_rotation_limit_reached");
      }

      const nextRotation = family.rotation + 1;
      const { nextRefreshToken, ...rotatedProof } =
        rotatedRefreshFields(family, payload.refreshToken, nextRotation);
      const nextIdleExpiresAt = earliestExpiry(
        ttlExpiry(nowEpochMs, config.refreshIdleTtlSeconds),
        family.absolute_expires_at_epoch_ms
      );
      const nextAccessExpiresAt = earliestExpiry(
        nowEpochMs + config.accessTtlSeconds * 1000,
        nextIdleExpiresAt,
        family.absolute_expires_at_epoch_ms
      )!;
      const nextAccessToken = accessToken(
        payload.actorId,
        family.device_id,
        family.family_id,
        nextRotation,
        nextAccessExpiresAt
      );
      if (!nextAccessToken) return unavailableResponse();

      const updatedFamily: FieldSessionFamily = {
        ...family,
        ...rotatedProof,
        rotation: nextRotation,
        current_refresh_digest_sha256: sha256(nextRefreshToken),
        current_access_digest_sha256: sha256(nextAccessToken),
        access_expires_at_epoch_ms: nextAccessExpiresAt,
        idle_expires_at_epoch_ms: nextIdleExpiresAt,
        last_rotated_at_epoch_ms: nowEpochMs
      };
      const response = sessionResponse(
        request,
        payload.actorId,
        updatedFamily,
        nextRefreshToken,
        nextAccessToken,
        config
      );
      const blocked = writeRotationWithGuard({
        ...state,
        families: state.families.map((entry) =>
          entry.family_id === updatedFamily.family_id ? updatedFamily : entry
        )
      }, commitGuard, () => {
        committed = true;
      });
      if (blocked) return blocked;
      return response;
    }, request.signal);
    if (allDevicesRevoked) notifyAllDevicesRevoked(payload.actorId, onAllDevicesRevoked);
    return response;
  } catch (error) {
    // login-F3: the renamed state (with the reuse watermark) may already be durable.
    if (allDevicesRevoked && (committed || error instanceof FieldSessionStorageOutcomeUnknownError)) {
      notifyAllDevicesRevoked(payload.actorId, onAllDevicesRevoked);
    }
    return refreshStorageFailureResponse(error, committed);
  }
}

function backendRefreshProof(
  family: BackendFieldSessionFamily,
  payload: FieldRefreshPayload
): "current" | "consumed" | "invalid" {
  if (family.device_id !== payload.deviceId) return "invalid";
  const digest = sha256(payload.refreshToken);
  if (isConsumedRefreshToken(family, payload.refreshToken, payload.rotation)) {
    return "consumed";
  }
  if (
    family.rotation === payload.rotation &&
    family.current_refresh_digest_sha256 !== null &&
    constantTimeEqual(digest, family.current_refresh_digest_sha256)
  ) return "current";
  // Decision 1: a revoked family's last token (digest cleared) still proves possession, so
  // its holder reaches the revoked answer (with the reuse reason) instead of a generic 401.
  return family.revoked_at_epoch_ms !== null &&
    isIssuedRefreshToken(family, payload.refreshToken, payload.rotation)
    ? "current" : "invalid";
}

export async function refreshBackendFieldLongSession(
  request: Request,
  payload: FieldRefreshPayload,
  checkCurrent: BackendEmailAccountCheckCurrent,
  nowEpochMs = Date.now(),
  commitGuard?: FieldRotationCommitGuard,
  onAllDevicesRevoked?: (actorId: string) => void
): Promise<Response> {
  const config = configuration();
  if (!config) return unavailableResponse();
  if (!validRefreshPayload(payload) || !isSafeEpoch(nowEpochMs)) {
    return Response.json(
      { code: "field_session_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  let storedBinding: BackendEmailAccountBinding;
  let reuseCandidate = false;
  try {
    const state = readActorState(payload.actorId);
    const family = backendFamilies(state).find((entry) => entry.family_id === payload.familyId);
    const proof = family ? backendRefreshProof(family, payload) : "invalid";
    if (!family || proof === "invalid") {
      return invalidRefreshResponse();
    }
    if (family.revoked_at_epoch_ms !== null) return revokedRefreshResponse(family, payload);
    storedBinding = { ...family.binding };
    reuseCandidate = proof === "consumed";
  } catch (error) {
    return storageFailureResponse(error);
  }
  // login-F6: reuse of a consumed token does not depend on Backend currency, so it is
  // detected (and every device revoked) without waiting for, or failing on, the Backend.
  const check = reuseCandidate ? null : await checkStoredBackendBinding(checkCurrent, storedBinding);
  if (check === "unavailable") return backendBindingUnavailableResponse();
  let allDevicesRevoked = false;
  let committed = false;
  const commitState = (next: FieldSessionActorState): void => {
    writeActorState(next);
    committed = true;
  };
  try {
    const response = await withActorLock(payload.actorId, () => {
      const state = readActorState(payload.actorId);
      const family = backendFamilies(state).find((entry) => entry.family_id === payload.familyId);
      if (!family || !sameBackendBinding(storedBinding, family.binding)) {
        return invalidRefreshResponse();
      }
      const proof = backendRefreshProof(family, payload);
      if (proof === "consumed") {
        if (family.revoked_at_epoch_ms !== null) return revokedRefreshResponse(family, payload);
        revokeAllActorFamiliesForReuse(state, nowEpochMs);
        allDevicesRevoked = true;
        commitState(state);
        return invalidRefreshResponse("refresh_token_reuse_detected");
      }
      if (family.revoked_at_epoch_ms !== null) return revokedRefreshResponse(family, payload);
      if (proof === "invalid") return invalidRefreshResponse();
      // A consumed token cannot become current again; keep the Backend check mandatory.
      if (check === null) return backendBindingUnavailableResponse();
      if (check === "denied") {
        revokeFamily(family, "backend_binding_denied", nowEpochMs);
        commitState(state);
        return invalidRefreshResponse("backend_account_binding_denied");
      }
      if (expiredAt(nowEpochMs, family.absolute_expires_at_epoch_ms)) {
        revokeFamily(family, "refresh_absolute_expired", nowEpochMs);
        commitState(state);
        return invalidRefreshResponse("refresh_token_absolute_expired");
      }
      if (expiredAt(nowEpochMs, family.idle_expires_at_epoch_ms)) {
        revokeFamily(family, "refresh_idle_expired", nowEpochMs);
        commitState(state);
        return invalidRefreshResponse("refresh_token_idle_expired");
      }
      if (family.rotation >= refreshRotationLimit(config)) {
        revokeFamily(family, "refresh_rotation_limit", nowEpochMs);
        commitState(state);
        return invalidRefreshResponse("refresh_rotation_limit_reached");
      }
      const nextRotation = family.rotation + 1;
      const { nextRefreshToken, ...rotatedProof } =
        rotatedRefreshFields(family, payload.refreshToken, nextRotation);
      const nextIdleExpiresAt = earliestExpiry(
        ttlExpiry(nowEpochMs, config.refreshIdleTtlSeconds),
        family.absolute_expires_at_epoch_ms
      );
      const nextAccessExpiresAt = earliestExpiry(
        nowEpochMs + config.accessTtlSeconds * 1000,
        nextIdleExpiresAt,
        family.absolute_expires_at_epoch_ms
      )!;
      const nextAccessToken = accessToken(
        payload.actorId, family.device_id, family.family_id, nextRotation, nextAccessExpiresAt, "v6"
      );
      if (!nextAccessToken) return unavailableResponse();
      const updatedFamily: BackendFieldSessionFamily = {
        ...family,
        ...rotatedProof,
        rotation: nextRotation,
        current_refresh_digest_sha256: sha256(nextRefreshToken),
        current_access_digest_sha256: sha256(nextAccessToken),
        access_expires_at_epoch_ms: nextAccessExpiresAt,
        idle_expires_at_epoch_ms: nextIdleExpiresAt,
        last_rotated_at_epoch_ms: nowEpochMs
      };
      const response = sessionResponse(
        request, payload.actorId, updatedFamily, nextRefreshToken, nextAccessToken, config
      );
      const blocked = writeRotationWithGuard({
        ...state,
        backend_families: backendFamilies(state).map((entry) =>
          entry.family_id === updatedFamily.family_id ? updatedFamily : entry
        )
      }, commitGuard, () => {
        committed = true;
      });
      if (blocked) return blocked;
      return response;
    }, request.signal);
    if (allDevicesRevoked) notifyAllDevicesRevoked(payload.actorId, onAllDevicesRevoked);
    return response;
  } catch (error) {
    if (allDevicesRevoked && (committed || error instanceof FieldSessionStorageOutcomeUnknownError)) {
      notifyAllDevicesRevoked(payload.actorId, onAllDevicesRevoked);
    }
    return refreshStorageFailureResponse(error, committed);
  }
}

/**
 * `onAllDevicesRevoked` runs after a refresh-token reuse revoked every long-session family
 * of the actor (decision 1), outside the actor lock, so the caller can end short sessions too.
 */
export async function refreshPublicFieldLongSession(
  request: Request,
  payload: FieldRefreshPayload,
  legacyActorConfigured: boolean,
  checkCurrent: BackendEmailAccountCheckCurrent,
  runGuarded: (
    operation: (commitGuard: FieldRotationCommitGuard) => Promise<Response>
  ) => Promise<Response> =
    (operation) => operation((commit) => { commit(); return null; }),
  onAllDevicesRevoked?: (actorId: string) => void
): Promise<Response> {
  if (!configuration()) return unavailableResponse();
  if (!validRefreshPayload(payload)) {
    return legacyActorConfigured
      ? refreshFieldLongSession(request, payload)
      : invalidRefreshResponse();
  }
  try {
    const state = readActorState(payload.actorId);
    const legacy = state.families.find((family) => family.family_id === payload.familyId);
    const backend = backendFamilies(state).find((family) => family.family_id === payload.familyId);
    if (legacy && backend) return invalidRefreshResponse();
    if (backend) {
      if (backendRefreshProof(backend, payload) === "invalid") return invalidRefreshResponse();
      return runGuarded((commitGuard) =>
        refreshBackendFieldLongSession(
          request, payload, checkCurrent, Date.now(), commitGuard, onAllDevicesRevoked
        )
      );
    }
    if (legacy && legacyActorConfigured) {
      const candidateDigest = sha256(payload.refreshToken);
      const consumed = isConsumedRefreshToken(legacy, payload.refreshToken, payload.rotation);
      const current = legacy.device_id === payload.deviceId &&
        legacy.rotation === payload.rotation &&
        legacy.current_refresh_digest_sha256 !== null &&
        constantTimeEqual(candidateDigest, legacy.current_refresh_digest_sha256);
      if (!consumed && !current) {
        return legacy.revoked_at_epoch_ms !== null
          ? revokedRefreshResponse(legacy, payload)
          : invalidRefreshResponse("invalid_refresh_token");
      }
      return runGuarded((commitGuard) =>
        refreshFieldLongSession(request, payload, Date.now(), commitGuard, onAllDevicesRevoked)
      );
    }
    return invalidRefreshResponse();
  } catch (error) {
    return storageFailureResponse(error);
  }
}

/** The current long-session access identity: legacy `v4.` or Backend-email `v6.` family. */
function currentLongSessionIdentity(
  request: Request,
  nowEpochMs: number
): { identity: FieldLongSessionIdentity; backend: false } |
   { identity: BackendFieldLongSessionIdentity; backend: true } | null {
  if (hasFieldLongSessionCookie(request)) {
    const identity = fieldLongSessionIdentity(request, nowEpochMs);
    return identity ? { identity, backend: false } : null;
  }
  const identity = backendFieldLongSessionIdentity(request, nowEpochMs);
  return identity ? { identity, backend: true } : null;
}

function longSessionFamilies(state: FieldSessionActorState, backend: boolean): FieldSessionFamily[] {
  return backend ? backendFamilies(state) : state.families;
}

export function fieldLongSessionStatus(request: Request, nowEpochMs = Date.now()): Response | null {
  if (!hasFieldLongSessionCookie(request) && !isBackendFieldLongSessionCookie(request)) return null;
  const current = currentLongSessionIdentity(request, nowEpochMs);
  if (!current) {
    return Response.json(
      { required: true, authenticated: false, actor_id: null, session_scope: null },
      { headers: { "cache-control": "no-store" } }
    );
  }
  const { identity } = current;
  return Response.json(
    {
      required: true,
      authenticated: true,
      actor_id: identity.actorId,
      ...(current.backend ? { account_generation: current.identity.binding.accountGeneration } : {}),
      session_scope: "general",
      session_mode: "long_lived",
      device_id: identity.deviceId,
      family_id: identity.familyId,
      rotation: identity.rotation,
      access_expires_at_epoch_ms: identity.accessExpiresAtEpochMs,
      idle_expires_at_epoch_ms: identity.idleExpiresAtEpochMs,
      absolute_expires_at_epoch_ms: identity.absoluteExpiresAtEpochMs
    },
    { headers: { "cache-control": "no-store" } }
  );
}

export function listFieldLongSessionDevices(
  request: Request,
  nowEpochMs = Date.now()
): Response {
  const current = currentLongSessionIdentity(request, nowEpochMs);
  if (!current) return deviceSessionAuthRequiredResponse();
  const { identity } = current;
  try {
    const state = readActorState(identity.actorId);
    const devices = longSessionFamilies(state, current.backend)
      .filter(
        (family) =>
          family.revoked_at_epoch_ms === null &&
          !expiredAt(nowEpochMs, family.idle_expires_at_epoch_ms) &&
          !expiredAt(nowEpochMs, family.absolute_expires_at_epoch_ms)
      )
      .map((family) => ({
        device_id: family.device_id,
        family_id: family.family_id,
        current: family.family_id === identity.familyId,
        rotation: family.rotation,
        created_at_epoch_ms: family.created_at_epoch_ms,
        last_rotated_at_epoch_ms: family.last_rotated_at_epoch_ms,
        idle_expires_at_epoch_ms: family.idle_expires_at_epoch_ms,
        absolute_expires_at_epoch_ms: family.absolute_expires_at_epoch_ms
      }));
    return Response.json(
      { actor_id: identity.actorId, devices },
      { headers: { "cache-control": "no-store" } }
    );
  } catch (error) {
    return storageFailureResponse(error);
  }
}

function clearedCookieResponse(request: Request): Response {
  return new Response(null, {
    status: 204,
    headers: {
      "cache-control": "no-store",
      "set-cookie": cookieHeader("", request, 0)
    }
  });
}

export async function clearCurrentFieldLongSession(
  request: Request,
  nowEpochMs = Date.now()
): Promise<Response> {
  const candidate = requestCookie(request);
  const backend = !hasFieldLongSessionCookie(request) && isBackendFieldLongSessionCookie(request);
  const lookup = backend
    ? backendIdentityFromCandidate(candidate, nowEpochMs, true)
    : identityFromCandidate(candidate, nowEpochMs, true);
  if (lookup.status === "storage_unavailable") {
    return fieldLongSessionStorageUnavailableResponse();
  }
  if (lookup.status === "authenticated") {
    const identity = lookup.identity;
    try {
      await withActorLock(identity.actorId, () => {
        // The family was authenticated before the lock, so a missing store here
        // means revocation cannot be confirmed; it must not be reported as logout.
        const state = readActorState(identity.actorId, true);
        const family = longSessionFamilies(state, backend)
          .find((entry) => entry.family_id === identity.familyId);
        if (family) {
          revokeFamily(family, "logout", nowEpochMs);
          writeActorState(state);
        }
      }, request.signal);
    } catch (error) {
      return storageFailureResponse(error);
    }
  }
  return clearedCookieResponse(request);
}

export async function clearFieldLongSessionWithRefresh(
  request: Request,
  payload: FieldRefreshPayload,
  nowEpochMs = Date.now(),
  onVerifiedLogout?: () => void
): Promise<Response> {
  if (!sessionSecret()) return unavailableResponse();
  if (!validRefreshPayload(payload) || !isSafeEpoch(nowEpochMs)) {
    return Response.json(
      { code: "field_session_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  let verifiedLogout = false;
  try {
    await withActorLock(payload.actorId, () => {
      const state = readActorState(payload.actorId);
      // Legacy and Backend-email families share the refresh-proof logout.
      const family = state.families.find((entry) => entry.family_id === payload.familyId) ??
        backendFamilies(state).find((entry) => entry.family_id === payload.familyId);
      if (!family || family.device_id !== payload.deviceId) return;
      const candidateDigest = sha256(payload.refreshToken);
      const currentMatches =
        family.rotation === payload.rotation &&
        family.current_refresh_digest_sha256 !== null &&
        constantTimeEqual(candidateDigest, family.current_refresh_digest_sha256);
      const consumedMatches = isConsumedRefreshToken(family, payload.refreshToken, payload.rotation);
      if (!currentMatches && !consumedMatches) return;
      const wasActive = family.revoked_at_epoch_ms === null;
      revokeFamily(family, "logout", nowEpochMs);
      if (wasActive) writeActorState(state);
      verifiedLogout = true;
    }, request.signal);
  } catch (error) {
    return storageFailureResponse(error);
  }
  if (verifiedLogout) onVerifiedLogout?.();
  return clearedCookieResponse(request);
}

export async function revokeFieldLongSessionDevice(
  request: Request,
  deviceId: string,
  nowEpochMs = Date.now()
): Promise<Response> {
  const current = currentLongSessionIdentity(request, nowEpochMs);
  if (!current) return deviceSessionAuthRequiredResponse();
  const { identity, backend } = current;
  if (!DEVICE_ID.test(deviceId)) {
    return Response.json(
      { code: "field_session_query_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  try {
    let stillAuthorized = true;
    await withActorLock(identity.actorId, () => {
      const currentIdentity = backend
        ? backendIdentityFromCandidate(requestCookie(request), nowEpochMs)
        : identityFromCandidate(requestCookie(request), nowEpochMs, false);
      if (currentIdentity.status === "storage_unavailable") {
        throw new FieldLongSessionStorageUnavailableError();
      }
      if (
        currentIdentity.status !== "authenticated" ||
        currentIdentity.identity.actorId !== identity.actorId ||
        currentIdentity.identity.familyId !== identity.familyId
      ) {
        stillAuthorized = false;
        return;
      }
      const state = readActorState(identity.actorId);
      let changed = false;
      for (const family of longSessionFamilies(state, backend)) {
        if (family.device_id === deviceId && family.revoked_at_epoch_ms === null) {
          revokeFamily(family, "selective_revoke", nowEpochMs);
          changed = true;
        }
      }
      if (changed) writeActorState(state);
    }, request.signal);
    if (!stillAuthorized) return deviceSessionAuthRequiredResponse();
  } catch (error) {
    return storageFailureResponse(error);
  }
  const headers: Record<string, string> = { "cache-control": "no-store" };
  if (deviceId === identity.deviceId) headers["set-cookie"] = cookieHeader("", request, 0);
  return new Response(null, { status: 204, headers });
}

export async function revokeAllFieldLongSessions(
  actorId: string,
  reason: "account_lock" | "security_incident",
  nowEpochMs = Date.now()
): Promise<number> {
  if (!ACTOR_ID.test(actorId) || !isSafeEpoch(nowEpochMs)) {
    throw new Error("invalid field session security revocation");
  }
  return actorAdmission.runPriority(actorId, () => withExclusiveFileLockAsync(actorLockPath(actorId), () => {
    const state = readActorState(actorId);
    let revoked = 0;
    for (const family of [...state.families, ...backendFamilies(state)]) {
      if (family.revoked_at_epoch_ms === null) {
        revokeFamily(family, reason, nowEpochMs);
        revoked += 1;
      }
    }
    if (revoked > 0) writeActorState(state);
    return revoked;
  }));
}

export {
  FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES,
  setFieldLongSessionStorageFaultForTests,
  setFieldLongSessionAdmissionHoldForTests,
  fieldLongSessionStateReadsForTests
} from "./field-long-session-store.js";
export {
  type BackendEmailAccountBinding,
  validFieldLongSessionStateForMaintenance
} from "./field-long-session-codec.js";
