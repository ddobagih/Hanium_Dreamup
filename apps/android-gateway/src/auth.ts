import { timingSafeEqual, createHash, createHmac, randomBytes } from "node:crypto";
import {
  fieldSessionReuseRevokedAtEpochMs,
  fieldLongSessionIdentity,
  backendFieldLongSessionIdentity,
  fieldLongSessionAuthenticatedAtEpochMs,
  backendFieldLongSessionAuthenticatedAtEpochMs,
  fieldLongSessionStorageOutcomeUnknownResponse,
  revokeAllFieldLongSessions
} from "./field-long-session.js";
import { isIP } from "node:net";
import {
  withExclusiveFileLockAsync,
  ExclusiveFileLockBusyError,
  withExclusiveFileLock
} from "./exclusive-file-lock.js";
import path from "node:path";
import { mkdir, lstat, realpath, readdir, rm, open, rename } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import { GatewayStorageOutcomeUnknownError } from "./encrypted-json-store.js";
import {
  ACTOR_ID,
  type GatewaySessionScope,
  type BackendAccountSessionBinding,
  type LegacyGatewaySessionIdentity,
  validGatewaySessionScope,
  SESSION_MAX_AGE_SECONDS,
  type BackendGatewaySessionIdentity,
  type BackendDeviceGatewaySessionIdentity,
  DEVICE_ID,
  type GatewaySessionIdentity,
  GatewaySessionRollbackError,
  GatewaySessionDeviceCapacityError
} from "./short-session-codec.js";
import {
  readActiveSession,
  isBackendDeviceSessionLedger,
  isBackendSessionWatermark,
  isBackendSessionIdentity,
  loginAttemptDirectory,
  ownedByCurrentProcess,
  writeActiveSession,
  sessionStatePath,
  writeSessionState,
  backendSessionStateWithSlots,
  backendSessionWatermarkState,
  clearActiveSession,
  durableUnlinkSync
} from "./short-session-store.js";

const FIELD_COOKIE = "walksafe_field_session";

const MIN_TOKEN_LENGTH = 24;

const LOGIN_ATTEMPT_WINDOW_MS = 5 * 60 * 1000;

const LOGIN_BLOCK_MS = 15 * 60 * 1000;

const LOGIN_CLIENT_ATTEMPT_LIMIT = 5;

const LOGIN_GLOBAL_ATTEMPT_LIMIT = 120;

const LOGIN_TOTAL_ATTEMPT_LIMIT = 240;

const LOGIN_CLIENT_FILE_LIMIT = 256;

const LOGIN_LOCK_WAIT_MS = 1000;

const LOGIN_ATTEMPT_RETENTION_MS = LOGIN_ATTEMPT_WINDOW_MS + LOGIN_BLOCK_MS;

const LOGIN_CLIENT_ATTEMPT_FILE = /^field-[0-9a-f]{64}\.log$/;

const LOGIN_GLOBAL_ATTEMPT_FILE = "global-attempts.log";

const LOGIN_GLOBAL_FAILURE_FILE = "global-failures.log";

const RESERVED_ACTOR_IDS = new Set(["unknown", "system", "anonymous"]);

const gatewayLoginLocks = new Map<string, Promise<void>>();

type GatewayAccount = { actorId: string; token: string };

function configuredToken(): string {
  return process.env.WALKSAFE_FIELD_TEST_TOKEN?.trim() ?? "";
}

function configuredAccounts(): GatewayAccount[] {
  const raw = process.env.WALKSAFE_FIELD_ACCOUNTS_JSON?.trim();
  if (!raw) {
    if (process.env.NODE_ENV === "production") return [];
    const token = configuredToken();
    return token.length >= MIN_TOKEN_LENGTH ? [{ actorId: "field-shared", token }] : [];
  }
  try {
    const decoded = JSON.parse(raw) as unknown;
    if (!Array.isArray(decoded) || decoded.length === 0 || decoded.length > 100) return [];
    const accounts = decoded.map((entry) => {
      if (typeof entry !== "object" || entry === null || Array.isArray(entry)) throw new Error("invalid_account");
      const value = entry as Record<string, unknown>;
      const actorId = typeof value.actor_id === "string" ? value.actor_id.trim() : "";
      const token = typeof value.token === "string" ? value.token.trim() : "";
      const normalizedActorId = actorId.toLowerCase();
      if (
        !ACTOR_ID.test(actorId) ||
        RESERVED_ACTOR_IDS.has(normalizedActorId) ||
        normalizedActorId.endsWith("-shared") ||
        token.length < MIN_TOKEN_LENGTH
      ) {
        throw new Error("invalid_account");
      }
      return { actorId, token };
    });
    if (new Set(accounts.map((account) => account.actorId)).size !== accounts.length) return [];
    if (new Set(accounts.map((account) => account.token)).size !== accounts.length) return [];
    return accounts;
  } catch {
    return [];
  }
}

function productionCredentialsAreSeparated(): boolean {
  const fieldInternal = configuredToken();
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  const configuredServiceCredentials = [fieldInternal, secret].filter((value) => value.length > 0);
  if (new Set(configuredServiceCredentials).size !== configuredServiceCredentials.length) return false;
  const accountTokens = configuredAccounts().map((account) => account.token);
  return (
    new Set(accountTokens).size === accountTokens.length &&
    accountTokens.every((token) => !configuredServiceCredentials.includes(token))
  );
}

function constantTimeEqual(candidate: string, expected: string): boolean {
  const candidateBytes = Buffer.from(candidate);
  const expectedBytes = Buffer.from(expected);
  return candidateBytes.length === expectedBytes.length && timingSafeEqual(candidateBytes, expectedBytes);
}

function sessionSecret(account: GatewayAccount): string | null {
  const configured = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  if (
    configured.length >= 32 &&
    configured !== account.token &&
    configured !== configuredToken()
  ) {
    return configured;
  }
  return process.env.NODE_ENV === "production" ? null : account.token;
}

function dedicatedSessionSecret(): string | null {
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  const serviceToken = configuredToken();
  return secret.length >= 32 && secret !== serviceToken ? secret : null;
}

function sessionValue(
  account: GatewayAccount,
  expiresAtSeconds: number,
  sessionId: string,
  sessionScope: GatewaySessionScope
): string | null {
  const expires = String(expiresAtSeconds);
  const encodedActor = Buffer.from(account.actorId, "utf8").toString("base64url");
  const credentialDigest = createHash("sha256").update(account.token).digest("hex");
  const secret = sessionSecret(account);
  if (!secret) return null;
  const unsigned = `v5.${encodedActor}.${sessionScope}.${expires}.${sessionId}`;
  const signature = createHmac("sha256", secret)
    .update(`walksafe-field-session-v5:${unsigned}:${credentialDigest}`)
    .digest("base64url");
  return `${unsigned}.${signature}`;
}

function backendAccountSessionValue(
  binding: BackendAccountSessionBinding,
  expiresAtSeconds: number,
  sessionId: string,
  sessionScope: GatewaySessionScope
): string | null {
  const secret = dedicatedSessionSecret();
  if (!secret) return null;
  const encodedActor = Buffer.from(binding.actorId, "utf8").toString("base64url");
  if (binding.deviceId) {
    const encodedDevice = Buffer.from(binding.deviceId, "utf8").toString("base64url");
    const unsigned = [
      "v7",
      encodedActor,
      String(binding.accountGeneration),
      String(binding.authEpoch),
      encodedDevice,
      sessionScope,
      String(expiresAtSeconds),
      sessionId
    ].join(".");
    const signature = createHmac("sha256", secret)
      .update(`walksafe-backend-account-device-session-v1:${unsigned}`)
      .digest("base64url");
    return `${unsigned}.${signature}`;
  }
  const unsigned = [
    "v6",
    encodedActor,
    String(binding.accountGeneration),
    String(binding.authEpoch),
    sessionScope,
    String(expiresAtSeconds),
    sessionId
  ].join(".");
  const signature = createHmac("sha256", secret)
    .update(`walksafe-backend-account-session-v1:${unsigned}`)
    .digest("base64url");
  return `${unsigned}.${signature}`;
}

function validLegacySessionIdentity(candidate: string): LegacyGatewaySessionIdentity | null {
  const [version, encodedActor, sessionScope, expiresText, sessionId, signature, extra] =
    candidate.split(".");
  if (
    extra !== undefined ||
    version !== "v5" ||
    !encodedActor ||
    !validGatewaySessionScope(sessionScope) ||
    !expiresText ||
    !sessionId ||
    !signature
  ) return null;
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(sessionId)) return null;
  let actorId = "";
  try {
    actorId = Buffer.from(encodedActor, "base64url").toString("utf8");
  } catch {
    return null;
  }
  const account = configuredAccounts().find((entry) => entry.actorId === actorId);
  if (!account) return null;
  const expiresAtSeconds = Number(expiresText);
  const nowSeconds = Math.floor(Date.now() / 1000);
  if (!Number.isInteger(expiresAtSeconds) || expiresAtSeconds <= nowSeconds) return null;
  if (expiresAtSeconds > nowSeconds + SESSION_MAX_AGE_SECONDS) return null;
  const expected = sessionValue(account, expiresAtSeconds, sessionId, sessionScope);
  if (!expected || !constantTimeEqual(candidate, expected)) return null;
  const active = readActiveSession(actorId);
  if (
    !active ||
    isBackendDeviceSessionLedger(active) ||
    isBackendSessionWatermark(active) ||
    "sessionKind" in active ||
    active.actorId !== actorId ||
    active.sessionId !== sessionId ||
    active.expiresAtSeconds !== expiresAtSeconds ||
    active.sessionScope !== sessionScope
  ) {
    return null;
  }
  const identity = { actorId, sessionId, expiresAtSeconds, sessionScope };
  return endedByAccountWideReuse(identity) ? null : identity;
}

function positiveCanonicalInteger(value: string): number | null {
  if (!/^[1-9][0-9]{0,15}$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function validBackendSessionIdentity(
  candidate: string
): BackendGatewaySessionIdentity | null {
  const [
    version,
    encodedActor,
    generationText,
    authEpochText,
    sessionScope,
    expiresText,
    sessionId,
    signature,
    extra
  ] = candidate.split(".");
  if (
    extra !== undefined ||
    version !== "v6" ||
    !encodedActor ||
    !validGatewaySessionScope(sessionScope) ||
    !sessionId ||
    !signature ||
    !/^[A-Za-z0-9_-]{32,128}$/.test(sessionId)
  ) return null;
  let actorId = "";
  try {
    actorId = Buffer.from(encodedActor, "base64url").toString("utf8");
    if (Buffer.from(actorId, "utf8").toString("base64url") !== encodedActor) return null;
  } catch {
    return null;
  }
  if (!ACTOR_ID.test(actorId)) return null;
  const accountGeneration = positiveCanonicalInteger(generationText ?? "");
  const authEpoch = positiveCanonicalInteger(authEpochText ?? "");
  const expiresAtSeconds = positiveCanonicalInteger(expiresText ?? "");
  const nowSeconds = Math.floor(Date.now() / 1000);
  if (
    accountGeneration === null ||
    authEpoch === null ||
    expiresAtSeconds === null ||
    expiresAtSeconds <= nowSeconds ||
    expiresAtSeconds > nowSeconds + SESSION_MAX_AGE_SECONDS
  ) return null;
  const binding = { actorId, accountGeneration, authEpoch };
  const expected = backendAccountSessionValue(
    binding,
    expiresAtSeconds,
    sessionId,
    sessionScope
  );
  if (!expected || !constantTimeEqual(candidate, expected)) return null;
  const state = readActiveSession(actorId);
  const active = state && isBackendDeviceSessionLedger(state)
    ? state.compatibilitySession ?? null
    : state;
  if (
    !active ||
    isBackendDeviceSessionLedger(active) ||
    isBackendSessionWatermark(active) ||
    !isBackendSessionIdentity(active) ||
    active.sessionKind !== "backend_account" ||
    active.actorId !== actorId ||
    active.accountGeneration !== accountGeneration ||
    active.authEpoch !== authEpoch ||
    active.sessionId !== sessionId ||
    active.expiresAtSeconds !== expiresAtSeconds ||
    active.sessionScope !== sessionScope
  ) return null;
  const identity: BackendGatewaySessionIdentity = {
    actorId,
    accountGeneration,
    authEpoch,
    sessionId,
    expiresAtSeconds,
    sessionKind: "backend_account",
    sessionScope
  };
  return endedByAccountWideReuse(identity) ? null : identity;
}

function validBackendDeviceSessionIdentity(
  candidate: string,
  nowSeconds = Math.floor(Date.now() / 1000)
): BackendDeviceGatewaySessionIdentity | null {
  const [
    version,
    encodedActor,
    generationText,
    authEpochText,
    encodedDevice,
    sessionScope,
    expiresText,
    sessionId,
    signature,
    extra
  ] = candidate.split(".");
  if (
    extra !== undefined ||
    version !== "v7" ||
    !encodedActor ||
    !encodedDevice ||
    sessionScope !== "general" ||
    !sessionId ||
    !signature ||
    !/^[A-Za-z0-9_-]{32,128}$/.test(sessionId)
  ) return null;
  let actorId = "";
  let deviceId = "";
  try {
    actorId = Buffer.from(encodedActor, "base64url").toString("utf8");
    deviceId = Buffer.from(encodedDevice, "base64url").toString("utf8");
    if (
      Buffer.from(actorId, "utf8").toString("base64url") !== encodedActor ||
      Buffer.from(deviceId, "utf8").toString("base64url") !== encodedDevice
    ) return null;
  } catch {
    return null;
  }
  if (!ACTOR_ID.test(actorId) || !DEVICE_ID.test(deviceId)) return null;
  const accountGeneration = positiveCanonicalInteger(generationText ?? "");
  const authEpoch = positiveCanonicalInteger(authEpochText ?? "");
  const expiresAtSeconds = positiveCanonicalInteger(expiresText ?? "");
  if (
    accountGeneration === null ||
    authEpoch === null ||
    expiresAtSeconds === null ||
    expiresAtSeconds <= nowSeconds ||
    expiresAtSeconds > nowSeconds + SESSION_MAX_AGE_SECONDS
  ) return null;
  const binding = { actorId, accountGeneration, authEpoch, deviceId };
  const expected = backendAccountSessionValue(
    binding,
    expiresAtSeconds,
    sessionId,
    sessionScope
  );
  if (!expected || !constantTimeEqual(candidate, expected)) return null;
  const state = readActiveSession(actorId);
  const active = state && isBackendDeviceSessionLedger(state)
    ? state.sessions.find((session) => session.sessionId === sessionId)
    : state && !isBackendSessionWatermark(state)
      ? state
      : null;
  if (
    !active ||
    isBackendDeviceSessionLedger(active) ||
    !("sessionKind" in active) ||
    active.sessionKind !== "backend_account_device"
  ) return null;
  const backendDeviceActive = active as BackendDeviceGatewaySessionIdentity;
  if (
    backendDeviceActive.actorId !== actorId ||
    backendDeviceActive.accountGeneration !== accountGeneration ||
    backendDeviceActive.authEpoch !== authEpoch ||
    backendDeviceActive.deviceId !== deviceId ||
    backendDeviceActive.sessionId !== sessionId ||
    backendDeviceActive.expiresAtSeconds !== expiresAtSeconds ||
    backendDeviceActive.sessionScope !== sessionScope
  ) return null;
  const identity: BackendDeviceGatewaySessionIdentity = {
    actorId,
    accountGeneration,
    authEpoch,
    deviceId,
    sessionId,
    expiresAtSeconds,
    sessionKind: "backend_account_device",
    sessionScope
  };
  return endedByAccountWideReuse(identity) ? null : identity;
}

function validSessionIdentity(candidate: string): GatewaySessionIdentity | null {
  if (candidate.startsWith("v7.")) return validBackendDeviceSessionIdentity(candidate);
  if (candidate.startsWith("v6.")) return validBackendSessionIdentity(candidate);
  return validLegacySessionIdentity(candidate);
}

/**
 * login-F3 (owner decision 1, FP-011-DETAIL-02 "immediately"): a short session issued at or
 * before the actor's last account-wide reuse revocation is rejected, fail-closed, even when
 * revokeShortFieldSessionsForActor could not rewrite the short-session store (lock busy,
 * storage error, unknown rename outcome). Issue time has second precision, so a login in
 * the same second as the revocation is also rejected and must log in again.
 */
function endedByAccountWideReuse(identity: GatewaySessionIdentity): boolean {
  const reuseRevokedAt = fieldSessionReuseRevokedAtEpochMs(identity.actorId);
  if (reuseRevokedAt === null) return false;
  return (identity.expiresAtSeconds - SESSION_MAX_AGE_SECONDS) * 1000 <= reuseRevokedAt;
}

function requestCookies(request: Request): Map<string, string> | null {
  const cookies = new Map<string, string>();
  for (const part of (request.headers.get("cookie") ?? "").split(";")) {
    const separator = part.indexOf("=");
    if (separator <= 0) continue;
    const name = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (name === FIELD_COOKIE && cookies.has(name)) return null;
    if (name) cookies.set(name, value);
  }
  return cookies;
}

function secureRequest(request: Request): boolean {
  const forwardedProtocol = request.headers.get("x-forwarded-proto")?.split(",", 1)[0]?.trim().toLowerCase();
  return forwardedProtocol === "https" || new URL(request.url).protocol === "https:";
}

export function gatewayTrustedClientIp(request: Request): string | null {
  const configuredHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER?.trim().toLowerCase() ?? "";
  const trustedHeader = new Set(["cf-connecting-ip", "x-real-ip"]).has(configuredHeader)
    ? configuredHeader
    : null;
  if (!trustedHeader) return null;
  const rawAddress = request.headers.get(trustedHeader);
  if (
    !rawAddress ||
    rawAddress.length > 64 ||
    rawAddress !== rawAddress.trim() ||
    rawAddress.includes(",")
  ) {
    return null;
  }
  const version = isIP(rawAddress);
  if (version === 4) return rawAddress;
  if (version !== 6) return null;
  let address: string;
  try {
    const hostname = new URL(`http://[${rawAddress}]/`).hostname;
    if (!hostname.startsWith("[") || !hostname.endsWith("]")) return null;
    address = hostname.slice(1, -1);
  } catch {
    return null;
  }
  const mapped = /^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$/.exec(address);
  if (mapped) {
    const high = Number.parseInt(mapped[1]!, 16);
    const low = Number.parseInt(mapped[2]!, 16);
    return `${high >>> 8}.${high & 0xff}.${low >>> 8}.${low & 0xff}`;
  }
  return address;
}

function loginClientKey(request: Request): string | null {
  const address = gatewayTrustedClientIp(request);
  if (!address) return null;
  return `field:${address}`;
}

export async function withGatewayLoginLock<T>(
  action: () => Promise<T>,
  onBusy: () => T
): Promise<T> {
  const key = "gateway-login-state";
  const previous = gatewayLoginLocks.get(key) ?? Promise.resolve();
  let release!: () => void;
  const hold = new Promise<void>((resolve) => {
    release = resolve;
  });
  const tail = previous.then(() => hold);
  gatewayLoginLocks.set(key, tail);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const acquired = await Promise.race([
    previous.then(() => true),
    new Promise<boolean>((resolve) => {
      timer = setTimeout(() => resolve(false), LOGIN_LOCK_WAIT_MS);
    })
  ]);
  if (timer) clearTimeout(timer);
  if (!acquired) {
    release();
    void tail.finally(() => {
      if (gatewayLoginLocks.get(key) === tail) gatewayLoginLocks.delete(key);
    });
    return onBusy();
  }
  try {
    try {
      return await withExclusiveFileLockAsync(
        path.join(loginAttemptDirectory(), "gateway-login-state.lock"),
        action
      );
    } catch (error) {
      if (error instanceof ExclusiveFileLockBusyError) return onBusy();
      throw error;
    }
  } finally {
    release();
    if (gatewayLoginLocks.get(key) === tail) gatewayLoginLocks.delete(key);
  }
}

function loginAttemptPath(directory: string, request: Request): string {
  const clientKey = loginClientKey(request);
  if (!clientKey) throw new Error("trusted gateway client IP is required");
  const digest = createHash("sha256").update(clientKey).digest("hex");
  return path.join(directory, `field-${digest}.log`);
}

function globalLoginAttemptPath(directory: string): string {
  return path.join(directory, LOGIN_GLOBAL_ATTEMPT_FILE);
}

function globalLoginFailurePath(directory: string): string {
  return path.join(directory, LOGIN_GLOBAL_FAILURE_FILE);
}

async function secureLoginAttemptDirectory(): Promise<string> {
  const directory = loginAttemptDirectory();
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const [metadata, resolved] = await Promise.all([
    lstat(directory),
    realpath(directory)
  ]);
  if (!metadata.isDirectory() || metadata.isSymbolicLink() || resolved !== directory) {
    throw new Error("gateway rate-limit directory must be a real directory without symlinks");
  }
  if (!ownedByCurrentProcess(metadata.uid) || (metadata.mode & 0o077) !== 0) {
    throw new Error("gateway rate-limit directory must be owned by this process and mode 0700");
  }
  return directory;
}

async function pruneExpiredLoginAttemptFiles(directory: string, now: number): Promise<void> {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (
      !LOGIN_CLIENT_ATTEMPT_FILE.test(entry.name) &&
      entry.name !== LOGIN_GLOBAL_ATTEMPT_FILE &&
      entry.name !== LOGIN_GLOBAL_FAILURE_FILE
    ) continue;
    const filePath = path.join(directory, entry.name);
    let metadata;
    try {
      metadata = await lstat(filePath);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
      throw error;
    }
    if (
      !metadata.isFile() ||
      metadata.isSymbolicLink() ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.nlink !== 1 ||
      (metadata.mode & 0o077) !== 0
    ) {
      throw new Error("gateway rate-limit file must be an owned regular file with mode 0600");
    }
    if (metadata.mtimeMs <= now - LOGIN_ATTEMPT_RETENTION_MS) await rm(filePath, { force: true });
  }
}

async function readLoginAttempts(filePath: string): Promise<number[]> {
  let handle: Awaited<ReturnType<typeof open>> | null = null;
  try {
    handle = await open(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = await handle.stat();
    if (
      !metadata.isFile() ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.nlink !== 1 ||
      (metadata.mode & 0o077) !== 0
    ) {
      throw new Error("gateway rate-limit file must be owned by this process and mode 0600");
    }
    const raw = await handle.readFile({ encoding: "utf8" });
    if (Buffer.byteLength(raw, "utf8") > 64 * 1024) {
      return Array.from({ length: LOGIN_TOTAL_ATTEMPT_LIMIT }, () => Date.now());
    }
    return raw
      .split("\n")
      .map((entry) => Number(entry))
      .filter((value) => Number.isFinite(value) && value > 0)
      .sort((left, right) => left - right);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw error;
  } finally {
    await handle?.close();
  }
}

async function writeLoginAttempts(filePath: string, attempts: number[]): Promise<void> {
  const temporary = `${filePath}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let handle: Awaited<ReturnType<typeof open>> | null = null;
  try {
    handle = await open(
      temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600
    );
    const metadata = await handle.stat();
    if (
      !metadata.isFile() ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.nlink !== 1 ||
      (metadata.mode & 0o077) !== 0
    ) {
      throw new Error("gateway rate-limit file must be owned by this process and mode 0600");
    }
    await handle.writeFile(`${attempts.join("\n")}\n`, { encoding: "utf8" });
    // Login limits are security state: match the session ledger durability
    // (file fsync -> rename -> directory fsync) so a power loss cannot reset them.
    await handle.sync();
    await handle.close();
    handle = null;
    await rename(temporary, filePath);
    const directoryHandle = await open(
      path.dirname(filePath),
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
    );
    try {
      await directoryHandle.sync();
    } finally {
      await directoryHandle.close();
    }
  } finally {
    await handle?.close();
    await rm(temporary, { force: true });
  }
}

function recentLoginAttempts(attempts: number[], now: number): number[] {
  return attempts.filter(
    (attemptAt) => attemptAt > now - LOGIN_ATTEMPT_RETENTION_MS && attemptAt <= now
  );
}

function blockedUntilFromAttempts(attempts: number[], limit: number, now: number): number {
  for (let index = attempts.length - 1; index >= limit - 1; index -= 1) {
    const windowStart = attempts[index - limit + 1]!;
    const windowEnd = attempts[index]!;
    if (windowEnd - windowStart <= LOGIN_ATTEMPT_WINDOW_MS && windowEnd + LOGIN_BLOCK_MS > now) {
      return windowEnd + LOGIN_BLOCK_MS;
    }
  }
  return 0;
}

async function loginClientFileCapacityReached(directory: string, target: string): Promise<boolean> {
  try {
    const metadata = await lstat(target);
    if (
      !metadata.isFile() ||
      metadata.isSymbolicLink() ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.nlink !== 1 ||
      (metadata.mode & 0o077) !== 0
    ) {
      throw new Error("gateway rate-limit file must be an owned regular file with mode 0600");
    }
    return false;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
  const entries = await readdir(directory, { withFileTypes: true });
  return entries.filter((entry) => LOGIN_CLIENT_ATTEMPT_FILE.test(entry.name)).length >= LOGIN_CLIENT_FILE_LIMIT;
}

function cookieHeader(value: string, request: Request, maxAge: number | null): string {
  const attributes = [
    `${FIELD_COOKIE}=${value}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Strict"
  ];
  if (maxAge !== null) attributes.push(`Max-Age=${maxAge}`);
  if (process.env.NODE_ENV === "production" || secureRequest(request)) attributes.push("Secure");
  return attributes.join("; ");
}

function legacyGatewayAccessConfigured(): boolean {
  const accounts = configuredAccounts();
  if (configuredToken().length < MIN_TOKEN_LENGTH || accounts.length === 0) return false;
  if (process.env.NODE_ENV !== "production") return true;
  if (!productionCredentialsAreSeparated()) return false;
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  return (
    secret.length >= 32 &&
    secret !== configuredToken() &&
    accounts.every(
      (account) =>
        secret !== account.token &&
        account.token !== configuredToken() &&
        !account.actorId.endsWith("-shared")
    )
  );
}

function backendAccountSessionsConfigured(): boolean {
  return configuredToken().length >= MIN_TOKEN_LENGTH && dedicatedSessionSecret() !== null;
}

export function isGatewayAccessConfigured(): boolean {
  return legacyGatewayAccessConfigured() || backendAccountSessionsConfigured();
}

export function isGatewayActorConfigured(actorId: string): boolean {
  return (
    legacyGatewayAccessConfigured() &&
    configuredAccounts().some((account) => account.actorId === actorId)
  );
}

export function isInsecureLocalGatewayBypassAllowed(): boolean {
  return process.env.NODE_ENV !== "production" && process.env.WALKSAFE_ALLOW_INSECURE_LOCAL_DEV === "true";
}

type GatewaySessionAuthorization = {
  actorId: string;
  sessionScope: GatewaySessionScope;
  accountGeneration: number | null;
  authEpoch: number | null;
};

function gatewaySessionAuthorization(request: Request): GatewaySessionAuthorization | null {
  const identity = fieldLongSessionIdentity(request);
  if (identity && isGatewayActorConfigured(identity.actorId)) {
    return {
      actorId: identity.actorId,
      sessionScope: "general",
      accountGeneration: null,
      authEpoch: null
    };
  }
  // Owner decision 2 / S19-1: Backend-email long-session family. The stored binding carries
  // the account generation and auth epoch, so backendAccountSessionGate checks Backend
  // currency on every request exactly as for a v7 device session.
  const backendLong = backendFieldLongSessionIdentity(request);
  if (backendLong) {
    return {
      actorId: backendLong.actorId,
      sessionScope: "general",
      accountGeneration: backendLong.binding.accountGeneration,
      authEpoch: backendLong.binding.authEpoch
    };
  }
  const candidate = requestCookies(request)?.get(FIELD_COOKIE) ?? "";
  const shortIdentity = validSessionIdentity(candidate);
  return shortIdentity
    ? {
        actorId: shortIdentity.actorId,
        sessionScope: shortIdentity.sessionScope,
        accountGeneration: "sessionKind" in shortIdentity
          ? shortIdentity.accountGeneration
          : null,
        authEpoch: "sessionKind" in shortIdentity ? shortIdentity.authEpoch : null
      }
    : null;
}

export function isGatewaySessionAuthorized(request: Request): boolean {
  return gatewaySessionAuthorization(request) !== null;
}

/**
 * S19-44: when the credential check behind the current session happened. Short sessions
 * are only minted by a password or token login with a fixed lifetime, so their issue time
 * is `expiresAtSeconds - SESSION_MAX_AGE_SECONDS`; long sessions use the family creation
 * time. Returns null when the session is not authorized or the time is unknown.
 */
export function gatewaySessionAuthenticatedAtEpochMs(
  request: Request,
  nowEpochMs = Date.now()
): number | null {
  const identity = fieldLongSessionIdentity(request, nowEpochMs);
  if (identity && isGatewayActorConfigured(identity.actorId)) {
    return fieldLongSessionAuthenticatedAtEpochMs(request, nowEpochMs);
  }
  if (backendFieldLongSessionIdentity(request, nowEpochMs)) {
    return backendFieldLongSessionAuthenticatedAtEpochMs(request, nowEpochMs);
  }
  const candidate = requestCookies(request)?.get(FIELD_COOKIE) ?? "";
  const shortIdentity = validSessionIdentity(candidate);
  if (!shortIdentity) return null;
  const issuedAtEpochMs = (shortIdentity.expiresAtSeconds - SESSION_MAX_AGE_SECONDS) * 1000;
  return Number.isSafeInteger(issuedAtEpochMs) && issuedAtEpochMs > 0
    ? issuedAtEpochMs
    : null;
}

export function gatewaySessionScope(request: Request): GatewaySessionScope | null {
  return gatewaySessionAuthorization(request)?.sessionScope ?? null;
}

export function isGatewayGeneralSessionAuthorized(request: Request): boolean {
  return gatewaySessionScope(request) === "general";
}

export function gatewaySessionActor(request: Request): string | null {
  return gatewaySessionAuthorization(request)?.actorId ?? null;
}

export function gatewaySessionAccountGeneration(request: Request): number | null {
  return gatewaySessionAuthorization(request)?.accountGeneration ?? null;
}

export function gatewaySessionAuthEpoch(request: Request): number | null {
  return gatewaySessionAuthorization(request)?.authEpoch ?? null;
}

export type GatewayFieldLongSessionBinding = {
  actorId: string;
  accountId: string;
  deviceId: string;
  familyId: string;
  sessionRotation: number;
  accountGeneration?: number | null;
};

export function gatewayFieldLongSessionBinding(
  request: Request
): GatewayFieldLongSessionBinding | null {
  const identity = fieldLongSessionIdentity(request);
  if (identity && isGatewayActorConfigured(identity.actorId)) {
    return {
      actorId: identity.actorId,
      accountId: identity.actorId,
      deviceId: identity.deviceId,
      familyId: identity.familyId,
      sessionRotation: identity.rotation,
      accountGeneration: null
    };
  }
  // A Backend-email long family binds a walk like a v7 device session: the account key and
  // auth epoch stay fixed while the refresh rotation changes on every renewal.
  const backendLong = backendFieldLongSessionIdentity(request);
  if (backendLong) {
    return {
      actorId: backendLong.actorId,
      accountId: `${backendLong.actorId}:generation:${backendLong.binding.accountGeneration}`,
      deviceId: backendLong.deviceId,
      familyId: backendLong.familyId,
      sessionRotation: backendLong.binding.authEpoch,
      accountGeneration: backendLong.binding.accountGeneration
    };
  }
  const candidate = requestCookies(request)?.get(FIELD_COOKIE) ?? "";
  const backendIdentity = validBackendDeviceSessionIdentity(candidate);
  return backendIdentity
    ? {
        actorId: backendIdentity.actorId,
        accountId: `${backendIdentity.actorId}:generation:${backendIdentity.accountGeneration}`,
        deviceId: backendIdentity.deviceId,
        familyId: backendIdentity.sessionId,
        sessionRotation: backendIdentity.authEpoch,
        accountGeneration: backendIdentity.accountGeneration
      }
    : null;
}

export function gatewayFieldLongSessionBindingMatches(
  request: Request,
  expected: GatewayFieldLongSessionBinding
): boolean {
  const current = gatewayFieldLongSessionBinding(request);
  return current !== null &&
    current.actorId === expected.actorId &&
    current.accountId === expected.accountId &&
    current.deviceId === expected.deviceId &&
    current.familyId === expected.familyId &&
    current.sessionRotation === expected.sessionRotation &&
    (current.accountGeneration ?? null) === (expected.accountGeneration ?? null);
}

/** Used by same-origin field telemetry and field API proxies. */
export function isFieldSessionAuthorized(request: Request): boolean {
  return isGatewayGeneralSessionAuthorized(request);
}

export function verifyGatewayCredential(
  actorId: string,
  candidate: string
): string | null {
  if (!legacyGatewayAccessConfigured()) return null;
  const normalizedActor = actorId.trim() || "field-shared";
  const account = configuredAccounts().find((entry) => entry.actorId === normalizedActor);
  return account && constantTimeEqual(candidate.trim(), account.token) ? account.actorId : null;
}

export async function gatewayLoginRateLimitResponse(
  request: Request
): Promise<Response | null> {
  if (!loginClientKey(request)) {
    return Response.json(
      { code: "gateway_trusted_client_ip_required", message: "신뢰할 수 있는 접속 주소를 확인할 수 없습니다." },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  const now = Date.now();
  const directory = await secureLoginAttemptDirectory();
  await pruneExpiredLoginAttemptFiles(directory, now);
  const clientPath = loginAttemptPath(directory, request);
  if (await loginClientFileCapacityReached(directory, clientPath)) {
    return Response.json(
      { code: "gateway_login_capacity_unavailable", message: "인증 시도 저장 한도에 도달했습니다." },
      {
        status: 503,
        headers: { "cache-control": "no-store", "retry-after": "60" }
      }
    );
  }
  const [clientAttempts, globalFailures, totalAttempts] = await Promise.all([
    readLoginAttempts(clientPath),
    readLoginAttempts(globalLoginFailurePath(directory)),
    readLoginAttempts(globalLoginAttemptPath(directory))
  ]);
  // Legacy global timestamps are requests, not confirmed credential failures.
  // Legacy client timestamps retain their conservative IP bound until expiry.
  const clientBlockedUntil = blockedUntilFromAttempts(
    recentLoginAttempts(clientAttempts, now),
    LOGIN_CLIENT_ATTEMPT_LIMIT,
    now
  );
  const globalBlockedUntil = blockedUntilFromAttempts(
    recentLoginAttempts(globalFailures, now),
    LOGIN_GLOBAL_ATTEMPT_LIMIT,
    now
  );
  const blockedUntil = Math.max(clientBlockedUntil, globalBlockedUntil);
  if (blockedUntil > now) {
    const retryAfterSeconds = Math.max(1, Math.ceil((blockedUntil - now) / 1000));
    return Response.json(
      {
        code: globalBlockedUntil >= clientBlockedUntil
          ? "gateway_login_global_rate_limited"
          : "gateway_login_rate_limited",
        message: "인증 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."
      },
      {
        status: 429,
        headers: {
          "cache-control": "no-store",
          "retry-after": String(retryAfterSeconds)
        }
      }
    );
  }
  const recentTotal = totalAttempts.filter(
    (attemptAt) => attemptAt > now - LOGIN_ATTEMPT_WINDOW_MS && attemptAt <= now
  );
  if (recentTotal.length < LOGIN_TOTAL_ATTEMPT_LIMIT) return null;
  return Response.json(
    {
      code: "gateway_login_total_rate_limited",
      message: "인증 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."
    },
    {
      status: 429,
      headers: {
        "cache-control": "no-store",
        "retry-after": String(Math.max(1, Math.ceil(
          (recentTotal[recentTotal.length - LOGIN_TOTAL_ATTEMPT_LIMIT]! + LOGIN_ATTEMPT_WINDOW_MS - now) / 1000
        )))
      }
    }
  );
}

export async function recordGatewayLoginAttempt(request: Request): Promise<void> {
  const now = Date.now();
  const directory = await secureLoginAttemptDirectory();
  await pruneExpiredLoginAttemptFiles(directory, now);
  const clientPath = loginAttemptPath(directory, request);
  if (await loginClientFileCapacityReached(directory, clientPath)) {
    throw new Error("gateway rate-limit client file capacity reached");
  }
  const globalPath = globalLoginAttemptPath(directory);
  const globalAttempts = await readLoginAttempts(globalPath);
  await writeLoginAttempts(globalPath, [...recentLoginAttempts(globalAttempts, now), now]);
}

export async function recordGatewayLoginFailure(request: Request): Promise<void> {
  const now = Date.now();
  const directory = await secureLoginAttemptDirectory();
  await pruneExpiredLoginAttemptFiles(directory, now);
  const clientPath = loginAttemptPath(directory, request);
  if (await loginClientFileCapacityReached(directory, clientPath)) {
    throw new Error("gateway rate-limit client file capacity reached");
  }
  const globalPath = globalLoginFailurePath(directory);
  const [clientAttempts, globalFailures] = await Promise.all([
    readLoginAttempts(clientPath),
    readLoginAttempts(globalPath)
  ]);
  await writeLoginAttempts(clientPath, [...recentLoginAttempts(clientAttempts, now), now]);
  await writeLoginAttempts(globalPath, [...recentLoginAttempts(globalFailures, now), now]);
}

export function gatewayLoginBusyResponse(): Response {
  return Response.json(
    { code: "gateway_login_busy", message: "인증 처리 용량이 사용 중입니다. 잠시 후 다시 시도해 주세요." },
    {
      status: 503,
      headers: { "cache-control": "no-store", "retry-after": "1" }
    }
  );
}

export function establishGatewaySession(
  request: Request,
  actorId = "field-shared",
  sessionScope: GatewaySessionScope = "general"
): Response {
  const account = configuredAccounts().find((entry) => entry.actorId === actorId);
  if (!account || !isGatewayAccessConfigured()) return gatewayUnavailableResponse();
  const expiresAtSeconds = Math.floor(Date.now() / 1000) + SESSION_MAX_AGE_SECONDS;
  const sessionId = randomBytes(32).toString("base64url");
  const value = sessionValue(account, expiresAtSeconds, sessionId, sessionScope);
  if (!value) return gatewayUnavailableResponse();
  try {
    writeActiveSession({ actorId: account.actorId, sessionId, expiresAtSeconds, sessionScope });
  } catch (error) {
    // S19-27: the session state may be durable; the client logs in again.
    if (error instanceof GatewayStorageOutcomeUnknownError) {
      return fieldLongSessionStorageOutcomeUnknownResponse();
    }
    return error instanceof ExclusiveFileLockBusyError
      ? gatewayLoginBusyResponse()
      : gatewayUnavailableResponse();
  }
  return Response.json({ session_scope: sessionScope }, {
    status: 200,
    headers: {
      "cache-control": "no-store",
      "set-cookie": cookieHeader(value, request, SESSION_MAX_AGE_SECONDS)
    }
  });
}

export function establishBackendGatewaySession(
  request: Request,
  binding: BackendAccountSessionBinding,
  rememberMe: boolean,
  sessionScope: GatewaySessionScope = "general"
): Response {
  if (
    !validGatewaySessionScope(sessionScope) ||
    (sessionScope === "account_deletion_recovery" &&
      (rememberMe !== false || binding.deviceId != null)) ||
    !ACTOR_ID.test(binding.actorId) ||
    !Number.isSafeInteger(binding.accountGeneration) ||
    binding.accountGeneration < 1 ||
    !Number.isSafeInteger(binding.authEpoch) ||
    binding.authEpoch < 1 ||
    (binding.deviceId !== undefined && binding.deviceId !== null &&
      !DEVICE_ID.test(binding.deviceId)) ||
    !backendAccountSessionsConfigured()
  ) return gatewayUnavailableResponse();
  const expiresAtSeconds = Math.floor(Date.now() / 1000) + SESSION_MAX_AGE_SECONDS;
  const sessionId = randomBytes(32).toString("base64url");
  const value = backendAccountSessionValue(
    binding,
    expiresAtSeconds,
    sessionId,
    sessionScope
  );
  if (!value) return gatewayUnavailableResponse();
  try {
    const common = {
      actorId: binding.actorId,
      accountGeneration: binding.accountGeneration,
      authEpoch: binding.authEpoch,
      expiresAtSeconds,
      sessionId,
      sessionScope
    };
    writeActiveSession(binding.deviceId
      ? {
          ...common,
          deviceId: binding.deviceId,
          sessionKind: "backend_account_device"
        }
      : {
          ...common,
          sessionKind: "backend_account"
        });
  } catch (error) {
    if (error instanceof GatewaySessionRollbackError) {
      return Response.json(
        {
          detail: {
            code: "account_authentication_stale",
            message: "Authentication state changed while login was in progress. Retry login."
          }
        },
        { status: 409, headers: { "cache-control": "no-store" } }
      );
    }
    if (error instanceof GatewaySessionDeviceCapacityError) {
      return Response.json(
        { code: "field_session_device_capacity_unavailable" },
        { status: 503, headers: { "cache-control": "no-store" } }
      );
    }
    return error instanceof ExclusiveFileLockBusyError
      ? gatewayLoginBusyResponse()
      : gatewaySessionStorageFailureResponse(error);
  }
  return Response.json({ session_scope: sessionScope }, {
    status: 200,
    headers: {
      "cache-control": "no-store",
      "set-cookie": cookieHeader(
        value,
        request,
        rememberMe ? SESSION_MAX_AGE_SECONDS : null
      )
    }
  });
}

function gatewaySessionStorageFailureResponse(error: unknown): Response {
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
  // S19-27: the session may already be committed; the client logs in again.
  if (error instanceof GatewayStorageOutcomeUnknownError) {
    return fieldLongSessionStorageOutcomeUnknownResponse();
  }
  // S19-11: nothing was committed, so the request can be retried unchanged.
  return Response.json(
    {
      code: "field_session_storage_unavailable",
      message: "현장 세션 상태를 안전하게 저장할 수 없습니다.",
      retryable: true
    },
    {
      status: 503,
      headers: { "cache-control": "no-store", "retry-after": "1" }
    }
  );
}

export function listBackendDeviceGatewaySessionDevices(
  request: Request,
  nowEpochMs = Date.now()
): Response | null {
  const candidate = requestCookies(request)?.get(FIELD_COOKIE) ?? "";
  if (!candidate.startsWith("v7.")) return null;
  const nowSeconds = Math.floor(nowEpochMs / 1000);
  const identity = validBackendDeviceSessionIdentity(candidate, nowSeconds);
  if (!identity) return gatewayUnauthorizedResponse();
  const target = sessionStatePath(identity.actorId);
  try {
    return withExclusiveFileLock(`${target}.lock`, () => {
      const current = validBackendDeviceSessionIdentity(candidate, nowSeconds);
      if (!current) return gatewayUnauthorizedResponse();
      const state = readActiveSession(current.actorId);
      const sessions = state && isBackendDeviceSessionLedger(state)
        ? state.sessions
        : state && isBackendSessionIdentity(state) &&
          state.sessionKind === "backend_account_device"
          ? [state]
          : [];
      const devices = sessions
        .filter((session) =>
          session.accountGeneration === current.accountGeneration &&
          session.authEpoch === current.authEpoch &&
          session.expiresAtSeconds > nowSeconds &&
          session.expiresAtSeconds <= nowSeconds + SESSION_MAX_AGE_SECONDS
        )
        .sort((left, right) => left.deviceId.localeCompare(right.deviceId))
        .map((session) => {
          const createdAtEpochMs =
            (session.expiresAtSeconds - SESSION_MAX_AGE_SECONDS) * 1000;
          const expiresAtEpochMs = session.expiresAtSeconds * 1000;
          return {
            device_id: session.deviceId,
            family_id: session.sessionId,
            current: session.sessionId === current.sessionId,
            rotation: 0,
            created_at_epoch_ms: createdAtEpochMs,
            last_rotated_at_epoch_ms: createdAtEpochMs,
            idle_expires_at_epoch_ms: expiresAtEpochMs,
            absolute_expires_at_epoch_ms: expiresAtEpochMs
          };
        });
      return Response.json(
        { actor_id: current.actorId, devices },
        { headers: { "cache-control": "no-store" } }
      );
    });
  } catch (error) {
    return gatewaySessionStorageFailureResponse(error);
  }
}

export function revokeBackendDeviceGatewaySession(
  request: Request,
  deviceId: string,
  nowEpochMs = Date.now()
): Response | null {
  const candidate = requestCookies(request)?.get(FIELD_COOKIE) ?? "";
  if (!candidate.startsWith("v7.")) return null;
  if (!DEVICE_ID.test(deviceId)) {
    return Response.json(
      { code: "field_session_query_invalid" },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  const nowSeconds = Math.floor(nowEpochMs / 1000);
  const identity = validBackendDeviceSessionIdentity(candidate, nowSeconds);
  if (!identity) return gatewayUnauthorizedResponse();
  const target = sessionStatePath(identity.actorId);
  try {
    const response = withExclusiveFileLock(`${target}.lock`, () => {
      const current = validBackendDeviceSessionIdentity(candidate, nowSeconds);
      if (!current) return gatewayUnauthorizedResponse();
      const state = readActiveSession(current.actorId);
      if (state && isBackendDeviceSessionLedger(state)) {
        const sessions = state.sessions.filter(
          (session) => session.deviceId !== deviceId
        );
        if (sessions.length !== state.sessions.length) {
          writeSessionState(
            target,
            backendSessionStateWithSlots(
              state.sessions[0]!,
              sessions,
              state.compatibilitySession
            )
          );
        }
      } else if (
        state &&
        isBackendSessionIdentity(state) &&
        state.sessionKind === "backend_account_device" &&
        state.deviceId === deviceId
      ) {
        writeSessionState(target, backendSessionWatermarkState(state));
      }
      return new Response(null, {
        status: 204,
        headers: { "cache-control": "no-store" }
      });
    });
    if (response.status === 204 && deviceId === identity.deviceId) {
      response.headers.set("set-cookie", cookieHeader("", request, 0));
    }
    return response;
  } catch (error) {
    return gatewaySessionStorageFailureResponse(error);
  }
}

export function clearGatewaySession(request: Request): Response {
  const cookies = requestCookies(request);
  if (!cookies) return gatewayUnauthorizedResponse();
  const candidate = cookies.get(FIELD_COOKIE) ?? "";
  try {
    const identity = validSessionIdentity(candidate);
    if (identity) clearActiveSession(identity);
  } catch (error) {
    return error instanceof ExclusiveFileLockBusyError
      ? gatewayLoginBusyResponse()
      : gatewayUnavailableResponse();
  }
  return new Response(null, {
    status: 204,
    headers: {
      "cache-control": "no-store",
      "set-cookie": cookieHeader("", request, 0)
    }
  });
}

/**
 * Owner decision 1 (2026-09-29, FP-011-DETAIL-02): after a refresh-token reuse revoked every
 * long-session family of the actor, every short device session of the actor ends too.
 * Backend account state keeps its generation/auth-epoch watermark (no login rollback).
 */
export function revokeShortFieldSessionsForActor(actorId: string): void {
  if (!ACTOR_ID.test(actorId)) return;
  const target = sessionStatePath(actorId);
  withExclusiveFileLock(`${target}.lock`, () => {
    const active = readActiveSession(actorId);
    if (!active || isBackendSessionWatermark(active)) return;
    if (isBackendDeviceSessionLedger(active)) {
      writeSessionState(target, backendSessionWatermarkState(active.sessions[0]!));
    } else if (isBackendSessionIdentity(active)) {
      writeSessionState(target, backendSessionWatermarkState(active));
    } else {
      durableUnlinkSync(target);
    }
  });
}

export async function revokeFieldSessionsForSecurityEvent(
  actorId: string,
  reason: "account_lock" | "security_incident",
  nowEpochMs = Date.now()
): Promise<number> {
  let longSessionCount = 0;
  let failure: unknown;
  try {
    longSessionCount = await revokeAllFieldLongSessions(actorId, reason, nowEpochMs);
  } catch (error) {
    failure = error;
  }
  try {
    const target = sessionStatePath(actorId);
    withExclusiveFileLock(`${target}.lock`, () => {
      durableUnlinkSync(target);
    });
  } catch (error) {
    failure ??= error;
  }
  if (failure) throw failure;
  return longSessionCount;
}

export function gatewaySessionStatus(request: Request): Response {
  const required = isGatewayAccessConfigured();
  if (!required && !isInsecureLocalGatewayBypassAllowed()) {
    return gatewayUnavailableResponse();
  }
  const authorization = required ? gatewaySessionAuthorization(request) : null;
  const authenticated = !required || authorization !== null;
  return Response.json(
    {
      required,
      authenticated,
      actor_id: authorization?.actorId ?? null,
      session_scope: authorization?.sessionScope ?? null
    },
    { headers: { "cache-control": "no-store" } }
  );
}

export function gatewayTokenForBackend(): string {
  return configuredToken();
}

export function gatewayUnauthorizedResponse(forbidden = false): Response {
  return Response.json(
    {
      code: forbidden ? "gateway_forbidden" : "gateway_auth_required",
      message: forbidden ? "요청한 권한과 현재 세션 권한이 다릅니다." : "인증이 필요합니다."
    },
    { status: forbidden ? 403 : 401, headers: { "cache-control": "no-store" } }
  );
}

export function gatewayUnavailableResponse(): Response {
  return Response.json(
    { code: "gateway_security_not_configured", message: "현장 테스트 보안 설정을 확인해 주세요." },
    { status: 503, headers: { "cache-control": "no-store" } }
  );
}

export {
  SHORT_SESSION_MAX_PLAINTEXT_BYTES,
  setShortSessionStorageFaultForTests
} from "./short-session-store.js";
export {
  SHORT_DEVICE_SESSION_LIMIT,
  type BackendAccountSessionBinding,
  type GatewaySessionScope,
  validShortSessionStateForMaintenance
} from "./short-session-codec.js";
