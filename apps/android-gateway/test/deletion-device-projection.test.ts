import assert from "node:assert/strict";
import { generateKeyPairSync, sign, verify } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import path from "node:path";
import { tmpdir } from "node:os";
import { type AddressInfo } from "node:net";
import { after, before, test } from "node:test";
import { createGatewayServer } from "../src/node-adapter.js";
import { ACCOUNT_DELETION_INVENTORY_V2, parseAccountDeletionStatusV2, parseAccountDeletionStatusViewV2,
  deviceDeletionEvidenceSha256V2, deviceDeletionEvidenceBodyV2, parseDeviceDeletionEvidenceV2,
  type AccountDeletionStatusV2, type AccountDeletionDeviceVerificationV1 } from "../src/privacy-deletion-codec.js";
import { acceptOrReplayAccountDeletionV2, forwardAccountDeletionRequestV2,
  DELETION_ACCESS_SECRET_HEADER, privacyDeletionV2LedgerPathForTests } from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const actor = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3051";
const requestId = "deletion_device_view_request_0001";
const capability = Buffer.alloc(32, 0x61).toString("base64url");
const acceptedAt = "2026-10-11T00:00:00.000Z";
const canonical: AccountDeletionStatusV2 = {
  schema_version: "walksafe.account-deletion-status.v2", request_id: requestId,
  client_revision: 1, revision: 1, accepted_at: acceptedAt, updated_at: acceptedAt,
  account_generation: 7, tombstone_id: "deletion_device_view_tombstone_0001",
  request_receipt_sha256: "a".repeat(64), overall_status: "PROCESSING",
  items: ACCOUNT_DELETION_INVENTORY_V2.map(item => ({ key: item.key, status: "PENDING",
    item_revision: 1, due_at: new Date(Date.parse(acceptedAt) + item.dueAfterMs).toISOString(),
    updated_at: acceptedAt, evidence_sha256: null, disposition_basis: null, retry_after: null,
    restriction_reason: null, legal_hold_review_at: null, legal_hold_contact: null, terminal_at: null })),
  completion_receipt_sha256: null
};
const projection = (evaluatedAt: string): AccountDeletionDeviceVerificationV1 => ({
  schema_version: "walksafe.account-deletion-device-verification.v1", completion_view: "IN_PROGRESS",
  target_count: 1, deleted_count: 0, not_found_count: 0, failed_count: 0, pending_count: 1,
  unverifiable_count: 0, unverifiable_reason: null, follow_up: null, device_clock_anomaly_count: 0,
  device_due_at: canonical.items[0]!.due_at, evaluated_at: evaluatedAt
});
let directory = "";
before(async () => {
  directory = await mkdtemp(path.join(tmpdir(), "walksafe-deletion-device-view-"));
  Object.assign(process.env, { NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_FIELD_TEST_TOKEN: "deletion-device-view-internal-token-1234567890123",
    WALKSAFE_GATEWAY_SESSION_SECRET: "deletion-device-view-session-secret-1234567890123456789012",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json") });
  await configureTestStateEncryption(path.join(directory, "keyring.json"));
});
after(async () => { await rm(directory, { recursive: true, force: true }); });

test("device projection parser keeps canonical exact keys and rejects inconsistent counts/views", () => {
  const status = { ...canonical, device_verification: projection("2026-10-11T00:01:00Z") };
  assert.ok(parseAccountDeletionStatusViewV2(status));
  assert.equal(parseAccountDeletionStatusV2(status), null);
  assert.equal(parseAccountDeletionStatusViewV2({ ...status, unknown: true }), null);
  for (const delta of [{ target_count: 2 }, { pending_count: -1 }, { completion_view: "COMPLETED" },
    { pending_count: 0, unverifiable_count: 1 }, { evaluated_at: "not-a-date" }, { extra: "never-store" }]) {
    assert.equal(parseAccountDeletionStatusViewV2({ ...status, device_verification: { ...status.device_verification, ...delta } }), null);
  }
});

test("P-256 evidence signature survives parsing/outbox serialization without altering canonical SHA", () => {
  const statement = { schema_version: "walksafe.device-deletion-evidence.v2" as const,
    request_id: requestId, tombstone_id: canonical.tombstone_id, request_receipt_sha256: canonical.request_receipt_sha256,
    installation_id: "deletion_signature_installation_0001", evidence_id: "deletion_signature_evidence_0001",
    client_revision: 1, expected_status_revision: 1, item: "device_untransmitted_data" as const,
    result: "DELETED" as const, completed_at: "2026-10-11T00:01:00Z" };
  const evidence_sha256 = deviceDeletionEvidenceSha256V2(statement);
  const keys = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const message = Buffer.from(`walksafe/device-deletion-evidence-signature/v1\0${evidence_sha256}`, "ascii");
  const installation_signature = sign("sha256", message, keys.privateKey).toString("base64url");
  const parsed = parseDeviceDeletionEvidenceV2({ ...statement, evidence_sha256, installation_signature });
  assert.ok(parsed); assert.equal(deviceDeletionEvidenceSha256V2(parsed), evidence_sha256);
  const forwarded = JSON.parse(deviceDeletionEvidenceBodyV2(parsed));
  assert.equal(forwarded.installation_signature, installation_signature);
  assert.equal(verify("sha256", message, keys.publicKey, Buffer.from(forwarded.installation_signature, "base64url")), true);
  assert.equal(parseDeviceDeletionEvidenceV2({ ...parsed, installation_signature: `${installation_signature}=` }), null);
  assert.equal(parseDeviceDeletionEvidenceV2({ ...parsed, installation_signature: null }), null);
  assert.equal(parseDeviceDeletionEvidenceV2({ ...parsed, result: "FAILED" }), null);
});

test("HTTP status device view changes at the same canonical revision without touching the encrypted ledger", async () => {
  assert.equal(acceptOrReplayAccountDeletionV2(actor, 7, {
    schema_version: "walksafe.account-deletion-request.v2", request_id: requestId,
    client_revision: 1, confirmation: "DELETE_MY_ACCOUNT"
  }, capability).kind, "accepted");
  const accepted = await forwardAccountDeletionRequestV2(requestId, new Request("http://gateway.invalid/privacy/account-deletions"),
    async () => Response.json(canonical, { status: 202 }));
  assert.equal(accepted.kind, "ok");
  const ledgerBefore = await readFile(privacyDeletionV2LedgerPathForTests(), "utf8");
  let viewRequests = 0;
  let ordinaryRequests = 0;
  const server = createGatewayServer({ fetchImpl: async (input, init) => {
    assert.equal(new URL(String(input)).pathname, `/privacy/account-deletions/${requestId}/status`);
    assert.equal(init?.method, "GET");
    const requested = new Headers(init?.headers).get("x-walksafe-deletion-status-view");
    if (requested) {
      assert.equal(requested, "device-verification.v1"); viewRequests += 1;
      return Response.json({ ...canonical, device_verification: projection(`2026-10-11T00:0${viewRequests}:00Z`) });
    }
    ordinaryRequests += 1; return Response.json(canonical);
  } });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/privacy/account-deletions/${requestId}/status`;
  try {
    for (let index = 0; index < 2; index += 1) {
      const response = await fetch(url, { headers: { [DELETION_ACCESS_SECRET_HEADER]: capability,
        "x-walksafe-deletion-status-view": "device-verification.v1" } });
      assert.equal(response.status, 200); assert.equal(response.headers.get("cache-control"), "no-store");
      const body = await response.json() as Record<string, unknown>;
      assert.ok(parseAccountDeletionStatusViewV2(body)); assert.equal(body.revision, 1);
      assert.deepEqual(body.device_verification, projection(`2026-10-11T00:0${index + 1}:00Z`));
      assert.equal(await readFile(privacyDeletionV2LedgerPathForTests(), "utf8"), ledgerBefore);
    }
    const ordinary = await fetch(url, { headers: { [DELETION_ACCESS_SECRET_HEADER]: capability } });
    assert.equal(ordinary.status, 200); assert.deepEqual(await ordinary.json(), canonical);
    const wrongCapability = await fetch(url, { headers: { [DELETION_ACCESS_SECRET_HEADER]: Buffer.alloc(32, 0x62).toString("base64url"),
      "x-walksafe-deletion-status-view": "device-verification.v1" } });
    assert.equal(wrongCapability.status, 404); await wrongCapability.arrayBuffer();
    assert.equal(viewRequests, 2); assert.equal(ordinaryRequests, 1);
  } finally {
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
