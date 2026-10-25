import {
  type GatewayFetch,
  checkBackendAccountSessionCurrent,
  BACKEND_SESSION_GATE_TIMEOUT_MS
} from "./backend.js";
import {
  isGatewaySessionAuthorized,
  gatewaySessionActor,
  gatewaySessionAccountGeneration,
  gatewaySessionAuthEpoch,
  gatewayUnauthorizedResponse,
  gatewayUnavailableResponse
} from "./auth.js";

export function noStore(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("cache-control", "no-store");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers
  });
}

export function clientClosedResponse(): Response {
  return Response.json(
    { detail: { code: "gateway_client_closed", message: "client request was cancelled" } },
    { status: 499, headers: { "cache-control": "no-store" } }
  );
}

export async function backendAccountSessionGate(
  request: Request,
  fetchImpl?: GatewayFetch
): Promise<Response | null> {
  if (request.signal.aborted) return clientClosedResponse();
  if (!isGatewaySessionAuthorized(request)) return null;
  const actorId = gatewaySessionActor(request);
  const accountGeneration = gatewaySessionAccountGeneration(request);
  const authEpoch = gatewaySessionAuthEpoch(request);
  if (accountGeneration === null && authEpoch === null) return null;
  if (!actorId || accountGeneration === null || authEpoch === null) {
    return noStore(gatewayUnauthorizedResponse());
  }
  try {
    // gw-F6 (S19-59): its own short limit, counted in the adapter's request deadline.
    const current = await checkBackendAccountSessionCurrent(
      { actorId, accountGeneration, authEpoch },
      fetchImpl,
      BACKEND_SESSION_GATE_TIMEOUT_MS,
      request.signal
    );
    if (request.signal.aborted) return clientClosedResponse();
    if (current === "current") return null;
    return noStore(current === "denied"
      ? gatewayUnauthorizedResponse()
      : gatewayUnavailableResponse());
  } catch {
    return request.signal.aborted
      ? clientClosedResponse()
      : noStore(gatewayUnavailableResponse());
  }
}

export function routeNotFound(): Response {
  return Response.json(
    { code: "gateway_route_not_found" },
    { status: 404, headers: { "cache-control": "no-store" } }
  );
}

export function methodNotAllowed(methods: readonly string[]): Response {
  return Response.json(
    { code: "gateway_method_not_allowed" },
    {
      status: 405,
      headers: {
        allow: methods.join(", "),
        "cache-control": "no-store"
      }
    }
  );
}

export function cancelUpstream(response: Response): void {
  void response.body?.cancel().catch(() => undefined);
}

export function objectPayload(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

export function requestHasEntityBody(request: Request): boolean {
  if (request.body === null || request.headers.get("content-length") === "0") return false;
  return (
    request.headers.has("content-length") ||
    request.headers.has("transfer-encoding") ||
    request.headers.has("content-type")
  );
}

