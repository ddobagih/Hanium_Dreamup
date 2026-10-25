import path from "node:path";
import { type GatewayClockTelemetryMode, recordGatewayStateEvent } from "./telemetry.js";
import {
  openSync,
  constants as fsConstants,
  fstatSync,
  readFileSync,
  closeSync,
  writeFileSync,
  fsyncSync,
  renameSync,
  rmSync
} from "node:fs";
import {
  maxGatewayStateEnvelopeBytes,
  decryptGatewayStateJson,
  encryptGatewayStateJson
} from "./encrypted-json-store.js";
import { requiredGatewayStateFileWasLost, registerRequiredGatewayStateFile } from "./state-init-marker.js";
import { withExclusiveFileLock } from "./exclusive-file-lock.js";
import { randomUUID } from "node:crypto";
import {
  LEASE_TTL_MS,
  record,
  exactKeys,
  epoch,
  FIELD_WALK_CLOCK_STEP_TOLERANCE_MS,
  type FieldWalkLedgerOptions,
  type ActiveWalk,
  type AccountLedger
} from "./field-walk-codec.js";
import {
  ownedByCurrentProcess,
  FieldWalkLedgerStateMissingError,
  ledgerLockPath,
  secureLedgerDirectory,
  ledgerPath
} from "./field-walk-store.js";

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

export type ServerClockReading = {
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
export function readServerClock(options: FieldWalkLedgerOptions): ServerClockReading {
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

export function now(options: FieldWalkLedgerOptions): number {
  return readServerClock(options).epochMs;
}

export function effectiveLeaseExpiry(active: ActiveWalk, clock: ServerClockReading): number {
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
export function clampLease(account: AccountLedger, clock: ServerClockReading): boolean {
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

