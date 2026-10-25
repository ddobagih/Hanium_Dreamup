import { DEFAULT_PROXY_TIMEOUT_MS, type GatewayFetch } from "./backend-contract.js";
import { signBackendActorRequest } from "./backend-request-signing.js";

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

