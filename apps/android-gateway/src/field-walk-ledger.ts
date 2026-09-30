import { createHash, randomUUID } from "node:crypto";
import {
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
  readdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import type { GatewayFieldLongSessionBinding } from "./auth.js";
import { withExclusiveFileLock } from "./exclusive-file-lock.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GatewayStorageOutcomeUnknownError,
  maxGatewayStateEnvelopeBytes
} from "./encrypted-json-store.js";
import {
  registerRequiredGatewayStateFile,
  requiredGatewayStateFileWasLost
} from "./state-init-marker.js";
import { recordGatewayStateEvent, type GatewayClockTelemetryMode } from "./telemetry.js";

const COMMAND_SCHEMA = "walksafe.field-walk-command.v1";
/**
 * S19-17: v2 adds the client's `issued_at_epoch_ms`, fixed when the request id is created
 * and part of the canonical command, so the ledger can bound idempotency by time.
 */
const COMMAND_SCHEMA_V2 = "walksafe.field-walk-command.v2";
const RESPONSE_SCHEMA = "walksafe.field-walk-response.v1";
const LEASE_TTL_MS = 90_000;
/**
 * S19-17: documented idempotency window. A v2 request is replayed from the stored result
 * while it is cached; a v2 request issued more than this long before (or after) server
 * time, or older than the newest evicted request of the same device, is rejected with
 * 409 idempotency_expired and never executed as a new command.
 */
export const FIELD_WALK_IDEMPOTENCY_WINDOW_MS = 24 * 60 * 60 * 1000;
const MAX_IDEMPOTENCY_WATERMARK_DEVICES = 64;
const MAX_EVICTED_LEGACY_START_DIGESTS = 256;
const LEGACY_START_DIGEST = /^[A-Za-z0-9_-]{22}$/;
/**
 * S19-20: a wall-clock step larger than this between two ledger reads (measured against the
 * monotonic clock) is treated as a clock change, not elapsed time.
 */
export const FIELD_WALK_CLOCK_STEP_TOLERANCE_MS = 2_000;
const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const DEFAULT_MAX_REQUESTS_PER_ACCOUNT = 4_096;
const DEFAULT_MAX_AUDIT_ENTRIES = 8_192;
export const DEFAULT_MAX_LEDGER_BYTES = 16 * 1024 * 1024;
const TERMINAL_BYTES_PER_AUDIT_ENTRY = 4096;
export const MAX_STORED_LEDGER_BYTES = DEFAULT_MAX_LEDGER_BYTES +
  DEFAULT_MAX_AUDIT_ENTRIES * TERMINAL_BYTES_PER_AUDIT_ENTRY;
/**
 * S19-13/S19-14/S01-28: a shard keeps at most this many audit entries next to its account
 * state. Reaching it seals the entries into the next encrypted archive segment
 * (`<shard>.audit-NNNNNNNN`, written and fsynced before the shard state that points to it),
 * so the audit history no longer bounds walking and a heartbeat rewrites a bounded file.
 * Segments are never deleted automatically (no retention period is decided yet).
 */
export const FIELD_WALK_AUDIT_SEGMENT_ENTRIES = 1_024;
const AUDIT_SEGMENT_SCHEMA = "walksafe.field-walk-audit-segment.v1";
const MAX_AUDIT_SEGMENT_SEQUENCE = 99_999_999;
const SHA256_HEX = /^[0-9a-f]{64}$/;

type LedgerLimits = {
  maxRequestsPerAccount: number;
  maxAuditEntries: number;
  maxLedgerBytes: number;
  /** Tests only: rotation threshold (defaults to FIELD_WALK_AUDIT_SEGMENT_ENTRIES). */
  auditSegmentEntries?: number;
};

type LedgerFaultStage = "file_fsync" | "rename" | "directory_fsync" | "audit_archive";

let testLimits: LedgerLimits | null = null;
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

export function setFieldWalkLedgerTestLimits(limits: LedgerLimits | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("field walk ledger test limits are unavailable in production");
  }
  if (
    limits !== null &&
    (!Number.isSafeInteger(limits.maxRequestsPerAccount) ||
      limits.maxRequestsPerAccount < 1 ||
      !Number.isSafeInteger(limits.maxAuditEntries) ||
      limits.maxAuditEntries < 1 ||
      !Number.isSafeInteger(limits.maxLedgerBytes) ||
      limits.maxLedgerBytes < 1 ||
      (limits.auditSegmentEntries !== undefined &&
        (!Number.isSafeInteger(limits.auditSegmentEntries) || limits.auditSegmentEntries < 1)))
  ) {
    throw new Error("invalid field walk ledger test limits");
  }
  testLimits = limits;
}

export function setFieldWalkLedgerTestFault(stage: LedgerFaultStage | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("field walk ledger test faults are unavailable in production");
  }
  testFaultStage = stage;
}

function limits(): LedgerLimits {
  return testLimits ?? {
    maxRequestsPerAccount: DEFAULT_MAX_REQUESTS_PER_ACCOUNT,
    maxAuditEntries: DEFAULT_MAX_AUDIT_ENTRIES,
    maxLedgerBytes: DEFAULT_MAX_LEDGER_BYTES
  };
}

function terminalLedgerByteLimit(): number {
  return limits().maxLedgerBytes +
    limits().maxAuditEntries * TERMINAL_BYTES_PER_AUDIT_ENTRY;
}

function injectFault(stage: LedgerFaultStage): void {
  if (testFaultStage === stage) throw new Error(`injected field walk ledger ${stage} failure`);
}

type ActiveWalk = {
  walkId: string;
  leaseId: string;
  fencingToken: number;
  actorId: string;
  deviceId: string;
  familyId: string;
  acquiredAtEpochMs: number;
  leaseExpiresAtEpochMs: number;
};

type SavedRequest = {
  canonicalCommand: string;
  actorId: string;
  deviceId: string;
  familyId: string;
  response: Record<string, unknown>;
};

/**
 * S19-17: what the bounded request cache forgot. Present only after an eviction, so ledgers
 * that never evicted keep the earlier schema byte-for-byte.
 */
type AccountIdempotency = {
  /** Per device: newest `issued_at_epoch_ms` among evicted v2 requests. */
  evictedIssuedAtByDevice: Record<string, number>;
  /** v1 has no request time: digests of evicted v1 start request ids (FIFO, bounded). */
  evictedLegacyStartDigests: string[];
};

/**
 * Owner decision 3(b) (2026-09-29): RAW segments are uploaded only after their walk ended,
 * under the walk's original id. The phone keeps them up to 30 days (FP-035), so an account
 * remembers the walks it recently ended for the RAW relay's write gate.
 */
export const FIELD_WALK_ENDED_HISTORY_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
/**
 * Per-account bound; the oldest ended walk is dropped first (its uploads are then refused and
 * the phone deletes those segments). transfer-F6: 256 covers more than eight walks a day for the
 * whole 30-day window, so a phone that rarely meets Wi-Fi plus charging still keeps its walks.
 */
export const FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT = 256;

type EndedWalk = {
  walkId: string;
  actorId: string;
  deviceId: string;
  endedAtEpochMs: number;
  reason: "ENDED" | "EXPIRED" | "TAKEN_OVER";
  /**
   * S01-31 (R11): for an explicit end only, the issue time of the ending command
   * (`issued_at_epoch_ms` of a v2 end; the server time for a v1 end, logout or remote
   * revoke). A start or takeover of the walk issued at or before it is a replay and is
   * refused; one issued later is a new command. Records written before this field existed
   * lack it and stay refused (conservative).
   */
  endIssuedAtEpochMs?: number;
};

type AccountLedger = {
  nextFencingToken: number;
  active: ActiveWalk | null;
  requests: Record<string, SavedRequest>;
  idempotency?: AccountIdempotency;
  /**
   * Decision 3(b): present only once a walk of the account ended under this build, so ledgers
   * without an ended walk keep the earlier schema byte-for-byte (like `idempotency`). An
   * earlier build refuses the extra key and fails closed; rolling back after it is written is
   * unsupported.
   */
  endedWalks?: EndedWalk[];
};

type AuditEntry = {
  transition: "ACQUIRED" | "EXPIRED" | "TAKEN_OVER" | "RENEWED" | "ENDED";
  accountId: string;
  walkId: string;
  leaseId: string;
  fencingToken: number;
  actorId: string;
  deviceId: string;
  familyId: string;
  occurredAtEpochMs: number;
  previousLeaseId?: string;
  previousFencingToken?: number;
};

/**
 * S19-14-F1 (w22 review): shard 0 carries this stamp once any account shard can be in use.
 * A pre-shard build reads only shard 0 with an exact three-key check, so it refuses the
 * stamped file instead of silently restarting fencing for accounts that moved to other
 * shards. Rolling back after the first shard write is unsupported.
 */
const SHARD_LAYOUT = "walksafe.field-walk-ledger-shards.v1";

/**
 * S19-13: where the shard's sealed audit history ends. Present only after the first
 * rotation, so shards that never rotated keep the earlier schema byte-for-byte. An earlier
 * build refuses the extra key and fails closed; rolling back after a rotation is unsupported.
 */
type AuditArchivePointer = {
  segmentCount: number;
  archivedEntries: number;
  /** sha256 (hex) of the last segment's canonical plaintext JSON; each segment chains the previous. */
  lastSegmentSha256: string;
};

type AuditSegment = {
  schemaVersion: typeof AUDIT_SEGMENT_SCHEMA;
  ledgerFile: string;
  sequence: number;
  previousSegmentSha256: string | null;
  entries: AuditEntry[];
};

type LedgerState = {
  schemaVersion: "walksafe.field-walk-ledger.v1";
  accounts: Record<string, AccountLedger>;
  audit: AuditEntry[];
  shardLayout?: typeof SHARD_LAYOUT;
  auditArchive?: AuditArchivePointer;
};

type CommandSchema = typeof COMMAND_SCHEMA | typeof COMMAND_SCHEMA_V2;

type StartCommand = {
  schema_version: CommandSchema;
  request_id: string;
  action: "start";
  walk_id: string;
  issued_at_epoch_ms?: number;
};

type TakeoverCommand = {
  schema_version: CommandSchema;
  request_id: string;
  action: "takeover";
  walk_id: string;
  expected_active_walk_id: string;
  expected_fencing_token: number;
  confirmation: "voice_confirmed";
  issued_at_epoch_ms?: number;
};

type LeaseCommand = {
  schema_version: CommandSchema;
  request_id: string;
  action: "renew" | "end";
  walk_id: string;
  lease_id: string;
  fencing_token: number;
  issued_at_epoch_ms?: number;
};

type FieldWalkCommand = StartCommand | TakeoverCommand | LeaseCommand;

export type FieldWalkLedgerResult = {
  status: number;
  body: Record<string, unknown>;
};

export type FieldWalkLedgerOptions = {
  nowEpochMs?: number;
  ledgerPath?: string;
};

function emptyRecord<T>(): Record<string, T> {
  return Object.create(null) as Record<string, T>;
}

function ownEntry<T>(entries: Record<string, T>, key: string): T | undefined {
  return Object.prototype.hasOwnProperty.call(entries, key)
    ? entries[key]
    : undefined;
}

function setOwnEntry<T>(entries: Record<string, T>, key: string, value: T): void {
  Object.defineProperty(entries, key, {
    value,
    enumerable: true,
    configurable: true,
    writable: true
  });
}

function ledgerPath(options: FieldWalkLedgerOptions): string {
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
function withAccountShard<T>(
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

function ledgerLockPath(filePath: string): string {
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

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function storedId(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 256;
}

function epoch(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function validActiveWalk(value: unknown): value is ActiveWalk {
  const active = record(value);
  return active !== null &&
    exactKeys(active, [
      "walkId",
      "leaseId",
      "fencingToken",
      "actorId",
      "deviceId",
      "familyId",
      "acquiredAtEpochMs",
      "leaseExpiresAtEpochMs"
    ]) &&
    validId(active.walkId) &&
    validId(active.leaseId) &&
    validFence(active.fencingToken) &&
    storedId(active.actorId) &&
    storedId(active.deviceId) &&
    storedId(active.familyId) &&
    epoch(active.acquiredAtEpochMs) &&
    epoch(active.leaseExpiresAtEpochMs) &&
    active.leaseExpiresAtEpochMs > active.acquiredAtEpochMs;
}

function validSavedResponse(value: unknown): value is Record<string, unknown> {
  const response = record(value);
  if (!response || response.schema_version !== RESPONSE_SCHEMA) return false;
  if (
    ["ACQUIRED", "ALREADY_ACTIVE", "TAKEN_OVER", "RENEWED"].includes(
      String(response.result)
    )
  ) {
    return exactKeys(response, [
      "schema_version",
      "result",
      "walk_id",
      "lease_id",
      "fencing_token",
      "acquired_at_epoch_ms",
      "lease_expires_at_epoch_ms",
      "server_time_epoch_ms"
    ]) &&
      validId(response.walk_id) &&
      validId(response.lease_id) &&
      validFence(response.fencing_token) &&
      epoch(response.acquired_at_epoch_ms) &&
      epoch(response.lease_expires_at_epoch_ms) &&
      epoch(response.server_time_epoch_ms);
  }
  return response.result === "ENDED" &&
    exactKeys(response, [
      "schema_version",
      "result",
      "walk_id",
      "lease_id",
      "fencing_token",
      "ended_at_epoch_ms",
      "server_time_epoch_ms"
    ]) &&
    validId(response.walk_id) &&
    validId(response.lease_id) &&
    validFence(response.fencing_token) &&
    epoch(response.ended_at_epoch_ms) &&
    epoch(response.server_time_epoch_ms);
}

function validSavedRequest(requestId: string, value: unknown): value is SavedRequest {
  const saved = record(value);
  if (
    !saved ||
    !exactKeys(saved, [
      "canonicalCommand",
      "actorId",
      "deviceId",
      "familyId",
      "response"
    ]) ||
    typeof saved.canonicalCommand !== "string" ||
    !storedId(saved.actorId) ||
    !storedId(saved.deviceId) ||
    !storedId(saved.familyId) ||
    !validSavedResponse(saved.response)
  ) {
    return false;
  }
  try {
    const command = parseFieldWalkCommand(JSON.parse(saved.canonicalCommand) as unknown);
    return command !== null &&
      command.request_id === requestId &&
      canonicalCommand(command) === saved.canonicalCommand;
  } catch {
    return false;
  }
}

function validAccountIdempotency(value: unknown): value is AccountIdempotency {
  const idempotency = record(value);
  if (
    !idempotency ||
    !exactKeys(idempotency, ["evictedIssuedAtByDevice", "evictedLegacyStartDigests"])
  ) {
    return false;
  }
  const watermarks = record(idempotency.evictedIssuedAtByDevice);
  const digests = idempotency.evictedLegacyStartDigests;
  return watermarks !== null &&
    Object.keys(watermarks).length <= MAX_IDEMPOTENCY_WATERMARK_DEVICES &&
    Object.entries(watermarks).every(
      ([deviceId, issuedAt]) => storedId(deviceId) && epoch(issuedAt)
    ) &&
    Array.isArray(digests) &&
    digests.length <= MAX_EVICTED_LEGACY_START_DIGESTS &&
    digests.every((digest) => typeof digest === "string" && LEGACY_START_DIGEST.test(digest));
}

function validEndedWalks(value: unknown): value is EndedWalk[] {
  return Array.isArray(value) &&
    value.length >= 1 &&
    value.length <= FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT &&
    value.every((item) => {
      const ended = record(item);
      const hasEndIssuedAt = ended !== null &&
        Object.prototype.hasOwnProperty.call(ended, "endIssuedAtEpochMs");
      return ended !== null &&
        exactKeys(ended, [
          "walkId", "actorId", "deviceId", "endedAtEpochMs", "reason",
          ...(hasEndIssuedAt ? ["endIssuedAtEpochMs"] : [])
        ]) &&
        validId(ended.walkId) &&
        storedId(ended.actorId) &&
        storedId(ended.deviceId) &&
        epoch(ended.endedAtEpochMs) &&
        ["ENDED", "EXPIRED", "TAKEN_OVER"].includes(String(ended.reason)) &&
        (!hasEndIssuedAt || (ended.reason === "ENDED" && epoch(ended.endIssuedAtEpochMs)));
    }) &&
    new Set(value.map((item) => (item as EndedWalk).walkId)).size === value.length;
}

function validAccountLedger(value: unknown): value is AccountLedger {
  const account = record(value);
  const hasIdempotency = account !== null &&
    Object.prototype.hasOwnProperty.call(account, "idempotency");
  const hasEndedWalks = account !== null &&
    Object.prototype.hasOwnProperty.call(account, "endedWalks");
  if (
    !account ||
    !exactKeys(
      account,
      [
        "nextFencingToken",
        "active",
        "requests",
        ...(hasIdempotency ? ["idempotency"] : []),
        ...(hasEndedWalks ? ["endedWalks"] : [])
      ]
    ) ||
    (hasIdempotency && !validAccountIdempotency(account.idempotency)) ||
    (hasEndedWalks && !validEndedWalks(account.endedWalks)) ||
    !validFence(account.nextFencingToken) ||
    (account.active !== null && !validActiveWalk(account.active))
  ) {
    return false;
  }
  if (
    account.active !== null &&
    (account.active as ActiveWalk).fencingToken >= account.nextFencingToken
  ) {
    return false;
  }
  const requests = record(account.requests);
  return requests !== null &&
    Object.keys(requests).length <= limits().maxRequestsPerAccount &&
    Object.entries(requests).every(
      ([requestId, saved]) => validId(requestId) && validSavedRequest(requestId, saved)
    );
}

function validAuditEntry(value: unknown): value is AuditEntry {
  const entry = record(value);
  if (!entry) return false;
  const takeover = entry.transition === "TAKEN_OVER";
  return exactKeys(entry, [
    "transition",
    "accountId",
    "walkId",
    "leaseId",
    "fencingToken",
    "actorId",
    "deviceId",
    "familyId",
    "occurredAtEpochMs",
    ...(takeover ? ["previousLeaseId", "previousFencingToken"] : [])
  ]) &&
    ["ACQUIRED", "EXPIRED", "TAKEN_OVER", "RENEWED", "ENDED"].includes(
      String(entry.transition)
    ) &&
    storedId(entry.accountId) &&
    validId(entry.walkId) &&
    validId(entry.leaseId) &&
    validFence(entry.fencingToken) &&
    storedId(entry.actorId) &&
    storedId(entry.deviceId) &&
    storedId(entry.familyId) &&
    epoch(entry.occurredAtEpochMs) &&
    (!takeover ||
      (validId(entry.previousLeaseId) && validFence(entry.previousFencingToken)));
}

function validAuditArchivePointer(value: unknown): value is AuditArchivePointer {
  const pointer = record(value);
  return pointer !== null &&
    exactKeys(pointer, ["segmentCount", "archivedEntries", "lastSegmentSha256"]) &&
    Number.isSafeInteger(pointer.segmentCount) &&
    Number(pointer.segmentCount) >= 1 &&
    Number(pointer.segmentCount) <= MAX_AUDIT_SEGMENT_SEQUENCE &&
    Number.isSafeInteger(pointer.archivedEntries) &&
    Number(pointer.archivedEntries) >= Number(pointer.segmentCount) &&
    typeof pointer.lastSegmentSha256 === "string" &&
    SHA256_HEX.test(pointer.lastSegmentSha256);
}

function validLedgerState(value: unknown): value is LedgerState {
  const state = record(value);
  const stamped = state !== null && Object.prototype.hasOwnProperty.call(state, "shardLayout");
  const archived = state !== null && Object.prototype.hasOwnProperty.call(state, "auditArchive");
  if (
    !state ||
    !exactKeys(
      state,
      [
        "schemaVersion",
        "accounts",
        "audit",
        ...(stamped ? ["shardLayout"] : []),
        ...(archived ? ["auditArchive"] : [])
      ]
    ) ||
    (stamped && state.shardLayout !== SHARD_LAYOUT) ||
    (archived && !validAuditArchivePointer(state.auditArchive)) ||
    state.schemaVersion !== "walksafe.field-walk-ledger.v1" ||
    !Array.isArray(state.audit) ||
    state.audit.length > limits().maxAuditEntries * 2 ||
    !state.audit.every(validAuditEntry)
  ) {
    return false;
  }
  const accounts = record(state.accounts);
  return accounts !== null &&
    Object.entries(accounts).every(
      ([accountId, account]) => storedId(accountId) && validAccountLedger(account)
    );
}

export function validFieldWalkLedgerStateForMaintenance(value: unknown): boolean {
  return validLedgerState(value);
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

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

function secureLedgerDirectory(filePath: string): string {
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

function saveState(filePath: string, state: LedgerState, terminal = false): void {
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

function validAuditSegment(value: unknown, ledgerFile: string, sequence: number): value is AuditSegment {
  const segment = record(value);
  return segment !== null &&
    exactKeys(segment, ["schemaVersion", "ledgerFile", "sequence", "previousSegmentSha256", "entries"]) &&
    segment.schemaVersion === AUDIT_SEGMENT_SCHEMA &&
    segment.ledgerFile === ledgerFile &&
    segment.sequence === sequence &&
    (sequence === 1
      ? segment.previousSegmentSha256 === null
      : typeof segment.previousSegmentSha256 === "string" &&
        SHA256_HEX.test(segment.previousSegmentSha256)) &&
    Array.isArray(segment.entries) &&
    segment.entries.length >= 1 &&
    segment.entries.every(validAuditEntry);
}

const AUDIT_SEGMENT_FILE = /^(.+)\.audit-(\d{8})$/;

/** Maintenance: validates a segment against the sequence and shard encoded in its file name. */
export function validFieldWalkAuditSegmentForMaintenance(value: unknown, recordId: string): boolean {
  const match = AUDIT_SEGMENT_FILE.exec(recordId);
  return match !== null && validAuditSegment(value, match[1]!, Number(match[2]));
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

export type FieldWalkLedgerClock = {
  wallEpochMs: () => number;
  monotonicMs: () => number;
};

const SYSTEM_CLOCK: FieldWalkLedgerClock = {
  wallEpochMs: () => Date.now(),
  monotonicMs: () => performance.now()
};

/**
 * S19-20 (w22 review): after the unstable window a server clock that runs ahead of the wall
 * clock (the wall clock stepped back) slows down by this fraction of elapsed time until it
 * meets the wall clock again, so meanwhile a 90 s lease lasts at most 100 s of real time.
 */
export const FIELD_WALK_CLOCK_SLEW_RATE = 0.1;
/** A larger backward skew re-anchors at the end of the window; the lease clamp bounds it. */
export const FIELD_WALK_CLOCK_MAX_SLEW_SKEW_MS = 10 * 60 * 1000;
const CLOCK_STATE_SCHEMA = "walksafe.field-walk-clock.v1";
const MAX_CLOCK_STATE_BYTES = 4096;

/** Leases stored up to `boundaryEpochMs` before a forward re-anchor are read `deltaMs` later. */
type ClockGrace = {
  boundaryEpochMs: number;
  deltaMs: number;
  untilEpochMs: number;
};

/**
 * The last detected wall-clock step. `serverEpochMs` continues the server time from before
 * the step, so `wallEpochMs - serverEpochMs` is the absorbed skew. It is persisted, so a
 * restart continues the same timeline instead of snapping lease time to the wall clock.
 */
type ClockAnchor = {
  wallEpochMs: number;
  serverEpochMs: number;
  unstableMs: number;
  carriedGrace: ClockGrace | null;
};

type ClockState = {
  anchor: ClockAnchor | null;
  anchorMonotonicMs: number;
  lastWallEpochMs: number;
  lastMonotonicMs: number;
  /** A new anchor that is not durable yet; written before any reading is returned. */
  unsavedAnchor: boolean;
};

let testClock: FieldWalkLedgerClock | null = null;
const clockStates = new Map<string, ClockState>();

export function setFieldWalkLedgerTestClock(clock: FieldWalkLedgerClock | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("field walk ledger test clock is unavailable in production");
  }
  testClock = clock;
  // Tests model a process restart by installing a clock: the in-process state is dropped
  // and the next read restores it from the persisted anchor.
  clockStates.clear();
}

type ServerClockReading = {
  epochMs: number;
  wallEpochMs: number;
  /** False for one lease TTL after a wall-clock step was detected. */
  stable: boolean;
  graces: readonly ClockGrace[];
};

function clockStatePath(basePath: string): string {
  return `${basePath}.clock`;
}

/** S19-20: the persisted clock anchor that belongs to a walk ledger (maintenance, marker). */
export function fieldWalkLedgerClockStatePath(basePath: string): string {
  return clockStatePath(path.resolve(basePath));
}

function anchorSkew(anchor: ClockAnchor): number {
  return anchor.wallEpochMs - anchor.serverEpochMs;
}

function clockMode(skew: number): GatewayClockTelemetryMode {
  if (skew >= 0) return "reanchor_after_window";
  return -skew > FIELD_WALK_CLOCK_MAX_SLEW_SKEW_MS
    ? "reanchor_clamp_after_window"
    : "slew_after_window";
}

/**
 * Skew (wall - server) `elapsedMs` of monotonic time after the anchor. During the unstable
 * window the whole step stays absorbed. Afterwards a server clock behind the wall clock
 * (wall stepped forward) re-anchors at once and its older leases get a grace shift; a
 * server clock ahead of the wall clock converges at the bounded slew rate, or re-anchors
 * when the skew is too large to converge (the lease clamp then bounds the effect).
 */
function skewAfterAnchor(anchor: ClockAnchor, elapsedMs: number): number {
  const skew = anchorSkew(anchor);
  if (elapsedMs < anchor.unstableMs) return skew;
  if (clockMode(skew) !== "slew_after_window") return 0;
  return Math.min(
    0,
    skew + Math.floor(FIELD_WALK_CLOCK_SLEW_RATE * (elapsedMs - anchor.unstableMs))
  );
}

function anchorGraces(anchor: ClockAnchor, elapsedMs: number): ClockGrace[] {
  const graces = anchor.carriedGrace ? [anchor.carriedGrace] : [];
  const skew = anchorSkew(anchor);
  if (skew > 0 && elapsedMs >= anchor.unstableMs) {
    // Every lease written before the re-anchor expires by reanchorServer + TTL; every lease
    // written after it expires later than that, so the two domains never overlap.
    const reanchorServer = anchor.serverEpochMs + anchor.unstableMs;
    graces.push({
      boundaryEpochMs: reanchorServer + LEASE_TTL_MS,
      deltaMs: skew,
      untilEpochMs: reanchorServer + skew + LEASE_TTL_MS
    });
  }
  return graces;
}

function serverTimeAt(state: ClockState, wall: number, monotonic: number): number {
  if (!state.anchor) return Math.round(wall);
  return Math.round(wall - skewAfterAnchor(state.anchor, monotonic - state.anchorMonotonicMs));
}

function setClockAnchor(
  state: ClockState,
  wall: number,
  monotonic: number,
  serverBefore: number,
  stepMs: number,
  detectedAt: "runtime" | "restart"
): void {
  const previous = state.anchor
    ? anchorGraces(state.anchor, monotonic - state.anchorMonotonicMs)
      .filter((grace) => serverBefore < grace.untilEpochMs)
    : [];
  state.anchor = {
    wallEpochMs: wall,
    serverEpochMs: serverBefore,
    unstableMs: LEASE_TTL_MS,
    carriedGrace: previous.at(-1) ?? null
  };
  state.anchorMonotonicMs = monotonic;
  state.unsavedAnchor = true;
  recordGatewayStateEvent({
    event_name: "walksafe.gateway.clock.step_detected",
    step_ms: stepMs,
    skew_ms: wall - serverBefore,
    mode: clockMode(wall - serverBefore),
    detected_at: detectedAt
  });
}

function clockAnchorValue(anchor: ClockAnchor): Record<string, unknown> {
  return {
    schema_version: CLOCK_STATE_SCHEMA,
    anchor_wall_epoch_ms: anchor.wallEpochMs,
    anchor_server_epoch_ms: anchor.serverEpochMs,
    unstable_ms: anchor.unstableMs,
    carried_grace: anchor.carriedGrace
      ? {
          boundary_epoch_ms: anchor.carriedGrace.boundaryEpochMs,
          delta_ms: anchor.carriedGrace.deltaMs,
          until_epoch_ms: anchor.carriedGrace.untilEpochMs
        }
      : null
  };
}

function parseClockAnchor(value: unknown): ClockAnchor | null {
  const state = record(value);
  if (
    !state ||
    !exactKeys(state, [
      "schema_version",
      "anchor_wall_epoch_ms",
      "anchor_server_epoch_ms",
      "unstable_ms",
      "carried_grace"
    ]) ||
    state.schema_version !== CLOCK_STATE_SCHEMA ||
    !epoch(state.anchor_wall_epoch_ms) ||
    !epoch(state.anchor_server_epoch_ms) ||
    !Number.isSafeInteger(state.unstable_ms) ||
    Number(state.unstable_ms) < 0 ||
    Number(state.unstable_ms) > LEASE_TTL_MS
  ) {
    return null;
  }
  let carriedGrace: ClockGrace | null = null;
  if (state.carried_grace !== null) {
    const grace = record(state.carried_grace);
    if (
      !grace ||
      !exactKeys(grace, ["boundary_epoch_ms", "delta_ms", "until_epoch_ms"]) ||
      !epoch(grace.boundary_epoch_ms) ||
      !epoch(grace.delta_ms) ||
      !epoch(grace.until_epoch_ms)
    ) {
      return null;
    }
    carriedGrace = {
      boundaryEpochMs: grace.boundary_epoch_ms,
      deltaMs: grace.delta_ms,
      untilEpochMs: grace.until_epoch_ms
    };
  }
  return {
    wallEpochMs: state.anchor_wall_epoch_ms,
    serverEpochMs: state.anchor_server_epoch_ms,
    unstableMs: Number(state.unstable_ms),
    carriedGrace
  };
}

export function validFieldWalkClockStateForMaintenance(value: unknown): boolean {
  return parseClockAnchor(value) !== null;
}

export const FIELD_WALK_CLOCK_STATE_MAX_BYTES = MAX_CLOCK_STATE_BYTES;

function readClockAnchor(basePath: string): ClockAnchor | null {
  const filePath = clockStatePath(basePath);
  let descriptor: number | null = null;
  try {
    descriptor = openSync(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size > maxGatewayStateEnvelopeBytes(MAX_CLOCK_STATE_BYTES)
    ) {
      throw new Error("field walk clock state must be an owned regular file with mode 0600");
    }
    const anchor = parseClockAnchor(decryptGatewayStateJson(
      { kind: "field-walk-ledger", recordId: path.basename(filePath) },
      readFileSync(descriptor, "utf8"),
      MAX_CLOCK_STATE_BYTES
    ).value);
    if (!anchor) throw new Error("invalid field walk clock state");
    return anchor;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      // S19-26: a registered anchor that disappeared is lost state, not a first run.
      if (requiredGatewayStateFileWasLost(filePath)) {
        throw new FieldWalkLedgerStateMissingError(filePath);
      }
      return null;
    }
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function writeClockAnchor(basePath: string, anchor: ClockAnchor): void {
  const filePath = clockStatePath(basePath);
  const serialized = encryptGatewayStateJson(
    { kind: "field-walk-ledger", recordId: path.basename(filePath) },
    clockAnchorValue(anchor),
    MAX_CLOCK_STATE_BYTES
  );
  withExclusiveFileLock(ledgerLockPath(filePath), () => {
    const directory = secureLedgerDirectory(filePath);
    const temporary = path.join(directory, `.${path.basename(filePath)}.${randomUUID()}.tmp`);
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
      fsyncSync(descriptor);
      closeSync(descriptor);
      descriptor = null;
      renameSync(temporary, filePath);
      committed = true;
      directoryDescriptor = openSync(
        directory,
        fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
      );
      fsyncSync(directoryDescriptor);
      registerRequiredGatewayStateFile(filePath);
    } finally {
      if (descriptor !== null) closeSync(descriptor);
      if (directoryDescriptor !== null) closeSync(directoryDescriptor);
      if (!committed) rmSync(temporary, { force: true });
    }
  });
}

function restoreClockState(basePath: string, wall: number, monotonic: number): ClockState {
  const state: ClockState = {
    anchor: null,
    anchorMonotonicMs: monotonic,
    lastWallEpochMs: wall,
    lastMonotonicMs: monotonic,
    unsavedAnchor: false
  };
  const anchor = readClockAnchor(basePath);
  if (!anchor) return state;
  state.anchor = anchor;
  // Across a restart the wall clock since the anchor stands in for monotonic time.
  const elapsed = wall - anchor.wallEpochMs;
  if (elapsed >= -FIELD_WALK_CLOCK_STEP_TOLERANCE_MS) {
    state.anchorMonotonicMs = monotonic - Math.max(0, elapsed);
    return state;
  }
  // The wall clock went back past the anchor while the service was down: continue the
  // server time from the anchor as a new step (the downtime itself is unknown).
  setClockAnchor(state, wall, monotonic, anchor.serverEpochMs, Math.round(elapsed), "restart");
  return state;
}

/**
 * S19-20: lease time follows monotonic elapsed time. A wall-clock step above the tolerance
 * is absorbed (server time stays continuous) and persisted as the clock anchor; for one
 * lease TTL afterwards new activations (start/takeover) fail closed while the current
 * holder can still renew or end. After that window the absorbed skew is removed again
 * (see skewAfterAnchor), and a restart restores the same timeline from the anchor.
 */
function readServerClock(options: FieldWalkLedgerOptions): ServerClockReading {
  if (options.nowEpochMs !== undefined) {
    const value = options.nowEpochMs;
    if (!Number.isSafeInteger(value) || value < 1) throw new Error("invalid server time");
    return { epochMs: value, wallEpochMs: value, stable: true, graces: [] };
  }
  const clock = testClock ?? SYSTEM_CLOCK;
  const wall = clock.wallEpochMs();
  const monotonic = clock.monotonicMs();
  if (!Number.isSafeInteger(wall) || wall < 1 || !Number.isFinite(monotonic)) {
    throw new Error("invalid server time");
  }
  const basePath = ledgerPath(options);
  let state = clockStates.get(basePath);
  if (!state) {
    state = restoreClockState(basePath, wall, monotonic);
    clockStates.set(basePath, state);
  } else {
    const expectedWall = state.lastWallEpochMs +
      Math.max(0, monotonic - state.lastMonotonicMs);
    const step = Math.round(wall - expectedWall);
    if (Math.abs(step) > FIELD_WALK_CLOCK_STEP_TOLERANCE_MS) {
      setClockAnchor(
        state,
        wall,
        monotonic,
        serverTimeAt(state, expectedWall, monotonic),
        step,
        "runtime"
      );
    }
    state.lastWallEpochMs = wall;
    state.lastMonotonicMs = Math.max(state.lastMonotonicMs, monotonic);
  }
  if (state.unsavedAnchor && state.anchor) {
    // A failed write keeps the anchor pending and fails this reading; the next read retries.
    writeClockAnchor(basePath, state.anchor);
    state.unsavedAnchor = false;
  }
  const epochMs = serverTimeAt(state, wall, monotonic);
  if (!Number.isSafeInteger(epochMs) || epochMs < 1) throw new Error("invalid server time");
  if (!state.anchor) return { epochMs, wallEpochMs: wall, stable: true, graces: [] };
  const elapsed = monotonic - state.anchorMonotonicMs;
  return {
    epochMs,
    wallEpochMs: wall,
    stable: elapsed >= state.anchor.unstableMs,
    graces: anchorGraces(state.anchor, elapsed)
      .filter((grace) => epochMs < grace.untilEpochMs)
  };
}

function now(options: FieldWalkLedgerOptions): number {
  return readServerClock(options).epochMs;
}

function effectiveLeaseExpiry(active: ActiveWalk, clock: ServerClockReading): number {
  let expiry = active.leaseExpiresAtEpochMs;
  for (const grace of clock.graces) {
    if (expiry <= grace.boundaryEpochMs) expiry += grace.deltaMs;
  }
  return expiry;
}

/**
 * S19-20: a lease that ends more than one TTL after server time, or was acquired after it,
 * was written in another clock domain (a restart after the wall clock moved back, or a
 * backward re-anchor). It is cut back to one TTL from now, never extended, and the cached
 * responses of that lease keep matching it. Returns true when the account changed.
 */
function clampLease(account: AccountLedger, clock: ServerClockReading): boolean {
  const active = account.active;
  if (!active) return false;
  const serverTime = clock.epochMs;
  const expiry = effectiveLeaseExpiry(active, clock);
  const futureExpiry = expiry - serverTime > LEASE_TTL_MS + FIELD_WALK_CLOCK_STEP_TOLERANCE_MS;
  const futureAcquisition = active.acquiredAtEpochMs > serverTime;
  if (!futureExpiry && !futureAcquisition) return false;
  const acquiredAt = Math.min(active.acquiredAtEpochMs, serverTime);
  const expiresAt = Math.max(
    acquiredAt + 1,
    futureExpiry ? serverTime + LEASE_TTL_MS : active.leaseExpiresAtEpochMs
  );
  if (acquiredAt !== active.acquiredAtEpochMs) {
    for (const saved of Object.values(account.requests)) {
      if (
        saved.response.lease_id === active.leaseId &&
        saved.response.acquired_at_epoch_ms === active.acquiredAtEpochMs
      ) {
        saved.response = { ...saved.response, acquired_at_epoch_ms: acquiredAt };
      }
    }
  }
  active.acquiredAtEpochMs = acquiredAt;
  active.leaseExpiresAtEpochMs = expiresAt;
  recordGatewayStateEvent({
    event_name: "walksafe.gateway.clock.lease_clamped",
    step_ms: null,
    skew_ms: clock.wallEpochMs - serverTime,
    mode: null,
    detected_at: "runtime"
  });
  return true;
}

function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  return actual.length === keys.length &&
    actual.every((key, index) => key === [...keys].sort()[index]);
}

function validId(value: unknown): value is string {
  return typeof value === "string" && ID.test(value);
}

function validFence(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

export function parseFieldWalkCommand(value: unknown): FieldWalkCommand | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const payload = value as Record<string, unknown>;
  const v2 = payload.schema_version === COMMAND_SCHEMA_V2;
  if (
    (payload.schema_version !== COMMAND_SCHEMA && !v2) ||
    !validId(payload.request_id) ||
    typeof payload.action !== "string" ||
    (v2 && !epoch(payload.issued_at_epoch_ms))
  ) {
    return null;
  }
  // S19-17: v1 and v2 are both accepted; v2 carries exactly one extra field.
  const versioned = (keys: string[]): string[] =>
    v2 ? [...keys, "issued_at_epoch_ms"] : keys;
  if (
    payload.action === "start" &&
    exactKeys(payload, versioned(["schema_version", "request_id", "action", "walk_id"])) &&
    validId(payload.walk_id)
  ) {
    return payload as StartCommand;
  }
  if (
    payload.action === "takeover" &&
    exactKeys(payload, versioned([
      "schema_version",
      "request_id",
      "action",
      "walk_id",
      "expected_active_walk_id",
      "expected_fencing_token",
      "confirmation"
    ])) &&
    validId(payload.walk_id) &&
    validId(payload.expected_active_walk_id) &&
    validFence(payload.expected_fencing_token) &&
    payload.confirmation === "voice_confirmed"
  ) {
    return payload as TakeoverCommand;
  }
  if (
    (payload.action === "renew" || payload.action === "end") &&
    exactKeys(payload, versioned([
      "schema_version",
      "request_id",
      "action",
      "walk_id",
      "lease_id",
      "fencing_token"
    ])) &&
    validId(payload.walk_id) &&
    validId(payload.lease_id) &&
    validFence(payload.fencing_token)
  ) {
    return payload as LeaseCommand;
  }
  return null;
}

function accountLedger(state: LedgerState, accountId: string): AccountLedger {
  return ownEntry(state.accounts, accountId) ?? {
    nextFencingToken: 1,
    active: null,
    requests: emptyRecord<SavedRequest>()
  };
}

function holderMatches(active: ActiveWalk, binding: GatewayFieldLongSessionBinding): boolean {
  return active.actorId === binding.actorId &&
    active.deviceId === binding.deviceId &&
    active.familyId === binding.familyId;
}

/** Keeps only ended walks inside the RAW retention window; drops the key when none is left. */
function pruneEndedWalks(account: AccountLedger, serverTime: number): boolean {
  const current = account.endedWalks;
  if (!current) return false;
  const kept = current.filter(
    (ended) => ended.endedAtEpochMs + FIELD_WALK_ENDED_HISTORY_RETENTION_MS > serverTime
  );
  if (kept.length === current.length) return false;
  if (kept.length === 0) delete account.endedWalks;
  else account.endedWalks = kept;
  return true;
}

/** Decision 3(b): remembers that `walk` of this account ended, for later RAW uploads. */
function recordEndedWalk(
  account: AccountLedger,
  walk: ActiveWalk,
  reason: EndedWalk["reason"],
  serverTime: number,
  endIssuedAtEpochMs?: number
): void {
  pruneEndedWalks(account, serverTime);
  const history = (account.endedWalks ?? []).filter((ended) => ended.walkId !== walk.walkId);
  history.push({
    walkId: walk.walkId,
    actorId: walk.actorId,
    deviceId: walk.deviceId,
    endedAtEpochMs: serverTime,
    reason,
    // S01-31 (R11): explicit ends keep the ending command's issue time (server time when
    // the Gateway ended the walk itself).
    ...(reason === "ENDED" ? { endIssuedAtEpochMs: endIssuedAtEpochMs ?? serverTime } : {})
  });
  account.endedWalks = history.slice(-FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT);
}

export type FieldWalkRawUploadEligibility =
  | "ENDED_WALK"
  | "WALK_IN_PROGRESS"
  | "WALK_NOT_UPLOADABLE";

/**
 * Decision 3(b) RAW write gate: a segment is accepted only for a walk this account and device
 * held that ended within [FIELD_WALK_ENDED_HISTORY_RETENTION_MS], and never while any walk of
 * the account is active. The session family is not compared, so a later login on the same
 * device still sends the walk's segments under their original walk id.
 */
export function fieldWalkRawUploadEligibility(
  binding: GatewayFieldLongSessionBinding,
  walkId: string,
  options: FieldWalkLedgerOptions = {}
): FieldWalkRawUploadEligibility {
  now(options);
  return withAccountShard(options, binding.accountId, (filePath, state) => {
    const clock = readServerClock(options);
    const serverTime = clock.epochMs;
    const account = ownEntry(state.accounts, binding.accountId);
    if (!account) return "WALK_NOT_UPLOADABLE";
    if (expire(state, binding.accountId, account, clock)) saveState(filePath, state, true);
    if (account.active) return "WALK_IN_PROGRESS";
    const ended = account.endedWalks?.find((entry) => entry.walkId === walkId);
    return ended !== undefined &&
      ended.actorId === binding.actorId &&
      ended.deviceId === binding.deviceId &&
      ended.endedAtEpochMs + FIELD_WALK_ENDED_HISTORY_RETENTION_MS > serverTime
      ? "ENDED_WALK"
      : "WALK_NOT_UPLOADABLE";
  });
}

function expire(
  state: LedgerState,
  accountId: string,
  account: AccountLedger,
  clock: ServerClockReading
): boolean {
  const serverTime = clock.epochMs;
  const clamped = clampLease(account, clock);
  // transfer-F6: ended walks past the 30-day window leave the stored history whenever the
  // account is read for a change or an upload, not only when a later walk ends.
  const pruned = pruneEndedWalks(account, serverTime);
  const active = account.active;
  if (!active || effectiveLeaseExpiry(active, clock) > serverTime) return clamped || pruned;
  recordEndedWalk(account, active, "EXPIRED", serverTime);
  appendAudit(state, {
    transition: "EXPIRED",
    accountId,
    walkId: active.walkId,
    leaseId: active.leaseId,
    fencingToken: active.fencingToken,
    actorId: active.actorId,
    deviceId: active.deviceId,
    familyId: active.familyId,
    occurredAtEpochMs: serverTime
  });
  account.active = null;
  return true;
}

function activeResponse(
  result: "ACQUIRED" | "ALREADY_ACTIVE" | "TAKEN_OVER" | "RENEWED",
  active: ActiveWalk,
  clock: ServerClockReading
): Record<string, unknown> {
  return {
    schema_version: RESPONSE_SCHEMA,
    result,
    walk_id: active.walkId,
    lease_id: active.leaseId,
    fencing_token: active.fencingToken,
    acquired_at_epoch_ms: active.acquiredAtEpochMs,
    lease_expires_at_epoch_ms: effectiveLeaseExpiry(active, clock),
    server_time_epoch_ms: clock.epochMs
  };
}

function conflict(active: ActiveWalk | null, clock: ServerClockReading): FieldWalkLedgerResult {
  return {
    status: 409,
    body: {
      schema_version: RESPONSE_SCHEMA,
      code: "walk_lease_conflict",
      active_walk_id: active?.walkId ?? null,
      active_device_id: active?.deviceId ?? null,
      fencing_token: active?.fencingToken ?? null,
      lease_expires_at_epoch_ms: active ? effectiveLeaseExpiry(active, clock) : null,
      server_time_epoch_ms: clock.epochMs
    }
  };
}

function requestIdConflict(serverTime: number): FieldWalkLedgerResult {
  return {
    status: 409,
    body: {
      schema_version: RESPONSE_SCHEMA,
      code: "request_id_conflict",
      server_time_epoch_ms: serverTime
    }
  };
}

function canonicalCommand(command: FieldWalkCommand): string {
  // v1 canonical strings are unchanged; v2 appends issued_at_epoch_ms last.
  const issued = command.schema_version === COMMAND_SCHEMA_V2
    ? { issued_at_epoch_ms: command.issued_at_epoch_ms }
    : {};
  if (command.action === "start") {
    return JSON.stringify({
      schema_version: command.schema_version,
      request_id: command.request_id,
      action: command.action,
      walk_id: command.walk_id,
      ...issued
    });
  }
  if (command.action === "takeover") {
    return JSON.stringify({
      schema_version: command.schema_version,
      request_id: command.request_id,
      action: command.action,
      walk_id: command.walk_id,
      expected_active_walk_id: command.expected_active_walk_id,
      expected_fencing_token: command.expected_fencing_token,
      confirmation: command.confirmation,
      ...issued
    });
  }
  return JSON.stringify({
    schema_version: command.schema_version,
    request_id: command.request_id,
    action: command.action,
    walk_id: command.walk_id,
    lease_id: command.lease_id,
    fencing_token: command.fencing_token,
    ...issued
  });
}

function legacyStartDigest(requestId: string): string {
  return createHash("sha256").update(requestId, "utf8").digest("base64url").slice(0, 22);
}

/** S19-17: remember what an evicted request would need to be refused on replay. */
function recordEviction(
  account: AccountLedger,
  requestId: string,
  clock: ServerClockReading
): void {
  const saved = ownEntry(account.requests, requestId);
  if (!saved) return;
  let command: FieldWalkCommand | null;
  try {
    command = parseFieldWalkCommand(JSON.parse(saved.canonicalCommand) as unknown);
  } catch {
    command = null;
  }
  if (!command) return;
  const idempotency: AccountIdempotency = account.idempotency ?? {
    evictedIssuedAtByDevice: emptyRecord<number>(),
    evictedLegacyStartDigests: []
  };
  if (
    command.schema_version === COMMAND_SCHEMA_V2 &&
    command.issued_at_epoch_ms !== undefined
  ) {
    const previous = ownEntry(idempotency.evictedIssuedAtByDevice, saved.deviceId) ?? 0;
    setOwnEntry(
      idempotency.evictedIssuedAtByDevice,
      saved.deviceId,
      Math.max(previous, command.issued_at_epoch_ms)
    );
  } else if (command.action === "start") {
    // Renew/end/takeover replays stay lease/fence CAS guarded; only a v1 start needs this.
    idempotency.evictedLegacyStartDigests.push(legacyStartDigest(requestId));
    while (idempotency.evictedLegacyStartDigests.length > MAX_EVICTED_LEGACY_START_DIGESTS) {
      idempotency.evictedLegacyStartDigests.shift();
    }
  }
  // Watermarks older than the window are redundant with the window check itself; the
  // window accepts either clock, so only values older than both are dropped.
  const oldestReference = Math.min(clock.epochMs, clock.wallEpochMs);
  const watermarks = idempotency.evictedIssuedAtByDevice;
  for (const [deviceId, issuedAt] of Object.entries(watermarks)) {
    if (issuedAt < oldestReference - FIELD_WALK_IDEMPOTENCY_WINDOW_MS) delete watermarks[deviceId];
  }
  const ordered = Object.entries(watermarks).sort((left, right) => left[1] - right[1]);
  while (ordered.length > MAX_IDEMPOTENCY_WATERMARK_DEVICES) {
    delete watermarks[ordered.shift()![0]];
  }
  account.idempotency = idempotency;
}

function idempotencyExpired(serverTime: number): FieldWalkLedgerResult {
  return {
    status: 409,
    body: {
      schema_version: RESPONSE_SCHEMA,
      code: "idempotency_expired",
      server_time_epoch_ms: serverTime
    }
  };
}

/**
 * S19-17: an uncached request outside the idempotency guarantee is refused, not executed.
 * S19-20 (w22 review): the ±24 h window accepts `issued_at_epoch_ms` near either the ledger
 * server time or the wall clock, so an absorbed clock step never turns the window against
 * a correctly clocked device (or a client that follows the returned server time).
 */
function outsideIdempotencyGuarantee(
  account: AccountLedger,
  binding: GatewayFieldLongSessionBinding,
  command: FieldWalkCommand,
  clock: ServerClockReading
): boolean {
  if (command.schema_version === COMMAND_SCHEMA_V2) {
    const issuedAt = command.issued_at_epoch_ms ?? 0;
    const within = (reference: number): boolean =>
      issuedAt >= reference - FIELD_WALK_IDEMPOTENCY_WINDOW_MS &&
      issuedAt <= reference + FIELD_WALK_IDEMPOTENCY_WINDOW_MS;
    if (!within(clock.epochMs) && !within(clock.wallEpochMs)) {
      return true;
    }
    const watermark = account.idempotency
      ? ownEntry(account.idempotency.evictedIssuedAtByDevice, binding.deviceId)
      : undefined;
    return watermark !== undefined && issuedAt <= watermark;
  }
  return command.action === "start" &&
    account.idempotency !== undefined &&
    account.idempotency.evictedLegacyStartDigests.includes(
      legacyStartDigest(command.request_id)
    );
}

/**
 * S01-31 (R11): while the account remembers an explicitly ended walk (end command, logout or
 * remote revoke; FIELD_WALK_ENDED_HISTORY_RETENTION_MS, at most
 * FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT walks), a start or takeover of that walk id issued
 * at or before the ending command (`endIssuedAtEpochMs`) is a replay of an older command and
 * is refused, independent of the request cache. A command issued after the end is new and
 * runs: Android returns a start lease it can no longer use with an end and retries the same
 * READY-epoch walk id. Without an issue time (a v1 command, or a record written before
 * `endIssuedAtEpochMs` existed) the order cannot be proven and the command is refused.
 * Expired or taken-over walks are not frozen: the Android owned-walk recovery may still
 * reacquire such an id once to end it.
 */
function endedBeforeIssue(
  account: AccountLedger,
  walkId: string,
  command: FieldWalkCommand
): boolean {
  const ended = account.endedWalks?.find(
    (entry) => entry.walkId === walkId && entry.reason === "ENDED"
  );
  if (ended === undefined) return false;
  const issuedAt = command.schema_version === COMMAND_SCHEMA_V2
    ? command.issued_at_epoch_ms
    : undefined;
  return issuedAt === undefined ||
    ended.endIssuedAtEpochMs === undefined ||
    issuedAt <= ended.endIssuedAtEpochMs;
}

/** S01-31 (R11): the issue time an explicit end records (server time without one). */
function commandIssuedAt(command: FieldWalkCommand, serverTime: number): number {
  return command.schema_version === COMMAND_SCHEMA_V2 && command.issued_at_epoch_ms !== undefined
    ? command.issued_at_epoch_ms
    : serverTime;
}

function walkAlreadyEnded(walkId: string, serverTime: number): FieldWalkLedgerResult {
  return {
    status: 409,
    body: {
      schema_version: RESPONSE_SCHEMA,
      code: "walk_already_ended",
      walk_id: walkId,
      server_time_epoch_ms: serverTime
    }
  };
}

function clockUnavailable(serverTime: number): FieldWalkLedgerResult {
  return {
    status: 503,
    body: {
      schema_version: RESPONSE_SCHEMA,
      code: "field_walk_clock_unavailable",
      server_time_epoch_ms: serverTime
    }
  };
}

function remember(
  account: AccountLedger,
  binding: GatewayFieldLongSessionBinding,
  command: FieldWalkCommand,
  canonical: string,
  body: Record<string, unknown>,
  clock: ServerClockReading
): void {
  if (!ownEntry(account.requests, command.request_id)) {
    const requestIds = Object.keys(account.requests);
    if (requestIds.length >= limits().maxRequestsPerAccount) {
      // A start has no lease/fence CAS, so replaying an evicted start could acquire a
      // new lease for an ended walk. Renew/end/takeover replays stay CAS-guarded, so
      // they are evicted first. Starts may hold at most half of the cache, so a long-used
      // account still keeps a replay window for recent end/takeover responses.
      const isStart = (requestId: string): boolean => {
        const result = account.requests[requestId]?.response.result;
        return result === "ACQUIRED" || result === "ALREADY_ACTIVE";
      };
      const starts = requestIds.filter(isStart);
      const others = requestIds.filter((requestId) => !isStart(requestId));
      const pool = others.length === 0 ||
        starts.length * 2 > limits().maxRequestsPerAccount ? starts : others;
      pool.sort((left, right) => {
        const leftTime = Number(account.requests[left]?.response.server_time_epoch_ms);
        const rightTime = Number(account.requests[right]?.response.server_time_epoch_ms);
        return leftTime - rightTime || left.localeCompare(right);
      });
      recordEviction(account, pool[0]!, clock);
      delete account.requests[pool[0]!];
    }
  }
  setOwnEntry(account.requests, command.request_id, {
    canonicalCommand: canonical,
    actorId: binding.actorId,
    deviceId: binding.deviceId,
    familyId: binding.familyId,
    response: body
  });
}

function appendAudit(state: LedgerState, entry: AuditEntry): void {
  const terminal = entry.transition === "ENDED" || entry.transition === "EXPIRED";
  const maxEntries = limits().maxAuditEntries * (terminal ? 2 : 1);
  if (state.audit.length >= maxEntries) {
    throw new FieldWalkLedgerCapacityError("field walk ledger audit ceiling reached");
  }
  state.audit.push(entry);
}

function newActive(
  account: AccountLedger,
  binding: GatewayFieldLongSessionBinding,
  walkId: string,
  serverTime: number
): ActiveWalk {
  const active: ActiveWalk = {
    walkId,
    leaseId: randomUUID(),
    fencingToken: account.nextFencingToken,
    actorId: binding.actorId,
    deviceId: binding.deviceId,
    familyId: binding.familyId,
    acquiredAtEpochMs: serverTime,
    leaseExpiresAtEpochMs: serverTime + LEASE_TTL_MS
  };
  account.nextFencingToken += 1;
  return active;
}

export function getFieldWalk(
  binding: GatewayFieldLongSessionBinding,
  options: FieldWalkLedgerOptions = {}
): FieldWalkLedgerResult {
  now(options);
  return withAccountShard(options, binding.accountId, (filePath, state) => {
    const clock = readServerClock(options);
    const serverTime = clock.epochMs;
  const account = accountLedger(state, binding.accountId);
  setOwnEntry(state.accounts, binding.accountId, account);
  if (expire(state, binding.accountId, account, clock)) saveState(filePath, state, true);
  const active = account.active;
  if (!active) {
    return {
      status: 200,
      body: {
        schema_version: RESPONSE_SCHEMA,
        result: "NONE",
        held_by_current_device: false,
        server_time_epoch_ms: serverTime
      }
    };
  }
  return {
    status: 200,
    body: {
      schema_version: RESPONSE_SCHEMA,
      result: "ACTIVE",
      active_walk_id: active.walkId,
      active_device_id: active.deviceId,
      fencing_token: active.fencingToken,
      lease_expires_at_epoch_ms: effectiveLeaseExpiry(active, clock),
      held_by_current_device: holderMatches(active, binding),
      server_time_epoch_ms: serverTime
    }
  };
  });
}

export type FieldWalkRevokedSessionHolder = {
  accountId: string;
  actorId: string;
  deviceId: string;
  /** Omit to match every session of the revoked device. */
  familyId?: string;
};

/**
 * Ends the account's active walk when it is held by a session that was just logged
 * out or remotely revoked. It never transfers the walk: a later session, even on the
 * same device id, must start a new walk or use the confirmed takeover flow.
 */
export function endFieldWalkForRevokedSession(
  holder: FieldWalkRevokedSessionHolder,
  options: FieldWalkLedgerOptions = {}
): boolean {
  now(options);
  return withAccountShard(options, holder.accountId, (filePath, state) => {
    const clock = readServerClock(options);
    const serverTime = clock.epochMs;
    const account = ownEntry(state.accounts, holder.accountId);
    if (!account) return false;
    const expired = expire(state, holder.accountId, account, clock);
    const active = account.active;
    if (
      !active ||
      active.actorId !== holder.actorId ||
      active.deviceId !== holder.deviceId ||
      (holder.familyId !== undefined && active.familyId !== holder.familyId)
    ) {
      if (expired) saveState(filePath, state, true);
      return false;
    }
    account.active = null;
    recordEndedWalk(account, active, "ENDED", serverTime);
    // Reuses the existing terminal transition so the ledger schema (and a rollback
    // to an earlier Gateway build) stays readable.
    appendAudit(state, {
      transition: "ENDED",
      accountId: holder.accountId,
      walkId: active.walkId,
      leaseId: active.leaseId,
      fencingToken: active.fencingToken,
      actorId: active.actorId,
      deviceId: active.deviceId,
      familyId: active.familyId,
      occurredAtEpochMs: serverTime
    });
    saveState(filePath, state, true);
    return true;
  });
}

export function commandFieldWalk(
  binding: GatewayFieldLongSessionBinding,
  command: FieldWalkCommand,
  options: FieldWalkLedgerOptions = {}
): FieldWalkLedgerResult {
  now(options);
  return withAccountShard(options, binding.accountId, (filePath, state) => {
    const clock = readServerClock(options);
    const serverTime = clock.epochMs;
  const account = accountLedger(state, binding.accountId);
  const canonical = canonicalCommand(command);
  const expired = expire(state, binding.accountId, account, clock);
  // Persist lease release independently of any subsequent command admission.
  if (expired) saveState(filePath, state, true);
  const saved = ownEntry(account.requests, command.request_id);
  if (saved) {
    if (
      saved.canonicalCommand !== canonical ||
      saved.actorId !== binding.actorId ||
      saved.deviceId !== binding.deviceId ||
      saved.familyId !== binding.familyId
    ) return requestIdConflict(serverTime);
    if (saved.response.result !== "ENDED") {
      const active = account.active;
      if (
        !active ||
        effectiveLeaseExpiry(active, clock) <= serverTime ||
        active.walkId !== saved.response.walk_id ||
        active.leaseId !== saved.response.lease_id ||
        active.fencingToken !== saved.response.fencing_token ||
        active.acquiredAtEpochMs !== saved.response.acquired_at_epoch_ms ||
        !holderMatches(active, binding)
      ) return conflict(active, clock);
      return {
        status: 200,
        body: {
          ...saved.response,
          lease_expires_at_epoch_ms: effectiveLeaseExpiry(active, clock),
          server_time_epoch_ms: serverTime
        }
      };
    }
    return { status: 200, body: saved.response };
  }

  if (outsideIdempotencyGuarantee(account, binding, command, clock)) {
    return idempotencyExpired(serverTime);
  }
  if ((command.action === "start" || command.action === "takeover") && !clock.stable) {
    return clockUnavailable(serverTime);
  }

  if (command.action === "start") {
    const current = account.active;
    if (current) {
      if (current.walkId !== command.walk_id || !holderMatches(current, binding)) {
        return conflict(current, clock);
      }
      const body = activeResponse("ALREADY_ACTIVE", current, clock);
      remember(account, binding, command, canonical, body, clock);
      saveState(filePath, state);
      return { status: 200, body };
    }
    if (endedBeforeIssue(account, command.walk_id, command)) {
      return walkAlreadyEnded(command.walk_id, serverTime);
    }
    const active = newActive(account, binding, command.walk_id, serverTime);
    account.active = active;
    setOwnEntry(state.accounts, binding.accountId, account);
    appendAudit(state, {
      transition: "ACQUIRED",
      accountId: binding.accountId,
      walkId: active.walkId,
      leaseId: active.leaseId,
      fencingToken: active.fencingToken,
      actorId: active.actorId,
      deviceId: active.deviceId,
      familyId: active.familyId,
      occurredAtEpochMs: serverTime
    });
    const body = activeResponse("ACQUIRED", active, clock);
    remember(account, binding, command, canonical, body, clock);
    saveState(filePath, state);
    return { status: 200, body };
  }

  if (command.action === "takeover") {
    const previous = account.active;
    if (
      !previous ||
      previous.walkId !== command.expected_active_walk_id ||
      previous.fencingToken !== command.expected_fencing_token
    ) {
      return conflict(previous, clock);
    }
    if (endedBeforeIssue(account, command.walk_id, command)) {
      return walkAlreadyEnded(command.walk_id, serverTime);
    }
    const active = newActive(account, binding, command.walk_id, serverTime);
    recordEndedWalk(account, previous, "TAKEN_OVER", serverTime);
    account.active = active;
    appendAudit(state, {
      transition: "TAKEN_OVER",
      accountId: binding.accountId,
      walkId: active.walkId,
      leaseId: active.leaseId,
      fencingToken: active.fencingToken,
      actorId: active.actorId,
      deviceId: active.deviceId,
      familyId: active.familyId,
      occurredAtEpochMs: serverTime,
      previousLeaseId: previous.leaseId,
      previousFencingToken: previous.fencingToken
    });
    const body = activeResponse("TAKEN_OVER", active, clock);
    remember(account, binding, command, canonical, body, clock);
    saveState(filePath, state);
    return { status: 200, body };
  }

  const active = account.active;
  if (
    !active ||
    !holderMatches(active, binding) ||
    active.walkId !== command.walk_id ||
    active.leaseId !== command.lease_id ||
    active.fencingToken !== command.fencing_token
  ) {
    return conflict(active, clock);
  }

  if (command.action === "renew") {
    active.leaseExpiresAtEpochMs = serverTime + LEASE_TTL_MS;
    appendAudit(state, {
      transition: "RENEWED",
      accountId: binding.accountId,
      walkId: active.walkId,
      leaseId: active.leaseId,
      fencingToken: active.fencingToken,
      actorId: active.actorId,
      deviceId: active.deviceId,
      familyId: active.familyId,
      occurredAtEpochMs: serverTime
    });
    const body = activeResponse("RENEWED", active, clock);
    remember(account, binding, command, canonical, body, clock);
    saveState(filePath, state);
    return { status: 200, body };
  }

  account.active = null;
  recordEndedWalk(account, active, "ENDED", serverTime, commandIssuedAt(command, serverTime));
  appendAudit(state, {
    transition: "ENDED",
    accountId: binding.accountId,
    walkId: active.walkId,
    leaseId: active.leaseId,
    fencingToken: active.fencingToken,
    actorId: active.actorId,
    deviceId: active.deviceId,
    familyId: active.familyId,
    occurredAtEpochMs: serverTime
  });
  const body = {
    schema_version: RESPONSE_SCHEMA,
    result: "ENDED",
    walk_id: active.walkId,
    lease_id: active.leaseId,
    fencing_token: active.fencingToken,
    ended_at_epoch_ms: serverTime,
    server_time_epoch_ms: serverTime
  };
  remember(account, binding, command, canonical, body, clock);
  saveState(filePath, state, true);
  return { status: 200, body };
  });
}
