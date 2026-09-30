import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, test } from "node:test";
import {
  establishBackendGatewaySession,
  gatewaySessionAuthEpoch
} from "../src/auth.js";
import {
  ACTOR_ASSERTION_HEADER, ACTOR_ID_HEADER, ACCOUNT_GENERATION_HEADER,
  PENDING_BACKEND_ACTOR_ASSERTION,
  createBackendActorAssertion, createRawCollectionRequestProof,
  proxyRequestHeaders, rawCollectionBackendHeaders, signBackendActorRequest
} from "../src/backend.js";
import { assertBackendActorAssertionV4 } from "./actor-assertion-v4-fixture.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const SECRET = "auth-epoch-contract-secret-123456789012345678901234";
const ACTOR = "auth-epoch-actor";
let directory = "";

beforeEach(async () => {
  directory = await mkdtemp(path.join(tmpdir(), "walksafe-actor-epoch-"));
  Object.assign(process.env, {
    NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_GATEWAY_SESSION_SECRET: SECRET,
    WALKSAFE_FIELD_TEST_TOKEN: "epoch-internal-token-12345678901234567890",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json")
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(directory, "keyring.json"));
});
afterEach(async () => { await rm(directory, { recursive: true, force: true }); });

test("v3 binds the cross-language actor, generation, epoch and timestamp contract", () => {
  const signature = createHmac("sha256", SECRET)
    .update("walksafe-backend-actor-v3:field:auth-epoch-actor:7:4:2000000000")
    .digest("base64url");
  assert.equal(createBackendActorAssertion(ACTOR, 7, 2_000_000_000, 4),
    `v3.2000000000.4.${signature}`);
  assert.match(createBackendActorAssertion(ACTOR, 7, 2_000_000_000)!, /^v2\./);
  for (const epoch of [0, -1, 1.5, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(createBackendActorAssertion(ACTOR, 7, 2_000_000_000, epoch), null);
  }
});

for (const deviceId of [undefined, "epoch-device"]) {
  test(`${deviceId ? "v7" : "v6"} proxies the verified session epoch, not client headers`, async () => {
    const login = new Request("https://gateway.invalid/api/field-session");
    const binding = {
      actorId: ACTOR, accountGeneration: 7, authEpoch: 4,
      ...(deviceId === undefined ? {} : { deviceId })
    };
    const response = establishBackendGatewaySession(login, binding, false);
    assert.equal(response.status, 200);
    const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
    const request = new Request("https://gateway.invalid/api/navigation/walking", {
      headers: { cookie, "x-walksafe-auth-epoch": "999", [ACTOR_ID_HEADER]: "forged" }
    });
    assert.equal(gatewaySessionAuthEpoch(request), 4);
    const headers = proxyRequestHeaders(request);
    assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "7");
    // S19-52: the verified session epoch reaches the Backend inside the v4 signature.
    assert.equal(headers.get(ACTOR_ASSERTION_HEADER), PENDING_BACKEND_ACTOR_ASSERTION);
    const url = "http://127.0.0.1:8000/navigation/walking";
    const signed = await signBackendActorRequest(url, { method: "POST", headers, body: "{}" });
    assert.ok(signed);
    assert.equal(assertBackendActorAssertionV4(url, signed, {
      actorId: ACTOR, accountGeneration: 7, authEpoch: 4
    }).method, "POST");
    assert.equal(proxyRequestHeaders(request, undefined, 8).has(ACTOR_ASSERTION_HEADER), false);
    assert.equal(establishBackendGatewaySession(login, { ...binding, authEpoch: 5 }, false).status, 200);
    assert.equal(gatewaySessionAuthEpoch(request), null);
    assert.equal(proxyRequestHeaders(request).has(ACTOR_ASSERTION_HEADER), false);
  });
}

test("raw collection retains its request proof and carries v4 actor authority", async () => {
  const input = {
    actorId: ACTOR, accountGeneration: 7, authEpoch: 4,
    operation: "GET_STATUS" as const, method: "GET" as const,
    path: "/raw-collections/aaaaaaaa-1111-4111-8111-111111111111",
    purpose: "GENERAL_RAW" as const,
    walkId: "bbbbbbbb-2222-4222-8222-222222222222",
    manifestSha256: "a".repeat(64), consentReceiptSha256: null,
    chunkSha256: null, commitSha256: null
  };
  const headers = rawCollectionBackendHeaders(input, undefined, 2_000_000_000)!;
  assert.equal(headers.get(ACTOR_ASSERTION_HEADER), PENDING_BACKEND_ACTOR_ASSERTION);
  assert.equal(headers.get("x-walksafe-raw-request-proof"),
    createRawCollectionRequestProof(input, 2_000_000_000));
  const url = `http://127.0.0.1:8000${input.path}`;
  const signed = await signBackendActorRequest(url, { method: "GET", headers });
  assert.ok(signed);
  assert.equal(assertBackendActorAssertionV4(url, signed, {
    actorId: ACTOR, accountGeneration: 7, authEpoch: 4
  }).target, input.path);
  assert.equal(new Headers(signed.headers).get("x-walksafe-raw-request-proof"),
    createRawCollectionRequestProof(input, 2_000_000_000));
});
