import { createHmac, randomBytes, createHash } from "node:crypto";
import { gatewayTokenForBackend } from "./auth.js";
import {
  ACTOR_ID_HEADER,
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  FIELD_TEST_TOKEN_HEADER,
  RAW_REQUEST_PROOF_HEADER,
  RAW_PURPOSE_HEADER,
  RAW_WALK_ID_HEADER,
  RAW_MANIFEST_SHA256_HEADER,
  RAW_CONSENT_RECEIPT_SHA256_HEADER,
  RAW_CHUNK_SHA256_HEADER,
  RAW_COMMIT_SHA256_HEADER,
  DELETION_ACCESS_PRE_DIGEST_HEADER,
  DELETION_TOMBSTONE_HEADER
} from "./backend-contract.js";

const ACTOR_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;

const RAW_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const LOWER_SHA256 = /^[0-9a-f]{64}$/;

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
export function bindBackendActor(headers: Headers, binding: BackendActorBinding): boolean {
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

export function attachBackendActor(
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

