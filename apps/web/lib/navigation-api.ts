import { DETECT_API_BASE } from "@/lib/detect-api";
import type { DestinationSearchResponse, DestinationSearchResult, RoutePoint, WalkingRouteGuidePoint, WalkingRouteRequest, WalkingRouteResponse, WalkingRouteStep } from "@/types/navigation";

const NAVIGATION_TIMEOUT_MS = 8000;

export type DestinationSearchOptions = {
  limit?: number;
  origin?: RoutePoint | null;
  signal?: AbortSignal;
};

export class NavigationApiError extends Error {
  status: number;
  code: string | null;
  reason: string | null;

  constructor(message: string, status: number, code: string | null = null, reason: string | null = null) {
    super(message);
    this.name = "NavigationApiError";
    this.status = status;
    this.code = code;
    this.reason = reason;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function toRoutePoint(value: unknown): RoutePoint | null {
  if (!isRecord(value)) {
    return null;
  }

  const latitude = finiteNumber(value.latitude);
  const longitude = finiteNumber(value.longitude);
  if (latitude === null || longitude === null) {
    return null;
  }

  const name = typeof value.name === "string" ? value.name : null;
  return { latitude, longitude, name };
}


function toDestinationSearchResult(value: unknown): DestinationSearchResult | null {
  if (!isRecord(value)) {
    return null;
  }

  const id = typeof value.id === "string" ? value.id : null;
  const name = typeof value.name === "string" ? value.name : null;
  const point = toRoutePoint(value.point);
  if (id === null || name === null || point === null) {
    return null;
  }

  return {
    id,
    name,
    point,
    address: typeof value.address === "string" ? value.address : null,
    road_address: typeof value.road_address === "string" ? value.road_address : null,
    category: typeof value.category === "string" ? value.category : null,
    result_type:
      value.result_type === "poi" || value.result_type === "address" || value.result_type === "alias"
        ? value.result_type
        : "poi",
    distance_m: finiteNumber(value.distance_m)
  };
}

function toRouteStep(value: unknown): WalkingRouteStep | null {
  if (!isRecord(value)) {
    return null;
  }

  const index = finiteNumber(value.index);
  const distance = finiteNumber(value.distance_m);
  const duration = finiteNumber(value.duration_s);
  const points = Array.isArray(value.points) ? value.points.map(toRoutePoint).filter((point): point is RoutePoint => point !== null) : [];
  if (index === null || distance === null || duration === null) {
    return null;
  }

  return {
    index,
    distance_m: distance,
    duration_s: duration,
    points,
    instruction: typeof value.instruction === "string" ? value.instruction : null,
    road_name: typeof value.road_name === "string" ? value.road_name : null,
    turn_type: finiteNumber(value.turn_type),
    facility_type: finiteNumber(value.facility_type)
  };
}

function toGuidePoint(value: unknown): WalkingRouteGuidePoint | null {
  if (!isRecord(value)) {
    return null;
  }

  const index = finiteNumber(value.index);
  const point = toRoutePoint(value.point);
  if (index === null || point === null) {
    return null;
  }

  return {
    index,
    point,
    instruction: typeof value.instruction === "string" ? value.instruction : null,
    turn_type: finiteNumber(value.turn_type),
    point_type: typeof value.point_type === "string" ? value.point_type : null,
    facility_type: finiteNumber(value.facility_type),
    distance_from_start_m: finiteNumber(value.distance_from_start_m),
    remaining_distance_m: finiteNumber(value.remaining_distance_m)
  };
}

function errorParts(payload: unknown): { code: string | null; reason: string | null; message: string | null } {
  if (!isRecord(payload)) {
    return { code: null, reason: null, message: null };
  }

  const detail = payload.detail;
  const source = isRecord(detail) ? detail : payload;
  const code = typeof source.code === "string" ? source.code : null;
  const reason = typeof source.reason === "string" ? source.reason : null;
  const message = typeof source.message === "string" ? source.message : typeof detail === "string" ? detail : null;
  return { code, reason, message };
}

function navigationErrorMessage(payload: unknown, fallback: string) {
  const { code, reason, message } = errorParts(payload);
  if (code === "tmap_app_key_missing") {
    return "TMAP 길안내 appKey가 설정되지 않았습니다.";
  }
  if (code === "tmap_invalid_api_key") {
    return "TMAP appKey가 유효하지 않거나 보행자 경로안내 상품 권한이 없습니다.";
  }
  if (code === "kakao_api_key_missing") {
    return "카카오 길안내 API 키가 설정되지 않았습니다.";
  }
  if (code === "route_unavailable") {
    return "도보 경로를 찾을 수 없습니다.";
  }
  if (code === "empty_query") {
    return "목적지 검색어가 비어 있습니다.";
  }
  if (code === "tmap_timeout") {
    return "TMAP 응답 시간이 초과됐습니다. 잠시 후 다시 시도해 주세요.";
  }
  if (code === "tmap_network_error") {
    return "TMAP 서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.";
  }
  if (code === "invalid_tmap_response") {
    return "TMAP 응답 형식을 확인할 수 없습니다.";
  }
  return message || reason || code || fallback;
}

function assertOnline() {
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    throw new NavigationApiError("오프라인 상태입니다. 길안내 API 요청을 보내지 않습니다.", 0, "offline");
  }
}

async function parseJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return response.json();
  }

  const text = await response.text();
  return text ? { message: text } : null;
}

async function fetchWithTimeout(url: string, init: RequestInit, fallbackMessage: string, signal?: AbortSignal): Promise<unknown> {
  const abortController = new AbortController();
  const timeoutId = window.setTimeout(() => abortController.abort(), NAVIGATION_TIMEOUT_MS);
  const abortFromSignal = () => abortController.abort();
  if (signal?.aborted) {
    abortController.abort();
  } else {
    signal?.addEventListener("abort", abortFromSignal, { once: true });
  }
  let response: Response;

  try {
    response = await fetch(url, {
      ...init,
      cache: "no-store",
      signal: abortController.signal
    });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      if (signal?.aborted) {
        throw new NavigationApiError("요청을 취소했습니다.", 0, "aborted");
      }
      throw new NavigationApiError("길안내 서버 응답 시간이 초과됐습니다.", 0, "timeout");
    }
    throw new NavigationApiError("길안내 서버에 연결할 수 없습니다.", 0, "network_error");
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortFromSignal);
  }

  const payload = await parseJson(response);
  if (!response.ok) {
    const parts = errorParts(payload);
    throw new NavigationApiError(navigationErrorMessage(payload, `${fallbackMessage} (${response.status})`), response.status, parts.code, parts.reason);
  }

  return payload;
}

function walkingRouteFromPayload(payload: unknown): WalkingRouteResponse {
  if (!isRecord(payload) || payload.schema_version !== "walksafe.walking_route.v1") {
    throw new NavigationApiError("길안내 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  const summary = isRecord(payload.summary) ? payload.summary : {};
  const distance = finiteNumber(summary.distance_m);
  const duration = finiteNumber(summary.duration_s);
  const polyline = Array.isArray(payload.polyline) ? payload.polyline.map(toRoutePoint).filter((point): point is RoutePoint => point !== null) : [];
  const steps = Array.isArray(payload.steps) ? payload.steps.map(toRouteStep).filter((step): step is WalkingRouteStep => step !== null) : [];
  const guidePoints = Array.isArray(payload.guide_points)
    ? payload.guide_points.map(toGuidePoint).filter((point): point is WalkingRouteGuidePoint => point !== null)
    : [];
  const provider = payload.provider === "tmap_pedestrian" || payload.provider === "kakao_mobility" ? payload.provider : null;
  const priority =
    payload.priority === "RECOMMEND" ||
    payload.priority === "MAIN_STREET" ||
    payload.priority === "DISTANCE" ||
    payload.priority === "STAIR_AVOID"
      ? payload.priority
      : null;
  const resultCode = finiteNumber(payload.provider_result_code);
  const resultMessage = typeof payload.provider_result_message === "string" ? payload.provider_result_message : null;

  if (provider === null || priority === null || distance === null || duration === null || resultCode === null || resultMessage === null) {
    throw new NavigationApiError("길안내 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return {
    schema_version: "walksafe.walking_route.v1",
    provider,
    provider_route_id: typeof payload.provider_route_id === "string" ? payload.provider_route_id : null,
    priority,
    summary: { distance_m: distance, duration_s: duration },
    polyline,
    steps,
    guide_points: guidePoints,
    provider_result_code: resultCode,
    provider_result_message: resultMessage
  };
}


function destinationSearchFromPayload(payload: unknown): DestinationSearchResponse {
  if (!isRecord(payload) || payload.schema_version !== "walksafe.destination_search.v1") {
    throw new NavigationApiError("목적지 검색 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  const provider = payload.provider === "tmap_poi" ? payload.provider : null;
  const query = typeof payload.query === "string" ? payload.query : null;
  const results = Array.isArray(payload.results)
    ? payload.results.map(toDestinationSearchResult).filter((result): result is DestinationSearchResult => result !== null)
    : [];

  if (provider === null || query === null) {
    throw new NavigationApiError("목적지 검색 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return {
    schema_version: "walksafe.destination_search.v1",
    provider,
    query,
    results
  };
}

export async function fetchWalkingRoute(request: WalkingRouteRequest): Promise<WalkingRouteResponse> {
  assertOnline();
  const payload = await fetchWithTimeout(
    `${DETECT_API_BASE}/navigation/walking`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request)
    },
    "도보 길안내 요청 실패"
  );

  return walkingRouteFromPayload(payload);
}

export async function searchDestinations(
  query: string,
  optionsOrLimit: number | DestinationSearchOptions = 5
): Promise<DestinationSearchResponse> {
  assertOnline();
  const options = typeof optionsOrLimit === "number" ? { limit: optionsOrLimit } : optionsOrLimit;
  const params = new URLSearchParams({ query, limit: String(options.limit ?? 5) });
  if (options.origin) {
    params.set("origin_lat", String(options.origin.latitude));
    params.set("origin_lng", String(options.origin.longitude));
  }
  const payload = await fetchWithTimeout(
    `${DETECT_API_BASE}/navigation/destinations/search?${params.toString()}`,
    { method: "GET" },
    "목적지 검색 요청 실패",
    options.signal
  );

  return destinationSearchFromPayload(payload);
}
