// S19-27: every Gateway store separates "not committed" from "committed, outcome unknown"
// with the common GatewayStorageOutcomeUnknownError, thrown only after the atomic rename.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { lstat, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";

import { setShortSessionStorageFaultForTests } from "../src/auth.js";
import {
  GatewayStorageOutcomeUnknownError,
  resetGatewayStateEncryptionForTests,
  setGatewayStateCommitFaultForTests
} from "../src/encrypted-json-store.js";
import { FieldWalkLedgerStorageOutcomeUnknownError } from "../src/field-walk-ledger.js";
import { fieldSessionErrorContractViolation } from "../src/field-session-errors.js";
import { acceptOrReplayAccountDeletionV2 } from "../src/privacy-deletion-v2.js";
import { activateActorGeneration, currentActorGeneration } from "../src/privacy-rights.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR_ID = "field-operator";
const ACCOUNT_TOKEN = "field-account-token-12345678901234567890";
let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-outcome-unknown-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:9",
    WALKSAFE_FIELD_TEST_TOKEN: "field-internal-token-12345678901234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "gateway-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(`${stateDirectory}-state-keyring.json`);
});

afterEach(() => {
  setGatewayStateCommitFaultForTests(null);
  setShortSessionStorageFaultForTests(null);
});

after(async () => {
  resetGatewayStateEncryptionForTests();
  await rm(stateDirectory, { recursive: true, force: true });
  await rm(`${stateDirectory}-state-keyring.json`, { force: true });
});

test("S19-27 the older store-specific outcome errors are the common type", () => {
  assert.ok(new FieldWalkLedgerStorageOutcomeUnknownError(new Error("x")) instanceof GatewayStorageOutcomeUnknownError);
  assert.equal(new GatewayStorageOutcomeUnknownError("example").committed, "unknown");
});

test("S19-27 a short-session login whose rename happened answers outcome unknown, not unavailable", async () => {
  setShortSessionStorageFaultForTests("directory_fsync");
  const response = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.77" },
    body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
  }));
  setShortSessionStorageFaultForTests(null);
  assert.equal(response.status, 500);
  assert.equal(response.headers.get("set-cookie"), null);
  const body = await response.json() as Record<string, unknown>;
  assert.equal(body.code, "field_session_storage_outcome_unknown");
  assert.equal(fieldSessionErrorContractViolation(500, body), null);
  // The session state was renamed into place: it is the committed-unknown case.
  const digest = createHash("sha256").update(`field\0${ACTOR_ID}`).digest("hex");
  await lstat(path.join(stateDirectory, "sessions", `field-${digest}.json`));
  // A new login succeeds normally.
  const retried = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.78" },
    body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
  }));
  assert.equal(retried.status, 200);
});

test("S19-27 privacy-rights ledger: a post-rename failure is outcome unknown and the change is durable", () => {
  const actor = "s19-27-privacy-rights-actor";
  setGatewayStateCommitFaultForTests("privacy-rights-ledger");
  assert.throws(() => activateActorGeneration(actor), (error: unknown) =>
    error instanceof GatewayStorageOutcomeUnknownError && error.store === "privacy-rights-ledger");
  setGatewayStateCommitFaultForTests(null);
  // Re-reading the state resolves the unknown outcome: the generation was committed.
  assert.equal(currentActorGeneration(actor), 1);
  assert.equal(activateActorGeneration(actor), 1);
});

test("S19-27 deletion ledger: a post-rename failure is outcome unknown and the same request replays", () => {
  const actor = "s19-27-deletion-actor";
  const capability = Buffer.alloc(32, 0x5a).toString("base64url");
  const request = {
    schema_version: "walksafe.account-deletion-request.v2" as const,
    request_id: "s19_27_deletion_request_0001",
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT" as const
  };
  setGatewayStateCommitFaultForTests("privacy-deletion-v2");
  assert.throws(() => acceptOrReplayAccountDeletionV2(actor, 2, request, capability), (error: unknown) =>
    error instanceof GatewayStorageOutcomeUnknownError && error.store === "privacy-deletion-v2");
  setGatewayStateCommitFaultForTests(null);
  assert.equal(acceptOrReplayAccountDeletionV2(actor, 2, request, capability).kind, "replay");
});
