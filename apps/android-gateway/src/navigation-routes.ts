import { MIMEType } from "node:util";
import { type GatewayRouteMatch } from "./route-registry.js";
import {
  type GatewayFetch,
  authorizeProxyRequest,
  authorizedProxyActor,
  proxyRequestHeaders,
  fetchBackend,
  backendUrl,
  guardWalkingBackendResponse,
  toBackendResponse
} from "./backend.js";
import {
  gatewayUnauthorizedResponse,
  gatewaySessionAccountGeneration,
  gatewayUnavailableResponse
} from "./auth.js";
import { readBoundedTextBody, duplicateJsonObjectKeysError } from "./request-body.js";
import { finishPrivacyOperation } from "./privacy-rights.js";
import { walkOwnershipGate } from "./field-walk-routes.js";
import {
  startPrivacyOperation,
  privacyOperationIsCurrent,
  privacyOperationInactive,
  responseWithPrivacyOperation
} from "./request-scope.js";
import { cancelUpstream } from "./route-responses.js";

function navigationRequestInvalid(): Response {
  return Response.json(
    { code: "navigation_request_invalid" },
    { status: 400, headers: { "cache-control": "no-store" } }
  );
}

const NAVIGATION_PRIORITIES = new Set([
  "RECOMMEND", "MAIN_STREET", "DISTANCE", "STAIR_AVOID"
]);

const DESTINATION_SEARCH_KEYS = new Set([
  "query", "limit", "origin_lat", "origin_lng"
]);

function finiteNavigationNumber(value: unknown): number | null {
  if (typeof value !== "number") {
    if (typeof value !== "string" ||
        !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim())) {
      return null;
    }
  }
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function navigationPointValid(value: unknown): boolean {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const point = value as Record<string, unknown>;
  const latitude = finiteNavigationNumber(point.latitude);
  const longitude = finiteNavigationNumber(point.longitude);
  return latitude !== null && latitude >= -90 && latitude <= 90 &&
    longitude !== null && longitude >= -180 && longitude <= 180;
}

function walkingRoutePayloadValid(value: unknown): boolean {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const route = value as Record<string, unknown>;
  if (!navigationPointValid(route.origin) || !navigationPointValid(route.destination)) return false;
  if (route.waypoints !== undefined &&
      (!Array.isArray(route.waypoints) || route.waypoints.length > 5 ||
       !route.waypoints.every(navigationPointValid))) return false;
  if (route.priority !== undefined &&
      (typeof route.priority !== "string" || !NAVIGATION_PRIORITIES.has(route.priority))) {
    return false;
  }
  if (route.radius_m !== undefined) {
    const radius = finiteNavigationNumber(route.radius_m);
    if (radius === null || !Number.isInteger(radius) || radius < 1 || radius > 12000) return false;
  }
  if (route.default_speed !== undefined && route.default_speed !== null) {
    const speed = finiteNavigationNumber(route.default_speed);
    if (speed === null || speed <= 0 || speed > 20) return false;
  }
  return true;
}

function navigationJsonContentType(request: Request): boolean {
  const contentType = request.headers.get("content-type");
  if (!contentType) return false;
  try {
    return new MIMEType(contentType).essence === "application/json";
  } catch {
    return false;
  }
}

function destinationSearchQueryValid(request: Request): boolean {
  const params = new URL(request.url).searchParams;
  const seen = new Set<string>();
  for (const [key] of params) {
    if (!DESTINATION_SEARCH_KEYS.has(key) || seen.has(key)) return false;
    seen.add(key);
  }
  const query = params.get("query");
  if (query === null || [...query].length < 1 || [...query].length > 80) return false;
  const limitValue = params.get("limit");
  if (limitValue !== null) {
    const limit = finiteNavigationNumber(limitValue);
    if (limit === null || !Number.isInteger(limit) || limit < 1 || limit > 10) return false;
  }
  const hasLatitude = params.has("origin_lat");
  const hasLongitude = params.has("origin_lng");
  if (hasLatitude !== hasLongitude) return false;
  if (hasLatitude) {
    const latitude = finiteNavigationNumber(params.get("origin_lat"));
    const longitude = finiteNavigationNumber(params.get("origin_lng"));
    if (latitude === null || latitude < -90 || latitude > 90 ||
        longitude === null || longitude < -180 || longitude > 180) return false;
  }
  return true;
}

// The Backend provider ends first (4 s); the complete upstream response has 5 s.
// The navigation handler includes the account gate, body read, and upstream work.
const NAVIGATION_PROXY_TIMEOUT_MS = 5_000;

export const NAVIGATION_WALKING_DEADLINE_MS = 7_500;

export const NAVIGATION_SEARCH_DEADLINE_MS = 6_500;

const NAVIGATION_PROXY_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

export function navigationDeadlineResponse(): Response {
  return Response.json(
    { detail: { code: "gateway_navigation_timeout", message: "navigation request exceeded its deadline" } },
    { status: 504, headers: { "cache-control": "no-store" } }
  );
}

export async function walkingRoute(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const actorId = authorizedProxyActor(request);
  if (!actorId) return gatewayUnauthorizedResponse();
  const walkOwned = walkOwnershipGate(request);
  if (walkOwned) return walkOwned;
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  let responseOwnsOperation = false;
  try {
    if (match.body !== "json") {
      return gatewayUnavailableResponse();
    }
    if (!navigationJsonContentType(request)) return navigationRequestInvalid();
    const scopedRequest = new Request(request, {
      signal: AbortSignal.any([request.signal, operation.lease.controller.signal])
    });
    const bounded = await readBoundedTextBody(scopedRequest, 16 * 1024);
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (bounded.error) return bounded.error;
    let payload: unknown;
    try {
      payload = JSON.parse(bounded.text);
    } catch {
      return navigationRequestInvalid();
    }
    const duplicateError = duplicateJsonObjectKeysError(bounded.text);
    if (duplicateError) return duplicateError;
    if (!walkingRoutePayloadValid(payload)) return navigationRequestInvalid();
    const init: RequestInit = {
      method: "POST",
      headers: proxyRequestHeaders(request, {
        "content-type": request.headers.get("content-type") ?? "application/json"
      }, operation.lease.accountGeneration),
      body: bounded.text,
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const fetched = await fetchBackend(
      request, backendUrl("/navigation/walking"), init,
      NAVIGATION_PROXY_TIMEOUT_MS, fetchImpl ?? globalThis.fetch,
      NAVIGATION_PROXY_MAX_RESPONSE_BYTES
    );
    const response = await guardWalkingBackendResponse(fetched);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    const protectedResponse = responseWithPrivacyOperation(
      await toBackendResponse(response),
      operation.lease,
      request.signal
    );
    responseOwnsOperation = true;
    return protectedResponse;
  } finally {
    if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
  }
}

export async function destinationSearch(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const actorId = authorizedProxyActor(request);
  if (!actorId) return gatewayUnauthorizedResponse();
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  let responseOwnsOperation = false;
  try {
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (match.query === "validated" &&
        !destinationSearchQueryValid(request)) return navigationRequestInvalid();
    const init: RequestInit = {
      headers: proxyRequestHeaders(request, undefined, operation.lease.accountGeneration),
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const response = await fetchBackend(
      request, backendUrl("/navigation/destinations/search", request), init,
      NAVIGATION_PROXY_TIMEOUT_MS, fetchImpl ?? globalThis.fetch,
      NAVIGATION_PROXY_MAX_RESPONSE_BYTES
    );
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return privacyOperationInactive();
    }
    const protectedResponse = responseWithPrivacyOperation(
      await toBackendResponse(response),
      operation.lease,
      request.signal
    );
    responseOwnsOperation = true;
    return protectedResponse;
  } finally {
    if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
  }
}

