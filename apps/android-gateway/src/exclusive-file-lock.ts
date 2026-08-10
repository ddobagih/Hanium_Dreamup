import { spawnSync } from "node:child_process";
import {
  closeSync,
  constants as fsConstants,
  fstatSync,
  lstatSync,
  mkdirSync,
  openSync,
  realpathSync
} from "node:fs";
import path from "node:path";

const FLOCK_HELPER = "/usr/bin/flock";
const FLOCK_CHILD_DESCRIPTOR = "3";
const FLOCK_HELPER_TIMEOUT_MS = 1_000;

type LockIdentity = {
  device: bigint;
  inode: bigint;
};

type HeldLock = {
  descriptor: number;
  lockPath: string;
  identity: LockIdentity;
};

export type SharedFileLockLease = {
  release: () => void;
};

type SynchronousAction<Action extends () => unknown> =
  Extract<ReturnType<Action>, PromiseLike<unknown>> extends never
    ? Action
    : never;

export class ExclusiveFileLockBusyError extends Error {
  readonly code = "exclusive_file_lock_busy";

  constructor(readonly lockPath: string) {
    super(`exclusive file lock is busy: ${lockPath}`);
    this.name = "ExclusiveFileLockBusyError";
  }
}

export class ExclusiveFileLockIntegrityError extends Error {
  readonly code = "exclusive_file_lock_integrity_error";

  constructor(message: string) {
    super(message);
    this.name = "ExclusiveFileLockIntegrityError";
  }
}

function closeWithoutMasking(descriptor: number): void {
  try {
    closeSync(descriptor);
  } catch {
    // Preserve the integrity or acquisition error already being handled.
  }
}

function ownedByCurrentUser(uid: bigint): boolean {
  return (
    typeof process.getuid === "function" &&
    uid === BigInt(process.getuid())
  );
}

function secureParentDirectory(lockPath: string): void {
  const directory = path.dirname(lockPath);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const metadata = lstatSync(directory, { bigint: true });
  if (
    !metadata.isDirectory() ||
    metadata.isSymbolicLink() ||
    realpathSync(directory) !== directory ||
    !ownedByCurrentUser(metadata.uid) ||
    (metadata.mode & 0o777n) !== 0o700n
  ) {
    throw new ExclusiveFileLockIntegrityError(
      "exclusive file lock directory must be an owned real directory with mode 0700"
    );
  }
}

function validateLockFileMetadata(
  metadata: ReturnType<typeof fstatSync> & { dev: bigint; ino: bigint; mode: bigint; nlink: bigint; uid: bigint }
): void {
  if (
    !metadata.isFile() ||
    metadata.isSymbolicLink() ||
    !ownedByCurrentUser(metadata.uid) ||
    metadata.nlink !== 1n ||
    (metadata.mode & 0o777n) !== 0o600n
  ) {
    throw new ExclusiveFileLockIntegrityError(
      "exclusive file lock must be an owned regular file with mode 0600 and one link"
    );
  }
}

function verifiedLockIdentity(
  lockPath: string,
  descriptor: number,
  expected?: LockIdentity
): LockIdentity {
  const opened = fstatSync(descriptor, { bigint: true });
  const pathname = lstatSync(lockPath, { bigint: true });
  validateLockFileMetadata(opened);
  validateLockFileMetadata(pathname);
  if (
    opened.dev !== pathname.dev ||
    opened.ino !== pathname.ino ||
    (expected !== undefined &&
      (opened.dev !== expected.device || opened.ino !== expected.inode))
  ) {
    throw new ExclusiveFileLockIntegrityError(
      "exclusive file lock path does not reference the held inode"
    );
  }
  return { device: opened.dev, inode: opened.ino };
}

function acquireKernelLock(
  descriptor: number,
  lockPath: string,
  mode: "exclusive" | "shared"
): void {
  const flock = spawnSync(
    FLOCK_HELPER,
    [mode === "exclusive" ? "--exclusive" : "--shared", "--nonblock", FLOCK_CHILD_DESCRIPTOR],
    {
      stdio: ["ignore", "ignore", "ignore", descriptor],
      timeout: FLOCK_HELPER_TIMEOUT_MS,
      killSignal: "SIGKILL"
    }
  );
  if (flock.error) {
    throw new ExclusiveFileLockIntegrityError(
      "required /usr/bin/flock lock helper is unavailable or failed"
    );
  }
  if (flock.status === 1) {
    throw new ExclusiveFileLockBusyError(lockPath);
  }
  if (flock.status !== 0 || flock.signal !== null) {
    throw new ExclusiveFileLockIntegrityError(
      "exclusive file flock acquisition failed"
    );
  }
}

function acquireFileLock(
  rawLockPath: string,
  mode: "exclusive" | "shared"
): HeldLock {
  const lockPath = path.resolve(rawLockPath);
  let descriptor: number | null = null;
  try {
    // Boundary: one Linux host and an owned local filesystem. Multi-host,
    // network-filesystem, and writers that ignore advisory locks are NOT_COVERED.
    if (process.platform !== "linux") {
      throw new ExclusiveFileLockIntegrityError(
        "exclusive file locks require a single Linux host and local filesystem"
      );
    }
    secureParentDirectory(lockPath);
    descriptor = openSync(
      lockPath,
      fsConstants.O_CREAT |
        fsConstants.O_RDWR |
        fsConstants.O_NOFOLLOW,
      0o600
    );
    const identity = verifiedLockIdentity(lockPath, descriptor);
    acquireKernelLock(descriptor, lockPath, mode);
    verifiedLockIdentity(lockPath, descriptor, identity);
    const held = { descriptor, lockPath, identity };
    descriptor = null;
    return held;
  } catch (error) {
    if (
      error instanceof ExclusiveFileLockBusyError ||
      error instanceof ExclusiveFileLockIntegrityError
    ) {
      throw error;
    }
    throw new ExclusiveFileLockIntegrityError(
      "exclusive file lock storage validation failed"
    );
  } finally {
    if (descriptor !== null) closeWithoutMasking(descriptor);
  }
}

function acquireExclusiveFileLock(rawLockPath: string): HeldLock {
  return acquireFileLock(rawLockPath, "exclusive");
}

function releaseFileLock(lock: HeldLock): void {
  let failure: unknown;
  try {
    verifiedLockIdentity(lock.lockPath, lock.descriptor, lock.identity);
  } catch (error) {
    failure =
      error instanceof ExclusiveFileLockIntegrityError
        ? error
        : new ExclusiveFileLockIntegrityError(
            "exclusive file lock path validation failed during release"
          );
  }
  try {
    closeSync(lock.descriptor);
  } catch (error) {
    failure ??= error;
  }
  if (failure) throw failure;
}

export function acquireSharedFileLock(rawLockPath: string): SharedFileLockLease {
  const lock = acquireFileLock(rawLockPath, "shared");
  let released = false;
  return {
    release: () => {
      if (released) return;
      released = true;
      releaseFileLock(lock);
    }
  };
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (
    ((typeof value === "object" && value !== null) ||
      typeof value === "function") &&
    typeof (value as { then?: unknown }).then === "function"
  );
}

function releaseRejectedSyncThenableWhenSettled(
  lock: HeldLock,
  thenable: PromiseLike<unknown>
): void {
  const release = (): void => {
    try {
      releaseFileLock(lock);
    } catch {
      // The synchronous caller already received an integrity error and there is
      // no asynchronous result channel. releaseFileLock still closes
      // the descriptor before reporting a path-integrity failure.
    }
  };
  void Promise.resolve(thenable).then(release, release);
}

export function withExclusiveFileLock<Action extends () => unknown>(
  lockPath: string,
  action: SynchronousAction<Action>
): ReturnType<Action> {
  const lock = acquireExclusiveFileLock(lockPath);
  let releaseSynchronously = true;
  try {
    const result = action() as ReturnType<Action>;
    if (isThenable(result)) {
      releaseRejectedSyncThenableWhenSettled(lock, result);
      releaseSynchronously = false;
      throw new ExclusiveFileLockIntegrityError(
        "withExclusiveFileLock action returned a Promise or thenable; use withExclusiveFileLockAsync"
      );
    }
    return result;
  } finally {
    if (releaseSynchronously) releaseFileLock(lock);
  }
}

export async function withExclusiveFileLockAsync<T>(
  lockPath: string,
  action: () => Promise<T> | T
): Promise<T> {
  const lock = acquireExclusiveFileLock(lockPath);
  try {
    return await action();
  } finally {
    releaseFileLock(lock);
  }
}
