import { KeyedAdmissionQueue } from "./keyed-admission-queue.js";
import {
  GatewayStorageOutcomeUnknownError,
  maxGatewayStateEnvelopeBytes,
  decryptGatewayStateJson,
  encryptGatewayStateJson
} from "./encrypted-json-store.js";
import path from "node:path";
import { tmpdir } from "node:os";
import {
  mkdirSync,
  lstatSync,
  realpathSync,
  openSync,
  constants as fsConstants,
  fstatSync,
  readFileSync,
  closeSync,
  writeFileSync,
  fsyncSync,
  chmodSync,
  renameSync,
  rmSync
} from "node:fs";
import { createHash, randomBytes } from "node:crypto";
import { withExclusiveFileLockAsync } from "./exclusive-file-lock.js";
import { type FieldSessionActorState, validActorState } from "./field-long-session-codec.js";

export const FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES = 4 * 1024 * 1024;

export const actorAdmission = new KeyedAdmissionQueue(32, 1_000);

// "auth_time_read": S19-44 auth-time re-read of the family (GWBE-10 tests).
type StorageFaultPoint = "file_fsync" | "rename" | "directory_fsync" | "auth_time_read";

let storageFaultPointForTests: StorageFaultPoint | null = null;

let actorAdmissionHoldForTests: Promise<void> | null = null;

export class FieldSessionStorageOutcomeUnknownError extends GatewayStorageOutcomeUnknownError {
  constructor() {
    super("field long-session");
    this.name = "FieldSessionStorageOutcomeUnknownError";
  }
}

export function setFieldLongSessionStorageFaultForTests(
  point: StorageFaultPoint | null
): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("field long-session storage fault injection is test-only");
  }
  storageFaultPointForTests = point;
}

export function setFieldLongSessionAdmissionHoldForTests(hold: Promise<void> | null): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("field long-session admission hold is test-only");
  }
  actorAdmissionHoldForTests = hold;
}

export function injectStorageFaultForTests(point: StorageFaultPoint): void {
  if (storageFaultPointForTests !== point) return;
  storageFaultPointForTests = null;
  throw new Error(`injected field long-session storage fault: ${point}`);
}

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

export function stateParentDirectory(): string {
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

export function actorLockPath(actorId: string): string {
  return `${statePath(actorId)}.lock`;
}

let actorStateReadsForTests = 0;

/** Tests only (S19-10): how many times an actor state file was opened for reading. */
export function fieldLongSessionStateReadsForTests(): number {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("field long-session read counter is test-only");
  }
  return actorStateReadsForTests;
}

export function readActorState(
  actorId: string,
  missingIsUnavailable = false
): FieldSessionActorState {
  actorStateReadsForTests += 1;
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
    if ((error as NodeJS.ErrnoException).code === "ENOENT" && !missingIsUnavailable) {
      return { schema_version: 1, actor_id: actorId, families: [] };
    }
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

export function writeActorState(state: FieldSessionActorState): void {
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

export function withActorLock<T>(
  actorId: string,
  action: () => Promise<T> | T,
  signal?: AbortSignal
): Promise<T> {
  return actorAdmission.run(
    actorId,
    () => actorAdmissionHoldForTests
      ? actorAdmissionHoldForTests.then(() => withExclusiveFileLockAsync(actorLockPath(actorId), action))
      : withExclusiveFileLockAsync(actorLockPath(actorId), action),
    signal
  );
}

