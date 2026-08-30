import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, beforeEach, test } from "node:test";

import { establishBackendGatewaySession } from "../src/auth.js";
import { handleGatewayRequest } from "../src/routes.js";
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

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-speech-relay-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_FIELD_TEST_TOKEN: FIELD_TOKEN,
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
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
  assert.equal(telemetry.length, 1);
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
