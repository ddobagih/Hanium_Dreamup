import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import {
  chmodSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const CASES = {
  devicesFirst: "v7 devices survive optional-device v6 login and v6 replacement",
  compatibilityFirst: "v6 survives later v7 logins and same-device v7 replacement",
  slotLogout: "v6 logout and selected v7 revoke preserve other slots and the final watermark",
  lastDevice: "v7 logout and last-device revoke preserve the v6 compatibility session",
  generations: "generation and auth epoch changes invalidate old slots and retain rollback rejection",
  oldShapes: "old singleton v6 and v7 and device-ledger shapes remain readable",
  malformed: "malformed coexistence shapes fail closed without changing stored bytes",
  deviceCap: "device-capacity failures preserve both slots and the encrypted file",
  byteCap: "byte-capacity failures preserve both slots and the encrypted file",
  expiry: "coexisting sessions retain the existing cookie lifetime and expiration",
  securityEvent: "existing account security-event revocation removes both short-session slots"
} as const;

type Scenario = keyof typeof CASES;
type FixtureSession = {
  actorId: string;
  accountGeneration: number;
  authEpoch: number;
  expiresAtSeconds: number;
  sessionId: string;
  sessionKind: string;
  sessionScope: string;
  deviceId?: string;
};
type FixtureLedger = {
  actorId: string;
  sessions: FixtureSession[];
  compatibilitySession?: FixtureSession;
};

// Parent safety only. A timeout fails the test and is never accepted as a result.
const CHILD_SAFETY_TIMEOUT_MS = 5_000;
const ACTOR = "a".repeat(64);
const INITIAL_NOW = 1_700_000_000_000;

async function runScenario(scenario: Scenario): Promise<void> {
  const auth = await import("../src/auth.js");
  const store = await import("../src/encrypted-json-store.js");
  const stateRoot = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  assert.ok(stateRoot);
  let now = INITIAL_NOW;
  let generation = 3;
  let epoch = 7;
  const originalNow = Date.now;
  Date.now = () => now;

  const request = (cookie?: string): Request => new Request(
    "https://gateway.invalid/api/field-session",
    cookie === undefined ? {} : { headers: { cookie } }
  );
  const issue = (
    deviceId: string | null = null,
    accountGeneration = generation,
    authEpoch = epoch
  ): Response => auth.establishBackendGatewaySession(
    request(),
    {
      actorId: ACTOR,
      accountGeneration,
      authEpoch,
      ...(deviceId === null ? {} : { deviceId })
    },
    true
  );
  const login = async (
    deviceId: string | null = null,
    accountGeneration = generation,
    authEpoch = epoch
  ): Promise<string> => {
    const response = issue(deviceId, accountGeneration, authEpoch);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { session_scope: "general" });
    assert.equal(response.headers.get("cache-control"), "no-store");
    const header = response.headers.get("set-cookie");
    assert.ok(header);
    assert.match(header, /Max-Age=43200(?:;|$)/);
    assert.match(header, /; HttpOnly(?:;|$)/);
    assert.match(header, /; SameSite=Strict(?:;|$)/);
    assert.match(header, /; Secure(?:;|$)/);
    const cookie = header.split(";", 1)[0]!;
    assert.ok(cookie.startsWith("walksafe_field_session=" + (deviceId === null ? "v6." : "v7.")));
    return cookie;
  };
  const live = (...cookies: string[]): void => {
    for (const cookie of cookies) {
      assert.equal(auth.gatewaySessionActor(request(cookie)), ACTOR);
      assert.equal(auth.isGatewayGeneralSessionAuthorized(request(cookie)), true);
    }
  };
  const dead = (...cookies: string[]): void => {
    for (const cookie of cookies) {
      assert.equal(auth.isGatewaySessionAuthorized(request(cookie)), false);
    }
  };
  const stateFile = (): string => {
    const directory = path.join(stateRoot, "sessions");
    const names = readdirSync(directory).filter(name => /^field-[0-9a-f]{64}\.json$/.test(name));
    assert.equal(names.length, 1);
    return path.join(directory, names[0]!);
  };
  const readState = (): unknown => {
    const target = stateFile();
    return store.decryptGatewayStateJson(
      { kind: "short-session", recordId: path.basename(target) },
      readFileSync(target, "utf8"),
      auth.SHORT_SESSION_MAX_PLAINTEXT_BYTES
    ).value;
  };
  const readLedger = (): FixtureLedger => {
    const value = readState() as FixtureLedger;
    assert.ok(Array.isArray(value.sessions));
    return value;
  };
  const writeFixture = (value: unknown): void => {
    const target = stateFile();
    const encrypted = store.encryptGatewayStateJson(
      { kind: "short-session", recordId: path.basename(target) },
      value,
      auth.SHORT_SESSION_MAX_PLAINTEXT_BYTES
    );
    writeFileSync(target, encrypted, { encoding: "utf8", mode: 0o600 });
  };
  const devices = async (cookie: string, expected: string[]): Promise<void> => {
    const response = auth.listBackendDeviceGatewaySessionDevices(request(cookie));
    assert.ok(response);
    assert.equal(response.status, 200);
    const body = await response.json() as { devices: { device_id: string }[] };
    assert.deepEqual(body.devices.map(entry => entry.device_id).sort(), [...expected].sort());
  };
  const logout = (cookie: string): void => {
    const response = auth.clearGatewaySession(request(cookie));
    assert.equal(response.status, 204);
    assert.match(response.headers.get("set-cookie") ?? "", /Max-Age=0(?:;|$)/);
  };
  const failedWithoutWrite = (response: Response, status: number, before: Buffer): void => {
    assert.equal(response.status, status);
    assert.equal(response.headers.has("set-cookie"), false);
    assert.deepEqual(readFileSync(stateFile()), before);
  };
  const watermark = (): void => {
    assert.deepEqual(readState(), { actorId: ACTOR, accountGeneration: generation, authEpoch: epoch });
  };

  try {
    assert.equal(auth.SHORT_SESSION_MAX_PLAINTEXT_BYTES, 16 * 1024);
    assert.equal(auth.SHORT_DEVICE_SESSION_LIMIT, 32);
    switch (scenario) {
      case "devicesFirst": {
        const a = await login("device-A");
        const b = await login("device-B");
        const v6 = await login();
        live(a, b, v6);
        await devices(a, ["device-A", "device-B"]);
        assert.equal(auth.listBackendDeviceGatewaySessionDevices(request(v6)), null);
        const replacement = await login();
        dead(v6);
        live(a, b, replacement);
        await devices(b, ["device-A", "device-B"]);
        break;
      }
      case "compatibilityFirst": {
        const v6 = await login();
        const a = await login("device-A");
        const b = await login("device-B");
        live(v6, a, b);
        const replacement = await login("device-A");
        dead(a);
        live(v6, b, replacement);
        await devices(replacement, ["device-A", "device-B"]);
        break;
      }
      case "slotLogout": {
        const a = await login("device-A");
        const b = await login("device-B");
        const v6 = await login();
        logout(v6);
        dead(v6);
        live(a, b);
        assert.equal(Object.hasOwn(readLedger(), "compatibilitySession"), false);
        const replacement = await login();
        const response = auth.revokeBackendDeviceGatewaySession(request(a), "device-B");
        assert.ok(response);
        assert.equal(response.status, 204);
        assert.equal(response.headers.has("set-cookie"), false);
        dead(b);
        live(a, replacement);
        logout(a);
        dead(a);
        live(replacement);
        assert.equal((readState() as FixtureSession).sessionKind, "backend_account");
        logout(replacement);
        dead(replacement);
        watermark();
        const before = readFileSync(stateFile());
        failedWithoutWrite(issue(null, generation, epoch - 1), 409, before);
        watermark();
        break;
      }
      case "lastDevice": {
        const v6 = await login();
        const a = await login("device-A");
        logout(a);
        dead(a);
        live(v6);
        const b = await login("device-B");
        const response = auth.revokeBackendDeviceGatewaySession(request(b), "device-B");
        assert.ok(response);
        assert.equal(response.status, 204);
        assert.match(response.headers.get("set-cookie") ?? "", /Max-Age=0(?:;|$)/);
        dead(b);
        live(v6);
        assert.equal((readState() as FixtureSession).sessionKind, "backend_account");
        break;
      }
      case "generations": {
        const a = await login("device-A");
        const b = await login("device-B");
        const v6 = await login();
        epoch += 1;
        const c = await login("device-C");
        dead(a, b, v6);
        live(c);
        let before = readFileSync(stateFile());
        failedWithoutWrite(issue(null, generation, epoch - 1), 409, before);
        const sameEpochV6 = await login();
        live(c, sameEpochV6);
        generation += 1;
        epoch = 2;
        const newGenerationV6 = await login();
        dead(c, sameEpochV6);
        live(newGenerationV6);
        before = readFileSync(stateFile());
        failedWithoutWrite(issue("old-generation", generation - 1, Number.MAX_SAFE_INTEGER), 409, before);
        const d = await login("device-D");
        live(newGenerationV6, d);
        logout(newGenerationV6);
        const revoked = auth.revokeBackendDeviceGatewaySession(request(d), "device-D");
        assert.ok(revoked);
        assert.equal(revoked.status, 204);
        watermark();
        before = readFileSync(stateFile());
        failedWithoutWrite(issue(null, generation, epoch - 1), 409, before);
        failedWithoutWrite(issue(null, generation - 1, Number.MAX_SAFE_INTEGER), 409, before);
        live(await login());
        break;
      }
      case "oldShapes": {
        const v6 = await login();
        assert.equal(Object.hasOwn(readState() as object, "sessions"), false);
        live(v6);
        const a = await login("device-A");
        const flatDevice = readLedger().sessions[0]!;
        assert.equal(auth.validShortSessionStateForMaintenance(flatDevice, path.basename(stateFile())), true);
        writeFixture(flatDevice);
        live(a);
        dead(v6);
        const secondV6 = await login();
        live(a, secondV6);
        const current = readLedger();
        const oldLedger = { actorId: current.actorId, sessions: current.sessions };
        assert.equal(auth.validShortSessionStateForMaintenance(oldLedger, path.basename(stateFile())), true);
        writeFixture(oldLedger);
        live(a);
        dead(secondV6);
        live(a, await login());
        break;
      }
      case "malformed": {
        const a = await login("device-A");
        const b = await login("device-B");
        const v6 = await login();
        const baseline = readLedger();
        const compatibility = baseline.compatibilitySession;
        assert.ok(compatibility);
        const replaceCompatibility = (value: unknown): unknown => ({
          ...structuredClone(baseline), compatibilitySession: value
        });
        const missingEpoch: Partial<FixtureSession> = { ...compatibility };
        delete missingEpoch.authEpoch;
        const mixedDevices = structuredClone(baseline);
        mixedDevices.sessions[0]!.authEpoch += 1;
        const overflow = structuredClone(baseline);
        while (overflow.sessions.length <= auth.SHORT_DEVICE_SESSION_LIMIT) {
          const index = overflow.sessions.length;
          overflow.sessions.push({
            ...baseline.sessions[0]!,
            deviceId: "overflow-" + index,
            sessionId: ("overflow-" + index).padEnd(32, "x")
          });
        }
        const malformed: unknown[] = [
          { ...structuredClone(baseline), schema_version: 99 },
          replaceCompatibility(null),
          replaceCompatibility([]),
          replaceCompatibility({ ...compatibility, extra: true }),
          replaceCompatibility(missingEpoch),
          replaceCompatibility({ ...compatibility, actorId: "b".repeat(64) }),
          replaceCompatibility({ ...compatibility, accountGeneration: generation + 1 }),
          replaceCompatibility({ ...compatibility, authEpoch: epoch + 1 }),
          replaceCompatibility({ ...compatibility, sessionKind: "backend_account_device" }),
          replaceCompatibility({ ...compatibility, sessionId: baseline.sessions[0]!.sessionId }),
          { ...structuredClone(baseline), sessions: [] },
          mixedDevices,
          overflow
        ];
        for (const value of malformed) {
          assert.equal(auth.validShortSessionStateForMaintenance(value, path.basename(stateFile())), false);
          writeFixture(value);
          const before = readFileSync(stateFile());
          dead(a, b, v6);
          assert.deepEqual(readFileSync(stateFile()), before);
        }
        writeFixture(baseline);
        live(a, b, v6);
        break;
      }
      case "deviceCap": {
        const v6 = await login();
        const cookies: string[] = [];
        for (let index = 0; index < auth.SHORT_DEVICE_SESSION_LIMIT; index += 1) {
          cookies.push(await login("device-" + index));
        }
        const before = readFileSync(stateFile());
        const failed = issue("device-over-cap");
        failedWithoutWrite(failed, 503, before);
        assert.deepEqual(await failed.json(), { code: "field_session_device_capacity_unavailable" });
        live(v6, ...cookies);
        const replaced = await login("device-0");
        dead(cookies[0]!);
        live(v6, replaced, ...cookies.slice(1));
        assert.equal(readLedger().sessions.length, 32);
        break;
      }
      case "byteCap": {
        generation = Number.MAX_SAFE_INTEGER;
        epoch = Number.MAX_SAFE_INTEGER;
        const a = await login("primary-" + "p".repeat(120));
        const v6 = await login();
        const nearLimit = readLedger();
        const template = nearLimit.sessions[0]!;
        while (nearLimit.sessions.length < auth.SHORT_DEVICE_SESSION_LIMIT - 1) {
          const index = nearLimit.sessions.length;
          nearLimit.sessions.push({
            ...template,
            deviceId: "padding-" + index,
            sessionId: ("filler-" + index).padEnd(32, "x")
          });
        }
        const targetBytes = auth.SHORT_SESSION_MAX_PLAINTEXT_BYTES - 1;
        for (const session of nearLimit.sessions.slice(1)) {
          for (const key of ["deviceId", "sessionId"] as const) {
            const value = session[key]!;
            const remaining = targetBytes - Buffer.byteLength(JSON.stringify(nearLimit), "utf8");
            assert.ok(remaining >= 0);
            session[key] = value + "x".repeat(Math.min(128 - value.length, remaining));
          }
        }
        assert.equal(Buffer.byteLength(JSON.stringify(nearLimit), "utf8"), targetBytes);
        assert.equal(auth.validShortSessionStateForMaintenance(nearLimit, path.basename(stateFile())), true);
        writeFixture(nearLimit);
        live(a, v6);
        const before = readFileSync(stateFile());
        failedWithoutWrite(issue("next-" + "n".repeat(123)), 503, before);
        live(a, v6);
        break;
      }
      case "expiry": {
        const a = await login("device-A");
        const v6 = await login();
        now += 12 * 60 * 60 * 1000 - 1_000;
        live(a, v6);
        now += 1_000;
        dead(a, v6);
        const replacement = await login("device-B");
        live(replacement);
        dead(a, v6);
        assert.equal(Object.hasOwn(readLedger(), "compatibilitySession"), false);
        break;
      }
      case "securityEvent": {
        const a = await login("device-A");
        const v6 = await login();
        assert.equal(await auth.revokeFieldSessionsForSecurityEvent(ACTOR, "account_lock"), 0);
        dead(a, v6);
        break;
      }
    }
  } finally {
    Date.now = originalNow;
  }
}

async function runFreshCase(scenario: Scenario): Promise<void> {
  const directory = mkdtempSync(path.join(realpathSync(tmpdir()), "walksafe-session-coexistence-"));
  try {
    const keyringPath = path.join(directory, "keyring.json");
    writeFileSync(keyringPath, JSON.stringify({
      schema_version: "walksafe.gateway-state-keyring.v1",
      keys: [{
        key_id: "session-coexistence-test",
        status: "active",
        key_base64url: Buffer.alloc(32, 0x6b).toString("base64url")
      }]
    }), { encoding: "utf8", mode: 0o600, flag: "wx" });
    chmodSync(keyringPath, 0o600);
    const child = spawn(
      process.execPath,
      [fileURLToPath(import.meta.url), "--s19-coexistence-child", scenario],
      {
        cwd: directory,
        env: {
          NODE_ENV: "test",
          WALKSAFE_GATEWAY_RATE_LIMIT_DIR: path.join(directory, "state"),
          WALKSAFE_GATEWAY_STATE_KEYRING_FILE: keyringPath,
          WALKSAFE_FIELD_TEST_TOKEN: "synthetic-field-token-0000000000000000",
          WALKSAFE_GATEWAY_SESSION_SECRET: "synthetic-session-secret-000000000000000000000000"
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
      assert.equal(stdout, "session-coexistence:" + scenario + ":ok\n");
    } finally {
      clearTimeout(watchdog);
      if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      await closed;
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

if (process.argv[2] === "--s19-coexistence-child") {
  const scenario = process.argv[3];
  assert.ok(scenario && Object.hasOwn(CASES, scenario));
  await runScenario(scenario as Scenario);
  process.stdout.write("session-coexistence:" + scenario + ":ok\n");
} else {
  for (const [scenario, label] of Object.entries(CASES)) {
    test(label, async () => { await runFreshCase(scenario as Scenario); });
  }
}
