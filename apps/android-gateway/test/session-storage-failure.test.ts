// S01-23 / S01-24 / S19-11: session storage faults must stay distinct from
// invalid, expired, or revoked credentials, and logout must not report success
// while the server-side revocation could not be confirmed.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";
import type { Server } from "node:http";

import {
  initializeGatewayStateEncryption,
  resetGatewayStateEncryptionForTests
} from "../src/encrypted-json-store.js";
import {
  fieldLongSessionLookup,
  setFieldLongSessionAdmissionHoldForTests,
  setFieldLongSessionStorageFaultForTests
} from "../src/field-long-session.js";
import { createGatewayServer } from "../src/node-adapter.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR_ID = "field-operator";
const ACCOUNT_TOKEN = "field-account-token-12345678901234567890";
const INTERNAL_TOKEN = "field-internal-token-12345678901234567890";
const SESSION_SECRET = "gateway-session-secret-123456789012345678901234567890";
const STORAGE_UNAVAILABLE = "field_session_storage_unavailable";

type Session = {
  cookie: string;
  actor_id: string;
  device_id: string;
  family_id: string;
  rotation: number;
  refresh_token: string;
  access_expires_at_epoch_ms: number;
};

let stateDirectory = "";
let keyringPath = "";
let server: Server;
let baseUrl = "";
let ipSuffix = 10;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-session-storage-failure-"));
  keyringPath = `${stateDirectory}-state-keyring.json`;
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:9",
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    // R19-GB-01: per-file ledger so parallel test files never share the default lock.
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "60",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "300",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "3600"
  });
  await configureTestStateEncryption(keyringPath);
  server = createGatewayServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterEach(async () => {
  setFieldLongSessionAdmissionHoldForTests(null);
  setFieldLongSessionStorageFaultForTests(null);
  await rm(stateDirectory, { recursive: true, force: true });
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
});

after(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await rm(stateDirectory, { recursive: true, force: true });
  await rm(keyringPath, { force: true });
});

function longStatePath(actorId = ACTOR_ID): string {
  const digest = createHash("sha256").update(`field-long-session\0${actorId}`).digest("hex");
  return path.join(stateDirectory, "field-long-sessions", `${digest}.json`);
}

function shortStatePath(actorId = ACTOR_ID): string {
  const digest = createHash("sha256").update(`field\0${actorId}`).digest("hex");
  return path.join(stateDirectory, "sessions", `field-${digest}.json`);
}

function loginRequest(body: Record<string, unknown>): Request {
  ipSuffix += 1;
  return new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": `198.51.100.${ipSuffix}` },
    body: JSON.stringify(body)
  });
}

async function longLogin(deviceId: string): Promise<Session> {
  const response = await handleGatewayRequest(
    loginRequest({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN, device_id: deviceId })
  );
  assert.equal(response.status, 200);
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /^walksafe_field_session=v4\./);
  return { ...(await response.json() as Omit<Session, "cookie">), cookie };
}

async function shortLogin(): Promise<string> {
  const response = await handleGatewayRequest(
    loginRequest({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
  );
  assert.equal(response.status, 200);
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /^walksafe_field_session=v5\./);
  return cookie;
}

async function http(
  method: string,
  route: string,
  cookie: string | null,
  body?: unknown
): Promise<{ status: number; code: string | null; setCookie: string | null; json: unknown }> {
  const headers: Record<string, string> = {};
  if (cookie) headers.cookie = cookie;
  if (body !== undefined) headers["content-type"] = "application/json";
  const response = await fetch(`${baseUrl}${route}`, {
    method,
    headers,
    ...(body !== undefined ? { body: JSON.stringify(body) } : {})
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) as unknown : null;
  const code = json && typeof json === "object" && "code" in json
    ? String((json as { code: unknown }).code)
    : null;
  return { status: response.status, code, setCookie: response.headers.get("set-cookie"), json };
}

function assertStorageUnavailable(
  result: { status: number; code: string | null; setCookie: string | null },
  label: string
): void {
  assert.deepEqual(
    { status: result.status, code: result.code, setCookie: result.setCookie },
    { status: 503, code: STORAGE_UNAVAILABLE, setCookie: null },
    label
  );
}

async function authenticated(cookie: string): Promise<boolean> {
  const result = await http("GET", "/api/field-session", cookie);
  assert.equal(result.status, 200);
  return (result.json as { authenticated: boolean }).authenticated;
}

// The hold is observed when the actor admission queue starts the locked step,
// i.e. after every pre-lock identity check has already succeeded.
function holdActorAdmission(): { reached: Promise<void>; release: () => void } {
  let reached!: () => void;
  let release!: () => void;
  const reachedPromise = new Promise<void>((resolve) => { reached = resolve; });
  const gate = new Promise<void>((resolve) => { release = resolve; });
  const hold = {
    then(onFulfilled?: (value: void) => unknown, onRejected?: (reason: unknown) => unknown) {
      reached();
      return gate.then(onFulfilled, onRejected);
    }
  } as unknown as Promise<void>;
  setFieldLongSessionAdmissionHoldForTests(hold);
  return { reached: reachedPromise, release };
}

test("S01-23 long-session status, devices, and protected API return 503 on permission/read faults", async () => {
  const session = await longLogin("s01-23-permission");
  const target = longStatePath();
  const directory = path.dirname(target);
  for (const [label, inject, restore] of [
    ["permission denied", () => chmod(target, 0o000), () => chmod(target, 0o600)],
    ["mode integrity", () => chmod(target, 0o644), () => chmod(target, 0o600)],
    ["insecure directory", () => chmod(directory, 0o755), () => chmod(directory, 0o700)]
  ] as const) {
    await inject();
    try {
      assertStorageUnavailable(await http("GET", "/api/field-session", session.cookie), `${label} status`);
      assertStorageUnavailable(
        await http("GET", "/api/field-session?devices=true", session.cookie),
        `${label} devices`
      );
      assertStorageUnavailable(
        await http("POST", "/api/navigation/walking", session.cookie, {}),
        `${label} protected API`
      );
    } finally {
      await restore();
    }
  }
  assert.equal(await authenticated(session.cookie), true);
});

test("S01-23 unavailable state encryption key is 503, not an unauthenticated session", async () => {
  const session = await longLogin("s01-23-keyring");
  resetGatewayStateEncryptionForTests();
  process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = `${keyringPath}.missing`;
  try {
    assertStorageUnavailable(await http("GET", "/api/field-session", session.cookie), "status");
    assertStorageUnavailable(await http("DELETE", "/api/field-session", session.cookie), "logout");
  } finally {
    resetGatewayStateEncryptionForTests();
    process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = keyringPath;
    initializeGatewayStateEncryption();
  }
  assert.equal(await authenticated(session.cookie), true);
});

test("S01-23 device revoke does not turn an in-lock storage fault into 401", async () => {
  const session = await longLogin("s01-23-revoke-self");
  await longLogin("s01-23-revoke-other");
  const target = longStatePath();
  const original = await readFile(target);
  const hold = holdActorAdmission();
  const pending = http(
    "DELETE",
    "/api/field-session?device_id=s01-23-revoke-other",
    session.cookie
  );
  await hold.reached;
  await writeFile(target, "corrupted encrypted state");
  hold.release();
  let result: Awaited<typeof pending>;
  try {
    result = await pending;
  } finally {
    setFieldLongSessionAdmissionHoldForTests(null);
    await writeFile(target, original);
  }
  assertStorageUnavailable(result, "selective revoke with in-lock read fault");
  assert.equal(await authenticated(session.cookie), true);
});

test("S01-23 short-session storage faults are 503, not 401 or an internal error", async () => {
  const cookie = await shortLogin();
  const target = shortStatePath();
  const directory = path.dirname(target);
  const original = await readFile(target);
  for (const [label, inject, restore] of [
    ["permission denied", () => chmod(target, 0o000), () => chmod(target, 0o600)],
    ["decrypt failure", () => writeFile(target, "corrupted encrypted state"), () => writeFile(target, original)],
    ["insecure directory", () => chmod(directory, 0o755), () => chmod(directory, 0o700)]
  ] as const) {
    await inject();
    try {
      assertStorageUnavailable(await http("GET", "/api/field-session", cookie), `${label} status`);
      assertStorageUnavailable(
        await http("GET", "/api/field-session?devices=true", cookie),
        `${label} devices`
      );
      assertStorageUnavailable(
        await http("POST", "/api/navigation/walking", cookie, {}),
        `${label} protected API`
      );
      assertStorageUnavailable(await http("DELETE", "/api/field-session", cookie), `${label} logout`);
    } finally {
      await restore();
    }
  }
  assert.equal(await authenticated(cookie), true);
});

test("S01-24 cookie logout with an unreadable session store is not reported as success", async () => {
  const session = await longLogin("s01-24-read");
  const target = longStatePath();
  await chmod(target, 0o000);
  try {
    assertStorageUnavailable(await http("DELETE", "/api/field-session", session.cookie), "logout");
  } finally {
    await chmod(target, 0o600);
  }
  assert.equal(await authenticated(session.cookie), true);
  const retried = await http("DELETE", "/api/field-session", session.cookie);
  assert.equal(retried.status, 204);
  assert.match(retried.setCookie ?? "", /Max-Age=0/);
  assert.equal(await authenticated(session.cookie), false);
});

test("S01-24 cookie logout with a failed revocation write keeps the family and fails closed", async () => {
  const session = await longLogin("s01-24-write");
  setFieldLongSessionStorageFaultForTests("file_fsync");
  assertStorageUnavailable(await http("DELETE", "/api/field-session", session.cookie), "logout");
  assert.equal(await authenticated(session.cookie), true);
});

test("S01-24 cookie logout does not report success when the store vanishes before revocation", async () => {
  const session = await longLogin("s01-24-vanish");
  const target = longStatePath();
  const held = `${target}.held`;
  const hold = holdActorAdmission();
  const pending = http("DELETE", "/api/field-session", session.cookie);
  await hold.reached;
  await rename(target, held);
  hold.release();
  let result: Awaited<typeof pending>;
  try {
    result = await pending;
  } finally {
    setFieldLongSessionAdmissionHoldForTests(null);
    await rename(held, target);
  }
  assertStorageUnavailable(result, "logout with vanished store");
  assert.equal(await authenticated(session.cookie), true);
});

test("S01-24 refresh-proof logout with an unreadable store is not reported as success", async () => {
  const session = await longLogin("s01-24-refresh-proof");
  const target = longStatePath();
  await chmod(target, 0o000);
  try {
    assertStorageUnavailable(
      await http("DELETE", "/api/field-session", null, {
        grant_type: "refresh_token",
        actor_id: session.actor_id,
        device_id: session.device_id,
        family_id: session.family_id,
        rotation: session.rotation,
        refresh_token: session.refresh_token
      }),
      "refresh-proof logout"
    );
  } finally {
    await chmod(target, 0o600);
  }
  assert.equal(await authenticated(session.cookie), true);
});

test("S19-11 refresh and expired lookup keep storage faults distinct from expiry", async () => {
  const session = await longLogin("s19-11-refresh");
  const target = longStatePath();
  const original = await readFile(target);
  const refreshBody = {
    grant_type: "refresh_token",
    actor_id: session.actor_id,
    device_id: session.device_id,
    family_id: session.family_id,
    rotation: session.rotation,
    refresh_token: session.refresh_token
  };
  await writeFile(target, "corrupted encrypted state");
  try {
    const request = new Request("https://gateway.invalid/api/field-session", {
      headers: { cookie: session.cookie }
    });
    assert.equal(
      fieldLongSessionLookup(request, session.access_expires_at_epoch_ms).status,
      "storage_unavailable"
    );
    const refreshed = await http("POST", "/api/field-session", null, refreshBody);
    assertStorageUnavailable(refreshed, "refresh");
    assert.equal(
      (refreshed.json as { reauthentication_required?: unknown }).reauthentication_required,
      undefined
    );
  } finally {
    await writeFile(target, original);
  }
  const recovered = await http("POST", "/api/field-session", null, refreshBody);
  assert.equal(recovered.status, 200);
});
