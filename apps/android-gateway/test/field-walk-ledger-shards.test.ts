// S19-14: accounts no longer share one walk-ledger file and lock.
import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, before } from "node:test";

import type { GatewayFieldLongSessionBinding } from "../src/auth.js";
import * as ledger from "../src/field-walk-ledger.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";

const shardApi = ledger as unknown as {
  fieldWalkLedgerShardPathForTests?: (base: string, accountId: string) => string;
};
// Pre-change builds keep every account in the configured file (used for the red run).
const homeOf = (base: string, accountId: string): string =>
  shardApi.fieldWalkLedgerShardPathForTests?.(base, accountId) ?? base;

let keyringDirectory = "";
before(async () => {
  keyringDirectory = await mkdtemp(path.join(tmpdir(), "field-walk-shards-keyring-"));
  await configureTestStateEncryption(path.join(keyringDirectory, "state-keyring.json"));
});
after(async () => {
  await rm(keyringDirectory, { recursive: true, force: true });
});

function binding(accountId: string): GatewayFieldLongSessionBinding {
  return {
    actorId: accountId,
    accountId,
    deviceId: `${accountId}-device`,
    familyId: `${accountId}-family`,
    sessionRotation: 1
  };
}

// account-1 and account-2 hash to different shards (7 and 0).
const A = binding("account-1");
const B = binding("account-2");

function start(owner: GatewayFieldLongSessionBinding, requestId: string, walkId: string, ledgerPath: string, nowEpochMs: number) {
  const command = ledger.parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1", request_id: requestId, action: "start", walk_id: walkId
  });
  assert.ok(command);
  return ledger.commandFieldWalk(owner, command, { ledgerPath, nowEpochMs });
}

function lease(
  owner: GatewayFieldLongSessionBinding,
  action: "renew" | "end",
  acquired: { body: Record<string, unknown> },
  requestId: string,
  ledgerPath: string,
  nowEpochMs: number
) {
  const command = ledger.parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: requestId,
    action,
    walk_id: acquired.body.walk_id,
    lease_id: acquired.body.lease_id,
    fencing_token: acquired.body.fencing_token
  });
  assert.ok(command);
  return ledger.commandFieldWalk(owner, command, { ledgerPath, nowEpochMs });
}

function waitForLine(child: ChildProcessWithoutNullStreams, expected: string): Promise<void> {
  return new Promise((resolve, reject) => {
    let buffered = "";
    let stderr = "";
    child.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString("utf8"); });
    child.stdout.on("data", (chunk: Buffer) => {
      buffered += chunk.toString("utf8");
      if (buffered.split(/\r?\n/).includes(expected)) resolve();
    });
    child.once("exit", (code) => {
      if (!buffered.split(/\r?\n/).includes(expected)) {
        reject(new Error(`lock holder exited ${code}: ${stderr}`));
      }
    });
  });
}

test("S19-14 a held lock of one account's shard does not block an account in another shard", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-shards-"));
  const ledgerPath = path.join(directory, "ledger.json");
  let holder: ChildProcessWithoutNullStreams | null = null;
  try {
    const aStarted = start(A, "shard-a-start-1", "shard-a-walk-1", ledgerPath, 1_000);
    const bStarted = start(B, "shard-b-start-1", "shard-b-walk-1", ledgerPath, 1_000);
    assert.equal(aStarted.status, 200);
    assert.equal(bStarted.status, 200);
    const aHome = homeOf(ledgerPath, A.accountId);
    // Another process holds the lock the ledger uses for account A (a slow heartbeat).
    holder = spawn(process.execPath, [
      "--input-type=module",
      "-e",
      [
        "const [url, lockPath] = process.argv.slice(1);",
        "const lock = await import(url);",
        "lock.acquireExclusiveFileLockLease(lockPath);",
        "process.stdout.write('locked\\n');",
        "setInterval(() => {}, 1000);"
      ].join("\n"),
      new URL("../src/exclusive-file-lock.js", import.meta.url).href,
      `${aHome}.lock`
    ], { stdio: ["pipe", "pipe", "pipe"] });
    await waitForLine(holder, "locked");
    const aBytes = await readFile(aHome, "utf8");

    const bRenewed = lease(B, "renew", bStarted, "shard-b-renew-1", ledgerPath, 2_000);
    assert.equal(bRenewed.status, 200, "an unrelated account must not wait for A's lock");
    assert.equal(bRenewed.body.result, "RENEWED");
    assert.throws(
      () => lease(A, "renew", aStarted, "shard-a-renew-1", ledgerPath, 2_000),
      (error: unknown) => (error as { code?: string }).code === "exclusive_file_lock_busy"
    );
    // B's heartbeat rewrote only B's shard.
    assert.equal(await readFile(aHome, "utf8"), aBytes);
    assert.notEqual(homeOf(ledgerPath, B.accountId), aHome);
  } finally {
    if (holder && holder.exitCode === null) {
      const exited = new Promise<void>((resolve) => holder!.once("exit", () => resolve()));
      holder.kill("SIGKILL");
      await exited;
    }
    await rm(directory, { recursive: true, force: true });
  }
});

test("S19-14 fencing stays monotonic per account across shards", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-shards-fence-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    let time = 1_000;
    for (let walk = 1; walk <= 3; walk += 1) {
      const aStarted = start(A, `fence-a-start-${walk}`, `fence-a-walk-${walk}`, ledgerPath, time);
      assert.equal(aStarted.body.fencing_token, walk);
      assert.equal(lease(A, "end", aStarted, `fence-a-end-${walk}`, ledgerPath, time + 1).status, 200);
      time += 10;
    }
    const bStarted = start(B, "fence-b-start-1", "fence-b-walk-1", ledgerPath, time);
    assert.equal(bStarted.body.fencing_token, 1);
    const aAgain = start(A, "fence-a-start-4", "fence-a-walk-4", ledgerPath, time);
    assert.equal(aAgain.body.fencing_token, 4);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S19-14 an account written by the single-file build moves to its shard without resetting fencing", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-shards-legacy-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const legacy = {
      schemaVersion: "walksafe.field-walk-ledger.v1",
      accounts: {
        "account-1": { nextFencingToken: 5, active: null, requests: {} },
        "account-2": { nextFencingToken: 9, active: null, requests: {} }
      },
      audit: [{
        transition: "ENDED",
        accountId: "account-1",
        walkId: "legacy-walk-0004",
        leaseId: "legacy-lease-0004",
        fencingToken: 4,
        actorId: "account-1",
        deviceId: "account-1-device",
        familyId: "account-1-family",
        occurredAtEpochMs: 500
      }]
    };
    await writeFile(ledgerPath, encryptTestStateFile(
      { kind: "field-walk-ledger", recordId: path.basename(ledgerPath) },
      legacy,
      16 * 1024 * 1024
    ), { mode: 0o600 });
    const aStarted = start(A, "legacy-a-start-5", "legacy-a-walk-5", ledgerPath, 1_000);
    assert.equal(aStarted.body.fencing_token, 5, "fencing continues from the legacy ledger");
    const bStarted = start(B, "legacy-b-start-9", "legacy-b-walk-9", ledgerPath, 1_000);
    assert.equal(bStarted.body.fencing_token, 9);
    const aHome = homeOf(ledgerPath, A.accountId);
    if (aHome !== ledgerPath) {
      const read = async (file: string) => decryptTestStateFile<typeof legacy>(
        { kind: "field-walk-ledger", recordId: path.basename(file) },
        await readFile(file, "utf8"),
        16 * 1024 * 1024
      );
      const shard = await read(aHome);
      const base = await read(ledgerPath);
      assert.ok(Object.hasOwn(shard.accounts, "account-1"));
      assert.equal(Object.hasOwn(base.accounts, "account-1"), false);
      assert.ok(Object.hasOwn(base.accounts, "account-2"));
      assert.deepEqual(shard.audit.map((entry) => entry.walkId), ["legacy-walk-0004", "legacy-a-walk-5"]);
      assert.equal(base.audit.some((entry) => entry.accountId === "account-1"), false);
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
test("S19-14-F1 shard 0 carries the layout stamp once another shard is used, so a pre-shard reader refuses it", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-shards-stamp-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    // account-1 is homed in shard 7: its first command must stamp shard 0 first.
    assert.equal(start(A, "stamp-a-start-1", "stamp-a-walk-1", ledgerPath, 1_000).status, 200);
    const base = await decryptTestStateFile<Record<string, unknown>>(
      { kind: "field-walk-ledger", recordId: path.basename(ledgerPath) },
      await readFile(ledgerPath, "utf8"),
      16 * 1024 * 1024
    );
    assert.equal(base.shardLayout, "walksafe.field-walk-ledger-shards.v1");
    // The pre-shard validator accepted exactly these three keys and nothing else.
    assert.notDeepEqual(Object.keys(base).sort(), ["accounts", "audit", "schemaVersion"]);
    // The stamp is kept by later shard-0 writes and by the legacy migration path.
    assert.equal(start(B, "stamp-b-start-1", "stamp-b-walk-1", ledgerPath, 2_000).status, 200);
    const after = await decryptTestStateFile<Record<string, unknown>>(
      { kind: "field-walk-ledger", recordId: path.basename(ledgerPath) },
      await readFile(ledgerPath, "utf8"),
      16 * 1024 * 1024
    );
    assert.equal(after.shardLayout, "walksafe.field-walk-ledger-shards.v1");
    assert.equal(Object.hasOwn(after.accounts as object, "account-2"), true);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
