import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import type { GatewayFetch } from "../src/backend.js";

const CASES = {
  passwordSuccess: "six different password accounts on one IP do not create failure entries",
  staticSuccess: "six different static accounts on one IP do not create failure entries",
  sharedIpSuccess: "121 distinct password accounts on one public IP do not consume failure budgets",
  passwordFailures: "password failures accumulate without successful login resetting the IP bound",
  staticFailures: "static credential failures accumulate without successful login resetting the IP bound",
  mixedFailures: "password and static credential failures share the existing IP bound",
  globalBudget: "240 successful admissions consume only the sliding total request budget",
  globalFailureBudget: "120 confirmed failures across IPs trigger the independent global failure lockout",
  malformedBudget: "malformed login consumes total budget without creating a failure",
  upstreamErrors: "upstream unavailability and invalid replies are not confirmed credential failures",
  privacyFailure: "privacy storage failure after valid authentication is not a credential failure",
  sessionFailure: "session storage failure after valid authentication is not a credential failure",
  legacyLogs: "unclassified legacy timestamps remain a conservative bound until existing expiry",
  pendingRequests: "five completed failures block new admissions but do not cancel admitted authentication",
  admissionBusy: "an occupied admission lock fails before authentication or recording",
  completionBusy: "failure completion lock contention is reported instead of silently losing the record",
  admissionIo: "admission storage errors propagate before authentication",
  completionIo: "failure completion storage errors propagate after the recorded admission",
  fileCapacity: "the existing 256 client-file capacity remains fail closed"
} as const;

type Scenario = keyof typeof CASES;

// Parent safety only: expiry always fails, kills and reaps the child.
const CHILD_SAFETY_TIMEOUT_MS = 30_000;
const CLIENT_IP = "192.0.2.10";
const FIELD_TOKEN = "synthetic-accounting-field-token-000000000000";
const SESSION_SECRET = "synthetic-accounting-session-secret-000000000000000000000000";
const STATIC_ACCOUNTS = Array.from({ length: 6 }, (_, index) => ({
  actor_id: "accounting-static-" + index,
  token: "synthetic-accounting-static-token-" + index + "-000000000000"
}));

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
}

async function runScenario(scenario: Scenario): Promise<void> {
  const { handleGatewayRequest } = await import("../src/routes.js");
  const { withExclusiveFileLockAsync } = await import("../src/exclusive-file-lock.js");
  const stateRoot = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  assert.ok(stateRoot);
  assert.equal(path.resolve(stateRoot), stateRoot);
  mkdirSync(stateRoot, { recursive: true, mode: 0o700 });
  const globalPath = path.join(stateRoot, "global-attempts.log");
  const globalFailurePath = path.join(stateRoot, "global-failures.log");
  const clientPath = path.join(
    stateRoot,
    "field-" + createHash("sha256").update("field:" + CLIENT_IP).digest("hex") + ".log"
  );
  const lockPath = path.join(stateRoot, "gateway-login-state.lock");
  const originalNow = Date.now;
  let now = originalNow();
  Date.now = () => now;
  let fetchCalls = 0;

  const raw = (target: string): string => existsSync(target) ? readFileSync(target, "utf8") : "";
  const entries = (target: string): string[] => raw(target).split("\n").filter(line => line !== "");
  const actorId = (index: number): string =>
    "00000000-0000-4000-8000-" + index.toString(16).padStart(12, "0");
  const authenticated = (index: number): Response => Response.json({
    schema_version: "walksafe.account-authentication.v1",
    actor_id: actorId(index),
    account_generation: 1,
    auth_epoch: 1
  });
  const rejected = (): Response => Response.json(
    { detail: { code: "invalid_account_credentials" } },
    { status: 401 }
  );
  const post = (
    payload: Record<string, unknown>,
    upstream: () => Response | Promise<Response> = () => { throw new Error("unexpected backend call"); },
    clientIp = CLIENT_IP
  ): Promise<Response> => {
    const request = new Request("https://gateway.invalid/api/field-session", {
      method: "POST",
      headers: { "content-type": "application/json", "x-real-ip": clientIp },
      body: JSON.stringify(payload)
    });
    const fetchImpl: GatewayFetch = async (input, init) => {
      fetchCalls += 1;
      assert.equal(String(input), "http://127.0.0.1:8000/accounts/authenticate");
      assert.equal(init?.method, "POST");
      assert.equal(init?.redirect, "error");
      assert.ok(init?.signal);
      assert.equal(new Headers(init?.headers).get("x-walksafe-field-test-token"), FIELD_TOKEN);
      return upstream();
    };
    return handleGatewayRequest(request, { fetchImpl });
  };
  const password = (
    index: number,
    upstream: () => Response | Promise<Response> = () => authenticated(index),
    clientIp = CLIENT_IP
  ): Promise<Response> => post({
    grant_type: "password",
    email: "accounting-" + index + "@example.test",
    password: "synthetic-password-123",
    remember_me: true,
    device_id: "accounting-device-" + index
  }, upstream, clientIp);
  const staticLogin = (index = 0, valid = true): Promise<Response> => {
    const account = STATIC_ACCOUNTS[index]!;
    return post({
      actor_id: account.actor_id,
      token: valid ? account.token : "synthetic-definitely-wrong-token-000000000000"
    });
  };
  const success = async (response: Response): Promise<void> => {
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.match(response.headers.get("set-cookie") ?? "", /Max-Age=43200(?:;|$)/);
    assert.deepEqual(await response.json(), { session_scope: "general" });
  };
  const failure = async (response: Response, kind: "password" | "static"): Promise<void> => {
    assert.equal(response.status, 401);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(response.headers.has("set-cookie"), false);
    assert.deepEqual(
      await response.json(),
      kind === "password"
        ? { detail: { code: "invalid_account_credentials" } }
        : { code: "invalid_field_test_token" }
    );
  };
  const blocked = async (response: Response, code: string, retryAfter = "900"): Promise<void> => {
    assert.equal(response.status, 429);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(response.headers.get("retry-after"), retryAfter);
    assert.equal(response.headers.has("set-cookie"), false);
    const body = await response.json() as { code: string };
    assert.equal(body.code, code);
  };
  const busy = async (response: Response): Promise<void> => {
    assert.equal(response.status, 503);
    assert.equal(response.headers.get("retry-after"), "1");
    assert.equal(response.headers.has("set-cookie"), false);
    const body = await response.json() as { code: string };
    assert.equal(body.code, "gateway_login_busy");
  };
  const noClientEntries = (): void => {
    assert.equal(existsSync(clientPath), false);
  };
  const noFailureEntries = (): void => {
    assert.equal(existsSync(globalFailurePath), false);
    noClientEntries();
  };
  const originalGlobal = (): string => {
    assert.equal(entries(globalPath).length, 1);
    return raw(globalPath);
  };

  try {
    switch (scenario) {
      case "passwordSuccess": {
        for (let index = 0; index < 6; index += 1) await success(await password(index));
        assert.equal(fetchCalls, 6);
        assert.equal(entries(globalPath).length, 6);
        noFailureEntries();
        break;
      }
      case "staticSuccess": {
        for (let index = 0; index < 6; index += 1) await success(await staticLogin(index));
        assert.equal(fetchCalls, 0);
        assert.equal(entries(globalPath).length, 6);
        noFailureEntries();
        break;
      }
      case "sharedIpSuccess": {
        for (let index = 0; index < 121; index += 1) await success(await password(index));
        assert.equal(fetchCalls, 121);
        assert.equal(entries(globalPath).length, 121);
        noFailureEntries();
        break;
      }
      case "passwordFailures": {
        for (let index = 0; index < 3; index += 1) await failure(await password(index, rejected), "password");
        const previous = raw(clientPath);
        await success(await password(9));
        assert.equal(raw(clientPath), previous);
        for (let index = 3; index < 5; index += 1) await failure(await password(index, rejected), "password");
        assert.equal(entries(clientPath).length, 5);
        assert.equal(entries(globalPath).length, 6);
        assert.equal(entries(globalFailurePath).length, 5);
        assert.equal(fetchCalls, 6);
        const beforeGlobal = raw(globalPath);
        await blocked(await password(10), "gateway_login_rate_limited");
        assert.equal(fetchCalls, 6);
        assert.equal(raw(globalPath), beforeGlobal);
        break;
      }
      case "staticFailures": {
        for (let index = 0; index < 3; index += 1) await failure(await staticLogin(index, false), "static");
        const previous = raw(clientPath);
        await success(await staticLogin(5));
        assert.equal(raw(clientPath), previous);
        for (let index = 3; index < 5; index += 1) await failure(await staticLogin(index, false), "static");
        assert.equal(entries(clientPath).length, 5);
        assert.equal(entries(globalPath).length, 6);
        assert.equal(entries(globalFailurePath).length, 5);
        const beforeGlobal = raw(globalPath);
        await blocked(await staticLogin(5), "gateway_login_rate_limited");
        assert.equal(raw(globalPath), beforeGlobal);
        assert.equal(fetchCalls, 0);
        break;
      }
      case "mixedFailures": {
        for (let index = 0; index < 3; index += 1) await failure(await password(index, rejected), "password");
        for (let index = 0; index < 2; index += 1) await failure(await staticLogin(index, false), "static");
        assert.equal(entries(clientPath).length, 5);
        assert.equal(entries(globalPath).length, 5);
        assert.equal(entries(globalFailurePath).length, 5);
        await blocked(await password(8), "gateway_login_rate_limited");
        await blocked(await staticLogin(5), "gateway_login_rate_limited");
        assert.equal(fetchCalls, 3);
        assert.equal(entries(globalPath).length, 5);
        break;
      }
      case "globalBudget": {
        for (let index = 0; index < 240; index += 1) await success(await staticLogin(index % 6));
        noFailureEntries();
        assert.equal(entries(globalPath).length, 240);
        const before = raw(globalPath);
        await blocked(await staticLogin(), "gateway_login_total_rate_limited", "300");
        assert.equal(raw(globalPath), before);
        assert.equal(fetchCalls, 0);
        now += 5 * 60 * 1000 + 1;
        await success(await staticLogin());
        assert.equal(entries(globalPath).length, 241);
        noFailureEntries();
        break;
      }
      case "globalFailureBudget": {
        for (let index = 0; index < 120; index += 1) {
          const clientIp = "198.51.100." + (Math.floor(index / 5) + 1);
          await failure(await password(index, rejected, clientIp), "password");
        }
        assert.equal(entries(globalPath).length, 120);
        assert.equal(entries(globalFailurePath).length, 120);
        const before = raw(globalPath);
        await blocked(await password(121, undefined, "198.51.100.25"), "gateway_login_global_rate_limited");
        assert.equal(raw(globalPath), before);
        assert.equal(fetchCalls, 120);
        now += 15 * 60 * 1000 + 1;
        await success(await password(122, undefined, "198.51.100.25"));
        assert.equal(entries(globalFailurePath).length, 120);
        break;
      }
      case "malformedBudget": {
        const response = await post({ grant_type: "password" });
        assert.equal(response.status, 400);
        assert.deepEqual(await response.json(), { code: "field_session_request_invalid" });
        assert.equal(entries(globalPath).length, 1);
        noFailureEntries();
        assert.equal(fetchCalls, 0);
        break;
      }
      case "upstreamErrors": {
        // S01-20: a Backend 503 whose code is not a registered 503 authenticate code is
        // not relayed (and never counts as a credential failure).
        const unavailable = await password(0, () => Response.json(
          { detail: { code: "invalid_account_credentials" } },
          { status: 503 }
        ));
        assert.equal(unavailable.status, 502);
        assert.deepEqual(await unavailable.json(), { detail: { code: "gateway_account_upstream_invalid" } });
        const invalidSuccess = await password(1, () => Response.json({ schema_version: "invalid" }));
        assert.equal(invalidSuccess.status, 502);
        assert.deepEqual(await invalidSuccess.json(), { detail: { code: "gateway_account_upstream_invalid" } });
        const invalidRejection = await password(2, () => new Response("not-json", {
          status: 401,
          headers: { "content-type": "text/plain" }
        }));
        assert.equal(invalidRejection.status, 502);
        assert.deepEqual(await invalidRejection.json(), { detail: { code: "gateway_account_upstream_invalid" } });
        assert.equal(fetchCalls, 3);
        assert.equal(entries(globalPath).length, 3);
        noFailureEntries();
        break;
      }
      case "privacyFailure": {
        const response = await password(0, () => {
          const directory = path.join(stateRoot, "privacy-rights");
          mkdirSync(directory, { recursive: true, mode: 0o700 });
          writeFileSync(path.join(directory, "ledger.json"), "{}", { mode: 0o600, flag: "wx" });
          return authenticated(0);
        });
        assert.equal(response.status, 503);
        assert.deepEqual(await response.json(), { code: "privacy_ledger_unavailable" });
        assert.equal(fetchCalls, 1);
        assert.equal(entries(globalPath).length, 1);
        noFailureEntries();
        break;
      }
      case "sessionFailure": {
        const response = await password(0, () => {
          writeFileSync(path.join(stateRoot, "sessions"), "not-a-directory", { mode: 0o600, flag: "wx" });
          return authenticated(0);
        });
        assert.equal(response.status, 503);
        const body = await response.json() as { code: string };
        assert.equal(body.code, "field_session_storage_unavailable");
        assert.equal(existsSync(path.join(stateRoot, "privacy-rights", "ledger.json")), true);
        assert.equal(fetchCalls, 1);
        assert.equal(entries(globalPath).length, 1);
        noFailureEntries();
        break;
      }
      case "legacyLogs": {
        const legacyClient = Array.from({ length: 4 }, () => String(now)).join("\n") + "\n";
        const legacyGlobal = String(now) + "\n";
        writeFileSync(clientPath, legacyClient, { mode: 0o600, flag: "wx" });
        writeFileSync(globalPath, legacyGlobal, { mode: 0o600, flag: "wx" });
        await success(await password(0));
        assert.equal(raw(clientPath), legacyClient);
        assert.equal(raw(globalPath), legacyGlobal + String(now) + "\n");
        assert.equal(existsSync(globalFailurePath), false);
        await failure(await password(1, rejected), "password");
        assert.equal(raw(clientPath), legacyClient + String(now) + "\n");
        assert.deepEqual(entries(globalFailurePath), [String(now)]);
        const before = raw(globalPath);
        await blocked(await password(2), "gateway_login_rate_limited");
        assert.equal(raw(globalPath), before);
        assert.equal(fetchCalls, 2);
        now += (5 + 15) * 60 * 1000 + 1;
        await success(await password(3));
        await failure(await password(4, rejected), "password");
        assert.deepEqual(entries(clientPath), [String(now)]);
        assert.deepEqual(entries(globalPath), [String(now), String(now)]);
        assert.deepEqual(entries(globalFailurePath), [String(now)]);
        break;
      }
      case "pendingRequests": {
        const upstreams = Array.from({ length: 6 }, () => deferred<Response>());
        const allStarted = deferred<void>();
        let started = 0;
        const pending = upstreams.map((upstream, index) => password(index, () => {
          started += 1;
          if (started === upstreams.length) allStarted.resolve(undefined);
          return upstream.promise;
        }));
        try {
          await Promise.race([
            allStarted.promise,
            Promise.all(pending).then(() => { throw new Error("authentication completed before release"); })
          ]);
          assert.equal(started, 6);
          assert.equal(entries(globalPath).length, 6);
          noFailureEntries();
          // This acquisition must succeed while all six backend replies are still pending.
          await withExclusiveFileLockAsync(lockPath, () => undefined);
          for (let index = 0; index < 5; index += 1) {
            upstreams[index]!.resolve(rejected());
            await failure(await pending[index]!, "password");
          }
          assert.equal(entries(clientPath).length, 5);
          assert.equal(entries(globalFailurePath).length, 5);
          await blocked(await password(20), "gateway_login_rate_limited");
          assert.equal(fetchCalls, 6);
          upstreams[5]!.resolve(authenticated(5));
          await success(await pending[5]!);
          assert.equal(entries(clientPath).length, 5);
          assert.equal(entries(globalPath).length, 6);
        } finally {
          for (const upstream of upstreams) upstream.resolve(rejected());
          await Promise.allSettled(pending);
        }
        break;
      }
      case "admissionBusy": {
        await withExclusiveFileLockAsync(lockPath, async () => {
          await busy(await password(0));
        });
        assert.equal(fetchCalls, 0);
        assert.equal(existsSync(globalPath), false);
        noFailureEntries();
        break;
      }
      case "completionBusy": {
        const reply = deferred<Response>();
        const started = deferred<void>();
        const pending = password(0, () => {
          started.resolve(undefined);
          return reply.promise;
        });
        try {
          await Promise.race([
            started.promise,
            pending.then(() => { throw new Error("authentication was not reached"); })
          ]);
          const before = originalGlobal();
          await withExclusiveFileLockAsync(lockPath, async () => {
            reply.resolve(rejected());
            await busy(await pending);
          });
          assert.equal(raw(globalPath), before);
          assert.equal(fetchCalls, 1);
          noFailureEntries();
        } finally {
          reply.resolve(rejected());
          await Promise.allSettled([pending]);
        }
        break;
      }
      case "admissionIo": {
        mkdirSync(clientPath, { mode: 0o700 });
        await assert.rejects(
          password(0),
          /gateway rate-limit file must be an owned regular file with mode 0600/
        );
        assert.equal(fetchCalls, 0);
        assert.equal(existsSync(globalPath), false);
        assert.equal(existsSync(globalFailurePath), false);
        break;
      }
      case "completionIo": {
        let before = "";
        await assert.rejects(
          password(0, () => {
            before = originalGlobal();
            mkdirSync(clientPath, { mode: 0o700 });
            return rejected();
          }),
          /gateway rate-limit file must be an owned regular file with mode 0600/
        );
        assert.notEqual(before, "");
        assert.equal(raw(globalPath), before);
        assert.equal(existsSync(globalFailurePath), false);
        assert.equal(fetchCalls, 1);
        break;
      }
      case "fileCapacity": {
        for (let index = 0; index < 256; index += 1) {
          writeFileSync(
            path.join(stateRoot, "field-" + index.toString(16).padStart(64, "0") + ".log"),
            "",
            { mode: 0o600, flag: "wx" }
          );
        }
        const response = await password(0);
        assert.equal(response.status, 503);
        const body = await response.json() as { code: string };
        assert.equal(body.code, "gateway_login_capacity_unavailable");
        assert.equal(fetchCalls, 0);
        assert.equal(existsSync(globalPath), false);
        noFailureEntries();
        break;
      }
    }
  } finally {
    Date.now = originalNow;
  }
}

async function runFreshCase(scenario: Scenario): Promise<void> {
  const directory = mkdtempSync(path.join(realpathSync(tmpdir()), "walksafe-login-accounting-"));
  try {
    const keyringPath = path.join(directory, "keyring.json");
    writeFileSync(keyringPath, JSON.stringify({
      schema_version: "walksafe.gateway-state-keyring.v1",
      keys: [{
        key_id: "login-accounting-test",
        status: "active",
        key_base64url: Buffer.alloc(32, 0x5c).toString("base64url")
      }]
    }), { encoding: "utf8", mode: 0o600, flag: "wx" });
    chmodSync(keyringPath, 0o600);
    const child = spawn(
      process.execPath,
      [fileURLToPath(import.meta.url), "--s19-login-accounting-child", scenario],
      {
        cwd: directory,
        env: {
          NODE_ENV: "test",
          WALKSAFE_GATEWAY_RATE_LIMIT_DIR: path.join(directory, "state"),
          WALKSAFE_GATEWAY_STATE_KEYRING_FILE: keyringPath,
          WALKSAFE_FIELD_TEST_TOKEN: FIELD_TOKEN,
          WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
          WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "x-real-ip",
          WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify(STATIC_ACCOUNTS)
        },
        stdio: ["ignore", "pipe", "pipe"]
      }
    );
    let stdout = "";
    let stderr = "";
    let expired = false;
    let spawnError: Error | undefined;
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => { stdout += chunk; });
    child.stderr.on("data", (chunk: string) => { stderr += chunk; });
    child.once("error", (error: Error) => { spawnError = error; });
    const closed = new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolve) => {
      child.once("close", (code, signal) => { resolve({ code, signal }); });
    });
    const watchdog = setTimeout(() => {
      expired = true;
      child.kill("SIGKILL");
    }, CHILD_SAFETY_TIMEOUT_MS);
    try {
      const result = await closed;
      assert.equal(expired, false, scenario + " exceeded its parent safety deadline: " + stderr);
      assert.ifError(spawnError);
      assert.equal(result.signal, null, stderr);
      assert.equal(result.code, 0, stderr);
      assert.equal(stdout, "login-accounting:" + scenario + ":ok\n");
    } finally {
      clearTimeout(watchdog);
      if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      await closed;
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

if (process.argv[2] === "--s19-login-accounting-child") {
  const scenario = process.argv[3];
  assert.ok(scenario && Object.hasOwn(CASES, scenario));
  await runScenario(scenario as Scenario);
  process.stdout.write("login-accounting:" + scenario + ":ok\n");
} else {
  for (const [scenario, label] of Object.entries(CASES)) {
    test(label, async () => { await runFreshCase(scenario as Scenario); });
  }
}
