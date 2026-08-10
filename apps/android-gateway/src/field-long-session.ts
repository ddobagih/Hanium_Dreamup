import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import {
  chmodSync,
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { resolveFieldLongSessionConfig, type FieldLongSessionConfig } from "./config.js";
import {
  ExclusiveFileLockBusyError,
  withExclusiveFileLockAsync
} from "./exclusive-file-lock.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  maxGatewayStateEnvelopeBytes
} from "./encrypted-json-store.js";

const FIELD_COOKIE = "walksafe_field_session";
const ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;
const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const OPAQUE_ID = /^[A-Za-z0-9_-]{24,128}$/;
const REFRESH_TOKEN = /^[A-Za-z0-9_-]{48,256}$/;
const SHA256_DIGEST = /^[0-9a-f]{64}$/;
const MAX_ACTIVE_DEVICES = 32;
const MAX_STORED_FAMILIES = 64;
const MAX_REFRESH_ROTATIONS = 4096;
export const FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES = 4 * 1024 * 1024;
const actorLocks = new Map<string, Promise<void>>();
type StorageFaultPoint = "file_fsync" | "rename" | "directory_fsync";
let storageFaultPointForTests: StorageFaultPoint | null = null;

class FieldSessionStorageOutcomeUnknownError extends Error {
  constructor() {
    super("field long-session storage outcome is unknown after rename");
    this.name = "FieldSessionStorageOutcomeUnknownError";
  }
}

type FieldSessionRevokeReason =
  | "login_replaced"
  | "logout"
  | "selective_revoke"
  | "account_lock"
  | "security_incident"
  | "refresh_token_reuse"
  | "refresh_idle_expired"
  | "refresh_absolute_expired"
  | "refresh_rotation_limit";

type FieldSessionFamily = {
  device_id: string;
  family_id: string;
  rotation: number;
  current_refresh_digest_sha256: string | null;
  consumed_refresh_digests_sha256: string[];
  current_access_digest_sha256: string | null;
  access_expires_at_epoch_ms: number;
  idle_expires_at_epoch_ms: number;
  absolute_expires_at_epoch_ms: number;
  created_at_epoch_ms: number;
  last_rotated_at_epoch_ms: number;
  revoked_at_epoch_ms: number | null;
  revoke_reason: FieldSessionRevokeReason | null;
};

type FieldSessionActorState = {
  schema_version: 1;
  actor_id: string;
  families: FieldSessionFamily[];
};

export type FieldLongSessionIdentity = {
  actorId: string;
  deviceId: string;
  familyId: string;
  rotation: number;
  accessExpiresAtEpochMs: number;
  idleExpiresAtEpochMs: number;
  absoluteExpiresAtEpochMs: number;
};

export type FieldRefreshPayload = {
  actorId: string;
  deviceId: string;
  familyId: string;
  rotation: number;
  refreshToken: string;
};

type ParsedAccessToken = {
  actorId: string;
  deviceId: string;
  familyId: string;
  rotation: number;
  expiresAtEpochMs: number;
};

export function setFieldLongSessionStorageFaultForTests(
  point: StorageFaultPoint | null
): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("field long-session storage fault injection is test-only");
  }
  storageFaultPointForTests = point;
}

function injectStorageFaultForTests(point: StorageFaultPoint): void {
  if (storageFaultPointForTests !== point) return;
  storageFaultPointForTests = null;
  throw new Error(`injected field long-session storage fault: ${point}`);
}

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

function stateParentDirectory(): string {
  const configured = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim() ?? "";
  if (process.env.NODE_ENV === "production" && !configured) {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  const directory = configured || path.join(tmpdir(), "walksafe-gateway-rate-limit-v1");
  if (!path.isAbsolute(directory)) throw new Error("gateway state directory must be absolute");
  return path.resolve(directory);
}

function secureDirectory(directory: string, context: string): string {
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const metadata = lstatSync(directory);
  const resolved = realpathSync(directory);
  if (!metadata.isDirectory() || metadata.isSymbolicLink() || resolved !== directory) {
    throw new Error(`${context} must be a real directory without symlinks`);
  }
  if (!ownedByCurrentProcess(metadata.uid) || (metadata.mode & 0o077) !== 0) {
    throw new Error(`${context} must be owned by this process and mode 0700`);
  }
  return directory;
}

function stateDirectory(): string {
  const parent = secureDirectory(stateParentDirectory(), "gateway state directory");
  return secureDirectory(path.join(parent, "field-long-sessions"), "field long-session directory");
}

function statePath(actorId: string): string {
  const digest = createHash("sha256").update(`field-long-session\0${actorId}`).digest("hex");
  return path.join(stateDirectory(), `${digest}.json`);
}

function actorLockPath(actorId: string): string {
  return `${statePath(actorId)}.lock`;
}

function isSafeEpoch(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function isRevokeReason(value: unknown): value is FieldSessionRevokeReason | null {
  return value === null || [
    "login_replaced",
    "logout",
    "selective_revoke",
    "account_lock",
    "security_incident",
    "refresh_token_reuse",
    "refresh_idle_expired",
    "refresh_absolute_expired",
    "refresh_rotation_limit"
  ].includes(String(value));
}

function validFamily(value: unknown): value is FieldSessionFamily {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const family = value as Partial<FieldSessionFamily>;
  return (
    typeof family.device_id === "string" &&
    DEVICE_ID.test(family.device_id) &&
    typeof family.family_id === "string" &&
    OPAQUE_ID.test(family.family_id) &&
    Number.isSafeInteger(family.rotation) &&
    Number(family.rotation) >= 0 &&
    Number(family.rotation) <= MAX_REFRESH_ROTATIONS &&
    (family.current_refresh_digest_sha256 === null ||
      (typeof family.current_refresh_digest_sha256 === "string" &&
        SHA256_DIGEST.test(family.current_refresh_digest_sha256))) &&
    Array.isArray(family.consumed_refresh_digests_sha256) &&
    family.consumed_refresh_digests_sha256.length <= MAX_REFRESH_ROTATIONS &&
    family.consumed_refresh_digests_sha256.every(
      (digest) => typeof digest === "string" && SHA256_DIGEST.test(digest)
    ) &&
    new Set(family.consumed_refresh_digests_sha256).size ===
      family.consumed_refresh_digests_sha256.length &&
    (family.current_access_digest_sha256 === null ||
      (typeof family.current_access_digest_sha256 === "string" &&
        SHA256_DIGEST.test(family.current_access_digest_sha256))) &&
    isSafeEpoch(family.access_expires_at_epoch_ms) &&
    isSafeEpoch(family.idle_expires_at_epoch_ms) &&
    isSafeEpoch(family.absolute_expires_at_epoch_ms) &&
    isSafeEpoch(family.created_at_epoch_ms) &&
    isSafeEpoch(family.last_rotated_at_epoch_ms) &&
    (family.revoked_at_epoch_ms === null || isSafeEpoch(family.revoked_at_epoch_ms)) &&
    isRevokeReason(family.revoke_reason) &&
    ((family.revoked_at_epoch_ms === null && family.revoke_reason === null) ||
      (family.revoked_at_epoch_ms !== null && family.revoke_reason !== null))
  );
}

function validActorState(value: unknown, actorId: string): value is FieldSessionActorState {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const state = value as Partial<FieldSessionActorState>;
  if (
    state.schema_version !== 1 ||
    state.actor_id !== actorId ||
    !ACTOR_ID.test(actorId) ||
    !Array.isArray(state.families) ||
    state.families.length > MAX_STORED_FAMILIES ||
    !state.families.every(validFamily)
  ) {
    return false;
  }
  const familyIds = state.families.map((family) => family.family_id);
  const activeDevices = state.families
    .filter((family) => family.revoked_at_epoch_ms === null)
    .map((family) => family.device_id);
  return (
    new Set(familyIds).size === familyIds.length &&
    new Set(activeDevices).size === activeDevices.length
  );
}

export function validFieldLongSessionStateForMaintenance(
  value: unknown,
  recordId: string
): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const actorId = (value as Partial<FieldSessionActorState>).actor_id;
  if (typeof actorId !== "string" || !validActorState(value, actorId)) return false;
  const digest = createHash("sha256")
    .update(`field-long-session\0${actorId}`)
    .digest("hex");
  return recordId === `${digest}.json`;
}

function readActorState(actorId: string): FieldSessionActorState {
  const target = statePath(actorId);
  let descriptor: number | null = null;
  try {
    descriptor = openSync(target, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.nlink !== 1 ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size > maxGatewayStateEnvelopeBytes(FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES)
    ) {
      throw new Error("field long-session state must be an owned regular file with mode 0600");
    }
    const decoded = decryptGatewayStateJson(
      { kind: "field-long-session", recordId: path.basename(target) },
      readFileSync(descriptor, "utf8"),
      FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES
    ).value;
    if (!validActorState(decoded, actorId)) throw new Error("invalid field long-session state");
    return decoded;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return { schema_version: 1, actor_id: actorId, families: [] };
    }
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function writeActorState(state: FieldSessionActorState): void {
  const target = statePath(state.actor_id);
  const serialized = encryptGatewayStateJson(
    { kind: "field-long-session", recordId: path.basename(target) },
    state,
    FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES
  );
  const temporary = `${target}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  let committed = false;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, serialized, "utf8");
    injectStorageFaultForTests("file_fsync");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    chmodSync(temporary, 0o600);
    injectStorageFaultForTests("rename");
    renameSync(temporary, target);
    committed = true;
    try {
      directoryDescriptor = openSync(
        path.dirname(target),
        fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
      );
      injectStorageFaultForTests("directory_fsync");
      fsyncSync(directoryDescriptor);
    } catch (error) {
      throw new FieldSessionStorageOutcomeUnknownError();
    }
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) {
      try {
        closeSync(directoryDescriptor);
      } catch (error) {
        if (!committed) throw error;
      }
    }
    try {
      rmSync(temporary, { force: true });
    } catch (error) {
      if (!committed) throw error;
    }
  }
}

async function withActorLock<T>(actorId: string, action: () => Promise<T> | T): Promise<T> {
  const previous = actorLocks.get(actorId) ?? Promise.resolve();
  let release!: () => void;
  const hold = new Promise<void>((resolve) => {
    release = resolve;
  });
  const tail = previous.then(() => hold);
  actorLocks.set(actorId, tail);
  await previous;
  try {
    return await withExclusiveFileLockAsync(actorLockPath(actorId), action);
  } finally {
    release();
    if (actorLocks.get(actorId) === tail) actorLocks.delete(actorId);
  }
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

function requestCookie(request: Request): string {
  let value = "";
  for (const part of (request.headers.get("cookie") ?? "").split(";")) {
    const separator = part.indexOf("=");
    if (separator <= 0 || part.slice(0, separator).trim() !== FIELD_COOKIE) continue;
    value = part.slice(separator + 1).trim();
  }
  return value;
}

export function hasFieldLongSessionCookie(request: Request): boolean {
  return requestCookie(request).startsWith("v4.");
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
  if (secureRequest(request)) attributes.push("Secure");
  return attributes.join("; ");
}

function accessToken(
  actorId: string,
  deviceId: string,
  familyId: string,
  rotation: number,
  expiresAtEpochMs: number
): string | null {
  const secret = sessionSecret();
  if (!secret) return null;
  const encodedActor = Buffer.from(actorId, "utf8").toString("base64url");
  const encodedDevice = Buffer.from(deviceId, "utf8").toString("base64url");
  const nonce = randomBytes(32).toString("base64url");
  const unsigned = `v4.${encodedActor}.${encodedDevice}.${familyId}.${rotation}.${expiresAtEpochMs}.${nonce}`;
  const signature = createHmac("sha256", secret)
    .update(`walksafe-field-access-v4:${unsigned}`)
    .digest("base64url");
  return `${unsigned}.${signature}`;
}

function parseAccessToken(candidate: string): ParsedAccessToken | null {
  const secret = sessionSecret();
  if (!secret) return null;
  const [version, encodedActor, encodedDevice, familyId, rotationText, expiresText, nonce, signature, extra] =
    candidate.split(".");
  if (
    extra !== undefined ||
    version !== "v4" ||
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
    rotation > MAX_REFRESH_ROTATIONS ||
    !isSafeEpoch(expiresAtEpochMs)
  ) {
    return null;
  }
  const unsigned = candidate.slice(0, candidate.lastIndexOf("."));
  const expectedSignature = createHmac("sha256", secret)
    .update(`walksafe-field-access-v4:${unsigned}`)
    .digest("base64url");
  if (!constantTimeEqual(signature, expectedSignature)) return null;
  return { actorId, deviceId, familyId, rotation, expiresAtEpochMs };
}

function identityFromCandidate(
  candidate: string,
  nowEpochMs: number,
  allowExpired: boolean
): FieldLongSessionIdentity | null {
  if (allowExpired ? !sessionSecret() : !configuration()) return null;
  const parsed = parseAccessToken(candidate);
  if (!parsed) return null;
  try {
    const state = readActorState(parsed.actorId);
    const family = state.families.find((entry) => entry.family_id === parsed.familyId);
    if (
      !family ||
      family.revoked_at_epoch_ms !== null ||
      family.device_id !== parsed.deviceId ||
      family.rotation !== parsed.rotation ||
      family.access_expires_at_epoch_ms !== parsed.expiresAtEpochMs ||
      !family.current_access_digest_sha256 ||
      !constantTimeEqual(sha256(candidate), family.current_access_digest_sha256)
    ) {
      return null;
    }
    if (
      !allowExpired &&
      (nowEpochMs >= family.access_expires_at_epoch_ms ||
        nowEpochMs >= family.idle_expires_at_epoch_ms ||
        nowEpochMs >= family.absolute_expires_at_epoch_ms)
    ) {
      return null;
    }
    return {
      actorId: parsed.actorId,
      deviceId: family.device_id,
      familyId: family.family_id,
      rotation: family.rotation,
      accessExpiresAtEpochMs: family.access_expires_at_epoch_ms,
      idleExpiresAtEpochMs: family.idle_expires_at_epoch_ms,
      absoluteExpiresAtEpochMs: family.absolute_expires_at_epoch_ms
    };
  } catch {
    return null;
  }
}

export function fieldLongSessionIdentity(
  request: Request,
  nowEpochMs = Date.now()
): FieldLongSessionIdentity | null {
  return identityFromCandidate(requestCookie(request), nowEpochMs, false);
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

function storageUnavailableResponse(): Response {
  return Response.json(
    {
      code: "field_session_storage_unavailable",
      message: "현장 세션 상태를 안전하게 저장할 수 없습니다."
    },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

function storageFailureResponse(error: unknown): Response {
  if (error instanceof ExclusiveFileLockBusyError) {
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
  return storageUnavailableResponse();
}

function invalidRefreshResponse(code = "invalid_refresh_token"): Response {
  return Response.json(
    { code, reauthentication_required: true },
    { status: 401, headers: { "cache-control": "no-store" } }
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

function sessionResponse(
  request: Request,
  actorId: string,
  family: FieldSessionFamily,
  refreshToken: string,
  accessCookie: string,
  config: FieldLongSessionConfig
): Response {
  const serialized = JSON.stringify({
    session_scope: "general",
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
        if (nowEpochMs >= family.absolute_expires_at_epoch_ms) {
          revokeFamily(family, "refresh_absolute_expired", nowEpochMs);
          expiredFamilyChanged = true;
        } else if (nowEpochMs >= family.idle_expires_at_epoch_ms) {
          revokeFamily(family, "refresh_idle_expired", nowEpochMs);
          expiredFamilyChanged = true;
        }
      }
      if (expiredFamilyChanged) writeActorState(state);
      for (const family of state.families) {
        if (family.device_id === deviceId) revokeFamily(family, "login_replaced", nowEpochMs);
      }
      const activeFamilies = state.families.filter((family) => family.revoked_at_epoch_ms === null);
      if (activeFamilies.length >= MAX_ACTIVE_DEVICES) {
        return Response.json(
          { code: "field_session_device_capacity_unavailable" },
          { status: 503, headers: { "cache-control": "no-store" } }
        );
      }
      const retainedFamilies = state.families.length >= MAX_STORED_FAMILIES
        ? activeFamilies
        : state.families;
      const familyId = randomBytes(24).toString("base64url");
      const refreshToken = randomBytes(48).toString("base64url");
      const absoluteExpiresAt = nowEpochMs + config.refreshAbsoluteTtlSeconds * 1000;
      const idleExpiresAt = Math.min(
        nowEpochMs + config.refreshIdleTtlSeconds * 1000,
        absoluteExpiresAt
      );
      const accessExpiresAt = Math.min(
        nowEpochMs + config.accessTtlSeconds * 1000,
        idleExpiresAt,
        absoluteExpiresAt
      );
      const generatedAccessToken = accessToken(actorId, deviceId, familyId, 0, accessExpiresAt);
      if (!generatedAccessToken) return unavailableResponse();
      const family: FieldSessionFamily = {
        device_id: deviceId,
        family_id: familyId,
        rotation: 0,
        current_refresh_digest_sha256: sha256(refreshToken),
        consumed_refresh_digests_sha256: [],
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
    });
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
    payload.rotation <= MAX_REFRESH_ROTATIONS &&
    REFRESH_TOKEN.test(payload.refreshToken)
  );
}

export async function refreshFieldLongSession(
  request: Request,
  payload: FieldRefreshPayload,
  nowEpochMs = Date.now()
): Promise<Response> {
  const config = configuration();
  if (!config) return unavailableResponse();
  if (!validRefreshPayload(payload) || !isSafeEpoch(nowEpochMs)) {
    return Response.json(
      { code: "field_session_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  try {
    return await withActorLock(payload.actorId, () => {
      const state = readActorState(payload.actorId);
      const family = state.families.find((entry) => entry.family_id === payload.familyId);
      if (!family) return invalidRefreshResponse();
      const candidateDigest = sha256(payload.refreshToken);
      if (
        family.consumed_refresh_digests_sha256.some(
          (digest) => constantTimeEqual(candidateDigest, digest)
        )
      ) {
        revokeFamily(family, "refresh_token_reuse", nowEpochMs);
        writeActorState(state);
        return invalidRefreshResponse("refresh_token_reuse_detected");
      }
      if (family.revoked_at_epoch_ms !== null) {
        return invalidRefreshResponse("field_session_revoked");
      }
      if (
        family.device_id !== payload.deviceId ||
        family.rotation !== payload.rotation ||
        !family.current_refresh_digest_sha256 ||
        !constantTimeEqual(candidateDigest, family.current_refresh_digest_sha256)
      ) {
        return invalidRefreshResponse();
      }
      if (nowEpochMs >= family.absolute_expires_at_epoch_ms) {
        revokeFamily(family, "refresh_absolute_expired", nowEpochMs);
        writeActorState(state);
        return invalidRefreshResponse("refresh_token_absolute_expired");
      }
      if (nowEpochMs >= family.idle_expires_at_epoch_ms) {
        revokeFamily(family, "refresh_idle_expired", nowEpochMs);
        writeActorState(state);
        return invalidRefreshResponse("refresh_token_idle_expired");
      }
      if (family.rotation >= MAX_REFRESH_ROTATIONS) {
        revokeFamily(family, "refresh_rotation_limit", nowEpochMs);
        writeActorState(state);
        return invalidRefreshResponse("refresh_rotation_limit_reached");
      }

      const nextRotation = family.rotation + 1;
      const nextRefreshToken = randomBytes(48).toString("base64url");
      const nextIdleExpiresAt = Math.min(
        nowEpochMs + config.refreshIdleTtlSeconds * 1000,
        family.absolute_expires_at_epoch_ms
      );
      const nextAccessExpiresAt = Math.min(
        nowEpochMs + config.accessTtlSeconds * 1000,
        nextIdleExpiresAt,
        family.absolute_expires_at_epoch_ms
      );
      const nextAccessToken = accessToken(
        payload.actorId,
        family.device_id,
        family.family_id,
        nextRotation,
        nextAccessExpiresAt
      );
      if (!nextAccessToken) return unavailableResponse();

      const previousDigest = family.current_refresh_digest_sha256;
      const updatedFamily: FieldSessionFamily = {
        ...family,
        rotation: nextRotation,
        current_refresh_digest_sha256: sha256(nextRefreshToken),
        consumed_refresh_digests_sha256: [
          ...family.consumed_refresh_digests_sha256,
          previousDigest
        ],
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
      writeActorState({
        ...state,
        families: state.families.map((entry) =>
          entry.family_id === updatedFamily.family_id ? updatedFamily : entry
        )
      });
      return response;
    });
  } catch (error) {
    return storageFailureResponse(error);
  }
}

export function fieldLongSessionStatus(request: Request, nowEpochMs = Date.now()): Response | null {
  if (!hasFieldLongSessionCookie(request)) return null;
  const identity = fieldLongSessionIdentity(request, nowEpochMs);
  if (!identity) {
    return Response.json(
      { required: true, authenticated: false, actor_id: null, session_scope: null },
      { headers: { "cache-control": "no-store" } }
    );
  }
  return Response.json(
    {
      required: true,
      authenticated: true,
      actor_id: identity.actorId,
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
  const identity = fieldLongSessionIdentity(request, nowEpochMs);
  if (!identity) return invalidRefreshResponse("gateway_auth_required");
  try {
    const state = readActorState(identity.actorId);
    const devices = state.families
      .filter(
        (family) =>
          family.revoked_at_epoch_ms === null &&
          nowEpochMs < family.idle_expires_at_epoch_ms &&
          nowEpochMs < family.absolute_expires_at_epoch_ms
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
  const identity = identityFromCandidate(candidate, nowEpochMs, true);
  if (identity) {
    try {
      await withActorLock(identity.actorId, () => {
        const state = readActorState(identity.actorId);
        const family = state.families.find((entry) => entry.family_id === identity.familyId);
        if (family) {
          revokeFamily(family, "logout", nowEpochMs);
          writeActorState(state);
        }
      });
    } catch (error) {
      return storageFailureResponse(error);
    }
  }
  return clearedCookieResponse(request);
}

export async function clearFieldLongSessionWithRefresh(
  request: Request,
  payload: FieldRefreshPayload,
  nowEpochMs = Date.now()
): Promise<Response> {
  if (!sessionSecret()) return unavailableResponse();
  if (!validRefreshPayload(payload) || !isSafeEpoch(nowEpochMs)) {
    return Response.json(
      { code: "field_session_request_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  try {
    await withActorLock(payload.actorId, () => {
      const state = readActorState(payload.actorId);
      const family = state.families.find((entry) => entry.family_id === payload.familyId);
      if (!family || family.device_id !== payload.deviceId) return;
      const candidateDigest = sha256(payload.refreshToken);
      const currentMatches =
        family.rotation === payload.rotation &&
        family.current_refresh_digest_sha256 !== null &&
        constantTimeEqual(candidateDigest, family.current_refresh_digest_sha256);
      const consumedMatches = family.consumed_refresh_digests_sha256.some(
        (digest) => constantTimeEqual(candidateDigest, digest)
      );
      if (!currentMatches && !consumedMatches) return;
      const wasActive = family.revoked_at_epoch_ms === null;
      revokeFamily(family, "logout", nowEpochMs);
      if (wasActive) writeActorState(state);
    });
  } catch (error) {
    return storageFailureResponse(error);
  }
  return clearedCookieResponse(request);
}

export async function revokeFieldLongSessionDevice(
  request: Request,
  deviceId: string,
  nowEpochMs = Date.now()
): Promise<Response> {
  const identity = fieldLongSessionIdentity(request, nowEpochMs);
  if (!identity) return invalidRefreshResponse("gateway_auth_required");
  if (!DEVICE_ID.test(deviceId)) {
    return Response.json(
      { code: "field_session_query_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  try {
    let stillAuthorized = true;
    await withActorLock(identity.actorId, () => {
      const currentIdentity = identityFromCandidate(requestCookie(request), nowEpochMs, false);
      if (
        !currentIdentity ||
        currentIdentity.actorId !== identity.actorId ||
        currentIdentity.familyId !== identity.familyId
      ) {
        stillAuthorized = false;
        return;
      }
      const state = readActorState(identity.actorId);
      let changed = false;
      for (const family of state.families) {
        if (family.device_id === deviceId && family.revoked_at_epoch_ms === null) {
          revokeFamily(family, "selective_revoke", nowEpochMs);
          changed = true;
        }
      }
      if (changed) writeActorState(state);
    });
    if (!stillAuthorized) return invalidRefreshResponse("gateway_auth_required");
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
  return withActorLock(actorId, () => {
    const state = readActorState(actorId);
    let revoked = 0;
    for (const family of state.families) {
      if (family.revoked_at_epoch_ms === null) {
        revokeFamily(family, reason, nowEpochMs);
        revoked += 1;
      }
    }
    if (revoked > 0) writeActorState(state);
    return revoked;
  });
}
