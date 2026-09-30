import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  RAW_MANIFEST_SHA256_HEADER,
  RAW_PURPOSE_HEADER,
  RAW_WALK_ID_HEADER,
  type GatewayFetch
} from "../src/backend.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

// RAW-CORE-F3: the Backend raw-ingest consent refusals reach Android with their code
// (Android maps raw_consent_policy_not_current to CONSENT_REJECTED) instead of the
// generic 502 gateway_raw_upstream_invalid. Self-contained: it does not depend on the
// integrated-consent setup of raw-collection-relay.test.ts.
const ACTOR_ID = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3121";
const COLLECTION_ID = "00000000-0000-0000-0000-000000000121";
const WALK_ID = "00000000-0000-0000-0000-000000000122";
let stateDirectory = "";
let cookie = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-raw-upstream-errors-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "raw-upstream-errors-internal-token-1234567890",
    WALKSAFE_GATEWAY_SESSION_SECRET: "raw-upstream-errors-session-secret-12345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  const login = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.121" },
    body: JSON.stringify({
      grant_type: "password",
      email: "raw-upstream-errors@example.org",
      password: "raw-upstream-errors-password-must-not-leak",
      remember_me: false,
      device_id: "android-raw-upstream-errors"
    })
  }), {
    fetchImpl: async () => Response.json({
      schema_version: "walksafe.account-authentication.v1",
      actor_id: ACTOR_ID,
      account_generation: 1,
      auth_epoch: 3
    })
  });
  assert.equal(login.status, 200);
  cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /=v7\./);
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

async function relayedStatusError(status: number, code: string): Promise<Response> {
  const fetchImpl: GatewayFetch = async (input) => {
    const pathname = new URL(String(input)).pathname;
    if (pathname === "/accounts/session-current") return new Response(null, { status: 204 });
    assert.equal(pathname, `/raw-collections/${COLLECTION_ID}`);
    return Response.json(
      { detail: { code, message: "Backend text is replaced by the gateway." } },
      { status }
    );
  };
  return handleGatewayRequest(new Request(
    `https://gateway.invalid/api/raw-collections/${COLLECTION_ID}`,
    { headers: {
      cookie,
      [RAW_PURPOSE_HEADER]: "GENERAL_RAW",
      [RAW_WALK_ID_HEADER]: WALK_ID,
      [RAW_MANIFEST_SHA256_HEADER]: "a".repeat(64)
    } }
  ), { fetchImpl });
}

test("RAW-CORE-F3 Backend 409 raw_consent_policy_not_current is relayed with its code", async () => {
  const response = await relayedStatusError(409, "raw_consent_policy_not_current");
  assert.equal(response.status, 409);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), {
    detail: {
      code: "raw_consent_policy_not_current",
      message: "The raw collection request was not accepted."
    }
  });
  // An unknown Backend code still fails closed.
  const unknown = await relayedStatusError(409, "raw_consent_new_private_marker");
  assert.equal(unknown.status, 502);
  assert.equal((await unknown.json() as { detail: { code: string } }).detail.code,
    "gateway_raw_upstream_invalid");
});

test("RAW-CORE-F3 every consent refusal the Backend raw ingest raises is relayed", async () => {
  const source = await readFile(
    path.join(process.cwd(), "..", "..", "backend", "app", "services", "raw_collection_ingest.py"),
    "utf8"
  );
  const codes = [...new Set([...source.matchAll(/Error\(\s*"([a-z][a-z0-9_]*)"/g)]
    .map((match) => match[1]!))].sort();
  assert.deepEqual(codes, [
    "automatic_reporting_consent_interrupted",
    "automatic_reporting_consent_required",
    "raw_consent_policy_not_current",
    "raw_consent_receipt_not_found",
    "raw_source_collection_consent_interrupted",
    "raw_source_collection_consent_required"
  ]);
  for (const code of codes) {
    const response = await relayedStatusError(409, code);
    assert.equal(response.status, 409, code);
    assert.equal((await response.json() as { detail: { code: string } }).detail.code, code);
  }
});
