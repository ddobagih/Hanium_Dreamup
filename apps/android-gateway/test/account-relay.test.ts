import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { mkdtemp, rm } from "node:fs/promises";

import {
  ACCOUNT_UPSTREAM_RESPONSE_LIMIT_BYTES,
  SIGNUP_CONSENT_DOCUMENT_KEYS
} from "../src/account-relay.js";
import {
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ID_HEADER,
  FIELD_TEST_TOKEN_HEADER,
  type GatewayFetch
} from "../src/backend.js";
import { withExclusiveFileLockAsync } from "../src/exclusive-file-lock.js";
import { handleGatewayRequest } from "../src/routes.js";
import {
  clearGatewaySession,
  establishBackendGatewaySession,
  gatewaySessionStatus,
  revokeBackendDeviceGatewaySession,
  revokeFieldSessionsForSecurityEvent,
  SHORT_DEVICE_SESSION_LIMIT,
  SHORT_SESSION_MAX_PLAINTEXT_BYTES,
  validShortSessionStateForMaintenance
} from "../src/auth.js";
import {
  ACCOUNT_DELETION_REQUEST_SCHEMA,
  acceptAccountDeletionRequest
} from "../src/privacy-rights.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";

const INTERNAL_TOKEN = "account-relay-internal-token-12345678901234567890";
const SESSION_SECRET = "account-relay-session-secret-123456789012345678901234567890";
const LEGACY_ACTOR = "legacy-account-relay-actor";
const LEGACY_TOKEN = "legacy-account-relay-token-12345678901234567890";
const ENROLLMENT_HANDLE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
const BACKEND_ACCOUNT_CLIENT_IP_HEADER = "x-walksafe-client-ip";

let stateDirectory = "";
let ipSuffix = 10;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-account-relay-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function nextClientIp(): string {
  ipSuffix += 1;
  return `203.0.113.${ipSuffix}`;
}

async function holdExclusiveLock(lockPath: string): Promise<() => Promise<void>> {
  let release!: () => void;
  let signalAcquired!: () => void;
  let signalFailure!: (error: unknown) => void;
  const acquired = new Promise<void>((resolve, reject) => {
    signalAcquired = resolve;
    signalFailure = reject;
  });
  const holding = withExclusiveFileLockAsync(lockPath, async () => {
    signalAcquired();
    await new Promise<void>((resolve) => {
      release = resolve;
    });
  });
  void holding.catch(signalFailure);
  await acquired;
  return async () => {
    release();
    await holding;
  };
}

function enrollmentBody(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schema_version: "walksafe.account-enrollment-email-otp.v1",
    email: "person@example.org",
    date_of_birth: "2000-02-29",
    request_id: "account_enrollment_request_0001",
    ...extra
  };
}

function consentBody(): Record<string, unknown> {
  return {
    schema_version: "walksafe.signup-consent.v1",
    document_versions: Object.fromEntries(
      SIGNUP_CONSENT_DOCUMENT_KEYS.map(key => [key, `${key}.v1`])
    ),
    selections: Object.fromEntries(
      SIGNUP_CONSENT_DOCUMENT_KEYS.map(key => [key, true])
    )
  };
}

function accountCreateBody(): Record<string, unknown> {
  return {
    schema_version: "walksafe.account-create.v1",
    enrollment_handle: ENROLLMENT_HANDLE,
    otp_code: "123456",
    password: "do-not-log-this-password",
    consent: consentBody()
  };
}

function postJson(pathname: string, body: unknown, headers: HeadersInit = {}): Request {
  return new Request(`https://gateway.invalid${pathname}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...Object.fromEntries(new Headers(headers)) },
    body: JSON.stringify(body)
  });
}

test("email OTP enrollment validates the body before service auth and relays only exact JSON", async () => {
  const previousToken = process.env.WALKSAFE_FIELD_TEST_TOKEN;
  delete process.env.WALKSAFE_FIELD_TEST_TOKEN;
  try {
    const invalid = await handleGatewayRequest(postJson(
      "/api/account-enrollments/email-otp",
      enrollmentBody({ extra: "not-allowed" })
    ));
    assert.equal(invalid.status, 422);
    assert.deepEqual(await invalid.json(), {
      detail: { code: "account_enrollment_request_invalid" }
    });

    const oversized = await handleGatewayRequest(new Request(
      "https://gateway.invalid/api/account-enrollments/email-otp",
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "content-length": String(4 * 1024 + 1)
        },
        body: "{}"
      }
    ));
    assert.equal(oversized.status, 413);
  } finally {
    process.env.WALKSAFE_FIELD_TEST_TOKEN = previousToken;
  }

  let calls = 0;
  const clientIp = nextClientIp();
  const events: unknown[] = [];
  const response = await handleGatewayRequest(
    postJson("/api/account-enrollments/email-otp", enrollmentBody(), {
      "cf-connecting-ip": clientIp,
      "x-real-ip": "198.51.100.45",
      [BACKEND_ACCOUNT_CLIENT_IP_HEADER]: "198.51.100.46"
    }),
    {
      fetchImpl: async (input, init) => {
        calls += 1;
        assert.equal(String(input), "http://127.0.0.1:8000/account-enrollments/email-otp");
        assert.equal(init?.method, "POST");
        assert.equal(init?.redirect, "error");
        const headers = new Headers(init?.headers);
        assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
        assert.equal(headers.get(BACKEND_ACCOUNT_CLIENT_IP_HEADER), clientIp);
        assert.equal(headers.get("cookie"), null);
        assert.deepEqual(JSON.parse(String(init?.body)), enrollmentBody());
        return Response.json({
          schema_version: "walksafe.account-enrollment-email-otp-response.v1",
          enrollment_handle: ENROLLMENT_HANDLE,
          expires_at: "2026-08-29T12:10:00Z",
          resend_available_at: "2026-08-29T12:01:00Z"
        }, {
          status: 202,
          headers: {
            "set-cookie": "backend-secret=must-not-leak",
            [FIELD_TEST_TOKEN_HEADER]: "must-not-leak"
          }
        });
      },
      telemetrySink: event => events.push(event)
    }
  );
  assert.equal(calls, 1);
  assert.equal(response.status, 202);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("set-cookie"), null);
  assert.equal(response.headers.get(FIELD_TEST_TOKEN_HEADER), null);
  assert.deepEqual(await response.json(), {
    schema_version: "walksafe.account-enrollment-email-otp-response.v1",
    enrollment_handle: ENROLLMENT_HANDLE,
    expires_at: "2026-08-29T12:10:00Z",
    resend_available_at: "2026-08-29T12:01:00Z"
  });
  assert.doesNotMatch(
    JSON.stringify(events),
    new RegExp(`${clientIp.replaceAll(".", "\\.")}|person@example\\.org|198\\.51\\.100\\.4[56]`, "i")
  );
});

test("email OTP enrollment requires one trusted canonical client IP before Backend fetch", async () => {
  let calls = 0;
  const fetchImpl: GatewayFetch = async () => {
    calls += 1;
    return Response.json({});
  };
  const previousHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER;
  try {
    delete process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER;
    const unconfigured = await handleGatewayRequest(
      postJson("/api/account-enrollments/email-otp", enrollmentBody(), {
        [BACKEND_ACCOUNT_CLIENT_IP_HEADER]: "203.0.113.240"
      }),
      { fetchImpl }
    );
    assert.equal(unconfigured.status, 400);
    assert.deepEqual(await unconfigured.json(), {
      code: "gateway_trusted_client_ip_required",
      message: "신뢰할 수 있는 접속 주소를 확인할 수 없습니다."
    });

    process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER = "cf-connecting-ip";
    for (const headers of [
      { [BACKEND_ACCOUNT_CLIENT_IP_HEADER]: "203.0.113.241" },
      { "cf-connecting-ip": "203.0.113.1, 203.0.113.2" },
      { "cf-connecting-ip": "not-an-ip" }
    ]) {
      const rejected = await handleGatewayRequest(
        postJson("/api/account-enrollments/email-otp", enrollmentBody(), headers),
        { fetchImpl }
      );
      assert.equal(rejected.status, 400);
      assert.equal((await rejected.json() as { code: string }).code, "gateway_trusted_client_ip_required");
    }
    assert.equal(calls, 0);

    process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER = "x-real-ip";
    const relayed = await handleGatewayRequest(
      postJson("/api/account-enrollments/email-otp", enrollmentBody(), {
        "cf-connecting-ip": "198.51.100.90",
        "x-real-ip": "2001:0DB8:0:0:0:0:0:1",
        [BACKEND_ACCOUNT_CLIENT_IP_HEADER]: "198.51.100.91"
      }),
      {
        fetchImpl: async (_input, init) => {
          calls += 1;
          const headers = new Headers(init?.headers);
          assert.equal(headers.get(BACKEND_ACCOUNT_CLIENT_IP_HEADER), "2001:db8::1");
          return Response.json({
            schema_version: "walksafe.account-enrollment-email-otp-response.v1",
            enrollment_handle: ENROLLMENT_HANDLE,
            expires_at: "2026-08-29T12:10:00Z",
            resend_available_at: "2026-08-29T12:01:00Z"
          }, { status: 202 });
        }
      }
    );
    assert.equal(relayed.status, 202);
    assert.equal(calls, 1);
  } finally {
    if (previousHeader === undefined) delete process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER;
    else process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER = previousHeader;
  }
});

test("account email relay sends only canonical ASCII IDNA domains", async () => {
  let calls = 0;
  const vectors = [
    ["Local.Name+tag@EXAMPLE.ORG", "Local.Name+tag@example.org"],
    ["person@faß.de", "person@xn--fa-hia.de"],
    ["person@ς.gr", "person@xn--3xa.gr"],
    ["person@example。com", "person@example.com"],
    ["person@xn--fa-hia.de", "person@xn--fa-hia.de"],
    ["person@☕.example", "person@xn--53h.example"]
  ] as const;
  for (const [inputEmail, canonicalEmail] of vectors) {
    const canonical = await handleGatewayRequest(
      postJson(
        "/api/account-enrollments/email-otp",
        enrollmentBody({ email: inputEmail }),
        { "cf-connecting-ip": nextClientIp() }
      ),
      {
        fetchImpl: async (_input, init) => {
          calls += 1;
          const payload = JSON.parse(String(init?.body)) as { email: string };
          assert.equal(payload.email, canonicalEmail);
          return Response.json({
            schema_version: "walksafe.account-enrollment-email-otp-response.v1",
            enrollment_handle: ENROLLMENT_HANDLE,
            expires_at: "2026-08-29T12:10:00Z",
            resend_available_at: "2026-08-29T12:01:00Z"
          }, { status: 202 });
        }
      }
    );
    assert.equal(canonical.status, 202);
  }

  const invalidEmails = [
    "사용자@example.org",
    "person@a\u200db.com",
    "person@xn--ab-m1t.com",
    "person@ab--cd.example",
    "person@-example.org"
  ];
  for (const email of invalidEmails) {
    const rejected = await handleGatewayRequest(
      postJson(
        "/api/account-enrollments/email-otp",
        enrollmentBody({ email }),
        { "cf-connecting-ip": nextClientIp() }
      ),
      { fetchImpl: async () => { calls += 1; return Response.json({}); } }
    );
    assert.equal(rejected.status, 422);
    const rejectedBody = await rejected.text();
    assert.deepEqual(JSON.parse(rejectedBody), {
      detail: { code: "account_enrollment_request_invalid" }
    });
    assert.equal(rejectedBody.includes(email), false);
  }

  const loginRejected = await handleGatewayRequest(
    postJson("/api/field-session", {
      grant_type: "password",
      email: invalidEmails[0],
      password: "long-enough-password",
      remember_me: false
    }, { "cf-connecting-ip": nextClientIp() }),
    { fetchImpl: async () => { calls += 1; return Response.json({}); } }
  );
  assert.equal(loginRejected.status, 422);
  assert.deepEqual(await loginRejected.json(), {
    detail: { code: "account_authentication_request_invalid" }
  });
  assert.equal(calls, vectors.length);
});

test("account creation requires the exact six consent keys and projects the success response", async () => {
  const invalidConsent = accountCreateBody();
  const consent = invalidConsent.consent as Record<string, unknown>;
  const selections = consent.selections as Record<string, unknown>;
  delete selections.training_reuse;
  let calls = 0;
  const rejected = await handleGatewayRequest(
    postJson("/api/accounts", invalidConsent),
    { fetchImpl: async () => { calls += 1; return Response.json({}); } }
  );
  assert.equal(rejected.status, 422);
  assert.equal(calls, 0);

  const events: unknown[] = [];
  const created = await handleGatewayRequest(
    postJson("/api/accounts", accountCreateBody()),
    {
      fetchImpl: async (input, init) => {
        assert.equal(String(input), "http://127.0.0.1:8000/accounts");
        assert.equal(new Headers(init?.headers).get(BACKEND_ACCOUNT_CLIENT_IP_HEADER), null);
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        assert.deepEqual(body, accountCreateBody());
        return Response.json({
          schema_version: "walksafe.account.v1",
          actor_id: "018f2b63-8fb8-4cc2-98a1-4a4fd27c3002",
          account_generation: 7,
          signup_receipt_sha256: "a".repeat(64)
        }, { status: 201 });
      },
      telemetrySink: event => events.push(event)
    }
  );
  assert.equal(created.status, 201);
  assert.deepEqual(await created.json(), {
    schema_version: "walksafe.account.v1",
    actor_id: "018f2b63-8fb8-4cc2-98a1-4a4fd27c3002",
    account_generation: 7,
    signup_receipt_sha256: "a".repeat(64)
  });
  assert.doesNotMatch(JSON.stringify(events), /123456|do-not-log-this-password/i);
});

test("account relays bound malformed and oversized upstream bodies and redact errors", async () => {
  const request = (): Request => postJson(
    "/api/account-enrollments/email-otp",
    enrollmentBody(),
    { "cf-connecting-ip": nextClientIp() }
  );
  const sensitive = await handleGatewayRequest(request(), {
    fetchImpl: async () => Response.json({
      detail: {
        code: "account_enrollment_conflict",
        message: "person@example.org do-not-log-this-password"
      }
    }, { status: 409 })
  });
  assert.equal(sensitive.status, 409);
  assert.deepEqual(await sensitive.json(), {
    detail: { code: "account_enrollment_conflict" }
  });

  const malformed = await handleGatewayRequest(request(), {
    fetchImpl: async () => new Response("{not-json", {
      status: 202,
      headers: { "content-type": "application/json" }
    })
  });
  assert.equal(malformed.status, 502);
  assert.deepEqual(await malformed.json(), {
    detail: { code: "gateway_account_upstream_invalid" }
  });

  const oversized = await handleGatewayRequest(request(), {
    fetchImpl: async () => new Response(
      JSON.stringify({ value: "x".repeat(ACCOUNT_UPSTREAM_RESPONSE_LIMIT_BYTES) }),
      { status: 202, headers: { "content-type": "application/json" } }
    )
  });
  assert.equal(oversized.status, 502);

  const timeout = await handleGatewayRequest(request(), {
    fetchImpl: async () => Response.json(
      { detail: { code: "gateway_upstream_timeout", message: "private" } },
      { status: 504 }
    )
  });
  assert.equal(timeout.status, 504);
  assert.deepEqual(await timeout.json(), {
    detail: { code: "gateway_upstream_timeout" }
  });
});

test("password login creates a v6 Backend account session without static accounts", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3003";
  const email = "backend-user@EXAMPLE.ORG";
  const canonicalEmail = "backend-user@example.org";
  const password = "backend-password-must-not-leak";
  const login = await handleGatewayRequest(
    postJson("/api/field-session", {
      grant_type: "password",
      email,
      password,
      remember_me: false
    }, { "cf-connecting-ip": nextClientIp() }),
    {
      fetchImpl: async (input, init) => {
        assert.equal(String(input), "http://127.0.0.1:8000/accounts/authenticate");
        const headers = new Headers(init?.headers);
        assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
        assert.equal(headers.get(BACKEND_ACCOUNT_CLIENT_IP_HEADER), null);
        assert.deepEqual(JSON.parse(String(init?.body)), {
          schema_version: "walksafe.account-authenticate.v1",
          email: canonicalEmail,
          password
        });
        return Response.json({
          schema_version: "walksafe.account-authentication.v1",
          actor_id: actorId,
          account_generation: 7,
          auth_epoch: 3
        });
      }
    }
  );
  assert.equal(login.status, 200);
  assert.deepEqual(await login.json(), { session_scope: "general" });
  const setCookie = login.headers.get("set-cookie") ?? "";
  assert.match(setCookie, /^walksafe_field_session=v6\.[^.]+\.7\.3\.general\./);
  assert.doesNotMatch(setCookie, /Max-Age=/i);
  assert.doesNotMatch(setCookie, /backend-user|password|example\.org/i);
  const cookie = setCookie.split(";", 1)[0]!;

  const walking = await handleGatewayRequest(
    postJson("/api/navigation/walking", {}, { cookie }),
    {
      fetchImpl: async (_input, init) => {
        const headers = new Headers(init?.headers);
        assert.equal(headers.get(ACTOR_ID_HEADER), actorId);
        assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "7");
        return Response.json({ ok: true });
      }
    }
  );
  assert.equal(walking.status, 200);

  const tamperedGeneration = cookie.replace(".7.3.general.", ".8.3.general.");
  const tampered = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      headers: { cookie: tamperedGeneration }
    }),
    { fetchImpl: async () => Response.json({}, { status: 503 }) }
  );
  assert.equal((await tampered.json() as { authenticated: boolean }).authenticated, false);

  const originalNow = Date.now;
  Date.now = () => originalNow() + 13 * 60 * 60 * 1000;
  try {
    const expired = await handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session", { headers: { cookie } }),
      { fetchImpl: async () => Response.json({}, { status: 503 }) }
    );
    assert.equal((await expired.json() as { authenticated: boolean }).authenticated, false);
  } finally {
    Date.now = originalNow;
  }

  const generationMismatch = await handleGatewayRequest(
    postJson("/api/field-session", {
      grant_type: "password",
      email,
      password,
      remember_me: true
    }, { "cf-connecting-ip": nextClientIp() }),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.account-authentication.v1",
        actor_id: actorId,
        account_generation: 8,
        auth_epoch: 4
      })
    }
  );
  assert.equal(generationMismatch.status, 409);
  assert.deepEqual(await generationMismatch.json(), {
    detail: {
      code: "account_generation_inactive",
      message: "The account generation is no longer active."
    }
  });
});

test("device-bound password login creates v7 and supports field-walk without promoting v6", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3010";
  const credentials = {
    grant_type: "password",
    email: "device-user@example.org",
    password: "device-password-must-not-leak",
    remember_me: false
  };
  const fetchImpl: GatewayFetch = async () => Response.json({
    schema_version: "walksafe.account-authentication.v1",
    actor_id: actorId,
    account_generation: 11,
    auth_epoch: 5
  });
  const invalidDevice = await handleGatewayRequest(
    postJson("/api/field-session", {
      ...credentials,
      device_id: " android-device-a"
    }, { "cf-connecting-ip": nextClientIp() }),
    { fetchImpl: async () => assert.fail("invalid device must be rejected before Backend fetch") }
  );
  assert.equal(invalidDevice.status, 400);
  const legacy = await handleGatewayRequest(
    postJson("/api/field-session", credentials, {
      "cf-connecting-ip": nextClientIp()
    }),
    { fetchImpl }
  );
  assert.equal(legacy.status, 200);
  const v6Cookie = (legacy.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(v6Cookie, /^walksafe_field_session=v6\./);
  const legacyWalk = await handleGatewayRequest(postJson(
    "/api/field-walk",
    {
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "v6-walk-request-0001",
      action: "start",
      walk_id: "v6-walk-0001"
    },
    { cookie: v6Cookie }
  ));
  assert.equal(legacyWalk.status, 401);

  const login = await handleGatewayRequest(
    postJson("/api/field-session", {
      ...credentials,
      device_id: "android-device-a"
    }, { "cf-connecting-ip": nextClientIp() }),
    { fetchImpl }
  );
  assert.equal(login.status, 200);
  const cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /^walksafe_field_session=v7\.[^.]+\.11\.5\.[^.]+\.general\./);

  const cookieValue = cookie.slice(cookie.indexOf("=") + 1);
  for (const [index, replacement] of [
    [1, Buffer.from("018f2b63-8fb8-4cc2-98a1-4a4fd27c3011").toString("base64url")],
    [2, "12"],
    [3, "6"],
    [4, Buffer.from("android-device-b").toString("base64url")],
    [7, "A".repeat(43)]
  ] as const) {
    const parts = cookieValue.split(".");
    parts[index] = replacement;
    const status = await handleGatewayRequest(new Request(
      "https://gateway.invalid/api/field-session",
      { headers: { cookie: `walksafe_field_session=${parts.join(".")}` } }
    ));
    assert.equal(
      (await status.json() as { authenticated: boolean }).authenticated,
      false
    );
  }

  const recordId = `field-${createHash("sha256")
    .update(`field\0${actorId}`)
    .digest("hex")}.json`;
  const statePath = path.join(stateDirectory, "sessions", recordId);
  const originalState = await readFile(statePath, "utf8");
  const stateContext = { kind: "short-session" as const, recordId };
  const state = decryptTestStateFile<Record<string, unknown>>(
    stateContext,
    originalState,
    SHORT_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(state.actorId, actorId);
  assert.ok(Array.isArray(state.sessions));
  const activeSession = (state.sessions as Array<Record<string, unknown>>)[0]!;
  for (const mismatchedState of [
    { ...state, actorId: "018f2b63-8fb8-4cc2-98a1-4a4fd27c3011" },
    { ...state, sessions: [{ ...activeSession, accountGeneration: 12 }] },
    { ...state, sessions: [{ ...activeSession, authEpoch: 6 }] },
    { ...state, sessions: [{ ...activeSession, deviceId: "android-device-b" }] },
    { ...state, sessions: [{ ...activeSession, sessionId: "A".repeat(43) }] },
    { ...state, extra: true }
  ]) {
    await writeFile(statePath, encryptTestStateFile(
      stateContext,
      mismatchedState,
      SHORT_SESSION_MAX_PLAINTEXT_BYTES
    ));
    const stateMismatch = await handleGatewayRequest(new Request(
      "https://gateway.invalid/api/field-session",
      { headers: { cookie } }
    ));
    assert.equal(
      (await stateMismatch.json() as { authenticated: boolean }).authenticated,
      false
    );
  }
  await writeFile(statePath, originalState);

  const start = await handleGatewayRequest(postJson(
    "/api/field-walk",
    {
      schema_version: "walksafe.field-walk-command.v1",
      request_id: "v7-walk-start-0001",
      action: "start",
      walk_id: "v7-walk-0001"
    },
    { cookie }
  ));
  assert.equal(start.status, 200);
  const active = await start.json() as {
    result: string;
    lease_id: string;
    fencing_token: number;
  };
  assert.equal(active.result, "ACQUIRED");

  const get = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-walk",
    { headers: { cookie } }
  ));
  assert.equal(get.status, 200);
  assert.equal((await get.json() as { held_by_current_device: boolean })
    .held_by_current_device, true);

  const leaseCommand = async (action: "renew" | "end", requestId: string) =>
    handleGatewayRequest(postJson(
      "/api/field-walk",
      {
        schema_version: "walksafe.field-walk-command.v1",
        request_id: requestId,
        action,
        walk_id: "v7-walk-0001",
        lease_id: active.lease_id,
        fencing_token: active.fencing_token
      },
      { cookie }
    ));
  const renewed = await leaseCommand("renew", "v7-walk-renew-0001");
  assert.equal((await renewed.json() as { result: string }).result, "RENEWED");
  const ended = await leaseCommand("end", "v7-walk-end-0001");
  assert.equal((await ended.json() as { result: string }).result, "ENDED");

  const otherDevice = await handleGatewayRequest(
    postJson("/api/field-session", {
      ...credentials,
      device_id: "android-device-b"
    }, { "cf-connecting-ip": nextClientIp() }),
    { fetchImpl }
  );
  assert.equal(otherDevice.status, 200);
  const retainedDevice = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-walk",
    { headers: { cookie } }
  ));
  assert.equal(retainedDevice.status, 200);
  const otherDeviceCookie = (otherDevice.headers.get("set-cookie") ?? "")
    .split(";", 1)[0]!;
  assert.equal(acceptAccountDeletionRequest(
    actorId,
    {
      schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA,
      request_id: "v7_tombstone_request_0001",
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    "f".repeat(64)
  ).kind, "accepted");
  const tombstoned = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-walk",
    { headers: { cookie: otherDeviceCookie } }
  ));
  assert.equal(tombstoned.status, 409);

  const otherGeneration = await handleGatewayRequest(
    postJson("/api/field-session", {
      ...credentials,
      device_id: "android-device-c"
    }, { "cf-connecting-ip": nextClientIp() }),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.account-authentication.v1",
        actor_id: actorId,
        account_generation: 12,
        auth_epoch: 6
      })
    }
  );
  assert.equal(otherGeneration.status, 409);
});

test("v7 device sessions coexist, replace only the same device, and selectively revoke", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3020";
  const credentials = {
    grant_type: "password",
    email: "multi-device-user@example.org",
    password: "multi-device-password-must-not-leak",
    remember_me: false
  };
  const fetchImpl: GatewayFetch = async () => Response.json({
    schema_version: "walksafe.account-authentication.v1",
    actor_id: actorId,
    account_generation: 21,
    auth_epoch: 9
  });
  const login = async (deviceId: string): Promise<string> => {
    const response = await handleGatewayRequest(
      postJson("/api/field-session", {
        ...credentials,
        device_id: deviceId
      }, { "cf-connecting-ip": nextClientIp() }),
      { fetchImpl }
    );
    assert.equal(response.status, 200);
    return (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  };
  const authenticated = (cookie: string): Promise<boolean> =>
    gatewaySessionStatus(new Request(
      "https://gateway.invalid/api/field-session",
      { headers: { cookie } }
    )).json().then((body) => (body as { authenticated: boolean }).authenticated);

  const firstDeviceA = await login("short-device-a");
  const deviceB = await login("short-device-b");
  assert.equal(await authenticated(firstDeviceA), true);
  assert.equal(await authenticated(deviceB), true);

  const currentDeviceA = await login("short-device-a");
  assert.equal(await authenticated(firstDeviceA), false);
  assert.equal(await authenticated(currentDeviceA), true);
  assert.equal(await authenticated(deviceB), true);

  const listed = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-session?devices=true",
    { headers: { cookie: currentDeviceA } }
  ));
  assert.equal(listed.status, 200);
  const listBody = await listed.json() as {
    actor_id: string;
    devices: Array<{
      device_id: string;
      family_id: string;
      current: boolean;
      rotation: number;
      created_at_epoch_ms: number;
      last_rotated_at_epoch_ms: number;
      idle_expires_at_epoch_ms: number;
      absolute_expires_at_epoch_ms: number;
    }>;
  };
  assert.equal(listBody.actor_id, actorId);
  assert.deepEqual(
    listBody.devices.map((device) => device.device_id),
    ["short-device-a", "short-device-b"]
  );
  const listedCurrent = listBody.devices.find(
    (device) => device.device_id === "short-device-a"
  )!;
  assert.equal(listedCurrent.current, true);
  assert.equal(listedCurrent.rotation, 0);
  assert.equal(
    listedCurrent.absolute_expires_at_epoch_ms - listedCurrent.created_at_epoch_ms,
    12 * 60 * 60 * 1000
  );
  assert.equal(
    listedCurrent.idle_expires_at_epoch_ms,
    listedCurrent.absolute_expires_at_epoch_ms
  );
  assert.equal(
    listedCurrent.last_rotated_at_epoch_ms,
    listedCurrent.created_at_epoch_ms
  );

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const revoked = await handleGatewayRequest(new Request(
      "https://gateway.invalid/api/field-session?device_id=short-device-b",
      { method: "DELETE", headers: { cookie: currentDeviceA } }
    ));
    assert.equal(revoked.status, 204);
    assert.equal(revoked.headers.get("set-cookie"), null);
  }
  assert.equal(await authenticated(currentDeviceA), true);
  assert.equal(await authenticated(deviceB), false);

  const selfRevoked = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-session?device_id=short-device-a",
    { method: "DELETE", headers: { cookie: currentDeviceA } }
  ));
  assert.equal(selfRevoked.status, 204);
  assert.match(selfRevoked.headers.get("set-cookie") ?? "", /Max-Age=0/);
  assert.equal(await authenticated(currentDeviceA), false);
});

test("v7 rejects generation or authentication epoch rollback without changing active devices", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3023";
  const request = new Request("https://gateway.invalid/api/field-session");
  const issue = (
    deviceId: string,
    accountGeneration: number,
    authEpoch: number
  ): Response => establishBackendGatewaySession(
    request,
    { actorId, accountGeneration, authEpoch, deviceId },
    false
  );
  const cookie = (response: Response): string =>
    (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  const authenticated = (value: string): Promise<boolean> =>
    gatewaySessionStatus(new Request(request.url, {
      headers: { cookie: value }
    })).json().then((body) => (body as { authenticated: boolean }).authenticated);
  const recordId = `field-${createHash("sha256")
    .update(`field\0${actorId}`)
    .digest("hex")}.json`;
  const statePath = path.join(stateDirectory, "sessions", recordId);

  const deviceA = issue("rollback-device-a", 51, 7);
  const deviceB = issue("rollback-device-b", 51, 7);
  assert.equal(deviceA.status, 200);
  assert.equal(deviceB.status, 200);
  const deviceACookie = cookie(deviceA);
  const deviceBCookie = cookie(deviceB);
  const beforeRollback = await readFile(statePath, "utf8");

  for (const stale of [
    issue("stale-generation-device", 50, 99),
    issue("stale-epoch-device", 51, 6)
  ]) {
    assert.equal(stale.status, 409);
    assert.deepEqual(await stale.json(), {
      detail: {
        code: "account_authentication_stale",
        message: "Authentication state changed while login was in progress. Retry login."
      }
    });
    assert.equal(await readFile(statePath, "utf8"), beforeRollback);
  }
  assert.equal(await authenticated(deviceACookie), true);
  assert.equal(await authenticated(deviceBCookie), true);

  const advanced = issue("advanced-epoch-device", 51, 8);
  assert.equal(advanced.status, 200);
  assert.equal(await authenticated(deviceACookie), false);
  assert.equal(await authenticated(deviceBCookie), false);
  assert.equal(await authenticated(cookie(advanced)), true);
});

test("v7 logout and last-device revoke retain a non-authenticating rollback watermark", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3025";
  const request = new Request("https://gateway.invalid/api/field-session");
  const issue = (deviceId: string, authEpoch: number): Response =>
    establishBackendGatewaySession(
      request,
      { actorId, accountGeneration: 71, authEpoch, deviceId },
      false
    );
  const cookie = (response: Response): string =>
    (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  const recordId = `field-${createHash("sha256")
    .update(`field\0${actorId}`)
    .digest("hex")}.json`;
  const statePath = path.join(stateDirectory, "sessions", recordId);
  const stateContext = { kind: "short-session" as const, recordId };
  const assertWatermark = async (authEpoch: number): Promise<void> => {
    const state = decryptTestStateFile<Record<string, unknown>>(
      stateContext,
      await readFile(statePath, "utf8"),
      SHORT_SESSION_MAX_PLAINTEXT_BYTES
    );
    assert.deepEqual(state, {
      actorId,
      accountGeneration: 71,
      authEpoch
    });
    assert.equal(validShortSessionStateForMaintenance(state, recordId), true);
  };
  const assertStale = async (response: Response): Promise<void> => {
    assert.equal(response.status, 409);
    assert.equal(
      (await response.json() as { detail: { code: string } }).detail.code,
      "account_authentication_stale"
    );
  };

  const loggedIn = issue("watermark-logout-device", 10);
  assert.equal(loggedIn.status, 200);
  const logout = clearGatewaySession(new Request(request.url, {
    method: "DELETE",
    headers: { cookie: cookie(loggedIn) }
  }));
  assert.equal(logout.status, 204);
  await assertWatermark(10);
  await assertStale(issue("delayed-after-logout", 9));

  const current = issue("watermark-revoke-device", 10);
  assert.equal(current.status, 200);
  const selfRevoked = revokeBackendDeviceGatewaySession(
    new Request(request.url, { headers: { cookie: cookie(current) } }),
    "watermark-revoke-device"
  );
  assert.equal(selfRevoked?.status, 204);
  await assertWatermark(10);
  await assertStale(issue("delayed-after-revoke", 9));

  const advanced = issue("watermark-advanced-device", 11);
  assert.equal(advanced.status, 200);
});

test("v7 device list and selective revoke preserve the long-session storage-busy contract", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3024";
  const response = await handleGatewayRequest(
    postJson("/api/field-session", {
      grant_type: "password",
      email: "busy-device-user@example.org",
      password: "busy-device-password-must-not-leak",
      remember_me: false,
      device_id: "busy-device"
    }, { "cf-connecting-ip": nextClientIp() }),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.account-authentication.v1",
        actor_id: actorId,
        account_generation: 61,
        auth_epoch: 3
      })
    }
  );
  assert.equal(response.status, 200);
  const sessionCookie = (response.headers.get("set-cookie") ?? "")
    .split(";", 1)[0]!;
  const recordId = `field-${createHash("sha256")
    .update(`field\0${actorId}`)
    .digest("hex")}.json`;
  const statePath = path.join(stateDirectory, "sessions", recordId);
  const before = await readFile(statePath, "utf8");
  const releaseLock = await holdExclusiveLock(`${statePath}.lock`);
  try {
    for (const request of [
      new Request("https://gateway.invalid/api/field-session?devices=true", {
        headers: { cookie: sessionCookie }
      }),
      new Request("https://gateway.invalid/api/field-session?device_id=busy-device", {
        method: "DELETE",
        headers: { cookie: sessionCookie }
      })
    ]) {
      const blocked = await handleGatewayRequest(request);
      assert.equal(blocked.status, 503);
      assert.equal(blocked.headers.get("cache-control"), "no-store");
      assert.equal(blocked.headers.get("retry-after"), "1");
      assert.deepEqual(await blocked.json(), {
        code: "field_session_storage_busy",
        message: "다른 현장 세션 갱신이 완료될 때까지 다시 시도해야 합니다.",
        retryable: true
      });
      assert.equal(await readFile(statePath, "utf8"), before);
    }
  } finally {
    await releaseLock();
  }
  assert.equal(
    (await gatewaySessionStatus(new Request(
      "https://gateway.invalid/api/field-session",
      { headers: { cookie: sessionCookie } }
    )).json() as { authenticated: boolean }).authenticated,
    true
  );
});

test("v7 safely migrates a singleton and generation or security fences clear every device", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3021";
  const request = new Request("https://gateway.invalid/api/field-session");
  const issue = (deviceId: string, accountGeneration = 31, authEpoch = 4): Response =>
    establishBackendGatewaySession(
      request,
      { actorId, accountGeneration, authEpoch, deviceId },
      false
    );
  const cookie = (response: Response): string =>
    (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  const authenticated = (value: string): Promise<boolean> =>
    gatewaySessionStatus(new Request(
      "https://gateway.invalid/api/field-session",
      { headers: { cookie: value } }
    )).json().then((body) => (body as { authenticated: boolean }).authenticated);
  const recordId = `field-${createHash("sha256")
    .update(`field\0${actorId}`)
    .digest("hex")}.json`;
  const statePath = path.join(stateDirectory, "sessions", recordId);
  const stateContext = { kind: "short-session" as const, recordId };

  const deviceAResponse = issue("migration-device-a");
  assert.equal(deviceAResponse.status, 200);
  const deviceA = cookie(deviceAResponse);
  const ledger = decryptTestStateFile<{
    actorId: string;
    sessions: Array<Record<string, unknown>>;
  }>(
    stateContext,
    await readFile(statePath, "utf8"),
    SHORT_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(ledger.sessions.length, 1);
  const legacySingleton = ledger.sessions[0]!;
  assert.equal(validShortSessionStateForMaintenance(legacySingleton, recordId), true);
  await writeFile(statePath, encryptTestStateFile(
    stateContext,
    legacySingleton,
    SHORT_SESSION_MAX_PLAINTEXT_BYTES
  ));
  assert.equal(await authenticated(deviceA), true);

  const deviceBResponse = issue("migration-device-b");
  assert.equal(deviceBResponse.status, 200);
  const deviceB = cookie(deviceBResponse);
  const migrated = decryptTestStateFile<{
    actorId: string;
    sessions: Array<Record<string, unknown>>;
  }>(
    stateContext,
    await readFile(statePath, "utf8"),
    SHORT_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(validShortSessionStateForMaintenance(migrated, recordId), true);
  assert.deepEqual(
    migrated.sessions.map((session) => session.deviceId),
    ["migration-device-a", "migration-device-b"]
  );
  assert.equal(await authenticated(deviceA), true);
  assert.equal(await authenticated(deviceB), true);

  const nextGenerationResponse = issue("generation-device", 32, 5);
  assert.equal(nextGenerationResponse.status, 200);
  const nextGeneration = cookie(nextGenerationResponse);
  assert.equal(await authenticated(deviceA), false);
  assert.equal(await authenticated(deviceB), false);
  assert.equal(await authenticated(nextGeneration), true);
  const fenced = decryptTestStateFile<{
    sessions: Array<Record<string, unknown>>;
  }>(
    stateContext,
    await readFile(statePath, "utf8"),
    SHORT_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.deepEqual(
    fenced.sessions.map((session) => session.deviceId),
    ["generation-device"]
  );

  await revokeFieldSessionsForSecurityEvent(actorId, "security_incident");
  assert.equal(await authenticated(nextGeneration), false);
});

test("v7 device capacity replaces the same device and reclaims exact-expiry sessions", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3022";
  const request = new Request("https://gateway.invalid/api/field-session");
  const originalNow = Date.now;
  const startedAtEpochMs = 2_000_000_000_000;
  Date.now = () => startedAtEpochMs;
  try {
    const cookies: string[] = [];
    for (let index = 0; index < SHORT_DEVICE_SESSION_LIMIT; index += 1) {
      const response = establishBackendGatewaySession(
        request,
        {
          actorId,
          accountGeneration: 41,
          authEpoch: 8,
          deviceId: `capacity-device-${index}`
        },
        false
      );
      assert.equal(response.status, 200);
      cookies.push((response.headers.get("set-cookie") ?? "").split(";", 1)[0]!);
    }
    const overflow = establishBackendGatewaySession(
      request,
      {
        actorId,
        accountGeneration: 41,
        authEpoch: 8,
        deviceId: "capacity-overflow"
      },
      false
    );
    assert.equal(overflow.status, 503);
    assert.deepEqual(await overflow.json(), {
      code: "field_session_device_capacity_unavailable"
    });

    const sameDevice = establishBackendGatewaySession(
      request,
      {
        actorId,
        accountGeneration: 41,
        authEpoch: 8,
        deviceId: "capacity-device-0"
      },
      false
    );
    assert.equal(sameDevice.status, 200);
    const replacementCookie = (sameDevice.headers.get("set-cookie") ?? "")
      .split(";", 1)[0]!;
    assert.equal(
      (await gatewaySessionStatus(new Request(request.url, {
        headers: { cookie: cookies[0]! }
      })).json() as { authenticated: boolean }).authenticated,
      false
    );
    assert.equal(
      (await gatewaySessionStatus(new Request(request.url, {
        headers: { cookie: replacementCookie }
      })).json() as { authenticated: boolean }).authenticated,
      true
    );

    Date.now = () => startedAtEpochMs + 12 * 60 * 60 * 1000;
    assert.equal(
      (await gatewaySessionStatus(new Request(request.url, {
        headers: { cookie: replacementCookie }
      })).json() as { authenticated: boolean }).authenticated,
      false
    );
    const reclaimed = establishBackendGatewaySession(
      request,
      {
        actorId,
        accountGeneration: 41,
        authEpoch: 8,
        deviceId: "capacity-after-expiry"
      },
      false
    );
    assert.equal(reclaimed.status, 200);
    const recordId = `field-${createHash("sha256")
      .update(`field\0${actorId}`)
      .digest("hex")}.json`;
    const state = decryptTestStateFile<{
      actorId: string;
      sessions: Array<Record<string, unknown>>;
    }>(
      { kind: "short-session", recordId },
      await readFile(path.join(stateDirectory, "sessions", recordId), "utf8"),
      SHORT_SESSION_MAX_PLAINTEXT_BYTES
    );
    assert.deepEqual(
      state.sessions.map((session) => session.deviceId),
      ["capacity-after-expiry"]
    );
  } finally {
    Date.now = originalNow;
  }
});

test("password failures are generic and legacy v5 login remains compatible", async () => {
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  const denied = await handleGatewayRequest(
    postJson("/api/field-session", {
      grant_type: "password",
      email: "unknown@example.org",
      password: "must-not-echo",
      remember_me: true
    }, { "cf-connecting-ip": nextClientIp() }),
    {
      fetchImpl: async () => Response.json({
        detail: {
          code: "account_not_found",
          message: "unknown@example.org must-not-echo"
        }
      }, { status: 401 })
    }
  );
  assert.equal(denied.status, 401);
  assert.deepEqual(await denied.json(), {
    detail: { code: "invalid_account_credentials" }
  });

  const limited = await handleGatewayRequest(
    postJson("/api/field-session", {
      grant_type: "password",
      email: "limited@EXAMPLE.ORG",
      password: "must-not-echo",
      remember_me: false
    }, { "cf-connecting-ip": nextClientIp() }),
    {
      fetchImpl: async (_input, init) => {
        assert.equal(
          (JSON.parse(String(init?.body)) as { email: string }).email,
          "limited@example.org"
        );
        return Response.json({
          detail: { code: "account_authentication_rate_limited" }
        }, { status: 429, headers: { "retry-after": "7" } });
      }
    }
  );
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get("cache-control"), "no-store");
  assert.equal(limited.headers.get("retry-after"), "7");
  assert.deepEqual(await limited.json(), {
    detail: { code: "account_authentication_rate_limited" }
  });

  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([{
    actor_id: LEGACY_ACTOR,
    token: LEGACY_TOKEN
  }]);
  try {
    const legacy = await handleGatewayRequest(postJson("/api/field-session", {
      actor_id: LEGACY_ACTOR,
      token: LEGACY_TOKEN
    }, { "cf-connecting-ip": nextClientIp() }));
    assert.equal(legacy.status, 200);
    assert.match(legacy.headers.get("set-cookie") ?? "", /^walksafe_field_session=v5\./);
  } finally {
    delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  }
});

test("auth remains Backend-agnostic and no auth/backend import cycle is introduced", async () => {
  const [authSource, backendSource] = await Promise.all([
    readFile(path.join(process.cwd(), "src/auth.ts"), "utf8"),
    readFile(path.join(process.cwd(), "src/backend.ts"), "utf8")
  ]);
  assert.doesNotMatch(authSource, /from\s+["']\.\/backend\.js["']/);
  assert.match(backendSource, /from\s+["']\.\/auth\.js["']/);
});
