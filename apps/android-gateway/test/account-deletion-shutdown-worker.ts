import { writeSync } from "node:fs";

import {
  acceptOrReplayAccountDeletionV2,
  ACCOUNT_DELETION_INVENTORY_V2
} from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const mode = process.argv[2];
const held: Array<() => void> = [];
await configureTestStateEncryption(process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE!);
if (mode !== "recovery") {
  for (let index = 0; index < 4; index += 1) {
    acceptOrReplayAccountDeletionV2(`shutdown-actor-${index}`, 1, {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: `shutdown_request_${index.toString().padStart(4, "0")}`,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    }, Buffer.alloc(32, index + 1).toString("base64url"));
  }
}

function accepted(requestId: string): Response {
  const acceptedAt = "2026-09-25T00:00:00.000Z";
  return Response.json({
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: requestId, client_revision: 1, revision: 1,
    accepted_at: acceptedAt, updated_at: acceptedAt,
    account_generation: 1, tombstone_id: `tombstone_${requestId}`,
    request_receipt_sha256: "a".repeat(64), overall_status: "PROCESSING",
    completion_receipt_sha256: null,
    items: ACCOUNT_DELETION_INVENTORY_V2.map((item) => ({
      key: item.key, status: "PENDING", item_revision: 1,
      due_at: new Date(Date.parse(acceptedAt) + item.dueAfterMs).toISOString(),
      updated_at: acceptedAt, evidence_sha256: null, disposition_basis: null,
      retry_after: null, restriction_reason: null, legal_hold_review_at: null,
      legal_hold_contact: null, terminal_at: null
    }))
  }, { status: 202 });
}

globalThis.fetch = async (input, init) => {
  if (String(input) !== "http://127.0.0.1:8000/privacy/account-deletions") {
    throw new Error("unexpected shutdown fixture upstream");
  }
  const requestId = (JSON.parse(String(init?.body)) as { request_id: string }).request_id;
  writeSync(1, `FETCH ${requestId}\n`);
  init?.signal?.addEventListener("abort", () => writeSync(1, `ABORT ${requestId}\n`), { once: true });
  if (mode === "recovery") return accepted(requestId);
  // Keep the promise unsettled after cancellation to model a slow final response.
  return new Promise<Response>((resolve) => {
    held.push(() => resolve(mode === "success" ? accepted(requestId) : new Response(null, { status: 503 })));
  });
};
process.on("message", (message) => {
  if (message !== "release") return;
  for (const resolve of held) resolve();
  setImmediate(() => process.disconnect());
});
if (mode === "recovery") {
  process.once("SIGTERM", () => setImmediate(() => process.disconnect()));
}
await import("../server.js");
