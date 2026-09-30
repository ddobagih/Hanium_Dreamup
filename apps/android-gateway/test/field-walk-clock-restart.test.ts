// S19-20-F1/F2 (w22 review): a wall-clock step absorbed by the walk ledger must survive a
// restart, must not stay absorbed forever, and must not turn the S19-17 v2 window against
// correctly clocked devices.
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, afterEach, before } from "node:test";
import type { GatewayFieldLongSessionBinding } from "../src/auth.js";
import * as ledger from "../src/field-walk-ledger.js";
import * as telemetry from "../src/telemetry.js";
import { assertGatewayManagedStateReady } from "../src/state-encryption-maintenance.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";
// Namespace access keeps this file loadable against a pre-change build for the red run.
type TestClock = { wallEpochMs: () => number; monotonicMs: () => number };
const hooks = ledger as unknown as {
  setFieldWalkLedgerTestClock?: (clock: TestClock | null) => void;
};
type StateEvent = Record<string, unknown>;
const telemetryHooks = telemetry as unknown as {
  setGatewayStateTelemetrySinkForTests?: (sink: ((event: StateEvent) => void) | null) => void;
};
const V1 = "walksafe.field-walk-command.v1";
const V2 = "walksafe.field-walk-command.v2";
const HOUR = 60 * 60 * 1000;
const TTL = 90_000;
let keyringDirectory = "";
const savedEnvironment = {
  root: process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR,
  walk: process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH,
  marker: process.env.WALKSAFE_GATEWAY_STATE_INIT_MARKER
};
before(async () => {
  keyringDirectory = await mkdtemp(path.join(tmpdir(), "field-walk-clock-restart-key-"));
  await configureTestStateEncryption(path.join(keyringDirectory, "state-keyring.json"));
});
after(async () => {
  for (const [key, value] of [
    ["WALKSAFE_GATEWAY_RATE_LIMIT_DIR", savedEnvironment.root],
    ["WALKSAFE_FIELD_WALK_LEDGER_PATH", savedEnvironment.walk],
    ["WALKSAFE_GATEWAY_STATE_INIT_MARKER", savedEnvironment.marker]
  ] as const) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  await rm(keyringDirectory, { recursive: true, force: true });
});
const realDateNow = Date.now;
afterEach(() => {
  hooks.setFieldWalkLedgerTestClock?.(null);
  telemetryHooks.setGatewayStateTelemetrySinkForTests?.(null);
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
async function withLedger(run: (ledgerPath: string) => Promise<void> | void): Promise<void> {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-clock-restart-"));
  try {
    await run(path.join(directory, "ledger.json"));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}
type Clock = { wall: number; mono: number };
/** Installing a clock also drops the in-process clock state, i.e. models a process restart. */
function install(clock: Clock): void {
  hooks.setFieldWalkLedgerTestClock?.({
    wallEpochMs: () => clock.wall,
    monotonicMs: () => clock.mono
  });
  Date.now = () => clock.wall;
}
function advance(clock: Clock, ms: number): void {
  clock.mono += ms;
  clock.wall += ms;
}
function start(ledgerPath: string, owner: GatewayFieldLongSessionBinding, id: string, walk: string) {
  return ledger.commandFieldWalk(owner, command({
    schema_version: V1, request_id: id, action: "start", walk_id: walk
  }), { ledgerPath });
}
function renew(
  ledgerPath: string,
  owner: GatewayFieldLongSessionBinding,
  id: string,
  started: ledger.FieldWalkLedgerResult
) {
  return ledger.commandFieldWalk(owner, command({
    schema_version: V1,
    request_id: id,
    action: "renew",
    walk_id: started.body.walk_id,
    lease_id: started.body.lease_id,
    fencing_token: started.body.fencing_token
  }), { ledgerPath });
}
/** The Android lease parser's constraints (GatewayWalkResponseParser.lease). */
function assertAndroidParsableLease(body: Record<string, unknown>): void {
  const server = Number(body.server_time_epoch_ms);
  const remaining = Number(body.lease_expires_at_epoch_ms) - server;
  assert.ok(Number(body.acquired_at_epoch_ms) <= server, JSON.stringify(body));
  assert.ok(remaining >= 1 && remaining <= TTL, `remaining ${remaining}`);
}

test("S19-20 restart after an absorbed backward step does not keep the lease past its TTL", async () => {
  await withLedger((ledgerPath) => {
    const clock = { wall: 1_700_000_000_000, mono: 1_000 };
    install(clock);
    const holder = binding("restart-back");
    const started = start(ledgerPath, holder, "restart-back-start", "restart-back-walk");
    assert.equal(started.status, 200);
    clock.mono += 1_000;
    clock.wall += 1_000 - 10 * 60_000;
    assert.equal(renew(ledgerPath, holder, "restart-back-renew", started).status, 200);
    install(clock);
    advance(clock, 120_000);
    assert.equal(
      ledger.getFieldWalk(holder, { ledgerPath }).body.result,
      "NONE",
      "120 s after the last renew the 90 s lease must be gone after a restart too"
    );
    const other = start(ledgerPath, binding("restart-back", "restart-back-device-2"),
      "restart-back-other", "restart-back-other-walk");
    assert.equal(other.status, 200, JSON.stringify(other.body));
    assert.equal(other.body.result, "ACQUIRED");
  });
});

test("S19-20 restart after an absorbed forward step keeps a renewed lease through the re-anchor", async () => {
  await withLedger((ledgerPath) => {
    const clock = { wall: 1_700_000_000_000, mono: 1_000 };
    install(clock);
    const holder = binding("restart-fwd");
    const started = start(ledgerPath, holder, "restart-fwd-start", "restart-fwd-walk");
    assert.equal(started.status, 200);
    clock.mono += 1_000;
    clock.wall += 1_000 + 10 * 60_000;
    assert.equal(renew(ledgerPath, holder, "restart-fwd-renew-1", started).status, 200);
    install(clock);
    advance(clock, 1_000);
    const read = ledger.getFieldWalk(holder, { ledgerPath });
    assert.equal(read.body.result, "ACTIVE", "1 s after a renew the lease is still held after a restart");
    // The holder keeps renewing every 30 s across the end of the unstable window.
    for (let index = 2; index <= 4; index += 1) {
      advance(clock, 30_000);
      const renewed = renew(ledgerPath, holder, `restart-fwd-renew-${index}`, started);
      assert.equal(renewed.status, 200, JSON.stringify(renewed.body));
      assertAndroidParsableLease(renewed.body);
    }
    // More than one lease TTL after the step: server time is the wall clock again.
    const after = renew(ledgerPath, holder, "restart-fwd-renew-5", started);
    assert.equal(after.status, 200, JSON.stringify(after.body));
    assert.equal(Number(after.body.server_time_epoch_ms), clock.wall);
    assertAndroidParsableLease(after.body);
  });
});

test("S19-20 a >24 h forward step re-anchors after the window; correct v2 clients stay accepted", async () => {
  await withLedger((ledgerPath) => {
    const events: StateEvent[] = [];
    telemetryHooks.setGatewayStateTelemetrySinkForTests?.((event) => events.push(event));
    const clock = { wall: 1_700_000_000_000, mono: 1_000 };
    install(clock);
    ledger.getFieldWalk(binding("lag-seed"), { ledgerPath });
    clock.mono += 1_000;
    clock.wall += 1_000 + 25 * HOUR;
    const during = ledger.commandFieldWalk(binding("lag-a"), command({
      schema_version: V2,
      request_id: "lag-v2-during",
      action: "start",
      walk_id: "lag-walk-a",
      issued_at_epoch_ms: clock.wall
    }), { ledgerPath });
    assert.equal(during.status, 503, JSON.stringify(during.body));
    assert.equal(during.body.code, "field_walk_clock_unavailable");
    advance(clock, 10 * 60_000);
    const accepted = ledger.commandFieldWalk(binding("lag-a"), command({
      schema_version: V2,
      request_id: "lag-v2-after",
      action: "start",
      walk_id: "lag-walk-a",
      issued_at_epoch_ms: clock.wall
    }), { ledgerPath });
    assert.equal(accepted.status, 200, JSON.stringify(accepted.body));
    assert.equal(Number(accepted.body.server_time_epoch_ms), clock.wall, "no permanent skew");
    install(clock);
    advance(clock, 1_000);
    assert.equal(
      Number(ledger.getFieldWalk(binding("lag-a"), { ledgerPath }).body.server_time_epoch_ms),
      clock.wall
    );
    const steps = events.filter((event) => event.event_name === "walksafe.gateway.clock.step_detected");
    assert.equal(steps.length, 1, JSON.stringify(events));
    assert.equal(steps[0]!.step_ms, 25 * HOUR);
    assert.equal(steps[0]!.mode, "reanchor_after_window");
  });
});

test("S19-20 a >24 h backward step: renew continues, the lease is clamped to one TTL, server time returns to the wall", async () => {
  await withLedger((ledgerPath) => {
    const events: StateEvent[] = [];
    telemetryHooks.setGatewayStateTelemetrySinkForTests?.((event) => events.push(event));
    const clock = { wall: 1_700_000_000_000, mono: 1_000 };
    install(clock);
    const holder = binding("back-large");
    const started = start(ledgerPath, holder, "back-large-start", "back-large-walk");
    assert.equal(started.status, 200);
    clock.mono += 1_000;
    clock.wall += 1_000 - 25 * HOUR;
    for (let index = 1; index <= 4; index += 1) {
      advance(clock, 30_000);
      const renewed = renew(ledgerPath, holder, `back-large-renew-${index}`, started);
      assert.equal(renewed.status, 200, JSON.stringify(renewed.body));
      assertAndroidParsableLease(renewed.body);
    }
    assert.equal(Number(renew(ledgerPath, holder, "back-large-renew-5", started)
      .body.server_time_epoch_ms), clock.wall, "re-anchored to the wall clock");
    advance(clock, TTL + 1_000);
    assert.equal(ledger.getFieldWalk(holder, { ledgerPath }).body.result, "NONE",
      "without renew the lease ends one TTL after the last renew");
    const steps = events.filter((event) => event.event_name === "walksafe.gateway.clock.step_detected");
    assert.equal(steps.length, 1);
    assert.equal(steps[0]!.step_ms, -25 * HOUR);
    assert.equal(steps[0]!.mode, "reanchor_clamp_after_window");
  });
});

test("S19-20 a small backward step converges to the wall clock at a bounded rate", async () => {
  await withLedger((ledgerPath) => {
    const clock = { wall: 1_700_000_000_000, mono: 1_000 };
    install(clock);
    const seed = binding("slew-seed");
    ledger.getFieldWalk(seed, { ledgerPath });
    clock.mono += 1_000;
    clock.wall += 1_000 - 5 * 60_000;
    ledger.getFieldWalk(seed, { ledgerPath });
    advance(clock, TTL);
    const holder = binding("slew-holder");
    const started = start(ledgerPath, holder, "slew-start", "slew-walk");
    assert.equal(started.status, 200, JSON.stringify(started.body));
    // While converging, a 90 s lease lasts at most 100 s of real time.
    advance(clock, 80_000);
    assert.equal(ledger.getFieldWalk(holder, { ledgerPath }).body.result, "ACTIVE");
    advance(clock, 21_000);
    assert.equal(ledger.getFieldWalk(holder, { ledgerPath }).body.result, "NONE");
    const serverBefore = Number(ledger.getFieldWalk(seed, { ledgerPath }).body.server_time_epoch_ms);
    assert.ok(serverBefore > clock.wall, "still ahead of the wall clock while converging");
    advance(clock, 60 * 60_000);
    assert.equal(
      Number(ledger.getFieldWalk(seed, { ledgerPath }).body.server_time_epoch_ms),
      clock.wall,
      "a 5 min skew is gone within 50 min of convergence"
    );
  });
});

test("S19-20 a wall clock moved back while the service was down cannot extend a lease beyond one TTL", async () => {
  await withLedger((ledgerPath) => {
    const events: StateEvent[] = [];
    telemetryHooks.setGatewayStateTelemetrySinkForTests?.((event) => events.push(event));
    const clock = { wall: 1_700_000_000_000, mono: 1_000 };
    install(clock);
    const holder = binding("down-back");
    const started = start(ledgerPath, holder, "down-back-start", "down-back-walk");
    assert.equal(started.status, 200);
    clock.wall -= HOUR;
    clock.mono = 50;
    install(clock);
    const read = ledger.getFieldWalk(holder, { ledgerPath });
    assert.equal(read.body.result, "ACTIVE");
    assert.ok(Number(read.body.lease_expires_at_epoch_ms) - Number(read.body.server_time_epoch_ms) <= TTL);
    const renewed = renew(ledgerPath, holder, "down-back-renew", started);
    assert.equal(renewed.status, 200, JSON.stringify(renewed.body));
    assertAndroidParsableLease(renewed.body);
    advance(clock, TTL + 1_000);
    assert.equal(ledger.getFieldWalk(holder, { ledgerPath }).body.result, "NONE");
    assert.ok(events.some((event) => event.event_name === "walksafe.gateway.clock.lease_clamped"),
      JSON.stringify(events));
  });
});

test("S19-20 the clock anchor is encrypted required state: registered at write, a later loss fails closed", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "field-walk-clock-root-"));
  const walkVolume = await mkdtemp(path.join(tmpdir(), "field-walk-clock-walk-"));
  const walkLedger = path.join(walkVolume, "field-walk-ledger.json");
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = root;
  process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = walkLedger;
  delete process.env.WALKSAFE_GATEWAY_STATE_INIT_MARKER;
  try {
    assertGatewayManagedStateReady();
    const clock = { wall: 1_700_000_000_000, mono: 1_000 };
    install(clock);
    const holder = binding("anchor-file");
    ledger.getFieldWalk(holder);
    clock.mono += 1_000;
    clock.wall += 1_000 + 10 * 60_000;
    ledger.getFieldWalk(holder);
    const clockFile = `${walkLedger}.clock`;
    const metadata = await stat(clockFile);
    assert.equal(metadata.mode & 0o777, 0o600);
    const raw = await readFile(clockFile, "utf8");
    assert.doesNotMatch(raw, /anchor_wall_epoch_ms/, "the anchor is not stored as plaintext");
    const marker = JSON.parse(await readFile(path.join(root, "state-init-marker.v1"), "utf8")) as {
      required_files: string[];
    };
    assert.ok(marker.required_files.includes(clockFile), JSON.stringify(marker));
    const inspected = assertGatewayManagedStateReady();
    assert.ok(inspected.some((result) =>
      result.kind === "field-walk-ledger" && result.statusBefore === "encrypted-active"));
    await rm(clockFile);
    install(clock);
    assert.throws(() => ledger.getFieldWalk(holder), { name: "FieldWalkLedgerStateMissingError" });
    assert.throws(() => assertGatewayManagedStateReady(), { name: "GatewayStateFileMissingError" });
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(walkVolume, { recursive: true, force: true });
  }
});
