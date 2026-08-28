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
  DEFAULT_MAX_LEDGER_BYTES,
  validFieldWalkLedgerStateForMaintenance
} from "./field-walk-ledger.js";
import {
  INTEGRATED_CONSENT_MAX_STATE_BYTES,
  validIntegratedConsentStateForMaintenance,
  withIntegratedConsentStateFileLockForMaintenance
} from "./integrated-consent.js";
import {
  PRIVACY_DELETION_V2_MAX_BYTES,
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
  | "encrypted-unknown";

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
  maxPlaintextBytes: number;
  integratedConsentLock: boolean;
  lockPath?: string;
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
  return resolveGatewayStateDirectory(environment);
}

function fieldWalkLedgerPath(environment: NodeJS.ProcessEnv): string {
  const configured = environment.WALKSAFE_FIELD_WALK_LEDGER_PATH?.trim() ?? "";
  if (!configured && environment.NODE_ENV === "production") {
    throw new Error("WALKSAFE_FIELD_WALK_LEDGER_PATH is required in production");
  }
  const value = configured || path.join(
    tmpdir(),
    "walksafe-android-gateway",
    "field-walk-ledger.json"
  );
  if (!path.isAbsolute(value)) throw new Error("field walk ledger path must be absolute");
  return path.resolve(value);
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
        validate: validPrivacyDeletionV2StateForMaintenance
      });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }
  for (const filePath of matchingFiles(path.join(root, "integrated-consent"), DIGEST_FILE)) {
    const recordId = path.basename(filePath);
    states.push({
      filePath,
      context: { kind: "integrated-consent", recordId },
      maxPlaintextBytes: INTEGRATED_CONSENT_MAX_STATE_BYTES,
      integratedConsentLock: true,
      validate: (value) => validIntegratedConsentStateForMaintenance(value, recordId)
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

  const walkLedger = fieldWalkLedgerPath(environment);
  try {
    lstatSync(walkLedger);
    states.push({
      filePath: walkLedger,
      context: { kind: "field-walk-ledger", recordId: path.basename(walkLedger) },
      maxPlaintextBytes: DEFAULT_MAX_LEDGER_BYTES,
      integratedConsentLock: false,
      validate: validFieldWalkLedgerStateForMaintenance
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

function classifyState(state: ManagedState, raw: string): ClassifiedState {
  let metadata: ReturnType<typeof inspectGatewayStateEnvelope>;
  try {
    metadata = inspectGatewayStateEnvelope(raw, state.maxPlaintextBytes);
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
    state.maxPlaintextBytes
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

export function assertGatewayManagedStateReady(
  environment: NodeJS.ProcessEnv = process.env
): GatewayStateMaintenanceResult[] {
  initializeGatewayStateEncryption(environment);
  const results = managedStates(environment).map(
    (state) => prepareOne("inspect", state).result
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
  return results;
}

function prepareOne(
  command: GatewayStateMaintenanceCommand,
  state: ManagedState
): PreparedState {
  const raw = readManagedState(state);
  const classified = classifyState(state, raw);
  let action: GatewayStateMaintenanceResult["action"] = "none";
  let encoded: string | null = null;

  if (command === "migrate-plaintext" && classified.status === "plaintext") {
    encoded = encryptGatewayStateJson(
      state.context,
      classified.value,
      state.maxPlaintextBytes
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
        state.maxPlaintextBytes
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

function applyPrepared(prepared: PreparedState): GatewayStateMaintenanceResult {
  if (prepared.encoded !== null) {
    atomicReplace(prepared.state, prepared.encoded);
    const verified = classifyState(
      prepared.state,
      readManagedState(prepared.state)
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
  action: () => Promise<T> | T
): Promise<T> {
  if (state.integratedConsentLock) {
    return withIntegratedConsentStateFileLockForMaintenance(state.filePath, action);
  }
  return withExclusiveFileLockAsync(state.lockPath ?? `${state.filePath}.lock`, action);
}

async function withAllStateLocks<T>(
  states: readonly ManagedState[],
  index: number,
  action: () => Promise<T> | T
): Promise<T> {
  const state = states[index];
  if (state === undefined) return action();
  return withStateLock(
    state,
    () => withAllStateLocks(states, index + 1, action)
  );
}

export async function runGatewayStateMaintenance(
  command: GatewayStateMaintenanceCommand,
  environment: NodeJS.ProcessEnv = process.env
): Promise<GatewayStateMaintenanceResult[]> {
  if (environment.WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED !== "true") {
    throw new Error(
      "WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED=true is required after stopping the gateway service"
    );
  }
  return withExclusiveFileLockAsync(
    resolveGatewayServiceRuntimeLockPath(environment),
    async () => {
      initializeGatewayStateEncryption(environment);
      const states = managedStates(environment);
      return withAllStateLocks(states, 0, () => {
        const prepared = states.map((state) => prepareOne(command, state));
        return prepared.map(applyPrepared);
      });
    }
  );
}

async function main(): Promise<void> {
  const command = process.argv[2];
  if (!command || !["inspect", "migrate-plaintext", "rotate"].includes(command)) {
    throw new Error(
      "usage: state-encryption-maintenance <inspect|migrate-plaintext|rotate>"
    );
  }
  const results = await runGatewayStateMaintenance(
    command as GatewayStateMaintenanceCommand
  );
  process.stdout.write(`${JSON.stringify({ command, results })}\n`);
  if (results.some((result) => [
    "plaintext",
    "encrypted-compromised",
    "encrypted-unknown"
  ].includes(result.statusBefore) && result.action === "none")) {
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
