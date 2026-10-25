import { BACKEND_API_BASE_URL } from "./config.js";
import { readBoundedJsonBody } from "./request-body.js";

const WALKING_POLYLINE_MAX_POINTS = 8_192;

const WALKING_MAX_STEPS = 1_024;

const WALKING_MAX_GUIDES = 1_024;

function backendResponseRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function walkingCoordinateValid(value: unknown): boolean {
  if (!backendResponseRecord(value)) return false;
  const latitude = value.latitude;
  const longitude = value.longitude;
  return typeof latitude === "number" && Number.isFinite(latitude) &&
    latitude >= -90 && latitude <= 90 &&
    typeof longitude === "number" && Number.isFinite(longitude) &&
    longitude >= -180 && longitude <= 180;
}

function walkingGeometryWithinLimits(payload: Record<string, unknown>): boolean {
  const polyline = payload.polyline;
  if (!Array.isArray(polyline) || polyline.length < 2 ||
      polyline.length > WALKING_POLYLINE_MAX_POINTS ||
      !polyline.every(walkingCoordinateValid)) return false;

  // Older walking responses may omit steps or guides. Validate their geometry when
  // present without duplicating the Backend's full route schema or Android policy.
  const steps = payload.steps;
  if (steps !== undefined) {
    if (!Array.isArray(steps) || steps.length > WALKING_MAX_STEPS) return false;
    let stepPointCount = 0;
    for (const step of steps) {
      if (!backendResponseRecord(step) || !Array.isArray(step.points) ||
          step.points.length === 0) return false;
      stepPointCount += step.points.length;
      if (stepPointCount > WALKING_POLYLINE_MAX_POINTS ||
          !step.points.every(walkingCoordinateValid)) return false;
    }
  }

  const guides = payload.guide_points;
  if (guides !== undefined && guides !== null) {
    if (!Array.isArray(guides) || guides.length > WALKING_MAX_GUIDES ||
        !guides.every((guide: unknown) =>
          backendResponseRecord(guide) && walkingCoordinateValid(guide.point))) return false;
  }
  return true;
}

/** S19-48: bounded navigation bytes are already complete before this structural guard. */
export async function guardWalkingBackendResponse(response: Response): Promise<Response> {
  if (response.status < 200 || response.status >= 300) return response;
  let payload: unknown;
  try {
    payload = await response.clone().json();
  } catch {
    payload = null;
  }
  if (response.status === 200 && backendResponseRecord(payload) &&
      walkingGeometryWithinLimits(payload)) return response;
  void response.body?.cancel("invalid walking route geometry").catch(() => undefined);
  return Response.json(
    { detail: { code: "gateway_upstream_unavailable", message: "upstream response failed" } },
    { status: 502, headers: { "cache-control": "no-store" } }
  );
}

async function isStaleAccountAuthentication(response: Response): Promise<boolean> {
  if (response.status !== 401 || !response.body) return false;
  try {
    const bodyRequest = new Request(BACKEND_API_BASE_URL, {
      method: "POST",
      headers: response.headers,
      body: response.body,
      duplex: "half"
    } as RequestInit);
    const bounded = await readBoundedJsonBody(bodyRequest, 4 * 1024);
    if (bounded.error) return false;
    const value = bounded.value;
    if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
    const detail = (value as Record<string, unknown>).detail;
    return detail !== null && typeof detail === "object" && !Array.isArray(detail) &&
      (detail as Record<string, unknown>).code === "account_authentication_stale";
  } catch {
    return false;
  }
}

export async function toBackendResponse(
  response: Response,
  fallbackContentType = "application/json"
): Promise<Response> {
  if (response.status === 401 || response.status === 403) {
    const staleAccount = await isStaleAccountAuthentication(response);
    void response.body?.cancel("upstream authentication response is not exposed to the Android client")
      .catch(() => undefined);
    if (staleAccount) {
      return Response.json(
        { detail: {
          code: "account_authentication_stale",
          message: "The account session is no longer current."
        } },
        { status: 401, headers: { "cache-control": "no-store" } }
      );
    }
    return Response.json(
      {
        detail: {
          code: "gateway_upstream_auth_failed",
          message: "upstream service authentication failed"
        }
      },
      { status: 502, headers: { "cache-control": "no-store" } }
    );
  }
  const headers = new Headers({ "cache-control": "no-store" });
  const contentType = response.headers.get("content-type") ?? fallbackContentType;
  if (contentType) headers.set("content-type", contentType);

  const contentDisposition = response.headers.get("content-disposition");
  if (contentDisposition) headers.set("content-disposition", contentDisposition);
  for (const headerName of [
    "retry-after",
    "x-walksafe-audit-id",
    "x-walksafe-actor-id",
    "x-walksafe-demo-filter",
    "x-walksafe-export-profile",
    "x-walksafe-location-precision"
  ]) {
    const value = response.headers.get(headerName);
    if (value) headers.set(headerName, value);
  }
  const requestId = response.headers.get("x-request-id") ?? response.headers.get("x-correlation-id");
  if (requestId) headers.set("x-request-id", requestId);

  return new Response(response.body, { status: response.status, headers });
}

