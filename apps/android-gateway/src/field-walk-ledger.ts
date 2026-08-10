import { randomUUID } from "node:crypto";
import {
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
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
  maxGatewayStateEnvelopeBytes
} from "./encrypted-json-store.js";

const COMMAND_SCHEMA = "walksafe.field-walk-command.v1";
const RESPONSE_SCHEMA = "walksafe.field-walk-response.v1";
const LEASE_TTL_MS = 90_000;
const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const DEFAULT_MAX_REQUESTS_PER_ACCOUNT = 4_096;
const DEFAULT_MAX_AUDIT_ENTRIES = 8_192;
export const DEFAULT_MAX_LEDGER_BYTES = 16 * 1024 * 1024;

type LedgerLimits = {
  maxRequestsPerAccount: number;
  maxAuditEntries: number;
  maxLedgerBytes: number;
};

type LedgerFaultStage = "file_fsync" | "rename" | "directory_fsync";

let testLimits: LedgerLimits | null = null;
let testFaultStage: LedgerFaultStage | null = null;

export class FieldWalkLedgerCapacityError extends Error {
  constructor(message = "field walk ledger capacity reached") {
    super(message);
    this.name = "FieldWalkLedgerCapacityError";
  }
}

export class FieldWalkLedgerStorageOutcomeUnknownError extends Error {
  constructor(cause: unknown) {
    super("field walk ledger storage outcome is unknown", { cause });
    this.name = "FieldWalkLedgerStorageOutcomeUnknownError";
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
      limits.maxLedgerBytes < 1)
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

type AccountLedger = {
  nextFencingToken: number;
  active: ActiveWalk | null;
  requests: Record<string, SavedRequest>;
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

type LedgerState = {
  schemaVersion: "walksafe.field-walk-ledger.v1";
  accounts: Record<string, AccountLedger>;
  audit: AuditEntry[];
};

type StartCommand = {
  schema_version: typeof COMMAND_SCHEMA;
  request_id: string;
  action: "start";
  walk_id: string;
};

type TakeoverCommand = {
  schema_version: typeof COMMAND_SCHEMA;
  request_id: string;
  action: "takeover";
  walk_id: string;
  expected_active_walk_id: string;
  expected_fencing_token: number;
  confirmation: "voice_confirmed";
};

type LeaseCommand = {
  schema_version: typeof COMMAND_SCHEMA;
  request_id: string;
  action: "renew" | "end";
  walk_id: string;
  lease_id: string;
  fencing_token: number;
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

function validAccountLedger(value: unknown): value is AccountLedger {
  const account = record(value);
  if (
    !account ||
    !exactKeys(account, ["nextFencingToken", "active", "requests"]) ||
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

function validLedgerState(value: unknown): value is LedgerState {
  const state = record(value);
  if (
    !state ||
    !exactKeys(state, ["schemaVersion", "accounts", "audit"]) ||
    state.schemaVersion !== "walksafe.field-walk-ledger.v1" ||
    !Array.isArray(state.audit) ||
    state.audit.length > limits().maxAuditEntries ||
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
    if (metadata.size > maxGatewayStateEnvelopeBytes(limits().maxLedgerBytes)) {
      throw new FieldWalkLedgerCapacityError("field walk ledger exceeds its byte ceiling");
    }
    const raw = readFileSync(descriptor, "utf8");
    if (
      Buffer.byteLength(raw, "utf8") >
      maxGatewayStateEnvelopeBytes(limits().maxLedgerBytes)
    ) {
      throw new FieldWalkLedgerCapacityError("field walk ledger exceeds its byte ceiling");
    }
    const state = decryptGatewayStateJson(
      { kind: "field-walk-ledger", recordId: path.basename(filePath) },
      raw,
      limits().maxLedgerBytes
    ).value;
    if (!validLedgerState(state)) {
      throw new Error("invalid field walk ledger");
    }
    return safeLedgerMaps(state);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return emptyState();
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

function saveState(filePath: string, state: LedgerState): void {
  if (!validLedgerState(state)) {
    throw new FieldWalkLedgerCapacityError("field walk ledger structural ceiling reached");
  }
  const plaintext = Buffer.from(`${JSON.stringify(state)}\n`, "utf8");
  if (plaintext.byteLength > limits().maxLedgerBytes) {
    throw new FieldWalkLedgerCapacityError("field walk ledger exceeds its byte ceiling");
  }
  const serialized = Buffer.from(
    encryptGatewayStateJson(
      { kind: "field-walk-ledger", recordId: path.basename(filePath) },
      state,
      limits().maxLedgerBytes
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

function now(options: FieldWalkLedgerOptions): number {
  const value = options.nowEpochMs ?? Date.now();
  if (!Number.isSafeInteger(value) || value < 1) throw new Error("invalid server time");
  return value;
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
  if (
    payload.schema_version !== COMMAND_SCHEMA ||
    !validId(payload.request_id) ||
    typeof payload.action !== "string"
  ) {
    return null;
  }
  if (
    payload.action === "start" &&
    exactKeys(payload, ["schema_version", "request_id", "action", "walk_id"]) &&
    validId(payload.walk_id)
  ) {
    return payload as StartCommand;
  }
  if (
    payload.action === "takeover" &&
    exactKeys(payload, [
      "schema_version",
      "request_id",
      "action",
      "walk_id",
      "expected_active_walk_id",
      "expected_fencing_token",
      "confirmation"
    ]) &&
    validId(payload.walk_id) &&
    validId(payload.expected_active_walk_id) &&
    validFence(payload.expected_fencing_token) &&
    payload.confirmation === "voice_confirmed"
  ) {
    return payload as TakeoverCommand;
  }
  if (
    (payload.action === "renew" || payload.action === "end") &&
    exactKeys(payload, [
      "schema_version",
      "request_id",
      "action",
      "walk_id",
      "lease_id",
      "fencing_token"
    ]) &&
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

function expire(
  state: LedgerState,
  accountId: string,
  account: AccountLedger,
  serverTime: number
): boolean {
  const active = account.active;
  if (!active || active.leaseExpiresAtEpochMs > serverTime) return false;
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
  serverTime: number
): Record<string, unknown> {
  return {
    schema_version: RESPONSE_SCHEMA,
    result,
    walk_id: active.walkId,
    lease_id: active.leaseId,
    fencing_token: active.fencingToken,
    acquired_at_epoch_ms: active.acquiredAtEpochMs,
    lease_expires_at_epoch_ms: active.leaseExpiresAtEpochMs,
    server_time_epoch_ms: serverTime
  };
}

function conflict(active: ActiveWalk | null, serverTime: number): FieldWalkLedgerResult {
  return {
    status: 409,
    body: {
      schema_version: RESPONSE_SCHEMA,
      code: "walk_lease_conflict",
      active_walk_id: active?.walkId ?? null,
      active_device_id: active?.deviceId ?? null,
      fencing_token: active?.fencingToken ?? null,
      lease_expires_at_epoch_ms: active?.leaseExpiresAtEpochMs ?? null,
      server_time_epoch_ms: serverTime
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
  if (command.action === "start") {
    return JSON.stringify({
      schema_version: command.schema_version,
      request_id: command.request_id,
      action: command.action,
      walk_id: command.walk_id
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
      confirmation: command.confirmation
    });
  }
  return JSON.stringify({
    schema_version: command.schema_version,
    request_id: command.request_id,
    action: command.action,
    walk_id: command.walk_id,
    lease_id: command.lease_id,
    fencing_token: command.fencing_token
  });
}

function remember(
  account: AccountLedger,
  binding: GatewayFieldLongSessionBinding,
  command: FieldWalkCommand,
  canonical: string,
  body: Record<string, unknown>
): void {
  if (!ownEntry(account.requests, command.request_id)) {
    const requestIds = Object.keys(account.requests);
    if (requestIds.length >= limits().maxRequestsPerAccount) {
      requestIds.sort((left, right) => {
        const leftTime = Number(account.requests[left]?.response.server_time_epoch_ms);
        const rightTime = Number(account.requests[right]?.response.server_time_epoch_ms);
        return leftTime - rightTime || left.localeCompare(right);
      });
      delete account.requests[requestIds[0]!];
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
  if (state.audit.length >= limits().maxAuditEntries) {
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
  const serverTime = now(options);
  const filePath = ledgerPath(options);
  // This lock is a single-host local-filesystem boundary. Multi-host coordination
  // requires an external transactional store and is intentionally not implemented here.
  return withExclusiveFileLock(ledgerLockPath(filePath), () => {
  const state = loadState(filePath);
  const account = accountLedger(state, binding.accountId);
  setOwnEntry(state.accounts, binding.accountId, account);
  if (expire(state, binding.accountId, account, serverTime)) saveState(filePath, state);
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
      lease_expires_at_epoch_ms: active.leaseExpiresAtEpochMs,
      held_by_current_device: holderMatches(active, binding),
      server_time_epoch_ms: serverTime
    }
  };
  });
}

export function commandFieldWalk(
  binding: GatewayFieldLongSessionBinding,
  command: FieldWalkCommand,
  options: FieldWalkLedgerOptions = {}
): FieldWalkLedgerResult {
  const serverTime = now(options);
  const filePath = ledgerPath(options);
  return withExclusiveFileLock(ledgerLockPath(filePath), () => {
  const state = loadState(filePath);
  const account = accountLedger(state, binding.accountId);
  setOwnEntry(state.accounts, binding.accountId, account);
  const canonical = canonicalCommand(command);
  const expired = expire(state, binding.accountId, account, serverTime);
  const saved = ownEntry(account.requests, command.request_id);
  if (saved) {
    if (expired) saveState(filePath, state);
    return saved.canonicalCommand === canonical &&
      saved.actorId === binding.actorId &&
      saved.deviceId === binding.deviceId &&
      saved.familyId === binding.familyId
      ? { status: 200, body: saved.response }
      : requestIdConflict(serverTime);
  }

  if (command.action === "start") {
    const current = account.active;
    if (current) {
      if (current.walkId !== command.walk_id || !holderMatches(current, binding)) {
        saveState(filePath, state);
        return conflict(current, serverTime);
      }
      const body = activeResponse("ALREADY_ACTIVE", current, serverTime);
      remember(account, binding, command, canonical, body);
      saveState(filePath, state);
      return { status: 200, body };
    }
    const active = newActive(account, binding, command.walk_id, serverTime);
    account.active = active;
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
    const body = activeResponse("ACQUIRED", active, serverTime);
    remember(account, binding, command, canonical, body);
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
      saveState(filePath, state);
      return conflict(previous, serverTime);
    }
    const active = newActive(account, binding, command.walk_id, serverTime);
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
    const body = activeResponse("TAKEN_OVER", active, serverTime);
    remember(account, binding, command, canonical, body);
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
    saveState(filePath, state);
    return conflict(active, serverTime);
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
    const body = activeResponse("RENEWED", active, serverTime);
    remember(account, binding, command, canonical, body);
    saveState(filePath, state);
    return { status: 200, body };
  }

  account.active = null;
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
  remember(account, binding, command, canonical, body);
  saveState(filePath, state);
  return { status: 200, body };
  });
}
