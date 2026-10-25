import {
  type GatewayFetch,
  authorizedProxyActor,
  proxyRequestHeaders,
  fetchBackend,
  backendUrl,
  toBackendResponse
} from "./backend.js";
import { gatewaySessionAccountGeneration, gatewaySessionScope, gatewayUnauthorizedResponse } from "./auth.js";
import { readBoundedJsonBody } from "./request-body.js";
import { finishPrivacyOperation } from "./privacy-rights.js";
import {
  startPrivacyOperation,
  privacyOperationIsCurrent,
  privacyOperationInactive,
  responseWithPrivacyOperation
} from "./request-scope.js";
import { cancelUpstream } from "./route-responses.js";

/** Independent opt-in test diagnostics; never uses RAW/report consent or client identity headers. */
export async function relayFieldDiagnostics(request: Request, fetchImpl?: GatewayFetch): Promise<Response> {
  const actorId = authorizedProxyActor(request);
  const generation = gatewaySessionAccountGeneration(request);
  if (!actorId || generation === null || gatewaySessionScope(request) !== "general") {
    return gatewayUnauthorizedResponse(true);
  }
  const operation = startPrivacyOperation(actorId, generation);
  if (operation.error) return operation.error;
  let responseOwnsOperation = false;
  try {
    const url = new URL(request.url);
    if (url.search || !privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    let body: string | undefined;
    if (request.method !== "GET") {
      if (request.headers.get("content-type")?.split(";", 1)[0]?.trim() !== "application/json") {
        return Response.json({ detail: { code: "diagnostic_content_type_invalid" } }, { status: 415 });
      }
      const scopedRequest = new Request(request, {
        signal: AbortSignal.any([request.signal, operation.lease.controller.signal])
      });
      const bounded = await readBoundedJsonBody(scopedRequest, 262_144);
      if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
      if (bounded.error) return bounded.error;
      body = JSON.stringify(bounded.value);
    }
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const headers = proxyRequestHeaders(request, { accept: "application/json" }, generation);
    if (body !== undefined) headers.set("content-type", "application/json");
    const init: RequestInit = { method: request.method, headers, cache: "no-store", signal: operation.lease.controller.signal,
      ...(body === undefined ? {} : { body }) };
    const backendPath = url.pathname.slice("/api".length);
    const response = await fetchBackend(request, backendUrl(backendPath), init, 15_000,
      fetchImpl ?? globalThis.fetch, 262_144);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    const converted = await toBackendResponse(response);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(converted);
      return privacyOperationInactive();
    }
    const protectedResponse = responseWithPrivacyOperation(converted, operation.lease, request.signal);
    responseOwnsOperation = true;
    return protectedResponse;
  } finally {
    if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
  }
}

