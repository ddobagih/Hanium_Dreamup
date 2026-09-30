import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, mock, test } from "node:test";

import type { GatewayFetch } from "../src/backend.js";
import { setFieldLongSessionStorageFaultForTests } from "../src/field-long-session.js";
import {
  acceptOrReplayAccountDeletionV2,
  ACCOUNT_DELETION_INVENTORY_V2,
  DELETION_ACCESS_SECRET_HEADER,
  privacyDeletionV2LedgerPathForTests,
  type AccountDeletionStatusV2
} from "../src/privacy-deletion-v2.js";
import {
  ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS,
  handleGatewayRequest
} from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

// S19-44: only the first acceptance of a new account deletion needs a recent credential
// check; replay, status and device evidence keep working with the capability alone.
const ACTOR = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3044";
const OTHER_ACTOR = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3045";
const CAPABILITY = Buffer.alloc(32, 0x5b).toString("base64url");
const OTHER_CAPABILITY = Buffer.alloc(32, 0x5c).toString("base64url");
const REQUEST_ID = "deletion_reauth_request_01";
const OTHER_REQUEST_ID = "deletion_reauth_request_02";
const TOMBSTONE_ID = "5bde8fea-6919-4755-8fa9-f324299ba344";
const START_MS = Date.parse("2026-09-28T00:00:00.000Z");
let stateDirectory = "";
let clientIp = 60;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-deletion-reauth-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: "deletion-reauth-internal-token-1234567890123",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: "field-reauth-operator", token: "deletion-reauth-actor-token-1234567890" }
    ]),
    WALKSAFE_GATEWAY_SESSION_SECRET:
      "deletion-reauth-session-secret-1234567890123456789012345",
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

function statusFixture(requestId: string): AccountDeletionStatusV2 {
  const acceptedAt = new Date(START_MS).toISOString();
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: requestId,
    client_revision: 1,
    revision: 1,
    accepted_at: acceptedAt,
    updated_at: acceptedAt,
    account_generation: 1,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: "a".repeat(64),
    overall_status: "PARTIAL",
    items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => {
      const external = [
        "device_untransmitted_data",
        "training_datasets",
        "training_labels",
        "derived_artifacts",
        "backups"
      ].includes(definition.key);
      return {
        key: definition.key,
        status: external ? "EXTERNAL_PENDING" as const : "PENDING" as const,
        item_revision: 1,
        due_at: new Date(START_MS + definition.dueAfterMs).toISOString(),
        updated_at: acceptedAt,
        evidence_sha256: null,
        disposition_basis: null,
        retry_after: null,
        restriction_reason: null,
        legal_hold_review_at: null,
        legal_hold_contact: null,
        terminal_at: null
      };
    }),
    completion_receipt_sha256: null
  };
}

async function passwordLogin(actorId: string, deviceId: string): Promise<string> {
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
        email: "deletion-reauth@example.org",
        password: "deletion-reauth-password-must-not-leak",
        remember_me: true,
        device_id: deviceId
      })
    }),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.account-authentication.v1",
        actor_id: actorId,
        account_generation: 1,
        auth_epoch: 1
      })
    }
  );
  assert.equal(response.status, 200);
  return (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
}

function deletionBody(requestId: string): string {
  return JSON.stringify({
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: requestId,
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT"
  });
}

function deletionRequest(requestId: string, capability: string, cookie?: string): Request {
  return new Request("http://127.0.0.1:8081/privacy/account-deletions", {
    method: "POST",
    headers: {
      ...(cookie ? { cookie } : {}),
      "content-type": "application/json",
      [DELETION_ACCESS_SECRET_HEADER]: capability
    },
    body: deletionBody(requestId)
  });
}

function backendFor(requestId: string, calls: string[]): GatewayFetch {
  return async (input, init) => {
    const url = String(input);
    calls.push(`${init?.method ?? "GET"} ${new URL(url).pathname}`);
    if (url.endsWith("/privacy/account-deletions")) {
      return Response.json(statusFixture(requestId), { status: 202 });
    }
    if (url.endsWith(`/${requestId}/status`)) return Response.json(statusFixture(requestId));
    return Response.json({ detail: { code: "unexpected" } }, { status: 500 });
  };
}

test("S19-44 new deletion needs a login within the window; replay and status do not", async () => {
  mock.timers.enable({ apis: ["Date"], now: START_MS });
  const staleCookie = await passwordLogin(ACTOR, "reauth-device-a");
  mock.timers.tick(ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS + 60 * 1000);
  const calls: string[] = [];
  const ledgerBefore = await readFile(privacyDeletionV2LedgerPathForTests(), "utf8")
    .catch(() => null);

  const stale = await handleGatewayRequest(
    deletionRequest(REQUEST_ID, CAPABILITY, staleCookie),
    { fetchImpl: backendFor(REQUEST_ID, calls) }
  );
  assert.equal(stale.status, 401);
  assert.equal(stale.headers.get("cache-control"), "no-store");
  const staleBody = await stale.json() as Record<string, unknown>;
  assert.equal(staleBody.code, "account_deletion_reauthentication_required");
  assert.equal(staleBody.reauthentication_required, true);
  assert.deepEqual(calls, [], "no Backend call before the recent credential check");
  assert.equal(
    await readFile(privacyDeletionV2LedgerPathForTests(), "utf8").catch(() => null),
    ledgerBefore,
    "the rejected request is not recorded"
  );

  // Re-entering the password mints a fresh session; the same request id is then accepted.
  const freshCookie = await passwordLogin(ACTOR, "reauth-device-a");
  const accepted = await handleGatewayRequest(
    deletionRequest(REQUEST_ID, CAPABILITY, freshCookie),
    { fetchImpl: backendFor(REQUEST_ID, calls) }
  );
  assert.equal(accepted.status, 202);
  assert.deepEqual(calls, ["POST /privacy/account-deletions"]);

  // Long after the window: exact replay and status keep using the capability only.
  mock.timers.tick(24 * 60 * 60 * 1000);
  const replay = await handleGatewayRequest(
    deletionRequest(REQUEST_ID, CAPABILITY),
    { fetchImpl: backendFor(REQUEST_ID, calls) }
  );
  assert.equal(replay.status, 200);
  const status = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/status`,
      { headers: { [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY } }
    ),
    { fetchImpl: backendFor(REQUEST_ID, calls) }
  );
  assert.equal(status.status, 200);
});

test("S19-44 a login inside the window is accepted at the boundary", async () => {
  mock.timers.enable({ apis: ["Date"], now: START_MS + 10 * 24 * 60 * 60 * 1000 });
  const cookie = await passwordLogin(OTHER_ACTOR, "reauth-device-b");
  mock.timers.tick(ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS - 1000);
  const calls: string[] = [];
  const accepted = await handleGatewayRequest(
    deletionRequest(OTHER_REQUEST_ID, OTHER_CAPABILITY, cookie),
    { fetchImpl: backendFor(OTHER_REQUEST_ID, calls) }
  );
  assert.equal(accepted.status, 202);
});

test("S19-44 ledger gate blocks only creation and never an existing request", () => {
  const input = {
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: "deletion_reauth_request_03",
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT"
  } as const;
  const capability = Buffer.alloc(32, 0x5d).toString("base64url");
  const actor = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3046";
  assert.deepEqual(
    acceptOrReplayAccountDeletionV2(actor, 1, input, capability, Date.now(), false),
    { kind: "reauthentication_required" }
  );
  const accepted = acceptOrReplayAccountDeletionV2(actor, 1, input, capability, Date.now(), true);
  assert.equal(accepted.kind, "accepted");
  const replay = acceptOrReplayAccountDeletionV2(null, null, input, capability, Date.now(), false);
  assert.equal(replay.kind, "replay");
});

test("GWBE-10 a failed auth-time read is not recent: new request 401, capability replay unaffected", async () => {
  const LONG_ENV = {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "600",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "3600",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "86400"
  };
  const previous = Object.fromEntries(Object.keys(LONG_ENV).map((key) => [key, process.env[key]]));
  Object.assign(process.env, LONG_ENV);
  try {
    mock.timers.enable({ apis: ["Date"], now: START_MS + 20 * 24 * 60 * 60 * 1000 });
    clientIp += 1;
    const login = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": `198.51.100.${clientIp}` },
      body: JSON.stringify({
        actor_id: "field-reauth-operator",
        token: "deletion-reauth-actor-token-1234567890",
        device_id: "reauth-long-device"
      })
    }));
    assert.equal(login.status, 200);
    const cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
    assert.match(cookie, /=v4\./, "a v4 long-session cookie reads the family for its auth time");
    const requestId = "deletion_reauth_request_10";
    const capability = Buffer.alloc(32, 0x5e).toString("base64url");
    const calls: string[] = [];
    const ledgerBefore = await readFile(privacyDeletionV2LedgerPathForTests(), "utf8")
      .catch(() => null);

    // New acceptance while the auth-time read fails: not recent -> 401, nothing recorded.
    setFieldLongSessionStorageFaultForTests("auth_time_read");
    const rejected = await handleGatewayRequest(
      deletionRequest(requestId, capability, cookie),
      { fetchImpl: backendFor(requestId, calls) }
    );
    assert.equal(rejected.status, 401);
    assert.equal((await rejected.json() as { code: string }).code,
      "account_deletion_reauthentication_required");
    assert.deepEqual(calls, []);
    assert.equal(
      await readFile(privacyDeletionV2LedgerPathForTests(), "utf8").catch(() => null),
      ledgerBefore
    );

    // The same fresh session is accepted once the read works; then a replay that still
    // carries the cookie while the auth-time read fails keeps working with the capability.
    const accepted = await handleGatewayRequest(
      deletionRequest(requestId, capability, cookie),
      { fetchImpl: backendFor(requestId, calls) }
    );
    assert.equal(accepted.status, 202);
    setFieldLongSessionStorageFaultForTests("auth_time_read");
    const replay = await handleGatewayRequest(
      deletionRequest(requestId, capability, cookie),
      { fetchImpl: backendFor(requestId, calls) }
    );
    assert.equal(replay.status, 200);
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});
