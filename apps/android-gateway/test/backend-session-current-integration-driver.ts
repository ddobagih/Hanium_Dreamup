/** Driven by the opt-in Postgres test; authentication responses use real HTTP. */
import assert from "node:assert/strict";
import { once } from "node:events";
import path from "node:path";
import { createInterface } from "node:readline";

import { gatewaySessionAuthEpoch } from "../src/auth.js";
import type { GatewayFetch } from "../src/backend.js";
import {
  CONSENT_CONTROL_SECRET_HEADER, CONSENT_INSTALLATION_HEADER,
  CONSENT_NETWORK_TRANSPORT_HEADER, CONSENT_POLICY_HEADER,
  CONSENT_RECEIPT_HEADER, CONSENT_REVISION_HEADER,
  INTEGRATED_CONSENT_ITEM_VERSIONS, INTEGRATED_CONSENT_POLICY_VERSION,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import { createGatewayServer } from "../src/node-adapter.js";
import { REPORT_PURPOSE_HEADER } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const backendOrigin = new URL(process.env.EPOCH_TEST_BACKEND_ORIGIN!);
assert.equal(backendOrigin.hostname, "127.0.0.1");
assert.equal(backendOrigin.protocol, "http:");
assert.equal(backendOrigin.pathname, "/");
assert.equal(backendOrigin.search + backendOrigin.hash + backendOrigin.username + backendOrigin.password, "");
assert.ok(Number(backendOrigin.port) > 0);
const version = process.env.EPOCH_TEST_COOKIE_VERSION;
assert.ok(version === "v6" || version === "v7");
const nativeFetch = globalThis.fetch;
globalThis.fetch = async () => { throw new Error("unconfigured integration transport"); };
const allowedBackendPaths = new Set([
  "/accounts/authenticate", "/accounts/session-current", "/privacy/consent-events",
  "/navigation/walking", "/reports/v2"
]);
const trace: Array<{ path: string; status: number }> = [];
const upstream: GatewayFetch = async (input, init) => {
  const source = new URL(String(input));
  assert.equal(source.origin, "http://127.0.0.1:8000");
  assert.equal(source.username + source.password + source.search + source.hash, "");
  assert.ok(allowedBackendPaths.has(source.pathname), `unexpected upstream path: ${source.pathname}`);
  assert.equal(init?.method, "POST");
  const response = await nativeFetch(new URL(source.pathname, backendOrigin), init);
  trace.push({ path: source.pathname, status: response.status });
  return response;
};
await configureTestStateEncryption(path.join(process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR!, "keyring.json"));
const server = createGatewayServer({ fetchImpl: upstream });
server.listen(0, "127.0.0.1");
await once(server, "listening");
const address = server.address();
assert.ok(address && typeof address !== "string");
const gatewayOrigin = `http://127.0.0.1:${address.port}`;
const commands = createInterface({ input: process.stdin })[Symbol.asyncIterator]();

async function waitFor(command: string): Promise<void> {
  const input = await commands.next();
  assert.equal(input.done, false);
  assert.equal(input.value, command);
}
function emit(value: unknown): void { process.stdout.write(`${JSON.stringify(value)}\n`); }
async function gatewayFetch(route: string, init: RequestInit): Promise<Response> {
  return nativeFetch(new URL(route, gatewayOrigin), { ...init, signal: AbortSignal.timeout(10_000) });
}
async function login(): Promise<string> {
  const response = await gatewayFetch("/api/field-session", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({
      grant_type: "password", email: process.env.EPOCH_TEST_EMAIL,
      password: process.env.EPOCH_TEST_PASSWORD, remember_me: false,
      ...(version === "v7" ? { device_id: "epoch-integration-device" } : {})
    })
  });
  assert.equal(response.status, 200, await response.text());
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.ok(cookie.startsWith(`walksafe_field_session=${version}.`));
  return cookie;
}
async function consent(cookie: string): Promise<HeadersInit> {
  const installation = `epoch-integration-${version}`;
  const response = await gatewayFetch("/privacy/rights?control=integrated-consent", {
    method: "PUT", headers: {
      cookie, "content-type": "application/json", [CONSENT_CONTROL_SECRET_HEADER]: "a".repeat(64)
    },
    body: JSON.stringify({
      schema_version: "walksafe.integrated-consent-request.v1",
      installation_id: installation, request_id: `epoch-integration-consent-${version}`,
      policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
      item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS, client_revision: 1,
      expected_previous_backend_receipt_sha256: null,
      selections: {
        raw_source_collection: true, automatic_reporting: false,
        mobile_network_transfer: false, training_reuse: false
      }
    })
  });
  assert.equal(response.status, 201);
  const confirmation = await response.json() as IntegratedConsentConfirmation;
  assert.equal(confirmation.current, true);
  return {
    [CONSENT_CONTROL_SECRET_HEADER]: "a".repeat(64),
    [CONSENT_INSTALLATION_HEADER]: installation,
    [CONSENT_POLICY_HEADER]: confirmation.policy_version,
    [CONSENT_REVISION_HEADER]: String(confirmation.revision),
    [CONSENT_RECEIPT_HEADER]: confirmation.backend_consent_receipt_sha256,
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi", [REPORT_PURPOSE_HEADER]: "explicit"
  };
}
async function exercise(cookie: string, consentHeaders: HeadersInit): Promise<number[]> {
  const walking = await gatewayFetch("/api/navigation/walking", {
    method: "POST", headers: { cookie, "content-type": "application/json" },
    body: JSON.stringify({
      origin: { latitude: 37.5665, longitude: 126.978 },
      destination: { latitude: 37.567, longitude: 126.979 }
    })
  });
  await walking.text();
  const form = new FormData();
  form.set("metadata", JSON.stringify({ source: "android", auto_reported: false }));
  form.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
  const report = await gatewayFetch("/api/reports/v2", {
    method: "POST", headers: { cookie, ...Object.fromEntries(new Headers(consentHeaders)) }, body: form
  });
  await report.text();
  for (const response of [walking, report]) assert.equal(response.headers.get("cache-control"), "no-store");
  return [walking.status, report.status];
}
function localEpoch(cookie: string): number | null {
  return gatewaySessionAuthEpoch(new Request(`${gatewayOrigin}/api/navigation/walking`, { headers: { cookie } }));
}
try {
  const originalCookie = await login();
  const reportConsent = await consent(originalCookie);
  assert.equal(localEpoch(originalCookie), 1);
  const before = await exercise(originalCookie, reportConsent);
  assert.deepEqual(before, [200, 201]);
  emit({ phase: "before", version, local_epoch: localEpoch(originalCookie), statuses: before, trace: trace.splice(0) });
  await waitFor("epoch-updated");

  assert.equal(localEpoch(originalCookie), 1, "the Gateway ledger must remain unchanged before stale requests");
  const stale = await exercise(originalCookie, reportConsent);
  assert.deepEqual(stale, [401, 401]);
  const staleTrace = trace.splice(0);
  assert.deepEqual(staleTrace, [
    { path: "/accounts/session-current", status: 401 },
    { path: "/accounts/session-current", status: 401 }
  ]);
  emit({ phase: "stale", version, local_epoch: localEpoch(originalCookie), statuses: stale, trace: staleTrace });
  await waitFor("stale-observed");

  const refreshedCookie = await login();
  assert.notEqual(refreshedCookie, originalCookie);
  assert.equal(localEpoch(refreshedCookie), 2);
  const refreshed = await exercise(refreshedCookie, reportConsent);
  assert.deepEqual(refreshed, [200, 201]);
  emit({ phase: "refreshed", version, local_epoch: localEpoch(refreshedCookie), statuses: refreshed, trace: trace.splice(0) });
} finally {
  process.stdin.destroy();
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}
