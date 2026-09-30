import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  BACKEND_SESSION_GATE_TIMEOUT_MS,
  FIELD_TEST_TOKEN_HEADER,
  resetImageUploadAdmissionForTests,
  type GatewayFetch
} from "../src/backend.js";
import {
  CONSENT_CONTROL_SECRET_HEADER,
  CONSENT_INSTALLATION_HEADER,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  CONSENT_POLICY_HEADER,
  CONSENT_RECEIPT_HEADER,
  CONSENT_REVISION_HEADER,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_POLICY_VERSION,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import { gatewayRequestDeadlineMs } from "../src/node-adapter.js";
import {
  handleGatewayRequest, REPORT_ID_HEADER, REPORT_PAYLOAD_BYTES_HEADER,
  REPORT_PAYLOAD_SHA256_HEADER, REPORT_PURPOSE_HEADER
} from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";
import { assertBackendActorAssertionV4 } from "./actor-assertion-v4-fixture.js";

const BASE_URL = "http://127.0.0.1:8081";
const BACKEND_URL = "http://127.0.0.1:8000";
const SERVICE_TOKEN = "s19-3-service-token-12345678901234567890";
const SESSION_SECRET = "s19-3-session-secret-123456789012345678901234567890";
const CONTROL_SECRET = "a".repeat(64);
const WALKING_ROUTE_REPLY = {
  schema_version: "walksafe.walking_route.v1",
  provider: "tmap_pedestrian",
  priority: "STAIR_AVOID",
  summary: { distance_m: 12, duration_s: 10 },
  polyline: [
    { latitude: 37.5665, longitude: 126.9780 },
    { latitude: 37.5670, longitude: 126.9790 }
  ],
  steps: [{ index: 0, distance_m: 12, duration_s: 10, points: [
    { latitude: 37.5665, longitude: 126.9780 },
    { latitude: 37.5670, longitude: 126.9790 }
  ] }],
  guide_points: [{ index: 0, point: { latitude: 37.5670, longitude: 126.9790 }, instruction: "도착" }],
  provider_result_code: 0,
  provider_result_message: "OK"
};
const ENV_KEYS = [
  "NODE_ENV",
  "WALKSAFE_ENVIRONMENT",
  "BACKEND_API_BASE_URL",
  "WALKSAFE_FIELD_TEST_TOKEN",
  "WALKSAFE_FIELD_ACCOUNTS_JSON",
  "WALKSAFE_GATEWAY_SESSION_SECRET",
  "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER",
  "WALKSAFE_GATEWAY_RATE_LIMIT_DIR",
  "WALKSAFE_FIELD_WALK_LEDGER_PATH"
] as const;

type CookieVersion = "v6" | "v7";
type BackendState = "current" | "denied" | "unavailable" | "network-failure";
type Endpoint = "walking" | "report-v2";

let stateDirectory = "";
const previousEnvironment = new Map<string, string | undefined>();

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-s19-3-route-"));
  for (const key of ENV_KEYS) previousEnvironment.set(key, process.env[key]);
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: BACKEND_URL,
    WALKSAFE_FIELD_TEST_TOKEN: SERVICE_TOKEN,
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
  for (const [key, value] of previousEnvironment) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
});

async function accountCookie(version: CookieVersion, index: number): Promise<{ cookie: string; actorId: string }> {
  const actorId = `018f2b63-8fb8-4cc2-98a1-4a4fd27c${String(index).padStart(4, "0")}`;
  const body: Record<string, unknown> = {
    grant_type: "password",
    email: `s19-3-${index}@example.org`,
    password: "s19-3-password-must-not-leak",
    remember_me: false
  };
  if (version === "v7") body.device_id = `android-device-${index}`;
  let authenticationCalls = 0;
  const login = await handleGatewayRequest(
    new Request(`${BASE_URL}/api/field-session`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": `198.51.100.${index + 10}`
      },
      body: JSON.stringify(body)
    }),
    {
      fetchImpl: async (input, init) => {
        authenticationCalls += 1;
        assert.equal(String(input), `${BACKEND_URL}/accounts/authenticate`);
        assert.equal(new Headers(init?.headers).get(FIELD_TEST_TOKEN_HEADER), SERVICE_TOKEN);
        return Response.json({
          schema_version: "walksafe.account-authentication.v1",
          actor_id: actorId,
          account_generation: 7,
          auth_epoch: 3
        });
      }
    }
  );
  assert.equal(login.status, 200);
  assert.equal(authenticationCalls, 1);
  const cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, version === "v6"
    ? /^walksafe_field_session=v6\./
    : /^walksafe_field_session=v7\./);
  return { cookie, actorId };
}

async function reportConsent(cookie: string, index: number): Promise<HeadersInit> {
  const installationId = `s19-3-installation-${index}`;
  const requestId = `s19-3-consent-request-${index}`;
  const consent = await handleGatewayRequest(
    new Request(`${BASE_URL}/privacy/rights?control=integrated-consent`, {
      method: "PUT",
      headers: {
        cookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: requestId,
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 1,
        expected_previous_backend_receipt_sha256: null,
        selections: {
          raw_source_collection: true,
          automatic_reporting: false,
          mobile_network_transfer: false,
          training_reuse: false
        }
      })
    }),
    {
      fetchImpl: async (input, init) => {
        if (String(input) === `${BACKEND_URL}/accounts/session-current`) {
          return new Response(null, { status: 204 });
        }
        assert.equal(String(input), `${BACKEND_URL}/privacy/consent-events`);
        const backendBody = String(init?.body ?? "");
        const payload = JSON.parse(backendBody) as { request_id: string; client_revision: number };
        return Response.json({
          schema_version: "walksafe.privacy-consent-receipt.v2",
          request_id: payload.request_id,
          client_revision: payload.client_revision,
          receipt_sha256: createHash("sha256").update(backendBody).digest("hex"),
          recorded_at: "2026-08-09T12:05:00Z"
        }, { status: 201 });
      }
    }
  );
  assert.equal(consent.status, 201);
  const confirmation = await consent.json() as IntegratedConsentConfirmation;
  assert.equal(confirmation.current, true);
  return {
    [CONSENT_INSTALLATION_HEADER]: installationId,
    [CONSENT_POLICY_HEADER]: confirmation.policy_version,
    [CONSENT_REVISION_HEADER]: String(confirmation.revision),
    [CONSENT_RECEIPT_HEADER]: confirmation.backend_consent_receipt_sha256,
    [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi",
    [REPORT_PURPOSE_HEADER]: "explicit"
  };
}

function endpointRequest(endpoint: Endpoint, cookie: string, index: number, consentHeaders: HeadersInit): Request {
  if (endpoint === "walking") {
    return new Request(`${BASE_URL}/api/navigation/walking`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({
        origin: { latitude: 37.5665, longitude: 126.9780 },
        destination: { latitude: 37.5670, longitude: 126.9790 }
      })
    });
  }
  const form = new FormData();
  form.set("metadata", JSON.stringify({ source: "android", auto_reported: false }));
  form.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
  return new Request(`${BASE_URL}/api/reports/v2`, {
    method: "POST",
    headers: {
      cookie,
      "cf-connecting-ip": `203.0.113.${index + 10}`,
      ...Object.fromEntries(new Headers(consentHeaders)),
      [REPORT_ID_HEADER]: `11111111-1111-4111-8111-${String(index).padStart(12, "0")}`,
      [REPORT_PAYLOAD_SHA256_HEADER]: "1".repeat(64),
      [REPORT_PAYLOAD_BYTES_HEADER]: "123"
    },
    body: form
  });
}

let caseIndex = 0;
for (const version of ["v6", "v7"] as const) {
  for (const endpoint of ["walking", "report-v2"] as const) {
    for (const state of ["current", "denied", "unavailable", "network-failure"] as const) {
      const index = ++caseIndex;
      test(`S19-3 ${version} ${endpoint}: Backend session ${state}`, async () => {
        const { cookie, actorId } = await accountCookie(version, index);
        const consentHeaders = endpoint === "report-v2"
          ? await reportConsent(cookie, index)
          : {};
        if (endpoint === "report-v2") resetImageUploadAdmissionForTests();
        let sessionCurrentCalls = 0;
        const upstreamCalls: string[] = [];
        const fetchImpl: GatewayFetch = async (input, init) => {
          const url = String(input);
          if (url === `${BACKEND_URL}/accounts/session-current`) {
            sessionCurrentCalls += 1;
            assert.equal(init?.method, "POST");
            assert.equal(init?.body, undefined);
            const headers = new Headers(init?.headers);
            assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), SERVICE_TOKEN);
            assert.equal(headers.get(ACTOR_ID_HEADER), actorId);
            assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "7");
            assert.equal(headers.get(ACTOR_ASSERTION_HEADER)?.startsWith("v4."), true);
            assertBackendActorAssertionV4(input, init, {
              actorId, accountGeneration: 7, authEpoch: 3
            });
            if (state === "network-failure") throw new Error("Backend network unavailable");
            return new Response(null, {
              status: state === "current" ? 204 : state === "denied" ? 401 : 503
            });
          }
          upstreamCalls.push(url);
          const expectedUrl = `${BACKEND_URL}${endpoint === "walking" ? "/navigation/walking" : "/reports/v2"}`;
          assert.equal(url, expectedUrl);
          assertBackendActorAssertionV4(input, init, {
            actorId, accountGeneration: 7, authEpoch: 3
          });
          return endpoint === "walking"
            ? Response.json(WALKING_ROUTE_REPLY)
            : Response.json({ id: "11111111-1111-4111-8111-111111111111" }, { status: 201 });
        };
        const response = await handleGatewayRequest(
          endpointRequest(endpoint, cookie, index, consentHeaders),
          { fetchImpl }
        );
        assert.ok(sessionCurrentCalls > 0, "Backend session-current must be checked for this account cookie");
        if (state === "current") {
          assert.equal(response.status, endpoint === "walking" ? 200 : 201);
          assert.deepEqual(upstreamCalls, [
            `${BACKEND_URL}${endpoint === "walking" ? "/navigation/walking" : "/reports/v2"}`
          ]);
        } else {
          assert.equal(response.status, state === "denied" ? 401 : 503);
          assert.deepEqual(upstreamCalls, [], "stale or unverifiable sessions must not relay route/report requests");
        }
      });
    }
  }
}

for (const version of ["v6", "v7"] as const) {
  for (const endpoint of ["walking", "report-v2"] as const) {
    for (const phase of ["before-check", "during-check", "late-current", "late-denied"] as const) {
      const index = ++caseIndex;
      test(`S19-3 ${version} ${endpoint}: client abort ${phase} stops currentness and business calls`, async () => {
        const { cookie } = await accountCookie(version, index);
        const consentHeaders = endpoint === "report-v2"
          ? await reportConsent(cookie, index)
          : {};
        if (endpoint === "report-v2") resetImageUploadAdmissionForTests();
        const controller = new AbortController();
        const request = new Request(endpointRequest(endpoint, cookie, index, consentHeaders), {
          signal: controller.signal
        });
        const reason = new Error("client disconnected during account check");
        if (phase === "before-check") controller.abort(reason);
        let checkCalls = 0;
        let checkSignal: AbortSignal | null | undefined;
        const upstreamCalls: string[] = [];
        const fetchImpl: GatewayFetch = async (input, init) => {
          const url = String(input);
          if (url !== `${BACKEND_URL}/accounts/session-current`) {
            upstreamCalls.push(url);
            return Response.json({ ok: true });
          }
          checkCalls += 1;
          checkSignal = init?.signal;
          if (phase === "during-check") {
            return new Promise((_resolve, reject) => {
              const watchdog = setTimeout(() => reject(new Error("account check did not abort")), 500);
              checkSignal?.addEventListener("abort", () => {
                clearTimeout(watchdog);
                reject(checkSignal?.reason);
              }, { once: true });
              controller.abort(reason);
            });
          }
          controller.abort(reason);
          // A response can already be resolving when the client disconnects.
          return new Response(null, { status: phase === "late-denied" ? 401 : 204 });
        };
        const response = await handleGatewayRequest(request, { fetchImpl });
        assert.equal(response.status, 499);
        assert.equal(response.headers.get("cache-control"), "no-store");
        assert.equal((await response.json() as { detail: { code: string } }).detail.code,
          "gateway_client_closed");
        assert.equal(checkCalls, phase === "before-check" ? 0 : 1);
        if (phase !== "before-check") {
          assert.equal(checkSignal?.aborted, true);
          assert.equal(checkSignal?.reason, reason);
        }
        assert.deepEqual(upstreamCalls, [], "cancelled requests must not start business requests");
      });
    }
  }
}
{
  const index = ++caseIndex;
  test("gw-F6 S19-59 a hanging Backend session check ends at its own limit, inside the request deadline", async () => {
    const { cookie } = await accountCookie("v7", index);
    let checkAbortedAfterMs: number | null = null;
    const upstreamCalls: string[] = [];
    const started = performance.now();
    const fetchImpl: GatewayFetch = async (input, init) => {
      const url = String(input);
      if (url !== `${BACKEND_URL}/accounts/session-current`) {
        upstreamCalls.push(url);
        return Response.json({ ok: true });
      }
      // Like a Backend that accepted the connection and never answers.
      const signal = init?.signal as AbortSignal;
      return await new Promise<Response>((_resolve, reject) => {
        signal.addEventListener("abort", () => {
          checkAbortedAfterMs = performance.now() - started;
          reject(signal.reason);
        }, { once: true });
      });
    };
    const request = endpointRequest("walking", cookie, index, {});
    // AbortSignal.timeout does not keep the event loop alive; a listening server would.
    const keepAlive = setInterval(() => undefined, 1_000);
    let response: Response;
    try {
      response = await handleGatewayRequest(request, { fetchImpl });
    } finally {
      clearInterval(keepAlive);
    }
    const elapsed = performance.now() - started;
    // "unavailable", not a logout, and no business call; answered by the gate's own limit.
    assert.equal(response.status, 503);
    assert.deepEqual(upstreamCalls, []);
    assert.ok(checkAbortedAfterMs !== null);
    assert.ok(
      elapsed >= BACKEND_SESSION_GATE_TIMEOUT_MS - 50 && elapsed < BACKEND_SESSION_GATE_TIMEOUT_MS + 3_000,
      `gate answered after ${Math.round(elapsed)} ms`
    );
    // Navigation now has one 8 s outer budget. The 5 s account gate must abort first.
    assert.equal(gatewayRequestDeadlineMs(request), 8_000);
    assert.ok(checkAbortedAfterMs < gatewayRequestDeadlineMs(request));
    assert.ok(elapsed < gatewayRequestDeadlineMs(request));
  });
}
