import { writeSync } from "node:fs";

import type { GatewayFetch } from "../src/backend.js";
import {
  acceptOrReplayAccountDeletionV2,
  ACCOUNT_DELETION_INVENTORY_V2,
  authorizedAccountDeletionStatusV2,
  drainAccountDeletionOutboxV2,
  type AccountDeletionStatusV2
} from "../src/privacy-deletion-v2.js";

const ACTOR = "deletion-crash-actor";
const REQUEST_ID = "deletion_crash_request_0001";
const CAPABILITY = Buffer.alloc(32, 0x6b).toString("base64url");
const ACCEPTED_AT = "2026-08-09T00:00:00.000Z";

function statusFixture(): AccountDeletionStatusV2 {
  const acceptedAtMs = Date.parse(ACCEPTED_AT);
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: REQUEST_ID,
    client_revision: 1,
    revision: 1,
    accepted_at: ACCEPTED_AT,
    updated_at: ACCEPTED_AT,
    account_generation: 1,
    tombstone_id: "deletion-crash-tombstone-0001",
    request_receipt_sha256: "a".repeat(64),
    overall_status: "PARTIAL",
    items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => ({
      key: definition.key,
      status: [
        "device_untransmitted_data",
        "training_datasets",
        "training_labels",
        "derived_artifacts",
        "backups"
      ].includes(definition.key) ? "EXTERNAL_PENDING" : "PENDING",
      item_revision: 1,
      due_at: new Date(acceptedAtMs + definition.dueAfterMs).toISOString(),
      updated_at: ACCEPTED_AT,
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

const phase = process.argv[2];
if (phase === "enqueue-crash") {
  const accepted = acceptOrReplayAccountDeletionV2(
    ACTOR,
    1,
    {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: REQUEST_ID,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    CAPABILITY
  );
  if (accepted.kind !== "accepted") process.exit(2);
  writeSync(1, "QUEUED\n");
  process.kill(process.pid, "SIGKILL");
}

if (phase === "drain") {
  const fetchImpl: GatewayFetch = async () =>
    Response.json(statusFixture(), { status: 202 });
  const drained = await drainAccountDeletionOutboxV2(
    fetchImpl,
    Date.now() + 60_000
  );
  const status = authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY);
  if (!status) process.exit(3);
  writeSync(1, `${JSON.stringify({ drained, revision: status.revision })}\n`);
  process.exit(0);
}

if (phase === "verify") {
  let backendCalls = 0;
  const drained = await drainAccountDeletionOutboxV2(async () => {
    backendCalls += 1;
    throw new Error("acknowledged outbox operation must not be replayed");
  }, Date.now() + 24 * 60 * 60 * 1_000);
  const status = authorizedAccountDeletionStatusV2(REQUEST_ID, CAPABILITY);
  if (!status) process.exit(4);
  writeSync(1, `${JSON.stringify({ drained, backendCalls, revision: status.revision })}\n`);
  process.exit(0);
}

process.exit(64);
