import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import { type GatewayFetch } from "../src/backend.js";
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
import {
  handleGatewayRequest, REPORT_ID_HEADER, REPORT_PAYLOAD_BYTES_HEADER,
  REPORT_PAYLOAD_SHA256_HEADER, REPORT_PURPOSE_HEADER
} from "../src/routes.js";
import { upstreamFormData } from "./actor-assertion-v4-fixture.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const BASE_URL = "http://127.0.0.1:8081";
const BACKEND_URL = "http://127.0.0.1:8000";
const ACTOR_ID = "field-operator";
const ACCOUNT_TOKEN = "s19-46-account-token-12345678901234567890";
const SERVICE_TOKEN = "s19-46-service-token-12345678901234567890";
const SESSION_SECRET = "s19-46-session-secret-123456789012345678901234567890";
const CONTROL_SECRET = "c".repeat(64);
const WALKING_ROUTE_REPLY = {
  schema_version: "walksafe.walking_route.v1",
  provider: "tmap_pedestrian",
  priority: "STAIR_AVOID",
  summary: { distance_m: 12, duration_s: 10 },
  polyline: [
    { latitude: 37.5, longitude: 127.0 },
    { latitude: 37.6, longitude: 127.1 }
  ],
  steps: [{ index: 0, distance_m: 12, duration_s: 10, points: [
    { latitude: 37.5, longitude: 127.0 },
    { latitude: 37.6, longitude: 127.1 }
  ] }],
  guide_points: [{ index: 0, point: { latitude: 37.6, longitude: 127.1 }, instruction: "도착" }],
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
  "WALKSAFE_FIELD_WALK_LEDGER_PATH",
  "WALKSAFE_GATEWAY_STATE_KEYRING_FILE"
] as const;

let stateDirectory = "";
let cookie = "";
const previousEnvironment = new Map<string, string | undefined>();

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-s19-46-relay-"));
  for (const key of ENV_KEYS) previousEnvironment.set(key, process.env[key]);
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: BACKEND_URL,
    WALKSAFE_FIELD_TEST_TOKEN: SERVICE_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    // R19-GB-01: per-file ledger so parallel test files never share the default lock.
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  const login = await handleGatewayRequest(new Request(`${BASE_URL}/api/field-session`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "cf-connecting-ip": "198.51.100.46"
    },
    body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
  }));
  assert.equal(login.status, 200);
  cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /^walksafe_field_session=/);
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
  for (const [key, value] of previousEnvironment) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
});

async function reportConsentHeaders(): Promise<Record<string, string>> {
  const installationId = "s19-46-installation-0001";
  const response = await handleGatewayRequest(
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
        request_id: "s19-46-consent-request-0001",
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
        assert.equal(String(input), `${BACKEND_URL}/privacy/consent-events`);
        const backendBody = String(init?.body ?? "");
        const payload = JSON.parse(backendBody) as {
          request_id: string;
          client_revision: number;
        };
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
  assert.equal(response.status, 201);
  const confirmation = await response.json() as IntegratedConsentConfirmation;
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

function reportRequest(metadata: string, consentHeaders: Record<string, string>): Request {
  const form = new FormData();
  form.set("metadata", metadata);
  form.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], {
    type: "image/jpeg"
  }), "report.jpg");
  return new Request(`${BASE_URL}/api/reports/v2`, {
    method: "POST",
    headers: {
      cookie,
      "cf-connecting-ip": "198.51.100.47",
      ...consentHeaders,
      [REPORT_ID_HEADER]: "11111111-1111-4111-8111-111111111111",
      [REPORT_PAYLOAD_SHA256_HEADER]: "1".repeat(64),
      [REPORT_PAYLOAD_BYTES_HEADER]: "123"
    },
    body: form
  });
}

async function assertInvalidDuplicate(response: Response, name: string): Promise<void> {
  assert.equal(response.status, 400, name);
  assert.equal(response.headers.get("cache-control"), "no-store", name);
  assert.deepEqual(await response.json(), { code: "invalid_json_body" }, name);
}

test("walking relay rejects duplicate JSON members before Backend business calls", async () => {
  const forwarded: string[] = [];
  const fetchImpl: GatewayFetch = async (input, init) => {
    assert.equal(String(input), `${BACKEND_URL}/navigation/walking`);
    forwarded.push(String(init?.body));
    return Response.json(WALKING_ROUTE_REPLY);
  };
  const duplicateBodies = [
    ["top-level", '{"origin":{"latitude":37.5,"longitude":127},"destination":{"latitude":37.6,"longitude":127.1},"radius_m":10,"radius_m":20}'],
    ["nested", '{"origin":{"latitude":37.5,"latitude":37.6,"longitude":127},"destination":{"latitude":37.6,"longitude":127.1}}'],
    ["escape-equivalent", String.raw`{"origin":{"latitude":37.5,"longitude":127},"destination":{"latitude":37.6,"longitude":127.1},"radius_m":10,"rad\u0069us_m":20}`]
  ] as const;
  for (const [name, body] of duplicateBodies) {
    const response = await handleGatewayRequest(new Request(`${BASE_URL}/api/navigation/walking`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body
    }), { fetchImpl });
    await assertInvalidDuplicate(response, name);
    assert.deepEqual(forwarded, [], name);
  }

  const validBody = `{
    "origin": {"latitude":37.5,"longitude":127},
    "destination": {"latitude":37.6,"longitude":127.1}
  }`;
  const accepted = await handleGatewayRequest(new Request(`${BASE_URL}/api/navigation/walking`, {
    method: "POST",
    headers: { cookie, "content-type": "application/json" },
    body: validBody
  }), { fetchImpl });
  assert.equal(accepted.status, 200);
  assert.deepEqual(await accepted.json(), WALKING_ROUTE_REPLY);
  assert.deepEqual(forwarded, [validBody]);
});

test("report relay rejects duplicate metadata members before Backend business calls", async () => {
  const consentHeaders = await reportConsentHeaders();
  const forwardedMetadata: string[] = [];
  const fetchImpl: GatewayFetch = async (input, init) => {
    if (String(input) === `${BACKEND_URL}/accounts/session-current`) {
      return new Response(null, { status: 204 });
    }
    assert.equal(String(input), `${BACKEND_URL}/reports/v2`);
    // S19-52: multipart is forwarded as the exact bytes the v4 assertion signs.
    const form = await upstreamFormData(init);
    forwardedMetadata.push(String(form.get("metadata")));
    return Response.json({ id: "11111111-1111-4111-8111-111111111111" }, { status: 201 });
  };
  const duplicateMetadata = [
    ["top-level", '{"auto_reported":false,"auto_reported":false}'],
    ["nested", '{"auto_reported":false,"details":{"id":1,"id":2}}'],
    ["escape-equivalent", String.raw`{"auto_reported":false,"auto_\u0072eported":false}`]
  ] as const;
  for (const [name, metadata] of duplicateMetadata) {
    await assertInvalidDuplicate(
      await handleGatewayRequest(reportRequest(metadata, consentHeaders), { fetchImpl }),
      name
    );
    assert.deepEqual(forwardedMetadata, [], name);
  }

  const validMetadata = '{ "auto_reported": false, "source": "android" }';
  const accepted = await handleGatewayRequest(
    reportRequest(validMetadata, consentHeaders),
    { fetchImpl }
  );
  assert.equal(accepted.status, 201);
  await accepted.json();
  assert.deepEqual(forwardedMetadata, [validMetadata]);
});
