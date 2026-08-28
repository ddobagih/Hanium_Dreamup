import assert from "node:assert/strict";
import {
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  stat,
  writeFile
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";

import {
  FIELD_TEST_TOKEN_HEADER,
  type GatewayFetch
} from "../src/backend.js";
import {
  decryptGatewayStateJson,
  initializeGatewayStateEncryption,
  resetGatewayStateEncryptionForTests
} from "../src/encrypted-json-store.js";
import { establishGatewaySession } from "../src/auth.js";
import { activateActorGeneration } from "../src/privacy-rights.js";
import { handleGatewayRequest } from "../src/routes.js";
import {
  currentServerCapacityLevelForTelemetry,
  resolveServerCapacityStatePath,
  SERVER_CAPACITY_LEVELS,
  SERVER_CAPACITY_MAX_PLAINTEXT_BYTES,
  SERVER_CAPACITY_STATE_RECORD_ID,
  resetServerCapacityForTests,
  serverCapacityForFieldSession,
  type ServerCapacitySnapshot
} from "../src/server-capacity.js";
import type { GatewayTelemetryEvent } from "../src/telemetry.js";
import {
  assertGatewayManagedStateReady,
  runGatewayStateMaintenance
} from "../src/state-encryption-maintenance.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR_ID = "capacity-field-operator";
const ACCOUNT_TOKEN = "capacity-account-token-12345678901234567890";
const MACHINE_TOKEN = "capacity-machine-token-12345678901234567890";
const SESSION_SECRET = "capacity-session-secret-123456789012345678901234567890";
const temporaryDirectories: string[] = [];

type CapacityFixture = {
  directory: string;
  stateRoot: string;
  keyringPath: string;
};

async function setupCapacityFixture(): Promise<CapacityFixture> {
  const directory = await mkdtemp(path.join(tmpdir(), "gateway-server-capacity-"));
  temporaryDirectories.push(directory);
  const stateRoot = path.join(directory, "state");
  const keyringPath = path.join(directory, "state-keyring.json");
  await mkdir(stateRoot, { recursive: true, mode: 0o700 });
  Object.assign(process.env, {
    NODE_ENV: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: MACHINE_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }
    ]),
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateRoot
  });
  delete process.env.WALKSAFE_ADMIN_TOKEN;
  delete process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON;
  delete process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED;
  delete process.env.WALKSAFE_FIELD_ACCESS_TTL_SECONDS;
  delete process.env.WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS;
  delete process.env.WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS;
  await configureTestStateEncryption(keyringPath);
  return { directory, stateRoot, keyringPath };
}

function capacitySnapshot(
  override: Partial<ServerCapacitySnapshot> = {}
): ServerCapacitySnapshot {
  return {
    version: 1,
    observed_at: "2035-01-01T00:00:00Z",
    expires_at: "2099-01-01T00:00:00Z",
    level: "NORMAL",
    reason: "STORAGE_UTILIZATION",
    ...override
  };
}

function fieldStatusRequest(headers: HeadersInit = {}): Request {
  return new Request("http://127.0.0.1:8081/api/field-session", { headers });
}

function authenticatedFieldStatusRequest(headers: HeadersInit = {}): Request {
  activateActorGeneration(ACTOR_ID);
  const login = establishGatewaySession(
    new Request("https://gateway.invalid/api/field-session"),
    ACTOR_ID
  );
  assert.equal(login.status, 200);
  const cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  return fieldStatusRequest({ ...Object.fromEntries(new Headers(headers)), cookie });
}

function jsonCapacityFetch(value: unknown, status = 200): GatewayFetch {
  return async () => Response.json(value, { status });
}

async function responseBody(
  fetchImpl: GatewayFetch,
  request: Request = authenticatedFieldStatusRequest()
): Promise<Record<string, unknown>> {
  const response = await handleGatewayRequest(request, { fetchImpl });
  assert.equal(response.status, 200);
  return response.json() as Promise<Record<string, unknown>>;
}

afterEach(async () => {
  process.env.NODE_ENV = "test";
  resetServerCapacityForTests();
  resetGatewayStateEncryptionForTests();
  for (const name of [
    "WALKSAFE_GATEWAY_STATE_KEYRING_FILE",
    "WALKSAFE_GATEWAY_RATE_LIMIT_DIR",
    "WALKSAFE_FIELD_WALK_LEDGER_PATH",
    "WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED",
    "WALKSAFE_FIELD_TEST_TOKEN",
    "WALKSAFE_FIELD_ACCOUNTS_JSON",
    "WALKSAFE_GATEWAY_SESSION_SECRET",
    "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER",
    "WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED",
    "WALKSAFE_FIELD_ACCESS_TTL_SECONDS",
    "WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS",
    "WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS"
  ]) delete process.env[name];
  for (const directory of temporaryDirectories.splice(0)) {
    await rm(directory, { recursive: true, force: true });
  }
});

test("fresh capacity is fetched with only machine headers and encrypted atomically", async () => {
  await setupCapacityFixture();
  const snapshot = capacitySnapshot({
    version: 7,
    level: "ADMIN_ONLY_WARNING"
  });
  let calls = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    calls += 1;
    assert.equal(String(input), "http://127.0.0.1:8000/internal/capacity");
    assert.equal(init?.method, "GET");
    assert.equal(init?.redirect, "error");
    assert.equal(init?.body, undefined);
    assert.ok(init?.signal instanceof AbortSignal);
    assert.deepEqual([...new Headers(init?.headers).entries()], [
      ["accept", "application/json"],
      [FIELD_TEST_TOKEN_HEADER, MACHINE_TOKEN]
    ]);
    return Response.json(snapshot);
  };
  const body = await responseBody(
    fetchImpl,
    authenticatedFieldStatusRequest({
      authorization: "Bearer user-secret",
      cookie: "walksafe_field_session=user-cookie",
      [FIELD_TEST_TOKEN_HEADER]: "user-supplied-token",
      "x-walksafe-actor-id": "user-actor",
      "x-walksafe-actor-assertion": "user-assertion"
    })
  );
  assert.equal(calls, 1);
  assert.deepEqual(body, {
    required: true,
    authenticated: true,
    actor_id: ACTOR_ID,
    session_scope: "general",
    capacity: snapshot
  });

  const target = resolveServerCapacityStatePath();
  const metadata = await stat(target);
  assert.equal(metadata.mode & 0o777, 0o600);
  const raw = await readFile(target, "utf8");
  assert.doesNotMatch(raw, /ADMIN_ONLY_WARNING|STORAGE_UTILIZATION/);
  assert.deepEqual(
    decryptGatewayStateJson(
      { kind: "server-capacity", recordId: SERVER_CAPACITY_STATE_RECORD_ID },
      raw,
      SERVER_CAPACITY_MAX_PLAINTEXT_BYTES
    ).value,
    snapshot
  );
  assert.deepEqual(
    (await readdir(path.dirname(target))).filter((name) => name.endsWith(".json")),
    [SERVER_CAPACITY_STATE_RECORD_ID]
  );
});

test("unauthenticated status neither fetches nor exposes server capacity", async () => {
  await setupCapacityFixture();
  let calls = 0;
  const response = await handleGatewayRequest(fieldStatusRequest(), {
    fetchImpl: async () => {
      calls += 1;
      return Response.json(capacitySnapshot());
    }
  });

  assert.equal(calls, 0);
  assert.deepEqual(await response.json(), {
    required: true,
    authenticated: false,
    actor_id: null,
    session_scope: null
  });
});

test("concurrent authenticated status requests share one capacity fetch", async () => {
  await setupCapacityFixture();
  const snapshot = capacitySnapshot({ version: 8 });
  const authenticated = authenticatedFieldStatusRequest();
  const cookie = authenticated.headers.get("cookie")!;
  let calls = 0;
  let markStarted: () => void = () => undefined;
  let releaseFetch: () => void = () => undefined;
  const started = new Promise<void>((resolve) => { markStarted = resolve; });
  const release = new Promise<void>((resolve) => { releaseFetch = resolve; });
  const fetchImpl: GatewayFetch = async () => {
    calls += 1;
    markStarted();
    await release;
    return Response.json(snapshot);
  };

  const first = handleGatewayRequest(fieldStatusRequest({ cookie }), { fetchImpl });
  await started;
  const second = handleGatewayRequest(fieldStatusRequest({ cookie }), { fetchImpl });
  releaseFetch();
  const responses = await Promise.all([first, second]);

  assert.equal(calls, 1);
  for (const response of responses) {
    assert.equal(response.status, 200);
    assert.deepEqual(
      (await response.json() as Record<string, unknown>).capacity,
      snapshot
    );
  }
});

test("an upstream failure returns the unexpired encrypted cache after restart", async () => {
  const fixture = await setupCapacityFixture();
  const snapshot = capacitySnapshot({
    version: 11,
    level: "PAUSE_NEW_FIELD_TEST_PARTICIPANTS"
  });
  assert.deepEqual(
    (await responseBody(jsonCapacityFetch(snapshot))).capacity,
    snapshot
  );
  const target = resolveServerCapacityStatePath();
  const beforeRestart = await readFile(target, "utf8");

  resetGatewayStateEncryptionForTests();
  process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = fixture.keyringPath;
  initializeGatewayStateEncryption();
  const cached = await responseBody(async () =>
    Response.json({ detail: "unavailable" }, { status: 503 })
  );
  assert.deepEqual(cached.capacity, snapshot);
  assert.equal(await readFile(target, "utf8"), beforeRestart);
});

test("version replay, conflicts, and observed-time rollback never replace last-known state", async () => {
  await setupCapacityFixture();
  const accepted = capacitySnapshot({
    version: 20,
    level: "HOLD_NEW_RAW_COLLECTION_SESSIONS"
  });
  assert.deepEqual(
    (await responseBody(jsonCapacityFetch(accepted))).capacity,
    accepted
  );
  const target = resolveServerCapacityStatePath();
  const acceptedRaw = await readFile(target, "utf8");

  assert.deepEqual(
    (await responseBody(jsonCapacityFetch({ ...accepted }))).capacity,
    accepted
  );
  assert.equal(await readFile(target, "utf8"), acceptedRaw);

  const rollback = capacitySnapshot({
    version: 19,
    level: "HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES"
  });
  assert.deepEqual(
    (await responseBody(jsonCapacityFetch(rollback))).capacity,
    accepted
  );
  assert.equal(await readFile(target, "utf8"), acceptedRaw);

  const conflict = { ...accepted, level: "NORMAL" as const };
  assert.deepEqual(
    (await responseBody(jsonCapacityFetch(conflict))).capacity,
    accepted
  );
  assert.equal(await readFile(target, "utf8"), acceptedRaw);

  const observedAtRollback = capacitySnapshot({
    version: 21,
    observed_at: "2034-12-31T23:59:59.999999Z",
    level: "HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES"
  });
  assert.deepEqual(
    (await responseBody(jsonCapacityFetch(observedAtRollback))).capacity,
    accepted
  );
  assert.equal(await readFile(target, "utf8"), acceptedRaw);

  const advanced = capacitySnapshot({
    version: 21,
    level: "HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES"
  });
  assert.deepEqual(
    (await responseBody(jsonCapacityFetch(advanced))).capacity,
    advanced
  );
  assert.notEqual(await readFile(target, "utf8"), acceptedRaw);
});

test("cache freshness excludes the exact expiry boundary", async () => {
  await setupCapacityFixture();
  const expiresAt = "2038-01-19T03:14:07.123000Z";
  const snapshot = capacitySnapshot({
    observed_at: "2038-01-19T03:14:06.999999Z",
    expires_at: expiresAt
  });
  const expiryEpochMs = Date.parse(expiresAt);
  const request = fieldStatusRequest();
  assert.deepEqual(
    await serverCapacityForFieldSession(
      request,
      jsonCapacityFetch(snapshot),
      expiryEpochMs - 1,
      50
    ),
    snapshot
  );
  assert.equal(
    await serverCapacityForFieldSession(
      request,
      async () => { throw new Error("offline"); },
      expiryEpochMs,
      50
    ),
    null
  );
});

test("completion telemetry uses only the fresh process-memory capacity level", async () => {
  await setupCapacityFixture();
  const expiresAt = "2038-01-19T03:14:07.123000Z";
  const snapshot = capacitySnapshot({
    version: 17,
    observed_at: "2038-01-19T03:14:06.999999Z",
    expires_at: expiresAt,
    level: "ADMIN_ONLY_WARNING"
  });
  const events: GatewayTelemetryEvent[] = [];

  const response = await handleGatewayRequest(authenticatedFieldStatusRequest(), {
    fetchImpl: jsonCapacityFetch(snapshot),
    telemetrySink: event => events.push(event)
  });

  assert.equal(response.status, 200);
  assert.equal(events.length, 1);
  assert.equal(events[0]!.capacity_level, "ADMIN_ONLY_WARNING");
  await rm(resolveServerCapacityStatePath());
  const expiryEpochMs = Date.parse(expiresAt);
  assert.equal(
    currentServerCapacityLevelForTelemetry(expiryEpochMs - 1),
    "ADMIN_ONLY_WARNING"
  );
  assert.equal(currentServerCapacityLevelForTelemetry(expiryEpochMs), "UNKNOWN");
});

test("non-2xx, malformed, duplicate-member, and oversized upstream data leave status unchanged", async () => {
  await setupCapacityFixture();
  const malformedFetches: GatewayFetch[] = [
    async () => Response.json(capacitySnapshot(), { status: 500 }),
    jsonCapacityFetch({ ...capacitySnapshot(), extra: true }),
    async () => new Response(
      `{"version":1,"version":2,"observed_at":"2035-01-01T00:00:00Z","expires_at":"2099-01-01T00:00:00Z","level":"NORMAL","reason":"STORAGE_UTILIZATION"}`,
      { headers: { "content-type": "application/json" } }
    ),
    jsonCapacityFetch(capacitySnapshot({ observed_at: "2035-02-30T00:00:00Z" })),
    jsonCapacityFetch(capacitySnapshot({ observed_at: "2035-01-01T00:00:00+00:00" })),
    async () => new Response(" ".repeat(4 * 1024 + 1), {
      headers: { "content-type": "application/json" }
    })
  ];
  for (const fetchImpl of malformedFetches) {
    assert.deepEqual(await responseBody(fetchImpl), {
      required: true,
      authenticated: true,
      actor_id: ACTOR_ID,
      session_scope: "general"
    });
  }
});

test("capacity timeout cannot turn field-session status into a 5xx", async () => {
  await setupCapacityFixture();
  const hangingFetch: GatewayFetch = async (_input, init) =>
    new Promise<Response>((_resolve, reject) => {
      const signal = init?.signal;
      if (!signal) return;
      if (signal.aborted) {
        reject(signal.reason);
        return;
      }
      signal.addEventListener("abort", () => reject(signal.reason), { once: true });
    });
  assert.deepEqual(await responseBody(hangingFetch), {
    required: true,
    authenticated: true,
    actor_id: ACTOR_ID,
    session_scope: "general"
  });
});

test("long status may include capacity while devices=true performs zero capacity fetches", async () => {
  await setupCapacityFixture();
  Object.assign(process.env, {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "900",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "2592000",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "7776000"
  });
  const login = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "198.51.100.71"
      },
      body: JSON.stringify({
        actor_id: ACTOR_ID,
        token: ACCOUNT_TOKEN,
        device_id: "capacity-device"
      })
    })
  );
  assert.equal(login.status, 200);
  const cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /^walksafe_field_session=/);

  const snapshot = capacitySnapshot({ version: 31 });
  let capacityFetches = 0;
  const fetchImpl: GatewayFetch = async () => {
    capacityFetches += 1;
    return Response.json(snapshot);
  };
  const statusResponse = await handleGatewayRequest(
    fieldStatusRequest({ cookie }),
    { fetchImpl }
  );
  assert.equal(statusResponse.status, 200);
  assert.deepEqual(
    (await statusResponse.json() as Record<string, unknown>).capacity,
    snapshot
  );
  assert.equal(capacityFetches, 1);

  const devicesRequest = (): Request => new Request(
    "https://gateway.invalid/api/field-session?devices=true",
    { headers: { cookie } }
  );
  const baseline = await handleGatewayRequest(devicesRequest());
  const withCapacityDependency = await handleGatewayRequest(
    devicesRequest(),
    { fetchImpl }
  );
  assert.equal(baseline.status, 200);
  assert.equal(withCapacityDependency.status, 200);
  assert.deepEqual(await withCapacityDependency.json(), await baseline.json());
  assert.equal(capacityFetches, 1);
});

test("OpenAPI exposes optional capacity only on basic and long session status", async () => {
  const contract = JSON.parse(
    await readFile(path.join(process.cwd(), "openapi.json"), "utf8")
  ) as {
    info: { version: string };
    components: {
      schemas: Record<string, {
        required?: string[];
        properties?: Record<string, unknown>;
        additionalProperties?: boolean;
      }>;
    };
  };
  assert.equal(contract.info.version, "0.7.0");
  const capacity = contract.components.schemas.CapacitySnapshot!;
  assert.deepEqual(capacity.required, [
    "version",
    "observed_at",
    "expires_at",
    "level",
    "reason"
  ]);
  assert.deepEqual(Object.keys(capacity.properties ?? {}), capacity.required);
  assert.equal(capacity.additionalProperties, false);
  assert.deepEqual(
    (capacity.properties?.level as { enum: string[] }).enum,
    [...SERVER_CAPACITY_LEVELS]
  );
  for (const timestampField of ["observed_at", "expires_at"]) {
    assert.equal(
      (capacity.properties?.[timestampField] as { pattern: string }).pattern,
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"
    );
  }
  for (const schemaName of ["FieldSessionBasicStatus", "FieldLongSessionStatus"]) {
    const schema = contract.components.schemas[schemaName]!;
    assert.deepEqual(schema.properties?.capacity, {
      $ref: "#/components/schemas/CapacitySnapshot"
    });
    assert.equal(schema.required?.includes("capacity"), false);
  }
  assert.equal(
    Object.hasOwn(
      contract.components.schemas.FieldSessionDeviceList!.properties ?? {},
      "capacity"
    ),
    false
  );
});

test("state encryption maintenance inspects and migrates the singleton capacity cache", async () => {
  const { stateRoot } = await setupCapacityFixture();
  process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = path.join(
    stateRoot,
    "missing-field-walk-ledger.json"
  );
  process.env.WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED = "true";
  const snapshot = capacitySnapshot({
    version: 41,
    level: "HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES"
  });
  const target = resolveServerCapacityStatePath();
  await mkdir(path.dirname(target), { recursive: true, mode: 0o700 });
  await writeFile(target, `${JSON.stringify(snapshot)}\n`, { mode: 0o600 });

  const inspected = await runGatewayStateMaintenance("inspect");
  assert.equal(inspected.length, 1);
  assert.deepEqual(inspected[0], {
    file: target,
    kind: "server-capacity",
    statusBefore: "plaintext",
    action: "none",
    keyId: null
  });
  const migrated = await runGatewayStateMaintenance("migrate-plaintext");
  assert.equal(migrated.length, 1);
  assert.equal(migrated[0]?.action, "migrated");
  assert.deepEqual(
    decryptGatewayStateJson(
      { kind: "server-capacity", recordId: SERVER_CAPACITY_STATE_RECORD_ID },
      await readFile(target, "utf8"),
      SERVER_CAPACITY_MAX_PLAINTEXT_BYTES
    ).value,
    snapshot
  );
  assert.equal(assertGatewayManagedStateReady().length, 1);
});
