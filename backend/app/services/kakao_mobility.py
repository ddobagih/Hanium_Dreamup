from __future__ import annotations

from typing import Any

import httpx

from backend.app.config import Settings
from backend.app.schemas import (
    RoutePoint,
    WalkingRouteRequest,
    WalkingRouteResponse,
    WalkingRouteStep,
    WalkingRouteSummary,
)


class KakaoMobilityRouteError(RuntimeError):
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


def _format_point(point: RoutePoint) -> str:
    return f"{point.longitude},{point.latitude}"


def _route_points_from_vertexes(vertexes: object) -> list[RoutePoint]:
    if not isinstance(vertexes, list):
        return []

    points: list[RoutePoint] = []
    for index in range(0, len(vertexes) - 1, 2):
        longitude = vertexes[index]
        latitude = vertexes[index + 1]
        if not isinstance(longitude, (int, float)) or not isinstance(latitude, (int, float)):
            continue
        points.append(RoutePoint(latitude=float(latitude), longitude=float(longitude)))
    return points


def _append_unique_point(points: list[RoutePoint], point: RoutePoint) -> None:
    if points and points[-1].latitude == point.latitude and points[-1].longitude == point.longitude:
        return
    points.append(point)


def _int_field(payload: dict[str, Any], name: str) -> int:
    value = payload.get(name)
    if isinstance(value, bool) or not isinstance(value, int):
        return 0
    return max(value, 0)


def _normalize_kakao_response(payload: Any, request: WalkingRouteRequest) -> WalkingRouteResponse:
    if not isinstance(payload, dict):
        raise KakaoMobilityRouteError(code="invalid_kakao_response", message="Kakao walking route response is not JSON object.")

    routes = payload.get("routes")
    if not isinstance(routes, list) or not routes:
        raise KakaoMobilityRouteError(code="route_unavailable", message="Kakao walking route response has no routes.", http_status=422)

    route = routes[0]
    if not isinstance(route, dict):
        raise KakaoMobilityRouteError(code="invalid_kakao_response", message="Kakao walking route item is invalid.")

    result_code = _int_field(route, "result_code")
    result_message = route.get("result_message") if isinstance(route.get("result_message"), str) else ""
    if result_code != 0:
        raise KakaoMobilityRouteError(
            code="route_unavailable",
            message=result_message or "Kakao walking route failed.",
            http_status=422,
            provider_result_code=result_code,
        )

    summary = route.get("summary") if isinstance(route.get("summary"), dict) else {}
    response_summary = WalkingRouteSummary(
        distance_m=_int_field(summary, "distance"),
        duration_s=_int_field(summary, "duration"),
    )

    steps: list[WalkingRouteStep] = []
    polyline: list[RoutePoint] = []
    sections = route.get("sections") if isinstance(route.get("sections"), list) else []
    step_index = 0
    for section in sections:
        if not isinstance(section, dict):
            continue
        roads = section.get("roads") if isinstance(section.get("roads"), list) else []
        for road in roads:
            if not isinstance(road, dict):
                continue
            road_points = _route_points_from_vertexes(road.get("vertexes"))
            if not road_points:
                continue
            for point in road_points:
                _append_unique_point(polyline, point)
            steps.append(
                WalkingRouteStep(
                    index=step_index,
                    distance_m=_int_field(road, "distance"),
                    duration_s=_int_field(road, "duration"),
                    points=road_points,
                )
            )
            step_index += 1

    if not polyline:
        polyline = [request.origin, request.destination]

    provider_route_id = payload.get("trans_id") if isinstance(payload.get("trans_id"), str) else None
    return WalkingRouteResponse(
        schema_version="walksafe.walking_route.v1",
        provider="kakao_mobility",
        provider_route_id=provider_route_id,
        priority=request.priority,
        summary=response_summary,
        polyline=polyline,
        steps=steps,
        provider_result_code=result_code,
        provider_result_message=result_message,
    )


async def fetch_kakao_walking_route(
    request: WalkingRouteRequest,
    settings: Settings,
    *,
    http_client: httpx.AsyncClient | None = None,
) -> WalkingRouteResponse:
    if request.priority not in ("DISTANCE", "MAIN_STREET"):
        raise KakaoMobilityRouteError(
            code="route_priority_unsupported",
            message="Kakao Mobility walking route supports only DISTANCE or MAIN_STREET priority.",
            http_status=422,
        )

    if not settings.kakao_mobility_rest_api_key:
        raise KakaoMobilityRouteError(
            code="kakao_api_key_missing",
            message="Kakao Mobility REST API key is not configured.",
            http_status=503,
        )

    params: dict[str, object] = {
        "origin": _format_point(request.origin),
        "destination": _format_point(request.destination),
        "waypoints": "|".join(_format_point(point) for point in request.waypoints),
        "radius": request.radius_m,
        "priority": request.priority,
        "summary": "false",
    }
    if request.default_speed is not None:
        params["default_speed"] = request.default_speed

    headers = {
        "accept": "application/json",
        "service": settings.kakao_mobility_service_name,
        "Authorization": f"KakaoAK {settings.kakao_mobility_rest_api_key}",
        "Content-Type": "application/json",
    }

    should_close = http_client is None
    client = http_client or httpx.AsyncClient(timeout=settings.kakao_mobility_timeout_seconds)
    try:
        response = await client.get(settings.kakao_mobility_walking_directions_url, params=params, headers=headers)
    except httpx.TimeoutException as exc:
        raise KakaoMobilityRouteError(code="kakao_timeout", message="Kakao walking route request timed out.", http_status=504) from exc
    except httpx.HTTPError as exc:
        raise KakaoMobilityRouteError(code="kakao_network_error", message="Kakao walking route request failed.") from exc
    finally:
        if should_close:
            await client.aclose()

    if response.status_code < 200 or response.status_code >= 300:
        raise KakaoMobilityRouteError(
            code="kakao_provider_error",
            message="Kakao walking route provider returned an error.",
            provider_status=response.status_code,
        )

    try:
        payload = response.json()
    except ValueError as exc:
        raise KakaoMobilityRouteError(code="invalid_kakao_response", message="Kakao walking route response is not valid JSON.") from exc

    return _normalize_kakao_response(payload, request)
