import path from "node:path";
import { tmpdir } from "node:os";
import {
  mkdirSync,
  lstatSync,
  chmodSync,
  openSync,
  constants as fsConstants,
  fstatSync,
  readFileSync,
  closeSync,
  writeFileSync,
  fsyncSync,
  renameSync,
  rmSync,
  writeSync
} from "node:fs";
import {
  decryptGatewayStateJson,
  maxGatewayStateEnvelopeBytes,
  encryptGatewayStateJson,
  syncGatewayStateDirectoryAfterRename
} from "./encrypted-json-store.js";
import { requiredGatewayStateFileWasLost, registerRequiredGatewayStateFile } from "./state-init-marker.js";
import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  type FileLockLease,
  acquireExclusiveFileLockLeaseAsync,
  ExclusiveFileLockBusyError
} from "./exclusive-file-lock.js";
import {
  PrivacyRightsLedgerError,
  type PrivacyLedger,
  LEDGER_SCHEMA_VERSION,
  validLedger
} from "./privacy-rights-codec.js";

export const PRIVACY_LEDGER_MAX_BYTES = 16 * 1024 * 1024;

const LOCK_WAIT_MS = 1_000;

/** S19-25: non-blocking retry backoff for the asynchronous ledger lock (total LOCK_WAIT_MS). */
const LOCK_RETRY_INITIAL_MS = 10;

const LOCK_RETRY_MAX_MS = 200;

const sleepArray = new Int32Array(new SharedArrayBuffer(4));

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || process.getuid() === uid;
}

function stateDirectory(): string {
  const root = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim()
    || path.join(tmpdir(), "walksafe-android-gateway");
  return path.join(root, "privacy-rights");
}

export function ledgerPath(): string {
  return path.join(stateDirectory(), "ledger.json");
}

function ensureDirectory(): void {
  const directory = stateDirectory();
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const metadata = lstatSync(directory);
  if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger directory is unsafe");
  }
  if ((metadata.mode & 0o077) !== 0) chmodSync(directory, 0o700);
}

function emptyLedger(): PrivacyLedger {
  return {
    schema_version: LEDGER_SCHEMA_VERSION,
    actors: {},
    requests: {},
    events: []
  };
}

function readSecureFile(file: string, maxBytes: number): string {
  const descriptor = openSync(
    file,
    fsConstants.O_RDONLY | (fsConstants.O_NOFOLLOW ?? 0)
  );
  try {
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.size > maxBytes ||
      (metadata.mode & 0o777) !== 0o600
    ) {
      throw new PrivacyRightsLedgerError("integrity", "privacy ledger file is unsafe");
    }
    return readFileSync(descriptor, "utf8");
  } finally {
    closeSync(descriptor);
  }
}

export function readLedger(): PrivacyLedger {
  ensureDirectory();
  try {
    const target = ledgerPath();
    const decoded = decryptGatewayStateJson(
      { kind: "privacy-rights-ledger", recordId: path.basename(target) },
      readSecureFile(target, maxGatewayStateEnvelopeBytes(PRIVACY_LEDGER_MAX_BYTES)),
      PRIVACY_LEDGER_MAX_BYTES
    ).value;
    if (!validLedger(decoded)) {
      throw new PrivacyRightsLedgerError("integrity", "privacy ledger validation failed");
    }
    return decoded;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      // S19-26: a lost ledger must not silently reopen fenced account generations.
      if (requiredGatewayStateFileWasLost(ledgerPath())) {
        throw new PrivacyRightsLedgerError("integrity", "privacy ledger is missing");
      }
      return emptyLedger();
    }
    if (error instanceof PrivacyRightsLedgerError) throw error;
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger cannot be read");
  }
}

function writeLedger(ledger: PrivacyLedger): void {
  ensureDirectory();
  const plaintext = `${JSON.stringify(ledger)}\n`;
  if (Buffer.byteLength(plaintext) > PRIVACY_LEDGER_MAX_BYTES) {
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger size limit exceeded");
  }
  const target = ledgerPath();
  const encoded = encryptGatewayStateJson(
    { kind: "privacy-rights-ledger", recordId: path.basename(target) },
    ledger,
    PRIVACY_LEDGER_MAX_BYTES
  );
  const temporary = path.join(
    stateDirectory(),
    `.ledger-${process.pid}-${randomBytes(12).toString("hex")}.tmp`
  );
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_CREAT |
        fsConstants.O_EXCL |
        fsConstants.O_WRONLY |
        (fsConstants.O_NOFOLLOW ?? 0),
      0o600
    );
    writeFileSync(descriptor, encoded, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    renameSync(temporary, target);
    // S19-27: after the rename a failure is outcome unknown, not "not written".
    syncGatewayStateDirectoryAfterRename("privacy-rights-ledger", stateDirectory());
    registerRequiredGatewayStateFile(target);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

type LedgerLock = {
  descriptor: number;
};

function acquireLock(): LedgerLock {
  ensureDirectory();
  const file = path.join(stateDirectory(), "ledger.lock");
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      file,
      fsConstants.O_CREAT |
        fsConstants.O_RDWR |
        (fsConstants.O_NOFOLLOW ?? 0),
      0o600
    );
    const metadata = fstatSync(descriptor, { bigint: true });
    const pathname = lstatSync(file, { bigint: true });
    if (
      !metadata.isFile() ||
      !pathname.isFile() ||
      pathname.isSymbolicLink() ||
      metadata.dev !== pathname.dev ||
      metadata.ino !== pathname.ino ||
      (metadata.mode & 0o777n) !== 0o600n ||
      (pathname.mode & 0o777n) !== 0o600n
    ) {
      throw new PrivacyRightsLedgerError(
        "integrity",
        "privacy ledger lock inode is unsafe"
      );
    }
    const flock = spawnSync(
      "/usr/bin/flock",
      [
        "--exclusive",
        "--timeout",
        String(LOCK_WAIT_MS / 1_000),
        "3"
      ],
      {
        stdio: ["ignore", "ignore", "ignore", descriptor],
        timeout: LOCK_WAIT_MS + 1_000,
        killSignal: "SIGKILL"
      }
    );
    if (flock.error) {
      const code = (flock.error as NodeJS.ErrnoException).code;
      if (code === "ETIMEDOUT") {
        throw new PrivacyRightsLedgerError("busy", "privacy ledger is busy");
      }
      throw new PrivacyRightsLedgerError(
        "integrity",
        "required /usr/bin/flock lock helper is unavailable"
      );
    }
    if (flock.status !== 0) {
      if (flock.status === 1) {
        throw new PrivacyRightsLedgerError("busy", "privacy ledger is busy");
      }
      throw new PrivacyRightsLedgerError(
        "integrity",
        "privacy ledger flock acquisition failed"
      );
    }
    const lock = { descriptor };
    descriptor = null;
    return lock;
  } catch (error) {
    if (error instanceof PrivacyRightsLedgerError) throw error;
    throw new PrivacyRightsLedgerError(
      "integrity",
      "privacy ledger lock storage failed"
    );
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function releaseLock(lock: LedgerLock): void {
  closeSync(lock.descriptor);
}

function assertLockTestHook(): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("privacy ledger lock test hook is unavailable");
  }
}

export function crashAfterAcquiringPrivacyLedgerLockForTests(): never {
  assertLockTestHook();
  acquireLock();
  process.kill(process.pid, "SIGKILL");
  while (true) Atomics.wait(sleepArray, 0, 0, 60_000);
}

export function holdPrivacyLedgerFlockForTests(): never {
  assertLockTestHook();
  acquireLock();
  writeSync(1, "privacy-ledger-flock-ready\n", null, "utf8");
  while (true) Atomics.wait(sleepArray, 0, 0, 60_000);
}

export type LedgerMutator<T> = (ledger: PrivacyLedger) => {
  value: T;
  changed: boolean;
};

function applyLedgerMutation<T>(mutator: LedgerMutator<T>): T {
  const ledger = readLedger();
  const result = mutator(ledger);
  if (result.changed) {
    if (!validLedger(ledger)) {
      throw new PrivacyRightsLedgerError(
        "integrity",
        "next privacy ledger state failed validation"
      );
    }
    writeLedger(ledger);
  }
  return result.value;
}

export function mutateLedger<T>(mutator: LedgerMutator<T>): T {
  const lock = acquireLock();
  try {
    return applyLedgerMutation(mutator);
  } finally {
    releaseLock(lock);
  }
}

function lockRetryDelay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/**
 * S19-25: the same ledger lock (flock on privacy-rights/ledger.lock) acquired without
 * blocking the event loop. Each attempt is a non-blocking flock through an asynchronous
 * helper; contention backs off with timers (10 ms doubling to 200 ms) for at most
 * LOCK_WAIT_MS and then fails as `busy`, exactly like the synchronous path. The critical
 * section itself stays synchronous, so no other ledger work interleaves with it.
 */
export async function mutateLedgerAsync<T>(mutator: LedgerMutator<T>): Promise<T> {
  ensureDirectory();
  const lockFile = path.join(stateDirectory(), "ledger.lock");
  const deadline = performance.now() + LOCK_WAIT_MS;
  let backoff = LOCK_RETRY_INITIAL_MS;
  let lease: FileLockLease | null = null;
  while (lease === null) {
    try {
      lease = await acquireExclusiveFileLockLeaseAsync(lockFile);
    } catch (error) {
      if (!(error instanceof ExclusiveFileLockBusyError)) {
        throw new PrivacyRightsLedgerError("integrity", "privacy ledger lock storage failed");
      }
      const remaining = deadline - performance.now();
      if (remaining <= 0) {
        throw new PrivacyRightsLedgerError("busy", "privacy ledger is busy");
      }
      await lockRetryDelay(Math.min(backoff, remaining));
      backoff = Math.min(backoff * 2, LOCK_RETRY_MAX_MS);
    }
  }
  try {
    return applyLedgerMutation(mutator);
  } finally {
    lease.release();
  }
}

