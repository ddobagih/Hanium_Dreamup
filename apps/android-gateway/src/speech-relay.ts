import {
  gatewaySessionActor,
  gatewayTrustedClientIp,
  gatewayUnauthorizedResponse
} from "./auth.js";
import { authorizeProxyRequest, type GatewayFetch } from "./backend.js";
import { resolveVoiceServiceConfig, type VoiceServiceConfig } from "./config.js";
import { readBoundedJsonBody } from "./request-body.js";

export const SPEECH_STT_RESPONSE_SCHEMA = "walksafe.speech-stt-response.v1";
export const SPEECH_TTS_REQUEST_SCHEMA = "walksafe.speech-tts-request.v1";

const VOICE_STT_RESPONSE_SCHEMA = "walksafe.voice-stt-response.v1";
const VOICE_SERVICE_TOKEN_HEADER = "x-walksafe-voice-service-token";
const VOICE_CLIENT_IP_HEADER = "x-walksafe-voice-client-ip";
const ACTOR_ID_HEADER = "x-walksafe-actor-id";
const REQUEST_ID_HEADER = "x-request-id";
const DEFAULT_STT_MAX_BODY_BYTES = 10 * 1024 * 1024;
const DEFAULT_STT_RESPONSE_MAX_BYTES = 64 * 1024;
const DEFAULT_STT_TIMEOUT_MS = 35_000;
const DEFAULT_TTS_MAX_BODY_BYTES = 2 * 1024;
const DEFAULT_TTS_RESPONSE_MAX_BYTES = 4 * 1024 * 1024;
const DEFAULT_TTS_MAX_AUDIO_DURATION_SECONDS = 30;
const DEFAULT_TTS_TIMEOUT_MS = 65_000;
const DEFAULT_BODY_READ_TIMEOUT_MS = 10_000;
const DEFAULT_RATE_WINDOW_SECONDS = 60;
const CANONICAL_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const MODEL_REVISION = /^[0-9a-f]{40}$/;
const UNICODE_CONTROL = /[\p{Cc}\p{Cf}]/u;
const STT_CONTENT_TYPES = new Map<string, string>([
  ["audio/aac", "audio.aac"],
  ["audio/flac", "audio.flac"],
  ["audio/mp4", "audio.m4a"],
  ["audio/mpeg", "audio.mp3"],
  ["audio/ogg", "audio.ogg"],
  ["audio/wav", "audio.wav"],
  ["audio/wave", "audio.wav"],
  ["audio/webm", "audio.webm"],
  ["audio/x-flac", "audio.flac"],
  ["audio/x-m4a", "audio.m4a"],
  ["audio/x-wav", "audio.wav"]
]);
const VOICE_STT_RESPONSE_KEYS = [
  "schema_version",
  "transcript",
  "normalized",
  "intent",
  "score",
  "confidence",
  "slots",
  "action",
  "should_execute",
  "reason",
  "prompt",
  "acoustic_confidence",
  "avg_logprob",
  "no_speech_probability",
  "acoustic_execution_allowed",
  "language",
  "duration_sec",
  "audio_duration_sec",
  "model",
  "model_revision",
  "segments"
] as const;

type SpeechKind = "stt" | "tts";
type SpeechAdmission =
  | { actorId: string; clientIp: string; release: () => void; error?: never }
  | { actorId?: never; clientIp?: never; release?: never; error: Response };
type BytesResult = { bytes: Uint8Array; error?: never } | { bytes?: never; error: Response };
type VoiceFetchResult =
  | { response: Response; deadlineSignal: AbortSignal; error?: never }
  | { response?: never; deadlineSignal?: never; error: Response };

const rateEvents = new Map<string, number[]>();
const inFlight: Record<SpeechKind, number> = { stt: 0, tts: 0 };

function responseError(status: number, code: string, retryAfter?: number): Response {
  const headers = new Headers({
    "cache-control": "no-store",
    "content-type": "application/json",
    "x-content-type-options": "nosniff"
  });
  if (retryAfter !== undefined) headers.set("retry-after", String(Math.max(1, retryAfter)));
  return Response.json({ code }, { status, headers });
}

function boundedInteger(
  name: string,
  fallback: number,
  minimum: number,
  maximum: number
): number {
  const raw = process.env[name]?.trim() ?? "";
  if (!/^\d+$/.test(raw)) return fallback;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= minimum && value <= maximum
    ? value
    : fallback;
}

function speechLimit(kind: SpeechKind, suffix: string, fallback: number): number {
  const ranges: Record<string, readonly [number, number]> = {
    GLOBAL_RATE_LIMIT: [1, 100_000],
    ACTOR_RATE_LIMIT: [1, 10_000],
    IP_RATE_LIMIT: [1, 10_000],
    MAX_CONCURRENCY: [1, 4]
  };
  const [minimum, maximum] = ranges[suffix] ?? [1, 100_000];
  return boundedInteger(
    `WALKSAFE_VOICE_${kind.toUpperCase()}_${suffix}`,
    fallback,
    minimum!,
    maximum!
  );
}

function pruneRateEvents(nowMs: number, windowMs: number): void {
  const cutoff = nowMs - windowMs;
  for (const [key, events] of rateEvents) {
    const retained = events.filter(eventAt => eventAt > cutoff && eventAt <= nowMs);
    if (retained.length === 0) rateEvents.delete(key);
    else rateEvents.set(key, retained);
  }
}

function acquireSpeechAdmission(
  request: Request,
  kind: SpeechKind,
  nowMs = Date.now()
): SpeechAdmission {
  const denied = authorizeProxyRequest(request);
  if (denied) return { error: denied };
  const actorId = gatewaySessionActor(request);
  if (!actorId) return { error: gatewayUnauthorizedResponse() };
  const clientIp = gatewayTrustedClientIp(request);
  if (!clientIp) return { error: responseError(400, "speech_trusted_client_ip_required") };
  if (!Number.isFinite(nowMs) || nowMs < 0) {
    return { error: responseError(503, "speech_admission_unavailable", 1) };
  }

  const windowSeconds = boundedInteger(
    "WALKSAFE_VOICE_RATE_WINDOW_SECONDS",
    DEFAULT_RATE_WINDOW_SECONDS,
    1,
    3600
  );
  const windowMs = windowSeconds * 1000;
  pruneRateEvents(nowMs, windowMs);
  const defaults = kind === "stt"
    ? { global: 120, actor: 12, ip: 30 }
    : { global: 120, actor: 30, ip: 60 };
  const limits: Array<[string, number]> = [
    [`speech:${kind}:global`, speechLimit(kind, "GLOBAL_RATE_LIMIT", defaults.global)],
    [`speech:${kind}:actor:${actorId}`, speechLimit(kind, "ACTOR_RATE_LIMIT", defaults.actor)],
    [`speech:${kind}:ip:${clientIp}`, speechLimit(kind, "IP_RATE_LIMIT", defaults.ip)]
  ];
  for (const [key, limit] of limits) {
    const events = rateEvents.get(key) ?? [];
    if (events.length >= limit) {
      const retryAfter = Math.ceil((events[0]! + windowMs - nowMs) / 1000);
      return { error: responseError(429, "speech_rate_limited", retryAfter) };
    }
  }
  const concurrency = speechLimit(kind, "MAX_CONCURRENCY", 1);
  if (inFlight[kind] >= concurrency) {
    return { error: responseError(503, "speech_capacity_busy", 1) };
  }
  for (const [key] of limits) {
    const events = rateEvents.get(key) ?? [];
    events.push(nowMs);
    rateEvents.set(key, events);
  }
  inFlight[kind] += 1;
  let released = false;
  return {
    actorId,
    clientIp,
    release: () => {
      if (released) return;
      released = true;
      inFlight[kind] = Math.max(0, inFlight[kind] - 1);
    }
  };
}

export function resetSpeechAdmissionForTests(): void {
  rateEvents.clear();
  inFlight.stt = 0;
  inFlight.tts = 0;
}

function configuredVoiceService(): VoiceServiceConfig | Response {
  try {
    return resolveVoiceServiceConfig() ?? responseError(503, "speech_service_disabled");
  } catch {
    return responseError(503, "speech_service_unavailable");
  }
}

async function readBoundedBytes(
  request: Request,
  maxBytes: number,
  timeoutMs = DEFAULT_BODY_READ_TIMEOUT_MS
): Promise<BytesResult> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^\d+$/.test(declaredLength)) {
      return { error: responseError(400, "invalid_content_length") };
    }
    const value = Number(declaredLength);
    if (!Number.isSafeInteger(value)) {
      return { error: responseError(400, "invalid_content_length") };
    }
    if (value > maxBytes) return { error: responseError(413, "speech_request_too_large") };
  }
  if (!request.body) return { error: responseError(400, "speech_request_body_required") };
  return readBoundedStream(request.body, maxBytes, AbortSignal.any([
    request.signal,
    AbortSignal.timeout(Math.max(1, timeoutMs))
  ]), request.signal);
}

async function readBoundedStream(
  stream: ReadableStream<Uint8Array>,
  maxBytes: number,
  signal: AbortSignal,
  clientSignal?: AbortSignal
): Promise<BytesResult> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      if (signal.aborted) throw signal.reason;
      let rejectAbort: (reason?: unknown) => void = () => undefined;
      const aborted = new Promise<never>((_resolve, reject) => {
        rejectAbort = reject;
      });
      const onAbort = () => rejectAbort(signal.reason);
      signal.addEventListener("abort", onAbort, { once: true });
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await Promise.race([reader.read(), aborted]);
      } finally {
        signal.removeEventListener("abort", onAbort);
      }
      if (chunk.done) break;
      totalBytes += chunk.value.byteLength;
      if (totalBytes > maxBytes) {
        void reader.cancel("speech body exceeded its byte ceiling").catch(() => undefined);
        return { error: responseError(413, "speech_request_too_large") };
      }
      chunks.push(chunk.value);
    }
  } catch {
    void reader.cancel("speech body read was cancelled").catch(() => undefined);
    if (clientSignal?.aborted) return { error: responseError(499, "gateway_client_closed") };
    if (signal.aborted) return { error: responseError(504, "speech_upstream_timeout") };
    return { error: responseError(400, "speech_body_read_failed") };
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // Cancellation may retain the reader until the underlying stream settles.
    }
  }
  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { bytes };
}

function voiceHeaders(
  config: VoiceServiceConfig,
  actorId: string,
  clientIp: string,
  requestId: string
): Headers {
  return new Headers({
    [VOICE_SERVICE_TOKEN_HEADER]: config.serviceToken,
    [ACTOR_ID_HEADER]: actorId,
    [VOICE_CLIENT_IP_HEADER]: clientIp,
    [REQUEST_ID_HEADER]: requestId
  });
}

async function fetchVoice(
  request: Request,
  url: string,
  init: RequestInit,
  timeoutMs: number,
  fetchImpl: GatewayFetch
): Promise<VoiceFetchResult> {
  const deadlineSignal = AbortSignal.timeout(Math.max(1, timeoutMs));
  const signal = AbortSignal.any([request.signal, deadlineSignal]);
  let rejectAbort: (reason?: unknown) => void = () => undefined;
  const aborted = new Promise<never>((_resolve, reject) => {
    rejectAbort = reject;
  });
  const onAbort = () => rejectAbort(signal.reason);
  signal.addEventListener("abort", onAbort, { once: true });
  try {
    const response = await Promise.race([
      fetchImpl(url, { ...init, cache: "no-store", redirect: "error", signal }),
      aborted
    ]);
    return { response, deadlineSignal };
  } catch {
    if (request.signal.aborted) return { error: responseError(499, "gateway_client_closed") };
    if (deadlineSignal.aborted) return { error: responseError(504, "speech_upstream_timeout") };
    return { error: responseError(502, "speech_upstream_unavailable") };
  } finally {
    signal.removeEventListener("abort", onAbort);
  }
}

function cancelUpstream(response: Response, reason: string): void {
  void response.body?.cancel(reason).catch(() => undefined);
}

function projectedUpstreamError(response: Response, kind: SpeechKind): Response {
  cancelUpstream(response, "voice upstream error body is not exposed");
  if (response.status === 429) return responseError(429, "speech_service_rate_limited", 1);
  if (response.status === 503) return responseError(503, "speech_service_busy", 1);
  if (response.status === 504) return responseError(504, `${kind}_inference_timeout`);
  if (kind === "stt" && [400, 413, 415, 422].includes(response.status)) {
    return responseError(response.status === 413 ? 413 : 422, "speech_audio_rejected");
  }
  return responseError(502, "speech_upstream_rejected");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
}

function finiteOrNull(value: unknown): value is number | null {
  return value === null || (typeof value === "number" && Number.isFinite(value));
}

function validTranscript(value: unknown): value is string {
  return typeof value === "string" &&
    Array.from(value).length <= 500 &&
    !UNICODE_CONTROL.test(value);
}

function projectSttResponse(value: unknown, requestId: string): Response | null {
  if (!isRecord(value) || !exactKeys(value, VOICE_STT_RESPONSE_KEYS)) return null;
  if (
    value.schema_version !== VOICE_STT_RESPONSE_SCHEMA ||
    !validTranscript(value.transcript) ||
    typeof value.acoustic_confidence !== "number" ||
    !Number.isFinite(value.acoustic_confidence) ||
    value.acoustic_confidence < 0 ||
    value.acoustic_confidence > 1 ||
    !finiteOrNull(value.avg_logprob) ||
    !finiteOrNull(value.no_speech_probability) ||
    (value.no_speech_probability !== null &&
      (value.no_speech_probability < 0 || value.no_speech_probability > 1)) ||
    typeof value.acoustic_execution_allowed !== "boolean" ||
    typeof value.model_revision !== "string" ||
    !MODEL_REVISION.test(value.model_revision)
  ) return null;
  return Response.json({
    schema_version: SPEECH_STT_RESPONSE_SCHEMA,
    request_id: requestId,
    transcript: value.transcript,
    acoustic: {
      confidence: value.acoustic_confidence,
      avg_logprob: value.avg_logprob,
      no_speech_probability: value.no_speech_probability,
      execution_allowed: value.acoustic_execution_allowed
    },
    model_revision: value.model_revision
  }, {
    headers: {
      "cache-control": "no-store",
      "x-content-type-options": "nosniff"
    }
  });
}

function allowedSttContentType(request: Request): { contentType: string; filename: string } | null {
  const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  const filename = STT_CONTENT_TYPES.get(contentType);
  return filename ? { contentType, filename } : null;
}

function ownedArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

export async function relaySpeechStt(
  request: Request,
  requestId: string,
  fetchImpl: GatewayFetch = globalThis.fetch
): Promise<Response> {
  const admission = acquireSpeechAdmission(request, "stt");
  if (admission.error) return admission.error;
  try {
    const config = configuredVoiceService();
    if (config instanceof Response) return config;
    const media = allowedSttContentType(request);
    if (!media) return responseError(415, "speech_audio_content_type_unsupported");
    const maxBodyBytes = boundedInteger(
      "WALKSAFE_VOICE_STT_MAX_BODY_BYTES",
      DEFAULT_STT_MAX_BODY_BYTES,
      1024,
      32 * 1024 * 1024
    );
    const bounded = await readBoundedBytes(request, maxBodyBytes);
    if (bounded.error) return bounded.error;
    if (bounded.bytes.byteLength === 0) return responseError(400, "speech_audio_empty");

    const form = new FormData();
    form.set(
      "audio",
      new Blob([ownedArrayBuffer(bounded.bytes)], { type: media.contentType }),
      media.filename
    );
    const timeoutMs = boundedInteger(
      "WALKSAFE_VOICE_STT_TIMEOUT_MS",
      DEFAULT_STT_TIMEOUT_MS,
      1000,
      300_000
    );
    const upstream = await fetchVoice(
      request,
      `${config.baseUrl}/speech/stt`,
      {
        method: "POST",
        headers: voiceHeaders(config, admission.actorId, admission.clientIp, requestId),
        body: form
      },
      timeoutMs,
      fetchImpl
    );
    if (upstream.error) return upstream.error;
    if (upstream.response.status < 200 || upstream.response.status > 299) {
      return projectedUpstreamError(upstream.response, "stt");
    }
    const contentType = upstream.response.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
    if (contentType !== "application/json" || !upstream.response.body) {
      cancelUpstream(upstream.response, "voice STT response type is invalid");
      return responseError(502, "speech_upstream_invalid");
    }
    const maxResponseBytes = boundedInteger(
      "WALKSAFE_VOICE_STT_RESPONSE_MAX_BYTES",
      DEFAULT_STT_RESPONSE_MAX_BYTES,
      1024,
      1024 * 1024
    );
    const declaredLength = upstream.response.headers.get("content-length");
    if (declaredLength !== null &&
      (!/^\d+$/.test(declaredLength) || Number(declaredLength) > maxResponseBytes)) {
      cancelUpstream(upstream.response, "voice STT response length is invalid");
      return responseError(502, "speech_upstream_invalid");
    }
    const bytes = await readBoundedStream(
      upstream.response.body,
      maxResponseBytes,
      AbortSignal.any([request.signal, upstream.deadlineSignal]),
      request.signal
    );
    if (bytes.error) {
      return bytes.error.status === 413
        ? responseError(502, "speech_upstream_invalid")
        : bytes.error;
    }
    let value: unknown;
    try {
      value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes.bytes));
    } catch {
      return responseError(502, "speech_upstream_invalid");
    }
    return projectSttResponse(value, requestId) ?? responseError(502, "speech_upstream_invalid");
  } finally {
    admission.release();
  }
}

function validTtsRequest(value: unknown): value is {
  schema_version: typeof SPEECH_TTS_REQUEST_SCHEMA;
  text: string;
  request_id: string;
} {
  if (!isRecord(value) || !exactKeys(value, ["schema_version", "text", "request_id"])) return false;
  return value.schema_version === SPEECH_TTS_REQUEST_SCHEMA &&
    typeof value.text === "string" &&
    value.text.trim().length > 0 &&
    Array.from(value.text).length <= 180 &&
    !UNICODE_CONTROL.test(value.text) &&
    typeof value.request_id === "string" &&
    CANONICAL_UUID.test(value.request_id);
}

function wavDurationSeconds(bytes: Uint8Array): number | null {
  if (bytes.byteLength < 44) return null;
  const ascii = (offset: number, length: number): string =>
    String.fromCharCode(...bytes.subarray(offset, offset + length));
  if (ascii(0, 4) !== "RIFF" || ascii(8, 4) !== "WAVE") return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint32(4, true) + 8 !== bytes.byteLength) return null;
  let byteRate: number | null = null;
  let dataBytes: number | null = null;
  let offset = 12;
  while (offset + 8 <= bytes.byteLength) {
    const chunkId = ascii(offset, 4);
    const chunkSize = view.getUint32(offset + 4, true);
    const start = offset + 8;
    const end = start + chunkSize;
    if (end > bytes.byteLength) return null;
    if (chunkId === "fmt " && chunkSize >= 16) {
      const format = view.getUint16(start, true);
      const channels = view.getUint16(start + 2, true);
      const sampleRate = view.getUint32(start + 4, true);
      const candidateByteRate = view.getUint32(start + 8, true);
      if (
        ![1, 3, 65_534].includes(format) ||
        channels < 1 || channels > 2 ||
        sampleRate < 8_000 || sampleRate > 192_000 ||
        candidateByteRate < 1
      ) return null;
      byteRate = candidateByteRate;
    } else if (chunkId === "data") {
      dataBytes = chunkSize;
    }
    offset = end + (chunkSize % 2);
  }
  if (byteRate === null || dataBytes === null || dataBytes < 1) return null;
  const duration = dataBytes / byteRate;
  return Number.isFinite(duration) && duration > 0 ? duration : null;
}

export async function relaySpeechTts(
  request: Request,
  correlationId: string,
  fetchImpl: GatewayFetch = globalThis.fetch
): Promise<Response> {
  const admission = acquireSpeechAdmission(request, "tts");
  if (admission.error) return admission.error;
  try {
    const config = configuredVoiceService();
    if (config instanceof Response) return config;
    const maxBodyBytes = boundedInteger(
      "WALKSAFE_VOICE_TTS_MAX_BODY_BYTES",
      DEFAULT_TTS_MAX_BODY_BYTES,
      256,
      16 * 1024
    );
    const bounded = await readBoundedJsonBody(request, maxBodyBytes);
    if (bounded.error) return bounded.error;
    if (!validTtsRequest(bounded.value)) return responseError(422, "speech_tts_request_invalid");

    const timeoutMs = boundedInteger(
      "WALKSAFE_VOICE_TTS_TIMEOUT_MS",
      DEFAULT_TTS_TIMEOUT_MS,
      1000,
      300_000
    );
    const headers = voiceHeaders(config, admission.actorId, admission.clientIp, correlationId);
    headers.set("content-type", "application/json");
    const upstream = await fetchVoice(
      request,
      `${config.baseUrl}/speech/tts`,
      {
        method: "POST",
        headers,
        body: JSON.stringify({
          text: bounded.value.text,
          use_cache: false,
          allow_fallback: false
        })
      },
      timeoutMs,
      fetchImpl
    );
    if (upstream.error) return upstream.error;
    if (upstream.response.status < 200 || upstream.response.status > 299) {
      return projectedUpstreamError(upstream.response, "tts");
    }
    const contentType = upstream.response.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
    if (contentType !== "audio/wav" || !upstream.response.body) {
      cancelUpstream(upstream.response, "voice TTS response type is invalid");
      return responseError(502, "speech_upstream_invalid");
    }
    const maxResponseBytes = boundedInteger(
      "WALKSAFE_VOICE_TTS_RESPONSE_MAX_BYTES",
      DEFAULT_TTS_RESPONSE_MAX_BYTES,
      1024,
      32 * 1024 * 1024
    );
    const declaredLength = upstream.response.headers.get("content-length");
    if (declaredLength !== null &&
      (!/^\d+$/.test(declaredLength) || Number(declaredLength) > maxResponseBytes)) {
      cancelUpstream(upstream.response, "voice TTS response length is invalid");
      return responseError(502, "speech_upstream_invalid");
    }
    const bytes = await readBoundedStream(
      upstream.response.body,
      maxResponseBytes,
      AbortSignal.any([request.signal, upstream.deadlineSignal]),
      request.signal
    );
    if (bytes.error) {
      return bytes.error.status === 413
        ? responseError(502, "speech_upstream_invalid")
        : bytes.error;
    }
    const duration = wavDurationSeconds(bytes.bytes);
    const maxDuration = boundedInteger(
      "WALKSAFE_VOICE_TTS_MAX_AUDIO_DURATION_SECONDS",
      DEFAULT_TTS_MAX_AUDIO_DURATION_SECONDS,
      1,
      120
    );
    if (duration === null || duration > maxDuration) {
      return responseError(502, "speech_upstream_invalid");
    }
    const responseHeaders = new Headers({
      "cache-control": "no-store",
      "content-type": "audio/wav",
      "content-length": String(bytes.bytes.byteLength),
      "x-content-type-options": "nosniff",
      "x-walksafe-speech-request-id": bounded.value.request_id
    });
    const modelRevision = upstream.response.headers.get("x-voice-model-revision")?.trim() ?? "";
    if (!MODEL_REVISION.test(modelRevision)) {
      return responseError(502, "speech_upstream_invalid");
    }
    responseHeaders.set("x-walksafe-voice-model-revision", modelRevision);
    return new Response(ownedArrayBuffer(bytes.bytes), { status: 200, headers: responseHeaders });
  } finally {
    admission.release();
  }
}
