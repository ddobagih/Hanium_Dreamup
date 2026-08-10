/**
 * Client for the normalized backend navigation contract. Provider keys stay on the backend;
 * offline, timeout and malformed responses fail closed instead of returning a mock route.
 */
import { notifyGatewaySessionInvalid } from "./gateway-session-client";
import type { DestinationSearchResponse, DestinationSearchResult, RoutePoint, WalkingRouteGuidePoint, WalkingRouteRequest, WalkingRouteResponse, WalkingRouteStep } from "@/types/navigation";

const NAVIGATION_TIMEOUT_MS = 8000;
const ROUTE_ENDPOINT_MAX_DISTANCE_M = 100;
const ROUTE_SUMMARY_GEOMETRY_MIN_RATIO = 0.5;
const ROUTE_SUMMARY_GEOMETRY_MAX_RATIO = 3;
const ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M = 50;
const GUIDE_POINT_MAX_ROUTE_DISTANCE_M = 30;
const GUIDE_DISTANCE_ABSOLUTE_TOLERANCE_M = 30;
const GUIDE_DISTANCE_RELATIVE_TOLERANCE = 0.1;
const GUIDE_MONOTONIC_TOLERANCE_M = 10;
export const NAVIGATION_API_BASE = "/api";

export type DestinationSearchOptions = {
  limit?: number;
  origin?: RoutePoint | null;
  signal?: AbortSignal;
};

export type WalkingRouteRequestOptions = {
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

export const WALKING_ROUTE_PROVIDER_LABEL = "TMAP";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function optionalString(value: unknown): string | null | undefined {
  return value === null || value === undefined ? null : typeof value === "string" ? value : undefined;
}

function optionalInteger(value: unknown, minimum?: number): number | null | undefined {
  if (value === null || value === undefined) {
    return null;
  }
  const parsed = finiteNumber(value);
  if (parsed === null || !Number.isInteger(parsed) || (minimum !== undefined && parsed < minimum)) {
    return undefined;
  }
  return parsed;
}

function toRoutePoint(value: unknown): RoutePoint | null {
  if (!isRecord(value)) {
    return null;
  }

  const latitude = finiteNumber(value.latitude);
  const longitude = finiteNumber(value.longitude);
  if (latitude === null || longitude === null || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    return null;
  }

  const name = optionalString(value.name);
  if (name === undefined) {
    return null;
  }
  return { latitude, longitude, name };
}


function toDestinationSearchResult(value: unknown): DestinationSearchResult | null {
  if (!isRecord(value)) {
    return null;
  }

  const id = typeof value.id === "string" && value.id.trim() ? value.id : null;
  const name = typeof value.name === "string" && value.name.trim() ? value.name : null;
  const point = toRoutePoint(value.point);
  const address = optionalString(value.address);
  const roadAddress = optionalString(value.road_address);
  const category = optionalString(value.category);
  const distance = value.distance_m === null || value.distance_m === undefined ? null : finiteNumber(value.distance_m);
  if (
    id === null ||
    name === null ||
    point === null ||
    address === undefined ||
    roadAddress === undefined ||
    category === undefined ||
    (distance !== null && (!Number.isInteger(distance) || distance < 0)) ||
    (value.result_type !== "poi" && value.result_type !== "address" && value.result_type !== "alias")
  ) {
    return null;
  }

  return {
    id,
    name,
    point,
    address,
    road_address: roadAddress,
    category,
    result_type: value.result_type,
    distance_m: distance
  };
}

function toRouteStep(value: unknown): WalkingRouteStep | null {
  if (!isRecord(value)) {
    return null;
  }

  const index = optionalInteger(value.index, 0);
  const distance = finiteNumber(value.distance_m);
  const duration = finiteNumber(value.duration_s);
  const rawPoints = Array.isArray(value.points) ? value.points : null;
  const points = rawPoints?.map(toRoutePoint) ?? [];
  const instruction = optionalString(value.instruction);
  const roadName = optionalString(value.road_name);
  const turnType = optionalInteger(value.turn_type);
  const facilityType = optionalInteger(value.facility_type);
  if (
    index === null ||
    index === undefined ||
    distance === null ||
    !Number.isInteger(distance) ||
    distance < 0 ||
    duration === null ||
    !Number.isInteger(duration) ||
    duration < 0 ||
    rawPoints === null ||
    points.length === 0 ||
    points.some((point) => point === null) ||
    instruction === undefined ||
    roadName === undefined ||
    turnType === undefined ||
    facilityType === undefined
  ) {
    return null;
  }

  return {
    index,
    distance_m: distance,
    duration_s: duration,
    points: points as RoutePoint[],
    instruction,
    road_name: roadName,
    turn_type: turnType,
    facility_type: facilityType
  };
}

function toGuidePoint(value: unknown): WalkingRouteGuidePoint | null {
  if (!isRecord(value)) {
    return null;
  }

  const index = optionalInteger(value.index, 0);
  const point = toRoutePoint(value.point);
  const instruction = optionalString(value.instruction);
  const pointType = optionalString(value.point_type);
  const turnType = optionalInteger(value.turn_type);
  const facilityType = optionalInteger(value.facility_type);
  const distanceFromStart = optionalInteger(value.distance_from_start_m, 0);
  const remainingDistance = optionalInteger(value.remaining_distance_m, 0);
  if (
    index === null ||
    index === undefined ||
    point === null ||
    instruction === undefined ||
    pointType === undefined ||
    turnType === undefined ||
    facilityType === undefined ||
    distanceFromStart === undefined ||
    remainingDistance === undefined
  ) {
    return null;
  }

  return {
    index,
    point,
    instruction,
    turn_type: turnType,
    point_type: pointType,
    facility_type: facilityType,
    distance_from_start_m: distanceFromStart,
    remaining_distance_m: remainingDistance
  };
}

function routePointDistanceM(start: RoutePoint, end: RoutePoint): number {
  const earthRadiusM = 6_371_000;
  const startLat = (start.latitude * Math.PI) / 180;
  const endLat = (end.latitude * Math.PI) / 180;
  const deltaLat = endLat - startLat;
  const deltaLng = ((end.longitude - start.longitude) * Math.PI) / 180;
  const value =
    Math.sin(deltaLat / 2) ** 2 +
    Math.cos(startLat) * Math.cos(endLat) * Math.sin(deltaLng / 2) ** 2;
  const bounded = Math.min(1, Math.max(0, value));
  return earthRadiusM * 2 * Math.atan2(Math.sqrt(bounded), Math.sqrt(1 - bounded));
}

type GuideRouteProjection = {
  distanceToRouteM: number;
  distanceFromStartM: number;
};

function projectGuidePointToPolyline(point: RoutePoint, polyline: readonly RoutePoint[]): GuideRouteProjection | null {
  let best: GuideRouteProjection | null = null;
  let traversedM = 0;
  const metersPerLatitudeDegree = 111_320;

  for (let index = 1; index < polyline.length; index += 1) {
    const start = polyline[index - 1];
    const end = polyline[index];
    const segmentLengthM = routePointDistanceM(start, end);
    if (segmentLengthM <= 0) {
      continue;
    }

    const meanLatitudeRadians = (((start.latitude + end.latitude + point.latitude) / 3) * Math.PI) / 180;
    const metersPerLongitudeDegree = metersPerLatitudeDegree * Math.max(0.01, Math.cos(meanLatitudeRadians));
    const segmentX = (end.longitude - start.longitude) * metersPerLongitudeDegree;
    const segmentY = (end.latitude - start.latitude) * metersPerLatitudeDegree;
    const pointX = (point.longitude - start.longitude) * metersPerLongitudeDegree;
    const pointY = (point.latitude - start.latitude) * metersPerLatitudeDegree;
    const segmentNormSquared = segmentX * segmentX + segmentY * segmentY;
    const fraction = segmentNormSquared > 0
      ? Math.min(1, Math.max(0, (pointX * segmentX + pointY * segmentY) / segmentNormSquared))
      : 0;
    const projectedPoint: RoutePoint = {
      latitude: start.latitude + (end.latitude - start.latitude) * fraction,
      longitude: start.longitude + (end.longitude - start.longitude) * fraction
    };
    const candidate = {
      distanceToRouteM: routePointDistanceM(point, projectedPoint),
      distanceFromStartM: traversedM + segmentLengthM * fraction
    };
    if (!best || candidate.distanceToRouteM < best.distanceToRouteM) {
      best = candidate;
    }
    traversedM += segmentLengthM;
  }

  return best;
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

  notifyGatewaySessionInvalid(response);
  const payload = await parseJson(response);
  if (!response.ok) {
    const parts = errorParts(payload);
    throw new NavigationApiError(navigationErrorMessage(payload, `${fallbackMessage} (${response.status})`), response.status, parts.code, parts.reason);
  }

  return payload;
}

export function parseWalkingRoutePayload(payload: unknown, request: WalkingRouteRequest): WalkingRouteResponse {
  if (!toRoutePoint(request.origin) || !toRoutePoint(request.destination)) {
    throw new NavigationApiError("출발지 또는 목적지 좌표를 확인해 주세요.", 0, "invalid_request");
  }
  if (!isRecord(payload) || payload.schema_version !== "walksafe.walking_route.v1") {
    throw new NavigationApiError("길안내 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  const summary = isRecord(payload.summary) ? payload.summary : {};
  const distance = finiteNumber(summary.distance_m);
  const duration = finiteNumber(summary.duration_s);
  const rawPolyline = Array.isArray(payload.polyline) ? payload.polyline : null;
  const parsedPolyline = rawPolyline?.map(toRoutePoint) ?? [];
  const rawSteps = Array.isArray(payload.steps) ? payload.steps : null;
  const parsedSteps = rawSteps?.map(toRouteStep) ?? [];
  const rawGuidePoints = Array.isArray(payload.guide_points) ? payload.guide_points : null;
  const parsedGuidePoints = rawGuidePoints?.map(toGuidePoint) ?? [];
  const provider = payload.provider === "tmap_pedestrian" ? payload.provider : null;
  const priority =
    payload.priority === "RECOMMEND" ||
    payload.priority === "MAIN_STREET" ||
    payload.priority === "DISTANCE" ||
    payload.priority === "STAIR_AVOID"
      ? payload.priority
      : null;
  const resultCode = finiteNumber(payload.provider_result_code);
  const resultMessage = typeof payload.provider_result_message === "string" ? payload.provider_result_message : null;
  const providerRouteId = optionalString(payload.provider_route_id);

  if (
    provider === null ||
    priority === null ||
    distance === null ||
    !Number.isInteger(distance) ||
    distance <= 0 ||
    duration === null ||
    !Number.isInteger(duration) ||
    duration <= 0 ||
    resultCode !== 0 ||
    !Number.isInteger(resultCode) ||
    resultMessage === null ||
    providerRouteId === undefined ||
    rawPolyline === null ||
    parsedPolyline.length < 2 ||
    parsedPolyline.some((point) => point === null) ||
    rawSteps === null ||
    parsedSteps.length === 0 ||
    parsedSteps.some((step) => step === null) ||
    rawGuidePoints === null ||
    parsedGuidePoints.length === 0 ||
    parsedGuidePoints.some((point) => point === null)
  ) {
    throw new NavigationApiError("길안내 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  const polyline = parsedPolyline as RoutePoint[];
  const steps = parsedSteps as WalkingRouteStep[];
  const guidePoints = parsedGuidePoints as WalkingRouteGuidePoint[];
  if (
    priority !== (request.priority ?? "STAIR_AVOID") ||
    steps.some((step, index) => step.index !== index) ||
    guidePoints.some(
      (point, index) =>
        point.index !== index ||
        (point.distance_from_start_m !== null && point.distance_from_start_m !== undefined && point.distance_from_start_m > distance) ||
        (point.remaining_distance_m !== null && point.remaining_distance_m !== undefined && point.remaining_distance_m > distance)
    ) ||
    routePointDistanceM(request.origin, polyline[0]) > ROUTE_ENDPOINT_MAX_DISTANCE_M ||
    routePointDistanceM(request.destination, polyline[polyline.length - 1]) > ROUTE_ENDPOINT_MAX_DISTANCE_M
  ) {
    throw new NavigationApiError("길안내 경로가 요청한 출발지와 목적지에 연결되지 않습니다.", 0, "invalid_route_geometry");
  }

  const geometryDistanceM = polyline.slice(1).reduce(
    (total, point, index) => total + routePointDistanceM(polyline[index], point),
    0
  );
  const minimumSummaryM = geometryDistanceM * ROUTE_SUMMARY_GEOMETRY_MIN_RATIO - ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M;
  const maximumSummaryM = geometryDistanceM * ROUTE_SUMMARY_GEOMETRY_MAX_RATIO + ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M;
  if (geometryDistanceM <= 0 || distance < minimumSummaryM || distance > maximumSummaryM) {
    throw new NavigationApiError("길안내 거리와 경로 형상이 일치하지 않습니다.", 0, "invalid_route_geometry");
  }

  const guideDistanceToleranceM = Math.max(
    GUIDE_DISTANCE_ABSOLUTE_TOLERANCE_M,
    distance * GUIDE_DISTANCE_RELATIVE_TOLERANCE
  );
  let previousProjectedDistanceM = -GUIDE_MONOTONIC_TOLERANCE_M;
  let previousDeclaredDistanceM = -GUIDE_MONOTONIC_TOLERANCE_M;
  for (const guide of guidePoints) {
    const projection = projectGuidePointToPolyline(guide.point, polyline);
    if (!projection || projection.distanceToRouteM > GUIDE_POINT_MAX_ROUTE_DISTANCE_M) {
      throw new NavigationApiError("회전 안내점이 보행 경로에서 벗어났습니다.", 0, "invalid_route_geometry");
    }

    const projectedSummaryDistanceM = (projection.distanceFromStartM * distance) / geometryDistanceM;
    const declaredDistanceM =
      guide.distance_from_start_m ??
      (guide.remaining_distance_m === null || guide.remaining_distance_m === undefined
        ? projectedSummaryDistanceM
        : distance - guide.remaining_distance_m);
    if (
      Math.abs(declaredDistanceM - projectedSummaryDistanceM) > guideDistanceToleranceM ||
      (guide.distance_from_start_m !== null &&
        guide.distance_from_start_m !== undefined &&
        guide.remaining_distance_m !== null &&
        guide.remaining_distance_m !== undefined &&
        Math.abs(guide.distance_from_start_m + guide.remaining_distance_m - distance) > guideDistanceToleranceM) ||
      projectedSummaryDistanceM + GUIDE_MONOTONIC_TOLERANCE_M < previousProjectedDistanceM ||
      declaredDistanceM + GUIDE_MONOTONIC_TOLERANCE_M < previousDeclaredDistanceM
    ) {
      throw new NavigationApiError("회전 안내점의 순서 또는 누적 거리가 보행 경로와 일치하지 않습니다.", 0, "invalid_route_geometry");
    }
    previousProjectedDistanceM = projectedSummaryDistanceM;
    previousDeclaredDistanceM = declaredDistanceM;
  }

  return {
    schema_version: "walksafe.walking_route.v1",
    provider,
    provider_route_id: providerRouteId,
    priority,
    summary: { distance_m: distance, duration_s: duration },
    polyline,
    steps,
    guide_points: guidePoints,
    provider_result_code: resultCode,
    provider_result_message: resultMessage
  };
}


export function parseDestinationSearchPayload(payload: unknown, expectedQuery: string, limit: number): DestinationSearchResponse {
  if (!isRecord(payload) || payload.schema_version !== "walksafe.destination_search.v1") {
    throw new NavigationApiError("목적지 검색 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  const provider = payload.provider === "tmap_poi" ? payload.provider : null;
  const query = typeof payload.query === "string" ? payload.query : null;
  const rawResults = Array.isArray(payload.results) ? payload.results : null;
  const parsedResults = rawResults?.map(toDestinationSearchResult) ?? [];
  const parsedIds = parsedResults.flatMap((result) => (result ? [result.id] : []));
  const normalizedExpectedQuery = expectedQuery.trim().replace(/\s+/g, " ");

  if (
    provider === null ||
    query === null ||
    query !== normalizedExpectedQuery ||
    rawResults === null ||
    parsedResults.some((result) => result === null) ||
    new Set(parsedIds).size !== parsedIds.length ||
    parsedResults.length > limit
  ) {
    throw new NavigationApiError("목적지 검색 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return {
    schema_version: "walksafe.destination_search.v1",
    provider,
    query,
    results: parsedResults as DestinationSearchResult[]
  };
}

export async function fetchWalkingRoute(
  request: WalkingRouteRequest,
  options: WalkingRouteRequestOptions = {}
): Promise<WalkingRouteResponse> {
  assertOnline();
  if (
    !toRoutePoint(request.origin) ||
    !toRoutePoint(request.destination) ||
    (request.waypoints?.some((point) => !toRoutePoint(point)) ?? false)
  ) {
    throw new NavigationApiError("출발지 또는 목적지 좌표를 확인해 주세요.", 0, "invalid_request");
  }
  const payload = await fetchWithTimeout(
    `${NAVIGATION_API_BASE}/navigation/walking`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request)
    },
    "도보 길안내 요청 실패",
    options.signal
  );

  return parseWalkingRoutePayload(payload, request);
}

export async function searchDestinations(
  query: string,
  optionsOrLimit: number | DestinationSearchOptions = 5
): Promise<DestinationSearchResponse> {
  assertOnline();
  const options = typeof optionsOrLimit === "number" ? { limit: optionsOrLimit } : optionsOrLimit;
  const limit = options.limit ?? 5;
  if (!query.trim() || !Number.isInteger(limit) || limit < 1 || limit > 10 || (options.origin && !toRoutePoint(options.origin))) {
    throw new NavigationApiError("목적지 검색 조건을 확인해 주세요.", 0, "invalid_request");
  }
  const params = new URLSearchParams({ query, limit: String(limit) });
  if (options.origin) {
    params.set("origin_lat", String(options.origin.latitude));
    params.set("origin_lng", String(options.origin.longitude));
  }
  const payload = await fetchWithTimeout(
    `${NAVIGATION_API_BASE}/navigation/destinations/search?${params.toString()}`,
    { method: "GET" },
    "목적지 검색 요청 실패",
    options.signal
  );

  return parseDestinationSearchPayload(payload, query, limit);
}
