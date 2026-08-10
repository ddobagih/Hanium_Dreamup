import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, afterEach, before } from "node:test";

import {
  commandFieldWalk,
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

async function readFieldWalkState<T>(filePath: string): Promise<T> {
  return decryptTestStateFile<T>(
    { kind: "field-walk-ledger", recordId: path.basename(filePath) },
    await readFile(filePath, "utf8"),
    FIELD_WALK_LEDGER_MAX_PLAINTEXT_BYTES
  );
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
      acquired
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

    const state = await readFieldWalkState<{
      audit: Array<{ transition: string }>;
    }>(ledgerPath);
    assert.deepEqual(state.audit.map((entry) => entry.transition), ["ACQUIRED", "TAKEN_OVER"]);
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
    assert.equal(replay.body.result, "ACQUIRED");
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

test("an external ledger lock blocks expiry and commands without changing bytes", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-lock-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const lockPath = `${ledgerPath}.lock`;
  try {
    commandFieldWalk(first, parseFieldWalkCommand({
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "request-1",
      action: "start",
      walk_id: "walk-001"
    })!, { ledgerPath, nowEpochMs: 1_000 });
    const before = await readFile(ledgerPath, "utf8");
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
      assert.equal(await readFile(ledgerPath, "utf8"), before);
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("child processes cannot overlap the ledger critical section", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-process-race-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const lockPath = `${ledgerPath}.lock`;
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
    await assert.rejects(readFile(ledgerPath, "utf8"), { code: "ENOENT" });

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
      ["request-2", "request-3", "request-4"]
    );
    assert.equal(state.audit.length, 4);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("audit and serialized-size ceilings fail closed", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-capacity-"));
  const ledgerPath = path.join(directory, "ledger.json");
  const smallLedgerPath = path.join(directory, "small.json");
  try {
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
    const before = await readFile(ledgerPath, "utf8");
    assert.throws(
      () => commandFieldWalk(first, renew("request-3"), { ledgerPath, nowEpochMs: 3_000 }),
      FieldWalkLedgerCapacityError
    );
    assert.equal(await readFile(ledgerPath, "utf8"), before);

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
    await assert.rejects(readFile(smallLedgerPath, "utf8"), { code: "ENOENT" });
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
      await assert.rejects(readFile(ledgerPath, "utf8"), { code: "ENOENT" });
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
      acquired
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
