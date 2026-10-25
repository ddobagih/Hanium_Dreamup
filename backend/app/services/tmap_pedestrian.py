"""TMAP POI search and pedestrian-route transport/worker adapter.

Pure provider normalization lives in dedicated modules. This module keeps the
existing entry points and coordinates HTTP, worker jobs and readiness evidence.
"""

from __future__ import annotations

import asyncio
import json
import sys
from time import monotonic
from typing import Any, Callable
from urllib.parse import quote

import httpx
from pydantic import ValidationError

from backend.app.config import Settings
from backend.app.schemas import (
    DestinationSearchResponse,
    RoutePoint,
    WalkingRoutePriority,
    WalkingRouteRequest,
    WalkingRouteResponse,
)
from backend.app.services.tmap_runtime import TmapRuntime, TmapRuntimeError
from backend.app.services.walking_route_sanity import route_point_distance_m
from backend.app.services.tmap_route_health_state import TmapRouteHealthState
from backend.app.services.tmap_response_normalization import (
    TmapPoiSearchError,
    TmapPedestrianRouteError,
    TMAP_MAX_STRING_LENGTH,
    _ensure_tmap_deadline,
    _json_has_oversized_string,
    _haversine_m,
)
from backend.app.services.tmap_walking_normalization import (
    USELESS_GUIDE_DESCRIPTION_RE,
    TMAP_MAX_FEATURES,
    TMAP_MAX_POLYLINE_POINTS,
    TMAP_MAX_STEPS,
    TMAP_MAX_GUIDE_POINTS,
    TMAP_SEGMENT_SEAM_ROUNDING_MAX_M,
    TMAP_SEGMENT_SEAM_ROUNDING_TOTAL_MAX_M,
    TMAP_WAYPOINT_MAX_DISTANCE_M,
    _TMAP_WAYPOINT_EARTH_RADIUS_M,
    _TMAP_KNOWN_TURN_TYPES,
    _TMAP_KNOWN_FACILITY_TYPES,
    _TMAP_WAYPOINT_MIN_ARC_CROSS_NORM,
    _TMAP_WAYPOINT_MISMATCH_MESSAGE,
    _int_field,
    _tmap_code_field,
    _required_tmap_step_distance,
    _validate_tmap_step_geometry_distance,
    _str_field,
    _clean_guide_instruction,
    _route_waypoint_mismatch,
    _waypoint_sin_cos_degrees,
    _waypoint_local_coordinates,
    _waypoint_allowed_intervals,
    _validate_route_waypoint_order,
    _bearing_deg,
    _route_bearing_for_point,
    _route_point_from_coordinate,
    _line_points,
    _append_unique_point,
    _tmap_guide_projections,
    _normalize_tmap_geometry_distances,
    _tmap_projection_visits,
    _tmap_summary_conflict_error,
    _merge_tmap_summary_total,
    _tmap_guide_position_error,
    _normalize_tmap_response,
)
from backend.app.services.tmap_poi_normalization import (
    KOREAN_DESTINATION_ACTION_RE,
    TMAP_MAX_POI_RESULTS,
    MOCK_POI_ITEMS,
    normalize_destination_query,
    _poi_text,
    _poi_text_or_number,
    _poi_float,
    _POI_COORDINATE_FAMILIES,
    _poi_coordinate_candidates,
    _poi_coordinate_pair,
    _poi_distance_m,
    _join_address,
    _join_address_number,
    _poi_nested_road_address,
    _normalize_poi_search_response,
    _mock_poi_search_response,
    _is_named_university_query,
    _destination_search_intent,
    _is_named_station_query,
)


# Preserve the adapter clock seam while the state owns all locking and ordering.
_TMAP_DEPENDENCY_STATE = TmapRouteHealthState(clock=lambda: monotonic())


def _begin_route_check() -> tuple[int, int]:
    return _TMAP_DEPENDENCY_STATE.begin_route_check()


def _record_route_failure(check: tuple[int, int]) -> None:
    _TMAP_DEPENDENCY_STATE.record_route_failure(check)


def _record_tmap_success(
    dependency: str, *, now: float | None = None, route_check: tuple[int, int] | None = None,
) -> None:
    _TMAP_DEPENDENCY_STATE.record_success(dependency, now=now, route_check=route_check)


def reset_tmap_success_cache() -> None:
    """Clear process-local evidence; intended for startup and deterministic tests."""
    _TMAP_DEPENDENCY_STATE.reset()


def tmap_dependency_readiness(
    max_age_seconds: float,
    *,
    now: float | None = None,
) -> dict[str, object]:
    return _TMAP_DEPENDENCY_STATE.readiness(max_age_seconds, now=now)


TMAP_SEARCH_OPTIONS: dict[WalkingRoutePriority, str] = {
    "RECOMMEND": "0",
    "MAIN_STREET": "4",
    "DISTANCE": "10",
    "STAIR_AVOID": "30",
}

TMAP_MAX_RESPONSE_BYTES = 2 * 1024 * 1024


class _TmapResponseLimitError(RuntimeError):
    pass


class _ObservedResponseBody(httpx.AsyncByteStream):
    """Observe raw EOF before HTTPX awaits its already-owned stream cleanup."""

    def __init__(self, stream: httpx.AsyncByteStream, on_read: Callable[[], None]) -> None:
        self._stream = stream
        self._on_read = on_read

    async def __aiter__(self):
        async for chunk in self._stream:
            yield chunk
        self._on_read()

    async def aclose(self) -> None:
        await self._stream.aclose()


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
    runtime: TmapRuntime,
    json_body: dict[str, object] | None = None,
    timeout_seconds: float | None = None,
    on_response: Callable[[], None] | None = None,
    on_body_read: Callable[[], None] | None = None,
) -> tuple[int, bytes]:
    _ensure_tmap_deadline(deadline_at)
    request_headers = {**headers, "Accept-Encoding": "identity"}
    request_kwargs: dict[str, Any] = {"params": params, "headers": request_headers}
    if json_body is not None:
        request_kwargs["json"] = json_body
    if timeout_seconds is not None:
        request_kwargs["timeout"] = min(timeout_seconds, max(deadline_at - monotonic(), 0.0))
    stream = client.stream(method, url, **request_kwargs)
    response = None
    try:
        async with asyncio.timeout(max(deadline_at - monotonic(), 0.0)):
            response = await stream.__aenter__()
            runtime.own_http_response(response)
            if on_body_read is not None and not response.is_stream_consumed:
                response.stream = _ObservedResponseBody(response.stream, on_body_read)
            if on_response is not None:
                on_response()
            runtime.record_rate_limit_response(
                response.status_code, response.headers.get("retry-after"),
            )
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
                if on_body_read is not None:
                    on_body_read()
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
            if on_body_read is not None:
                on_body_read()
            _ensure_tmap_deadline(deadline_at)
            return response.status_code, bytes(body)
    finally:
        if response is not None:
            exc_info = sys.exc_info()
            # Cleanup has ownership, not a new HTTP budget. The original
            # deadline still bounds entering/reading the response above.
            await runtime.finish_http_cleanup(lambda: stream.__aexit__(*exc_info))


def _encoded_name(point: RoutePoint, fallback: str) -> str:
    return quote(point.name or fallback, safe="")


def _pass_list(points: list[RoutePoint]) -> str:
    return "_".join(f"{point.longitude},{point.latitude}" for point in points)


async def fetch_tmap_pedestrian_route(
    request: WalkingRouteRequest,
    settings: Settings,
    *,
    http_client: httpx.AsyncClient | None = None,
    runtime: TmapRuntime | None = None,
    _http_timeout_seconds: float | None = None,
    _readiness_probe: bool = False,
) -> WalkingRouteResponse:
    try:
        if runtime is None:
            raise TmapRuntimeError(
                code="tmap_processing_unavailable",
                message="TMAP request processing is unavailable.",
            )
        runtime.ensure_available()
    except TmapRuntimeError as exc:
        raise TmapPedestrianRouteError(code=exc.code, message=exc.message, http_status=exc.http_status) from None

    if not settings.tmap_app_key:
        raise TmapPedestrianRouteError(
            code="tmap_app_key_missing",
            message="TMAP appKey is not configured.",
            http_status=503,
        )

    # The exact speed TMAP estimates totalTime with; the plausibility cap follows it.
    speed_kmh = int(round(request.default_speed or settings.tmap_pedestrian_speed_kmh))
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
        "speed": speed_kmh,
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
    route_check = None
    response_entered = False
    body_read = False

    def record_failure() -> None:
        # An admitted result may still finish after shutdown, but is no longer
        # evidence for the current runtime's readiness.
        try:
            runtime.ensure_available()
        except TmapRuntimeError:
            return
        if route_check is not None:
            _record_route_failure(route_check)

    async def operation() -> WalkingRouteResponse:
        nonlocal route_check, response_entered, body_read
        runtime.ensure_rate_limit_available("route")
        if not _readiness_probe:
            # The readiness probe was admitted from its own allowance (R9).
            runtime.consume_call_budget("route", settings)
        client = http_client if http_client is not None else runtime.client
        route_check = _begin_route_check()

        def entered_response() -> None:
            nonlocal response_entered
            response_entered = True

        def completed_body() -> None:
            nonlocal body_read
            body_read = True

        try:
            provider_status, response_body = await _stream_tmap_response(
                client,
                "POST",
                settings.tmap_pedestrian_route_url,
                deadline_at=deadline_at,
                params=params,
                headers=headers,
                runtime=runtime,
                timeout_seconds=_http_timeout_seconds,
                json_body=body,
                on_response=entered_response,
                on_body_read=completed_body,
            )
        except (TimeoutError, httpx.TimeoutException) as exc:
            if (response_entered and not body_read) or isinstance(exc, (httpx.ConnectTimeout, httpx.ReadTimeout, httpx.WriteTimeout)):
                record_failure()
            raise TmapPedestrianRouteError(code="tmap_timeout", message="TMAP pedestrian route request timed out.", http_status=504) from exc
        except _TmapResponseLimitError as exc:
            record_failure()
            raise TmapPedestrianRouteError(
                code="invalid_tmap_response",
                message="TMAP pedestrian route response exceeds the safe resource limit.",
            ) from exc
        except httpx.HTTPError as exc:
            if isinstance(exc, (httpx.NetworkError, httpx.RemoteProtocolError)):
                record_failure()
            raise TmapPedestrianRouteError(code="tmap_network_error", message="TMAP pedestrian route request failed.") from exc
        try:
            return await runtime.process_response(
                "route", provider_status, response_body,
                {"request": request.model_dump(mode="json"), "speed_kmh": speed_kmh},
                deadline_at=deadline_at,
            )
        except TmapRuntimeError as exc:
            if exc.code not in {"tmap_processing_unavailable", "tmap_timeout", "tmap_capacity_exceeded"}:
                record_failure()
            raise

    try:
        normalized = await runtime.execute(operation, deadline_at=deadline_at)
        _ensure_tmap_deadline(deadline_at)
    except TmapRuntimeError as exc:
        raise TmapPedestrianRouteError(
            code=exc.code,
            message=exc.message,
            http_status=exc.http_status,
            provider_status=exc.provider_status,
            provider_result_code=exc.provider_result_code,
        ) from None
    except TimeoutError as exc:
        if response_entered and not body_read:
            record_failure()
        raise TmapPedestrianRouteError(
            code="tmap_timeout",
            message="TMAP pedestrian route request timed out.",
            http_status=504,
        ) from exc
    try:
        runtime.ensure_available()
    except TmapRuntimeError:
        pass
    else:
        _record_tmap_success("route", route_check=route_check)
    return normalized


async def fetch_tmap_poi_search(
    query: str,
    settings: Settings,
    *,
    limit: int = 5,
    origin_lat: float | None = None,
    origin_lng: float | None = None,
    http_client: httpx.AsyncClient | None = None,
    runtime: TmapRuntime | None = None,
    _http_timeout_seconds: float | None = None,
    _readiness_probe: bool = False,
) -> DestinationSearchResponse:
    try:
        if runtime is None:
            raise TmapRuntimeError(
                code="tmap_processing_unavailable",
                message="TMAP request processing is unavailable.",
            )
        runtime.ensure_available()
    except TmapRuntimeError as exc:
        raise TmapPoiSearchError(code=exc.code, message=exc.message, http_status=exc.http_status) from None

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
    if provider not in {"live", "mock"}:
        raise TmapPoiSearchError(
            code="tmap_poi_provider_invalid",
            message="Configured destination search provider is not supported.",
            http_status=503,
        )
    if provider != "mock" and not settings.tmap_app_key:
        raise TmapPoiSearchError(
            code="tmap_app_key_missing",
            message="TMAP appKey is not configured.",
            http_status=503,
        )

    keyword, nearby = _destination_search_intent(normalized_query)
    if origin_lat is None:
        keyword, nearby = normalized_query, False
    params = {
        "version": settings.tmap_pedestrian_api_version,
        "searchKeyword": keyword,
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
        # Apply relevance before the bounded provider page for named facilities.
        params["searchtypCd"] = "A" if (
            not nearby and (_is_named_university_query(keyword) or _is_named_station_query(keyword))
        ) else "R"
        params["centerLat"] = origin_lat
        params["centerLon"] = origin_lng
    headers = {"Accept": "application/json", "appKey": settings.tmap_app_key}

    deadline_at = monotonic() + settings.tmap_timeout_seconds

    async def operation() -> DestinationSearchResponse:
        if provider == "mock":
            return await runtime.process_response(
                "poi", 200, b"null", {
                "query": normalized_query,
                "limit": limit,
                "origin_lat": origin_lat,
                "origin_lng": origin_lng,
                "mock": provider == "mock",
            },
                deadline_at=deadline_at,
            )
        runtime.ensure_rate_limit_available("poi")
        if not _readiness_probe:
            runtime.consume_call_budget("poi", settings)
        client = http_client if http_client is not None else runtime.client
        try:
            provider_status, response_body = await _stream_tmap_response(
                client,
                "GET",
                settings.tmap_poi_search_url,
                deadline_at=deadline_at,
                params=params,
                headers=headers,
                runtime=runtime,
                timeout_seconds=_http_timeout_seconds,
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
        return await runtime.process_response(
            "poi", provider_status, response_body, {
                "query": normalized_query,
                "limit": limit,
                "origin_lat": origin_lat,
                "origin_lng": origin_lng,
                "mock": provider == "mock",
            },
            deadline_at=deadline_at,
        )

    try:
        normalized = await runtime.execute(operation, deadline_at=deadline_at)
        _ensure_tmap_deadline(deadline_at)
    except TmapRuntimeError as exc:
        raise TmapPoiSearchError(
            code=exc.code,
            message=exc.message,
            http_status=exc.http_status,
            provider_status=exc.provider_status,
        ) from None
    except TimeoutError as exc:
        raise TmapPoiSearchError(
            code="tmap_timeout",
            message="TMAP POI search request timed out.",
            http_status=504,
        ) from exc
    if provider != "mock":
        _record_tmap_success("poi")
    return normalized


async def probe_tmap_dependencies(
    settings: Settings,
    *,
    runtime: TmapRuntime | None = None,
) -> dict[str, object]:
    """Use the app owner, or explicitly own and close a standalone probe runtime."""
    if runtime is None:
        scoped_runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        try:
            await scoped_runtime.start()
            return await probe_tmap_dependencies(settings, runtime=scoped_runtime)
        finally:
            await scoped_runtime.aclose()
    # S20-29/R9: users cannot route while their budget is exhausted, so a
    # provider success here would misreport navigation; do not probe. The probe
    # itself uses a separate 1-per-interval allowance, never the user windows.
    exhausted = runtime.call_budget_exhaustion(settings)
    if exhausted is not None:
        raise exhausted
    runtime.reserve_readiness_probe()
    timeout = min(settings.tmap_timeout_seconds, settings.tmap_readiness_probe_timeout_seconds)
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=37.5662952, longitude=126.9779451, name="readiness-start"),
        destination=RoutePoint(latitude=37.5664852, longitude=126.9781451, name="readiness-end"),
        priority="STAIR_AVOID",
    )
    await fetch_tmap_pedestrian_route(
        request, settings, runtime=runtime, _http_timeout_seconds=timeout,
        _readiness_probe=True,
    )
    await fetch_tmap_poi_search(
        "서울시청", settings, limit=1, runtime=runtime, _http_timeout_seconds=timeout,
        _readiness_probe=True,
    )
    return tmap_dependency_readiness(settings.tmap_readiness_success_max_age_seconds)


def _process_tmap_response(
    mode: str,
    provider_status: int,
    response_body: bytes,
    arguments: dict[str, Any],
    *,
    deadline_at: float,
) -> WalkingRouteResponse | DestinationSearchResponse:
    _ensure_tmap_deadline(deadline_at)
    if mode == "route":
        return _process_tmap_route_response(
            provider_status, response_body, arguments, deadline_at=deadline_at,
        )
    if mode == "poi":
        return _process_tmap_poi_response(
            provider_status, response_body, arguments, deadline_at=deadline_at,
        )
    raise ValueError("Unknown TMAP processing operation")


def _process_tmap_route_response(
    provider_status: int,
    response_body: bytes,
    arguments: dict[str, Any],
    *,
    deadline_at: float,
) -> WalkingRouteResponse:
    request = WalkingRouteRequest.model_validate(arguments["request"])
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
        speed_kmh = arguments.get("speed_kmh")
        normalized = _normalize_tmap_response(
            payload,
            request,
            deadline_at=deadline_at,
            speed_kmh=speed_kmh if type(speed_kmh) in (int, float) else None,
            _strings_checked=True,
        )
        _ensure_tmap_deadline(deadline_at)
        return normalized
    except TmapPedestrianRouteError as exc:
        if (
            (exc.code, exc.http_status) != ("invalid_tmap_response", 502)
            or exc.message not in {
                "TMAP pedestrian route roadType is invalid.",
                "TMAP pedestrian route facilityType is invalid.",
                "TMAP pedestrian route turnType is invalid.",
            }
        ):
            raise
        raise TmapPedestrianRouteError(
            code=exc.code,
            message="TMAP pedestrian route response violates the WalkSafe schema.",
            http_status=exc.http_status,
            provider_status=exc.provider_status,
            provider_result_code=exc.provider_result_code,
        ) from exc
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


def _process_tmap_poi_response(
    provider_status: int,
    response_body: bytes,
    arguments: dict[str, Any],
    *,
    deadline_at: float,
) -> DestinationSearchResponse:
    normalized_query = arguments["query"]
    limit = arguments["limit"]
    origin_lat, origin_lng = arguments["origin_lat"], arguments["origin_lng"]
    if arguments.get("mock") is True:
        _ensure_tmap_deadline(deadline_at)
        result = _mock_poi_search_response(
            normalized_query, limit=max(min(limit, 10), 1),
            origin_lat=origin_lat, origin_lng=origin_lng,
        )
        _ensure_tmap_deadline(deadline_at)
        return result
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
            _strings_checked=True,
        )
        requested_count = max(min(limit, 10), 1)
        results = normalized.results[:requested_count]
        for destination in results:
            if origin_lat is None or origin_lng is None:
                destination.distance_m = None
                destination.distance_source = None
            elif destination.distance_m is None:
                destination.distance_m = _haversine_m(
                    origin_lat,
                    origin_lng,
                    destination.point.latitude,
                    destination.point.longitude,
                )
                destination.distance_source = "straight_line"
        # The request is always provider page 1 of `requested_count`; decide "more" from the
        # provider total, never from the normalized (deduplicated, validated) result count.
        more_results_available = (
            None if normalized.total_count is None else normalized.total_count > requested_count
        )
        result = normalized.model_copy(
            update={"results": results, "more_results_available": more_results_available},
        )
        _ensure_tmap_deadline(deadline_at)
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
