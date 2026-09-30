import { createHash, createHmac, randomBytes } from "node:crypto";

import {
  gatewaySessionActor,
  gatewaySessionAccountGeneration,
  gatewaySessionAuthEpoch,
  gatewayFieldLongSessionBinding,
  gatewayTrustedClientIp,
  gatewayTokenForBackend,
  gatewayUnavailableResponse,
  gatewayUnauthorizedResponse,
  isGatewayAccessConfigured,
  isFieldSessionAuthorized,
  isInsecureLocalGatewayBypassAllowed
} from "./auth.js";
import { BACKEND_API_BASE_URL } from "./config.js";
import { readBoundedJsonBody } from "./request-body.js";

export const FIELD_TEST_TOKEN_HEADER = "x-walksafe-field-test-token";
export const ACTOR_ID_HEADER = "x-walksafe-actor-id";
export const ACTOR_ASSERTION_HEADER = "x-walksafe-actor-assertion";
export const ACCOUNT_GENERATION_HEADER = "x-walksafe-account-generation";
export const RAW_REQUEST_PROOF_HEADER = "x-walksafe-raw-request-proof";
export const RAW_PURPOSE_HEADER = "x-walksafe-raw-purpose";
export const RAW_WALK_ID_HEADER = "x-walksafe-raw-walk-id";
export const RAW_MANIFEST_SHA256_HEADER = "x-walksafe-raw-manifest-sha256";
export const RAW_CHUNK_SHA256_HEADER = "x-walksafe-chunk-sha256";
export const RAW_COMMIT_SHA256_HEADER = "x-walksafe-raw-commit-sha256";
export const RAW_CONSENT_RECEIPT_SHA256_HEADER =
  "x-walksafe-consent-receipt-sha256";
export const DELETION_ACCESS_PRE_DIGEST_HEADER =
  "x-walksafe-deletion-access-pre-digest";
export const DELETION_TOMBSTONE_HEADER =
  "x-walksafe-deletion-tombstone-id";
export const IMAGE_MULTIPART_LIMIT_BYTES = 9 * 1024 * 1024;
export const REPORT_METADATA_LIMIT_BYTES = 64 * 1024;
export const REPORT_IMAGE_LIMIT_BYTES = 8 * 1024 * 1024;

const DEFAULT_PROXY_TIMEOUT_MS = 15_000;
/**
 * S19-59: a report v2 upload's own limits, the multipart read and the Backend proxy call
 * (both DEFAULT_PROXY_TIMEOUT_MS); the adapter's whole-request deadline is set above this.
 */
export const REPORT_V2_OWN_LIMITS_MS = 2 * DEFAULT_PROXY_TIMEOUT_MS;
/**
 * gw-F6 (S19-59): the per-request Backend account-session check (routes.ts
 * backendAccountSessionGate) that a v7 account session passes before its route runs. It is
 * a small bodyless POST, so it has its own short limit ("unavailable" → 503, never a logout),
 * and the adapter's whole-request deadline adds it to every route's own limits.
 */
export const BACKEND_SESSION_GATE_TIMEOUT_MS = 5_000;
/** gw-F6: an ordinary route's own limits: bounded JSON body read 10 s + one Backend call. */
export const ORDINARY_ROUTE_OWN_LIMITS_MS = 10_000 + DEFAULT_PROXY_TIMEOUT_MS;
const IMAGE_UPLOAD_RATE_WINDOW_MS = 60_000;
const IMAGE_UPLOAD_GLOBAL_RATE_LIMIT = 120;
const IMAGE_UPLOAD_ACTOR_RATE_LIMIT = 12;
const IMAGE_UPLOAD_IP_RATE_LIMIT = 30;
/**
 * S19-49: conservative process-wide default. Raising it needs a deployment memory budget
 * (measured peak per maximum upload x N); it is not raised here.
 */
const IMAGE_UPLOAD_MAX_CONCURRENCY = 1;
/** S19-49: one upload per actor, so one account can never hold several global slots. */
const IMAGE_UPLOAD_MAX_CONCURRENCY_PER_ACTOR = 1;
const ACTOR_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;
const RAW_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const LOWER_SHA256 = /^[0-9a-f]{64}$/;

type BoundedFormDataResult =
  | { formData: FormData; error?: never }
  | { formData?: never; error: Response };

export type ImageUploadAdmission =
  | { release: () => void; error?: never }
  | { release?: never; error: Response };

export type GatewayFetch = (
  input: string | URL | Request,
  init?: RequestInit
) => Promise<Response>;

const imageUploadRateEvents = new Map<string, number[]>();
let imageUploadsInFlight = 0;
const imageUploadsInFlightByActor = new Map<string, number>();
let imageUploadMaxConcurrencyForTests: number | null = null;

function imageUploadMaxConcurrency(): number {
  return imageUploadMaxConcurrencyForTests ?? IMAGE_UPLOAD_MAX_CONCURRENCY;
}

/** Test-only: exercises per-actor fairness when the global limit is above one. */
export function setImageUploadMaxConcurrencyForTests(value: number | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("image upload concurrency override is unavailable in production");
  }
  if (value !== null && (!Number.isSafeInteger(value) || value < 1)) {
    throw new Error("invalid image upload concurrency override");
  }
  imageUploadMaxConcurrencyForTests = value;
}

function multipartError(status: number, code: string, message: string): BoundedFormDataResult {
  return {
    error: Response.json(
      { detail: { code, message } },
      { status, headers: { "cache-control": "no-store" } }
    )
  };
}

function multipartBoundary(contentType: string): string | null {
  const match = /;\s*boundary=(?:"([\x20-\x7e]{1,70})"|([^;\s]{1,70}))/i.exec(contentType);
  const boundary = match?.[1] ?? match?.[2] ?? "";
  return boundary && !boundary.endsWith(" ") ? boundary : null;
}

export function countMultipartDelimiters(bytes: Uint8Array, boundary: string): number {
  const body = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const delimiter = Buffer.from(`--${boundary}`, "utf8");
  let count = 0;
  let offset = 0;
  while (offset < body.length) {
    const found = body.indexOf(delimiter, offset);
    if (found < 0) break;
    const startsLine = found === 0 || (body[found - 2] === 13 && body[found - 1] === 10);
    const after = found + delimiter.length;
    const opensPart = body[after] === 13 && body[after + 1] === 10;
    const closesBody = body[after] === 45 && body[after + 1] === 45 && (
      after + 2 === body.length ||
      (body[after + 2] === 13 && body[after + 3] === 10)
    );
    if (startsLine && (opensPart || closesBody)) {
      count += 1;
      if (count > 3) return count;
    }
    offset = found + delimiter.length;
  }
  return count;
}

function validateReportMultipart(formData: FormData): BoundedFormDataResult {
  const entries = [...formData.entries()];
  const metadataValues = formData.getAll("metadata");
  const imageValues = formData.getAll("image");
  if (
    entries.length !== 2 ||
    metadataValues.length !== 1 ||
    imageValues.length !== 1 ||
    entries.some(([name]) => name !== "metadata" && name !== "image")
  ) {
    return multipartError(422, "multipart_fields_invalid", "exactly one metadata and one image part are required");
  }
  const metadata = metadataValues[0];
  if (typeof metadata !== "string") {
    return multipartError(422, "multipart_metadata_invalid", "metadata must be a text field");
  }
  if (Buffer.byteLength(metadata, "utf8") > REPORT_METADATA_LIMIT_BYTES) {
    return multipartError(413, "multipart_metadata_too_large", `metadata must be <= ${REPORT_METADATA_LIMIT_BYTES} bytes`);
  }
  const image = imageValues[0];
  if (!(image instanceof Blob) || image.type.toLowerCase() !== "image/jpeg") {
    return multipartError(415, "multipart_image_type_invalid", "image must be an image/jpeg file");
  }
  if (image.size === 0 || image.size > REPORT_IMAGE_LIMIT_BYTES) {
    return multipartError(413, "multipart_image_size_invalid", `image must be between 1 and ${REPORT_IMAGE_LIMIT_BYTES} bytes`);
  }
  return { formData };
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
  const boundary = multipartBoundary(contentType);
  if (!boundary) {
    return multipartError(400, "multipart_boundary_invalid", "multipart boundary is missing or invalid");
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
  const contiguousThresholdBytes = Math.max(4 * 1024 * 1024, Math.floor(maxBytes / 2));
  let contiguous: Uint8Array | null = null;
  let totalBytes = 0;
  try {
    while (true) {
      if (readSignal.aborted) throw readSignal.reason;
      let rejectOnAbort: (reason?: unknown) => void = () => undefined;
      const aborted = new Promise<never>((_resolve, reject) => {
        rejectOnAbort = reject;
      });
      const onAbort = () => rejectOnAbort(readSignal.reason);
      readSignal.addEventListener("abort", onAbort, { once: true });
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await Promise.race([reader.read(), aborted]);
      } finally {
        readSignal.removeEventListener("abort", onAbort);
      }
      if (chunk.done) break;
      const receivedBytes = totalBytes + chunk.value.byteLength;
      if (receivedBytes > maxBytes) {
        void reader.cancel("multipart request exceeded the configured limit").catch(() => undefined);
        return multipartError(413, "multipart_too_large", `multipart request must be <= ${maxBytes} bytes`);
      }
      if (contiguous !== null) {
        contiguous.set(chunk.value, totalBytes);
      } else if (receivedBytes > contiguousThresholdBytes) {
        contiguous = new Uint8Array(maxBytes);
        let offset = 0;
        for (const previous of chunks) {
          contiguous.set(previous, offset);
          offset += previous.byteLength;
        }
        chunks.length = 0;
        contiguous.set(chunk.value, totalBytes);
      } else {
        chunks.push(chunk.value);
      }
      totalBytes = receivedBytes;
    }

    let bytes: Uint8Array;
    if (contiguous !== null) {
      bytes = contiguous.subarray(0, totalBytes);
    } else {
      bytes = new Uint8Array(totalBytes);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
    }
    if (countMultipartDelimiters(bytes, boundary) !== 3) {
      return multipartError(422, "multipart_part_count_invalid", "multipart body must contain exactly two parts");
    }
    // S19-49: hand the collected bytes to the multipart parser as a stream. A BufferSource
    // body is copied again on construction, and the former `new Uint8Array(bytes)` added a
    // further full copy of the request.
    const collected = bytes;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(collected);
        controller.close();
      }
    });
    const formData = await new Response(body, { headers: { "content-type": contentType } }).formData();
    return validateReportMultipart(formData);
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
      // Cancellation can retain the lock until the underlying socket observes it.
    }
  }
}

function imageUploadAdmissionError(
  status: number,
  code: string,
  message: string,
  retryAfter: number
): Response {
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
  return gatewayTrustedClientIp(request) ?? "unbound";
}

/**
 * S19-32: the rate part of the report admission, checked before the consent lookup without
 * taking a slot or recording an event. The global/actor slot is taken by
 * acquireImageUploadAdmission only after the first consent check (still before the body is
 * opened), so a report waiting on a slow consent lock never holds the one global slot.
 */
export function imageUploadRateLimitedResponse(
  request: Request,
  fieldHeaders: Headers,
  nowMs = Date.now()
): Response | null {
  if (!Number.isFinite(nowMs) || nowMs < 0) return null;
  pruneImageUploadRateEvents(nowMs);
  const actorId = fieldHeaders.get(ACTOR_ID_HEADER)?.trim() || "unbound";
  const clientIp = imageUploadClientIp(request);
  for (const [key, limit] of [
    ["image:global", IMAGE_UPLOAD_GLOBAL_RATE_LIMIT],
    [`image:actor:${actorId}`, IMAGE_UPLOAD_ACTOR_RATE_LIMIT],
    [`image:ip:${clientIp}`, IMAGE_UPLOAD_IP_RATE_LIMIT]
  ] as Array<[string, number]>) {
    const events = imageUploadRateEvents.get(key) ?? [];
    if (events.length >= limit) {
      const retryAfter = Math.ceil((events[0]! + IMAGE_UPLOAD_RATE_WINDOW_MS - nowMs) / 1000);
      return imageUploadAdmissionError(
        429,
        "image_upload_rate_limited",
        "Image upload rate limit exceeded.",
        retryAfter
      );
    }
  }
  return null;
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
      const retryAfter = Math.ceil((events[0]! + IMAGE_UPLOAD_RATE_WINDOW_MS - nowMs) / 1000);
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
  if (
    (imageUploadsInFlightByActor.get(actorId) ?? 0) >= IMAGE_UPLOAD_MAX_CONCURRENCY_PER_ACTOR ||
    imageUploadsInFlight >= imageUploadMaxConcurrency()
  ) {
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
  imageUploadsInFlightByActor.set(actorId, (imageUploadsInFlightByActor.get(actorId) ?? 0) + 1);
  let released = false;
  return {
    release: () => {
      if (released) return;
      released = true;
      imageUploadsInFlight = Math.max(0, imageUploadsInFlight - 1);
      const remaining = (imageUploadsInFlightByActor.get(actorId) ?? 1) - 1;
      if (remaining > 0) imageUploadsInFlightByActor.set(actorId, remaining);
      else imageUploadsInFlightByActor.delete(actorId);
    }
  };
}

export function resetImageUploadAdmissionForTests(): void {
  imageUploadRateEvents.clear();
  imageUploadsInFlight = 0;
  imageUploadsInFlightByActor.clear();
}

export function backendUrl(path: string, request?: Request): string {
  const query = request ? new URL(request.url).search : "";
  return `${BACKEND_API_BASE_URL}${path}${query}`;
}

export function backendServiceRequestHeaders(initial?: HeadersInit): Headers | null {
  const token = gatewayTokenForBackend();
  if (token.length < 24) return null;
  const headers = new Headers(initial);
  headers.set(FIELD_TEST_TOKEN_HEADER, token);
  return headers;
}

/**
 * Legacy v2/v3 wire format, kept only for the Backend parallel-period contract.
 * The Gateway no longer sends it; general Backend calls carry v4 (see
 * `signBackendActorRequest`).
 */
export function createBackendActorAssertion(
  actorId: string,
  accountGeneration: number,
  nowSeconds = Math.floor(Date.now() / 1000),
  authEpoch: number | null = null
): string | null {
  const normalizedActorId = actorId.trim();
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  if (
    !ACTOR_ID_PATTERN.test(normalizedActorId) ||
    secret.length < 32 ||
    !Number.isSafeInteger(accountGeneration) ||
    accountGeneration < 1 ||
    !Number.isSafeInteger(nowSeconds) ||
    nowSeconds <= 0 ||
    (authEpoch !== null && (!Number.isSafeInteger(authEpoch) || authEpoch < 1))
  ) {
    return null;
  }
  const message = authEpoch === null
    ? `walksafe-backend-actor-v2:field:${normalizedActorId}:${accountGeneration}:${nowSeconds}`
    : `walksafe-backend-actor-v3:field:${normalizedActorId}:${accountGeneration}:${authEpoch}:${nowSeconds}`;
  const signature = createHmac("sha256", secret).update(message).digest("base64url");
  return authEpoch === null
    ? `v2.${nowSeconds}.${signature}`
    : `v3.${nowSeconds}.${authEpoch}.${signature}`;
}

/**
 * S19-52: request-bound v4 actor assertion. The signature covers the Backend
 * audience, key ID, actor authority, a 30 s lifetime, a single-use jti and the
 * exact request (method, origin-form target, SHA-256 of the sent body bytes).
 */
export const BACKEND_ACTOR_ASSERTION_AUDIENCE = "walksafe-backend";
export const BACKEND_ACTOR_ASSERTION_TTL_SECONDS = 30;
/** Placeholder until `fetchBackend` signs; the Backend rejects it if ever sent. */
export const PENDING_BACKEND_ACTOR_ASSERTION = "v4.pending";
const EMPTY_BODY_SHA256 =
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
const V4_METHOD = /^[A-Z]{3,7}$/;
const V4_TARGET = /^\/[\x21-\x7e]{0,4095}$/;
const V4_JTI = /^[A-Za-z0-9_-]{22}$/;

export type BackendActorBinding = Readonly<{
  actorId: string;
  accountGeneration: number;
  authEpoch: number | null;
}>;

export type BackendActorRequestBinding = BackendActorBinding & Readonly<{
  method: string;
  target: string;
  bodySha256: string;
}>;

function gatewayAssertionSecret(): string {
  return process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
}

export function backendActorAssertionKeyId(secret: string): string {
  return createHmac("sha256", secret)
    .update("walksafe-backend-actor-kid-v1")
    .digest("base64url")
    .slice(0, 16);
}

function backendActorBindingValid(binding: BackendActorBinding): boolean {
  return ACTOR_ID_PATTERN.test(binding.actorId.trim()) &&
    gatewayAssertionSecret().length >= 32 &&
    Number.isSafeInteger(binding.accountGeneration) && binding.accountGeneration >= 1 &&
    (binding.authEpoch === null ||
      (Number.isSafeInteger(binding.authEpoch) && binding.authEpoch >= 1));
}

export function createBackendActorAssertionV4(
  input: BackendActorRequestBinding,
  nowSeconds = Math.floor(Date.now() / 1000),
  jti = randomBytes(16).toString("base64url")
): string | null {
  const actorId = input.actorId.trim();
  const secret = gatewayAssertionSecret();
  if (
    !backendActorBindingValid(input) ||
    !Number.isSafeInteger(nowSeconds) || nowSeconds <= 0 ||
    !V4_METHOD.test(input.method) ||
    !V4_TARGET.test(input.target) ||
    !LOWER_SHA256.test(input.bodySha256) ||
    !V4_JTI.test(jti)
  ) {
    return null;
  }
  const expiresAt = nowSeconds + BACKEND_ACTOR_ASSERTION_TTL_SECONDS;
  const kid = backendActorAssertionKeyId(secret);
  const epoch = input.authEpoch ?? 0;
  const message = [
    "walksafe-backend-actor-v4",
    `aud:${BACKEND_ACTOR_ASSERTION_AUDIENCE}`,
    `kid:${kid}`,
    "access:field",
    `actor:${actorId}`,
    `generation:${input.accountGeneration}`,
    `epoch:${epoch}`,
    `iat:${nowSeconds}`,
    `exp:${expiresAt}`,
    `jti:${jti}`,
    `method:${input.method}`,
    `target:${input.target}`,
    `body-sha256:${input.bodySha256}`
  ].join("\n");
  const signature = createHmac("sha256", secret).update(message, "utf8").digest("base64url");
  return `v4.${nowSeconds}.${expiresAt}.${epoch}.${kid}.${jti}.${signature}`;
}

const pendingBackendActorBindings = new WeakMap<Headers, BackendActorBinding>();

/** Records actor authority on Gateway-created headers; signing waits for the request. */
function bindBackendActor(headers: Headers, binding: BackendActorBinding): boolean {
  if (!backendActorBindingValid(binding)) return false;
  const actorId = binding.actorId.trim();
  headers.set(ACTOR_ID_HEADER, actorId);
  headers.set(ACCOUNT_GENERATION_HEADER, String(binding.accountGeneration));
  headers.set(ACTOR_ASSERTION_HEADER, PENDING_BACKEND_ACTOR_ASSERTION);
  pendingBackendActorBindings.set(headers, {
    actorId,
    accountGeneration: binding.accountGeneration,
    authEpoch: binding.authEpoch
  });
  return true;
}

type SignedBackendBody = {
  bytes: Uint8Array;
  replacement?: Uint8Array<ArrayBuffer>;
  contentType?: string;
};

async function signableBackendBody(
  body: RequestInit["body"]
): Promise<SignedBackendBody | null> {
  if (body === undefined || body === null) return { bytes: new Uint8Array(0) };
  if (typeof body === "string") return { bytes: Buffer.from(body, "utf8") };
  if (body instanceof ArrayBuffer) return { bytes: new Uint8Array(body) };
  if (ArrayBuffer.isView(body)) {
    return { bytes: new Uint8Array(body.buffer, body.byteOffset, body.byteLength) };
  }
  if (body instanceof FormData || body instanceof Blob || body instanceof URLSearchParams) {
    // A multipart boundary is chosen at serialization time, so serialize exactly
    // once here and send (and sign) those bytes.
    const serialized = new Response(body);
    const bytes = new Uint8Array(await serialized.arrayBuffer());
    const contentType = serialized.headers.get("content-type");
    return contentType === null
      ? { bytes, replacement: bytes }
      : { bytes, replacement: bytes, contentType };
  }
  return null;
}

export class BackendActorAssertionUnavailableError extends Error {
  constructor() {
    super("the request-bound backend actor assertion could not be created");
    this.name = "BackendActorAssertionUnavailableError";
  }
}

/**
 * Signs a v4 assertion for headers bound by `bindBackendActor`, using the exact
 * method, URL and body about to be sent. Returns null when the headers carry no
 * general actor binding, and throws (before any Backend I/O) when a bound
 * request cannot be signed. The caller's headers are never mutated.
 */
export async function signBackendActorRequest(
  url: string,
  init: RequestInit
): Promise<RequestInit | null> {
  const original = init.headers;
  if (!(original instanceof Headers)) return null;
  const binding = pendingBackendActorBindings.get(original);
  if (!binding) return null;
  const parsed = new URL(url);
  const target = `${parsed.pathname}${parsed.search}`;
  const body = await signableBackendBody(init.body);
  if (!body) throw new BackendActorAssertionUnavailableError();
  const bodySha256 = body.bytes.byteLength === 0
    ? EMPTY_BODY_SHA256
    : createHash("sha256").update(body.bytes).digest("hex");
  const assertion = createBackendActorAssertionV4({
    ...binding,
    method: (init.method ?? "GET").toUpperCase(),
    target,
    bodySha256
  });
  if (!assertion) throw new BackendActorAssertionUnavailableError();
  const headers = new Headers(original);
  if (body.contentType !== undefined) headers.set("content-type", body.contentType);
  headers.set(ACTOR_ASSERTION_HEADER, assertion);
  return body.replacement === undefined
    ? { ...init, headers }
    : { ...init, headers, body: body.replacement };
}

export type BackendAccountSessionBinding = Readonly<{
  actorId: string;
  accountGeneration: number;
  authEpoch: number;
}>;

export type BackendAccountSessionCurrent = "current" | "denied" | "unavailable";

/** Checks the Backend's current authority using only a stored account binding. */
export async function checkBackendAccountSessionCurrent(
  binding: BackendAccountSessionBinding,
  fetchImpl: GatewayFetch = globalThis.fetch,
  timeoutMs = DEFAULT_PROXY_TIMEOUT_MS,
  requestSignal?: AbortSignal
): Promise<BackendAccountSessionCurrent> {
  const headers = backendServiceRequestHeaders();
  if (!headers || !bindBackendActor(headers, binding)) return "unavailable";
  const url = backendUrl("/accounts/session-current");
  try {
    const signed = await signBackendActorRequest(url, { method: "POST", headers });
    if (!signed) return "unavailable";
    const timeoutSignal = AbortSignal.timeout(Math.max(1, timeoutMs));
    const signal = requestSignal
      ? AbortSignal.any([requestSignal, timeoutSignal])
      : timeoutSignal;
    signal.throwIfAborted();
    const response = await fetchImpl(url, {
      ...signed,
      redirect: "error",
      signal
    });
    signal.throwIfAborted();
    if (response.status === 204) return "current";
    if (response.status === 401) return "denied";
  } catch {
    // A transport failure must not be mistaken for a revoked account.
  }
  return "unavailable";
}

export type RawCollectionOperation =
  | "PUT_MANIFEST"
  | "PUT_CHUNK"
  | "GET_STATUS"
  | "COMMIT";

export type RawCollectionBackendProofInput = {
  actorId: string;
  accountGeneration: number;
  authEpoch?: number | null;
  operation: RawCollectionOperation;
  method: "HEAD" | "GET" | "PUT" | "POST";
  path: string;
  purpose: "GENERAL_RAW" | "AUTO_REPORT";
  walkId: string;
  manifestSha256: string;
  consentReceiptSha256: string | null;
  chunkSha256: string | null;
  commitSha256: string | null;
};

function rawCollectionOperationForPath(path: string): RawCollectionOperation | null {
  const collection = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  if (new RegExp(`^/raw-collections/${collection}/manifest$`).test(path)) {
    return "PUT_MANIFEST";
  }
  if (new RegExp(`^/raw-collections/${collection}/commit$`).test(path)) {
    return "COMMIT";
  }
  if (new RegExp(
    `^/raw-collections/${collection}/objects/${collection}/chunks/(?:0|[1-9][0-9]{0,3})$`
  ).test(path)) {
    return "PUT_CHUNK";
  }
  if (new RegExp(`^/raw-collections/${collection}$`).test(path)) {
    return "GET_STATUS";
  }
  return null;
}

function canonicalRawProofJson(value: Record<string, unknown>): string {
  return `{${Object.keys(value).sort().map((key) =>
    `${JSON.stringify(key)}:${JSON.stringify(value[key])}`
  ).join(",")}}`;
}

export function rawCollectionRequestProofMessage(
  input: RawCollectionBackendProofInput,
  issuedAt: number
): Buffer | null {
  const actualOperation = rawCollectionOperationForPath(input.path);
  const actualMethod = input.operation === "PUT_MANIFEST" || input.operation === "PUT_CHUNK"
    ? "PUT"
    : input.operation === "COMMIT" ? "POST" : "GET";
  const hashesMatch = input.operation === "PUT_MANIFEST"
    ? input.consentReceiptSha256 !== null && input.chunkSha256 === null &&
      input.commitSha256 === null
    : input.operation === "PUT_CHUNK"
      ? input.consentReceiptSha256 !== null && input.chunkSha256 !== null &&
        input.commitSha256 === null
      : input.operation === "COMMIT"
        ? input.consentReceiptSha256 !== null && input.chunkSha256 === null &&
          input.commitSha256 !== null
        : input.consentReceiptSha256 === null && input.chunkSha256 === null &&
          input.commitSha256 === null;
  if (
    !ACTOR_ID_PATTERN.test(input.actorId) ||
    !Number.isSafeInteger(input.accountGeneration) || input.accountGeneration < 1 ||
    actualOperation !== input.operation ||
    (input.method !== "HEAD" && input.method !== actualMethod) ||
    (input.method === "HEAD" && input.operation === "GET_STATUS") ||
    (input.purpose !== "GENERAL_RAW" && input.purpose !== "AUTO_REPORT") ||
    !RAW_UUID.test(input.walkId) ||
    !LOWER_SHA256.test(input.manifestSha256) ||
    (input.consentReceiptSha256 !== null &&
      !LOWER_SHA256.test(input.consentReceiptSha256)) ||
    (input.chunkSha256 !== null && !LOWER_SHA256.test(input.chunkSha256)) ||
    (input.commitSha256 !== null && !LOWER_SHA256.test(input.commitSha256)) ||
    !hashesMatch ||
    !Number.isSafeInteger(issuedAt) || issuedAt <= 0
  ) return null;
  const payload = {
    account_generation: input.accountGeneration,
    actor_id: input.actorId,
    chunk_sha256: input.chunkSha256,
    commit_sha256: input.commitSha256,
    consent_receipt_sha256: input.consentReceiptSha256,
    issued_at: issuedAt,
    manifest_sha256: input.manifestSha256,
    method: input.method,
    operation: input.operation,
    path: input.path,
    purpose: input.purpose,
    version: 1,
    walk_id: input.walkId
  };
  return Buffer.from(
    `walksafe/raw-collection-request-proof/v1\0${canonicalRawProofJson(payload)}`,
    "utf8"
  );
}

export function createRawCollectionRequestProof(
  input: RawCollectionBackendProofInput,
  nowSeconds = Math.floor(Date.now() / 1000)
): string | null {
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  const message = rawCollectionRequestProofMessage(input, nowSeconds);
  if (secret.length < 32 || message === null) return null;
  const signature = createHmac("sha256", secret).update(message).digest("base64url");
  return `v1.${nowSeconds}.${signature}`;
}

export function rawCollectionBackendHeaders(
  input: RawCollectionBackendProofInput,
  initial?: HeadersInit,
  nowSeconds = Math.floor(Date.now() / 1000)
): Headers | null {
  const serviceToken = gatewayTokenForBackend();
  const rawProof = createRawCollectionRequestProof(input, nowSeconds);
  if (serviceToken.length < 24 || !rawProof) return null;
  const headers = new Headers(initial);
  headers.set(FIELD_TEST_TOKEN_HEADER, serviceToken);
  if (!bindBackendActor(headers, {
    actorId: input.actorId,
    accountGeneration: input.accountGeneration,
    authEpoch: input.authEpoch ?? null
  })) return null;
  headers.set(RAW_REQUEST_PROOF_HEADER, rawProof);
  headers.set(RAW_PURPOSE_HEADER, input.purpose);
  headers.set(RAW_WALK_ID_HEADER, input.walkId);
  headers.set(RAW_MANIFEST_SHA256_HEADER, input.manifestSha256);
  if (input.consentReceiptSha256 !== null) {
    headers.set(RAW_CONSENT_RECEIPT_SHA256_HEADER, input.consentReceiptSha256);
  }
  if (input.chunkSha256 !== null) {
    headers.set(RAW_CHUNK_SHA256_HEADER, input.chunkSha256);
  }
  if (input.commitSha256 !== null) {
    headers.set(RAW_COMMIT_SHA256_HEADER, input.commitSha256);
  }
  return headers;
}

function attachBackendActor(
  headers: Headers,
  actorId: string | null,
  accountGeneration: number | null,
  authEpoch: number | null
): void {
  if (!actorId || accountGeneration === null) return;
  bindBackendActor(headers, { actorId, accountGeneration, authEpoch });
}

export type DeletionBackendAssertionInput = {
  actorId: string;
  accountGeneration: number;
  requestId: string;
  tombstoneId: string | null;
  accessPreDigest: string;
  method: "GET" | "POST";
  path: string;
  bodySha256: string;
};

export function createDeletionBackendAssertion(
  input: DeletionBackendAssertionInput,
  nowSeconds = Math.floor(Date.now() / 1000)
): string | null {
  const actorId = input.actorId.trim();
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  if (
    !ACTOR_ID_PATTERN.test(actorId) ||
    !Number.isSafeInteger(input.accountGeneration) ||
    input.accountGeneration < 1 ||
    !/^[A-Za-z0-9_-]{16,128}$/.test(input.requestId) ||
    (input.tombstoneId !== null &&
      !/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(input.tombstoneId)) ||
    !/^[0-9a-f]{64}$/.test(input.accessPreDigest) ||
    !/^[0-9a-f]{64}$/.test(input.bodySha256) ||
    !input.path.startsWith("/privacy/account-deletions") ||
    input.path.includes("?") ||
    secret.length < 32 ||
    !Number.isSafeInteger(nowSeconds) ||
    nowSeconds <= 0
  ) {
    return null;
  }
  const message = JSON.stringify({
    version: 2,
    purpose: "account-deletion",
    actor_id: actorId,
    account_generation: input.accountGeneration,
    request_id: input.requestId,
    tombstone_id: input.tombstoneId,
    access_pre_digest: input.accessPreDigest,
    method: input.method,
    path: input.path,
    body_sha256: input.bodySha256,
    issued_at: nowSeconds
  });
  const signature = createHmac("sha256", secret)
    .update(message, "utf8")
    .digest("base64url");
  return `v2.${nowSeconds}.${signature}`;
}

export function deletionBackendHeaders(
  input: DeletionBackendAssertionInput,
  initial?: HeadersInit
): Headers | null {
  const assertion = createDeletionBackendAssertion(input);
  if (!assertion) return null;
  const headers = new Headers(initial);
  headers.set(FIELD_TEST_TOKEN_HEADER, gatewayTokenForBackend());
  headers.set(ACTOR_ID_HEADER, input.actorId.trim());
  headers.set(ACCOUNT_GENERATION_HEADER, String(input.accountGeneration));
  headers.set(DELETION_ACCESS_PRE_DIGEST_HEADER, input.accessPreDigest);
  if (input.tombstoneId !== null) {
    headers.set(DELETION_TOMBSTONE_HEADER, input.tombstoneId);
  }
  headers.set(ACTOR_ASSERTION_HEADER, assertion);
  return headers;
}

export async function fetchBackend(
  request: Request,
  url: string,
  init: RequestInit = {},
  timeoutMs = DEFAULT_PROXY_TIMEOUT_MS,
  fetchImpl: GatewayFetch = globalThis.fetch,
  maxBufferedBodyBytes = 0
): Promise<Response> {
  const timeoutSignal = AbortSignal.timeout(Math.max(1, timeoutMs));
  const signals: AbortSignal[] = [request.signal, timeoutSignal];
  if (init.signal) signals.push(init.signal);
  const signal = AbortSignal.any(signals);
  let upstream: Response | undefined;
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  let responseTooLarge = false;
  const withinSignal = async <T>(promise: Promise<T>): Promise<T> => {
    signal.throwIfAborted();
    let onAbort: (() => void) | undefined;
    const aborted = new Promise<never>((_resolve, reject) => {
      onAbort = () => reject(signal.reason);
      signal.addEventListener("abort", onAbort, { once: true });
    });
    try {
      return await Promise.race([promise, aborted]);
    } finally {
      if (onAbort) signal.removeEventListener("abort", onAbort);
    }
  };
  try {
    // S19-52: the single signing point for general actor assertions (v4). A bound
    // request that cannot be signed fails closed here, before any Backend I/O.
    const signed = await signBackendActorRequest(url, init);
    signal.throwIfAborted();
    const pending = fetchImpl(url, {
      ...(signed ?? init),
      redirect: "error",
      signal
    });
    if (maxBufferedBodyBytes <= 0) return await pending;
    // A navigation response is complete only when its entire body arrives. The same
    // upstream limit covers fetch headers and every body chunk, including a stalled tail.
    void pending.then((late) => {
      if (signal.aborted && late !== upstream) {
        void late.body?.cancel("upstream request was cancelled").catch(() => undefined);
      }
    }, () => undefined);
    upstream = await withinSignal(pending);
    if (!upstream.body) return upstream;
    const declaredLength = Number(upstream.headers.get("content-length"));
    if (Number.isFinite(declaredLength) && declaredLength > maxBufferedBodyBytes) {
      responseTooLarge = true;
      throw new Error("navigation response is too large");
    }
    reader = upstream.body.getReader();
    const chunks: Buffer[] = [];
    let bodyBytes = 0;
    while (true) {
      const item = await withinSignal(reader.read());
      if (item.done) break;
      bodyBytes += item.value.byteLength;
      if (bodyBytes > maxBufferedBodyBytes) {
        responseTooLarge = true;
        throw new Error("navigation response is too large");
      }
      chunks.push(Buffer.from(item.value));
    }
    reader.releaseLock();
    reader = undefined;
    return new Response(Buffer.concat(chunks), {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: upstream.headers
    });
  } catch {
    if (reader) {
      void reader.cancel("upstream request was cancelled").catch(() => undefined);
      try { reader.releaseLock(); } catch { /* a pending read can retain the lock */ }
    } else if (maxBufferedBodyBytes > 0 && upstream?.body && !upstream.bodyUsed) {
      void upstream.body.cancel("upstream request was cancelled").catch(() => undefined);
    }
    if (responseTooLarge) {
      return Response.json(
        { detail: { code: "gateway_upstream_response_too_large", message: "upstream response exceeded its limit" } },
        { status: 502, headers: { "cache-control": "no-store" } }
      );
    }
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
      { detail: { code: "gateway_upstream_unavailable", message: "upstream request failed" } },
      { status: 502, headers: { "cache-control": "no-store" } }
    );
  }
}

const WALKING_POLYLINE_MAX_POINTS = 8_192;
const WALKING_MAX_STEPS = 1_024;
const WALKING_MAX_GUIDES = 1_024;

function backendResponseRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function walkingCoordinateValid(value: unknown): boolean {
  if (!backendResponseRecord(value)) return false;
  const latitude = value.latitude;
  const longitude = value.longitude;
  return typeof latitude === "number" && Number.isFinite(latitude) &&
    latitude >= -90 && latitude <= 90 &&
    typeof longitude === "number" && Number.isFinite(longitude) &&
    longitude >= -180 && longitude <= 180;
}

function walkingGeometryWithinLimits(payload: Record<string, unknown>): boolean {
  const polyline = payload.polyline;
  if (!Array.isArray(polyline) || polyline.length < 2 ||
      polyline.length > WALKING_POLYLINE_MAX_POINTS ||
      !polyline.every(walkingCoordinateValid)) return false;

  // Older walking responses may omit steps or guides. Validate their geometry when
  // present without duplicating the Backend's full route schema or Android policy.
  const steps = payload.steps;
  if (steps !== undefined) {
    if (!Array.isArray(steps) || steps.length > WALKING_MAX_STEPS) return false;
    let stepPointCount = 0;
    for (const step of steps) {
      if (!backendResponseRecord(step) || !Array.isArray(step.points) ||
          step.points.length === 0) return false;
      stepPointCount += step.points.length;
      if (stepPointCount > WALKING_POLYLINE_MAX_POINTS ||
          !step.points.every(walkingCoordinateValid)) return false;
    }
  }

  const guides = payload.guide_points;
  if (guides !== undefined && guides !== null) {
    if (!Array.isArray(guides) || guides.length > WALKING_MAX_GUIDES ||
        !guides.every((guide: unknown) =>
          backendResponseRecord(guide) && walkingCoordinateValid(guide.point))) return false;
  }
  return true;
}

/** S19-48: bounded navigation bytes are already complete before this structural guard. */
export async function guardWalkingBackendResponse(response: Response): Promise<Response> {
  if (response.status < 200 || response.status >= 300) return response;
  let payload: unknown;
  try {
    payload = await response.clone().json();
  } catch {
    payload = null;
  }
  if (response.status === 200 && backendResponseRecord(payload) &&
      walkingGeometryWithinLimits(payload)) return response;
  void response.body?.cancel("invalid walking route geometry").catch(() => undefined);
  return Response.json(
    { detail: { code: "gateway_upstream_unavailable", message: "upstream response failed" } },
    { status: 502, headers: { "cache-control": "no-store" } }
  );
}

export function authorizeProxyRequest(request: Request): Response | null {
  if (!isGatewayAccessConfigured() && isInsecureLocalGatewayBypassAllowed()) {
    return null;
  }
  if (!isGatewayAccessConfigured()) return gatewayUnavailableResponse();
  if (isFieldSessionAuthorized(request)) return null;
  return gatewayUnauthorizedResponse();
}

export function authorizedProxyActor(request: Request): string | null {
  if (!isFieldSessionAuthorized(request)) return null;
  return gatewaySessionActor(request);
}

export type AuthorizedProxyDeviceBinding = Readonly<{
  actorId: string;
  accountGeneration: number;
  deviceId: string;
}>;

/**
 * Resolves only a current v7 Backend-account device session. The caller must
 * use this value, rather than any inbound identity header, when proxying user
 * rights requests.
 */
export function authorizedProxyDeviceBinding(
  request: Request
): AuthorizedProxyDeviceBinding | null {
  if (!isFieldSessionAuthorized(request)) return null;
  const actorId = gatewaySessionActor(request);
  const accountGeneration = gatewaySessionAccountGeneration(request);
  const binding = gatewayFieldLongSessionBinding(request);
  if (
    !actorId ||
    accountGeneration === null ||
    !binding ||
    binding.actorId !== actorId ||
    binding.accountGeneration !== accountGeneration
  ) return null;
  return { actorId, accountGeneration, deviceId: binding.deviceId };
}

export function proxyRequestHeaders(
  request: Request,
  initial?: HeadersInit,
  accountGeneration: number | null = null
): Headers {
  const headers = new Headers(initial);
  if (isFieldSessionAuthorized(request)) {
    const sessionGeneration = gatewaySessionAccountGeneration(request);
    if (sessionGeneration !== null && accountGeneration !== null &&
        sessionGeneration !== accountGeneration) return headers;
    headers.set(FIELD_TEST_TOKEN_HEADER, gatewayTokenForBackend());
    attachBackendActor(
      headers,
      gatewaySessionActor(request),
      sessionGeneration ?? accountGeneration,
      gatewaySessionAuthEpoch(request)
    );
  }
  return headers;
}

async function isStaleAccountAuthentication(response: Response): Promise<boolean> {
  if (response.status !== 401 || !response.body) return false;
  try {
    const bodyRequest = new Request(BACKEND_API_BASE_URL, {
      method: "POST",
      headers: response.headers,
      body: response.body,
      duplex: "half"
    } as RequestInit);
    const bounded = await readBoundedJsonBody(bodyRequest, 4 * 1024);
    if (bounded.error) return false;
    const value = bounded.value;
    if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
    const detail = (value as Record<string, unknown>).detail;
    return detail !== null && typeof detail === "object" && !Array.isArray(detail) &&
      (detail as Record<string, unknown>).code === "account_authentication_stale";
  } catch {
    return false;
  }
}

export async function toBackendResponse(
  response: Response,
  fallbackContentType = "application/json"
): Promise<Response> {
  if (response.status === 401 || response.status === 403) {
    const staleAccount = await isStaleAccountAuthentication(response);
    void response.body?.cancel("upstream authentication response is not exposed to the Android client")
      .catch(() => undefined);
    if (staleAccount) {
      return Response.json(
        { detail: {
          code: "account_authentication_stale",
          message: "The account session is no longer current."
        } },
        { status: 401, headers: { "cache-control": "no-store" } }
      );
    }
    return Response.json(
      {
        detail: {
          code: "gateway_upstream_auth_failed",
          message: "upstream service authentication failed"
        }
      },
      { status: 502, headers: { "cache-control": "no-store" } }
    );
  }
  const headers = new Headers({ "cache-control": "no-store" });
  const contentType = response.headers.get("content-type") ?? fallbackContentType;
  if (contentType) headers.set("content-type", contentType);

  const contentDisposition = response.headers.get("content-disposition");
  if (contentDisposition) headers.set("content-disposition", contentDisposition);
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
  if (requestId) headers.set("x-request-id", requestId);

  return new Response(response.body, { status: response.status, headers });
}
