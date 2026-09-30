import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash, createHmac } from "node:crypto";
import fs from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { syncBuiltinESMExports } from "node:module";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, test } from "node:test";
import { fileURLToPath } from "node:url";

import { AccountDeletionOutboxWorker } from "../src/account-deletion-outbox-worker.js";
import { DELETION_ACCESS_PRE_DIGEST_HEADER } from "../src/backend.js";
import { createDeletionCapabilityHmac } from "../src/encrypted-json-store.js";
import {
  acceptOrReplayAccountDeletionV2,
  authorizedAccountDeletionStatusV2,
  ACCOUNT_DELETION_INVENTORY_V2,
  DELETION_ACCESS_SECRET_HEADER,
  drainAccountDeletionOutboxV2,
  forwardAccountDeletionRequestV2,
  privacyDeletionV2LedgerPathForTests,
  PrivacyDeletionV2Error,
  PRIVACY_DELETION_V2_MAX_BYTES,
  refreshBackendAccountDeletionStatusV2,
  type AccountDeletionStatusV2
} from "../src/privacy-deletion-v2.js";
import { handleGatewayRequest } from "../src/routes.js";
import { countLegacyDeletionCapabilities } from "../src/state-encryption-maintenance.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";

const CAPABILITY = Buffer.alloc(32, 0x5a).toString("base64url");
const REQUEST_ID = "server_review_deletion_0001";
const ACTOR = "server-review-actor";
const SESSION_SECRET = "server-review-session-secret-12345678901234567890";
const CONTEXT = { kind: "privacy-deletion-v2", recordId: "ledger.json" } as const;
let directory = "";
let keyringPath = "";

type TestRecord = {
  actor_sha256: string;
  account_generation: number;
  request_id: string;
  access_pre_digest: string;
  access_bound_digest: string | null;
  capability_key?: { version: number; key_id: string; verifier_sha256: string };
  backend_status: AccountDeletionStatusV2 | null;
  outbox: Array<{
    operation_id: string;
    kind: "REQUEST" | "DEVICE_EVIDENCE";
    body: string;
    body_sha256: string;
    attempts: number;
    next_attempt_at: string;
    paused?: { reason: string; cause_code: string; http_status: number };
  }>;
  request_receipt_sha256: string | null;
};
type TestLedger = { requests: Record<string, TestRecord> };

beforeEach(async () => {
  directory = await mkdtemp(path.join(tmpdir(), "walksafe-server-review-"));
  keyringPath = path.join(directory, "keyring.json");
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json"),
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_FIELD_TEST_TOKEN: "server-review-field-token-12345678901234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: "[]",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000"
  });
  await configureTestStateEncryption(keyringPath, [
    { keyId: "cap-old", status: "active", fillByte: 0x41 }
  ]);
});

afterEach(async () => {
  await rm(directory, { recursive: true, force: true });
});

function accept(requestId = REQUEST_ID, actor = ACTOR): void {
  assert.equal(acceptOrReplayAccountDeletionV2(actor, 1, {
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: requestId, client_revision: 1, confirmation: "DELETE_MY_ACCOUNT"
  }, CAPABILITY).kind, "accepted");
}

async function ledger(): Promise<TestLedger> {
  return decryptTestStateFile<TestLedger>(
    CONTEXT, await readFile(privacyDeletionV2LedgerPathForTests(), "utf8"),
    PRIVACY_DELETION_V2_MAX_BYTES
  );
}

async function save(value: TestLedger): Promise<void> {
  await writeFile(privacyDeletionV2LedgerPathForTests(),
    encryptTestStateFile(CONTEXT, value, PRIVACY_DELETION_V2_MAX_BYTES),
    { encoding: "utf8", mode: 0o600 });
}

function statusFixture(): AccountDeletionStatusV2 {
  const accepted = Date.parse("2026-08-09T00:00:00.000Z");
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: REQUEST_ID, client_revision: 1, revision: 1,
    accepted_at: new Date(accepted).toISOString(), updated_at: new Date(accepted).toISOString(),
    account_generation: 1, tombstone_id: "review_tombstone_0001",
    request_receipt_sha256: "a".repeat(64), overall_status: "PROCESSING",
    completion_receipt_sha256: null,
    items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => ({
      key: definition.key, status: "PENDING", item_revision: 1,
      due_at: new Date(accepted + definition.dueAfterMs).toISOString(),
      updated_at: new Date(accepted).toISOString(), evidence_sha256: null,
      disposition_basis: null, retry_after: null, restriction_reason: null,
      legal_hold_review_at: null, legal_hold_contact: null, terminal_at: null
    }))
  };
}

function client(): Request {
  return new Request("http://127.0.0.1/privacy-deletion-review");
}

function responseWithEscapingStatus(error: Error): Response {
  return new Proxy(new Response(null, { status: 503 }), {
    get(target, property) {
      if (property === "status") throw error;
      return Reflect.get(target, property, target);
    }
  });
}

function waitForStart(started: Promise<void>): Promise<void> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("outbox operation did not start")), 1_000);
    started.then(
      () => { clearTimeout(timeout); resolve(); },
      (error: unknown) => { clearTimeout(timeout); reject(error); }
    );
  });
}

test("capability uses a versioned purpose-derived key and survives session secret rotation", async () => {
  accept();
  const state = await ledger();
  const record = state.requests[REQUEST_ID]!;
  assert.deepEqual(record.capability_key, {
    version: 1, key_id: "cap-old", verifier_sha256: record.access_pre_digest
  });
  assert.notEqual(
    createDeletionCapabilityHmac("cap-old").update("probe").digest("hex"),
    createHmac("sha256", Buffer.alloc(32, 0x41)).update("probe").digest("hex")
  );
  assert.equal(JSON.stringify(state).includes(CAPABILITY), false);
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "rotated-session-secret-123456789012345678901234";
  assert.equal(authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY), null);
  assert.equal(authorizedAccountDeletionStatusV2(REQUEST_ID, Buffer.alloc(32, 9).toString("base64url")), undefined);
});

test("legacy bound capability upgrades without changing the Backend receipt digest", async () => {
  accept();
  const state = await ledger();
  const record = state.requests[REQUEST_ID]!;
  const pre = createHmac("sha256", SESSION_SECRET)
    .update("walksafe/delete-access/pre/v2\0")
    .update(record.actor_sha256).update("\0").update("1").update("\0")
    .update(REQUEST_ID).update("\0").update(Buffer.from(CAPABILITY, "base64url")).digest("hex");
  record.access_pre_digest = pre;
  delete record.capability_key;
  await save(state);
  assert.equal(authorizedAccountDeletionStatusV2(REQUEST_ID, Buffer.alloc(32, 9).toString("base64url")), undefined);
  assert.equal((await ledger()).requests[REQUEST_ID]!.capability_key, undefined);
  const forwarded = await forwardAccountDeletionRequestV2(REQUEST_ID, client(), async (_url, init) => {
    assert.equal(new Headers(init?.headers).get(DELETION_ACCESS_PRE_DIGEST_HEADER), pre);
    return Response.json(statusFixture(), { status: 202 });
  });
  assert.equal(forwarded.kind, "ok");
  assert.equal(authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY)?.tombstone_id,
    "review_tombstone_0001");
  const upgraded = (await ledger()).requests[REQUEST_ID]!;
  assert.equal(upgraded.access_pre_digest, pre);
  assert.equal(upgraded.capability_key?.version, 1);
  assert.notEqual(upgraded.capability_key?.verifier_sha256, pre);
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "rotated-session-secret-123456789012345678901234";
  const refreshed = await refreshBackendAccountDeletionStatusV2(
    REQUEST_ID, CAPABILITY, client(), async (_url, init) => {
      assert.equal(new Headers(init?.headers).get(DELETION_ACCESS_PRE_DIGEST_HEADER), pre);
      return Response.json(statusFixture());
    });
  assert.equal(refreshed.kind, "ok");
});

test("S19-35 maintenance counts session-key-bound legacy capabilities and blocks session secret retirement", async () => {
  accept();
  accept("server_review_deletion_0002", "server-review-actor-2");
  const state = await ledger();
  const record = state.requests[REQUEST_ID]!;
  record.access_pre_digest = createHmac("sha256", SESSION_SECRET)
    .update("walksafe/delete-access/pre/v2\0")
    .update(record.actor_sha256).update("\0").update("1").update("\0")
    .update(REQUEST_ID).update("\0").update(Buffer.from(CAPABILITY, "base64url")).digest("hex");
  delete record.capability_key;
  await save(state);
  const maintenanceEnvironment = { ...process.env, WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED: "true" };
  assert.deepEqual(await countLegacyDeletionCapabilities(maintenanceEnvironment), {
    active: 1, archived: 0, total: 1
  });
  const check = () => spawnSync(process.execPath, [
    fileURLToPath(new URL("../src/state-encryption-maintenance.js", import.meta.url)),
    "check-session-secret-retirement"
  ], { encoding: "utf8", env: maintenanceEnvironment, timeout: 15_000 });
  const blocked = check();
  assert.equal(blocked.status, 3, blocked.stderr);
  assert.deepEqual(JSON.parse(blocked.stdout), {
    command: "check-session-secret-retirement",
    legacy_deletion_capabilities: { active: 1, archived: 0, total: 1 },
    session_secret_retirement: "blocked"
  });
  // One authenticated use of the legacy capability re-keys it; then retirement is allowed.
  assert.equal(authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY), null);
  assert.equal((await ledger()).requests[REQUEST_ID]!.capability_key?.version, 1);
  const allowed = check();
  assert.equal(allowed.status, 0, allowed.stderr);
  assert.equal((JSON.parse(allowed.stdout) as { session_secret_retirement: string }).session_secret_retirement, "allowed");
  await assert.rejects(countLegacyDeletionCapabilities({ ...process.env }), /SERVICE_STOPPED=true/);
});

test("decrypt-only capability verifies and rekeys on authenticated use", async () => {
  accept();
  const pre = (await ledger()).requests[REQUEST_ID]!.access_pre_digest;
  await configureTestStateEncryption(keyringPath, [
    { keyId: "cap-old", status: "decrypt-only", fillByte: 0x41 },
    { keyId: "cap-new", status: "active", fillByte: 0x42 }
  ]);
  assert.equal(authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY), null);
  const updated = (await ledger()).requests[REQUEST_ID]!;
  assert.equal(updated.capability_key?.key_id, "cap-new");
  assert.equal(updated.access_pre_digest, pre);
  await configureTestStateEncryption(keyringPath, [
    { keyId: "cap-new", status: "active", fillByte: 0x42 }
  ]);
  assert.equal(authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY), null);
});

for (const oldStatus of ["unknown", "compromised"] as const) {
  test(`referenced ${oldStatus} capability key fails closed after envelope rotation`, async () => {
    accept();
    await configureTestStateEncryption(keyringPath, [
      { keyId: "cap-old", status: "decrypt-only", fillByte: 0x41 },
      { keyId: "cap-new", status: "active", fillByte: 0x42 }
    ]);
    await save(await ledger());
    await configureTestStateEncryption(keyringPath, [
      { keyId: "cap-new", status: "active", fillByte: 0x42 },
      ...(oldStatus === "compromised"
        ? [{ keyId: "cap-old", status: "compromised" as const, fillByte: 0x41 }]
        : [])
    ]);
    assert.throws(() => authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY),
      /ledger is invalid/);
  });
}

test("direct retries honor persisted backoff measured from failed attempt completion", async () => {
  accept();
  let calls = 0;
  let failedAt = 0;
  const failure = async (): Promise<Response> => {
    calls += 1;
    await new Promise((resolve) => setTimeout(resolve, 25));
    failedAt = Date.now();
    return new Response(null, { status: 503 });
  };
  assert.equal((await forwardAccountDeletionRequestV2(REQUEST_ID, client(), failure)).kind, "pending");
  const operation = (await ledger()).requests[REQUEST_ID]!.outbox[0]!;
  assert.equal(operation.attempts, 1);
  assert.ok(Date.parse(operation.next_attempt_at) >= failedAt + 4_990);
  const early = await forwardAccountDeletionRequestV2(REQUEST_ID, client(), failure);
  assert.equal(early.kind, "pending");
  for (const method of ["POST", "GET"] as const) {
    const path = method === "POST" ? "/privacy/account-deletions"
      : `/privacy/account-deletions/${REQUEST_ID}/status`;
    const replay = await handleGatewayRequest(new Request(`http://127.0.0.1${path}`, {
      method,
      headers: {
        "content-type": "application/json",
        [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
      },
      ...(method === "POST" ? { body: JSON.stringify({
        schema_version: "walksafe.account-deletion-request.v2",
        request_id: REQUEST_ID, client_revision: 1, confirmation: "DELETE_MY_ACCOUNT"
      }) } : {})
    }), { fetchImpl: failure });
    assert.equal(replay.status, 503);
    assert.equal(replay.headers.get("retry-after"), "5");
    assert.deepEqual(await replay.json(), { code: "account_deletion_backend_pending" });
    assert.deepEqual((await ledger()).requests[REQUEST_ID]!.outbox[0], operation,
      "ordinary request replay and unaccepted status lookup must not advance persisted backoff");
  }
  assert.equal(calls, 1);
  const logicalNow = Date.now() + 60_000;
  assert.deepEqual(await drainAccountDeletionOutboxV2(failure, logicalNow), { attempted: 1, succeeded: 0 });
  assert.equal(calls, 2);
  assert.ok(Date.parse((await ledger()).requests[REQUEST_ID]!.outbox[0]!.next_attempt_at) >= logicalNow + 10_000);
});

for (const mode of ["direct", "drain"] as const) {
test(`${mode} failure completion backoff includes a slow preflight ledger read`, async (t) => {
  accept();
  const originalRead = fs.readFileSync;
  const ledgerMetadata = fs.statSync(privacyDeletionV2LedgerPathForTests());
  let slowReads = 0;
  let failedAt = 0;
  fs.readFileSync = ((...args: Parameters<typeof fs.readFileSync>) => {
    const file = args[0];
    if (typeof file === "number") {
      const metadata = fs.fstatSync(file);
      if (metadata.dev === ledgerMetadata.dev && metadata.ino === ledgerMetadata.ino) {
        slowReads += 1;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 120);
      }
    }
    return Reflect.apply(originalRead, fs, args);
  }) as typeof fs.readFileSync;
  syncBuiltinESMExports();
  try {
    const failure = async (): Promise<Response> => {
      await new Promise((resolve) => setTimeout(resolve, 25));
      failedAt = Date.now();
      return new Response(null, { status: 503 });
    };
    if (mode === "direct") {
      assert.equal((await forwardAccountDeletionRequestV2(REQUEST_ID, client(), failure)).kind, "pending");
    } else {
      assert.deepEqual(await drainAccountDeletionOutboxV2(failure), { attempted: 1, succeeded: 0 });
    }
  } finally {
    fs.readFileSync = originalRead;
    syncBuiltinESMExports();
  }
  const operation = (await ledger()).requests[REQUEST_ID]!.outbox[0]!;
  const remainingAtFailure = Date.parse(operation.next_attempt_at) - failedAt;
  t.diagnostic(JSON.stringify({ slowReads, remainingAtFailure }));
  assert.ok(slowReads >= 2, "both wrapper and common forwarding preflight must read the delayed ledger");
  assert.equal(operation.attempts, 1);
  assert.ok(remainingAtFailure >= 4_990,
    "the first five-second backoff must begin when the delayed attempt fails");
});
}

test("drain starts an independent request before a slow head response completes", async () => {
  const secondRequestId = "server_review_deletion_0002";
  accept();
  accept(secondRequestId, "server-review-actor-2");
  const now = Date.now() + 1_000;
  const state = await ledger();
  state.requests[REQUEST_ID]!.outbox[0]!.next_attempt_at = new Date(now - 2_000).toISOString();
  state.requests[secondRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 1_000).toISOString();
  await save(state);

  let releaseFirst!: () => void;
  const firstResponse = new Promise<void>((resolve) => { releaseFirst = resolve; });
  let firstStarted!: () => void;
  const firstCall = new Promise<void>((resolve) => { firstStarted = resolve; });
  let secondStarted!: () => void;
  const secondCall = new Promise<void>((resolve) => { secondStarted = resolve; });
  const calls: string[] = [];
  const draining = drainAccountDeletionOutboxV2(async (_url, init) => {
    const requestId = (JSON.parse(init!.body as string) as { request_id: string }).request_id;
    calls.push(requestId);
    if (requestId === REQUEST_ID) {
      firstStarted();
      await firstResponse;
    } else {
      secondStarted();
    }
    return Response.json({
      ...statusFixture(), request_id: requestId,
      tombstone_id: requestId === REQUEST_ID ? "review_tombstone_0001" : "review_tombstone_0002"
    }, { status: 202 });
  }, now);
  try {
    await waitForStart(firstCall);
    await waitForStart(secondCall);
    assert.deepEqual(calls, [REQUEST_ID, secondRequestId]);
  } finally {
    releaseFirst();
  }
  assert.deepEqual(await draining, { attempted: 2, succeeded: 2 });
});

test("drain preserves ordering for operations of the same request", async () => {
  const secondRequestId = "server_review_deletion_0002";
  accept();
  accept(secondRequestId, "server-review-actor-2");
  const now = Date.now() + 1_000;
  const state = await ledger();
  const first = state.requests[REQUEST_ID]!.outbox[0]!;
  first.next_attempt_at = new Date(now - 3_000).toISOString();
  state.requests[REQUEST_ID]!.outbox.push({
    ...first, operation_id: "review_duplicate_request_0001",
    next_attempt_at: new Date(now - 2_000).toISOString()
  });
  state.requests[secondRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 1_000).toISOString();
  await save(state);

  let releaseFirst!: () => void;
  const firstResponse = new Promise<void>((resolve) => { releaseFirst = resolve; });
  let secondStarted!: () => void;
  const secondCall = new Promise<void>((resolve) => { secondStarted = resolve; });
  const calls: string[] = [];
  const draining = drainAccountDeletionOutboxV2(async (_url, init) => {
    const requestId = (JSON.parse(init!.body as string) as { request_id: string }).request_id;
    calls.push(requestId);
    if (requestId === REQUEST_ID && calls.filter((id) => id === REQUEST_ID).length === 1) {
      await firstResponse;
    }
    if (requestId === secondRequestId) secondStarted();
    return Response.json({
      ...statusFixture(), request_id: requestId,
      tombstone_id: requestId === REQUEST_ID ? "review_tombstone_0001" : "review_tombstone_0002"
    }, { status: 202 });
  }, now);
  try {
    await waitForStart(secondCall);
    assert.equal(calls.filter((id) => id === REQUEST_ID).length, 1);
  } finally {
    releaseFirst();
  }
  assert.deepEqual(await draining, { attempted: 3, succeeded: 3 });
  assert.deepEqual(calls, [REQUEST_ID, secondRequestId, REQUEST_ID]);
});

test("drain cursor cannot put a later same-request operation ahead of its predecessor", async () => {
  const secondRequestId = "server_review_deletion_0002";
  const thirdRequestId = "server_review_deletion_0003";
  const fourthRequestId = "server_review_deletion_0004";
  accept();
  assert.deepEqual(await drainAccountDeletionOutboxV2(async () =>
    new Response(null, { status: 503 }), Date.now() + 1_000),
  { attempted: 1, succeeded: 0 });
  accept(secondRequestId, "server-review-actor-2");
  accept(thirdRequestId, "server-review-actor-3");
  accept(fourthRequestId, "server-review-actor-4");
  const now = Date.now() + 1_000;
  const state = await ledger();
  const first = state.requests[REQUEST_ID]!.outbox[0]!;
  first.next_attempt_at = new Date(now - 4_000).toISOString();
  state.requests[REQUEST_ID]!.outbox.push({
    ...first, operation_id: "review_duplicate_request_0001", attempts: 0,
    next_attempt_at: new Date(now - 1_000).toISOString()
  });
  state.requests[secondRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 3_000).toISOString();
  state.requests[thirdRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 2_000).toISOString();
  state.requests[fourthRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 1_000).toISOString();
  await save(state);
  const abort = new AbortController();
  assert.deepEqual(await drainAccountDeletionOutboxV2(async () => {
    abort.abort();
    return new Response(null, { status: 503 });
  }, now, abort.signal), { attempted: 3, succeeded: 0 });

  const ready = await ledger();
  const firstAttempts = ready.requests[REQUEST_ID]!.outbox[0]!.attempts;
  ready.requests[REQUEST_ID]!.outbox[0]!.next_attempt_at = new Date(now - 3_000).toISOString();
  ready.requests[secondRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 2_000).toISOString();
  ready.requests[REQUEST_ID]!.outbox[1]!.next_attempt_at = new Date(now - 1_000).toISOString();
  ready.requests[thirdRequestId]!.outbox[0]!.next_attempt_at = new Date(now + 60_000).toISOString();
  ready.requests[fourthRequestId]!.outbox[0]!.next_attempt_at = new Date(now + 60_000).toISOString();
  await save(ready);
  const calls: string[] = [];
  const result = await drainAccountDeletionOutboxV2(async (_url, init) => {
    const requestId = (JSON.parse(init!.body as string) as { request_id: string }).request_id;
    calls.push(requestId);
    return requestId === REQUEST_ID
      ? new Response(null, { status: 503 })
      : Response.json({ ...statusFixture(), request_id: secondRequestId,
          tombstone_id: "review_tombstone_0002" }, { status: 202 });
  }, now);
  const after = await ledger();
  assert.deepEqual(calls, [secondRequestId, REQUEST_ID]);
  assert.deepEqual(result, { attempted: 2, succeeded: 1 });
  assert.equal(after.requests[REQUEST_ID]!.outbox[0]!.attempts, firstAttempts + 1);
  assert.equal(after.requests[REQUEST_ID]!.outbox[1]!.attempts, 0);
  assert.equal(after.requests[secondRequestId]!.outbox.length, 0);
});

test("drain bounds independent requests to three concurrent forwards", async () => {
  for (let index = 0; index < 5; index += 1) {
    accept(`server_review_deletion_${String(index).padStart(4, "0")}`,
      `server-review-actor-${index}`);
  }
  let releaseResponses!: () => void;
  const responses = new Promise<void>((resolve) => { releaseResponses = resolve; });
  let thirdStarted!: () => void;
  const thirdCall = new Promise<void>((resolve) => { thirdStarted = resolve; });
  let active = 0;
  let peak = 0;
  let calls = 0;
  const draining = drainAccountDeletionOutboxV2(async (_url, init) => {
    const requestId = (JSON.parse(init!.body as string) as { request_id: string }).request_id;
    calls += 1;
    active += 1;
    peak = Math.max(peak, active);
    if (calls === 3) thirdStarted();
    await responses;
    active -= 1;
    return Response.json({ ...statusFixture(), request_id: requestId,
      tombstone_id: `review_tombstone_${requestId}` }, { status: 202 });
  }, Date.now() + 1_000);
  try {
    await waitForStart(thirdCall);
    assert.equal(calls, 3);
    assert.equal(peak, 3);
  } finally {
    releaseResponses();
  }
  assert.deepEqual(await draining, { attempted: 5, succeeded: 5 });
  assert.equal(calls, 5);
  assert.equal(peak, 3);
});

test("overlapping drains share the three-forward process cap", async () => {
  for (let index = 0; index < 3; index += 1) {
    accept(`server_review_deletion_${String(index).padStart(4, "0")}`,
      `server-review-actor-${index}`);
  }
  let releaseResponses!: () => void;
  const responses = new Promise<void>((resolve) => { releaseResponses = resolve; });
  let thirdStarted!: () => void;
  const thirdCall = new Promise<void>((resolve) => { thirdStarted = resolve; });
  let active = 0;
  let peak = 0;
  const calls: string[] = [];
  const fetchImpl = async (_url: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const requestId = (JSON.parse(init!.body as string) as { request_id: string }).request_id;
    calls.push(requestId);
    active += 1;
    peak = Math.max(peak, active);
    if (calls.length === 3) thirdStarted();
    await responses;
    active -= 1;
    return Response.json({ ...statusFixture(), request_id: requestId,
      tombstone_id: `review_tombstone_${requestId}` }, { status: 202 });
  };
  const now = Date.now() + 1_000;
  const firstDrain = drainAccountDeletionOutboxV2(fetchImpl, now);
  let secondDrain: ReturnType<typeof drainAccountDeletionOutboxV2> | undefined;
  let callsBeforeRelease = 0;
  try {
    await waitForStart(thirdCall);
    for (let index = 3; index < 6; index += 1) {
      accept(`server_review_deletion_${String(index).padStart(4, "0")}`,
        `server-review-actor-${index}`);
    }
    secondDrain = drainAccountDeletionOutboxV2(fetchImpl, now);
    await new Promise<void>((resolve) => setImmediate(resolve));
    callsBeforeRelease = calls.length;
  } finally {
    releaseResponses();
  }
  assert.deepEqual(await firstDrain, { attempted: 3, succeeded: 3 });
  assert.deepEqual(await secondDrain, { attempted: 3, succeeded: 3 });
  assert.equal(callsBeforeRelease, 3);
  assert.equal(calls.length, 6);
  assert.equal(peak, 3);
});

test("drain cancellation does not begin subsequent operations", async () => {
  for (let index = 0; index < 5; index += 1) {
    accept(`server_review_deletion_${String(index).padStart(4, "0")}`,
      `server-review-actor-${index}`);
  }
  const abort = new AbortController();
  const calls: string[] = [];
  const result = await drainAccountDeletionOutboxV2(async (_url, init) => {
    calls.push((JSON.parse(init!.body as string) as { request_id: string }).request_id);
    abort.abort();
    assert.equal(init?.signal?.aborted, true);
    return new Response(null, { status: 503 });
  }, Date.now() + 1_000, abort.signal);
  assert.equal(result.succeeded, 0);
  assert.ok(result.attempted >= 1 && result.attempted <= 3);
  assert.ok(calls.length <= 3);
  assert.equal(abort.signal.aborted, true);
});

test("drain isolates an escaping job exception without changing its outbox or receipt", async () => {
  const secondRequestId = "server_review_deletion_0002";
  accept();
  accept(secondRequestId, "server-review-actor-2");
  const now = Date.now() + 1_000;
  const state = await ledger();
  state.requests[REQUEST_ID]!.outbox[0]!.next_attempt_at = new Date(now - 2_000).toISOString();
  state.requests[secondRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 1_000).toISOString();
  await save(state);
  const before = await ledger();
  const calls: string[] = [];
  let firstRequestId: string | undefined;

  const result = await drainAccountDeletionOutboxV2(async (_url, init) => {
    const body = JSON.parse(init!.body as string) as { request_id: string };
    calls.push(body.request_id);
    if (firstRequestId === undefined) {
      firstRequestId = body.request_id;
      return responseWithEscapingStatus(new Error("synthetic unexpected failure"));
    }
    assert.notEqual(body.request_id, firstRequestId);
    return Response.json({
      ...statusFixture(),
      request_id: body.request_id,
      tombstone_id: body.request_id === REQUEST_ID
        ? "review_tombstone_0001" : "review_tombstone_0002"
    }, { status: 202 });
  }, now);

  const after = await ledger();
  assert.equal(calls.length, 2);
  const failedRequestId = calls[0]!;
  const successfulRequestId = calls[1]!;
  assert.equal(failedRequestId, firstRequestId);
  assert.notEqual(successfulRequestId, failedRequestId);
  assert.deepEqual(result, { attempted: 2, succeeded: 1 });
  assert.deepEqual(after.requests[failedRequestId], before.requests[failedRequestId]);
  assert.equal(after.requests[failedRequestId]!.outbox.length, 1);
  assert.equal(after.requests[successfulRequestId]!.outbox.length, 0);
  assert.equal(after.requests[successfulRequestId]!.request_receipt_sha256, "a".repeat(64));
});

test("transient busy on one job leaves it due and forwards an independent request", async () => {
  const secondRequestId = "server_review_deletion_0002";
  accept();
  accept(secondRequestId, "server-review-actor-2");
  const now = Date.now() + 1_000;
  const state = await ledger();
  state.requests[REQUEST_ID]!.outbox[0]!.next_attempt_at = new Date(now - 2_000).toISOString();
  state.requests[secondRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 1_000).toISOString();
  await save(state);
  const before = await ledger();
  const calls: string[] = [];
  const result = await drainAccountDeletionOutboxV2(async (_url, init) => {
    const requestId = (JSON.parse(init!.body as string) as { request_id: string }).request_id;
    calls.push(requestId);
    if (requestId === REQUEST_ID) {
      return responseWithEscapingStatus(new PrivacyDeletionV2Error("busy", "synthetic lock contention"));
    }
    return Response.json({ ...statusFixture(), request_id: secondRequestId,
      tombstone_id: "review_tombstone_0002" }, { status: 202 });
  }, now);
  const after = await ledger();
  assert.deepEqual(calls.slice().sort(), [REQUEST_ID, secondRequestId].sort());
  assert.deepEqual(result, { attempted: 2, succeeded: 1 });
  assert.deepEqual(after.requests[REQUEST_ID], before.requests[REQUEST_ID]);
  assert.equal(after.requests[secondRequestId]!.outbox.length, 0);
  assert.equal(after.requests[secondRequestId]!.request_receipt_sha256, "a".repeat(64));
});

test("a second drain reaches the tail while the first sixteen jobs remain due", async () => {
  const requestIds = Array.from({ length: 17 }, (_, index) =>
    `server_review_deletion_${String(index).padStart(4, "0")}`);
  for (const [index, requestId] of requestIds.entries()) {
    accept(requestId, `server-review-actor-${index}`);
  }
  const now = Date.now() + 1_000;
  const state = await ledger();
  for (const [index, requestId] of requestIds.entries()) {
    state.requests[requestId]!.outbox[0]!.next_attempt_at =
      new Date(now - (17 - index) * 1_000).toISOString();
  }
  await save(state);
  const before = await ledger();
  const calls: string[] = [];
  let succeedingId: string | null = null;
  const fetchImpl = async (_url: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const body = JSON.parse(init!.body as string) as { request_id: string };
    calls.push(body.request_id);
    if (body.request_id !== succeedingId) {
      return responseWithEscapingStatus(new Error("synthetic blocked job"));
    }
    return Response.json({
      ...statusFixture(),
      request_id: body.request_id,
      tombstone_id: "review_tombstone_tail"
    }, { status: 202 });
  };

  assert.deepEqual(await drainAccountDeletionOutboxV2(fetchImpl, now),
    { attempted: 16, succeeded: 0 });
  const firstCalls = calls.slice();
  assert.equal(new Set(firstCalls).size, 16);
  succeedingId = requestIds.find((requestId) => !firstCalls.includes(requestId)) ?? null;
  assert.ok(succeedingId);
  const afterFirst = await ledger();
  for (const requestId of firstCalls) {
    assert.deepEqual(afterFirst.requests[requestId], before.requests[requestId]);
    assert.ok(Date.parse(afterFirst.requests[requestId]!.outbox[0]!.next_attempt_at) <= now);
  }

  assert.deepEqual(await drainAccountDeletionOutboxV2(fetchImpl, now),
    { attempted: 16, succeeded: 1 });
  assert.equal(calls[16], succeedingId);
  const afterSecond = await ledger();
  for (const requestId of firstCalls) {
    assert.deepEqual(afterSecond.requests[requestId], before.requests[requestId]);
  }
  assert.equal(afterSecond.requests[succeedingId]!.outbox.length, 0);
  assert.equal(afterSecond.requests[succeedingId]!.request_receipt_sha256, "a".repeat(64));
});

test("drain still propagates deliberate integrity failures", async () => {
  accept();
  const before = (await ledger()).requests[REQUEST_ID]!;
  await assert.rejects(
    drainAccountDeletionOutboxV2(async () =>
      responseWithEscapingStatus(new PrivacyDeletionV2Error("integrity", "synthetic invariant")),
    Date.now() + 1_000),
    (error: unknown) => error instanceof PrivacyDeletionV2Error && error.code === "integrity"
  );
  assert.deepEqual((await ledger()).requests[REQUEST_ID], before);
});


for (const failure of ["network", "json", "status"] as const) {
  test(`outbox item ${failure} failure still processes the next due request`, async () => {
    const secondRequestId = "server_review_deletion_0002";
    accept();
    accept(secondRequestId, "server-review-actor-2");
    const now = Date.now() + 1_000;
    const state = await ledger();
    state.requests[REQUEST_ID]!.outbox[0]!.next_attempt_at = new Date(now - 2_000).toISOString();
    state.requests[secondRequestId]!.outbox[0]!.next_attempt_at = new Date(now - 1_000).toISOString();
    await save(state);
    const calls: string[] = [];

    const result = await drainAccountDeletionOutboxV2(async (_url, init) => {
      assert.equal(typeof init?.body, "string");
      const body = JSON.parse(init!.body as string) as { request_id: string };
      calls.push(body.request_id);
      if (body.request_id === REQUEST_ID) {
        if (failure === "network") throw new TypeError("injected local network failure");
        if (failure === "json") {
          return new Response("{invalid-json", {
            status: 202, headers: { "content-type": "application/json" }
          });
        }
        return Response.json({ ...statusFixture(), request_id: "wrong_request_id_0001" },
          { status: 202 });
      }
      assert.equal(body.request_id, secondRequestId);
      return Response.json({
        ...statusFixture(),
        request_id: secondRequestId,
        tombstone_id: "review_tombstone_0002"
      }, { status: 202 });
    }, now);

    const after = await ledger();
    const firstOperation = after.requests[REQUEST_ID]!.outbox[0]!;
    const secondOutbox = after.requests[secondRequestId]!.outbox;
    console.log(JSON.stringify({
      probe: "outbox_next_due_request",
      failure,
      ...result,
      call_order: calls,
      first_attempts: firstOperation.attempts,
      first_retry_is_future: Date.parse(firstOperation.next_attempt_at) > now,
      second_outbox_remaining: secondOutbox.length
    }));
    assert.deepEqual(calls, [REQUEST_ID, secondRequestId]);
    assert.deepEqual(result, { attempted: 2, succeeded: 1 });
    if (failure === "network") {
      assert.equal(firstOperation.attempts, 1);
      assert.ok(Date.parse(firstOperation.next_attempt_at) > now);
      assert.equal(firstOperation.paused, undefined);
    } else {
      assert.equal(firstOperation.attempts, 0);
      assert.equal(firstOperation.paused?.reason, "contract");
      assert.equal(firstOperation.paused?.cause_code, "invalid_success_payload");
      assert.equal(firstOperation.paused?.http_status, 202);
    }
    assert.equal(secondOutbox.length, 0);
    assert.equal(
      authorizedAccountDeletionStatusV2(secondRequestId, CAPABILITY)?.request_id,
      secondRequestId
    );
  });
}


test("worker shutdown awaits the actual drain completion and prevents restart", async () => {
  let finish!: () => void;
  let started!: () => void;
  const began = new Promise<void>((resolve) => { started = resolve; });
  let signal: AbortSignal | undefined;
  let calls = 0;
  const worker = new AccountDeletionOutboxWorker(async (incoming) => {
    calls += 1;
    signal = incoming;
    started();
    await new Promise<void>((resolve) => { finish = resolve; });
  }, () => assert.fail("drain unexpectedly failed"));
  worker.start();
  worker.start();
  await began;
  let stopped = false;
  const stopping = worker.stop().then(() => { stopped = true; });
  await Promise.resolve();
  assert.equal(signal?.aborted, true);
  assert.equal(stopped, false);
  finish();
  await stopping;
  worker.start();
  assert.equal(calls, 1);
});

test("worker contains drain errors and stop-before-start schedules nothing", async () => {
  let errors = 0;
  const worker = new AccountDeletionOutboxWorker(async () => {
    throw new Error("test failure");
  }, () => { errors += 1; });
  worker.start();
  await new Promise((resolve) => setImmediate(resolve));
  await worker.stop();
  assert.equal(errors, 1);
  const never = new AccountDeletionOutboxWorker(async () => assert.fail("must not run"), () => {});
  await never.stop();
  never.start();
});
