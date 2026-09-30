import assert from "node:assert/strict";
import { mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, afterEach, before } from "node:test";

import type { GatewayFieldLongSessionBinding } from "../src/auth.js";
import {
  commandFieldWalk,
  fieldWalkLedgerShardPathForTests,
  getFieldWalk,
  parseFieldWalkCommand,
  readFieldWalkAuditHistory,
  setFieldWalkLedgerTestFault,
  setFieldWalkLedgerTestLimits
} from "../src/field-walk-ledger.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";

const MAX_PLAINTEXT = 16 * 1024 * 1024 + 8_192 * 4_096;
let keyringDirectory = "";

before(async () => {
  keyringDirectory = await mkdtemp(path.join(tmpdir(), "field-walk-audit-archive-keys-"));
  await configureTestStateEncryption(path.join(keyringDirectory, "state-keyring.json"));
});
after(async () => {
  await rm(keyringDirectory, { recursive: true, force: true });
});
afterEach(() => {
  setFieldWalkLedgerTestFault(null);
  setFieldWalkLedgerTestLimits(null);
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

type ShardState = {
  audit: Array<{ transition: string }>;
  auditArchive?: { segmentCount: number; archivedEntries: number; lastSegmentSha256: string };
};

async function shardState(ledgerPath: string): Promise<ShardState> {
  const shard = fieldWalkLedgerShardPathForTests(ledgerPath, "account-1");
  return decryptTestStateFile<ShardState>(
    { kind: "field-walk-ledger", recordId: path.basename(shard) },
    await readFile(shard, "utf8"),
    MAX_PLAINTEXT
  );
}

async function segmentFiles(ledgerPath: string): Promise<string[]> {
  const shard = path.basename(fieldWalkLedgerShardPathForTests(ledgerPath, "account-1"));
  return (await readdir(path.dirname(ledgerPath)))
    .filter((name) => name.startsWith(`${shard}.audit-`))
    .sort();
}

/** Drives one account through v1 commands with a per-test request counter. */
function walker(ledgerPath: string) {
  let sequence = 0;
  const requestId = (): string => `request-${String(++sequence).padStart(6, "0")}`;
  const leases = new Map<string, { walkId: string; leaseId: string; fencingToken: number }>();
  const command = (
    binding: GatewayFieldLongSessionBinding,
    payload: Record<string, unknown>,
    nowEpochMs: number
  ) => commandFieldWalk(binding, parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: requestId(),
    ...payload
  })!, { ledgerPath, nowEpochMs });
  const remember = (binding: GatewayFieldLongSessionBinding, body: Record<string, unknown>) => {
    leases.set(binding.deviceId, {
      walkId: String(body.walk_id),
      leaseId: String(body.lease_id),
      fencingToken: Number(body.fencing_token)
    });
  };
  return {
    start(binding: GatewayFieldLongSessionBinding, walkId: string, now: number) {
      const result = command(binding, { action: "start", walk_id: walkId }, now);
      assert.equal(result.status, 200, JSON.stringify(result.body));
      assert.equal(result.body.result, "ACQUIRED");
      remember(binding, result.body);
      return result.body;
    },
    renew(binding: GatewayFieldLongSessionBinding, now: number) {
      const lease = leases.get(binding.deviceId)!;
      const result = command(binding, {
        action: "renew", walk_id: lease.walkId, lease_id: lease.leaseId, fencing_token: lease.fencingToken
      }, now);
      assert.equal(result.status, 200, JSON.stringify(result.body));
      assert.equal(result.body.result, "RENEWED");
      return result.body;
    },
    end(binding: GatewayFieldLongSessionBinding, now: number) {
      const lease = leases.get(binding.deviceId)!;
      const result = command(binding, {
        action: "end", walk_id: lease.walkId, lease_id: lease.leaseId, fencing_token: lease.fencingToken
      }, now);
      assert.equal(result.status, 200, JSON.stringify(result.body));
      assert.equal(result.body.result, "ENDED");
      return result.body;
    },
    takeover(binding: GatewayFieldLongSessionBinding, from: GatewayFieldLongSessionBinding, walkId: string, now: number) {
      const previous = leases.get(from.deviceId)!;
      const result = command(binding, {
        action: "takeover",
        walk_id: walkId,
        expected_active_walk_id: previous.walkId,
        expected_fencing_token: previous.fencingToken,
        confirmation: "voice_confirmed"
      }, now);
      assert.equal(result.status, 200, JSON.stringify(result.body));
      assert.equal(result.body.result, "TAKEN_OVER");
      remember(binding, result.body);
      return result.body;
    },
    expire(now: number) {
      const result = getFieldWalk(first, { ledgerPath, nowEpochMs: now });
      assert.equal(result.status, 200);
      assert.equal(result.body.result, "NONE");
    }
  };
}

test("S01-28 start, renew, end, expire and takeover succeed at and right after the audit ceiling", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-audit-ceiling-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    // Ceiling 2: every second transition reaches the ceiling and seals the tail, so each
    // operation below runs once at the ceiling (tail 1 -> 2) and once right after a seal (0 -> 1).
    setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 64, maxAuditEntries: 2, maxLedgerBytes: 1024 * 1024 });
    const walk = walker(ledgerPath);
    const positions: Array<[string, number]> = [];
    const at = async (operation: string, run: () => unknown): Promise<void> => {
      positions.push([operation, (await shardState(ledgerPath).catch(() => ({ audit: [] }))).audit.length]);
      run();
    };
    await at("start", () => walk.start(first, "walk-000001", 1_000));
    await at("renew", () => walk.renew(first, 2_000));
    await at("renew", () => walk.renew(first, 3_000));
    await at("end", () => walk.end(first, 4_000));
    await at("start", () => walk.start(first, "walk-000002", 5_000));
    await at("takeover", () => walk.takeover(second, first, "walk-000003", 6_000));
    await at("end", () => walk.end(second, 7_000));
    await at("start", () => walk.start(first, "walk-000004", 8_000));
    await at("expire", () => walk.expire(98_001));
    await at("start", () => walk.start(first, "walk-000005", 99_000));
    await at("takeover", () => walk.takeover(second, first, "walk-000006", 100_000));
    await at("expire", () => walk.expire(190_001));

    for (const operation of ["start", "renew", "end", "expire", "takeover"]) {
      const seen = new Set(positions.filter(([name]) => name === operation).map(([, tail]) => tail));
      assert.deepEqual([...seen].sort(), [0, 1], `${operation} at the ceiling and right after it`);
    }
    assert.deepEqual(readFieldWalkAuditHistory({ ledgerPath }).map((entry) => entry.transition), [
      "ACQUIRED", "RENEWED", "RENEWED", "ENDED", "ACQUIRED", "TAKEN_OVER",
      "ENDED", "ACQUIRED", "EXPIRED", "ACQUIRED", "TAKEN_OVER", "EXPIRED"
    ]);
    // Leases and fencing stay in the account state: tokens keep increasing across seals.
    const fences = readFieldWalkAuditHistory({ ledgerPath })
      .filter((entry) => entry.transition === "ACQUIRED" || entry.transition === "TAKEN_OVER")
      .map((entry) => entry.fencingToken);
    assert.deepEqual(fences, [1, 2, 3, 4, 5, 6]);
    const state = await shardState(ledgerPath);
    assert.equal(state.audit.length, 0);
    assert.equal(state.auditArchive?.segmentCount, 6);
    assert.equal(state.auditArchive?.archivedEntries, 12);
    assert.equal((await segmentFiles(ledgerPath)).length, 6);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S19-13 audit past the former ceiling keeps renew, start and takeover working with the full history", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-audit-rotation-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    // The former behavior refused every non-terminal transition once 4 entries existed.
    setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 16, maxAuditEntries: 4, maxLedgerBytes: 1024 * 1024 });
    const walk = walker(ledgerPath);
    walk.start(first, "walk-000001", 1_000);
    for (let index = 0; index < 20; index += 1) walk.renew(first, 2_000 + index * 1_000);
    walk.takeover(second, first, "walk-000002", 30_000);
    walk.renew(second, 31_000);
    walk.end(second, 32_000);
    walk.start(first, "walk-000003", 33_000);
    const history = readFieldWalkAuditHistory({ ledgerPath }).map((entry) => entry.transition);
    assert.equal(history.length, 25);
    assert.deepEqual(history.slice(0, 2), ["ACQUIRED", "RENEWED"]);
    assert.deepEqual(history.slice(-4), ["TAKEN_OVER", "RENEWED", "ENDED", "ACQUIRED"]);
    const state = await shardState(ledgerPath);
    assert.ok(state.audit.length < 4);
    assert.equal(
      (state.auditArchive?.archivedEntries ?? 0) + state.audit.length,
      25
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S19-14 shard state size stays bounded while the audit history grows", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-audit-size-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    setFieldWalkLedgerTestLimits({
      maxRequestsPerAccount: 4,
      maxAuditEntries: 8_192,
      maxLedgerBytes: 16 * 1024 * 1024,
      auditSegmentEntries: 8
    });
    const walk = walker(ledgerPath);
    const shard = fieldWalkLedgerShardPathForTests(ledgerPath, "account-1");
    walk.start(first, "walk-000001", 1_000_000);
    const sizes: number[] = [];
    for (let index = 0; index < 240; index += 1) {
      walk.renew(first, 1_001_000 + index * 1_000);
      sizes.push((await stat(shard)).size);
      assert.ok((await shardState(ledgerPath)).audit.length < 8);
    }
    // After the first seals, the shard holds only account state, a bounded request cache,
    // a tail below 8 entries, and the archive pointer: its size no longer follows the history.
    // (+64 bytes covers the pointer's growing digit counts; 200 more entries would add ~60 KiB.)
    const reference = Math.max(...sizes.slice(16, 40));
    const later = Math.max(...sizes.slice(40));
    assert.ok(later <= reference + 64, `${later} > ${reference} + 64`);
    assert.equal(readFieldWalkAuditHistory({ ledgerPath }).length, 241);
    assert.equal((await segmentFiles(ledgerPath)).length, 30);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S19-13 a segment is durable before the shard names it and an unnamed orphan is replaced", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-audit-crash-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 16, maxAuditEntries: 2, maxLedgerBytes: 1024 * 1024 });
    const walk = walker(ledgerPath);
    const shard = fieldWalkLedgerShardPathForTests(ledgerPath, "account-1");
    walk.start(first, "walk-000001", 1_000);
    const before = await readFile(shard);
    // The shard write fails before its rename, after the segment was sealed.
    setFieldWalkLedgerTestFault("rename");
    assert.throws(() => walk.renew(first, 2_000), /injected field walk ledger rename failure/);
    setFieldWalkLedgerTestFault(null);
    assert.deepEqual(await readFile(shard), before);
    assert.deepEqual(await segmentFiles(ledgerPath), [`${path.basename(shard)}.audit-00000001`]);
    assert.equal((await shardState(ledgerPath)).auditArchive, undefined);
    // The orphan is not part of the history; the shard still owns its tail.
    assert.deepEqual(readFieldWalkAuditHistory({ ledgerPath }).map((entry) => entry.transition), ["ACQUIRED"]);

    walk.renew(first, 3_000);
    assert.deepEqual(readFieldWalkAuditHistory({ ledgerPath }).map((entry) => entry.transition), [
      "ACQUIRED", "RENEWED"
    ]);
    assert.equal((await shardState(ledgerPath)).auditArchive?.segmentCount, 1);
    walk.end(first, 4_000);
    walk.start(first, "walk-000002", 5_000);
    assert.deepEqual(readFieldWalkAuditHistory({ ledgerPath }).map((entry) => entry.transition), [
      "ACQUIRED", "RENEWED", "ENDED", "ACQUIRED"
    ]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("S19-13 an altered, missing, or reordered archive segment fails the history closed", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "field-walk-audit-integrity-"));
  const ledgerPath = path.join(directory, "ledger.json");
  try {
    setFieldWalkLedgerTestLimits({ maxRequestsPerAccount: 16, maxAuditEntries: 2, maxLedgerBytes: 1024 * 1024 });
    const walk = walker(ledgerPath);
    walk.start(first, "walk-000001", 1_000);
    for (let index = 0; index < 5; index += 1) walk.renew(first, 2_000 + index * 1_000);
    const [segmentOne, segmentTwo] = (await segmentFiles(ledgerPath)).map((name) => path.join(directory, name));
    assert.ok(segmentOne && segmentTwo);
    const context = (file: string) => ({ kind: "field-walk-ledger" as const, recordId: path.basename(file) });
    const original = await readFile(segmentOne, "utf8");
    const value = decryptTestStateFile<{ entries: Array<{ occurredAtEpochMs: number }> }>(
      context(segmentOne), original, MAX_PLAINTEXT
    );
    value.entries[0]!.occurredAtEpochMs += 1;
    await writeFile(segmentOne, encryptTestStateFile(context(segmentOne), value, MAX_PLAINTEXT), { mode: 0o600 });
    assert.throws(() => readFieldWalkAuditHistory({ ledgerPath }), /integrity/);
    await writeFile(segmentOne, original, { mode: 0o600 });
    assert.equal(readFieldWalkAuditHistory({ ledgerPath }).length, 6);

    // A segment re-encrypted under another sequence number is refused.
    const other = decryptTestStateFile(context(segmentTwo), await readFile(segmentTwo, "utf8"), MAX_PLAINTEXT);
    await writeFile(segmentOne, encryptTestStateFile(context(segmentOne), other, MAX_PLAINTEXT), { mode: 0o600 });
    assert.throws(() => readFieldWalkAuditHistory({ ledgerPath }), /integrity/);
    await rm(segmentOne);
    assert.throws(() => readFieldWalkAuditHistory({ ledgerPath }), { code: "ENOENT" });
    // Walking itself never reads the archive.
    walk.renew(first, 20_000);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
