// S19-26: a lost required ledger is not recreated as a first run.
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, before } from "node:test";

import type { GatewayFieldLongSessionBinding } from "../src/auth.js";
import * as ledger from "../src/field-walk-ledger.js";
import { assertGatewayManagedStateReady } from "../src/state-encryption-maintenance.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let keyringDirectory = "";
const savedEnvironment = {
  root: process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR,
  walk: process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH,
  marker: process.env.WALKSAFE_GATEWAY_STATE_INIT_MARKER
};

before(async () => {
  keyringDirectory = await mkdtemp(path.join(tmpdir(), "state-init-marker-keyring-"));
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

const owner: GatewayFieldLongSessionBinding = {
  actorId: "marker-account",
  accountId: "marker-account",
  deviceId: "marker-device",
  familyId: "marker-family",
  sessionRotation: 1
};

// The owner's home shard file (S19-14); shard 0 is the configured path itself.
const ownerShard = (walkLedger: string): string =>
  (ledger as unknown as {
    fieldWalkLedgerShardPathForTests?: (base: string, accountId: string) => string;
  }).fieldWalkLedgerShardPathForTests?.(walkLedger, owner.accountId) ?? walkLedger;

/** A state root and a separate walk-ledger volume, as in a deployment. */
async function environment(): Promise<{ root: string; walkLedger: string; cleanup: () => Promise<void> }> {
  const root = await mkdtemp(path.join(tmpdir(), "state-init-marker-root-"));
  const walkVolume = await mkdtemp(path.join(tmpdir(), "state-init-marker-walk-"));
  const walkLedger = path.join(walkVolume, "field-walk-ledger.json");
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = root;
  process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = walkLedger;
  delete process.env.WALKSAFE_GATEWAY_STATE_INIT_MARKER;
  return {
    root,
    walkLedger,
    cleanup: async () => {
      await rm(root, { recursive: true, force: true });
      await rm(walkVolume, { recursive: true, force: true });
    }
  };
}

function start(requestId: string, walkId: string, nowEpochMs: number) {
  const command = ledger.parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: requestId,
    action: "start",
    walk_id: walkId
  });
  assert.ok(command);
  return ledger.commandFieldWalk(owner, command, { nowEpochMs });
}

function end(result: ReturnType<typeof start>, requestId: string, nowEpochMs: number) {
  const command = ledger.parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: requestId,
    action: "end",
    walk_id: result.body.walk_id,
    lease_id: result.body.lease_id,
    fencing_token: result.body.fencing_token
  });
  assert.ok(command);
  return ledger.commandFieldWalk(owner, command, { nowEpochMs });
}

test("S19-26 first run starts empty; a later-deleted walk ledger fails closed instead of fencing 1", async () => {
  const env = await environment();
  try {
    // First service start: nothing exists yet, so an empty ledger is legitimate.
    assertGatewayManagedStateReady();
    const first = start("marker-start-0001", "marker-walk-0001", 1_000);
    assert.equal(first.status, 200);
    assert.equal(first.body.fencing_token, 1);
    assert.equal(end(first, "marker-end-0001", 2_000).status, 200);
    const second = start("marker-start-0002", "marker-walk-0002", 3_000);
    assert.equal(second.body.fencing_token, 2);

    // Operational loss of the ledger (deleted file or wrong walk-ledger volume).
    await rm(ownerShard(env.walkLedger));
    assert.throws(
      () => start("marker-start-0003", "marker-walk-0003", 4_000),
      (error: unknown) => error instanceof Error && (error as { code?: string }).code === "state_file_missing"
    );
    assert.throws(
      () => ledger.getFieldWalk(owner, { nowEpochMs: 4_000 }),
      (error: unknown) => error instanceof Error && (error as { code?: string }).code === "state_file_missing"
    );
    // The next service start is refused as well.
    assert.throws(
      () => assertGatewayManagedStateReady(),
      (error: unknown) => error instanceof Error && (error as { code?: string }).code === "state_file_missing"
    );
  } finally {
    await env.cleanup();
  }
});

test("S19-26 a pre-existing ledger is registered at start and its later loss refuses start", async () => {
  const env = await environment();
  try {
    // State written by an earlier build before the marker existed.
    const created = start("marker-legacy-0001", "marker-legacy-walk", 1_000);
    assert.equal(created.status, 200);
    assertGatewayManagedStateReady();
    const marker = JSON.parse(
      await readFile(path.join(env.root, "state-init-marker.v1"), "utf8")
    ) as { required_files: string[] };
    // S19-14-F1: shard 0 (the configured path) always exists once an account shard is used,
    // because it carries the shard-layout stamp that refuses a pre-shard rollback.
    assert.deepEqual(
      marker.required_files,
      [...new Set([env.walkLedger, ownerShard(env.walkLedger)])].sort()
    );
    await rm(ownerShard(env.walkLedger));
    assert.throws(() => assertGatewayManagedStateReady(), /required gateway state file is missing/);
  } finally {
    await env.cleanup();
  }
});

test("S19-26 a damaged marker fails closed at start and on a missing-ledger read", async () => {
  const env = await environment();
  try {
    assertGatewayManagedStateReady();
    await writeFile(path.join(env.root, "state-init-marker.v1"), "{\"schema_version\":", {
      mode: 0o600
    });
    assert.throws(() => assertGatewayManagedStateReady(), /gateway state init marker is damaged/);
    assert.throws(() => start("marker-damaged-0001", "marker-damaged-walk", 1_000),
      /gateway state init marker is damaged/);
  } finally {
    await env.cleanup();
  }
});

test("S19-26 required mode refuses a state root without a marker (empty or wrong volume)", async () => {
  const env = await environment();
  try {
    process.env.WALKSAFE_GATEWAY_STATE_INIT_MARKER = "required";
    assert.throws(
      () => assertGatewayManagedStateReady(),
      /init marker is required but missing/
    );
    process.env.WALKSAFE_GATEWAY_STATE_INIT_MARKER = "sometimes";
    assert.throws(() => assertGatewayManagedStateReady(), /must be required or optional/);
  } finally {
    delete process.env.WALKSAFE_GATEWAY_STATE_INIT_MARKER;
    await env.cleanup();
  }
});

test("S19-26 production requires the marker by default and initializes only with the explicit first-run flag", async () => {
  const env = await environment();
  const { assertGatewayStateInitMarker } = await import("../src/state-init-marker.js");
  const markerPath = path.join(env.root, "state-init-marker.v1");
  const production = (extra: Record<string, string> = {}): NodeJS.ProcessEnv => ({
    ...process.env,
    NODE_ENV: "production",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: env.root,
    ...extra
  });
  try {
    // Owner decision 7: no marker in production is an empty or wrong volume, not a first run.
    assert.throws(
      () => assertGatewayStateInitMarker([env.walkLedger], production()),
      /init marker is required but missing.*WALKSAFE_GATEWAY_STATE_INIT=first-run/
    );
    await assert.rejects(readFile(markerPath, "utf8"), { code: "ENOENT" });
    // Production cannot opt back into silent re-initialization.
    assert.throws(
      () => assertGatewayStateInitMarker(
        [env.walkLedger],
        production({ WALKSAFE_GATEWAY_STATE_INIT_MARKER: "optional" })
      ),
      /optional is not allowed in production/
    );
    assert.throws(
      () => assertGatewayStateInitMarker(
        [env.walkLedger],
        production({ WALKSAFE_GATEWAY_STATE_INIT: "yes" })
      ),
      /WALKSAFE_GATEWAY_STATE_INIT must be first-run or unset/
    );
    // First installation start: the explicit flag creates the marker once.
    const created = assertGatewayStateInitMarker(
      [env.walkLedger],
      production({ WALKSAFE_GATEWAY_STATE_INIT: "first-run" })
    );
    assert.deepEqual(created, { created: true, required_files: [] });
    assert.equal(JSON.parse(await readFile(markerPath, "utf8")).schema_version,
      "walksafe.gateway-state-init-marker.v1");
    // A flag left in the environment after initialization refuses start.
    assert.throws(
      () => assertGatewayStateInitMarker(
        [env.walkLedger],
        production({ WALKSAFE_GATEWAY_STATE_INIT: "first-run" })
      ),
      /already initialized; remove WALKSAFE_GATEWAY_STATE_INIT=first-run/
    );
    // Normal production restarts with the marker present succeed without any flag.
    assert.equal(assertGatewayStateInitMarker([env.walkLedger], production()).created, false);
    // Losing the marker again (wrong volume) refuses start by default.
    await rm(markerPath);
    assert.throws(
      () => assertGatewayStateInitMarker([env.walkLedger], production()),
      /init marker is required but missing/
    );
    // Development and tests keep the optional first-run behavior.
    assert.equal(
      assertGatewayStateInitMarker(
        [env.walkLedger],
        { ...process.env, NODE_ENV: "test", WALKSAFE_GATEWAY_RATE_LIMIT_DIR: env.root }
      ).created,
      true
    );
  } finally {
    await env.cleanup();
  }
});

test("S19-26 privacy ledgers: a registered privacy-rights ledger and a listed deletion ledger fail closed when lost", async () => {
  const env = await environment();
  try {
    assertGatewayManagedStateReady();
    const rights = await import("../src/privacy-rights.js");
    const deletion = await import("../src/privacy-deletion-v2.js");
    assert.equal(rights.activateActorGeneration("marker-privacy-actor", 1_000), 1);
    const rightsLedger = path.join(env.root, "privacy-rights", "ledger.json");
    const marker = JSON.parse(
      await readFile(path.join(env.root, "state-init-marker.v1"), "utf8")
    ) as { schema_version: string; initialized_at_epoch_ms: number; required_files: string[] };
    assert.deepEqual(marker.required_files, [rightsLedger]);
    await rm(rightsLedger);
    assert.throws(() => rights.currentActorGeneration("marker-privacy-actor"),
      /privacy ledger is missing/);

    // A deletion ledger listed by an earlier start (fences, receipts) that is now gone.
    const deletionLedger = deletion.privacyDeletionV2LedgerPathForTests();
    await writeFile(path.join(env.root, "state-init-marker.v1"), JSON.stringify({
      ...marker,
      required_files: [deletionLedger].sort()
    }) + "\n", { mode: 0o600 });
    assert.throws(() => deletion.isAccountGenerationFencedV2("marker-privacy-actor", 1),
      /privacy deletion v2 ledger is missing/);
    assert.throws(() => assertGatewayManagedStateReady(), /required gateway state file is missing/);
  } finally {
    await env.cleanup();
  }
});

test("S19-26 without a service start marker the legacy first-run behavior is unchanged", async () => {
  const env = await environment();
  try {
    const first = start("marker-none-0001", "marker-none-walk", 1_000);
    assert.equal(first.body.fencing_token, 1);
    await rm(ownerShard(env.walkLedger));
    const again = start("marker-none-0002", "marker-none-walk-2", 2_000);
    assert.equal(again.status, 200);
    assert.equal(again.body.fencing_token, 1);
  } finally {
    await env.cleanup();
  }
});
test("S19-26-F1 a start with a marker registers an existing but unlisted required ledger", async () => {
  const env = await environment();
  try {
    assertGatewayManagedStateReady();
    assert.equal(start("marker-unlisted-0001", "marker-unlisted-walk", 1_000).status, 200);
    // Crash between the ledger's first durable write and its registration: the marker
    // does not list the file although it exists.
    const markerPath = path.join(env.root, "state-init-marker.v1");
    const marker = JSON.parse(await readFile(markerPath, "utf8")) as { required_files: string[] };
    await writeFile(markerPath, `${JSON.stringify({
      ...marker,
      required_files: marker.required_files.filter((file) => file !== ownerShard(env.walkLedger))
    })}\n`);
    assertGatewayManagedStateReady();
    const restarted = JSON.parse(await readFile(markerPath, "utf8")) as { required_files: string[] };
    assert.ok(restarted.required_files.includes(ownerShard(env.walkLedger)),
      JSON.stringify(restarted.required_files));
    // Its later loss is refused instead of restarting fencing at 1.
    await rm(ownerShard(env.walkLedger));
    assert.throws(() => assertGatewayManagedStateReady(), /required gateway state file is missing/);
  } finally {
    await env.cleanup();
  }
});
