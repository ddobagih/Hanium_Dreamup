type BoundedTextResult = { text: string; error?: never } | { text?: never; error: Response };
type BoundedJsonResult = { value: unknown; error?: never } | { value?: never; error: Response };

const DEFAULT_BODY_READ_TIMEOUT_MS = 10_000;

function bodyError(status: number, code: string): Response {
  return Response.json({ code }, { status, headers: { "cache-control": "no-store" } });
}

function hasDuplicateJsonObjectKeys(text: string): boolean {
  const objectKeys: Set<string>[] = [];
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] === "{") {
      objectKeys.push(new Set<string>());
      continue;
    }
    if (text[index] === "}") {
      objectKeys.pop();
      continue;
    }
    if (text[index] !== '"') continue;

    const start = index;
    for (index += 1; index < text.length; index += 1) {
      if (text[index] === "\\") {
        index += 1;
      } else if (text[index] === '"') {
        break;
      }
    }
    let next = index + 1;
    while (
      text[next] === " " || text[next] === "\t" ||
      text[next] === "\r" || text[next] === "\n"
    ) {
      next += 1;
    }
    if (text[next] !== ":") continue;

    const keys = objectKeys[objectKeys.length - 1];
    if (!keys) continue;
    const key = JSON.parse(text.slice(start, index + 1)) as string;
    if (keys.has(key)) return true;
    keys.add(key);
  }
  return false;
}

export function duplicateJsonObjectKeysError(text: string): Response | null {
  return hasDuplicateJsonObjectKeys(text) ? bodyError(400, "invalid_json_body") : null;
}

export async function readBoundedTextBody(
  request: Request,
  maxBytes: number,
  timeoutMs = DEFAULT_BODY_READ_TIMEOUT_MS
): Promise<BoundedTextResult> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^\d+$/.test(declaredLength)) return { error: bodyError(400, "invalid_content_length") };
    if (Number(declaredLength) > maxBytes) return { error: bodyError(413, "request_body_too_large") };
  }
  if (!request.body) return { error: bodyError(400, "request_body_required") };

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
        void reader.cancel("request body exceeded the configured limit").catch(() => undefined);
        return { error: bodyError(413, "request_body_too_large") };
      }
      chunks.push(chunk.value);
    }
  } catch {
    void reader.cancel("request body read was cancelled").catch(() => undefined);
    if (request.signal.aborted) return { error: bodyError(499, "gateway_client_closed") };
    if (timeoutSignal.aborted) return { error: bodyError(408, "request_body_read_timeout") };
    return { error: bodyError(400, "request_body_read_failed") };
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // Cancellation can retain the lock until the underlying socket observes it.
    }
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return { text: new TextDecoder("utf-8", { fatal: true }).decode(bytes) };
  } catch {
    return { error: bodyError(400, "request_body_invalid_utf8") };
  }
}

export async function readBoundedJsonBody(
  request: Request,
  maxBytes: number,
  timeoutMs = DEFAULT_BODY_READ_TIMEOUT_MS
): Promise<BoundedJsonResult> {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (contentType.split(";", 1)[0]?.trim() !== "application/json") {
    return { error: bodyError(415, "json_content_type_required") };
  }
  const bounded = await readBoundedTextBody(request, maxBytes, timeoutMs);
  if (bounded.error) return bounded;
  try {
    const value: unknown = JSON.parse(bounded.text);
    const duplicateError = duplicateJsonObjectKeysError(bounded.text);
    if (duplicateError) return { error: duplicateError };
    return { value };
  } catch {
    return { error: bodyError(400, "invalid_json_body") };
  }
}
