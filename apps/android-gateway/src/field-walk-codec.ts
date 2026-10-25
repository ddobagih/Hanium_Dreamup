
const COMMAND_SCHEMA = "walksafe.field-walk-command.v1";

/**
 * S19-17: v2 adds the client's `issued_at_epoch_ms`, fixed when the request id is created
 * and part of the canonical command, so the ledger can bound idempotency by time.
 */
export const COMMAND_SCHEMA_V2 = "walksafe.field-walk-command.v2";

export const RESPONSE_SCHEMA = "walksafe.field-walk-response.v1";

export const LEASE_TTL_MS = 90_000;

export const MAX_IDEMPOTENCY_WATERMARK_DEVICES = 64;

export const MAX_EVICTED_LEGACY_START_DIGESTS = 256;

const LEGACY_START_DIGEST = /^[A-Za-z0-9_-]{22}$/;

/**
 * S19-20: a wall-clock step larger than this between two ledger reads (measured against the
 * monotonic clock) is treated as a clock change, not elapsed time.
 */
export const FIELD_WALK_CLOCK_STEP_TOLERANCE_MS = 2_000;

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;

const DEFAULT_MAX_REQUESTS_PER_ACCOUNT = 4_096;

export const DEFAULT_MAX_AUDIT_ENTRIES = 8_192;

export const DEFAULT_MAX_LEDGER_BYTES = 16 * 1024 * 1024;

export const TERMINAL_BYTES_PER_AUDIT_ENTRY = 4096;

/**
 * S19-13/S19-14/S01-28: a shard keeps at most this many audit entries next to its account
 * state. Reaching it seals the entries into the next encrypted archive segment
 * (`<shard>.audit-NNNNNNNN`, written and fsynced before the shard state that points to it),
 * so the audit history no longer bounds walking and a heartbeat rewrites a bounded file.
 * Segments are never deleted automatically (no retention period is decided yet).
 */
export const FIELD_WALK_AUDIT_SEGMENT_ENTRIES = 1_024;

export const AUDIT_SEGMENT_SCHEMA = "walksafe.field-walk-audit-segment.v1";

export const MAX_AUDIT_SEGMENT_SEQUENCE = 99_999_999;

const SHA256_HEX = /^[0-9a-f]{64}$/;

type LedgerLimits = {
  maxRequestsPerAccount: number;
  maxAuditEntries: number;
  maxLedgerBytes: number;
  /** Tests only: rotation threshold (defaults to FIELD_WALK_AUDIT_SEGMENT_ENTRIES). */
  auditSegmentEntries?: number;
};

let testLimits: LedgerLimits | null = null;

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

export function limits(): LedgerLimits {
  return testLimits ?? {
    maxRequestsPerAccount: DEFAULT_MAX_REQUESTS_PER_ACCOUNT,
    maxAuditEntries: DEFAULT_MAX_AUDIT_ENTRIES,
    maxLedgerBytes: DEFAULT_MAX_LEDGER_BYTES
  };
}

export type ActiveWalk = {
  walkId: string;
  leaseId: string;
  fencingToken: number;
  actorId: string;
  deviceId: string;
  familyId: string;
  acquiredAtEpochMs: number;
  leaseExpiresAtEpochMs: number;
};

export type SavedRequest = {
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
export type AccountIdempotency = {
  /** Per device: newest `issued_at_epoch_ms` among evicted v2 requests. */
  evictedIssuedAtByDevice: Record<string, number>;
  /** v1 has no request time: digests of evicted v1 start request ids (FIFO, bounded). */
  evictedLegacyStartDigests: string[];
};

/**
 * Per-account bound; the oldest ended walk is dropped first (its uploads are then refused and
 * the phone deletes those segments). transfer-F6: 256 covers more than eight walks a day for the
 * whole 30-day window, so a phone that rarely meets Wi-Fi plus charging still keeps its walks.
 */
export const FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT = 256;

export type EndedWalk = {
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

export type AccountLedger = {
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

export type AuditEntry = {
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
export const SHARD_LAYOUT = "walksafe.field-walk-ledger-shards.v1";

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

export type AuditSegment = {
  schemaVersion: typeof AUDIT_SEGMENT_SCHEMA;
  ledgerFile: string;
  sequence: number;
  previousSegmentSha256: string | null;
  entries: AuditEntry[];
};

export type LedgerState = {
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

export type FieldWalkCommand = StartCommand | TakeoverCommand | LeaseCommand;

export type FieldWalkLedgerResult = {
  status: number;
  body: Record<string, unknown>;
};

export type FieldWalkLedgerOptions = {
  nowEpochMs?: number;
  ledgerPath?: string;
};

export function emptyRecord<T>(): Record<string, T> {
  return Object.create(null) as Record<string, T>;
}

export function ownEntry<T>(entries: Record<string, T>, key: string): T | undefined {
  return Object.prototype.hasOwnProperty.call(entries, key)
    ? entries[key]
    : undefined;
}

export function setOwnEntry<T>(entries: Record<string, T>, key: string, value: T): void {
  Object.defineProperty(entries, key, {
    value,
    enumerable: true,
    configurable: true,
    writable: true
  });
}

export function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function storedId(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 256;
}

export function epoch(value: unknown): value is number {
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

export function validAuditEntry(value: unknown): value is AuditEntry {
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

export function validLedgerState(value: unknown): value is LedgerState {
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

export function validAuditSegment(value: unknown, ledgerFile: string, sequence: number): value is AuditSegment {
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

export const AUDIT_SEGMENT_FILE = /^(.+)\.audit-(\d{8})$/;

/** Maintenance: validates a segment against the sequence and shard encoded in its file name. */
export function validFieldWalkAuditSegmentForMaintenance(value: unknown, recordId: string): boolean {
  const match = AUDIT_SEGMENT_FILE.exec(recordId);
  return match !== null && validAuditSegment(value, match[1]!, Number(match[2]));
}

export function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
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

export function canonicalCommand(command: FieldWalkCommand): string {
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

