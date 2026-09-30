import assert from "node:assert/strict";
import { mkdir, mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { request as httpRequest } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";
import ts from "typescript";

import * as accountRelay from "../src/account-relay.js";
import { fetchBackend, type GatewayFetch } from "../src/backend.js";
import {
  FIELD_SESSION_ERROR_CONTRACT,
  fieldSessionErrorContractViolation
} from "../src/field-session-errors.js";
import { setFieldLongSessionStorageFaultForTests } from "../src/field-long-session.js";
import { createGatewayServer } from "../src/node-adapter.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

// S01-20: every error the /api/field-session route produces must be a registered code with
// its registered status and retryable / reauthentication_required flags. A new code or a
// changed flag fails here until the table (and the Android mirror via openapi.json) changes.
const ACTOR_ID = "field-contract-operator";
const ACCOUNT_TOKEN = "field-contract-account-token-1234567890123";
const BACKEND_ACTOR = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3020";
let stateDirectory = "";
let keyringPath = "";
let ipSuffix = 100;
const observed = new Map<string, number>();

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-field-session-contract-"));
  keyringPath = `${stateDirectory}-state-keyring.json`;
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "field-contract-internal-token-12345678901",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "field-contract-session-secret-12345678901234567890123",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "60",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "300",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "3600"
  });
  await configureTestStateEncryption(keyringPath);
});

afterEach(async () => {
  setFieldLongSessionStorageFaultForTests(null);
  await rm(stateDirectory, { recursive: true, force: true });
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
  await rm(keyringPath, { force: true });
});

function nextClientIp(): string {
  ipSuffix += 1;
  return `198.51.100.${ipSuffix % 250}`;
}

async function expectError(response: Response, expectedCode: string): Promise<void> {
  assert.ok(response.status >= 400, `expected an error for ${expectedCode}`);
  const body = await response.json() as Record<string, unknown>;
  const detail = body.detail as Record<string, unknown> | undefined;
  assert.equal(detail?.code ?? body.code, expectedCode);
  assert.equal(fieldSessionErrorContractViolation(response.status, body), null);
  observed.set(expectedCode, (observed.get(expectedCode) ?? 0) + 1);
}

function fieldSession(init: RequestInit & { query?: string } = {}): Request {
  const { query = "", ...rest } = init;
  return new Request(`https://gateway.invalid/api/field-session${query}`, rest);
}

function jsonPost(body: unknown, headers: Record<string, string> = {}): Request {
  return fieldSession({
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": nextClientIp(), ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body)
  });
}

async function longLogin(deviceId: string): Promise<Record<string, unknown>> {
  const response = await handleGatewayRequest(
    jsonPost({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN, device_id: deviceId })
  );
  assert.equal(response.status, 200);
  return await response.json() as Record<string, unknown>;
}

function refreshBody(session: Record<string, unknown>): Record<string, unknown> {
  return {
    grant_type: "refresh_token",
    actor_id: session.actor_id,
    device_id: session.device_id,
    family_id: session.family_id,
    rotation: session.rotation,
    refresh_token: session.refresh_token
  };
}

function passwordLogin(fetchImpl: GatewayFetch, deviceId: string | null = "contract-device"): Promise<Response> {
  return handleGatewayRequest(jsonPost({
    grant_type: "password",
    email: "contract@example.org",
    password: "contract-password-must-not-leak",
    remember_me: false,
    ...(deviceId === null ? {} : { device_id: deviceId })
  }), { fetchImpl });
}

function sessionCookie(response: Response): string {
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0] ?? "";
  assert.match(cookie, /^walksafe_field_session=v\d+\./);
  return cookie;
}

// Backend stub for a current v6 password session (authenticate + session-current).
const backendAccountFetch: GatewayFetch = async (input) => {
  const pathname = new URL(String(input)).pathname;
  if (pathname === "/accounts/authenticate") {
    return Response.json({
      schema_version: "walksafe.account-authentication.v1",
      actor_id: BACKEND_ACTOR,
      account_generation: 1,
      auth_epoch: 1
    });
  }
  if (pathname === "/accounts/session-current") return new Response(null, { status: 204 });
  return Response.json({ detail: { code: "unexpected_contract_path" } }, { status: 500 });
};

function rawHttp(
  port: number,
  method: string,
  pathname: string,
  headers: Record<string, string>,
  body: string
): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest({ host: "127.0.0.1", port, method, path: pathname, headers }, (res) => {
      let text = "";
      res.setEncoding("utf8");
      res.on("data", (chunk: string) => { text += chunk; });
      res.on("end", () => resolve({ status: res.statusCode ?? 0, body: text }));
    });
    outgoing.once("error", reject);
    outgoing.end(body);
  });
}

type DeclarationIndex = Readonly<{
  declarations: ReadonlyMap<string, string>;
  imports: ReadonlyMap<string, readonly [string, string]>;
}>;

const TOP_LEVEL_DECLARATION =
  /^(?:export\s+)?(?:declare\s+)?(?:async\s+)?(?:function\*?|const|let|class|type|interface|enum)\s+([A-Za-z_$][\w$]*)/;

/** Splits every src module into top-level declarations and its relative named imports. */
async function indexGatewaySources(): Promise<ReadonlyMap<string, DeclarationIndex>> {
  const directory = path.join(process.cwd(), "src");
  const index = new Map<string, DeclarationIndex>();
  for (const file of (await readdir(directory)).filter((name) => name.endsWith(".ts")).sort()) {
    const text = await readFile(path.join(directory, file), "utf8");
    const declarations = new Map<string, string[]>();
    let current: string[] | null = null;
    for (const line of text.split("\n")) {
      const match = TOP_LEVEL_DECLARATION.exec(line);
      if (match) {
        current = [];
        declarations.set(match[1]!, current);
      } else if (/^(?:import\b|export\s*\{)/.test(line)) {
        current = null;
      }
      current?.push(line);
    }
    const imports = new Map<string, readonly [string, string]>();
    for (const match of text.matchAll(/import\s*(?:type\s*)?\{([^}]*)\}\s*from\s*"\.\/([\w-]+)\.js"/g)) {
      for (const specifier of match[1]!.split(",").map((part) => part.trim()).filter(Boolean)) {
        if (specifier.startsWith("type ")) continue;
        const [imported, local = imported] = specifier.split(/\s+as\s+/) as [string, string?];
        imports.set(local, [`${match[2]}.ts`, imported]);
      }
    }
    index.set(file, {
      declarations: new Map([...declarations].map(([name, lines]) => [name, lines.join("\n")])),
      imports
    });
  }
  return index;
}

/**
 * Every top-level declaration reachable from the seeds by identifier reference, following
 * same-module names and `./module.js` named imports (functions, consts and classes).
 */
function reachableDeclarations(
  index: ReadonlyMap<string, DeclarationIndex>,
  seeds: ReadonlyArray<readonly [string, string]>
): Map<string, string> {
  const reached = new Map<string, string>();
  const queue = [...seeds];
  while (queue.length > 0) {
    const [file, name] = queue.shift()!;
    const key = `${file}:${name}`;
    if (reached.has(key)) continue;
    const module = index.get(file);
    const body = module?.declarations.get(name);
    if (!module || body === undefined) continue;
    reached.set(key, body);
    for (const match of body.matchAll(/(?<![.\w$])([A-Za-z_$][\w$]*)\b/g)) {
      const identifier = match[1]!;
      if (identifier === name) continue;
      if (module.declarations.has(identifier)) queue.push([file, identifier]);
      else {
        const imported = module.imports.get(identifier);
        if (imported) queue.push(imported);
      }
    }
  }
  return reached;
}

// Public error-code literals; a string-literal union type (`code: "a" | "b"`) is not a response.
const CODE_LITERAL =
  /(?:\bcode:\s*|invalidRefreshResponse\(|accountError\(\d+,\s*|bodyError\(\d+,\s*|invalidRequest\(|adapterError\(\d+,\s*)"([a-z][a-z0-9_]*)"(?!\s*\|\s*")/g;
const CODE_TERNARY = /\bcode:\s*[^,\n]*\?\s*"([a-z][a-z0-9_]*)"\s*:\s*"([a-z][a-z0-9_]*)"/g;

function scanCodes(text: string, found: Map<string, string>, where: string): void {
  for (const match of text.matchAll(CODE_LITERAL)) found.set(match[1]!, where);
  for (const match of text.matchAll(CODE_TERNARY)) {
    found.set(match[1]!, where);
    found.set(match[2]!, where);
  }
}

/** Narrow only the opt-in buffered-response branch, after proving every reached call uses zero. */
function fieldSessionBackendSource(
  index: ReadonlyMap<string, DeclarationIndex>,
  reached: ReadonlyMap<string, string>
): string {
  let calls = 0;
  for (const [where, body] of reached) {
    const file = where.split(":", 1)[0]!;
    const names = new Set([...index.get(file)!.imports]
      .filter(([, [module, name]]) => module === "backend.ts" && name === "fetchBackend")
      .map(([local]) => local));
    if (file === "backend.ts") names.add("fetchBackend");
    const parsed = ts.createSourceFile(file, body, ts.ScriptTarget.Latest, true);
    const visit = (node: ts.Node): void => {
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && names.has(node.expression.text)) {
        calls += 1;
        assert.ok(!node.arguments.some(ts.isSpreadElement), `${where}: fetchBackend arguments must be explicit`);
        assert.ok(node.arguments.length <= 5 ||
          (node.arguments.length === 6 && node.arguments[5]!.getText(parsed) === "0"),
        `${where}: a field-session helper opted into buffered Backend responses; review its error contract`);
      }
      ts.forEachChild(node, visit);
    };
    visit(parsed);
  }
  assert.ok(calls > 0, "the field-session graph must include its Backend fetch calls");
  const text = reached.get("backend.ts:fetchBackend")!;
  const parsed = ts.createSourceFile("backend.ts", text, ts.ScriptTarget.Latest, true);
  const declaration = parsed.statements.find(ts.isFunctionDeclaration)!;
  const limit = declaration.parameters.find((parameter) => parameter.name.getText(parsed) === "maxBufferedBodyBytes");
  assert.equal(limit?.initializer?.getText(parsed), "0");
  const body = declaration.body!;
  const flag = body.statements.filter(ts.isVariableStatement).flatMap((statement) =>
    [...statement.declarationList.declarations]).find((item) => item.name.getText(parsed) === "responseTooLarge");
  assert.equal(flag?.initializer?.kind, ts.SyntaxKind.FalseKeyword);
  const attempt = body.statements.find(ts.isTryStatement)!;
  const guard = attempt.tryBlock.statements.filter(ts.isIfStatement)
    .find((statement) => statement.expression.getText(parsed) === "maxBufferedBodyBytes <= 0")!;
  assert.ok(guard && ts.isReturnStatement(guard.thenStatement));
  assert.equal(guard.thenStatement.expression?.getText(parsed), "await pending");
  let assignments = 0;
  const checkFlag = (node: ts.Node): void => {
    if (ts.isBinaryExpression(node) && node.left.getText(parsed) === "responseTooLarge" &&
      node.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      assignments += 1;
      assert.equal(node.right.kind, ts.SyntaxKind.TrueKeyword);
      assert.ok(node.getStart(parsed) > guard.end && node.end < attempt.tryBlock.end,
        "responseTooLarge can be set only after the unbounded early return");
    }
    ts.forEachChild(node, checkFlag);
  };
  checkFlag(body);
  assert.ok(assignments > 0, "buffered navigation responses retain their size checks");
  const branches = attempt.catchClause!.block.statements.filter(ts.isIfStatement)
    .filter((statement) => statement.expression.getText(parsed) === "responseTooLarge");
  assert.equal(branches.length, 1);
  const branch = branches[0]!;
  assert.equal(branch.elseStatement, undefined);
  const bufferedCodes = new Map<string, string>();
  scanCodes(branch.getText(parsed), bufferedCodes, "buffered Backend response");
  assert.deepEqual([...bufferedCodes.keys()], ["gateway_upstream_response_too_large"]);
  return text.slice(0, branch.getStart(parsed)) + text.slice(branch.end);
}

test("S01-20 the table is the exact openapi FieldSessionError contract", async () => {
  const raw = await readFile(path.join(process.cwd(), "openapi.json"), "utf8");
  const contract = JSON.parse(raw) as {
    components: { schemas: Record<string, {
      properties: { code: { enum: string[] } };
      "x-walksafe-error-contract": Array<Record<string, unknown>>;
    }> };
  };
  const schema = contract.components.schemas.FieldSessionError!;
  assert.deepEqual(schema.properties.code.enum, FIELD_SESSION_ERROR_CONTRACT.map((row) => row.code));
  assert.deepEqual(schema["x-walksafe-error-contract"], FIELD_SESSION_ERROR_CONTRACT.map((row) => ({
    code: row.code,
    statuses: [...row.statuses],
    next_action: row.next,
    retryable: row.retryable ?? null,
    reauthentication_required: row.reauthenticationRequired ?? null
  })));
  const codes = FIELD_SESSION_ERROR_CONTRACT.map((row) => row.code);
  assert.deepEqual([...codes].sort(), codes, "table stays sorted and unique");
  assert.equal(new Set(codes).size, codes.length);
  for (const row of FIELD_SESSION_ERROR_CONTRACT) {
    if (row.retryable === true) assert.equal(row.next, "RETRY_SAFE");
    if (row.reauthenticationRequired === true) assert.notEqual(row.next, "RETRY_SAFE");
  }
});

test("S01-20 /api/field-session operations reference FieldSessionError for every table status", async () => {
  const raw = await readFile(path.join(process.cwd(), "openapi.json"), "utf8");
  const contract = JSON.parse(raw) as {
    paths: Record<string, Record<string, { responses: Record<string, Record<string, unknown>> }>>;
    components: { responses: Record<string, {
      content: { "application/json": { schema: Record<string, unknown> } };
    }> };
  };
  assert.deepEqual(
    contract.components.responses.FieldSessionError?.content["application/json"].schema,
    { $ref: "#/components/schemas/FieldSessionError" }
  );
  const declared = new Set<number>();
  for (const method of ["get", "post", "delete"]) {
    const operation = contract.paths["/api/field-session"]![method]!;
    for (const [status, response] of Object.entries(operation.responses)) {
      if (Number(status) < 400) continue;
      assert.deepEqual(response, { $ref: "#/components/responses/FieldSessionError" },
        `${method} ${status} must use the FieldSessionError contract`);
      declared.add(Number(status));
    }
  }
  // 405 is answered for methods the path does not declare (Allow: GET, POST, DELETE), so no
  // declared operation can carry it; FieldSessionError still lists it as a table row.
  const undeclarable = new Set([405]);
  const missing = [...new Set(FIELD_SESSION_ERROR_CONTRACT.flatMap((row) => row.statuses))]
    .filter((status) => !declared.has(status) && !undeclarable.has(status))
    .sort((left, right) => left - right);
  assert.deepEqual(missing, []);
  const orphan = [...declared].filter((status) =>
    !FIELD_SESSION_ERROR_CONTRACT.some((row) => row.statuses.includes(status)));
  assert.deepEqual(orphan, []);
});

test("S01-20 every error code reachable from the field-session route is registered", async () => {
  const table = new Set(FIELD_SESSION_ERROR_CONTRACT.map((row) => row.code));
  const index = await indexGatewaySources();
  // Seeds: the route handler, the dispatch 405 for undeclared methods and the
  // handleGatewayRequest storage fallback. Every helper they reference (transitively,
  // across ./module.js imports) is scanned, so a code in a new helper cannot bypass the table.
  const reached = reachableDeclarations(index, [
    ["routes.ts", "fieldSession"],
    ["routes.ts", "methodNotAllowed"],
    ["field-long-session.ts", "fieldLongSessionStorageUnavailableResponse"]
  ]);
  for (const helper of [
    "routes.ts:fieldSessionQueryInvalid",
    "routes.ts:privacyOperationInactive",
    "routes.ts:backendAccountSessionGate",
    "account-relay.ts:authenticateBackendAccount",
    "field-long-session.ts:listFieldLongSessionDevices",
    "auth.ts:revokeBackendDeviceGatewaySession",
    "request-body.ts:readBoundedJsonBody"
  ]) assert.ok(reached.has(helper), `call graph reaches ${helper}`);
  const found = new Map<string, string>();
  const unboundedBackend = fieldSessionBackendSource(index, reached);
  for (const [where, body] of reached) {
    scanCodes(where === "backend.ts:fetchBackend" ? unboundedBackend : body, found, where);
  }
  // The Node adapter wraps every route, including /api/field-session.
  scanCodes(await readFile(path.join(process.cwd(), "src", "node-adapter.ts"), "utf8"), found,
    "node-adapter.ts");
  // Whole-module scan of the session modules stays as a superset; these three codes are
  // emitted only by the signup relays of account-relay.ts, never on /api/field-session.
  const signupOnly = new Set([
    "account_create_request_invalid",
    "account_enrollment_request_invalid",
    "account_request_validation_failed"
  ]);
  for (const file of ["auth.ts", "field-long-session.ts", "request-body.ts", "account-relay.ts"]) {
    const text = await readFile(path.join(process.cwd(), "src", file), "utf8");
    const whole = new Map<string, string>();
    scanCodes(text, whole, file);
    for (const [code, where] of whole) if (!signupOnly.has(code) && !found.has(code)) found.set(code, where);
  }
  const unregistered = [...found].filter(([code]) => !table.has(code))
    .map(([code, where]) => `${code} (${where})`);
  assert.deepEqual(unregistered, []);
  assert.ok(found.size >= 40, `scanned ${found.size} codes`);
});

test("S01-20 oversized password responses use the account contract; navigation buffering is opt-in", async () => {
  const oversized = "x".repeat(2 * 1024 * 1024 + 1);
  await expectError(await passwordLogin(async () => new Response(oversized)), "gateway_account_upstream_invalid");
  const upstream = new Response(oversized);
  const request = fieldSession();
  assert.equal(await fetchBackend(request, "http://127.0.0.1:8000/accounts/authenticate", {},
    15_000, async () => upstream), upstream, "the default call returns the unbuffered response");
  const bounded = await fetchBackend(request, "http://127.0.0.1:8000/navigation/walking", {},
    15_000, async () => new Response(oversized), 16);
  assert.equal(bounded.status, 502);
  assert.equal((await bounded.json() as { detail: { code: string } }).detail.code,
    "gateway_upstream_response_too_large");
});

test("S01-20 the password grant relays only the registered Backend authenticate codes", () => {
  const { PASSWORD_GRANT_UPSTREAM_ERRORS } = accountRelay;
  assert.ok(Array.isArray(PASSWORD_GRANT_UPSTREAM_ERRORS), "account-relay exports the allowlist");
  for (const { code, status } of PASSWORD_GRANT_UPSTREAM_ERRORS) {
    assert.deepEqual(
      FIELD_SESSION_ERROR_CONTRACT.find((row) => row.code === code)?.statuses,
      [status],
      `${code} is a registered ${status} row`
    );
  }
  assert.deepEqual(
    PASSWORD_GRANT_UPSTREAM_ERRORS.map(({ code }) => code),
    [
      "account_authentication_busy",
      "account_authentication_rate_limited",
      "account_authentication_unavailable",
      "account_crypto_key_binding_missing",
      "account_crypto_key_mismatch"
    ]
  );
});

test("S01-20 the checker rejects unregistered codes, statuses and flags", () => {
  assert.equal(fieldSessionErrorContractViolation(503, {
    code: "field_session_storage_busy", message: "x", retryable: true
  }), null);
  assert.match(String(fieldSessionErrorContractViolation(503, { code: "field_session_new_code" })),
    /unregistered field-session error code/);
  assert.match(String(fieldSessionErrorContractViolation(500, {
    code: "field_session_storage_busy", retryable: true
  })), /unregistered status/);
  assert.match(String(fieldSessionErrorContractViolation(503, {
    code: "field_session_storage_busy"
  })), /retryable flag/);
  assert.match(String(fieldSessionErrorContractViolation(401, {
    code: "invalid_refresh_token"
  })), /reauthentication_required flag/);
  assert.equal(fieldSessionErrorContractViolation(409, {
    detail: { code: "account_generation_inactive", message: "inactive" }
  }), null);
});

test("S01-20 route errors across login, refresh, status, devices and logout match the table", async () => {
  await expectError(await handleGatewayRequest(fieldSession({ query: "?x=1" })),
    "field_session_query_invalid");
  await expectError(await handleGatewayRequest(fieldSession({ query: "?devices=true" })),
    "gateway_auth_required");
  await expectError(await handleGatewayRequest(fieldSession({
    method: "DELETE", query: "?device_id="
  })), "field_session_query_invalid");
  await expectError(await handleGatewayRequest(jsonPost({ grant_type: "refresh_token" })),
    "field_session_request_invalid");
  await expectError(await handleGatewayRequest(jsonPost("{")), "invalid_json_body");
  await expectError(await handleGatewayRequest(jsonPost({ actor_id: ACTOR_ID, token: "wrong-token" })),
    "invalid_field_test_token");
  await expectError(await handleGatewayRequest(fieldSession({
    method: "POST",
    headers: { "content-type": "text/plain", "cf-connecting-ip": nextClientIp() },
    body: "{}"
  })), "json_content_type_required");
  await expectError(await handleGatewayRequest(jsonPost("x".repeat(5 * 1024))),
    "request_body_too_large");
  await expectError(await handleGatewayRequest(fieldSession({
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
  })), "gateway_trusted_client_ip_required");

  // Password login relays: Backend credential failure, rate limit, outage and invalid bodies.
  await expectError(await passwordLogin(async () => Response.json(
    { detail: { code: "account_authentication_failed", message: "no" } }, { status: 401 }
  )), "invalid_account_credentials");
  await expectError(await passwordLogin(async () => Response.json(
    { detail: { code: "account_authentication_rate_limited", message: "slow" } },
    { status: 429, headers: { "retry-after": "5" } }
  )), "account_authentication_rate_limited");
  await expectError(await passwordLogin(async () => Response.json(
    { detail: { code: "account_authentication_unavailable", message: "later" } },
    { status: 503, headers: { "retry-after": "5" } }
  )), "account_authentication_unavailable");
  const bindingMissing = await passwordLogin(async () => Response.json(
    { detail: { code: "account_crypto_key_binding_missing", message: "later" } },
    { status: 503, headers: { "retry-after": "7" } }
  ));
  assert.equal(bindingMissing.status, 503);
  assert.equal(bindingMissing.headers.get("retry-after"), "7");
  await expectError(bindingMissing, "account_crypto_key_binding_missing");
  // A Backend 429/503 code outside the table (or with another status) is not relayed.
  await expectError(await passwordLogin(async () => Response.json(
    { detail: { code: "account_enrollment_unavailable", message: "crypto misconfigured" } },
    { status: 503 }
  )), "gateway_account_upstream_invalid");
  await expectError(await passwordLogin(async () => Response.json(
    { detail: { code: "actor_rate_limit_exceeded", message: "slow" } },
    { status: 429, headers: { "retry-after": "5" } }
  )), "gateway_account_upstream_invalid");
  await expectError(await passwordLogin(async () => Response.json(
    { detail: { code: "account_authentication_busy", message: "status mismatch" } },
    { status: 429 }
  )), "gateway_account_upstream_invalid");
  await expectError(await passwordLogin(async () => new Response("{", {
    status: 200, headers: { "content-type": "application/json" }
  })), "gateway_account_upstream_invalid");
  await expectError(await passwordLogin(async () => Response.json({
    schema_version: "walksafe.account-authentication.v1",
    actor_id: BACKEND_ACTOR,
    account_generation: 0,
    auth_epoch: 1
  })), "gateway_account_upstream_invalid");

  // Long-session refresh failures.
  const session = await longLogin("contract-device-a");
  await expectError(await handleGatewayRequest(jsonPost({
    ...refreshBody(session), family_id: "f".repeat(43)
  })), "invalid_refresh_token");
  const rotated = await handleGatewayRequest(jsonPost(refreshBody(session)));
  assert.equal(rotated.status, 200);
  await expectError(await handleGatewayRequest(jsonPost(refreshBody(session))),
    "refresh_token_reuse_detected");
  const rotatedBody = await rotated.json() as Record<string, unknown>;
  // Owner decision 1: the reuse revoked every device; the holder learns the registered reason.
  await expectError(await handleGatewayRequest(jsonPost(refreshBody(rotatedBody))),
    "field_session_revoked_reuse_detected");
  // A family ended by its own logout keeps the generic revoked answer.
  const loggedOut = await longLogin("contract-device-logout");
  assert.equal((await handleGatewayRequest(fieldSession({
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(refreshBody(loggedOut))
  }))).status, 204);
  await expectError(await handleGatewayRequest(jsonPost(refreshBody(loggedOut))),
    "field_session_revoked");

  const faulted = await longLogin("contract-device-b");
  setFieldLongSessionStorageFaultForTests("file_fsync");
  await expectError(await handleGatewayRequest(jsonPost(refreshBody(faulted))),
    "field_session_storage_unavailable");
  setFieldLongSessionStorageFaultForTests("directory_fsync");
  await expectError(await handleGatewayRequest(jsonPost(refreshBody(faulted))),
    "field_session_storage_outcome_unknown");
  setFieldLongSessionStorageFaultForTests(null);

  process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED = "false";
  try {
    await expectError(await handleGatewayRequest(jsonPost(refreshBody(faulted))),
      "field_long_lived_sessions_unavailable");
  } finally {
    process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED = "true";
  }

  // Undeclared method on the same path.
  const put = await handleGatewayRequest(fieldSession({ method: "PUT" }));
  assert.equal(put.headers.get("allow"), "GET, POST, DELETE");
  await expectError(put, "gateway_method_not_allowed");

  // Logout without a session is an idempotent 204, not an error.
  assert.equal((await handleGatewayRequest(fieldSession({ method: "DELETE" }))).status, 204);
  assert.ok(observed.size >= 20, `covered ${observed.size} distinct codes`);
});

test("S01-20 short v5 and v6 sessions get the registered auth-required shape on devices and revoke", async () => {
  const v5Login = await handleGatewayRequest(jsonPost({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }));
  assert.equal(v5Login.status, 200);
  const v5 = sessionCookie(v5Login);
  assert.match(v5, /=v5\./);
  const v6Login = await passwordLogin(backendAccountFetch, null);
  assert.equal(v6Login.status, 200);
  const v6 = sessionCookie(v6Login);
  assert.match(v6, /=v6\./);
  for (const cookie of [v5, v6]) {
    const devices = await handleGatewayRequest(
      fieldSession({ query: "?devices=true", headers: { cookie } }),
      { fetchImpl: backendAccountFetch }
    );
    assert.equal(devices.status, 401);
    assert.equal(devices.headers.get("cache-control"), "no-store");
    await expectError(devices, "gateway_auth_required");
    const revoke = await handleGatewayRequest(
      fieldSession({ method: "DELETE", query: "?device_id=contract-other-device", headers: { cookie } }),
      { fetchImpl: backendAccountFetch }
    );
    assert.equal(revoke.status, 401);
    assert.equal(revoke.headers.get("set-cookie"), null);
    await expectError(revoke, "gateway_auth_required");
  }
  // The short sessions stay usable: the failed device calls did not revoke them.
  for (const cookie of [v5, v6]) {
    const status = await handleGatewayRequest(fieldSession({ headers: { cookie } }),
      { fetchImpl: backendAccountFetch });
    assert.equal((await status.json() as { authenticated: boolean }).authenticated, true);
  }
});

test("S01-20 Node adapter errors on /api/field-session are registered", async () => {
  const server = createGatewayServer({
    fetchImpl: async () => { throw new Error("adapter rejection must not reach Backend"); }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const port = (server.address() as AddressInfo).port;
    const invalid = await rawHttp(port, "GET", "/api/field-session",
      { "content-length": "2", "content-type": "application/json" }, "{}");
    assert.equal(invalid.status, 400);
    const body = JSON.parse(invalid.body) as Record<string, unknown>;
    assert.equal(body.code, "gateway_invalid_request");
    assert.equal(fieldSessionErrorContractViolation(invalid.status, body), null);
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
  assert.equal(fieldSessionErrorContractViolation(500, { code: "gateway_internal_error" }), null);
});
