import { KeyedAdmissionQueue } from "./keyed-admission-queue.js";
import path from "node:path";
import { tmpdir } from "node:os";
import {
  mkdirSync,
  lstatSync,
  realpathSync,
  readdirSync,
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
import {
  type FileLockLease,
  acquireExclusiveFileLockLeaseAsync,
  ExclusiveFileLockBusyError
} from "./exclusive-file-lock.js";
import {
  maxGatewayStateEnvelopeBytes,
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  syncGatewayStateDirectoryAfterRename,
  GatewayStorageOutcomeUnknownError
} from "./encrypted-json-store.js";
import { randomBytes } from "node:crypto";
import {
  type IntegratedConsentState,
  type IntegratedConsentEvent,
  sha256,
  IntegratedConsentRequestError,
  type ConsentAuditSegment,
  canonicalJson,
  INTEGRATED_CONSENT_MAX_STATE_BYTES,
  type ConsentHistoryQuery,
  objectValue,
  exactKeys,
  SHA256,
  MAX_EVENTS,
  WITHDRAWAL_RESERVE_EVENTS,
  type ConsentHistoryCursor,
  auditSegmentPath,
  validIntegratedConsentAuditSegmentForMaintenance,
  validState,
  authoritativeEvent,
  NORMAL_MAX_STATE_BYTES,
  type ConsentAuditArchive
} from "./integrated-consent-codec.js";

const FILE_LOCK_RETRY_MS = 25;

const FILE_LOCK_MAX_ATTEMPTS = 100;

const installationAdmission = new KeyedAdmissionQueue(32, 1_000);

export const archivedRequestState = new WeakMap<IntegratedConsentState, {
  prior: IntegratedConsentEvent | undefined; actorFloor: number;
}>();

type ConsentStorageFaultStage = "archive_open" | "archive_file_fsync" |
  "archive_rename" | "archive_directory_fsync" | "current_file_fsync" | "current_rename";

let storageFaultForTests: ConsentStorageFaultStage | null = null;

/** Test seam for the separate durable archive/current commits; never enabled in production. */
export function setIntegratedConsentStorageFaultForTests(stage: ConsentStorageFaultStage | null): void {
  if (process.env.NODE_ENV === "production") throw new Error("consent storage test faults are disabled");
  storageFaultForTests = stage;
}

function storageFault(stage: ConsentStorageFaultStage): void {
  if (storageFaultForTests === stage) throw new Error(`injected consent ${stage} failure`);
}

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || process.getuid() === uid;
}

function stateParentDirectory(environment: NodeJS.ProcessEnv): string {
  const configured = environment.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim() ?? "";
  const production = process.env.NODE_ENV === "production" || environment.NODE_ENV === "production";
  if (!configured && production) {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  const directory = configured || path.join(tmpdir(), "walksafe-gateway-rate-limit-v1");
  if (!path.isAbsolute(directory)) throw new Error("gateway state directory must be absolute");
  if (production && path.resolve(directory) !== directory) {
    throw new Error("gateway state directory must be one canonical absolute path");
  }
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

function stateDirectory(environment: NodeJS.ProcessEnv = process.env): string {
  const parent = secureDirectory(stateParentDirectory(environment), "gateway state directory");
  return secureDirectory(path.join(parent, "integrated-consent"), "integrated consent directory");
}

function statePath(installationId: string): string {
  const digest = sha256(`integrated-consent\0${installationId}`);
  return path.join(stateDirectory(), `${digest}.json`);
}

/**
 * S19-22: the per-installation consent lock is the shared kernel lock
 * (exclusive-file-lock.ts): flock on the same `.lock` path, released by the kernel when
 * the descriptor closes or the process dies. The file content is never read, so an
 * empty/partial legacy PID record cannot wedge recovery, and a PID can never be mistaken
 * for the owner. A residual legacy `.reaper` hard link leaves the lock with two links and
 * fails closed (503 store_unavailable) until an operator removes it with the service stopped.
 */
type ConsentFileLock = FileLockLease;

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function acquireConsentFileLockAtPath(
  lockPath: string
): Promise<ConsentFileLock> {
  for (let attempt = 0; attempt < FILE_LOCK_MAX_ATTEMPTS; attempt += 1) {
    try {
      return await acquireExclusiveFileLockLeaseAsync(lockPath);
    } catch (error) {
      if (!(error instanceof ExclusiveFileLockBusyError)) throw error;
      await delay(FILE_LOCK_RETRY_MS);
    }
  }
  throw new IntegratedConsentRequestError(
    503,
    "integrated_consent_store_busy",
    "Consent storage is busy."
  );
}

async function acquireConsentFileLock(
  installationId: string
): Promise<ConsentFileLock> {
  return acquireConsentFileLockAtPath(`${statePath(installationId)}.lock`);
}

function releaseConsentFileLock(lock: ConsentFileLock): void {
  lock.release();
}

export async function withIntegratedConsentStateFileLockForMaintenance<T>(
  filePath: string,
  action: () => Promise<T> | T,
  environment: NodeJS.ProcessEnv = process.env
): Promise<T> {
  const resolved = path.resolve(filePath);
  if (
    resolved !== filePath ||
    path.dirname(resolved) !== stateDirectory(environment) ||
    !/^[0-9a-f]{64}\.json$/.test(path.basename(resolved))
  ) {
    throw new Error("integrated consent maintenance state path is invalid");
  }
  const lock = await acquireConsentFileLockAtPath(`${resolved}.lock`);
  try {
    return await action();
  } finally {
    releaseConsentFileLock(lock);
  }
}

function auditSegmentDigest(segment: ConsentAuditSegment): string {
  return sha256(canonicalJson(segment));
}

/** Archives use the installation's encryption kind, with distinct authenticated record IDs. */
export function integratedConsentAuditSegmentPaths(
  environment: NodeJS.ProcessEnv = process.env
): Array<{ segmentPath: string; statePath: string }> {
  const directory = stateDirectory(environment);
  const result: Array<{ segmentPath: string; statePath: string }> = [];
  for (const name of readdirSync(directory)) {
    // A killed writer can leave its exclusive temporary file before rename. It is not a
    // sealed archive and is preserved for diagnostics, without blocking unrelated stores.
    if (/^[0-9a-f]{64}\.json\.audit-[0-9]{8}\.[0-9]+\.[0-9a-f]{16}\.tmp$/.test(name)) continue;
    if (!name.includes(".audit-")) continue;
    const match = /^([0-9a-f]{64}\.json)\.audit-([0-9]{8})$/.exec(name);
    if (!match || Number(match[2]) === 0) {
      throw new Error("invalid integrated consent archive path");
    }
    result.push({ segmentPath: path.join(directory, name), statePath: path.join(directory, match[1]!) });
  }
  return result.sort((left, right) => left.segmentPath.localeCompare(right.segmentPath));
}

function readConsentFile(filePath: string, environment: NodeJS.ProcessEnv = process.env): unknown {
  const descriptor = openSync(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW | fsConstants.O_NONBLOCK);
  try {
    const metadata = fstatSync(descriptor);
    if (!metadata.isFile() || !ownedByCurrentProcess(metadata.uid) || metadata.nlink !== 1 ||
      (metadata.mode & 0o777) !== 0o600 || metadata.size < 2 ||
      metadata.size > maxGatewayStateEnvelopeBytes(INTEGRATED_CONSENT_MAX_STATE_BYTES)) {
      throw new Error("integrated consent file must be an owned regular file with mode 0600");
    }
    const raw = readFileSync(descriptor, "utf8");
    if (Buffer.byteLength(raw, "utf8") !== metadata.size) throw new Error("consent file changed during read");
    return decryptGatewayStateJson(
      { kind: "integrated-consent", recordId: path.basename(filePath) }, raw,
      INTEGRATED_CONSENT_MAX_STATE_BYTES, environment
    ).value;
  } finally {
    closeSync(descriptor);
  }
}

function expandArchivedState(
  value: unknown, filePath: string, environment: NodeJS.ProcessEnv,
  query?: ConsentHistoryQuery
): IntegratedConsentState {
  const state = objectValue(value);
  const installationId = state?.installation_id;
  const pointer = objectValue(state?.audit_archive);
  if (!state || state.schema_version !== 7 || typeof installationId !== "string" ||
    !exactKeys(state, ["schema_version", "installation_id", "control_secret_sha256", "field_actor_binding", "events", "revision", "audit_archive"]) ||
    !pointer || !exactKeys(pointer, ["segment_count", "archived_events", "last_segment_sha256"]) ||
    !Number.isSafeInteger(pointer.segment_count) || Number(pointer.segment_count) < 1 || Number(pointer.segment_count) > 99_999_999 ||
    !Number.isSafeInteger(pointer.archived_events) || Number(pointer.archived_events) < Number(pointer.segment_count) ||
    typeof pointer.last_segment_sha256 !== "string" || !SHA256.test(pointer.last_segment_sha256) ||
    !Array.isArray(state.events) || state.events.length < 1 || state.events.length > MAX_EVENTS + WITHDRAWAL_RESERVE_EVENTS ||
    !Number.isSafeInteger(state.revision) || state.revision !== Number(pointer.archived_events) + state.events.length) {
    throw new Error("invalid integrated consent archive boundary");
  }
  // Keep only one segment in memory. A cursor verifies the entire retained chain without
  // rebuilding an ever-growing event array; a PUT retains only its matching historical ID.
  const cursor: ConsentHistoryCursor = { revision: 0, audit: null, previousClientRevision: 0,
    actorRevisions: new Map(), authoritative: false };
  let prior: IntegratedConsentEvent | undefined;
  let previous: string | null = null;
  for (let sequence = 1; sequence <= Number(pointer.segment_count); sequence += 1) {
    const segmentPath = auditSegmentPath(filePath, sequence);
    const decoded = readConsentFile(segmentPath, environment);
    if (!validIntegratedConsentAuditSegmentForMaintenance(decoded, path.basename(segmentPath))) {
      throw new Error("invalid integrated consent archive");
    }
    const segment = decoded as ConsentAuditSegment;
    if (segment.installation_id !== installationId || segment.sequence !== sequence ||
      segment.previous_segment_sha256 !== previous || segment.events[0]!.revision !== cursor.revision + 1) {
      throw new Error("integrated consent archive chain mismatch");
    }
    const last = segment.events.at(-1)!;
    if (!validState({ schema_version: 6, installation_id: installationId,
      control_secret_sha256: state.control_secret_sha256,
      field_actor_binding: authoritativeEvent(last) ? { actor_sha256: last.field_actor_binding_sha256,
        consent_revision: last.revision, bound_at: last.confirmed_at } : null,
      events: segment.events }, installationId, true, cursor.revision, cursor.audit, cursor)) {
      throw new Error("invalid integrated consent archived history");
    }
    if (query && !prior) prior = segment.events.find((event) => event.request_id === query.requestId &&
      (!authoritativeEvent(event) || event.field_actor_binding_sha256 === query.actorSha256));
    previous = auditSegmentDigest(segment);
  }
  if (cursor.revision !== pointer.archived_events || previous !== pointer.last_segment_sha256) {
    throw new Error("integrated consent archive boundary mismatch");
  }
  const actorFloor = query ? cursor.actorRevisions.get(query.actorSha256) ?? 0 : 0;
  if (!validState({ schema_version: 6, installation_id: installationId,
    control_secret_sha256: state.control_secret_sha256, field_actor_binding: state.field_actor_binding,
    events: state.events }, installationId, true, cursor.revision, cursor.audit, cursor)) {
    throw new Error("invalid integrated consent history");
  }
  const result = state as unknown as IntegratedConsentState;
  archivedRequestState.set(result, { prior, actorFloor });
  return result;
}

export function validIntegratedConsentStateForMaintenance(
  value: unknown,
  recordId: string,
  environment: NodeJS.ProcessEnv = process.env
): boolean {
  const state = objectValue(value);
  const installationId = state?.installation_id;
  if (typeof installationId !== "string") return false;
  if (state?.schema_version === 7) {
    try {
      expandArchivedState(value, path.join(stateDirectory(environment), recordId), environment);
    } catch { return false; }
  } else if (!validState(value, installationId)) return false;
  return recordId === `${sha256(`integrated-consent\0${installationId}`)}.json`;
}

export function readState(installationId: string, query?: ConsentHistoryQuery): IntegratedConsentState {
  const target = statePath(installationId);
  let decoded: unknown;
  try {
    decoded = readConsentFile(target);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      if (integratedConsentAuditSegmentPaths().some((segment) => segment.statePath === target)) {
        throw new Error("integrated consent current state is missing beside retained audit");
      }
      return {
        schema_version: 6,
        installation_id: installationId,
        control_secret_sha256: "",
        field_actor_binding: null,
        events: []
      };
    }
    throw error;
  }
  if (objectValue(decoded)?.schema_version === 7) {
    if (objectValue(decoded)?.installation_id !== installationId) throw new Error("consent installation mismatch");
    return expandArchivedState(decoded, target, process.env, query);
  }
  if (!validState(decoded, installationId)) throw new Error("invalid integrated consent state");
  return decoded;
}

export function writeState(state: IntegratedConsentState): void {
  const persisted = state.schema_version === 7 ? {
    ...state,
    revision: state.revision
  } : state;
  const plaintext = `${JSON.stringify(persisted)}\n`;
  if (Buffer.byteLength(plaintext, "utf8") > INTEGRATED_CONSENT_MAX_STATE_BYTES) {
    throw new Error("integrated consent state capacity exceeded");
  }
  const target = statePath(state.installation_id);
  const serialized = encryptGatewayStateJson(
    { kind: "integrated-consent", recordId: path.basename(target) },
    persisted,
    INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  const temporary = `${target}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, serialized, "utf8");
    storageFault("current_file_fsync");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    chmodSync(temporary, 0o600);
    storageFault("current_rename");
    renameSync(temporary, target);
    // S19-27: from here the new consent state may be durable (outcome unknown on failure).
    syncGatewayStateDirectoryAfterRename("integrated-consent", path.dirname(target));
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

function syncConsentDirectory(directory: string): void {
  const descriptor = openSync(directory, fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW);
  try { fsyncSync(descriptor); } finally { closeSync(descriptor); }
}

function sealAuditSegment(filePath: string, segment: ConsentAuditSegment): void {
  const temporary = `${filePath}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  try {
    storageFault("archive_open");
    descriptor = openSync(temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW, 0o600);
    writeFileSync(descriptor, encryptGatewayStateJson(
      { kind: "integrated-consent", recordId: path.basename(filePath) }, segment,
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    ), "utf8");
    storageFault("archive_file_fsync");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    storageFault("archive_rename");
    renameSync(temporary, filePath);
    storageFault("archive_directory_fsync");
    syncConsentDirectory(path.dirname(filePath));
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

/**
 * Sealing is a separate transaction with no permission change. Archives become durable
 * before the compact current state references them. An archive-only crash leaves the old
 * main state authoritative; retry verifies and fsyncs the exact retained prefix, never
 * replaces an orphan. Failed sealing keeps the four-event withdrawal reserve available.
 */
export function archiveConsentHistory(state: IntegratedConsentState): boolean {
  const archived = state.audit_archive?.archived_events ?? 0;
  const tail = state.events;
  if (tail.length < MAX_EVENTS && Buffer.byteLength(JSON.stringify({ ...state, events: tail }), "utf8") + 4096 <= NORMAL_MAX_STATE_BYTES) {
    return true;
  }
  if (tail.length < 2) return false;
  const target = statePath(state.installation_id);
  const sequence = (state.audit_archive?.segment_count ?? 0) + 1;
  let pointer: ConsentAuditArchive;
  try {
    const segmentPath = auditSegmentPath(target, sequence);
    let orphan: unknown;
    try { orphan = readConsentFile(segmentPath); } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    const eventCount = orphan === undefined ? Math.min(MAX_EVENTS - 1, tail.length - 1)
      : (objectValue(orphan)?.events as unknown[])?.length;
    if (!Number.isSafeInteger(eventCount) || !eventCount || eventCount >= tail.length) {
      throw new Error("consent orphan archive prefix is invalid");
    }
    const segment: ConsentAuditSegment = {
      schema_version: "walksafe.integrated-consent-audit.v1",
      installation_id: state.installation_id,
      sequence,
      previous_segment_sha256: state.audit_archive?.last_segment_sha256 ?? null,
      events: tail.slice(0, eventCount)
    };
    if (orphan !== undefined) {
      if (!validIntegratedConsentAuditSegmentForMaintenance(orphan, path.basename(segmentPath)) ||
        canonicalJson(orphan) !== canonicalJson(segment)) {
        throw new Error("consent orphan archive does not match retained audit history");
      }
      // A prior archive directory-fsync failure must be repaired before publishing its pointer.
      const descriptor = openSync(segmentPath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
      try { storageFault("archive_file_fsync"); fsyncSync(descriptor); } finally { closeSync(descriptor); }
      storageFault("archive_directory_fsync");
      syncConsentDirectory(path.dirname(segmentPath));
    } else {
      sealAuditSegment(segmentPath, segment);
    }
    pointer = { segment_count: sequence, archived_events: archived + eventCount,
      last_segment_sha256: auditSegmentDigest(segment) };
  } catch {
    return false;
  }
  const compacted: IntegratedConsentState = { ...state, schema_version: 7,
    revision: state.revision ?? state.events.length, audit_archive: pointer,
    events: tail.slice(pointer.archived_events - archived) };
  try { writeState(compacted); } catch (error) {
    if (error instanceof GatewayStorageOutcomeUnknownError) throw error;
    return false;
  }
  state.schema_version = 7;
  state.revision = compacted.revision!;
  state.audit_archive = pointer;
  state.events = compacted.events;
  return true;
}

export async function withInstallationLock<T>(
  installationId: string,
  action: () => Promise<T> | T,
  signal?: AbortSignal
): Promise<T> {
  return installationAdmission.run(installationId, async () => {
    const fileLock = await acquireConsentFileLock(installationId);
    try {
      return await action();
    } finally {
      releaseConsentFileLock(fileLock);
    }
  }, signal);
}

