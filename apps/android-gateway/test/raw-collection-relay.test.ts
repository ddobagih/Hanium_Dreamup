import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  gatewayFieldLongSessionBinding,
  type GatewayFieldLongSessionBinding
} from "../src/auth.js";
import {
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  createRawCollectionRequestProof,
  FIELD_TEST_TOKEN_HEADER,
  rawCollectionRequestProofMessage,
  RAW_CHUNK_SHA256_HEADER,
  RAW_COMMIT_SHA256_HEADER,
  RAW_CONSENT_RECEIPT_SHA256_HEADER,
  RAW_MANIFEST_SHA256_HEADER,
  RAW_PURPOSE_HEADER,
  RAW_REQUEST_PROOF_HEADER,
  RAW_WALK_ID_HEADER,
  type GatewayFetch,
  type RawCollectionBackendProofInput
} from "../src/backend.js";
import {
  commandFieldWalk,
  parseFieldWalkCommand
} from "../src/field-walk-ledger.js";
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
  matchRawCollectionRoute,
  resetRawCollectionRelayForTests
} from "../src/raw-collection-relay.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const INTERNAL_TOKEN = "raw-relay-internal-token-12345678901234567890";
const SESSION_SECRET = "raw-relay-session-secret-123456789012345678901234567890";
const ACTOR_ID = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3100";
const COLLECTION_ID = "00000000-0000-0000-0000-000000000001";
const OBJECT_ID = "00000000-0000-0000-0000-000000000002";
const WALK_ID = "00000000-0000-0000-0000-000000000003";
const SEGMENT_ID = "00000000-0000-0000-0000-000000000004";
const INSTALLATION_ID = "raw-relay-installation-0001";
const CONTROL_SECRET = "c".repeat(64);
const RAW_BYTES = Buffer.from("raw-body", "utf8");
const RAW_SHA256 = createHash("sha256").update(RAW_BYTES).digest("hex");

let stateDirectory = "";
let confirmation: IntegratedConsentConfirmation;
let sessionCookie = "";
let currentBinding: GatewayFieldLongSessionBinding | null = null;
let walkLeaseId = "";
let walkFence = 0;

function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") return String(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const object = value as Record<string, unknown>;
  return `{${Object.keys(object).sort().map((key) =>
    `${JSON.stringify(key)}:${canonicalJson(object[key])}`
  ).join(",")}}`;
}

function contractDigest(domain: string, payload: Record<string, unknown>): string {
  return createHash("sha256").update(domain, "utf8").update(canonicalJson(payload), "utf8")
    .digest("hex");
}

const manifestUnsigned: Record<string, unknown> = {
  schema_version: "walksafe.raw-collection-manifest.v1",
  collection_id: COLLECTION_ID,
  walk_id: WALK_ID,
  segment_id: SEGMENT_ID,
  purpose: "GENERAL_RAW",
  captured_started_at: "2026-08-29T00:00:00Z",
  captured_ended_at: "2026-08-29T00:00:05Z",
  consent_receipt_sha256: "pending",
  object_count: 1,
  chunk_count: 1,
  total_bytes: RAW_BYTES.byteLength,
  objects: [{
    object_id: OBJECT_ID,
    kind: "VIDEO",
    content_type: "video/mp4",
    size_bytes: RAW_BYTES.byteLength,
    sha256: RAW_SHA256,
    chunks: [{ index: 0, size_bytes: RAW_BYTES.byteLength, sha256: RAW_SHA256 }]
  }]
};

function bindingResolver(): GatewayFieldLongSessionBinding | null {
  return currentBinding ? { ...currentBinding } : null;
}

function backendReceipt(rawBody: string): Response {
  const body = JSON.parse(rawBody) as Record<string, unknown>;
  return Response.json({
    schema_version: "walksafe.privacy-consent-receipt.v2",
    request_id: body.request_id,
    client_revision: body.client_revision,
    receipt_sha256: createHash("sha256").update(rawBody).digest("hex"),
    recorded_at: "2026-08-29T00:00:06Z"
  }, { status: 201 });
}

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-raw-relay-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  const login = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-session",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "198.51.100.77"
      },
      body: JSON.stringify({
        grant_type: "password",
        email: "raw-relay@example.org",
        password: "raw-relay-password-must-not-leak",
        remember_me: false,
        device_id: "android-raw-device"
      })
    }
  ), {
    fetchImpl: async () => Response.json({
      schema_version: "walksafe.account-authentication.v1",
      actor_id: ACTOR_ID,
      account_generation: 1,
      auth_epoch: 7
    })
  });
  assert.equal(login.status, 200);
  sessionCookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  currentBinding = gatewayFieldLongSessionBinding(new Request(
    "https://gateway.invalid/api/field-walk",
    { headers: { cookie: sessionCookie } }
  ));
  assert.ok(currentBinding);
  const started = commandFieldWalk(currentBinding!, parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: "raw-relay-walk-start-0001",
    action: "start",
    walk_id: WALK_ID
  })!);
  assert.equal(started.status, 200);
  walkLeaseId = String(started.body.lease_id);
  walkFence = Number(started.body.fencing_token);

  const saved = await handleGatewayRequest(new Request(
    "https://gateway.invalid/privacy/rights?control=integrated-consent",
    {
      method: "PUT",
      headers: {
        cookie: sessionCookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: INSTALLATION_ID,
        request_id: "raw_relay_consent_request_0001",
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 1,
        expected_previous_backend_receipt_sha256: null,
        selections: {
          raw_source_collection: true,
          automatic_reporting: true,
          mobile_network_transfer: true,
          training_reuse: false
        }
      })
    }
  ), {
    fetchImpl: async (_input, init) => backendReceipt(String(init?.body))
  });
  assert.equal(saved.status, 201);
  confirmation = await saved.json() as IntegratedConsentConfirmation;
  assert.notEqual(
    confirmation.gateway_audit_record_sha256,
    confirmation.backend_consent_receipt_sha256
  );
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function manifest(): Record<string, unknown> {
  const unsigned = {
    ...manifestUnsigned,
    consent_receipt_sha256: confirmation.backend_consent_receipt_sha256
  };
  return {
    ...unsigned,
    manifest_sha256: contractDigest(
      "walksafe/raw-collection-manifest/v1\0",
      unsigned
    )
  };
}

function rawWriteRequest(
  suffix: "/manifest" | "/commit",
  body: string,
  extraHeaders: HeadersInit = {}
): Request {
  const payload = manifest();
  return new Request(`https://gateway.invalid/api/raw-collections/${COLLECTION_ID}${suffix}`, {
    method: suffix === "/manifest" ? "PUT" : "POST",
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(body)),
      cookie: sessionCookie,
      [RAW_PURPOSE_HEADER]: "GENERAL_RAW",
      [RAW_WALK_ID_HEADER]: WALK_ID,
      [RAW_MANIFEST_SHA256_HEADER]: String(payload.manifest_sha256),
      [RAW_CONSENT_RECEIPT_SHA256_HEADER]: confirmation.backend_consent_receipt_sha256,
      [CONSENT_INSTALLATION_HEADER]: INSTALLATION_ID,
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
      [CONSENT_POLICY_HEADER]: confirmation.policy_version,
      [CONSENT_REVISION_HEADER]: String(confirmation.revision),
      [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi",
      [FIELD_TEST_TOKEN_HEADER]: "client-token-must-be-discarded",
      [ACTOR_ID_HEADER]: "client-actor-must-be-discarded",
      [ACCOUNT_GENERATION_HEADER]: "999",
      [ACTOR_ASSERTION_HEADER]: "client-assertion-must-be-discarded",
      [RAW_REQUEST_PROOF_HEADER]: "client-proof-must-be-discarded",
      ...Object.fromEntries(new Headers(extraHeaders))
    },
    body
  });
}

function statusPayload(): Record<string, unknown> {
  const payload = manifest();
  return {
    schema_version: "walksafe.raw-collection-status.v1",
    collection_id: COLLECTION_ID,
    manifest_sha256: payload.manifest_sha256,
    purpose: "GENERAL_RAW",
    state: "MANIFEST_ACCEPTED",
    object_count: 1,
    chunk_count: 1,
    total_bytes: RAW_BYTES.byteLength,
    received_chunk_count: 0,
    received_bytes: 0,
    objects: [{
      object_id: OBJECT_ID,
      kind: "VIDEO",
      sha256: RAW_SHA256,
      chunk_count: 1,
      received_chunk_count: 0,
      size_bytes: RAW_BYTES.byteLength,
      received_bytes: 0,
      missing_ranges: [{ start: 0, end: 0 }]
    }],
    receipt: null
  };
}

function commonProofInput(method: "HEAD" | "GET" | "PUT" | "POST"):
RawCollectionBackendProofInput {
  const payload = manifest();
  return {
    actorId: ACTOR_ID,
    accountGeneration: 1,
    operation: method === "GET" ? "GET_STATUS" : "PUT_MANIFEST",
    method,
    path: method === "GET"
      ? `/raw-collections/${COLLECTION_ID}`
      : `/raw-collections/${COLLECTION_ID}/manifest`,
    purpose: "GENERAL_RAW",
    walkId: WALK_ID,
    manifestSha256: String(payload.manifest_sha256),
    consentReceiptSha256: method === "GET"
      ? null
      : confirmation.backend_consent_receipt_sha256,
    chunkSha256: null,
    commitSha256: null
  };
}

function assertGeneratedProof(
  headers: Headers,
  input: RawCollectionBackendProofInput
): string {
  assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
  assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR_ID);
  assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
  assert.match(headers.get(ACTOR_ASSERTION_HEADER) ?? "", /^v2\.\d+\.[A-Za-z0-9_-]{43}$/);
  const proof = headers.get(RAW_REQUEST_PROOF_HEADER) ?? "";
  const issuedAt = Number(proof.split(".")[1]);
  assert.equal(proof, createRawCollectionRequestProof(input, issuedAt));
  return proof;
}

test("raw proof bytes match the Backend golden contract and GET HEAD is not signable", () => {
  const input: RawCollectionBackendProofInput = {
    actorId: "raw.gateway.actor",
    accountGeneration: 3,
    operation: "PUT_CHUNK",
    method: "PUT",
    path: "/raw-collections/00000000-0000-0000-0000-000000000001/objects/00000000-0000-0000-0000-000000000002/chunks/7",
    purpose: "AUTO_REPORT",
    walkId: "00000000-0000-0000-0000-000000000003",
    manifestSha256: "a".repeat(64),
    consentReceiptSha256: "b".repeat(64),
    chunkSha256: "c".repeat(64),
    commitSha256: null
  };
  const issuedAt = 1_725_000_000;
  assert.equal(
    rawCollectionRequestProofMessage(input, issuedAt)?.toString("utf8"),
    "walksafe/raw-collection-request-proof/v1\0" +
      '{"account_generation":3,"actor_id":"raw.gateway.actor","chunk_sha256":"' +
      "c".repeat(64) + '","commit_sha256":null,"consent_receipt_sha256":"' +
      "b".repeat(64) + '","issued_at":1725000000,"manifest_sha256":"' +
      "a".repeat(64) +
      '","method":"PUT","operation":"PUT_CHUNK","path":"/raw-collections/00000000-0000-0000-0000-000000000001/objects/00000000-0000-0000-0000-000000000002/chunks/7","purpose":"AUTO_REPORT","version":1,"walk_id":"00000000-0000-0000-0000-000000000003"}'
  );
  assert.equal(
    createRawCollectionRequestProof(input, issuedAt),
    "v1.1725000000.ytYcIUmx5U6OS4DLInKgHBiso8B_dj1NnwXYesemckg"
  );
  assert.equal(rawCollectionRequestProofMessage({
    ...input,
    operation: "GET_STATUS",
    method: "HEAD",
    path: `/raw-collections/${COLLECTION_ID}`,
    consentReceiptSha256: null,
    chunkSha256: null
  }, issuedAt), null);
});

test("raw route matcher exposes only the four exact canonical paths", () => {
  assert.equal(matchRawCollectionRoute(
    `/api/raw-collections/${COLLECTION_ID}/manifest`
  )?.operation, "PUT_MANIFEST");
  assert.equal(matchRawCollectionRoute(
    `/api/raw-collections/${COLLECTION_ID}/objects/${OBJECT_ID}/chunks/2047`
  )?.operation, "PUT_CHUNK");
  assert.equal(matchRawCollectionRoute(`/api/raw-collections/${COLLECTION_ID}`)?.operation,
    "GET_STATUS");
  assert.equal(matchRawCollectionRoute(
    `/api/raw-collections/${COLLECTION_ID}/commit`
  )?.operation, "COMMIT");
  assert.equal(matchRawCollectionRoute(
    `/api/raw-collections/${COLLECTION_ID}/objects/${OBJECT_ID}/chunks/2048`
  ), null);
  assert.equal(matchRawCollectionRoute(`/api/raw-collections/${COLLECTION_ID}/`), null);
});

test("write gates run before body read and only the Backend receipt has authority", async () => {
  resetRawCollectionRelayForTests();
  const body = canonicalJson(manifest());
  let calls = 0;
  const auditRequest = rawWriteRequest("/manifest", body, {
    [RAW_CONSENT_RECEIPT_SHA256_HEADER]: confirmation.gateway_audit_record_sha256
  });
  const auditDenied = await handleGatewayRequest(auditRequest, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async () => { calls += 1; return assert.fail("must not fetch"); }
  });
  assert.equal(auditDenied.status, 409);
  assert.equal(auditRequest.bodyUsed, false);
  assert.equal(calls, 0);

  const preflightRequest = rawWriteRequest("/manifest", body);
  const preflightDenied = await handleGatewayRequest(preflightRequest, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async (_input, init) => {
      calls += 1;
      assert.equal(init?.method, "HEAD");
      return Response.json({
        detail: { code: "raw_ingest_disabled", message: "disabled" }
      }, { status: 503 });
    }
  });
  assert.equal(preflightDenied.status, 503);
  assert.equal(preflightRequest.bodyUsed, false);
  assert.equal(calls, 1);

  const cellularRequest = rawWriteRequest("/manifest", body, {
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "cellular"
  });
  const cellularDenied = await handleGatewayRequest(cellularRequest, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async () => { calls += 1; return assert.fail("must not fetch"); }
  });
  assert.equal(cellularDenied.status, 422);
  assert.equal(cellularRequest.bodyUsed, false);

  let resolutions = 0;
  const changesDuringConsent = rawWriteRequest("/manifest", body);
  const stale = await handleGatewayRequest(changesDuringConsent, {
    fieldLongSessionBindingResolver: () => {
      resolutions += 1;
      return resolutions < 3
        ? bindingResolver()
        : { ...bindingResolver()!, sessionRotation: 8 };
    },
    fetchImpl: async () => { calls += 1; return assert.fail("must not fetch"); }
  });
  assert.equal(stale.status, 409);
  assert.equal(changesDuringConsent.bodyUsed, false);
});

test("manifest uses HEAD then actual proof, projects strict status, and GET recovers after walk end", async () => {
  resetRawCollectionRelayForTests();
  currentBinding = {
    ...gatewayFieldLongSessionBinding(new Request(
      "https://gateway.invalid/api/field-walk",
      { headers: { cookie: sessionCookie } }
    ))!
  };
  const body = canonicalJson(manifest());
  let calls = 0;
  let headProof = "";
  const fetchImpl: GatewayFetch = async (input, init) => {
    calls += 1;
    assert.equal(String(input), `http://127.0.0.1:8000/raw-collections/${COLLECTION_ID}/manifest`);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(RAW_PURPOSE_HEADER), "GENERAL_RAW");
    assert.equal(headers.get(RAW_WALK_ID_HEADER), WALK_ID);
    assert.equal(headers.get(RAW_CONSENT_RECEIPT_SHA256_HEADER),
      confirmation.backend_consent_receipt_sha256);
    if (init?.method === "HEAD") {
      headProof = assertGeneratedProof(headers, commonProofInput("HEAD"));
      assert.equal(init.body, undefined);
      return new Response(null, { status: 204 });
    }
    assert.equal(init?.method, "PUT");
    assert.notEqual(assertGeneratedProof(headers, commonProofInput("PUT")), headProof);
    assert.equal(Buffer.from(init.body as Uint8Array).toString("utf8"), body);
    return Response.json(statusPayload(), {
      status: 201,
      headers: {
        "set-cookie": "backend-cookie=must-not-leak",
        [ACTOR_ASSERTION_HEADER]: "must-not-leak"
      }
    });
  };
  const response = await handleGatewayRequest(rawWriteRequest("/manifest", body), {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl
  });
  assert.equal(response.status, 201);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("set-cookie"), null);
  assert.equal(response.headers.get(ACTOR_ASSERTION_HEADER), null);
  assert.deepEqual(await response.json(), statusPayload());
  assert.equal(calls, 2);

  const ended = commandFieldWalk(currentBinding!, parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: "raw-relay-walk-end-0001",
    action: "end",
    walk_id: WALK_ID,
    lease_id: walkLeaseId,
    fencing_token: walkFence
  })!);
  assert.equal(ended.status, 200);
  const payload = manifest();
  const statusRequest = new Request(
    `https://gateway.invalid/api/raw-collections/${COLLECTION_ID}`,
    { headers: {
      cookie: sessionCookie,
      [RAW_PURPOSE_HEADER]: "GENERAL_RAW",
      [RAW_WALK_ID_HEADER]: WALK_ID,
      [RAW_MANIFEST_SHA256_HEADER]: String(payload.manifest_sha256)
    } }
  );
  let statusCalls = 0;
  const recovered = await handleGatewayRequest(statusRequest, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async (input, init) => {
      statusCalls += 1;
      assert.equal(String(input), `http://127.0.0.1:8000/raw-collections/${COLLECTION_ID}`);
      assert.equal(init?.method, "GET");
      const headers = new Headers(init?.headers);
      assertGeneratedProof(headers, commonProofInput("GET"));
      assert.equal(headers.get(RAW_CONSENT_RECEIPT_SHA256_HEADER), null);
      return Response.json(statusPayload());
    }
  });
  assert.equal(recovered.status, 200);
  assert.deepEqual(await recovered.json(), statusPayload());
  assert.equal(statusCalls, 1);
});

test("new commit rejects a legacy v1 receipt even with its exact 180-day contract", async () => {
  const restarted = commandFieldWalk(currentBinding!, parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: "raw-relay-walk-restart-0001",
    action: "start",
    walk_id: WALK_ID
  })!);
  assert.equal(restarted.status, 200);
  const payload = manifest();
  const commitBody: Record<string, unknown> = {
    schema_version: "walksafe.raw-collection-commit.v1",
    collection_id: COLLECTION_ID,
    manifest_sha256: payload.manifest_sha256,
    object_count: 1,
    chunk_count: 1,
    total_bytes: RAW_BYTES.byteLength
  };
  const body = canonicalJson(commitBody);
  const commitSha256 = contractDigest("walksafe/raw-collection-commit/v1\0", commitBody);
  const committedAt = "2026-08-29T00:00:00Z";
  const receiptUnsigned: Record<string, unknown> = {
    schema_version: "walksafe.raw-collection-receipt.v1",
    collection_id: COLLECTION_ID,
    manifest_sha256: payload.manifest_sha256,
    purpose: "GENERAL_RAW",
    persistence_marker: "DATABASE_AND_ENCRYPTED_CHUNK_STORE",
    object_count: 1,
    chunk_count: 1,
    total_bytes: RAW_BYTES.byteLength,
    objects: [{
      object_id: OBJECT_ID,
      kind: "VIDEO",
      size_bytes: RAW_BYTES.byteLength,
      sha256: RAW_SHA256,
      chunk_count: 1
    }],
    retention_class: "RAW_ORIGINAL_180D",
    committed_at: committedAt,
    retention_expires_at: new Date(
      Date.parse(committedAt) + 180 * 24 * 60 * 60 * 1_000
    ).toISOString().replace(".000Z", "Z")
  };
  const legacyReceipt = {
    ...receiptUnsigned,
    receipt_sha256: contractDigest(
      "walksafe/raw-collection-receipt/v1\0",
      receiptUnsigned
    )
  };
  let calls = 0;
  const response = await handleGatewayRequest(rawWriteRequest("/commit", body, {
    [RAW_COMMIT_SHA256_HEADER]: commitSha256
  }), {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async (_input, init) => {
      calls += 1;
      if (init?.method === "HEAD") return new Response(null, { status: 204 });
      assert.equal(init?.method, "POST");
      return Response.json(legacyReceipt);
    }
  });
  assert.equal(calls, 2);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), {
    detail: {
      code: "gateway_raw_upstream_invalid",
      message: "The Backend response was invalid."
    }
  });

  const legacyStatus = {
    ...statusPayload(),
    state: "COMMITTED",
    received_chunk_count: 1,
    received_bytes: RAW_BYTES.byteLength,
    objects: [{
      object_id: OBJECT_ID,
      kind: "VIDEO",
      sha256: RAW_SHA256,
      chunk_count: 1,
      received_chunk_count: 1,
      size_bytes: RAW_BYTES.byteLength,
      received_bytes: RAW_BYTES.byteLength,
      missing_ranges: []
    }],
    receipt: legacyReceipt
  };
  const recovered = await handleGatewayRequest(new Request(
    `https://gateway.invalid/api/raw-collections/${COLLECTION_ID}`,
    { headers: {
      cookie: sessionCookie,
      [RAW_PURPOSE_HEADER]: "GENERAL_RAW",
      [RAW_WALK_ID_HEADER]: WALK_ID,
      [RAW_MANIFEST_SHA256_HEADER]: String(payload.manifest_sha256)
    } }
  ), {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async () => Response.json(legacyStatus)
  });
  assert.equal(recovered.status, 200);
  assert.deepEqual(await recovered.json(), legacyStatus);
});

test("chunk relays HEAD then PUT with a strict ack and blocks actual PUT on SHA or length mismatch", async () => {
  resetRawCollectionRelayForTests();
  const payload = manifest();
  const chunkRequest = (
    digest: string,
    declaredLength = RAW_BYTES.byteLength
  ): Request => new Request(
    `https://gateway.invalid/api/raw-collections/${COLLECTION_ID}` +
      `/objects/${OBJECT_ID}/chunks/0`,
    {
      method: "PUT",
      headers: {
        cookie: sessionCookie,
        "content-type": "application/octet-stream",
        "content-length": String(declaredLength),
        [RAW_PURPOSE_HEADER]: "GENERAL_RAW",
        [RAW_WALK_ID_HEADER]: WALK_ID,
        [RAW_MANIFEST_SHA256_HEADER]: String(payload.manifest_sha256),
        [RAW_CHUNK_SHA256_HEADER]: digest,
        [RAW_CONSENT_RECEIPT_SHA256_HEADER]:
          confirmation.backend_consent_receipt_sha256,
        [CONSENT_INSTALLATION_HEADER]: INSTALLATION_ID,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
        [CONSENT_POLICY_HEADER]: confirmation.policy_version,
        [CONSENT_REVISION_HEADER]: String(confirmation.revision),
        [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi"
      },
      body: RAW_BYTES
    }
  );
  const ack = {
    schema_version: "walksafe.raw-collection-chunk-ack.v1",
    collection_id: COLLECTION_ID,
    object_id: OBJECT_ID,
    index: 0,
    size_bytes: RAW_BYTES.byteLength,
    sha256: RAW_SHA256,
    state: "READY_TO_COMMIT",
    stored_at: "2026-08-29T00:00:07Z"
  };
  const methods: string[] = [];
  const accepted = await handleGatewayRequest(chunkRequest(RAW_SHA256), {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async (input, init) => {
      methods.push(String(init?.method));
      assert.equal(
        String(input),
        `http://127.0.0.1:8000/raw-collections/${COLLECTION_ID}` +
          `/objects/${OBJECT_ID}/chunks/0`
      );
      if (init?.method === "HEAD") return new Response(null, { status: 204 });
      assert.equal(init?.method, "PUT");
      assert.equal(new Headers(init.headers).get(RAW_CHUNK_SHA256_HEADER), RAW_SHA256);
      assert.deepEqual(Buffer.from(init.body as Uint8Array), RAW_BYTES);
      return Response.json(ack, { status: 201 });
    }
  });
  assert.equal(accepted.status, 201);
  assert.deepEqual(await accepted.json(), ack);
  assert.deepEqual(methods, ["HEAD", "PUT"]);

  for (const [request, expectedCode] of [
    [chunkRequest("f".repeat(64)), "raw_chunk_sha256_mismatch"],
    [chunkRequest(RAW_SHA256, RAW_BYTES.byteLength + 1), "raw_body_length_mismatch"]
  ] as const) {
    const attemptedMethods: string[] = [];
    const denied = await handleGatewayRequest(request, {
      fieldLongSessionBindingResolver: bindingResolver,
      fetchImpl: async (_input, init) => {
        attemptedMethods.push(String(init?.method));
        if (init?.method === "HEAD") return new Response(null, { status: 204 });
        return assert.fail("digest or length mismatch must not reach actual PUT");
      }
    });
    assert.equal(denied.status, 400);
    assert.equal((await denied.json() as { detail: { code: string } }).detail.code,
      expectedCode);
    assert.deepEqual(attemptedMethods, ["HEAD"]);
    assert.equal(request.bodyUsed, true);
  }
});

test("stalled authenticated PUT times out and releases raw write admission", async () => {
  resetRawCollectionRelayForTests();
  const body = canonicalJson(manifest());
  const template = rawWriteRequest("/manifest", body);
  const cancelAbort = new AbortController();
  let stalledController!: ReadableStreamDefaultController<Uint8Array>;
  let cancelReason: unknown;
  const stalledRequest = new Request(template.url, {
    method: "PUT",
    headers: template.headers,
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        stalledController = controller;
      },
      pull: () => new Promise<void>(() => undefined),
      cancel(reason) {
        cancelReason = reason;
        cancelAbort.abort(reason);
      }
    }),
    signal: cancelAbort.signal,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const stalledMethods: string[] = [];
  const startedAt = Date.now();
  const stalledResponsePromise = handleGatewayRequest(stalledRequest, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async (_input, init) => {
      stalledMethods.push(String(init?.method));
      assert.equal(init?.method, "HEAD");
      return new Response(null, { status: 204 });
    }
  });
  let guard: NodeJS.Timeout | undefined;
  try {
    const stalledResponse = await Promise.race([
      stalledResponsePromise,
      new Promise<Response>((_resolve, reject) => {
        guard = setTimeout(() => {
          try { stalledController.error(new Error("raw body deadline test guard")); } catch {}
          reject(new Error("stalled raw body did not reach its 15 second deadline"));
        }, 30_000);
      })
    ]);
    const elapsedMs = Date.now() - startedAt;
    assert.equal(stalledResponse.status, 408);
    assert.equal(
      (await stalledResponse.json() as { detail: { code: string } }).detail.code,
      "raw_body_read_timeout"
    );
    assert.ok(elapsedMs >= 14_000 && elapsedMs < 30_000, `elapsed ${elapsedMs}ms`);
    assert.deepEqual(stalledMethods, ["HEAD"]);
    assert.equal(stalledRequest.bodyUsed, true);
    assert.equal(cancelReason, "raw body read cancelled");
    assert.equal(stalledRequest.signal.aborted, true);

    const recoveredMethods: string[] = [];
    const recovered = await handleGatewayRequest(rawWriteRequest("/manifest", body), {
      fieldLongSessionBindingResolver: bindingResolver,
      fetchImpl: async (_input, init) => {
        recoveredMethods.push(String(init?.method));
        if (init?.method === "HEAD") return new Response(null, { status: 204 });
        assert.equal(init?.method, "PUT");
        return Response.json(statusPayload(), { status: 201 });
      }
    });
    assert.equal(recovered.status, 201);
    assert.deepEqual(await recovered.json(), statusPayload());
    assert.deepEqual(recoveredMethods, ["HEAD", "PUT"]);
  } finally {
    if (guard) clearTimeout(guard);
    try { stalledController.error(new Error("raw body deadline test finished")); } catch {}
  }
});

test("client abort during a stalled raw PUT remains 499", async () => {
  resetRawCollectionRelayForTests();
  const body = canonicalJson(manifest());
  const template = rawWriteRequest("/manifest", body);
  const clientAbort = new AbortController();
  let stalledController!: ReadableStreamDefaultController<Uint8Array>;
  let cancelReason: unknown;
  const request = new Request(template.url, {
    method: "PUT",
    headers: template.headers,
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        stalledController = controller;
      },
      pull: () => new Promise<void>(() => undefined),
      cancel(reason) {
        cancelReason = reason;
      }
    }),
    signal: clientAbort.signal,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  let markBodyOpened!: () => void;
  const bodyOpened = new Promise<void>((resolve) => { markBodyOpened = resolve; });
  const requestBody = request.body!;
  const originalGetReader = requestBody.getReader.bind(requestBody);
  Object.defineProperty(requestBody, "getReader", {
    configurable: true,
    value: () => {
      markBodyOpened();
      return originalGetReader();
    }
  });
  const methods: string[] = [];
  const responsePromise = handleGatewayRequest(request, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async (_input, init) => {
      methods.push(String(init?.method));
      assert.equal(init?.method, "HEAD");
      return new Response(null, { status: 204 });
    }
  });
  let guard: NodeJS.Timeout | undefined;
  const guardFailure = new Promise<never>((_resolve, reject) => {
    guard = setTimeout(() => {
      try { stalledController.error(new Error("client abort test guard")); } catch {}
      reject(new Error("client abort did not settle the stalled raw body read"));
    }, 5_000);
  });
  try {
    await Promise.race([bodyOpened, guardFailure]);
    clientAbort.abort(new Error("client disconnected"));
    const response = await Promise.race([responsePromise, guardFailure]);
    assert.equal(response.status, 499);
    assert.equal(
      (await response.json() as { detail: { code: string } }).detail.code,
      "gateway_client_closed"
    );
    assert.deepEqual(methods, ["HEAD"]);
    assert.equal(request.signal.aborted, true);
    assert.equal(cancelReason, "raw body read cancelled");
  } finally {
    if (guard) clearTimeout(guard);
    try { stalledController.error(new Error("client abort test finished")); } catch {}
  }
});

test("valid commit projects quarantine receipt v2 while a concurrent second write is busy", async () => {
  resetRawCollectionRelayForTests();
  const payload = manifest();
  const commitPayload: Record<string, unknown> = {
    schema_version: "walksafe.raw-collection-commit.v1",
    collection_id: COLLECTION_ID,
    manifest_sha256: payload.manifest_sha256,
    object_count: 1,
    chunk_count: 1,
    total_bytes: RAW_BYTES.byteLength
  };
  const commitBody = canonicalJson(commitPayload);
  const commitSha256 = contractDigest(
    "walksafe/raw-collection-commit/v1\0",
    commitPayload
  );
  const committedAt = "2026-08-29T00:00:00Z";
  const receiptUnsigned: Record<string, unknown> = {
    schema_version: "walksafe.raw-collection-receipt.v2",
    collection_id: COLLECTION_ID,
    manifest_sha256: payload.manifest_sha256,
    purpose: "GENERAL_RAW",
    persistence_marker: "DATABASE_AND_ENCRYPTED_CHUNK_STORE",
    object_count: 1,
    chunk_count: 1,
    total_bytes: RAW_BYTES.byteLength,
    objects: [{
      object_id: OBJECT_ID,
      kind: "VIDEO",
      size_bytes: RAW_BYTES.byteLength,
      sha256: RAW_SHA256,
      chunk_count: 1
    }],
    retention_class: "RAW_QUARANTINE_14D",
    committed_at: committedAt,
    quarantine_expires_at: new Date(
      Date.parse(committedAt) + 14 * 24 * 60 * 60 * 1_000
    ).toISOString().replace(".000Z", "Z")
  };
  const receipt = {
    ...receiptUnsigned,
    receipt_sha256: contractDigest(
      "walksafe/raw-collection-receipt/v2\0",
      receiptUnsigned
    )
  };
  const firstRequest = rawWriteRequest("/commit", commitBody, {
    [RAW_COMMIT_SHA256_HEADER]: commitSha256
  });
  let markHeadStarted!: () => void;
  let releaseHead!: () => void;
  const headStarted = new Promise<void>((resolve) => { markHeadStarted = resolve; });
  const headMayFinish = new Promise<void>((resolve) => { releaseHead = resolve; });
  const firstMethods: string[] = [];
  const firstResponsePromise = handleGatewayRequest(firstRequest, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async (_input, init) => {
      firstMethods.push(String(init?.method));
      if (init?.method === "HEAD") {
        markHeadStarted();
        await headMayFinish;
        return new Response(null, { status: 204 });
      }
      assert.equal(init?.method, "POST");
      return Response.json(receipt, {
        status: 200,
        headers: { "set-cookie": "backend-secret=must-not-leak" }
      });
    }
  });
  await headStarted;

  const secondRequest = rawWriteRequest("/commit", commitBody, {
    [RAW_COMMIT_SHA256_HEADER]: commitSha256
  });
  const busy = await handleGatewayRequest(secondRequest, {
    fieldLongSessionBindingResolver: bindingResolver,
    fetchImpl: async () => assert.fail("busy write must not reach Backend")
  });
  assert.equal(busy.status, 503);
  assert.equal((await busy.json() as { detail: { code: string } }).detail.code,
    "raw_collection_busy");
  assert.equal(busy.headers.get("retry-after"), "1");
  assert.equal(secondRequest.bodyUsed, false);

  releaseHead();
  const committed = await firstResponsePromise;
  assert.equal(committed.status, 200);
  assert.equal(committed.headers.get("set-cookie"), null);
  assert.deepEqual(await committed.json(), receipt);
  assert.deepEqual(firstMethods, ["HEAD", "POST"]);
});
