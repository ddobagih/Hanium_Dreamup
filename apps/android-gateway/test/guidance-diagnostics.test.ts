import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { beforeEach, afterEach, test } from "node:test";
import { establishBackendGatewaySession } from "../src/auth.js";
import { handleGatewayRequest } from "../src/routes.js";
import { matchGatewayRoute } from "../src/route-registry.js";
import { bindBackendActorGeneration, abortPrivacyOperationsForAccountDeletion } from "../src/privacy-rights.js";
import { assertBackendActorAssertionV4 } from "./actor-assertion-v4-fixture.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR = "diagnostic-test-account";
let directory = "";
let cookie = "";
beforeEach(async () => {
  directory = await mkdtemp(path.join(tmpdir(), "walksafe-diagnostic-relay-"));
  Object.assign(process.env, {
    NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_GATEWAY_SESSION_SECRET: "diagnostic-test-session-secret-12345678901234567890",
    WALKSAFE_FIELD_TEST_TOKEN: "diagnostic-test-internal-field-token-1234567890",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json")
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(directory, "keyring.json"));
  const grant = establishBackendGatewaySession(new Request("https://gateway.invalid/api/field-session"),
    { actorId: ACTOR, accountGeneration: 7, authEpoch: 4 }, false);
  assert.equal(grant.status, 200);
  cookie = grant.headers.get("set-cookie")!.split(";", 1)[0]!;
  assert.equal(bindBackendActorGeneration(ACTOR, 7), 7);
});
afterEach(async () => { await rm(directory, { recursive: true, force: true }); });
const SESSION = "01234567-1234-4234-9234-012345678901";

for (const [method, publicPath, body] of [
  ["GET", "/api/field-diagnostics/preference", undefined],
  ["POST", "/api/field-diagnostics/preference", { authorize_if_absent: true }],
  ["PUT", "/api/field-diagnostics/preference", { enabled: false, expected_revision: 0 }],
  ["POST", `/api/field-diagnostics/sessions/${SESSION}/batches`, { batch_id: SESSION, preference_revision: 1, events: [] }],
  ["GET", `/api/field-diagnostics/sessions/${SESSION}`, undefined]
] as const) {
  test(`${method} ${publicPath} relays same current account and exact v4 request without client identity headers`, async () => {
    let upstream = 0;
    const response = await handleGatewayRequest(new Request(`https://gateway.invalid${publicPath}`, {
      method, headers: { cookie, "content-type": "application/json", "x-walksafe-actor-id": "forged-actor",
        "x-walksafe-account-generation": "999", "x-walksafe-admin-token": "must-not-forward" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    }), { fetchImpl: async (url, init) => {
      if (new URL(String(url)).pathname === "/accounts/session-current") return new Response(null, { status: 204 });
      upstream++;
      assert.equal(new URL(String(url)).pathname, publicPath.slice(4));
      assert.ok(init);
      assertBackendActorAssertionV4(String(url), init, { actorId: ACTOR, accountGeneration: 7, authEpoch: 4 });
      assert.equal(new Headers(init.headers).get("x-walksafe-admin-token"), null);
      assert.equal(new Headers(init.headers).get("cookie"), null);
      assert.equal(init.body, body === undefined ? undefined : JSON.stringify(body));
      return Response.json({ present: true, enabled: false, revision: 1 });
    } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.deepEqual(await response.json(), { present: true, enabled: false, revision: 1 });
    assert.equal(upstream, 1);
  });
}

test("unauthenticated, invalid UUID, query, duplicate body and administrator routes never reach diagnostic Backend", async () => {
  let diagnosticFetches = 0;
  const fetchImpl = async (url: string | URL | Request) => {
    if (new URL(String(url)).pathname === "/accounts/session-current") return new Response(null, { status: 204 });
    diagnosticFetches++;
    return Response.json({});
  };
  const anonymous = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-diagnostics/preference"), { fetchImpl });
  assert.equal(anonymous.status, 401);
  const malformed = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-diagnostics/sessions/not-a-uuid", { headers: { cookie } }), { fetchImpl });
  assert.equal(malformed.status, 404);
  const query = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-diagnostics/preference?actor_id=other", { headers: { cookie } }), { fetchImpl });
  assert.equal(query.status, 400);
  const duplicate = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-diagnostics/preference", {
    method: "PUT", headers: { cookie, "content-type": "application/json" }, body: '{"enabled":true,"enabled":false,"expected_revision":0}'
  }), { fetchImpl });
  assert.equal(duplicate.status, 400);
  assert.equal(matchGatewayRoute(new URL(`https://gateway.invalid/api/admin/field-diagnostics/sessions/${SESSION}`)), null);
  assert.equal(diagnosticFetches, 0);
});

test("backend stale account stops relay and actual deletion abort cannot publish a late diagnostic response", async () => {
  let diagnostics = 0;
  const denied = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-diagnostics/preference", { headers: { cookie } }),
    { fetchImpl: async () => new Response(null, { status: 401 }) });
  assert.equal(denied.status, 401);
  const changed = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-diagnostics/preference", { headers: { cookie } }),
    { fetchImpl: async url => {
      if (new URL(String(url)).pathname === "/accounts/session-current") return new Response(null, { status: 204 });
      diagnostics++;
      abortPrivacyOperationsForAccountDeletion(ACTOR, 7);
      return Response.json({ present: true, enabled: true, revision: 1 });
    } });
  assert.equal(changed.status, 409);
  assert.equal(diagnostics, 1);
});
