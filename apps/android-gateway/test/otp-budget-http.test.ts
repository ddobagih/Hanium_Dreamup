import assert from "node:assert/strict";
import { createServer } from "node:http";
import { type AddressInfo } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { createGatewayServer } from "../src/node-adapter.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let directory = "";
let upstreamOrigin = "";
let gatewayOrigin = "";
let stall = false;
let upstreamCalls = 0;
const timers = new Set<ReturnType<typeof setTimeout>>();
const upstream = createServer(async (request, response) => {
  upstreamCalls += 1;
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  assert.equal(body.email, "Person@xn--bcher-kva.example");
  assert.equal(body.date_of_birth, "1990-01-01");
  if (stall) return;
  const timer = setTimeout(() => {
    timers.delete(timer);
    response.writeHead(202, { "content-type": "application/json" });
    response.end(JSON.stringify({ schema_version: "walksafe.account-enrollment-email-otp-response.v1",
      enrollment_handle: Buffer.alloc(32, 0x31).toString("base64url"), expires_at: "2026-10-12T00:00:00Z",
      resend_available_at: "2026-10-11T00:00:00Z" }));
  }, 20_000);
  timers.add(timer);
});
const gateway = createGatewayServer({ fetchImpl: (input, init) => {
  assert.equal(new URL(String(input)).pathname, "/account-enrollments/email-otp");
  return fetch(`${upstreamOrigin}/account-enrollments/email-otp`, init);
} });
before(async () => {
  directory = await mkdtemp(path.join(tmpdir(), "walksafe-otp-budget-http-"));
  Object.assign(process.env, { NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_FIELD_TEST_TOKEN: "otp-budget-http-service-token-1234567890",
    WALKSAFE_GATEWAY_SESSION_SECRET: "otp-budget-http-session-secret-12345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip", WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json") });
  await configureTestStateEncryption(path.join(directory, "keys.json"));
  await new Promise<void>(resolve => upstream.listen(0, "127.0.0.1", resolve));
  upstreamOrigin = `http://127.0.0.1:${(upstream.address() as AddressInfo).port}`;
  await new Promise<void>(resolve => gateway.listen(0, "127.0.0.1", resolve));
  gatewayOrigin = `http://127.0.0.1:${(gateway.address() as AddressInfo).port}`;
});
after(async () => {
  for (const timer of timers) clearTimeout(timer);
  gateway.closeAllConnections(); upstream.closeAllConnections();
  await Promise.all([gateway, upstream].map(server => new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))));
  await rm(directory, { recursive: true, force: true });
});
const send = () => fetch(`${gatewayOrigin}/api/account-enrollments/email-otp`, {
  method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.19" },
  body: JSON.stringify({ schema_version: "walksafe.account-enrollment-email-otp.v1", email: "Person@bücher.example",
    date_of_birth: "1990-01-01", request_id: "otp_budget_http_fixture_0001" }), signal: AbortSignal.timeout(45_000)
});

test("OTP delivery taking twenty real seconds completes within the OTP-only budget with one upstream call", async () => {
  const beforeCalls = upstreamCalls; const started = performance.now();
  const response = await send();
  assert.equal(response.status, 202); assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal((await response.json() as { schema_version: string }).schema_version, "walksafe.account-enrollment-email-otp-response.v1");
  assert.ok(performance.now() - started >= 19_900);
  assert.equal(upstreamCalls, beforeCalls + 1);
});

