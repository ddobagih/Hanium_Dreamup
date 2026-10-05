// Owner decision 2 (2026-09-29) / S19-1 / S01-16: a Backend email login can opt into a
// device-bound rotating refresh family (`session_mode: "long_lived"`), accepted by every
// session-aware route like a v7 device session and ended by the explicit end events only.
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";

import {
  gatewayFieldLongSessionBinding,
  gatewaySessionAuthenticatedAtEpochMs,
  isGatewaySessionAuthorized
} from "../src/auth.js";
import type { GatewayFetch } from "../src/backend.js";
import { fieldSessionErrorContractViolation } from "../src/field-session-errors.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const BACKEND_ACTOR = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3021";
let stateDirectory = "";
let keyringPath = "";
let ipSuffix = 10;
let accountCurrent = true;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-backend-email-long-"));
  keyringPath = `${stateDirectory}-state-keyring.json`;
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "backend-email-long-internal-token-123456",
    WALKSAFE_FIELD_ACCOUNTS_JSON: "[]",
    WALKSAFE_GATEWAY_SESSION_SECRET: "backend-email-long-session-secret-1234567890123456",
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
  accountCurrent = true;
  process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED = "true";
  await rm(stateDirectory, { recursive: true, force: true });
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
  await rm(keyringPath, { force: true });
});

const backendFetch: GatewayFetch = async (input) => {
  const pathname = new URL(String(input)).pathname;
  if (pathname === "/accounts/authenticate") {
    return Response.json({
      schema_version: "walksafe.account-authentication.v1",
      actor_id: BACKEND_ACTOR,
      account_generation: 1,
      auth_epoch: 1
    });
  }
  if (pathname === "/accounts/session-current") {
    return new Response(null, { status: accountCurrent ? 204 : 401 });
  }
  return Response.json({ detail: { code: "unexpected_test_path" } }, { status: 500 });
};

type LongSession = {
  actor_id: string;
  account_generation: number;
  device_id: string;
  family_id: string;
  rotation: number;
  refresh_token: string;
  access_expires_at_epoch_ms: number;
  idle_expires_at_epoch_ms: number | null;
  absolute_expires_at_epoch_ms: number | null;
  cookie: string;
};

function nextClientIp(): string {
  ipSuffix += 1;
  return `198.51.100.${ipSuffix % 250}`;
}

function post(body: Record<string, unknown>): Promise<Response> {
  return handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": nextClientIp() },
    body: JSON.stringify(body)
  }), { fetchImpl: backendFetch });
}

function passwordLogin(extra: Record<string, unknown> = {}): Promise<Response> {
  return post({
    grant_type: "password",
    email: "long@example.org",
    password: "long-lived-password-1234",
    remember_me: true,
    device_id: "email-long-device-a",
    session_mode: "long_lived",
    ...extra
  });
}

async function longSession(response: Response): Promise<LongSession> {
  assert.equal(response.status, 200);
  const setCookie = response.headers.get("set-cookie") ?? "";
  assert.match(setCookie, /^walksafe_field_session=v6\.[^;]+; Path=\/; HttpOnly; SameSite=Strict; Max-Age=60; Secure$/);
  const body = await response.json() as Omit<LongSession, "cookie">;
  assert.deepEqual(Object.keys(body).sort(), [
    "absolute_expires_at_epoch_ms", "access_expires_at_epoch_ms", "account_generation", "actor_id", "device_id", "family_id",
    "idle_expires_at_epoch_ms", "refresh_token", "rotation", "session_scope"
  ]);
  return { ...body, cookie: setCookie.split(";", 1)[0]! };
}

function refresh(session: LongSession): Promise<Response> {
  return post({
    grant_type: "refresh_token",
    actor_id: session.actor_id,
    device_id: session.device_id,
    family_id: session.family_id,
    rotation: session.rotation,
    refresh_token: session.refresh_token
  });
}

function withCookie(cookie: string, init: RequestInit & { query?: string } = {}): Request {
  const { query = "", ...rest } = init;
  return new Request(`https://gateway.invalid/api/field-session${query}`, {
    ...rest,
    headers: { ...(rest.headers as Record<string, string> | undefined), cookie }
  });
}

async function status(cookie: string): Promise<Response> {
  return handleGatewayRequest(withCookie(cookie), { fetchImpl: backendFetch });
}

async function expectCode(response: Response, status: number, code: string): Promise<void> {
  assert.equal(response.status, status);
  const body = await response.json() as Record<string, unknown>;
  assert.equal((body.detail as { code?: string } | undefined)?.code ?? body.code, code);
  assert.equal(fieldSessionErrorContractViolation(response.status, body), null);
}

test("decision 2 email login opts into an unlimited device-bound refresh family", async () => {
  const session = await longSession(await passwordLogin());
  assert.equal(session.actor_id, BACKEND_ACTOR);
  assert.equal(session.device_id, "email-long-device-a");
  assert.equal(session.rotation, 0);
  assert.equal(session.idle_expires_at_epoch_ms, null);
  assert.equal(session.absolute_expires_at_epoch_ms, null);

  const described = await status(session.cookie);
  assert.equal(described.status, 200);
  const body = await described.json() as Record<string, unknown>;
  assert.deepEqual(body, {
    required: true,
    authenticated: true,
    actor_id: BACKEND_ACTOR,
    account_generation: 1,
    session_scope: "general",
    session_mode: "long_lived",
    device_id: "email-long-device-a",
    family_id: session.family_id,
    rotation: 0,
    access_expires_at_epoch_ms: session.access_expires_at_epoch_ms,
    idle_expires_at_epoch_ms: null,
    absolute_expires_at_epoch_ms: null
  });

  // The cookie authorizes like a v7 device session; the walk key stays fixed across rotation.
  const request = withCookie(session.cookie);
  assert.equal(isGatewaySessionAuthorized(request), true);
  const binding = gatewayFieldLongSessionBinding(request);
  assert.deepEqual(binding, {
    actorId: BACKEND_ACTOR,
    accountId: `${BACKEND_ACTOR}:generation:1`,
    deviceId: "email-long-device-a",
    familyId: session.family_id,
    sessionRotation: 1,
    accountGeneration: 1
  });
  const authenticatedAt = gatewaySessionAuthenticatedAtEpochMs(request);
  assert.ok(authenticatedAt !== null && Date.now() - authenticatedAt < 60_000);

  const rotated = await longSession(await refresh(session));
  assert.equal(rotated.rotation, 1);
  assert.equal(rotated.account_generation, session.account_generation);
  assert.equal(rotated.family_id, session.family_id);
  assert.equal(rotated.absolute_expires_at_epoch_ms, null);
  assert.deepEqual(gatewayFieldLongSessionBinding(withCookie(rotated.cookie)), binding);
  // The replaced access cookie no longer authorizes.
  assert.equal(isGatewaySessionAuthorized(withCookie(session.cookie)), false);

  const devices = await handleGatewayRequest(
    withCookie(rotated.cookie, { query: "?devices=true" }), { fetchImpl: backendFetch }
  );
  assert.equal(devices.status, 200);
  const listed = await devices.json() as { actor_id: string; devices: Array<Record<string, unknown>> };
  assert.equal(listed.actor_id, BACKEND_ACTOR);
  assert.deepEqual(listed.devices.map((device) => [device.device_id, device.current, device.absolute_expires_at_epoch_ms]),
    [["email-long-device-a", true, null]]);
});

test("decision 2 email long login keeps the short v7 path without the opt-in and validates it", async () => {
  const short = await passwordLogin({ session_mode: undefined });
  assert.equal(short.status, 200);
  assert.deepEqual(await short.json(), { session_scope: "general" });
  assert.match(short.headers.get("set-cookie") ?? "", /^walksafe_field_session=v7\./);

  await expectCode(await passwordLogin({ remember_me: false }), 400, "field_session_request_invalid");
  await expectCode(await passwordLogin({ device_id: undefined }), 400, "field_session_request_invalid");
  await expectCode(await passwordLogin({ session_mode: "forever" }), 400, "field_session_request_invalid");
  process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED = "false";
  await expectCode(await passwordLogin(), 503, "field_long_lived_sessions_unavailable");
});

test("decision 2 email long login still ends on Backend denial, remote revoke and logout", async () => {
  const first = await longSession(await passwordLogin());
  const second = await longSession(await passwordLogin({ device_id: "email-long-device-b" }));

  // Account lock / auth-epoch change: the Backend denies the binding on every request.
  accountCurrent = false;
  await expectCode(await status(first.cookie), 401, "gateway_auth_required");
  await expectCode(await refresh(first), 401, "backend_account_binding_denied");
  accountCurrent = true;

  // Remote revoke of the other device (recent password login) ends only that device.
  const revoke = await handleGatewayRequest(
    withCookie(second.cookie, { method: "DELETE", query: "?device_id=email-long-device-a" }),
    { fetchImpl: backendFetch }
  );
  assert.equal(revoke.status, 204);
  assert.equal((await (await status(second.cookie)).json() as { authenticated: boolean }).authenticated, true);

  // Explicit logout of the current device revokes its family.
  const logout = await handleGatewayRequest(
    withCookie(second.cookie, { method: "DELETE" }), { fetchImpl: backendFetch }
  );
  assert.equal(logout.status, 204);
  assert.match(logout.headers.get("set-cookie") ?? "", /Max-Age=0/);
  assert.equal((await (await status(second.cookie)).json() as { authenticated: boolean }).authenticated, false);
  await expectCode(await refresh(second), 401, "field_session_revoked");
});

test("decision 1 reuse on an email long family logs out every device of the account, v7 included", async () => {
  const shortResponse = await passwordLogin({ session_mode: undefined, device_id: "email-short-device" });
  assert.equal(shortResponse.status, 200);
  const shortCookie = (shortResponse.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  const first = await longSession(await passwordLogin());
  const second = await longSession(await passwordLogin({ device_id: "email-long-device-b" }));
  const rotated = await longSession(await refresh(first));
  assert.equal((await (await status(shortCookie)).json() as { authenticated: boolean }).authenticated, true);

  await expectCode(await refresh(first), 401, "refresh_token_reuse_detected");
  for (const cookie of [rotated.cookie, second.cookie, shortCookie]) {
    assert.equal((await (await status(cookie)).json() as { authenticated: boolean }).authenticated, false);
  }
  await expectCode(await refresh(second), 401, "field_session_revoked_reuse_detected");
  await expectCode(await refresh(rotated), 401, "field_session_revoked_reuse_detected");

  // A new login works and a replay of the old token does not end it again (the revoked
  // family answers its token holder with the reuse reason before any state change).
  const relogged = await longSession(await passwordLogin({ device_id: "email-long-device-b" }));
  await expectCode(await refresh(first), 401, "field_session_revoked_reuse_detected");
  assert.equal((await (await status(relogged.cookie)).json() as { authenticated: boolean }).authenticated, true);
});
