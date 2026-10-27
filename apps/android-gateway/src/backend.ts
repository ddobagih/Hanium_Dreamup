import { BACKEND_API_BASE_URL } from "./config.js";
import {
  gatewayTokenForBackend,
  isGatewayAccessConfigured,
  isInsecureLocalGatewayBypassAllowed,
  gatewayUnavailableResponse,
  isFieldSessionAuthorized,
  gatewayUnauthorizedResponse,
  gatewaySessionActor,
  gatewaySessionAccountGeneration,
  gatewayFieldLongSessionBinding,
  gatewaySessionAuthEpoch
} from "./auth.js";
import { FIELD_TEST_TOKEN_HEADER, type GatewayFetch, DEFAULT_PROXY_TIMEOUT_MS } from "./backend-contract.js";
import { bindBackendActor, signBackendActorRequest, attachBackendActor } from "./backend-request-signing.js";

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
  let response: Response | undefined;
  try {
    const signed = await signBackendActorRequest(url, { method: "POST", headers });
    if (!signed) return "unavailable";
    const timeoutSignal = AbortSignal.timeout(Math.max(1, timeoutMs));
    const signal = requestSignal
      ? AbortSignal.any([requestSignal, timeoutSignal])
      : timeoutSignal;
    signal.throwIfAborted();
    response = await fetchImpl(url, {
      ...signed,
      redirect: "error",
      signal
    });
    try {
      signal.throwIfAborted();
      if (response.status === 204) return "current";
      if (response.status === 401) return "denied";
    } finally {
      // Only the status is used. Release error and late-response bodies as well;
      // cleanup must not extend the authority check's deadline.
      void response.body?.cancel().catch(() => undefined);
    }
  } catch {
    // A transport failure must not be mistaken for a revoked account.
  }
  return "unavailable";
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

export {
  FIELD_TEST_TOKEN_HEADER,
  ACTOR_ID_HEADER,
  ACTOR_ASSERTION_HEADER,
  ACCOUNT_GENERATION_HEADER,
  RAW_REQUEST_PROOF_HEADER,
  RAW_PURPOSE_HEADER,
  RAW_WALK_ID_HEADER,
  RAW_MANIFEST_SHA256_HEADER,
  RAW_CHUNK_SHA256_HEADER,
  RAW_COMMIT_SHA256_HEADER,
  RAW_CONSENT_RECEIPT_SHA256_HEADER,
  DELETION_ACCESS_PRE_DIGEST_HEADER,
  DELETION_TOMBSTONE_HEADER,
  REPORT_V2_OWN_LIMITS_MS,
  BACKEND_SESSION_GATE_TIMEOUT_MS,
  ORDINARY_ROUTE_OWN_LIMITS_MS,
  type GatewayFetch
} from "./backend-contract.js";
export {
  IMAGE_MULTIPART_LIMIT_BYTES,
  REPORT_METADATA_LIMIT_BYTES,
  REPORT_IMAGE_LIMIT_BYTES,
  countMultipartDelimiters,
  readBoundedMultipartFormData
} from "./report-multipart.js";
export {
  type ImageUploadAdmission,
  setImageUploadMaxConcurrencyForTests,
  imageUploadRateLimitedResponse,
  acquireImageUploadAdmission,
  resetImageUploadAdmissionForTests
} from "./image-upload-admission.js";
export {
  createBackendActorAssertion,
  BACKEND_ACTOR_ASSERTION_AUDIENCE,
  BACKEND_ACTOR_ASSERTION_TTL_SECONDS,
  PENDING_BACKEND_ACTOR_ASSERTION,
  type BackendActorBinding,
  type BackendActorRequestBinding,
  backendActorAssertionKeyId,
  createBackendActorAssertionV4,
  BackendActorAssertionUnavailableError,
  signBackendActorRequest,
  type RawCollectionOperation,
  type RawCollectionBackendProofInput,
  rawCollectionRequestProofMessage,
  createRawCollectionRequestProof,
  rawCollectionBackendHeaders,
  type DeletionBackendAssertionInput,
  createDeletionBackendAssertion,
  deletionBackendHeaders
} from "./backend-request-signing.js";
export { fetchBackend } from "./backend-transport.js";
export { guardWalkingBackendResponse, toBackendResponse } from "./backend-response.js";
