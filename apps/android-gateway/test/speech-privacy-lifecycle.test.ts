import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import { type GatewayFetch } from "../src/backend.js";
import { createGatewayRuntime } from "../src/gateway-runtime.js";
import { handleGatewayRequest } from "../src/routes.js";
import { activePrivacyOperationCountForTests } from "../src/privacy-rights.js";
import { isAccountGenerationFencedV2, DELETION_ACCESS_SECRET_HEADER } from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let stateDirectory = "";
let identity = 0;
before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-speech-privacy-"));
  Object.assign(process.env, {
    NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "walk-ledger.json"),
    WALKSAFE_GATEWAY_SESSION_SECRET: "speech-privacy-session-secret-12345678901234567890",
    WALKSAFE_FIELD_TEST_TOKEN: "speech-privacy-field-token-123456789012345",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_VOICE_ENABLED: "true",
    WALKSAFE_VOICE_API_BASE_URL: "http://127.0.0.1:9001",
    WALKSAFE_VOICE_SERVICE_TOKEN: "speech-privacy-voice-token-123456789012345"
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(stateDirectory, "keyring.json"));
});
after(async () => { await rm(stateDirectory, { recursive: true, force: true }); });

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

function successfulVoiceResponse(kind: "stt" | "tts"): Response {
  if (kind === "stt") return Response.json({
    schema_version: "walksafe.voice-stt-response.v1",
    transcript: "주변을 설명해줘", normalized: "주변을 설명해줘",
    intent: "describe_surroundings", score: 1, confidence: 1, slots: {},
    action: "execute", should_execute: true, reason: null, prompt: null,
    acoustic_confidence: 0.76, avg_logprob: -0.2, no_speech_probability: 0.05,
    acoustic_execution_allowed: true, language: "ko", duration_sec: 0.1,
    audio_duration_sec: 0.5, model: "Systran/faster-whisper-medium",
    model_revision: "a".repeat(40), segments: [{ start: 0, end: 0.5, text: "주변을 설명해줘" }]
  });
  const wav = Buffer.alloc(46);
  wav.write("RIFF", 0);
  wav.writeUInt32LE(38, 4);
  wav.write("WAVEfmt ", 8);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(8_000, 24);
  wav.writeUInt32LE(16_000, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write("data", 36);
  wav.writeUInt32LE(2, 40);
  return new Response(wav, { headers: {
    "content-type": "audio/wav", "x-voice-model-revision": "a".repeat(40)
  } });
}

async function fixture(kind: "stt" | "tts") {
  const actorId = `11111111-1111-4111-8111-${String(++identity).padStart(12, "0")}`;
  const runtime = createGatewayRuntime();
  const clientIp = `198.51.100.${identity}`;
  // Exercise the production login binding, not just the cookie mint helper.
  const login = await handleGatewayRequest(new Request("https://gateway.invalid/api/field-session", {
    method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": clientIp },
    body: JSON.stringify({ grant_type: "password", email: "voice@example.org",
      password: "fixture-password", remember_me: false })
  }), { runtime, fetchImpl: async () => Response.json({
    schema_version: "walksafe.account-authentication.v1", actor_id: actorId,
    account_generation: 3, auth_epoch: 2
  }) });
  assert.equal(login.status, 200);
  const cookie = login.headers.get("set-cookie")!.split(";", 1)[0]!;
  const payload = kind === "stt" ? new Uint8Array([82, 73, 70, 70]) : new TextEncoder().encode(
    JSON.stringify({ schema_version: "walksafe.speech-tts-request.v1", text: "안내 시험",
      request_id: "11111111-1111-4111-8111-111111111111" }));
  const request = (body: BodyInit = payload, signal?: AbortSignal) => new Request(
    `https://gateway.invalid/api/speech/${kind}`, {
      method: "POST", headers: { cookie, "cf-connecting-ip": clientIp,
        "content-type": kind === "stt" ? "audio/wav" : "application/json" },
      body, duplex: "half", ...(signal ? { signal } : {})
    } as RequestInit);
  const run = (input: Request, voiceFetch: GatewayFetch) => handleGatewayRequest(input, {
    runtime, fetchImpl: async (url, init) => {
      if (new URL(String(url)).pathname === "/accounts/session-current") {
        return new Response(null, { status: 204 });
      }
      return voiceFetch(url, init);
    }
  });
  const routeRequest = (pathname: string, method: string, body?: BodyInit) => new Request(
    `https://gateway.invalid${pathname}`, { method, headers: { cookie, "content-type": "application/json" },
      ...(body ? { body, duplex: "half" } : {}) } as RequestInit);
  const removeAccount = async () => {
    const deletion = await handleGatewayRequest(new Request("https://gateway.invalid/privacy/account-deletions", {
      method: "POST", headers: { cookie, "content-type": "application/json",
        [DELETION_ACCESS_SECRET_HEADER]: Buffer.alloc(32, identity).toString("base64url") },
      body: JSON.stringify({ schema_version: "walksafe.account-deletion-request.v2",
        request_id: `speech_privacy_deletion_${identity}`, client_revision: 1,
        confirmation: "DELETE_MY_ACCOUNT" })
    }), { runtime, fetchImpl: async () => new Response(null, { status: 503 }) });
    // Local acceptance must cancel requests even while Backend delivery is pending.
    assert.equal(deletion.status, 503);
    assert.equal((await deletion.json() as { code: string }).code, "account_deletion_backend_pending");
    assert.equal(isAccountGenerationFencedV2(actorId, 3), true);
  };
  return { request, routeRequest, run, removeAccount, payload };
}

for (const pathname of ["/api/field-diagnostics/preference", "/api/navigation/walking"]) {
  test(`${pathname}: deletion cancels a pending request body before Backend dispatch`, { timeout: 5_000 }, async () => {
    const context = await fixture("stt");
    const reading = deferred<void>();
    let bodyController!: ReadableStreamDefaultController<Uint8Array>;
    let cancellations = 0;
    let calls = 0;
    const body = new ReadableStream<Uint8Array>({
      start(controller) { bodyController = controller; },
      pull() { reading.resolve(); },
      cancel() { cancellations += 1; }
    }, { highWaterMark: 0 });
    const pending = context.run(context.routeRequest(pathname, "POST", body), async () => {
      calls += 1;
      return Response.json({});
    });
    await reading.promise;
    await context.removeAccount();
    const cancelledAtDeletion = cancellations;
    if (!cancellations) {
      bodyController.enqueue(new TextEncoder().encode("{}"));
      bodyController.close();
    }
    assert.equal((await pending).status, 409);
    assert.equal(cancelledAtDeletion, 1);
    assert.equal(calls, 0);
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });
}

test("diagnostics retains its lease during asynchronous Backend response conversion", { timeout: 5_000 }, async (t) => {
  const context = await fixture("stt");
  const converting = deferred<void>();
  const releaseRead = deferred<void>();
  const originalGetReader = ReadableStream.prototype.getReader;
  let readers = 0;
  // Backend transport first buffers the response; the second reader belongs to
  // the real toBackendResponse -> readBoundedJsonBody authentication projection.
  const hook = t.mock.method(ReadableStream.prototype, "getReader", function (this: ReadableStream) {
    const reader = originalGetReader.call(this) as ReadableStreamDefaultReader;
    if (++readers === 2) {
      const read = reader.read.bind(reader);
      reader.read = async () => {
        converting.resolve();
        await releaseRead.promise;
        return read();
      };
    }
    return reader;
  });
  const pending = context.run(context.routeRequest("/api/field-diagnostics/preference", "GET"),
    async () => Response.json({ detail: { code: "account_authentication_stale" } }, { status: 401 }));
  await converting.promise;
  hook.mock.restore();
  const activeWhileConverting = activePrivacyOperationCountForTests();
  await context.removeAccount();
  releaseRead.resolve();
  const response = await pending;
  assert.equal(activeWhileConverting, 1);
  assert.equal(response.status, 409);
  assert.equal(activePrivacyOperationCountForTests(), 0);
});

test("diagnostics cancels response delivery if deletion arrives after headers", async () => {
  const context = await fixture("stt");
  const response = await context.run(context.routeRequest("/api/field-diagnostics/preference", "GET"),
    async () => Response.json({ present: true, enabled: true, revision: 1 }));
  assert.equal(response.status, 200);
  const activeBeforeDeletion = activePrivacyOperationCountForTests();
  await context.removeAccount();
  let delivered = false;
  try { await response.text(); delivered = true; } catch { /* deletion terminates delivery */ }
  assert.equal(activeBeforeDeletion, 1);
  assert.equal(delivered, false);
  assert.equal(activePrivacyOperationCountForTests(), 0);
});

for (const kind of ["stt", "tts"] as const) {
  test(`${kind}: deletion after response headers blocks unread transcript or WAV delivery`, async () => {
    const context = await fixture(kind);
    const response = await context.run(context.request(), async () => successfulVoiceResponse(kind));
    assert.equal(response.status, 200);
    const activeBeforeDeletion = activePrivacyOperationCountForTests();
    await context.removeAccount();
    await assert.rejects(response.arrayBuffer(), /account_generation_tombstoned/);
    assert.equal(activeBeforeDeletion, 1);
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });

  test(`${kind}: ordinary body consumption releases the response lease`, async () => {
    const context = await fixture(kind);
    const response = await context.run(context.request(), async () => successfulVoiceResponse(kind));
    assert.equal(response.status, 200);
    assert.equal(activePrivacyOperationCountForTests(), 1);
    if (kind === "stt") {
      assert.equal((await response.json() as { transcript: string }).transcript, "주변을 설명해줘");
    } else {
      const bytes = Buffer.from(await response.arrayBuffer());
      assert.equal(bytes.subarray(0, 4).toString(), "RIFF");
      assert.equal(bytes.byteLength, 46);
    }
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });

  test(`${kind}: caller body cancellation releases the response lease`, async () => {
    const context = await fixture(kind);
    const response = await context.run(context.request(), async () => successfulVoiceResponse(kind));
    assert.equal(response.status, 200);
    assert.equal(activePrivacyOperationCountForTests(), 1);
    await response.body!.cancel("caller stopped reading");
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });

  test(`${kind}: client abort after response headers prevents delivery and releases the lease`, async () => {
    const context = await fixture(kind);
    const controller = new AbortController();
    const response = await context.run(context.request(undefined, controller.signal),
      async () => successfulVoiceResponse(kind));
    assert.equal(response.status, 200);
    assert.equal(activePrivacyOperationCountForTests(), 1);
    controller.abort(new Error("client left before reading"));
    await assert.rejects(response.arrayBuffer(), /client left before reading/);
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });

  test(`${kind}: deletion during body read cancels upload and never starts Voice`, { timeout: 5_000 }, async () => {
    const context = await fixture(kind);
    const reading = deferred<void>();
    let bodyController!: ReadableStreamDefaultController<Uint8Array>;
    let cancelled = 0;
    let voiceCalls = 0;
    const body = new ReadableStream<Uint8Array>({
      start(controller) { bodyController = controller; },
      pull() { reading.resolve(); },
      cancel() { cancelled += 1; }
    }, { highWaterMark: 0 });
    const pending = context.run(context.request(body), async () => {
      voiceCalls += 1;
      return new Response(null, { status: 503 });
    });
    await reading.promise;
    await context.removeAccount();
    const cancelledAtDeletion = cancelled;
    // Release the baseline's unguarded reader so regression failure does not hang.
    if (!cancelled) { bodyController.enqueue(context.payload); bodyController.close(); }
    const response = await pending;
    assert.equal(response.status, 409);
    assert.equal((await response.json() as { detail: { code: string } }).detail.code,
      "account_generation_inactive");
    assert.equal(cancelledAtDeletion, 1);
    assert.equal(voiceCalls, 0);
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });

  test(`${kind}: deletion during inference aborts Voice and cancels a late response`, { timeout: 5_000 }, async () => {
    const context = await fixture(kind);
    const entered = deferred<AbortSignal>();
    const late = deferred<Response>();
    const pending = context.run(context.request(), async (_url, init) => {
      assert.ok(init?.signal);
      entered.resolve(init.signal);
      return late.promise;
    });
    const upstreamSignal = await entered.promise;
    await context.removeAccount();
    // A non-cooperative Voice transport must not hold the cancelled route open.
    const response = await pending;
    let cancelled = 0;
    late.resolve(new Response(new ReadableStream({ cancel() { cancelled += 1; } },
      { highWaterMark: 0 }), { status: 503 }));
    await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(upstreamSignal.aborted, true);
    assert.equal(response.status, 409);
    assert.equal(cancelled, 1);
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });

  test(`${kind}: deletion during upstream body read cancels its reader and blocks results`, { timeout: 5_000 }, async () => {
    const context = await fixture(kind);
    const reading = deferred<void>();
    let bodyController!: ReadableStreamDefaultController<Uint8Array>;
    let cancelled = 0;
    let upstreamSignal: AbortSignal | null = null;
    const pending = context.run(context.request(), async (_url, init) => {
      upstreamSignal = init?.signal ?? null;
      return new Response(new ReadableStream<Uint8Array>({
        start(controller) { bodyController = controller; },
        pull() { reading.resolve(); },
        cancel() { cancelled += 1; }
      }, { highWaterMark: 0 }), { headers: {
        "content-type": kind === "stt" ? "application/json" : "audio/wav"
      } });
    });
    await reading.promise;
    await context.removeAccount();
    const cancelledAtDeletion = cancelled;
    if (!cancelled) bodyController.close();
    assert.equal((await pending).status, 409);
    assert.equal((upstreamSignal as AbortSignal | null)?.aborted, true);
    assert.equal(cancelledAtDeletion, 1);
    assert.equal(activePrivacyOperationCountForTests(), 0);
  });

  test(`${kind}: session-current rejection cancels its body before any Voice call`, async () => {
    const context = await fixture(kind);
    for (const status of [401, 503, 200]) {
      let cancellations = 0;
      let calls = 0;
      const response = await handleGatewayRequest(context.request(), { fetchImpl: async (url) => {
        calls += 1;
        assert.equal(new URL(String(url)).pathname, "/accounts/session-current");
        return new Response(new ReadableStream({
          pull() { assert.fail("authority check must not consume the error body"); },
          cancel() { cancellations += 1; }
        }, { highWaterMark: 0 }), { status });
      } });
      assert.equal(response.status, status === 401 ? 401 : 503);
      assert.equal(cancellations, 1);
      assert.equal(calls, 1);
      assert.equal(activePrivacyOperationCountForTests(), 0);
    }
  });
}
