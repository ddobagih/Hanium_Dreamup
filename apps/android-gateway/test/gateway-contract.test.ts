import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { mkdtemp, rm } from "node:fs/promises";

import {
  establishBackendGatewaySession,
  isGatewayAccessConfigured,
  SHORT_SESSION_MAX_PLAINTEXT_BYTES
} from "../src/auth.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson
} from "../src/encrypted-json-store.js";
import { withExclusiveFileLockAsync } from "../src/exclusive-file-lock.js";
import {
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  ACCOUNT_GENERATION_HEADER,
  acquireImageUploadAdmission,
  countMultipartDelimiters,
  FIELD_TEST_TOKEN_HEADER,
  fetchBackend,
  IMAGE_MULTIPART_LIMIT_BYTES,
  REPORT_IMAGE_LIMIT_BYTES,
  resetImageUploadAdmissionForTests,
  type GatewayFetch
} from "../src/backend.js";
import {
  resolveBackendBaseUrl,
  resolveBindAddress,
  resolvePrivacyRightsRequestUrl
} from "../src/config.js";
import {
  handleGatewayRequest,
  PUBLIC_GATEWAY_ROUTES,
  REPORT_ID_HEADER,
  REPORT_PAYLOAD_BYTES_HEADER,
  REPORT_PAYLOAD_SHA256_HEADER
} from "../src/routes.js";
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
import { configureTestStateEncryption } from "./state-encryption-fixture.js";
import {
  ACCOUNT_DELETION_INVENTORY_V2,
  DELETION_ACCESS_SECRET_HEADER,
  type AccountDeletionStatusV2
} from "../src/privacy-deletion-v2.js";
import {
  abortPrivacyOperationsForAccountDeletion,
  bindBackendActorGeneration,
  currentActorGeneration
} from "../src/privacy-rights.js";

const ACTOR_ID = "field-operator";
const ACCOUNT_TOKEN = "field-account-token-12345678901234567890";
const INTERNAL_TOKEN = "field-internal-token-12345678901234567890";
const SESSION_SECRET = "gateway-session-secret-123456789012345678901234567890";

let stateDirectory = "";
let nextIpSuffix = 10;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-android-gateway-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  delete process.env.WALKSAFE_ADMIN_TOKEN;
  delete process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON;
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function nextClientIp(): string {
  nextIpSuffix += 1;
  return `198.51.100.${nextIpSuffix}`;
}

async function login(extraHeaders: HeadersInit = {}): Promise<string> {
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextClientIp(),
        ...Object.fromEntries(new Headers(extraHeaders))
      },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
    })
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { session_scope: "general" });
  const setCookie = response.headers.get("set-cookie") ?? "";
  assert.match(setCookie, /^walksafe_field_session=[^;]+;/);
  return setCookie.split(";", 1)[0]!;
}

function backendAccountSessionCookie(deviceId: string | null): string {
  bindBackendActorGeneration(ACTOR_ID, 1);
  const response = establishBackendGatewaySession(
    new Request("https://walksafe.example/api/field-session"),
    {
      actorId: ACTOR_ID,
      accountGeneration: 1,
      authEpoch: 1,
      ...(deviceId === null ? {} : { deviceId })
    },
    false
  );
  assert.equal(response.status, 200);
  const setCookie = response.headers.get("set-cookie") ?? "";
  assert.match(setCookie, deviceId === null
    ? /^walksafe_field_session=v6\./
    : /^walksafe_field_session=v7\./);
  return setCookie.split(";", 1)[0]!;
}

async function holdExclusiveLock(lockPath: string): Promise<() => Promise<void>> {
  let markAcquired!: () => void;
  let rejectAcquired!: (error: unknown) => void;
  const acquired = new Promise<void>((resolve, reject) => {
    markAcquired = resolve;
    rejectAcquired = reject;
  });
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const holding = withExclusiveFileLockAsync(lockPath, async () => {
    markAcquired();
    await held;
  });
  void holding.catch(rejectAcquired);
  await acquired;
  return async () => {
    release();
    await holding;
  };
}

async function reportConsentHeaders(cookie: string): Promise<Record<string, string>> {
  const installationId = "9c68097a-0569-43b6-baa2-1ed35f514f14";
  const controlSecret = "c".repeat(64);
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
      method: "PUT",
      headers: {
        cookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: controlSecret
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: "gateway_contract_report_consent_0001",
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 1,
        expected_previous_backend_receipt_sha256: null,
        selections: {
          raw_source_collection: true,
          automatic_reporting: true,
          mobile_network_transfer: false,
          training_reuse: false
        }
      })
    }),
    {
      fetchImpl: async (input, init) => {
        assert.equal(
          String(input),
          "http://127.0.0.1:8000/privacy/consent-events"
        );
        const event = JSON.parse(String(init?.body)) as {
          request_id: string;
          client_revision: number;
        };
        return Response.json({
          schema_version: "walksafe.privacy-consent-receipt.v2",
          request_id: event.request_id,
          client_revision: event.client_revision,
          receipt_sha256: createHash("sha256")
            .update(String(init?.body))
            .digest("hex"),
          recorded_at: "2026-08-09T12:05:00Z"
        }, { status: 201 });
      }
    }
  );
  assert.ok(response.status === 200 || response.status === 201);
  const confirmation = await response.json() as IntegratedConsentConfirmation;
  return {
    [CONSENT_INSTALLATION_HEADER]: confirmation.installation_id,
    [CONSENT_CONTROL_SECRET_HEADER]: controlSecret,
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi",
    "x-walksafe-report-purpose": "explicit",
    [CONSENT_POLICY_HEADER]: confirmation.policy_version,
    [CONSENT_REVISION_HEADER]: String(confirmation.revision),
    [CONSENT_RECEIPT_HEADER]: confirmation.backend_consent_receipt_sha256
  };
}

test("OpenAPI and router expose service, consent-control, and deletion paths", async () => {
  const raw = await readFile(path.join(process.cwd(), "openapi.json"), "utf8");
  const contract = JSON.parse(raw) as {
    info: { version: string };
    paths: Record<string, Record<string, unknown>>;
    components: { schemas: Record<string, unknown> };
  };
  const expected = [
    "/api/account-enrollments/email-otp",
    "/api/accounts",
    "/api/field-session",
    "/api/field-walk",
    "/api/speech/stt",
    "/api/speech/tts",
    "/api/navigation/walking",
    "/api/navigation/destinations/search",
    "/api/reports/v2",
    "/api/reports/v2/{report_id}/status",
    "/api/reports/mine",
    "/api/reports/mine/deletions/{request_id}",
    "/api/reports/mine/{report_id}",
    "/api/reports/mine/{report_id}/content",
    "/api/reports/mine/{report_id}/corrections",
    "/api/reports/mine/{report_id}/requests",
    "/api/raw-collections/{collection_id}/manifest",
    "/api/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}",
    "/api/raw-collections/{collection_id}",
    "/api/raw-collections/{collection_id}/commit",
    "/privacy/rights",
    "/privacy/account-deletions",
    "/privacy/account-deletions/{request_id}/status",
    "/privacy/account-deletions/{request_id}/device-evidence"
  ];
  assert.deepEqual(Object.keys(contract.paths), expected);
  assert.deepEqual(PUBLIC_GATEWAY_ROUTES, expected);
  assert.deepEqual(Object.keys(contract.paths[expected[0]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[1]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[2]!]!).sort(), ["delete", "get", "post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[3]!]!).sort(), ["get", "post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[4]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[5]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[6]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[7]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[8]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[9]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[10]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[11]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[12]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[13]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[14]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[15]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[16]!]!), ["put"]);
  assert.deepEqual(Object.keys(contract.paths[expected[17]!]!), ["put"]);
  assert.deepEqual(Object.keys(contract.paths[expected[18]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[19]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[20]!]!).sort(), ["get", "put"]);
  assert.deepEqual(Object.keys(contract.paths[expected[21]!]!), ["post"]);
  assert.deepEqual(Object.keys(contract.paths[expected[22]!]!), ["get"]);
  assert.deepEqual(Object.keys(contract.paths[expected[23]!]!), ["post"]);
  assert.equal(contract.info.version, "0.11.0");
  const rawManifest = contract.paths[expected[16]!]!.put as {
    "x-max-body-bytes": number;
    parameters: Array<{ $ref: string }>;
  };
  const rawChunk = contract.paths[expected[17]!]!.put as {
    "x-max-body-bytes": number;
    parameters: Array<{ $ref: string }>;
  };
  const rawStatus = contract.paths[expected[18]!]!.get as {
    parameters: Array<{ $ref: string }>;
  };
  const rawCommit = contract.paths[expected[19]!]!.post as {
    "x-max-body-bytes": number;
    parameters: Array<{ $ref: string }>;
  };
  assert.equal(rawManifest["x-max-body-bytes"], 512 * 1024);
  assert.equal(rawChunk["x-max-body-bytes"], 8 * 1024 * 1024);
  assert.equal(rawCommit["x-max-body-bytes"], 16 * 1024);
  assert.equal(rawManifest.parameters.some(item =>
    item.$ref.endsWith("/RawConsentReceiptSha256")), true);
  assert.equal(rawChunk.parameters.some(item => item.$ref.endsWith("/RawChunkSha256")), true);
  assert.equal(rawCommit.parameters.some(item => item.$ref.endsWith("/RawCommitSha256")), true);
  assert.equal(rawStatus.parameters.some(item =>
    item.$ref.endsWith("/RawConsentReceiptSha256")), false);
  const reportOperation = (
    contract as unknown as {
      paths: Record<string, {
        post?: {
          parameters?: Array<{ name: string; in: string; required?: boolean }>;
          responses?: Record<string, unknown>;
        };
      }>;
    }
  ).paths["/api/reports/v2"]!.post!;
  const requiredReportHeaders = (reportOperation.parameters ?? [])
    .filter((parameter) => parameter.in === "header" && parameter.required)
    .map((parameter) => parameter.name.toLowerCase());
  for (const header of [
    CONSENT_INSTALLATION_HEADER,
    CONSENT_CONTROL_SECRET_HEADER,
    CONSENT_NETWORK_TRANSPORT_HEADER,
    CONSENT_POLICY_HEADER,
    CONSENT_REVISION_HEADER,
    CONSENT_RECEIPT_HEADER,
    "x-walksafe-report-purpose"
  ]) {
    assert.ok(requiredReportHeaders.includes(header));
  }
  const optionalReportHeaders = (reportOperation.parameters ?? [])
    .filter((parameter) => parameter.in === "header" && !parameter.required)
    .map((parameter) => parameter.name.toLowerCase());
  assert.deepEqual(optionalReportHeaders, [
    "x-walksafe-report-id",
    "x-walksafe-report-payload-sha256",
    "x-walksafe-report-payload-bytes"
  ]);
  assert.ok(Object.hasOwn(reportOperation.responses ?? {}, "428"));
  for (const schema of [
    "FieldShortSession",
    "FieldSessionScope",
    "FieldSessionRefreshProof",
    "FieldSessionRefresh",
    "FieldLongSession",
    "FieldLongSessionStatus",
    "FieldSessionDeviceList"
  ]) {
    assert.ok(schema in contract.components.schemas);
  }
  const loginSchema = contract.components.schemas.FieldSessionLogin as {
    allOf?: Array<{ not?: { required?: string[]; properties?: Record<string, unknown> } }>;
  };
  assert.deepEqual(loginSchema.allOf, [{
    not: {
      required: ["purpose", "device_id"],
      properties: { purpose: { const: "account_deletion_recovery" } }
    }
  }]);
  const passwordLoginSchema = contract.components.schemas.AccountPasswordSessionLogin as {
    required?: string[];
    properties?: Record<string, { pattern?: string }>;
    additionalProperties?: boolean;
  };
  assert.deepEqual(passwordLoginSchema.required, [
    "grant_type", "email", "password", "remember_me"
  ]);
  assert.equal(
    passwordLoginSchema.properties?.device_id?.pattern,
    "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
  );
  assert.equal(passwordLoginSchema.additionalProperties, false);
  const detailed = contract as unknown as {
    paths: Record<string, Record<string, {
      description?: string;
      security?: Array<Record<string, unknown>>;
      parameters?: Array<{ name: string; in: string; required?: boolean }>;
      responses?: Record<string, { $ref?: string }>;
    }>>;
    components: {
      schemas: Record<string, {
        required?: string[];
        properties?: Record<string, { const?: unknown }>;
        additionalProperties?: boolean;
      }>;
      responses: Record<string, {
        content?: { "application/json"?: { schema?: { oneOf?: Array<{ $ref: string }> } } };
      }>;
      securitySchemes: Record<string, { in?: string; name?: string }>;
    };
  };
  const consentGet = detailed.paths["/privacy/rights"]!.get!;
  const consentPut = detailed.paths["/privacy/rights"]!.put!;
  assert.deepEqual(
    consentGet.parameters?.map(({ name, in: location, required }) => ({
      name,
      in: location,
      required
    })),
    [
      { name: "control", in: "query", required: true },
      { name: "installation_id", in: "query", required: true },
      { name: "policy_version", in: "query", required: true }
    ]
  );
  const reportStatus = detailed.paths["/api/reports/v2/{report_id}/status"]!.get!;
  assert.deepEqual(reportStatus.security, [{ fieldSession: [] }]);
  assert.equal(
    reportStatus.responses?.["200"]?.$ref,
    "#/components/responses/ReportTransportStatus"
  );
  assert.equal(
    detailed.components.schemas.ReportTransportReceipt!.additionalProperties,
    false
  );
  assert.equal(
    detailed.components.schemas.ReportTransportStatus!.additionalProperties,
    false
  );
  const userList = detailed.paths["/api/reports/mine"]!.get!;
  const userDeletion = detailed.paths["/api/reports/mine/deletions/{request_id}"]!.get!;
  const userDetail = detailed.paths["/api/reports/mine/{report_id}"]!.get!;
  const userContent = detailed.paths["/api/reports/mine/{report_id}/content"]!.get!;
  const userCorrection = detailed.paths["/api/reports/mine/{report_id}/corrections"]!.post!;
  const userRequest = detailed.paths["/api/reports/mine/{report_id}/requests"]!.post!;
  for (const operation of [
    userList,
    userDeletion,
    userDetail,
    userContent,
    userCorrection,
    userRequest
  ]) {
    assert.deepEqual(operation.security, [{ fieldSession: [] }]);
    assert.match(operation.description ?? "", /current v7/i);
    assert.match(operation.description ?? "", /consent is not required/i);
    assert.doesNotMatch(
      JSON.stringify(operation.parameters ?? []),
      /x-walksafe-consent|consentControlSecret/i
    );
  }
  assert.equal(userList.responses?.["200"]?.$ref, "#/components/responses/UserReportList");
  assert.equal(
    userDeletion.responses?.["200"]?.$ref,
    "#/components/responses/ReportDeletionStatus"
  );
  assert.equal(userDetail.responses?.["200"]?.$ref, "#/components/responses/UserReportDetail");
  assert.equal(
    userContent.responses?.["200"]?.$ref,
    "#/components/responses/ReportContentCurrent"
  );
  assert.equal(
    userCorrection.responses?.["200"]?.$ref,
    "#/components/responses/ReportContentRevision"
  );
  assert.equal(
    userCorrection.responses?.["201"]?.$ref,
    "#/components/responses/ReportContentRevision"
  );
  assert.equal(userRequest.responses?.["201"]?.$ref, "#/components/responses/ReportUserRequest");
  for (const schemaName of [
    "ReportUserRequest",
    "UserReport",
    "UserReportList",
    "UserReportDetail",
    "ReportUserRequestCreate",
    "ReportContentCurrent",
    "ReportContentCorrectionRequest",
    "ReportContentRevision",
    "ReportDeletionStatus"
  ]) {
    assert.equal(detailed.components.schemas[schemaName]!.additionalProperties, false);
  }
  const requestContract = contract.paths["/api/reports/mine/{report_id}/requests"]!
    .post as {
      "x-max-body-bytes"?: number;
      requestBody?: { content?: { "application/json"?: { schema?: { $ref?: string } } } };
    };
  assert.equal(requestContract["x-max-body-bytes"], 4096);
  assert.equal(
    requestContract.requestBody?.content?.["application/json"]?.schema?.$ref,
    "#/components/schemas/ReportUserRequestCreate"
  );
  assert.equal(
    (detailed.components.schemas.ReportUserRequestCreate!.properties!
      .request_text as unknown as { maxLength: number }).maxLength,
    500
  );
  const correctionContract = contract.paths["/api/reports/mine/{report_id}/corrections"]!
    .post as {
      "x-max-body-bytes"?: number;
      requestBody?: { content?: { "application/json"?: { schema?: { $ref?: string } } } };
    };
  assert.equal(correctionContract["x-max-body-bytes"], 4096);
  assert.equal(
    correctionContract.requestBody?.content?.["application/json"]?.schema?.$ref,
    "#/components/schemas/ReportContentCorrectionRequest"
  );
  const correctionSchema = contract.components.schemas.ReportContentCorrectionRequest as {
    required?: string[];
    anyOf?: Array<{ required?: string[] }>;
  };
  assert.deepEqual(correctionSchema.required, ["expected_revision", "idempotency_key"]);
  assert.deepEqual(correctionSchema.anyOf, [
    { required: ["user_description"] },
    { required: ["category_hint"] }
  ]);
  const reportRightsResponses = (contract as unknown as {
    components: {
      responses: Record<string, {
        headers?: Record<string, { $ref?: string }>;
        content?: { "application/json"?: { schema?: { $ref?: string } } };
      }>;
    };
  }).components.responses;
  for (const [name, schemaName] of [
    ["ReportContentCurrent", "ReportContentCurrent"],
    ["ReportContentRevision", "ReportContentRevision"],
    ["ReportDeletionStatus", "ReportDeletionStatus"]
  ] as const) {
    assert.equal(
      reportRightsResponses[name]!.headers?.["Cache-Control"]?.$ref,
      "#/components/headers/NoStore"
    );
    assert.equal(
      reportRightsResponses[name]!.content?.["application/json"]?.schema?.$ref,
      `#/components/schemas/${schemaName}`
    );
  }
  assert.deepEqual(consentGet.security, [
    { fieldSession: [], consentControlSecret: [] },
    { fieldSession: [] }
  ]);
  assert.equal(
    consentGet.responses?.["200"]?.$ref,
    "#/components/responses/IntegratedConsentRead"
  );
  assert.deepEqual(consentPut.security, [{
    fieldSession: [],
    consentControlSecret: []
  }]);
  assert.deepEqual(
    consentPut.parameters?.map(({ name, in: location, required }) => ({
      name,
      in: location,
      required
    })),
    [{ name: "control", in: "query", required: true }]
  );
  assert.deepEqual(detailed.components.securitySchemes.consentControlSecret, {
    type: "apiKey",
    in: "header",
    name: CONSENT_CONTROL_SECRET_HEADER,
    description: "Exactly 64 lowercase hexadecimal characters generated and retained by the Android encrypted preference store."
  });
  for (const schemaName of [
    "IntegratedConsentItemVersions",
    "IntegratedConsentSelections",
    "IntegratedConsentRequest",
    "IntegratedConsentBootstrap",
    "IntegratedConsentConfirmation"
  ]) {
    assert.equal(
      detailed.components.schemas[schemaName]!.additionalProperties,
      false
    );
  }
  assert.deepEqual(
    detailed.components.schemas.IntegratedConsentRequest!.required,
    [
      "schema_version",
      "installation_id",
      "request_id",
      "policy_version",
      "item_versions",
      "client_revision",
      "expected_previous_backend_receipt_sha256",
      "selections"
    ]
  );
  assert.deepEqual(
    detailed.components.schemas.IntegratedConsentBootstrap!.required,
    [
      "schema_version",
      "status",
      "source",
      "installation_id",
      "policy_version",
      "item_versions",
      "client_revision_floor",
      "selections",
      "source_receipt_sha256",
      "expected_previous_backend_receipt_sha256"
    ]
  );
  assert.deepEqual(
    detailed.components.schemas.IntegratedConsentConfirmation!.required,
    [
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
    ]
  );
  assert.deepEqual(
    detailed.components.schemas.IntegratedConsentItemVersions!.properties,
    {
      raw_source_collection: { type: "string", const: "FP-013-RAW-1.1.0" },
      automatic_reporting: { type: "string", const: "FP-013-AUTO-1.1.0" },
      mobile_network_transfer: { type: "string", const: "FP-013-MOBILE-1.0.0" },
      training_reuse: { type: "string", const: "FP-013-TRAINING-1.1.0" }
    }
  );
  const evidence409 = detailed.paths[
    "/privacy/account-deletions/{request_id}/device-evidence"
  ]!.post!.responses!["409"]!;
  assert.equal(
    evidence409.$ref,
    "#/components/responses/AccountDeletionConflict"
  );
  assert.deepEqual(
    detailed.components.responses.AccountDeletionConflict!.content!
      ["application/json"]!.schema!.oneOf,
    [
      { $ref: "#/components/schemas/AccountDeletionStatusV2" },
      { $ref: "#/components/schemas/GatewayError" }
    ]
  );
  assert.doesNotMatch(raw, /x-walksafe-field-test-token|x-walksafe-actor-assertion/i);
  assert.doesNotMatch(raw, /WALKSAFE_[A-Z]/);
  assert.equal("servers" in (contract as object), false);

  const unknown = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/health"));
  assert.equal(unknown.status, 404);
  assert.equal(unknown.headers.get("cache-control"), "no-store");
  const trailing = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/field-session/"));
  assert.equal(trailing.status, 404);
  const wrongMethod = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", { method: "DELETE" })
  );
  assert.equal(wrongMethod.status, 405);
  assert.equal(wrongMethod.headers.get("allow"), "POST");
  assert.equal(wrongMethod.headers.get("cache-control"), "no-store");

  const packageJson = JSON.parse(
    await readFile(path.join(process.cwd(), "package.json"), "utf8")
  ) as { scripts: Record<string, string>; dependencies?: Record<string, string>; devDependencies?: Record<string, string> };
  assert.equal(packageJson.scripts.start, "node dist/server.js");
  const dependencyNames = [
    ...Object.keys(packageJson.dependencies ?? {}),
    ...Object.keys(packageJson.devDependencies ?? {})
  ];
  assert.equal(dependencyNames.some((name) => /^(next|react|react-dom)$/.test(name)), false);
});

test("privacy rights page shares only its documented path with the consent control API", async () => {
  const response = await handleGatewayRequest(
    new Request("https://gateway.invalid/privacy/rights"),
    { privacyRightsRequestUrl: "https://privacy.invalid/walksafe/rights-request" }
  );
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html;/);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.match(response.headers.get("content-security-policy") ?? "", /default-src 'none'/);
  const html = await response.text();
  assert.match(html, /열람/);
  assert.match(html, /동의 철회/);
  assert.match(html, /삭제/);
  assert.match(html, /https:\/\/privacy\.invalid\/walksafe\/rights-request/);
  assert.doesNotMatch(html, /field-session|walksafe_field_session|token/i);
  assert.equal(PUBLIC_GATEWAY_ROUTES.includes("/privacy/rights"), true);

  const head = await handleGatewayRequest(
    new Request("https://gateway.invalid/privacy/rights", { method: "HEAD" }),
    { privacyRightsRequestUrl: "https://privacy.invalid/walksafe/rights-request" }
  );
  assert.equal(head.status, 200);
  assert.equal(await head.text(), "");

  const wrongMethod = await handleGatewayRequest(
    new Request("https://gateway.invalid/privacy/rights", { method: "POST" }),
    { privacyRightsRequestUrl: "https://privacy.invalid/walksafe/rights-request" }
  );
  assert.equal(wrongMethod.status, 405);
  assert.equal(wrongMethod.headers.get("allow"), "GET, HEAD");
});

test("configuration accepts only the fixed loopback backend and bind host", () => {
  assert.equal(resolveBackendBaseUrl(), "http://127.0.0.1:8000");
  for (const value of [
    "http://localhost:8000",
    "http://127.0.0.1:8000/",
    "https://127.0.0.1:8000",
    "http://127.0.0.1:8000/api"
  ]) {
    assert.throws(() => resolveBackendBaseUrl(value), /must be exactly/);
  }
  assert.deepEqual(resolveBindAddress(undefined, undefined), { host: "127.0.0.1", port: 8081 });
  assert.throws(() => resolveBindAddress("0.0.0.0", "8081"), /must be exactly/);
  assert.throws(() => resolveBindAddress("127.0.0.1", "0"), /between 1 and 65535/);
});

test("privacy rights intake configuration accepts only one exact HTTPS URL", () => {
  assert.equal(
    resolvePrivacyRightsRequestUrl("https://privacy.example.org/walksafe/rights"),
    "https://privacy.example.org/walksafe/rights"
  );
  for (const value of [
    "http://privacy.example.org/walksafe/rights",
    "https://user@privacy.example.org/walksafe/rights",
    "https://privacy.example.org/walksafe/rights?actor=user",
    "https://privacy.example.org/walksafe/rights#fragment"
  ]) {
    assert.throws(() => resolvePrivacyRightsRequestUrl(value), /HTTPS URL/);
  }
});

test("admin environment cannot affect field authority or expose a gateway route", async () => {
  const previous = process.env.NODE_ENV;
  const previousAdminToken = process.env.WALKSAFE_ADMIN_TOKEN;
  const previousAdminAccounts = process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON;
  process.env.NODE_ENV = "production";
  process.env.WALKSAFE_ADMIN_TOKEN = INTERNAL_TOKEN;
  process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = JSON.stringify([{
    actor_id: "admin-operator",
    token: ACCOUNT_TOKEN
  }]);
  try {
    assert.equal(isGatewayAccessConfigured(), true);
    for (const target of ["/api/admin-session", "/api/admin/walks", "/admin"]) {
      for (const method of ["GET", "POST"]) {
        const response = await handleGatewayRequest(
          new Request(`http://127.0.0.1:8081${target}`, { method })
        );
        assert.equal(response.status, 404);
        assert.equal(response.headers.get("cache-control"), "no-store");
      }
    }
    const adminCookie = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/destinations/search?query=test", {
        headers: { cookie: "walksafe_admin_session=v3.invalid" }
      })
    );
    assert.equal(adminCookie.status, 401);
  } finally {
    process.env.NODE_ENV = previous;
    if (previousAdminToken === undefined) delete process.env.WALKSAFE_ADMIN_TOKEN;
    else process.env.WALKSAFE_ADMIN_TOKEN = previousAdminToken;
    if (previousAdminAccounts === undefined) {
      delete process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON;
    } else {
      process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON = previousAdminAccounts;
    }
  }
});

test("field login fails busy while the global or actor state lock is held", async () => {
  const request = () => new Request("http://127.0.0.1:8081/api/field-session", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "cf-connecting-ip": nextClientIp()
    },
    body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
  });

  const releaseGlobal = await holdExclusiveLock(
    path.join(stateDirectory, "gateway-login-state.lock")
  );
  try {
    const busy = await handleGatewayRequest(request());
    assert.equal(busy.status, 503);
    assert.equal((await busy.json() as { code: string }).code, "gateway_login_busy");
  } finally {
    await releaseGlobal();
  }

  const actorDigest = createHash("sha256").update(`field\0${ACTOR_ID}`).digest("hex");
  const sessionPath = path.join(stateDirectory, "sessions", `field-${actorDigest}.json`);
  const releaseActor = await holdExclusiveLock(`${sessionPath}.lock`);
  try {
    const busy = await handleGatewayRequest(request());
    assert.equal(busy.status, 503);
    assert.equal((await busy.json() as { code: string }).code, "gateway_login_busy");
    await assert.rejects(
      readFile(sessionPath, "utf8"),
      (error: NodeJS.ErrnoException) => error.code === "ENOENT"
    );
  } finally {
    await releaseActor();
  }
});

test("field session is actor-bound, revocable, secure on HTTPS, and never cached", async () => {
  const missingTrustedIp = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
    })
  );
  assert.equal(missingTrustedIp.status, 400);
  assert.deepEqual(await missingTrustedIp.json(), {
    code: "gateway_trusted_client_ip_required",
    message: "신뢰할 수 있는 접속 주소를 확인할 수 없습니다."
  });

  const invalid = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": nextClientIp() },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: "wrong" })
    })
  );
  assert.equal(invalid.status, 401);
  assert.equal(invalid.headers.get("cache-control"), "no-store");

  const cookie = await login({ "x-forwarded-proto": "https" });
  const setCookieResponse = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      headers: { cookie }
    })
  );
  assert.equal(setCookieResponse.status, 200);
  assert.deepEqual(await setCookieResponse.json(), {
    required: true,
    authenticated: true,
    actor_id: ACTOR_ID,
    session_scope: "general"
  });

  const freshLogin = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextClientIp(),
        "x-forwarded-proto": "https"
      },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
    })
  );
  const secureCookie = freshLogin.headers.get("set-cookie") ?? "";
  assert.match(secureCookie, /; Path=\/; HttpOnly; SameSite=Strict; Max-Age=43200; Secure$/);
  assert.doesNotMatch(secureCookie, /Domain=/i);
  const activeCookie = secureCookie.split(";", 1)[0]!;

  const logout = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { cookie: activeCookie }
    })
  );
  assert.equal(logout.status, 204);
  assert.match(logout.headers.get("set-cookie") ?? "", /Max-Age=0; Secure$/);
  const afterLogout = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", { headers: { cookie: activeCookie } })
  );
  const status = await afterLogout.json() as { authenticated: boolean };
  assert.equal(status.authenticated, false);
});

test("recovery login binds scope and cannot authorize general or device routes", async () => {
  const createSession = (purpose?: unknown, deviceId?: string): Promise<Response> =>
    handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "cf-connecting-ip": nextClientIp()
        },
        body: JSON.stringify({
          actor_id: ACTOR_ID,
          token: ACCOUNT_TOKEN,
          ...(purpose === undefined ? {} : { purpose }),
          ...(deviceId === undefined ? {} : { device_id: deviceId })
        })
      })
    );

  const explicitGeneral = await createSession("general");
  assert.equal(explicitGeneral.status, 200);
  assert.deepEqual(await explicitGeneral.json(), { session_scope: "general" });

  for (const invalid of ["administrator", null, 7]) {
    const response = await createSession(invalid);
    assert.equal(response.status, 400);
    assert.equal((await response.json() as { code: string }).code, "field_session_request_invalid");
  }
  const recoveryDevice = await createSession("account_deletion_recovery", "device-recovery");
  assert.equal(recoveryDevice.status, 400);

  const recovery = await createSession("account_deletion_recovery");
  assert.equal(recovery.status, 200);
  assert.deepEqual(await recovery.json(), {
    session_scope: "account_deletion_recovery"
  });
  const setCookie = recovery.headers.get("set-cookie") ?? "";
  assert.match(
    setCookie,
    /^walksafe_field_session=v5\.[^.]+\.account_deletion_recovery\.[^;]+; Path=\/; HttpOnly; SameSite=Strict; Max-Age=43200; Secure$/
  );
  const cookie = setCookie.split(";", 1)[0]!;

  const current = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", { headers: { cookie } })
  );
  assert.deepEqual(await current.json(), {
    required: true,
    authenticated: true,
    actor_id: ACTOR_ID,
    session_scope: "account_deletion_recovery"
  });

  const [, encodedActor, , expires, sessionId, signature] =
    cookie.slice(cookie.indexOf("=") + 1).split(".");
  const exactLegacyShape =
    `walksafe_field_session=v3.${encodedActor}.${expires}.${sessionId}.${signature}`;
  for (const forgedCookie of [
    cookie.replace(".account_deletion_recovery.", ".general."),
    cookie.replace("=v5.", "=v3."),
    exactLegacyShape
  ]) {
    const forged = await handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session", {
        headers: { cookie: forgedCookie }
      })
    );
    assert.deepEqual(await forged.json(), {
      required: true,
      authenticated: false,
      actor_id: null,
      session_scope: null
    });
  }

  const actorDigest = createHash("sha256").update(`field\0${ACTOR_ID}`).digest("hex");
  const recordId = `field-${actorDigest}.json`;
  const statePath = path.join(stateDirectory, "sessions", recordId);
  const originalState = await readFile(statePath, "utf8");
  const stateContext = { kind: "short-session" as const, recordId };
  const decodedState = decryptGatewayStateJson(
    stateContext,
    originalState,
    SHORT_SESSION_MAX_PLAINTEXT_BYTES
  ).value as Record<string, unknown>;
  assert.equal(decodedState.sessionScope, "account_deletion_recovery");
  await writeFile(
    statePath,
    encryptGatewayStateJson(
      stateContext,
      { ...decodedState, sessionScope: "general" },
      SHORT_SESSION_MAX_PLAINTEXT_BYTES
    )
  );
  const stateDowngrade = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", { headers: { cookie } })
  );
  assert.deepEqual(await stateDowngrade.json(), {
    required: true,
    authenticated: false,
    actor_id: null,
    session_scope: null
  });
  await writeFile(statePath, originalState);

  const requestId = "recovery_request_0001";
  for (const request of [
    new Request("https://gateway.invalid/privacy/account-deletions", {
      method: "POST",
      headers: { cookie, "x-walksafe-deletion-access-secret": "invalid" }
    }),
    new Request(`https://gateway.invalid/privacy/account-deletions/${requestId}/status`, {
      headers: { cookie, "x-walksafe-deletion-access-secret": "invalid" }
    }),
    new Request(`https://gateway.invalid/privacy/account-deletions/${requestId}/device-evidence`, {
      method: "POST",
      headers: { cookie, "x-walksafe-deletion-access-secret": "invalid" }
    })
  ]) {
    const allowed = await handleGatewayRequest(request);
    assert.equal(allowed.status, 404);
    assert.notEqual((await allowed.json() as { code: string }).code, "gateway_forbidden");
  }

  const forbiddenRequests = [
    new Request("https://gateway.invalid/api/field-walk"),
    new Request("https://gateway.invalid/api/navigation/walking", { method: "POST" }),
    new Request("https://gateway.invalid/api/navigation/destinations/search?query=station"),
    new Request("https://gateway.invalid/api/reports/v2", { method: "POST" }),
    new Request("https://gateway.invalid/privacy/rights"),
    new Request("https://gateway.invalid/privacy/rights?control=integrated-consent", {
      method: "PUT"
    }),
    new Request("https://gateway.invalid/api/field-session?devices=true"),
    new Request("https://gateway.invalid/api/field-session?device_id=device-a", {
      method: "DELETE"
    }),
    new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        grant_type: "refresh_token",
        actor_id: ACTOR_ID,
        device_id: "device-a",
        family_id: "f".repeat(32),
        rotation: 0,
        refresh_token: "r".repeat(64)
      })
    }),
    new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        actor_id: ACTOR_ID,
        device_id: "device-a",
        family_id: "f".repeat(32),
        rotation: 0,
        refresh_token: "r".repeat(64)
      })
    })
  ];
  for (const request of forbiddenRequests) {
    request.headers.set("cookie", cookie);
    const forbidden = await handleGatewayRequest(request);
    assert.equal(forbidden.status, 403);
    assert.equal((await forbidden.json() as { code: string }).code, "gateway_forbidden");
  }

  const logout = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { cookie }
    })
  );
  assert.equal(logout.status, 204);
  const loggedOut = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", { headers: { cookie } })
  );
  assert.deepEqual(await loggedOut.json(), {
    required: true,
    authenticated: false,
    actor_id: null,
    session_scope: null
  });
});

test("a valid recovery session can prepare deletion and is then revoked", async () => {
  const recoveryActor = "recovery-delete-operator";
  const recoveryToken = "recovery-delete-token-12345678901234567890";
  const previousAccounts = process.env.WALKSAFE_FIELD_ACCOUNTS_JSON!;
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: ACTOR_ID, token: ACCOUNT_TOKEN },
    { actor_id: recoveryActor, token: recoveryToken }
  ]);
  try {
    const loginResponse = await handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "cf-connecting-ip": nextClientIp()
        },
        body: JSON.stringify({
          actor_id: recoveryActor,
          token: recoveryToken,
          purpose: "account_deletion_recovery"
        })
      })
    );
    assert.equal(loginResponse.status, 200);
    assert.deepEqual(await loginResponse.json(), {
      session_scope: "account_deletion_recovery"
    });
    const cookie = (loginResponse.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
    const requestId = "recovery_accept_0001";
    const capability = Buffer.alloc(32, 0x52).toString("base64url");
    const acceptedAt = "2026-08-10T00:00:00.000Z";
    const acceptedAtMs = Date.parse(acceptedAt);
    const backendStatus: AccountDeletionStatusV2 = {
      schema_version: "walksafe.account-deletion-status.v2",
      request_id: requestId,
      client_revision: 1,
      revision: 1,
      accepted_at: acceptedAt,
      updated_at: acceptedAt,
      account_generation: 1,
      tombstone_id: "recovery-tombstone-0001",
      request_receipt_sha256: "a".repeat(64),
      overall_status: "PROCESSING",
      items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => ({
        key: definition.key,
        status: "PENDING",
        item_revision: 1,
        due_at: new Date(acceptedAtMs + definition.dueAfterMs).toISOString(),
        updated_at: acceptedAt,
        evidence_sha256: null,
        disposition_basis: null,
        retry_after: null,
        restriction_reason: null,
        legal_hold_review_at: null,
        legal_hold_contact: null,
        terminal_at: null
      })),
      completion_receipt_sha256: null
    };
    let backendCalls = 0;
    const accepted = await handleGatewayRequest(
      new Request("https://gateway.invalid/privacy/account-deletions", {
        method: "POST",
        headers: {
          cookie,
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: capability
        },
        body: JSON.stringify({
          schema_version: "walksafe.account-deletion-request.v2",
          request_id: requestId,
          client_revision: 1,
          confirmation: "DELETE_MY_ACCOUNT"
        })
      }),
      {
        fetchImpl: async (input, init) => {
          backendCalls += 1;
          assert.equal(String(input), "http://127.0.0.1:8000/privacy/account-deletions");
          const headers = new Headers(init?.headers);
          assert.equal(headers.get(ACTOR_ID_HEADER), recoveryActor);
          assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
          assert.equal(headers.get(DELETION_ACCESS_SECRET_HEADER), null);
          return Response.json(backendStatus, { status: 202 });
        }
      }
    );
    assert.equal(accepted.status, 202);
    assert.deepEqual(await accepted.json(), backendStatus);
    assert.equal(backendCalls, 1);

    const sessionlessReplay = await handleGatewayRequest(
      new Request("https://gateway.invalid/privacy/account-deletions", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: capability
        },
        body: JSON.stringify({
          schema_version: "walksafe.account-deletion-request.v2",
          request_id: requestId,
          client_revision: 1,
          confirmation: "DELETE_MY_ACCOUNT"
        })
      }),
      {
        fetchImpl: async () => {
          assert.fail("a completed gateway replay must not repeat the backend request");
        }
      }
    );
    assert.equal(sessionlessReplay.status, 200);
    assert.deepEqual(await sessionlessReplay.json(), backendStatus);
    assert.equal(backendCalls, 1);

    const revokedStatus = await handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session", { headers: { cookie } })
    );
    assert.deepEqual(await revokedStatus.json(), {
      required: true,
      authenticated: false,
      actor_id: null,
      session_scope: null
    });
  } finally {
    process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = previousAccounts;
  }
});

test("field login limiter blocks the sixth attempt from one trusted client", async () => {
  const clientIp = nextClientIp();
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/field-session", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": clientIp },
        body: JSON.stringify({ actor_id: ACTOR_ID, token: `wrong-${attempt}` })
      })
    );
    assert.equal(response.status, 401);
  }
  const blocked = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": clientIp },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
    })
  );
  assert.equal(blocked.status, 429);
  assert.equal(blocked.headers.get("cache-control"), "no-store");
  assert.ok(Number(blocked.headers.get("retry-after")) > 0);
  assert.equal((await blocked.json() as { code: string }).code, "gateway_login_rate_limited");
});

test("walking and search proxy only approved data and internal actor credentials", async () => {
  const cookie = await login();
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const fetchImpl: GatewayFetch = async (input, init = {}) => {
    calls.push({ url: String(input), init });
    return Response.json(
      { ok: true },
      {
        headers: {
          "cache-control": "public, max-age=3600",
          "set-cookie": "backend_secret=bad",
          "x-correlation-id": "request-123",
          [FIELD_TEST_TOKEN_HEADER]: "must-not-leak"
        }
      }
    );
  };

  const walkingBody = JSON.stringify({ origin: { latitude: 37.5, longitude: 127.0 } });
  const walking = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: {
        cookie,
        "content-type": "application/json",
        [FIELD_TEST_TOKEN_HEADER]: "client-spoof",
        [ACTOR_ID_HEADER]: "client-spoof",
        [ACTOR_ASSERTION_HEADER]: "client-spoof"
      },
      body: walkingBody
    }),
    { fetchImpl }
  );
  assert.equal(walking.status, 200);
  assert.equal(walking.headers.get("cache-control"), "no-store");
  assert.equal(walking.headers.get("set-cookie"), null);
  assert.equal(walking.headers.get(FIELD_TEST_TOKEN_HEADER), null);
  assert.match(
    walking.headers.get("x-request-id") ?? "",
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
  );
  assert.notEqual(walking.headers.get("x-request-id"), "request-123");

  const first = calls[0]!;
  assert.equal(first.url, "http://127.0.0.1:8000/navigation/walking");
  assert.equal(first.init.method, "POST");
  assert.equal(first.init.body, walkingBody);
  assert.equal(first.init.redirect, "error");
  const walkingHeaders = new Headers(first.init.headers);
  assert.equal(walkingHeaders.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
  assert.equal(walkingHeaders.get(ACTOR_ID_HEADER), ACTOR_ID);
  assert.match(walkingHeaders.get(ACTOR_ASSERTION_HEADER) ?? "", /^v2\.\d+\.[A-Za-z0-9_-]+$/);
  assert.equal(walkingHeaders.get(ACCOUNT_GENERATION_HEADER), "1");
  assert.notEqual(walkingHeaders.get(ACTOR_ASSERTION_HEADER), "client-spoof");

  const search = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/api/navigation/destinations/search?query=%EC%84%9C%EC%9A%B8&limit=5",
      { headers: { cookie, [FIELD_TEST_TOKEN_HEADER]: "client-spoof" } }
    ),
    { fetchImpl }
  );
  assert.equal(search.status, 200);
  assert.equal(
    calls[1]!.url,
    "http://127.0.0.1:8000/navigation/destinations/search?query=%EC%84%9C%EC%9A%B8&limit=5"
  );
  assert.equal(search.headers.get("cache-control"), "no-store");
});

test("upstream 401 and 403 never invalidate the Android field session", async () => {
  const cookie = await login();
  for (const upstreamStatus of [401, 403]) {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/walking", {
        method: "POST",
        headers: { cookie, "content-type": "application/json" },
        body: "{}"
      }),
      { fetchImpl: async () => Response.json({ secret: "backend auth detail" }, { status: upstreamStatus }) }
    );
    assert.equal(response.status, 502);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.deepEqual(await response.json(), {
      detail: {
        code: "gateway_upstream_auth_failed",
        message: "upstream service authentication failed"
      }
    });
  }
  const status = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", { headers: { cookie } })
  );
  assert.equal((await status.json() as { authenticated: boolean }).authenticated, true);
});

test("report multipart is bounded and proxied only after field authentication", async () => {
  resetImageUploadAdmissionForTests();
  const cookie = await login();
  const consentHeaders = await reportConsentHeaders(cookie);
  let calls = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    calls += 1;
    assert.equal(String(input), "http://127.0.0.1:8000/reports/v2");
    assert.equal(init?.redirect, "error");
    assert.ok(init?.body instanceof FormData);
    assert.equal(
      (init.body as FormData).get("metadata"),
      "{\"source\":\"android\",\"auto_reported\":false}"
    );
    assert.ok((init.body as FormData).get("image") instanceof Blob);
    const headers = new Headers(init.headers);
    assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
    assert.equal(headers.get("content-type"), null);
    assert.equal(headers.get(REPORT_ID_HEADER), null);
    assert.equal(headers.get(REPORT_PAYLOAD_SHA256_HEADER), null);
    assert.equal(headers.get(REPORT_PAYLOAD_BYTES_HEADER), null);
    return Response.json({ id: "report-id" }, { status: 201 });
  };
  const form = new FormData();
  form.set("metadata", "{\"source\":\"android\",\"auto_reported\":false}");
  form.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
  const uploaded = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders },
      body: form
    }),
    { fetchImpl }
  );
  assert.equal(uploaded.status, 201);
  assert.equal(uploaded.headers.get("cache-control"), "no-store");
  assert.equal(calls, 1);

  const oversized = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: {
        cookie,
        "cf-connecting-ip": nextClientIp(),
        ...consentHeaders,
        "content-type": "multipart/form-data; boundary=oversized",
        "content-length": String(IMAGE_MULTIPART_LIMIT_BYTES + 1)
      },
      body: "x"
    }),
    { fetchImpl }
  );
  assert.equal(oversized.status, 413);
  assert.equal(calls, 1);

  for (const mutate of [
    (value: FormData) => value.append("metadata", "{}"),
    (value: FormData) => value.append("extra", "unexpected")
  ]) {
    const invalid = new FormData();
    invalid.set("metadata", "{}");
    invalid.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
    mutate(invalid);
    const rejected = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/reports/v2", {
        method: "POST",
        headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders },
        body: invalid
      }),
      { fetchImpl }
    );
    assert.equal(rejected.status, 422);
  }

  const wrongType = new FormData();
  wrongType.set("metadata", "{}");
  wrongType.set("image", new Blob([new Uint8Array([1])], { type: "application/octet-stream" }), "report.bin");
  const rejectedType = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders },
      body: wrongType
    }),
    { fetchImpl }
  );
  assert.equal(rejectedType.status, 415);

  const largeImage = new FormData();
  largeImage.set("metadata", "{}");
  largeImage.set(
    "image",
    new Blob([new Uint8Array(REPORT_IMAGE_LIMIT_BYTES + 1)], { type: "image/jpeg" }),
    "report.jpg"
  );
  const rejectedImage = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders },
      body: largeImage
    }),
    { fetchImpl }
  );
  assert.equal(rejectedImage.status, 413);
  assert.equal(calls, 1);
});

test("multipart part counting ignores boundary text inside a payload", () => {
  const boundary = "walksafe-fixed-boundary";
  const body = Buffer.from(
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="metadata"\r\n\r\n` +
    `{"note":"inside--${boundary}-is-not-a-delimiter"}\r\n` +
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="image"; filename="report.jpg"\r\n` +
    `Content-Type: image/jpeg\r\n\r\n` +
    `jpeg--${boundary}-payload\r\n` +
    `--${boundary}--\r\n`,
    "utf8"
  );
  assert.equal(countMultipartDelimiters(body, boundary), 3);
});

test("report transport headers are all-or-none and only exact values reach backend", async () => {
  resetImageUploadAdmissionForTests();
  const cookie = await login();
  const consentHeaders = await reportConsentHeaders(cookie);
  const reportId = "aaaaaaaa-1111-4111-8111-111111111111";
  const payloadSha256 = "a".repeat(64);
  const payloadBytes = "123";
  let calls = 0;
  const form = (): FormData => {
    const value = new FormData();
    value.set("metadata", "{\"source\":\"android\",\"auto_reported\":false}");
    value.set(
      "image",
      new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }),
      "report.jpg"
    );
    return value;
  };
  const fetchImpl: GatewayFetch = async (_input, init) => {
    calls += 1;
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(REPORT_ID_HEADER), reportId);
    assert.equal(headers.get(REPORT_PAYLOAD_SHA256_HEADER), payloadSha256);
    assert.equal(headers.get(REPORT_PAYLOAD_BYTES_HEADER), payloadBytes);
    assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR_ID);
    assert.equal(headers.get("x-walksafe-admin-token"), null);
    assert.equal(headers.get("x-walksafe-read-purpose"), null);
    return Response.json({ id: reportId }, { status: 201 });
  };

  for (const headers of [
    { [REPORT_ID_HEADER]: reportId },
    {
      [REPORT_ID_HEADER]: reportId.toUpperCase(),
      [REPORT_PAYLOAD_SHA256_HEADER]: payloadSha256,
      [REPORT_PAYLOAD_BYTES_HEADER]: payloadBytes
    },
    {
      [REPORT_ID_HEADER]: reportId,
      [REPORT_PAYLOAD_SHA256_HEADER]: payloadSha256.toUpperCase(),
      [REPORT_PAYLOAD_BYTES_HEADER]: "0"
    }
  ]) {
    const rejected = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/reports/v2", {
        method: "POST",
        headers: {
          cookie,
          "cf-connecting-ip": nextClientIp(),
          ...consentHeaders,
          ...headers
        },
        body: form()
      }),
      { fetchImpl }
    );
    assert.equal(rejected.status, 422);
  }
  assert.equal(calls, 0);

  const accepted = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: {
        cookie,
        "cf-connecting-ip": nextClientIp(),
        ...consentHeaders,
        [REPORT_ID_HEADER]: reportId,
        [REPORT_PAYLOAD_SHA256_HEADER]: payloadSha256,
        [REPORT_PAYLOAD_BYTES_HEADER]: payloadBytes,
        [ACTOR_ID_HEADER]: "spoofed-actor",
        "x-walksafe-admin-token": "must-not-forward",
        "x-walksafe-read-purpose": "must-not-forward"
      },
      body: form()
    }),
    { fetchImpl }
  );
  assert.equal(accepted.status, 201);
  assert.equal(calls, 1);
});

test("report status is actor-bound, bounded, strict, and no-store", async () => {
  const cookie = await login();
  const reportId = "11111111-1111-4111-8111-111111111111";
  const marker = "22222222-2222-4222-8222-222222222222";
  const statusUrl = `http://127.0.0.1:8081/api/reports/v2/${reportId}/status`;
  let calls = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    calls += 1;
    assert.equal(String(input), `http://127.0.0.1:8000/reports/v2/${reportId}/status`);
    assert.equal(init?.method, "GET");
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR_ID);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
    assert.match(headers.get(ACTOR_ASSERTION_HEADER) ?? "", /^v2\.\d+\.[A-Za-z0-9_-]+$/);
    assert.equal(headers.get(REPORT_ID_HEADER), null);
    assert.equal(headers.get("x-walksafe-admin-token"), null);
    return Response.json({
      persistence_state: "PERSISTED",
      user_status: "RECEIVED",
      transport_receipt: {
        marker: "DATABASE_AND_ENCRYPTED_IMAGE_STORE",
        report_id: reportId,
        persistence_marker: marker,
        payload_sha256: "a".repeat(64),
        payload_bytes: 123
      }
    });
  };

  const missing = await handleGatewayRequest(new Request(statusUrl), { fetchImpl });
  assert.equal(missing.status, 404);
  assert.equal(calls, 0);

  const status = await handleGatewayRequest(
    new Request(statusUrl, {
      headers: {
        cookie,
        [ACTOR_ID_HEADER]: "spoofed-actor",
        [REPORT_ID_HEADER]: "33333333-3333-4333-8333-333333333333",
        "x-walksafe-admin-token": "must-not-forward"
      }
    }),
    { fetchImpl }
  );
  assert.equal(status.status, 200);
  assert.equal(status.headers.get("cache-control"), "no-store");
  assert.deepEqual(await status.json(), {
    persistence_state: "PERSISTED",
    user_status: "RECEIVED",
    transport_receipt: {
      marker: "DATABASE_AND_ENCRYPTED_IMAGE_STORE",
      report_id: reportId,
      persistence_marker: marker,
      payload_sha256: "a".repeat(64),
      payload_bytes: 123
    }
  });
  assert.equal(calls, 1);

  const hidden = await handleGatewayRequest(
    new Request(statusUrl, { headers: { cookie } }),
    { fetchImpl: async () => Response.json({ private_actor: "other@example.org" }, { status: 404 }) }
  );
  assert.equal(hidden.status, 404);
  assert.deepEqual(await hidden.json(), {
    detail: { code: "report_transport_status_not_found" }
  });

  const upstreamDenied = await handleGatewayRequest(
    new Request(statusUrl, { headers: { cookie } }),
    {
      fetchImpl: async () => Response.json(
        { private_actor: "other@example.org" },
        { status: 403 }
      )
    }
  );
  assert.equal(upstreamDenied.status, 404);
  assert.deepEqual(await upstreamDenied.json(), {
    detail: { code: "report_transport_status_not_found" }
  });

  const invalidUpstream = await handleGatewayRequest(
    new Request(statusUrl, { headers: { cookie } }),
    {
      fetchImpl: async () => Response.json({
        persistence_state: "PERSISTED",
        user_status: "RECEIVED",
        gps: { latitude: 37.5 },
        transport_receipt: {
          marker: "DATABASE_AND_ENCRYPTED_IMAGE_STORE",
          report_id: reportId,
          persistence_marker: marker,
          payload_sha256: "a".repeat(64),
          payload_bytes: 123
        }
      })
    }
  );
  assert.equal(invalidUpstream.status, 502);
  assert.doesNotMatch(await invalidUpstream.text(), /latitude|private_actor|other@example/);

  const fenced = await handleGatewayRequest(
    new Request(statusUrl, { headers: { cookie } }),
    {
      fetchImpl: async () => {
        const generation = currentActorGeneration(ACTOR_ID);
        assert.notEqual(generation, null);
        abortPrivacyOperationsForAccountDeletion(ACTOR_ID, generation!);
        return Response.json({ private_actor: "must-not-leak" });
      }
    }
  );
  assert.equal(fenced.status, 404);
  assert.deepEqual(await fenced.json(), {
    detail: { code: "report_transport_status_not_found" }
  });
});

test("user report routes bind actor generation and expose only strict minimum JSON", async () => {
  const cookie = backendAccountSessionCookie("android-report-rights-device");
  const reportId = "44444444-4444-4444-8444-444444444444";
  const requestId = "55555555-5555-4555-8555-555555555555";
  const correctionId = "66666666-6666-4666-8666-666666666666";
  const clearCorrectionId = "77777777-7777-4777-8777-777777777777";
  const deletionRequestId = "88888888-8888-4888-8888-888888888888";
  const createdAt = "2026-08-29T01:02:03Z";
  const initialContent = {
    schema_version: "walksafe.report-content-current.v1",
    report_id: reportId,
    revision: 0,
    content_sha256: "a".repeat(64),
    user_description: null,
    category_hint: null,
    corrected_at: null
  };
  const deletionStatus = {
    schema_version: "walksafe.report-deletion-status.v1",
    request_id: deletionRequestId,
    report_id: reportId,
    state: "PENDING",
    request_status_version: 1,
    external_copy_count: 0,
    updated_at: createdAt
  };
  const baseItem = {
    report_id: reportId,
    created_at: createdAt,
    user_status: "RECEIVED",
    public_rejection_reason: null,
    latest_request: null
  };
  const calls: string[] = [];
  const fetchImpl: GatewayFetch = async (input, init) => {
    const url = String(input);
    calls.push(url);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR_ID);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
    assert.match(headers.get(ACTOR_ASSERTION_HEADER) ?? "", /^v2\.\d+\.[A-Za-z0-9_-]+$/);
    assert.equal(headers.get("x-walksafe-admin-token"), null);
    for (const header of [
      CONSENT_CONTROL_SECRET_HEADER,
      CONSENT_INSTALLATION_HEADER,
      CONSENT_NETWORK_TRANSPORT_HEADER,
      CONSENT_POLICY_HEADER,
      CONSENT_RECEIPT_HEADER,
      CONSENT_REVISION_HEADER,
      "x-walksafe-report-purpose",
      "x-walksafe-gateway-audit-record-sha256"
    ]) {
      assert.equal(headers.get(header), null);
    }
    if (url.endsWith("/content")) {
      assert.equal(init?.method, "GET");
      return Response.json(initialContent);
    }
    if (url.endsWith("/corrections")) {
      assert.equal(init?.method, "POST");
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
      if (body.idempotency_key === correctionId) {
        assert.deepEqual(body, {
          expected_revision: 0,
          idempotency_key: correctionId,
          user_description: "표면 손상 범위"
        });
        return Response.json({
          schema_version: "walksafe.report-content-revision.v1",
          report_id: reportId,
          revision: 1,
          expected_revision: 0,
          idempotency_key: correctionId,
          content_sha256: "b".repeat(64),
          user_description: "표면 손상 범위",
          category_hint: null,
          corrected_at: createdAt
        }, { status: 201 });
      }
      assert.deepEqual(body, {
        expected_revision: 1,
        idempotency_key: clearCorrectionId,
        category_hint: null
      });
      return Response.json({
        schema_version: "walksafe.report-content-revision.v1",
        report_id: reportId,
        revision: 2,
        expected_revision: 1,
        idempotency_key: clearCorrectionId,
        content_sha256: "c".repeat(64),
        user_description: "표면 손상 범위",
        category_hint: null,
        corrected_at: createdAt
      });
    }
    if (url.includes("/reports/mine/deletions/")) {
      assert.equal(init?.method, "GET");
      return Response.json(deletionStatus);
    }
    if (url.endsWith("/requests")) {
      assert.equal(init?.method, "POST");
      assert.deepEqual(JSON.parse(String(init?.body)), {
        client_request_id: requestId,
        request_type: "CORRECTION",
        request_text: "표면 손상 범위를 정정해 주세요."
      });
      return Response.json({
        request_id: requestId,
        request_type: "CORRECTION",
        status: "RECEIVED",
        status_version: 1,
        public_response: null,
        created_at: createdAt,
        updated_at: createdAt
      }, { status: 201 });
    }
    assert.equal(url, "http://127.0.0.1:8000/reports/mine?limit=25");
    assert.equal(init?.method, "GET");
    return Response.json({
      schema_version: "walksafe.user-report-list.v1",
      items: [baseItem],
      next_cursor: null
    });
  };

  const list = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/mine?limit=25", {
      headers: { cookie, [ACTOR_ID_HEADER]: "spoofed", "x-walksafe-admin-token": "secret" }
    }),
    { fetchImpl }
  );
  assert.equal(list.status, 200);
  assert.equal(list.headers.get("cache-control"), "no-store");
  assert.deepEqual(await list.json(), {
    schema_version: "walksafe.user-report-list.v1",
    items: [baseItem],
    next_cursor: null
  });
  assert.equal(calls[0], "http://127.0.0.1:8000/reports/mine?limit=25");

  const content = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/content`, {
      headers: { cookie }
    }),
    { fetchImpl }
  );
  assert.equal(content.status, 200);
  assert.equal(content.headers.get("cache-control"), "no-store");
  assert.deepEqual(await content.json(), initialContent);

  const corrected = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/corrections`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({
        expected_revision: 0,
        idempotency_key: correctionId,
        user_description: "  표면   손상  범위  "
      })
    }),
    { fetchImpl }
  );
  assert.equal(corrected.status, 201);
  assert.deepEqual(await corrected.json(), {
    schema_version: "walksafe.report-content-revision.v1",
    report_id: reportId,
    revision: 1,
    expected_revision: 0,
    idempotency_key: correctionId,
    content_sha256: "b".repeat(64),
    user_description: "표면 손상 범위",
    category_hint: null,
    corrected_at: createdAt
  });

  const clearedCategory = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/corrections`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({
        expected_revision: 1,
        idempotency_key: clearCorrectionId,
        category_hint: null
      })
    }),
    { fetchImpl }
  );
  assert.equal(clearedCategory.status, 200);
  assert.equal(
    (await clearedCategory.json() as { category_hint: unknown }).category_hint,
    null
  );

  const deletion = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/api/reports/mine/deletions/${deletionRequestId}`,
      { headers: { cookie } }
    ),
    { fetchImpl }
  );
  assert.equal(deletion.status, 200);
  assert.equal(deletion.headers.get("cache-control"), "no-store");
  assert.deepEqual(await deletion.json(), deletionStatus);

  const created = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/requests`, {
      method: "POST",
      headers: {
        cookie,
        "content-type": "application/json",
        [ACTOR_ID_HEADER]: "spoofed",
        "x-walksafe-admin-token": "secret"
      },
      body: JSON.stringify({
        client_request_id: requestId,
        request_type: "CORRECTION",
        request_text: "표면 손상 범위를 정정해 주세요."
      })
    }),
    { fetchImpl }
  );
  assert.equal(created.status, 201);
  assert.equal((await created.json() as { request_id: string }).request_id, requestId);

  const invalidBody = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/requests`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({
        client_request_id: requestId,
        request_type: "DELETE",
        request_text: "삭제해 주세요.",
        internal_note: "must-not-forward"
      })
    }),
    { fetchImpl: async () => assert.fail("invalid request must not reach backend") }
  );
  assert.equal(invalidBody.status, 422);

  for (const body of [
    {
      expected_revision: 2,
      idempotency_key: "99999999-9999-4999-8999-999999999999"
    },
    {
      expected_revision: 2,
      idempotency_key: "99999999-9999-4999-8999-999999999999",
      user_description: "control\u0000text"
    },
    {
      expected_revision: 2,
      idempotency_key: "99999999-9999-4999-8999-999999999999",
      category_hint: "ROAD_DAMAGE",
      internal_note: "must-not-forward"
    }
  ]) {
    const invalidCorrection = await handleGatewayRequest(
      new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/corrections`, {
        method: "POST",
        headers: { cookie, "content-type": "application/json" },
        body: JSON.stringify(body)
      }),
      { fetchImpl: async () => assert.fail("invalid correction must not reach backend") }
    );
    assert.equal(invalidCorrection.status, 422);
    assert.deepEqual(await invalidCorrection.json(), {
      detail: { code: "report_content_correction_invalid" }
    });
  }

  const oversizedBody = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/requests`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({
        client_request_id: requestId,
        request_type: "CORRECTION",
        request_text: "x".repeat(5000)
      })
    }),
    { fetchImpl: async () => assert.fail("oversized request must not reach backend") }
  );
  assert.equal(oversizedBody.status, 413);
  assert.equal(oversizedBody.headers.get("cache-control"), "no-store");

  const leakCorrectionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  for (const leakCase of [
    {
      url: `http://127.0.0.1:8081/api/reports/mine/${reportId}/content`,
      init: { headers: { cookie } },
      payload: { ...initialContent, private_actor: ACTOR_ID },
      upstreamStatus: 200
    },
    {
      url: `http://127.0.0.1:8081/api/reports/mine/${reportId}/corrections`,
      init: {
        method: "POST",
        headers: { cookie, "content-type": "application/json" },
        body: JSON.stringify({
          expected_revision: 0,
          idempotency_key: leakCorrectionId,
          category_hint: "ROAD_DAMAGE"
        })
      },
      payload: {
        schema_version: "walksafe.report-content-revision.v1",
        report_id: reportId,
        revision: 1,
        expected_revision: 0,
        idempotency_key: leakCorrectionId,
        content_sha256: "d".repeat(64),
        user_description: null,
        category_hint: "ROAD_DAMAGE",
        corrected_at: createdAt,
        private_actor: ACTOR_ID
      },
      upstreamStatus: 201
    },
    {
      url: `http://127.0.0.1:8081/api/reports/mine/deletions/${deletionRequestId}`,
      init: { headers: { cookie } },
      payload: { ...deletionStatus, private_actor: ACTOR_ID },
      upstreamStatus: 200
    }
  ]) {
    const leaked = await handleGatewayRequest(
      new Request(leakCase.url, leakCase.init),
      {
        fetchImpl: async () => Response.json(
          leakCase.payload,
          { status: leakCase.upstreamStatus }
        )
      }
    );
    assert.equal(leaked.status, 502);
    assert.doesNotMatch(await leaked.text(), /private_actor|field-operator/);
  }

  const leakedDetail = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}`, {
      headers: { cookie }
    }),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.user-report-detail.v1",
        ...baseItem,
        latitude: 37.5,
        internal_reason: "private"
      })
    }
  );
  assert.equal(leakedDetail.status, 502);
  assert.doesNotMatch(await leakedDetail.text(), /latitude|internal_reason|private/);

  const fenced = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}`, {
      headers: { cookie }
    }),
    {
      fetchImpl: async () => {
        const generation = currentActorGeneration(ACTOR_ID);
        assert.notEqual(generation, null);
        abortPrivacyOperationsForAccountDeletion(ACTOR_ID, generation!);
        return Response.json({ private_actor: "must-not-leak" });
      }
    }
  );
  assert.equal(fenced.status, 404);
  assert.deepEqual(await fenced.json(), {
    detail: { code: "report_not_found" }
  });

  const v6Cookie = backendAccountSessionCookie(null);
  const v6Rejected = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/content`, {
      headers: { cookie: v6Cookie }
    }),
    { fetchImpl: async () => assert.fail("v6 session must not reach backend") }
  );
  assert.equal(v6Rejected.status, 404);
  assert.deepEqual(await v6Rejected.json(), {
    detail: { code: "report_not_found" }
  });
});

test("report authentication and busy admission reject before opening the body", async () => {
  resetImageUploadAdmissionForTests();
  const observableRequest = (cookie: string, clientIp: string): { request: Request; opened: () => boolean } => {
    const request = new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: {
        cookie,
        "cf-connecting-ip": clientIp,
        "content-type": "multipart/form-data; boundary=not-opened"
      },
      body: new ReadableStream<Uint8Array>({
        pull: () => new Promise<void>(() => undefined)
      }),
      duplex: "half"
    } as RequestInit & { duplex: "half" });
    const body = request.body!;
    const originalGetReader = body.getReader.bind(body);
    let wasOpened = false;
    Object.defineProperty(body, "getReader", {
      configurable: true,
      value: () => {
        wasOpened = true;
        return originalGetReader();
      }
    });
    return { request, opened: () => wasOpened };
  };

  const unauthorized = observableRequest("", nextClientIp());
  const denied = await handleGatewayRequest(unauthorized.request);
  assert.equal(denied.status, 401);
  assert.equal(unauthorized.opened(), false);

  const cookie = await login();
  const held = acquireImageUploadAdmission(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: { "cf-connecting-ip": nextClientIp() }
    }),
    new Headers({ [ACTOR_ID_HEADER]: ACTOR_ID })
  );
  assert.ok(held.release);
  try {
    const busyRequest = observableRequest(cookie, nextClientIp());
    const busy = await handleGatewayRequest(busyRequest.request);
    assert.equal(busy.status, 503);
    assert.equal((await busy.json() as { detail: { code: string } }).detail.code, "image_upload_busy");
    assert.equal(busyRequest.opened(), false);
  } finally {
    held.release();
  }
});

test("report admission enforces one in-flight upload and twelve actor events per minute", () => {
  resetImageUploadAdmissionForTests();
  const now = Date.now();
  const actorHeaders = new Headers({ [ACTOR_ID_HEADER]: ACTOR_ID });
  const request = new Request("http://127.0.0.1:8081/api/reports/v2", {
    headers: { "cf-connecting-ip": nextClientIp() }
  });
  const first = acquireImageUploadAdmission(request, actorHeaders, now);
  assert.ok(first.release);
  const busy = acquireImageUploadAdmission(request, actorHeaders, now);
  assert.equal(busy.error?.status, 503);
  first.release();

  for (let index = 1; index < 12; index += 1) {
    const admitted = acquireImageUploadAdmission(request, actorHeaders, now);
    assert.ok(admitted.release);
    admitted.release();
  }
  const limited = acquireImageUploadAdmission(request, actorHeaders, now);
  assert.equal(limited.error?.status, 429);
  assert.equal(limited.error?.headers.get("retry-after"), "60");
});

test("bounded navigation body and upstream deadline fail closed", async () => {
  const cookie = await login();
  let called = false;
  const oversized = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: {
        cookie,
        "content-type": "application/json",
        "content-length": String(16 * 1024 + 1)
      },
      body: "{}"
    }),
    { fetchImpl: async () => { called = true; return Response.json({}); } }
  );
  assert.equal(oversized.status, 413);
  assert.equal(called, false);

  const timeoutFetch: GatewayFetch = async (_input, init) => {
    return await new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(init.signal?.reason), { once: true });
    });
  };
  let guard: ReturnType<typeof setTimeout> | undefined;
  const timeout = await Promise.race([
    fetchBackend(
      new Request("http://127.0.0.1:8081/api/navigation/walking"),
      "http://127.0.0.1:8000/navigation/walking",
      {},
      5,
      timeoutFetch
    ),
    new Promise<never>((_resolve, reject) => {
      guard = setTimeout(() => reject(new Error("upstream timeout test did not finish")), 250);
    })
  ]).finally(() => {
    if (guard) clearTimeout(guard);
  });
  assert.equal(timeout.status, 504);
  assert.equal(timeout.headers.get("cache-control"), "no-store");
  assert.equal((await timeout.json() as { detail: { code: string } }).detail.code, "gateway_upstream_timeout");
});
