import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { type AddressInfo } from "node:net";
import { after, before, test } from "node:test";
import { createGatewayServer, gatewayRequestDeadlineMs, GATEWAY_REQUEST_DEADLINE_MS } from "../src/node-adapter.js";
import { SIGNUP_CONSENT_DOCUMENT_KEYS, ACCOUNT_ENROLLMENT_UPSTREAM_TIMEOUT_MS } from "../src/account-relay.js";
import { type GatewayTelemetryEvent } from "../src/telemetry.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let directory = "";
let base = "";
let calls = 0;
let upstreamStatus = 200;
let upstreamBody: unknown;
const events: GatewayTelemetryEvent[] = [];
const policy = () => ({
  schema_version: "walksafe.account-signup-policy.v1",
  documents: Object.fromEntries(SIGNUP_CONSENT_DOCUMENT_KEYS.map((key, index) => [key, {
    version: "1.2.0", required: index < 3, equivalent_versions: ["1.1.1"], prior_versions: index < 3 ? [] : ["1.1.0"]
  }]))
});
const server = createGatewayServer({
  fetchImpl: async (input, init) => {
    calls += 1;
    assert.equal(new URL(String(input)).pathname, "/account-signup-policy");
    assert.equal(init?.method, "GET");
    assert.equal(init?.cache, "no-store");
    assert.equal(new Headers(init?.headers).get("x-walksafe-field-test-token"), "signup-policy-http-service-token-1234567890");
    return Response.json(upstreamBody, { status: upstreamStatus, headers: { "retry-after": "2" } });
  },
  privacyRightsRequestUrl: "https://privacy.example.invalid/rights",
  telemetrySink: event => events.push(event)
});
before(async () => {
  directory = await mkdtemp(path.join(tmpdir(), "walksafe-signup-policy-http-"));
  Object.assign(process.env, { NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_FIELD_TEST_TOKEN: "signup-policy-http-service-token-1234567890",
    WALKSAFE_GATEWAY_SESSION_SECRET: "signup-policy-http-session-secret-12345678901234567890",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json") });
  await configureTestStateEncryption(path.join(directory, "keys.json"));
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(async () => {
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  await rm(directory, { recursive: true, force: true });
});

test("signup policy is public strict no-store metadata over the Node HTTP adapter", async () => {
  upstreamStatus = 200; upstreamBody = policy();
  const response = await fetch(`${base}/api/account-signup-policy`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), policy());
  assert.ok(events.some(event => event.route_template === "/api/account-signup-policy" && event.http_status === 200));
  assert.doesNotMatch(JSON.stringify(events), /signup-policy-http-service-token/);
});

test("policy query and unsupported methods never call the Backend; privacy HEAD remains public", async () => {
  const beforeCalls = calls;
  const query = await fetch(`${base}/api/account-signup-policy?extra=1`);
  assert.equal(query.status, 400); await query.arrayBuffer();
  for (const method of ["POST", "PUT", "DELETE", "HEAD"]) {
    const response = await fetch(`${base}/api/account-signup-policy`, { method });
    assert.equal(response.status, 405, method);
    assert.equal(response.headers.get("allow"), "GET");
    await response.arrayBuffer();
  }
  const head = await fetch(`${base}/privacy/rights`, { method: "HEAD" });
  assert.equal(head.status, 200); assert.equal(await head.text(), "");
  assert.equal(calls, beforeCalls);
});

test("malformed policy and unsafe upstream extras become 502; configuration failure remains 503", async () => {
  const malformed = [
    { ...policy(), secret: "never-expose" },
    { ...policy(), documents: { ...policy().documents, unknown: {} } },
    { ...policy(), documents: { ...policy().documents, terms_of_service: { version: "1", required: false, equivalent_versions: [], prior_versions: [] } } },
    { ...policy(), documents: { ...policy().documents, terms_of_service: { version: "1", required: true, equivalent_versions: ["1"], prior_versions: ["1"] } } },
    { ...policy(), documents: { ...policy().documents, privacy_notice: { version: "1", required: true, equivalent_versions: ["1", "1"], prior_versions: [] } } },
    { ...policy(), documents: { ...policy().documents, terms_of_service: { version: "1", required: true, equivalent_versions: ["1"], prior_versions: [] } } }
  ];
  upstreamStatus = 200;
  for (upstreamBody of malformed) {
    const response = await fetch(`${base}/api/account-signup-policy`);
    assert.equal(response.status, 502);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.deepEqual(await response.json(), { detail: { code: "gateway_account_upstream_invalid" } });
  }
  upstreamStatus = 503; upstreamBody = { detail: { code: "account_signup_policy_unavailable", message: "internal configuration details" }, secret: "never-expose" };
  const unavailable = await fetch(`${base}/api/account-signup-policy`);
  assert.equal(unavailable.status, 503); assert.equal(unavailable.headers.get("retry-after"), "2");
  assert.deepEqual(await unavailable.json(), { detail: { code: "account_signup_policy_unavailable" } });
});

test("only OTP uses the SMTP-derived upstream and whole-handler budgets", () => {
  assert.equal(ACCOUNT_ENROLLMENT_UPSTREAM_TIMEOUT_MS, 40_000);
  assert.equal(gatewayRequestDeadlineMs(new Request("http://gateway.invalid/api/account-enrollments/email-otp", { method: "POST" })), 60_000);
  assert.equal(gatewayRequestDeadlineMs(new Request("http://gateway.invalid/api/accounts", { method: "POST" })), GATEWAY_REQUEST_DEADLINE_MS);
  assert.equal(gatewayRequestDeadlineMs(new Request("http://gateway.invalid/api/account-signup-policy")), GATEWAY_REQUEST_DEADLINE_MS);
});
