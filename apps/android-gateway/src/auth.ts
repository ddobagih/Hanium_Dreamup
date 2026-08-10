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
  unlinkSync,
  writeFileSync
} from "node:fs";
import { lstat, mkdir, open, readdir, realpath, rename, rm } from "node:fs/promises";
import { isIP } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";

import {
  fieldLongSessionIdentity,
  revokeAllFieldLongSessions
} from "./field-long-session.js";
import {
  ExclusiveFileLockBusyError,
  withExclusiveFileLock,
  withExclusiveFileLockAsync
} from "./exclusive-file-lock.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  maxGatewayStateEnvelopeBytes
} from "./encrypted-json-store.js";

const FIELD_COOKIE = "walksafe_field_session";
const SESSION_MAX_AGE_SECONDS = 12 * 60 * 60;
const MIN_TOKEN_LENGTH = 24;
const LOGIN_ATTEMPT_WINDOW_MS = 5 * 60 * 1000;
const LOGIN_BLOCK_MS = 15 * 60 * 1000;
const LOGIN_CLIENT_ATTEMPT_LIMIT = 5;
const LOGIN_GLOBAL_ATTEMPT_LIMIT = 120;
const LOGIN_CLIENT_FILE_LIMIT = 256;
const LOGIN_LOCK_WAIT_MS = 1000;
const LOGIN_ATTEMPT_RETENTION_MS = LOGIN_ATTEMPT_WINDOW_MS + LOGIN_BLOCK_MS;
const LOGIN_CLIENT_ATTEMPT_FILE = /^field-[0-9a-f]{64}\.log$/;
const LOGIN_GLOBAL_ATTEMPT_FILE = "global-attempts.log";
const ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;
const RESERVED_ACTOR_IDS = new Set(["unknown", "system", "anonymous"]);
const gatewayLoginLocks = new Map<string, Promise<void>>();
export const SHORT_SESSION_MAX_PLAINTEXT_BYTES = 16 * 1024;

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

export type GatewaySessionScope = "general" | "account_deletion_recovery";

type GatewaySessionIdentity = {
  actorId: string;
  sessionId: string;
  expiresAtSeconds: number;
  sessionScope: GatewaySessionScope;
};

function validGatewaySessionScope(value: unknown): value is GatewaySessionScope {
  return value === "general" || value === "account_deletion_recovery";
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

function secureDirectorySync(directory: string, context: string): string {
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

function sessionStateDirectory(): string {
  const parent = secureDirectorySync(loginAttemptDirectory(), "gateway state directory");
  return secureDirectorySync(path.join(parent, "sessions"), "gateway session directory");
}

function sessionStatePath(actorId: string): string {
  const digest = createHash("sha256").update(`field\0${actorId}`).digest("hex");
  return path.join(sessionStateDirectory(), `field-${digest}.json`);
}

function durableUnlinkSync(target: string): void {
  try {
    unlinkSync(target);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
    throw error;
  }
  let directoryDescriptor: number | null = null;
  try {
    directoryDescriptor = openSync(
      path.dirname(target),
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
    );
    fsyncSync(directoryDescriptor);
  } finally {
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
  }
}

function readActiveSession(actorId: string): GatewaySessionIdentity | null {
  const filePath = sessionStatePath(actorId);
  let descriptor: number | null = null;
  try {
    descriptor = openSync(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size > maxGatewayStateEnvelopeBytes(SHORT_SESSION_MAX_PLAINTEXT_BYTES)
    ) {
      throw new Error("gateway session state must be an owned regular file with mode 0600");
    }
    const recordId = path.basename(filePath);
    const decoded = decryptGatewayStateJson(
      { kind: "short-session", recordId },
      readFileSync(descriptor, "utf8"),
      SHORT_SESSION_MAX_PLAINTEXT_BYTES
    ).value as Partial<GatewaySessionIdentity>;
    if (!validShortSessionStateForMaintenance(decoded, recordId)) return null;
    // Known v3 records may survive a rollout so startup can proceed, but they
    // remain deliberately unauthenticated until a new scope-bound login replaces them.
    if (!validGatewaySessionScope(decoded.sessionScope)) return null;
    return decoded as GatewaySessionIdentity;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

export function validShortSessionStateForMaintenance(
  value: unknown,
  recordId: string
): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const state = value as Partial<GatewaySessionIdentity>;
  const keys = Object.keys(value).sort().join("\0");
  const legacyKeys = ["actorId", "expiresAtSeconds", "sessionId"].sort().join("\0");
  const scopedKeys = ["actorId", "expiresAtSeconds", "sessionId", "sessionScope"]
    .sort()
    .join("\0");
  const legacyV3State = keys === legacyKeys;
  if (
    (!legacyV3State && keys !== scopedKeys) ||
    typeof state.actorId !== "string" ||
    !ACTOR_ID.test(state.actorId) ||
    typeof state.sessionId !== "string" ||
    !/^[A-Za-z0-9_-]{32,128}$/.test(state.sessionId) ||
    (!legacyV3State && !validGatewaySessionScope(state.sessionScope)) ||
    !Number.isSafeInteger(state.expiresAtSeconds) ||
    Number(state.expiresAtSeconds) <= 0
  ) {
    return false;
  }
  const digest = createHash("sha256")
    .update(`field\0${state.actorId}`)
    .digest("hex");
  return recordId === `field-${digest}.json`;
}

function writeActiveSession(identity: GatewaySessionIdentity): void {
  const target = sessionStatePath(identity.actorId);
  withExclusiveFileLock(`${target}.lock`, () => {
    const temporary = `${target}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
    let descriptor: number | null = null;
    let directoryDescriptor: number | null = null;
    try {
      const encoded = encryptGatewayStateJson(
        { kind: "short-session", recordId: path.basename(target) },
        identity,
        SHORT_SESSION_MAX_PLAINTEXT_BYTES
      );
      descriptor = openSync(
        temporary,
        fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
        0o600
      );
      writeFileSync(descriptor, encoded, "utf8");
      fsyncSync(descriptor);
      closeSync(descriptor);
      descriptor = null;
      chmodSync(temporary, 0o600);
      renameSync(temporary, target);
      directoryDescriptor = openSync(
        path.dirname(target),
        fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
      );
      fsyncSync(directoryDescriptor);
    } finally {
      if (descriptor !== null) closeSync(descriptor);
      if (directoryDescriptor !== null) closeSync(directoryDescriptor);
      rmSync(temporary, { force: true });
    }
  });
}

function clearActiveSession(identity: GatewaySessionIdentity): void {
  const target = sessionStatePath(identity.actorId);
  withExclusiveFileLock(`${target}.lock`, () => {
    const active = readActiveSession(identity.actorId);
    if (active?.sessionId === identity.sessionId) {
      durableUnlinkSync(target);
    }
  });
}

function validSessionIdentity(candidate: string): GatewaySessionIdentity | null {
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
  if (!Number.isInteger(expiresAtSeconds) || expiresAtSeconds < nowSeconds) return null;
  if (expiresAtSeconds > nowSeconds + SESSION_MAX_AGE_SECONDS) return null;
  const expected = sessionValue(account, expiresAtSeconds, sessionId, sessionScope);
  if (!expected || !constantTimeEqual(candidate, expected)) return null;
  const active = readActiveSession(actorId);
  if (
    !active ||
    active.actorId !== actorId ||
    active.sessionId !== sessionId ||
    active.expiresAtSeconds !== expiresAtSeconds ||
    active.sessionScope !== sessionScope
  ) {
    return null;
  }
  return { actorId, sessionId, expiresAtSeconds, sessionScope };
}

function requestCookies(request: Request): Map<string, string> {
  const cookies = new Map<string, string>();
  for (const part of (request.headers.get("cookie") ?? "").split(";")) {
    const separator = part.indexOf("=");
    if (separator <= 0) continue;
    const name = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (name) cookies.set(name, value);
  }
  return cookies;
}

function secureRequest(request: Request): boolean {
  const forwardedProtocol = request.headers.get("x-forwarded-proto")?.split(",", 1)[0]?.trim().toLowerCase();
  return forwardedProtocol === "https" || new URL(request.url).protocol === "https:";
}

function loginClientKey(request: Request): string | null {
  const configuredHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER?.trim().toLowerCase() ?? "";
  const trustedHeader = new Set(["cf-connecting-ip", "x-real-ip"]).has(configuredHeader)
    ? configuredHeader
    : null;
  if (!trustedHeader) return null;
  const rawAddress = request.headers.get(trustedHeader);
  if (!rawAddress || rawAddress !== rawAddress.trim() || rawAddress.includes(",") || isIP(rawAddress) === 0) {
    return null;
  }
  const address = rawAddress;
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

function loginAttemptDirectory(): string {
  const configured = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim() ?? "";
  if (process.env.NODE_ENV === "production" && !configured) {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  const directory = configured || path.join(tmpdir(), "walksafe-gateway-rate-limit-v1");
  if (!path.isAbsolute(directory)) throw new Error("gateway rate-limit directory must be absolute");
  return path.resolve(directory);
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

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
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
    if (!LOGIN_CLIENT_ATTEMPT_FILE.test(entry.name) && entry.name !== LOGIN_GLOBAL_ATTEMPT_FILE) continue;
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
      return Array.from({ length: LOGIN_GLOBAL_ATTEMPT_LIMIT }, () => Date.now());
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
    await handle.close();
    handle = null;
    await rename(temporary, filePath);
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

export function isGatewayAccessConfigured(): boolean {
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

export function isGatewayActorConfigured(actorId: string): boolean {
  return (
    isGatewayAccessConfigured() &&
    configuredAccounts().some((account) => account.actorId === actorId)
  );
}

export function isInsecureLocalGatewayBypassAllowed(): boolean {
  return process.env.NODE_ENV !== "production" && process.env.WALKSAFE_ALLOW_INSECURE_LOCAL_DEV === "true";
}

type GatewaySessionAuthorization = {
  actorId: string;
  sessionScope: GatewaySessionScope;
};

function gatewaySessionAuthorization(request: Request): GatewaySessionAuthorization | null {
  const identity = fieldLongSessionIdentity(request);
  if (identity && isGatewayActorConfigured(identity.actorId)) {
    return { actorId: identity.actorId, sessionScope: "general" };
  }
  const candidate = requestCookies(request).get(FIELD_COOKIE) ?? "";
  const shortIdentity = validSessionIdentity(candidate);
  return shortIdentity
    ? { actorId: shortIdentity.actorId, sessionScope: shortIdentity.sessionScope }
    : null;
}

export function isGatewaySessionAuthorized(request: Request): boolean {
  return gatewaySessionAuthorization(request) !== null;
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

export type GatewayFieldLongSessionBinding = {
  actorId: string;
  accountId: string;
  deviceId: string;
  familyId: string;
  sessionRotation: number;
};

export function gatewayFieldLongSessionBinding(
  request: Request
): GatewayFieldLongSessionBinding | null {
  const identity = fieldLongSessionIdentity(request);
  if (!identity || !isGatewayActorConfigured(identity.actorId)) return null;
  return {
    actorId: identity.actorId,
    accountId: identity.actorId,
    deviceId: identity.deviceId,
    familyId: identity.familyId,
    sessionRotation: identity.rotation
  };
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
    current.sessionRotation === expected.sessionRotation;
}

/** Used by same-origin field telemetry and field API proxies. */
export function isFieldSessionAuthorized(request: Request): boolean {
  return isGatewayGeneralSessionAuthorized(request);
}

export function verifyGatewayCredential(
  actorId: string,
  candidate: string
): string | null {
  if (!isGatewayAccessConfigured()) return null;
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
  const [clientAttempts, globalAttempts] = await Promise.all([
    readLoginAttempts(clientPath),
    readLoginAttempts(globalLoginAttemptPath(directory))
  ]);
  const clientBlockedUntil = blockedUntilFromAttempts(
    recentLoginAttempts(clientAttempts, now),
    LOGIN_CLIENT_ATTEMPT_LIMIT,
    now
  );
  const globalBlockedUntil = blockedUntilFromAttempts(
    recentLoginAttempts(globalAttempts, now),
    LOGIN_GLOBAL_ATTEMPT_LIMIT,
    now
  );
  const blockedUntil = Math.max(clientBlockedUntil, globalBlockedUntil);
  if (blockedUntil <= now) return null;
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

export async function recordGatewayLoginAttempt(request: Request): Promise<void> {
  const now = Date.now();
  const directory = await secureLoginAttemptDirectory();
  await pruneExpiredLoginAttemptFiles(directory, now);
  const clientPath = loginAttemptPath(directory, request);
  if (await loginClientFileCapacityReached(directory, clientPath)) {
    throw new Error("gateway rate-limit client file capacity reached");
  }
  const globalPath = globalLoginAttemptPath(directory);
  const [clientAttempts, globalAttempts] = await Promise.all([
    readLoginAttempts(clientPath),
    readLoginAttempts(globalPath)
  ]);
  await writeLoginAttempts(globalPath, [...recentLoginAttempts(globalAttempts, now), now]);
  await writeLoginAttempts(clientPath, [...recentLoginAttempts(clientAttempts, now), now]);
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

export function clearGatewaySession(request: Request): Response {
  const candidate = requestCookies(request).get(FIELD_COOKIE) ?? "";
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
