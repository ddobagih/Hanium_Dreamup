// S19-41: repeated revision-conflict recovery must not exhaust the device evidence budget.
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import type { GatewayFetch } from "../src/backend.js";
import {
  acceptOrReplayAccountDeletionV2,
  ACCOUNT_DELETION_INVENTORY_V2,
  DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
  deviceDeletionEvidenceSha256V2,
  forwardAccountDeletionRequestV2,
  forwardDeviceDeletionEvidenceV2,
  queueDeviceDeletionEvidenceV2,
  type AccountDeletionStatusV2,
  type DeviceDeletionEvidenceV2
} from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const CAPABILITY = Buffer.alloc(32, 0x3c).toString("base64url");
const TOMBSTONE_ID = "7cde8fea-6919-4755-8fa9-f324299ba401";
const REQUEST_RECEIPT = "b".repeat(64);
const ACCEPTED_AT = "2026-08-09T00:00:00.000Z";
const REQUEST_ID = "deletion_evidence_budget_0001";
let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-evidence-budget-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "evidence-budget-internal-token-1234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: "deletion-evidence-budget-actor", token: "evidence-budget-actor-token-123456789012" }
    ]),
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_SESSION_SECRET: "evidence-budget-session-secret-123456789012345678901234",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function status(
  revision: number,
  deviceEvidenceSha256: string | null = null,
  requestId = REQUEST_ID
): AccountDeletionStatusV2 {
  const acceptedAtMs = Date.parse(ACCEPTED_AT);
  const updatedAt = new Date(acceptedAtMs + revision * 1_000).toISOString();
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: requestId,
    client_revision: 1,
    revision,
    accepted_at: ACCEPTED_AT,
    updated_at: revision === 1 ? ACCEPTED_AT : updatedAt,
    account_generation: 1,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: REQUEST_RECEIPT,
    overall_status: "PARTIAL",
    items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => {
      const deviceCompleted = definition.key === "device_untransmitted_data" &&
        deviceEvidenceSha256 !== null;
      const external = [
        "device_untransmitted_data", "training_datasets", "training_labels",
        "derived_artifacts", "backups"
      ].includes(definition.key);
      return {
        key: definition.key,
        status: deviceCompleted
          ? "COMPLETED" as const
          : external ? "EXTERNAL_PENDING" as const : "PENDING" as const,
        item_revision: deviceCompleted ? 2 : 1,
        due_at: new Date(acceptedAtMs + definition.dueAfterMs).toISOString(),
        updated_at: deviceCompleted ? updatedAt : ACCEPTED_AT,
        evidence_sha256: deviceCompleted ? deviceEvidenceSha256 : null,
        disposition_basis: null,
        retry_after: null,
        restriction_reason: null,
        legal_hold_review_at: null,
        legal_hold_contact: null,
        terminal_at: deviceCompleted ? updatedAt : null
      };
    }),
    completion_receipt_sha256: null
  };
}

function evidence(
  installationId: string,
  evidenceId: string,
  revision: number,
  requestId = REQUEST_ID
): DeviceDeletionEvidenceV2 {
  const statement = {
    schema_version: DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
    request_id: requestId,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: REQUEST_RECEIPT,
    installation_id: installationId,
    evidence_id: evidenceId,
    client_revision: 1,
    expected_status_revision: revision,
    item: "device_untransmitted_data" as const,
    result: "DELETED" as const,
    completed_at: "2026-08-09T00:05:00Z"
  };
  return { ...statement, evidence_sha256: deviceDeletionEvidenceSha256V2(statement) };
}

test("S19-41 100 revision conflicts on one device still admit the last valid evidence", async () => {
  const accepted = acceptOrReplayAccountDeletionV2(
    "deletion-evidence-budget-actor",
    1,
    {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: REQUEST_ID,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    CAPABILITY
  );
  assert.equal(accepted.kind, "accepted");
  const forwarded = await forwardAccountDeletionRequestV2(
    REQUEST_ID,
    new Request("http://127.0.0.1/privacy/account-deletions"),
    async () => Response.json(status(1), { status: 202 })
  );
  assert.equal(forwarded.kind, "ok");

  let revision = 1;
  // Each submission races a Backend status change: 409 with the newer canonical status.
  const conflicting: GatewayFetch = async () =>
    Response.json(status(revision + 1), { status: 409 });
  const request = new Request("http://127.0.0.1/privacy/account-deletions/device-evidence");
  const now = Date.parse("2026-08-09T00:06:00Z");
  for (let attempt = 1; attempt <= 100; attempt += 1) {
    const submitted = evidence("installation-budget-a", `evidence-budget-a-${attempt}`, revision);
    const queued = queueDeviceDeletionEvidenceV2(submitted, CAPABILITY, now);
    assert.equal(queued.kind, "queued", `attempt ${attempt}`);
    const result = await forwardDeviceDeletionEvidenceV2(
      REQUEST_ID, submitted.evidence_id, request, conflicting
    );
    assert.equal(result.kind, "conflict", `attempt ${attempt}`);
    revision += 1;
  }

  // The superseded history still replays exactly.
  const replayed = queueDeviceDeletionEvidenceV2(
    evidence("installation-budget-a", "evidence-budget-a-100", 100), CAPABILITY, now
  );
  assert.equal(replayed.kind, "superseded");
  const firstReplay = queueDeviceDeletionEvidenceV2(
    evidence("installation-budget-a", "evidence-budget-a-1", 1), CAPABILITY, now
  );
  assert.equal(firstReplay.kind, "superseded");

  // The last valid evidence for the current revision is admitted and acknowledged.
  const last = evidence("installation-budget-a", "evidence-budget-a-final", revision);
  assert.equal(queueDeviceDeletionEvidenceV2(last, CAPABILITY, now).kind, "queued");
  const acknowledged = await forwardDeviceDeletionEvidenceV2(
    REQUEST_ID,
    last.evidence_id,
    request,
    async () => Response.json(status(revision + 1, last.evidence_sha256), { status: 200 })
  );
  assert.equal(acknowledged.kind, "ok");
  revision += 1;
  // An identical retransmission is idempotent, and another device stays independent.
  assert.equal(queueDeviceDeletionEvidenceV2(last, CAPABILITY, now).kind, "replay");
  const otherDevice = evidence("installation-budget-b", "evidence-budget-b-1", revision);
  assert.equal(queueDeviceDeletionEvidenceV2(otherDevice, CAPABILITY, now).kind, "queued");
});

test("S19-41 superseded history is bounded: an evicted id is refused as a conflict, never re-queued", async () => {
  const requestId = "deletion_evidence_budget_0002";
  const accepted = acceptOrReplayAccountDeletionV2(
    "deletion-evidence-budget-actor-2",
    1,
    {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    CAPABILITY
  );
  assert.equal(accepted.kind, "accepted");
  assert.equal((await forwardAccountDeletionRequestV2(
    requestId,
    new Request("http://127.0.0.1/privacy/account-deletions"),
    async () => Response.json(status(1, null, requestId), { status: 202 })
  )).kind, "ok");
  let revision = 1;
  const request = new Request("http://127.0.0.1/privacy/account-deletions/device-evidence");
  const now = Date.parse("2026-08-09T00:06:00Z");
  const total = 256 + 4;
  for (let attempt = 1; attempt <= total; attempt += 1) {
    const submitted = evidence("installation-bound-a", `evidence-bound-a-${attempt}`, revision, requestId);
    assert.equal(queueDeviceDeletionEvidenceV2(submitted, CAPABILITY, now).kind, "queued");
    const result = await forwardDeviceDeletionEvidenceV2(
      requestId, submitted.evidence_id, request,
      async () => Response.json(status(revision + 1, null, requestId), { status: 409 })
    );
    assert.equal(result.kind, "conflict");
    revision += 1;
  }
  // The four oldest ids were evicted; their stale expected revision is refused.
  assert.equal(queueDeviceDeletionEvidenceV2(
    evidence("installation-bound-a", "evidence-bound-a-1", 1, requestId), CAPABILITY, now
  ).kind, "conflict");
  assert.equal(queueDeviceDeletionEvidenceV2(
    evidence("installation-bound-a", "evidence-bound-a-5", 5, requestId), CAPABILITY, now
  ).kind, "superseded");
  assert.equal(queueDeviceDeletionEvidenceV2(
    evidence("installation-bound-a", `evidence-bound-a-${total}`, total, requestId), CAPABILITY, now
  ).kind, "superseded");
  const last = evidence("installation-bound-a", "evidence-bound-a-final", revision, requestId);
  assert.equal(queueDeviceDeletionEvidenceV2(last, CAPABILITY, now).kind, "queued");
});

test("S19-41-F1 eviction follows submission order, also for all-digit evidence ids", async () => {
  const requestId = "deletion_evidence_budget_0003";
  const accepted = acceptOrReplayAccountDeletionV2(
    "deletion-evidence-budget-actor-3",
    1,
    {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    CAPABILITY
  );
  assert.equal(accepted.kind, "accepted");
  assert.equal((await forwardAccountDeletionRequestV2(
    requestId,
    new Request("http://127.0.0.1/privacy/account-deletions"),
    async () => Response.json(status(1, null, requestId), { status: 202 })
  )).kind, "ok");
  let revision = 1;
  const request = new Request("http://127.0.0.1/privacy/account-deletions/device-evidence");
  const now = Date.parse("2026-08-09T00:06:00Z");
  const total = 256 + 3;
  // Every 16th id is all digits: JavaScript orders such keys first after a JSON round trip.
  const idOf = (attempt: number): string =>
    attempt % 16 === 0 ? String(10_000_000 + attempt) : `evidence-order-a-${attempt}`;
  for (let attempt = 1; attempt <= total; attempt += 1) {
    const submitted = evidence("installation-order-a", idOf(attempt), revision, requestId);
    assert.equal(queueDeviceDeletionEvidenceV2(submitted, CAPABILITY, now).kind, "queued", `attempt ${attempt}`);
    const result = await forwardDeviceDeletionEvidenceV2(
      requestId, submitted.evidence_id, request,
      async () => Response.json(status(revision + 1, null, requestId), { status: 409 })
    );
    assert.equal(result.kind, "conflict", `attempt ${attempt}`);
    revision += 1;
  }
  // The three oldest submissions were evicted; the all-digit ids (not oldest) stay.
  for (const attempt of [1, 2, 3]) {
    assert.equal(queueDeviceDeletionEvidenceV2(
      evidence("installation-order-a", idOf(attempt), attempt, requestId), CAPABILITY, now
    ).kind, "conflict", `evicted ${attempt}`);
  }
  for (const attempt of [4, 16, 32, 256, total]) {
    assert.equal(queueDeviceDeletionEvidenceV2(
      evidence("installation-order-a", idOf(attempt), attempt, requestId), CAPABILITY, now
    ).kind, "superseded", `kept ${attempt}`);
  }
});

test("S19-41 repeated acknowledged evidence from one device counts once toward the active budget", async () => {
  const requestId = "deletion_evidence_budget_0004";
  assert.equal(acceptOrReplayAccountDeletionV2(
    "deletion-evidence-budget-actor-4",
    1,
    {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    CAPABILITY
  ).kind, "accepted");
  assert.equal((await forwardAccountDeletionRequestV2(
    requestId,
    new Request("http://127.0.0.1/privacy/account-deletions"),
    async () => Response.json(status(1, null, requestId), { status: 202 })
  )).kind, "ok");
  let revision = 1;
  const request = new Request("http://127.0.0.1/privacy/account-deletions/device-evidence");
  const now = Date.parse("2026-08-09T00:06:00Z");
  // The device's item fails again and it resubmits new evidence, which is acknowledged,
  // 100 times; the former ceiling refused the 65th.
  for (let attempt = 1; attempt <= 100; attempt += 1) {
    const submitted = evidence("installation-ack-a", `evidence-ack-a-${attempt}`, revision, requestId);
    assert.equal(queueDeviceDeletionEvidenceV2(submitted, CAPABILITY, now).kind, "queued", `attempt ${attempt}`);
    const result = await forwardDeviceDeletionEvidenceV2(
      requestId, submitted.evidence_id, request,
      async () => Response.json(status(revision + 1, submitted.evidence_sha256, requestId), { status: 200 })
    );
    assert.equal(result.kind, "ok", `attempt ${attempt}`);
    revision += 1;
  }
  // Older acknowledged evidence stays replayable (idempotent), the newest too.
  assert.equal(queueDeviceDeletionEvidenceV2(
    evidence("installation-ack-a", "evidence-ack-a-100", 100, requestId), CAPABILITY, now
  ).kind, "replay");
  assert.equal(queueDeviceDeletionEvidenceV2(
    evidence("installation-ack-a", "evidence-ack-a-50", 50, requestId), CAPABILITY, now
  ).kind, "replay");
  // Another device and the same device can still submit.
  assert.equal(queueDeviceDeletionEvidenceV2(
    evidence("installation-ack-b", "evidence-ack-b-1", revision, requestId), CAPABILITY, now
  ).kind, "queued");
});
