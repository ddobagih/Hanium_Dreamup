import {
  GatewayStorageOutcomeUnknownError,
  maxGatewayStateEnvelopeBytes,
  decryptGatewayStateJson,
  encryptGatewayStateJson
} from "./encrypted-json-store.js";
import path from "node:path";
import { tmpdir } from "node:os";
import { createHash, randomUUID } from "node:crypto";
import { withExclusiveFileLock } from "./exclusive-file-lock.js";
import {
  openSync,
  constants as fsConstants,
  fstatSync,
  readFileSync,
  closeSync,
  mkdirSync,
  lstatSync,
  realpathSync,
  writeFileSync,
  fsyncSync,
  renameSync,
  rmSync,
  readdirSync
} from "node:fs";
import { requiredGatewayStateFileWasLost, registerRequiredGatewayStateFile } from "./state-init-marker.js";
import {
  limits,
  TERMINAL_BYTES_PER_AUDIT_ENTRY,
  type FieldWalkLedgerOptions,
  type LedgerState,
  ownEntry,
  setOwnEntry,
  SHARD_LAYOUT,
  emptyRecord,
  type AccountLedger,
  type SavedRequest,
  validLedgerState,
  FIELD_WALK_AUDIT_SEGMENT_ENTRIES,
  type AuditSegment,
  AUDIT_SEGMENT_FILE,
  validAuditEntry,
  MAX_AUDIT_SEGMENT_SEQUENCE,
  AUDIT_SEGMENT_SCHEMA,
  validAuditSegment,
  type AuditEntry
} from "./field-walk-codec.js";

type LedgerFaultStage = "file_fsync" | "rename" | "directory_fsync" | "audit_archive";

let testFaultStage: LedgerFaultStage | null = null;

export class FieldWalkLedgerCapacityError extends Error {
  constructor(message = "field walk ledger capacity reached") {
    super(message);
    this.name = "FieldWalkLedgerCapacityError";
  }
}

export class FieldWalkLedgerStorageOutcomeUnknownError extends GatewayStorageOutcomeUnknownError {
  constructor(cause: unknown) {
    super("field walk ledger", { cause });
    this.name = "FieldWalkLedgerStorageOutcomeUnknownError";
  }
}

/** S19-26: the ledger was registered as initialized state and is now missing. */
export class FieldWalkLedgerStateMissingError extends Error {
  readonly code = "state_file_missing";

  constructor(readonly filePath: string) {
    super("field walk ledger is missing after initialization");
    this.name = "FieldWalkLedgerStateMissingError";
  }
}

export function setFieldWalkLedgerTestFault(stage: LedgerFaultStage | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("field walk ledger test faults are unavailable in production");
  }
  testFaultStage = stage;
}

function terminalLedgerByteLimit(): number {
  return limits().maxLedgerBytes +
    limits().maxAuditEntries * TERMINAL_BYTES_PER_AUDIT_ENTRY;
}

function injectFault(stage: LedgerFaultStage): void {
  if (testFaultStage === stage) throw new Error(`injected field walk ledger ${stage} failure`);
}

export function ledgerPath(options: FieldWalkLedgerOptions): string {
  const configured = options.ledgerPath
    ?? process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH?.trim();
  if (!configured && process.env.NODE_ENV === "production") {
    throw new Error("WALKSAFE_FIELD_WALK_LEDGER_PATH is required in production");
  }
  if (configured && !path.isAbsolute(configured)) {
    throw new Error("field walk ledger path must be absolute");
  }
  return path.resolve(
    configured || path.join(tmpdir(), "walksafe-android-gateway", "field-walk-ledger.json")
  );
}

/**
 * S19-14: accounts are spread over a fixed set of shard files by a hash of the account id.
 * Each shard is a complete ledger (same schema, its own accounts and audit) with its own
 * kernel lock, so a heartbeat reads, validates, encrypts, and fsyncs only its shard, and
 * accounts in different shards never wait for each other. Fencing stays per account inside
 * the account's home shard. Shard 0 is the configured path itself, so a ledger written by an
 * earlier single-file build is the legacy shard: an account found there but homed elsewhere
 * is moved once (home shard written first, then removed from shard 0) under both locks.
 */
export const FIELD_WALK_LEDGER_SHARD_COUNT = 16;

const legacyShardClean = new Set<string>();

function shardIndexForAccount(accountId: string): number {
  return createHash("sha256").update(accountId, "utf8").digest()[0]! %
    FIELD_WALK_LEDGER_SHARD_COUNT;
}

function shardFilePath(basePath: string, index: number): string {
  return index === 0 ? basePath : `${basePath}.shard-${String(index).padStart(2, "0")}`;
}

export function fieldWalkLedgerShardPaths(basePath: string): string[] {
  const resolved = path.resolve(basePath);
  return Array.from(
    { length: FIELD_WALK_LEDGER_SHARD_COUNT },
    (_, index) => shardFilePath(resolved, index)
  );
}

export function fieldWalkLedgerShardPathForTests(basePath: string, accountId: string): string {
  if (process.env.NODE_ENV === "production") {
    throw new Error("field walk ledger shard paths for tests are unavailable in production");
  }
  return shardFilePath(path.resolve(basePath), shardIndexForAccount(accountId));
}

function migrateLegacyAccount(
  basePath: string,
  filePath: string,
  state: LedgerState,
  accountId: string
): void {
  if (legacyShardClean.has(basePath)) return;
  // Lock order is always home shard -> shard 0, so no two paths wait on each other.
  withExclusiveFileLock(ledgerLockPath(basePath), () => {
    const legacy = loadState(basePath);
    const legacyAccount = ownEntry(legacy.accounts, accountId);
    if (legacyAccount) {
      setOwnEntry(state.accounts, accountId, legacyAccount);
      state.audit.push(...legacy.audit.filter((entry) => entry.accountId === accountId));
      saveState(filePath, state, true);
      delete legacy.accounts[accountId];
      legacy.audit = legacy.audit.filter((entry) => entry.accountId !== accountId);
      saveState(basePath, legacy, true);
    }
    if (!Object.keys(legacy.accounts).some((id) => shardIndexForAccount(id) !== 0)) {
      legacyShardClean.add(basePath);
    }
  });
}

const shardZeroStamped = new Set<string>();

/** S19-14-F1: stamps shard 0 (under its lock, home shard -> shard 0 order) before any other shard is used. */
function stampShardZero(basePath: string): void {
  if (shardZeroStamped.has(basePath)) return;
  withExclusiveFileLock(ledgerLockPath(basePath), () => {
    const legacy = loadState(basePath);
    if (legacy.shardLayout !== SHARD_LAYOUT) {
      legacy.shardLayout = SHARD_LAYOUT;
      saveState(basePath, legacy, true);
    }
  });
  shardZeroStamped.add(basePath);
}

/** Runs `action` under the account's home shard lock with that shard's state. */
export function withAccountShard<T>(
  options: FieldWalkLedgerOptions,
  accountId: string,
  action: (filePath: string, state: LedgerState) => T
): T {
  const basePath = ledgerPath(options);
  const index = shardIndexForAccount(accountId);
  const filePath = shardFilePath(basePath, index);
  // This lock is a single-host local-filesystem boundary. Multi-host coordination
  // requires an external transactional store and is intentionally not implemented here.
  const critical = (): T => {
    if (index !== 0) stampShardZero(basePath);
    const state = loadState(filePath);
    if (index !== 0 && !ownEntry(state.accounts, accountId)) {
      migrateLegacyAccount(basePath, filePath, state, accountId);
    }
    return action(filePath, state);
  };
  // `action` is synchronous for every caller; the generic cannot prove it to the checker.
  return withExclusiveFileLock<() => T>(ledgerLockPath(filePath), critical as never);
}

export function ledgerLockPath(filePath: string): string {
  const lockPath = path.resolve(`${filePath}.lock`);
  if (
    lockPath === filePath ||
    path.dirname(lockPath) !== path.dirname(filePath) ||
    path.basename(lockPath) === path.basename(filePath)
  ) {
    throw new Error("field walk ledger and lock must be distinct sibling paths");
  }
  return lockPath;
}

function emptyState(): LedgerState {
  return {
    schemaVersion: "walksafe.field-walk-ledger.v1",
    accounts: emptyRecord<AccountLedger>(),
    audit: []
  };
}

function safeLedgerMaps(state: LedgerState): LedgerState {
  const accounts = emptyRecord<AccountLedger>();
  for (const [accountId, account] of Object.entries(state.accounts)) {
    const requests = emptyRecord<SavedRequest>();
    for (const [requestId, saved] of Object.entries(account.requests)) {
      setOwnEntry(requests, requestId, saved);
    }
    if (account.idempotency) {
      const watermarks = emptyRecord<number>();
      for (const [deviceId, issuedAt] of Object.entries(
        account.idempotency.evictedIssuedAtByDevice
      )) {
        setOwnEntry(watermarks, deviceId, issuedAt);
      }
      setOwnEntry(accounts, accountId, {
        ...account,
        requests,
        idempotency: {
          evictedIssuedAtByDevice: watermarks,
          evictedLegacyStartDigests: [...account.idempotency.evictedLegacyStartDigests]
        }
      });
      continue;
    }
    setOwnEntry(accounts, accountId, { ...account, requests });
  }
  return { ...state, accounts };
}

function loadState(filePath: string): LedgerState {
  let descriptor: number | null = null;
  try {
    descriptor = openSync(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600
    ) {
      throw new Error("field walk ledger must be an owned regular file with mode 0600 and one link");
    }
    if (metadata.size > maxGatewayStateEnvelopeBytes(terminalLedgerByteLimit())) {
      throw new FieldWalkLedgerCapacityError("field walk ledger exceeds its byte ceiling");
    }
    const raw = readFileSync(descriptor, "utf8");
    if (
      Buffer.byteLength(raw, "utf8") >
      maxGatewayStateEnvelopeBytes(terminalLedgerByteLimit())
    ) {
      throw new FieldWalkLedgerCapacityError("field walk ledger exceeds its byte ceiling");
    }
    const state = decryptGatewayStateJson(
      { kind: "field-walk-ledger", recordId: path.basename(filePath) },
      raw,
      terminalLedgerByteLimit()
    ).value;
    if (!validLedgerState(state)) {
      throw new Error("invalid field walk ledger");
    }
    return safeLedgerMaps(state);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      // S19-26: only a never-initialized ledger may start empty (fencing token 1).
      if (requiredGatewayStateFileWasLost(filePath)) {
        throw new FieldWalkLedgerStateMissingError(filePath);
      }
      return emptyState();
    }
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

export function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

export function secureLedgerDirectory(filePath: string): string {
  const directory = path.dirname(filePath);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const metadata = lstatSync(directory);
  if (
    !metadata.isDirectory() ||
    metadata.isSymbolicLink() ||
    realpathSync(directory) !== directory ||
    !ownedByCurrentProcess(metadata.uid) ||
    (metadata.mode & 0o077) !== 0
  ) {
    throw new Error("field walk ledger directory must be real, owned, and mode 0700");
  }
  return directory;
}

export function saveState(filePath: string, state: LedgerState, terminal = false): void {
  // S19-13: seal a full audit tail first; the segment is durable before this state names it.
  archiveAuditTail(filePath, state);
  if (!validLedgerState(state)) {
    throw new FieldWalkLedgerCapacityError("field walk ledger structural ceiling reached");
  }
  const plaintext = Buffer.from(`${JSON.stringify(state)}\n`, "utf8");
  const maxBytes = terminal ? terminalLedgerByteLimit() : limits().maxLedgerBytes;
  if (plaintext.byteLength > maxBytes) {
    throw new FieldWalkLedgerCapacityError("field walk ledger exceeds its byte ceiling");
  }
  const serialized = Buffer.from(
    encryptGatewayStateJson(
      { kind: "field-walk-ledger", recordId: path.basename(filePath) },
      state,
      maxBytes
    ),
    "utf8"
  );
  const directory = secureLedgerDirectory(filePath);
  const temporary = path.join(directory, `.${path.basename(filePath)}.${randomUUID()}.tmp`);
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  let committed = false;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY |
        fsConstants.O_CREAT |
        fsConstants.O_EXCL |
        fsConstants.O_NOFOLLOW,
      0o600
    );
    const temporaryMetadata = fstatSync(descriptor);
    if (
      !temporaryMetadata.isFile() ||
      temporaryMetadata.nlink !== 1 ||
      !ownedByCurrentProcess(temporaryMetadata.uid) ||
      (temporaryMetadata.mode & 0o777) !== 0o600
    ) {
      throw new Error("field walk ledger temporary file failed integrity validation");
    }
    writeFileSync(descriptor, serialized);
    injectFault("file_fsync");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    injectFault("rename");
    renameSync(temporary, filePath);
    committed = true;
    directoryDescriptor = openSync(
      directory,
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
    );
    const directoryMetadata = fstatSync(directoryDescriptor);
    if (
      !directoryMetadata.isDirectory() ||
      !ownedByCurrentProcess(directoryMetadata.uid) ||
      (directoryMetadata.mode & 0o077) !== 0
    ) {
      throw new Error("field walk ledger directory changed during commit");
    }
    injectFault("directory_fsync");
    fsyncSync(directoryDescriptor);
    // S19-26: after the first durable write the ledger is required state.
    registerRequiredGatewayStateFile(filePath);
  } catch (error) {
    if (committed) throw new FieldWalkLedgerStorageOutcomeUnknownError(error);
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    if (!committed) {
      try {
        rmSync(temporary, { force: true });
      } catch {
        // Preserve the original persistence error.
      }
    }
  }
}

function auditRotationThreshold(): number {
  const current = limits();
  return Math.min(
    current.auditSegmentEntries ?? FIELD_WALK_AUDIT_SEGMENT_ENTRIES,
    current.maxAuditEntries
  );
}

function auditSegmentPath(filePath: string, sequence: number): string {
  return `${filePath}.audit-${String(sequence).padStart(8, "0")}`;
}

function auditSegmentDigest(segment: AuditSegment): string {
  return createHash("sha256").update(JSON.stringify(segment), "utf8").digest("hex");
}

/**
 * S19-13 (maintenance, key rotation): every existing audit segment of every shard of the
 * ledger at `basePath`, with the shard file it belongs to.
 */
export function fieldWalkAuditSegmentPaths(
  basePath: string
): Array<{ segmentPath: string; shardPath: string }> {
  const shards = new Map(
    fieldWalkLedgerShardPaths(basePath).map((shard) => [path.basename(shard), shard])
  );
  const directory = path.dirname(path.resolve(basePath));
  let names: string[];
  try {
    names = readdirSync(directory);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw error;
  }
  const segments: Array<{ segmentPath: string; shardPath: string }> = [];
  for (const name of names) {
    const match = AUDIT_SEGMENT_FILE.exec(name);
    const shardPath = match ? shards.get(match[1]!) : undefined;
    if (shardPath) segments.push({ segmentPath: path.join(directory, name), shardPath });
  }
  return segments.sort((left, right) => left.segmentPath.localeCompare(right.segmentPath));
}

function writeAuditSegment(segmentPath: string, segment: AuditSegment): void {
  injectFault("audit_archive");
  const maxBytes = terminalLedgerByteLimit();
  const serialized = Buffer.from(
    encryptGatewayStateJson(
      { kind: "field-walk-ledger", recordId: path.basename(segmentPath) },
      segment,
      maxBytes
    ),
    "utf8"
  );
  const directory = secureLedgerDirectory(segmentPath);
  const temporary = path.join(directory, `.${path.basename(segmentPath)}.${randomUUID()}.tmp`);
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  let renamed = false;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600
    );
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600
    ) {
      throw new Error("field walk audit segment temporary file failed integrity validation");
    }
    writeFileSync(descriptor, serialized);
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    // An orphan with this sequence (sealed before a shard write that did not commit) is
    // replaced: the shard never named it.
    renameSync(temporary, segmentPath);
    renamed = true;
    directoryDescriptor = openSync(
      directory,
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
    );
    fsyncSync(directoryDescriptor);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    if (!renamed) {
      try {
        rmSync(temporary, { force: true });
      } catch {
        // Preserve the original persistence error.
      }
    }
  }
}

/**
 * S19-13: moves a full audit tail into the next sealed segment. The segment is fsynced (file
 * and directory) before the caller writes the shard that points to it; a crash in between
 * leaves an unnamed orphan that the next rotation overwrites. If the segment cannot be
 * written the tail stays in the shard, so the earlier ceilings still bound it: new leases
 * fail closed at `maxAuditEntries` and ending or expiring keeps its reserve.
 */
function archiveAuditTail(filePath: string, state: LedgerState): void {
  if (state.audit.length < auditRotationThreshold()) return;
  if (!state.audit.every(validAuditEntry)) return;
  const previous = state.auditArchive;
  const sequence = (previous?.segmentCount ?? 0) + 1;
  if (sequence > MAX_AUDIT_SEGMENT_SEQUENCE) return;
  const segment: AuditSegment = {
    schemaVersion: AUDIT_SEGMENT_SCHEMA,
    ledgerFile: path.basename(filePath),
    sequence,
    previousSegmentSha256: previous?.lastSegmentSha256 ?? null,
    entries: state.audit
  };
  try {
    writeAuditSegment(auditSegmentPath(filePath, sequence), segment);
  } catch {
    return;
  }
  state.auditArchive = {
    segmentCount: sequence,
    archivedEntries: (previous?.archivedEntries ?? 0) + segment.entries.length,
    lastSegmentSha256: auditSegmentDigest(segment)
  };
  state.audit = [];
}

function readAuditSegment(filePath: string, sequence: number): AuditSegment {
  const segmentPath = auditSegmentPath(filePath, sequence);
  let descriptor: number | null = null;
  try {
    descriptor = openSync(segmentPath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size > maxGatewayStateEnvelopeBytes(terminalLedgerByteLimit())
    ) {
      throw new Error("field walk audit segment must be an owned regular file with mode 0600");
    }
    const value = decryptGatewayStateJson(
      { kind: "field-walk-ledger", recordId: path.basename(segmentPath) },
      readFileSync(descriptor, "utf8"),
      terminalLedgerByteLimit()
    ).value;
    if (!validAuditSegment(value, path.basename(filePath), sequence)) {
      throw new Error("field walk audit archive integrity check failed");
    }
    return value;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

export type FieldWalkAuditRecord = Readonly<AuditEntry>;

/**
 * S19-13: the complete audit history of every shard (sealed segments in order, then the
 * shard's current tail). Each segment is checked against the chain the shard names; a
 * missing, reordered, or altered segment fails closed. Orphan segments are ignored.
 */
export function readFieldWalkAuditHistory(options: FieldWalkLedgerOptions = {}): FieldWalkAuditRecord[] {
  const basePath = ledgerPath(options);
  const history: FieldWalkAuditRecord[] = [];
  for (const filePath of fieldWalkLedgerShardPaths(basePath)) {
    const shard = withExclusiveFileLock<() => AuditEntry[]>(ledgerLockPath(filePath), (() => {
      const state = loadState(filePath);
      const entries: AuditEntry[] = [];
      const pointer = state.auditArchive;
      let previous: string | null = null;
      for (let sequence = 1; pointer && sequence <= pointer.segmentCount; sequence += 1) {
        const segment = readAuditSegment(filePath, sequence);
        if (segment.previousSegmentSha256 !== previous) {
          throw new Error("field walk audit archive integrity check failed");
        }
        previous = auditSegmentDigest(segment);
        entries.push(...segment.entries);
      }
      if (
        pointer &&
        (previous !== pointer.lastSegmentSha256 || entries.length !== pointer.archivedEntries)
      ) {
        throw new Error("field walk audit archive integrity check failed");
      }
      entries.push(...state.audit);
      return entries;
    }) as never);
    history.push(...shard);
  }
  return history;
}

