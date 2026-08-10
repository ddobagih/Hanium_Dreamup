import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";

import { withExclusiveFileLockAsync } from "../src/exclusive-file-lock.js";
import {
  fieldLongSessionIdentity,
  refreshFieldLongSession,
  establishFieldLongSession,
  setFieldLongSessionStorageFaultForTests,
  type FieldRefreshPayload
} from "../src/field-long-session.js";
import {
  revokeFieldSessionsForSecurityEvent
} from "../src/auth.js";
import { resolveFieldLongSessionConfig } from "../src/config.js";
import { handleGatewayRequest } from "../src/routes.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile
} from "./state-encryption-fixture.js";

const ACTOR_ID = "field-operator";
const SECOND_ACTOR_ID = "second-operator";
const ACCOUNT_TOKEN = "field-account-token-12345678901234567890";
const SECOND_ACCOUNT_TOKEN = "second-account-token-1234567890123456";
const INTERNAL_TOKEN = "field-internal-token-12345678901234567890";
const SESSION_SECRET = "gateway-session-secret-123456789012345678901234567890";
const ACCOUNTS_JSON = JSON.stringify([
  { actor_id: ACTOR_ID, token: ACCOUNT_TOKEN },
  { actor_id: SECOND_ACTOR_ID, token: SECOND_ACCOUNT_TOKEN }
]);
const ACCESS_TTL_SECONDS = "60";
const IDLE_TTL_SECONDS = "300";
const ABSOLUTE_TTL_SECONDS = "3600";
const FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024;

type SessionBody = {
  session_scope: "general";
  actor_id: string;
  device_id: string;
  family_id: string;
  rotation: number;
  refresh_token: string;
  access_expires_at_epoch_ms: number;
  idle_expires_at_epoch_ms: number;
  absolute_expires_at_epoch_ms: number;
};

type Session = SessionBody & { cookie: string; accessToken: string };

let stateDirectory = "";
let keyringPath = "";
let ipSuffix = 20;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-field-long-session-test-"));
  keyringPath = `${stateDirectory}-state-keyring.json`;
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: ACCOUNTS_JSON,
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: ACCESS_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: IDLE_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: ABSOLUTE_TTL_SECONDS
  });
  await configureTestStateEncryption(keyringPath);
});

afterEach(async () => {
  setFieldLongSessionStorageFaultForTests(null);
  Object.assign(process.env, {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: ACCESS_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: IDLE_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: ABSOLUTE_TTL_SECONDS,
    WALKSAFE_FIELD_ACCOUNTS_JSON: ACCOUNTS_JSON
  });
  delete process.env.WALKSAFE_ADMIN_TOKEN;
  delete process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON;
  await rm(stateDirectory, { recursive: true, force: true });
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
  await rm(keyringPath, { force: true });
});

function nextClientIp(): string {
  ipSuffix += 1;
  return `198.51.100.${ipSuffix}`;
}

function actorStatePath(actorId = ACTOR_ID): string {
  const digest = createHash("sha256")
    .update(`field-long-session\0${actorId}`)
    .digest("hex");
  return path.join(stateDirectory, "field-long-sessions", `${digest}.json`);
}

function actorLockPath(actorId = ACTOR_ID): string {
  return `${actorStatePath(actorId)}.lock`;
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

const FIELD_SESSION_CHILD_WORKER = String.raw`
const [mode, lockModuleUrl, sessionModuleUrl, lockPath, payloadText] = process.argv.slice(1);
const { refreshFieldLongSession } = await import(sessionModuleUrl);
const payload = JSON.parse(payloadText);
const runRefresh = async () => {
  const response = await refreshFieldLongSession(
    new Request("https://gateway.invalid/api/field-session"),
    payload
  );
  process.stdout.write("RESULT " + response.status + "\n");
};
if (mode === "holder") {
  const { withExclusiveFileLockAsync } = await import(lockModuleUrl);
  await withExclusiveFileLockAsync(lockPath, async () => {
    process.stdout.write("LOCKED\n");
    await new Promise((resolve) => process.stdin.once("data", resolve));
  });
  await runRefresh();
} else {
  await runRefresh();
}
`;

function spawnFieldSessionWorker(
  mode: "holder" | "contender",
  lockPath: string,
  payload: FieldRefreshPayload
): ChildProcessWithoutNullStreams {
  return spawn(
    process.execPath,
    [
      "--input-type=module",
      "--eval",
      FIELD_SESSION_CHILD_WORKER,
      mode,
      new URL("../src/exclusive-file-lock.js", import.meta.url).href,
      new URL("../src/field-long-session.js", import.meta.url).href,
      lockPath,
      JSON.stringify(payload)
    ],
    { env: { ...process.env } }
  );
}

function waitForChildLine(
  child: ChildProcessWithoutNullStreams,
  prefix: string
): Promise<string> {
  return new Promise((resolve, reject) => {
    let buffered = "";
    let errorOutput = "";
    const cleanup = (): void => {
      child.stdout.off("data", onData);
      child.stderr.off("data", onErrorData);
      child.off("error", onError);
      child.off("close", onClose);
    };
    const onData = (chunk: Buffer): void => {
      buffered += chunk.toString("utf8");
      let newline = buffered.indexOf("\n");
      while (newline >= 0) {
        const line = buffered.slice(0, newline);
        buffered = buffered.slice(newline + 1);
        if (line.startsWith(prefix)) {
          cleanup();
          resolve(line);
          return;
        }
        newline = buffered.indexOf("\n");
      }
    };
    const onErrorData = (chunk: Buffer): void => {
      errorOutput += chunk.toString("utf8");
    };
    const onError = (error: Error): void => {
      cleanup();
      reject(error);
    };
    const onClose = (code: number | null): void => {
      cleanup();
      reject(new Error(`child exited with ${code}: ${errorOutput.trim()}`));
    };
    child.stdout.on("data", onData);
    child.stderr.on("data", onErrorData);
    child.once("error", onError);
    child.once("close", onClose);
  });
}

async function assertChildExitedSuccessfully(
  child: ChildProcessWithoutNullStreams
): Promise<void> {
  const code = child.exitCode ?? await new Promise<number | null>((resolve) => {
    child.once("close", resolve);
  });
  assert.equal(code, 0);
}

async function responseSession(response: Response): Promise<Session> {
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const setCookie = response.headers.get("set-cookie") ?? "";
  assert.match(
    setCookie,
    /^walksafe_field_session=v4\.[^;]+; Path=\/; HttpOnly; SameSite=Strict; Max-Age=60; Secure$/
  );
  const cookie = setCookie.split(";", 1)[0]!;
  const accessToken = cookie.slice(cookie.indexOf("=") + 1);
  const body = await response.json() as SessionBody;
  assert.equal(body.session_scope, "general");
  return { ...body, cookie, accessToken };
}

async function login(
  deviceId: string,
  actorId = ACTOR_ID,
  token = ACCOUNT_TOKEN
): Promise<Session> {
  return responseSession(await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextClientIp()
      },
      body: JSON.stringify({ actor_id: actorId, token, device_id: deviceId })
    })
  ));
}

function refreshPayload(session: Session): Record<string, unknown> {
  return {
    grant_type: "refresh_token",
    actor_id: session.actor_id,
    device_id: session.device_id,
    family_id: session.family_id,
    rotation: session.rotation,
    refresh_token: session.refresh_token
  };
}

async function refresh(
  session: Session,
  override: Record<string, unknown> = {}
): Promise<Response> {
  return handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ...refreshPayload(session), ...override })
    })
  );
}

async function status(cookie: string): Promise<Record<string, unknown>> {
  const response = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      headers: { cookie }
    })
  );
  assert.equal(response.status, 200);
  return response.json() as Promise<Record<string, unknown>>;
}

async function legacyLogin(): Promise<string> {
  const response = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextClientIp()
      },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
    })
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { session_scope: "general" });
  return (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
}

test("long-lived mode requires explicit enablement and all bounded TTL values", async () => {
  const valid: NodeJS.ProcessEnv = {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: ACCESS_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: IDLE_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: ABSOLUTE_TTL_SECONDS
  };
  assert.deepEqual(resolveFieldLongSessionConfig(valid), {
    accessTtlSeconds: 60,
    refreshIdleTtlSeconds: 300,
    refreshAbsoluteTtlSeconds: 3600
  });
  for (const environment of [
    { ...valid, WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "false" },
    { ...valid, WALKSAFE_FIELD_ACCESS_TTL_SECONDS: undefined },
    { ...valid, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "59" },
    { ...valid, WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "299" },
    { ...valid, WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "3601" }
  ]) {
    assert.equal(resolveFieldLongSessionConfig(environment), null);
  }

  const enabledSession = await login("disabled-mode-logout");
  process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED = "false";
  const fallback = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextClientIp()
      },
      body: JSON.stringify({
        actor_id: ACTOR_ID,
        token: ACCOUNT_TOKEN,
        device_id: "disabled-device"
      })
    })
  );
  assert.equal(fallback.status, 200);
  assert.deepEqual(await fallback.json(), { session_scope: "general" });
  assert.match(fallback.headers.get("set-cookie") ?? "", /walksafe_field_session=v5\./);

  const disabledRefresh = await refresh({
    session_scope: "general",
    actor_id: ACTOR_ID,
    device_id: "disabled-device",
    family_id: "a".repeat(32),
    rotation: 0,
    refresh_token: "b".repeat(64),
    access_expires_at_epoch_ms: 1,
    idle_expires_at_epoch_ms: 1,
    absolute_expires_at_epoch_ms: 1,
    cookie: "",
    accessToken: ""
  });
  assert.equal(disabledRefresh.status, 503);
  assert.equal(
    (await disabledRefresh.json() as { code: string }).code,
    "field_long_lived_sessions_unavailable"
  );
  const disabledModeLogout = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { cookie: enabledSession.cookie }
    })
  );
  assert.equal(disabledModeLogout.status, 204);
  process.env.WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED = "true";
  assert.equal((await status(enabledSession.cookie)).authenticated, false);
});

test("device login returns split credentials while persisted state contains only digests", async () => {
  const session = await login("device-a");
  assert.equal(session.actor_id, ACTOR_ID);
  assert.equal(session.device_id, "device-a");
  assert.equal(session.rotation, 0);
  assert.match(session.family_id, /^[A-Za-z0-9_-]+$/);
  assert.match(session.refresh_token, /^[A-Za-z0-9_-]+$/);

  assert.deepEqual(await status(session.cookie), {
    required: true,
    authenticated: true,
    actor_id: ACTOR_ID,
    session_scope: "general",
    session_mode: "long_lived",
    device_id: "device-a",
    family_id: session.family_id,
    rotation: 0,
    access_expires_at_epoch_ms: session.access_expires_at_epoch_ms,
    idle_expires_at_epoch_ms: session.idle_expires_at_epoch_ms,
    absolute_expires_at_epoch_ms: session.absolute_expires_at_epoch_ms
  });

  const directoryEntries = await readdir(
    path.join(stateDirectory, "field-long-sessions")
  );
  const stateFiles = directoryEntries.filter((entry) => entry.endsWith(".json"));
  assert.equal(stateFiles.length, 1);
  assert.deepEqual(
    [...directoryEntries].sort(),
    [stateFiles[0]!, `${stateFiles[0]!}.lock`].sort()
  );
  const rawState = await readFile(
    path.join(stateDirectory, "field-long-sessions", stateFiles[0]!),
    "utf8"
  );
  assert.doesNotMatch(rawState, new RegExp(session.refresh_token));
  assert.doesNotMatch(rawState, new RegExp(session.accessToken.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  const persisted = decryptTestStateFile<{
    families: Array<{
      current_refresh_digest_sha256: string;
      current_access_digest_sha256: string;
    }>;
  }>(
    { kind: "field-long-session", recordId: stateFiles[0]! },
    rawState,
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(
    persisted.families[0]?.current_refresh_digest_sha256,
    createHash("sha256").update(session.refresh_token).digest("hex")
  );
  assert.equal(
    persisted.families[0]?.current_access_digest_sha256,
    createHash("sha256").update(session.accessToken).digest("hex")
  );
});

test("rotation is one-time and consumed-token reuse revokes only that device family", async () => {
  const firstDevice = await login("device-a");
  const secondDevice = await login("device-b");
  const rotated = await responseSession(await refresh(firstDevice));
  assert.equal(rotated.rotation, 1);
  assert.notEqual(rotated.refresh_token, firstDevice.refresh_token);
  assert.equal((await status(firstDevice.cookie)).authenticated, false);
  assert.equal((await status(rotated.cookie)).authenticated, true);

  const reused = await refresh(firstDevice);
  assert.equal(reused.status, 401);
  assert.deepEqual(await reused.json(), {
    code: "refresh_token_reuse_detected",
    reauthentication_required: true
  });
  assert.equal((await status(rotated.cookie)).authenticated, false);
  assert.equal((await status(secondDevice.cookie)).authenticated, true);

  const revokedCurrent = await refresh(rotated);
  assert.equal(revokedCurrent.status, 401);
  assert.deepEqual(await revokedCurrent.json(), {
    code: "field_session_revoked",
    reauthentication_required: true
  });
});

test("concurrent use of one refresh token permits one rotation then fails closed", async () => {
  const initial = await login("race-device");
  const responses = await Promise.all([refresh(initial), refresh(initial)]);
  assert.deepEqual(responses.map((response) => response.status).sort(), [200, 401]);
  const success = responses.find((response) => response.status === 200)!;
  const failure = responses.find((response) => response.status === 401)!;
  const rotated = await responseSession(success);
  assert.equal(
    (await failure.json() as { code: string }).code,
    "refresh_token_reuse_detected"
  );
  assert.equal((await status(rotated.cookie)).authenticated, false);
});

test("an external actor lock blocks every mutation without changing persisted state", async () => {
  const session = await login("locked-device");
  const before = await readFile(actorStatePath(), "utf8");
  const releaseLock = await holdExclusiveLock(actorLockPath());
  try {
    const blockedRefresh = await refresh(session);
    assert.equal(blockedRefresh.status, 503);
    assert.deepEqual(await blockedRefresh.json(), {
      code: "field_session_storage_busy",
      message: "다른 현장 세션 갱신이 완료될 때까지 다시 시도해야 합니다.",
      retryable: true
    });

    const blockedRevoke = await handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session?device_id=locked-device", {
        method: "DELETE",
        headers: { cookie: session.cookie }
      })
    );
    assert.equal(blockedRevoke.status, 503);
    await assert.rejects(
      () => revokeFieldSessionsForSecurityEvent(ACTOR_ID, "security_incident"),
      /exclusive file lock is busy/
    );
    assert.equal(await readFile(actorStatePath(), "utf8"), before);
  } finally {
    await releaseLock();
  }

  const retried = await responseSession(await refresh(session));
  assert.equal(retried.rotation, 1);
});

test("independent processes serialize one refresh proof across an actor-lock barrier", async () => {
  const session = await login("independent-race-device");
  const payload: FieldRefreshPayload = {
    actorId: session.actor_id,
    deviceId: session.device_id,
    familyId: session.family_id,
    rotation: session.rotation,
    refreshToken: session.refresh_token
  };
  const before = await readFile(actorStatePath(), "utf8");
  const holder = spawnFieldSessionWorker("holder", actorLockPath(), payload);
  try {
    assert.equal(await waitForChildLine(holder, "LOCKED"), "LOCKED");
    const contender = spawnFieldSessionWorker("contender", actorLockPath(), payload);
    try {
      assert.equal(await waitForChildLine(contender, "RESULT "), "RESULT 503");
      await assertChildExitedSuccessfully(contender);
    } finally {
      if (contender.exitCode === null) contender.kill();
    }
    assert.equal(await readFile(actorStatePath(), "utf8"), before);

    const holderResult = waitForChildLine(holder, "RESULT ");
    holder.stdin.end("release\n");
    assert.equal(await holderResult, "RESULT 200");
    await assertChildExitedSuccessfully(holder);
  } finally {
    if (holder.exitCode === null) {
      holder.stdin.end("release\n");
      holder.kill();
    }
  }
  const persisted = decryptTestStateFile<{
    families: Array<{ device_id: string; rotation: number }>;
  }>(
    { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
    await readFile(actorStatePath(), "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(
    persisted.families.find((family) => family.device_id === session.device_id)?.rotation,
    1
  );
});

test("login reclaims 32 idle-expired device families before capacity enforcement", async () => {
  const issuedAt = 1_800_100_000_000;
  const request = new Request("https://gateway.invalid/api/field-session");
  for (let index = 0; index < 32; index += 1) {
    const response = await establishFieldLongSession(
      request,
      ACTOR_ID,
      `expired-capacity-device-${index}`,
      issuedAt
    );
    assert.equal(response.status, 200);
  }

  const replacement = await responseSession(
    await establishFieldLongSession(
      request,
      ACTOR_ID,
      "replacement-capacity-device",
      issuedAt + 300_000
    )
  );
  const persisted = decryptTestStateFile<{
    families: Array<{
      device_id: string;
      revoked_at_epoch_ms: number | null;
      revoke_reason: string | null;
    }>;
  }>(
    { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
    await readFile(actorStatePath(), "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  const expired = persisted.families.filter(
    (family) => family.device_id.startsWith("expired-capacity-device-")
  );
  assert.equal(expired.length, 32);
  assert.ok(expired.every(
    (family) =>
      family.revoked_at_epoch_ms === issuedAt + 300_000 &&
      family.revoke_reason === "refresh_idle_expired"
  ));
  assert.deepEqual(
    persisted.families
      .filter((family) => family.revoked_at_epoch_ms === null)
      .map(({ device_id, revoked_at_epoch_ms, revoke_reason }) => ({
        device_id,
        revoked_at_epoch_ms,
        revoke_reason
      })),
    [{
      device_id: replacement.device_id,
      revoked_at_epoch_ms: null,
      revoke_reason: null
    }]
  );
});

test("a random refresh mismatch does not revoke the valid family", async () => {
  const initial = await login("mismatch-device");
  const mismatch = await refresh(initial, {
    refresh_token: initial.refresh_token.replace(/^./, initial.refresh_token[0] === "a" ? "b" : "a")
  });
  assert.equal(mismatch.status, 401);
  assert.equal((await mismatch.json() as { code: string }).code, "invalid_refresh_token");
  assert.equal((await status(initial.cookie)).authenticated, true);
  assert.equal((await refresh(initial)).status, 200);
});

test("access, refresh idle, and refresh absolute boundaries expire at the exact timestamp", async () => {
  const issuedAt = 1_800_000_000_000;
  const directRequest = new Request("https://gateway.invalid/api/field-session");
  const issued = await responseSession(
    await establishFieldLongSession(directRequest, ACTOR_ID, "expiry-device", issuedAt)
  );
  const accessRequest = new Request("https://gateway.invalid/api/navigation/walking", {
    headers: { cookie: issued.cookie }
  });
  assert.notEqual(fieldLongSessionIdentity(accessRequest, issuedAt + 59_999), null);
  assert.equal(fieldLongSessionIdentity(accessRequest, issuedAt + 60_000), null);

  const idleExpired = await refreshFieldLongSession(
    directRequest,
    {
      actorId: issued.actor_id,
      deviceId: issued.device_id,
      familyId: issued.family_id,
      rotation: issued.rotation,
      refreshToken: issued.refresh_token
    },
    issuedAt + 300_000
  );
  assert.equal(idleExpired.status, 401);
  assert.equal(
    (await idleExpired.json() as { code: string }).code,
    "refresh_token_idle_expired"
  );

  process.env.WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS = ABSOLUTE_TTL_SECONDS;
  const absoluteIssued = await responseSession(
    await establishFieldLongSession(directRequest, ACTOR_ID, "absolute-device", issuedAt)
  );
  const absoluteExpired = await refreshFieldLongSession(
    directRequest,
    {
      actorId: absoluteIssued.actor_id,
      deviceId: absoluteIssued.device_id,
      familyId: absoluteIssued.family_id,
      rotation: absoluteIssued.rotation,
      refreshToken: absoluteIssued.refresh_token
    },
    issuedAt + 3_600_000
  );
  assert.equal(absoluteExpired.status, 401);
  assert.equal(
    (await absoluteExpired.json() as { code: string }).code,
    "refresh_token_absolute_expired"
  );
});

test("device listing and selective revoke remain bound to the authenticated actor", async () => {
  const current = await login("actor-one-current");
  const other = await login("shared-target");
  const otherActor = await login("shared-target", SECOND_ACTOR_ID, SECOND_ACCOUNT_TOKEN);

  const listed = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session?devices=true", {
      headers: { cookie: current.cookie }
    })
  );
  assert.equal(listed.status, 200);
  const listBody = await listed.json() as {
    actor_id: string;
    devices: Array<{ device_id: string; current: boolean }>;
  };
  assert.equal(listBody.actor_id, ACTOR_ID);
  assert.deepEqual(
    listBody.devices.map((device) => device.device_id).sort(),
    ["actor-one-current", "shared-target"]
  );
  assert.equal(
    listBody.devices.find((device) => device.device_id === "actor-one-current")?.current,
    true
  );

  const unauthenticated = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session?devices=true")
  );
  assert.equal(unauthenticated.status, 401);

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const revoked = await handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session?device_id=shared-target", {
        method: "DELETE",
        headers: { cookie: current.cookie }
      })
    );
    assert.equal(revoked.status, 204);
  }
  assert.equal((await status(current.cookie)).authenticated, true);
  assert.equal((await status(other.cookie)).authenticated, false);
  assert.equal((await status(otherActor.cookie)).authenticated, true);
});

test("removing an actor account immediately closes status and device-management access", async () => {
  const session = await login("removed-actor-device");
  process.env.WALKSAFE_FIELD_ACCOUNTS_JSON = JSON.stringify([
    { actor_id: SECOND_ACTOR_ID, token: SECOND_ACCOUNT_TOKEN }
  ]);
  assert.deepEqual(await status(session.cookie), {
    required: true,
    authenticated: false,
    actor_id: null,
    session_scope: null
  });
  const devices = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session?devices=true", {
      headers: { cookie: session.cookie }
    })
  );
  assert.equal(devices.status, 401);
  const revoke = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session?device_id=removed-actor-device", {
      method: "DELETE",
      headers: { cookie: session.cookie }
    })
  );
  assert.equal(revoke.status, 401);
});

test("refresh-proof logout works without an access cookie and is idempotent", async () => {
  const session = await login("logout-device");
  const logoutBody = {
    grant_type: "refresh_token",
    actor_id: session.actor_id,
    device_id: session.device_id,
    family_id: session.family_id,
    rotation: session.rotation,
    refresh_token: session.refresh_token
  };
  const invalidProof = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ...logoutBody, refresh_token: "z".repeat(64) })
    })
  );
  assert.equal(invalidProof.status, 204);
  assert.equal((await status(session.cookie)).authenticated, true);

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const logout = await handleGatewayRequest(
      new Request("https://gateway.invalid/api/field-session", {
        method: "DELETE",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(logoutBody)
      })
    );
    assert.equal(logout.status, 204);
    assert.match(logout.headers.get("set-cookie") ?? "", /Max-Age=0; Secure$/);
  }
  assert.equal((await status(session.cookie)).authenticated, false);
  assert.equal((await refresh(session)).status, 401);
});

test("account lock and security incident revoke every actor session but no other actor", async () => {
  const first = await login("lock-device-a");
  const second = await login("lock-device-b");
  const legacyCookie = await legacyLogin();
  const otherActor = await login("other-actor-device", SECOND_ACTOR_ID, SECOND_ACCOUNT_TOKEN);
  assert.equal(
    await revokeFieldSessionsForSecurityEvent(ACTOR_ID, "account_lock"),
    2
  );
  assert.equal((await status(first.cookie)).authenticated, false);
  assert.equal((await status(second.cookie)).authenticated, false);
  assert.equal((await status(legacyCookie)).authenticated, false);
  assert.equal((await status(otherActor.cookie)).authenticated, true);

  const third = await login("incident-device-a");
  const fourth = await login("incident-device-b");
  assert.equal(
    await revokeFieldSessionsForSecurityEvent(ACTOR_ID, "security_incident"),
    2
  );
  assert.equal((await status(third.cookie)).authenticated, false);
  assert.equal((await status(fourth.cookie)).authenticated, false);
  assert.equal((await status(otherActor.cookie)).authenticated, true);
});

test("pre-commit storage failures preserve the old refresh proof for one retry", async () => {
  for (const fault of ["file_fsync", "rename"] as const) {
    const session = await login(`retry-device-${fault}`);
    setFieldLongSessionStorageFaultForTests(fault);
    const failed = await refresh(session);
    assert.equal(failed.status, 503);
    assert.equal(
      (await failed.json() as { code: string }).code,
      "field_session_storage_unavailable"
    );
    const retried = await responseSession(await refresh(session));
    assert.equal(retried.rotation, 1);
  }
});

test("post-rename durability failure is outcome-unknown and old proof fails closed", async () => {
  const session = await login("outcome-unknown-device");
  setFieldLongSessionStorageFaultForTests("directory_fsync");
  const failed = await refresh(session);
  assert.equal(failed.status, 500);
  assert.deepEqual(
    await failed.json(),
    {
      code: "field_session_storage_outcome_unknown",
      message: "현장 세션 회전 결과를 확정할 수 없어 다시 로그인해야 합니다.",
      reauthentication_required: true,
      retryable: false
    }
  );

  const retried = await refresh(session);
  assert.equal(retried.status, 401);
  assert.equal(
    (await retried.json() as { code: string }).code,
    "refresh_token_reuse_detected"
  );
  assert.equal((await status(session.cookie)).authenticated, false);
});

test("field-session query and refresh shapes reject ambiguous input", async () => {
  const session = await login("shape-device");
  for (const url of [
    "https://gateway.invalid/api/field-session?devices=false",
    "https://gateway.invalid/api/field-session?devices=true&devices=true",
    "https://gateway.invalid/api/field-session?unknown=true"
  ]) {
    const response = await handleGatewayRequest(new Request(url));
    assert.equal(response.status, 400);
    assert.equal((await response.json() as { code: string }).code, "field_session_query_invalid");
  }
  const extraRefreshField = await refresh(session, { unexpected: true });
  assert.equal(extraRefreshField.status, 400);
  const extraLoginField = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextClientIp()
      },
      body: JSON.stringify({
        actor_id: ACTOR_ID,
        token: ACCOUNT_TOKEN,
        device_id: "shape-device",
        unexpected: true
      })
    })
  );
  assert.equal(extraLoginField.status, 400);
  const malformedLogout = await handleGatewayRequest(
    new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ actor_id: ACTOR_ID })
    })
  );
  assert.equal(malformedLogout.status, 400);
});
