import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import { establishBackendGatewaySession } from "../src/auth.js";
import { bindBackendActorGeneration } from "../src/privacy-rights.js";
import { ACTOR_ID_HEADER } from "../src/backend.js";
import { createGatewayRuntime } from "../src/gateway-runtime.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let stateDirectory = "";
let cookie = "";
before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-runtime-isolation-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_GATEWAY_SESSION_SECRET: "runtime-isolation-session-secret-12345678901234567890",
    WALKSAFE_FIELD_TEST_TOKEN: "runtime-isolation-field-token-1234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_VOICE_ENABLED: "true",
    WALKSAFE_VOICE_API_BASE_URL: "http://127.0.0.1:9001",
    WALKSAFE_VOICE_SERVICE_TOKEN: "runtime-isolation-voice-token-1234567890"
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(stateDirectory, "keyring.json"));
  bindBackendActorGeneration("runtime-account", 3);
  const grant = establishBackendGatewaySession(new Request("https://gateway.invalid/api/field-session"),
    { actorId: "runtime-account", accountGeneration: 3, authEpoch: 2 }, false);
  assert.equal(grant.status, 200);
  cookie = grant.headers.get("set-cookie")!.split(";", 1)[0]!;
});
after(async () => { await rm(stateDirectory, { recursive: true, force: true }); });

function request(pathname = "/api/speech/tts"): Request {
  return new Request(`https://gateway.invalid${pathname}`, {
    method: "POST",
    headers: { cookie, "cf-connecting-ip": "198.51.100.70", "content-type": "application/json" },
    body: JSON.stringify({
      schema_version: "walksafe.speech-tts-request.v1", text: "안내 시험",
      request_id: "11111111-1111-4111-8111-111111111111"
    })
  });
}

test("image and RAW admission retain same-runtime bounds and isolate independent owners", () => {
  const first = createGatewayRuntime();
  const second = createGatewayRuntime();
  const headers = new Headers({ [ACTOR_ID_HEADER]: "runtime-account" });
  const a = first.imageUpload.acquireImageUploadAdmission(request(), headers);
  const b = second.imageUpload.acquireImageUploadAdmission(request(), headers);
  assert.ok(a.release);
  assert.ok(b.release);
  assert.equal(first.imageUpload.acquireImageUploadAdmission(request(), headers).error?.status, 503);
  a.release();
  a.release();
  const next = first.imageUpload.acquireImageUploadAdmission(request(), headers);
  assert.ok(next.release);
  assert.equal(second.imageUpload.acquireImageUploadAdmission(request(), headers).error?.status, 503);
  next.release();
  b.release();

  const rawA = first.rawWrite.acquireRawWrite();
  const rawB = second.rawWrite.acquireRawWrite();
  assert.ok(rawA);
  assert.ok(rawB);
  assert.equal(first.rawWrite.acquireRawWrite(), null);
  rawA();
  rawA();
  const rawNext = first.rawWrite.acquireRawWrite();
  assert.ok(rawNext);
  assert.equal(second.rawWrite.acquireRawWrite(), null);
  rawNext();
  rawB();
});

test("refresh windows use their owner's clock and refusal counters", () => {
  let now = 10_000;
  const first = createGatewayRuntime(() => now);
  const second = createGatewayRuntime(() => now);
  for (const runtime of [first, second]) runtime.refresh.setFieldSessionRefreshAdmissionForTests({
    perClientPerMinute: 10, failuresPerFamilyPerMinute: 1, maxConcurrent: 1
  });
  const admitted = first.refresh.admitFieldSessionRefresh("198.51.100.70", "actor", "family");
  assert.ok(admitted.admitted);
  const busy = first.refresh.admitFieldSessionRefresh("198.51.100.70", "actor", "different");
  assert.equal(busy.admitted, false);
  if (!busy.admitted) assert.equal(busy.response.status, 503);
  const independent = second.refresh.admitFieldSessionRefresh("198.51.100.70", "actor", "family");
  assert.ok(independent.admitted);
  admitted.finish(401);
  admitted.finish(401);
  independent.finish(200);
  const refused = first.refresh.admitFieldSessionRefresh("198.51.100.70", "actor", "family");
  assert.equal(refused.admitted, false);
  if (!refused.admitted) assert.equal(refused.response.status, 429);
  const unaffected = second.refresh.admitFieldSessionRefresh("198.51.100.70", "actor", "family");
  assert.ok(unaffected.admitted);
  unaffected.finish(200);
  now += 60_000;
  const expired = first.refresh.admitFieldSessionRefresh("198.51.100.70", "actor", "family");
  assert.ok(expired.admitted);
  expired.finish(200);
});

test("public speech dispatch uses the supplied runtime admission and releases independently", async () => {
  const first = createGatewayRuntime();
  const second = createGatewayRuntime();
  const held = first.speech.acquireSpeechAdmission(request(), "tts");
  assert.ok(held.release);
  let voiceCalls = 0;
  const fetchImpl = async (url: string | URL | Request) => {
    if (new URL(String(url)).pathname === "/accounts/session-current") {
      return new Response(null, { status: 204 });
    }
    voiceCalls += 1;
    return new Response(null, { status: 503 });
  };
  const blocked = await handleGatewayRequest(request(), { runtime: first, fetchImpl });
  assert.equal(blocked.status, 503);
  assert.equal((await blocked.json() as { code: string }).code, "speech_capacity_busy");
  assert.equal(voiceCalls, 0);
  const independentResponse = await handleGatewayRequest(request(), { runtime: second, fetchImpl });
  await independentResponse.body?.cancel();
  assert.equal(voiceCalls, 1);
  held.release();
  const resumedResponse = await handleGatewayRequest(request(), { runtime: first, fetchImpl });
  await resumedResponse.body?.cancel();
  assert.equal(voiceCalls, 2);
});

test("capacity single-flight survives a cancelled waiter and memory remains per owner", async () => {
  const first = createGatewayRuntime();
  const second = createGatewayRuntime();
  const now = Date.now();
  const snapshot = {
    version: 1, observed_at: new Date(now - 1_000).toISOString(),
    expires_at: new Date(now + 60_000).toISOString(), level: "NORMAL",
    reason: "STORAGE_UTILIZATION"
  };
  let release!: () => void;
  const ready = new Promise<void>(resolve => { release = resolve; });
  let calls = 0;
  const fetchImpl = async () => { calls += 1; await ready; return Response.json(snapshot); };
  const abort = new AbortController();
  const cancelled = first.capacity.serverCapacityForFieldSession(
    new Request("https://gateway.invalid/api/field-session", { signal: abort.signal }), fetchImpl);
  const shared = first.capacity.serverCapacityForFieldSession(
    new Request("https://gateway.invalid/api/field-session"), fetchImpl);
  // Single-flight starts its fetch asynchronously after request signing.
  await new Promise<void>(resolve => setImmediate(resolve));
  assert.equal(calls, 1);
  abort.abort();
  await cancelled;
  release();
  assert.deepEqual(await shared, snapshot);
  assert.equal(first.capacity.currentServerCapacityLevelForTelemetry(), "NORMAL");
  assert.equal(second.capacity.currentServerCapacityLevelForTelemetry(), "UNKNOWN");
  assert.deepEqual(await second.capacity.serverCapacityForFieldSession(
    new Request("https://gateway.invalid/api/field-session"), fetchImpl), snapshot);
  assert.equal(calls, 2);
});
