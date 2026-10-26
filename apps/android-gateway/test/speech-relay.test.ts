import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer, type Server } from "node:http";
import { type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, beforeEach, test } from "node:test";

import { establishBackendGatewaySession } from "../src/auth.js";
import { createGatewayServer } from "../src/node-adapter.js";
import { handleGatewayRequest as routeRequest, type GatewayDependencies } from "../src/routes.js";
import {
  resetSpeechAdmissionForTests,
  SPEECH_STT_RESPONSE_SCHEMA,
  SPEECH_TTS_REQUEST_SCHEMA
} from "../src/speech-relay.js";
import type { GatewayTelemetryEvent } from "../src/telemetry.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const FIELD_TOKEN = "field-service-token-12345678901234567890";
const SESSION_SECRET = "gateway-session-secret-123456789012345678901234567890";
const VOICE_TOKEN = "dedicated-voice-token-12345678901234567890";
const REQUEST_ID = "018f2b63-8fb8-7cc2-98a1-4a4fd27c3001";
const MODEL_REVISION = "a".repeat(40);
const ACTOR_ID = "account-user-voice";
const CLIENT_IP = "198.51.100.77";

let stateDirectory = "";
let backendSessionStatus = 204;

// The router revalidates Backend account sessions before voice admission. Model that boundary
// separately from the Voice service mock so relay assertions actually reach the voice route.
function handleGatewayRequest(request: Request, dependencies: GatewayDependencies = {}): Promise<Response> {
  return routeRequest(request, {
    ...dependencies,
    fetchImpl: async (input, init) => {
      if (String(input) === "http://127.0.0.1:8000/accounts/session-current") {
        return new Response(null, { status: backendSessionStatus });
      }
      assert.ok(dependencies.fetchImpl, "unexpected upstream request");
      return dependencies.fetchImpl(input, init);
    }
  });
}

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-speech-relay-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_FIELD_TEST_TOKEN: FIELD_TOKEN,
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_VOICE_ENABLED: "true",
    WALKSAFE_VOICE_API_BASE_URL: "http://127.0.0.1:9001",
    WALKSAFE_VOICE_SERVICE_TOKEN: VOICE_TOKEN
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

beforeEach(() => {
  resetSpeechAdmissionForTests();
  backendSessionStatus = 204;
  for (const name of [
    "WALKSAFE_VOICE_STT_ACTOR_RATE_LIMIT",
    "WALKSAFE_VOICE_STT_MAX_BODY_BYTES",
    "WALKSAFE_VOICE_STT_RESPONSE_MAX_BYTES",
    "WALKSAFE_VOICE_STT_TIMEOUT_MS",
    "WALKSAFE_VOICE_TTS_ACTOR_RATE_LIMIT",
    "WALKSAFE_VOICE_TTS_RESPONSE_MAX_BYTES",
    "WALKSAFE_VOICE_TTS_TIMEOUT_MS"
  ]) delete process.env[name];
});

function backendSessionCookie(): string {
  const response = establishBackendGatewaySession(
    new Request("https://walksafe.example/api/field-session"),
    { actorId: ACTOR_ID, accountGeneration: 3, authEpoch: 2 },
    false
  );
  assert.equal(response.status, 200);
  const setCookie = response.headers.get("set-cookie") ?? "";
  assert.match(setCookie, /^walksafe_field_session=v6\./);
  return setCookie.split(";", 1)[0]!;
}

function speechHeaders(cookie: string, contentType: string): Record<string, string> {
  return {
    cookie,
    "cf-connecting-ip": CLIENT_IP,
    "content-type": contentType,
    "x-request-id": REQUEST_ID
  };
}

function voiceSttPayload(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schema_version: "walksafe.voice-stt-response.v1",
    transcript: "주변을 설명해줘",
    normalized: "주변을 설명해줘",
    intent: "describe_surroundings",
    score: 1,
    confidence: 1,
    slots: {},
    action: "execute",
    should_execute: true,
    reason: null,
    prompt: null,
    acoustic_confidence: 0.76,
    avg_logprob: -0.2,
    no_speech_probability: 0.05,
    acoustic_execution_allowed: true,
    language: "ko",
    duration_sec: 0.1,
    audio_duration_sec: 0.5,
    model: "Systran/faster-whisper-medium",
    model_revision: MODEL_REVISION,
    segments: [{ start: 0, end: 0.5, text: "주변을 설명해줘" }],
    ...extra
  };
}

function jsonVoiceStt(extra: Record<string, unknown> = {}): Response {
  return Response.json(voiceSttPayload(extra));
}

function wavBytes(durationSeconds = 0.1, sampleRate = 8_000): Uint8Array {
  const frames = Math.max(1, Math.floor(durationSeconds * sampleRate));
  const dataBytes = frames * 2;
  const bytes = new Uint8Array(44 + dataBytes);
  const view = new DataView(bytes.buffer);
  const writeAscii = (offset: number, value: string): void => {
    for (let index = 0; index < value.length; index += 1) {
      bytes[offset + index] = value.charCodeAt(index);
    }
  };
  writeAscii(0, "RIFF");
  view.setUint32(4, bytes.byteLength - 8, true);
  writeAscii(8, "WAVE");
  writeAscii(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeAscii(36, "data");
  view.setUint32(40, dataBytes, true);
  return bytes;
}

function ownedBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

test("unauthenticated STT is rejected before body read or upstream fetch", async () => {
  let fetches = 0;
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: { "content-type": "audio/wav", "cf-connecting-ip": CLIENT_IP },
      body: ownedBuffer(wavBytes())
    }),
    { fetchImpl: async () => { fetches += 1; return jsonVoiceStt(); } }
  );

  assert.equal(response.status, 401);
  assert.equal(fetches, 0);
});

test("Voice integration is disabled by default and fails closed before upstream fetch", async () => {
  const cookie = backendSessionCookie();
  process.env.WALKSAFE_VOICE_ENABLED = "false";
  let fetches = 0;
  try {
    const response = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/api/speech/stt", {
        method: "POST",
        headers: speechHeaders(cookie, "audio/wav"),
        body: ownedBuffer(wavBytes())
      }),
      { fetchImpl: async () => { fetches += 1; return jsonVoiceStt(); } }
    );
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { code: "speech_service_disabled" });
    assert.equal(fetches, 0);
  } finally {
    process.env.WALKSAFE_VOICE_ENABLED = "true";
  }
});

test("STT replaces spoofable headers and projects only transcript and acoustic evidence", async () => {
  const cookie = backendSessionCookie();
  const telemetry: GatewayTelemetryEvent[] = [];
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: {
        ...speechHeaders(cookie, "audio/wav"),
        authorization: "Bearer inbound-secret",
        "x-walksafe-actor-id": "spoofed-actor",
        "x-walksafe-actor-assertion": "spoofed-assertion",
        "x-walksafe-voice-service-token": "spoofed-voice-token"
      },
      body: ownedBuffer(wavBytes())
    }),
    {
      telemetrySink: event => telemetry.push(event),
      fetchImpl: async (input, init) => {
        assert.equal(String(input), "http://127.0.0.1:9001/speech/stt");
        assert.equal(init?.redirect, "error");
        const headers = new Headers(init?.headers);
        assert.equal(headers.get("x-walksafe-voice-service-token"), VOICE_TOKEN);
        assert.equal(headers.get("x-walksafe-actor-id"), ACTOR_ID);
        assert.equal(headers.get("x-walksafe-voice-client-ip"), CLIENT_IP);
        assert.equal(headers.get("authorization"), null);
        assert.equal(headers.get("cookie"), null);
        assert.equal(headers.get("x-walksafe-actor-assertion"), null);
        assert.ok(init?.body instanceof FormData);
        const entries = [...init.body.entries()];
        assert.equal(entries.length, 1);
        assert.equal(entries[0]?.[0], "audio");
        assert.ok(entries[0]?.[1] instanceof Blob);
        return jsonVoiceStt();
      }
    }
  );

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), {
    schema_version: SPEECH_STT_RESPONSE_SCHEMA,
    request_id: REQUEST_ID,
    transcript: "주변을 설명해줘",
    acoustic: {
      confidence: 0.76,
      avg_logprob: -0.2,
      no_speech_probability: 0.05,
      execution_allowed: true
    },
    model_revision: MODEL_REVISION
  });
  assert.deepEqual(telemetry.map(event => event.event_name), [
    "walksafe.gateway.request.prepared", "walksafe.gateway.request.finished"
  ]);
  const serialized = JSON.stringify(telemetry);
  for (const sensitive of ["주변을 설명해줘", VOICE_TOKEN, CLIENT_IP, "execute", "segments"]) {
    assert.equal(serialized.includes(sensitive), false);
  }
});

test("STT rejects unknown upstream fields and cumulative oversized request bodies", async () => {
  const cookie = backendSessionCookie();
  let fetches = 0;
  const malformed = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: speechHeaders(cookie, "audio/wav"),
      body: ownedBuffer(wavBytes())
    }),
    { fetchImpl: async () => jsonVoiceStt({ unexpected: "not exposed" }) }
  );
  assert.equal(malformed.status, 502);

  resetSpeechAdmissionForTests();
  backendSessionStatus = 204;
  process.env.WALKSAFE_VOICE_STT_MAX_BODY_BYTES = "1024";
  const oversized = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: speechHeaders(cookie, "audio/wav"),
      body: ownedBuffer(new Uint8Array(1025))
    }),
    { fetchImpl: async () => { fetches += 1; return jsonVoiceStt(); } }
  );
  assert.equal(oversized.status, 413);
  assert.equal(fetches, 0);
});

test("STT aborts a stalled Voice request at the configured hard deadline", async () => {
  const cookie = backendSessionCookie();
  process.env.WALKSAFE_VOICE_STT_TIMEOUT_MS = "1000";
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: speechHeaders(cookie, "audio/wav"),
      body: ownedBuffer(wavBytes())
    }),
    {
      fetchImpl: async (_input, init) => await new Promise<Response>((_resolve, reject) => {
        const keepAlive = setTimeout(() => reject(new Error("deadline did not abort fetch")), 2_000);
        init?.signal?.addEventListener("abort", () => {
          clearTimeout(keepAlive);
          reject(init.signal?.reason);
        }, { once: true });
      })
    }
  );
  assert.equal(response.status, 504);
  assert.deepEqual(await response.json(), { code: "speech_upstream_timeout" });
});

test("STT enforces actor rate and in-flight capacity before another body is read", async () => {
  const cookie = backendSessionCookie();
  process.env.WALKSAFE_VOICE_STT_ACTOR_RATE_LIMIT = "1";
  let fetches = 0;
  const first = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: speechHeaders(cookie, "audio/wav"),
      body: ownedBuffer(wavBytes())
    }),
    { fetchImpl: async () => { fetches += 1; return jsonVoiceStt(); } }
  );
  const second = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: speechHeaders(cookie, "audio/wav"),
      body: ownedBuffer(wavBytes())
    }),
    { fetchImpl: async () => { fetches += 1; return jsonVoiceStt(); } }
  );
  assert.equal(first.status, 200);
  assert.equal(second.status, 429);
  assert.equal(fetches, 1);

  delete process.env.WALKSAFE_VOICE_STT_ACTOR_RATE_LIMIT;
  resetSpeechAdmissionForTests();
  backendSessionStatus = 204;
  let releaseFetch!: (response: Response) => void;
  let markStarted!: () => void;
  const started = new Promise<void>(resolve => { markStarted = resolve; });
  const held = new Promise<Response>(resolve => { releaseFetch = resolve; });
  const pending = handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: speechHeaders(cookie, "audio/wav"),
      body: ownedBuffer(wavBytes())
    }),
    { fetchImpl: async () => { markStarted(); return held; } }
  );
  await started;
  const busy = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST",
      headers: speechHeaders(cookie, "audio/wav"),
      body: ownedBuffer(wavBytes())
    }),
    { fetchImpl: async () => { throw new Error("capacity must reject before fetch"); } }
  );
  assert.equal(busy.status, 503);
  releaseFetch(jsonVoiceStt());
  assert.equal((await pending).status, 200);
});

test("TTS forwards exact non-caching request and returns only validated bounded WAV", async () => {
  const cookie = backendSessionCookie();
  const wav = wavBytes();
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/tts", {
      method: "POST",
      headers: speechHeaders(cookie, "application/json"),
      body: JSON.stringify({
        schema_version: SPEECH_TTS_REQUEST_SCHEMA,
        text: "앞에 장애물이 있습니다.",
        request_id: REQUEST_ID
      })
    }),
    {
      fetchImpl: async (input, init) => {
        assert.equal(String(input), "http://127.0.0.1:9001/speech/tts");
        const headers = new Headers(init?.headers);
        assert.equal(headers.get("x-walksafe-voice-service-token"), VOICE_TOKEN);
        assert.equal(headers.get("authorization"), null);
        assert.equal(headers.get("cookie"), null);
        assert.deepEqual(JSON.parse(String(init?.body)), {
          text: "앞에 장애물이 있습니다.",
          use_cache: false,
          allow_fallback: false
        });
        return new Response(ownedBuffer(wav), {
          headers: {
            "content-type": "audio/wav",
            "x-voice-model-revision": MODEL_REVISION,
            "x-voice-model": "sensitive-internal-model-path"
          }
        });
      }
    }
  );

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "audio/wav");
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  assert.equal(response.headers.get("x-walksafe-voice-model-revision"), MODEL_REVISION);
  assert.equal(response.headers.get("x-voice-model"), null);
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), wav);
});

test("TTS rejects controls, wrong upstream types, and declared oversize without exposing bodies", async () => {
  const cookie = backendSessionCookie();
  let fetches = 0;
  const invalidText = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/tts", {
      method: "POST",
      headers: speechHeaders(cookie, "application/json"),
      body: JSON.stringify({
        schema_version: SPEECH_TTS_REQUEST_SCHEMA,
        text: "비밀\u0000문장",
        request_id: REQUEST_ID
      })
    }),
    { fetchImpl: async () => { fetches += 1; throw new Error("not reached"); } }
  );
  assert.equal(invalidText.status, 422);
  assert.equal(fetches, 0);

  resetSpeechAdmissionForTests();
  backendSessionStatus = 204;
  const wrongType = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/tts", {
      method: "POST",
      headers: speechHeaders(cookie, "application/json"),
      body: JSON.stringify({
        schema_version: SPEECH_TTS_REQUEST_SCHEMA,
        text: "안내 문장",
        request_id: REQUEST_ID
      })
    }),
    { fetchImpl: async () => new Response("not audio", { headers: { "content-type": "text/plain" } }) }
  );
  assert.equal(wrongType.status, 502);

  resetSpeechAdmissionForTests();
  backendSessionStatus = 204;
  const missingRevision = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/tts", {
      method: "POST",
      headers: speechHeaders(cookie, "application/json"),
      body: JSON.stringify({
        schema_version: SPEECH_TTS_REQUEST_SCHEMA,
        text: "안내 문장",
        request_id: REQUEST_ID
      })
    }),
    {
      fetchImpl: async () => new Response(ownedBuffer(wavBytes()), {
        headers: { "content-type": "audio/wav" }
      })
    }
  );
  assert.equal(missingRevision.status, 502);

  resetSpeechAdmissionForTests();
  backendSessionStatus = 204;
  process.env.WALKSAFE_VOICE_TTS_RESPONSE_MAX_BYTES = "1024";
  const oversized = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/speech/tts", {
      method: "POST",
      headers: speechHeaders(cookie, "application/json"),
      body: JSON.stringify({
        schema_version: SPEECH_TTS_REQUEST_SCHEMA,
        text: "안내 문장",
        request_id: REQUEST_ID
      })
    }),
    {
      fetchImpl: async () => new Response(ownedBuffer(wavBytes()), {
        headers: { "content-type": "audio/wav", "content-length": "1025" }
      })
    }
  );
  assert.equal(oversized.status, 502);
});


test("revoked or unavailable Backend account session never reaches Voice", async () => {
  const cookie = backendSessionCookie();
  for (const status of [401, 503]) {
    backendSessionStatus = status;
    let voiceRequests = 0;
    const response = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST", headers: speechHeaders(cookie, "audio/wav"), body: ownedBuffer(wavBytes())
    }), { fetchImpl: async () => { voiceRequests++; return jsonVoiceStt(); } });
    assert.equal(response.status, status);
    assert.equal(voiceRequests, 0);
  }
});

for (const duplicate of [
  '"transcript":"종료",',
  '"acoustic_execution_allowed":false,',
  '"acoustic_\\u0065xecution_allowed":false,'
]) {
  test(`STT rejects conflicting duplicate response fields: ${duplicate}`, async () => {
    const cookie = backendSessionCookie();
    const request = () => new Request("http://127.0.0.1:8081/api/speech/stt", {
      method: "POST", headers: speechHeaders(cookie, "audio/wav"), body: ownedBuffer(wavBytes())
    });
    const response = await handleGatewayRequest(request(), {
      fetchImpl: async () => new Response("{" + duplicate + JSON.stringify(voiceSttPayload()).slice(1), {
        headers: { "content-type": "application/json" }
      })
    });
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { code: "speech_upstream_invalid" });
    assert.equal((await handleGatewayRequest(request(), { fetchImpl: async () => jsonVoiceStt() })).status, 200);
  });
}

test("valid acoustic refusal is forwarded unchanged rather than enabled by the relay", async () => {
  const response = await handleGatewayRequest(new Request("http://127.0.0.1:8081/api/speech/stt", {
    method: "POST", headers: speechHeaders(backendSessionCookie(), "audio/wav"), body: ownedBuffer(wavBytes())
  }), { fetchImpl: async () => jsonVoiceStt({
    acoustic_confidence: 0, acoustic_execution_allowed: false,
    no_speech_probability: 1, avg_logprob: -20
  }) });
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json() as { acoustic: unknown }).acoustic, {
    confidence: 0, execution_allowed: false, no_speech_probability: 1, avg_logprob: -20
  });
});

for (const kind of ["stt", "tts"] as const) {
  test(`${kind} malformed client stream stays an input error and never reaches Voice`, async () => {
    let voiceCalls = 0;
    const response = await handleGatewayRequest(new Request(`http://127.0.0.1:8081/api/speech/${kind}`, {
      method: "POST", headers: speechHeaders(backendSessionCookie(), kind === "stt" ? "audio/wav" : "application/json"),
      body: new ReadableStream<Uint8Array>({ start(controller) { controller.error(new Error("private-input-error")); } }),
      duplex: "half"
    } as RequestInit & { duplex: "half" }), {
      fetchImpl: async () => { voiceCalls++; throw new Error("Voice must not be reached"); }
    });
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), { code: kind === "stt" ? "speech_body_read_failed" : "request_body_read_failed" });
    assert.equal(voiceCalls, 0);
  });

  test(`${kind} cancels a late upstream body after client cancellation and frees admission`, { timeout: 5_000 }, async () => {
    const cookie = backendSessionCookie();
    const controller = new AbortController();
    let started!: () => void;
    const entered = new Promise<void>(resolve => { started = resolve; });
    let release!: (response: Response) => void;
    const pending = new Promise<Response>(resolve => { release = resolve; });
    const request = (signal?: AbortSignal) => new Request(`http://127.0.0.1:8081/api/speech/${kind}`, {
      method: "POST", headers: speechHeaders(cookie, kind === "stt" ? "audio/wav" : "application/json"),
      body: kind === "stt" ? ownedBuffer(wavBytes()) : JSON.stringify({
        schema_version: SPEECH_TTS_REQUEST_SCHEMA, text: "시험 안내", request_id: REQUEST_ID
      }), ...(signal ? { signal } : {})
    });
    const response = handleGatewayRequest(request(controller.signal), {
      fetchImpl: async () => { started(); return pending; }
    });
    await entered;
    controller.abort();
    assert.equal((await response).status, 499);
    let cancelled = 0;
    const late = new Response(new ReadableStream<Uint8Array>({ cancel() { cancelled++; } }));
    try {
      release(late);
      await new Promise<void>(resolve => setImmediate(resolve));
      assert.equal(cancelled, 1, "abandoned response body must be cancelled");
      const retry = await handleGatewayRequest(request(), { fetchImpl: async () => kind === "stt"
        ? jsonVoiceStt() : new Response(ownedBuffer(wavBytes()), {
          headers: { "content-type": "audio/wav", "x-voice-model-revision": MODEL_REVISION }
        }) });
      assert.equal(retry.status, 200);
    } finally {
      await late.body?.cancel().catch(() => undefined);
    }
  });
}

// Real HTTP on both sides of the Gateway, with owned test services instead of
// Python authentication/inference. This is transport evidence, not model/device accuracy.
for (const kind of ["stt", "tts"] as const) {
  for (const fault of ["truncated", "stalled", "disconnect", "wrong-type", "oversized", "session-revoked", "session-unavailable",
    "session-revoked-stream", "session-unavailable-stream"] as const) {
    test(`${kind} real HTTP ${fault} fails without leaking data and permits retry`, { timeout: 8_000 }, async () => {
      const sessionFailure = fault.startsWith("session-");
      const sessionRevoked = fault.startsWith("session-revoked");
      const timeoutName = `WALKSAFE_VOICE_${kind.toUpperCase()}_TIMEOUT_MS`;
      const limitName = `WALKSAFE_VOICE_${kind.toUpperCase()}_RESPONSE_MAX_BYTES`;
      const previousTimeout = process.env[timeoutName];
      const previousLimit = process.env[limitName];
      process.env[timeoutName] = "1000";
      process.env[limitName] = "1024";
      let healthy = false;
      let gateCalls = 0;
      let voiceCalls = 0;
      let unfinishedClosed!: () => void;
      const closed = new Promise<void>(resolve => { unfinishedClosed = resolve; });
      const upstream = createServer((request, response) => {
        request.resume();
        if (request.url === "/accounts/session-current") {
          gateCalls++;
          const denied = !healthy && sessionFailure;
          if (denied) response.once("close", unfinishedClosed);
          response.writeHead(denied ? (sessionRevoked ? 401 : 503) : 204);
          if (denied && fault.endsWith("-stream")) response.write("private-session-service-detail");
          else response.end();
          return;
        }
        assert.equal(request.url, `/speech/${kind}`);
        voiceCalls++;
        assert.equal(request.headers["x-walksafe-voice-service-token"], VOICE_TOKEN);
        assert.equal(request.headers["cookie"], undefined);
        const payload = kind === "stt" ? Buffer.from(JSON.stringify(voiceSttPayload())) : Buffer.from(wavBytes(0.01));
        const headers = { "content-type": kind === "stt" ? "application/json" : "audio/wav",
          "x-voice-model-revision": MODEL_REVISION };
        if (healthy) { response.writeHead(200, headers).end(payload); return; }
        response.once("close", unfinishedClosed);
        if (fault === "disconnect") { request.socket.destroy(); return; }
        if (fault === "wrong-type") {
          response.writeHead(200, { "content-type": "text/html" }).end("private-upstream-detail");
          return;
        }
        response.writeHead(200, fault === "truncated" ? {
          ...headers, "content-length": String(payload.length + 100)
        } : headers);
        response.flushHeaders();
        if (fault === "truncated") { response.write(payload, () => response.destroy()); return; }
        if (fault === "oversized") { response.end(Buffer.alloc(1025, 65)); return; }
        response.write("private-upstream-detail"); // leave the tail unfinished until aborted
      });
      let upstreamOrigin = "";
      // Preserve the product's fixed-origin policy. Only translate the approved
      // loopback ports to this owned ephemeral listener; use real fetch throughout.
      const gateway = createGatewayServer({ fetchImpl: (input, init) => {
        const url = new URL(String(input));
        assert.ok(["http://127.0.0.1:8000", "http://127.0.0.1:9001"].includes(url.origin));
        return fetch(new URL(url.pathname + url.search, upstreamOrigin), init);
      } });
      const listen = (server: Server) => new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
      try {
        await listen(upstream);
        upstreamOrigin = `http://127.0.0.1:${(upstream.address() as AddressInfo).port}`;
        await listen(gateway);
        const origin = `http://127.0.0.1:${(gateway.address() as AddressInfo).port}`;
        const cookie = backendSessionCookie();
        const send = () => fetch(`${origin}/api/speech/${kind}`, {
          method: "POST", headers: speechHeaders(cookie, kind === "stt" ? "audio/wav" : "application/json"),
          body: kind === "stt" ? ownedBuffer(wavBytes()) : JSON.stringify({
            schema_version: SPEECH_TTS_REQUEST_SCHEMA, text: "시험 안내", request_id: REQUEST_ID
          }), signal: AbortSignal.timeout(6_000)
        });
        const failed = await send();
        const expectedStatus = sessionFailure ? (sessionRevoked ? 401 : 503)
          : fault === "stalled" ? 504 : 502;
        const expectedCode = sessionFailure ? (sessionRevoked ? "gateway_auth_required" : "gateway_security_not_configured")
          : fault === "stalled" ? "speech_upstream_timeout" : fault === "disconnect"
          ? "speech_upstream_unavailable" : "speech_upstream_invalid";
        const failureBody = await failed.json();
        assert.equal(failed.status, expectedStatus, JSON.stringify(failureBody));
        assert.equal(failed.headers.get("cache-control"), "no-store");
        assert.deepEqual(failureBody, sessionFailure ? {
          code: expectedCode, message: sessionRevoked ? "인증이 필요합니다." : "현장 테스트 보안 설정을 확인해 주세요."
        } : { code: expectedCode });
        let closeTimeout: ReturnType<typeof setTimeout> | undefined;
        try {
          await Promise.race([closed, new Promise<never>((_resolve, reject) => {
            closeTimeout = setTimeout(() => reject(new Error("discarded upstream body remained open")), 1_000);
          })]);
        } finally {
          if (closeTimeout !== undefined) clearTimeout(closeTimeout);
        }
        if (sessionFailure) assert.equal(voiceCalls, 0, "failed account gate must not send audio/text to Voice");
        healthy = true;
        const retry = await send();
        assert.equal(retry.status, 200);
        if (kind === "stt") assert.equal((await retry.json() as { transcript: string }).transcript, "주변을 설명해줘");
        else assert.deepEqual(Buffer.from(await retry.arrayBuffer()), Buffer.from(wavBytes(0.01)));
        assert.equal(gateCalls, 2);
        assert.equal(voiceCalls, sessionFailure ? 1 : 2);
        assert.equal((await fetch(`${origin}/internal/health`)).status, 200);
      } finally {
        for (const server of [gateway, upstream]) {
          server.closeAllConnections();
          await new Promise<void>(resolve => server.close(() => resolve()));
        }
        for (const [name, value] of [[timeoutName, previousTimeout], [limitName, previousLimit]] as const) {
          if (value === undefined) delete process.env[name]; else process.env[name] = value;
        }
      }
    });
  }
}
