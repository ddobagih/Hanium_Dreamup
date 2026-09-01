import { createHash } from "node:crypto";

import {
  gatewayFieldLongSessionBinding,
  type GatewayFieldLongSessionBinding
} from "./auth.js";
import {
  backendUrl,
  fetchBackend,
  rawCollectionBackendHeaders,
  RAW_CHUNK_SHA256_HEADER,
  RAW_COMMIT_SHA256_HEADER,
  RAW_CONSENT_RECEIPT_SHA256_HEADER,
  RAW_MANIFEST_SHA256_HEADER,
  RAW_PURPOSE_HEADER,
  RAW_WALK_ID_HEADER,
  type GatewayFetch,
  type RawCollectionBackendProofInput,
  type RawCollectionOperation
} from "./backend.js";
import { getFieldWalk } from "./field-walk-ledger.js";
import {
  authorizeIntegratedConsentRequest,
  CONSENT_NETWORK_TRANSPORT_HEADER
} from "./integrated-consent.js";
import { isAccountGenerationFencedV2 } from "./privacy-deletion-v2.js";
import {
  beginPrivacyOperation,
  consentActorBindingId,
  finishPrivacyOperation,
  revalidatePrivacyOperation,
  type PrivacyOperationLease
} from "./privacy-rights.js";

export const RAW_MANIFEST_MAX_BYTES = 512 * 1024;
export const RAW_CHUNK_MAX_BYTES = 8 * 1024 * 1024;
export const RAW_COMMIT_MAX_BYTES = 16 * 1024;

const RAW_RESPONSE_MAX_BYTES = 512 * 1024;
const RAW_ERROR_MAX_BYTES = 16 * 1024;
const RAW_BODY_TIMEOUT_MS = 15_000;
const RAW_MAX_OBJECTS = 64;
const RAW_MAX_CHUNKS = 2_048;
const RAW_MAX_TOTAL_BYTES = RAW_MAX_CHUNKS * RAW_CHUNK_MAX_BYTES;
const RAW_RECEIPT_RETENTION_DAYS_V1 = 180;
const RAW_RECEIPT_QUARANTINE_DAYS_V2 = 14;
const RAW_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const LOWER_SHA256 = /^[0-9a-f]{64}$/;
const CANONICAL_INDEX = /^(?:0|[1-9][0-9]{0,3})$/;
const CANONICAL_LENGTH = /^[1-9][0-9]{0,7}$/;
const UTC_SECONDS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const RAW_CONTENT_TYPE =
  /^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$/;
const RAW_KINDS = new Set([
  "VIDEO", "AUDIO", "EXACT_LOCATION", "SENSOR", "ROUTE", "DETECTION",
  "REPORT", "PERFORMANCE"
]);
const RAW_STATES = new Set([
  "MANIFEST_ACCEPTED", "RECEIVING", "READY_TO_COMMIT", "COMMITTED", "QUARANTINED"
]);
const RAW_UPSTREAM_ERROR_CODES = new Set([
  "account_generation_inactive",
  "account_generation_tombstoned",
  "raw_capacity_hold",
  "raw_capacity_reservation_unavailable",
  "raw_capacity_state_unavailable",
  "raw_chunk_commit_state",
  "raw_chunk_encryption_unavailable",
  "raw_chunk_manifest_mismatch",
  "raw_chunk_persistence_ambiguous",
  "raw_chunk_sha256_mismatch",
  "raw_chunk_storage_unavailable",
  "raw_collection_incomplete",
  "raw_collection_inventory_ambiguous",
  "raw_collection_manifest_conflict",
  "raw_collection_not_found",
  "raw_collection_state_ambiguous",
  "raw_commit_binding_mismatch",
  "raw_commit_inventory_mismatch",
  "raw_commit_persistence_ambiguous",
  "raw_consent_receipt_not_found",
  "raw_ingest_admission_unavailable",
  "raw_ingest_disabled",
  "raw_ingest_storage_unavailable",
  "raw_manifest_binding_mismatch",
  "raw_manifest_shape_not_implemented",
  "raw_manifest_single_chunk_inventory_invalid",
  "raw_manifest_storage_unavailable",
  "raw_receipt_persistence_ambiguous",
  "raw_source_collection_consent_interrupted",
  "raw_source_collection_consent_required"
]);

export type RawCollectionRoute = {
  operation: RawCollectionOperation;
  method: "GET" | "PUT" | "POST";
  publicPath: string;
  backendPath: string;
  collectionId: string;
  objectId: string | null;
  chunkIndex: number | null;
};

export type RawCollectionRelayDependencies = {
  fetchImpl?: GatewayFetch;
  bindingResolver?: (request: Request) => GatewayFieldLongSessionBinding | null;
};

type RawHeaderBinding = {
  purpose: "GENERAL_RAW" | "AUTO_REPORT";
  walkId: string;
  manifestSha256: string;
  consentReceiptSha256: string | null;
  chunkSha256: string | null;
  commitSha256: string | null;
  networkTransport: "wifi" | "cellular" | null;
};

type ExactBodyResult =
  | { bytes: Uint8Array; sha256: string; error?: never }
  | { bytes?: never; sha256?: never; error: Response };

let rawWriteInFlight = false;

function jsonError(status: number, code: string, message: string, headers?: HeadersInit): Response {
  return Response.json(
    { detail: { code, message } },
    { status, headers: { "cache-control": "no-store", ...Object.fromEntries(new Headers(headers)) } }
  );
}

function notFound(): Response {
  return jsonError(404, "gateway_route_not_found", "The raw collection route was not found.");
}

function methodNotAllowed(method: string): Response {
  return jsonError(
    405,
    "gateway_method_not_allowed",
    "The raw collection method is not allowed.",
    { allow: method }
  );
}

export function matchRawCollectionRoute(pathname: string): RawCollectionRoute | null {
  const collection = pathname.match(
    /^\/api\/raw-collections\/([0-9a-f-]{36})(?:\/(.*))?$/
  );
  if (!collection || !RAW_UUID.test(collection[1] ?? "")) return null;
  const collectionId = collection[1]!;
  const suffix = collection[2];
  if (suffix === undefined) {
    return {
      operation: "GET_STATUS",
      method: "GET",
      publicPath: pathname,
      backendPath: `/raw-collections/${collectionId}`,
      collectionId,
      objectId: null,
      chunkIndex: null
    };
  }
  if (suffix === "manifest" || suffix === "commit") {
    const operation = suffix === "manifest" ? "PUT_MANIFEST" : "COMMIT";
    return {
      operation,
      method: suffix === "manifest" ? "PUT" : "POST",
      publicPath: pathname,
      backendPath: `/raw-collections/${collectionId}/${suffix}`,
      collectionId,
      objectId: null,
      chunkIndex: null
    };
  }
  const chunk = suffix.match(
    /^objects\/([0-9a-f-]{36})\/chunks\/([^/]+)$/
  );
  if (
    !chunk || !RAW_UUID.test(chunk[1] ?? "") ||
    !CANONICAL_INDEX.test(chunk[2] ?? "") || Number(chunk[2]) >= 2_048
  ) return null;
  return {
    operation: "PUT_CHUNK",
    method: "PUT",
    publicPath: pathname,
    backendPath: `/raw-collections/${collectionId}/objects/${chunk[1]}/chunks/${chunk[2]}`,
    collectionId,
    objectId: chunk[1]!,
    chunkIndex: Number(chunk[2])
  };
}

function exactHeader(request: Request, name: string): string | null {
  const value = request.headers.get(name);
  if (value === null || value.length === 0 || value !== value.trim() || value.includes(",")) {
    return null;
  }
  return value;
}

function rawHeaders(request: Request, route: RawCollectionRoute): RawHeaderBinding | null {
  const purpose = exactHeader(request, RAW_PURPOSE_HEADER);
  const walkId = exactHeader(request, RAW_WALK_ID_HEADER);
  const manifestSha256 = exactHeader(request, RAW_MANIFEST_SHA256_HEADER);
  const write = route.operation !== "GET_STATUS";
  const consentReceiptSha256 = write
    ? exactHeader(request, RAW_CONSENT_RECEIPT_SHA256_HEADER)
    : null;
  const chunkSha256 = route.operation === "PUT_CHUNK"
    ? exactHeader(request, RAW_CHUNK_SHA256_HEADER)
    : null;
  const commitSha256 = route.operation === "COMMIT"
    ? exactHeader(request, RAW_COMMIT_SHA256_HEADER)
    : null;
  const networkTransport = write
    ? exactHeader(request, CONSENT_NETWORK_TRANSPORT_HEADER)
    : null;
  if (
    (purpose !== "GENERAL_RAW" && purpose !== "AUTO_REPORT") ||
    !walkId || !RAW_UUID.test(walkId) ||
    !manifestSha256 || !LOWER_SHA256.test(manifestSha256) ||
    (write && (!consentReceiptSha256 || !LOWER_SHA256.test(consentReceiptSha256))) ||
    (route.operation === "PUT_CHUNK" &&
      (!chunkSha256 || !LOWER_SHA256.test(chunkSha256))) ||
    (route.operation === "COMMIT" &&
      (!commitSha256 || !LOWER_SHA256.test(commitSha256))) ||
    (write && networkTransport !== "wifi" && networkTransport !== "cellular") ||
    (write && purpose === "GENERAL_RAW" && networkTransport !== "wifi") ||
    (route.operation !== "PUT_CHUNK" && request.headers.has(RAW_CHUNK_SHA256_HEADER)) ||
    (route.operation !== "COMMIT" && request.headers.has(RAW_COMMIT_SHA256_HEADER)) ||
    (!write && (
      request.headers.has(RAW_CONSENT_RECEIPT_SHA256_HEADER) ||
      request.headers.has(CONSENT_NETWORK_TRANSPORT_HEADER)
    ))
  ) return null;
  return {
    purpose,
    walkId,
    manifestSha256,
    consentReceiptSha256,
    chunkSha256,
    commitSha256,
    networkTransport: networkTransport as "wifi" | "cellular" | null
  };
}

function privacyCurrent(lease: PrivacyOperationLease): boolean {
  try {
    return revalidatePrivacyOperation(lease) &&
      !isAccountGenerationFencedV2(lease.actorId, lease.accountGeneration);
  } catch {
    return false;
  }
}

function bindingMatches(
  request: Request,
  expected: GatewayFieldLongSessionBinding,
  resolver: (request: Request) => GatewayFieldLongSessionBinding | null
): boolean {
  const current = resolver(request);
  return current !== null && current.actorId === expected.actorId &&
    current.accountId === expected.accountId && current.deviceId === expected.deviceId &&
    current.familyId === expected.familyId &&
    current.sessionRotation === expected.sessionRotation &&
    (current.accountGeneration ?? null) === (expected.accountGeneration ?? null);
}

function activeWalkCurrent(
  binding: GatewayFieldLongSessionBinding,
  walkId: string
): boolean {
  try {
    const result = getFieldWalk(binding);
    return result.status === 200 &&
      result.body.result === "ACTIVE" &&
      result.body.active_walk_id === walkId &&
      result.body.active_device_id === binding.deviceId &&
      result.body.held_by_current_device === true;
  } catch {
    return false;
  }
}

async function writeGate(
  request: Request,
  binding: GatewayFieldLongSessionBinding,
  lease: PrivacyOperationLease,
  headers: RawHeaderBinding,
  resolver: (request: Request) => GatewayFieldLongSessionBinding | null
): Promise<Response | null> {
  if (
    !bindingMatches(request, binding, resolver) ||
    !privacyCurrent(lease) ||
    !activeWalkCurrent(binding, headers.walkId)
  ) {
    return jsonError(
      409,
      "raw_collection_binding_stale",
      "The device, walk, or account generation is no longer current."
    );
  }
  const required: Array<
    "raw_source_collection" | "automatic_reporting" | "mobile_network_transfer"
  > = ["raw_source_collection"];
  if (headers.purpose === "AUTO_REPORT") required.push("automatic_reporting");
  if (headers.networkTransport === "cellular") required.push("mobile_network_transfer");
  const consent = await authorizeIntegratedConsentRequest(
    request,
    required,
    consentActorBindingId(lease)
  );
  if (
    !bindingMatches(request, binding, resolver) ||
    !privacyCurrent(lease) ||
    !activeWalkCurrent(binding, headers.walkId)
  ) {
    return jsonError(
      409,
      "raw_collection_binding_stale",
      "The device, walk, or account generation changed during consent validation."
    );
  }
  return consent.error ? consent.error : null;
}

function contentLength(request: Request, maxBytes: number): number | null {
  const value = exactHeader(request, "content-length");
  if (!value || !CANONICAL_LENGTH.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 1 && parsed <= maxBytes ? parsed : null;
}

async function readExactBody(
  request: Request,
  expectedBytes: number,
  expectedSha256: string | null
): Promise<ExactBodyResult> {
  if (!request.body) {
    return { error: jsonError(400, "raw_body_required", "A raw collection body is required.") };
  }
  const body = Buffer.alloc(expectedBytes);
  const hash = createHash("sha256");
  const timeout = AbortSignal.timeout(RAW_BODY_TIMEOUT_MS);
  const signal = AbortSignal.any([request.signal, timeout]);
  const reader = request.body.getReader();
  let offset = 0;
  try {
    while (true) {
      if (signal.aborted) throw signal.reason;
      let rejectOnAbort: (reason?: unknown) => void = () => undefined;
      const aborted = new Promise<never>((_resolve, reject) => {
        rejectOnAbort = reject;
      });
      const onAbort = () => rejectOnAbort(signal.reason);
      signal.addEventListener("abort", onAbort, { once: true });
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await Promise.race([reader.read(), aborted]);
      } finally {
        signal.removeEventListener("abort", onAbort);
      }
      if (chunk.done) break;
      if (offset + chunk.value.byteLength > expectedBytes) {
        await reader.cancel("raw body exceeded Content-Length");
        return {
          error: jsonError(400, "raw_body_length_mismatch", "Content-Length does not match the body.")
        };
      }
      body.set(chunk.value, offset);
      hash.update(chunk.value);
      offset += chunk.value.byteLength;
    }
  } catch {
    const requestAborted = request.signal.aborted;
    const timeoutAborted = timeout.aborted;
    void reader.cancel("raw body read cancelled").catch(() => undefined);
    if (requestAborted) {
      return { error: jsonError(499, "gateway_client_closed", "The client request was cancelled.") };
    }
    if (timeoutAborted) {
      return { error: jsonError(408, "raw_body_read_timeout", "The raw body read timed out.") };
    }
    return { error: jsonError(400, "raw_body_read_failed", "The raw body could not be read.") };
  } finally {
    try { reader.releaseLock(); } catch { /* cancelled streams can retain the lock */ }
  }
  if (offset !== expectedBytes) {
    return {
      error: jsonError(400, "raw_body_length_mismatch", "Content-Length does not match the body.")
    };
  }
  const observed = hash.digest("hex");
  if (expectedSha256 !== null && observed !== expectedSha256) {
    return {
      error: jsonError(400, "raw_chunk_sha256_mismatch", "The chunk digest does not match the body.")
    };
  }
  return { bytes: body, sha256: observed };
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length &&
    actual.every((key, index) => key === expected[index]);
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new TypeError("raw JSON numbers must be integers");
    return String(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const object = objectValue(value);
  if (!object) throw new TypeError("unsupported raw JSON value");
  return `{${Object.keys(object).sort().map((key) =>
    `${JSON.stringify(key)}:${canonicalJson(object[key])}`
  ).join(",")}}`;
}

function canonicalJsonBody(bytes: Uint8Array): Record<string, unknown> | null {
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    const parsed = objectValue(JSON.parse(text) as unknown);
    return parsed && canonicalJson(parsed) === text ? parsed : null;
  } catch {
    return null;
  }
}

function jsonBody(bytes: Uint8Array): Record<string, unknown> | null {
  try {
    return objectValue(JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(bytes)
    ) as unknown);
  } catch {
    return null;
  }
}

function integerInRange(value: unknown, minimum: number, maximum: number): value is number {
  return Number.isSafeInteger(value) && Number(value) >= minimum && Number(value) <= maximum;
}

function canonicalUtcSecond(value: unknown): value is string {
  if (typeof value !== "string" || !UTC_SECONDS.test(value)) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) &&
    new Date(parsed).toISOString().replace(".000Z", "Z") === value;
}

function domainDigest(
  domain:
    | "walksafe/raw-collection-manifest/v1\0"
    | "walksafe/raw-collection-commit/v1\0"
    | "walksafe/raw-collection-receipt/v1\0"
    | "walksafe/raw-collection-receipt/v2\0",
  payload: Record<string, unknown>,
  digestField: string
): string {
  const unsigned = { ...payload };
  delete unsigned[digestField];
  return createHash("sha256").update(domain, "utf8").update(canonicalJson(unsigned), "utf8")
    .digest("hex");
}

function validManifestInventory(payload: Record<string, unknown>): boolean {
  if (
    typeof payload.segment_id !== "string" || !RAW_UUID.test(payload.segment_id) ||
    !canonicalUtcSecond(payload.captured_started_at) ||
    !canonicalUtcSecond(payload.captured_ended_at) ||
    Date.parse(payload.captured_ended_at) < Date.parse(payload.captured_started_at) ||
    !integerInRange(payload.object_count, 1, RAW_MAX_OBJECTS) ||
    !integerInRange(payload.chunk_count, 1, RAW_MAX_CHUNKS) ||
    !integerInRange(payload.total_bytes, 1, RAW_MAX_TOTAL_BYTES) ||
    !Array.isArray(payload.objects) || payload.objects.length !== payload.object_count
  ) return false;
  let observedChunks = 0;
  let observedBytes = 0;
  let previousObjectId = "";
  for (const item of payload.objects) {
    const object = objectValue(item);
    if (
      !object || !exactKeys(object, [
        "object_id", "kind", "content_type", "size_bytes", "sha256", "chunks"
      ]) ||
      typeof object.object_id !== "string" || !RAW_UUID.test(object.object_id) ||
      object.object_id <= previousObjectId ||
      typeof object.kind !== "string" || !RAW_KINDS.has(object.kind) ||
      typeof object.content_type !== "string" || !RAW_CONTENT_TYPE.test(object.content_type) ||
      !integerInRange(object.size_bytes, 1, RAW_MAX_TOTAL_BYTES) ||
      typeof object.sha256 !== "string" || !LOWER_SHA256.test(object.sha256) ||
      !Array.isArray(object.chunks) || object.chunks.length < 1 ||
      object.chunks.length > RAW_MAX_CHUNKS
    ) return false;
    previousObjectId = object.object_id;
    let objectBytes = 0;
    for (let index = 0; index < object.chunks.length; index += 1) {
      const chunk = objectValue(object.chunks[index]);
      if (
        !chunk || !exactKeys(chunk, ["index", "size_bytes", "sha256"]) ||
        chunk.index !== index ||
        !integerInRange(chunk.size_bytes, 1, RAW_CHUNK_MAX_BYTES) ||
        typeof chunk.sha256 !== "string" || !LOWER_SHA256.test(chunk.sha256)
      ) return false;
      objectBytes += chunk.size_bytes;
    }
    if (objectBytes !== object.size_bytes) return false;
    observedChunks += object.chunks.length;
    observedBytes += object.size_bytes;
  }
  return observedChunks === payload.chunk_count && observedBytes === payload.total_bytes;
}

function validCommitInventory(payload: Record<string, unknown>): boolean {
  return integerInRange(payload.object_count, 1, RAW_MAX_OBJECTS) &&
    integerInRange(payload.chunk_count, 1, RAW_MAX_CHUNKS) &&
    integerInRange(payload.total_bytes, 1, RAW_MAX_TOTAL_BYTES);
}

function validateJsonBinding(
  route: RawCollectionRoute,
  headers: RawHeaderBinding,
  bytes: Uint8Array
): Response | null {
  const payload = canonicalJsonBody(bytes);
  if (!payload) {
    return jsonError(400, "raw_json_invalid", "The JSON body must use canonical UTF-8 JSON.");
  }
  if (route.operation === "PUT_MANIFEST") {
    if (
      !exactKeys(payload, [
        "schema_version", "collection_id", "walk_id", "segment_id", "purpose",
        "captured_started_at", "captured_ended_at", "consent_receipt_sha256",
        "object_count", "chunk_count", "total_bytes", "objects", "manifest_sha256"
      ]) ||
      payload.schema_version !== "walksafe.raw-collection-manifest.v1" ||
      payload.collection_id !== route.collectionId ||
      payload.walk_id !== headers.walkId ||
      payload.purpose !== headers.purpose ||
      payload.consent_receipt_sha256 !== headers.consentReceiptSha256 ||
      payload.manifest_sha256 !== headers.manifestSha256 ||
      !validManifestInventory(payload) ||
      domainDigest(
        "walksafe/raw-collection-manifest/v1\0",
        payload,
        "manifest_sha256"
      ) !== headers.manifestSha256
    ) {
      return jsonError(409, "raw_manifest_binding_mismatch", "The manifest binding is invalid.");
    }
    return null;
  }
  if (
    !exactKeys(payload, [
      "schema_version", "collection_id", "manifest_sha256", "object_count",
      "chunk_count", "total_bytes"
    ]) ||
    payload.schema_version !== "walksafe.raw-collection-commit.v1" ||
    payload.collection_id !== route.collectionId ||
    payload.manifest_sha256 !== headers.manifestSha256 ||
    !validCommitInventory(payload) ||
    domainDigest(
      "walksafe/raw-collection-commit/v1\0",
      payload,
      "commit_sha256"
    ) !== headers.commitSha256
  ) {
    return jsonError(409, "raw_commit_binding_mismatch", "The commit binding is invalid.");
  }
  return null;
}

function proofInput(
  binding: GatewayFieldLongSessionBinding,
  route: RawCollectionRoute,
  headers: RawHeaderBinding,
  method: "HEAD" | "GET" | "PUT" | "POST"
): RawCollectionBackendProofInput {
  return {
    actorId: binding.actorId,
    accountGeneration: binding.accountGeneration!,
    operation: route.operation,
    method,
    path: route.backendPath,
    purpose: headers.purpose,
    walkId: headers.walkId,
    manifestSha256: headers.manifestSha256,
    consentReceiptSha256: headers.consentReceiptSha256,
    chunkSha256: headers.chunkSha256,
    commitSha256: headers.commitSha256
  };
}

async function boundedResponseBytes(response: Response, maxBytes: number): Promise<Uint8Array | null> {
  const declared = response.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > maxBytes)) {
    void response.body?.cancel("raw upstream response too large").catch(() => undefined);
    return null;
  }
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      total += chunk.value.byteLength;
      if (total > maxBytes) {
        await reader.cancel("raw upstream response too large");
        return null;
      }
      chunks.push(chunk.value);
    }
  } catch {
    void reader.cancel("raw upstream response read failed").catch(() => undefined);
    return null;
  } finally {
    try { reader.releaseLock(); } catch { /* cancelled streams can retain the lock */ }
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

function validReceipt(value: unknown, route: RawCollectionRoute, headers: RawHeaderBinding): boolean {
  const receipt = objectValue(value);
  if (!receipt) return false;
  const isV1 = receipt.schema_version === "walksafe.raw-collection-receipt.v1";
  const isV2 = receipt.schema_version === "walksafe.raw-collection-receipt.v2";
  if ((!isV1 && !isV2) || !exactKeys(receipt, [
    "schema_version", "collection_id", "manifest_sha256", "purpose",
    "persistence_marker", "object_count", "chunk_count", "total_bytes", "objects",
    "retention_class", "committed_at",
    isV1 ? "retention_expires_at" : "quarantine_expires_at", "receipt_sha256"
  ])) return false;
  const expiresAt = isV1 ? receipt.retention_expires_at : receipt.quarantine_expires_at;
  const expectedDays = isV1
    ? RAW_RECEIPT_RETENTION_DAYS_V1
    : RAW_RECEIPT_QUARANTINE_DAYS_V2;
  if (
    receipt.collection_id !== route.collectionId ||
    receipt.manifest_sha256 !== headers.manifestSha256 ||
    receipt.purpose !== headers.purpose ||
    receipt.persistence_marker !== "DATABASE_AND_ENCRYPTED_CHUNK_STORE" ||
    !integerInRange(receipt.object_count, 1, RAW_MAX_OBJECTS) ||
    !integerInRange(receipt.chunk_count, 1, RAW_MAX_CHUNKS) ||
    !integerInRange(receipt.total_bytes, 1, RAW_MAX_TOTAL_BYTES) ||
    receipt.retention_class !== (isV1 ? "RAW_ORIGINAL_180D" : "RAW_QUARANTINE_14D") ||
    !canonicalUtcSecond(receipt.committed_at) ||
    !canonicalUtcSecond(expiresAt) ||
    Date.parse(expiresAt) - Date.parse(receipt.committed_at) !==
      expectedDays * 24 * 60 * 60 * 1_000 ||
    typeof receipt.receipt_sha256 !== "string" || !LOWER_SHA256.test(receipt.receipt_sha256) ||
    !Array.isArray(receipt.objects) || receipt.objects.length !== receipt.object_count ||
    domainDigest(
      isV1
        ? "walksafe/raw-collection-receipt/v1\0"
        : "walksafe/raw-collection-receipt/v2\0",
      receipt,
      "receipt_sha256"
    ) !== receipt.receipt_sha256
  ) return false;
  let previousObjectId = "";
  let observedChunks = 0;
  let observedBytes = 0;
  for (const item of receipt.objects) {
    const object = objectValue(item);
    if (!(object !== null && exactKeys(object, [
      "object_id", "kind", "size_bytes", "sha256", "chunk_count"
    ]) && typeof object.object_id === "string" && RAW_UUID.test(object.object_id) &&
      object.object_id > previousObjectId &&
      typeof object.kind === "string" && RAW_KINDS.has(object.kind) &&
      integerInRange(object.size_bytes, 1, RAW_MAX_TOTAL_BYTES) &&
      typeof object.sha256 === "string" && LOWER_SHA256.test(object.sha256) &&
      integerInRange(object.chunk_count, 1, RAW_MAX_CHUNKS))) return false;
    previousObjectId = object.object_id;
    observedChunks += object.chunk_count;
    observedBytes += object.size_bytes;
  }
  return observedChunks === receipt.chunk_count && observedBytes === receipt.total_bytes;
}

function validChunkAck(
  value: unknown,
  route: RawCollectionRoute,
  headers: RawHeaderBinding
): boolean {
  const ack = objectValue(value);
  return ack !== null && exactKeys(ack, [
    "schema_version", "collection_id", "object_id", "index", "size_bytes",
    "sha256", "state", "stored_at"
  ]) && ack.schema_version === "walksafe.raw-collection-chunk-ack.v1" &&
    ack.collection_id === route.collectionId && ack.object_id === route.objectId &&
    ack.index === route.chunkIndex && integerInRange(ack.size_bytes, 1, RAW_CHUNK_MAX_BYTES) &&
    ack.sha256 === headers.chunkSha256 &&
    typeof ack.state === "string" && RAW_STATES.has(ack.state) &&
    canonicalUtcSecond(ack.stored_at);
}

function validStatus(value: unknown, route: RawCollectionRoute, headers: RawHeaderBinding): boolean {
  const status = objectValue(value);
  if (!status || !exactKeys(status, [
    "schema_version", "collection_id", "manifest_sha256", "purpose", "state",
    "object_count", "chunk_count", "total_bytes", "received_chunk_count",
    "received_bytes", "objects", "receipt"
  ])) return false;
  if (
    status.schema_version !== "walksafe.raw-collection-status.v1" ||
    status.collection_id !== route.collectionId ||
    status.manifest_sha256 !== headers.manifestSha256 || status.purpose !== headers.purpose ||
    typeof status.state !== "string" || !RAW_STATES.has(status.state) ||
    !integerInRange(status.object_count, 1, RAW_MAX_OBJECTS) ||
    !integerInRange(status.chunk_count, 1, RAW_MAX_CHUNKS) ||
    !integerInRange(status.total_bytes, 1, RAW_MAX_TOTAL_BYTES) ||
    !integerInRange(status.received_chunk_count, 0, RAW_MAX_CHUNKS) ||
    !integerInRange(status.received_bytes, 0, RAW_MAX_TOTAL_BYTES) ||
    status.received_chunk_count > status.chunk_count ||
    status.received_bytes > status.total_bytes ||
    !Array.isArray(status.objects) || status.objects.length !== status.object_count
  ) return false;
  let previousObjectId = "";
  let observedChunks = 0;
  let observedBytes = 0;
  let observedReceivedChunks = 0;
  let observedReceivedBytes = 0;
  for (const item of status.objects) {
    const object = objectValue(item);
    if (!object || !exactKeys(object, [
      "object_id", "kind", "sha256", "chunk_count", "received_chunk_count",
      "size_bytes", "received_bytes", "missing_ranges"
    ]) || typeof object.object_id !== "string" || !RAW_UUID.test(object.object_id) ||
      object.object_id <= previousObjectId ||
      typeof object.kind !== "string" || !RAW_KINDS.has(object.kind) ||
      typeof object.sha256 !== "string" || !LOWER_SHA256.test(object.sha256) ||
      !integerInRange(object.chunk_count, 1, RAW_MAX_CHUNKS) ||
      !integerInRange(object.received_chunk_count, 0, RAW_MAX_CHUNKS) ||
      object.received_chunk_count > object.chunk_count ||
      !integerInRange(object.size_bytes, 1, RAW_MAX_TOTAL_BYTES) ||
      !integerInRange(object.received_bytes, 0, RAW_MAX_TOTAL_BYTES) ||
      object.received_bytes > object.size_bytes ||
      !Array.isArray(object.missing_ranges)) return false;
    previousObjectId = object.object_id;
    let previousEnd = -2;
    let missingCount = 0;
    for (const range of object.missing_ranges) {
      const item = objectValue(range);
      if (!(item !== null && exactKeys(item, ["start", "end"]) &&
        integerInRange(item.start, 0, RAW_MAX_CHUNKS - 1) &&
        integerInRange(item.end, item.start, RAW_MAX_CHUNKS - 1) &&
        item.end < object.chunk_count && item.start > previousEnd + 1)) return false;
      previousEnd = item.end;
      missingCount += item.end - item.start + 1;
    }
    const complete = object.received_chunk_count === object.chunk_count;
    if (
      missingCount !== object.chunk_count - object.received_chunk_count ||
      ((object.received_chunk_count === 0) !== (object.received_bytes === 0)) ||
      (complete !== (object.received_bytes === object.size_bytes)) ||
      (object.received_chunk_count > 0 && !complete &&
        !(object.received_bytes > 0 && object.received_bytes < object.size_bytes))
    ) return false;
    observedChunks += object.chunk_count;
    observedBytes += object.size_bytes;
    observedReceivedChunks += object.received_chunk_count;
    observedReceivedBytes += object.received_bytes;
  }
  const complete = status.received_chunk_count === status.chunk_count &&
    status.received_bytes === status.total_bytes;
  if (
    observedChunks !== status.chunk_count || observedBytes !== status.total_bytes ||
    observedReceivedChunks !== status.received_chunk_count ||
    observedReceivedBytes !== status.received_bytes ||
    ((status.state === "READY_TO_COMMIT" || status.state === "COMMITTED" ||
      status.state === "QUARANTINED") && !complete) ||
    (status.state === "MANIFEST_ACCEPTED" &&
      (status.received_chunk_count !== 0 || status.received_bytes !== 0)) ||
    (status.state === "RECEIVING" && (status.received_chunk_count === 0 || complete)) ||
    ((status.state === "COMMITTED" || status.state === "QUARANTINED") !==
      (status.receipt !== null)) ||
    (status.receipt !== null && !validReceipt(status.receipt, route, headers))
  ) return false;
  if (status.receipt !== null) {
    const receipt = objectValue(status.receipt)!;
    if (
      (status.state === "COMMITTED" &&
        receipt.schema_version !== "walksafe.raw-collection-receipt.v1") ||
      (status.state === "QUARANTINED" &&
        receipt.schema_version !== "walksafe.raw-collection-receipt.v2")
    ) return false;
    if (
      receipt.object_count !== status.object_count || receipt.chunk_count !== status.chunk_count ||
      receipt.total_bytes !== status.total_bytes || !Array.isArray(receipt.objects) ||
      receipt.objects.length !== status.objects.length
    ) return false;
    for (let index = 0; index < status.objects.length; index += 1) {
      const statusObject = objectValue(status.objects[index])!;
      const receiptObject = objectValue(receipt.objects[index])!;
      if (
        receiptObject.object_id !== statusObject.object_id ||
        receiptObject.kind !== statusObject.kind ||
        receiptObject.size_bytes !== statusObject.size_bytes ||
        receiptObject.sha256 !== statusObject.sha256 ||
        receiptObject.chunk_count !== statusObject.chunk_count
      ) return false;
    }
  }
  return true;
}

async function projectRawResponse(
  response: Response,
  route: RawCollectionRoute,
  headers: RawHeaderBinding,
  preflight = false
): Promise<Response> {
  if (preflight && response.status >= 200 && response.status < 300) {
    void response.body?.cancel("raw admission accepted").catch(() => undefined);
    return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
  }
  if (response.status === 401 || response.status === 403) {
    void response.body?.cancel("raw upstream authentication failure").catch(() => undefined);
    return jsonError(502, "gateway_upstream_auth_failed", "Backend authentication failed.");
  }
  const expectedSuccessStatus = route.operation === "PUT_MANIFEST" ||
    route.operation === "PUT_CHUNK"
    ? response.status === 200 || response.status === 201
    : response.status === 200;
  const success = !preflight && expectedSuccessStatus;
  const contentType = response.headers.get("content-type");
  if (contentType !== "application/json") {
    void response.body?.cancel("raw upstream content type invalid").catch(() => undefined);
    return jsonError(502, "gateway_raw_upstream_invalid", "The Backend response was invalid.");
  }
  const bytes = await boundedResponseBytes(
    response,
    success ? RAW_RESPONSE_MAX_BYTES : RAW_ERROR_MAX_BYTES
  );
  if (bytes === null) {
    return jsonError(502, "gateway_raw_upstream_invalid", "The Backend response was invalid.");
  }
  const payload = bytes.byteLength > 0 ? jsonBody(bytes) : null;
  if (success) {
    const valid = route.operation === "PUT_CHUNK"
      ? validChunkAck(payload, route, headers)
      : route.operation === "COMMIT"
        ? validReceipt(payload, route, headers) &&
          objectValue(payload)?.schema_version === "walksafe.raw-collection-receipt.v2"
        : validStatus(payload, route, headers);
    if (!valid) {
      return jsonError(502, "gateway_raw_upstream_invalid", "The Backend response was invalid.");
    }
    return new Response(canonicalJson(payload), {
      status: response.status,
      headers: { "cache-control": "no-store", "content-type": "application/json" }
    });
  }
  const detail = objectValue(payload?.detail);
  const code = detail?.code;
  const allowedStatus = new Set([400, 403, 404, 409, 413, 415, 422, 429, 503]);
  if (
    !allowedStatus.has(response.status) || typeof code !== "string" ||
    !RAW_UPSTREAM_ERROR_CODES.has(code) || !payload || !exactKeys(payload, ["detail"]) ||
    !detail || !(
      exactKeys(detail, ["code", "message"]) ||
      exactKeys(detail, ["code", "message", "max_bytes"])
    ) || typeof detail.message !== "string" || detail.message.length < 1 ||
    detail.message.length > 500 ||
    (Object.hasOwn(detail, "max_bytes") &&
      !integerInRange(detail.max_bytes, 1, RAW_MAX_TOTAL_BYTES))
  ) {
    return jsonError(502, "gateway_raw_upstream_invalid", "The Backend response was invalid.");
  }
  const retryAfter = response.headers.get("retry-after");
  const projectedHeaders = retryAfter && /^[1-9][0-9]{0,3}$/.test(retryAfter)
    ? { "retry-after": retryAfter }
    : undefined;
  return jsonError(
    response.status,
    code,
    "The raw collection request was not accepted.",
    projectedHeaders
  );
}

async function fetchRawBackend(
  request: Request,
  route: RawCollectionRoute,
  binding: GatewayFieldLongSessionBinding,
  headers: RawHeaderBinding,
  method: "HEAD" | "GET" | "PUT" | "POST",
  body: Uint8Array | null,
  lease: PrivacyOperationLease,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const initial: HeadersInit = body === null ? {} : {
    "content-length": String(body.byteLength),
    "content-type": route.operation === "PUT_CHUNK"
      ? "application/octet-stream"
      : "application/json"
  };
  const backendHeaders = rawCollectionBackendHeaders(
    proofInput(binding, route, headers, method),
    initial
  );
  if (!backendHeaders) {
    return jsonError(503, "gateway_backend_assertion_unavailable", "Backend proof is unavailable.");
  }
  const init: RequestInit = {
    method,
    headers: backendHeaders,
    cache: "no-store",
    signal: lease.controller.signal,
    ...(body === null ? {} : { body: Buffer.from(body) })
  };
  return fetchImpl
    ? fetchBackend(request, backendUrl(route.backendPath), init, 20_000, fetchImpl)
    : fetchBackend(request, backendUrl(route.backendPath), init, 20_000);
}

function acquireRawWrite(): (() => void) | null {
  if (rawWriteInFlight) return null;
  rawWriteInFlight = true;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    rawWriteInFlight = false;
  };
}

export function resetRawCollectionRelayForTests(): void {
  rawWriteInFlight = false;
}

export async function relayRawCollection(
  request: Request,
  dependencies: RawCollectionRelayDependencies = {}
): Promise<Response> {
  const url = new URL(request.url);
  const route = matchRawCollectionRoute(url.pathname);
  if (!route) return notFound();
  if (request.method !== route.method) return methodNotAllowed(route.method);
  if (url.search !== "") {
    return jsonError(400, "raw_collection_query_invalid", "Raw collection queries are not allowed.");
  }
  const write = route.operation !== "GET_STATUS";
  if (
    request.headers.has("content-encoding") || request.headers.has("transfer-encoding") ||
    request.headers.has("digest") || request.headers.has("trailer") ||
    (!write && (request.body !== null || request.headers.has("content-length") ||
      request.headers.has("content-type")))
  ) {
    return jsonError(400, "raw_collection_request_invalid", "The raw request framing is invalid.");
  }
  const headers = rawHeaders(request, route);
  if (!headers) {
    return jsonError(422, "raw_collection_headers_invalid", "Raw collection headers are invalid.");
  }
  const bindingResolver = dependencies.bindingResolver ?? gatewayFieldLongSessionBinding;
  const binding = bindingResolver(request);
  if (
    binding?.accountGeneration === null || binding?.accountGeneration === undefined ||
    binding.accountId !== `${binding.actorId}:generation:${binding.accountGeneration}`
  ) {
    return jsonError(401, "gateway_unauthorized", "A current device-bound account session is required.");
  }
  let lease: PrivacyOperationLease | null = null;
  try {
    lease = beginPrivacyOperation(binding.actorId);
  } catch {
    return jsonError(503, "privacy_ledger_unavailable", "Privacy state is unavailable.");
  }
  if (!lease || lease.accountGeneration !== binding.accountGeneration || !privacyCurrent(lease)) {
    if (lease) finishPrivacyOperation(lease);
    return jsonError(409, "account_generation_inactive", "The account generation is inactive.");
  }
  let releaseWrite: (() => void) | null = null;
  try {
    if (!bindingMatches(request, binding, bindingResolver)) {
      return jsonError(401, "gateway_unauthorized", "The device-bound session is no longer current.");
    }
    if (!write) {
      const response = await fetchRawBackend(
        request,
        route,
        binding,
        headers,
        "GET",
        null,
        lease,
        dependencies.fetchImpl
      );
      if (!bindingMatches(request, binding, bindingResolver) || !privacyCurrent(lease)) {
        void response.body?.cancel("raw status binding changed").catch(() => undefined);
        return jsonError(409, "raw_collection_binding_stale", "The account binding changed.");
      }
      const projected = await projectRawResponse(response, route, headers);
      if (!bindingMatches(request, binding, bindingResolver) || !privacyCurrent(lease)) {
        return jsonError(409, "raw_collection_binding_stale", "The account binding changed.");
      }
      return projected;
    }
    releaseWrite = acquireRawWrite();
    if (!releaseWrite) {
      return jsonError(503, "raw_collection_busy", "Another raw write is in progress.", {
        "retry-after": "1"
      });
    }
    const contentType = exactHeader(request, "content-type");
    const maxBytes = route.operation === "PUT_MANIFEST"
      ? RAW_MANIFEST_MAX_BYTES
      : route.operation === "PUT_CHUNK" ? RAW_CHUNK_MAX_BYTES : RAW_COMMIT_MAX_BYTES;
    const declaredLength = contentLength(request, maxBytes);
    if (
      declaredLength === null ||
      contentType !== (route.operation === "PUT_CHUNK"
        ? "application/octet-stream"
        : "application/json")
    ) {
      return jsonError(415, "raw_content_contract_invalid", "Raw Content-Type or Length is invalid.");
    }
    const initialGate = await writeGate(request, binding, lease, headers, bindingResolver);
    if (initialGate) return initialGate;
    const preflight = await fetchRawBackend(
      request,
      route,
      binding,
      headers,
      "HEAD",
      null,
      lease,
      dependencies.fetchImpl
    );
    if (preflight.status < 200 || preflight.status >= 300) {
      return projectRawResponse(preflight, route, headers, true);
    }
    void preflight.body?.cancel("raw admission accepted").catch(() => undefined);
    const beforeReadGate = await writeGate(request, binding, lease, headers, bindingResolver);
    if (beforeReadGate) return beforeReadGate;
    const body = await readExactBody(
      request,
      declaredLength,
      route.operation === "PUT_CHUNK" ? headers.chunkSha256 : null
    );
    if (body.error) return body.error;
    if (route.operation !== "PUT_CHUNK") {
      const invalid = validateJsonBinding(route, headers, body.bytes);
      if (invalid) return invalid;
    }
    const afterReadGate = await writeGate(request, binding, lease, headers, bindingResolver);
    if (afterReadGate) return afterReadGate;
    const response = await fetchRawBackend(
      request,
      route,
      binding,
      headers,
      route.method,
      body.bytes,
      lease,
      dependencies.fetchImpl
    );
    const afterUpstreamGate = await writeGate(
      request,
      binding,
      lease,
      headers,
      bindingResolver
    );
    if (afterUpstreamGate) {
      void response.body?.cancel("raw binding changed after upstream").catch(() => undefined);
      return afterUpstreamGate;
    }
    const projected = await projectRawResponse(response, route, headers);
    const finalGate = await writeGate(request, binding, lease, headers, bindingResolver);
    return finalGate ?? projected;
  } finally {
    releaseWrite?.();
    if (lease) finishPrivacyOperation(lease);
  }
}
