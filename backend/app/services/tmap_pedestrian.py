"""TMAP POI search and pedestrian-route adapter.

Provider-specific payloads are normalized to WalkSafe schemas. The route
adapter preserves guide points and derives bearings from the returned
polyline; it does not fuse public accessibility datasets or correct GPS.
"""

from __future__ import annotations

import asyncio
from hashlib import sha256
import json
from math import isfinite
import re
from threading import Lock
from time import monotonic
from typing import Any
from urllib.parse import quote

import httpx
from pydantic import ValidationError

from backend.app.config import Settings
from backend.app.schemas import (
    DestinationSearchResponse,
    DestinationSearchResult,
    RoutePoint,
    WalkingRouteGuidePoint,
    WalkingRoutePriority,
    WalkingRouteRequest,
    WalkingRouteResponse,
    WalkingRouteStep,
    WalkingRouteSummary,
)
from backend.app.services.walking_route_sanity import (
    WalkingRouteSanityError,
    validate_normalized_walking_route,
)




class TmapPoiSearchError(RuntimeError):
    def __init__(
        self,
        *,
        code: str,
        message: str,
        http_status: int = 502,
        provider_status: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status
        self.provider_status = provider_status
        self.provider_result_code = None


class TmapPedestrianRouteError(RuntimeError):
    def __init__(
        self,
        *,
        code: str,
        message: str,
        http_status: int = 502,
        provider_status: int | None = None,
        provider_result_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status
        self.provider_status = provider_status
        self.provider_result_code = provider_result_code


TMAP_SEARCH_OPTIONS: dict[WalkingRoutePriority, str] = {
    "RECOMMEND": "0",
    "MAIN_STREET": "4",
    "DISTANCE": "10",
    "STAIR_AVOID": "30",
}

USELESS_GUIDE_DESCRIPTION_RE = re.compile(r"^\s*,?\s*\d+(?:\.\d+)?\s*m\s*$", re.IGNORECASE)
KOREAN_DESTINATION_ACTION_RE = re.compile(
    r"\s*(?:안내해줘|안내해|가자|목적지\s*설정(?:해)?|설정(?:해)?|지정(?:해)?)\s*$"
)
TMAP_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
TMAP_MAX_FEATURES = 2048
TMAP_MAX_POLYLINE_POINTS = 8192
TMAP_MAX_STEPS = 1024
TMAP_MAX_GUIDE_POINTS = 1024
TMAP_MAX_POI_RESULTS = 100
TMAP_MAX_STRING_LENGTH = 2048

MOCK_POI_ITEMS: tuple[dict[str, Any], ...] = (
    {
        "id": "mock-seoul-station",
        "name": "서울역",
        "latitude": 37.5546788,
        "longitude": 126.9706069,
        "address": "서울 중구 봉래동2가",
        "road_address": "서울 중구 한강대로 405",
        "category": "교통",
        "aliases": ("서울역", "서울역으로", "서울역까지"),
    },
    {
        "id": "mock-city-hall",
        "name": "시청역",
        "latitude": 37.5657037,
        "longitude": 126.9768616,
        "address": "서울 중구 정동",
        "road_address": "서울 중구 세종대로 101",
        "category": "교통",
        "aliases": ("시청", "시청역", "서울시청"),
    },
    {
        "id": "mock-gangnam-station",
        "name": "강남역",
        "latitude": 37.497952,
        "longitude": 127.027619,
        "address": "서울 강남구 역삼동",
        "road_address": "서울 강남구 강남대로 396",
        "category": "교통",
        "aliases": ("강남", "강남역"),
    },
)

_TMAP_SUCCESS_LOCK = Lock()
_TMAP_SUCCESS_AT: dict[str, float] = {}


class _TmapResponseLimitError(RuntimeError):
    pass


def _ensure_tmap_deadline(deadline_at: float | None) -> None:
    if deadline_at is not None and monotonic() >= deadline_at:
        raise TimeoutError("TMAP request exceeded its wall-clock deadline")


def _json_has_oversized_string(
    payload: Any,
    *,
    deadline_at: float | None = None,
) -> bool:
    pending = [payload]
    inspected = 0
    while pending:
        value = pending.pop()
        inspected += 1
        if inspected % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        if isinstance(value, str):
            if len(value) > TMAP_MAX_STRING_LENGTH:
                return True
        elif isinstance(value, dict):
            if any(
                isinstance(key, str) and len(key) > TMAP_MAX_STRING_LENGTH
                for key in value
            ):
                return True
            pending.extend(value.values())
        elif isinstance(value, list):
            pending.extend(value)
    _ensure_tmap_deadline(deadline_at)
    return False


def _load_tmap_json(body: bytes, *, deadline_at: float) -> Any:
    _ensure_tmap_deadline(deadline_at)
    payload = json.loads(body)
    _ensure_tmap_deadline(deadline_at)
    if _json_has_oversized_string(payload, deadline_at=deadline_at):
        raise _TmapResponseLimitError("TMAP response contains an oversized string")
    return payload


async def _stream_tmap_response(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    deadline_at: float,
    params: dict[str, object],
    headers: dict[str, str],
    json_body: dict[str, object] | None = None,
) -> tuple[int, bytes]:
    _ensure_tmap_deadline(deadline_at)
    request_headers = {**headers, "Accept-Encoding": "identity"}
    request_kwargs: dict[str, Any] = {"params": params, "headers": request_headers}
    if json_body is not None:
        request_kwargs["json"] = json_body
    async with asyncio.timeout(max(deadline_at - monotonic(), 0.0)):
        async with client.stream(method, url, **request_kwargs) as response:
            content_encoding = response.headers.get("content-encoding", "").strip().lower()
            if content_encoding not in {"", "identity"}:
                raise _TmapResponseLimitError(
                    "TMAP response Content-Encoding is not supported"
                )
            declared_length = response.headers.get("content-length")
            if declared_length is not None:
                try:
                    parsed_length = int(declared_length)
                except ValueError as exc:
                    raise _TmapResponseLimitError(
                        "TMAP response Content-Length is invalid"
                    ) from exc
                if parsed_length < 0 or parsed_length > TMAP_MAX_RESPONSE_BYTES:
                    raise _TmapResponseLimitError(
                        "TMAP response Content-Length exceeds the hard cap"
                    )

            if response.is_stream_consumed:
                content = response.content
                if len(content) > TMAP_MAX_RESPONSE_BYTES:
                    raise _TmapResponseLimitError(
                        "TMAP buffered response exceeds the hard cap"
                    )
                _ensure_tmap_deadline(deadline_at)
                return response.status_code, content

            body = bytearray()
            async for chunk in response.aiter_raw():
                _ensure_tmap_deadline(deadline_at)
                if len(body) + len(chunk) > TMAP_MAX_RESPONSE_BYTES:
                    raise _TmapResponseLimitError(
                        "TMAP streamed response exceeds the hard cap"
                    )
                body.extend(chunk)
            _ensure_tmap_deadline(deadline_at)
            return response.status_code, bytes(body)


def _record_tmap_success(dependency: str, *, now: float | None = None) -> None:
    if dependency not in {"route", "poi"}:
        raise ValueError("unsupported TMAP dependency")
    with _TMAP_SUCCESS_LOCK:
        _TMAP_SUCCESS_AT[dependency] = monotonic() if now is None else now


def reset_tmap_success_cache() -> None:
    """Clear process-local evidence; intended for startup and deterministic tests."""
    with _TMAP_SUCCESS_LOCK:
        _TMAP_SUCCESS_AT.clear()


def tmap_dependency_readiness(
    max_age_seconds: float,
    *,
    now: float | None = None,
) -> dict[str, object]:
    current = monotonic() if now is None else now
    with _TMAP_SUCCESS_LOCK:
        successes = dict(_TMAP_SUCCESS_AT)
    ages = {
        dependency: max(0.0, current - timestamp)
        for dependency, timestamp in successes.items()
    }
    fresh = {
        dependency: dependency in ages and ages[dependency] <= max_age_seconds
        for dependency in ("route", "poi")
    }
    ready = all(fresh.values())
    return {
        "ready": ready,
        "provider": "tmap_pedestrian",
        "evidence": "recent_success" if ready else "none",
        "route_recent": fresh["route"],
        "poi_recent": fresh["poi"],
    }


def _int_field(payload: dict[str, Any], name: str) -> int:
    value = payload.get(name)
    if isinstance(value, bool):
        return 0
    if isinstance(value, (int, float, str)):
        try:
            return max(int(value), 0)
        except (ValueError, OverflowError):
            return 0
    return 0


def _str_field(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    return value if isinstance(value, str) and value else None


def _clean_guide_instruction(description: str | None) -> str | None:
    if description is None:
        return None
    instruction = description.strip()
    if not instruction or USELESS_GUIDE_DESCRIPTION_RE.fullmatch(instruction):
        return None
    return instruction


def normalize_destination_query(query: str) -> str:
    normalized = " ".join(query.split()).strip()
    action = KOREAN_DESTINATION_ACTION_RE.search(normalized)
    if action:
        normalized = normalized[: action.start()].strip()
        if normalized.endswith("까지"):
            normalized = normalized[:-2].strip()
        elif normalized.endswith("으로"):
            normalized = normalized[:-2].strip()
        elif normalized.endswith("로로"):
            normalized = normalized[:-1].strip()
        normalized = re.sub(r"^목적지(?:를|을)?\s*", "", normalized).strip()
    return normalized


def _haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> int:
    from math import atan2, cos, radians, sin, sqrt

    earth_radius_m = 6371000
    d_lat = radians(lat2 - lat1)
    d_lng = radians(lng2 - lng1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lng / 2) ** 2
    return int(round(earth_radius_m * 2 * atan2(sqrt(a), sqrt(1 - a))))


def _bearing_deg(start: RoutePoint, end: RoutePoint) -> float:
    from math import atan2, cos, degrees, radians, sin

    start_lat = radians(start.latitude)
    end_lat = radians(end.latitude)
    delta_lng = radians(end.longitude - start.longitude)
    x = sin(delta_lng) * cos(end_lat)
    y = cos(start_lat) * sin(end_lat) - sin(start_lat) * cos(end_lat) * cos(delta_lng)
    return (degrees(atan2(x, y)) + 360.0) % 360.0


def _route_bearing_for_point(
    point: RoutePoint,
    polyline: list[RoutePoint],
    *,
    deadline_at: float | None = None,
) -> float | None:
    if len(polyline) < 2:
        return None
    for index, route_point in enumerate(polyline[:-1]):
        if index % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        if abs(route_point.latitude - point.latitude) <= 0.000001 and abs(route_point.longitude - point.longitude) <= 0.000001:
            return _bearing_deg(route_point, polyline[index + 1])

    nearest_index = 0
    nearest_distance = _haversine_m(
        point.latitude,
        point.longitude,
        polyline[0].latitude,
        polyline[0].longitude,
    )
    for index, route_point in enumerate(polyline[1:-1], start=1):
        if index % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        distance = _haversine_m(
            point.latitude,
            point.longitude,
            route_point.latitude,
            route_point.longitude,
        )
        if distance < nearest_distance:
            nearest_index = index
            nearest_distance = distance
    if nearest_distance > 20:
        return None
    return _bearing_deg(polyline[nearest_index], polyline[nearest_index + 1])


def _encoded_name(point: RoutePoint, fallback: str) -> str:
    return quote(point.name or fallback, safe="")


def _pass_list(points: list[RoutePoint]) -> str:
    return "_".join(f"{point.longitude},{point.latitude}" for point in points)


def _route_point_from_coordinate(coordinate: object) -> RoutePoint | None:
    if not isinstance(coordinate, list) or len(coordinate) < 2:
        return None
    longitude = coordinate[0]
    latitude = coordinate[1]
    if not isinstance(longitude, (int, float)) or not isinstance(latitude, (int, float)):
        return None
    return RoutePoint(latitude=float(latitude), longitude=float(longitude))


def _line_points(
    coordinates: object,
    *,
    deadline_at: float | None = None,
) -> list[RoutePoint]:
    if not isinstance(coordinates, list):
        return []
    if len(coordinates) > TMAP_MAX_POLYLINE_POINTS:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route polyline exceeds the safe point limit.",
        )

    points: list[RoutePoint] = []
    for index, coordinate in enumerate(coordinates):
        if index % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        point = _route_point_from_coordinate(coordinate)
        if point is not None:
            points.append(point)
    return points


def _append_unique_point(points: list[RoutePoint], point: RoutePoint) -> None:
    if points and points[-1].latitude == point.latitude and points[-1].longitude == point.longitude:
        return
    points.append(point)


def _normalize_tmap_response(
    payload: Any,
    request: WalkingRouteRequest,
    *,
    deadline_at: float | None = None,
) -> WalkingRouteResponse:
    if not isinstance(payload, dict):
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not JSON object.")

    if payload.get("type") != "FeatureCollection":
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not GeoJSON FeatureCollection.")

    features = payload.get("features")
    if not isinstance(features, list) or not features:
        raise TmapPedestrianRouteError(code="route_unavailable", message="TMAP pedestrian route response has no route features.", http_status=422)
    if len(features) > TMAP_MAX_FEATURES:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route response exceeds the safe feature limit.",
        )
    if _json_has_oversized_string(payload, deadline_at=deadline_at):
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route response contains an oversized string.",
        )

    summary_distance_m = 0
    summary_duration_s = 0
    steps: list[WalkingRouteStep] = []
    guide_points: list[WalkingRouteGuidePoint] = []
    polyline: list[RoutePoint] = []
    step_index = 0
    guide_index = 0
    distance_from_start_m = 0
    step_point_count = 0

    # TMAP interleaves Point guide features and LineString route segments.
    # Segment distance advances the running position used by later guide points.
    for feature_index, feature in enumerate(features):
        if feature_index % 64 == 0:
            _ensure_tmap_deadline(deadline_at)
        if not isinstance(feature, dict):
            continue
        geometry = feature.get("geometry") if isinstance(feature.get("geometry"), dict) else {}
        properties = feature.get("properties") if isinstance(feature.get("properties"), dict) else {}

        total_distance = _int_field(properties, "totalDistance")
        total_time = _int_field(properties, "totalTime")
        if total_distance:
            summary_distance_m = total_distance
        if total_time:
            summary_duration_s = total_time

        if geometry.get("type") == "Point":
            point = _route_point_from_coordinate(geometry.get("coordinates"))
            if point is None:
                continue
            if len(guide_points) >= TMAP_MAX_GUIDE_POINTS:
                raise TmapPedestrianRouteError(
                    code="invalid_tmap_response",
                    message="TMAP pedestrian route response exceeds the safe guide limit.",
                )
            guide_points.append(
                WalkingRouteGuidePoint(
                    index=guide_index,
                    point=point,
                    instruction=_clean_guide_instruction(_str_field(properties, "description")),
                    turn_type=_int_field(properties, "turnType") if "turnType" in properties else None,
                    point_type=_str_field(properties, "pointType"),
                    facility_type=_int_field(properties, "facilityType") if "facilityType" in properties else None,
                    distance_from_start_m=distance_from_start_m,
                    remaining_distance_m=max(summary_distance_m - distance_from_start_m, 0) if summary_distance_m else None,
                )
            )
            guide_index += 1
            continue

        if geometry.get("type") != "LineString":
            continue

        points = _line_points(
            geometry.get("coordinates"),
            deadline_at=deadline_at,
        )
        if not points:
            continue
        step_point_count += len(points)
        if step_point_count > TMAP_MAX_POLYLINE_POINTS:
            raise TmapPedestrianRouteError(
                code="invalid_tmap_response",
                message="TMAP pedestrian route response exceeds the safe polyline limit.",
            )
        for point in points:
            _append_unique_point(polyline, point)
            if len(polyline) > TMAP_MAX_POLYLINE_POINTS:
                raise TmapPedestrianRouteError(
                    code="invalid_tmap_response",
                    message="TMAP pedestrian route response exceeds the safe polyline limit.",
                )

        distance_m = _int_field(properties, "distance")
        duration_s = _int_field(properties, "time")
        if len(steps) >= TMAP_MAX_STEPS:
            raise TmapPedestrianRouteError(
                code="invalid_tmap_response",
                message="TMAP pedestrian route response exceeds the safe step limit.",
            )
        steps.append(
            WalkingRouteStep(
                index=step_index,
                distance_m=distance_m,
                duration_s=duration_s,
                points=points,
                instruction=_clean_guide_instruction(_str_field(properties, "description")),
                road_name=_str_field(properties, "name"),
                turn_type=None,
                facility_type=_int_field(properties, "facilityType") if "facilityType" in properties else None,
            )
        )
        step_index += 1
        distance_from_start_m += distance_m

    if not summary_distance_m:
        summary_distance_m = sum(step.distance_m for step in steps)
    if not summary_duration_s:
        summary_duration_s = sum(step.duration_s for step in steps)
    try:
        validate_normalized_walking_route(
            origin=request.origin,
            destination=request.destination,
            polyline=polyline,
            distance_m=summary_distance_m,
            duration_s=summary_duration_s,
        )
    except WalkingRouteSanityError as exc:
        raise TmapPedestrianRouteError(
            code=exc.code,
            message=f"TMAP {exc.message}",
        ) from exc
    if request.priority == "STAIR_AVOID" and (
        any(step.facility_type == 17 for step in steps)
        or any(
            guide.facility_type == 17 or guide.turn_type in {127, 129}
            for guide in guide_points
        )
    ):
        raise TmapPedestrianRouteError(
            code="route_stairs_present",
            message="TMAP returned stairs despite the stair-exclusion request.",
            http_status=422,
        )
    if summary_distance_m:
        for guide_point in guide_points:
            if guide_point.distance_from_start_m is not None and guide_point.remaining_distance_m is None:
                guide_point.remaining_distance_m = max(summary_distance_m - guide_point.distance_from_start_m, 0)
    normalized_guides: list[WalkingRouteGuidePoint] = []
    for guide_point in guide_points:
        _ensure_tmap_deadline(deadline_at)
        normalized_guides.append(
            guide_point if guide_point.bearing_deg is not None else guide_point.model_copy(
                update={
                    "bearing_deg": _route_bearing_for_point(
                        guide_point.point,
                        polyline,
                        deadline_at=deadline_at,
                    )
                },
            )
        )
    _ensure_tmap_deadline(deadline_at)

    return WalkingRouteResponse(
        schema_version="walksafe.walking_route.v1",
        provider="tmap_pedestrian",
        provider_route_id=None,
        priority=request.priority,
        summary=WalkingRouteSummary(distance_m=summary_distance_m, duration_s=summary_duration_s),
        polyline=polyline,
        steps=steps,
        guide_points=normalized_guides,
        provider_result_code=0,
        provider_result_message="OK",
    )


async def fetch_tmap_pedestrian_route(
    request: WalkingRouteRequest,
    settings: Settings,
    *,
    http_client: httpx.AsyncClient | None = None,
) -> WalkingRouteResponse:
    if not settings.tmap_app_key:
        raise TmapPedestrianRouteError(
            code="tmap_app_key_missing",
            message="TMAP appKey is not configured.",
            http_status=503,
        )

    body: dict[str, object] = {
        "startX": request.origin.longitude,
        "startY": request.origin.latitude,
        "endX": request.destination.longitude,
        "endY": request.destination.latitude,
        "reqCoordType": "WGS84GEO",
        "resCoordType": "WGS84GEO",
        "startName": _encoded_name(request.origin, "출발"),
        "endName": _encoded_name(request.destination, "도착"),
        "searchOption": TMAP_SEARCH_OPTIONS[request.priority],
        "speed": int(round(request.default_speed or settings.tmap_pedestrian_speed_kmh)),
        "sort": "index",
    }
    if request.waypoints:
        body["passList"] = _pass_list(request.waypoints)

    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "appKey": settings.tmap_app_key,
    }
    params = {"version": settings.tmap_pedestrian_api_version}

    deadline_at = monotonic() + settings.tmap_timeout_seconds
    should_close = http_client is None
    client = http_client or httpx.AsyncClient(timeout=settings.tmap_timeout_seconds)
    try:
        provider_status, response_body = await _stream_tmap_response(
            client,
            "POST",
            settings.tmap_pedestrian_route_url,
            deadline_at=deadline_at,
            params=params,
            headers=headers,
            json_body=body,
        )
    except (TimeoutError, httpx.TimeoutException) as exc:
        raise TmapPedestrianRouteError(code="tmap_timeout", message="TMAP pedestrian route request timed out.", http_status=504) from exc
    except _TmapResponseLimitError as exc:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route response exceeds the safe resource limit.",
        ) from exc
    except httpx.HTTPError as exc:
        raise TmapPedestrianRouteError(code="tmap_network_error", message="TMAP pedestrian route request failed.") from exc
    finally:
        if should_close:
            await client.aclose()

    if provider_status < 200 or provider_status >= 300:
        provider_code = None
        try:
            error_payload = _load_tmap_json(response_body, deadline_at=deadline_at)
        except _TmapResponseLimitError as exc:
            raise TmapPedestrianRouteError(
                code="invalid_tmap_response",
                message="TMAP pedestrian route response exceeds the safe resource limit.",
            ) from exc
        except TimeoutError as exc:
            raise TmapPedestrianRouteError(
                code="tmap_timeout",
                message="TMAP pedestrian route request timed out.",
                http_status=504,
            ) from exc
        except (ValueError, RecursionError):
            error_payload = None
        if isinstance(error_payload, dict) and isinstance(error_payload.get("error"), dict):
            error_detail = error_payload["error"]
            if isinstance(error_detail.get("code"), str):
                provider_code = error_detail["code"]
        error_code = "tmap_provider_error"
        error_message = "TMAP pedestrian route provider returned an error."
        error_http_status = 502
        if provider_status in (401, 403) or provider_code == "INVALID_API_KEY":
            error_code = "tmap_invalid_api_key"
            error_message = "TMAP appKey is invalid or not allowed to use pedestrian route API."
        elif provider_status == 429:
            error_code = "tmap_rate_limited"
            error_message = "TMAP pedestrian route request limit was reached."
            error_http_status = 503

        raise TmapPedestrianRouteError(
            code=error_code,
            message=error_message,
            http_status=error_http_status,
            provider_status=provider_status,
        )

    try:
        payload = _load_tmap_json(response_body, deadline_at=deadline_at)
    except _TmapResponseLimitError as exc:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route response exceeds the safe resource limit.",
        ) from exc
    except TimeoutError as exc:
        raise TmapPedestrianRouteError(
            code="tmap_timeout",
            message="TMAP pedestrian route request timed out.",
            http_status=504,
        ) from exc
    except (ValueError, RecursionError) as exc:
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not valid JSON.") from exc

    try:
        normalized = _normalize_tmap_response(
            payload,
            request,
            deadline_at=deadline_at,
        )
        _ensure_tmap_deadline(deadline_at)
        _record_tmap_success("route")
        return normalized
    except TimeoutError as exc:
        raise TmapPedestrianRouteError(
            code="tmap_timeout",
            message="TMAP pedestrian route request timed out.",
            http_status=504,
        ) from exc
    except ValidationError as exc:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route response violates the WalkSafe schema.",
        ) from exc


def _poi_text(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _poi_float(payload: dict[str, Any], *names: str) -> float | None:
    for name in names:
        value = payload.get(name)
        if value in (None, "") or isinstance(value, bool):
            continue
        try:
            number = float(value)
        except (TypeError, ValueError):
            continue
        if isfinite(number):
            return number
    return None


def _poi_distance_m(payload: dict[str, Any]) -> int | None:
    radius_km = _poi_float(payload, "radius")
    if radius_km is None or radius_km < 0:
        return None
    try:
        return int(round(radius_km * 1000))
    except (ValueError, OverflowError):
        return None


def _join_address(*parts: str | None) -> str | None:
    text = " ".join(part for part in parts if part)
    return text or None


def _join_address_number(first: str | None, second: str | None) -> str | None:
    if second in (None, "", "0"):
        return first
    return _join_address(first, second)


def _normalize_poi_search_response(
    payload: Any,
    query: str,
    *,
    deadline_at: float | None = None,
) -> DestinationSearchResponse:
    if not isinstance(payload, dict):
        raise TmapPoiSearchError(code="invalid_tmap_response", message="TMAP POI search response is not JSON object.")
    if _json_has_oversized_string(payload, deadline_at=deadline_at):
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response contains an oversized string.",
        )

    search_info = payload.get("searchPoiInfo")
    if not isinstance(search_info, dict):
        raise TmapPoiSearchError(code="invalid_tmap_response", message="TMAP POI search response has no searchPoiInfo.")

    pois = search_info.get("pois")
    raw_items = pois.get("poi") if isinstance(pois, dict) else []
    if isinstance(raw_items, dict):
        items = [raw_items]
    elif isinstance(raw_items, list):
        items = raw_items
    else:
        items = []
    if len(items) > TMAP_MAX_POI_RESULTS:
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response exceeds the safe result limit.",
        )

    results: list[DestinationSearchResult] = []
    seen_candidate_ids: set[str] = set()
    for item_index, item in enumerate(items):
        if item_index % 32 == 0:
            _ensure_tmap_deadline(deadline_at)
        if not isinstance(item, dict):
            continue

        name = _poi_text(item, "name")
        longitude = _poi_float(item, "noorLon", "frontLon", "lon", "newLon")
        latitude = _poi_float(item, "noorLat", "frontLat", "lat", "newLat")
        if not name or longitude is None or latitude is None:
            continue
        if latitude < -90 or latitude > 90 or longitude < -180 or longitude > 180:
            continue

        road_address = _join_address(
            _poi_text(item, "upperRoadAddrName"),
            _poi_text(item, "middleRoadAddrName"),
            _poi_text(item, "lowerRoadAddrName"),
            _poi_text(item, "roadName"),
            _join_address_number(_poi_text(item, "firstBuildNo"), _poi_text(item, "secondBuildNo")),
        )
        address = _join_address(
            _poi_text(item, "upperAddrName"),
            _poi_text(item, "middleAddrName"),
            _poi_text(item, "lowerAddrName"),
            _poi_text(item, "detailAddrName"),
            _join_address_number(_poi_text(item, "firstNo"), _poi_text(item, "secondNo")),
        )

        destination = DestinationSearchResult(
                id=_poi_text(item, "id") or _poi_text(item, "pkey") or f"{latitude},{longitude}",
                name=name,
                point=RoutePoint(latitude=latitude, longitude=longitude, name=name),
                address=address,
                road_address=road_address,
                category=(
                    _poi_text(item, "bizCatName")
                    or _poi_text(item, "catName")
                    or _poi_text(item, "lowerBizName")
                    or _poi_text(item, "middleBizName")
                    or _poi_text(item, "upperBizName")
                    or _poi_text(item, "bizName")
                ),
                result_type="poi",
                distance_m=_poi_distance_m(item),
        )
        # Provider IDs can be shared by distinct entrances or facilities. This is
        # an opaque candidate key, independent of query, ordering and distance.
        identity = (
            destination.id,
            _poi_text(item, "pkey"),
            destination.name,
            destination.point.latitude,
            destination.point.longitude,
            destination.address,
            destination.road_address,
            destination.category,
        )
        identity_bytes = json.dumps(identity, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        destination.id = "tmap-poi:v1:" + sha256(identity_bytes).hexdigest()
        if destination.id in seen_candidate_ids:
            continue
        seen_candidate_ids.add(destination.id)
        results.append(destination)
        if len(results) > TMAP_MAX_POI_RESULTS:
            raise TmapPoiSearchError(
                code="invalid_tmap_response",
                message="TMAP POI search response exceeds the safe result limit.",
            )

    _ensure_tmap_deadline(deadline_at)
    return DestinationSearchResponse(
        schema_version="walksafe.destination_search.v1",
        provider="tmap_poi",
        query=query,
        results=results,
    )


def _mock_poi_search_response(
    query: str,
    *,
    limit: int,
    origin_lat: float | None = None,
    origin_lng: float | None = None,
) -> DestinationSearchResponse:
    normalized_query = normalize_destination_query(query)
    compact_query = normalized_query.replace(" ", "")
    results: list[DestinationSearchResult] = []

    for item in MOCK_POI_ITEMS:
        aliases = tuple(str(alias).replace(" ", "") for alias in item.get("aliases", ()))
        name = str(item["name"])
        name_compact = name.replace(" ", "")
        if compact_query not in aliases and compact_query not in name_compact and not any(compact_query in alias for alias in aliases):
            continue

        latitude = float(item["latitude"])
        longitude = float(item["longitude"])
        distance_m = (
            _haversine_m(origin_lat, origin_lng, latitude, longitude)
            if origin_lat is not None and origin_lng is not None
            else None
        )
        results.append(
            DestinationSearchResult(
                id=str(item["id"]),
                name=name,
                point=RoutePoint(latitude=latitude, longitude=longitude, name=name),
                address=str(item["address"]),
                road_address=str(item["road_address"]),
                category=str(item["category"]),
                result_type="alias" if compact_query in aliases else "poi",
                distance_m=distance_m,
            )
        )

    return DestinationSearchResponse(
        schema_version="walksafe.destination_search.v1",
        provider="tmap_poi",
        query=normalized_query,
        results=sorted(results, key=lambda result: result.distance_m if result.distance_m is not None else 10**9)[:limit],
    )


def _is_named_university_query(query: str) -> bool:
    words = query.split()
    if len(words) > 1 and (len(words) != 2 or words[0] not in ("국립", "공립", "사립")):
        return False
    compact_query = "".join(words)
    if any(marker in compact_query for marker in ("주변", "근처", "인근", "가까운")):
        return False
    match = re.fullmatch(r"([가-힣A-Za-z0-9]+?)(?:대학교|대학|공대)", compact_query)
    return match is not None and match.group(1) not in ("국립", "공립", "사립")


def _is_named_station_query(query: str) -> bool:
    compact_query = "".join(query.split())
    if any(marker in compact_query for marker in ("주변", "근처", "인근", "가까운")):
        return False
    match = re.fullmatch(r"([가-힣A-Za-z0-9]+?)역", compact_query)
    return match is not None and match.group(1) not in (
        "지하철", "전철", "기차", "철도", "고속철도",
    )


async def fetch_tmap_poi_search(
    query: str,
    settings: Settings,
    *,
    limit: int = 5,
    origin_lat: float | None = None,
    origin_lng: float | None = None,
    http_client: httpx.AsyncClient | None = None,
) -> DestinationSearchResponse:
    normalized_query = normalize_destination_query(query)
    if not normalized_query:
        raise TmapPoiSearchError(code="empty_query", message="Destination search query is empty.", http_status=422)
    if (origin_lat is None) != (origin_lng is None):
        raise TmapPoiSearchError(
            code="origin_coordinates_incomplete",
            message="origin_lat and origin_lng must be provided together.",
            http_status=422,
        )
    provider = getattr(settings, "tmap_poi_provider", "live")
    if provider == "mock":
        return _mock_poi_search_response(
            normalized_query,
            limit=max(min(limit, 10), 1),
            origin_lat=origin_lat,
            origin_lng=origin_lng,
        )
    if provider != "live":
        raise TmapPoiSearchError(
            code="tmap_poi_provider_invalid",
            message="Configured destination search provider is not supported.",
            http_status=503,
        )
    if not settings.tmap_app_key:
        raise TmapPoiSearchError(
            code="tmap_app_key_missing",
            message="TMAP appKey is not configured.",
            http_status=503,
        )

    params = {
        "version": settings.tmap_pedestrian_api_version,
        "searchKeyword": normalized_query,
        "searchType": "all",
        "searchtypCd": "A",
        "radius": 0,
        "reqCoordType": "WGS84GEO",
        "resCoordType": "WGS84GEO",
        "page": 1,
        "count": max(min(limit, 10), 1),
        "multiPoint": "N",
        "poiGroupYn": "N",
    }
    if origin_lat is not None and origin_lng is not None:
        # Named institutions and stations need relevance before the bounded provider page.
        # Keep nearby/category and explicitly qualified facility searches unchanged.
        params["searchtypCd"] = "A" if (
            _is_named_university_query(normalized_query) or _is_named_station_query(normalized_query)
        ) else "R"
        params["centerLat"] = origin_lat
        params["centerLon"] = origin_lng
    headers = {"Accept": "application/json", "appKey": settings.tmap_app_key}

    deadline_at = monotonic() + settings.tmap_timeout_seconds
    should_close = http_client is None
    client = http_client or httpx.AsyncClient(timeout=settings.tmap_timeout_seconds)
    try:
        provider_status, response_body = await _stream_tmap_response(
            client,
            "GET",
            settings.tmap_poi_search_url,
            deadline_at=deadline_at,
            params=params,
            headers=headers,
        )
    except (TimeoutError, httpx.TimeoutException) as exc:
        raise TmapPoiSearchError(code="tmap_timeout", message="TMAP POI search request timed out.", http_status=504) from exc
    except _TmapResponseLimitError as exc:
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response exceeds the safe resource limit.",
        ) from exc
    except httpx.HTTPError as exc:
        raise TmapPoiSearchError(code="tmap_network_error", message="TMAP POI search request failed.") from exc
    finally:
        if should_close:
            await client.aclose()

    if provider_status < 200 or provider_status >= 300:
        provider_code = None
        try:
            error_payload = _load_tmap_json(response_body, deadline_at=deadline_at)
        except _TmapResponseLimitError as exc:
            raise TmapPoiSearchError(
                code="invalid_tmap_response",
                message="TMAP POI search response exceeds the safe resource limit.",
            ) from exc
        except TimeoutError as exc:
            raise TmapPoiSearchError(
                code="tmap_timeout",
                message="TMAP POI search request timed out.",
                http_status=504,
            ) from exc
        except (ValueError, RecursionError):
            error_payload = None
        if isinstance(error_payload, dict) and isinstance(error_payload.get("error"), dict):
            error_detail = error_payload["error"]
            if isinstance(error_detail.get("code"), str):
                provider_code = error_detail["code"]
        error_code = "tmap_provider_error"
        error_message = "TMAP POI search provider returned an error."
        error_http_status = 502
        if provider_status in (401, 403) or provider_code == "INVALID_API_KEY":
            error_code = "tmap_invalid_api_key"
            error_message = "TMAP appKey is invalid or not allowed to use POI search API."
        elif provider_status == 429:
            error_code = "tmap_rate_limited"
            error_message = "TMAP POI search request limit was reached."
            error_http_status = 503

        raise TmapPoiSearchError(
            code=error_code,
            message=error_message,
            http_status=error_http_status,
            provider_status=provider_status,
        )

    try:
        payload = _load_tmap_json(response_body, deadline_at=deadline_at)
    except _TmapResponseLimitError as exc:
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response exceeds the safe resource limit.",
        ) from exc
    except TimeoutError as exc:
        raise TmapPoiSearchError(
            code="tmap_timeout",
            message="TMAP POI search request timed out.",
            http_status=504,
        ) from exc
    except (ValueError, RecursionError) as exc:
        raise TmapPoiSearchError(code="invalid_tmap_response", message="TMAP POI search response is not valid JSON.") from exc

    try:
        normalized = _normalize_poi_search_response(
            payload,
            normalized_query,
            deadline_at=deadline_at,
        )
        results = normalized.results[: max(min(limit, 10), 1)]
        for destination in results:
            if origin_lat is None or origin_lng is None:
                destination.distance_m = None
            elif destination.distance_m is None:
                destination.distance_m = _haversine_m(
                    origin_lat,
                    origin_lng,
                    destination.point.latitude,
                    destination.point.longitude,
                )
        result = normalized.model_copy(update={"results": results})
        _ensure_tmap_deadline(deadline_at)
        _record_tmap_success("poi")
        return result
    except TimeoutError as exc:
        raise TmapPoiSearchError(
            code="tmap_timeout",
            message="TMAP POI search request timed out.",
            http_status=504,
        ) from exc
    except ValidationError as exc:
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response violates the WalkSafe schema.",
        ) from exc


async def probe_tmap_dependencies(settings: Settings) -> dict[str, object]:
    """Perform one bounded route and POI request for opt-in deployment readiness."""
    timeout = min(settings.tmap_timeout_seconds, settings.tmap_readiness_probe_timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        request = WalkingRouteRequest(
            origin=RoutePoint(latitude=37.5662952, longitude=126.9779451, name="readiness-start"),
            destination=RoutePoint(latitude=37.5664852, longitude=126.9781451, name="readiness-end"),
            priority="STAIR_AVOID",
        )
        await fetch_tmap_pedestrian_route(request, settings, http_client=client)
        await fetch_tmap_poi_search("서울시청", settings, limit=1, http_client=client)
    return tmap_dependency_readiness(settings.tmap_readiness_success_max_age_seconds)
