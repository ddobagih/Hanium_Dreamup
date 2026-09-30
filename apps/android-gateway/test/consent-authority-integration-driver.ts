/** Real Backend consent authority exercised through the loopback Gateway. */
import assert from "node:assert/strict";
import { once } from "node:events";
import path from "node:path";

import type { GatewayFetch } from "../src/backend.js";
import {
  CONSENT_CONTROL_SECRET_HEADER,
  INTEGRATED_CONSENT_BOOTSTRAP_CONTROL,
  INTEGRATED_CONSENT_CONTROL,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_POLICY_VERSION,
  type IntegratedConsentBootstrap,
  type IntegratedConsentConfirmation,
  type IntegratedConsentSelections
} from "../src/integrated-consent.js";
import { createGatewayServer } from "../src/node-adapter.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const backendOrigin = new URL(process.env.S19_31_BACKEND_ORIGIN!);
assert.equal(backendOrigin.hostname, "127.0.0.1");
assert.equal(backendOrigin.protocol, "http:");
assert.equal(backendOrigin.pathname, "/");
assert.equal(backendOrigin.search + backendOrigin.hash + backendOrigin.username + backendOrigin.password, "");
assert.ok(Number(backendOrigin.port) > 0);

const nativeFetch = globalThis.fetch;
globalThis.fetch = async () => { throw new Error("unconfigured integration transport"); };
const allowedBackendPaths = new Set([
  "/accounts/authenticate", "/accounts/session-current",
  "/privacy/consent-events", "/privacy/consent-bootstrap"
]);
const trace: Array<{ path: string; status: number }> = [];
const upstream: GatewayFetch = async (input, init) => {
  const source = new URL(String(input));
  assert.equal(source.origin, "http://127.0.0.1:8000");
  assert.equal(source.username + source.password + source.hash, "");
  assert.ok(allowedBackendPaths.has(source.pathname), `unexpected upstream path: ${source.pathname}`);
  const response = await nativeFetch(new URL(source.pathname + source.search, backendOrigin), init);
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
const installations = { a: "s19-31-installation-a", b: "s19-31-installation-b" };
const secrets = { a: "a".repeat(64), b: "b".repeat(64) };
const initialSelections: IntegratedConsentSelections = {
  raw_source_collection: true,
  automatic_reporting: false,
  mobile_network_transfer: false,
  training_reuse: false
};

async function gatewayFetch(route: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("cf-connecting-ip", "127.0.0.1");
  return nativeFetch(new URL(route, gatewayOrigin), {
    ...init, headers, signal: AbortSignal.timeout(10_000)
  });
}

async function login(device: "a" | "b"): Promise<string> {
  const response = await gatewayFetch("/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      grant_type: "password",
      email: process.env.S19_31_EMAIL,
      password: process.env.S19_31_PASSWORD,
      remember_me: false,
      device_id: `s19-31-device-${device}`
    })
  });
  assert.equal(response.status, 200, await response.clone().text());
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.ok(cookie.startsWith("walksafe_field_session=v7."));
  return cookie;
}

async function bootstrap(device: "a" | "b", cookie: string): Promise<IntegratedConsentBootstrap> {
  const query = new URLSearchParams({
    control: INTEGRATED_CONSENT_BOOTSTRAP_CONTROL,
    installation_id: installations[device],
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION
  });
  const response = await gatewayFetch(`/privacy/rights?${query}`, {
    method: "GET", headers: { cookie }
  });
  assert.equal(response.status, 200, await response.clone().text());
  return await response.json() as IntegratedConsentBootstrap;
}

async function put(
  device: "a" | "b", cookie: string, revision: number,
  previous: string | null, selections: IntegratedConsentSelections
): Promise<IntegratedConsentConfirmation> {
  const response = await gatewayFetch(
    `/privacy/rights?control=${INTEGRATED_CONSENT_CONTROL}`,
    {
      method: "PUT",
      headers: {
        cookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: secrets[device]
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installations[device],
        request_id: `s19_31_${device}_${revision}_request`,
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: revision,
        expected_previous_backend_receipt_sha256: previous,
        selections
      })
    }
  );
  assert.equal(response.status, 201, await response.clone().text());
  const confirmation = await response.json() as IntegratedConsentConfirmation;
  assert.equal(confirmation.current, true);
  assert.deepEqual(confirmation.selections, selections);
  return confirmation;
}

async function read(device: "a" | "b", cookie: string): Promise<Response> {
  const query = new URLSearchParams({
    control: INTEGRATED_CONSENT_CONTROL,
    installation_id: installations[device],
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION
  });
  return gatewayFetch(`/privacy/rights?${query}`, {
    method: "GET",
    headers: { cookie, [CONSENT_CONTROL_SECRET_HEADER]: secrets[device] }
  });
}

if (process.env.S19_31_SERVER_ONLY === "1") {
  try {
    process.stdout.write(`${JSON.stringify({ phase: "ready", gateway_url: gatewayOrigin })}\n`);
    await once(process.stdin, "data");
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close(error => error ? reject(error) : resolve()));
  }
} else {
try {
  const cookieA = await login("a");
  const cookieB = await login("b");
  const aInitial = await put("a", cookieA, 1, null, initialSelections);

  const bFloor = await bootstrap("b", cookieB);
  assert.equal(bFloor.source, "CURRENT_CONSENT");
  assert.equal(bFloor.expected_previous_backend_receipt_sha256, aInitial.backend_consent_receipt_sha256);
  const bSame = await put(
    "b", cookieB, bFloor.client_revision_floor + 1,
    bFloor.expected_previous_backend_receipt_sha256, initialSelections
  );
  const beforeSameReadPosts = trace.filter(item => item.path === "/privacy/consent-events").length;
  const aSameResponse = await read("a", cookieA);
  assert.equal(aSameResponse.status, 200, await aSameResponse.clone().text());
  const aSame = await aSameResponse.json() as IntegratedConsentConfirmation;
  assert.equal(aSame.current, true);
  assert.equal(aSame.backend_consent_receipt_sha256, aInitial.backend_consent_receipt_sha256);
  assert.equal(trace.filter(item => item.path === "/privacy/consent-events").length, beforeSameReadPosts,
    "same-choice authority refresh must not create another consent event");

  const withdrawn: IntegratedConsentSelections = { ...initialSelections, raw_source_collection: false };
  const bWithdraw = await put(
    "b", cookieB, bSame.client_revision + 1,
    bSame.backend_consent_receipt_sha256, withdrawn
  );
  const aStale = await read("a", cookieA);
  assert.equal(aStale.status, 409, await aStale.clone().text());
  assert.equal((await aStale.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_reconsent_required");

  const aFloor = await bootstrap("a", cookieA);
  assert.equal(aFloor.source, "CURRENT_CONSENT");
  assert.equal(aFloor.expected_previous_backend_receipt_sha256, bWithdraw.backend_consent_receipt_sha256);
  assert.deepEqual(aFloor.selections, withdrawn);
  const aReconfirmed = await put(
    "a", cookieA, aFloor.client_revision_floor + 1,
    aFloor.expected_previous_backend_receipt_sha256, initialSelections
  );
  const aCurrentResponse = await read("a", cookieA);
  assert.equal(aCurrentResponse.status, 200, await aCurrentResponse.clone().text());
  const aCurrent = await aCurrentResponse.json() as IntegratedConsentConfirmation;
  assert.equal(aCurrent.current, true);
  assert.equal(aCurrent.backend_consent_receipt_sha256, aReconfirmed.backend_consent_receipt_sha256);

  assert.equal(trace.filter(item => item.path === "/privacy/consent-events").length, 4);
  process.stdout.write(`${JSON.stringify({
    phase: "done", same_choice_get: 200, same_choice_extra_put: 0,
    changed_choice_get: 409, bootstrap_source: aFloor.source,
    reconfirmed_get: 200, consent_event_posts: 4,
    backend_paths: [...new Set(trace.map(item => item.path))].sort()
  })}\n`);
} finally {
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}
}
