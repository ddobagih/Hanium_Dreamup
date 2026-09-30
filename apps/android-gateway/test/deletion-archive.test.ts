// S19-36: completed deletion requests leave the 4 MiB active ledger crash-safely.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";

import * as deletion from "../src/privacy-deletion-v2.js";
import type { AccountDeletionStatusV2 } from "../src/privacy-deletion-v2.js";
import {
  countLegacyDeletionCapabilities,
  runGatewayStateMaintenance
} from "../src/state-encryption-maintenance.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile,
  TEST_ACTIVE_KEY_ID
} from "./state-encryption-fixture.js";

const hooks = deletion as unknown as {
  setPrivacyDeletionV2ArchiveForTests?: (options: {
    triggerBytes: number | null;
    fault?: "after_archive_write" | null;
  }) => void;
};

const CAPABILITY = Buffer.alloc(32, 0x71).toString("base64url");
const TOMBSTONE_ID = "8dde8fea-6919-4755-8fa9-f324299ba5aa";
const ACCEPTED_AT = "2026-08-09T00:00:00.000Z";
let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-deletion-archive-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "deletion-archive-internal-token-123456789",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: "archive-actor-a", token: "archive-actor-token-1234567890123456" }
    ]),
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_SESSION_SECRET: "deletion-archive-session-secret-1234567890123456789012",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  hooks.setPrivacyDeletionV2ArchiveForTests?.({ triggerBytes: null, fault: null });
  await rm(stateDirectory, { recursive: true, force: true });
});

function completedStatus(requestId: string): AccountDeletionStatusV2 {
  const acceptedAtMs = Date.parse(ACCEPTED_AT);
  const terminalAt = "2026-08-09T00:10:00.000Z";
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: requestId,
    client_revision: 1,
    revision: 1,
    accepted_at: ACCEPTED_AT,
    updated_at: terminalAt,
    account_generation: 1,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: "a".repeat(64),
    overall_status: "COMPLETED",
    items: deletion.ACCOUNT_DELETION_INVENTORY_V2.map((definition) => ({
      key: definition.key,
      status: "COMPLETED",
      item_revision: 2,
      due_at: new Date(acceptedAtMs + definition.dueAfterMs).toISOString(),
      updated_at: terminalAt,
      evidence_sha256: "e".repeat(64),
      disposition_basis: null,
      retry_after: null,
      restriction_reason: null,
      legal_hold_review_at: null,
      legal_hold_contact: null,
      terminal_at: terminalAt
    })),
    completion_receipt_sha256: "c".repeat(64)
  } as AccountDeletionStatusV2;
}

function accept(actorId: string, requestId: string) {
  return deletion.acceptOrReplayAccountDeletionV2(
    actorId,
    1,
    {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    CAPABILITY
  );
}

async function complete(actorId: string, requestId: string): Promise<void> {
  assert.equal(accept(actorId, requestId).kind, "accepted");
  const forwarded = await deletion.forwardAccountDeletionRequestV2(
    requestId,
    new Request("http://127.0.0.1/privacy/account-deletions"),
    async () => Response.json(completedStatus(requestId), { status: 202 })
  );
  assert.equal(forwarded.kind, "ok");
}

async function activeLedger(): Promise<{ requests: Record<string, unknown>; actors: Record<string, unknown> }> {
  return decryptTestStateFile(
    { kind: "privacy-deletion-v2", recordId: "ledger.json" },
    await readFile(deletion.privacyDeletionV2LedgerPathForTests(), "utf8"),
    deletion.PRIVACY_DELETION_V2_MAX_BYTES
  );
}

async function archiveCount(kind: "requests" | "actors"): Promise<number> {
  try {
    const entries = await readdir(
      path.join(path.dirname(deletion.privacyDeletionV2LedgerPathForTests()), "archive", kind)
    );
    return entries.filter((name) => name.endsWith(".json")).length;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return 0;
    throw error;
  }
}

test("S19-36 completed requests move to the archive crash-safely and keep fences and status", async () => {
  await complete("archive-actor-a", "archive_request_a_0001");
  await complete("archive-actor-b", "archive_request_b_0001");
  assert.deepEqual(Object.keys((await activeLedger()).requests).sort(), [
    "archive_request_a_0001", "archive_request_b_0001"
  ]);

  // A crash after the archive writes but before the active ledger write.
  hooks.setPrivacyDeletionV2ArchiveForTests?.({ triggerBytes: 1, fault: "after_archive_write" });
  assert.throws(() => accept("archive-actor-c", "archive_request_c_0001"), /injected privacy deletion archive fault/);
  assert.equal(Object.keys((await activeLedger()).requests).length, 2, "active ledger is unchanged");
  assert.equal(await archiveCount("requests"), 2);
  assert.equal(await archiveCount("actors"), 2);
  // Both copies coexist consistently: status and fences read the same.
  assert.equal(
    deletion.authorizedAccountDeletionStatusV2("archive_request_a_0001", CAPABILITY)?.overall_status,
    "COMPLETED"
  );
  assert.equal(deletion.isAccountGenerationFencedV2("archive-actor-a", 1), true);

  // Recovery: the next write finishes the move.
  hooks.setPrivacyDeletionV2ArchiveForTests?.({ triggerBytes: 1, fault: null });
  assert.equal(accept("archive-actor-c", "archive_request_c_0001").kind, "accepted");
  const after = await activeLedger();
  assert.deepEqual(Object.keys(after.requests), ["archive_request_c_0001"]);
  assert.equal(Object.keys(after.actors).length, 1);

  // Archived requests stay queryable, replayable, and keep blocking re-acceptance.
  assert.equal(
    deletion.authorizedAccountDeletionStatusV2("archive_request_b_0001", CAPABILITY)?.overall_status,
    "COMPLETED"
  );
  assert.equal(accept("archive-actor-a", "archive_request_a_0001").kind, "replay");
  assert.equal(deletion.isAccountGenerationFencedV2("archive-actor-a", 1), true);
  assert.notEqual(accept("archive-actor-a", "archive_request_a_0002").kind, "accepted");
  // A pending request is never archived.
  assert.equal(accept("archive-actor-d", "archive_request_d_0001").kind, "accepted");
  assert.ok(Object.keys((await activeLedger()).requests).includes("archive_request_c_0001"));
});

test("S19-36 archived files are covered by state-encryption maintenance", async () => {
  const results = await runGatewayStateMaintenance("inspect", {
    ...process.env,
    WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED: "true"
  });
  const archived = results.filter((result) =>
    result.file.includes(`${path.sep}archive${path.sep}`)
  );
  assert.equal(archived.length, 4, JSON.stringify(results.map((result) => result.file)));
  assert.ok(archived.every((result) => result.statusBefore === "encrypted-active"));
});

test("gw-F5 S19-35 archived legacy capabilities are counted and inspect keeps its report under a compromised key", async () => {
  const maintenanceEnvironment = { ...process.env, WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED: "true" };
  const archiveRequests = path.join(path.dirname(deletion.privacyDeletionV2LedgerPathForTests()), "archive", "requests");
  const archivedFile = path.join(
    archiveRequests,
    (await readdir(archiveRequests)).filter((name) => name.endsWith(".json")).sort()[0]!
  );
  // An archived request created before capability keys: its capability is still verified
  // with the session secret, so it must block session secret retirement like an active one.
  const context = { kind: "privacy-deletion-v2", recordId: path.basename(archivedFile) } as const;
  const archivedState = decryptTestStateFile<{ record: Record<string, unknown> }>(
    context, await readFile(archivedFile, "utf8"), deletion.PRIVACY_DELETION_V2_MAX_BYTES
  );
  assert.ok(Object.hasOwn(archivedState.record, "capability_key"));
  delete archivedState.record.capability_key;
  await writeFile(
    archivedFile,
    encryptTestStateFile(context, archivedState, deletion.PRIVACY_DELETION_V2_MAX_BYTES),
    { mode: 0o600 }
  );
  assert.deepEqual(await countLegacyDeletionCapabilities(maintenanceEnvironment), {
    active: 0, archived: 1, total: 1
  });
  const cli = (command: string) => spawnSync(process.execPath, [
    fileURLToPath(new URL("../src/state-encryption-maintenance.js", import.meta.url)),
    command
  ], { encoding: "utf8", env: maintenanceEnvironment, timeout: 30_000 });
  const blocked = cli("check-session-secret-retirement");
  assert.equal(blocked.status, 3, blocked.stderr);
  assert.deepEqual(JSON.parse(blocked.stdout).legacy_deletion_capabilities, { active: 0, archived: 1, total: 1 });
  const healthy = cli("inspect");
  assert.equal(healthy.status, 0, healthy.stderr);
  const healthyReport = JSON.parse(healthy.stdout) as Record<string, unknown>;
  assert.deepEqual(healthyReport.legacy_deletion_capabilities, { active: 0, archived: 1, total: 1 });

  // The deletion states' key becomes compromised: the per-file report is still written and
  // names them; the legacy count that cannot be taken is reported by its error code.
  const keyringPath = path.join(stateDirectory, "state-keyring.json");
  await configureTestStateEncryption(keyringPath, [
    { keyId: "archive-new-key", status: "active", fillByte: 0x42 },
    { keyId: TEST_ACTIVE_KEY_ID, status: "compromised", fillByte: 0x41 }
  ]);
  try {
    const compromised = cli("inspect");
    assert.equal(compromised.status, 2, compromised.stderr);
    const report = JSON.parse(compromised.stdout) as {
      results: Array<{ file: string; statusBefore: string }>;
      legacy_deletion_capabilities: unknown;
    };
    const deletionFiles = report.results.filter((result) =>
      result.file.includes(`${path.sep}privacy-deletion-v2${path.sep}`));
    assert.ok(deletionFiles.length >= 5, JSON.stringify(deletionFiles));
    assert.ok(deletionFiles.every((result) => result.statusBefore === "encrypted-compromised"));
    assert.deepEqual(report.legacy_deletion_capabilities, { error: "compromised_key" });
  } finally {
    await configureTestStateEncryption(keyringPath);
  }
});
