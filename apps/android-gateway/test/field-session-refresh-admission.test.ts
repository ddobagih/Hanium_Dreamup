// S19-10: refresh admission before any session state access.
// S19-11: a not-committed refresh storage failure is retryable and keeps the proof valid.
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, test } from "node:test";

import { resetGatewayStateEncryptionForTests } from "../src/encrypted-json-store.js";
import {
  fieldLongSessionStateReadsForTests,
  refreshPublicFieldLongSession,
  setFieldLongSessionAdmissionHoldForTests,
  setFieldLongSessionStorageFaultForTests
} from "../src/field-long-session.js";
import { fieldSessionErrorContractViolation } from "../src/field-session-errors.js";
import {
  admitFieldSessionRefresh,
  setFieldSessionRefreshAdmissionForTests
} from "../src/refresh-admission.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR_ID = "field-operator";
const ACCOUNT_TOKEN = "field-account-token-12345678901234567890";

type Session = {
  actor_id: string;
  device_id: string;
  family_id: string;
  rotation: number;
  refresh_token: string;
};

let stateDirectory = "";
let ipSuffix = 10;

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-refresh-admission-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:9",
    WALKSAFE_FIELD_TEST_TOKEN: "field-internal-token-12345678901234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "gateway-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "60",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "300",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "3600"
  });
  await configureTestStateEncryption(`${stateDirectory}-state-keyring.json`);
});

afterEach(() => {
  setFieldSessionRefreshAdmissionForTests(null);
  setFieldLongSessionAdmissionHoldForTests(null);
  setFieldLongSessionStorageFaultForTests(null);
});

after(async () => {
  resetGatewayStateEncryptionForTests();
  await rm(stateDirectory, { recursive: true, force: true });
  await rm(`${stateDirectory}-state-keyring.json`, { force: true });
});

function nextIp(): string {
  ipSuffix += 1;
  return `198.51.${Math.floor(ipSuffix / 250)}.${ipSuffix % 250}`;
}

async function longLogin(deviceId: string): Promise<Session> {
  const response = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": nextIp() },
    body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN, device_id: deviceId })
  }));
  assert.equal(response.status, 200);
  return await response.json() as Session;
}

function refresh(session: Session, ip: string, override: Partial<Session> = {}): Promise<Response> {
  const body = { ...session, ...override };
  return handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": ip },
    body: JSON.stringify({
      grant_type: "refresh_token",
      actor_id: body.actor_id,
      device_id: body.device_id,
      family_id: body.family_id,
      rotation: body.rotation,
      refresh_token: body.refresh_token
    })
  }));
}

test("S19-10 invalid refresh floods stop before the state read while valid refreshes keep rotating", async () => {
  setFieldSessionRefreshAdmissionForTests(null);
  const victim = await longLogin("s19-10-victim-device");
  const flooded = await longLogin("s19-10-flooded-device");
  const ip = nextIp();
  const readsBefore = fieldLongSessionStateReadsForTests();
  const statuses: number[] = [];
  let limited: Response | null = null;
  for (let index = 0; index < 100; index += 1) {
    const response = await refresh(flooded, ip, { refresh_token: "f".repeat(64) });
    statuses.push(response.status);
    if (response.status === 429 && limited === null) limited = response;
  }
  // 30 refused proofs reach the state; the other 70 stop at admission.
  assert.equal(statuses.filter((status) => status === 401).length, 30);
  assert.equal(statuses.filter((status) => status === 429).length, 70);
  assert.ok(fieldLongSessionStateReadsForTests() - readsBefore <= 30);
  assert.ok(limited);
  const body = await limited.json() as Record<string, unknown>;
  assert.equal(body.code, "field_session_refresh_rate_limited");
  assert.equal(body.retryable, true);
  assert.ok(Number(limited.headers.get("retry-after")) >= 1);
  assert.equal(fieldSessionErrorContractViolation(429, body), null);

  // A refresh that rotates never consumes the per-actor refusal budget; the per-client
  // budget (60 per minute) is still available from another address.
  setFieldSessionRefreshAdmissionForTests(null);
  let current = victim;
  const victimIp = nextIp();
  for (let index = 0; index < 40; index += 1) {
    const response = await refresh(current, victimIp);
    assert.equal(response.status, 200, `rotation ${index}`);
    current = await response.json() as Session;
  }
  assert.equal(current.rotation, 40);

  // Per client: the 61st refresh from one address within a minute is refused before reading.
  setFieldSessionRefreshAdmissionForTests(null);
  const sharedIp = nextIp();
  const clientReadsBefore = fieldLongSessionStateReadsForTests();
  const clientStatuses: number[] = [];
  for (let index = 0; index < 70; index += 1) {
    const response = await refresh(
      { ...flooded, actor_id: `field-operator-${String(index).padStart(3, "0")}` },
      sharedIp
    );
    clientStatuses.push(response.status);
  }
  assert.equal(clientStatuses.filter((status) => status === 429).length, 10);
  assert.ok(fieldLongSessionStateReadsForTests() - clientReadsBefore <= 60);
});

test("S19-10 load: concurrent valid refreshes keep rotating during an invalid-proof flood", async (t) => {
  setFieldSessionRefreshAdmissionForTests(null);
  // State reads of one admitted valid refresh, measured on a spare device.
  const spare = await longLogin("s19-10-load-spare");
  const spareReadsBefore = fieldLongSessionStateReadsForTests();
  assert.equal((await refresh(spare, nextIp())).status, 200);
  const readsPerValid = fieldLongSessionStateReadsForTests() - spareReadsBefore;
  setFieldSessionRefreshAdmissionForTests(null);

  const devices: Session[] = [];
  for (let index = 0; index < 12; index += 1) devices.push(await longLogin(`s19-10-load-${index}`));
  const deviceIps = devices.map(() => nextIp());
  const floodIps = Array.from({ length: 20 }, () => nextIp());
  const floodActors = 5;
  const readsBefore = fieldLongSessionStateReadsForTests();
  const floodStatuses: number[] = [];
  let validAttempts = 0;
  for (let round = 0; round < 3; round += 1) {
    // 100 invalid proofs per round in waves of 10 concurrent requests, alongside the devices.
    const flood = (async () => {
      for (let wave = 0; wave < 10; wave += 1) {
        await Promise.all(Array.from({ length: 10 }, async (_, slot) => {
          const index = wave * 10 + slot;
          const response = await refresh(
            { ...devices[0]!, actor_id: `field-operator-flood-${index % floodActors}` },
            floodIps[index % floodIps.length]!,
            { refresh_token: "e".repeat(64) }
          );
          floodStatuses.push(response.status);
          await response.arrayBuffer();
        }));
      }
    })();
    const valid = devices.map(async (device, index) => {
      for (let attempt = 0; attempt < 100; attempt += 1) {
        validAttempts += 1;
        const response = await refresh(device, deviceIps[index]!);
        if (response.status === 200) return await response.json() as Session;
        // Only the confirmed "nothing applied" busy answer happens; the same proof is retried.
        assert.equal(response.status, 503, `device ${index} round ${round}`);
        const body = await response.json() as Record<string, unknown>;
        assert.equal(body.code, "field_session_storage_busy");
        assert.equal(body.retryable, true);
        await new Promise((resolve) => setTimeout(resolve, 2));
      }
      throw new Error(`device ${index} never rotated in round ${round}`);
    });
    const [rotated] = await Promise.all([Promise.all(valid), flood]);
    rotated.forEach((session, index) => { devices[index] = session; });
  }
  // Every device rotated once per round; no valid refresh met a rate limit.
  for (const device of devices) assert.equal(device.rotation, 3);
  // Invalid proofs never rotate: refusal (401), admission limit (429), or busy (503) only.
  assert.equal(floodStatuses.length, 300);
  assert.ok(floodStatuses.every((status) => status === 401 || status === 429 || status === 503));
  // Per flooded actor at most its 30 refusals (plus the 16 that can be in flight together)
  // reach a state read in this minute; the rest stop at admission.
  const floodReadBound = floodActors * (30 + 16);
  const refusedAfterRead = floodStatuses.filter((status) => status === 401).length;
  assert.ok(refusedAfterRead <= floodReadBound, `${refusedAfterRead} refusals read the state`);
  assert.ok(floodStatuses.filter((status) => status === 429).length > 0);
  const reads = fieldLongSessionStateReadsForTests() - readsBefore;
  assert.ok(reads <= floodReadBound + readsPerValid * validAttempts, `${reads} state reads`);
  t.diagnostic(JSON.stringify({
    flood: Object.fromEntries([401, 429, 503].map((status) =>
      [status, floodStatuses.filter((value) => value === status).length])),
    valid_rotations: devices.length * 3,
    valid_attempts: validAttempts,
    state_reads: reads,
    reads_per_valid: readsPerValid
  }));
});

test("gw-F3 S19-10 an invalid-proof flood against one actor never locks that actor's own family", async (t) => {
  setFieldSessionRefreshAdmissionForTests(null);
  let victim = await longLogin("gw-f3-victim-device");
  const victimIp = nextIp();
  const floodIps = Array.from({ length: 6 }, () => nextIp());
  const statuses: number[] = [];
  let rotations = 0;
  const readsBefore = fieldLongSessionStateReadsForTests();
  for (let wave = 0; wave < 10; wave += 1) {
    // 30 invalid proofs per wave for the victim's actor: 20 under one guessed family, 10
    // under fresh random families; the victim's real family rotates in between.
    for (let index = 0; index < 30; index += 1) {
      const family = index < 20 ? "gw-f3-guessed-family-0001" : `gw-f3-random-${wave}-${index}-family`;
      const response = await refresh(victim, floodIps[(wave * 30 + index) % floodIps.length]!, {
        family_id: family, refresh_token: "d".repeat(64)
      });
      statuses.push(response.status);
      await response.arrayBuffer();
    }
    const response = await refresh(victim, victimIp);
    assert.equal(response.status, 200, `victim rotation in wave ${wave}`);
    victim = await response.json() as Session;
    rotations += 1;
  }
  assert.equal(victim.rotation, 10);
  assert.equal(rotations, 10);
  // The guessed family is refused 30 times and then limited before the state read; random
  // families are bounded only by the per-client limit (60 per address and minute).
  const count = (status: number) => statuses.filter((value) => value === status).length;
  assert.equal(count(401), 30 + 100);
  assert.equal(count(429), 170);
  assert.ok(fieldLongSessionStateReadsForTests() - readsBefore <= 130 + 10 * 4);
  t.diagnostic(JSON.stringify({ flood: { 401: count(401), 429: count(429) }, victim_rotations: rotations }));
});

test("gw-F3 S19-10 refusal windows are per (actor, family) and count only refresh verdicts", async () => {
  setFieldSessionRefreshAdmissionForTests(null);
  const realNow = Date.now;
  let clock = 1_000_000;
  Date.now = () => clock;
  const previousNodeEnv = process.env.NODE_ENV;
  try {
    // A sustained same-actor flood (30 refusals per window, below the per-client limit) under
    // a guessed family: the victim's family is admitted in every window.
    const victim: Array<boolean> = [];
    for (let window = 0; window < 10; window += 1) {
      clock = 1_000_000 + window * 60_001;
      for (let index = 0; index < 30; index += 1) {
        const attack = admitFieldSessionRefresh("203.0.113.66", "victim-actor", "guessed-family", clock);
        assert.equal(attack.admitted, true);
        if (attack.admitted) attack.finish(401);
      }
      assert.equal(admitFieldSessionRefresh("203.0.113.67", "victim-actor", "guessed-family", clock).admitted, false);
      for (const offset of [5_000, 50_000]) {
        clock = 1_000_000 + window * 60_001 + offset;
        const own = admitFieldSessionRefresh(`198.51.100.${window + 1}`, "victim-actor", "victim-family", clock);
        victim.push(own.admitted);
        if (own.admitted) own.finish(200);
      }
    }
    assert.deepEqual(victim, Array.from({ length: 20 }, () => true));
    // Client cancellation, deadline, storage and other answers never count as refusals.
    setFieldSessionRefreshAdmissionForTests(null);
    clock = 50_000_000;
    for (const status of [499, 504, 500, 503, 429, 200]) {
      for (let index = 0; index < 40; index += 1) {
        const attempt = admitFieldSessionRefresh(`198.51.101.${index}`, "flaky-actor", "flaky-family", clock);
        assert.equal(attempt.admitted, true, `after ${status} ${index}`);
        if (attempt.admitted) attempt.finish(status);
      }
    }
    // The refresh verdicts 400, 401, 403 and 409 do: the 31st attempt of that family is
    // limited, while another family of the same actor is not.
    for (let index = 0; index < 30; index += 1) {
      const attempt = admitFieldSessionRefresh(`198.51.102.${index}`, "verdict-actor", "verdict-family", clock);
      assert.equal(attempt.admitted, true, `verdict ${index}`);
      if (attempt.admitted) attempt.finish([400, 401, 403, 409][index % 4]!);
    }
    const limited = admitFieldSessionRefresh("198.51.103.1", "verdict-actor", "verdict-family", clock);
    assert.equal(limited.admitted, false);
    if (!limited.admitted) {
      assert.equal(limited.response.status, 429);
      assert.equal((await limited.response.json() as Record<string, unknown>).code, "field_session_refresh_rate_limited");
    }
    const otherFamily = admitFieldSessionRefresh("198.51.103.2", "verdict-actor", "other-family", clock);
    assert.equal(otherFamily.admitted, true);
    if (otherFamily.admitted) otherFamily.finish(200);

    // gw-F2 defense in depth: a production request without a usable client address still
    // meets the per-client limit in one shared bucket; outside production it is skipped.
    setFieldSessionRefreshAdmissionForTests(null);
    process.env.NODE_ENV = "production";
    const unbound: number[] = [];
    for (let index = 0; index < 61; index += 1) {
      const attempt = admitFieldSessionRefresh(null, "unbound-actor", `unbound-family-${index}`, clock);
      unbound.push(attempt.admitted ? 0 : attempt.response.status);
      if (attempt.admitted) attempt.finish(200);
    }
    assert.equal(unbound.filter((status) => status === 429).length, 1);
    assert.equal(unbound[60], 429);
    process.env.NODE_ENV = previousNodeEnv;
    setFieldSessionRefreshAdmissionForTests(null);
    for (let index = 0; index < 70; index += 1) {
      const attempt = admitFieldSessionRefresh(null, "unbound-actor", `unbound-family-${index}`, clock);
      assert.equal(attempt.admitted, true);
      if (attempt.admitted) attempt.finish(200);
    }
  } finally {
    Date.now = realNow;
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
    setFieldSessionRefreshAdmissionForTests(null);
  }
});

test("S19-10 concurrent valid refreshes rotate up to the global bound and the next is busy", async () => {
  setFieldSessionRefreshAdmissionForTests({ perClientPerMinute: 60, failuresPerFamilyPerMinute: 30, maxConcurrent: 4 });
  const sessions: Session[] = [];
  for (let index = 0; index < 5; index += 1) sessions.push(await longLogin(`s19-10-concurrent-${index}`));
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  setFieldLongSessionAdmissionHoldForTests(gate);
  const pending = sessions.slice(0, 4).map((session) => refresh(session, nextIp()));
  await new Promise((resolve) => setTimeout(resolve, 20));
  const readsBefore = fieldLongSessionStateReadsForTests();
  const overflow = await refresh(sessions[4]!, nextIp());
  assert.equal(overflow.status, 503);
  assert.equal(overflow.headers.get("retry-after"), "1");
  const overflowBody = await overflow.json() as Record<string, unknown>;
  assert.equal(overflowBody.code, "field_session_storage_busy");
  assert.equal(overflowBody.retryable, true);
  assert.equal(fieldLongSessionStateReadsForTests(), readsBefore);
  release();
  const results = await Promise.all(pending);
  assert.deepEqual(results.map((response) => response.status), [200, 200, 200, 200]);
  setFieldLongSessionAdmissionHoldForTests(null);
  // The same, unchanged proof of the refused request rotates once admission is free.
  assert.equal((await refresh(sessions[4]!, nextIp())).status, 200);
});

test("S19-11 a refresh storage failure before commit is retryable and keeps the proof valid", async () => {
  setFieldSessionRefreshAdmissionForTests(null);
  const session = await longLogin("s19-11-retryable-device");
  for (const stage of ["file_fsync", "rename"] as const) {
    setFieldLongSessionStorageFaultForTests(stage);
    const failed = await refresh(session, nextIp());
    setFieldLongSessionStorageFaultForTests(null);
    assert.equal(failed.status, 503, stage);
    assert.equal(failed.headers.get("retry-after"), "1");
    const body = await failed.json() as Record<string, unknown>;
    assert.equal(body.code, "field_session_storage_unavailable");
    assert.equal(body.retryable, true);
    assert.equal(body.reauthentication_required, undefined);
    assert.equal(fieldSessionErrorContractViolation(503, body), null);
  }
  const retried = await refresh(session, nextIp());
  assert.equal(retried.status, 200);
  const rotated = await retried.json() as Session;
  assert.equal(rotated.rotation, 1);

  setFieldLongSessionStorageFaultForTests("directory_fsync");
  const unknown = await refresh(rotated, nextIp());
  setFieldLongSessionStorageFaultForTests(null);
  assert.equal(unknown.status, 500);
  const unknownBody = await unknown.json() as Record<string, unknown>;
  assert.equal(unknownBody.code, "field_session_storage_outcome_unknown");
  assert.equal(unknownBody.retryable, false);
});

test("S19-11 a failure after the rotation committed is outcome unknown, not retryable", async () => {
  const session = await longLogin("s19-11-committed-device");
  const request = new Request("https://gateway.invalid/api/field-session", { method: "POST" });
  const response = await refreshPublicFieldLongSession(
    request,
    {
      actorId: session.actor_id,
      deviceId: session.device_id,
      familyId: session.family_id,
      rotation: session.rotation,
      refreshToken: session.refresh_token
    },
    true,
    async () => "current",
    (operation) => operation((commit) => {
      commit();
      throw new Error("guard release failed after the commit");
    })
  );
  assert.equal(response.status, 500);
  const body = await response.json() as Record<string, unknown>;
  assert.equal(body.code, "field_session_storage_outcome_unknown");
  assert.equal(body.retryable, false);
  // The rotation is durable: the presented proof is now consumed.
  assert.equal((await refresh(session, nextIp())).status, 401);
});
