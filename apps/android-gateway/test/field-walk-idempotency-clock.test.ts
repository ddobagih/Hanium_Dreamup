// S19-17 (time-bounded idempotency) and S19-20 (wall-clock steps) for the field walk ledger.
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, afterEach, before } from "node:test";

import * as ledger from "../src/field-walk-ledger.js";
import type { GatewayFieldLongSessionBinding } from "../src/auth.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

// Namespace access keeps this file loadable against a pre-change build for the red run.
type TestClock = { wallEpochMs: () => number; monotonicMs: () => number };
const hooks = ledger as unknown as {
  setFieldWalkLedgerTestClock?: (clock: TestClock | null) => void;
};

let encryptionFixtureDirectory = "";

before(async () => {
  encryptionFixtureDirectory = await mkdtemp(path.join(tmpdir(), "field-walk-idem-encryption-"));
  await configureTestStateEncryption(
    path.join(encryptionFixtureDirectory, "state-keyring.json")
  );
});

after(async () => {
  await rm(encryptionFixtureDirectory, { recursive: true, force: true });
});

const realDateNow = Date.now;

afterEach(() => {
  ledger.setFieldWalkLedgerTestLimits(null);
  hooks.setFieldWalkLedgerTestClock?.(null);
  Date.now = realDateNow;
});

function binding(account: string, device = `${account}-device`): GatewayFieldLongSessionBinding {
  return {
    actorId: account,
    accountId: account,
    deviceId: device,
    familyId: `${device}-family`,
    sessionRotation: 1
  };
}

function command(value: Record<string, unknown>) {
  const parsed = ledger.parseFieldWalkCommand(value);
  assert.ok(parsed, `command must parse: ${JSON.stringify(value)}`);
  return parsed;
}

const V1 = "walksafe.field-walk-command.v1";
const V2 = "walksafe.field-walk-command.v2";

async function withLedger(run: (ledgerPath: string) => Promise<void> | void): Promise<void> {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-idem-"));
  try {
    await run(path.join(directory, "ledger.json"));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

/** Starts and ends `count` walks; returns the start request ids in order. */
function cycleWalks(
  ledgerPath: string,
  owner: GatewayFieldLongSessionBinding,
  schema: string,
  count: number,
  firstTime: number
): string[] {
  const starts: string[] = [];
  let time = firstTime;
  for (let index = 1; index <= count; index += 1) {
    const walkId = `walk-cycle-${index}`;
    const startId = `start-cycle-${index}`;
    starts.push(startId);
    const issued = schema === V2 ? { issued_at_epoch_ms: time } : {};
    const started = ledger.commandFieldWalk(owner, command({
      schema_version: schema, request_id: startId, action: "start", walk_id: walkId, ...issued
    }), { ledgerPath, nowEpochMs: time });
    assert.equal(started.status, 200);
    time += 10;
    const ended = ledger.commandFieldWalk(owner, command({
      schema_version: schema,
      request_id: `end-cycle-${index}`,
      action: "end",
      walk_id: walkId,
      lease_id: started.body.lease_id,
      fencing_token: started.body.fencing_token,
      ...(schema === V2 ? { issued_at_epoch_ms: time } : {})
    }), { ledgerPath, nowEpochMs: time });
    assert.equal(ended.status, 200);
    assert.equal(ended.body.result, "ENDED");
    time += 10;
  }
  return starts;
}

test("S19-17 v1 and v2 commands both parse; v2 requires exactly issued_at_epoch_ms", () => {
  assert.ok(ledger.parseFieldWalkCommand({
    schema_version: V1, request_id: "request-v1-1", action: "start", walk_id: "walk-v1-1"
  }));
  assert.ok(ledger.parseFieldWalkCommand({
    schema_version: V2,
    request_id: "request-v2-1",
    action: "start",
    walk_id: "walk-v2-1",
    issued_at_epoch_ms: 1_000
  }));
  assert.equal(ledger.parseFieldWalkCommand({
    schema_version: V2, request_id: "request-v2-2", action: "start", walk_id: "walk-v2-2"
  }), null);
  assert.equal(ledger.parseFieldWalkCommand({
    schema_version: V1,
    request_id: "request-v1-2",
    action: "start",
    walk_id: "walk-v1-2",
    issued_at_epoch_ms: 1_000
  }), null);
  assert.equal(ledger.parseFieldWalkCommand({
    schema_version: V2,
    request_id: "request-v2-3",
    action: "start",
    walk_id: "walk-v2-3",
    issued_at_epoch_ms: 0
  }), null);
});

test("S19-17 an evicted v1 start replay is refused with idempotency_expired, not re-executed", async () => {
  await withLedger((ledgerPath) => {
    ledger.setFieldWalkLedgerTestLimits({
      maxRequestsPerAccount: 4,
      maxAuditEntries: 8_192,
      maxLedgerBytes: 16 * 1024 * 1024
    });
    const owner = binding("account-v1-evict");
    const starts = cycleWalks(ledgerPath, owner, V1, 4, 1_000);
    const before = ledger.getFieldWalk(owner, { ledgerPath, nowEpochMs: 2_000 });
    assert.equal(before.body.result, "NONE");
    const replay = ledger.commandFieldWalk(owner, command({
      schema_version: V1, request_id: starts[0], action: "start", walk_id: "walk-cycle-1"
    }), { ledgerPath, nowEpochMs: 2_000 });
    assert.equal(replay.status, 409, JSON.stringify(replay.body));
    assert.equal(replay.body.code, "idempotency_expired");
    assert.equal(
      ledger.getFieldWalk(owner, { ledgerPath, nowEpochMs: 2_000 }).body.result,
      "NONE",
      "the ended walk must not be re-acquired"
    );
    // The fencing sequence is untouched: the next genuine start gets token 5.
    const next = ledger.commandFieldWalk(owner, command({
      schema_version: V1, request_id: "start-after-expiry", action: "start", walk_id: "walk-next"
    }), { ledgerPath, nowEpochMs: 2_001 });
    assert.equal(next.status, 200);
    assert.equal(next.body.fencing_token, 5);
  });
});

test("S19-17 v2: evicted replay and requests outside the window are refused; cached replay still works", async () => {
  await withLedger((ledgerPath) => {
    ledger.setFieldWalkLedgerTestLimits({
      maxRequestsPerAccount: 4,
      maxAuditEntries: 8_192,
      maxLedgerBytes: 16 * 1024 * 1024
    });
    const owner = binding("account-v2-evict");
    const starts = cycleWalks(ledgerPath, owner, V2, 4, 1_000_000);
    const serverTime = 1_000_500;
    const replay = ledger.commandFieldWalk(owner, command({
      schema_version: V2,
      request_id: starts[0],
      action: "start",
      walk_id: "walk-cycle-1",
      issued_at_epoch_ms: 1_000_000
    }), { ledgerPath, nowEpochMs: serverTime });
    assert.equal(replay.status, 409, JSON.stringify(replay.body));
    assert.equal(replay.body.code, "idempotency_expired");
    // A delayed first delivery older than the newest evicted request of this device.
    const delayed = ledger.commandFieldWalk(owner, command({
      schema_version: V2,
      request_id: "start-delayed-never-seen",
      action: "start",
      walk_id: "walk-delayed",
      issued_at_epoch_ms: 1_000_001
    }), { ledgerPath, nowEpochMs: serverTime });
    assert.equal(delayed.body.code, "idempotency_expired");
    assert.equal(ledger.getFieldWalk(owner, { ledgerPath, nowEpochMs: serverTime }).body.result, "NONE");
    // Outside the documented window on either side.
    const window = (ledger as unknown as { FIELD_WALK_IDEMPOTENCY_WINDOW_MS?: number })
      .FIELD_WALK_IDEMPOTENCY_WINDOW_MS ?? 24 * 60 * 60 * 1000;
    const other = binding("account-v2-window");
    const now = 10 * window;
    for (const issuedAt of [now - window - 1, now + window + 1]) {
      const result = ledger.commandFieldWalk(other, command({
        schema_version: V2,
        request_id: `start-window-${issuedAt}`,
        action: "start",
        walk_id: "walk-window",
        issued_at_epoch_ms: issuedAt
      }), { ledgerPath, nowEpochMs: now });
      assert.equal(result.status, 409);
      assert.equal(result.body.code, "idempotency_expired");
    }
    // A fresh request from another device of the same account is not affected by this
    // device's watermark, and a still-cached request replays its stored result.
    const fresh = ledger.commandFieldWalk(owner, command({
      schema_version: V2,
      request_id: "start-fresh",
      action: "start",
      walk_id: "walk-fresh",
      issued_at_epoch_ms: serverTime
    }), { ledgerPath, nowEpochMs: serverTime });
    assert.equal(fresh.status, 200);
    assert.equal(fresh.body.result, "ACQUIRED");
    assert.equal(fresh.body.fencing_token, 5);
    const cached = ledger.commandFieldWalk(owner, command({
      schema_version: V2,
      request_id: "start-fresh",
      action: "start",
      walk_id: "walk-fresh",
      issued_at_epoch_ms: serverTime
    }), { ledgerPath, nowEpochMs: serverTime + 1 });
    assert.equal(cached.status, 200);
    assert.equal(cached.body.fencing_token, 5);
    const changedTime = ledger.commandFieldWalk(owner, command({
      schema_version: V2,
      request_id: "start-fresh",
      action: "start",
      walk_id: "walk-fresh",
      issued_at_epoch_ms: serverTime + 5
    }), { ledgerPath, nowEpochMs: serverTime + 2 });
    assert.equal(changedTime.body.code, "request_id_conflict");
    const otherDevice = binding("account-v2-evict", "account-v2-evict-device-b");
    const conflict = ledger.commandFieldWalk(otherDevice, command({
      schema_version: V2,
      request_id: "start-other-device",
      action: "start",
      walk_id: "walk-other-device",
      issued_at_epoch_ms: 1_000_001
    }), { ledgerPath, nowEpochMs: serverTime + 3 });
    assert.equal(conflict.body.code, "walk_lease_conflict", "other device is judged by the lease");
  });
});

function installClock(start: { wall: number; mono: number }) {
  const clock = { ...start };
  hooks.setFieldWalkLedgerTestClock?.({
    wallEpochMs: () => clock.wall,
    monotonicMs: () => clock.mono
  });
  // Pre-change builds read Date.now() directly.
  Date.now = () => clock.wall;
  return clock;
}

test("S19-20 a forward wall-clock step does not expire the lease early and blocks new activation", async () => {
  await withLedger((ledgerPath) => {
    const clock = installClock({ wall: 1_700_000_000_000, mono: 1_000 });
    const holder = binding("account-clock-forward");
    const started = ledger.commandFieldWalk(holder, command({
      schema_version: V1, request_id: "clock-forward-start", action: "start", walk_id: "walk-clock-forward"
    }), { ledgerPath });
    assert.equal(started.status, 200);
    const acquiredAt = Number(started.body.acquired_at_epoch_ms);
    // 1 s of real (monotonic) time, but the wall clock jumps forward 10 minutes.
    clock.mono += 1_000;
    clock.wall += 1_000 + 10 * 60_000;
    const renewed = ledger.commandFieldWalk(holder, command({
      schema_version: V1,
      request_id: "clock-forward-renew",
      action: "renew",
      walk_id: "walk-clock-forward",
      lease_id: started.body.lease_id,
      fencing_token: started.body.fencing_token
    }), { ledgerPath });
    assert.equal(renewed.status, 200, JSON.stringify(renewed.body));
    assert.equal(renewed.body.result, "RENEWED");
    assert.equal(Number(renewed.body.server_time_epoch_ms), acquiredAt + 1_000);
    // Another account cannot activate while the clock is unstable (one lease TTL).
    const other = binding("account-clock-other");
    const blocked = ledger.commandFieldWalk(other, command({
      schema_version: V1, request_id: "clock-other-start", action: "start", walk_id: "walk-clock-other"
    }), { ledgerPath });
    assert.equal(blocked.status, 503, JSON.stringify(blocked.body));
    assert.equal(blocked.body.code, "field_walk_clock_unavailable");
    // The holder can still end its walk.
    clock.mono += 1_000;
    clock.wall += 1_000;
    const ended = ledger.commandFieldWalk(holder, command({
      schema_version: V1,
      request_id: "clock-forward-end",
      action: "end",
      walk_id: "walk-clock-forward",
      lease_id: started.body.lease_id,
      fencing_token: started.body.fencing_token
    }), { ledgerPath });
    assert.equal(ended.status, 200);
    assert.equal(ended.body.result, "ENDED");
    // After one lease TTL of monotonic time, new activation resumes.
    clock.mono += 90_000;
    clock.wall += 90_000;
    const resumed = ledger.commandFieldWalk(other, command({
      schema_version: V1, request_id: "clock-other-start-2", action: "start", walk_id: "walk-clock-other"
    }), { ledgerPath });
    assert.equal(resumed.status, 200);
    assert.equal(resumed.body.result, "ACQUIRED");
  });
});

test("S19-20 a backward wall-clock step does not extend the lease", async () => {
  await withLedger((ledgerPath) => {
    const clock = installClock({ wall: 1_700_000_000_000, mono: 5_000 });
    const holder = binding("account-clock-backward");
    const started = ledger.commandFieldWalk(holder, command({
      schema_version: V1, request_id: "clock-backward-start", action: "start", walk_id: "walk-clock-backward"
    }), { ledgerPath });
    assert.equal(started.status, 200);
    clock.mono += 1_000;
    clock.wall += 1_000 - 10 * 60_000;
    assert.equal(ledger.getFieldWalk(holder, { ledgerPath }).body.result, "ACTIVE");
    // 91 s of monotonic time without renew: the lease must be gone even though the wall
    // clock still reads ten minutes earlier than the acquisition.
    clock.mono += 91_000;
    clock.wall += 91_000;
    assert.equal(ledger.getFieldWalk(holder, { ledgerPath }).body.result, "NONE");
  });
});

test("S19-20 wall-clock jitter below the tolerance keeps normal operation", async () => {
  await withLedger((ledgerPath) => {
    const clock = installClock({ wall: 1_700_000_000_000, mono: 9_000 });
    const holder = binding("account-clock-jitter");
    const started = ledger.commandFieldWalk(holder, command({
      schema_version: V1, request_id: "clock-jitter-start", action: "start", walk_id: "walk-clock-jitter"
    }), { ledgerPath });
    assert.equal(started.status, 200);
    clock.mono += 1_000;
    clock.wall += 1_000 + 1_500;
    const other = binding("account-clock-jitter-other");
    const allowed = ledger.commandFieldWalk(other, command({
      schema_version: V1, request_id: "clock-jitter-other", action: "start", walk_id: "walk-clock-jitter-other"
    }), { ledgerPath });
    assert.equal(allowed.status, 200);
    assert.equal(Number(allowed.body.server_time_epoch_ms), clock.wall);
  });
});
