import {
  mkdirSync,
  lstatSync,
  realpathSync,
  unlinkSync,
  openSync,
  constants as fsConstants,
  fsyncSync,
  closeSync,
  fstatSync,
  readFileSync,
  writeFileSync,
  chmodSync,
  renameSync,
  rmSync
} from "node:fs";
import path from "node:path";
import { createHash, randomBytes } from "node:crypto";
import {
  maxGatewayStateEnvelopeBytes,
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GatewayStorageOutcomeUnknownError
} from "./encrypted-json-store.js";
import { FieldLongSessionStorageUnavailableError } from "./field-long-session.js";
import { withExclusiveFileLock } from "./exclusive-file-lock.js";
import { tmpdir } from "node:os";
import {
  type GatewaySessionState,
  type BackendDeviceGatewaySessionLedger,
  type BackendGatewaySessionWatermark,
  type BackendGatewaySessionIdentity,
  type BackendDeviceGatewaySessionIdentity,
  validShortSessionStateForMaintenance,
  validGatewaySessionScope,
  type BackendAccountSessionBinding,
  SESSION_MAX_AGE_SECONDS,
  type GatewaySessionIdentity,
  GatewaySessionRollbackError,
  SHORT_DEVICE_SESSION_LIMIT,
  GatewaySessionDeviceCapacityError
} from "./short-session-codec.js";

export const SHORT_SESSION_MAX_PLAINTEXT_BYTES = 16 * 1024;

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

export function sessionStatePath(actorId: string): string {
  const digest = createHash("sha256").update(`field\0${actorId}`).digest("hex");
  return path.join(sessionStateDirectory(), `field-${digest}.json`);
}

export function durableUnlinkSync(target: string): void {
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

export function isBackendDeviceSessionLedger(
  state: GatewaySessionState
): state is BackendDeviceGatewaySessionLedger {
  return "sessions" in state;
}

export function isBackendSessionWatermark(
  state: GatewaySessionState
): state is BackendGatewaySessionWatermark {
  return "accountGeneration" in state && !("sessionKind" in state);
}

export function isBackendSessionIdentity(
  state: GatewaySessionState
): state is BackendGatewaySessionIdentity | BackendDeviceGatewaySessionIdentity {
  return "sessionKind" in state && (
    state.sessionKind === "backend_account" ||
    state.sessionKind === "backend_account_device"
  );
}

export function readActiveSession(actorId: string): GatewaySessionState | null {
  let descriptor: number | null = null;
  try {
    const filePath = sessionStatePath(actorId);
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
    ).value;
    if (!validShortSessionStateForMaintenance(decoded, recordId)) return null;
    // Known v3 records may survive a rollout so startup can proceed, but they
    // remain deliberately unauthenticated until a new scope-bound login replaces them.
    const state = decoded as GatewaySessionState;
    if (isBackendDeviceSessionLedger(state) || isBackendSessionWatermark(state)) {
      return state;
    }
    if (!validGatewaySessionScope(state.sessionScope)) return null;
    return state;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    // Unreadable, undecryptable, or insecure state is a storage fault, not an
    // invalid credential; callers map this to the retryable 503 response.
    throw new FieldLongSessionStorageUnavailableError();
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

export function writeSessionState(target: string, state: GatewaySessionState): void {
  const temporary = `${target}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  try {
    const encoded = encryptGatewayStateJson(
      { kind: "short-session", recordId: path.basename(target) },
      state,
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
    // S19-27: after the rename the new session state may be durable; a later failure is
    // reported as outcome unknown, never as the not-committed storage error.
    try {
      injectShortSessionStorageFault("directory_fsync");
      directoryDescriptor = openSync(
        path.dirname(target),
        fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
      );
      fsyncSync(directoryDescriptor);
    } catch (error) {
      throw new GatewayStorageOutcomeUnknownError("short-session", { cause: error });
    }
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    rmSync(temporary, { force: true });
  }
}

let shortSessionStorageFaultForTests: "directory_fsync" | null = null;

/** Tests only (S19-27): injects a failure right after the short-session rename. */
export function setShortSessionStorageFaultForTests(stage: "directory_fsync" | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("short-session storage faults are unavailable in production");
  }
  shortSessionStorageFaultForTests = stage;
}

function injectShortSessionStorageFault(stage: "directory_fsync"): void {
  if (shortSessionStorageFaultForTests === stage) {
    throw new Error(`injected short-session ${stage} failure`);
  }
}

function currentBackendDeviceSessions(
  state: GatewaySessionState | null,
  identity: BackendAccountSessionBinding,
  nowSeconds: number
): BackendDeviceGatewaySessionIdentity[] {
  const sessions = state && isBackendDeviceSessionLedger(state)
    ? state.sessions
    : state && isBackendSessionIdentity(state) &&
      state.sessionKind === "backend_account_device"
      ? [state]
      : [];
  return sessions.filter((session) =>
    session.actorId === identity.actorId &&
    session.accountGeneration === identity.accountGeneration &&
    session.authEpoch === identity.authEpoch &&
    session.expiresAtSeconds > nowSeconds &&
    session.expiresAtSeconds <= nowSeconds + SESSION_MAX_AGE_SECONDS
  );
}

function backendSessionWatermark(
  state: GatewaySessionState | null
): { accountGeneration: number; authEpoch: number } | null {
  if (state && isBackendSessionWatermark(state)) {
    return {
      accountGeneration: state.accountGeneration,
      authEpoch: state.authEpoch
    };
  }
  const session = state && isBackendDeviceSessionLedger(state)
    ? state.sessions[0] ?? null
    : state && isBackendSessionIdentity(state)
      ? state
      : null;
  return session
    ? {
        accountGeneration: session.accountGeneration,
        authEpoch: session.authEpoch
      }
    : null;
}

export function backendSessionWatermarkState(
  state: BackendAccountSessionBinding
): BackendGatewaySessionWatermark {
  return {
    actorId: state.actorId,
    accountGeneration: state.accountGeneration,
    authEpoch: state.authEpoch
  };
}

function currentBackendCompatibilitySession(
  state: GatewaySessionState | null,
  identity: BackendAccountSessionBinding,
  nowSeconds: number
): BackendGatewaySessionIdentity | undefined {
  const session = state && isBackendDeviceSessionLedger(state)
    ? state.compatibilitySession
    : state && isBackendSessionIdentity(state) &&
      state.sessionKind === "backend_account"
      ? state
      : undefined;
  return session &&
    session.actorId === identity.actorId &&
    session.accountGeneration === identity.accountGeneration &&
    session.authEpoch === identity.authEpoch &&
    session.expiresAtSeconds > nowSeconds &&
    session.expiresAtSeconds <= nowSeconds + SESSION_MAX_AGE_SECONDS
    ? session
    : undefined;
}

export function backendSessionStateWithSlots(
  binding: BackendAccountSessionBinding,
  sessions: BackendDeviceGatewaySessionIdentity[],
  compatibilitySession?: BackendGatewaySessionIdentity
): GatewaySessionState {
  if (sessions.length > 0) {
    return compatibilitySession
      ? { actorId: binding.actorId, sessions, compatibilitySession }
      : { actorId: binding.actorId, sessions };
  }
  return compatibilitySession ?? backendSessionWatermarkState(binding);
}

export function writeActiveSession(identity: GatewaySessionIdentity): void {
  const target = sessionStatePath(identity.actorId);
  withExclusiveFileLock(`${target}.lock`, () => {
    const active = "sessionKind" in identity
      ? readActiveSession(identity.actorId)
      : null;
    if ("sessionKind" in identity) {
      const watermark = backendSessionWatermark(active);
      if (watermark && (
        identity.accountGeneration < watermark.accountGeneration ||
        (identity.accountGeneration === watermark.accountGeneration &&
          identity.authEpoch < watermark.authEpoch)
      )) {
        throw new GatewaySessionRollbackError();
      }
      const nowSeconds = Math.floor(Date.now() / 1000);
      let sessions = currentBackendDeviceSessions(active, identity, nowSeconds);
      let compatibilitySession = currentBackendCompatibilitySession(
        active,
        identity,
        nowSeconds
      );
      if (identity.sessionKind === "backend_account_device") {
        sessions = sessions.filter((session) => session.deviceId !== identity.deviceId);
        if (sessions.length >= SHORT_DEVICE_SESSION_LIMIT) {
          throw new GatewaySessionDeviceCapacityError();
        }
        sessions = [...sessions, identity];
      } else {
        compatibilitySession = identity;
      }
      writeSessionState(
        target,
        backendSessionStateWithSlots(identity, sessions, compatibilitySession)
      );
      return;
    }
    writeSessionState(target, identity);
  });
}

export function clearActiveSession(identity: GatewaySessionIdentity): void {
  const target = sessionStatePath(identity.actorId);
  withExclusiveFileLock(`${target}.lock`, () => {
    const active = readActiveSession(identity.actorId);
    if (active && isBackendDeviceSessionLedger(active)) {
      if ("sessionKind" in identity && identity.sessionKind === "backend_account") {
        if (active.compatibilitySession?.sessionId !== identity.sessionId) return;
        writeSessionState(
          target,
          backendSessionStateWithSlots(active.sessions[0]!, active.sessions)
        );
        return;
      }
      if (!("sessionKind" in identity) ||
        identity.sessionKind !== "backend_account_device"
      ) return;
      const sessions = active.sessions.filter(
        (session) => session.sessionId !== identity.sessionId
      );
      if (sessions.length === active.sessions.length) return;
      writeSessionState(
        target,
        backendSessionStateWithSlots(
          active.sessions[0]!,
          sessions,
          active.compatibilitySession
        )
      );
      return;
    }
    if (active && isBackendSessionWatermark(active)) return;
    if (active?.sessionId === identity.sessionId) {
      if (isBackendSessionIdentity(active)) {
        writeSessionState(target, backendSessionWatermarkState(active));
      } else {
        durableUnlinkSync(target);
      }
    }
  });
}

export function loginAttemptDirectory(): string {
  const configured = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim() ?? "";
  if (process.env.NODE_ENV === "production" && !configured) {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  const directory = configured || path.join(tmpdir(), "walksafe-gateway-rate-limit-v1");
  if (!path.isAbsolute(directory)) throw new Error("gateway rate-limit directory must be absolute");
  return path.resolve(directory);
}

export function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

