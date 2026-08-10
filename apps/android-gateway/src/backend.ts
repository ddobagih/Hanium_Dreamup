import { createHmac } from "node:crypto";
import { isIP } from "node:net";

import {
  gatewaySessionActor,
  gatewayTokenForBackend,
  gatewayUnavailableResponse,
  gatewayUnauthorizedResponse,
  isGatewayAccessConfigured,
  isFieldSessionAuthorized,
  isInsecureLocalGatewayBypassAllowed
} from "./auth.js";
import { BACKEND_API_BASE_URL } from "./config.js";

export const FIELD_TEST_TOKEN_HEADER = "x-walksafe-field-test-token";
export const ACTOR_ID_HEADER = "x-walksafe-actor-id";
export const ACTOR_ASSERTION_HEADER = "x-walksafe-actor-assertion";
export const ACCOUNT_GENERATION_HEADER = "x-walksafe-account-generation";
export const DELETION_ACCESS_PRE_DIGEST_HEADER =
  "x-walksafe-deletion-access-pre-digest";
export const DELETION_TOMBSTONE_HEADER =
  "x-walksafe-deletion-tombstone-id";
export const IMAGE_MULTIPART_LIMIT_BYTES = 9 * 1024 * 1024;
export const REPORT_METADATA_LIMIT_BYTES = 64 * 1024;
export const REPORT_IMAGE_LIMIT_BYTES = 8 * 1024 * 1024;

const DEFAULT_PROXY_TIMEOUT_MS = 15_000;
const IMAGE_UPLOAD_RATE_WINDOW_MS = 60_000;
const IMAGE_UPLOAD_GLOBAL_RATE_LIMIT = 120;
const IMAGE_UPLOAD_ACTOR_RATE_LIMIT = 12;
const IMAGE_UPLOAD_IP_RATE_LIMIT = 30;
const IMAGE_UPLOAD_MAX_CONCURRENCY = 1;
const ACTOR_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;

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
      totalBytes += chunk.value.byteLength;
      if (totalBytes > maxBytes) {
        void reader.cancel("multipart request exceeded the configured limit").catch(() => undefined);
        return multipartError(413, "multipart_too_large", `multipart request must be <= ${maxBytes} bytes`);
      }
      chunks.push(chunk.value);
    }

    const bytes = new Uint8Array(totalBytes);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    if (countMultipartDelimiters(bytes, boundary) !== 3) {
      return multipartError(422, "multipart_part_count_invalid", "multipart body must contain exactly two parts");
    }
    const formData = await new Response(bytes, { headers: { "content-type": contentType } }).formData();
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

export function resetImageUploadAdmissionForTests(): void {
  imageUploadRateEvents.clear();
  imageUploadsInFlight = 0;
}

export function backendUrl(path: string, request?: Request): string {
  const query = request ? new URL(request.url).search : "";
  return `${BACKEND_API_BASE_URL}${path}${query}`;
}

export function createBackendActorAssertion(
  actorId: string,
  accountGeneration: number,
  nowSeconds = Math.floor(Date.now() / 1000)
): string | null {
  const normalizedActorId = actorId.trim();
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  if (
    !ACTOR_ID_PATTERN.test(normalizedActorId) ||
    secret.length < 32 ||
    !Number.isSafeInteger(accountGeneration) ||
    accountGeneration < 1 ||
    !Number.isSafeInteger(nowSeconds) ||
    nowSeconds <= 0
  ) {
    return null;
  }
  const message =
    `walksafe-backend-actor-v2:field:${normalizedActorId}:` +
    `${accountGeneration}:${nowSeconds}`;
  const signature = createHmac("sha256", secret).update(message).digest("base64url");
  return `v2.${nowSeconds}.${signature}`;
}

function attachBackendActor(
  headers: Headers,
  actorId: string | null,
  accountGeneration: number | null
): void {
  if (!actorId || accountGeneration === null) return;
  const assertion = createBackendActorAssertion(actorId, accountGeneration);
  if (!assertion) return;
  headers.set(ACTOR_ID_HEADER, actorId.trim());
  headers.set(ACCOUNT_GENERATION_HEADER, String(accountGeneration));
  headers.set(ACTOR_ASSERTION_HEADER, assertion);
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
  fetchImpl: GatewayFetch = globalThis.fetch
): Promise<Response> {
  const timeoutSignal = AbortSignal.timeout(Math.max(1, timeoutMs));
  const signals: AbortSignal[] = [request.signal, timeoutSignal];
  if (init.signal) signals.push(init.signal);
  try {
    return await fetchImpl(url, {
      ...init,
      redirect: "error",
      signal: AbortSignal.any(signals)
    });
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
      { detail: { code: "gateway_upstream_unavailable", message: "upstream request failed" } },
      { status: 502, headers: { "cache-control": "no-store" } }
    );
  }
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

export function proxyRequestHeaders(
  request: Request,
  initial?: HeadersInit,
  accountGeneration: number | null = null
): Headers {
  const headers = new Headers(initial);
  if (isFieldSessionAuthorized(request)) {
    headers.set(FIELD_TEST_TOKEN_HEADER, gatewayTokenForBackend());
    attachBackendActor(headers, gatewaySessionActor(request), accountGeneration);
  }
  return headers;
}

export async function toBackendResponse(
  response: Response,
  fallbackContentType = "application/json"
): Promise<Response> {
  if (response.status === 401 || response.status === 403) {
    void response.body?.cancel("upstream authentication response is not exposed to the Android client")
      .catch(() => undefined);
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
