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
  readBoundedMultipartFormData,
  REPORT_IMAGE_LIMIT_BYTES,
  REPORT_METADATA_LIMIT_BYTES,
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
import { matchGatewayRoute } from "../src/route-registry.js";
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
import { FIELD_SESSION_ERROR_CONTRACT } from "../src/field-session-errors.js";
import {
  assertBackendActorAssertionV4, upstreamFormData
} from "./actor-assertion-v4-fixture.js";
import {
  ACCOUNT_DELETION_INVENTORY_V2,
  DELETION_ACCESS_SECRET_HEADER,
  type AccountDeletionStatusV2
} from "../src/privacy-deletion-v2.js";
import {
  activePrivacyOperationCountForTests,
  abortPrivacyOperationsForAccountDeletion,
  bindBackendActorGeneration,
  currentActorGeneration
} from "../src/privacy-rights.js";

const ACTOR_ID = "field-operator";
const ACCOUNT_TOKEN = "field-account-token-12345678901234567890";
const INTERNAL_TOKEN = "field-internal-token-12345678901234567890";
const SESSION_SECRET = "gateway-session-secret-123456789012345678901234567890";
const WALKING_REQUEST = {
  origin: { latitude: 37.5, longitude: 127.0 },
  destination: { latitude: 37.6, longitude: 127.1 }
};
const WALKING_BODY = JSON.stringify(WALKING_REQUEST);
const REPORT_TRANSPORT_HEADERS = {
  [REPORT_ID_HEADER]: "11111111-1111-4111-8111-111111111111",
  [REPORT_PAYLOAD_SHA256_HEADER]: "1".repeat(64),
  [REPORT_PAYLOAD_BYTES_HEADER]: "123"
};
const WALKING_ROUTE_REPLY = {
  schema_version: "walksafe.walking_route.v1", provider: "tmap_pedestrian",
  priority: "STAIR_AVOID", summary: { distance_m: 12, duration_s: 10 },
  polyline: [
    { latitude: 37.5, longitude: 127.0 },
    { latitude: 37.5001, longitude: 127.0 }
  ],
  steps: [{ index: 0, distance_m: 12, duration_s: 10, points: [
    { latitude: 37.5, longitude: 127.0 },
    { latitude: 37.5001, longitude: 127.0 }
  ] }],
  guide_points: [{ index: 0, point: { latitude: 37.5001, longitude: 127.0 }, instruction: "도착" }],
  provider_result_code: 0, provider_result_message: "OK"
};

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
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    // R19-GB-01: per-file ledger so parallel test files never share the default lock.
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
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

async function reportConsentHeaders(
  cookie: string,
  installationId = "9c68097a-0569-43b6-baa2-1ed35f514f14",
  requestId = "gateway_contract_report_consent_0001"
): Promise<Record<string, string>> {
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
        request_id: requestId,
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 1,
        expected_previous_backend_receipt_sha256: null,
        selections: {
          raw_source_collection: false,
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

const EXPECTED_GATEWAY_METHODS: Readonly<Record<string, readonly string[]>> = {
  "/api/account-enrollments/email-otp": ["post"],
  "/api/accounts": ["post"],
  "/api/field-session": ["delete", "get", "post"],
  "/api/field-walk": ["get", "post"],
  "/api/speech/stt": ["post"],
  "/api/speech/tts": ["post"],
  "/api/navigation/walking": ["post"],
  "/api/navigation/destinations/search": ["get"],
  "/api/reports/v2": ["post"],
  "/api/reports/v2/{report_id}/status": ["get"],
  "/api/reports/mine": ["get"],
  "/api/reports/mine/requests/history": ["get"],
  "/api/reports/mine/deletions/{request_id}": ["get"],
  "/api/reports/mine/{report_id}": ["get"],
  "/api/reports/mine/{report_id}/content": ["get"],
  "/api/reports/mine/{report_id}/corrections": ["post"],
  "/api/reports/mine/{report_id}/requests": ["post"],
  "/api/reports/mine/{report_id}/requests/{request_id}": ["get"],
  "/api/raw-collections/{collection_id}/manifest": ["put"],
  "/api/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}": ["put"],
  "/api/raw-collections/{collection_id}": ["get"],
  "/api/raw-collections/{collection_id}/commit": ["post"],
  "/privacy/rights": ["get", "head", "put"],
  "/privacy/account-deletions": ["post"],
  "/privacy/account-deletions/{request_id}/status": ["get"],
  "/privacy/account-deletions/{request_id}/device-evidence": ["post"]
};

test("S19-54 dispatch registry has the independent public path and method contract", () => {
  const id = "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e";
  for (const [template, declaredMethods] of Object.entries(EXPECTED_GATEWAY_METHODS)) {
    const pathname = template
      .replace("/privacy/account-deletions/{request_id}",
        "/privacy/account-deletions/probe_request_000001")
      .replaceAll("{report_id}", id)
      .replaceAll("{collection_id}", id)
      .replaceAll("{object_id}", id)
      .replaceAll("{index}", "2047")
      .replaceAll("{request_id}", id);
    const match = matchGatewayRoute(new URL(`http://gateway.invalid${pathname}`));
    assert.equal(match?.template, template, template);
    if (template !== "/privacy/rights") {
      const methods = match!.methods.map(method => method.toLowerCase()).sort();
      assert.deepEqual(methods, [...declaredMethods].sort(), template);
    }
  }
  const privacy = (control: string) => matchGatewayRoute(
    new URL(`http://gateway.invalid/privacy/rights?control=${control}`)
  );
  assert.deepEqual(privacy("integrated-consent")?.methods, ["GET", "PUT"]);
  assert.deepEqual(privacy("integrated-consent-bootstrap")?.methods, ["GET"]);
  assert.equal(privacy("integrated-consent-bootstrap")?.unsupportedMethod,
    "bootstrap-validation");
  assert.equal(privacy("account-deletion")?.unsupportedMethod, "retired");
  assert.equal(matchGatewayRoute(new URL("http://gateway.invalid/api/reports/mine"))?.auth,
    "field-concealed");
  assert.equal(matchGatewayRoute(new URL("http://gateway.invalid/api/navigation/walking"))?.deadline,
    "walking");
});

const EXPECTED_EXTERNAL_COPY_PROPERTIES = [
  "institution", "state", "status_recorded_at"
] as const;

function assertExactMembers(actual: readonly string[], expected: readonly string[]): void {
  assert.deepEqual([...actual].sort(), [...expected].sort());
}

function assertGatewayPathMethods(
  paths: Record<string, Record<string, unknown>>,
  publicRoutes: readonly string[]
): void {
  const expectedPaths = Object.keys(EXPECTED_GATEWAY_METHODS);
  assertExactMembers(Object.keys(paths), expectedPaths);
  assertExactMembers(publicRoutes, expectedPaths);
  for (const [route, methods] of Object.entries(EXPECTED_GATEWAY_METHODS)) {
    assertExactMembers(Object.keys(paths[route]!), methods);
  }
}

test("S19-60 README route table is exactly the OpenAPI paths and the router routes", async () => {
  const readme = await readFile(path.join(process.cwd(), "README.md"), "utf8");
  const start = readme.indexOf("## 공개 계약");
  assert.ok(start >= 0);
  const end = readme.indexOf("\n## ", start + 1);
  const section = readme.slice(start, end < 0 ? undefined : end);
  const rows = [...section.matchAll(/^\| `([^`]+)` \| ([A-Z, ]+) \|/gm)].map((match) => [
    match[1]!,
    match[2]!.split(",").map((method) => method.trim().toLowerCase()).sort()
  ] as const);
  const readmeRoutes = Object.fromEntries(rows);
  assert.equal(Object.keys(readmeRoutes).length, rows.length, "README lists every path once");
  const contract = JSON.parse(await readFile(path.join(process.cwd(), "openapi.json"), "utf8")) as {
    paths: Record<string, Record<string, unknown>>;
  };
  assert.deepEqual(
    Object.keys(readmeRoutes).sort(),
    Object.keys(contract.paths).sort(),
    "README route table paths == openapi paths"
  );
  assert.deepEqual(Object.keys(readmeRoutes).sort(), [...PUBLIC_GATEWAY_ROUTES].sort());
  for (const [route, methods] of rows) {
    assert.deepEqual(methods, Object.keys(contract.paths[route]!).sort(), `${route} methods`);
  }
});

test("S19-60 README field-session error codes and statuses match the contract table", async () => {
  const readme = await readFile(path.join(process.cwd(), "README.md"), "utf8");
  const table = new Map(FIELD_SESSION_ERROR_CONTRACT.map((row) => [row.code, row]));
  const sessionPrefix = /^(field_session_|field_long_lived_|refresh_token_|refresh_rotation_|invalid_refresh_token)/;
  const mentioned = [...readme.matchAll(/\b([1-5]\d\d) `([a-z0-9_]+)`/g)];
  let checked = 0;
  for (const match of mentioned) {
    const status = Number(match[1]);
    const code = match[2]!;
    const row = table.get(code);
    if (!row) {
      assert.doesNotMatch(code, sessionPrefix, `README names unregistered field-session code ${code}`);
      continue;
    }
    assert.ok(row.statuses.includes(status), `README says ${status} ${code}; table has ${row.statuses.join(",")}`);
    checked += 1;
  }
  assert.ok(checked >= 10, "README names the field-session error codes it documents");
  for (const code of [
    "field_session_refresh_rate_limited",
    "field_session_storage_unavailable",
    "field_session_storage_outcome_unknown"
  ]) {
    assert.ok(readme.includes(`\`${code}\``), `README documents ${code}`);
  }
});

test("S19-34 README records the first-party trust decision without overclaiming", async () => {
  const readme = await readFile(path.join(process.cwd(), "README.md"), "utf8");
  const start = readme.indexOf("### 보고 목적 신뢰 경계");
  assert.ok(start >= 0, "README keeps the report-purpose trust boundary section");
  const next = readme.slice(start + 1).search(/\n#{2,3} /);
  const section = next < 0 ? readme.slice(start) : readme.slice(start, start + 1 + next);
  assert.match(
    section,
    /S19-34 현 단계 결정: 인증된 first-party 앱을 신뢰하는 범위를 유지하고, 등록 기기·배포 무결성 증명\(attestation\)은 도입하지 않는다/
  );
  assert.match(section, /사용자의 실제 신고 의사나 실제 통신망 사용을 증명한다고 설명하지 않는다/);
  assert.match(section, /`x-walksafe-report-purpose`[^\n]*암호학적 client attestation이 아니다/);
  assert.match(section, /`x-walksafe-network-transport`[^\n]*실제 통신망이나 사용자의 신고 의사를 독립적으로 증명하지 않는다/);
  assert.match(section, /`NOT_COVERED`/);
  // No sentence of the README says the Gateway itself proves report intent or the network used.
  assert.doesNotMatch(readme, /(신고 의사|실제 통신망)[^.\n]*(증명합니다|증명한다\.)/);
});

test("OpenAPI and router expose service, consent-control, and deletion paths", async (t) => {
  const raw = await readFile(path.join(process.cwd(), "openapi.json"), "utf8");
  const contract = JSON.parse(raw) as {
    info: { version: string };
    paths: Record<string, Record<string, unknown>>;
    components: { schemas: Record<string, unknown> };
  };
  assertGatewayPathMethods(contract.paths, PUBLIC_GATEWAY_ROUTES);
  assert.equal(contract.info.version, "0.11.0");
  const rawManifest = contract.paths["/api/raw-collections/{collection_id}/manifest"]!.put as {
    "x-max-body-bytes": number;
    parameters: Array<{ $ref: string }>;
  };
  const rawChunk = contract.paths["/api/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}"]!.put as {
    "x-max-body-bytes": number;
    parameters: Array<{ $ref: string }>;
  };
  const rawStatus = contract.paths["/api/raw-collections/{collection_id}"]!.get as {
    parameters: Array<{ $ref: string }>;
  };
  const rawCommit = contract.paths["/api/raw-collections/{collection_id}/commit"]!.post as {
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
    "x-walksafe-report-purpose",
    REPORT_ID_HEADER,
    REPORT_PAYLOAD_SHA256_HEADER,
    REPORT_PAYLOAD_BYTES_HEADER
  ]) {
    assert.ok(requiredReportHeaders.includes(header));
  }
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
  const userRequestHistory = detailed.paths["/api/reports/mine/requests/history"]!.get!;
  const userDeletion = detailed.paths["/api/reports/mine/deletions/{request_id}"]!.get!;
  const userDetail = detailed.paths["/api/reports/mine/{report_id}"]!.get!;
  const userContent = detailed.paths["/api/reports/mine/{report_id}/content"]!.get!;
  const userCorrection = detailed.paths["/api/reports/mine/{report_id}/corrections"]!.post!;
  const userRequest = detailed.paths["/api/reports/mine/{report_id}/requests"]!.post!;
  const userRequestDetail = detailed.paths[
    "/api/reports/mine/{report_id}/requests/{request_id}"
  ]!.get!;
  for (const operation of [
    userList,
    userRequestHistory,
    userDeletion,
    userDetail,
    userContent,
    userCorrection,
    userRequest,
    userRequestDetail
  ]) {
    assert.deepEqual(operation.security, [{ fieldSession: [] }]);
    assert.match(operation.description ?? "", /current v7/i);
    assert.match(operation.description ?? "", /consent is not required/i);
    assert.doesNotMatch(
      JSON.stringify(operation.parameters ?? []),
      /x-walksafe-consent|consentControlSecret/i
    );
    assert.equal(
      operation.responses?.["429"]?.$ref,
      "#/components/responses/ReportRateLimited"
    );
  }
  assert.equal(userList.responses?.["200"]?.$ref, "#/components/responses/UserReportList");
  assert.equal(
    userRequestHistory.responses?.["200"]?.$ref,
    "#/components/responses/UserReportRequestHistory"
  );
  assert.equal(
    userRequestHistory.responses?.["422"]?.$ref,
    "#/components/responses/GatewayError"
  );
  assert.deepEqual(
    userRequestHistory.parameters?.map(({ name, in: location, required }) => ({
      name,
      in: location,
      required
    })),
    [
      { name: "limit", in: "query", required: false },
      { name: "cursor", in: "query", required: false },
      { name: "report_id", in: "query", required: false }
    ]
  );
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
  assert.equal(
    userRequestDetail.responses?.["200"]?.$ref,
    "#/components/responses/ReportUserRequest"
  );
  for (const schemaName of [
    "ReportUserRequest",
    "UserReport",
    "UserReportList",
    "UserReportDetail",
    "ReportUserRequestCreate",
    "ReportContentCurrent",
    "ReportContentCorrectionRequest",
    "ReportContentRevision",
    "ReportDeletionExternalCopyStatus",
    "ReportDeletionStatus",
    "UserReportRequestHistoryItem",
    "UserReportRequestHistoryPage"
  ]) {
    assert.equal(detailed.components.schemas[schemaName]!.additionalProperties, false);
  }
  const deletionStatusSchema = detailed.components.schemas.ReportDeletionStatus as unknown as {
    description?: string;
    required?: string[];
    properties?: {
      schema_version?: { const?: string };
      external_copies?: { type?: string; items?: { $ref?: string } };
    };
  };
  assert.match(deletionStatusSchema.description ?? "", /count exactly equals/i);
  assert.equal(
    deletionStatusSchema.properties?.schema_version?.const,
    "walksafe.report-deletion-status.v2"
  );
  assert.ok(deletionStatusSchema.required?.includes("external_copies"));
  assert.equal(deletionStatusSchema.properties?.external_copies?.type, "array");
  assert.equal(
    deletionStatusSchema.properties?.external_copies?.items?.$ref,
    "#/components/schemas/ReportDeletionExternalCopyStatus"
  );
  const historyPageSchema = detailed.components.schemas
    .UserReportRequestHistoryPage as unknown as {
      properties?: {
        schema_version?: { const?: string };
        items?: { maxItems?: number; items?: { $ref?: string } };
      };
    };
  assert.equal(
    historyPageSchema.properties?.schema_version?.const,
    "walksafe.user-report-request-history-page.v1"
  );
  assert.equal(historyPageSchema.properties?.items?.maxItems, 25);
  assert.equal(
    historyPageSchema.properties?.items?.items?.$ref,
    "#/components/schemas/UserReportRequestHistoryItem"
  );
  const externalCopySchema = detailed.components.schemas
    .ReportDeletionExternalCopyStatus as unknown as {
      required?: string[];
      properties?: Record<string, unknown>;
    };
  assert.deepEqual(externalCopySchema.required, [
    "institution", "state", "status_recorded_at"
  ]);
  assertExactMembers(
    Object.keys(externalCopySchema.properties ?? {}),
    EXPECTED_EXTERNAL_COPY_PROPERTIES
  );

  await t.test("public contract inventory accepts equivalent key ordering without mutation", () => {
    const reorderedPaths = Object.fromEntries(
      Object.entries(contract.paths).reverse().map(([route, methods]) => [
        route,
        Object.fromEntries(Object.entries(methods).reverse())
      ])
    );
    const reorderedProperties = Object.fromEntries(
      Object.entries(externalCopySchema.properties ?? {}).reverse()
    );
    const reorderedRoutes = [...PUBLIC_GATEWAY_ROUTES].reverse();
    const routesBefore = [...reorderedRoutes];
    const exportedRoutesBefore = [...PUBLIC_GATEWAY_ROUTES];
    assertGatewayPathMethods(reorderedPaths, reorderedRoutes);
    assertExactMembers(Object.keys(reorderedProperties), EXPECTED_EXTERNAL_COPY_PROPERTIES);
    assert.deepEqual(reorderedRoutes, routesBefore);
    assert.deepEqual(PUBLIC_GATEWAY_ROUTES, exportedRoutesBefore);
  });

  for (const mutation of [
    "missing path",
    "extra path",
    "missing method",
    "extra method",
    "missing property",
    "extra property",
    "missing public route",
    "extra public route",
    "duplicate public route"
  ] as const) {
    await t.test(`public contract inventory rejects ${mutation}`, () => {
      const paths: Record<string, Record<string, unknown>> = Object.fromEntries(
        Object.entries(contract.paths).map(([route, methods]) => [route, { ...methods }])
      );
      const routes: string[] = [...PUBLIC_GATEWAY_ROUTES];
      const properties = { ...(externalCopySchema.properties ?? {}) };
      switch (mutation) {
        case "missing path":
          delete paths["/api/speech/stt"];
          break;
        case "extra path":
          paths["/api/unexpected"] = { post: {} };
          break;
        case "missing method":
          delete paths["/api/speech/stt"]!.post;
          break;
        case "extra method":
          paths["/api/speech/stt"]!.delete = {};
          break;
        case "missing property":
          delete properties.state;
          break;
        case "extra property":
          properties.unexpected = { type: "string" };
          break;
        case "missing public route":
          routes.splice(0, 1);
          break;
        case "extra public route":
          routes.push("/api/unexpected");
          break;
        case "duplicate public route":
          routes.push(routes[0]!);
          break;
      }
      assert.throws(() => {
        assertGatewayPathMethods(paths, routes);
        assertExactMembers(Object.keys(properties), EXPECTED_EXTERNAL_COPY_PROPERTIES);
      }, { code: "ERR_ASSERTION" });
    });
  }
  const historyContract = contract.paths["/api/reports/mine/requests/history"]!
    .get as {
      "x-max-response-bytes"?: number;
      parameters?: Array<{
        name?: string;
        schema?: {
          minimum?: number;
          maximum?: number;
          default?: number;
          maxLength?: number;
          pattern?: string;
        };
      }>;
    };
  assert.equal(historyContract["x-max-response-bytes"], 48 * 1024);
  const historyParameters = new Map(
    historyContract.parameters?.map(parameter => [parameter.name, parameter.schema])
  );
  assert.deepEqual(historyParameters.get("limit"), {
    type: "integer",
    minimum: 1,
    maximum: 25,
    default: 10
  });
  assert.equal(historyParameters.get("cursor")?.maxLength, 1024);
  assert.equal(
    historyParameters.get("report_id")?.pattern,
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
  );
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
        description?: string;
        headers?: Record<string, {
          $ref?: string;
          schema?: { type?: string; minimum?: number };
        }>;
        content?: { "application/json"?: { schema?: { $ref?: string } } };
      }>;
    };
  }).components.responses;
  assert.equal(
    reportRightsResponses.ReportUserRequest!.description,
    "Public report-scoped user request status"
  );
  assert.equal(
    reportRightsResponses.ReportRateLimited!.headers?.["Cache-Control"]?.$ref,
    "#/components/headers/NoStore"
  );
  assert.deepEqual(
    reportRightsResponses.ReportRateLimited!.headers?.["Retry-After"]?.schema,
    { type: "integer", minimum: 0 }
  );
  assert.equal(
    reportRightsResponses.ReportRateLimited!.content?.["application/json"]?.schema?.$ref,
    "#/components/schemas/GatewayError"
  );
  for (const [name, schemaName] of [
    ["UserReportRequestHistory", "UserReportRequestHistoryPage"],
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
      raw_source_collection: { type: "string", const: "FP-013-RAW-1.2.0" },
      automatic_reporting: { type: "string", const: "FP-013-AUTO-1.2.0" },
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
  assert.match(raw, /explicit does not require optional raw-source consent/);
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

test("duplicate short-session cookies cannot report a successful logout", async () => {
  const cookie = await login();
  const url = "https://gateway.invalid/api/field-session";
  const duplicate = `${cookie}; walksafe_field_session=invalid`;

  const ambiguousStatus = await handleGatewayRequest(
    new Request(url, { headers: { cookie: duplicate } })
  );
  assert.equal(ambiguousStatus.status, 200);
  assert.equal((await ambiguousStatus.json() as { authenticated: boolean }).authenticated, false);

  const ambiguousLogout = await handleGatewayRequest(
    new Request(url, { method: "DELETE", headers: { cookie: duplicate } })
  );
  assert.equal(ambiguousLogout.status, 401);
  assert.equal(ambiguousLogout.headers.get("cache-control"), "no-store");
  assert.equal(ambiguousLogout.headers.get("set-cookie"), null);
  assert.equal((await ambiguousLogout.json() as { code: string }).code, "gateway_auth_required");

  const noCookieLogout = await handleGatewayRequest(new Request(url, { method: "DELETE" }));
  assert.equal(noCookieLogout.status, 204);
  const beforeLogout = await handleGatewayRequest(new Request(url, { headers: { cookie } }));
  assert.equal((await beforeLogout.json() as { authenticated: boolean }).authenticated, true);

  const logout = await handleGatewayRequest(
    new Request(url, { method: "DELETE", headers: { cookie } })
  );
  assert.equal(logout.status, 204);
  const afterLogout = await handleGatewayRequest(new Request(url, { headers: { cookie } }));
  assert.equal((await afterLogout.json() as { authenticated: boolean }).authenticated, false);
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
      String(input).endsWith("/navigation/walking") ? WALKING_ROUTE_REPLY : { ok: true },
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

  const walkingBody = WALKING_BODY;
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
  assert.deepEqual(await walking.json(), WALKING_ROUTE_REPLY);

  const first = calls[0]!;
  assert.equal(first.url, "http://127.0.0.1:8000/navigation/walking");
  assert.equal(first.init.method, "POST");
  assert.equal(first.init.body, walkingBody);
  assert.equal(first.init.redirect, "error");
  const walkingHeaders = new Headers(first.init.headers);
  assert.equal(walkingHeaders.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
  assert.equal(walkingHeaders.get(ACTOR_ID_HEADER), ACTOR_ID);
  assertBackendActorAssertionV4(first.url, first.init, {
    actorId: ACTOR_ID, accountGeneration: 1, authEpoch: null
  });
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
  assert.equal(
    assertBackendActorAssertionV4(calls[1]!.url, calls[1]!.init, {
      actorId: ACTOR_ID, accountGeneration: 1, authEpoch: null
    }).target,
    "/navigation/destinations/search?query=%EC%84%9C%EC%9A%B8&limit=5"
  );
  assert.equal(search.headers.get("cache-control"), "no-store");
  assert.deepEqual(await search.json(), { ok: true });
});

test("navigation walking rejects invalid requests before Backend and accepts valid options", async () => {
  const cookie = await login();
  let calls = 0;
  const fetchImpl: GatewayFetch = async () => {
    calls += 1;
    return Response.json(WALKING_ROUTE_REPLY);
  };
  const invalidBodies = [
    { name: "content type", contentType: "text/plain", body: WALKING_BODY },
    { name: "malformed JSON", contentType: "application/json", body: '{"origin":' },
    { name: "missing destination", contentType: "application/json",
      body: JSON.stringify({ origin: WALKING_REQUEST.origin }) },
    { name: "nonfinite origin", contentType: "application/json",
      body: '{"origin":{"latitude":1e999,"longitude":127},"destination":{"latitude":37.6,"longitude":127.1}}' },
    { name: "out-of-range destination", contentType: "application/json",
      body: JSON.stringify({ ...WALKING_REQUEST,
        destination: { latitude: 37.6, longitude: 180.1 } }) },
    { name: "too many waypoints", contentType: "application/json",
      body: JSON.stringify({ ...WALKING_REQUEST,
        waypoints: Array.from({ length: 6 }, () => WALKING_REQUEST.origin) }) },
    { name: "invalid waypoint", contentType: "application/json",
      body: JSON.stringify({ ...WALKING_REQUEST,
        waypoints: [{ latitude: 91, longitude: 127 }] }) },
    { name: "invalid priority", contentType: "application/json",
      body: JSON.stringify({ ...WALKING_REQUEST, priority: "FASTEST" }) },
    { name: "invalid radius", contentType: "application/json",
      body: JSON.stringify({ ...WALKING_REQUEST, radius_m: 0 }) },
    { name: "noninteger radius", contentType: "application/json",
      body: JSON.stringify({ ...WALKING_REQUEST, radius_m: 1.5 }) },
    { name: "invalid speed", contentType: "application/json",
      body: JSON.stringify({ ...WALKING_REQUEST, default_speed: 21 }) }
  ];
  for (const { name, contentType, body } of invalidBodies) {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/walking", {
        method: "POST", headers: { cookie, "content-type": contentType }, body
      }),
      { fetchImpl }
    );
    assert.equal(response.status, 400, name);
    assert.equal(response.headers.get("cache-control"), "no-store", name);
    assert.deepEqual(await response.json(), { code: "navigation_request_invalid" }, name);
    assert.equal(calls, 0, name);
    assert.equal(activePrivacyOperationCountForTests(), 0, name);
  }

  const fullBody = JSON.stringify({
    ...WALKING_REQUEST,
    waypoints: [{ latitude: 37.55, longitude: 127.05, name: "via" }],
    priority: "MAIN_STREET", radius_m: 12000, default_speed: 20
  });
  const accepted = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: { cookie, "content-type": "application/json; charset=utf-8" },
      body: fullBody
    }),
    { fetchImpl }
  );
  assert.equal(accepted.status, 200);
  assert.equal(accepted.headers.get("cache-control"), "no-store");
  assert.deepEqual(await accepted.json(), WALKING_ROUTE_REPLY);
  assert.equal(calls, 1);
  assert.equal(activePrivacyOperationCountForTests(), 0);
});

test("destination search rejects duplicate, unknown, and invalid query before Backend", async () => {
  const cookie = await login();
  let calls = 0;
  const fetchImpl: GatewayFetch = async () => {
    calls += 1;
    return Response.json({ ok: true });
  };
  const invalidQueries = [
    "", "?query=", `?query=${"x".repeat(81)}`,
    "?query=test&query=again", "?query=test&limit=2&limit=3",
    "?query=test&unknown=1", "?query=test&limit=0", "?query=test&limit=11",
    "?query=test&limit=1.5", "?query=test&origin_lat=37.5",
    "?query=test&origin_lat=NaN&origin_lng=127",
    "?query=test&origin_lat=91&origin_lng=127",
    "?query=test&origin_lat=37.5&origin_lng=181"
  ];
  for (const query of invalidQueries) {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/destinations/search" + query, {
        headers: { cookie }
      }),
      { fetchImpl }
    );
    assert.equal(response.status, 400, query);
    assert.equal(response.headers.get("cache-control"), "no-store", query);
    assert.deepEqual(await response.json(), { code: "navigation_request_invalid" }, query);
    assert.equal(calls, 0, query);
    assert.equal(activePrivacyOperationCountForTests(), 0, query);
  }
});

test("S07-30 navigation proxy deadline ends before the Android 8 s read timeout and cancels upstream", async (t) => {
  const cookie = await login();
  // Backend TMAP deadline is 4 s and the Android route client reads for 8 s,
  // so the Gateway must answer with its own cause-bearing 504 in between.
  const ANDROID_READ_TIMEOUT_MS = 8_000;
  const BACKEND_TMAP_DEADLINE_MS = 4_000;
  // AbortSignal.timeout() timers are unref'ed; keep the test process alive.
  const keepAlive = setInterval(() => undefined, 1_000);
  t.after(() => clearInterval(keepAlive));
  const stalledUntilAbort: GatewayFetch = (_input, init = {}) =>
    new Promise<Response>((_resolve, reject) => {
      const signal = init.signal!;
      signal.addEventListener("abort", () => reject(signal.reason), { once: true });
    });
  const timed = async (request: Request) => {
    const started = performance.now();
    const response = await handleGatewayRequest(request, { fetchImpl: stalledUntilAbort });
    return { response, elapsed: performance.now() - started };
  };
  const [walking, search] = await Promise.all([
    timed(new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: WALKING_BODY
    })),
    timed(new Request("http://127.0.0.1:8081/api/navigation/destinations/search?query=test", {
      headers: { cookie }
    }))
  ]);
  for (const [name, { response, elapsed }] of [["walking", walking], ["search", search]] as const) {
    assert.equal(response.status, 504, name);
    assert.deepEqual(
      ((await response.json()) as { detail: { code: string } }).detail.code,
      "gateway_upstream_timeout",
      name
    );
    assert.ok(elapsed > BACKEND_TMAP_DEADLINE_MS, `${name} ${elapsed}`);
    assert.ok(elapsed < ANDROID_READ_TIMEOUT_MS - 1_000, `${name} ${elapsed}`);
  }
  assert.equal(activePrivacyOperationCountForTests(), 0);

  const client = new AbortController();
  let upstreamAborted = false;
  const cancelled = handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: WALKING_BODY,
      signal: client.signal
    }),
    {
      fetchImpl: (_input, init = {}) => new Promise<Response>((_resolve, reject) => {
        init.signal!.addEventListener("abort", () => {
          upstreamAborted = true;
          reject(init.signal!.reason);
        }, { once: true });
        setTimeout(() => client.abort(), 20);
      })
    }
  );
  const cancelledResponse = await cancelled;
  assert.equal(upstreamAborted, true);
  assert.equal(cancelledResponse.status, 499);
  assert.equal(activePrivacyOperationCountForTests(), 0);
});

test("S07-30 navigation budget includes account gate and request body, and waits for the full upstream body", async (t) => {
  const cookie = backendAccountSessionCookie("s07-30-device");
  const keepAlive = setInterval(() => undefined, 1_000);
  t.after(() => clearInterval(keepAlive));
  const url = "http://127.0.0.1:8081";
  const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
  let gateUpstreamAborted = false;
  const gateStarted = performance.now();
  const accountGateThenSearch = handleGatewayRequest(
    new Request(`${url}/api/navigation/destinations/search?query=test`, { headers: { cookie } }),
    { fetchImpl: async (input, init) => {
      if (String(input).endsWith("/accounts/session-current")) {
        await pause(4_000);
        return new Response(null, { status: 204 });
      }
      return await new Promise<Response>((_resolve, reject) => {
        const signal = init!.signal!;
        signal.addEventListener("abort", () => {
          gateUpstreamAborted = true;
          reject(signal.reason);
        }, { once: true });
      });
    } }
  ).then(response => ({ response, elapsed: performance.now() - gateStarted }));

  const bodyAbort = new AbortController();
  let bodyCancelled = false;
  const slowBody = new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(new TextEncoder().encode('{"origin":')); },
    cancel() { bodyCancelled = true; }
  });
  const bodyStarted = performance.now();
  const bodyThenWalking = handleGatewayRequest(
    new Request(`${url}/api/navigation/walking`, {
      method: "POST", headers: { cookie, "content-type": "application/json" },
      body: slowBody, duplex: "half", signal: bodyAbort.signal
    } as RequestInit & { duplex: "half" }),
    { fetchImpl: async (input) => String(input).endsWith("/accounts/session-current")
      ? new Response(null, { status: 204 }) : Response.json({ unexpected: true }) }
  ).then(response => ({ response, elapsed: performance.now() - bodyStarted }));

  let upstreamBodyCancelled = false;
  const chunkedStarted = performance.now();
  const chunkedSearch = handleGatewayRequest(
    new Request(`${url}/api/navigation/destinations/search?query=chunked`, { headers: { cookie } }),
    { fetchImpl: async (input) => String(input).endsWith("/accounts/session-current")
      ? new Response(null, { status: 204 })
      : new Response(new ReadableStream<Uint8Array>({
          start(controller) { controller.enqueue(new TextEncoder().encode('{"items":')); },
          cancel() { upstreamBodyCancelled = true; }
        }), { status: 200, headers: { "content-type": "application/json" } }) }
  ).then(response => ({ response, elapsed: performance.now() - chunkedStarted }));

  const [gate, body, chunked] = await Promise.all([accountGateThenSearch, bodyThenWalking, chunkedSearch]);
  for (const [name, { response }] of [["gate", gate], ["body", body], ["chunked", chunked]] as const) {
    assert.equal(response.status, 504, name);
    assert.equal((await response.json() as { detail: { code: string } }).detail.code,
      name === "chunked" ? "gateway_upstream_timeout" : "gateway_navigation_timeout", name);
  }
  assert.ok(gate.elapsed < 7_000, `search gate + proxy ${gate.elapsed} ms`);
  assert.ok(body.elapsed < 8_000, `walking request body ${body.elapsed} ms`);
  assert.ok(chunked.elapsed < 5_500, `upstream body ${chunked.elapsed} ms`);
  assert.equal(gateUpstreamAborted, true);
  assert.equal(bodyCancelled, true);
  assert.equal(upstreamBodyCancelled, true);
  assert.equal(activePrivacyOperationCountForTests(), 0);
});

test("S07-30 navigation response buffering stops above the Android 2 MiB limit", async () => {
  const cookie = await login();
  let upstreamCancelled = false;
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/destinations/search?query=test", {
      headers: { cookie }
    }),
    { fetchImpl: async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new Uint8Array(2 * 1024 * 1024 + 1)); },
      cancel() { upstreamCancelled = true; }
    }), { status: 200, headers: { "content-type": "application/json" } }) }
  );
  assert.equal(response.status, 502);
  assert.equal((await response.json() as { detail: { code: string } }).detail.code,
    "gateway_upstream_response_too_large");
  assert.equal(upstreamCancelled, true);
  assert.equal(activePrivacyOperationCountForTests(), 0);
});

test("S19-48 walking response rejects oversized geometry and malformed coordinates before 200", async (t) => {
  const cookie = await login();
  const start = { latitude: 37.5, longitude: 127.0 };
  const end = { latitude: 37.5001, longitude: 127.0 };
  const validRoute = () => structuredClone(WALKING_ROUTE_REPLY);
  const request = (payload: unknown, status = 200) => handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST", headers: { cookie, "content-type": "application/json" },
      body: WALKING_BODY
    }),
    { fetchImpl: async () => Response.json(payload, { status }) }
  );
  const cases: Array<[string, Record<string, unknown>]> = [
    ["missing route shape", { ok: true }],
    ["polyline > 8192", { ...validRoute(), polyline: Array(8193).fill(start) }],
    ["steps > 1024", { ...validRoute(), steps: Array(1025).fill({ points: [start] }) }],
    ["guide_points > 1024", { ...validRoute(), guide_points: Array(1025).fill({ point: end }) }],
    ["sum steps[].points > 8192", {
      ...validRoute(), steps: Array(9).fill({ points: Array(1000).fill(start) })
    }],
    ["polyline latitude out of range", { ...validRoute(), polyline: [{ latitude: 91, longitude: 127 }, end] }],
    ["step longitude out of range", {
      ...validRoute(), steps: [{ points: [{ latitude: 37.5, longitude: 181 }] }]
    }],
    ["guide point not numeric", {
      ...validRoute(), guide_points: [{ point: { latitude: "37.5", longitude: 127 } }]
    }]
  ];
  for (const [name, payload] of cases) {
    await t.test(name, async () => {
      assert.ok(Buffer.byteLength(JSON.stringify(payload)) < 2 * 1024 * 1024);
      const response = await request(payload);
      assert.equal(response.status, 502);
      assert.equal((await response.json() as { detail: { code: string } }).detail.code,
        "gateway_upstream_unavailable");
    });
  }
  for (const [name, payload] of [
    ["normal route", validRoute()],
    ["empty steps", { ...validRoute(), steps: [] }],
    ["optional guides omitted", { ...validRoute(), guide_points: undefined }]
  ] as const) {
    await t.test(name, async () => {
      const response = await request(payload);
      assert.equal(response.status, 200);
      assert.equal((await response.json() as { schema_version: string }).schema_version,
        "walksafe.walking_route.v1");
    });
  }
  for (const status of [201, 206]) {
    await t.test(`walking route must reject upstream ${status}`, async () => {
      const response = await request(validRoute(), status);
      const body = await response.json() as { detail?: { code?: string } };
      assert.equal(response.status, 502);
      assert.equal(body.detail?.code, "gateway_upstream_unavailable");
    });
  }
  await t.test("body stream error after response headers", async () => {
    let pulls = 0;
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/walking", {
        method: "POST", headers: { cookie, "content-type": "application/json" }, body: WALKING_BODY
      }),
      { fetchImpl: async () => new Response(new ReadableStream<Uint8Array>({
        pull(controller) {
          if (pulls++ === 0) controller.enqueue(new TextEncoder().encode('{"schema_version":'));
          else controller.error(new Error("upstream response tail failed"));
        }
      }), { status: 200, headers: { "content-type": "application/json" } }) }
    );
    assert.equal(response.status, 502);
    assert.equal((await response.json() as { detail: { code: string } }).detail.code,
      "gateway_upstream_unavailable");
    assert.ok(pulls >= 2);
  });
  assert.equal(activePrivacyOperationCountForTests(), 0);
});

test("account deletion aborts an in-flight proxied response body before delivery", async () => {
  const cookie = await login();
  let upstreamCancelled = false;
  let bodyStarted!: () => void;
  const readingBody = new Promise<void>((resolve) => { bodyStarted = resolve; });
  const upstreamBody = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('{"prefix":'));
      bodyStarted();
    },
    cancel() {
      upstreamCancelled = true;
    }
  });
  const pending = handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: WALKING_BODY
    }),
    {
      fetchImpl: async () => new Response(upstreamBody, {
        status: 200,
        headers: { "content-type": "application/json" }
      })
    }
  );
  await readingBody;
  await new Promise<void>((resolve) => setImmediate(resolve));

  const generation = currentActorGeneration(ACTOR_ID);
  assert.notEqual(generation, null);
  abortPrivacyOperationsForAccountDeletion(ACTOR_ID, generation!);

  const response = await pending;
  assert.equal(response.status, 409);
  assert.equal((await response.json() as { detail: { code: string } }).detail.code,
    "account_generation_inactive");
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(upstreamCancelled, true);
});

test("proxied response completion, body failure, and client cancellation preserve outcomes", async () => {
  const cookie = await login();
  const completed = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/destinations/search?query=test", {
      headers: { cookie }
    }),
    {
      fetchImpl: async () => new Response('{"ok":true}', {
        status: 200,
        headers: { "content-type": "application/json" }
      })
    }
  );
  assert.equal(completed.status, 200);
  assert.equal(completed.headers.get("content-type"), "application/json");
  assert.equal(await completed.text(), '{"ok":true}');

  const failed = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/destinations/search?query=test", {
      headers: { cookie }
    }),
    {
      fetchImpl: async () => new Response(new ReadableStream<Uint8Array>({
        start(controller) {
          controller.error(new Error("upstream response failed"));
        }
      }), { status: 200 })
    }
  );
  assert.equal(failed.status, 502);
  assert.equal((await failed.json() as { detail: { code: string } }).detail.code,
    "gateway_upstream_unavailable");

  let upstreamCancelled = false;
  const clientAbort = new AbortController();
  let bodyStarted!: () => void;
  const readingBody = new Promise<void>((resolve) => { bodyStarted = resolve; });
  const pending = handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/destinations/search?query=test", {
      headers: { cookie }, signal: clientAbort.signal
    }),
    {
      fetchImpl: async () => new Response(new ReadableStream<Uint8Array>({
        start() { bodyStarted(); },
        cancel() {
          upstreamCancelled = true;
        }
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      })
    }
  );
  await readingBody;
  clientAbort.abort(new Error("client stopped reading"));
  assert.equal((await pending).status, 499);
  assert.equal(upstreamCancelled, true);
});

test("client abort releases a pending proxied body and its privacy lease", async () => {
  const cookie = await login();
  const clientAbort = new AbortController();
  const baselineOperations = activePrivacyOperationCountForTests();
  let upstreamCancelled = false;
  let bodyStarted!: () => void;
  const readingBody = new Promise<void>((resolve) => { bodyStarted = resolve; });
  const pending = handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/destinations/search?query=test", {
      headers: { cookie },
      signal: clientAbort.signal
    }),
    {
      fetchImpl: async () => new Response(new ReadableStream<Uint8Array>({
        start() { bodyStarted(); },
        cancel() {
          upstreamCancelled = true;
        }
      }), { status: 200 })
    }
  );
  await readingBody;
  assert.equal(activePrivacyOperationCountForTests(), baselineOperations + 1);

  clientAbort.abort(new Error("client disconnected"));
  assert.equal((await pending).status, 499);
  await new Promise<void>((resolve) => setImmediate(resolve));

  assert.equal(upstreamCancelled, true);
  assert.equal(activePrivacyOperationCountForTests(), baselineOperations);
});

test("destination search keeps each origin and permits retry without replacing the session", async () => {
  const cookie = await login();
  const origins: Array<Record<string, string>> = [
    {},
    { origin_lat: "37.5665", origin_lng: "126.978" },
    { origin_lat: "35.1796", origin_lng: "129.0756" }
  ];
  for (const origin of origins) {
    const query = new URLSearchParams({ query: "Cafe & Station", limit: "2", ...origin });
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/destinations/search?" + query, {
        headers: { cookie, [ACTOR_ID_HEADER]: "spoofed-actor" }
      }),
      {
        fetchImpl: async (input, init) => {
          const upstreamUrl = new URL(String(input));
          assert.equal(upstreamUrl.pathname, "/navigation/destinations/search");
          assert.deepEqual([...upstreamUrl.searchParams], [...query]);
          assert.equal(init?.cache, "no-store");
          const headers = new Headers(init?.headers);
          assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR_ID);
          assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
          assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
          return Response.json({
            schema_version: "walksafe.destination_search.v1", provider: "tmap_poi",
            query: "Cafe & Station", results: []
          });
        }
      }
    );
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    await response.json();
    assert.equal(activePrivacyOperationCountForTests(), 0);
  }

  const query = new URLSearchParams({
    query: "Cafe & Station", limit: "2", origin_lat: "35.1796", origin_lng: "129.0756"
  });
  let attempts = 0;
  const fetchImpl: GatewayFetch = async (input) => {
    assert.deepEqual([...new URL(String(input)).searchParams], [...query]);
    attempts += 1;
    if (attempts === 1) {
      return Response.json(
        { detail: { code: "tmap_rate_limited" } },
        { status: 503, headers: { "retry-after": "2" } }
      );
    }
    if (attempts === 2) throw new TypeError("test transport disconnected");
    return Response.json({
      schema_version: "walksafe.destination_search.v1", provider: "tmap_poi",
      query: "Cafe & Station", results: []
    });
  };
  for (const expectedStatus of [503, 502, 200]) {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/destinations/search?" + query, {
        headers: { cookie }
      }),
      { fetchImpl }
    );
    assert.equal(response.status, expectedStatus);
    if (expectedStatus === 503) assert.equal(response.headers.get("retry-after"), "2");
    await response.json();
    assert.equal(activePrivacyOperationCountForTests(), 0);
  }
  assert.equal(attempts, 3);
  const status = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", { headers: { cookie } })
  );
  assert.equal((await status.json() as { authenticated: boolean }).authenticated, true);
});

test("upstream 401 and 403 never invalidate the Android field session", async () => {
  const cookie = await login();
  for (const upstreamStatus of [401, 403]) {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/navigation/walking", {
        method: "POST",
        headers: { cookie, "content-type": "application/json" },
        body: WALKING_BODY
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
    // S19-52: the Gateway serializes the validated form once and signs those bytes;
    // the boundary is the Gateway's own, never the client's.
    assert.ok(ArrayBuffer.isView(init?.body));
    const forwarded = await upstreamFormData(init);
    assert.deepEqual([...forwarded.keys()], ["metadata", "image"]);
    assert.equal(
      forwarded.get("metadata"),
      "{\"source\":\"android\",\"auto_reported\":false}"
    );
    assert.ok(forwarded.get("image") instanceof Blob);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
    assertBackendActorAssertionV4(input, init, {
      actorId: ACTOR_ID, accountGeneration: 1, authEpoch: null
    });
    assert.equal(headers.get(REPORT_ID_HEADER), REPORT_TRANSPORT_HEADERS[REPORT_ID_HEADER]);
    assert.equal(headers.get(REPORT_PAYLOAD_SHA256_HEADER), REPORT_TRANSPORT_HEADERS[REPORT_PAYLOAD_SHA256_HEADER]);
    assert.equal(headers.get(REPORT_PAYLOAD_BYTES_HEADER), REPORT_TRANSPORT_HEADERS[REPORT_PAYLOAD_BYTES_HEADER]);
    return Response.json({ id: "report-id" }, { status: 201 });
  };
  const form = new FormData();
  form.set("metadata", "{\"source\":\"android\",\"auto_reported\":false}");
  form.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
  const uploaded = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders, ...REPORT_TRANSPORT_HEADERS },
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
        ...REPORT_TRANSPORT_HEADERS,
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
        headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders, ...REPORT_TRANSPORT_HEADERS },
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
      headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders, ...REPORT_TRANSPORT_HEADERS },
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
      headers: { cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders, ...REPORT_TRANSPORT_HEADERS },
      body: largeImage
    }),
    { fetchImpl }
  );
  assert.equal(rejectedImage.status, 413);
  assert.equal(calls, 1);
});

test("S19-49 multipart reader accepts a split near-limit valid body", async () => {
  const metadata = "m".repeat(REPORT_METADATA_LIMIT_BYTES);
  const form = new FormData();
  form.set("metadata", metadata);
  form.set("image", new Blob([new Uint8Array(REPORT_IMAGE_LIMIT_BYTES)], { type: "image/jpeg" }), "report.jpg");
  const encoded = new Response(form);
  const contentType = encoded.headers.get("content-type");
  assert.ok(contentType);
  const body = new Uint8Array(await encoded.arrayBuffer());
  assert.ok(body.byteLength > IMAGE_MULTIPART_LIMIT_BYTES / 2);
  assert.ok(body.byteLength <= IMAGE_MULTIPART_LIMIT_BYTES);
  let offset = 0;
  const request = new Request("http://127.0.0.1:8081/api/reports/v2", {
    method: "POST",
    headers: { "content-type": contentType },
    body: new ReadableStream<Uint8Array>({
      pull(controller) {
        if (offset === body.byteLength) {
          controller.close();
          return;
        }
        const end = Math.min(offset + 128 * 1024, body.byteLength);
        controller.enqueue(body.subarray(offset, end));
        offset = end;
      }
    }),
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const result = await readBoundedMultipartFormData(request, IMAGE_MULTIPART_LIMIT_BYTES);
  assert.equal(result.error, undefined);
  assert.equal(result.formData?.get("metadata"), metadata);
  const image = result.formData?.get("image");
  assert.ok(image instanceof Blob);
  assert.equal(image.size, REPORT_IMAGE_LIMIT_BYTES);
});

test("S19-49 multipart reader preserves over-limit, abort, and timeout responses", async () => {
  const url = "http://127.0.0.1:8081/api/reports/v2";
  const contentType = "multipart/form-data; boundary=s1949";
  const declaredOversized = await readBoundedMultipartFormData(new Request(url, {
    method: "POST",
    headers: {
      "content-type": contentType,
      "content-length": String(IMAGE_MULTIPART_LIMIT_BYTES + 1)
    },
    body: "x"
  }), IMAGE_MULTIPART_LIMIT_BYTES);
  assert.equal(declaredOversized.error?.status, 413);

  let part = 0;
  const streamedOversized = await readBoundedMultipartFormData(new Request(url, {
    method: "POST",
    headers: { "content-type": contentType },
    body: new ReadableStream<Uint8Array>({
      pull(controller) {
        if (part === 0) controller.enqueue(new Uint8Array(5 * 1024 * 1024));
        else if (part === 1) controller.enqueue(new Uint8Array(IMAGE_MULTIPART_LIMIT_BYTES - 5 * 1024 * 1024 + 1));
        else controller.close();
        part += 1;
      }
    }),
    duplex: "half"
  } as RequestInit & { duplex: "half" }), IMAGE_MULTIPART_LIMIT_BYTES);
  assert.equal(streamedOversized.error?.status, 413);

  const stalledBody = () => new ReadableStream<Uint8Array>({
    pull: () => new Promise<void>(() => undefined)
  });
  const controller = new AbortController();
  const abortedRequest = new Request(url, {
    method: "POST",
    headers: { "content-type": contentType },
    body: stalledBody(),
    signal: controller.signal,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const abortedRead = readBoundedMultipartFormData(abortedRequest, IMAGE_MULTIPART_LIMIT_BYTES);
  controller.abort();
  assert.equal((await abortedRead).error?.status, 499);

  let watchdog: ReturnType<typeof setTimeout> | undefined;
  try {
    const timedOut = await Promise.race([
      readBoundedMultipartFormData(new Request(url, {
        method: "POST",
        headers: { "content-type": contentType },
        body: stalledBody(),
        duplex: "half"
      } as RequestInit & { duplex: "half" }), IMAGE_MULTIPART_LIMIT_BYTES, 5),
      new Promise<never>((_, reject) => {
        watchdog = setTimeout(() => reject(new Error("S19-49 multipart reader timeout did not resolve")), 1_000);
      })
    ]);
    assert.equal(timedOut.error?.status, 408);
  } finally {
    if (watchdog !== undefined) clearTimeout(watchdog);
  }
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

test("S19-33 report without a complete transport identity is rejected before reading body", async () => {
  const cookie = await login();
  const consentHeaders = await reportConsentHeaders(
    cookie, "s19_33_required_headers_installation", "s19_33_required_headers_consent_0001"
  );
  let backendCalls = 0;
  for (const headers of [
    {},
    { [REPORT_ID_HEADER]: "aaaaaaaa-1111-4111-8111-111111111111" },
    {
      [REPORT_ID_HEADER]: "aaaaaaaa-1111-4111-8111-111111111111",
      [REPORT_PAYLOAD_SHA256_HEADER]: "a".repeat(64)
    }
  ]) {
    let bodyOpened = false;
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/reports/v2", {
        method: "POST",
        headers: {
          cookie,
          "cf-connecting-ip": nextClientIp(),
          ...consentHeaders,
          "content-type": "multipart/form-data; boundary=unopened",
          ...headers
        },
        body: new ReadableStream<Uint8Array>({
          pull(controller) {
            bodyOpened = true;
            controller.enqueue(new TextEncoder().encode("must-not-read"));
            controller.close();
          }
        }, { highWaterMark: 0 }),
        duplex: "half"
      } as RequestInit & { duplex: "half" }),
      { fetchImpl: async () => {
        backendCalls += 1;
        return Response.json({ id: "must-not-store" }, { status: 201 });
      } }
    );
    assert.equal(response.status, 422);
    assert.deepEqual(await response.json(), {
      detail: { code: "report_transport_headers_incomplete" }
    });
    assert.equal(bodyOpened, false);
    assert.equal(backendCalls, 0);
  }
});

test("S19-33 accepted report stays outcome-unknown after consent or lease changes", async (t) => {
  const reportId = "34343434-3434-4434-8434-343434343434";
  const receipt = {
    persistence_state: "PERSISTED",
    user_status: "RECEIVED",
    transport_receipt: {
      marker: "DATABASE_AND_ENCRYPTED_IMAGE_STORE",
      report_id: reportId,
      persistence_marker: "45454545-4545-4454-8454-454545454545",
      payload_sha256: "a".repeat(64),
      payload_bytes: 123
    }
  };
  const form = (): FormData => {
    const value = new FormData();
    value.set("metadata", '{"source":"android","auto_reported":false}');
    value.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
    return value;
  };
  let caseIndex = 0;
  for (const [name, afterAccepted] of [
    ["consent withdrawn", "withdraw"],
    ["consent store unreadable", "store_error"],
    ["account lease aborted", "lease_abort"],
    ["postcommit 503 and consent withdrawn", "postcommit_failure"]
  ] as const) {
    await t.test(name, async () => {
      resetImageUploadAdmissionForTests();
      const priorOperations = activePrivacyOperationCountForTests();
      caseIndex += 1;
      const cookie = await login();
      const installationId = `s19_33_report_installation_${caseIndex}`;
      const consentHeaders = await reportConsentHeaders(
        cookie, installationId, `s19_33_report_consent_initial_${caseIndex}`
      );
      const stateRecordId = `${createHash("sha256")
        .update(`integrated-consent\0${installationId}`).digest("hex")}.json`;
      const statePath = path.join(stateDirectory, "integrated-consent", stateRecordId);
      let priorState: Buffer | null = null;
      let reportPosts = 0;
      let statusGets = 0;
      const fetchImpl: GatewayFetch = async (input) => {
        if (String(input).endsWith(`/reports/v2/${reportId}/status`)) {
          statusGets += 1;
          return Response.json(receipt);
        }
        assert.equal(String(input), "http://127.0.0.1:8000/reports/v2");
        reportPosts += 1;
        if (afterAccepted === "withdraw" || afterAccepted === "postcommit_failure") {
          const withdrawn = await handleGatewayRequest(
            new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
              method: "PUT",
              headers: {
                cookie, "content-type": "application/json",
                [CONSENT_CONTROL_SECRET_HEADER]: consentHeaders[CONSENT_CONTROL_SECRET_HEADER]!
              },
              body: JSON.stringify({
                schema_version: "walksafe.integrated-consent-request.v1",
                installation_id: installationId,
                request_id: `s19_33_report_consent_withdraw_${caseIndex}`,
                policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
                item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
                client_revision: 2,
                expected_previous_backend_receipt_sha256: consentHeaders[CONSENT_RECEIPT_HEADER],
                selections: {
                  raw_source_collection: false, automatic_reporting: false,
                  mobile_network_transfer: false, training_reuse: false
                }
              })
            }),
            { fetchImpl: async (_url, init) => {
              const event = JSON.parse(String(init?.body)) as { request_id: string; client_revision: number };
              return Response.json({
                schema_version: "walksafe.privacy-consent-receipt.v2",
                request_id: event.request_id,
                client_revision: event.client_revision,
                receipt_sha256: createHash("sha256").update(String(init?.body)).digest("hex"),
                recorded_at: "2026-08-09T12:06:00Z"
              }, { status: 201 });
            } }
          );
          assert.equal(withdrawn.status, 201);
        } else if (afterAccepted === "store_error") {
          priorState = await readFile(statePath);
          await writeFile(statePath, "invalid consent state");
        } else {
          const generation = currentActorGeneration(ACTOR_ID);
          assert.notEqual(generation, null);
          abortPrivacyOperationsForAccountDeletion(ACTOR_ID, generation!);
        }
        return afterAccepted === "postcommit_failure"
          ? Response.json({ detail: { code: "report_postcommit_response_failed" } }, { status: 503 })
          : Response.json({ id: reportId, private: "must-not-leak" }, { status: 201 });
      };
      try {
        const uploaded = await handleGatewayRequest(
          new Request("http://127.0.0.1:8081/api/reports/v2", {
            method: "POST",
            headers: {
              cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders,
              [REPORT_ID_HEADER]: reportId,
              [REPORT_PAYLOAD_SHA256_HEADER]: "a".repeat(64),
              [REPORT_PAYLOAD_BYTES_HEADER]: "123"
            },
            body: form()
          }),
          { fetchImpl }
        );
        const body = await uploaded.text();
        assert.equal(uploaded.status, 409);
        assert.deepEqual(
          JSON.parse(body),
          { detail: {
            code: "report_transport_outcome_unknown",
            message: "The report result is unknown; verify its status before retrying.",
            retry: "status_query_before_retry"
          } }
        );
        assert.doesNotMatch(body, /must-not-leak|private/);
        assert.equal(reportPosts, 1);
      } finally {
        if (priorState !== null) await writeFile(statePath, priorState);
      }
      const status = await handleGatewayRequest(new Request(
        `http://127.0.0.1:8081/api/reports/v2/${reportId}/status`, { headers: { cookie } }
      ), { fetchImpl });
      assert.equal(status.status, 200);
      assert.deepEqual(await status.json(), receipt);
      assert.equal(statusGets, 1);
      assert.equal(reportPosts, 1, "status recovery must not replay the POST");
      if (afterAccepted === "lease_abort") {
        // The deletion abort also settles operations left open by earlier test responses.
        assert.ok(activePrivacyOperationCountForTests() <= priorOperations);
      } else {
        assert.equal(activePrivacyOperationCountForTests(), priorOperations);
      }
    });
  }
  for (const [status, code] of [
    [400, "report_metadata_invalid"],
    [409, "privacy_consent_reconsent_required"]
  ] as const) await t.test(`Backend precommit ${status} remains an explicit denial`, async () => {
    resetImageUploadAdmissionForTests();
    const priorOperations = activePrivacyOperationCountForTests();
    const cookie = await login();
    const consentHeaders = await reportConsentHeaders(
      cookie, "s19_33_precommit_denial_installation", "s19_33_precommit_denial_consent_0001"
    );
    let reportPosts = 0;
    const denied = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/reports/v2", {
        method: "POST",
        headers: {
          cookie, "cf-connecting-ip": nextClientIp(), ...consentHeaders,
          [REPORT_ID_HEADER]: reportId,
          [REPORT_PAYLOAD_SHA256_HEADER]: "a".repeat(64),
          [REPORT_PAYLOAD_BYTES_HEADER]: "123"
        },
        body: form()
      }),
      { fetchImpl: async () => {
        reportPosts += 1;
        return Response.json({ detail: { code } }, { status });
      } }
    );
    assert.equal(denied.status, status);
    assert.deepEqual(await denied.json(), { detail: { code } });
    assert.equal(reportPosts, 1);
    assert.equal(activePrivacyOperationCountForTests(), priorOperations);
  });
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
    assertBackendActorAssertionV4(input, init, {
      actorId: ACTOR_ID, accountGeneration: 1, authEpoch: null
    });
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
  const unicodeRequestId = "56555555-5555-4555-8555-555555555555";
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
    schema_version: "walksafe.report-deletion-status.v2",
    request_id: deletionRequestId,
    report_id: reportId,
    state: "DELETED",
    request_status_version: 2,
    external_copy_count: 2,
    external_copies: [
      {
        institution: "서울시",
        state: "NOT_REQUESTED",
        status_recorded_at: null
      },
      {
        institution: "서울교통공사",
        state: "REQUEST_SENT",
        status_recorded_at: createdAt
      }
    ],
    updated_at: createdAt
  };
  const requestStatus = {
    request_id: requestId,
    request_type: "CORRECTION",
    status: "ACKNOWLEDGED",
    status_version: 2,
    public_response: "요청을 확인했습니다.",
    created_at: createdAt,
    updated_at: createdAt
  };
  const baseItem = {
    report_id: reportId,
    created_at: createdAt,
    user_status: "RECEIVED",
    public_rejection_reason: null,
    latest_request: null
  };
  const withSessionCurrent = (relay: GatewayFetch): GatewayFetch => {
    let sessionChecked = false;
    return async (input, init) => {
      if (String(input) === "http://127.0.0.1:8000/accounts/session-current") {
        assert.equal(init?.method, "POST");
        sessionChecked = true;
        return new Response(null, { status: 204 });
      }
      assert.equal(sessionChecked, true, "report relay must follow session check");
      sessionChecked = false;
      return relay(input, init);
    };
  };
  const calls: string[] = [];
  const fetchImpl: GatewayFetch = withSessionCurrent(async (input, init) => {
    const url = String(input);
    calls.push(url);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR_ID);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
    assertBackendActorAssertionV4(input, init, {
      actorId: ACTOR_ID, accountGeneration: 1, authEpoch: 1
    });
    assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
    assert.equal(headers.get("cookie"), null);
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
    if (url.endsWith(`/requests/${requestId}`)) {
      assert.equal(
        url,
        `http://127.0.0.1:8000/reports/mine/${reportId}/requests/${requestId}`
      );
      assert.equal(init?.method, "GET");
      assert.equal(init?.body, undefined);
      return Response.json(requestStatus);
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
  });

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

  const unicodeRequestText = "😀".repeat(500);
  const unicodeCreated = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/requests`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({
        client_request_id: unicodeRequestId,
        request_type: "DELETE",
        request_text: unicodeRequestText
      })
    }),
    {
      fetchImpl: withSessionCurrent(async (_input, init) => {
        assert.equal(
          (JSON.parse(String(init?.body)) as { request_text: string }).request_text,
          unicodeRequestText
        );
        return Response.json({
          request_id: unicodeRequestId,
          request_type: "DELETE",
          status: "RECEIVED",
          status_version: 1,
          public_response: unicodeRequestText,
          created_at: createdAt,
          updated_at: createdAt
        }, { status: 201 });
      })
    }
  );
  assert.equal(unicodeCreated.status, 201);
  assert.equal(
    (await unicodeCreated.json() as { public_response: string }).public_response,
    unicodeRequestText
  );

  const retrievedRequest = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}`,
      { headers: { cookie } }
    ),
    { fetchImpl }
  );
  assert.equal(retrievedRequest.status, 200);
  assert.equal(retrievedRequest.headers.get("cache-control"), "no-store");
  assert.deepEqual(await retrievedRequest.json(), requestStatus);

  const overlongUnicodeResponse = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}`,
      { headers: { cookie } }
    ),
    {
      fetchImpl: withSessionCurrent(async () => Response.json({
        ...requestStatus,
        public_response: "😀".repeat(501)
      }))
    }
  );
  assert.equal(overlongUnicodeResponse.status, 502);

  for (const { request: limitedRequest, retryAfter, expectedRetryAfter } of [
    {
      request: new Request(
        `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}`,
        { headers: { cookie } }
      ),
      retryAfter: "17",
      expectedRetryAfter: "17"
    },
    {
      request: new Request(
        `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}`,
        { headers: { cookie } }
      ),
      retryAfter: "17 seconds",
      expectedRetryAfter: null
    },
    {
      request: new Request(
        `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests`,
        {
          method: "POST",
          headers: { cookie, "content-type": "application/json" },
          body: JSON.stringify({
            client_request_id: requestId,
            request_type: "CORRECTION",
            request_text: "표면 손상 범위를 정정해 주세요."
          })
        }
      ),
      retryAfter: "8",
      expectedRetryAfter: "8"
    }
  ] as const) {
    const limited = await handleGatewayRequest(limitedRequest, {
      fetchImpl: withSessionCurrent(async () => Response.json(
        {
          detail: {
            code: "actor_rate_limit_exceeded",
            message: "Backend-only rate limit detail"
          },
          internal_note: "must-not-leak"
        },
        { status: 429, headers: { "retry-after": retryAfter } }
      ))
    });
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get("cache-control"), "no-store");
    assert.equal(limited.headers.get("retry-after"), expectedRetryAfter);
    assert.deepEqual(await limited.json(), {
      detail: { code: "report_rate_limited" }
    });
  }

  const crossBoundRequest = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}`,
      { headers: { cookie } }
    ),
    {
      fetchImpl: withSessionCurrent(async () => Response.json({
        ...requestStatus,
        request_id: correctionId
      }))
    }
  );
  assert.equal(crossBoundRequest.status, 502);

  for (const [invalid, expectedStatus] of [
    [new Request(
      `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}?debug=1`,
      { headers: { cookie } }
    ), 404],
    [new Request(
      `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}`,
      { method: "POST", headers: { cookie } }
    ), 405],
    [new Request(
      "http://127.0.0.1:8081/api/reports/mine/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA/requests/BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB",
      { headers: { cookie } }
    ), 404]
  ] as const) {
    const rejected = await handleGatewayRequest(invalid, {
      fetchImpl: async () => assert.fail("invalid request lookup must not reach backend")
    });
    assert.equal(rejected.status, expectedStatus);
    assert.equal(rejected.headers.get("cache-control"), "no-store");
  }

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
    { fetchImpl: withSessionCurrent(async () => assert.fail("invalid request must not reach backend")) }
  );
  assert.equal(invalidBody.status, 422);

  const overlongUnicodeBody = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}/requests`, {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({
        client_request_id: requestId,
        request_type: "DELETE",
        request_text: "😀".repeat(501)
      })
    }),
    { fetchImpl: withSessionCurrent(async () => assert.fail("overlong request must not reach backend")) }
  );
  assert.equal(overlongUnicodeBody.status, 422);

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
      { fetchImpl: withSessionCurrent(async () => assert.fail("invalid correction must not reach backend")) }
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
    { fetchImpl: withSessionCurrent(async () => assert.fail("oversized request must not reach backend")) }
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
    },
    {
      url: `http://127.0.0.1:8081/api/reports/mine/${reportId}/requests/${requestId}`,
      init: { headers: { cookie } },
      payload: {
        ...requestStatus,
        request_text: "내부 요청 원문",
        internal_note: "내부 메모",
        intent_sha256: "f".repeat(64)
      },
      upstreamStatus: 200
    }
  ]) {
    const leaked = await handleGatewayRequest(
      new Request(leakCase.url, leakCase.init),
      {
        fetchImpl: withSessionCurrent(async () => Response.json(
          leakCase.payload,
          { status: leakCase.upstreamStatus }
        ))
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
      fetchImpl: withSessionCurrent(async () => Response.json({
        schema_version: "walksafe.user-report-detail.v1",
        ...baseItem,
        latitude: 37.5,
        internal_reason: "private"
      }))
    }
  );
  assert.equal(leakedDetail.status, 502);
  assert.doesNotMatch(await leakedDetail.text(), /latitude|internal_reason|private/);

  const crossBoundDetail = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}`, {
      headers: { cookie }
    }),
    {
      fetchImpl: withSessionCurrent(async () => Response.json({
        schema_version: "walksafe.user-report-detail.v1",
        ...baseItem,
        report_id: correctionId
      }))
    }
  );
  assert.equal(crossBoundDetail.status, 502);

  const fenced = await handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/reports/mine/${reportId}`, {
      headers: { cookie }
    }),
    {
      fetchImpl: withSessionCurrent(async () => {
        const generation = currentActorGeneration(ACTOR_ID);
        assert.notEqual(generation, null);
        abortPrivacyOperationsForAccountDeletion(ACTOR_ID, generation!);
        return Response.json({ private_actor: "must-not-leak" });
      })
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

test("report request history restores active and deleted requests through strict actor relay", async () => {
  const cookie = backendAccountSessionCookie("android-report-request-history-device");
  const reportId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const deletedReportId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const correctionRequestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  const deletionRequestId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const tombstoneRequestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
  const recordedAt = "2026-09-01T01:02:03.456Z";
  const activeCorrection = {
    revision: 11,
    source: "ACTIVE_REQUEST",
    report_id: reportId,
    request_id: correctionRequestId,
    request: {
      request_id: correctionRequestId,
      request_type: "CORRECTION",
      status: "ACKNOWLEDGED",
      status_version: 2,
      public_response: "요청을 확인했습니다.",
      created_at: recordedAt,
      updated_at: recordedAt
    },
    deletion_status: null
  };
  const activeDeletion = {
    revision: 9,
    source: "ACTIVE_REQUEST",
    report_id: reportId,
    request_id: deletionRequestId,
    request: {
      request_id: deletionRequestId,
      request_type: "DELETE",
      status: "ACKNOWLEDGED",
      status_version: 3,
      public_response: null,
      created_at: recordedAt,
      updated_at: recordedAt
    },
    deletion_status: {
      schema_version: "walksafe.report-deletion-status.v2",
      request_id: deletionRequestId,
      report_id: reportId,
      state: "PENDING",
      request_status_version: 3,
      external_copy_count: 0,
      external_copies: [],
      updated_at: recordedAt
    }
  };
  const deletionTombstone = {
    revision: 7,
    source: "DELETION_TOMBSTONE",
    report_id: deletedReportId,
    request_id: tombstoneRequestId,
    request: null,
    deletion_status: {
      schema_version: "walksafe.report-deletion-status.v2",
      request_id: tombstoneRequestId,
      report_id: deletedReportId,
      state: "DELETED",
      request_status_version: 4,
      external_copy_count: 1,
      external_copies: [{
        institution: "서울시",
        state: "REPLY_DELETION_CONFIRMED",
        status_recorded_at: recordedAt
      }],
      updated_at: recordedAt
    }
  };
  const pages = new Map<string, Record<string, unknown>>([
    ["?limit=2", {
      schema_version: "walksafe.user-report-request-history-page.v1",
      report_id: null,
      snapshot_revision: 11,
      total_count: 3,
      items: [activeCorrection, activeDeletion],
      next_cursor: "cursor_1"
    }],
    ["?limit=2&cursor=cursor_1", {
      schema_version: "walksafe.user-report-request-history-page.v1",
      report_id: null,
      snapshot_revision: 11,
      total_count: 3,
      items: [deletionTombstone],
      next_cursor: null
    }],
    [`?report_id=${reportId}&limit=2`, {
      schema_version: "walksafe.user-report-request-history-page.v1",
      report_id: reportId,
      snapshot_revision: 11,
      total_count: 2,
      items: [activeCorrection, activeDeletion],
      next_cursor: null
    }]
  ]);
  const calls: string[] = [];
  let sessionChecks = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    const url = new URL(String(input));
    if (url.toString() === "http://127.0.0.1:8000/accounts/session-current") {
      assert.equal(init?.method, "POST");
      sessionChecks += 1;
      return new Response(null, { status: 204 });
    }
    calls.push(url.toString());
    assert.equal(url.pathname, "/reports/mine/requests/history");
    assert.equal(init?.method, "GET");
    assert.equal(init?.body, undefined);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR_ID);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
    assertBackendActorAssertionV4(input, init, {
      actorId: ACTOR_ID, accountGeneration: 1, authEpoch: 1
    });
    assert.equal(headers.get("x-walksafe-admin-token"), null);
    const page = pages.get(url.search);
    assert.ok(page);
    return new Response(JSON.stringify(page), {
      headers: { "content-type": "application/json; charset=utf-8" }
    });
  };

  for (const query of pages.keys()) {
    const response = await handleGatewayRequest(
      new Request(`http://127.0.0.1:8081/api/reports/mine/requests/history${query}`, {
        headers: {
          cookie,
          [ACTOR_ID_HEADER]: "spoofed-actor",
          [ACCOUNT_GENERATION_HEADER]: "999",
          [FIELD_TEST_TOKEN_HEADER]: "spoofed-service-token",
          "x-walksafe-admin-token": "must-not-forward"
        }
      }),
      { fetchImpl }
    );
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.deepEqual(await response.json(), pages.get(query));
  }
  assert.deepEqual(calls, [
    "http://127.0.0.1:8000/reports/mine/requests/history?limit=2",
    "http://127.0.0.1:8000/reports/mine/requests/history?limit=2&cursor=cursor_1",
    `http://127.0.0.1:8000/reports/mine/requests/history?report_id=${reportId}&limit=2`
  ]);
  assert.equal(sessionChecks, pages.size);
});

test("report request history rejects invalid query, upstream status, shape, and byte budget", async () => {
  const cookie = backendAccountSessionCookie("android-report-request-history-strict-device");
  const reportId = "11111111-1111-4111-8111-111111111111";
  const requestId = "22222222-2222-4222-8222-222222222222";
  const secondRequestId = "33333333-3333-4333-8333-333333333333";
  const recordedAt = "2026-09-01T02:03:04Z";
  const item = {
    revision: 5,
    source: "ACTIVE_REQUEST",
    report_id: reportId,
    request_id: requestId,
    request: {
      request_id: requestId,
      request_type: "CORRECTION",
      status: "RECEIVED",
      status_version: 1,
      public_response: null,
      created_at: recordedAt,
      updated_at: recordedAt
    },
    deletion_status: null
  };
  const secondItem = {
    ...item,
    revision: 4,
    request_id: secondRequestId,
    request: { ...item.request, request_id: secondRequestId }
  };
  const valid = {
    schema_version: "walksafe.user-report-request-history-page.v1",
    report_id: null,
    snapshot_revision: 5,
    total_count: 1,
    items: [item],
    next_cursor: null
  };
  const withSessionCurrent = (relay: GatewayFetch): GatewayFetch => {
    let sessionChecked = false;
    return async (input, init) => {
      if (String(input) === "http://127.0.0.1:8000/accounts/session-current") {
        assert.equal(init?.method, "POST");
        sessionChecked = true;
        return new Response(null, { status: 204 });
      }
      assert.equal(sessionChecked, true, "report relay must follow session check");
      return relay(input, init);
    };
  };
  const tombstone = {
    revision: 3,
    source: "DELETION_TOMBSTONE",
    report_id: reportId,
    request_id: secondRequestId,
    request: null,
    deletion_status: {
      schema_version: "walksafe.report-deletion-status.v2",
      request_id: secondRequestId,
      report_id: reportId,
      state: "DELETED",
      request_status_version: 2,
      external_copy_count: 0,
      external_copies: [],
      updated_at: recordedAt
    }
  };
  const invalidPayloads: unknown[] = [
    { ...valid, private_actor: ACTOR_ID },
    { ...valid, snapshot_revision: 0 },
    { ...valid, snapshot_revision: 6 },
    { ...valid, total_count: 0 },
    { ...valid, snapshot_revision: Number.MAX_SAFE_INTEGER + 1 },
    { ...valid, items: [{ ...item, revision: 6 }] },
    { ...valid, total_count: 2, next_cursor: null },
    { ...valid, next_cursor: "unexpected_cursor" },
    { ...valid, total_count: 2, items: [], next_cursor: null },
    { ...valid, next_cursor: "bad+cursor" },
    { ...valid, items: [{ ...item, private_actor: ACTOR_ID }] },
    { ...valid, items: [{ ...item, deletion_status: "not-null" }] },
    { ...valid, items: [{ ...item, request: { ...item.request, created_at: "2026-09-01" } }] },
    { ...valid, items: [{ ...item, request: null }] },
    { ...valid, items: [{ ...tombstone, request: "not-null" }] },
    { ...valid, items: [{ ...tombstone, deletion_status: null }] },
    { ...valid, total_count: 2, items: [secondItem, item], next_cursor: null },
    {
      ...valid,
      total_count: 2,
      items: [
        item,
        {
          ...secondItem,
          request_id: requestId,
          request: { ...secondItem.request, request_id: requestId }
        }
      ],
      next_cursor: null
    }
  ];
  for (const payload of invalidPayloads) {
    const rejected = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/reports/mine/requests/history", {
        headers: { cookie }
      }),
      { fetchImpl: withSessionCurrent(async () => Response.json(payload)) }
    );
    assert.equal(rejected.status, 502);
    assert.equal(rejected.headers.get("cache-control"), "no-store");
    assert.doesNotMatch(await rejected.text(), /private_actor|field-operator/);
  }

  const filteredMismatch = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/api/reports/mine/requests/history?report_id=${reportId}`,
      { headers: { cookie } }
    ),
    { fetchImpl: withSessionCurrent(async () => Response.json(valid)) }
  );
  assert.equal(filteredMismatch.status, 502);
  const limitOverflow = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/api/reports/mine/requests/history?limit=1",
      { headers: { cookie } }
    ),
    {
      fetchImpl: withSessionCurrent(async () => Response.json({
        ...valid,
        total_count: 2,
        items: [item, secondItem]
      }))
    }
  );
  assert.equal(limitOverflow.status, 502);
  const impossibleCursorSnapshot = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/api/reports/mine/requests/history?cursor=opaque_cursor",
      { headers: { cookie } }
    ),
    {
      fetchImpl: withSessionCurrent(async () => Response.json({
        schema_version: "walksafe.user-report-request-history-page.v1",
        report_id: null,
        snapshot_revision: 0,
        total_count: 0,
        items: [],
        next_cursor: null
      }))
    }
  );
  assert.equal(impossibleCursorSnapshot.status, 502);
  const impossibleCursorFullSnapshot = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/api/reports/mine/requests/history?cursor=opaque_cursor",
      { headers: { cookie } }
    ),
    { fetchImpl: withSessionCurrent(async () => Response.json(valid)) }
  );
  assert.equal(impossibleCursorFullSnapshot.status, 502);
  for (const payload of [
    {
      ...valid,
      total_count: 2
    },
    {
      ...valid,
      total_count: 2,
      items: [secondItem],
      next_cursor: "next_cursor"
    },
    {
      ...valid,
      total_count: 3,
      items: [secondItem],
      next_cursor: "opaque_cursor"
    }
  ]) {
    const rejected = await handleGatewayRequest(
      new Request(
        "http://127.0.0.1:8081/api/reports/mine/requests/history?cursor=opaque_cursor",
        { headers: { cookie } }
      ),
      { fetchImpl: withSessionCurrent(async () => Response.json(payload)) }
    );
    assert.equal(rejected.status, 502);
  }

  for (const response of [
    Response.json(valid, { headers: { "content-length": String(48 * 1024 + 1) } }),
    new Response(`${" ".repeat(48 * 1024)}${JSON.stringify(valid)}`, {
      headers: { "content-type": "application/json" }
    }),
    new Response(JSON.stringify(valid), { headers: { "content-type": "text/plain" } }),
    new Response(JSON.stringify(valid), { headers: { "content-type": "application/jsonp" } }),
    new Response("{", { headers: { "content-type": "application/json" } })
  ]) {
    const rejected = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/reports/mine/requests/history", {
        headers: { cookie }
      }),
      { fetchImpl: withSessionCurrent(async () => response) }
    );
    assert.equal(rejected.status, 502);
    assert.equal(rejected.headers.get("cache-control"), "no-store");
  }

  for (const invalidQuery of [
    "?limit=0",
    "?limit=26",
    "?limit=01",
    "?limit=10&limit=11",
    "?cursor=",
    "?cursor=bad%2Bcursor",
    "?report_id=AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
    "?report_id=11111111-1111-4111-8111-111111111111&report_id=11111111-1111-4111-8111-111111111111",
    "?debug=1"
  ]) {
    const rejected = await handleGatewayRequest(
      new Request(`http://127.0.0.1:8081/api/reports/mine/requests/history${invalidQuery}`, {
        headers: { cookie }
      }),
      { fetchImpl: async () => assert.fail("invalid query must not reach backend") }
    );
    assert.equal(rejected.status, 422);
    assert.deepEqual(await rejected.json(), {
      detail: { code: "report_request_history_query_invalid" }
    });
  }

  for (const [upstreamStatus, expectedStatus] of [
    [422, 422],
    [404, 404],
    [429, 429],
    [503, 502]
  ] as const) {
    const rejected = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/reports/mine/requests/history", {
        headers: { cookie }
      }),
      {
        fetchImpl: withSessionCurrent(async () => Response.json(
          { detail: { code: "private_backend_code", actor: ACTOR_ID } },
          {
            status: upstreamStatus,
            ...(upstreamStatus === 429 ? { headers: { "retry-after": "17" } } : {})
          }
        ))
      }
    );
    assert.equal(rejected.status, expectedStatus);
    assert.equal(rejected.headers.get("cache-control"), "no-store");
    assert.doesNotMatch(await rejected.text(), /private_backend_code|field-operator/);
    if (upstreamStatus === 429) assert.equal(rejected.headers.get("retry-after"), "17");
  }

  const wrongMethod = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/mine/requests/history", {
      method: "POST",
      headers: { cookie }
    }),
    { fetchImpl: async () => assert.fail("wrong method must not reach backend") }
  );
  assert.equal(wrongMethod.status, 405);
  const networkFailure = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/mine/requests/history", {
      headers: { cookie }
    }),
    { fetchImpl: withSessionCurrent(async () => { throw new Error("private network detail"); }) }
  );
  assert.equal(networkFailure.status, 502);
  assert.doesNotMatch(await networkFailure.text(), /private network detail/);
  const unauthenticated = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/mine/requests/history"),
    { fetchImpl: async () => assert.fail("unauthenticated request must not reach backend") }
  );
  assert.equal(unauthenticated.status, 404);
});

test("report deletion status accepts only the exact v2 external-copy projection", async () => {
  const cookie = backendAccountSessionCookie("android-report-deletion-device");
  const reportId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const requestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const recordedAt = "2026-08-30T12:34:56.123Z";
  const url = `http://127.0.0.1:8081/api/reports/mine/deletions/${requestId}`;
  const valid = {
    schema_version: "walksafe.report-deletion-status.v2",
    request_id: requestId,
    report_id: reportId,
    state: "DELETED",
    request_status_version: 3,
    external_copy_count: 1,
    external_copies: [{
      institution: "서울시",
      state: "REPLY_ACKNOWLEDGED",
      status_recorded_at: recordedAt
    }],
    updated_at: recordedAt
  };
  const withSessionCurrent = (relay: GatewayFetch): GatewayFetch => {
    let sessionChecked = false;
    return async (input, init) => {
      if (String(input) === "http://127.0.0.1:8000/accounts/session-current") {
        assert.equal(init?.method, "POST");
        sessionChecked = true;
        return new Response(null, { status: 204 });
      }
      assert.equal(sessionChecked, true, "report relay must follow session check");
      return relay(input, init);
    };
  };

  for (const payload of [
    {
      schema_version: "walksafe.report-deletion-status.v1",
      request_id: requestId,
      report_id: reportId,
      state: "DELETED",
      request_status_version: 3,
      external_copy_count: 1,
      updated_at: recordedAt
    },
    { ...valid, external_copy_count: 2 },
    { ...valid, state: "PENDING" },
    {
      ...valid,
      external_copies: [{ ...valid.external_copies[0], state: "UNKNOWN" }]
    },
    {
      ...valid,
      external_copies: [{ ...valid.external_copies[0], status_recorded_at: "2026-08-30" }]
    },
    {
      ...valid,
      external_copies: [{ ...valid.external_copies[0], institution: " 서울시" }]
    },
    {
      ...valid,
      external_copies: [{ ...valid.external_copies[0], private_actor: ACTOR_ID }]
    },
    { ...valid, updated_at: "2026-08-30T12:34:56" }
  ]) {
    const rejected = await handleGatewayRequest(
      new Request(url, { headers: { cookie } }),
      { fetchImpl: withSessionCurrent(async () => Response.json(payload)) }
    );
    assert.equal(rejected.status, 502);
    assert.equal(rejected.headers.get("cache-control"), "no-store");
    assert.doesNotMatch(await rejected.text(), /private_actor|field-operator/);
  }

  const oversized = await handleGatewayRequest(
    new Request(url, { headers: { cookie } }),
    {
      fetchImpl: withSessionCurrent(async () => Response.json(valid, {
        headers: { "content-length": String(128 * 1024 + 1) }
      }))
    }
  );
  assert.equal(oversized.status, 502);
  assert.equal(oversized.headers.get("cache-control"), "no-store");

  for (const [request, expectedStatus] of [
    [new Request(`${url}?debug=1`, { headers: { cookie } }), 404],
    [new Request(url, { method: "POST", headers: { cookie } }), 405],
    [new Request(url), 404]
  ] as const) {
    const rejected = await handleGatewayRequest(request, {
      fetchImpl: async () => assert.fail("invalid deletion lookup must not reach backend")
    });
    assert.equal(rejected.status, expectedStatus);
    assert.equal(rejected.headers.get("cache-control"), "no-store");
  }
});

test("report authentication and busy admission reject before opening the body", async () => {
  resetImageUploadAdmissionForTests();
  const observableRequest = (
    cookie: string,
    clientIp: string,
    extraHeaders: Record<string, string> = {}
  ): { request: Request; opened: () => boolean } => {
    const request = new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: {
        cookie,
        "cf-connecting-ip": clientIp,
        ...extraHeaders,
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
    // S19-32: the slot is taken after the first consent check, so a busy answer needs a
    // current consent; it still comes before the body is opened.
    const busyRequest = observableRequest(cookie, nextClientIp(), {
      ...await reportConsentHeaders(cookie), ...REPORT_TRANSPORT_HEADERS
    });
    const busy = await handleGatewayRequest(busyRequest.request);
    assert.equal(busy.status, 503);
    assert.equal((await busy.json() as { detail: { code: string } }).detail.code, "image_upload_busy");
    assert.equal(busyRequest.opened(), false);
  } finally {
    held.release();
  }
});

test("S19-32 a report waiting on its installation's consent lock does not hold the upload slot", async () => {
  resetImageUploadAdmissionForTests();
  const cookie = await login();
  const slowInstallation = "2f3c1a40-6f0e-4d2b-9c61-7d1f5b8a9e01";
  const slowHeaders = await reportConsentHeaders(cookie, slowInstallation, "s19_32_slow_consent_0001");
  const otherHeaders = await reportConsentHeaders(
    cookie, "7a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "s19_32_other_consent_0001"
  );
  // A consent PUT for the slow installation waits on the Backend while it holds that
  // installation's admission, like a slow consent save.
  let releaseBackend!: () => void;
  const backendGate = new Promise<void>((resolve) => { releaseBackend = resolve; });
  let backendReached!: () => void;
  const reached = new Promise<void>((resolve) => { backendReached = resolve; });
  const pendingPut = handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
      method: "PUT",
      headers: {
        cookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: slowHeaders[CONSENT_CONTROL_SECRET_HEADER]!
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: slowInstallation,
        request_id: "s19_32_slow_consent_0002",
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 2,
        expected_previous_backend_receipt_sha256: slowHeaders[CONSENT_RECEIPT_HEADER],
        selections: {
          raw_source_collection: false, automatic_reporting: true,
          mobile_network_transfer: false, training_reuse: false
        }
      })
    }),
    {
      fetchImpl: async (_input, init) => {
        backendReached();
        await backendGate;
        const event = JSON.parse(String(init?.body)) as { request_id: string; client_revision: number };
        return Response.json({
          schema_version: "walksafe.privacy-consent-receipt.v2",
          request_id: event.request_id,
          client_revision: event.client_revision,
          receipt_sha256: createHash("sha256").update(String(init?.body)).digest("hex"),
          recorded_at: "2026-08-09T12:06:00Z"
        }, { status: 201 });
      }
    }
  );
  await reached;
  const reportForm = (): FormData => {
    const form = new FormData();
    form.set("metadata", "{\"source\":\"android\",\"auto_reported\":false}");
    form.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
    return form;
  };
  let reportCalls = 0;
  const reportBackend: GatewayFetch = async () => {
    reportCalls += 1;
    return Response.json({ id: "report-id" }, { status: 201 });
  };
  // This report waits (up to the 1 s admission deadline) for the slow installation.
  const waiting = handleGatewayRequest(new Request("http://127.0.0.1:8081/api/reports/v2", {
    method: "POST",
    headers: { cookie, "cf-connecting-ip": nextClientIp(), ...slowHeaders, ...REPORT_TRANSPORT_HEADERS },
    body: reportForm()
  }), { fetchImpl: reportBackend });
  await new Promise((resolve) => setTimeout(resolve, 50));
  // Another installation's report is admitted meanwhile instead of 503 image_upload_busy.
  const other = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/reports/v2", {
    method: "POST",
    headers: { cookie, "cf-connecting-ip": nextClientIp(), ...otherHeaders, ...REPORT_TRANSPORT_HEADERS },
    body: reportForm()
  }), { fetchImpl: reportBackend });
  assert.equal(other.status, 201, JSON.stringify(await other.clone().json()));
  assert.equal(reportCalls, 1);
  releaseBackend();
  assert.equal((await pendingPut).status, 201);
  const waited = await waiting;
  await waited.body?.cancel();
  // The one global slot (S19-49) was free again for every report.
  const held = acquireImageUploadAdmission(
    new Request("http://127.0.0.1:8081/api/reports/v2", { headers: { "cf-connecting-ip": nextClientIp() } }),
    new Headers({ [ACTOR_ID_HEADER]: ACTOR_ID })
  );
  assert.ok(held.release);
  held.release();
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
