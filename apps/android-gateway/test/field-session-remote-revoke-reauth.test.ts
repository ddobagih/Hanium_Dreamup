// S19-4-F1 (FP-011): revoking another device's session is a sensitive account change and
// needs the same recent credential login window as a new account deletion (S19-44).
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, mock, test } from "node:test";
import type { GatewayFetch } from "../src/backend.js";
import { setFieldLongSessionStorageFaultForTests } from "../src/field-long-session.js";
import { fieldSessionErrorContractViolation } from "../src/field-session-errors.js";
import {
  ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS,
  handleGatewayRequest
} from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3104";
const START_MS = Date.parse("2026-09-29T00:00:00.000Z");
let stateDirectory = "";
let clientIp = 20;
before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-remote-revoke-reauth-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "remote-revoke-reauth-internal-token-12345678",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: "revoke-reauth-operator", token: "remote-revoke-reauth-actor-token-123456" }
    ]),
    WALKSAFE_GATEWAY_SESSION_SECRET:
      "remote-revoke-reauth-session-secret-12345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});
afterEach(() => {
  mock.timers.reset();
  setFieldLongSessionStorageFaultForTests(null);
});
after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

const backend: GatewayFetch = async (input) => {
  if (String(input).endsWith("/accounts/session-current")) {
    return new Response(null, { status: 204 });
  }
  return Response.json({
    schema_version: "walksafe.account-authentication.v1",
    actor_id: ACTOR,
    account_generation: 1,
    auth_epoch: 1
  });
};

async function passwordLogin(deviceId: string): Promise<string> {
  clientIp += 1;
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": `198.51.100.${clientIp}`
      },
      body: JSON.stringify({
        grant_type: "password",
        email: "remote-revoke@example.org",
        password: "remote-revoke-password-must-not-leak",
        remember_me: true,
        device_id: deviceId
      })
    }),
    { fetchImpl: backend }
  );
  assert.equal(response.status, 200);
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /=v7\./);
  return cookie;
}

function revoke(cookie: string, deviceId: string): Promise<Response> {
  return handleGatewayRequest(
    new Request(`http://127.0.0.1:8081/api/field-session?device_id=${deviceId}`, {
      method: "DELETE",
      headers: { cookie }
    }),
    { fetchImpl: backend }
  );
}

async function authenticated(cookie: string): Promise<boolean> {
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", { headers: { cookie } }),
    { fetchImpl: backend }
  );
  return (await response.json() as { authenticated: boolean }).authenticated;
}

test("S19-4-F1 revoking another device outside the login window is refused with 401 and revokes nothing", async () => {
  mock.timers.enable({ apis: ["Date"], now: START_MS });
  const current = await passwordLogin("revoke-device-a");
  const other = await passwordLogin("revoke-device-b");
  mock.timers.tick(ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS + 60 * 1000);
  const refused = await revoke(current, "revoke-device-b");
  assert.equal(refused.status, 401);
  assert.equal(refused.headers.get("cache-control"), "no-store");
  assert.equal(refused.headers.get("set-cookie"), null);
  const body = await refused.json() as Record<string, unknown>;
  assert.equal(body.code, "field_session_reauthentication_required");
  assert.equal(body.reauthentication_required, true);
  assert.equal(fieldSessionErrorContractViolation(refused.status, body), null);
  assert.equal(await authenticated(other), true, "the other device keeps its session");
  assert.equal(await authenticated(current), true, "the refusal does not log the requester out");
  // A fresh password login on the requesting device opens the window again.
  const fresh = await passwordLogin("revoke-device-a");
  const revoked = await revoke(fresh, "revoke-device-b");
  assert.equal(revoked.status, 204);
  assert.equal(await authenticated(other), false);
});

test("S19-4-F1 a login inside the window revokes another device at the boundary", async () => {
  mock.timers.enable({ apis: ["Date"], now: START_MS + 24 * 60 * 60 * 1000 });
  const current = await passwordLogin("revoke-device-c");
  const other = await passwordLogin("revoke-device-d");
  mock.timers.tick(ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS - 1000);
  const revoked = await revoke(current, "revoke-device-d");
  assert.equal(revoked.status, 204);
  assert.equal(await authenticated(other), false);
  assert.equal(await authenticated(current), true);
});

test("S19-4-F1 the current device's own logout and self revoke are not gated", async () => {
  mock.timers.enable({ apis: ["Date"], now: START_MS + 2 * 24 * 60 * 60 * 1000 });
  const self = await passwordLogin("revoke-device-e");
  const plain = await passwordLogin("revoke-device-f");
  mock.timers.tick(ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS + 60 * 60 * 1000);
  const selfRevoke = await revoke(self, "revoke-device-e");
  assert.equal(selfRevoke.status, 204);
  assert.match(selfRevoke.headers.get("set-cookie") ?? "", /Max-Age=0/);
  assert.equal(await authenticated(self), false);
  const logout = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "DELETE",
      headers: { cookie: plain }
    }),
    { fetchImpl: backend }
  );
  assert.equal(logout.status, 204);
  assert.equal(await authenticated(plain), false);
});

test("S19-4-F1 a failed auth-time read of a v4 long session never revokes (fail closed)", async () => {
  const LONG_ENV = {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "600",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "3600",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "86400"
  };
  const previous = Object.fromEntries(Object.keys(LONG_ENV).map((key) => [key, process.env[key]]));
  Object.assign(process.env, LONG_ENV);
  try {
    mock.timers.enable({ apis: ["Date"], now: START_MS + 3 * 24 * 60 * 60 * 1000 });
    const login = async (deviceId: string): Promise<string> => {
      clientIp += 1;
      const response = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/field-session", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": `198.51.100.${clientIp}` },
        body: JSON.stringify({
          actor_id: "revoke-reauth-operator",
          token: "remote-revoke-reauth-actor-token-123456",
          device_id: deviceId
        })
      }));
      assert.equal(response.status, 200);
      const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
      assert.match(cookie, /=v4\./);
      return cookie;
    };
    const current = await login("revoke-long-device-a");
    const other = await login("revoke-long-device-b");
    setFieldLongSessionStorageFaultForTests("auth_time_read");
    const failed = await revoke(current, "revoke-long-device-b");
    // Same rule as account deletion (GWBE-10): an unreadable auth time is not recent. A
    // long-session storage outage itself (FieldLongSessionStorageUnavailableError) is
    // rethrown and answered as the registered 503 storage failure instead.
    assert.equal(failed.status, 401);
    const failedBody = await failed.json() as Record<string, unknown>;
    assert.equal(failedBody.code, "field_session_reauthentication_required");
    assert.equal(fieldSessionErrorContractViolation(failed.status, failedBody), null);
    setFieldLongSessionStorageFaultForTests(null);
    assert.equal(await authenticated(other), true);
    // Inside the window the v4 family creation time is recent: the revoke proceeds.
    const revoked = await revoke(current, "revoke-long-device-b");
    assert.equal(revoked.status, 204);
    assert.equal(await authenticated(other), false);
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});
