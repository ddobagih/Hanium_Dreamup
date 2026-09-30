// w24 fix-login (review-login F2, F3, F6, F7, F11, F12, F13): capacity eviction for an
// unlimited login (Root decision R2), fail-closed end of short sessions after an account-wide
// refresh-token reuse (owner decision 1), and small ordering/contract fixes.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";

import { withExclusiveFileLockAsync } from "../src/exclusive-file-lock.js";
import {
  establishBackendFieldLongSession,
  establishFieldLongSession,
  refreshBackendFieldLongSession,
  refreshFieldLongSession,
  setFieldLongSessionStorageFaultForTests,
  type BackendEmailAccountBinding
} from "../src/field-long-session.js";
import { resolveFieldLongSessionConfig } from "../src/config.js";
import type { GatewayFetch } from "../src/backend.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption, decryptTestStateFile } from "./state-encryption-fixture.js";

const ACTOR_ID = "hardening-operator";
const ACCOUNT_TOKEN = "hardening-account-token-1234567890123456";
const BACKEND_ACTOR = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3099";
const FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024;
const FINITE = { idle: "300", absolute: "3600" };

let stateDirectory = "";
let keyringPath = "";
let ipSuffix = 10;
let backendCalls = 0;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-field-hardening-"));
  keyringPath = `${stateDirectory}-state-keyring.json`;
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "hardening-internal-token-1234567890123",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "hardening-session-secret-12345678901234567890123",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "60",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "unlimited",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "unlimited"
  });
  await configureTestStateEncryption(keyringPath);
});

afterEach(async () => {
  setFieldLongSessionStorageFaultForTests(null);
  backendCalls = 0;
  Object.assign(process.env, {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "unlimited",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "unlimited"
  });
  await rm(stateDirectory, { recursive: true, force: true });
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
  await rm(keyringPath, { force: true });
});

const backendFetch: GatewayFetch = async (input) => {
  backendCalls += 1;
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
  return Response.json({ detail: { code: "unexpected_test_path" } }, { status: 500 });
};

type Session = {
  actor_id: string;
  device_id: string;
  family_id: string;
  rotation: number;
  refresh_token: string;
  cookie: string;
};

type StoredFamily = {
  device_id: string;
  family_id: string;
  revoked_at_epoch_ms: number | null;
  revoke_reason: string | null;
  last_rotated_at_epoch_ms: number;
};

function nextClientIp(): string {
  ipSuffix += 1;
  return `198.51.100.${ipSuffix % 250}`;
}

function post(body: Record<string, unknown>, method = "POST"): Promise<Response> {
  return handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method,
    headers: { "content-type": "application/json", "cf-connecting-ip": nextClientIp() },
    body: JSON.stringify(body)
  }), { fetchImpl: backendFetch });
}

async function session(response: Response): Promise<Session> {
  assert.equal(response.status, 200);
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  return { ...(await response.json() as Omit<Session, "cookie">), cookie };
}

function tokenLogin(deviceId?: string): Promise<Response> {
  return post(deviceId === undefined
    ? { actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }
    : { actor_id: ACTOR_ID, token: ACCOUNT_TOKEN, device_id: deviceId });
}

function refreshBody(current: Session): Record<string, unknown> {
  return {
    grant_type: "refresh_token",
    actor_id: current.actor_id,
    device_id: current.device_id,
    family_id: current.family_id,
    rotation: current.rotation,
    refresh_token: current.refresh_token
  };
}

async function authenticated(cookie: string): Promise<boolean> {
  const response = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", { headers: { cookie } }),
    { fetchImpl: backendFetch }
  );
  if (response.status !== 200) return false;
  return (await response.json() as { authenticated: boolean }).authenticated;
}

async function code(response: Response): Promise<string> {
  const body = await response.json() as { code?: string; detail?: { code?: string } };
  return body.detail?.code ?? body.code ?? "";
}

function longStatePath(actorId: string): string {
  const digest = createHash("sha256").update(`field-long-session\0${actorId}`).digest("hex");
  return path.join(stateDirectory, "field-long-sessions", `${digest}.json`);
}

function shortSessionLockPath(actorId: string): string {
  const digest = createHash("sha256").update(`field\0${actorId}`).digest("hex");
  return path.join(stateDirectory, "sessions", `field-${digest}.json.lock`);
}

function storedState(actorId: string): {
  families: StoredFamily[];
  backend_families?: StoredFamily[];
  reuse_revoked_at_epoch_ms?: number;
} {
  const target = longStatePath(actorId);
  return decryptTestStateFile(
    { kind: "field-long-session", recordId: path.basename(target) },
    readFileSync(target, "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
}

async function holdLock(lockPath: string): Promise<() => Promise<void>> {
  let release!: () => void;
  let acquired!: () => void;
  const ready = new Promise<void>((resolve) => { acquired = resolve; });
  const holding = withExclusiveFileLockAsync(lockPath, async () => {
    acquired();
    await new Promise<void>((resolve) => { release = resolve; });
  });
  await ready;
  return async () => {
    release();
    await holding;
  };
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

test("F2/R2 unlimited login at the 32-device cap evicts the least recently rotated family", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const start = 1_900_000_000_000;
  const families: Session[] = [];
  for (let index = 0; index < 32; index += 1) {
    families.push(await session(
      await establishFieldLongSession(request, ACTOR_ID, `capacity-device-${index}`, start + index)
    ));
  }
  // Device 0 is the oldest by creation but rotated most recently; device 1 is now the least
  // recently rotated family.
  const rotated = await session(await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: families[0]!.device_id, familyId: families[0]!.family_id,
    rotation: 0, refreshToken: families[0]!.refresh_token
  }, start + 100));
  assert.equal(rotated.rotation, 1);

  const newcomer = await session(
    await establishFieldLongSession(request, ACTOR_ID, "capacity-device-new", start + 200)
  );
  const persisted = storedState(ACTOR_ID).families;
  const active = persisted.filter((family) => family.revoked_at_epoch_ms === null);
  assert.equal(active.length, 32);
  assert.ok(active.some((family) => family.device_id === newcomer.device_id));
  assert.ok(active.some((family) => family.device_id === "capacity-device-0"));
  const evicted = persisted.filter((family) => family.revoke_reason === "capacity_evicted");
  assert.deepEqual(evicted.map((family) => [family.device_id, family.revoked_at_epoch_ms]),
    [["capacity-device-1", start + 200]]);

  // The evicted device learns only the generic revoked answer; the others keep working.
  const evictedRefresh = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: families[1]!.device_id, familyId: families[1]!.family_id,
    rotation: 0, refreshToken: families[1]!.refresh_token
  }, start + 300);
  assert.equal(evictedRefresh.status, 401);
  assert.equal(await code(evictedRefresh), "field_session_revoked");
  const kept = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: families[2]!.device_id, familyId: families[2]!.family_id,
    rotation: 0, refreshToken: families[2]!.refresh_token
  }, start + 300);
  assert.equal(kept.status, 200);
});

test("F2/R2 Backend-email families evict the least recently rotated family too", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const start = 1_900_000_000_000;
  const binding = (index: number | string): BackendEmailAccountBinding => ({
    actorId: BACKEND_ACTOR, accountGeneration: 1, authEpoch: 1, deviceId: `backend-capacity-${index}`
  });
  for (let index = 0; index < 32; index += 1) {
    assert.equal((await establishBackendFieldLongSession(request, binding(index), start + index)).status, 200);
  }
  assert.equal((await establishBackendFieldLongSession(request, binding("new"), start + 50)).status, 200);
  const backend = storedState(BACKEND_ACTOR).backend_families ?? [];
  assert.equal(backend.filter((family) => family.revoked_at_epoch_ms === null).length, 32);
  assert.deepEqual(
    backend.filter((family) => family.revoke_reason === "capacity_evicted").map((family) => family.device_id),
    ["backend-capacity-0"]
  );
});

test("F2 finite idle windows keep the existing 503 at the device cap", async () => {
  process.env.WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS = FINITE.idle;
  process.env.WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS = FINITE.absolute;
  const request = new Request("https://gateway.invalid/api/field-session");
  const start = Date.now();
  for (let index = 0; index < 32; index += 1) {
    assert.equal((await establishFieldLongSession(request, ACTOR_ID, `finite-${index}`, start)).status, 200);
  }
  const blocked = await establishFieldLongSession(request, ACTOR_ID, "finite-new", start + 1_000);
  assert.equal(blocked.status, 503);
  assert.equal(await code(blocked), "field_session_device_capacity_unavailable");
  const families = storedState(ACTOR_ID).families;
  assert.equal(families.length, 32);
  assert.ok(families.every((family) => family.revoked_at_epoch_ms === null));
});

test("F3 reuse ends short sessions fail-closed while the short-session store is locked", async () => {
  const shortResponse = await tokenLogin();
  assert.equal(shortResponse.status, 200);
  const shortCookie = (shortResponse.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(shortCookie, /^walksafe_field_session=v5\./);
  const first = await session(await tokenLogin("short-lock-device-a"));
  const second = await session(await tokenLogin("short-lock-device-b"));
  assert.equal((await post(refreshBody(first))).status, 200);
  assert.equal(await authenticated(shortCookie), true);

  // The short-session cleanup callback cannot take its lock: before F3 the v5 session
  // silently stayed valid for up to 12 hours.
  const release = await holdLock(shortSessionLockPath(ACTOR_ID));
  try {
    const reused = await post(refreshBody(first));
    assert.equal(reused.status, 401);
    assert.equal(await code(reused), "refresh_token_reuse_detected");
    assert.equal(await authenticated(shortCookie), false);
    assert.equal(await authenticated(second.cookie), false);
  } finally {
    await release();
  }
  const state = storedState(ACTOR_ID);
  assert.equal(typeof state.reuse_revoked_at_epoch_ms, "number");
  assert.ok(state.families.every((family) => family.revoke_reason === "refresh_token_reuse"));
  assert.equal(await authenticated(shortCookie), false);

  // A short login after the revocation second is accepted again.
  await sleep(1_100);
  const again = await tokenLogin();
  assert.equal(again.status, 200);
  assert.equal(await authenticated((again.headers.get("set-cookie") ?? "").split(";", 1)[0]!), true);
});

test("F3 reuse with an unknown rename outcome still ends v5 and v7 short sessions", async () => {
  const shortV5 = await tokenLogin();
  const v5Cookie = (shortV5.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  const first = await session(await tokenLogin("unknown-outcome-device"));
  assert.equal((await post(refreshBody(first))).status, 200);
  assert.equal(await authenticated(v5Cookie), true);
  const shortLock = await holdLock(shortSessionLockPath(ACTOR_ID));
  try {
    setFieldLongSessionStorageFaultForTests("directory_fsync");
    const reused = await post(refreshBody(first));
    assert.equal(reused.status, 500);
    assert.equal(await code(reused), "field_session_storage_outcome_unknown");
    assert.equal(await authenticated(v5Cookie), false);
  } finally {
    await shortLock();
  }

  // Backend-email account: a v7 device session ends with the long families.
  const v7 = await post({
    grant_type: "password", email: "hardening@example.org", password: "hardening-password-1234",
    remember_me: true, device_id: "hardening-v7-device"
  });
  assert.equal(v7.status, 200);
  const v7Cookie = (v7.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(v7Cookie, /^walksafe_field_session=v7\./);
  const long = await session(await post({
    grant_type: "password", email: "hardening@example.org", password: "hardening-password-1234",
    remember_me: true, device_id: "hardening-long-device", session_mode: "long_lived"
  }));
  assert.equal((await post(refreshBody(long))).status, 200);
  assert.equal(await authenticated(v7Cookie), true);
  const v7Lock = await holdLock(shortSessionLockPath(BACKEND_ACTOR));
  try {
    const reused = await post(refreshBody(long));
    assert.equal(await code(reused), "refresh_token_reuse_detected");
    assert.equal(await authenticated(v7Cookie), false);
  } finally {
    await v7Lock();
  }
});

test("F6 Backend reuse is detected even while the Backend check is unavailable", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const binding: BackendEmailAccountBinding = {
    actorId: BACKEND_ACTOR, accountGeneration: 1, authEpoch: 1, deviceId: "reuse-outage-a"
  };
  const first = await session(await establishBackendFieldLongSession(request, binding));
  const other = await session(
    await establishBackendFieldLongSession(request, { ...binding, deviceId: "reuse-outage-b" })
  );
  const payload = {
    actorId: BACKEND_ACTOR, deviceId: binding.deviceId, familyId: first.family_id,
    rotation: 0, refreshToken: first.refresh_token
  };
  assert.equal((await refreshBackendFieldLongSession(request, payload, async () => "current")).status, 200);
  let checks = 0;
  const unavailable = async (): Promise<"unavailable"> => {
    checks += 1;
    return "unavailable";
  };
  const reused = await refreshBackendFieldLongSession(request, payload, unavailable);
  assert.equal(reused.status, 401);
  assert.equal(await code(reused), "refresh_token_reuse_detected");
  assert.equal(checks, 0);
  const state = storedState(BACKEND_ACTOR).backend_families ?? [];
  assert.ok(state.every((family) => family.revoke_reason === "refresh_token_reuse"));
  const otherRefresh = await refreshBackendFieldLongSession(request, {
    actorId: BACKEND_ACTOR, deviceId: other.device_id, familyId: other.family_id,
    rotation: 0, refreshToken: other.refresh_token
  }, unavailable);
  assert.equal(await code(otherRefresh), "field_session_revoked_reuse_detected");
});

test("F7 a legacy replay against an already revoked family answers like a Backend family", async () => {
  const first = await session(await tokenLogin("replay-device-a"));
  const rotated = await session(await post(refreshBody(first)));
  assert.equal(await code(await post(refreshBody(first))), "refresh_token_reuse_detected");
  // The family is revoked now: its own consumed token gets the reuse reason, state unchanged.
  const before = storedState(ACTOR_ID);
  const replay = await post(refreshBody(first));
  assert.equal(replay.status, 401);
  assert.equal(await code(replay), "field_session_revoked_reuse_detected");
  assert.equal(await code(await post(refreshBody(rotated))), "field_session_revoked_reuse_detected");
  assert.deepEqual(storedState(ACTOR_ID), before);

  // A family ended by logout answers a consumed-token replay with the generic code.
  const logoutFamily = await session(await tokenLogin("replay-device-b"));
  const logoutRotated = await session(await post(refreshBody(logoutFamily)));
  assert.equal((await post(refreshBody(logoutRotated), "DELETE")).status, 204);
  const afterLogout = await post(refreshBody(logoutFamily));
  assert.equal(afterLogout.status, 401);
  assert.equal(await code(afterLogout), "field_session_revoked");
});

test("F11 long_lived password login with the mode disabled is 503 before any Backend call", async () => {
  process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED = "false";
  const response = await post({
    grant_type: "password", email: "hardening@example.org", password: "hardening-password-1234",
    remember_me: true, device_id: "disabled-long-device", session_mode: "long_lived"
  });
  assert.equal(response.status, 503);
  assert.equal(await code(response), "field_long_lived_sessions_unavailable");
  assert.equal(backendCalls, 0);
});

test("F12 the enable switch accepts only true or false", () => {
  const base: NodeJS.ProcessEnv = {
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "900",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "unlimited",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "unlimited"
  };
  assert.equal(resolveFieldLongSessionConfig({ ...base }), null);
  assert.equal(resolveFieldLongSessionConfig({ ...base, WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "" }), null);
  assert.equal(resolveFieldLongSessionConfig({ ...base, WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "false" }), null);
  assert.notEqual(resolveFieldLongSessionConfig({ ...base, WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true" }), null);
  for (const typo of ["True", "TRUE", "1", "yes", "on", "False", "0"]) {
    assert.throws(
      () => resolveFieldLongSessionConfig({ ...base, WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: typo }),
      /WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED must be true or false/
    );
  }
});

test("F13 a consumed-token refresh-proof DELETE ends only that family", async () => {
  const first = await session(await tokenLogin("delete-consumed-a"));
  const other = await session(await tokenLogin("delete-consumed-b"));
  const rotated = await session(await post(refreshBody(first)));
  // Android's pending revocation after a lost refresh response sends the consumed token.
  const logout = await post(refreshBody(first), "DELETE");
  assert.equal(logout.status, 204);
  assert.equal(await authenticated(rotated.cookie), false);
  assert.equal(await authenticated(other.cookie), true);
  const state = storedState(ACTOR_ID);
  assert.deepEqual(
    state.families.map((family) => [family.device_id, family.revoke_reason]),
    [["delete-consumed-a", "logout"], ["delete-consumed-b", null]]
  );
  assert.equal(state.reuse_revoked_at_epoch_ms, undefined);
  assert.equal((await post(refreshBody(other))).status, 200);
});
