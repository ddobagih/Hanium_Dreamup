import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { Readable } from "node:stream";

import { handleGatewayRequest, type GatewayDependencies } from "./routes.js";

const INTERNAL_HEALTH_PATH = "/internal/health";

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

function requestHeaders(request: IncomingMessage): Headers {
  const headers = new Headers();
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    const name = request.rawHeaders[index];
    const value = request.rawHeaders[index + 1];
    if (name !== undefined && value !== undefined) headers.append(name, value);
  }
  const trustedHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER?.trim().toLowerCase() ?? "";
  if (
    (trustedHeader === "cf-connecting-ip" || trustedHeader === "x-real-ip") &&
    !headers.has(trustedHeader)
  ) {
    const peerAddress = loopbackPeerAddress(request);
    if (peerAddress) headers.set(trustedHeader, peerAddress);
  }
  return headers;
}

export function toWebRequest(
  request: IncomingMessage,
  signal: AbortSignal,
  localPort: number
): Request {
  const rawTarget = request.url ?? "";
  if (!rawTarget.startsWith("/") || rawTarget.startsWith("//")) {
    throw new TypeError("invalid request target");
  }
  const url = new URL(rawTarget, `http://127.0.0.1:${localPort}`);
  const method = request.method?.toUpperCase() || "GET";
  const init: RequestInit & { duplex?: "half" } = {
    method,
    headers: requestHeaders(request),
    signal
  };
  if (method !== "GET" && method !== "HEAD") {
    init.body = Readable.toWeb(request) as ReadableStream<Uint8Array>;
    init.duplex = "half";
  }
  return new Request(url, init);
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

export function createGatewayServer(dependencies: GatewayDependencies = {}): Server {
  const server = createServer((request, response) => {
    const clientAbort = new AbortController();
    request.once("aborted", () => clientAbort.abort(new Error("client request aborted")));
    response.once("close", () => {
      if (!response.writableFinished) clientAbort.abort(new Error("client response closed"));
    });

    void (async () => {
      let gatewayResponse: Response;
      try {
        const localPort = request.socket.localPort ?? 8081;
        const webRequest = toWebRequest(request, clientAbort.signal, localPort);
        gatewayResponse = internalHealthResponse(request, webRequest)
          ?? await handleGatewayRequest(webRequest, dependencies);
      } catch (error) {
        gatewayResponse = error instanceof TypeError
          ? adapterError(400, "gateway_invalid_request")
          : adapterError(500, "gateway_internal_error");
      }
      if (!response.destroyed && !response.writableEnded) {
        try {
          await writeWebResponse(gatewayResponse, response);
        } catch {
          if (!response.destroyed) response.destroy();
        }
      }
    })();
  });
  // Qwen3-TTS has a bounded 65 second upstream deadline. Keep the Node request
  // ceiling slightly above it; individual routes retain their tighter limits.
  server.requestTimeout = 75_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5_000;
  return server;
}
