import { DEFAULT_PROXY_TIMEOUT_MS } from "./backend-contract.js";

export const IMAGE_MULTIPART_LIMIT_BYTES = 9 * 1024 * 1024;

export const REPORT_METADATA_LIMIT_BYTES = 64 * 1024;

export const REPORT_IMAGE_LIMIT_BYTES = 8 * 1024 * 1024;

type BoundedFormDataResult =
  | { formData: FormData; error?: never }
  | { formData?: never; error: Response };

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

