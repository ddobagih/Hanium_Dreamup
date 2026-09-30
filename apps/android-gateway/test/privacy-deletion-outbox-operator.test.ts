import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import type { GatewayFetch } from "../src/backend.js";
import { resolveGatewayServiceRuntimeLockPath } from "../src/config.js";
import { acquireSharedFileLock, ExclusiveFileLockBusyError } from "../src/exclusive-file-lock.js";
import { runPrivacyDeletionOutboxOperator } from "../src/privacy-deletion-outbox-operator.js";
import {
  acceptOrReplayAccountDeletionV2,
  ACCOUNT_DELETION_INVENTORY_V2,
  drainAccountDeletionOutboxV2,
  forwardAccountDeletionRequestV2,
  isAccountGenerationFencedV2,
  listPausedAccountDeletionOutboxV2,
  privacyDeletionV2LedgerPathForTests,
  type AccountDeletionStatusV2
} from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let stateDirectory = "";
const CAPABILITY = Buffer.alloc(32, 0x73).toString("base64url");

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-deletion-operator-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "deletion-operator-internal-token-1234567890",
    WALKSAFE_GATEWAY_SESSION_SECRET:
      "deletion-operator-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function accept(index: number): { requestId: string; actorId: string } {
  const requestId = `deletion_operator_request_${String(index).padStart(4, "0")}`;
  const actorId = `deletion-operator-actor-${index}`;
  assert.equal(acceptOrReplayAccountDeletionV2(actorId, 1, {
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: requestId,
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT"
  }, CAPABILITY).kind, "accepted");
  return { requestId, actorId };
}

function request(): Request {
  return new Request("http://127.0.0.1/privacy/account-deletions");
}

function statusFixture(requestId: string): AccountDeletionStatusV2 {
  const acceptedAt = "2026-09-25T00:00:00.000Z";
  const acceptedAtMs = Date.parse(acceptedAt);
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: requestId,
    client_revision: 1,
    revision: 1,
    accepted_at: acceptedAt,
    updated_at: acceptedAt,
    account_generation: 1,
    tombstone_id: "operator-tombstone-0001",
    request_receipt_sha256: "a".repeat(64),
    overall_status: "PARTIAL",
    items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => ({
      key: definition.key,
      status: "EXTERNAL_PENDING" as const,
      item_revision: 1,
      due_at: new Date(acceptedAtMs + definition.dueAfterMs).toISOString(),
      updated_at: acceptedAt,
      evidence_sha256: null,
      disposition_basis: null,
      retry_after: null,
      restriction_reason: null,
      legal_hold_review_at: null,
      legal_hold_contact: null,
      terminal_at: null
    })),
    completion_receipt_sha256: null
  };
}

test("400, known 422, 401 and 403 pause durably and alert once without sensitive values", async () => {
  const cases = [
    { status: 400, code: "untrusted_detail", reason: "contract", cause: "backend_http_400" },
    { status: 422, code: "account_deletion_request_invalid", reason: "contract", cause: "account_deletion_request_invalid" },
    { status: 401, code: "untrusted_detail", reason: "credentials", cause: "backend_http_401" },
    { status: 403, code: "untrusted_detail", reason: "credentials", cause: "backend_http_403" }
  ] as const;
  const originalError = console.error;
  const alerts: string[] = [];
  console.error = (...parts: unknown[]) => { alerts.push(parts.join(" ")); };
  try {
    for (const [index, item] of cases.entries()) {
      const { requestId, actorId } = accept(index + 1);
      let calls = 0;
      const fetchImpl: GatewayFetch = async () => {
        calls += 1;
        return Response.json({
          detail: { code: item.code, message: "secret-and-personal-data-must-not-appear" }
        }, { status: item.status });
      };
      assert.equal((await forwardAccountDeletionRequestV2(requestId, request(), fetchImpl)).kind, "pending");
      assert.equal((await forwardAccountDeletionRequestV2(requestId, request(), fetchImpl)).kind, "pending");
      assert.equal(calls, 1);
      const pause = listPausedAccountDeletionOutboxV2().find(
        (candidate) => candidate.cause_code === item.cause
      );
      assert.ok(pause);
      assert.equal(pause.reason, item.reason);
      assert.equal(pause.http_status, item.status);
      assert.equal(isAccountGenerationFencedV2(actorId, 1), true);
      assert.equal(acceptOrReplayAccountDeletionV2(actorId, 1, {
        schema_version: "walksafe.account-deletion-request.v2",
        request_id: requestId,
        client_revision: 1,
        confirmation: "DELETE_MY_ACCOUNT"
      }, CAPABILITY).kind, "replay");
      assert.equal(alerts.length, index + 1);
      const event = JSON.parse(alerts[index]!) as Record<string, unknown>;
      assert.equal(event.event_name, "walksafe.gateway.account_deletion_outbox.paused");
      assert.equal(event.reason, item.reason);
      assert.equal(event.cause_code, item.cause);
      assert.equal(event.request_ref, pause.request_ref);
      assert.ok(!alerts[index]!.includes(requestId));
      assert.ok(!alerts[index]!.includes(actorId));
      assert.ok(!alerts[index]!.includes(CAPABILITY));
      assert.ok(!alerts[index]!.includes("secret-and-personal-data-must-not-appear"));
    }
    assert.deepEqual(
      await drainAccountDeletionOutboxV2(undefined, Date.now() + 24 * 60 * 60 * 1_000),
      { attempted: 0, succeeded: 0 }
    );
    const encrypted = await readFile(privacyDeletionV2LedgerPathForTests(), "utf8");
    assert.ok(!encrypted.includes("deletion_operator_request_"));
    assert.ok(!encrypted.includes(CAPABILITY));
  } finally {
    console.error = originalError;
  }
});

test("429, 5xx, network and unknown 422 retry; 409 retains terminal handling", async () => {
  const cases = [
    { status: 429, code: "rate_limited", kind: "pending" },
    { status: 500, code: "server_error", kind: "pending" },
    { status: 503, code: "server_error", kind: "pending" },
    { status: 422, code: "unknown_future_contract", kind: "pending" },
    { status: 409, code: "account_deletion_request_conflict", kind: "terminal_conflict" }
  ] as const;
  for (const [index, item] of cases.entries()) {
    const { requestId } = accept(index + 20);
    let calls = 0;
    const fetchImpl: GatewayFetch = async () => {
      calls += 1;
      return Response.json({ detail: { code: item.code } }, { status: item.status });
    };
    assert.equal((await forwardAccountDeletionRequestV2(requestId, request(), fetchImpl)).kind, item.kind);
    assert.equal((await forwardAccountDeletionRequestV2(requestId, request(), fetchImpl)).kind, item.kind === "terminal_conflict" ? "terminal_conflict" : "pending");
    assert.equal(calls, 1);
  }
  const { requestId } = accept(30);
  let networkCalls = 0;
  const networkFailure: GatewayFetch = async () => {
    networkCalls += 1;
    throw new Error("network down");
  };
  assert.equal((await forwardAccountDeletionRequestV2(requestId, request(), networkFailure)).kind, "pending");
  assert.equal(networkCalls, 1);
  assert.equal(listPausedAccountDeletionOutboxV2().length, 4);
});

test("operator requires exclusive runtime lock and compare-and-resume after correction", async () => {
  const existingPauseIds = new Set(listPausedAccountDeletionOutboxV2().map((operation) => operation.pause_id));
  const { requestId, actorId } = accept(40);
  let corrected = false;
  let calls = 0;
  const fetchImpl: GatewayFetch = async () => {
    calls += 1;
    return corrected
      ? Response.json(statusFixture(requestId), { status: 202 })
      : Response.json({ detail: { code: "bad_credentials" } }, { status: 401 });
  };
  assert.equal((await forwardAccountDeletionRequestV2(requestId, request(), fetchImpl)).kind, "pending");
  const shared = acquireSharedFileLock(resolveGatewayServiceRuntimeLockPath());
  try {
    assert.throws(
      () => runPrivacyDeletionOutboxOperator(["list"]),
      ExclusiveFileLockBusyError
    );
  } finally {
    shared.release();
  }
  const listed = runPrivacyDeletionOutboxOperator(["list"]);
  assert.equal(listed.command, "list");
  if (listed.command !== "list") throw new Error("operator list failed");
  const pause = listed.paused.find((candidate) => candidate.cause_code === "backend_http_401" &&
    !existingPauseIds.has(candidate.pause_id));
  assert.ok(pause);
  assert.ok(!JSON.stringify(listed).includes(requestId));
  assert.ok(!JSON.stringify(listed).includes(actorId));
  const stale = runPrivacyDeletionOutboxOperator([
    "resume", pause.request_ref, pause.operation_ref, "0".repeat(32), "--corrected"
  ]);
  assert.deepEqual(stale, { command: "resume", result: "stale" });
  assert.equal(calls, 1);
  corrected = true;
  const resumed = runPrivacyDeletionOutboxOperator([
    "resume", pause.request_ref, pause.operation_ref, pause.pause_id, "--corrected"
  ]);
  assert.deepEqual(resumed, { command: "resume", result: "resumed" });
  assert.deepEqual(runPrivacyDeletionOutboxOperator([
    "resume", pause.request_ref, pause.operation_ref, pause.pause_id, "--corrected"
  ]), { command: "resume", result: "stale" });
  assert.equal((await forwardAccountDeletionRequestV2(requestId, request(), fetchImpl)).kind, "ok");
  assert.equal(calls, 2);
  assert.equal(isAccountGenerationFencedV2(actorId, 1), true);
  assert.ok(!listPausedAccountDeletionOutboxV2().some(
    (candidate) => candidate.pause_id === pause.pause_id
  ));
});
