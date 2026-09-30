import { randomBytes } from "node:crypto";
import {
  chmodSync,
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  lstatSync,
  openSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  SHORT_SESSION_MAX_PLAINTEXT_BYTES,
  validShortSessionStateForMaintenance
} from "./auth.js";
import {
  resolveGatewayServiceRuntimeLockPath,
  resolveGatewayStateDirectory
} from "./config.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GatewayStateEncryptionError,
  initializeGatewayStateEncryption,
  inspectGatewayStateEnvelope,
  maxGatewayStateEnvelopeBytes,
  parsePlaintextGatewayStateForMaintenance,
  type GatewayStateContext
} from "./encrypted-json-store.js";
import { withExclusiveFileLockAsync } from "./exclusive-file-lock.js";
import {
  FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES,
  validFieldLongSessionStateForMaintenance
} from "./field-long-session.js";
import {
  FIELD_WALK_CLOCK_STATE_MAX_BYTES,
  fieldWalkAuditSegmentPaths,
  fieldWalkLedgerClockStatePath,
  fieldWalkLedgerShardPaths,
  MAX_STORED_LEDGER_BYTES,
  validFieldWalkAuditSegmentForMaintenance,
  validFieldWalkClockStateForMaintenance,
  validFieldWalkLedgerStateForMaintenance
} from "./field-walk-ledger.js";
import { assertGatewayStateInitMarker } from "./state-init-marker.js";
import {
  INTEGRATED_CONSENT_MAX_STATE_BYTES,
  integratedConsentAuditSegmentPaths,
  validIntegratedConsentAuditSegmentForMaintenance,
  validIntegratedConsentStateForMaintenance,
  withIntegratedConsentStateFileLockForMaintenance
} from "./integrated-consent.js";
import {
  ARCHIVE_ACTOR_MAX_BYTES as PRIVACY_DELETION_V2_ARCHIVE_ACTOR_MAX_BYTES,
  PRIVACY_DELETION_V2_MAX_BYTES,
  validPrivacyDeletionV2ArchiveActorForMaintenance,
  validPrivacyDeletionV2ArchiveRequestForMaintenance,
  validPrivacyDeletionV2StateForMaintenance
} from "./privacy-deletion-v2.js";
import {
  PRIVACY_LEDGER_MAX_BYTES,
  validPrivacyLedgerStateForMaintenance
} from "./privacy-rights.js";
import {
  resolveServerCapacityStatePath,
  SERVER_CAPACITY_MAX_PLAINTEXT_BYTES,
  validServerCapacitySnapshotForMaintenance
} from "./server-capacity.js";

export type GatewayStateMaintenanceCommand =
  | "inspect"
  | "migrate-plaintext"
  | "rotate";

export type GatewayStateMaintenanceStatus =
  | "plaintext"
  | "encrypted-active"
  | "encrypted-decrypt-only"
  | "encrypted-compromised"
  | "encrypted-unknown"
  /**
   * gw-F4/F5: `inspect` only. The file failed authentication or validation (for example a
   * damaged sealed walk audit segment); it is reported per file instead of aborting the
   * report. Rotation and migration still refuse to run.
   */
  | "integrity-failed";

export type GatewayStateMaintenanceResult = {
  file: string;
  kind: GatewayStateContext["kind"];
  statusBefore: GatewayStateMaintenanceStatus;
  action: "none" | "migrated" | "rotated";
  keyId: string | null;
};

type ManagedState = {
  filePath: string;
  context: GatewayStateContext;
  /** S19-35: deletion-ledger role, for counting capabilities still bound to the session key. */
  deletionRole?: "ledger" | "archived-request";
  maxPlaintextBytes: number;
  integratedConsentLock: boolean;
  lockPath?: string;
  /** Covered by another managed state's lock (held for the whole maintenance run). */
  sharedLock?: true;
  /**
   * gw-F4 (R13): sealed walk audit history. The service start checks only its file safety,
   * envelope and key status; decryption and structure checks stay in inspect and rotate.
   */
  startupEnvelopeOnly?: true;
  validate: (value: unknown) => boolean;
};

type ClassifiedState = {
  status: GatewayStateMaintenanceStatus;
  keyId: string | null;
  value?: unknown;
};

type PreparedState = {
  state: ManagedState;
  result: GatewayStateMaintenanceResult;
  encoded: string | null;
};

const SHORT_SESSION_FILE = /^field-[0-9a-f]{64}\.json$/;
const DIGEST_FILE = /^[0-9a-f]{64}\.json$/;
const SERVER_CAPACITY_FILE = /^snapshot\.json$/;

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

function stateRoot(environment: NodeJS.ProcessEnv): string {
  if (
    process.env.NODE_ENV === "production" &&
    !environment.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim()
  ) {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  return resolveGatewayStateDirectory(environment);
}

function fieldWalkLedgerPath(environment: NodeJS.ProcessEnv): string {
  const configured = environment.WALKSAFE_FIELD_WALK_LEDGER_PATH?.trim() ?? "";
  const production = process.env.NODE_ENV === "production" || environment.NODE_ENV === "production";
  if (!configured && production) {
    throw new Error("WALKSAFE_FIELD_WALK_LEDGER_PATH is required in production");
  }
  const value = configured || path.join(
    tmpdir(),
    "walksafe-android-gateway",
    "field-walk-ledger.json"
  );
  if (!path.isAbsolute(value)) throw new Error("field walk ledger path must be absolute");
  if (production && path.resolve(value) !== value) {
    throw new Error("field walk ledger path must be canonical absolute");
  }
  return path.resolve(value);
}

function assertManagedStatePaths(environment: NodeJS.ProcessEnv): void {
  stateRoot(environment);
  fieldWalkLedgerPath(environment);
}

function existingSecureDirectory(directory: string): boolean {
  let metadata: ReturnType<typeof lstatSync>;
  try {
    metadata = lstatSync(directory);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
    throw error;
  }
  if (
    !metadata.isDirectory() ||
    metadata.isSymbolicLink() ||
    realpathSync(directory) !== directory ||
    !ownedByCurrentProcess(metadata.uid) ||
    (metadata.mode & 0o077) !== 0
  ) {
    throw new Error(`gateway managed state directory is unsafe: ${directory}`);
  }
  return true;
}

function matchingFiles(directory: string, expected: RegExp): string[] {
  if (!existingSecureDirectory(directory)) return [];
  const files: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (!entry.name.endsWith(".json")) continue;
    if (!expected.test(entry.name)) {
      throw new Error(`unexpected JSON state file in managed directory: ${entry.name}`);
    }
    files.push(path.join(directory, entry.name));
  }
  return files.sort();
}

function managedStates(environment: NodeJS.ProcessEnv): ManagedState[] {
  const root = stateRoot(environment);
  const states: ManagedState[] = [];
  for (const filePath of matchingFiles(path.join(root, "sessions"), SHORT_SESSION_FILE)) {
    const recordId = path.basename(filePath);
    states.push({
      filePath,
      context: { kind: "short-session", recordId },
      maxPlaintextBytes: SHORT_SESSION_MAX_PLAINTEXT_BYTES,
      integratedConsentLock: false,
      validate: (value) => validShortSessionStateForMaintenance(value, recordId)
    });
  }
  for (const filePath of matchingFiles(path.join(root, "field-long-sessions"), DIGEST_FILE)) {
    const recordId = path.basename(filePath);
    states.push({
      filePath,
      context: { kind: "field-long-session", recordId },
      maxPlaintextBytes: FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES,
      integratedConsentLock: false,
      validate: (value) => validFieldLongSessionStateForMaintenance(value, recordId)
    });
  }
  const privacyDirectory = path.join(root, "privacy-rights");
  if (existingSecureDirectory(privacyDirectory)) {
    const privacyFile = path.join(privacyDirectory, "ledger.json");
    try {
      lstatSync(privacyFile);
      states.push({
        filePath: privacyFile,
        context: { kind: "privacy-rights-ledger", recordId: "ledger.json" },
        maxPlaintextBytes: PRIVACY_LEDGER_MAX_BYTES,
        integratedConsentLock: false,
        lockPath: path.join(privacyDirectory, "ledger.lock"),
        validate: validPrivacyLedgerStateForMaintenance
      });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }
  const deletionV2Directory = path.join(root, "privacy-deletion-v2");
  if (existingSecureDirectory(deletionV2Directory)) {
    const deletionV2File = path.join(deletionV2Directory, "ledger.json");
    try {
      lstatSync(deletionV2File);
      states.push({
        filePath: deletionV2File,
        context: { kind: "privacy-deletion-v2", recordId: "ledger.json" },
        maxPlaintextBytes: PRIVACY_DELETION_V2_MAX_BYTES,
        integratedConsentLock: false,
        lockPath: path.join(deletionV2Directory, "ledger.lock"),
        deletionRole: "ledger",
        validate: validPrivacyDeletionV2StateForMaintenance
      });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    // S19-36: archived requests and actor fences are encrypted state as well; they are
    // guarded by the deletion ledger lock, which withAllStateLocks holds for the whole run.
    const archiveRoot = path.join(deletionV2Directory, "archive");
    if (existingSecureDirectory(archiveRoot)) {
      for (const kind of ["requests", "actors"] as const) {
        for (const filePath of matchingFiles(path.join(archiveRoot, kind), DIGEST_FILE)) {
          const recordId = path.basename(filePath);
          states.push({
            filePath,
            context: { kind: "privacy-deletion-v2", recordId },
            maxPlaintextBytes: kind === "requests"
              ? PRIVACY_DELETION_V2_MAX_BYTES
              : PRIVACY_DELETION_V2_ARCHIVE_ACTOR_MAX_BYTES,
            integratedConsentLock: false,
            sharedLock: true,
            ...(kind === "requests" ? { deletionRole: "archived-request" as const } : {}),
            validate: (value) => kind === "requests"
              ? validPrivacyDeletionV2ArchiveRequestForMaintenance(value, recordId)
              : validPrivacyDeletionV2ArchiveActorForMaintenance(value, recordId)
          });
        }
      }
    }
  }
  for (const filePath of matchingFiles(path.join(root, "integrated-consent"), DIGEST_FILE)) {
    const recordId = path.basename(filePath);
    states.push({
      filePath,
      context: { kind: "integrated-consent", recordId },
      maxPlaintextBytes: INTEGRATED_CONSENT_MAX_STATE_BYTES,
      integratedConsentLock: true,
      validate: (value) => validIntegratedConsentStateForMaintenance(value, recordId, environment)
    });
  }
  // S19-29: archived audit is encrypted with the installation's record context and uses
  // its main state's lock. Include orphan segments as well, so retiring an old key cannot
  // strand a verified archive-only crash retry.
  for (const { segmentPath, statePath } of integratedConsentAuditSegmentPaths(environment)) {
    if (!states.some((state) => state.filePath === statePath && state.integratedConsentLock)) {
      throw new Error(`integrated consent audit has no current state: ${segmentPath}`);
    }
    const recordId = path.basename(segmentPath);
    states.push({
      filePath: segmentPath,
      context: { kind: "integrated-consent", recordId },
      maxPlaintextBytes: INTEGRATED_CONSENT_MAX_STATE_BYTES,
      integratedConsentLock: false,
      sharedLock: true,
      validate: (value) => validIntegratedConsentAuditSegmentForMaintenance(value, recordId)
    });
  }
  const capacityPath = resolveServerCapacityStatePath(environment);
  for (const filePath of matchingFiles(
    path.dirname(capacityPath),
    SERVER_CAPACITY_FILE
  )) {
    states.push({
      filePath,
      context: { kind: "server-capacity", recordId: path.basename(filePath) },
      maxPlaintextBytes: SERVER_CAPACITY_MAX_PLAINTEXT_BYTES,
      integratedConsentLock: false,
      validate: validServerCapacitySnapshotForMaintenance
    });
  }

  // S19-14: the walk ledger is the configured path (shard 0) plus its account-hash shards.
  const walkShards = new Set<string>();
  for (const walkLedger of fieldWalkLedgerShardPaths(fieldWalkLedgerPath(environment))) {
    try {
      lstatSync(walkLedger);
      walkShards.add(walkLedger);
      states.push({
        filePath: walkLedger,
        context: { kind: "field-walk-ledger", recordId: path.basename(walkLedger) },
        maxPlaintextBytes: MAX_STORED_LEDGER_BYTES,
        integratedConsentLock: false,
        validate: validFieldWalkLedgerStateForMaintenance
      });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }
  // S19-13: sealed walk audit segments are encrypted state of their shard and are written
  // only under that shard's lock, which withAllStateLocks holds for the whole run.
  for (const { segmentPath, shardPath } of fieldWalkAuditSegmentPaths(
    fieldWalkLedgerPath(environment)
  )) {
    if (!walkShards.has(shardPath)) {
      throw new Error(`field walk audit segment has no walk ledger shard: ${segmentPath}`);
    }
    const recordId = path.basename(segmentPath);
    states.push({
      filePath: segmentPath,
      context: { kind: "field-walk-ledger", recordId },
      maxPlaintextBytes: MAX_STORED_LEDGER_BYTES,
      integratedConsentLock: false,
      sharedLock: true,
      startupEnvelopeOnly: true,
      validate: (value) => validFieldWalkAuditSegmentForMaintenance(value, recordId)
    });
  }
  // S19-20 (w22 review): the persisted clock anchor of the walk ledger.
  const walkClock = fieldWalkLedgerClockStatePath(fieldWalkLedgerPath(environment));
  try {
    lstatSync(walkClock);
    states.push({
      filePath: walkClock,
      context: { kind: "field-walk-ledger", recordId: path.basename(walkClock) },
      maxPlaintextBytes: FIELD_WALK_CLOCK_STATE_MAX_BYTES,
      integratedConsentLock: false,
      validate: validFieldWalkClockStateForMaintenance
    });
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }

  const unique = new Set<string>();
  for (const state of states) {
    if (unique.has(state.filePath)) {
      throw new Error(`gateway managed state path is duplicated: ${state.filePath}`);
    }
    unique.add(state.filePath);
  }
  return states.sort((left, right) => left.filePath.localeCompare(right.filePath));
}

function readManagedState(state: ManagedState): string {
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      state.filePath,
      fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW
    );
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size < 2 ||
      metadata.size > maxGatewayStateEnvelopeBytes(state.maxPlaintextBytes)
    ) {
      throw new Error(`gateway managed state file is unsafe: ${state.filePath}`);
    }
    const raw = readFileSync(descriptor, "utf8");
    if (Buffer.byteLength(raw, "utf8") !== metadata.size) {
      throw new Error(`gateway managed state file changed while reading: ${state.filePath}`);
    }
    return raw;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function validateState(state: ManagedState, value: unknown): void {
  if (!state.validate(value)) {
    throw new GatewayStateEncryptionError(
      "integrity",
      `gateway managed state validation failed: ${state.context.kind}`
    );
  }
}

function classifyState(
  state: ManagedState,
  raw: string,
  environment: NodeJS.ProcessEnv
): ClassifiedState {
  let metadata: ReturnType<typeof inspectGatewayStateEnvelope>;
  try {
    metadata = inspectGatewayStateEnvelope(raw, state.maxPlaintextBytes, environment);
  } catch (error) {
    if (
      error instanceof GatewayStateEncryptionError &&
      error.code === "plaintext_rejected"
    ) {
      const value = parsePlaintextGatewayStateForMaintenance(
        raw,
        state.maxPlaintextBytes
      );
      validateState(state, value);
      return { status: "plaintext", keyId: null, value };
    }
    throw error;
  }
  if (metadata.keyStatus === "compromised") {
    return {
      status: "encrypted-compromised",
      keyId: metadata.keyId
    };
  }
  if (metadata.keyStatus === "unknown") {
    return {
      status: "encrypted-unknown",
      keyId: metadata.keyId
    };
  }
  const decrypted = decryptGatewayStateJson(
    state.context,
    raw,
    state.maxPlaintextBytes,
    environment
  );
  validateState(state, decrypted.value);
  return {
    status: decrypted.keyStatus === "active"
      ? "encrypted-active"
      : "encrypted-decrypt-only",
    keyId: decrypted.keyId,
    value: decrypted.value
  };
}

function atomicReplace(state: ManagedState, encoded: string): void {
  const temporary = path.join(
    path.dirname(state.filePath),
    `.${path.basename(state.filePath)}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`
  );
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY |
        fsConstants.O_CREAT |
        fsConstants.O_EXCL |
        fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, encoded, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    chmodSync(temporary, 0o600);
    renameSync(temporary, state.filePath);
    directoryDescriptor = openSync(
      path.dirname(state.filePath),
      fsConstants.O_RDONLY |
        fsConstants.O_DIRECTORY |
        fsConstants.O_NOFOLLOW
    );
    fsyncSync(directoryDescriptor);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    rmSync(temporary, { force: true });
  }
}

function blockedKeyError(classified: ClassifiedState): GatewayStateEncryptionError {
  if (classified.status === "encrypted-compromised") {
    return new GatewayStateEncryptionError(
      "compromised_key",
      "gateway state encrypted by a compromised key cannot be maintained"
    );
  }
  return new GatewayStateEncryptionError(
    "unknown_key",
    "gateway state encrypted by an unknown key cannot be maintained"
  );
}

/**
 * S19-22-F1 (w22 review): a legacy PID-lock recovery link left in integrated-consent/
 * (`<digest>.json.lock.reaper`, or a `.lock` with more than one link) makes that
 * installation's consent store fail closed at request time. It is reported before the
 * service starts (and before maintenance runs) so the operator removes it with the service
 * stopped instead of learning about it from user failures.
 */
export class GatewayConsentLockResidueError extends Error {
  readonly code = "integrated_consent_lock_residue";

  constructor(readonly files: readonly string[]) {
    super(
      `integrated consent lock residue must be removed with the service stopped: ${files.join(", ")}` +
        " (delete each listed <digest>.json.lock.reaper; a .lock with extra links is recreated after removal)"
    );
    this.name = "GatewayConsentLockResidueError";
  }
}

const CONSENT_LOCK_FILE = /^[0-9a-f]{64}\.json\.lock$/;

export function integratedConsentLockResidue(
  environment: NodeJS.ProcessEnv = process.env
): string[] {
  const directory = path.join(stateRoot(environment), "integrated-consent");
  if (!existingSecureDirectory(directory)) return [];
  const residue: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const filePath = path.join(directory, entry.name);
    if (entry.name.endsWith(".reaper")) {
      residue.push(filePath);
    } else if (CONSENT_LOCK_FILE.test(entry.name) && lstatSync(filePath).nlink > 1) {
      residue.push(filePath);
    }
  }
  return residue.sort();
}

function assertNoIntegratedConsentLockResidue(environment: NodeJS.ProcessEnv): void {
  const residue = integratedConsentLockResidue(environment);
  if (residue.length > 0) throw new GatewayConsentLockResidueError(residue);
}

/**
 * gw-F4 (R13): file safety, envelope and key status of one managed state, without
 * decrypting it (no plaintext is produced, the payload is not validated).
 */
function inspectEnvelopeOnly(
  state: ManagedState,
  environment: NodeJS.ProcessEnv
): GatewayStateMaintenanceResult {
  const raw = readManagedState(state);
  let statusBefore: GatewayStateMaintenanceStatus;
  let keyId: string | null = null;
  try {
    const metadata = inspectGatewayStateEnvelope(raw, state.maxPlaintextBytes, environment);
    keyId = metadata.keyId;
    statusBefore = metadata.keyStatus === "active"
      ? "encrypted-active"
      : metadata.keyStatus === "decrypt-only"
        ? "encrypted-decrypt-only"
        : metadata.keyStatus === "compromised"
          ? "encrypted-compromised"
          : "encrypted-unknown";
  } catch (error) {
    if (!(error instanceof GatewayStateEncryptionError) || error.code !== "plaintext_rejected") {
      throw error;
    }
    statusBefore = "plaintext";
  }
  return { file: state.filePath, kind: state.context.kind, statusBefore, action: "none", keyId };
}

export type FieldWalkAuditSegmentUsage = {
  /** Sealed walk audit segments (`<shard>.audit-NNNNNNNN`) currently on disk. */
  count: number;
  /** Their total file size in bytes (encrypted envelopes, from lstat). */
  bytes: number;
};

/**
 * gw-F4 (R13): the sealed walk audit history is kept until a retention period is decided
 * (no automatic deletion), so its size is reported from file metadata only.
 */
export function fieldWalkAuditSegmentUsage(
  environment: NodeJS.ProcessEnv = process.env
): FieldWalkAuditSegmentUsage {
  const usage: FieldWalkAuditSegmentUsage = { count: 0, bytes: 0 };
  for (const { segmentPath } of fieldWalkAuditSegmentPaths(fieldWalkLedgerPath(environment))) {
    try {
      usage.bytes += lstatSync(segmentPath).size;
      usage.count += 1;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }
  return usage;
}

export function assertGatewayManagedStateReady(
  environment: NodeJS.ProcessEnv = process.env
): GatewayStateMaintenanceResult[] {
  assertManagedStatePaths(environment);
  assertNoIntegratedConsentLockResidue(environment);
  initializeGatewayStateEncryption(environment);
  // gw-F4 (R13): the start cost no longer grows with the sealed walk audit history, and a
  // damaged segment (unrelated to live walks) does not stop the service; `inspect` reports it.
  const results = managedStates(environment).map((state) => state.startupEnvelopeOnly
    ? inspectEnvelopeOnly(state, environment)
    : prepareOne("inspect", state, environment).result
  );
  for (const result of results) {
    if (result.statusBefore === "plaintext") {
      throw new GatewayStateEncryptionError(
        "plaintext_rejected",
        `gateway startup rejected plaintext persisted state: ${result.kind}`
      );
    }
    if (result.statusBefore === "encrypted-compromised") {
      throw new GatewayStateEncryptionError(
        "compromised_key",
        `gateway startup rejected state encrypted by a compromised key: ${result.kind}`
      );
    }
    if (result.statusBefore === "encrypted-unknown") {
      throw new GatewayStateEncryptionError(
        "unknown_key",
        `gateway startup rejected state encrypted by an unknown key: ${result.kind}`
      );
    }
  }
  // S19-26: a first run creates the init marker; afterwards a missing required ledger
  // (deleted file, wrong or empty volume) refuses start instead of starting empty.
  const root = stateRoot(environment);
  assertGatewayStateInitMarker([
    ...fieldWalkLedgerShardPaths(fieldWalkLedgerPath(environment)),
    fieldWalkLedgerClockStatePath(fieldWalkLedgerPath(environment)),
    path.join(root, "privacy-rights", "ledger.json"),
    path.join(root, "privacy-deletion-v2", "ledger.json")
  ], environment);
  // gw-F4 (R13): operator diagnostic of the retained audit history (metadata only).
  const usage = fieldWalkAuditSegmentUsage(environment);
  if (usage.count > 0) {
    process.stderr.write(
      `walksafe gateway state: field walk audit segments ${usage.count}, ${usage.bytes} bytes ` +
        "(kept until a retention period is decided; decrypted only by inspect and rotate)\n"
    );
  }
  return results;
}

/** S19-57: a shared ledger at or above this share of its envelope ceiling is "saturated". */
export const GATEWAY_STORE_SATURATION_RATIO = 0.9;

/**
 * S19-57: shared ledgers whose file is near its ceiling, from metadata only (lstat, no read
 * or decryption). Per-installation consent and per-actor session files are not included:
 * one user's full ledger affects that user, and must not take the whole Gateway out of
 * rotation. Returns the store names (for loopback diagnostics); empty means none.
 */
export async function saturatedGatewayStores(
  ledgerPath: string,
  environment: NodeJS.ProcessEnv = process.env
): Promise<string[]> {
  const fs = await import("node:fs/promises");
  const { resolveGatewayStateDirectory } = await import("./config.js");
  const { DEFAULT_MAX_LEDGER_BYTES, fieldWalkLedgerShardPaths } = await import("./field-walk-ledger.js");
  const root = resolveGatewayStateDirectory(environment);
  const checks: Array<[string, string, number]> = [
    ...fieldWalkLedgerShardPaths(ledgerPath).map(
      (shard) => ["field-walk-ledger", shard, DEFAULT_MAX_LEDGER_BYTES] as [string, string, number]
    ),
    ["privacy-deletion-v2", path.join(root, "privacy-deletion-v2", "ledger.json"), PRIVACY_DELETION_V2_MAX_BYTES],
    ["privacy-rights", path.join(root, "privacy-rights", "ledger.json"), PRIVACY_LEDGER_MAX_BYTES]
  ];
  const saturated = new Set<string>();
  for (const [name, filePath, maxPlaintextBytes] of checks) {
    let size: number;
    try {
      size = (await fs.lstat(filePath)).size;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
      throw error;
    }
    if (size >= GATEWAY_STORE_SATURATION_RATIO * maxGatewayStateEnvelopeBytes(maxPlaintextBytes)) {
      saturated.add(name);
    }
  }
  return [...saturated].sort();
}

let lastReportedStoreSaturation = "";

/**
 * S19-57: the reason for a saturation not-ready answer goes to the service log (store names
 * only), once per change, while `/internal/ready` keeps its minimal `{status}` body.
 */
function reportGatewayStoreSaturation(names: readonly string[]): void {
  const key = names.join(",");
  if (key === lastReportedStoreSaturation) return;
  lastReportedStoreSaturation = key;
  try {
    process.stderr.write(key
      ? `walksafe gateway readiness: saturated stores ${key}\n`
      : "walksafe gateway readiness: store saturation cleared\n");
  } catch {
    // Diagnostics never change the readiness answer.
  }
}

/** Bounded operational probe: metadata, free space, and one durable write per storage root. */
export async function probeGatewayManagedStorageReady(
  ledgerPath: string,
  environment: NodeJS.ProcessEnv = process.env
): Promise<boolean> {
  try {
    const fs = await import("node:fs/promises");
    const { constants } = await import("node:fs");
    const { randomUUID } = await import("node:crypto");
    const { resolveGatewayStateDirectory } = await import("./config.js");
    const { MAX_STORED_LEDGER_BYTES } = await import("./field-walk-ledger.js");
    const { probeGatewayStateKeyringReady } = await import("./encrypted-json-store.js");
    if (!path.isAbsolute(ledgerPath) || path.resolve(ledgerPath) !== ledgerPath) return false;
    if (!probeGatewayStateKeyringReady(environment)) return false;

    const directories = new Set([
      resolveGatewayStateDirectory(environment),
      path.dirname(ledgerPath)
    ]);
    for (const directory of directories) {
      const metadata = await fs.lstat(directory);
      if (!metadata.isDirectory() || metadata.isSymbolicLink()) return false;
      const space = await fs.statfs(directory);
      if (BigInt(space.bavail) * BigInt(space.bsize) < 64n * 1024n) return false;

      const directoryHandle = await fs.open(
        directory,
        constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW
      );
      const temporary = path.join(directory, `.walksafe-readiness-${randomUUID()}`);
      let probeHandle: Awaited<ReturnType<typeof fs.open>> | null = null;
      let created = false;
      try {
        probeHandle = await fs.open(
          temporary,
          constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW,
          0o600
        );
        created = true;
        await probeHandle.writeFile("ready");
        await probeHandle.sync();
        await probeHandle.close();
        probeHandle = null;
        await fs.unlink(temporary);
        created = false;
        await directoryHandle.sync();
      } finally {
        if (probeHandle !== null) await probeHandle.close();
        if (created) await fs.unlink(temporary);
        await directoryHandle.close();
      }
    }

    let ledgerHandle: Awaited<ReturnType<typeof fs.open>> | null = null;
    try {
      ledgerHandle = await fs.open(
        ledgerPath,
        constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK
      );
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    if (ledgerHandle !== null) {
      try {
        const metadata = await ledgerHandle.stat();
        if (
          !metadata.isFile() ||
          metadata.nlink !== 1 ||
          metadata.size >= MAX_STORED_LEDGER_BYTES - 2_048
        ) return false;
      } finally {
        await ledgerHandle.close();
      }
    }
    // S19-57: a saturated shared ledger (any walk shard, deletion, privacy rights) would fail
    // its writes; take the Gateway out of rotation (FP-043 interim: safe stop on persistent
    // failure of a supporting function) instead of answering ready.
    const saturated = await saturatedGatewayStores(ledgerPath, environment);
    reportGatewayStoreSaturation(saturated);
    if (saturated.length > 0) return false;
    return true;
  } catch {
    return false;
  }
}

function prepareOne(
  command: GatewayStateMaintenanceCommand,
  state: ManagedState,
  environment: NodeJS.ProcessEnv
): PreparedState {
  const raw = readManagedState(state);
  const classified = classifyState(state, raw, environment);
  let action: GatewayStateMaintenanceResult["action"] = "none";
  let encoded: string | null = null;

  if (command === "migrate-plaintext" && classified.status === "plaintext") {
    encoded = encryptGatewayStateJson(
      state.context,
      classified.value,
      state.maxPlaintextBytes,
      environment
    );
    action = "migrated";
  } else if (command === "rotate") {
    if (classified.status === "plaintext") {
      throw new GatewayStateEncryptionError(
        "plaintext_rejected",
        "plaintext gateway state must be migrated before rotation"
      );
    }
    if (
      classified.status === "encrypted-compromised" ||
      classified.status === "encrypted-unknown"
    ) {
      throw blockedKeyError(classified);
    }
    if (classified.status === "encrypted-decrypt-only") {
      encoded = encryptGatewayStateJson(
        state.context,
        classified.value,
        state.maxPlaintextBytes,
        environment
      );
      action = "rotated";
    }
  } else if (
    command === "migrate-plaintext" &&
    (classified.status === "encrypted-compromised" ||
      classified.status === "encrypted-unknown")
  ) {
    throw blockedKeyError(classified);
  }

  return {
    state,
    encoded,
    result: {
      file: state.filePath,
      kind: state.context.kind,
      statusBefore: classified.status,
      action,
      keyId: classified.keyId
    }
  };
}

function applyPrepared(
  prepared: PreparedState,
  environment: NodeJS.ProcessEnv
): GatewayStateMaintenanceResult {
  if (prepared.encoded !== null) {
    atomicReplace(prepared.state, prepared.encoded);
    const verified = classifyState(
      prepared.state,
      readManagedState(prepared.state),
      environment
    );
    if (verified.status !== "encrypted-active") {
      throw new GatewayStateEncryptionError(
        "integrity",
        "gateway state maintenance rewrite verification failed"
      );
    }
  }
  return prepared.result;
}

async function withStateLock<T>(
  state: ManagedState,
  action: () => Promise<T> | T,
  environment: NodeJS.ProcessEnv
): Promise<T> {
  if (state.integratedConsentLock) {
    return withIntegratedConsentStateFileLockForMaintenance(state.filePath, action, environment);
  }
  if (state.sharedLock) return action();
  return withExclusiveFileLockAsync(state.lockPath ?? `${state.filePath}.lock`, action);
}

async function withAllStateLocks<T>(
  states: readonly ManagedState[],
  index: number,
  action: () => Promise<T> | T,
  environment: NodeJS.ProcessEnv
): Promise<T> {
  const state = states[index];
  if (state === undefined) return action();
  return withStateLock(
    state,
    () => withAllStateLocks(states, index + 1, action, environment),
    environment
  );
}

/**
 * gw-F4/F5: `inspect` for the operator report: a file that fails authentication or
 * validation becomes an `integrity-failed` result instead of aborting the whole report.
 */
function prepareInspectReport(
  state: ManagedState,
  environment: NodeJS.ProcessEnv
): PreparedState {
  try {
    return prepareOne("inspect", state, environment);
  } catch (error) {
    if (!(error instanceof GatewayStateEncryptionError) || error.code !== "integrity") throw error;
    let keyId: string | null = null;
    try {
      keyId = inspectGatewayStateEnvelope(
        readManagedState(state),
        state.maxPlaintextBytes,
        environment
      ).keyId;
    } catch {
      keyId = null;
    }
    return {
      state,
      encoded: null,
      result: {
        file: state.filePath,
        kind: state.context.kind,
        statusBefore: "integrity-failed",
        action: "none",
        keyId
      }
    };
  }
}

export type GatewayStateMaintenanceOptions = {
  /**
   * `inspect` only (the CLI report): report integrity failures per file. Without it, the
   * first integrity failure rejects the run as before.
   */
  reportIntegrityFailures?: boolean;
};

export async function runGatewayStateMaintenance(
  command: GatewayStateMaintenanceCommand,
  environment: NodeJS.ProcessEnv = process.env,
  options: GatewayStateMaintenanceOptions = {}
): Promise<GatewayStateMaintenanceResult[]> {
  if (environment.WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED !== "true") {
    throw new Error(
      "WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED=true is required after stopping the gateway service"
    );
  }
  assertManagedStatePaths(environment);
  assertNoIntegratedConsentLockResidue(environment);
  return withExclusiveFileLockAsync(
    resolveGatewayServiceRuntimeLockPath(environment),
    async () => {
      initializeGatewayStateEncryption(environment);
      const states = managedStates(environment);
      const report = command === "inspect" && options.reportIntegrityFailures === true;
      return withAllStateLocks(states, 0, () => {
        // Re-enumerate under every installation lock: an archive may have been sealed
        // while maintenance waited for its writer. Every newly found segment shares an
        // already held main-state lock, and participates in the all-before-write validation.
        const lockedStates = managedStates(environment);
        const lockedMainPaths = new Set(states.filter((state) => state.integratedConsentLock).map((state) => state.filePath));
        if (lockedStates.some((state) => state.integratedConsentLock && !lockedMainPaths.has(state.filePath))) {
          throw new Error("integrated consent state set changed while maintenance acquired locks");
        }
        const prepared = lockedStates.map((state) => report
          ? prepareInspectReport(state, environment)
          : prepareOne(command, state, environment));
        return prepared.map((item) => applyPrepared(item, environment));
      }, environment);
    }
  );
}

export type LegacyDeletionCapabilityCount = {
  /** Active deletion requests whose capability is still verified with the session secret. */
  active: number;
  /** The same for archived (completed) requests, which stay queryable by capability. */
  archived: number;
  total: number;
};

function legacyCapabilityRecords(value: unknown): number {
  const record = typeof value === "object" && value !== null ? value as Record<string, unknown> : null;
  return record !== null && !Object.prototype.hasOwnProperty.call(record, "capability_key") ? 1 : 0;
}

/**
 * S19-35: deletion requests created before capability keys (no `capability_key`) are still
 * verified with WALKSAFE_GATEWAY_SESSION_SECRET until their capability is presented once.
 * Their secret is not stored, so they cannot be re-keyed in advance: the session secret must
 * not be retired while this count is above zero (`check-session-secret-retirement`).
 */
export async function countLegacyDeletionCapabilities(
  environment: NodeJS.ProcessEnv = process.env
): Promise<LegacyDeletionCapabilityCount> {
  if (environment.WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED !== "true") {
    throw new Error(
      "WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED=true is required after stopping the gateway service"
    );
  }
  assertManagedStatePaths(environment);
  return withExclusiveFileLockAsync(
    resolveGatewayServiceRuntimeLockPath(environment),
    async () => {
      initializeGatewayStateEncryption(environment);
      const states = managedStates(environment).filter((state) => state.deletionRole !== undefined);
      return withAllStateLocks(states, 0, () => {
        const count: LegacyDeletionCapabilityCount = { active: 0, archived: 0, total: 0 };
        for (const state of states) {
          const classified = classifyState(state, readManagedState(state), environment);
          if (classified.value === undefined) throw blockedKeyError(classified);
          const value = classified.value as Record<string, unknown>;
          if (state.deletionRole === "ledger") {
            const requests = value.requests as Record<string, unknown>;
            for (const request of Object.values(requests)) count.active += legacyCapabilityRecords(request);
          } else {
            count.archived += legacyCapabilityRecords(value.record);
          }
        }
        count.total = count.active + count.archived;
        return count;
      }, environment);
    }
  );
}

async function main(): Promise<void> {
  const command = process.argv[2];
  const commands = ["inspect", "migrate-plaintext", "rotate", "check-session-secret-retirement"];
  if (!command || !commands.includes(command)) {
    throw new Error(
      "usage: state-encryption-maintenance <inspect|migrate-plaintext|rotate|check-session-secret-retirement>"
    );
  }
  if (command === "check-session-secret-retirement") {
    const legacy = await countLegacyDeletionCapabilities();
    process.stdout.write(`${JSON.stringify({
      command,
      legacy_deletion_capabilities: legacy,
      session_secret_retirement: legacy.total === 0 ? "allowed" : "blocked"
    })}\n`);
    if (legacy.total > 0) process.exitCode = 3;
    return;
  }
  const inspect = command === "inspect";
  const results = await runGatewayStateMaintenance(
    command as GatewayStateMaintenanceCommand,
    process.env,
    { reportIntegrityFailures: inspect }
  );
  // gw-F5: the per-file report is always written; a legacy count that cannot be taken (for
  // example a deletion ledger under a compromised key, already listed in `results`) is
  // reported by its error code instead of discarding the report.
  let legacy: LegacyDeletionCapabilityCount | { error: string } | null = null;
  if (inspect) {
    try {
      legacy = await countLegacyDeletionCapabilities();
    } catch (error) {
      legacy = {
        error: error instanceof GatewayStateEncryptionError
          ? error.code
          : error instanceof Error ? error.name : "Error"
      };
    }
  }
  process.stdout.write(`${JSON.stringify({
    command,
    results,
    ...(legacy ? { legacy_deletion_capabilities: legacy } : {}),
    // gw-F4 (R13): size of the retained sealed walk audit history (metadata only).
    ...(inspect ? { field_walk_audit_segments: fieldWalkAuditSegmentUsage() } : {})
  })}\n`);
  if (results.some((result) => [
    "plaintext",
    "encrypted-compromised",
    "encrypted-unknown",
    "integrity-failed"
  ].includes(result.statusBefore) && result.action === "none") ||
    (legacy !== null && "error" in legacy)) {
    process.exitCode = 2;
  }
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))
) {
  void main().catch((error: unknown) => {
    const payload = error instanceof GatewayStateEncryptionError
      ? { name: error.name, code: error.code, message: error.message }
      : {
          name: error instanceof Error ? error.name : "Error",
          message: error instanceof Error ? error.message : "unknown maintenance error"
        };
    process.stderr.write(`${JSON.stringify(payload)}\n`);
    process.exitCode = 1;
  });
}
