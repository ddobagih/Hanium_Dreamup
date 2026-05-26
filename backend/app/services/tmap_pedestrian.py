from __future__ import annotations

import re
from typing import Any
from urllib.parse import quote

import httpx

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
KOREAN_DESTINATION_SUFFIX_RE = re.compile(r"(?:으로|로|까지)?\s*(?:안내해줘|안내해|가자|목적지\s*설정|설정|지정)?\s*$")

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


def _int_field(payload: dict[str, Any], name: str) -> int:
    value = payload.get(name)
    if isinstance(value, bool):
        return 0
    if isinstance(value, (int, float)):
        return max(int(value), 0)
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
    normalized = KOREAN_DESTINATION_SUFFIX_RE.sub("", normalized).strip()
    return normalized or " ".join(query.split()).strip()


def _haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> int:
    from math import atan2, cos, radians, sin, sqrt

    earth_radius_m = 6371000
    d_lat = radians(lat2 - lat1)
    d_lng = radians(lng2 - lng1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lng / 2) ** 2
    return int(round(earth_radius_m * 2 * atan2(sqrt(a), sqrt(1 - a))))


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


def _line_points(coordinates: object) -> list[RoutePoint]:
    if not isinstance(coordinates, list):
        return []

    points: list[RoutePoint] = []
    for coordinate in coordinates:
        point = _route_point_from_coordinate(coordinate)
        if point is not None:
            points.append(point)
    return points


def _append_unique_point(points: list[RoutePoint], point: RoutePoint) -> None:
    if points and points[-1].latitude == point.latitude and points[-1].longitude == point.longitude:
        return
    points.append(point)


def _normalize_tmap_response(payload: Any, request: WalkingRouteRequest) -> WalkingRouteResponse:
    if not isinstance(payload, dict):
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not JSON object.")

    if payload.get("type") != "FeatureCollection":
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not GeoJSON FeatureCollection.")

    features = payload.get("features")
    if not isinstance(features, list) or not features:
        raise TmapPedestrianRouteError(code="route_unavailable", message="TMAP pedestrian route response has no route features.", http_status=422)

    summary_distance_m = 0
    summary_duration_s = 0
    steps: list[WalkingRouteStep] = []
    guide_points: list[WalkingRouteGuidePoint] = []
    polyline: list[RoutePoint] = []
    step_index = 0
    guide_index = 0
    distance_from_start_m = 0

    for feature in features:
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

        points = _line_points(geometry.get("coordinates"))
        if not points:
            continue
        for point in points:
            _append_unique_point(polyline, point)

        distance_m = _int_field(properties, "distance")
        duration_s = _int_field(properties, "time")
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

    if not polyline:
        polyline = [request.origin, request.destination]
    if not summary_distance_m:
        summary_distance_m = sum(step.distance_m for step in steps)
    if not summary_duration_s:
        summary_duration_s = sum(step.duration_s for step in steps)
    if summary_distance_m:
        for guide_point in guide_points:
            if guide_point.distance_from_start_m is not None and guide_point.remaining_distance_m is None:
                guide_point.remaining_distance_m = max(summary_distance_m - guide_point.distance_from_start_m, 0)

    return WalkingRouteResponse(
        schema_version="walksafe.walking_route.v1",
        provider="tmap_pedestrian",
        provider_route_id=None,
        priority=request.priority,
        summary=WalkingRouteSummary(distance_m=summary_distance_m, duration_s=summary_duration_s),
        polyline=polyline,
        steps=steps,
        guide_points=guide_points,
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

    should_close = http_client is None
    client = http_client or httpx.AsyncClient(timeout=settings.tmap_timeout_seconds)
    try:
        response = await client.post(settings.tmap_pedestrian_route_url, params=params, json=body, headers=headers)
    except httpx.TimeoutException as exc:
        raise TmapPedestrianRouteError(code="tmap_timeout", message="TMAP pedestrian route request timed out.", http_status=504) from exc
    except httpx.HTTPError as exc:
        raise TmapPedestrianRouteError(code="tmap_network_error", message="TMAP pedestrian route request failed.") from exc
    finally:
        if should_close:
            await client.aclose()

    if response.status_code < 200 or response.status_code >= 300:
        provider_code = None
        provider_message = None
        try:
            error_payload = response.json()
        except ValueError:
            error_payload = None
        if isinstance(error_payload, dict) and isinstance(error_payload.get("error"), dict):
            error_detail = error_payload["error"]
            if isinstance(error_detail.get("code"), str):
                provider_code = error_detail["code"]
            if isinstance(error_detail.get("message"), str):
                provider_message = error_detail["message"]

        error_code = "tmap_provider_error"
        error_message = "TMAP pedestrian route provider returned an error."
        if response.status_code in (401, 403) or provider_code == "INVALID_API_KEY":
            error_code = "tmap_invalid_api_key"
            error_message = "TMAP appKey is invalid or not allowed to use pedestrian route API."

        raise TmapPedestrianRouteError(
            code=error_code,
            message=provider_message or error_message,
            provider_status=response.status_code,
        )

    try:
        payload = response.json()
    except ValueError as exc:
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not valid JSON.") from exc

    return _normalize_tmap_response(payload, request)


def _poi_text(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _poi_float(payload: dict[str, Any], *names: str) -> float | None:
    for name in names:
        value = payload.get(name)
        if value in (None, ""):
            continue
        try:
            return float(value)
        except (TypeError, ValueError):
            continue
    return None


def _poi_int(payload: dict[str, Any], name: str) -> int | None:
    value = payload.get(name)
    if value in (None, ""):
        return None
    try:
        return max(int(float(value)), 0)
    except (TypeError, ValueError):
        return None


def _join_address(*parts: str | None) -> str | None:
    text = " ".join(part for part in parts if part)
    return text or None


def _join_address_number(first: str | None, second: str | None) -> str | None:
    if second in (None, "", "0"):
        return first
    return _join_address(first, second)


def _normalize_poi_search_response(payload: Any, query: str) -> DestinationSearchResponse:
    if not isinstance(payload, dict):
        raise TmapPoiSearchError(code="invalid_tmap_response", message="TMAP POI search response is not JSON object.")

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

    results: list[DestinationSearchResult] = []
    for item in items:
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

        results.append(
            DestinationSearchResult(
                id=_poi_text(item, "id") or _poi_text(item, "pkey") or f"{latitude},{longitude}",
                name=name,
                point=RoutePoint(latitude=latitude, longitude=longitude, name=name),
                address=address,
                road_address=road_address,
                category=_poi_text(item, "bizCatName") or _poi_text(item, "catName"),
                result_type="poi",
                distance_m=_poi_int(item, "radius"),
            )
        )

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
    if getattr(settings, "tmap_poi_provider", "live") == "mock":
        return _mock_poi_search_response(
            normalized_query,
            limit=max(min(limit, 10), 1),
            origin_lat=origin_lat,
            origin_lng=origin_lng,
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
        "resCoordType": "WGS84GEO",
        "page": 1,
        "count": max(min(limit, 10), 1),
        "multiPoint": "N",
        "poiGroupYn": "N",
    }
    if origin_lat is not None and origin_lng is not None:
        params["centerLat"] = origin_lat
        params["centerLon"] = origin_lng
    headers = {"Accept": "application/json", "appKey": settings.tmap_app_key}

    should_close = http_client is None
    client = http_client or httpx.AsyncClient(timeout=settings.tmap_timeout_seconds)
    try:
        response = await client.get(settings.tmap_poi_search_url, params=params, headers=headers)
    except httpx.TimeoutException as exc:
        raise TmapPoiSearchError(code="tmap_timeout", message="TMAP POI search request timed out.", http_status=504) from exc
    except httpx.HTTPError as exc:
        raise TmapPoiSearchError(code="tmap_network_error", message="TMAP POI search request failed.") from exc
    finally:
        if should_close:
            await client.aclose()

    if response.status_code < 200 or response.status_code >= 300:
        provider_message = None
        provider_code = None
        try:
            error_payload = response.json()
        except ValueError:
            error_payload = None
        if isinstance(error_payload, dict) and isinstance(error_payload.get("error"), dict):
            error_detail = error_payload["error"]
            if isinstance(error_detail.get("code"), str):
                provider_code = error_detail["code"]
            if isinstance(error_detail.get("message"), str):
                provider_message = error_detail["message"]

        error_code = "tmap_provider_error"
        error_message = "TMAP POI search provider returned an error."
        if response.status_code in (401, 403) or provider_code == "INVALID_API_KEY":
            error_code = "tmap_invalid_api_key"
            error_message = "TMAP appKey is invalid or not allowed to use POI search API."

        raise TmapPoiSearchError(
            code=error_code,
            message=provider_message or error_message,
            provider_status=response.status_code,
        )

    try:
        payload = response.json()
    except ValueError as exc:
        raise TmapPoiSearchError(code="invalid_tmap_response", message="TMAP POI search response is not valid JSON.") from exc

    return _normalize_poi_search_response(payload, normalized_query)
