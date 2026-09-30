// S19-36-F1/F2 (w22 review): the active deletion ledger registers archived fence files and
// indexes the archived records that still hold outbox work, so a lost archive fails closed
// instead of reopening a deleted account, and the outbox drain does not decrypt the whole
// archive on every tick.
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { monitorEventLoopDelay } from "node:perf_hooks";
import { after, before, test } from "node:test";
import * as deletion from "../src/privacy-deletion-v2.js";
import type { AccountDeletionStatusV2, DeviceDeletionEvidenceV2 } from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption, decryptTestStateFile } from "./state-encryption-fixture.js";
// Namespace access keeps this file loadable against a pre-change build for the red run.
const hooks = deletion as unknown as {
  setPrivacyDeletionV2ArchiveForTests?: (options: {
    triggerBytes: number | null;
    fault?: "after_archive_write" | null;
  }) => void;
  privacyDeletionV2ArchiveReadCountForTests?: () => number;
};
const CAPABILITY = Buffer.alloc(32, 0x5a).toString("base64url");
const TOMBSTONE_ID = "8dde8fea-6919-4755-8fa9-f324299ba5aa";
const REQUEST_RECEIPT = "a".repeat(64);
const ACCEPTED_AT = "2026-08-09T00:00:00.000Z";
const ARCHIVED_FOR_DRAIN_BOUND = 1_024;
let stateDirectory = "";
before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-deletion-archive-index-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "deletion-archive-index-internal-token-1234",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: "index-actor-a", token: "index-actor-token-12345678901234567" }
    ]),
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_SESSION_SECRET: "deletion-archive-index-session-secret-12345678901234",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  hooks.setPrivacyDeletionV2ArchiveForTests?.({ triggerBytes: 1, fault: null });
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
    request_receipt_sha256: REQUEST_RECEIPT,
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
  return deletion.acceptOrReplayAccountDeletionV2(actorId, 1, {
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: requestId,
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT"
  }, CAPABILITY);
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
async function activeLedger(): Promise<Record<string, unknown>> {
  return await decryptTestStateFile(
    { kind: "privacy-deletion-v2", recordId: "ledger.json" },
    await readFile(deletion.privacyDeletionV2LedgerPathForTests(), "utf8"),
    deletion.PRIVACY_DELETION_V2_MAX_BYTES
  ) as Record<string, unknown>;
}
function evidence(requestId: string, evidenceId: string): DeviceDeletionEvidenceV2 {
  const statement = {
    schema_version: deletion.DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
    request_id: requestId,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: REQUEST_RECEIPT,
    installation_id: "index-installation-0001",
    evidence_id: evidenceId,
    client_revision: 1,
    expected_status_revision: 1,
    item: "device_untransmitted_data" as const,
    result: "DELETED" as const,
    completed_at: "2026-08-09T00:05:00Z"
  };
  return { ...statement, evidence_sha256: deletion.deviceDeletionEvidenceSha256V2(statement) };
}
async function timedDrain(fetchImpl: () => Promise<Response>): Promise<number> {
  const started = performance.now();
  await deletion.drainAccountDeletionOutboxV2(fetchImpl, Date.now());
  return performance.now() - started;
}

const integrity = (error: unknown): boolean =>
  error instanceof deletion.PrivacyDeletionV2Error && error.code === "integrity";

test("S19-36-F2 a lost archived fence fails closed instead of reopening the deleted actor", async () => {
  await complete("index-actor-a", "index_request_a_0001");
  // The next write moves the completed request (and its fence) out of the active requests.
  await complete("index-actor-b", "index_request_b_0001");
  const ledger = await activeLedger();
  assert.equal(Object.keys(ledger.requests as object).includes("index_request_a_0001"), false);
  assert.equal(deletion.isAccountGenerationFencedV2("index-actor-a", 1), true);
  await rm(path.join(path.dirname(deletion.privacyDeletionV2LedgerPathForTests()), "archive"), {
    recursive: true,
    force: true
  });
  // Pre-change builds answered false here and accepted a new deletion for the same actor.
  let fenced: boolean | "fail_closed";
  try {
    fenced = deletion.isAccountGenerationFencedV2("index-actor-a", 1);
  } catch (error) {
    assert.ok(integrity(error), String(error));
    fenced = "fail_closed";
  }
  assert.notEqual(fenced, false, "the deleted actor generation is never reported as unfenced");
  let reaccepted: string;
  try {
    reaccepted = accept("index-actor-a", "index_request_a_0002").kind;
  } catch (error) {
    assert.ok(integrity(error), String(error));
    reaccepted = "fail_closed";
  }
  assert.notEqual(reaccepted, "accepted");
  // Actors that were never archived are not affected by the lost archive.
  assert.equal(deletion.isAccountGenerationFencedV2("index-actor-never-deleted", 1), false);
  await complete("index-actor-c", "index_request_c_0001");
});

test("S19-36-F1 the outbox drain reads only indexed archived records (1024 archived regression bound)", async () => {
  for (let index = 0; index < ARCHIVED_FOR_DRAIN_BOUND; index += 1) {
    await complete(`index-bulk-${index}`, `index_bulk_request_${String(index).padStart(6, "0")}`);
  }
  const unavailable = async () => Response.json({}, { status: 503 });
  const readsBefore = hooks.privacyDeletionV2ArchiveReadCountForTests?.();
  const histogram = monitorEventLoopDelay({ resolution: 1 });
  histogram.enable();
  const samples: number[] = [];
  for (let run = 0; run < 5; run += 1) {
    samples.push(await timedDrain(unavailable));
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  histogram.disable();
  samples.sort((left, right) => left - right);
  const median = samples[2]!;
  console.log(JSON.stringify({
    probe: "s19-36-f1-drain", archived: ARCHIVED_FOR_DRAIN_BOUND,
    drain_ms_median: Math.round(median), drain_ms_max: Math.round(samples[4]!),
    event_loop_delay_max_ms: Math.round(histogram.max / 1e6)
  }));
  if (readsBefore !== undefined) {
    assert.equal(
      hooks.privacyDeletionV2ArchiveReadCountForTests!() - readsBefore,
      0,
      "no archive file is read while no archived record has outbox work"
    );
  }
  // Pre-change builds took about 0.14 ms per archived record and tick (1000 -> 140 ms).
  assert.ok(median < 100, `drain median ${median} ms with ${ARCHIVED_FOR_DRAIN_BOUND} archived`);

  // Evidence for one archived request: exactly that archive file is read by the drain.
  const target = "index_bulk_request_000007";
  const queued = deletion.queueDeviceDeletionEvidenceV2(evidence(target, "index_evidence_0001"), CAPABILITY);
  assert.equal(queued.kind, "queued");
  const readsWithWork = hooks.privacyDeletionV2ArchiveReadCountForTests?.();
  const attempted = await deletion.drainAccountDeletionOutboxV2(unavailable, Date.now());
  assert.equal(attempted.attempted, 1);
  if (readsWithWork !== undefined) {
    // Reads for the one indexed record and its own forwarding, independent of archive size.
    const reads = hooks.privacyDeletionV2ArchiveReadCountForTests!() - readsWithWork;
    console.log(JSON.stringify({ probe: "s19-36-f1-drain-one-indexed", archive_reads: reads }));
    assert.ok(reads < 64, `archive reads ${reads}`);
  }
  // After the backoff the Backend concludes the evidence (revision already moved on); the
  // record leaves the index and idle ticks read no archive file.
  const concluded = await deletion.drainAccountDeletionOutboxV2(
    async () => Response.json(completedStatus(target), { status: 409 }),
    Date.now() + 60 * 60 * 1000
  );
  assert.equal(concluded.attempted, 1);
  const readsIdle = hooks.privacyDeletionV2ArchiveReadCountForTests?.();
  const idle = await deletion.drainAccountDeletionOutboxV2(unavailable, Date.now());
  assert.equal(idle.attempted, 0);
  if (readsIdle !== undefined) {
    assert.equal(hooks.privacyDeletionV2ArchiveReadCountForTests!() - readsIdle, 0);
  }
  assert.deepEqual(deletion.listPausedAccountDeletionOutboxV2(), []);
  const index = (await activeLedger()).archive_index as {
    actor_prefixes: string;
    outbox_request_refs: string[];
  } | undefined;
  if (readsBefore !== undefined) {
    assert.ok(index);
    assert.deepEqual(index.outbox_request_refs, []);
    // 16 hex digits per archived fence: a, b, c and every completed bulk request.
    assert.equal(index.actor_prefixes.length / 16, ARCHIVED_FOR_DRAIN_BOUND + 3);
  }
});
