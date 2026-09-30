import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, afterEach, before } from "node:test";

import {
  commandFieldWalk,
  endFieldWalkForRevokedSession,
  fieldWalkLedgerShardPathForTests,
  fieldWalkLedgerShardPaths,
  FieldWalkLedgerCapacityError,
  FieldWalkLedgerStorageOutcomeUnknownError,
  getFieldWalk,
  parseFieldWalkCommand,
  setFieldWalkLedgerTestFault,
  setFieldWalkLedgerTestLimits
} from "../src/field-walk-ledger.js";
import {
  ExclusiveFileLockBusyError,
  withExclusiveFileLockAsync
} from "../src/exclusive-file-lock.js";
import type { GatewayFieldLongSessionBinding } from "../src/auth.js";
import { handleGatewayRequest } from "../src/routes.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";

let encryptionFixtureDirectory = "";
const FIELD_WALK_LEDGER_MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024;

before(async () => {
  encryptionFixtureDirectory = await mkdtemp(path.join(tmpdir(), "field-walk-encryption-"));
  await configureTestStateEncryption(
    path.join(encryptionFixtureDirectory, "state-keyring.json")
  );
});

after(async () => {
  await rm(encryptionFixtureDirectory, { recursive: true, force: true });
});

const first: GatewayFieldLongSessionBinding = {
  actorId: "account-1",
  accountId: "account-1",
  deviceId: "device-1",
  familyId: "family-1",
  sessionRotation: 1
};
const second: GatewayFieldLongSessionBinding = {
  actorId: "account-1",
  accountId: "account-1",
  deviceId: "device-2",
  familyId: "family-2",
  sessionRotation: 1
};

afterEach(() => {
  setFieldWalkLedgerTestFault(null);
  setFieldWalkLedgerTestLimits(null);
});

function childOutput(child: ChildProcessWithoutNullStreams): Promise<string> {
  return new Promise((resolve, reject) => {
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk: string) => {
      stderr += chunk;
    });
    child.once("error", reject);
    child.once("exit", (code) => {
      if (code === 0) resolve(stdout);
      else reject(new Error(`ledger child exited ${code}: ${stderr}`));
    });
  });
}

function waitForChildLine(
  child: ChildProcessWithoutNullStreams,
  expected: string
): Promise<void> {
  return new Promise((resolve, reject) => {
    let buffered = "";
    const onData = (chunk: Buffer): void => {
      buffered += chunk.toString("utf8");
      if (buffered.split(/\r?\n/).includes(expected)) {
        child.stdout.off("data", onData);
        resolve();
      }
    };
    child.stdout.on("data", onData);
    child.once("error", reject);
    child.once("exit", (code) => {
      if (!buffered.split(/\r?\n/).includes(expected)) {
        reject(new Error(`ledger child exited ${code} before ${expected}`));
      }
    });
  });
}

/** S19-14: the account-1 shard file (every binding in this file uses account-1). */
function accountShard(ledgerPath: string, accountId = "account-1"): string {
  return fieldWalkLedgerShardPathForTests(ledgerPath, accountId);
}

/**
 * S19-14: the ledger is sharded by account hash. This reads every existing shard and merges
 * them into the former single-file view (single-account tests see exactly one shard).
 */
async function readFieldWalkState<T>(filePath: string): Promise<T> {
  const states: Array<{ accounts?: Record<string, unknown>; audit?: unknown[] }> = [];
  for (const shard of fieldWalkLedgerShardPaths(filePath)) {
    let raw: string;
    try {
      raw = await readFile(shard, "utf8");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
      throw error;
    }
    states.push(decryptTestStateFile(
      { kind: "field-walk-ledger", recordId: path.basename(shard) },
      raw,
      FIELD_WALK_LEDGER_MAX_PLAINTEXT_BYTES
    ));
  }
  if (states.length === 0) await readFile(filePath, "utf8");
  if (states.length === 1) return states[0] as T;
  return {
    ...states[0],
    accounts: Object.fromEntries(states.flatMap((state) => Object.entries(state.accounts ?? {}))),
    audit: states.flatMap((state) => state.audit ?? [])
  } as T;
}

test("field walk lease is idempotent and takeover uses the current walk and fence", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-ledger-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const start = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!;
    const acquired = commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 1_000 });
    assert.equal(acquired.status, 200);
    assert.equal(acquired.body.result, "ACQUIRED");
    assert.equal(acquired.body.fencing_token, 1);
    assert.equal(acquired.body.lease_expires_at_epoch_ms, 91_000);
    assert.deepEqual(
      commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 2_000 }),
      {
        status: 200,
        body: { ...acquired.body, server_time_epoch_ms: 2_000 }
      }
    );
    assert.equal(
      commandFieldWalk(second, start, {
        ledgerPath,
        nowEpochMs: 2_000
      }).body.code,
      "request_id_conflict"
    );
    const differentPayload = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-999"
    })!;
    assert.deepEqual(
      commandFieldWalk(first, differentPayload, {
        ledgerPath,
        nowEpochMs: 2_000
      }).body,
      {
        schema_version: "walksafe.field-walk-response.v1",
        code: "request_id_conflict",
        server_time_epoch_ms: 2_000
      }
    );
    const liveConflict = commandFieldWalk(
      second,
      parseFieldWalkCommand({
        schema_version: "walksafe.field-walk-command.v1",
        request_id: "request-9",
        action: "start",
        walk_id: "walk-999"
      })!,
      { ledgerPath, nowEpochMs: 2_000 }
    );
    assert.deepEqual(liveConflict.body, {
      schema_version: "walksafe.field-walk-response.v1",
      code: "walk_lease_conflict",
      active_walk_id: "walk-001",
      active_device_id: "device-1",
      fencing_token: 1,
      lease_expires_at_epoch_ms: 91_000,
      server_time_epoch_ms: 2_000
    });

    const active = getFieldWalk(second, { ledgerPath, nowEpochMs: 2_000 });
    assert.equal(active.body.result, "ACTIVE");
    assert.equal(active.body.held_by_current_device, false);

    const takeover = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-2",
      action: "takeover",
      walk_id: "walk-002",
      expected_active_walk_id: "walk-001",
      expected_fencing_token: 1,
      confirmation: "voice_confirmed"
    })!;
    const taken = commandFieldWalk(second, takeover, { ledgerPath, nowEpochMs: 3_000 });
    assert.equal(taken.body.result, "TAKEN_OVER");
    assert.equal(taken.body.fencing_token, 2);
    assert.deepEqual(
      commandFieldWalk(second, takeover, { ledgerPath, nowEpochMs: 3_500 }),
      {
        status: 200,
        body: { ...taken.body, server_time_epoch_ms: 3_500 }
      }
    );
    assert.deepEqual(
      commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 4_000 }),
      {
        status: 409,
        body: {
          schema_version: "walksafe.field-walk-response.v1",
          code: "walk_lease_conflict",
          active_walk_id: "walk-002",
          active_device_id: "device-2",
          fencing_token: 2,
          lease_expires_at_epoch_ms: 93_000,
          server_time_epoch_ms: 4_000
        }
      }
    );

    const state = await readFieldWalkState<{
      audit: Array<{ transition: string }>;
    }>(ledgerPath);
    assert.deepEqual(state.audit.map((entry) => entry.transition), ["ACQUIRED", "TAKEN_OVER"]);
    assert.equal(commandFieldWalk(second, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-3",
      action: "end",
      walk_id: "walk-002",
      lease_id: String(taken.body.lease_id),
      fencing_token: 2
    })!, { ledgerPath, nowEpochMs: 5_000 }).body.result, "ENDED");
    assert.deepEqual(commandFieldWalk(second, takeover, {
      ledgerPath, nowEpochMs: 6_000
    }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: null,
        active_device_id: null,
        fencing_token: null,
        lease_expires_at_epoch_ms: null,
        server_time_epoch_ms: 6_000
      }
    });
    // S01-31: an explicitly ended walk id stays ended; the next walk uses a new walk id.
    assert.equal(commandFieldWalk(second, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-4",
      action: "start",
      walk_id: "walk-003"
    })!, { ledgerPath, nowEpochMs: 7_000 }).body.fencing_token, 3);
    assert.deepEqual(commandFieldWalk(second, takeover, {
      ledgerPath, nowEpochMs: 8_000
    }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: "walk-003",
        active_device_id: "device-2",
        fencing_token: 3,
        lease_expires_at_epoch_ms: 97_000,
        server_time_epoch_ms: 8_000
      }
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("expired lease is audited before a new acquisition", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-expiry-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const command = (requestId: string, walkId: string) => parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: requestId,
      action: "start",
      walk_id: walkId
    })!;
    const original = command("request-1", "walk-001");
    commandFieldWalk(first, original, {
      ledgerPath,
      nowEpochMs: 1_000
    });
    const replay = commandFieldWalk(first, original, {
      ledgerPath,
      nowEpochMs: 91_000
    });
    assert.deepEqual(replay, {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: null,
        active_device_id: null,
        fencing_token: null,
        lease_expires_at_epoch_ms: null,
        server_time_epoch_ms: 91_000
      }
    });
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 91_000 }).body.result, "NONE");
    const acquired = commandFieldWalk(second, command("request-2", "walk-002"), {
      ledgerPath,
      nowEpochMs: 91_001
    });
    assert.equal(acquired.body.result, "ACQUIRED");
    assert.equal(acquired.body.fencing_token, 2);
    const state = await readFieldWalkState<{
      audit: Array<{ transition: string }>;
    }>(ledgerPath);
    assert.deepEqual(
      state.audit.map((entry) => entry.transition),
      ["ACQUIRED", "EXPIRED", "ACQUIRED"]
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("ended start requests cannot regain a lease or replay after same-walk replacement", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-ended-replay-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const start = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!;
    const acquired = commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 1_000 });
    assert.equal(acquired.body.result, "ACQUIRED");
    const alreadyActive = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-2",
      action: "start",
      walk_id: "walk-001"
    })!;
    assert.equal(commandFieldWalk(first, alreadyActive, {
      ledgerPath, nowEpochMs: 1_500
    }).body.result, "ALREADY_ACTIVE");
    const renew = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-renew",
      action: "renew",
      walk_id: "walk-001",
      lease_id: String(acquired.body.lease_id),
      fencing_token: 1
    })!;
    assert.equal(commandFieldWalk(first, renew, {
      ledgerPath, nowEpochMs: 1_750
    }).body.result, "RENEWED");
    assert.equal(commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-3",
      action: "end",
      walk_id: "walk-001",
      lease_id: String(acquired.body.lease_id),
      fencing_token: 1
    })!, { ledgerPath, nowEpochMs: 2_000 }).body.result, "ENDED");
    const endedConflict = {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: null,
        active_device_id: null,
        fencing_token: null,
        lease_expires_at_epoch_ms: null,
        server_time_epoch_ms: 3_000
      }
    };
    assert.deepEqual(commandFieldWalk(first, start, {
      ledgerPath, nowEpochMs: 3_000
    }), endedConflict);
    assert.deepEqual(commandFieldWalk(first, alreadyActive, {
      ledgerPath, nowEpochMs: 3_000
    }), endedConflict);
    assert.deepEqual(commandFieldWalk(first, renew, {
      ledgerPath, nowEpochMs: 3_000
    }), endedConflict);
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 3_000 }).body.result, "NONE");

    // S01-31: a same-walk replacement under a new request id is refused (the ended walk id
    // stays ended, no fencing token is consumed); the replacement walk uses a new walk id.
    assert.deepEqual(commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-4",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 3_500 }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_already_ended",
        walk_id: "walk-001",
        server_time_epoch_ms: 3_500
      }
    });
    const replacement = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-5",
      action: "start",
      walk_id: "walk-002"
    })!, { ledgerPath, nowEpochMs: 4_000 });
    assert.equal(replacement.body.result, "ACQUIRED");
    assert.equal(replacement.body.fencing_token, 2);
    assert.deepEqual(commandFieldWalk(first, start, {
      ledgerPath, nowEpochMs: 5_000
    }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: "walk-002",
        active_device_id: "device-1",
        fencing_token: 2,
        lease_expires_at_epoch_ms: 94_000,
        server_time_epoch_ms: 5_000
      }
    });
    assert.deepEqual(commandFieldWalk(first, renew, {
      ledgerPath, nowEpochMs: 5_000
    }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: "walk-002",
        active_device_id: "device-1",
        fencing_token: 2,
        lease_expires_at_epoch_ms: 94_000,
        server_time_epoch_ms: 5_000
      }
    });
    assert.equal(getFieldWalk(first, {
      ledgerPath, nowEpochMs: 5_000
    }).body.fencing_token, 2);
    const state = await readFieldWalkState<{ audit: Array<{ transition: string }> }>(ledgerPath);
    assert.deepEqual(state.audit.map((entry) => entry.transition), [
      "ACQUIRED", "RENEWED", "ENDED", "ACQUIRED"
    ]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("cached active leases use current expiry and time across restart until exact expiry", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-replay-restart-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const start = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!;
    const acquired = commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 1_000 });
    const alreadyActive = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-2",
      action: "start",
      walk_id: "walk-001"
    })!;
    const already = commandFieldWalk(first, alreadyActive, {
      ledgerPath, nowEpochMs: 1_500
    });
    assert.equal(already.body.result, "ALREADY_ACTIVE");
    const renew = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-3",
      action: "renew",
      walk_id: "walk-001",
      lease_id: String(acquired.body.lease_id),
      fencing_token: 1
    })!;
    const renewed = commandFieldWalk(first, renew, { ledgerPath, nowEpochMs: 90_000 });
    assert.equal(renewed.body.result, "RENEWED");
    assert.equal(renewed.body.lease_expires_at_epoch_ms, 180_000);
    assert.deepEqual(commandFieldWalk(first, start, {
      ledgerPath, nowEpochMs: 90_999
    }), {
      status: 200,
      body: {
        ...acquired.body,
        lease_expires_at_epoch_ms: 180_000,
        server_time_epoch_ms: 90_999
      }
    });

    const replayScript = [
      "const [ledgerUrl, ledgerPath] = process.argv.slice(1);",
      "const ledger = await import(ledgerUrl);",
      "const binding = { actorId: 'account-1', accountId: 'account-1', deviceId: 'device-1', familyId: 'family-1', sessionRotation: 1 };",
      "const command = ledger.parseFieldWalkCommand({ schema_version: 'walksafe.field-walk-command.v1', request_id: 'request-1', action: 'start', walk_id: 'walk-001' });",
      "process.stdout.write(JSON.stringify(ledger.commandFieldWalk(binding, command, { ledgerPath, nowEpochMs: 179999 })));"
    ].join("\n");
    const child = spawn(process.execPath, [
      "--input-type=module", "--eval", replayScript,
      new URL("../src/field-walk-ledger.js", import.meta.url).href, ledgerPath
    ], { stdio: ["pipe", "pipe", "pipe"] });
    assert.deepEqual(JSON.parse(await childOutput(child)), {
      status: 200,
      body: {
        ...acquired.body,
        lease_expires_at_epoch_ms: 180_000,
        server_time_epoch_ms: 179_999
      }
    });
    assert.deepEqual(commandFieldWalk(first, alreadyActive, {
      ledgerPath, nowEpochMs: 179_999
    }), {
      status: 200,
      body: {
        ...already.body,
        lease_expires_at_epoch_ms: 180_000,
        server_time_epoch_ms: 179_999
      }
    });
    assert.deepEqual(commandFieldWalk(first, renew, {
      ledgerPath, nowEpochMs: 179_999
    }), {
      status: 200,
      body: {
        ...renewed.body,
        server_time_epoch_ms: 179_999
      }
    });
    assert.deepEqual(commandFieldWalk(first, renew, {
      ledgerPath, nowEpochMs: 180_000
    }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: null,
        active_device_id: null,
        fencing_token: null,
        lease_expires_at_epoch_ms: null,
        server_time_epoch_ms: 180_000
      }
    });
    const state = await readFieldWalkState<{ audit: Array<{ transition: string }> }>(ledgerPath);
    assert.deepEqual(state.audit.map((entry) => entry.transition), [
      "ACQUIRED", "RENEWED", "EXPIRED"
    ]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("cached takeover is active just before expiry and rejected at exact expiry", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-takeover-replay-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 });
    const takeover = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-2",
      action: "takeover",
      walk_id: "walk-002",
      expected_active_walk_id: "walk-001",
      expected_fencing_token: 1,
      confirmation: "voice_confirmed"
    })!;
    const taken = commandFieldWalk(second, takeover, { ledgerPath, nowEpochMs: 2_000 });
    assert.equal(taken.body.result, "TAKEN_OVER");
    assert.deepEqual(commandFieldWalk(second, takeover, {
      ledgerPath, nowEpochMs: 91_999
    }), {
      status: 200,
      body: { ...taken.body, server_time_epoch_ms: 91_999 }
    });
    assert.deepEqual(commandFieldWalk(second, takeover, {
      ledgerPath, nowEpochMs: 92_000
    }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: null,
        active_device_id: null,
        fencing_token: null,
        lease_expires_at_epoch_ms: null,
        server_time_epoch_ms: 92_000
      }
    });
    const state = await readFieldWalkState<{ audit: Array<{ transition: string }> }>(ledgerPath);
    assert.deepEqual(state.audit.map((entry) => entry.transition), [
      "ACQUIRED", "TAKEN_OVER", "EXPIRED"
    ]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("renew and end reject stale lease CAS values", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-cas-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const acquired = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 }).body;
    const leaseId = String(acquired.lease_id);
    const renew = (requestId: string, fence: number) => parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: requestId,
      action: "renew",
      walk_id: "walk-001",
      lease_id: leaseId,
      fencing_token: fence
    })!;
    assert.equal(
      commandFieldWalk(first, renew("request-2", 2), {
        ledgerPath,
        nowEpochMs: 2_000
      }).body.code,
      "walk_lease_conflict"
    );
    assert.equal(
      commandFieldWalk(first, renew("request-3", 1), {
        ledgerPath,
        nowEpochMs: 2_000
      }).body.result,
      "RENEWED"
    );
    const end = (requestId: string, leaseIdValue: string) => parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: requestId,
      action: "end",
      walk_id: "walk-001",
      lease_id: leaseIdValue,
      fencing_token: 1
    })!;
    assert.equal(
      commandFieldWalk(first, end("request-4", "stale-id"), {
        ledgerPath,
        nowEpochMs: 3_000
      }).body.code,
      "walk_lease_conflict"
    );
    assert.equal(
      commandFieldWalk(first, end("request-5", leaseId), {
        ledgerPath,
        nowEpochMs: 3_000
      }).body.result,
      "ENDED"
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("conflicts skip storage while expiry and end still commit", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-conflict-write-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const acquired = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-start-001",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 }).body;
    const before = await readFile(accountShard(ledgerPath), "utf8");
    const conflictingStart = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-start-002",
      action: "start",
      walk_id: "walk-002"
    })!;
    const expectedConflict = {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: "walk-001",
        active_device_id: "device-1",
        fencing_token: 1,
        lease_expires_at_epoch_ms: 91_000,
        server_time_epoch_ms: 2_000
      }
    };

    setFieldWalkLedgerTestFault("file_fsync");
    assert.deepEqual(
      commandFieldWalk(second, conflictingStart, { ledgerPath, nowEpochMs: 2_000 }),
      expectedConflict
    );
    assert.deepEqual(
      commandFieldWalk(second, conflictingStart, { ledgerPath, nowEpochMs: 2_000 }),
      expectedConflict
    );
    assert.equal(commandFieldWalk(second, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-takeover-001",
      action: "takeover",
      walk_id: "walk-002",
      expected_active_walk_id: "walk-incorrect",
      expected_fencing_token: 1,
      confirmation: "voice_confirmed"
    })!, { ledgerPath, nowEpochMs: 2_000 }).status, 409);
    assert.equal(commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-renew-001",
      action: "renew",
      walk_id: "walk-001",
      lease_id: String(acquired.lease_id),
      fencing_token: 2
    })!, { ledgerPath, nowEpochMs: 2_000 }).status, 409);
    assert.equal(await readFile(accountShard(ledgerPath), "utf8"), before);

    assert.throws(
      () => getFieldWalk(first, { ledgerPath, nowEpochMs: 91_000 }),
      /file_fsync/
    );
    setFieldWalkLedgerTestFault(null);
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 91_000 }).body.result, "NONE");
    assert.deepEqual(
      (await readFieldWalkState<{ audit: Array<{ transition: string }> }>(ledgerPath))
        .audit.map((entry) => entry.transition),
      ["ACQUIRED", "EXPIRED"]
    );

    const replacement = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-start-003",
      action: "start",
      walk_id: "walk-003"
    })!, { ledgerPath, nowEpochMs: 92_000 }).body;
    const end = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-end-001",
      action: "end",
      walk_id: "walk-003",
      lease_id: String(replacement.lease_id),
      fencing_token: Number(replacement.fencing_token)
    })!;
    setFieldWalkLedgerTestFault("file_fsync");
    assert.throws(
      () => commandFieldWalk(first, end, { ledgerPath, nowEpochMs: 93_000 }),
      /file_fsync/
    );
    setFieldWalkLedgerTestFault(null);
    assert.equal(
      commandFieldWalk(first, end, { ledgerPath, nowEpochMs: 93_000 }).body.result,
      "ENDED"
    );
    const state = await readFieldWalkState<{
      accounts: Record<string, { active: unknown }>;
      audit: Array<{ transition: string }>;
    }>(ledgerPath);
    assert.equal(state.accounts["account-1"]?.active, null);
    assert.deepEqual(state.audit.map((entry) => entry.transition), [
      "ACQUIRED", "EXPIRED", "ACQUIRED", "ENDED"
    ]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("lease expiry uses time after entering the ledger lock", async () => {
  for (const mode of ["read", "replay", "renew"] as const) {
    const directory = await mkdtemp(path.join(tmpdir(), `field-walk-clock-${mode}-`));
    const ledgerPath = path.join(directory, "ledger.json");
    try {
      const start = parseFieldWalkCommand({
        schema_version: "walksafe.field-walk-command.v1",
        request_id: "request-1",
        action: "start",
        walk_id: "walk-001"
      })!;
      const acquired = commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 1_000 });
      const originalNow = Date.now;
      let clockReads = 0;
      Date.now = () => clockReads++ === 0 ? 90_999 : 91_001;
      let result: ReturnType<typeof commandFieldWalk>;
      try {
        if (mode === "read") {
          result = getFieldWalk(first, { ledgerPath });
        } else if (mode === "replay") {
          result = commandFieldWalk(first, start, { ledgerPath });
        } else {
          const renew = parseFieldWalkCommand({
            schema_version: "walksafe.field-walk-command.v1",
            request_id: "request-2",
            action: "renew",
            walk_id: "walk-001",
            lease_id: String(acquired.body.lease_id),
            fencing_token: 1
          })!;
          result = commandFieldWalk(first, renew, { ledgerPath });
        }
      } finally {
        Date.now = originalNow;
      }
      assert.ok(clockReads >= 2);
      if (mode === "read") {
        assert.deepEqual(result, {
          status: 200,
          body: {
            schema_version: "walksafe.field-walk-response.v1",
            result: "NONE",
            held_by_current_device: false,
            server_time_epoch_ms: 91_001
          }
        });
      } else {
        assert.deepEqual(result, {
          status: 409,
          body: {
            schema_version: "walksafe.field-walk-response.v1",
            code: "walk_lease_conflict",
            active_walk_id: null,
            active_device_id: null,
            fencing_token: null,
            lease_expires_at_epoch_ms: null,
            server_time_epoch_ms: 91_001
          }
        });
      }
      const state = await readFieldWalkState<{ audit: Array<{ transition: string }> }>(ledgerPath);
      assert.deepEqual(state.audit.map((entry) => entry.transition), ["ACQUIRED", "EXPIRED"]);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }
});

test("an external ledger lock blocks expiry and commands without changing bytes", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-lock-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const lockPath = `${accountShard(ledgerPath)}.lock`;
  try {
    commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 });
    const before = await readFile(accountShard(ledgerPath), "utf8");
    await withExclusiveFileLockAsync(lockPath, async () => {
      const busy = (error: unknown): boolean =>
        error instanceof ExclusiveFileLockBusyError;
      assert.throws(
        () => getFieldWalk(first, { ledgerPath, nowEpochMs: 91_000 }),
        busy
      );
      assert.throws(
        () => commandFieldWalk(first, parseFieldWalkCommand({
          schema_version: "walksafe.field-walk-command.v1",
          request_id: "request-2",
          action: "renew",
          walk_id: "walk-001",
          lease_id: "blocked-lease",
          fencing_token: 1
        })!, { ledgerPath, nowEpochMs: 2_000 }),
        busy
      );
      assert.equal(await readFile(accountShard(ledgerPath), "utf8"), before);
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("child processes cannot overlap the ledger critical section", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-process-race-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const lockPath = `${accountShard(ledgerPath)}.lock`;
  const ledgerModule = new URL("../src/field-walk-ledger.js", import.meta.url).href;
  const lockModule = new URL("../src/exclusive-file-lock.js", import.meta.url).href;
  let holder: ChildProcessWithoutNullStreams | null = null;
  try {
    const holderScript = `
      const [ledgerUrl, lockUrl, ledgerPath, lockPath] = process.argv.slice(1);
      const ledger = await import(ledgerUrl);
      const lock = await import(lockUrl);
      const binding = { actorId: "account-1", accountId: "account-1", deviceId: "device-1", familyId: "family-1", sessionRotation: 1 };
      const command = ledger.parseFieldWalkCommand({ schema_version: "walksafe.field-walk-command.v1", request_id: "request-1", action: "start", walk_id: "walk-001" });
      await lock.withExclusiveFileLockAsync(lockPath, async () => {
        process.stdout.write("LOCKED\\n");
        await new Promise((resolve) => process.stdin.once("data", resolve));
      });
      const result = ledger.commandFieldWalk(binding, command, { ledgerPath, nowEpochMs: 1000 });
      process.stdout.write(JSON.stringify(result) + "\\n");
    `;
    holder = spawn(
      process.execPath,
      ["--input-type=module", "--eval", holderScript, ledgerModule, lockModule, ledgerPath, lockPath],
      { stdio: ["pipe", "pipe", "pipe"] }
    );
    const holderResult = childOutput(holder);
    await waitForChildLine(holder, "LOCKED");

    const contenderScript = `
      const [ledgerUrl, lockUrl, ledgerPath] = process.argv.slice(1);
      const ledger = await import(ledgerUrl);
      const lock = await import(lockUrl);
      const binding = { actorId: "account-1", accountId: "account-1", deviceId: "device-2", familyId: "family-2", sessionRotation: 1 };
      const command = ledger.parseFieldWalkCommand({ schema_version: "walksafe.field-walk-command.v1", request_id: "request-2", action: "start", walk_id: "walk-002" });
      try {
        ledger.commandFieldWalk(binding, command, { ledgerPath, nowEpochMs: 1000 });
        process.stdout.write("UNEXPECTED_SUCCESS\\n");
      } catch (error) {
        process.stdout.write(error instanceof lock.ExclusiveFileLockBusyError ? "BUSY\\n" : "OTHER\\n");
      }
    `;
    const contender = spawn(
      process.execPath,
      ["--input-type=module", "--eval", contenderScript, ledgerModule, lockModule, ledgerPath],
      { stdio: ["pipe", "pipe", "pipe"] }
    );
    assert.match(await childOutput(contender), /^BUSY$/m);
    await assert.rejects(readFile(accountShard(ledgerPath), "utf8"), { code: "ENOENT" });

    holder.stdin.write("release\n");
    holder.stdin.end();
    assert.match(await holderResult, /"result":"ACQUIRED"/);
    holder = null;
    const persisted = await readFieldWalkState<{
      accounts: Record<string, {
        nextFencingToken: number;
        active: { walkId: string } | null;
      }>;
      audit: Array<{ transition: string }>;
    }>(ledgerPath);
    assert.equal(persisted.accounts["account-1"]?.nextFencingToken, 2);
    assert.ok(persisted.accounts["account-1"]?.active);
    assert.equal(
      persisted.audit.filter((entry) => entry.transition === "ACQUIRED").length,
      1
    );
  } finally {
    holder?.kill();
    await rm(directory, { recursive: true, force: true });
  }
});

test("request retention is bounded and evicts the deterministic oldest response", async () => {
  setFieldWalkLedgerTestLimits({
    maxRequestsPerAccount: 3,
    maxAuditEntries: 10,
    maxLedgerBytes: 1024 * 1024
  });
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-retention-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const acquired = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 }).body;
    for (let index = 2; index <= 4; index += 1) {
      commandFieldWalk(first, parseFieldWalkCommand({
        schema_version: "walksafe.field-walk-command.v1",
        request_id: `request-${index}`,
        action: "renew",
        walk_id: "walk-001",
        lease_id: String(acquired.lease_id),
        fencing_token: 1
      })!, { ledgerPath, nowEpochMs: index * 1_000 });
    }
    const state = await readFieldWalkState<{
      accounts: Record<string, { requests: Record<string, unknown> }>;
      audit: unknown[];
    }>(ledgerPath);
    assert.deepEqual(
      Object.keys(state.accounts["account-1"]!.requests).sort(),
      ["request-1", "request-3", "request-4"]
    );
    assert.equal(state.audit.length, 4);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S01-31 request retention never lets renews evict a start that could reacquire an ended walk", async () => {
  setFieldWalkLedgerTestLimits({
    maxRequestsPerAccount: 3,
    maxAuditEntries: 10,
    maxLedgerBytes: 1024 * 1024
  });
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-evicted-start-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const start = parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!;
    const acquired = commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 1_000 }).body;
    assert.equal(acquired.result, "ACQUIRED");
    const lease = (requestId: string, action: "renew" | "end") => parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: requestId,
      action,
      walk_id: "walk-001",
      lease_id: String(acquired.lease_id),
      fencing_token: 1
    })!;
    assert.equal(commandFieldWalk(first, lease("request-2", "renew"), {
      ledgerPath, nowEpochMs: 2_000
    }).body.result, "RENEWED");
    assert.equal(commandFieldWalk(first, lease("request-3", "renew"), {
      ledgerPath, nowEpochMs: 3_000
    }).body.result, "RENEWED");
    assert.equal(commandFieldWalk(first, lease("request-4", "end"), {
      ledgerPath, nowEpochMs: 4_000
    }).body.result, "ENDED");

    const endedConflict = (serverTime: number) => ({
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_lease_conflict",
        active_walk_id: null,
        active_device_id: null,
        fencing_token: null,
        lease_expires_at_epoch_ms: null,
        server_time_epoch_ms: serverTime
      }
    });
    // The retained start still proves this request already ran, so it cannot reacquire.
    assert.deepEqual(commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 5_000 }), endedConflict(5_000));
    // The evicted renew is guarded by the ended lease ID and fencing token.
    assert.deepEqual(
      commandFieldWalk(first, lease("request-2", "renew"), { ledgerPath, nowEpochMs: 5_000 }),
      endedConflict(5_000)
    );
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 5_000 }).body.result, "NONE");
    const state = await readFieldWalkState<{
      accounts: Record<string, { requests: Record<string, unknown>; nextFencingToken: number }>;
      audit: Array<{ transition: string }>;
    }>(ledgerPath);
    assert.deepEqual(
      Object.keys(state.accounts["account-1"]!.requests).sort(),
      ["request-1", "request-3", "request-4"]
    );
    assert.equal(state.accounts["account-1"]!.nextFencingToken, 2);
    assert.deepEqual(state.audit.map((entry) => entry.transition), [
      "ACQUIRED", "RENEWED", "RENEWED", "ENDED"
    ]);

    // S01-31 (R11): a v1 start carries no issue time, so it cannot be told apart from a
    // replay of the ended walk's start and is refused without consuming a fencing token.
    assert.deepEqual(commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-5",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 6_000 }).body.code, "walk_already_ended");
    // A new request ID issued after the end may still deliberately start the same walk ID
    // again (the v1 end recorded the server time 4_000 as its issue time).
    const replacement = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v2",
      request_id: "request-6",
      action: "start",
      walk_id: "walk-001",
      issued_at_epoch_ms: 6_000
    })!, { ledgerPath, nowEpochMs: 6_000 });
    assert.equal(replacement.body.result, "ACQUIRED");
    assert.equal(replacement.body.fencing_token, 2);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S01-31 an explicitly ended walk id is not restarted after its evicted start left every replay record", async () => {
  setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 4, maxAuditEntries: 8_192, maxLedgerBytes: 16 * 1024 * 1024 });
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-ended-id-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const v1 = (payload: Record<string, unknown>) => parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1", ...payload
  })!;
  try {
    const originalStart = v1({ request_id: "request-start-a", action: "start", walk_id: "walk-00000a" });
    const leaseA = commandFieldWalk(first, originalStart, { ledgerPath, nowEpochMs: 1_000 }).body;
    assert.equal(commandFieldWalk(first, v1({
      request_id: "request-end-a", action: "end", walk_id: "walk-00000a",
      lease_id: String(leaseA.lease_id), fencing_token: 1
    }), { ledgerPath, nowEpochMs: 2_000 }).body.result, "ENDED");
    // 300 duplicate starts of another walk push the original start out of the cache and
    // out of the bounded evicted-start digests (256).
    const leaseB = commandFieldWalk(first, v1({
      request_id: "request-start-b-000", action: "start", walk_id: "walk-00000b"
    }), { ledgerPath, nowEpochMs: 3_000 }).body;
    for (let index = 1; index <= 300; index += 1) {
      assert.equal(commandFieldWalk(first, v1({
        request_id: `request-start-b-${String(index).padStart(3, "0")}`, action: "start", walk_id: "walk-00000b"
      }), { ledgerPath, nowEpochMs: 3_000 + index }).body.result, "ALREADY_ACTIVE");
    }
    assert.equal(commandFieldWalk(first, v1({
      request_id: "request-end-b", action: "end", walk_id: "walk-00000b",
      lease_id: String(leaseB.lease_id), fencing_token: 2
    }), { ledgerPath, nowEpochMs: 4_000 }).body.result, "ENDED");

    assert.deepEqual(commandFieldWalk(first, originalStart, { ledgerPath, nowEpochMs: 5_000 }), {
      status: 409,
      body: {
        schema_version: "walksafe.field-walk-response.v1",
        code: "walk_already_ended",
        walk_id: "walk-00000a",
        server_time_epoch_ms: 5_000
      }
    });
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 5_000 }).body.result, "NONE");
    const state = await readFieldWalkState<{
      accounts: Record<string, { nextFencingToken: number }>;
    }>(ledgerPath);
    assert.equal(state.accounts["account-1"]!.nextFencingToken, 3);
    // A takeover cannot revive it either; a new walk id starts normally.
    assert.equal(commandFieldWalk(first, v1({
      request_id: "request-start-c", action: "start", walk_id: "walk-00000c"
    }), { ledgerPath, nowEpochMs: 6_000 }).body.fencing_token, 3);
    assert.equal(commandFieldWalk(second, v1({
      request_id: "request-takeover-a", action: "takeover", walk_id: "walk-00000a",
      expected_active_walk_id: "walk-00000c", expected_fencing_token: 3, confirmation: "voice_confirmed"
    }), { ledgerPath, nowEpochMs: 7_000 }).body.code, "walk_already_ended");
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("gw-F1 S01-31 a start or takeover issued after the explicit end runs; one issued at or before it is refused", async () => {
  setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 4, maxAuditEntries: 8_192, maxLedgerBytes: 16 * 1024 * 1024 });
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-ended-order-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const v2 = (payload: Record<string, unknown>, issuedAt: number) => parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v2", issued_at_epoch_ms: issuedAt, ...payload
  })!;
  const refused = (walkId: string, serverTime: number) => ({
    status: 409,
    body: {
      schema_version: "walksafe.field-walk-response.v1",
      code: "walk_already_ended",
      walk_id: walkId,
      server_time_epoch_ms: serverTime
    }
  });
  const T = 1_000_000;
  try {
    // Android READY epoch: the start lease arrives after the readiness changed, so the app
    // returns it with an end and retries the same walk id with a new request id.
    const originalStart = v2({ request_id: "start-req-000001", action: "start", walk_id: "epoch-walk-0001" }, T);
    const granted = commandFieldWalk(first, originalStart, { ledgerPath, nowEpochMs: T }).body;
    assert.equal(granted.result, "ACQUIRED");
    assert.equal(commandFieldWalk(first, v2({
      request_id: "end-req-0000001", action: "end", walk_id: "epoch-walk-0001",
      lease_id: String(granted.lease_id), fencing_token: granted.fencing_token
    }, T + 500), { ledgerPath, nowEpochMs: T + 500 }).body.result, "ENDED");
    // Issued at or before the end: an older command, refused; no fencing token consumed.
    assert.deepEqual(commandFieldWalk(first, v2({
      request_id: "start-req-000000", action: "start", walk_id: "epoch-walk-0001"
    }, T + 400), { ledgerPath, nowEpochMs: T + 600 }), refused("epoch-walk-0001", T + 600));
    assert.deepEqual(commandFieldWalk(first, v2({
      request_id: "start-req-00000x", action: "start", walk_id: "epoch-walk-0001"
    }, T + 500), { ledgerPath, nowEpochMs: T + 600 }), refused("epoch-walk-0001", T + 600));
    // Issued after the end: the retry runs.
    const retry = commandFieldWalk(first, v2({
      request_id: "start-req-000002", action: "start", walk_id: "epoch-walk-0001"
    }, T + 5_000), { ledgerPath, nowEpochMs: T + 5_000 }).body;
    assert.equal(retry.result, "ACQUIRED");
    assert.equal(retry.walk_id, "epoch-walk-0001");
    assert.equal(retry.fencing_token, 2);
    assert.equal(commandFieldWalk(first, v2({
      request_id: "end-req-0000002", action: "end", walk_id: "epoch-walk-0001",
      lease_id: String(retry.lease_id), fencing_token: 2
    }, T + 6_000), { ledgerPath, nowEpochMs: T + 6_000 }).body.result, "ENDED");
    let state = await readFieldWalkState<{
      accounts: Record<string, {
        nextFencingToken: number;
        requests: Record<string, unknown>;
        idempotency?: { evictedIssuedAtByDevice: Record<string, number> };
        endedWalks?: Array<{ walkId: string; endIssuedAtEpochMs?: number }>;
      }>;
    }>(ledgerPath);
    assert.equal(
      state.accounts["account-1"]!.endedWalks?.find((entry) => entry.walkId === "epoch-walk-0001")
        ?.endIssuedAtEpochMs,
      T + 6_000
    );

    // Every replay record of the original start is gone: 70 other devices push it out of
    // the request cache and push device-1 out of the bounded (64) eviction watermarks.
    for (let index = 0; index < 70; index += 1) {
      const suffix = String(index).padStart(3, "0");
      const device: GatewayFieldLongSessionBinding = {
        ...first, deviceId: `device-x${suffix}`, familyId: `family-x${suffix}`
      };
      const issued = T + 10_000 + index * 10;
      const lease = commandFieldWalk(device, v2({
        request_id: `start-x${suffix}-req`, action: "start", walk_id: `walk-x${suffix}-01`
      }, issued), { ledgerPath, nowEpochMs: issued }).body;
      assert.equal(lease.result, "ACQUIRED");
      assert.equal(commandFieldWalk(device, v2({
        request_id: `end-x${suffix}-req0`, action: "end", walk_id: `walk-x${suffix}-01`,
        lease_id: String(lease.lease_id), fencing_token: lease.fencing_token
      }, issued + 1), { ledgerPath, nowEpochMs: issued + 1 }).body.result, "ENDED");
    }
    state = await readFieldWalkState(ledgerPath);
    const account = state.accounts["account-1"]!;
    assert.equal(Object.hasOwn(account.requests, "start-req-000001"), false);
    assert.equal(Object.hasOwn(account.idempotency!.evictedIssuedAtByDevice, first.deviceId), false);
    assert.equal(account.nextFencingToken, 73);
    // The replayed original start (issued before both ends) does not revive the walk.
    assert.deepEqual(
      commandFieldWalk(first, originalStart, { ledgerPath, nowEpochMs: T + 20_000 }),
      refused("epoch-walk-0001", T + 20_000)
    );
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: T + 20_000 }).body.result, "NONE");
    state = await readFieldWalkState(ledgerPath);
    assert.equal(state.accounts["account-1"]!.nextFencingToken, 73);

    // Takeover likewise: device-2 ended its own walk; a takeover into that walk id issued at
    // the end is refused, one issued later takes over.
    const own = commandFieldWalk(second, v2({
      request_id: "start-dev2-req01", action: "start", walk_id: "walk-dev2-0001"
    }, T + 30_000), { ledgerPath, nowEpochMs: T + 30_000 }).body;
    assert.equal(own.result, "ACQUIRED");
    assert.equal(commandFieldWalk(second, v2({
      request_id: "end-dev2-req001", action: "end", walk_id: "walk-dev2-0001",
      lease_id: String(own.lease_id), fencing_token: own.fencing_token
    }, T + 30_100), { ledgerPath, nowEpochMs: T + 30_100 }).body.result, "ENDED");
    const holder = commandFieldWalk(first, v2({
      request_id: "start-req-000003", action: "start", walk_id: "epoch-walk-0003"
    }, T + 30_200), { ledgerPath, nowEpochMs: T + 30_200 }).body;
    assert.equal(holder.result, "ACQUIRED");
    const takeover = (requestId: string, issuedAt: number) => v2({
      request_id: requestId, action: "takeover", walk_id: "walk-dev2-0001",
      expected_active_walk_id: "epoch-walk-0003", expected_fencing_token: holder.fencing_token,
      confirmation: "voice_confirmed"
    }, issuedAt);
    assert.deepEqual(
      commandFieldWalk(second, takeover("takeover-dev2-01", T + 30_100), { ledgerPath, nowEpochMs: T + 30_300 }),
      refused("walk-dev2-0001", T + 30_300)
    );
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: T + 30_300 }).body.active_walk_id, "epoch-walk-0003");
    const taken = commandFieldWalk(second, takeover("takeover-dev2-02", T + 30_400), {
      ledgerPath, nowEpochMs: T + 30_400
    }).body;
    assert.equal(taken.result, "TAKEN_OVER");
    assert.equal(taken.walk_id, "walk-dev2-0001");
    assert.equal(taken.fencing_token, Number(holder.fencing_token) + 1);

    // A Gateway-originated end (logout, remote revoke) records the server time.
    assert.equal(endFieldWalkForRevokedSession({
      accountId: second.accountId, actorId: second.actorId, deviceId: second.deviceId
    }, { ledgerPath, nowEpochMs: T + 40_000 }), true);
    assert.deepEqual(commandFieldWalk(second, v2({
      request_id: "start-dev2-req02", action: "start", walk_id: "walk-dev2-0001"
    }, T + 40_000), { ledgerPath, nowEpochMs: T + 40_100 }), refused("walk-dev2-0001", T + 40_100));
    assert.equal(commandFieldWalk(second, v2({
      request_id: "start-dev2-req03", action: "start", walk_id: "walk-dev2-0001"
    }, T + 40_001), { ledgerPath, nowEpochMs: T + 40_100 }).body.result, "ACQUIRED");
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("gw-F1 S01-31 an ended-walk record without the end issue time keeps refusing that walk id", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-ended-legacy-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const v2 = (payload: Record<string, unknown>, issuedAt: number) => parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v2", issued_at_epoch_ms: issuedAt, ...payload
  })!;
  try {
    const lease = commandFieldWalk(first, v2({
      request_id: "start-legacy-01", action: "start", walk_id: "walk-legacy-01"
    }, 1_000), { ledgerPath, nowEpochMs: 1_000 }).body;
    assert.equal(commandFieldWalk(first, v2({
      request_id: "end-legacy-0001", action: "end", walk_id: "walk-legacy-01",
      lease_id: String(lease.lease_id), fencing_token: lease.fencing_token
    }, 2_000), { ledgerPath, nowEpochMs: 2_000 }).body.result, "ENDED");
    // A record written before endIssuedAtEpochMs existed: same bytes minus that field.
    const shard = accountShard(ledgerPath);
    const context = { kind: "field-walk-ledger", recordId: path.basename(shard) } as const;
    const state = decryptTestStateFile<{
      accounts: Record<string, { endedWalks: Array<Record<string, unknown>> }>;
    }>(context, await readFile(shard, "utf8"), FIELD_WALK_LEDGER_MAX_PLAINTEXT_BYTES);
    const ended = state.accounts["account-1"]!.endedWalks[0]!;
    assert.equal(ended.endIssuedAtEpochMs, 2_000);
    delete ended.endIssuedAtEpochMs;
    await writeFile(shard, encryptTestStateFile(context, state, FIELD_WALK_LEDGER_MAX_PLAINTEXT_BYTES));
    // Conservative: the order cannot be proven, so even a later-issued start is refused.
    assert.equal(commandFieldWalk(first, v2({
      request_id: "start-legacy-02", action: "start", walk_id: "walk-legacy-01"
    }, 5_000), { ledgerPath, nowEpochMs: 5_000 }).body.code, "walk_already_ended");
    // The legacy record is still readable and a new walk id starts normally.
    assert.equal(commandFieldWalk(first, v2({
      request_id: "start-legacy-03", action: "start", walk_id: "walk-legacy-02"
    }, 6_000), { ledgerPath, nowEpochMs: 6_000 }).body.result, "ACQUIRED");
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S01-31 starts hold at most half the request cache so a long-used account keeps end replay", async () => {
  setFieldWalkLedgerTestLimits({
    maxRequestsPerAccount: 3,
    maxAuditEntries: 100,
    maxLedgerBytes: 1024 * 1024
  });
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-start-share-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    let now = 1_000;
    const start = (index: number) => parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: `start-request-${index}`,
      action: "start",
      walk_id: `walk-00${index}`
    })!;
    const end = (index: number, leaseId: unknown, fencingToken: unknown) => parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: `end-request-${index}`,
      action: "end",
      walk_id: `walk-00${index}`,
      lease_id: String(leaseId),
      fencing_token: Number(fencingToken)
    })!;
    let lastEnd: ReturnType<typeof end> | undefined;
    let lastEndResponse: unknown;
    for (let index = 1; index <= 3; index += 1) {
      const acquired = commandFieldWalk(first, start(index), { ledgerPath, nowEpochMs: now += 1_000 }).body;
      assert.equal(acquired.result, "ACQUIRED");
      lastEnd = end(index, acquired.lease_id, acquired.fencing_token);
      const ended = commandFieldWalk(first, lastEnd, { ledgerPath, nowEpochMs: now += 1_000 });
      assert.equal(ended.body.result, "ENDED");
      lastEndResponse = ended;
    }
    // Another start on the account must not push out the last end response.
    assert.equal(
      commandFieldWalk(first, start(4), { ledgerPath, nowEpochMs: now += 1_000 }).body.result,
      "ACQUIRED"
    );
    assert.deepEqual(
      commandFieldWalk(first, lastEnd!, { ledgerPath, nowEpochMs: now += 1_000 }),
      lastEndResponse
    );
    const state = await readFieldWalkState<{
      accounts: Record<string, { requests: Record<string, { response: { result?: string } }> }>;
    }>(ledgerPath);
    const retained = Object.values(state.accounts["account-1"]!.requests)
      .filter((entry) => entry.response.result === "ACQUIRED").length;
    assert.ok(retained * 2 <= 3 + 1);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("audit and serialized-size ceilings fail closed", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-capacity-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const smallLedgerPath = path.join(directory, "small.json");
  try {
    // S19-13: a full audit tail is normally sealed into an archive segment; the ceilings
    // below remain the bound when that segment cannot be written.
    setFieldWalkLedgerTestFault("audit_archive");
    setFieldWalkLedgerTestLimits({
      maxRequestsPerAccount: 10,
      maxAuditEntries: 2,
      maxLedgerBytes: 1024 * 1024
    });
    const acquired = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 }).body;
    const renew = (requestId: string) => parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: requestId,
      action: "renew",
      walk_id: "walk-001",
      lease_id: String(acquired.lease_id),
      fencing_token: 1
    })!;
    commandFieldWalk(first, renew("request-2"), { ledgerPath, nowEpochMs: 2_000 });
    const before = await readFile(accountShard(ledgerPath), "utf8");
    assert.throws(
      () => commandFieldWalk(first, renew("request-3"), { ledgerPath, nowEpochMs: 3_000 }),
      FieldWalkLedgerCapacityError
    );
    assert.equal(await readFile(accountShard(ledgerPath), "utf8"), before);

    const ended = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-end-at-capacity",
      action: "end",
      walk_id: "walk-001",
      lease_id: String(acquired.lease_id),
      fencing_token: 1
    })!, { ledgerPath, nowEpochMs: 4_000 });
    assert.equal(ended.body.result, "ENDED");
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 4_001 }).body.result, "NONE");
    assert.deepEqual((await readFieldWalkState<{ audit: Array<{ transition: string }> }>(ledgerPath))
      .audit.map((entry) => entry.transition), ["ACQUIRED", "RENEWED", "ENDED"]);

    setFieldWalkLedgerTestLimits({
      maxRequestsPerAccount: 10,
      maxAuditEntries: 10,
      maxLedgerBytes: 256
    });
    assert.throws(
      () => commandFieldWalk(first, parseFieldWalkCommand({
        schema_version: "walksafe.field-walk-command.v1",
        request_id: "request-1",
        action: "start",
        walk_id: "walk-001"
      })!, { ledgerPath: smallLedgerPath, nowEpochMs: 1_000 }),
      FieldWalkLedgerCapacityError
    );
    await assert.rejects(readFile(accountShard(smallLedgerPath), "utf8"), { code: "ENOENT" });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("byte-saturated ledger can end a current lease", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-byte-reserve-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    const acquired = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1", request_id: "request-start-001",
      action: "start", walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 }).body;
    const state = await readFieldWalkState<Record<string, unknown>>(ledgerPath);
    setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 10, maxAuditEntries: 10,
      maxLedgerBytes: Buffer.byteLength(JSON.stringify(state)) + 1 });
    const ended = commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1", request_id: "request-end-001",
      action: "end", walk_id: "walk-001", lease_id: String(acquired.lease_id), fencing_token: 1
    })!, { ledgerPath, nowEpochMs: 2_000 });
    assert.equal(ended.body.result, "ENDED");
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 2_001 }).body.result, "NONE");
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("full audit ledger can expire a lease without admitting new work", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-terminal-reserve-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    // S19-13: with the archive segment unwritable, the full tail keeps its terminal reserve.
    setFieldWalkLedgerTestFault("audit_archive");
    setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 10, maxAuditEntries: 1, maxLedgerBytes: 1024 * 1024 });
    commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1", request_id: "request-start-001",
      action: "start", walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 });
    assert.equal(getFieldWalk(first, { ledgerPath, nowEpochMs: 91_001 }).body.result, "NONE");
    const state = await readFieldWalkState<{ audit: Array<{ transition: string }> }>(ledgerPath);
    assert.deepEqual(state.audit.map((entry) => entry.transition), ["ACQUIRED", "EXPIRED"]);
    assert.throws(() => commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1", request_id: "request-start-002",
      action: "start", walk_id: "walk-002"
    })!, { ledgerPath, nowEpochMs: 91_002 }), FieldWalkLedgerCapacityError);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("pre-rename failures are retryable and directory fsync is outcome unknown", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-durability-"));
  const start = parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: "request-1",
    action: "start",
    walk_id: "walk-001"
  })!;
  try {
    for (const stage of ["file_fsync", "rename"] as const) {
      const ledgerPath = path.join(directory, `${stage}.json`);
      setFieldWalkLedgerTestFault(stage);
      assert.throws(
        () => commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 1_000 }),
        new RegExp(stage)
      );
      await assert.rejects(readFile(accountShard(ledgerPath), "utf8"), { code: "ENOENT" });
      setFieldWalkLedgerTestFault(null);
      assert.equal(
        commandFieldWalk(first, start, { ledgerPath, nowEpochMs: 1_000 }).body.result,
        "ACQUIRED"
      );
    }

    const uncertainPath = path.join(directory, "directory-fsync.json");
    setFieldWalkLedgerTestFault("directory_fsync");
    assert.throws(
      () => commandFieldWalk(first, start, {
        ledgerPath: uncertainPath,
        nowEpochMs: 1_000
      }),
      FieldWalkLedgerStorageOutcomeUnknownError
    );
    setFieldWalkLedgerTestFault(null);
    assert.equal(
      commandFieldWalk(first, start, {
        ledgerPath: uncertainPath,
        nowEpochMs: 2_000
      }).body.result,
      "ACQUIRED"
    );
    const state = await readFieldWalkState<{
      audit: Array<{ transition: string }>;
    }>(uncertainPath);
    assert.deepEqual(state.audit.map((entry) => entry.transition), ["ACQUIRED"]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("malformed nested ledger state fails closed", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-invalid-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    await writeFile(ledgerPath, encryptTestStateFile(
      { kind: "field-walk-ledger", recordId: path.basename(ledgerPath) },
      {
      schemaVersion: "walksafe.field-walk-ledger.v1",
      accounts: {
        "account-1": {
          nextFencingToken: 2,
          active: {
            walkId: "walk-001",
            leaseId: "lease-001",
            fencingToken: "invalid",
            actorId: "account-1",
            deviceId: "device-1",
            familyId: "family-1",
            acquiredAtEpochMs: 1_000,
            leaseExpiresAtEpochMs: 91_000
          },
          requests: {}
        }
      },
      audit: []
      },
      FIELD_WALK_LEDGER_MAX_PLAINTEXT_BYTES
    ), { mode: 0o600 });
    assert.throws(
      () => getFieldWalk(first, { ledgerPath, nowEpochMs: 2_000 }),
      /invalid field walk ledger/
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("POST revalidates the exact long-session binding after body read", async () => {
  let current: GatewayFieldLongSessionBinding | null = first;
  const body = JSON.stringify({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: "request-1",
    action: "start",
    walk_id: "walk-001"
  });
  const request = new Request("http://127.0.0.1:8081/api/field-walk", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: new ReadableStream<Uint8Array>({
      pull(controller) {
        current = null;
        controller.enqueue(new TextEncoder().encode(body));
        controller.close();
      }
    }),
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const response = await handleGatewayRequest(request, {
    fieldLongSessionBindingResolver: () => current
  });
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), {
    schema_version: "walksafe.field-walk-response.v1",
    code: "gateway_unauthorized"
  });
});

test("prototype-named account and request ids remain isolated", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-prototype-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const prototypeAccount: GatewayFieldLongSessionBinding = {
    ...first,
    actorId: "__proto__",
    accountId: "__proto__"
  };
  const constructorAccount: GatewayFieldLongSessionBinding = {
    ...first,
    actorId: "constructor",
    accountId: "constructor"
  };
  const start = (requestId: string, walkId: string) => parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: requestId,
    action: "start",
    walk_id: walkId
  })!;
  try {
    const prototypeStart = start("constructor", "walk-proto");
    const acquired = commandFieldWalk(prototypeAccount, prototypeStart, {
      ledgerPath,
      nowEpochMs: 1_000
    });
    assert.equal(acquired.body.result, "ACQUIRED");
    assert.deepEqual(
      commandFieldWalk(prototypeAccount, prototypeStart, {
        ledgerPath,
        nowEpochMs: 2_000
      }),
      {
        status: 200,
        body: { ...acquired.body, server_time_epoch_ms: 2_000 }
      }
    );
    assert.equal(
      commandFieldWalk(
        prototypeAccount,
        start("constructor", "walk-other"),
        { ledgerPath, nowEpochMs: 2_000 }
      ).body.code,
      "request_id_conflict"
    );

    const independent = commandFieldWalk(
      constructorAccount,
      start("constructor", "walk-ctor"),
      { ledgerPath, nowEpochMs: 2_000 }
    );
    assert.equal(independent.body.result, "ACQUIRED");
    assert.equal(independent.body.fencing_token, 1);
    assert.equal(
      commandFieldWalk(
        constructorAccount,
        start("toString", "walk-ctor"),
        { ledgerPath, nowEpochMs: 3_000 }
      ).body.result,
      "ALREADY_ACTIVE"
    );

    const persisted = await readFieldWalkState<{
      accounts: Record<string, unknown>;
    }>(ledgerPath);
    assert.equal(
      Object.prototype.hasOwnProperty.call(persisted.accounts, "__proto__"),
      true
    );
    assert.equal(
      Object.prototype.hasOwnProperty.call(persisted.accounts, "constructor"),
      true
    );
    assert.equal(Object.prototype.hasOwnProperty.call(Object.prototype, "walkId"), false);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
