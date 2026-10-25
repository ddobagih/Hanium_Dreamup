import { type GatewayFieldLongSessionBinding } from "./auth.js";
import { createHash, randomUUID } from "node:crypto";
import {
  DEFAULT_MAX_LEDGER_BYTES,
  DEFAULT_MAX_AUDIT_ENTRIES,
  TERMINAL_BYTES_PER_AUDIT_ENTRY,
  type LedgerState,
  type AccountLedger,
  ownEntry,
  emptyRecord,
  type SavedRequest,
  type ActiveWalk,
  type EndedWalk,
  FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT,
  type FieldWalkLedgerOptions,
  RESPONSE_SCHEMA,
  type FieldWalkLedgerResult,
  type FieldWalkCommand,
  parseFieldWalkCommand,
  type AccountIdempotency,
  COMMAND_SCHEMA_V2,
  setOwnEntry,
  MAX_EVICTED_LEGACY_START_DIGESTS,
  MAX_IDEMPOTENCY_WATERMARK_DEVICES,
  limits,
  type AuditEntry,
  LEASE_TTL_MS,
  canonicalCommand
} from "./field-walk-codec.js";
import {
  now,
  readServerClock,
  type ServerClockReading,
  clampLease,
  effectiveLeaseExpiry
} from "./field-walk-clock.js";
import { withAccountShard, saveState, FieldWalkLedgerCapacityError } from "./field-walk-store.js";

/**
 * S19-17: documented idempotency window. A v2 request is replayed from the stored result
 * while it is cached; a v2 request issued more than this long before (or after) server
 * time, or older than the newest evicted request of the same device, is rejected with
 * 409 idempotency_expired and never executed as a new command.
 */
export const FIELD_WALK_IDEMPOTENCY_WINDOW_MS = 24 * 60 * 60 * 1000;

export const MAX_STORED_LEDGER_BYTES = DEFAULT_MAX_LEDGER_BYTES +
  DEFAULT_MAX_AUDIT_ENTRIES * TERMINAL_BYTES_PER_AUDIT_ENTRY;

/**
 * Owner decision 3(b) (2026-09-29): RAW segments are uploaded only after their walk ended,
 * under the walk's original id. The phone keeps them up to 30 days (FP-035), so an account
 * remembers the walks it recently ended for the RAW relay's write gate.
 */
export const FIELD_WALK_ENDED_HISTORY_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

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

export {
  FIELD_WALK_CLOCK_STEP_TOLERANCE_MS,
  DEFAULT_MAX_LEDGER_BYTES,
  FIELD_WALK_AUDIT_SEGMENT_ENTRIES,
  setFieldWalkLedgerTestLimits,
  FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT,
  type FieldWalkLedgerResult,
  type FieldWalkLedgerOptions,
  validFieldWalkLedgerStateForMaintenance,
  validFieldWalkAuditSegmentForMaintenance,
  parseFieldWalkCommand
} from "./field-walk-codec.js";
export {
  FieldWalkLedgerCapacityError,
  FieldWalkLedgerStorageOutcomeUnknownError,
  FieldWalkLedgerStateMissingError,
  setFieldWalkLedgerTestFault,
  FIELD_WALK_LEDGER_SHARD_COUNT,
  fieldWalkLedgerShardPaths,
  fieldWalkLedgerShardPathForTests,
  fieldWalkAuditSegmentPaths,
  type FieldWalkAuditRecord,
  readFieldWalkAuditHistory
} from "./field-walk-store.js";
export {
  type FieldWalkLedgerClock,
  FIELD_WALK_CLOCK_SLEW_RATE,
  FIELD_WALK_CLOCK_MAX_SLEW_SKEW_MS,
  setFieldWalkLedgerTestClock,
  fieldWalkLedgerClockStatePath,
  validFieldWalkClockStateForMaintenance,
  FIELD_WALK_CLOCK_STATE_MAX_BYTES
} from "./field-walk-clock.js";
