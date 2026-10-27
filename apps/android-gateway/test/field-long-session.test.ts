import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdir, mkdtemp, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { request as httpRequest, type ClientRequest } from "node:http";
import { createServer, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  ExclusiveFileLockBusyError,
  acquireExclusiveFileLockLease,
  withExclusiveFileLockAsync
} from "../src/exclusive-file-lock.js";
import {
  backendFieldLongSessionLookup,
  establishBackendFieldLongSession,
  fieldLongSessionIdentity,
  fieldLongSessionLookup,
  FieldLongSessionStorageUnavailableError,
  refreshFieldLongSession,
  refreshBackendFieldLongSession,
  establishFieldLongSession,
  clearFieldLongSessionWithRefresh,
  revokeAllFieldLongSessions,
  setFieldLongSessionAdmissionHoldForTests,
  setFieldLongSessionStorageFaultForTests,
  type BackendEmailAccountBinding,
  type FieldRefreshPayload
} from "../src/field-long-session.js";
import {
  KeyedAdmissionQueue,
  QueueAdmissionAbortedError,
  QueueAdmissionBusyError
} from "../src/keyed-admission-queue.js";
import {
  revokeFieldSessionsForSecurityEvent,
  establishGatewaySession
} from "../src/auth.js";
import {
  assertFieldLongSessionConfiguration,
  resolveFieldLongSessionConfig
} from "../src/config.js";
import { handleGatewayRequest, setGatewayPrivacyFaultForTests } from "../src/routes.js";
import {
  abortPrivacyOperationsForAccountDeletion,
  activePrivacyOperationCountForTests,
  bindBackendActorGeneration,
  currentActorGeneration
} from "../src/privacy-rights.js";
import {
  acceptOrReplayAccountDeletionV2,
  privacyDeletionV2LedgerPathForTests
} from "../src/privacy-deletion-v2.js";
import { createGatewayServer } from "../src/node-adapter.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";
import { assertBackendActorAssertionV4 } from "./actor-assertion-v4-fixture.js";
// S19-17: this file drives the walk route with v1 commands; v1 intake is an explicit
// legacy opt-in (the product default refuses it with walk_command_version_retired).
process.env.WALKSAFE_FIELD_WALK_V1_COMMANDS = "accept";

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
const CHILD_PROCESS_TIMEOUT_MS = 10_000;

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
    // R19-GB-01: logout paths touch the walk ledger; never share the default TMPDIR lock.
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: ACCESS_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: IDLE_TTL_SECONDS,
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: ABSOLUTE_TTL_SECONDS
  });
  await configureTestStateEncryption(keyringPath);
});

afterEach(async () => {
  setFieldLongSessionAdmissionHoldForTests(null);
  setFieldLongSessionStorageFaultForTests(null);
  setGatewayPrivacyFaultForTests(null);
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

async function availableGatewayPort(): Promise<number> {
  const probe = createServer();
  await new Promise<void>((resolve, reject) => {
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", resolve);
  });
  const port = (probe.address() as AddressInfo).port;
  await new Promise<void>((resolve, reject) => {
    probe.close((error) => error ? reject(error) : resolve());
  });
  return port;
}

function spawnGatewayProcess(
  port: number,
  environment: NodeJS.ProcessEnv = {}
): ChildProcessWithoutNullStreams {
  return spawn(
    process.execPath,
    [fileURLToPath(new URL("../server.js", import.meta.url))],
    {
      env: {
        ...process.env,
        WALKSAFE_ANDROID_GATEWAY_HOST: "127.0.0.1",
        WALKSAFE_ANDROID_GATEWAY_PORT: String(port),
        WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
        WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL: "https://privacy.invalid/requests",
        ...environment
      }
    }
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
      clearTimeout(timeout);
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
    const timeout = setTimeout(() => {
      cleanup();
      if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      reject(new Error(
        `child did not emit ${JSON.stringify(prefix)} within ${CHILD_PROCESS_TIMEOUT_MS}ms: ` +
        (errorOutput.trim() || "no stderr")
      ));
    }, CHILD_PROCESS_TIMEOUT_MS);
    timeout.unref();
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

async function stopGatewayProcess(
  child: ChildProcessWithoutNullStreams
): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) {
    assert.equal(child.signalCode, null);
    assert.equal(child.exitCode, 0);
    return;
  }
  const closed = new Promise<{
    code: number | null;
    signal: NodeJS.Signals | null;
    stderr: string;
  }>((resolve, reject) => {
    let errorOutput = "";
    const cleanup = (): void => {
      clearTimeout(timeout);
      child.stderr.off("data", onErrorData);
      child.off("error", onError);
      child.off("close", onClose);
    };
    const onErrorData = (chunk: Buffer): void => {
      errorOutput += chunk.toString("utf8");
    };
    const onError = (error: Error): void => {
      cleanup();
      reject(error);
    };
    const onClose = (code: number | null, signal: NodeJS.Signals | null): void => {
      cleanup();
      resolve({ code, signal, stderr: errorOutput.trim() });
    };
    const timeout = setTimeout(() => {
      cleanup();
      if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      reject(new Error(
        `gateway did not stop within ${CHILD_PROCESS_TIMEOUT_MS}ms: ` +
        (errorOutput.trim() || "no stderr")
      ));
    }, CHILD_PROCESS_TIMEOUT_MS);
    timeout.unref();
    child.stderr.on("data", onErrorData);
    child.once("error", onError);
    child.once("close", onClose);
    if (!child.kill("SIGTERM")) {
      cleanup();
      reject(new Error("gateway SIGTERM delivery failed"));
    }
  });
  const result = await closed;
  assert.equal(result.signal, null, result.stderr);
  assert.equal(result.code, 0, result.stderr);
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

async function backendSession(response: Response): Promise<Session> {
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(cookie, /^walksafe_field_session=v6\.[^;]+$/);
  const body = await response.json() as SessionBody;
  return { ...body, cookie, accessToken: cookie.split("=", 2)[1]! };
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
  assert.doesNotThrow(() => assertFieldLongSessionConfiguration(valid));
  const disabled = { ...valid, WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "false" };
  assert.equal(resolveFieldLongSessionConfig(disabled), null);
  assert.doesNotThrow(() => assertFieldLongSessionConfiguration(disabled));
  for (const environment of [
    { ...valid, WALKSAFE_FIELD_ACCESS_TTL_SECONDS: undefined },
    { ...valid, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: undefined },
    { ...valid, WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: undefined },
    { ...valid, WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "invalid" },
    { ...valid, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "59" },
    { ...valid, WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "299" },
    { ...valid, WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "3601" },
    { ...valid, WALKSAFE_FIELD_ACCESS_TTL_SECONDS: ABSOLUTE_TTL_SECONDS },
    {
      ...valid,
      WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "3601",
      WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: ABSOLUTE_TTL_SECONDS
    }
  ]) {
    assert.throws(
      () => resolveFieldLongSessionConfig(environment),
      /field long-lived session TTL configuration is invalid/
    );
    assert.throws(
      () => assertFieldLongSessionConfiguration(environment),
      /field long-lived session TTL configuration is invalid/
    );
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

test("gateway rejects invalid enabled TTL before startup side effects", async () => {
  const childStateDirectory = path.join(stateDirectory, "invalid-startup-state");
  const child = spawnGatewayProcess(8081, {
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: childStateDirectory,
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: ""
  });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk: Buffer) => { stdout += chunk.toString(); });
  child.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });
  const result = await new Promise<{ code: number | null; signal: NodeJS.Signals | null }>(
    (resolve, reject) => {
      const timeout = setTimeout(() => child.kill("SIGKILL"), CHILD_PROCESS_TIMEOUT_MS);
      child.once("error", (error) => {
        clearTimeout(timeout);
        reject(error);
      });
      child.once("close", (code, signal) => {
        clearTimeout(timeout);
        resolve({ code, signal });
      });
    }
  );
  assert.equal(result.signal, null, stderr);
  assert.notEqual(result.code, 0, stderr);
  assert.match(stderr, /field long-lived session TTL configuration is invalid/);
  assert.doesNotMatch(stdout, /listening/);
  await assert.rejects(readdir(childStateDirectory), { code: "ENOENT" });
});

async function childExit(
  child: ChildProcessWithoutNullStreams
): Promise<{ code: number | null; signal: NodeJS.Signals | null; stdout: string; stderr: string }> {
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk: Buffer) => { stdout += chunk.toString(); });
  child.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => child.kill("SIGKILL"), CHILD_PROCESS_TIMEOUT_MS);
    child.once("error", (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.once("close", (code, signal) => {
      clearTimeout(timeout);
      resolve({ code, signal, stdout, stderr });
    });
  });
}

test("S19-21 a second gateway process on another port fails before listen", async () => {
  const firstPort = await availableGatewayPort();
  let secondPort = await availableGatewayPort();
  while (secondPort === firstPort) secondPort = await availableGatewayPort();
  const portIsClosed = (port: number): Promise<boolean> => fetch(
    `http://127.0.0.1:${port}/internal/health`
  ).then(() => false, () => true);
  let completed = false;
  const first = spawnGatewayProcess(firstPort);
  try {
    assert.match(
      await waitForChildLine(first, "walksafe-android-gateway listening "),
      new RegExp(`:${firstPort}$`)
    );
    // Same state root and ledger: the service-instance lock must refuse startup.
    const sameRoot = await childExit(spawnGatewayProcess(secondPort));
    assert.equal(sameRoot.signal, null, sameRoot.stderr);
    assert.notEqual(sameRoot.code, 0, sameRoot.stderr);
    assert.match(sameRoot.stderr, /exclusive file lock is busy: .*gateway-service-instance\.lock/);
    assert.doesNotMatch(sameRoot.stdout, /listening/);
    assert.equal(await portIsClosed(secondPort), true);

    // A different state root still cannot share the running field-walk ledger.
    const sameLedger = await childExit(spawnGatewayProcess(secondPort, {
      WALKSAFE_GATEWAY_RATE_LIMIT_DIR: path.join(stateDirectory, "s19-21-other-root")
    }));
    assert.equal(sameLedger.signal, null, sameLedger.stderr);
    assert.notEqual(sameLedger.code, 0, sameLedger.stderr);
    assert.match(
      sameLedger.stderr,
      /exclusive file lock is busy: .*field-walk-ledger\.json\.gateway-service-instance\.lock/
    );
    assert.doesNotMatch(sameLedger.stdout, /listening/);
    assert.equal(await portIsClosed(secondPort), true);

    const health = await fetch(`http://127.0.0.1:${firstPort}/internal/health`);
    assert.equal(health.status, 200);
    completed = true;
  } finally {
    if (first.exitCode === null && first.signalCode === null) {
      if (completed) await stopGatewayProcess(first);
      else first.kill("SIGKILL");
    }
  }
});

test("S19-12 production short and long-lived cookies are Secure on internal HTTP requests", async () => {
  const internalUrl = "http://127.0.0.1:8081/api/field-session";
  const headers: Array<[string, string]> = [];
  const previousNodeEnv = process.env.NODE_ENV;
  try {
    process.env.NODE_ENV = "production";
    const shortSession = establishGatewaySession(new Request(internalUrl, {
      headers: { "x-forwarded-proto": "http" }
    }), ACTOR_ID);
    assert.equal(shortSession.status, 200);
    headers.push(["short-login", shortSession.headers.get("set-cookie") ?? ""]);
    const issued = await establishFieldLongSession(
      new Request(internalUrl), ACTOR_ID, "s19-12-production-device"
    );
    assert.equal(issued.status, 200);
    headers.push(["login", issued.headers.get("set-cookie") ?? ""]);
    const initial = await issued.json() as SessionBody;
    const rotated = await refreshFieldLongSession(
      new Request(internalUrl, { headers: { "x-forwarded-proto": "http" } }),
      {
        actorId: initial.actor_id,
        deviceId: initial.device_id,
        familyId: initial.family_id,
        rotation: initial.rotation,
        refreshToken: initial.refresh_token
      }
    );
    assert.equal(rotated.status, 200);
    headers.push(["refresh", rotated.headers.get("set-cookie") ?? ""]);
    const next = await rotated.json() as SessionBody;
    const cleared = await clearFieldLongSessionWithRefresh(new Request(internalUrl), {
      actorId: next.actor_id,
      deviceId: next.device_id,
      familyId: next.family_id,
      rotation: next.rotation,
      refreshToken: next.refresh_token
    });
    assert.equal(cleared.status, 204);
    headers.push(["logout", cleared.headers.get("set-cookie") ?? ""]);
  } finally {
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
  }
  for (const [label, header] of headers) {
    assert.match(header, /^walksafe_field_session=/, label);
    assert.match(header, /(?:^|; )Secure(?:;|$)/, label);
  }
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

test("field-session lookup distinguishes invalid, expired, revoked, and storage faults", async () => {
  const session = await login("lookup-device");
  const request = new Request("https://gateway.invalid/api/field-session", {
    headers: { cookie: session.cookie }
  });
  assert.equal(fieldLongSessionLookup(request).status, "authenticated");
  const invalid = new Request(request.url, {
    headers: { cookie: `${session.cookie}x` }
  });
  assert.equal(fieldLongSessionLookup(invalid).status, "invalid");
  assert.equal(
    fieldLongSessionLookup(request, session.access_expires_at_epoch_ms).status,
    "expired"
  );

  const statePath = actorStatePath();
  const encryptedState = await readFile(statePath);
  await writeFile(statePath, "invalid encrypted state");
  try {
    assert.equal(fieldLongSessionLookup(request).status, "storage_unavailable");
    assert.throws(() => fieldLongSessionIdentity(request), FieldLongSessionStorageUnavailableError);
    const unavailable = await handleGatewayRequest(request);
    assert.equal(unavailable.status, 503);
    assert.equal((await unavailable.json() as { code: string }).code, "field_session_storage_unavailable");
    assert.equal(unavailable.headers.get("set-cookie"), null);
  } finally {
    await writeFile(statePath, encryptedState);
  }

  await revokeAllFieldLongSessions(ACTOR_ID, "security_incident");
  assert.equal(fieldLongSessionLookup(request).status, "revoked");
});

test("missing long-session state keeps credentials while short sessions remain usable", async () => {
  const shortCookie = await legacyLogin();
  const session = await login("missing-state-device");
  const statePath = actorStatePath();
  const heldPath = `${statePath}.held`;
  await rename(statePath, heldPath);
  try {
    for (const [method, url] of [
      ["GET", "https://gateway.invalid/api/field-session"],
      ["GET", "https://gateway.invalid/api/field-session?devices=true"],
      ["DELETE", "https://gateway.invalid/api/field-session"]
    ] as const) {
      const response = await handleGatewayRequest(new Request(url, {
        method,
        headers: { cookie: session.cookie }
      }));
      assert.equal(response.status, 503);
      assert.equal((await response.json() as { code: string }).code, "field_session_storage_unavailable");
      assert.equal(response.headers.get("set-cookie"), null);
    }
    assert.equal((await status(shortCookie)).authenticated, true);
  } finally {
    await rename(heldPath, statePath);
  }
  assert.equal((await status(session.cookie)).authenticated, true);
});

test("rotation is one-time and consumed-token reuse revokes every device of the account (decision 1)", async () => {
  const shortCookie = await legacyLogin();
  const firstDevice = await login("device-a");
  const secondDevice = await login("device-b");
  const rotated = await responseSession(await refresh(firstDevice));
  assert.equal(rotated.rotation, 1);
  assert.notEqual(rotated.refresh_token, firstDevice.refresh_token);
  assert.equal((await status(firstDevice.cookie)).authenticated, false);
  assert.equal((await status(rotated.cookie)).authenticated, true);
  assert.equal((await status(shortCookie)).authenticated, true);

  const reused = await refresh(firstDevice);
  assert.equal(reused.status, 401);
  assert.deepEqual(await reused.json(), {
    code: "refresh_token_reuse_detected",
    reauthentication_required: true
  });
  // Owner decision 1 (FP-011-DETAIL-02): every device of the account is logged out at once,
  // including other long-session families and the short session.
  assert.equal((await status(rotated.cookie)).authenticated, false);
  assert.equal((await status(secondDevice.cookie)).authenticated, false);
  assert.equal((await status(shortCookie)).authenticated, false);
  assert.deepEqual(readStoredActorState().families.map((family) => family.revoke_reason),
    ["refresh_token_reuse", "refresh_token_reuse"]);

  // Each other device learns the registered reason on its next refresh.
  for (const holder of [rotated, secondDevice]) {
    const revokedCurrent = await refresh(holder);
    assert.equal(revokedCurrent.status, 401);
    assert.deepEqual(await revokedCurrent.json(), {
      code: "field_session_revoked_reuse_detected",
      reauthentication_required: true
    });
  }
  // A forged token against a revoked family keeps the generic answer (no reason leak).
  const forged = await refresh({ ...secondDevice, refresh_token: "A".repeat(86) });
  assert.deepEqual(await forged.json(), {
    code: "field_session_revoked",
    reauthentication_required: true
  });

  // A new login works; replaying the old consumed token again cannot log it out.
  // login-F7: the revoked family answers its token holder like a Backend family does.
  const relogged = await login("device-b");
  const replay = await refresh(firstDevice);
  assert.equal((await replay.json() as { code: string }).code, "field_session_revoked_reuse_detected");
  assert.equal((await status(relogged.cookie)).authenticated, true);
  assert.equal((await responseSession(await refresh(relogged))).rotation, 1);
});

test("deletion during a queued refresh does not commit a new token", async () => {
  const session = await login("deletion-wait-device");
  const before = await readFile(actorStatePath(), "utf8");
  let release!: () => void;
  setFieldLongSessionAdmissionHoldForTests(new Promise<void>((resolve) => {
    release = resolve;
  }));
  const pending = refresh(session);
  try {
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(activePrivacyOperationCountForTests(), 1);
    const generation = currentActorGeneration(ACTOR_ID);
    assert.notEqual(generation, null);
    assert.equal(acceptOrReplayAccountDeletionV2(ACTOR_ID, generation, {
      schema_version: "walksafe.account-deletion-request.v2",
      request_id: "field_refresh_deletion_wait_0001",
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    }, Buffer.alloc(32, 0x5a).toString("base64url")).kind, "accepted");
    abortPrivacyOperationsForAccountDeletion(ACTOR_ID, generation!);
  } finally {
    setFieldLongSessionAdmissionHoldForTests(null);
    release();
  }
  const blocked = await pending;
  assert.equal(blocked.status, 409);
  assert.equal(
    (await blocked.json() as { detail?: { code?: string } }).detail?.code,
    "account_generation_inactive"
  );
  assert.equal(await readFile(actorStatePath(), "utf8"), before);
});

test("a deletion ledger writer blocks refresh rotation before the durable write", async () => {
  const session = await login("deletion-lock-device");
  const before = await readFile(actorStatePath(), "utf8");
  const deletionLock = acquireExclusiveFileLockLease(path.join(
    path.dirname(privacyDeletionV2LedgerPathForTests()), "ledger.lock"
  ));
  try {
    const blocked = await refresh(session);
    assert.equal(blocked.status, 503);
    assert.equal(await readFile(actorStatePath(), "utf8"), before);
  } finally {
    deletionLock.release();
  }
  assert.equal((await responseSession(await refresh(session))).rotation, 1);
});

test("an after-lease fault releases its newly acquired privacy lease", async () => {
  const session = await login("lease-fence-fault-device");
  const before = await readFile(actorStatePath(), "utf8");
  assert.equal(activePrivacyOperationCountForTests(), 0);
  setGatewayPrivacyFaultForTests("after_lease");
  const failed = await refresh(session);
  assert.equal(failed.status, 503);
  assert.equal((await failed.json() as { code: string }).code, "privacy_ledger_unavailable");
  assert.equal(activePrivacyOperationCountForTests(), 0);
  assert.equal(await readFile(actorStatePath(), "utf8"), before);
  assert.equal((await responseSession(await refresh(session))).rotation, 1);
});

test("a real deletion-fence lookup failure releases its privacy lease", async () => {
  const session = await login("lease-fence-read-fault-device");
  const before = await readFile(actorStatePath(), "utf8");
  assert.equal(activePrivacyOperationCountForTests(), 0);
  const ledgerPath = privacyDeletionV2LedgerPathForTests();
  await mkdir(path.dirname(ledgerPath), { recursive: true });
  const previousLedger = await readFile(ledgerPath).catch((error: NodeJS.ErrnoException) => {
    if (error.code === "ENOENT") return null;
    throw error;
  });
  try {
    await writeFile(ledgerPath, "{");
    const failed = await refresh(session);
    assert.equal(failed.status, 503);
    assert.equal((await failed.json() as { code: string }).code, "privacy_ledger_unavailable");
    assert.equal(activePrivacyOperationCountForTests(), 0);
    assert.equal(await readFile(actorStatePath(), "utf8"), before);
  } finally {
    if (previousLedger === null) await rm(ledgerPath, { force: true });
    else await writeFile(ledgerPath, previousLedger);
  }
  assert.equal((await responseSession(await refresh(session))).rotation, 1);
});

test("pre-write privacy ledger faults leave the refresh proof retryable", async () => {
  for (const point of ["refresh_pre_operation", "refresh_before_commit"] as const) {
    const session = await login(`pre-write-ledger-${point}`);
    const before = await readFile(actorStatePath(), "utf8");
    setGatewayPrivacyFaultForTests(point);
    const failed = await refresh(session);
    assert.equal(failed.status, 503);
    assert.equal((await failed.json() as { code: string }).code, "privacy_ledger_unavailable");
    assert.equal(activePrivacyOperationCountForTests(), 0);
    assert.equal(await readFile(actorStatePath(), "utf8"), before);
    assert.equal((await responseSession(await refresh(session))).rotation, 1);
  }
});

test("post-write privacy ledger fault reports an unknown rotation outcome", async () => {
  const session = await login("post-write-ledger-fault");
  setGatewayPrivacyFaultForTests("refresh_post_operation");
  const failed = await refresh(session);
  assert.equal(failed.status, 500);
  assert.equal(failed.headers.get("set-cookie"), null);
  assert.deepEqual(await failed.json(), {
    code: "field_session_storage_outcome_unknown",
    message: "현장 세션 회전 결과를 확정할 수 없어 다시 로그인해야 합니다.",
    reauthentication_required: true,
    retryable: false
  });
  assert.equal(activePrivacyOperationCountForTests(), 0);
  const persisted = decryptTestStateFile<{
    families: Array<{ family_id: string; rotation: number }>;
  }>(
    { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
    await readFile(actorStatePath(), "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(
    persisted.families.find((family) => family.family_id === session.family_id)?.rotation,
    1
  );
  assert.equal((await status(session.cookie)).authenticated, false);
});

test("revoked legacy wrong-token fails before privacy gate while consumed replay reaches it", async () => {
  const initial = await login("revoked-proof-device");
  const rotated = await responseSession(await refresh(initial));
  assert.equal(await revokeAllFieldLongSessions(ACTOR_ID, "security_incident"), 1);
  const replay = await refresh(initial);
  assert.equal(replay.status, 401);
  // login-F7: a consumed token of a family revoked for another reason gets the generic code.
  assert.equal((await replay.json() as { code: string }).code, "field_session_revoked");
  const generation = currentActorGeneration(ACTOR_ID);
  assert.notEqual(generation, null);
  assert.equal(acceptOrReplayAccountDeletionV2(ACTOR_ID, generation, {
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: "field_refresh_revoked_proof_0001",
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT"
  }, Buffer.alloc(32, 0x5b).toString("base64url")).kind, "accepted");
  abortPrivacyOperationsForAccountDeletion(ACTOR_ID, generation!);
  const wrong = await refresh(rotated, { refresh_token: "A".repeat(64) });
  assert.equal(wrong.status, 401);
  const gatedReplay = await refresh(initial);
  assert.equal(gatedReplay.status, 409);
  assert.equal(
    (await gatedReplay.json() as { detail?: { code?: string } }).detail?.code,
    "account_generation_inactive"
  );
});

test("a real gateway restart preserves refresh rotation and token reuse revokes every device (decision 1)", async () => {
  const port = await availableGatewayPort();
  const origin = `http://127.0.0.1:${port}`;
  const forwardedHeaders = {
    "content-type": "application/json",
    "x-forwarded-proto": "https"
  };
  const gatewayLogin = async (deviceId: string): Promise<Session> => responseSession(await fetch(
    `${origin}/api/field-session`,
    {
      method: "POST",
      headers: { ...forwardedHeaders, "cf-connecting-ip": nextClientIp() },
      body: JSON.stringify({
        actor_id: ACTOR_ID,
        token: ACCOUNT_TOKEN,
        device_id: deviceId
      })
    }
  ));
  const gatewayRefresh = (session: Session): Promise<Response> => fetch(
    `${origin}/api/field-session`,
    {
      method: "POST",
      headers: forwardedHeaders,
      body: JSON.stringify(refreshPayload(session))
    }
  );
  const gatewayStatus = async (cookie: string): Promise<Record<string, unknown>> => {
    const response = await fetch(`${origin}/api/field-session`, { headers: { cookie } });
    assert.equal(response.status, 200);
    return response.json() as Promise<Record<string, unknown>>;
  };

  let completed = false;
  let gateway: ChildProcessWithoutNullStreams | null = spawnGatewayProcess(port);
  try {
    assert.match(
      await waitForChildLine(gateway, "walksafe-android-gateway listening "),
      new RegExp(`:${port}$`)
    );
    const firstDevice = await gatewayLogin("restart-device-a");
    const secondDevice = await gatewayLogin("restart-device-b");
    const encryptedStateBeforeRestart = await readFile(actorStatePath(), "utf8");

    await stopGatewayProcess(gateway);
    gateway = spawnGatewayProcess(port);
    assert.match(
      await waitForChildLine(gateway, "walksafe-android-gateway listening "),
      new RegExp(`:${port}$`)
    );
    assert.equal(await readFile(actorStatePath(), "utf8"), encryptedStateBeforeRestart);

    const rotated = await responseSession(await gatewayRefresh(firstDevice));
    assert.equal(rotated.rotation, 1);
    assert.notEqual(rotated.refresh_token, firstDevice.refresh_token);

    const reused = await gatewayRefresh(firstDevice);
    assert.equal(reused.status, 401);
    assert.deepEqual(await reused.json(), {
      code: "refresh_token_reuse_detected",
      reauthentication_required: true
    });
    assert.equal((await gatewayStatus(rotated.cookie)).authenticated, false);
    // Owner decision 1: the other device of the account is logged out too, with the reason.
    assert.equal((await gatewayStatus(secondDevice.cookie)).authenticated, false);
    const secondRefresh = await gatewayRefresh(secondDevice);
    assert.equal(secondRefresh.status, 401);
    assert.deepEqual(await secondRefresh.json(), {
      code: "field_session_revoked_reuse_detected",
      reauthentication_required: true
    });
    completed = true;
  } finally {
    if (gateway !== null && gateway.exitCode === null && gateway.signalCode === null) {
      if (completed) await stopGatewayProcess(gateway);
      else gateway.kill("SIGKILL");
    }
  }
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

test("S19-10 invalid refresh proofs are rejected without entering the actor lock", async () => {
  const session = await login("s19-10-device");
  const before = await readFile(actorStatePath(), "utf8");
  const releaseLock = await holdExclusiveLock(actorLockPath());
  const statuses: string[] = [];
  try {
    for (const override of [
      { refresh_token: "z".repeat(64) },
      { rotation: 1 },
      { family_id: "f".repeat(32) },
      { device_id: "s19-10-other-device" }
    ]) {
      const rejected = await refresh(session, override);
      const body = await rejected.json() as { code: string };
      statuses.push(`${rejected.status}:${body.code}`);
    }
  } finally {
    await releaseLock();
  }
  // A held actor lock would surface as 503 field_session_storage_busy.
  assert.deepEqual(statuses, [
    "401:invalid_refresh_token",
    "401:invalid_refresh_token",
    "401:invalid_refresh_token",
    "401:invalid_refresh_token"
  ]);
  assert.equal(await readFile(actorStatePath(), "utf8"), before);
  assert.equal((await responseSession(await refresh(session))).rotation, 1);
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

test("S01-26 minimum access TTL reaches the rotation cap with explicit reauthentication before absolute expiry", async () => {
  process.env.WALKSAFE_FIELD_ACCESS_TTL_SECONDS = "60";
  process.env.WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS = String(90 * 24 * 60 * 60);
  process.env.WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS = String(365 * 24 * 60 * 60);
  const issuedAt = 1_800_000_000_000;
  const request = new Request("https://gateway.invalid/api/field-session");
  const issued = await responseSession(
    await establishFieldLongSession(request, ACTOR_ID, "rotation-cap-device", issuedAt)
  );
  assert.equal(issued.absolute_expires_at_epoch_ms, issuedAt + 365 * 86_400_000);

  // Stand in for 4,095 prior one-minute rotations; the full 4,096-rotation run is slow.
  const context = { kind: "field-long-session" as const, recordId: path.basename(actorStatePath()) };
  const state = decryptTestStateFile<{
    families: Array<{ family_id: string; rotation: number; consumed_refresh_digests_sha256: string[] }>;
  }>(context, await readFile(actorStatePath(), "utf8"), FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES);
  const family = state.families.find((entry) => entry.family_id === issued.family_id)!;
  family.rotation = 4095;
  family.consumed_refresh_digests_sha256 = Array.from({ length: 4095 }, (_, index) =>
    createHash("sha256").update(`s01-26-consumed-${index}`).digest("hex"));
  await writeFile(
    actorStatePath(),
    encryptTestStateFile(context, state, FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES),
    "utf8"
  );

  const lastAllowedAt = issuedAt + 4095 * 60_000;
  const last = await responseSession(await refreshFieldLongSession(request, {
    actorId: ACTOR_ID,
    deviceId: issued.device_id,
    familyId: issued.family_id,
    rotation: 4095,
    refreshToken: issued.refresh_token
  }, lastAllowedAt));
  assert.equal(last.rotation, 4096);

  const limitAt = lastAllowedAt + 60_000;
  const limited = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID,
    deviceId: last.device_id,
    familyId: last.family_id,
    rotation: last.rotation,
    refreshToken: last.refresh_token
  }, limitAt);
  assert.equal(limited.status, 401);
  assert.equal(limited.headers.get("cache-control"), "no-store");
  assert.deepEqual(await limited.json(), {
    code: "refresh_rotation_limit_reached",
    reauthentication_required: true
  });
  // About 2.84 days of one-minute access renewal, not the configured 365-day lifetime.
  assert.ok(last.absolute_expires_at_epoch_ms - limitAt > 362 * 86_400_000);
  assert.ok(last.idle_expires_at_epoch_ms > limitAt);
  const persisted = decryptTestStateFile<{
    families: Array<{ family_id: string; revoke_reason: string | null }>;
  }>(context, await readFile(actorStatePath(), "utf8"), FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES);
  assert.equal(
    persisted.families.find((entry) => entry.family_id === issued.family_id)?.revoke_reason,
    "refresh_rotation_limit"
  );
  const relogin = await responseSession(
    await establishFieldLongSession(request, ACTOR_ID, "rotation-cap-device", limitAt + 1)
  );
  assert.equal(relogin.rotation, 0);
  assert.notEqual(relogin.family_id, issued.family_id);
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

test("S19-6 lost rotation response cleanup with old proof revokes only that device family", async () => {
  const lostResponseDevice = await login("lost-response-device");
  const otherDevice = await login("unaffected-device");
  // The server committed a rotation; the client only retained the old proof before losing the response.
  const rotated = await responseSession(await refresh(lostResponseDevice));
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const logout = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(refreshPayload(lostResponseDevice))
    }));
    assert.equal(logout.status, 204);
    assert.equal((await status(rotated.cookie)).authenticated, false);
    assert.equal((await status(otherDevice.cookie)).authenticated, true);
  }
  assert.equal((await refresh(otherDevice)).status, 200);
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

test("security revocation preempts a saturated actor queue while ordinary work is held", async () => {
  const old = await login("security-saturation-old");
  const request = new Request("https://gateway.invalid/api/field-session");
  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  setFieldLongSessionAdmissionHoldForTests(held);
  const activePromise = establishFieldLongSession(request, ACTOR_ID, "security-saturation-active");
  const pending = Array.from({ length: 32 }, (_, index) =>
    establishFieldLongSession(request, ACTOR_ID, `security-saturation-pending-${index}`)
  );
  const overCapacity = establishFieldLongSession(request, ACTOR_ID, "security-saturation-over-capacity");
  const revoke = revokeFieldSessionsForSecurityEvent(ACTOR_ID, "security_incident");
  let responses: Response[] = [];
  let overCapacityResponse: Response | null = null;
  let authenticatedWhileHeld = false;
  try {
    [responses, overCapacityResponse] = await Promise.all([
      Promise.all(pending),
      overCapacity
    ]);
    authenticatedWhileHeld = (await status(old.cookie)).authenticated === true;
  } finally {
    setFieldLongSessionAdmissionHoldForTests(null);
    release();
  }
  const active = await responseSession(await activePromise);
  assert.equal(await revoke, 2);
  assert.equal(authenticatedWhileHeld, true);
  assert.equal(overCapacityResponse?.status, 503);
  assert.ok(responses.every((response) => response.status === 503));
  for (const response of responses) {
    const body = await response.json() as { code: string; retryable: boolean };
    assert.equal(body.code, "field_session_storage_busy");
    assert.equal(body.retryable, true);
  }
  assert.equal((await status(old.cookie)).authenticated, false);
  assert.equal((await status(active.cookie)).authenticated, false);
  assert.equal((await refresh(old)).status, 401);
  const persisted = decryptTestStateFile<{
    families: Array<{ device_id: string; revoked_at_epoch_ms: number | null }>;
  }>(
    { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
    await readFile(actorStatePath(), "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.deepEqual(
    persisted.families.map((family) => family.device_id).sort(),
    ["security-saturation-old", "security-saturation-active"].sort()
  );
  assert.ok(persisted.families.every((family) => family.revoked_at_epoch_ms !== null));
});

test("field-session admission allows 32 waiters, rejects the next, and preserves FIFO per actor", async () => {
  const queue = new KeyedAdmissionQueue(32, 1_000);
  let release!: () => void;
  let signalStarted!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  const started = new Promise<void>((resolve) => { signalStarted = resolve; });
  const order: number[] = [];
  const active = queue.run(ACTOR_ID, async () => {
    signalStarted();
    await held;
    return -1;
  });
  await started;
  const waiters = Array.from({ length: 32 }, (_, index) =>
    queue.run(ACTOR_ID, async () => {
      order.push(index);
      return index;
    })
  );
  try {
    await assert.rejects(
      queue.run(ACTOR_ID, async () => 32),
      QueueAdmissionBusyError
    );
    assert.equal(await queue.run(SECOND_ACTOR_ID, async () => 99), 99);
    assert.deepEqual(order, []);
  } finally {
    release();
  }
  assert.equal(await active, -1);
  assert.deepEqual(await Promise.all(waiters), Array.from({ length: 32 }, (_, index) => index));
  assert.deepEqual(order, Array.from({ length: 32 }, (_, index) => index));
});

test("field-session admission times out a waiter without executing it", async () => {
  const queue = new KeyedAdmissionQueue(32, 50);
  let release!: () => void;
  let signalStarted!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  const started = new Promise<void>((resolve) => { signalStarted = resolve; });
  let executed = false;
  const active = queue.run(ACTOR_ID, async () => {
    signalStarted();
    await held;
  });
  await started;
  try {
    await assert.rejects(
      queue.run(ACTOR_ID, async () => { executed = true; }),
      QueueAdmissionBusyError
    );
    assert.equal(executed, false);
  } finally {
    release();
  }
  await active;
});

test("field-session admission aborts only waiting work and retains a running permit", async () => {
  const queue = new KeyedAdmissionQueue(32, 1_000);
  const runningAbort = new AbortController();
  const waitingAbort = new AbortController();
  let release!: () => void;
  let signalStarted!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  const started = new Promise<void>((resolve) => { signalStarted = resolve; });
  let ranWaiting = false;
  let ranFollower = false;
  const active = queue.run(ACTOR_ID, async () => {
    signalStarted();
    await held;
    return "settled";
  }, runningAbort.signal);
  await started;
  const waiting = queue.run(ACTOR_ID, async () => { ranWaiting = true; }, waitingAbort.signal);
  const follower = queue.run(ACTOR_ID, async () => { ranFollower = true; });
  try {
    waitingAbort.abort();
    runningAbort.abort();
    await assert.rejects(waiting, QueueAdmissionAbortedError);
    assert.equal(ranWaiting, false);
    assert.equal(ranFollower, false);
  } finally {
    release();
  }
  assert.equal(await active, "settled");
  await follower;
  assert.equal(ranFollower, true);
});

test("field-session admission maps a full queue to the existing retryable storage response", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const payload: FieldRefreshPayload = {
    actorId: ACTOR_ID,
    deviceId: "admission-device",
    familyId: "b".repeat(32),
    rotation: 0,
    refreshToken: "a".repeat(64)
  };
  const responses = await Promise.all(Array.from({ length: 34 }, () =>
    clearFieldLongSessionWithRefresh(request, payload)
  ));
  assert.ok(responses.slice(0, 33).every((response) => response.status === 204));
  assert.equal(responses[33]?.status, 503);
  assert.equal(responses[33]?.headers.get("retry-after"), "1");
  assert.deepEqual(await responses[33]?.json(), {
    code: "field_session_storage_busy",
    message: "다른 현장 세션 갱신이 완료될 때까지 다시 시도해야 합니다.",
    retryable: true
  });
});

test("field-session admission maps a queued request abort before storage execution", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const first = establishFieldLongSession(request, ACTOR_ID, "admission-first");
  const controller = new AbortController();
  const queued = establishFieldLongSession(
    new Request("https://gateway.invalid/api/field-session", { signal: controller.signal }),
    ACTOR_ID,
    "admission-cancelled"
  );
  controller.abort();
  const cancelled = await queued;
  assert.equal(cancelled.status, 499);
  assert.deepEqual(await cancelled.json(), { code: "gateway_client_closed" });
  assert.equal((await first).status, 200);
  const persisted = decryptTestStateFile<{ families: Array<{ device_id: string }> }>(
    { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
    await readFile(actorStatePath(), "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.deepEqual(persisted.families.map((family) => family.device_id), ["admission-first"]);
});

test("S19-24 account login real HTTP disconnect frees a waiting actor slot", async () => {
  const firstDeviceId = "s19_24_http_account_first";
  const replacementDeviceId = "s19_24_http_account_replacement";
  let release!: () => void;
  setFieldLongSessionAdmissionHoldForTests(new Promise<void>((resolve) => {
    release = resolve;
  }));
  const server = createGatewayServer();
  const receivedResolvers = new Map<string, () => void>();
  const closeResolvers = new Map<string, () => void>();
  server.on("request", (request, response) => {
    const deviceId = request.headers["x-fixture-device-id"];
    if (typeof deviceId !== "string") return;
    receivedResolvers.get(deviceId)?.();
    receivedResolvers.delete(deviceId);
    response.once("close", () => {
      closeResolvers.get(deviceId)?.();
      closeResolvers.delete(deviceId);
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  type HttpCall = {
    client: ClientRequest;
    result: Promise<{ status: number; body: string; headers: Record<string, string | string[] | undefined> }>;
    sent: Promise<void>;
    received: Promise<void>;
    serverClosed: Promise<void>;
    responded: () => boolean;
  };
  const calls: HttpCall[] = [];
  const port = (server.address() as AddressInfo).port;
  const send = (deviceId: string): HttpCall => {
    const body = JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN, device_id: deviceId });
    const received = new Promise<void>((resolve) => { receivedResolvers.set(deviceId, resolve); });
    const serverClosed = new Promise<void>((resolve) => { closeResolvers.set(deviceId, resolve); });
    let client!: ClientRequest;
    let responseSeen = false;
    const result = new Promise<{
      status: number;
      body: string;
      headers: Record<string, string | string[] | undefined>;
    }>((resolve, reject) => {
      client = httpRequest({
        hostname: "127.0.0.1",
        port,
        method: "POST",
        path: "/api/field-session",
        agent: false,
        headers: {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
          "cf-connecting-ip": nextClientIp(),
          "x-fixture-device-id": deviceId
        }
      }, (response) => {
        responseSeen = true;
        const chunks: Buffer[] = [];
        response.on("data", (chunk: Buffer) => chunks.push(chunk));
        response.once("end", () => resolve({
          status: response.statusCode ?? 0,
          body: Buffer.concat(chunks).toString("utf8"),
          headers: response.headers
        }));
        response.once("error", reject);
      });
      client.setTimeout(3_000, () => client.destroy(new Error("fixture HTTP timeout")));
      client.once("error", reject);
      client.end(body);
    });
    void result.catch(() => undefined);
    const sent = new Promise<void>((resolve, reject) => {
      client.once("finish", resolve);
      client.once("error", reject);
    });
    void sent.catch(() => undefined);
    const call = { client, result, sent, received, serverClosed, responded: () => responseSeen };
    calls.push(call);
    return call;
  };
  const awaitReceived = (call: HttpCall): Promise<void> => Promise.race([
    call.received,
    call.result.then(({ status }) => {
      throw new Error(`HTTP login completed before server received it: ${status}`);
    })
  ]);
  const awaitServerClosed = async (targets: HttpCall[]): Promise<void> => {
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([
        Promise.all(targets.map((call) => call.serverClosed)),
        new Promise<never>((_, reject) => {
          timeout = setTimeout(() => reject(new Error(
            "server did not observe account login HTTP disconnect"
          )), 3_000);
        })
      ]);
    } finally {
      if (timeout) clearTimeout(timeout);
    }
  };

  try {
    const first = send(firstDeviceId);
    await first.sent;
    await awaitReceived(first);
    await new Promise<void>((resolve) => setImmediate(resolve));

    const waitersStartedAt = performance.now();
    const waiters = Array.from({ length: 32 }, (_, index) =>
      send(`s19_24_http_account_waiter_${index.toString().padStart(2, "0")}`)
    );
    await Promise.all(waiters.map(async (waiter) => {
      await waiter.sent;
      await awaitReceived(waiter);
    }));
    await new Promise<void>((resolve) => setImmediate(resolve));

    const overflow = await send("s19_24_http_account_overflow").result;
    assert.equal(overflow.status, 503);
    assert.equal(overflow.headers["retry-after"], "1");
    assert.equal(JSON.parse(overflow.body).code, "field_session_storage_busy");
    assert.ok(performance.now() - waitersStartedAt < 900,
      "overflow must occur before any original waiter can time out");

    const disconnected = waiters[0]!;
    const rejected = assert.rejects(disconnected.result, /fixture waiter disconnected/);
    disconnected.client.destroy(new Error("fixture waiter disconnected"));
    await rejected;
    await awaitServerClosed([disconnected]);
    assert.equal(disconnected.responded(), false, "closed client must not receive a 499 response");

    const replacement = send(replacementDeviceId);
    await replacement.sent;
    await awaitReceived(replacement);
    await new Promise<void>((resolve) => setImmediate(resolve));
    const fullAgain = await send("s19_24_http_account_full_again").result;
    assert.equal(fullAgain.status, 503);
    assert.equal(fullAgain.headers["retry-after"], "1");
    assert.equal(JSON.parse(fullAgain.body).code, "field_session_storage_busy");
    assert.ok(performance.now() - waitersStartedAt < 900,
      "replacement and second overflow must precede the one-second waiter deadline");

    const remaining = waiters.slice(1);
    const rejectedRemaining = remaining.map((waiter) =>
      assert.rejects(waiter.result, /fixture waiter cleanup/)
    );
    remaining.forEach((waiter) => waiter.client.destroy(new Error("fixture waiter cleanup")));
    await Promise.all(rejectedRemaining);
    await awaitServerClosed(remaining);
    assert.ok(waiters.every((waiter) => !waiter.responded()));

    release();
    const firstResponse = await first.result;
    assert.equal(firstResponse.status, 200, firstResponse.body);
    assert.equal(JSON.parse(firstResponse.body).device_id, firstDeviceId);
    assert.match(String(firstResponse.headers["set-cookie"]), /^walksafe_field_session=v4\./);
    const replacementResponse = await replacement.result;
    assert.equal(replacementResponse.status, 200, replacementResponse.body);
    assert.equal(JSON.parse(replacementResponse.body).device_id, replacementDeviceId);
    assert.match(String(replacementResponse.headers["set-cookie"]), /^walksafe_field_session=v4\./);
    const persisted = decryptTestStateFile<{ families: Array<{ device_id: string }> }>(
      { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
      await readFile(actorStatePath(), "utf8"),
      FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
    );
    assert.deepEqual(persisted.families.map((family) => family.device_id), [
      firstDeviceId, replacementDeviceId
    ]);
  } finally {
    setFieldLongSessionAdmissionHoldForTests(null);
    release();
    calls.forEach((call) => call.client.destroy());
    server.closeAllConnections();
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([
        new Promise<void>((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve());
        }),
        new Promise<never>((_, reject) => {
          timeout = setTimeout(() => reject(new Error("fixture server cleanup timed out")), 3_000);
        })
      ]);
    } finally {
      if (timeout) clearTimeout(timeout);
    }
  }
});

test("security revocation propagates actor storage contention without mutating the session", async () => {
  const session = await login("admission-security-device");
  const releaseLock = await holdExclusiveLock(actorLockPath());
  try {
    await assert.rejects(
      revokeAllFieldLongSessions(ACTOR_ID, "security_incident"),
      ExclusiveFileLockBusyError
    );
    assert.equal((await status(session.cookie)).authenticated, true);
  } finally {
    await releaseLock();
  }
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

test("duplicate long-session cookies are rejected on the public status path", async () => {
  const session = await login("duplicate-cookie-device");
  assert.equal((await status(session.cookie)).authenticated, true);
  assert.equal((await status("")).authenticated, false);

  const invalidCookie = "walksafe_field_session=invalid";
  for (const cookie of [
    `${invalidCookie}; ${session.cookie}`,
    `${session.cookie}; ${invalidCookie}`,
    `${session.cookie}; ${session.cookie}`
  ]) {
    const request = new Request("https://gateway.invalid/api/field-session", {
      headers: { cookie }
    });
    assert.equal(fieldLongSessionLookup(request).status, "invalid");
    assert.equal((await status(cookie)).authenticated, false);
  }
});

test("Backend-email families keep stored binding and old static state remains readable", async () => {
  const staticSession = await login("shared-device");
  const binding: BackendEmailAccountBinding = {
    actorId: ACTOR_ID,
    accountGeneration: 7,
    authEpoch: 11,
    deviceId: "shared-device"
  };
  const request = new Request("https://gateway.invalid/api/field-session");
  const backend = await backendSession(await establishBackendFieldLongSession(request, binding));
  const backendRequest = new Request(request.url, { headers: { cookie: backend.cookie } });
  assert.equal(fieldLongSessionLookup(backendRequest).status, "invalid");
  assert.equal((await backendFieldLongSessionLookup(
    backendRequest,
    async (stored) => {
      assert.deepEqual(stored, binding);
      return "current";
    }
  )).status, "authenticated");
  assert.equal((await status(staticSession.cookie)).authenticated, true);
  const persisted = decryptTestStateFile<{
    schema_version: number;
    families: Array<{ device_id: string }>;
    backend_families: Array<{ binding: BackendEmailAccountBinding; device_id: string }>;
  }>(
    { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
    await readFile(actorStatePath(), "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(persisted.schema_version, 1);
  assert.deepEqual(persisted.families.map((family) => family.device_id), ["shared-device"]);
  assert.deepEqual(persisted.backend_families[0]?.binding, binding);
  assert.equal(persisted.backend_families[0]?.device_id, binding.deviceId);
  assert.equal((await refresh(staticSession)).status, 200);
});

test("Backend refresh checks only stored binding and replay revokes every family of the account (decision 1)", async () => {
  const binding: BackendEmailAccountBinding = {
    actorId: ACTOR_ID,
    accountGeneration: 4,
    authEpoch: 9,
    deviceId: "backend-rotate-a"
  };
  const request = new Request("https://gateway.invalid/api/field-session");
  const first = await backendSession(await establishBackendFieldLongSession(request, binding));
  const second = await backendSession(await establishBackendFieldLongSession(
    request, { ...binding, deviceId: "backend-rotate-b" }
  ));
  const payload: FieldRefreshPayload = {
    actorId: first.actor_id,
    deviceId: first.device_id,
    familyId: first.family_id,
    rotation: first.rotation,
    refreshToken: first.refresh_token
  };
  const checked: BackendEmailAccountBinding[] = [];
  const checkCurrent = async (stored: Readonly<BackendEmailAccountBinding>) => {
    checked.push({ ...stored });
    return "current" as const;
  };
  const payloadWithForgedBinding = { ...payload, accountGeneration: 999, authEpoch: 999 };
  const rotated = await backendSession(await refreshBackendFieldLongSession(
    request,
    payloadWithForgedBinding,
    checkCurrent
  ));
  assert.equal(rotated.rotation, 1);
  assert.deepEqual(checked, [binding]);
  assert.equal((await backendFieldLongSessionLookup(
    new Request(request.url, { headers: { cookie: second.cookie } }), checkCurrent
  )).status, "authenticated");
  const legacyDevice = await login("backend-reuse-legacy-device");
  const shortCookie = await legacyLogin();
  const shortRevoked: string[] = [];
  const replay = await refreshBackendFieldLongSession(
    request, payload, checkCurrent, Date.now(), undefined, (actorId) => shortRevoked.push(actorId)
  );
  assert.equal(replay.status, 401);
  assert.equal((await replay.json() as { code: string }).code, "refresh_token_reuse_detected");
  assert.deepEqual(shortRevoked, [ACTOR_ID]);
  assert.equal((await backendFieldLongSessionLookup(
    new Request(request.url, { headers: { cookie: rotated.cookie } }), checkCurrent
  )).status, "revoked");
  // Owner decision 1: the other Backend device and the legacy family of the same actor end too.
  assert.equal((await backendFieldLongSessionLookup(
    new Request(request.url, { headers: { cookie: second.cookie } }), checkCurrent
  )).status, "revoked");
  assert.equal((await status(legacyDevice.cookie)).authenticated, false);
  const secondRefresh = await refreshBackendFieldLongSession(request, {
    actorId: second.actor_id,
    deviceId: second.device_id,
    familyId: second.family_id,
    rotation: second.rotation,
    refreshToken: second.refresh_token
  }, checkCurrent);
  assert.deepEqual(await secondRefresh.json(), {
    code: "field_session_revoked_reuse_detected",
    reauthentication_required: true
  });
  // login-F3: even the direct call (no short-session callback wired) ends the short session,
  // because the reuse watermark is written with the family revocation.
  assert.equal((await status(shortCookie)).authenticated, false);
  const stored = readStoredActorState();
  assert.deepEqual(
    [...stored.families, ...(stored.backend_families ?? [])].map((family) => family.revoke_reason),
    ["refresh_token_reuse", "refresh_token_reuse", "refresh_token_reuse"]
  );
});

test("public refresh rotates a nonstatic Backend family and rejects replay and forged actor", async () => {
  const binding: BackendEmailAccountBinding = {
    actorId: "backend-email-only",
    accountGeneration: 4,
    authEpoch: 9,
    deviceId: "public-backend-device"
  };
  bindBackendActorGeneration(binding.actorId, binding.accountGeneration);
  const request = new Request("https://gateway.invalid/api/field-session");
  const backend = await backendSession(await establishBackendFieldLongSession(request, binding));
  const legacy = await login("public-legacy-device");
  let backendChecks = 0;
  const publicRefresh = (
    session: Session,
    override: Record<string, unknown> = {}
  ): Promise<Response> => handleGatewayRequest(
    new Request(request.url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ...refreshPayload(session), ...override })
    }),
    {
      fetchImpl: async (input, init) => {
        backendChecks += 1;
        assert.equal(String(input), "http://127.0.0.1:8000/accounts/session-current");
        const headers = new Headers(init?.headers);
        assert.equal(headers.get("x-walksafe-actor-id"), binding.actorId);
        assert.equal(headers.get("x-walksafe-account-generation"), String(binding.accountGeneration));
        assertBackendActorAssertionV4(input, init, {
          actorId: binding.actorId,
          accountGeneration: binding.accountGeneration,
          authEpoch: binding.authEpoch
        });
        return new Response(null, { status: 204 });
      }
    }
  );

  const forgedActor = await publicRefresh(backend, { actor_id: ACTOR_ID });
  assert.equal(forgedActor.status, 401);
  assert.equal((await forgedActor.json() as { code: string }).code, "invalid_refresh_token");
  const forgedType = await publicRefresh(backend, { family_type: "backend" });
  assert.equal(forgedType.status, 400);
  const forgedBinding = await publicRefresh(backend, { accountGeneration: 999 });
  assert.equal(forgedBinding.status, 400);
  const wrongToken = await publicRefresh(backend, { refresh_token: "A".repeat(64) });
  assert.equal(wrongToken.status, 401);
  assert.equal(backendChecks, 0);

  const rotatedLegacy = await responseSession(await publicRefresh(legacy));
  assert.equal(rotatedLegacy.rotation, 1);
  assert.equal(backendChecks, 0);
  const rotatedBackend = await backendSession(await publicRefresh(backend));
  assert.equal(rotatedBackend.rotation, 1);
  assert.equal(backendChecks, 1);
  const replay = await publicRefresh(backend);
  assert.equal(replay.status, 401);
  assert.equal((await replay.json() as { code: string }).code, "refresh_token_reuse_detected");
  // login-F6: reuse detection does not wait for the Backend currency check.
  assert.equal(backendChecks, 1);
  assert.equal((await backendFieldLongSessionLookup(
    new Request(request.url, { headers: { cookie: rotatedBackend.cookie } }),
    async () => "current"
  )).status, "revoked");
});

test("public refresh with valid Backend proof stays unrotated while generation is inactive", async () => {
  const binding: BackendEmailAccountBinding = {
    actorId: "inactive-backend-email",
    accountGeneration: 5,
    authEpoch: 9,
    deviceId: "inactive-backend-device"
  };
  const request = new Request("https://gateway.invalid/api/field-session");
  // Deliberately omit generation binding to exercise the inactive-generation gate.
  const issued = await backendSession(await establishBackendFieldLongSession(request, binding));
  const attempt = (): Promise<Response> => handleGatewayRequest(
    new Request(request.url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(refreshPayload(issued))
    }),
    { fetchImpl: async () => new Response(null, { status: 204 }) }
  );
  const inactive = await attempt();
  assert.equal(inactive.status, 409);
  assert.equal(
    (await inactive.json() as { detail?: { code?: string } }).detail?.code,
    "account_generation_inactive"
  );
  bindBackendActorGeneration(binding.actorId, binding.accountGeneration);
  const rotated = await backendSession(await attempt());
  assert.equal(rotated.rotation, 1);
});

test("Backend binding outage leaves refresh proof intact; denial durably revokes", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const binding: BackendEmailAccountBinding = {
    actorId: ACTOR_ID,
    accountGeneration: 2,
    authEpoch: 3,
    deviceId: "backend-denied"
  };
  const issued = await backendSession(await establishBackendFieldLongSession(request, binding));
  const payload: FieldRefreshPayload = {
    actorId: issued.actor_id,
    deviceId: issued.device_id,
    familyId: issued.family_id,
    rotation: issued.rotation,
    refreshToken: issued.refresh_token
  };
  const before = await readFile(actorStatePath(), "utf8");
  const unavailable = await refreshBackendFieldLongSession(
    request, payload, async () => "unavailable"
  );
  assert.equal(unavailable.status, 503);
  assert.equal((await unavailable.json() as { code: string }).code, "backend_account_check_unavailable");
  assert.equal(await readFile(actorStatePath(), "utf8"), before);
  const denied = await refreshBackendFieldLongSession(request, payload, async () => "denied");
  assert.equal(denied.status, 401);
  assert.equal((await denied.json() as { code: string }).code, "backend_account_binding_denied");
  const persisted = decryptTestStateFile<{
    backend_families: Array<{ revoke_reason: string | null; revoked_at_epoch_ms: number | null }>;
  }>(
    { kind: "field-long-session", recordId: path.basename(actorStatePath()) },
    await readFile(actorStatePath(), "utf8"),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  assert.equal(persisted.backend_families[0]?.revoke_reason, "backend_binding_denied");
  assert.notEqual(persisted.backend_families[0]?.revoked_at_epoch_ms, null);
});

test("Backend refresh revalidates after async check without holding the actor lock", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const issued = await backendSession(await establishBackendFieldLongSession(request, {
    actorId: ACTOR_ID,
    accountGeneration: 1,
    authEpoch: 1,
    deviceId: "backend-check-race"
  }));
  let checkStarted!: () => void;
  let releaseCheck!: (result: "current") => void;
  const started = new Promise<void>((resolve) => { checkStarted = resolve; });
  const checked = new Promise<"current">((resolve) => { releaseCheck = resolve; });
  const pending = refreshBackendFieldLongSession(request, {
    actorId: issued.actor_id,
    deviceId: issued.device_id,
    familyId: issued.family_id,
    rotation: issued.rotation,
    refreshToken: issued.refresh_token
  }, async () => {
    checkStarted();
    return checked;
  });
  await started;
  assert.equal(await revokeAllFieldLongSessions(ACTOR_ID, "security_incident"), 1);
  releaseCheck("current");
  const response = await pending;
  assert.equal(response.status, 401);
  assert.equal((await response.json() as { code: string }).code, "field_session_revoked");
});

test("Backend rotation uses the existing durable failure boundary", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const issued = await backendSession(await establishBackendFieldLongSession(request, {
    actorId: ACTOR_ID,
    accountGeneration: 5,
    authEpoch: 6,
    deviceId: "backend-durable"
  }));
  const payload: FieldRefreshPayload = {
    actorId: issued.actor_id,
    deviceId: issued.device_id,
    familyId: issued.family_id,
    rotation: issued.rotation,
    refreshToken: issued.refresh_token
  };
  setFieldLongSessionStorageFaultForTests("file_fsync");
  const failed = await refreshBackendFieldLongSession(request, payload, async () => "current");
  assert.equal(failed.status, 503);
  const retried = await backendSession(await refreshBackendFieldLongSession(
    request, payload, async () => "current"
  ));
  assert.equal(retried.rotation, 1);
  setFieldLongSessionStorageFaultForTests("directory_fsync");
  const unknown = await refreshBackendFieldLongSession(request, {
    ...payload,
    rotation: retried.rotation,
    refreshToken: retried.refresh_token
  }, async () => "current");
  assert.equal(unknown.status, 500);
  assert.equal((await unknown.json() as { code: string }).code, "field_session_storage_outcome_unknown");
  const replay = await refreshBackendFieldLongSession(request, {
    ...payload,
    rotation: retried.rotation,
    refreshToken: retried.refresh_token
  }, async () => "current");
  assert.equal(replay.status, 401);
  assert.equal((await replay.json() as { code: string }).code, "refresh_token_reuse_detected");
});

test("S19-17 the walk route refuses v1 commands unless the legacy setting accepts them", async (t) => {
  const previousLedgerPath = process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH;
  process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = path.join(stateDirectory, "s19-17-walk-ledger.json");
  t.after(() => {
    if (previousLedgerPath === undefined) delete process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH;
    else process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = previousLedgerPath;
    process.env.WALKSAFE_FIELD_WALK_V1_COMMANDS = "accept";
  });
  const post = (cookie: string, command: Record<string, unknown>) =>
    handleGatewayRequest(new Request("https://gateway.invalid/api/field-walk", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify(command)
    }));
  const current = async (cookie: string): Promise<string> => {
    const response = await handleGatewayRequest(new Request(
      "https://gateway.invalid/api/field-walk", { headers: { cookie } }
    ));
    return (await response.json() as { result: string }).result;
  };
  const retired = {
    schema_version: "walksafe.field-walk-response.v1",
    code: "walk_command_version_retired",
    supported_schema_versions: ["walksafe.field-walk-command.v2"]
  };
  const holder = await login("s19-17-v1-device");
  const v1Start = {
    schema_version: "walksafe.field-walk-command.v1",
    request_id: "s19-17-v1-start-0001", action: "start", walk_id: "s19-17-walk-0001"
  };
  delete process.env.WALKSAFE_FIELD_WALK_V1_COMMANDS;
  const refused = await post(holder.cookie, v1Start);
  assert.equal(refused.status, 409);
  assert.deepEqual(await refused.json(), retired);
  assert.equal(await current(holder.cookie), "NONE");
  const v2 = await post(holder.cookie, {
    schema_version: "walksafe.field-walk-command.v2",
    request_id: "s19-17-v2-start-0001", action: "start", walk_id: "s19-17-walk-0001",
    issued_at_epoch_ms: Date.now()
  });
  assert.equal(v2.status, 200);
  assert.equal((await v2.json() as { result: string }).result, "ACQUIRED");
  process.env.WALKSAFE_FIELD_WALK_V1_COMMANDS = "reject";
  assert.equal((await post(holder.cookie, v1Start)).status, 409);
  // An unreadable value fails closed at request time and is a startup error.
  process.env.WALKSAFE_FIELD_WALK_V1_COMMANDS = "Accept";
  assert.deepEqual(await (await post(holder.cookie, v1Start)).json(), retired);
  assert.throws(
    () => assertFieldLongSessionConfiguration({ WALKSAFE_FIELD_WALK_V1_COMMANDS: "yes" }),
    /WALKSAFE_FIELD_WALK_V1_COMMANDS must be accept or reject/
  );
  process.env.WALKSAFE_FIELD_WALK_V1_COMMANDS = "accept";
  const accepted = await post(holder.cookie, v1Start);
  assert.equal(accepted.status, 200);
  assert.equal((await accepted.json() as { result: string }).result, "ALREADY_ACTIVE");
});
test("S19-18 refresh-token logout ends the walk lease held by that verified legacy family only", async (t) => {
  const previousLedgerPath = process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH;
  process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = path.join(stateDirectory, "s19-18-walk-ledger.json");
  t.after(() => {
    if (previousLedgerPath === undefined) delete process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH;
    else process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = previousLedgerPath;
  });
  const walkCommand = (cookie: string, command: Record<string, unknown>) =>
    handleGatewayRequest(new Request("https://gateway.invalid/api/field-walk", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: JSON.stringify({ schema_version: "walksafe.field-walk-command.v1", ...command })
    }));
  const walkResult = async (cookie: string): Promise<string> => {
    const response = await handleGatewayRequest(new Request(
      "https://gateway.invalid/api/field-walk", { headers: { cookie } }
    ));
    assert.equal(response.status, 200);
    return (await response.json() as { result: string }).result;
  };
  const refreshLogout = (payload: Record<string, unknown>) =>
    handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload)
    }));
  const holder = await login("s19-18-legacy-device");
  const started = await walkCommand(holder.cookie, {
    request_id: "s19-18-legacy-start-0001", action: "start", walk_id: "s19-18-legacy-walk-0001"
  });
  assert.equal((await started.json() as { result: string }).result, "ACQUIRED");

  // A refresh token that does not verify must not end the walk (204 is still returned).
  const forged = await refreshLogout({ ...refreshPayload(holder), refresh_token: "f".repeat(64) });
  assert.equal(forged.status, 204);
  assert.equal(await walkResult(holder.cookie), "ACTIVE");

  const verified = await refreshLogout(refreshPayload(holder));
  assert.equal(verified.status, 204);
  const observer = await login("s19-18-legacy-observer");
  assert.equal(await walkResult(observer.cookie), "NONE");
});

type StoredFamily = {
  family_id: string;
  device_id: string;
  rotation: number;
  consumed_refresh_digests_sha256: string[];
  current_refresh_digest_sha256: string | null;
  refresh_proof_key_b64url?: string;
  revoke_reason: string | null;
};

function readStoredActorState(actorId = ACTOR_ID): {
  bytes: number;
  families: StoredFamily[];
  backend_families?: StoredFamily[];
} {
  const context = { kind: "field-long-session" as const, recordId: path.basename(actorStatePath(actorId)) };
  const state = decryptTestStateFile<{ families: StoredFamily[]; backend_families?: StoredFamily[] }>(
    context,
    readFileSyncUtf8(actorStatePath(actorId)),
    FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  return { ...state, bytes: Buffer.byteLength(JSON.stringify(state)) };
}

function readFileSyncUtf8(target: string): string {
  return readFileSync(target, "utf8");
}

test("S19-7/S01-25 rotation reuse detection no longer grows the actor file per rotation", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const issuedAt = 1_800_000_000_000;
  const devices = 32;
  const rounds = 24;
  const sessions: SessionBody[] = [];
  for (let index = 0; index < devices; index += 1) {
    sessions.push(await responseSession(
      await establishFieldLongSession(request, ACTOR_ID, `s19-7-device-${index}`, issuedAt)
    ));
  }
  const firstIssued = sessions.map((entry) => ({ ...entry }));
  const refreshAll = async (now: number): Promise<void> => {
    for (let index = 0; index < devices; index += 1) {
      const current = sessions[index]!;
      const next = await refreshFieldLongSession(request, {
        actorId: ACTOR_ID, deviceId: current.device_id, familyId: current.family_id,
        rotation: current.rotation, refreshToken: current.refresh_token
      }, now);
      sessions[index] = await responseSession(next);
    }
  };
  await refreshAll(issuedAt + 30_000);
  const afterFirst = readStoredActorState().bytes;
  for (let round = 2; round <= rounds; round += 1) await refreshAll(issuedAt + round * 30_000);
  const afterAll = readStoredActorState();
  // Only fixed-width fields change; no per-rotation history is appended.
  assert.ok(afterAll.bytes - afterFirst <= devices * 8, `${afterFirst} -> ${afterAll.bytes}`);
  for (const family of afterAll.families) {
    assert.equal(family.rotation, rounds);
    assert.deepEqual(family.consumed_refresh_digests_sha256, []);
  }
  // Byte budget: every allowed active device at the rotation cap fits under the 4 MiB cap.
  assert.ok(afterAll.bytes * 2 < FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES / 64, String(afterAll.bytes));

  // Reuse detection is preserved for every consumed rotation, oldest included.
  const oldest = firstIssued[3]!;
  const replay = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: oldest.device_id, familyId: oldest.family_id,
    rotation: oldest.rotation, refreshToken: oldest.refresh_token
  }, issuedAt + (rounds + 1) * 30_000);
  assert.equal(replay.status, 401);
  assert.equal((await replay.json() as { code: string }).code, "refresh_token_reuse_detected");
  const afterReplay = readStoredActorState();
  assert.equal(
    afterReplay.families.find((family) => family.family_id === oldest.family_id)?.revoke_reason,
    "refresh_token_reuse"
  );
  // Owner decision 1 (2026-09-29): the reuse ends every device of the account.
  assert.deepEqual(
    [...new Set(afterReplay.families.map((family) => family.revoke_reason))],
    ["refresh_token_reuse"]
  );
  assert.equal(afterReplay.families.length, devices);
  const survivor = sessions[4]!;
  const revoked = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: survivor.device_id, familyId: survivor.family_id,
    rotation: survivor.rotation, refreshToken: survivor.refresh_token
  }, issuedAt + (rounds + 2) * 30_000);
  assert.equal(revoked.status, 401);
  assert.equal((await revoked.json() as { code: string }).code, "field_session_revoked_reuse_detected");
});

test("S19-7 a forged past-rotation token is rejected without revoking the family", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const issuedAt = 1_800_000_000_000;
  let current = await responseSession(
    await establishFieldLongSession(request, ACTOR_ID, "s19-7-forged-device", issuedAt)
  );
  for (let step = 1; step <= 3; step += 1) {
    current = await responseSession(await refreshFieldLongSession(request, {
      actorId: ACTOR_ID, deviceId: current.device_id, familyId: current.family_id,
      rotation: current.rotation, refreshToken: current.refresh_token
    }, issuedAt + step * 30_000));
  }
  const forgedTokens = [
    // Right shape, wrong proof.
    current.refresh_token.slice(0, 43) + "A".repeat(current.refresh_token.length - 43),
    // Legacy-shaped random token.
    "Q".repeat(64)
  ];
  for (const refreshToken of forgedTokens) {
    for (const rotation of [0, 1, 2]) {
      const forged = await refreshFieldLongSession(request, {
        actorId: ACTOR_ID, deviceId: current.device_id, familyId: current.family_id,
        rotation, refreshToken
      }, issuedAt + 200_000);
      assert.equal(forged.status, 401);
      assert.equal((await forged.json() as { code: string }).code, "invalid_refresh_token");
    }
  }
  assert.equal(readStoredActorState().families[0]!.revoke_reason, null);
  assert.equal((await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: current.device_id, familyId: current.family_id,
    rotation: current.rotation, refreshToken: current.refresh_token
  }, issuedAt + 210_000)).status, 200);
});

test("R19-GB-05 a stale proof token with a wrong rotation is 401 invalid_refresh_token without revoke", async () => {
  // Pins S19-7 rotation-bound reuse detection: a consumed proof-format token counts as
  // reuse only when presented with the rotation it was issued for.
  const request = new Request("https://gateway.invalid/api/field-session");
  const issuedAt = 1_800_000_000_000;
  const issued: SessionBody[] = [await responseSession(
    await establishFieldLongSession(request, ACTOR_ID, "r19-gb-05-device", issuedAt)
  )];
  for (let step = 1; step <= 3; step += 1) {
    const previous = issued[issued.length - 1]!;
    issued.push(await responseSession(await refreshFieldLongSession(request, {
      actorId: ACTOR_ID, deviceId: previous.device_id, familyId: previous.family_id,
      rotation: previous.rotation, refreshToken: previous.refresh_token
    }, issuedAt + step * 30_000)));
  }
  const stale = issued[1]!;
  const current = issued[issued.length - 1]!;
  assert.ok(stale.rotation < current.rotation);
  assert.match(stale.refresh_token, /^[A-Za-z0-9_-]{86}$/);
  const wrongRotations = [
    ...issued.map((entry) => entry.rotation).filter((rotation) => rotation !== stale.rotation),
    current.rotation + 1
  ];
  assert.ok(wrongRotations.includes(current.rotation));
  for (const rotation of wrongRotations) {
    const response = await refreshFieldLongSession(request, {
      actorId: ACTOR_ID, deviceId: stale.device_id, familyId: stale.family_id,
      rotation, refreshToken: stale.refresh_token
    }, issuedAt + 200_000);
    assert.equal(response.status, 401, `rotation ${rotation}`);
    assert.equal(
      (await response.json() as { code: string }).code,
      "invalid_refresh_token",
      `rotation ${rotation}`
    );
    const family = readStoredActorState().families[0]!;
    assert.equal(family.revoke_reason, null, `rotation ${rotation} must not revoke`);
    assert.equal(family.rotation, current.rotation);
  }
  // The family still rotates with its current token.
  const next = await responseSession(await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: current.device_id, familyId: current.family_id,
    rotation: current.rotation, refreshToken: current.refresh_token
  }, issuedAt + 210_000));
  assert.equal(next.rotation, current.rotation + 1);
  // Contrast: the same stale token with its own rotation is reuse and revokes the family.
  const replay = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: stale.device_id, familyId: stale.family_id,
    rotation: stale.rotation, refreshToken: stale.refresh_token
  }, issuedAt + 220_000);
  assert.equal(replay.status, 401);
  assert.equal((await replay.json() as { code: string }).code, "refresh_token_reuse_detected");
  assert.equal(readStoredActorState().families[0]!.revoke_reason, "refresh_token_reuse");
});

test("S19-7 a pre-existing digest-history family migrates once and keeps detecting old tokens", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const issuedAt = 1_800_000_000_000;
  const issued = await responseSession(
    await establishFieldLongSession(request, ACTOR_ID, "s19-7-legacy-device", issuedAt)
  );
  // Rewrite the stored family into the previous format: random legacy tokens and a digest list.
  const legacyConsumed = "L".repeat(64);
  const legacyCurrent = "M".repeat(64);
  const context = { kind: "field-long-session" as const, recordId: path.basename(actorStatePath()) };
  const state = decryptTestStateFile<{ families: Array<Record<string, unknown>> }>(
    context, await readFile(actorStatePath(), "utf8"), FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES
  );
  const legacy = state.families[0]!;
  delete legacy.refresh_proof_key_b64url;
  legacy.rotation = 1;
  legacy.consumed_refresh_digests_sha256 = [createHash("sha256").update(legacyConsumed).digest("hex")];
  legacy.current_refresh_digest_sha256 = createHash("sha256").update(legacyCurrent).digest("hex");
  await writeFile(actorStatePath(), encryptTestStateFile(context, state, FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES), "utf8");

  const migrated = await responseSession(await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: issued.device_id, familyId: issued.family_id,
    rotation: 1, refreshToken: legacyCurrent
  }, issuedAt + 30_000));
  assert.equal(migrated.rotation, 2);
  let stored = readStoredActorState().families[0]!;
  assert.equal(stored.consumed_refresh_digests_sha256.length, 2);
  assert.match(stored.refresh_proof_key_b64url ?? "", /^[A-Za-z0-9_-]{43}$/);
  const next = await responseSession(await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: migrated.device_id, familyId: migrated.family_id,
    rotation: migrated.rotation, refreshToken: migrated.refresh_token
  }, issuedAt + 60_000));
  stored = readStoredActorState().families[0]!;
  assert.equal(stored.consumed_refresh_digests_sha256.length, 2, "no growth after migration");
  assert.equal(next.rotation, 3);
  const replayLegacy = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: issued.device_id, familyId: issued.family_id,
    rotation: 1, refreshToken: legacyCurrent
  }, issuedAt + 90_000);
  assert.equal((await replayLegacy.json() as { code: string }).code, "refresh_token_reuse_detected");
});

test("S19-7 Backend families rotate without digest history and still revoke on replay", async () => {
  const request = new Request("https://gateway.invalid/api/field-session");
  const issuedAt = Date.now();
  const binding: BackendEmailAccountBinding = {
    actorId: "s19-7-backend-actor", accountGeneration: 3, authEpoch: 2, deviceId: "s19-7-backend-device"
  };
  const checkCurrent = async () => "current" as const;
  const first = await backendSession(await establishBackendFieldLongSession(request, binding, issuedAt));
  let current = first;
  for (let step = 1; step <= 5; step += 1) {
    const response = await refreshBackendFieldLongSession(request, {
      actorId: binding.actorId, deviceId: binding.deviceId, familyId: current.family_id,
      rotation: current.rotation, refreshToken: current.refresh_token
    }, checkCurrent, issuedAt + step * 1_000);
    current = await backendSession(response);
  }
  const stored = readStoredActorState(binding.actorId).backend_families![0]!;
  assert.equal(stored.rotation, 5);
  assert.deepEqual(stored.consumed_refresh_digests_sha256, []);
  const replay = await refreshBackendFieldLongSession(request, {
    actorId: binding.actorId, deviceId: binding.deviceId, familyId: first.family_id,
    rotation: first.rotation, refreshToken: first.refresh_token
  }, checkCurrent, issuedAt + 10_000);
  assert.equal((await replay.json() as { code: string }).code, "refresh_token_reuse_detected");
});

test("owner decision 2 accepts only an explicit unlimited idle/absolute TTL and keeps ordering", () => {
  const unlimited: NodeJS.ProcessEnv = {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "900",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "unlimited",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "unlimited"
  };
  assert.deepEqual(resolveFieldLongSessionConfig(unlimited), {
    accessTtlSeconds: 900,
    refreshIdleTtlSeconds: null,
    refreshAbsoluteTtlSeconds: null
  });
  assert.deepEqual(
    resolveFieldLongSessionConfig({ ...unlimited, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "3600" }),
    { accessTtlSeconds: 900, refreshIdleTtlSeconds: 3600, refreshAbsoluteTtlSeconds: null }
  );
  for (const environment of [
    // No implicit default: a missing or empty value is still a configuration error.
    { ...unlimited, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: undefined },
    { ...unlimited, WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "" },
    { ...unlimited, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "Unlimited" },
    { ...unlimited, WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "infinite" },
    // The short access credential never becomes unlimited.
    { ...unlimited, WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "unlimited" },
    // An unlimited idle window under a finite absolute cap is an inverted pair.
    { ...unlimited, WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "3600" },
    // Finite values keep their existing ranges.
    { ...unlimited, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "299" },
    { ...unlimited, WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: String(90 * 86_400 + 1) }
  ]) {
    assert.throws(
      () => assertFieldLongSessionConfiguration(environment),
      /field long-lived session TTL configuration is invalid/
    );
  }
});

test("owner decision 2 production example enables the unlimited long-lived login explicitly", async () => {
  const example = await readFile(
    path.join(process.cwd(), "..", "..", "deploy", "config", "walksafe-android-gateway.env.example"),
    "utf8"
  );
  const environment: NodeJS.ProcessEnv = {};
  for (const line of example.split("\n")) {
    const match = /^(WALKSAFE_FIELD_[A-Z_]+)=(.*)$/.exec(line);
    if (match) environment[match[1]!] = match[2]!;
  }
  assert.deepEqual(resolveFieldLongSessionConfig(environment), {
    accessTtlSeconds: 900,
    refreshIdleTtlSeconds: null,
    refreshAbsoluteTtlSeconds: null
  });
});

test("owner decision 2 unlimited login never expires by idle time, age or rotation count", async () => {
  process.env.WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS = "unlimited";
  process.env.WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS = "unlimited";
  // Public route: login, status and device list publish the unlimited expiries as null.
  const session = await login("unlimited-device");
  assert.equal(session.idle_expires_at_epoch_ms, null);
  assert.equal(session.absolute_expires_at_epoch_ms, null);
  assert.ok(session.access_expires_at_epoch_ms <= Date.now() + 60_000);
  const described = await status(session.cookie);
  assert.equal(described.authenticated, true);
  assert.equal(described.idle_expires_at_epoch_ms, null);
  assert.equal(described.absolute_expires_at_epoch_ms, null);
  const devices = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-session?devices=true",
    { headers: { cookie: session.cookie } }
  ));
  const listed = (await devices.json() as { devices: Array<Record<string, unknown>> }).devices;
  assert.deepEqual(listed.map((device) => [device.idle_expires_at_epoch_ms, device.absolute_expires_at_epoch_ms]),
    [[null, null]]);
  assert.deepEqual(
    readStoredActorState().families.map((family) => {
      const stored = family as unknown as Record<string, unknown>;
      return [stored.idle_expires_at_epoch_ms, stored.absolute_expires_at_epoch_ms];
    }),
    [[null, null]]
  );

  // Ten years without use: the refresh still rotates; the access credential stays short.
  const request = new Request("https://gateway.invalid/api/field-session");
  const later = Date.now() + 10 * 365 * 86_400_000;
  const rotated = await responseSession(await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: session.device_id, familyId: session.family_id,
    rotation: session.rotation, refreshToken: session.refresh_token
  }, later));
  assert.equal(rotated.rotation, 1);
  assert.equal(rotated.access_expires_at_epoch_ms, later + 60_000);
  assert.equal(rotated.idle_expires_at_epoch_ms, null);
  assert.equal(rotated.absolute_expires_at_epoch_ms, null);
  // Rotation stays one-time: the consumed token is reuse (decision 1 handles the scope).
  const replay = await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: session.device_id, familyId: session.family_id,
    rotation: session.rotation, refreshToken: session.refresh_token
  }, later + 1_000);
  assert.equal((await replay.json() as { code: string }).code, "refresh_token_reuse_detected");

  // The 4096-rotation cap is not a hidden maximum login age for an unlimited login.
  const next = await login("unlimited-rotation-device");
  const context = { kind: "field-long-session" as const, recordId: path.basename(actorStatePath()) };
  const state = decryptTestStateFile<{
    families: Array<{ family_id: string; rotation: number }>;
  }>(context, await readFile(actorStatePath(), "utf8"), FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES);
  state.families.find((entry) => entry.family_id === next.family_id)!.rotation = 4096;
  await writeFile(
    actorStatePath(),
    encryptTestStateFile(context, state, FIELD_LONG_SESSION_MAX_PLAINTEXT_BYTES),
    "utf8"
  );
  const beyondCap = await responseSession(await refreshFieldLongSession(request, {
    actorId: ACTOR_ID, deviceId: next.device_id, familyId: next.family_id,
    rotation: 4096, refreshToken: next.refresh_token
  }));
  assert.equal(beyondCap.rotation, 4097);
  const again = await responseSession(await refresh(beyondCap));
  assert.equal(again.rotation, 4098);
  assert.equal((await status(again.cookie)).rotation, 4098);

  // Changing the operator setting back to a finite idle window bounds the next rotation.
  process.env.WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS = IDLE_TTL_SECONDS;
  const bounded = await responseSession(await refresh(again));
  assert.equal(typeof bounded.idle_expires_at_epoch_ms, "number");
  assert.equal(bounded.absolute_expires_at_epoch_ms, null);
});

test("owner decision 2 Backend-bound families also keep an unlimited login", async () => {
  process.env.WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS = "unlimited";
  process.env.WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS = "unlimited";
  const binding: BackendEmailAccountBinding = {
    actorId: "backend-unlimited-actor",
    accountGeneration: 2,
    authEpoch: 3,
    deviceId: "backend-unlimited-device"
  };
  const request = new Request("https://gateway.invalid/api/field-session");
  const issuedAt = Date.now();
  const first = await backendSession(await establishBackendFieldLongSession(request, binding, issuedAt));
  assert.equal(first.idle_expires_at_epoch_ms, null);
  assert.equal(first.absolute_expires_at_epoch_ms, null);
  const checkCurrent = async () => "current" as const;
  const later = issuedAt + 5 * 365 * 86_400_000;
  const rotated = await backendSession(await refreshBackendFieldLongSession(request, {
    actorId: binding.actorId, deviceId: binding.deviceId, familyId: first.family_id,
    rotation: first.rotation, refreshToken: first.refresh_token
  }, checkCurrent, later));
  assert.equal(rotated.rotation, 1);
  assert.equal(rotated.idle_expires_at_epoch_ms, null);
  assert.equal(rotated.absolute_expires_at_epoch_ms, null);
  assert.equal((await backendFieldLongSessionLookup(
    new Request(request.url, { headers: { cookie: rotated.cookie } }), checkCurrent, later + 1_000
  )).status, "authenticated");
  // Account-state denial from the Backend still ends the unlimited login.
  const denied = await refreshBackendFieldLongSession(request, {
    actorId: binding.actorId, deviceId: binding.deviceId, familyId: rotated.family_id,
    rotation: rotated.rotation, refreshToken: rotated.refresh_token
  }, async () => "denied" as const, later + 2_000);
  assert.equal((await denied.json() as { code: string }).code, "backend_account_binding_denied");
});
