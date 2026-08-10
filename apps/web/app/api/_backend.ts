/** Shared authorization, bounded multipart, deadline, and response rules for same-origin BFF routes. */
import { createHmac } from "node:crypto";
import { isIP } from "node:net";
import {
  gatewayTokenForBackend,
  gatewaySessionActor,
  gatewayUnavailableResponse,
  gatewayUnauthorizedResponse,
  isGatewayCredential,
  isGatewayAccessConfigured,
  isGatewaySessionAuthorized,
  isInsecureLocalGatewayBypassAllowed,
  type GatewayAccess
} from "./_gateway-auth";

const EXPECTED_BACKEND_API_BASE_URL = "http://127.0.0.1:8000";
const EXPECTED_VOICE_API_BASE_URL = "http://127.0.0.1:9001";
const PROTECTED_WEB_ENVIRONMENTS = new Set(["field", "staging", "production"]);

export function isProtectedWebUpstreamRuntime(
  environment = process.env.WALKSAFE_ENVIRONMENT,
  nodeEnvironment = process.env.NODE_ENV,
  startMode = process.env.WALKSAFE_WEB_START_MODE
): boolean {
  const normalizedEnvironment = environment?.trim().toLowerCase() ?? "";
  return (
    PROTECTED_WEB_ENVIRONMENTS.has(normalizedEnvironment) ||
    nodeEnvironment?.trim().toLowerCase() === "production" ||
    startMode?.trim().toLowerCase() === "production"
  );
}

export function resolveUpstreamBaseUrl(
  name: "BACKEND_API_BASE_URL" | "VOICE_API_BASE_URL",
  rawValue: string | undefined,
  expectedValue: string,
  protectedRuntime = isProtectedWebUpstreamRuntime()
): string {
  const value = rawValue ?? expectedValue;
  if (!protectedRuntime) return value.replace(/\/+$/, "");

  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${name} must be exactly ${expectedValue} in field, staging, and production`);
  }
  const expected = new URL(expectedValue);
  if (
    value !== expectedValue ||
    parsed.protocol !== "http:" ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.hostname !== expected.hostname ||
    parsed.port !== expected.port ||
    parsed.pathname !== "/" ||
    parsed.search !== "" ||
    parsed.hash !== "" ||
    parsed.origin !== expected.origin
  ) {
    throw new Error(`${name} must be exactly ${expectedValue} in field, staging, and production`);
  }
  return expectedValue;
}

const BACKEND_API_BASE_URL = resolveUpstreamBaseUrl(
  "BACKEND_API_BASE_URL",
  process.env.BACKEND_API_BASE_URL,
  EXPECTED_BACKEND_API_BASE_URL
);
const VOICE_API_BASE_URL = resolveUpstreamBaseUrl(
  "VOICE_API_BASE_URL",
  process.env.VOICE_API_BASE_URL,
  EXPECTED_VOICE_API_BASE_URL
);

export const FIELD_TEST_TOKEN_HEADER = "x-walksafe-field-test-token";
export const ADMIN_TOKEN_HEADER = "x-walksafe-admin-token";
export const ACTOR_ID_HEADER = "x-walksafe-actor-id";
export const ACTOR_ASSERTION_HEADER = "x-walksafe-actor-assertion";
export const VOICE_SERVICE_TOKEN_HEADER = "x-walksafe-voice-service-token";
export const VOICE_CLIENT_IP_HEADER = "x-walksafe-voice-client-ip";
export const IMAGE_MULTIPART_LIMIT_BYTES = 9 * 1024 * 1024;
export const AUDIO_MULTIPART_LIMIT_BYTES = 11 * 1024 * 1024;
const DEFAULT_PROXY_TIMEOUT_MS = 15_000;
const IMAGE_UPLOAD_RATE_WINDOW_MS = 60_000;
const IMAGE_UPLOAD_GLOBAL_RATE_LIMIT = 120;
const IMAGE_UPLOAD_ACTOR_RATE_LIMIT = 12;
const IMAGE_UPLOAD_IP_RATE_LIMIT = 30;
const IMAGE_UPLOAD_MAX_CONCURRENCY = 1;
const VOICE_STT_RATE_WINDOW_MS = 60_000;
const VOICE_STT_GLOBAL_RATE_LIMIT = 120;
const VOICE_STT_ACTOR_RATE_LIMIT = 12;
const VOICE_STT_IP_RATE_LIMIT = 30;
const VOICE_STT_MAX_UPLOAD_CONCURRENCY = 1;
const ACTOR_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;
const UPLOAD_FILENAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.(?:jpe?g|png|webp)$/i;

type BoundedFormDataResult =
  | { formData: FormData; error?: never }
  | { formData?: never; error: Response };

type VoiceSttUploadAdmission =
  | { release: () => void; error?: never }
  | { release?: never; error: Response };

type ImageUploadAdmission =
  | { release: () => void; error?: never }
  | { release?: never; error: Response };

const imageUploadRateEvents = new Map<string, number[]>();
let imageUploadsInFlight = 0;
const voiceSttRateEvents = new Map<string, number[]>();
let voiceSttUploadsInFlight = 0;

function multipartError(status: number, code: string, message: string): BoundedFormDataResult {
  return { error: Response.json({ detail: { code, message } }, { status }) };
}

export async function readBoundedMultipartFormData(
  request: Request,
  maxBytes: number,
  timeoutMs = DEFAULT_PROXY_TIMEOUT_MS
): Promise<BoundedFormDataResult> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("multipart/form-data;")) {
    return multipartError(415, "multipart_required", "content-type must be multipart/form-data with a boundary");
  }

  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    const parsedLength = Number(declaredLength);
    if (!Number.isSafeInteger(parsedLength) || parsedLength < 0) {
      return multipartError(400, "invalid_content_length", "content-length must be a non-negative integer");
    }
    if (parsedLength > maxBytes) {
      return multipartError(413, "multipart_too_large", `multipart request must be <= ${maxBytes} bytes`);
    }
  }

  if (!request.body) {
    return multipartError(400, "empty_multipart_body", "multipart request body is required");
  }

  const reader = request.body.getReader();
  const timeoutSignal = AbortSignal.timeout(Math.max(1, timeoutMs));
  const readSignal = AbortSignal.any([request.signal, timeoutSignal]);
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      if (readSignal.aborted) throw readSignal.reason;
      let rejectOnAbort!: (reason?: unknown) => void;
      const aborted = new Promise<never>((_resolve, reject) => {
        rejectOnAbort = reject;
      });
      const onAbort = () => rejectOnAbort(readSignal.reason);
      readSignal.addEventListener("abort", onAbort, { once: true });
      let chunk;
      try {
        chunk = await Promise.race([reader.read(), aborted]);
      } finally {
        readSignal.removeEventListener("abort", onAbort);
      }
      const { done, value } = chunk;
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        void reader.cancel("multipart request exceeded the configured limit").catch(() => undefined);
        return multipartError(413, "multipart_too_large", `multipart request must be <= ${maxBytes} bytes`);
      }
      chunks.push(value);
    }

    const bytes = new Uint8Array(totalBytes);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    const formData = await new Response(bytes, { headers: { "content-type": contentType } }).formData();
    return { formData };
  } catch {
    void reader.cancel("multipart request was cancelled").catch(() => undefined);
    if (request.signal.aborted) {
      return multipartError(499, "gateway_client_closed", "client request was cancelled");
    }
    if (timeoutSignal.aborted) {
      return multipartError(408, "multipart_read_timeout", "multipart request body exceeded its deadline");
    }
    return multipartError(400, "invalid_multipart_body", "multipart request body could not be parsed");
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // A cancelled network read can retain the lock until its source observes cancellation.
    }
  }
}

function imageUploadAdmissionError(status: number, code: string, message: string, retryAfter: number): Response {
  return Response.json(
    { detail: { code, message } },
    {
      status,
      headers: {
        "cache-control": "no-store",
        "retry-after": String(Math.max(1, retryAfter))
      }
    }
  );
}

function pruneImageUploadRateEvents(nowMs: number): void {
  const cutoff = nowMs - IMAGE_UPLOAD_RATE_WINDOW_MS;
  for (const [key, events] of imageUploadRateEvents) {
    const current = events.filter((eventAt) => eventAt > cutoff && eventAt <= nowMs);
    if (current.length === 0) imageUploadRateEvents.delete(key);
    else imageUploadRateEvents.set(key, current);
  }
}

function imageUploadClientIp(request: Request): string {
  const configuredHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER?.trim().toLowerCase() ?? "";
  if (configuredHeader !== "cf-connecting-ip" && configuredHeader !== "x-real-ip") return "unbound";
  const value = request.headers.get(configuredHeader) ?? "";
  if (!value || value !== value.trim() || value.includes(",") || isIP(value) === 0) return "unbound";
  return value;
}

export function acquireImageUploadAdmission(
  request: Request,
  fieldHeaders: Headers,
  nowMs = Date.now()
): ImageUploadAdmission {
  if (!Number.isFinite(nowMs) || nowMs < 0) {
    return {
      error: imageUploadAdmissionError(
        503,
        "image_admission_unavailable",
        "Image upload admission clock is invalid.",
        1
      )
    };
  }
  pruneImageUploadRateEvents(nowMs);
  const actorId = fieldHeaders.get(ACTOR_ID_HEADER)?.trim() || "unbound";
  const clientIp = imageUploadClientIp(request);
  const limits: Array<[string, number]> = [
    ["image:global", IMAGE_UPLOAD_GLOBAL_RATE_LIMIT],
    [`image:actor:${actorId}`, IMAGE_UPLOAD_ACTOR_RATE_LIMIT],
    [`image:ip:${clientIp}`, IMAGE_UPLOAD_IP_RATE_LIMIT]
  ];
  for (const [key, limit] of limits) {
    const events = imageUploadRateEvents.get(key) ?? [];
    if (events.length >= limit) {
      const retryAfter = Math.ceil((events[0] + IMAGE_UPLOAD_RATE_WINDOW_MS - nowMs) / 1000);
      return {
        error: imageUploadAdmissionError(
          429,
          "image_upload_rate_limited",
          "Image upload rate limit exceeded.",
          retryAfter
        )
      };
    }
  }
  if (imageUploadsInFlight >= IMAGE_UPLOAD_MAX_CONCURRENCY) {
    return {
      error: imageUploadAdmissionError(
        503,
        "image_upload_busy",
        "Image upload capacity is busy. Retry shortly.",
        1
      )
    };
  }
  for (const [key] of limits) {
    const events = imageUploadRateEvents.get(key) ?? [];
    events.push(nowMs);
    imageUploadRateEvents.set(key, events);
  }
  imageUploadsInFlight += 1;
  let released = false;
  return {
    release: () => {
      if (released) return;
      released = true;
      imageUploadsInFlight = Math.max(0, imageUploadsInFlight - 1);
    }
  };
}

function voiceSttAdmissionError(status: number, code: string, message: string, retryAfter: number): Response {
  return Response.json(
    { detail: { code, message } },
    {
      status,
      headers: {
        "cache-control": "no-store",
        "retry-after": String(Math.max(1, retryAfter))
      }
    }
  );
}

function pruneVoiceSttRateEvents(nowMs: number): void {
  const cutoff = nowMs - VOICE_STT_RATE_WINDOW_MS;
  for (const [key, events] of voiceSttRateEvents) {
    const current = events.filter((eventAt) => eventAt > cutoff && eventAt <= nowMs);
    if (current.length === 0) voiceSttRateEvents.delete(key);
    else voiceSttRateEvents.set(key, current);
  }
}

export function acquireVoiceSttUploadAdmission(
  voiceHeaders: Headers,
  nowMs = Date.now()
): VoiceSttUploadAdmission {
  if (!Number.isFinite(nowMs) || nowMs < 0) {
    return {
      error: voiceSttAdmissionError(503, "voice_admission_unavailable", "Voice admission clock is invalid.", 1)
    };
  }
  pruneVoiceSttRateEvents(nowMs);
  const actorId = voiceHeaders.get(ACTOR_ID_HEADER)?.trim() || "unbound";
  const clientIp = voiceHeaders.get(VOICE_CLIENT_IP_HEADER)?.trim() || "unbound";
  const limits: Array<[string, number]> = [
    ["global", VOICE_STT_GLOBAL_RATE_LIMIT],
    [`actor:${actorId}`, VOICE_STT_ACTOR_RATE_LIMIT],
    [`ip:${clientIp}`, VOICE_STT_IP_RATE_LIMIT]
  ];
  for (const [key, limit] of limits) {
    const events = voiceSttRateEvents.get(key) ?? [];
    if (events.length >= limit) {
      const retryAfter = Math.ceil((events[0] + VOICE_STT_RATE_WINDOW_MS - nowMs) / 1000);
      return {
        error: voiceSttAdmissionError(
          429,
          "voice_rate_limited",
          "Voice request rate limit exceeded.",
          retryAfter
        )
      };
    }
  }
  if (voiceSttUploadsInFlight >= VOICE_STT_MAX_UPLOAD_CONCURRENCY) {
    return {
      error: voiceSttAdmissionError(
        503,
        "stt_upload_busy",
        "Speech upload capacity is busy. Retry shortly.",
        1
      )
    };
  }
  for (const [key] of limits) {
    const events = voiceSttRateEvents.get(key) ?? [];
    events.push(nowMs);
    voiceSttRateEvents.set(key, events);
  }
  voiceSttUploadsInFlight += 1;
  let released = false;
  return {
    release: () => {
      if (released) return;
      released = true;
      voiceSttUploadsInFlight = Math.max(0, voiceSttUploadsInFlight - 1);
    }
  };
}

export function backendUrl(path: string, request?: Request): string {
  const query = request ? new URL(request.url).search : "";
  return `${BACKEND_API_BASE_URL}${path}${query}`;
}

export function voiceUrl(path: string): string {
  return `${VOICE_API_BASE_URL}${path}`;
}

export function backendUploadPath(pathParts: readonly string[]): string | null {
  if (pathParts.length !== 1 || !UPLOAD_FILENAME_PATTERN.test(pathParts[0])) {
    return null;
  }
  return `/uploads/${encodeURIComponent(pathParts[0])}`;
}

export function createBackendActorAssertion(
  access: GatewayAccess,
  actorId: string,
  nowSeconds = Math.floor(Date.now() / 1000)
): string | null {
  const normalizedActorId = actorId.trim();
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  if (!ACTOR_ID_PATTERN.test(normalizedActorId) || secret.length < 32 || !Number.isSafeInteger(nowSeconds) || nowSeconds <= 0) {
    return null;
  }
  const message = `walksafe-backend-actor-v1:${access}:${normalizedActorId}:${nowSeconds}`;
  const signature = createHmac("sha256", secret).update(message).digest("base64url");
  return `v1.${nowSeconds}.${signature}`;
}

function attachBackendActor(headers: Headers, access: GatewayAccess, actorId: string | null): void {
  if (!actorId) return;
  const assertion = createBackendActorAssertion(access, actorId);
  if (!assertion) return;
  headers.set(ACTOR_ID_HEADER, actorId.trim());
  headers.set(ACTOR_ASSERTION_HEADER, assertion);
}

export async function fetchBackend(
  request: Request,
  url: string,
  init: RequestInit = {},
  timeoutMs = DEFAULT_PROXY_TIMEOUT_MS
): Promise<Response> {
  const timeoutSignal = AbortSignal.timeout(timeoutMs);
  const signals = [request.signal, timeoutSignal];
  if (init.signal) signals.push(init.signal);
  try {
    return await fetch(url, { ...init, signal: AbortSignal.any(signals) });
  } catch {
    if (timeoutSignal.aborted) {
      return Response.json(
        { detail: { code: "gateway_upstream_timeout", message: "upstream request exceeded its deadline" } },
        { status: 504, headers: { "cache-control": "no-store" } }
      );
    }
    if (request.signal.aborted) {
      return Response.json(
        { detail: { code: "gateway_client_closed", message: "client request was cancelled" } },
        { status: 499, headers: { "cache-control": "no-store" } }
      );
    }
    return Response.json(
      {
        detail: {
          code: "gateway_upstream_unavailable",
          message: "upstream request failed"
        }
      },
      { status: 502, headers: { "cache-control": "no-store" } }
    );
  }
}

export function authorizeProxyRequest(request: Request, access: GatewayAccess): Response | null {
  if (
    !isGatewayAccessConfigured("field") &&
    !isGatewayAccessConfigured("admin") &&
    isInsecureLocalGatewayBypassAllowed()
  ) {
    return null;
  }
  if (!isGatewayAccessConfigured(access)) {
    return gatewayUnavailableResponse();
  }
  if (isGatewaySessionAuthorized(request, access)) {
    return null;
  }
  const otherAccess: GatewayAccess = access === "field" ? "admin" : "field";
  if (isGatewaySessionAuthorized(request, otherAccess)) {
    return gatewayUnauthorizedResponse(true);
  }
  return gatewayUnauthorizedResponse();
}

export function proxyRequestHeaders(
  request: Request,
  access: GatewayAccess,
  initial?: HeadersInit
): Headers {
  const headers = new Headers(initial);
  if (access === "admin" && isGatewaySessionAuthorized(request, "admin")) {
    headers.set(ADMIN_TOKEN_HEADER, gatewayTokenForBackend("admin"));
    attachBackendActor(headers, "admin", gatewaySessionActor(request, "admin"));
  } else if (access === "field" && isGatewaySessionAuthorized(request, "field")) {
    headers.set(FIELD_TEST_TOKEN_HEADER, gatewayTokenForBackend("field"));
    attachBackendActor(headers, "field", gatewaySessionActor(request, "field"));
  }
  return headers;
}

export function voiceProxyRequestHeaders(request: Request, initial?: HeadersInit): Headers | null {
  const token = process.env.VOICE_SERVICE_TOKEN?.trim() ?? "";
  if (token.length < 24 || isGatewayCredential(token)) return null;
  const headers = new Headers(initial);
  headers.set(VOICE_SERVICE_TOKEN_HEADER, token);
  const actorId = gatewaySessionActor(request, "field");
  if (actorId) headers.set(ACTOR_ID_HEADER, actorId);

  const trustedIpHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER?.trim().toLowerCase() ?? "";
  if (trustedIpHeader === "cf-connecting-ip" || trustedIpHeader === "x-real-ip") {
    const clientIp = request.headers.get(trustedIpHeader)?.trim() ?? "";
    if (isIP(clientIp) !== 0) headers.set(VOICE_CLIENT_IP_HEADER, clientIp);
  }
  return headers;
}

export function voiceServiceUnavailableResponse(): Response {
  return Response.json(
    {
      detail: {
        code: "voice_service_auth_not_configured",
        message: "Voice service authentication is not configured"
      }
    },
    { status: 503, headers: { "cache-control": "no-store" } }
  );
}

export async function toBackendResponse(response: Response, fallbackContentType = "application/json"): Promise<Response> {
  const headers = new Headers();
  const contentType = response.headers.get("content-type") ?? fallbackContentType;
  if (contentType) {
    headers.set("content-type", contentType);
  }

  const contentDisposition = response.headers.get("content-disposition");
  if (contentDisposition) {
    headers.set("content-disposition", contentDisposition);
  }

  const cacheControl = response.headers.get("cache-control");
  if (cacheControl) {
    headers.set("cache-control", cacheControl);
  }

  for (const headerName of [
    "retry-after",
    "x-walksafe-audit-id",
    "x-walksafe-actor-id",
    "x-walksafe-demo-filter",
    "x-walksafe-export-profile",
    "x-walksafe-location-precision"
  ]) {
    const value = response.headers.get(headerName);
    if (value) headers.set(headerName, value);
  }

  const requestId = response.headers.get("x-request-id") ?? response.headers.get("x-correlation-id");
  if (requestId) {
    headers.set("x-request-id", requestId);
  }

  return new Response(response.body, {
    status: response.status,
    headers
  });
}
