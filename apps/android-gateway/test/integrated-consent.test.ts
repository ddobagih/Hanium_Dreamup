import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  authorizeIntegratedConsentRequest,
  CONSENT_CONTROL_SECRET_HEADER,
  CONSENT_INSTALLATION_HEADER,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  CONSENT_POLICY_HEADER,
  CONSENT_RECEIPT_HEADER,
  CONSENT_REVISION_HEADER,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_MAX_STATE_BYTES,
  INTEGRATED_CONSENT_POLICY_VERSION,
  hasCurrentIntegratedConsentItemVersions,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import {
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  FIELD_TEST_TOKEN_HEADER,
  type GatewayFetch
} from "../src/backend.js";
import { handleGatewayRequest } from "../src/routes.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";

let stateDirectory = "";
const CONTROL_SECRET = "a".repeat(64);
const PRIMARY_ACTOR = "consent-test-actor-a";
const PRIMARY_TOKEN = "consent-test-token-a-12345678901234567890";
const SECONDARY_ACTOR = "consent-test-actor-b";
const SECONDARY_TOKEN = "consent-test-token-b-12345678901234567890";
const INTERNAL_TOKEN = "consent-backend-token-12345678901234567890";
const PRIMARY_BINDING = `${PRIMARY_ACTOR}\0generation:1`;
const SECONDARY_BINDING = `${SECONDARY_ACTOR}\0generation:1`;
let primaryCookie = "";
let secondaryCookie = "";
const backendEvents = new Map<string, string>();
const expectedReceiptByInstallation = new Map<string, string>();
const expectedReceiptByRequest = new Map<string, string | null>();

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const payload = value as Record<string, unknown>;
  return `{${Object.keys(payload).sort().map(
    (key) => `${JSON.stringify(key)}:${canonicalJson(payload[key])}`
  ).join(",")}}`;
}

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-consent-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: PRIMARY_ACTOR, token: PRIMARY_TOKEN },
      { actor_id: SECONDARY_ACTOR, token: SECONDARY_TOKEN }
    ]),
    WALKSAFE_GATEWAY_SESSION_SECRET:
      "consent-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip"
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  primaryCookie = await login(PRIMARY_ACTOR, PRIMARY_TOKEN, "198.51.100.10");
  secondaryCookie = await login(SECONDARY_ACTOR, SECONDARY_TOKEN, "198.51.100.11");
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

async function login(actorId: string, token: string, ip: string): Promise<string> {
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": ip
      },
      body: JSON.stringify({ actor_id: actorId, token })
    })
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { session_scope: "general" });
  return (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
}

const consentBackendFetch: GatewayFetch = async (input, init) => {
  assert.equal(String(input), "http://127.0.0.1:8000/privacy/consent-events");
  assert.equal(init?.method, "POST");
  const headers = new Headers(init?.headers);
  assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
  assert.ok([PRIMARY_ACTOR, SECONDARY_ACTOR].includes(
    headers.get(ACTOR_ID_HEADER) ?? ""
  ));
  assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
  assert.match(headers.get(ACTOR_ASSERTION_HEADER) ?? "", /^v2\.\d+\.[A-Za-z0-9_-]+$/);
  const rawBody = String(init?.body ?? "");
  const body = JSON.parse(rawBody) as Record<string, unknown>;
  assert.deepEqual(Object.keys(body), [
    "schema_version",
    "installation_id",
    "request_id",
    "client_revision",
    "expected_previous_backend_receipt_sha256",
    "policy_version",
    "item_versions",
    "raw_source_collection",
    "automatic_reporting",
    "mobile_network_transfer",
    "training_reuse"
  ]);
  assert.equal(body.schema_version, "walksafe.privacy-consent-event.v2");
  const requestId = body.request_id as string;
  const prior = backendEvents.get(requestId);
  if (prior !== undefined && prior !== rawBody) {
    return Response.json({
      detail: {
        code: "privacy_consent_request_conflict",
        message: "Request ID was already used."
      }
    }, { status: 409 });
  }
  backendEvents.set(requestId, rawBody);
  return Response.json({
    schema_version: "walksafe.privacy-consent-receipt.v2",
    request_id: requestId,
    client_revision: body.client_revision,
    receipt_sha256: createHash("sha256").update(rawBody).digest("hex"),
    recorded_at: "2026-08-09T12:05:00Z"
  }, { status: prior === undefined ? 201 : 200 });
};

async function save(
  installationId: string,
  requestId: string,
  clientRevision: number,
  selections: Record<string, boolean>,
  cookie = primaryCookie
): Promise<{ status: number; confirmation: IntegratedConsentConfirmation }> {
  const expectedPrevious = expectedReceiptByRequest.has(requestId)
    ? expectedReceiptByRequest.get(requestId)!
    : expectedReceiptByInstallation.get(installationId) ?? null;
  expectedReceiptByRequest.set(requestId, expectedPrevious);
  const response = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      requestId,
      clientRevision,
      selections,
      cookie,
      expectedPrevious
    ),
    { fetchImpl: consentBackendFetch }
  );
  const result = {
    status: response.status,
    confirmation: await response.json() as IntegratedConsentConfirmation
  };
  if (result.status === 200 || result.status === 201) {
    expectedReceiptByInstallation.set(
      installationId,
      result.confirmation.backend_consent_receipt_sha256
    );
  }
  return result;
}

function consentPutRequest(
  installationId: string,
  requestId: string,
  clientRevision: number,
  selections: Record<string, boolean>,
  cookie = primaryCookie,
  expectedPreviousBackendReceiptSha256: string | null = null
): Request {
  return new Request(
    "http://127.0.0.1:8081/privacy/rights?control=integrated-consent",
    {
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
        client_revision: clientRevision,
        expected_previous_backend_receipt_sha256:
          expectedPreviousBackendReceiptSha256,
        selections
      })
    }
  );
}

function receiptHeaders(confirmation: IntegratedConsentConfirmation): Headers {
  return new Headers({
    [CONSENT_INSTALLATION_HEADER]: confirmation.installation_id,
    [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi",
    [CONSENT_POLICY_HEADER]: confirmation.policy_version,
    [CONSENT_REVISION_HEADER]: String(confirmation.revision),
    [CONSENT_RECEIPT_HEADER]: confirmation.backend_consent_receipt_sha256
  });
}

test("four selections are independent, versioned, idempotent, and current", async () => {
  const installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: true,
    training_reuse: false
  };
  const first = await save(
    installationId,
    "integrated_consent_request_0001",
    1,
    selections
  );
  assert.equal(first.status, 201);
  assert.equal(first.confirmation.revision, 1);
  assert.deepEqual(first.confirmation.selections, selections);
  assert.deepEqual(Object.keys(first.confirmation), [
    "schema_version",
    "current",
    "installation_id",
    "request_id",
    "policy_version",
    "item_versions",
    "client_revision",
    "revision",
    "selections",
    "confirmed_at",
    "gateway_audit_record_sha256",
    "backend_consent_receipt_sha256"
  ]);
  assert.equal(
    first.confirmation.schema_version,
    "walksafe.integrated-consent-confirmation.v2"
  );
  assert.match(first.confirmation.gateway_audit_record_sha256, /^[0-9a-f]{64}$/);
  assert.match(first.confirmation.backend_consent_receipt_sha256, /^[0-9a-f]{64}$/);
  assert.notEqual(
    first.confirmation.gateway_audit_record_sha256,
    first.confirmation.backend_consent_receipt_sha256
  );
  const gatewayAuditHeaders = receiptHeaders(first.confirmation);
  gatewayAuditHeaders.set(
    CONSENT_RECEIPT_HEADER,
    first.confirmation.gateway_audit_record_sha256
  );
  const gatewayAuditRejected = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: gatewayAuditHeaders
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(gatewayAuditRejected.error?.status, 409);

  const retry = await save(
    installationId,
    "integrated_consent_request_0001",
    1,
    selections
  );
  assert.equal(retry.status, 200);
  assert.deepEqual(retry.confirmation, first.confirmation);

  const currentResponse = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent" +
        `&installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      {
        headers: {
          cookie: primaryCookie,
          [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
        }
      }
    )
  );
  assert.equal(currentResponse.status, 200);
  assert.deepEqual(await currentResponse.json(), first.confirmation);
  assert.equal(hasCurrentIntegratedConsentItemVersions(first.confirmation), true);
  assert.equal(
    hasCurrentIntegratedConsentItemVersions({
      item_versions: {
        ...first.confirmation.item_versions,
        raw_source_collection: "FP-013-RAW-1.0.0"
      }
    }),
    false
  );
});

test("authenticated bootstrap strictly projects the Backend consent source", async () => {
  const installationId = "consent-bootstrap-installation-0001";
  const receipt = "d".repeat(64);
  const url =
    "http://127.0.0.1:8081/privacy/rights" +
    "?control=integrated-consent-bootstrap" +
    `&installation_id=${installationId}` +
    `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`;
  let backendCalls = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    backendCalls += 1;
    assert.equal(
      String(input),
      "http://127.0.0.1:8000/privacy/consent-bootstrap" +
        `?installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`
    );
    assert.equal(init?.method, "GET");
    assert.equal(init?.cache, "no-store");
    assert.equal(init?.body, undefined);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
    assert.equal(headers.get(ACTOR_ID_HEADER), PRIMARY_ACTOR);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
    assert.match(
      headers.get(ACTOR_ASSERTION_HEADER) ?? "",
      /^v2\.\d+\.[A-Za-z0-9_-]+$/
    );
    return Response.json({
      schema_version: "walksafe.integrated-consent-bootstrap.v1",
      status: "READY",
      source: "CURRENT_CONSENT",
      installation_id: installationId,
      policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
      item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
      client_revision_floor: 3,
      selections: {
        raw_source_collection: true,
        automatic_reporting: false,
        mobile_network_transfer: false,
        training_reuse: true
      },
      source_receipt_sha256: receipt,
      expected_previous_backend_receipt_sha256: receipt
    });
  };

  const unauthenticated = await handleGatewayRequest(
    new Request(url),
    { fetchImpl }
  );
  assert.equal(unauthenticated.status, 401);
  assert.equal(backendCalls, 0);

  const response = await handleGatewayRequest(
    new Request(url, { headers: { cookie: primaryCookie } }),
    { fetchImpl }
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), {
    schema_version: "walksafe.integrated-consent-bootstrap.v1",
    status: "READY",
    source: "CURRENT_CONSENT",
    installation_id: installationId,
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
    item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
    client_revision_floor: 3,
    selections: {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: true
    },
    source_receipt_sha256: receipt,
    expected_previous_backend_receipt_sha256: receipt
  });
  assert.equal(backendCalls, 1);
});

test("bootstrap rejects malformed Backend state and impossible nullable CAS", async () => {
  const installationId = "consent-bootstrap-installation-0002";
  const response = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent-bootstrap" +
        `&installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      { headers: { cookie: primaryCookie } }
    ),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.integrated-consent-bootstrap.v1",
        status: "RECONSENT_REQUIRED",
        source: "NONE",
        installation_id: installationId,
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision_floor: 2,
        selections: null,
        source_receipt_sha256: null,
        expected_previous_backend_receipt_sha256: null
      })
    }
  );
  assert.equal(response.status, 502);
  assert.equal(
    (await response.json() as { detail: { code: string } }).detail.code,
    "gateway_upstream_protocol_invalid"
  );
});

test("withdrawal advances revision and makes the prior receipt stale", async () => {
  const installationId = "960c62f3-7e70-4ae7-bd03-60c2bfe907dd";
  const granted = await save(
    installationId,
    "integrated_consent_request_1001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: false,
      training_reuse: true
    }
  );
  const withdrawn = await save(
    installationId,
    "integrated_consent_request_1002",
    2,
    {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: true
    }
  );
  assert.equal(withdrawn.confirmation.revision, 2);

  const stale = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(granted.confirmation)
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(stale.error?.status, 409);

  const current = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(withdrawn.confirmation)
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(current.error, undefined);
  assert.equal(current.confirmation?.selections.automatic_reporting, false);
});

test("all-denied selection is valid and wrong policy requires reconsent", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2def7";
  const denied = await save(
    installationId,
    "integrated_consent_request_2001",
    1,
    {
      raw_source_collection: false,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  assert.equal(denied.status, 201);

  const blocked = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(denied.confirmation)
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(blocked.error?.status, 403);

  const wrongVersion = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
      method: "PUT",
      headers: {
        cookie: primaryCookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: "integrated_consent_request_2002",
        policy_version: "FP-013-0.9.0",
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 2,
        expected_previous_backend_receipt_sha256: null,
        selections: denied.confirmation.selections
      })
    }),
    { fetchImpl: consentBackendFetch }
  );
  assert.equal(wrongVersion.status, 409);
  const body = await wrongVersion.json() as { detail: { required_policy_version: string } };
  assert.equal(body.detail.required_policy_version, INTEGRATED_CONSENT_POLICY_VERSION);
});

test("control secret protects read and write access", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2def8";
  const selections = {
    raw_source_collection: false,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  await save(
    installationId,
    "integrated_consent_request_3001",
    1,
    selections
  );
  const deniedRead = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent" +
        `&installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      { headers: { cookie: primaryCookie } }
    )
  );
  assert.equal(deniedRead.status, 401);
  const deniedWrite = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
      method: "PUT",
      headers: {
        cookie: primaryCookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: "b".repeat(64)
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: "integrated_consent_request_3002",
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 2,
        expected_previous_backend_receipt_sha256: null,
        selections
      })
    }),
    { fetchImpl: consentBackendFetch }
  );
  assert.equal(deniedWrite.status, 403);
});

test("older client revision cannot reverse a newer withdrawal", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2def9";
  const withdrawn = await save(
    installationId,
    "integrated_consent_request_4002",
    2,
    {
      raw_source_collection: false,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  assert.equal(withdrawn.status, 201);
  const staleGrant = await save(
    installationId,
    "integrated_consent_request_4001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: true,
      training_reuse: true
    }
  );
  assert.equal(staleGrant.status, 409);
});

test("cellular authorization requires the independent mobile selection", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2defa";
  const saved = await save(
    installationId,
    "integrated_consent_request_5001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  const cellularHeaders = receiptHeaders(saved.confirmation);
  cellularHeaders.set(CONSENT_NETWORK_TRANSPORT_HEADER, "cellular");
  const cellular = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: cellularHeaders
    }),
    ["raw_source_collection", "automatic_reporting", "mobile_network_transfer"],
    PRIMARY_BINDING
  );
  assert.equal(cellular.error?.status, 403);

  const wifi = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(saved.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    PRIMARY_BINDING
  );
  assert.equal(wifi.error, undefined);
});

test("receipt binds to the first field actor until explicit reconsent", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2defb";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: true,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const saved = await save(
    installationId,
    "integrated_consent_request_6001",
    1,
    selections
  );
  const firstActor = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(saved.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    PRIMARY_BINDING
  );
  assert.equal(firstActor.error, undefined);

  const otherActor = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(saved.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    SECONDARY_BINDING
  );
  assert.equal(otherActor.error?.status, 409);

  const reconfirmed = await save(
    installationId,
    "integrated_consent_request_6002",
    1,
    selections,
    secondaryCookie
  );
  const rebound = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(reconfirmed.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    SECONDARY_BINDING
  );
  assert.equal(rebound.error, undefined);
});

test("account switch hides local consent and commits an actor successor only after Backend success", async () => {
  const installationId = "consent-account-switch-installation-0001";
  const first = await save(
    installationId,
    "consent_account_switch_request_0001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: false,
      training_reuse: true
    }
  );
  assert.equal(first.status, 201);
  const currentUrl =
    "http://127.0.0.1:8081/privacy/rights" +
    "?control=integrated-consent" +
    `&installation_id=${installationId}` +
    `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`;
  const switchedRead = await handleGatewayRequest(new Request(currentUrl, {
    headers: {
      cookie: secondaryCookie,
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
    }
  }));
  assert.equal(switchedRead.status, 409);
  assert.equal(
    (await switchedRead.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_actor_reconsent_required"
  );

  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const before = await readFile(filePath);
  const successorSelections = {
    raw_source_collection: false,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const failedSuccessor = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      "consent_account_switch_request_0002",
      1,
      successorSelections,
      secondaryCookie,
      first.confirmation.backend_consent_receipt_sha256
    ),
    {
      fetchImpl: async () => Response.json({
        detail: {
          code: "privacy_consent_previous_receipt_conflict",
          message: "The previous backend consent receipt does not match the latest event."
        }
      }, { status: 409 })
    }
  );
  assert.equal(failedSuccessor.status, 409);
  assert.deepEqual(await readFile(filePath), before);

  const successor = await save(
    installationId,
    "consent_account_switch_request_0002",
    1,
    successorSelections,
    secondaryCookie
  );
  assert.equal(successor.status, 201);
  const newActorRead = await handleGatewayRequest(new Request(currentUrl, {
    headers: {
      cookie: secondaryCookie,
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
    }
  }));
  assert.equal(newActorRead.status, 200);
  assert.deepEqual(
    (await newActorRead.json() as IntegratedConsentConfirmation).selections,
    successorSelections
  );
  const oldActorRead = await handleGatewayRequest(new Request(currentUrl, {
    headers: {
      cookie: primaryCookie,
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
    }
  }));
  assert.equal(oldActorRead.status, 409);
  assert.equal(
    (await oldActorRead.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_actor_reconsent_required"
  );
});

test("backend failure or malformed receipt never exposes a local confirmation", async () => {
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const unavailableInstallation = "consent-backend-unavailable-installation";
  const unavailableRequest = "consent_backend_unavailable_request_0001";
  const unavailable = await handleGatewayRequest(
    consentPutRequest(
      unavailableInstallation,
      unavailableRequest,
      1,
      selections
    ),
    {
      fetchImpl: async () => Response.json({
        detail: {
          code: "privacy_lifecycle_store_unavailable",
          message: "Privacy lifecycle storage is unavailable."
        }
      }, { status: 503, headers: { "retry-after": "5" } })
    }
  );
  assert.equal(unavailable.status, 503);
  assert.equal(unavailable.headers.get("retry-after"), "5");
  assert.deepEqual(await unavailable.json(), {
    detail: {
      code: "privacy_lifecycle_store_unavailable",
      message: "Privacy lifecycle storage is unavailable."
    }
  });
  const unavailableRead = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent" +
        `&installation_id=${unavailableInstallation}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      {
        headers: {
          cookie: primaryCookie,
          [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
        }
      }
    )
  );
  assert.equal(unavailableRead.status, 404);

  for (const [suffix, mutate] of [
    ["fraction", (receipt: Record<string, unknown>) => {
      receipt.recorded_at = "2026-08-09T12:05:00.000Z";
    }],
    ["extra", (receipt: Record<string, unknown>) => {
      receipt.extra = true;
    }],
    ["hash", (receipt: Record<string, unknown>) => {
      receipt.receipt_sha256 = "A".repeat(64);
    }]
  ] as const) {
    const installationId = `consent-invalid-receipt-installation-${suffix}`;
    const requestId = `consent_invalid_receipt_request_${suffix}_0001`;
    const response = await handleGatewayRequest(
      consentPutRequest(installationId, requestId, 1, selections),
      {
        fetchImpl: async (_input, init) => {
          const event = JSON.parse(String(init?.body)) as {
            request_id: string;
            client_revision: number;
          };
          const receipt: Record<string, unknown> = {
            schema_version: "walksafe.privacy-consent-receipt.v2",
            request_id: event.request_id,
            client_revision: event.client_revision,
            receipt_sha256: "a".repeat(64),
            recorded_at: "2026-08-09T12:05:00Z"
          };
          mutate(receipt);
          return Response.json(receipt, { status: 201 });
        }
      }
    );
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), {
      detail: {
        code: "gateway_upstream_protocol_invalid",
        message: "The consent backend returned an invalid receipt."
      }
    });
    const hidden = await handleGatewayRequest(
      new Request(
        "http://127.0.0.1:8081/privacy/rights" +
          "?control=integrated-consent" +
          `&installation_id=${installationId}` +
          `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
        {
          headers: {
            cookie: primaryCookie,
            [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
          }
        }
      )
    );
    assert.equal(hidden.status, 404);
  }
});

test("Backend CAS failure leaves the local ledger byte-identical and retry CAS is bound", async () => {
  const installationId = "consent-backend-cas-installation-0001";
  const requestId = "consent_backend_cas_request_0002";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: true
  };
  const first = await save(
    installationId,
    "consent_backend_cas_request_0001",
    1,
    selections
  );
  assert.equal(first.status, 201);
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const before = await readFile(filePath);
  const wrongExpected = "f".repeat(64);
  const rejected = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      requestId,
      2,
      selections,
      primaryCookie,
      wrongExpected
    ),
    {
      fetchImpl: async () => Response.json({
        detail: {
          code: "privacy_consent_cas_conflict",
          message: "The expected previous consent receipt is stale."
        }
      }, { status: 409 })
    }
  );
  assert.equal(rejected.status, 409);
  assert.deepEqual(await readFile(filePath), before);

  const accepted = await save(installationId, requestId, 2, selections);
  assert.equal(accepted.status, 201);
  const state = decryptTestStateFile<{
    schema_version: number;
    events: Array<{
      request_id: string;
      expected_previous_backend_receipt_sha256?: string | null;
    }>;
  }>(
    { kind: "integrated-consent", recordId },
    await readFile(filePath, "utf8"),
    INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  assert.equal(state.schema_version, 6);
  assert.equal(state.events.at(-1)?.request_id, requestId);
  assert.equal(
    state.events.at(-1)?.expected_previous_backend_receipt_sha256,
    first.confirmation.backend_consent_receipt_sha256
  );

  let backendCalls = 0;
  const conflictingReplay = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      requestId,
      2,
      selections,
      primaryCookie,
      wrongExpected
    ),
    {
      fetchImpl: async () => {
        backendCalls += 1;
        throw new Error("conflicting replay must not reach Backend");
      }
    }
  );
  assert.equal(conflictingReplay.status, 409);
  assert.equal(
    (await conflictingReplay.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_request_conflict"
  );
  assert.equal(backendCalls, 0);
});

test("idempotent replay fails closed when Backend changes its authoritative receipt", async () => {
  const installationId = "consent-backend-replay-mismatch-installation";
  const requestId = "consent_backend_replay_mismatch_request_0001";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  let replay = false;
  const fetchImpl: GatewayFetch = async (_input, init) => {
    const event = JSON.parse(String(init?.body)) as {
      request_id: string;
      client_revision: number;
    };
    return Response.json({
      schema_version: "walksafe.privacy-consent-receipt.v2",
      request_id: event.request_id,
      client_revision: event.client_revision,
      receipt_sha256: (replay ? "b" : "a").repeat(64),
      recorded_at: "2026-08-09T12:05:00Z"
    }, { status: replay ? 200 : 201 });
  };
  const first = await handleGatewayRequest(
    consentPutRequest(installationId, requestId, 1, selections),
    { fetchImpl }
  );
  assert.equal(first.status, 201);
  replay = true;
  const second = await handleGatewayRequest(
    consentPutRequest(installationId, requestId, 1, selections),
    { fetchImpl }
  );
  assert.equal(second.status, 502);
  assert.equal(
    (await second.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_backend_receipt_mismatch"
  );
});

test("audit-chain corruption of the bound Backend receipt fails closed", async () => {
  const installationId = "consent-audit-corruption-installation";
  const saved = await save(
    installationId,
    "consent_audit_corruption_request_0001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  assert.equal(saved.status, 201);
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const state = decryptTestStateFile<{
    events: Array<{ backend_consent_receipt_sha256: string }>;
  }>(
    { kind: "integrated-consent", recordId },
    await readFile(filePath, "utf8"),
    INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  state.events.at(-1)!.backend_consent_receipt_sha256 = "c".repeat(64);
  await writeFile(
    filePath,
    encryptTestStateFile(
      { kind: "integrated-consent", recordId },
      state,
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    )
  );
  const response = await handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    {
      headers: {
        cookie: primaryCookie,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      }
    }
  ));
  assert.equal(response.status, 503);
});

test("legacy schema v3 local receipt is readable only as reconsent-required", async () => {
  const installationId = "consent-legacy-v3-installation";
  const eventWithoutReceipt = {
    request_id: "consent_legacy_v3_request_0001",
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
    item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
    client_revision: 1,
    revision: 1,
    selections: {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    },
    confirmed_at: "2026-08-09T12:05:00.000Z",
    previous_receipt_sha256: null
  };
  const receipt = createHash("sha256").update(canonicalJson({
    installation_id: installationId,
    ...eventWithoutReceipt
  })).digest("hex");
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const directory = path.join(stateDirectory, "integrated-consent");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await writeFile(
    path.join(directory, recordId),
    encryptTestStateFile(
      { kind: "integrated-consent", recordId },
      {
        schema_version: 3,
        installation_id: installationId,
        control_secret_sha256: createHash("sha256")
          .update(`integrated-consent-control\0${CONTROL_SECRET}`)
          .digest("hex"),
        field_actor_binding: null,
        events: [{ ...eventWithoutReceipt, receipt_sha256: receipt }]
      },
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    ),
    { mode: 0o600 }
  );
  const response = await handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    {
      headers: {
        cookie: primaryCookie,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      }
    }
  ));
  assert.equal(response.status, 409);
  assert.equal(
    (await response.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_reconsent_required"
  );
});

test("old-policy schema v4 GET requires reconsent and current PUT appends floor plus one", async () => {
  const installationId = "consent-old-policy-v4-installation";
  const actorSha256 = createHash("sha256")
    .update(`integrated-consent-field-actor\0${PRIMARY_BINDING}`)
    .digest("hex");
  const oldBackendReceipt = "9".repeat(64);
  const eventWithoutAudit = {
    request_id: "consent_old_policy_v4_request_0001",
    policy_version: "FP-013-1.0.0",
    item_versions: {
      raw_source_collection: "FP-013-RAW-1.0.0",
      automatic_reporting: "FP-013-AUTO-1.0.0",
      mobile_network_transfer: "FP-013-MOBILE-1.0.0",
      training_reuse: "FP-013-TRAINING-1.0.0"
    },
    client_revision: 1,
    revision: 1,
    selections: {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    },
    confirmed_at: "2026-08-09T12:05:00.000Z",
    previous_gateway_audit_record_sha256: null,
    backend_consent_receipt_sha256: oldBackendReceipt,
    backend_recorded_at: "2026-08-09T12:05:00Z",
    field_actor_binding_sha256: actorSha256
  };
  const gatewayAudit = createHash("sha256").update(canonicalJson({
    installation_id: installationId,
    ...eventWithoutAudit
  })).digest("hex");
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const directory = path.join(stateDirectory, "integrated-consent");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await writeFile(
    path.join(directory, recordId),
    encryptTestStateFile(
      { kind: "integrated-consent", recordId },
      {
        schema_version: 4,
        installation_id: installationId,
        control_secret_sha256: createHash("sha256")
          .update(`integrated-consent-control\0${CONTROL_SECRET}`)
          .digest("hex"),
        field_actor_binding: {
          actor_sha256: actorSha256,
          consent_revision: 1,
          bound_at: "2026-08-09T12:05:00.000Z"
        },
        events: [{
          ...eventWithoutAudit,
          gateway_audit_record_sha256: gatewayAudit
        }]
      },
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    ),
    { mode: 0o600 }
  );

  const oldRead = await handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    {
      headers: {
        cookie: primaryCookie,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      }
    }
  ));
  assert.equal(oldRead.status, 409);
  assert.equal(
    (await oldRead.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_reconsent_required"
  );

  const updatedResponse = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      "consent_old_policy_v4_request_0002",
      2,
      eventWithoutAudit.selections,
      primaryCookie,
      oldBackendReceipt
    ),
    { fetchImpl: consentBackendFetch }
  );
  assert.equal(updatedResponse.status, 201);
  const updated = await updatedResponse.json() as IntegratedConsentConfirmation;
  assert.equal(updated.client_revision, 2);
  assert.equal(updated.revision, 2);
  assert.equal(updated.policy_version, INTEGRATED_CONSENT_POLICY_VERSION);
});
