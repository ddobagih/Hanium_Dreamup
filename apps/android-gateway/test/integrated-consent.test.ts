import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
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
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

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
  const response = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      requestId,
      clientRevision,
      selections,
      cookie
    ),
    { fetchImpl: consentBackendFetch }
  );
  return {
    status: response.status,
    confirmation: await response.json() as IntegratedConsentConfirmation
  };
}

function consentPutRequest(
  installationId: string,
  requestId: string,
  clientRevision: number,
  selections: Record<string, boolean>,
  cookie = primaryCookie
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
    [CONSENT_RECEIPT_HEADER]: confirmation.receipt_sha256
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
  assert.match(first.confirmation.receipt_sha256, /^[0-9a-f]{64}$/);

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
      { headers: { [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET } }
    )
  );
  assert.equal(currentResponse.status, 200);
  assert.deepEqual(await currentResponse.json(), first.confirmation);
  assert.equal(hasCurrentIntegratedConsentItemVersions(first.confirmation), true);
  assert.equal(
    hasCurrentIntegratedConsentItemVersions({
      item_versions: {
        ...first.confirmation.item_versions,
        raw_source_collection: "FP-013-RAW-1.1.0"
      }
    }),
    false
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
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`
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
    2,
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
      { headers: { [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET } }
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
        { headers: { [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET } }
      )
    );
    assert.equal(hidden.status, 404);
  }
});
