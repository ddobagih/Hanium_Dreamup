import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { Readable } from "node:stream";

import {
  BACKEND_SESSION_GATE_TIMEOUT_MS,
  backendServiceRequestHeaders,
  backendUrl,
  ORDINARY_ROUTE_OWN_LIMITS_MS,
  REPORT_V2_OWN_LIMITS_MS,
  type GatewayFetch
} from "./backend.js";
import { gatewayTrustedClientIp } from "./auth.js";
import { resolveVoiceServiceConfig } from "./config.js";
import { ACCOUNT_ENROLLMENT_OWN_LIMITS_MS } from "./account-relay.js";
import { RAW_COLLECTION_OWN_LIMITS_MS } from "./raw-collection-relay.js";
import { handleGatewayRequest, type GatewayDependencies } from "./routes.js";
import { speechRelayOwnLimitsMs } from "./speech-relay.js";
import { gatewayTransportBodyForbidden, matchGatewayRoute } from "./route-registry.js";

const INTERNAL_HEALTH_PATH = "/internal/health";
const INTERNAL_READY_PATH = "/internal/ready";
const READINESS_RESPONSE_TIMEOUT_MS = 5_000;
const READINESS_SERVICE_TIMEOUT_MS = 3_000;
const READINESS_SERVICE_BODY_MAX_BYTES = 64 * 1024;
/** Above a route's own body and upstream limits, so the inner cause-bearing timeout answers first. */
const GATEWAY_REQUEST_DEADLINE_MARGIN_MS = 5_000;
/**
 * S19-59: whole-handler deadline of an ordinary request. gw-F6: it adds every limit the
 * request can meet in sequence: the Backend account-session gate (5 s), the bounded JSON body
 * read (10 s), one Backend call (15 s), and the margin. It covers admission waits, local state
 * work, and upstream calls until the Response exists; the response transfer is bounded by the
 * client and each relay.
 */
export const GATEWAY_REQUEST_DEADLINE_MS =
  BACKEND_SESSION_GATE_TIMEOUT_MS + ORDINARY_ROUTE_OWN_LIMITS_MS + GATEWAY_REQUEST_DEADLINE_MARGIN_MS;
/** After the deadline cancels the request, the handler may still return its own result this long. */
export const GATEWAY_REQUEST_DEADLINE_GRACE_MS = 1_000;

type GatewayServerDependencies = GatewayDependencies & {
  readinessProbe?: (signal?: AbortSignal) => Promise<boolean> | boolean;
  /** Tests only: replaces {@link gatewayRequestDeadlineMs}. */
  requestDeadlineMs?: (request: Request) => number;
};

/**
 * S19-59: the whole-handler deadline of one request. Upload and speech routes whose own limits
 * are longer keep them unchanged; their deadline is the Backend account-session gate (gw-F6),
 * those limits, and the margin.
 */
export function gatewayRequestDeadlineMs(request: Request): number {
  const route = matchGatewayRoute(new URL(request.url));
  if (request.method === "POST" && route?.template === "/api/account-enrollments/email-otp") {
    return ACCOUNT_ENROLLMENT_OWN_LIMITS_MS + GATEWAY_REQUEST_DEADLINE_MARGIN_MS;
  }
  if (request.method === "POST" && route?.deadline === "walking") return 8_000;
  if (request.method === "GET" && route?.deadline === "search") return 7_000;
  let ownLimitsMs = 0;
  if (request.method === "POST" && route?.template === "/api/speech/stt") {
    ownLimitsMs = speechRelayOwnLimitsMs("stt");
  } else if (request.method === "POST" && route?.template === "/api/speech/tts") {
    ownLimitsMs = speechRelayOwnLimitsMs("tts");
  } else if (request.method === "POST" && route?.template === "/api/reports/v2") {
    ownLimitsMs = REPORT_V2_OWN_LIMITS_MS;
  } else if (route?.family === "raw") {
    ownLimitsMs = RAW_COLLECTION_OWN_LIMITS_MS;
  }
  return Math.max(
    GATEWAY_REQUEST_DEADLINE_MS,
    BACKEND_SESSION_GATE_TIMEOUT_MS + ownLimitsMs + GATEWAY_REQUEST_DEADLINE_MARGIN_MS
  );
}

function cancelLateResponse(response: Response): void {
  void response.body?.cancel("gateway request deadline exceeded").catch(() => undefined);
}

/**
 * S19-59: runs the handler under its deadline. At the deadline the request signal is aborted,
 * which cancels Backend and Voice fetches, keyed admission waits, and body reads through the
 * same path as a client disconnect. A result the handler still returns within the grace period
 * is kept (it may report a commit); its own cancellation answer (499) or no answer becomes
 * 504 gateway_request_deadline_exceeded, and a late response body is cancelled.
 */
async function handleWithinDeadline(
  webRequest: Request,
  deadlineAbort: AbortController,
  dependencies: GatewayServerDependencies
): Promise<Response> {
  const deadlineMs = (dependencies.requestDeadlineMs ?? gatewayRequestDeadlineMs)(webRequest);
  const handled = handleGatewayRequest(webRequest, dependencies);
  const within = async (milliseconds: number): Promise<Response | null> => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        handled,
        new Promise<null>((resolve) => { timer = setTimeout(() => resolve(null), milliseconds); })
      ]);
    } finally {
      if (timer !== undefined) clearTimeout(timer);
    }
  };
  const onTime = await within(deadlineMs);
  if (onTime !== null) return onTime;
  deadlineAbort.abort(new Error("gateway request deadline exceeded"));
  let settled: Response | null = null;
  try {
    settled = await within(GATEWAY_REQUEST_DEADLINE_GRACE_MS);
  } catch {
    // A failure caused by the cancellation is reported as the deadline, not as a 500.
    settled = null;
  }
  if (settled !== null && settled.status !== 499) return settled;
  if (settled !== null) cancelLateResponse(settled);
  else void handled.then(cancelLateResponse, () => undefined);
  logAdapterFailure("gateway_request_deadline_exceeded");
  return adapterError(504, "gateway_request_deadline_exceeded");
}

class InvalidGatewayRequestError extends Error {}

function loopbackPeerAddress(request: IncomingMessage): string | null {
  const address = request.socket.remoteAddress ?? "";
  if (address === "127.0.0.1" || address === "::1") return address;
  if (address === "::ffff:127.0.0.1") return "127.0.0.1";
  return null;
}

function internalHealthResponse(
  request: IncomingMessage,
  webRequest: Request
): Response | null {
  const url = new URL(webRequest.url);
  if (
    loopbackPeerAddress(request) === null ||
    webRequest.method !== "GET" ||
    url.pathname !== INTERNAL_HEALTH_PATH ||
    url.search !== ""
  ) {
    return null;
  }
  return Response.json(
    { status: "ok" },
    { headers: { "cache-control": "no-store" } }
  );
}

async function internalReadyResponse(
  request: IncomingMessage,
  webRequest: Request,
  dependencies: GatewayServerDependencies
): Promise<Response | null> {
  const url = new URL(webRequest.url);
  if (
    loopbackPeerAddress(request) === null ||
    webRequest.method !== "GET" ||
    url.pathname !== INTERNAL_READY_PATH ||
    url.search !== ""
  ) return null;

  const controller = new AbortController();
  let timeout: ReturnType<typeof setTimeout> | undefined;
  let ready = false;
  try {
    const deadline = new Promise<boolean>((resolve) => {
      timeout = setTimeout(() => {
        controller.abort();
        resolve(false);
      }, READINESS_RESPONSE_TIMEOUT_MS);
    });
    const check = Promise.resolve(dependencies.readinessProbe?.(controller.signal))
      .then((value) => value === true)
      .catch(() => false);
    ready = await Promise.race([check, deadline]) && !controller.signal.aborted;
  } catch {
    ready = false;
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
  return Response.json(
    { status: ready ? "ready" : "not_ready" },
    { status: ready ? 200 : 503, headers: { "cache-control": "no-store" } }
  );
}

async function internalServiceReady(
  url: string,
  headers: HeadersInit,
  fetchImpl: GatewayFetch,
  timeoutMs: number,
  signal?: AbortSignal
): Promise<boolean> {
  if (signal?.aborted) return false;
  const controller = new AbortController();
  let timeout: ReturnType<typeof setTimeout> | undefined;
  let onAbort: (() => void) | undefined;
  const deadline = new Promise<boolean>((resolve) => {
    const abort = (): void => {
      controller.abort();
      resolve(false);
    };
    onAbort = abort;
    timeout = setTimeout(abort, timeoutMs);
    signal?.addEventListener("abort", abort, { once: true });
  });
  const check = (async (): Promise<boolean> => {
    let response: Response | undefined;
    try {
      response = await fetchImpl(url, {
        method: "GET",
        headers,
        cache: "no-store",
        redirect: "error",
        signal: controller.signal
      });
      if (controller.signal.aborted || response.status !== 200 || !response.body) return false;
      const reader = response.body.getReader();
      const chunks: Buffer[] = [];
      let bytes = 0;
      try {
        while (true) {
          const item = await reader.read();
          if (item.done) break;
          bytes += item.value.byteLength;
          if (bytes > READINESS_SERVICE_BODY_MAX_BYTES) return false;
          chunks.push(Buffer.from(item.value));
        }
      } finally {
        if (bytes > READINESS_SERVICE_BODY_MAX_BYTES || controller.signal.aborted) {
          await reader.cancel().catch(() => undefined);
        }
        reader.releaseLock();
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
      return !controller.signal.aborted && typeof payload === "object" && payload !== null &&
        !Array.isArray(payload) && "status" in payload && payload.status === "ready";
    } catch {
      return false;
    } finally {
      if (response?.body && !response.bodyUsed) {
        void response.body.cancel().catch(() => undefined);
      }
    }
  })();
  try {
    return await Promise.race([check, deadline]) && !controller.signal.aborted;
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
    if (onAbort !== undefined) signal?.removeEventListener("abort", onAbort);
  }
}

export function createGatewayReadinessProbe(
  storageReady: () => Promise<boolean>,
  outboxReady: () => boolean,
  fetchImpl: GatewayFetch = globalThis.fetch,
  timeoutMs = READINESS_SERVICE_TIMEOUT_MS
): (signal?: AbortSignal) => Promise<boolean> {
  return async (signal) => {
    try {
      if (signal?.aborted || !outboxReady() || !await storageReady() || signal?.aborted) return false;
      const backendHeaders = backendServiceRequestHeaders();
      if (backendHeaders === null) return false;
      const voice = resolveVoiceServiceConfig();
      const [backendReady, voiceReady] = await Promise.all([
        internalServiceReady(backendUrl("/ready"), backendHeaders, fetchImpl, timeoutMs, signal),
        voice === null
          ? Promise.resolve(true)
          : internalServiceReady(
              `${voice.baseUrl}/ready`,
              { "x-walksafe-voice-service-token": voice.serviceToken },
              fetchImpl,
              timeoutMs,
              signal
            )
      ]);
      return !signal?.aborted && backendReady && voiceReady && outboxReady();
    } catch {
      return false;
    }
  };
}

function requestHeaders(request: IncomingMessage): Headers {
  const headers = new Headers();
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    const name = request.rawHeaders[index];
    const value = request.rawHeaders[index + 1];
    if (name !== undefined && value !== undefined) {
      try {
        headers.append(name, value);
      } catch (error) {
        if (error instanceof TypeError) throw new InvalidGatewayRequestError();
        throw error;
      }
    }
  }
  const trustedHeader = configuredTrustedIpHeader();
  // S19-53: only outside production may a direct loopback peer (adb reverse to a developer
  // Gateway) stand in for the proxy-set client address. In production a missing header is
  // refused (trustedClientIpUnavailable) instead of counting every user as 127.0.0.1.
  if (trustedHeader && !headers.has(trustedHeader) && process.env.NODE_ENV !== "production") {
    const peerAddress = loopbackPeerAddress(request);
    if (peerAddress) headers.set(trustedHeader, peerAddress);
  }
  return headers;
}

function configuredTrustedIpHeader(): "cf-connecting-ip" | "x-real-ip" | null {
  const trustedHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER?.trim().toLowerCase() ?? "";
  return trustedHeader === "cf-connecting-ip" || trustedHeader === "x-real-ip"
    ? trustedHeader
    : null;
}

/**
 * S19-53: in production the reverse proxy must set the configured trusted client IP header
 * on every request to exactly one valid address. Without it, or with a value no consumer can
 * use (empty, not an IP, or duplicate headers merged into "a, b"), the request is refused
 * before any handler runs, so rate limits, login accounting, refresh admission and upload
 * admission never merge users under one address or silently skip a per-client limit.
 * (A missing header setting is a production startup error, config.ts.)
 */
function trustedClientIpUnavailable(webRequest: Request): Response | null {
  if (process.env.NODE_ENV !== "production") return null;
  if (!configuredTrustedIpHeader() || gatewayTrustedClientIp(webRequest) !== null) return null;
  return adapterError(503, "gateway_trusted_client_ip_unavailable");
}

export function toWebRequest(
  request: IncomingMessage,
  signal: AbortSignal,
  localPort: number
): Request {
  const rawTarget = request.url ?? "";
  if (!rawTarget.startsWith("/") || rawTarget.startsWith("//") || rawTarget.includes("\\")) {
    throw new InvalidGatewayRequestError("invalid request target");
  }
  let url: URL;
  try {
    url = new URL(rawTarget, `http://127.0.0.1:${localPort}`);
  } catch (error) {
    if (error instanceof TypeError) throw new InvalidGatewayRequestError();
    throw error;
  }
  const method = request.method?.toUpperCase() || "GET";
  const headers = requestHeaders(request);
  if (
    gatewayTransportBodyForbidden(method) &&
    (headers.has("transfer-encoding") ||
      (headers.has("content-length") && !/^0+$/.test(headers.get("content-length")!)))
  ) {
    throw new InvalidGatewayRequestError("GET and HEAD request bodies are not supported");
  }
  const init: RequestInit & { duplex?: "half" } = {
    method,
    headers,
    signal
  };
  if (!gatewayTransportBodyForbidden(method)) {
    init.body = Readable.toWeb(request) as ReadableStream<Uint8Array>;
    init.duplex = "half";
  }
  try {
    return new Request(url, init);
  } catch (error) {
    if (error instanceof TypeError) throw new InvalidGatewayRequestError();
    throw error;
  }
}

export async function writeWebResponse(response: Response, output: ServerResponse): Promise<void> {
  output.statusCode = response.status;
  output.statusMessage = response.statusText;
  response.headers.forEach((value, name) => output.setHeader(name, value));
  if (!response.body) {
    output.end();
    return;
  }

  const reader = response.body.getReader();
  let completed = false;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      if (output.destroyed || output.writableEnded) return;
      if (!output.write(Buffer.from(chunk.value)) && !await waitForDrainOrClose(output)) return;
    }
    output.end();
    completed = true;
  } finally {
    if (!completed) {
      try {
        await reader.cancel("client response socket closed");
      } catch {
        // The response body may already be errored; the adapter still must release it.
      }
    }
    reader.releaseLock();
  }
}

function waitForDrainOrClose(output: ServerResponse): Promise<boolean> {
  if (output.destroyed || output.writableEnded) return Promise.resolve(false);
  return new Promise<boolean>((resolve, reject) => {
    const cleanup = (): void => {
      output.off("drain", onDrain);
      output.off("close", onClose);
      output.off("error", onError);
    };
    const onDrain = (): void => {
      cleanup();
      resolve(true);
    };
    const onClose = (): void => {
      cleanup();
      resolve(false);
    };
    const onError = (error: Error): void => {
      cleanup();
      reject(error);
    };
    output.once("drain", onDrain);
    output.once("close", onClose);
    output.once("error", onError);
    if (output.destroyed || output.writableEnded) onClose();
  });
}

function adapterError(status: number, code: string): Response {
  return Response.json(
    { code },
    { status, headers: { "cache-control": "no-store" } }
  );
}

function logAdapterFailure(
  code: "gateway_conversion_internal_error" | "gateway_handler_internal_error" |
    "gateway_response_write_internal_error" | "gateway_request_deadline_exceeded"
): void {
  try {
    console.error(code);
  } catch {
    // Diagnostics must not replace the fixed internal-error response.
  }
}

export function createGatewayServer(dependencies: GatewayServerDependencies = {}): Server {
  const server = createServer((request, response) => {
    const clientAbort = new AbortController();
    // S19-59: the handler deadline cancels through the same request signal.
    const deadlineAbort = new AbortController();
    request.once("aborted", () => clientAbort.abort(new Error("client request aborted")));
    response.once("close", () => {
      if (!response.writableFinished) clientAbort.abort(new Error("client response closed"));
    });

    void (async () => {
      let converted: { request: Request; error?: never } | { request?: never; error: Response };
      try {
        const localPort = request.socket.localPort ?? 8081;
        converted = {
          request: toWebRequest(
            request,
            AbortSignal.any([clientAbort.signal, deadlineAbort.signal]),
            localPort
          )
        };
      } catch (error) {
        const invalidRequest = error instanceof InvalidGatewayRequestError;
        if (!invalidRequest) logAdapterFailure("gateway_conversion_internal_error");
        converted = {
          error: invalidRequest
            ? adapterError(400, "gateway_invalid_request")
            : adapterError(500, "gateway_internal_error")
        };
      }

      let gatewayResponse: Response;
      if (converted.error) {
        gatewayResponse = converted.error;
      } else {
        try {
          gatewayResponse = internalHealthResponse(request, converted.request)
            ?? await internalReadyResponse(request, converted.request, dependencies)
            ?? trustedClientIpUnavailable(converted.request)
            ?? await handleWithinDeadline(converted.request, deadlineAbort, dependencies);
        } catch {
          logAdapterFailure("gateway_handler_internal_error");
          gatewayResponse = adapterError(500, "gateway_internal_error");
        }
      }
      if (!response.destroyed && !response.writableEnded) {
        try {
          await writeWebResponse(gatewayResponse, response);
        } catch {
          logAdapterFailure("gateway_response_write_internal_error");
          if (!response.destroyed && !response.writableEnded && !response.headersSent) {
            try {
              for (const name of response.getHeaderNames()) response.removeHeader(name);
              await writeWebResponse(adapterError(500, "gateway_internal_error"), response);
              return;
            } catch {
              // If the fixed response also fails, only closing the socket is safe.
            }
          }
          if (!response.destroyed) response.destroy();
        }
      }
    })();
  });
  // requestTimeout bounds only receiving the client request (headers + body);
  // it is not a handler, queue, or response-write deadline. Upstream calls such
  // as the 65 second Qwen3-TTS relay and each body reader keep their own limits;
  // S19-59: the handler itself runs under gatewayRequestDeadlineMs (handleWithinDeadline).
  server.requestTimeout = 75_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5_000;
  return server;
}
