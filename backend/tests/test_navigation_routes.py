from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from contextvars import ContextVar
from copy import deepcopy
import multiprocessing
import os
from time import monotonic
import json
from pathlib import Path
import sys
from urllib.parse import unquote

import httpx
import pytest
from fastapi import FastAPI

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from backend.app.api import navigation as navigation_api  # noqa: E402
from backend.app.config import Settings, get_settings  # noqa: E402
from backend.app.schemas import (  # noqa: E402
    DestinationSearchResponse,
    DestinationSearchResult,
    RoutePoint,
    WalkingRouteRequest,
    WalkingRouteResponse,
    WalkingRouteGuidePoint,
    WalkingRouteStep,
    WalkingRouteSummary,
    route_required_geometry_turns,
)
from backend.app.services.tmap_pedestrian import (  # noqa: E402
    TMAP_MAX_FEATURES,
    TMAP_MAX_GUIDE_POINTS,
    TMAP_MAX_POI_RESULTS,
    TMAP_MAX_POLYLINE_POINTS,
    TMAP_MAX_RESPONSE_BYTES,
    TMAP_MAX_STEPS,
    TMAP_MAX_STRING_LENGTH,
    TmapPedestrianRouteError,
    TmapPoiSearchError,
    _normalize_poi_search_response,
    _record_tmap_success,
    _normalize_tmap_response,
    _route_bearing_for_point,
    _haversine_m,
    fetch_tmap_pedestrian_route as _fetch_tmap_pedestrian_route,
    fetch_tmap_poi_search as _fetch_tmap_poi_search,
    probe_tmap_dependencies,
    normalize_destination_query,
    reset_tmap_success_cache,
    tmap_dependency_readiness,
)
from backend.app.services import tmap_pedestrian as tmap_service  # noqa: E402
from backend.app.services import tmap_runtime as runtime_module  # noqa: E402
from backend.app.services.tmap_runtime import (  # noqa: E402
    TmapRuntime, TmapRuntimeError, TmapWorkerTestHooks,
)


_ACTIVE_NAVIGATION_RUNTIME = ContextVar("navigation_test_runtime", default=None)


@asynccontextmanager
async def _navigation_runtime_app(settings, *, runtime=None):
    owner = runtime if runtime is not None else TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)

    @asynccontextmanager
    async def lifespan(application):
        token = None
        try:
            await owner.start()
            application.state.tmap_runtime = owner
            token = _ACTIVE_NAVIGATION_RUNTIME.set(owner)
            yield
        finally:
            owner.stop_admission()
            try:
                await owner.aclose()
            finally:
                if token is not None:
                    _ACTIVE_NAVIGATION_RUNTIME.reset(token)

    application = FastAPI(lifespan=lifespan)
    application.include_router(navigation_api.create_router(
        settings, runtime_provider=lambda: application.state.tmap_runtime,
    ))
    # HTTPX ASGITransport does not run lifespan. This fixture explicitly does.
    async with lifespan(application):
        yield application, owner


class _NavigationTestClient:
    def request(self, method, url, *, params=None, json=None):
        async def run():
            async with _navigation_runtime_app(get_settings()) as (application, _):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=application), base_url="http://testserver",
                ) as transport:
                    return await transport.request(method, url, params=params, json=json)
        return asyncio.run(run())

    def get(self, url, *, params=None):
        return self.request("GET", url, params=params)

    def post(self, url, *, json=None):
        return self.request("POST", url, json=json)


def client() -> _NavigationTestClient:
    # Navigation-only fixture; this is not a test of main.py's other resources.
    return _NavigationTestClient()


async def fetch_tmap_pedestrian_route(request, settings, *, http_client=None, runtime=None):
    if runtime is not None:
        return await _fetch_tmap_pedestrian_route(
            request, settings, http_client=http_client, runtime=runtime,
        )
    owner = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
    try:
        await owner.start()
        return await _fetch_tmap_pedestrian_route(
            request, settings, http_client=http_client, runtime=owner,
        )
    finally:
        await owner.aclose()


async def fetch_tmap_poi_search(
    query, settings, *, limit=5, origin_lat=None, origin_lng=None, http_client=None, runtime=None,
):
    if runtime is not None:
        return await _fetch_tmap_poi_search(
            query, settings, limit=limit, origin_lat=origin_lat, origin_lng=origin_lng,
            http_client=http_client, runtime=runtime,
        )
    owner = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
    try:
        await owner.start()
        return await _fetch_tmap_poi_search(
            query, settings, limit=limit, origin_lat=origin_lat, origin_lng=origin_lng,
            http_client=http_client, runtime=owner,
        )
    finally:
        await owner.aclose()


def test_tmap_readiness_cache_requires_fresh_route_and_poi_evidence() -> None:
    reset_tmap_success_cache()
    _record_tmap_success("route", now=100.0)
    assert tmap_dependency_readiness(30.0, now=110.0)["ready"] is False

    _record_tmap_success("poi", now=105.0)
    assert tmap_dependency_readiness(30.0, now=110.0)["ready"] is True
    assert tmap_dependency_readiness(5.0, now=111.0)["ready"] is False
    reset_tmap_success_cache()


@pytest.mark.parametrize(
    ("query", "expected"),
    [
        ("종로", "종로"),
        ("구로", "구로"),
        ("낙성대로", "낙성대로"),
        ("종로로 안내해줘", "종로"),
        ("서울역으로 안내해줘", "서울역"),
        ("목적지 종로 설정해", "종로"),
    ],
)
def test_destination_normalization_preserves_names_ending_in_ro(query: str, expected: str) -> None:
    assert normalize_destination_query(query) == expected


@pytest.mark.parametrize("query", ["안내해줘", "목적지 설정해", "  안내해  "])
def test_destination_normalization_rejects_command_without_a_place(query: str) -> None:
    assert normalize_destination_query(query) == ""


def sample_route_request() -> dict[str, object]:
    return {
        "origin": {"latitude": 37.39472714688412, "longitude": 127.11015314141542},
        "destination": {"latitude": 37.401937080111644, "longitude": 127.10824367964793, "name": "테스트 목적지"},
        "priority": "STAIR_AVOID",
    }


def sample_route_response() -> WalkingRouteResponse:
    return WalkingRouteResponse(
        schema_version="walksafe.walking_route.v1",
        provider="tmap_pedestrian",
        provider_route_id=None,
        priority="STAIR_AVOID",
        summary=WalkingRouteSummary(distance_m=1281, duration_s=1220),
        polyline=[
            RoutePoint(latitude=37.39472714688412, longitude=127.11015314141542),
            RoutePoint(latitude=37.401937080111644, longitude=127.10824367964793),
        ],
        steps=[
            WalkingRouteStep(
                index=0,
                distance_m=20,
                duration_s=18,
                instruction="보행 구간 20m",
                road_name="테스트길",
                points=[
                    RoutePoint(latitude=37.39472714688412, longitude=127.11015314141542),
                    RoutePoint(latitude=37.401937080111644, longitude=127.10824367964793),
                ],
            )
        ],
        guide_points=[
            WalkingRouteGuidePoint(
                index=0,
                point=RoutePoint(latitude=37.39472714688412, longitude=127.11015314141542),
                instruction="출발",
                distance_from_start_m=0,
                remaining_distance_m=1281,
            ),
        ],
        provider_result_code=0,
        provider_result_message="OK",
    )


def sample_destination_search_response() -> DestinationSearchResponse:
    return DestinationSearchResponse(
        schema_version="walksafe.destination_search.v1",
        provider="tmap_poi",
        query="판교역",
        results=[
            DestinationSearchResult(
                id="poi-1",
                name="판교역",
                point=RoutePoint(latitude=37.3947, longitude=127.1112, name="판교역"),
                address="경기 성남시 분당구 백현동",
                road_address="경기 성남시 분당구 판교역로 160",
                category="교통",
                distance_m=120,
            )
        ],
    )


def test_destination_search_requires_tmap_key(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_app_key", "")

    response = client().get("/navigation/destinations/search?query=판교역")

    assert response.status_code == 503, response.text
    assert response.json()["detail"]["code"] == "tmap_app_key_missing"


def test_destination_search_health_reports_mock_ready(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_poi_provider", "mock")
    monkeypatch.setattr(settings, "tmap_app_key", "")

    response = client().get("/navigation/destinations/search/health")

    assert response.status_code == 200, response.text
    assert response.json()["mode"] == "mock"
    assert response.json()["status"] == "ready"
    assert response.json()["reason"] is None


def test_destination_search_mock_provider_uses_local_alias_and_distance(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_poi_provider", "mock")
    monkeypatch.setattr(settings, "tmap_app_key", "")

    response = client().get(
        "/navigation/destinations/search",
        params={"query": "서울역으로 안내해줘", "origin_lat": 37.5657, "origin_lng": 126.9769},
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["provider"] == "tmap_poi"
    assert body["query"] == "서울역"
    assert body["results"][0]["name"] == "서울역"
    assert body["results"][0]["result_type"] == "alias"
    assert body["results"][0]["distance_m"] > 0


def test_destination_search_rejects_partial_origin_coordinates(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_poi_provider", "mock")

    response = client().get(
        "/navigation/destinations/search",
        params={"query": "서울역", "origin_lat": 37.5657},
    )

    assert response.status_code == 422, response.text
    assert response.json()["detail"]["code"] == "origin_coordinates_incomplete"


def test_destination_search_rejects_unknown_runtime_provider(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_poi_provider", "unexpected")

    health = client().get("/navigation/destinations/search/health")
    search = client().get("/navigation/destinations/search?query=서울역")

    assert health.status_code == 503
    assert search.status_code == 503
    assert health.json()["detail"]["code"] == "tmap_poi_provider_invalid"
    assert search.json()["detail"]["code"] == "tmap_poi_provider_invalid"


@pytest.mark.parametrize("default_speed", [0, 20.1])
def test_walking_route_rejects_unsafe_speed_values(default_speed: float) -> None:
    request = sample_route_request()
    request["default_speed"] = default_speed

    response = client().post("/navigation/walking", json=request)

    assert response.status_code == 422


def test_walking_route_schema_rejects_nonfinite_coordinates_and_speed() -> None:
    for field, value in (("default_speed", float("inf")), ("origin.latitude", float("nan"))):
        request = sample_route_request()
        if field == "default_speed":
            request["default_speed"] = value
        else:
            assert isinstance(request["origin"], dict)
            request["origin"]["latitude"] = value
        with pytest.raises(ValueError):
            WalkingRouteRequest.model_validate(request)


def test_destination_search_route_returns_app_schema(monkeypatch) -> None:
    async def fake_fetch_tmap_poi_search(
        query: str,
        settings,
        *,
        limit: int,
        runtime: TmapRuntime,
        origin_lat: float | None = None,
        origin_lng: float | None = None,
    ) -> DestinationSearchResponse:
        assert runtime is _ACTIVE_NAVIGATION_RUNTIME.get()
        runtime.ensure_available()
        assert query == "판교역"
        assert limit == 3
        assert origin_lat is None
        assert origin_lng is None
        return sample_destination_search_response()

    monkeypatch.setattr(navigation_api, "fetch_tmap_poi_search", fake_fetch_tmap_poi_search)

    response = client().get("/navigation/destinations/search?query=판교역&limit=3")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["schema_version"] == "walksafe.destination_search.v1"
    assert body["provider"] == "tmap_poi"
    assert body["query"] == "판교역"
    assert body["results"][0]["name"] == "판교역"
    assert body["results"][0]["point"] == {"latitude": 37.3947, "longitude": 127.1112, "name": "판교역"}


def test_destination_search_uses_unicode_code_point_limit(monkeypatch) -> None:
    accepted = "😀" * 80
    calls: list[str] = []

    async def fake_fetch_tmap_poi_search(
        query: str,
        settings,
        *,
        limit: int,
        runtime: TmapRuntime,
        origin_lat: float | None = None,
        origin_lng: float | None = None,
    ) -> DestinationSearchResponse:
        assert runtime is _ACTIVE_NAVIGATION_RUNTIME.get()
        runtime.ensure_available()
        calls.append(query)
        return sample_destination_search_response().model_copy(update={"query": query})

    monkeypatch.setattr(navigation_api, "fetch_tmap_poi_search", fake_fetch_tmap_poi_search)

    accepted_response = client().get(
        "/navigation/destinations/search",
        params={"query": accepted},
    )
    rejected_response = client().get(
        "/navigation/destinations/search",
        params={"query": "😀" * 81},
    )

    assert accepted_response.status_code == 200
    assert rejected_response.status_code == 422
    assert calls == [accepted]


def test_tmap_poi_search_service_normalizes_provider_response(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_poi_search_url", "https://example.test/tmap/pois")
    monkeypatch.setattr(settings, "tmap_pedestrian_api_version", "1")

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.headers["appkey"] == "test-tmap-key"
        assert request.url.params["version"] == "1"
        assert request.url.params["searchKeyword"] == "판교역"
        assert request.url.params["searchType"] == "all"
        assert request.url.params["searchtypCd"] == "A"
        assert request.url.params["radius"] == "0"
        assert request.url.params["reqCoordType"] == "WGS84GEO"
        assert request.url.params["resCoordType"] == "WGS84GEO"
        assert request.url.params["count"] == "2"
        return httpx.Response(
            200,
            json={
                "searchPoiInfo": {
                    "totalCount": "1",
                    "pois": {
                        "poi": [
                            {
                                "id": "poi-1",
                                "name": "판교역",
                                "noorLon": "127.1112",
                                "noorLat": "37.3947",
                                "upperAddrName": "경기",
                                "middleAddrName": "성남시 분당구",
                                "lowerAddrName": "백현동",
                                "upperRoadAddrName": "경기",
                                "middleRoadAddrName": "성남시 분당구",
                                "roadName": "판교역로",
                                "firstBuildNo": "160",
                                "secondBuildNo": "0",
                                "lowerBizName": "교통",
                                "radius": "0.12",
                            },
                            {"id": "bad", "name": "좌표 없음"},
                        ]
                    },
                }
            },
        )

    async def run() -> DestinationSearchResponse:
        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as http_client:
            return await fetch_tmap_poi_search(" 판교역 ", settings, limit=2, http_client=http_client)

    result = asyncio.run(run())

    assert result.schema_version == "walksafe.destination_search.v1"
    assert result.provider == "tmap_poi"
    assert result.query == "판교역"
    assert len(result.results) == 1
    assert result.results[0].id.startswith("tmap-poi:v1:")
    assert len(result.results[0].id) == 76
    assert result.results[0].point.latitude == 37.3947
    assert result.results[0].point.longitude == 127.1112
    assert result.results[0].address == "경기 성남시 분당구 백현동"
    assert result.results[0].road_address == "경기 성남시 분당구 판교역로 160"
    assert result.results[0].category == "교통"
    assert result.results[0].distance_m is None


@pytest.mark.parametrize("use_origin", [False, True])
def test_destination_search_origin_prioritizes_nearby_matches(monkeypatch, use_origin: bool) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_poi_search_url", "https://example.test/tmap/pois")
    candidates = [
        {"id": "popular-far", "name": "Cafe", "noorLat": "35.1796", "noorLon": "129.0756", "radius": "325"},
        {"id": "nearby", "name": "Cafe", "noorLat": "37.5666", "noorLon": "126.9781", "radius": "0.12"},
    ]

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["radius"] == "0"
        assert request.url.params["searchtypCd"] == ("R" if use_origin else "A")
        if use_origin:
            assert request.url.params["centerLat"] == "37.5665"
            assert request.url.params["centerLon"] == "126.978"
        else:
            assert "centerLat" not in request.url.params
            assert "centerLon" not in request.url.params
        ordered = sorted(candidates, key=lambda item: float(item["radius"])) if (
            request.url.params["searchtypCd"] == "R"
        ) else candidates
        return httpx.Response(
            200,
            json={"searchPoiInfo": {"pois": {"poi": ordered[: int(request.url.params["count"])]}}},
        )

    async def run() -> DestinationSearchResponse:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http_client:
            return await fetch_tmap_poi_search(
                "Cafe",
                settings,
                limit=1,
                origin_lat=37.5665 if use_origin else None,
                origin_lng=126.978 if use_origin else None,
                http_client=http_client,
            )

    result = asyncio.run(run())
    assert len(result.results) == 1
    expected_candidate = candidates[1 if use_origin else 0]
    assert result.results[0].name == expected_candidate["name"]
    assert result.results[0].point.latitude == float(expected_candidate["noorLat"])
    assert result.results[0].point.longitude == float(expected_candidate["noorLon"])
    assert result.results[0].distance_m == (120 if use_origin else None)


@pytest.mark.parametrize("use_origin", [False, True])
@pytest.mark.parametrize(
    ("query", "sort_with_origin"),
    [
        ("금오공대", "A"),
        ("국립금오공과대학교", "A"),
        ("국립 금오공과대학교", "A"),
        ("한빛대학교", "A"),
        ("한빛대학", "A"),
        ("편의점", "R"),
        ("카페", "R"),
        ("금오공대 운동장", "R"),
        ("국립금오공과대학교 정문", "R"),
        ("국립금오공과대학교정문", "R"),
        ("편의점 국립금오공과대학교", "R"),
        ("금오공대 근처 편의점", "R"),
        ("주변 대학교", "R"),
        ("근처대학교", "R"),
        ("국립대학교", "R"),
        ("대학교", "R"),
        ("대학", "R"),
        ("공대", "R"),
    ],
)
def test_tmap_poi_named_university_relevance_preserves_other_search_contracts(
    monkeypatch, query: str, sort_with_origin: str, use_origin: bool,
) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_poi_search_url", "https://example.test/tmap/pois")
    expected_sort = sort_with_origin if use_origin else "A"
    # Synthetic provider candidates isolate page ordering from physical location.
    campus = {
        "id": "campus", "name": "국립금오공과대학교", "lowerBizName": "대학교",
        "noorLat": "37.55", "noorLon": "126.98", "radius": "0.4",
    }
    facility = {
        "id": "facility", "name": "국립금오공과대학교 운동장", "lowerBizName": "학교내시설물",
        "noorLat": "37.5666", "noorLon": "126.9781", "radius": "0.1",
    }
    provider_calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal provider_calls
        provider_calls += 1
        assert request.url.params["searchKeyword"] == query
        assert request.url.params["searchtypCd"] == expected_sort
        assert request.url.params["radius"] == "0"
        assert request.url.params["page"] == "1"
        assert request.url.params["count"] == "1"
        if use_origin:
            assert request.url.params["centerLat"] == "37.5665"
            assert request.url.params["centerLon"] == "126.978"
        else:
            assert "centerLat" not in request.url.params
            assert "centerLon" not in request.url.params
        ordered = [campus, facility] if expected_sort == "A" else [facility, campus]
        return httpx.Response(
            200,
            json={"searchPoiInfo": {"totalCount": "2", "pois": {"poi": ordered[:1]}}},
        )

    async def run() -> DestinationSearchResponse:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http_client:
            return await fetch_tmap_poi_search(
                query, settings, limit=1,
                origin_lat=37.5665 if use_origin else None,
                origin_lng=126.978 if use_origin else None,
                http_client=http_client,
            )

    result = asyncio.run(run())
    assert provider_calls == 1
    assert result.query == query
    assert len(result.results) == 1
    expected_candidate = campus if expected_sort == "A" else facility
    assert result.results[0].name == expected_candidate["name"]
    assert result.results[0].point.latitude == float(expected_candidate["noorLat"])
    assert result.results[0].point.longitude == float(expected_candidate["noorLon"])
    assert result.results[0].distance_m == (
        (400 if expected_sort == "A" else 100) if use_origin else None
    )


@pytest.mark.parametrize("coordinates", [
    {"origin_lat": "37.5665"},
    {"origin_lng": "126.978"},
    {"origin_lat": "nan", "origin_lng": "126.978"},
    {"origin_lat": "inf", "origin_lng": "126.978"},
    {"origin_lat": "91", "origin_lng": "126.978"},
    {"origin_lat": "37.5665", "origin_lng": "181"},
])
def test_destination_search_rejects_invalid_origin_before_provider_io(monkeypatch, coordinates) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_poi_provider", "mock")
    monkeypatch.setattr(settings, "tmap_app_key", "")

    response = client().get(
        "/navigation/destinations/search",
        params={"query": "Cafe", **coordinates},
    )

    assert response.status_code == 422
    if len(coordinates) == 1:
        assert response.json()["detail"]["code"] == "origin_coordinates_incomplete"


def test_destination_search_same_query_keeps_origin_after_provider_failure_and_retry(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_poi_search_url", "https://example.test/tmap/pois")
    origins = [(37.5665, 126.978), (35.1796, 129.0756)]
    calls: list[tuple[float, float]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["searchKeyword"] == "Cafe"
        assert request.url.params["searchtypCd"] == "R"
        assert request.url.params["radius"] == "0"
        origin = (float(request.url.params["centerLat"]), float(request.url.params["centerLon"]))
        calls.append(origin)
        if len(calls) == 2:
            return httpx.Response(503, json={"error": {"message": "temporarily unavailable"}})
        return httpx.Response(
            200,
            json={"searchPoiInfo": {"pois": {"poi": [{
                "id": str(origins.index(origin)),
                "name": "Cafe",
                "noorLat": str(origin[0]),
                "noorLon": str(origin[1]),
                "radius": "0.12",
            }]}}},
        )

    async def run() -> list[str]:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http_client:
            results = []
            for index, origin in enumerate([origins[0], origins[1], origins[1], origins[0]]):
                if index == 1:
                    with pytest.raises(TmapPoiSearchError) as failure:
                        await fetch_tmap_poi_search(
                            "Cafe", settings, origin_lat=origin[0], origin_lng=origin[1],
                            http_client=http_client,
                        )
                    assert failure.value.code == "tmap_provider_error"
                    assert failure.value.provider_status == 503
                    continue
                response = await fetch_tmap_poi_search(
                    "Cafe", settings, origin_lat=origin[0], origin_lng=origin[1],
                    http_client=http_client,
                )
                assert response.results[0].point.latitude == origin[0]
                assert response.results[0].point.longitude == origin[1]
                results.append(response.results[0].id)
            return results

    returned_ids = asyncio.run(run())
    assert len(returned_ids) == 3
    assert returned_ids[0] == returned_ids[2]
    assert returned_ids[0] != returned_ids[1]
    assert calls == [origins[0], origins[1], origins[1], origins[0]]


@pytest.mark.parametrize(
    ("use_origin", "provider_radius", "expected_distance_m"),
    [
        (False, "0", None),
        (False, "0.12", None),
        (True, None, 1112),
        (True, "", 1112),
        (True, "NaN", 1112),
        (True, "-1", 1112),
        (True, "0", 0),
        (True, "0.12", 120),
    ],
)
def test_destination_search_distance_requires_a_known_origin(
    monkeypatch, use_origin: bool, provider_radius: str | None, expected_distance_m: int | None,
) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_poi_search_url", "https://example.test/tmap/pois")
    provider_calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal provider_calls
        provider_calls += 1
        assert request.method == "GET"
        assert request.url.params["searchKeyword"] == "Cafe"
        assert request.url.params["searchtypCd"] == ("R" if use_origin else "A")
        if use_origin:
            assert float(request.url.params["centerLat"]) == 37.0
            assert float(request.url.params["centerLon"]) == 127.0
        else:
            assert "centerLat" not in request.url.params
            assert "centerLon" not in request.url.params
        poi = {"id": "cafe", "name": "Cafe", "noorLat": "37.01", "noorLon": "127.0"}
        if provider_radius is not None:
            poi["radius"] = provider_radius
        return httpx.Response(200, json={"searchPoiInfo": {"pois": {"poi": [poi]}}})

    async def fetch_with_transport(
        query: str, current_settings, *, limit: int, runtime: TmapRuntime,
        origin_lat: float | None = None, origin_lng: float | None = None,
    ) -> DestinationSearchResponse:
        assert runtime is _ACTIVE_NAVIGATION_RUNTIME.get()
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http_client:
            return await fetch_tmap_poi_search(
                query, current_settings, limit=limit, origin_lat=origin_lat, origin_lng=origin_lng,
                http_client=http_client, runtime=runtime,
            )

    monkeypatch.setattr(navigation_api, "fetch_tmap_poi_search", fetch_with_transport)
    params = {"query": "Cafe"}
    if use_origin:
        params.update({"origin_lat": "37.0", "origin_lng": "127.0"})

    response = client().get("/navigation/destinations/search", params=params)

    assert response.status_code == 200, response.text
    assert provider_calls == 1
    assert response.json()["results"][0]["distance_m"] == expected_distance_m


def test_walking_navigation_health_reports_missing_tmap_key(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(settings, "tmap_app_key", "")

    response = client().get("/navigation/walking/health")

    assert response.status_code == 200, response.text
    assert response.json()["provider"] == "tmap_pedestrian"
    assert response.json()["status"] == "unavailable"
    assert response.json()["reason"] == "tmap_app_key_missing"


@pytest.mark.parametrize("provider", ["unexpected_provider", "kakao_mobility"])
def test_settings_rejects_non_tmap_walking_route_provider(monkeypatch, provider: str) -> None:
    monkeypatch.setenv("WALKING_ROUTE_PROVIDER", provider)

    with pytest.raises(ValueError, match="WALKING_ROUTE_PROVIDER"):
        Settings()


def test_navigation_api_rejects_unknown_runtime_provider(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "walking_route_provider", "unexpected_provider")

    health_response = client().get("/navigation/walking/health")
    route_response = client().post("/navigation/walking", json=sample_route_request())

    assert health_response.status_code == 503, health_response.text
    assert route_response.status_code == 503, route_response.text
    assert health_response.json()["detail"]["code"] == "walking_route_provider_invalid"
    assert route_response.json()["detail"]["code"] == "walking_route_provider_invalid"


def test_walking_navigation_requires_tmap_key(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(settings, "tmap_app_key", "")

    response = client().post("/navigation/walking", json=sample_route_request())

    assert response.status_code == 503, response.text
    assert response.json()["detail"]["code"] == "tmap_app_key_missing"


def test_walking_navigation_route_returns_app_schema(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "walking_route_provider", "tmap_pedestrian")

    async def fake_fetch_tmap_pedestrian_route(
        request: WalkingRouteRequest, settings, *, runtime: TmapRuntime,
    ) -> WalkingRouteResponse:
        assert runtime is _ACTIVE_NAVIGATION_RUNTIME.get()
        runtime.ensure_available()
        assert request.destination.name == "테스트 목적지"
        assert request.priority == "STAIR_AVOID"
        return sample_route_response()

    monkeypatch.setattr(navigation_api, "fetch_tmap_pedestrian_route", fake_fetch_tmap_pedestrian_route)

    response = client().post("/navigation/walking", json=sample_route_request())

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["schema_version"] == "walksafe.walking_route.v1"
    assert body["provider"] == "tmap_pedestrian"
    assert body["summary"] == {"distance_m": 1281, "duration_s": 1220}
    assert len(body["polyline"]) == 2
    assert body["steps"][0]["distance_m"] == 20
    assert body["steps"][0]["instruction"] == "보행 구간 20m"
    assert len(body["guide_points"]) == 1
    assert body["guide_points"][0]["instruction"] == "출발"


def test_tmap_pedestrian_route_service_normalizes_provider_response(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_pedestrian_route_url", "https://example.test/tmap/routes/pedestrian")
    monkeypatch.setattr(settings, "tmap_pedestrian_api_version", "1")
    monkeypatch.setattr(settings, "tmap_pedestrian_speed_kmh", 4.0)

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        assert request.headers["appkey"] == "test-tmap-key"
        assert request.url.params["version"] == "1"
        assert body["startX"] == 127.11015314141542
        assert body["startY"] == 37.39472714688412
        assert body["endX"] == 127.10824367964793
        assert body["endY"] == 37.401937080111644
        assert body["searchOption"] == "30"
        assert body["reqCoordType"] == "WGS84GEO"
        assert body["resCoordType"] == "WGS84GEO"
        assert body["speed"] == 4
        assert unquote(body["startName"]) == "출발"
        assert unquote(body["endName"]) == "테스트 목적지"
        return httpx.Response(
            200,
            json={
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "geometry": {"type": "Point", "coordinates": [127.11015314141542, 37.39472714688412]},
                        "properties": {
                            "totalDistance": 900,
                            "totalTime": 810,
                            "index": 1,
                            "pointIndex": 1,
                            "name": "출발지",
                            "description": "출발",
                            "turnType": "200",
                            "pointType": "S",
                        },
                    },
                    {
                        "type": "Feature",
                        "geometry": {
                            "type": "LineString",
                            "coordinates": [
                                [127.11015314141542, 37.39472714688412],
                                [127.109, 37.398],
                            ],
                        },
                        "properties": {
                            "index": 2,
                            "lineIndex": 1,
                            "name": "테스트길",
                            "description": "테스트길, 400m",
                            "distance": 400,
                            "time": 360,
                            "roadType": 16,
                            "facilityType": "15",
                        },
                    },
                    {
                        "type": "Feature",
                        "geometry": {"type": "Point", "coordinates": [127.109, 37.398]},
                        "properties": {
                            "index": 3,
                            "pointIndex": 2,
                            "description": ", 73m",
                            "turnType": 13,
                            "pointType": "GP",
                        },
                    },
                    {
                        "type": "Feature",
                        "geometry": {
                            "type": "LineString",
                            "coordinates": [
                                [127.109, 37.398],
                                [127.10824367964793, 37.401937080111644],
                            ],
                        },
                        "properties": {
                            "index": 4,
                            "lineIndex": 2,
                            "name": "도착길",
                            "description": ", 500m",
                            "distance": 500,
                            "time": 450,
                            "roadType": 16,
                            "facilityType": 11,
                        },
                    },
                ],
            },
        )

    async def run() -> WalkingRouteResponse:
        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as http_client:
            return await fetch_tmap_pedestrian_route(
                WalkingRouteRequest.model_validate(sample_route_request()),
                settings,
                http_client=http_client,
            )

    result = asyncio.run(run())

    assert result.provider == "tmap_pedestrian"
    assert result.summary.distance_m == 900
    assert result.summary.duration_s == 810
    assert len(result.steps) == 2
    assert result.steps[0].instruction == "테스트길, 400m"
    assert result.steps[0].facility_type == 15
    assert result.steps[1].instruction is None
    assert len(result.guide_points) == 2
    assert result.guide_points[0].instruction == "출발"
    assert result.guide_points[0].turn_type == 200
    assert result.guide_points[0].point_type == "S"
    assert result.guide_points[0].distance_from_start_m == 0
    assert result.guide_points[0].remaining_distance_m == 900
    assert result.guide_points[0].bearing_deg is not None
    assert 0 <= result.guide_points[0].bearing_deg < 360
    assert result.guide_points[1].instruction is None
    assert result.guide_points[1].turn_type == 13
    assert result.guide_points[1].point_type == "GP"
    assert result.guide_points[1].distance_from_start_m == 414
    assert result.guide_points[1].remaining_distance_m == 486
    assert result.guide_points[1].bearing_deg is not None
    assert 0 <= result.guide_points[1].bearing_deg < 360
    assert len(result.polyline) == 3


def test_tmap_pedestrian_route_surfaces_invalid_api_key(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "bad-key")
    monkeypatch.setattr(settings, "tmap_pedestrian_route_url", "https://example.test/tmap/routes/pedestrian")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            403,
            json={"error": {"id": "403", "category": "gw", "code": "INVALID_API_KEY", "message": "Forbidden"}},
        )

    async def run() -> WalkingRouteResponse:
        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as http_client:
            return await fetch_tmap_pedestrian_route(
                WalkingRouteRequest.model_validate(sample_route_request()),
                settings,
                http_client=http_client,
            )

    try:
        asyncio.run(run())
    except Exception as exc:  # noqa: BLE001
        assert getattr(exc, "code", None) == "tmap_invalid_api_key"
        assert getattr(exc, "provider_status", None) == 403
    else:
        raise AssertionError("TMAP provider 403 did not raise route error")


def test_tmap_pedestrian_route_rejects_missing_geometry(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_pedestrian_route_url", "https://example.test/tmap/routes/pedestrian")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "geometry": {"type": "Point", "coordinates": [127.11015314141542, 37.39472714688412]},
                        "properties": {"totalDistance": 30, "totalTime": 27},
                    }
                ],
            },
        )

    async def run() -> WalkingRouteResponse:
        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as http_client:
            return await fetch_tmap_pedestrian_route(
                WalkingRouteRequest.model_validate(sample_route_request()),
                settings,
                http_client=http_client,
            )

    with pytest.raises(TmapPedestrianRouteError) as exc_info:
        asyncio.run(run())

    assert exc_info.value.code == "route_geometry_missing"
    assert exc_info.value.http_status == 502


def test_tmap_walking_route_rejects_invalid_summary_and_endpoint() -> None:
    request = WalkingRouteRequest.model_validate(sample_route_request())
    invalid_summary_payload = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [
                        [request.origin.longitude, request.origin.latitude],
                        [request.destination.longitude, request.destination.latitude],
                    ],
                },
                "properties": {"distance": 0, "time": 0},
            }
        ],
    }
    with pytest.raises(TmapPedestrianRouteError) as summary_exc:
        _normalize_tmap_response(invalid_summary_payload, request)
    assert summary_exc.value.code == "route_summary_invalid"

    endpoint_mismatch_payload = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[128.0, 36.0], [128.01, 36.01]],
                },
                "properties": {
                    "totalDistance": 1400,
                    "totalTime": 1200,
                    "distance": 1400,
                    "time": 1200,
                },
            }
        ],
    }
    with pytest.raises(TmapPedestrianRouteError) as endpoint_exc:
        _normalize_tmap_response(endpoint_mismatch_payload, request)
    assert endpoint_exc.value.code == "route_endpoint_mismatch"


def test_tmap_poi_stream_enforces_total_wall_clock_deadline(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_timeout_seconds", 0.1)

    async def run() -> TmapPoiSearchError:
        completed = asyncio.Event()
        payload = json.dumps(
            {"searchPoiInfo": {"pois": {"poi": []}}},
            separators=(",", ":"),
        ).encode("utf-8")
        chunk_size = max(1, (len(payload) + 7) // 8)
        chunks = [
            payload[index : index + chunk_size]
            for index in range(0, len(payload), chunk_size)
        ]

        async def drip_response(
            reader: asyncio.StreamReader,
            writer: asyncio.StreamWriter,
        ) -> None:
            try:
                await reader.readuntil(b"\r\n\r\n")
                writer.write(
                    b"HTTP/1.1 200 OK\r\n"
                    b"Content-Type: application/json\r\n"
                    b"Connection: close\r\n\r\n"
                )
                await writer.drain()
                for chunk in chunks:
                    writer.write(chunk)
                    await writer.drain()
                    await asyncio.sleep(0.03)
            except (ConnectionError, OSError, asyncio.IncompleteReadError):
                pass
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except (ConnectionError, OSError):
                    pass
                completed.set()

        server = await asyncio.start_server(drip_response, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        monkeypatch.setattr(
            settings,
            "tmap_poi_search_url",
            f"http://127.0.0.1:{port}/tmap/pois",
        )
        timeout = httpx.Timeout(connect=1.0, read=0.2, write=1.0, pool=1.0)
        try:
            async with httpx.AsyncClient(timeout=timeout, trust_env=False) as http_client:
                with pytest.raises(TmapPoiSearchError) as exc_info:
                    await fetch_tmap_poi_search(
                        "판교역",
                        settings,
                        http_client=http_client,
                    )
        finally:
            server.close()
            await server.wait_closed()
            await asyncio.wait_for(completed.wait(), timeout=1.0)
        return exc_info.value

    error = asyncio.run(run())

    assert error.code == "tmap_timeout"
    assert error.http_status == 504


@pytest.mark.parametrize("provider_status", [200, 403])
@pytest.mark.parametrize("framing", ["declared", "chunked"])
def test_tmap_route_stream_rejects_success_and_error_bodies_over_hard_cap(
    monkeypatch,
    provider_status: int,
    framing: str,
) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_timeout_seconds", 1.0)

    class BodyStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            if framing == "declared":
                yield b"{}"
            else:
                yield b"x" * TMAP_MAX_RESPONSE_BYTES
                yield b"x"

        async def aclose(self) -> None:
            return None

    def handler(_request: httpx.Request) -> httpx.Response:
        headers = (
            {"Content-Length": str(TMAP_MAX_RESPONSE_BYTES + 1)}
            if framing == "declared"
            else {"Transfer-Encoding": "chunked"}
        )
        return httpx.Response(
            provider_status,
            headers=headers,
            stream=BodyStream(),
        )

    async def run() -> WalkingRouteResponse:
        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as http_client:
            return await fetch_tmap_pedestrian_route(
                WalkingRouteRequest.model_validate(sample_route_request()),
                settings,
                http_client=http_client,
            )

    with pytest.raises(TmapPedestrianRouteError) as exc_info:
        asyncio.run(run())

    assert exc_info.value.code == "invalid_tmap_response"
    assert exc_info.value.provider_status is None


def test_tmap_route_rejects_encoded_response_before_reading_the_body(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_timeout_seconds", 1.0)
    body_read = False

    class EncodedBodyStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            nonlocal body_read
            body_read = True
            raise AssertionError("encoded response body must not be read")
            yield b""  # pragma: no cover

        async def aclose(self) -> None:
            return None

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["accept-encoding"] == "identity"
        return httpx.Response(
            200,
            headers={"Content-Encoding": "gzip"},
            stream=EncodedBodyStream(),
        )

    async def run() -> WalkingRouteResponse:
        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as http_client:
            return await fetch_tmap_pedestrian_route(
                WalkingRouteRequest.model_validate(sample_route_request()),
                settings,
                http_client=http_client,
            )

    with pytest.raises(TmapPedestrianRouteError) as exc_info:
        asyncio.run(run())

    assert exc_info.value.code == "invalid_tmap_response"
    assert body_read is False


@pytest.mark.parametrize("cardinality", ["feature", "polyline", "step", "guide"])
def test_tmap_route_rejects_post_json_cardinality_explosion(cardinality: str) -> None:
    request = WalkingRouteRequest.model_validate(sample_route_request())
    origin = [request.origin.longitude, request.origin.latitude]
    destination = [request.destination.longitude, request.destination.latitude]
    if cardinality == "feature":
        features = [{}] * (TMAP_MAX_FEATURES + 1)
    elif cardinality == "polyline":
        features = [
            {
                "geometry": {
                    "type": "LineString",
                    "coordinates": [origin] * (TMAP_MAX_POLYLINE_POINTS + 1),
                },
                "properties": {"distance": 1, "time": 1},
            }
        ]
    elif cardinality == "step":
        features = [
            {
                "geometry": {
                    "type": "LineString",
                    "coordinates": [origin, destination] if index % 2 == 0 else [destination, origin],
                },
                "properties": {"distance": 1, "time": 1},
            }
            for index in range(TMAP_MAX_STEPS + 1)
        ]
    else:
        features = [
            {
                "geometry": {"type": "Point", "coordinates": origin},
                "properties": {},
            }
        ] * (TMAP_MAX_GUIDE_POINTS + 1)

    with pytest.raises(TmapPedestrianRouteError) as exc_info:
        _normalize_tmap_response(
            {"type": "FeatureCollection", "features": features},
            request,
        )

    assert exc_info.value.code == "invalid_tmap_response"
    if cardinality == "step":
        assert exc_info.value.message == "TMAP pedestrian route response exceeds the safe step limit."


def test_tmap_poi_rejects_post_json_result_explosion() -> None:
    payload = {
        "searchPoiInfo": {
            "pois": {"poi": [{}] * (TMAP_MAX_POI_RESULTS + 1)},
        }
    }

    with pytest.raises(TmapPoiSearchError) as exc_info:
        _normalize_poi_search_response(payload, "판교역")

    assert exc_info.value.code == "invalid_tmap_response"


def test_tmap_route_rejects_post_json_string_explosion() -> None:
    request = WalkingRouteRequest.model_validate(sample_route_request())
    payload = {
        "type": "FeatureCollection",
        "features": [
            {
                "geometry": {"type": "Point", "coordinates": [127.0, 37.0]},
                "properties": {"description": "x" * (TMAP_MAX_STRING_LENGTH + 1)},
            }
        ],
    }

    with pytest.raises(TmapPedestrianRouteError) as exc_info:
        _normalize_tmap_response(payload, request)

    assert exc_info.value.code == "invalid_tmap_response"


@pytest.mark.parametrize("radius", ["0.001", "0.12", "1.25", "0"])
def test_tmap_poi_distance_converts_provider_kilometers(radius: str) -> None:
    result = _normalize_poi_search_response(
        {"searchPoiInfo": {"pois": {"poi": [{
            "name": "시청역", "noorLat": "37.5657", "noorLon": "126.9769", "radius": radius,
        }]}}},
        "시청역",
    )
    assert result.results[0].distance_m == round(float(radius) * 1000)


@pytest.mark.parametrize("radius", ["NaN", "Infinity", "-0.2", "invalid", "1e308"])
def test_tmap_poi_invalid_distance_remains_unknown(radius: str) -> None:
    result = _normalize_poi_search_response(
        {"searchPoiInfo": {"pois": {"poi": [{
            "name": "시청역", "noorLat": "37.5657", "noorLon": "126.9769", "radius": radius,
        }]}}},
        "시청역",
    )
    assert result.results[0].distance_m is None


@pytest.mark.parametrize("latitude", ["NaN", "Infinity", True])
def test_tmap_poi_skips_invalid_coordinates(latitude: object) -> None:
    result = _normalize_poi_search_response(
        {"searchPoiInfo": {"pois": {"poi": [
            {"name": "invalid", "noorLat": latitude, "noorLon": "126.9769"},
            {"name": "시청역", "noorLat": "37.5657", "noorLon": "126.9769"},
        ]}}},
        "시청역",
    )
    assert [item.name for item in result.results] == ["시청역"]


@pytest.mark.parametrize("stairs_source", ["line", "point", "turn", "combined_turn"])
def test_stair_exclusion_rejects_provider_reported_stairs(stairs_source: str) -> None:
    request = WalkingRouteRequest.model_validate(sample_route_request())
    origin = [request.origin.longitude, request.origin.latitude]
    destination = [request.destination.longitude, request.destination.latitude]
    guide_properties = {"turnType": "200"}
    line_properties = {"distance": 900, "time": 810, "facilityType": "11"}
    if stairs_source == "line":
        line_properties["facilityType"] = "17"
    elif stairs_source == "point":
        guide_properties["facilityType"] = "17"
    else:
        guide_properties["turnType"] = "127" if stairs_source == "turn" else "129"
    payload = {
        "type": "FeatureCollection",
        "features": [
            {"geometry": {"type": "Point", "coordinates": origin}, "properties": guide_properties},
            {"geometry": {"type": "LineString", "coordinates": [origin, destination]}, "properties": line_properties},
        ],
    }
    with pytest.raises(TmapPedestrianRouteError) as exc_info:
        _normalize_tmap_response(payload, request)
    assert exc_info.value.code == "route_stairs_present"
    assert exc_info.value.http_status == 422
    request.priority = "RECOMMEND"
    result = _normalize_tmap_response(payload, request)
    assert [(point.longitude, point.latitude) for point in result.polyline] == [tuple(origin), tuple(destination)]


@pytest.mark.parametrize("dependency", ["poi", "route"])
@pytest.mark.parametrize("provider_status", [403, 429, 500])
def test_tmap_provider_errors_are_classified_without_echoing_details(monkeypatch, dependency: str, provider_status: int) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(provider_status, json={"error": {"message": "provider-private-request-details"}})

    async def run() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http_client:
            if dependency == "poi":
                await fetch_tmap_poi_search("시청역", settings, http_client=http_client)
            else:
                await fetch_tmap_pedestrian_route(
                    WalkingRouteRequest.model_validate(sample_route_request()), settings, http_client=http_client,
                )

    with pytest.raises((TmapPoiSearchError, TmapPedestrianRouteError)) as exc_info:
        asyncio.run(run())
    error = exc_info.value
    assert error.provider_status == provider_status
    assert "provider-private-request-details" not in error.message
    assert error.code == {403: "tmap_invalid_api_key", 429: "tmap_rate_limited", 500: "tmap_provider_error"}[provider_status]
    assert error.http_status == (503 if provider_status == 429 else 502)


def _tmap_line_fixture(coordinates, distance_m, duration_s):
    return {
        "type": "Feature",
        "geometry": {"type": "LineString", "coordinates": coordinates},
        "properties": {"distance": distance_m, "time": duration_s},
    }


def _tmap_guide_fixture(coordinate, description, turn_type=None):
    properties = {"description": description}
    if turn_type is not None:
        properties["turnType"] = turn_type
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": coordinate},
        "properties": properties,
    }


def _tmap_geometry_turn_guides(points):
    turn_guides = []
    previous_turn_index = 0
    for _, corner in route_required_geometry_turns(points):
        corner_index = next(
            index for index in range(previous_turn_index + 1, len(points) - 1)
            if points[index].latitude == corner.latitude and points[index].longitude == corner.longitude
        )
        previous_turn_index = corner_index
        before, after = corner_index - 1, corner_index + 1
        while before > 0 and tmap_service.route_point_distance_m(points[before], corner) < 1:
            before -= 1
        while after < len(points) - 1 and tmap_service.route_point_distance_m(corner, points[after]) < 1:
            after += 1
        incoming = (corner.longitude - points[before].longitude, corner.latitude - points[before].latitude)
        outgoing = (points[after].longitude - corner.longitude, points[after].latitude - corner.latitude)
        cross = incoming[0] * outgoing[1] - incoming[1] * outgoing[0]
        dot = incoming[0] * outgoing[0] + incoming[1] * outgoing[1]
        turn_type = 14 if dot < 0 and abs(cross) <= -dot else (12 if cross > 0 else 13)
        turn_guide = _tmap_guide_fixture([corner.longitude, corner.latitude], "Corner")
        turn_guide["properties"]["turnType"] = turn_type
        turn_guides.append(turn_guide)
    return turn_guides


def _normalize_tmap_feature_fixture(features, origin, destination):
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=origin[1], longitude=origin[0]),
        destination=RoutePoint(latitude=destination[1], longitude=destination[0]),
        priority="RECOMMEND",
    )
    # Geometry-only test inputs still need a real start guide to represent a
    # voice-guidance-capable provider response. Keep the caller's input intact.
    has_provider_guide = any(
        isinstance(feature, dict) and isinstance(feature.get("geometry"), dict)
        and feature["geometry"].get("type") == "Point"
        for feature in features
    )
    if has_provider_guide:
        guided_features = features
    else:
        # Geometry-only fixtures need a guide at every obvious internal turn
        # now that such a line can be served as a spoken route only if guided.
        coordinates = []
        for feature in features:
            if not isinstance(feature, dict) or not isinstance(feature.get("geometry"), dict):
                continue
            geometry = feature["geometry"]
            if geometry.get("type") != "LineString":
                continue
            for coordinate in geometry.get("coordinates", []):
                if (
                    isinstance(coordinate, list) and len(coordinate) == 2
                    and all(isinstance(value, (float, int)) for value in coordinate)
                    and (not coordinates or coordinates[-1] != coordinate)
                ):
                    coordinates.append(coordinate)
        points = [RoutePoint(latitude=coordinate[1], longitude=coordinate[0]) for coordinate in coordinates]
        turn_guides = _tmap_geometry_turn_guides(points)
        guided_features = [_tmap_guide_fixture(origin, "Start"), *turn_guides, *features]
    return _normalize_tmap_response({"type": "FeatureCollection", "features": guided_features}, request)


def _tmap_loop_bearing_fixture(
    *,
    leg_distance_m=11,
    north_latitude=37.0001,
    east_longitude=127.001,
    east_distance_m=90,
    duplicate_outgoing=False,
):
    origin = [127.0, 37.0]
    north = [127.0, north_latitude]
    destination = [east_longitude, 37.0]
    outgoing = [origin, origin, destination, destination] if duplicate_outgoing else [origin, destination]
    features = [
        _tmap_guide_fixture(origin, "First visit"),
        _tmap_line_fixture([origin, north], leg_distance_m, 10),
        _tmap_guide_fixture(north, "Turn back", 14),
        _tmap_line_fixture([north, origin], leg_distance_m, 10),
        _tmap_guide_fixture(origin, "Second visit", 12),
        _tmap_line_fixture(outgoing, east_distance_m, 80),
    ]
    return features, origin, north, destination


def _loop_original_guides(result):
    # Loop fixtures now include the north U-turn instruction needed for voice
    # guidance. Bearing regressions below still inspect their original guides.
    return [guide for guide in result.guide_points if guide.instruction != "Turn back"]


@pytest.mark.parametrize("gap_axis", [0, 1])
@pytest.mark.parametrize("guide_between", [False, True])
def test_tmap_rejects_disconnected_linestrings_without_inventing_connector(gap_axis, guide_between):
    origin = [127.11015314141542, 37.39472714688412]
    first_end = [127.109, 37.398]
    second_start = first_end.copy()
    # Both axis variants leave about 50 m of unprovided route geometry.
    second_start[gap_axis] += 0.00057 if gap_axis == 0 else 0.00045
    destination = [127.10824367964793, 37.401937080111644]
    flat = [origin, first_end, second_start, destination]
    provided_connector = _normalize_tmap_feature_fixture(
        [_tmap_line_fixture(flat, 900, 810)], origin, destination,
    )
    assert [[point.longitude, point.latitude] for point in provided_connector.polyline] == flat
    assert provided_connector.summary.distance_m == 900

    disconnected = [_tmap_line_fixture([origin, first_end], 400, 360)]
    if guide_between:
        disconnected.append(_tmap_guide_fixture(first_end, "Unconnected boundary"))
    disconnected.append(_tmap_line_fixture([second_start, destination], 500, 450))
    before = deepcopy(disconnected)

    with pytest.raises(TmapPedestrianRouteError) as exc_info:
        _normalize_tmap_feature_fixture(disconnected, origin, destination)

    assert exc_info.value.code == "route_geometry_disconnected"
    assert exc_info.value.http_status == 502
    assert exc_info.value.message == "TMAP pedestrian route LineString segments are disconnected."
    assert disconnected == before


def test_tmap_snaps_submeter_linestring_seam_without_adding_connector():
    origin = [127.11015314141542, 37.39472714688412]
    shared = [127.109, 37.398]
    # About 0.30 m north: a provider coordinate-rounding seam, not a route leg.
    rounded_start = [shared[0], shared[1] + 0.0000027]
    destination = [127.10824367964793, 37.401937080111644]
    features = [
        _tmap_line_fixture([origin, shared], 400, 360),
        _tmap_line_fixture([rounded_start, destination], 500, 450),
    ]
    before = deepcopy(features)

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [[point.longitude, point.latitude] for point in result.polyline] == [
        origin, shared, destination,
    ]
    assert [step.points[0].model_dump(exclude_none=True) for step in result.steps] == [
        {"latitude": origin[1], "longitude": origin[0]},
        {"latitude": shared[1], "longitude": shared[0]},
    ]
    assert result.summary.distance_m == 900
    assert features == before


def test_tmap_rejects_seam_beyond_rounding_tolerance():
    origin = [127.11015314141542, 37.39472714688412]
    shared = [127.109, 37.398]
    # About 0.70 m: beyond the 0.5 m representation-error allowance.
    gap_start = [shared[0], shared[1] + 0.0000063]
    destination = [127.10824367964793, 37.401937080111644]

    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_feature_fixture([
            _tmap_line_fixture([origin, shared], 400, 360),
            _tmap_line_fixture([gap_start, destination], 500, 450),
        ], origin, destination)

    assert error.value.code == "route_geometry_disconnected"
    assert error.value.http_status == 502


@pytest.mark.parametrize(("segment_count", "seam_latitude", "accepted"), [
    (4, 0.0000027, True),   # Three 0.3 m seams: about 0.9 m total.
    (5, 0.0000027, False),  # Four 0.3 m seams: about 1.2 m total.
    (101, 0.0000036, False),  # One hundred 0.4 m seams: about 40 m total.
])
def test_tmap_limits_cumulative_linestring_seam_correction(
    segment_count, seam_latitude, accepted,
):
    origin = [127.0, 37.0]
    destination = [127.0 + segment_count * 0.0001125, 37.0]
    features = []
    for index in range(segment_count):
        start = [127.0 + index * 0.0001125, 37.0 + (seam_latitude if index else 0.0)]
        end = [127.0 + (index + 1) * 0.0001125, 37.0]
        features.append(_tmap_line_fixture([start, end], 10, 10))
    before = deepcopy(features)

    if accepted:
        result = _normalize_tmap_feature_fixture(features, origin, destination)
        assert len(result.steps) == segment_count
        assert len(result.polyline) == segment_count + 1
    else:
        with pytest.raises(TmapPedestrianRouteError) as error:
            _normalize_tmap_feature_fixture(features, origin, destination)
        assert error.value.code == "route_geometry_disconnected"
        assert error.value.http_status == 502
    assert features == before


@pytest.mark.parametrize("duplicate_vertices", [False, True])
def test_tmap_connected_linestrings_keep_shared_endpoint_and_provider_contract(duplicate_vertices):
    origin = [127.11015314141542, 37.39472714688412]
    shared = [127.109, 37.398]
    destination = [127.10824367964793, 37.401937080111644]
    first = [origin, origin, shared, shared] if duplicate_vertices else [origin, shared]
    second = [shared, shared, destination, destination] if duplicate_vertices else [shared, destination]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture(first, 400, 360),
        _tmap_guide_fixture(shared, "Continue"),
        _tmap_line_fixture(second, 500, 450),
    ]
    before = deepcopy(features)

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [[point.longitude, point.latitude] for point in result.polyline] == [origin, shared, destination]
    assert [len(step.points) for step in result.steps] == ([4, 4] if duplicate_vertices else [2, 2])
    assert [step.index for step in result.steps] == [0, 1]
    assert [step.distance_m for step in result.steps] == [414, 486]
    assert [step.duration_s for step in result.steps] == [360, 450]
    assert [guide.index for guide in result.guide_points] == [0, 1]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 414]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [900, 486]
    for guide in result.guide_points:
        assert guide.bearing_deg == pytest.approx(_route_bearing_for_point(guide.point, result.polyline))
    assert result.summary.distance_m == 900
    assert result.summary.duration_s == 810
    assert result.schema_version == "walksafe.walking_route.v1"
    assert result.provider == "tmap_pedestrian"
    assert result.priority == "RECOMMEND"
    assert result.provider_result_code == 0
    assert result.provider_result_message == "OK"
    assert features == before


def test_tmap_single_provided_long_segment_remains_valid():
    origin = [127.11015314141542, 37.39472714688412]
    destination = [127.10824367964793, 37.401937080111644]

    result = _normalize_tmap_feature_fixture(
        [_tmap_line_fixture([origin, destination], 900, 810)], origin, destination,
    )

    assert [[point.longitude, point.latitude] for point in result.polyline] == [origin, destination]
    assert len(result.steps) == 1
    assert result.summary.distance_m == 900


def test_tmap_connected_degenerate_segment_keeps_existing_geometry_contract():
    origin = [127.11015314141542, 37.39472714688412]
    destination = [127.10824367964793, 37.401937080111644]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture([origin, origin], 0, 0),
        _tmap_line_fixture([origin, destination], 900, 810),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [[point.longitude, point.latitude] for point in result.polyline] == [origin, destination]
    assert [step.distance_m for step in result.steps] == [0, 900]
    assert result.guide_points[0].distance_from_start_m == 0
    assert result.guide_points[0].remaining_distance_m == 900
    assert result.guide_points[0].bearing_deg == pytest.approx(
        _route_bearing_for_point(result.guide_points[0].point, result.polyline),
    )


@pytest.mark.parametrize("leg_distance_m", [0, 11])
@pytest.mark.parametrize("duplicate_outgoing", [False, True])
def test_tmap_revisited_guide_uses_its_connected_outgoing_step(leg_distance_m, duplicate_outgoing):
    features, origin, north, destination = _tmap_loop_bearing_fixture(
        leg_distance_m=leg_distance_m, duplicate_outgoing=duplicate_outgoing,
    )
    before = deepcopy(features)

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [[point.longitude, point.latitude] for point in result.polyline] == [origin, north, origin, destination]
    assert _loop_original_guides(result)[0].bearing_deg == 0.0
    assert _loop_original_guides(result)[1].bearing_deg == pytest.approx(90.0, abs=0.001)
    assert [guide.index for guide in _loop_original_guides(result)] == [0, 2]
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 18 if leg_distance_m == 0 else 22]
    assert [guide.remaining_distance_m for guide in _loop_original_guides(result)] == [2 * leg_distance_m + 90, 72 if leg_distance_m == 0 else 90]
    assert [step.index for step in result.steps] == [0, 1, 2]
    assert len(result.steps[2].points) == (4 if duplicate_outgoing else 2)
    assert result.summary.distance_m == 2 * leg_distance_m + 90
    assert result.summary.duration_s == 100
    assert features == before


def test_tmap_consecutive_same_coordinate_guides_share_outgoing_visit():
    features, origin, _, destination = _tmap_loop_bearing_fixture()
    features.insert(5, _tmap_guide_fixture(origin, "Second visit repeated", 12))

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.bearing_deg for guide in _loop_original_guides(result)] == pytest.approx([0.0, 90.0, 90.0], abs=0.001)
    assert [guide.index for guide in _loop_original_guides(result)] == [0, 2, 3]
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 22, 22]
    assert [guide.remaining_distance_m for guide in _loop_original_guides(result)] == [112, 90, 90]


def test_tmap_unbound_mid_segment_guide_uses_owning_edge_bearing():
    origin = [0.0, 0.0]
    destination = [0.0018, 0.0]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture([origin, destination], 200, 160),
        _tmap_guide_fixture([0.0009, 0.0], "Halfway"),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert result.guide_points[1].distance_from_start_m == 100
    assert result.guide_points[1].bearing_deg == pytest.approx(90.0)


def test_tmap_unbound_revisit_uses_second_visit_outgoing_bearing():
    origin, north, destination = [0.0, 0.0], [0.0, 0.0001], [0.001, 0.0]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture([origin, north, origin, destination], 134, 100),
        _tmap_guide_fixture(north, "Turn back", 14),
        _tmap_guide_fixture(origin, "Turn east", 12),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert result.guide_points[-1].distance_from_start_m > result.guide_points[1].distance_from_start_m
    assert result.guide_points[-1].bearing_deg == pytest.approx(90.0)


def test_tmap_short_corner_uses_raw_progress_when_provider_metres_round_together():
    origin, corner, destination = [0.0, 0.0], [0.0, 0.000003], [0.001, 0.000003]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture([origin, corner, destination], 111, 90),
        _tmap_guide_fixture(corner, "Turn east", 12),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 0]
    assert result.guide_points[0].bearing_deg == 0.0
    assert result.guide_points[1].bearing_deg == pytest.approx(90.0)


def test_tmap_turn_guide_just_before_next_linestring_uses_outgoing_bearing():
    origin, corner, destination = [0.0, 0.0], [0.0, 0.00045], [0.00045, 0.00045]
    guide = [0.0, 0.000432]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture([origin, corner], 50, 40),
        _tmap_guide_fixture(guide, "Turn right", 12),
        _tmap_line_fixture([corner, destination], 50, 40),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert result.guide_points[1].distance_from_start_m == 50
    assert result.guide_points[1].bearing_deg == pytest.approx(90.0)


def test_tmap_terminal_guide_has_no_outgoing_bearing_even_on_short_route():
    origin, destination = [0.0, 0.0], [0.0001, 0.0]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture([origin, destination], 11, 10),
        _tmap_guide_fixture(destination, "Arrive"),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert result.guide_points[-1].distance_from_start_m == result.summary.distance_m
    assert result.guide_points[-1].bearing_deg is None


def test_tmap_unbound_revisit_without_unique_order_is_rejected():
    origin, north, destination = [0.0, 0.0], [0.0, 0.0001], [0.001, 0.0]
    features = [
        _tmap_guide_fixture(origin, "Start"),
        _tmap_line_fixture([origin, north, origin, destination], 134, 100),
        _tmap_guide_fixture(origin, "Unknown visit"),
    ]

    with pytest.raises(TmapPedestrianRouteError, match="guide position is ambiguous or out of order"):
        _normalize_tmap_feature_fixture(features, origin, destination)


@pytest.mark.parametrize(("north_latitude", "leg_distance_m"), [(37.0001, 11), (37.001, 111)])
def test_tmap_all_points_first_uses_ordered_visits_for_unbound_guides(north_latitude, leg_distance_m):
    loop, origin, north, destination = _tmap_loop_bearing_fixture(
        north_latitude=north_latitude, leg_distance_m=leg_distance_m,
    )
    features = [
        _tmap_guide_fixture(origin, "First origin guide"),
        _tmap_guide_fixture(origin, "Unbound repeated origin guide"),
        _tmap_guide_fixture(north, "Unbound north guide", 14),
        _tmap_guide_fixture(origin, "Return turn guide", 12),
        *[feature for feature in loop if feature["geometry"]["type"] == "LineString"],
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.bearing_deg for guide in _loop_original_guides(result)[:3]] == [0.0, 0.0, 180.0]
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)[:3]] == [0, 0, leg_distance_m]
    assert abs(_loop_original_guides(result)[3].distance_from_start_m - 2 * leg_distance_m) <= 1
    for guide, legacy_bearing in zip(_loop_original_guides(result), [0.0, 0.0, 180.0]):
        assert _route_bearing_for_point(guide.point, result.polyline) == legacy_bearing


def test_tmap_mixed_point_run_after_return_uses_resolved_visit():
    features, origin, north, destination = _tmap_loop_bearing_fixture()
    features.insert(4, _tmap_guide_fixture(north, "Nonstandard mixed guide"))

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.bearing_deg for guide in _loop_original_guides(result)] == pytest.approx([0.0, 180.0, 90.0], abs=0.001)
    assert _route_bearing_for_point(_loop_original_guides(result)[2].point, result.polyline) == 0.0
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 11, 22]


def test_tmap_unrecognized_feature_keeps_resolved_visit_bearing():
    features, origin, _, destination = _tmap_loop_bearing_fixture()
    features.insert(5, {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": []}})

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.bearing_deg for guide in _loop_original_guides(result)] == pytest.approx([0.0, 90.0], abs=0.001)
    assert _route_bearing_for_point(_loop_original_guides(result)[1].point, result.polyline) == 0.0
    assert len(result.steps) == 3
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 22]


@pytest.mark.parametrize(("east_longitude", "east_distance_m", "expected_bearing"), [
    (127.0001, 9, 0.0),
    (127.001, 90, None),
])
def test_tmap_terminal_guide_has_no_outgoing_bearing(east_longitude, east_distance_m, expected_bearing):
    features, origin, _, destination = _tmap_loop_bearing_fixture(
        east_longitude=east_longitude, east_distance_m=east_distance_m,
    )
    features.append(_tmap_guide_fixture(destination, "Finish"))

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    terminal = _loop_original_guides(result)[-1]
    # The terminal geometry progress has no outgoing edge regardless of route length.
    assert terminal.bearing_deg is None
    assert _route_bearing_for_point(terminal.point, result.polyline) == expected_bearing
    assert terminal.distance_from_start_m == result.summary.distance_m
    assert terminal.remaining_distance_m == 0


@pytest.mark.parametrize(("latitude_offset", "expected_unknown"), [
    (0.0000005, False),
    (0.0001, False),
    (0.001, True),
])
def test_tmap_non_start_guide_keeps_existing_match_and_nearest_thresholds(latitude_offset, expected_unknown):
    origin = [127.0, 37.0]
    destination = [127.001, 37.0]
    features = [
        _tmap_guide_fixture([origin[0], origin[1] + latitude_offset], "Unbound offset guide"),
        _tmap_line_fixture([origin, destination], 90, 80),
    ]

    if expected_unknown:
        point = RoutePoint(latitude=origin[1] + latitude_offset, longitude=origin[0])
        polyline = [RoutePoint(latitude=coordinate[1], longitude=coordinate[0]) for coordinate in (origin, destination)]
        assert _route_bearing_for_point(point, polyline) is None
        with pytest.raises(TmapPedestrianRouteError, match="guide position is ambiguous or out of order"):
            _normalize_tmap_feature_fixture(features, origin, destination)
        return
    result = _normalize_tmap_feature_fixture(features, origin, destination)

    guide = _loop_original_guides(result)[0]
    assert guide.bearing_deg == _route_bearing_for_point(guide.point, result.polyline)
    assert (guide.bearing_deg is None) is expected_unknown


@pytest.mark.parametrize("degenerate_count", [1, 2])
@pytest.mark.parametrize("leg_distance_m", [0, 11])
def test_tmap_revisited_guide_crosses_connected_zero_edges(degenerate_count, leg_distance_m):
    features, origin, north, destination = _tmap_loop_bearing_fixture(leg_distance_m=leg_distance_m)
    features[5:5] = [
        _tmap_line_fixture([origin, origin], 0, 0)
        for _ in range(degenerate_count)
    ]
    before = deepcopy(features)

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [[point.longitude, point.latitude] for point in result.polyline] == [origin, north, origin, destination]
    assert [guide.bearing_deg for guide in _loop_original_guides(result)] == pytest.approx([0.0, 90.0], abs=0.001)
    assert _route_bearing_for_point(_loop_original_guides(result)[1].point, result.polyline) == 0.0
    assert [guide.index for guide in _loop_original_guides(result)] == [0, 2]
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 18 if leg_distance_m == 0 else 22]
    assert [guide.remaining_distance_m for guide in _loop_original_guides(result)] == [2 * leg_distance_m + 90, 72 if leg_distance_m == 0 else 90]
    assert [step.index for step in result.steps] == list(range(3 + degenerate_count))
    assert [step.distance_m for step in result.steps] == ([9, 9] if leg_distance_m == 0 else [11, 11]) + [0] * degenerate_count + [72 if leg_distance_m == 0 else 90]
    assert [step.duration_s for step in result.steps] == [10, 10] + [0] * degenerate_count + [80]
    for step in result.steps[2:-1]:
        assert [[point.longitude, point.latitude] for point in step.points] == [origin, origin]
    assert result.summary.distance_m == 2 * leg_distance_m + 90
    assert result.summary.duration_s == 100
    assert features == before


def test_tmap_same_coordinate_guides_share_visit_across_zero_edges():
    features, origin, _, destination = _tmap_loop_bearing_fixture()
    features[5:5] = [
        _tmap_line_fixture([origin, origin], 0, 0),
        _tmap_guide_fixture(origin, "Repeated second visit after zero edge"),
        _tmap_line_fixture([origin, origin], 0, 0),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.bearing_deg for guide in _loop_original_guides(result)] == pytest.approx([0.0, 90.0, 90.0], abs=0.001)
    assert [guide.index for guide in _loop_original_guides(result)] == [0, 2, 3]
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 22, 22]
    assert [guide.remaining_distance_m for guide in _loop_original_guides(result)] == [112, 90, 90]
    assert len(result.steps) == 5


@pytest.mark.parametrize("breaker", [
    None,
    {},
    {"geometry": {"type": "Polygon", "coordinates": []}},
    {"geometry": {"type": "Point", "coordinates": []}},
    {"geometry": {"type": "LineString", "coordinates": []}},
])
def test_tmap_zero_edge_pending_guide_stops_at_unusable_feature_but_keeps_visit(breaker):
    features, origin, _, destination = _tmap_loop_bearing_fixture()
    features[5:5] = [
        _tmap_line_fixture([origin, origin], 0, 0),
        breaker,
        _tmap_line_fixture([origin, origin], 0, 0),
    ]
    before = deepcopy(features)

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.bearing_deg for guide in _loop_original_guides(result)] == pytest.approx([0.0, 90.0], abs=0.001)
    assert _route_bearing_for_point(_loop_original_guides(result)[1].point, result.polyline) == 0.0
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 22]
    assert len(result.steps) == 5
    assert result.summary.distance_m == 112
    assert result.summary.duration_s == 100
    assert features == before


@pytest.mark.parametrize("mixed_after_zero_edge", [False, True])
def test_tmap_zero_edge_mixed_guide_block_keeps_global_fallback(mixed_after_zero_edge):
    features, origin, north, destination = _tmap_loop_bearing_fixture()
    mixed = [
        _tmap_guide_fixture(north, "Different coordinate in pending block"),
        _tmap_guide_fixture(origin, "Same coordinate after mixed guide"),
    ]
    zero_edge = _tmap_line_fixture([origin, origin], 0, 0)
    features[5:5] = [zero_edge, *mixed, deepcopy(zero_edge)] if mixed_after_zero_edge else [*mixed, zero_edge]

    # The extra north guide is after the second origin visit, so its unique
    # geometry progress goes backwards. Its bearing helper still has the
    # same conservative fallback; that cannot make the whole route valid.
    before = deepcopy(features)
    with pytest.raises(TmapPedestrianRouteError, match="guide position is ambiguous or out of order"):
        _normalize_tmap_feature_fixture(features, origin, destination)
    geometry = _normalize_tmap_feature_fixture(
        [
            feature for feature in features
            if feature["geometry"]["type"] == "LineString"
            or feature.get("properties", {}).get("description") in {
                "First visit", "Turn back", "Second visit",
            }
        ], origin, destination,
    )
    for coordinate, expected, legacy in ((origin, None, 0.0), (north, 180.0, 180.0), (origin, None, 0.0)):
        point = RoutePoint(latitude=coordinate[1], longitude=coordinate[0])
        assert _route_bearing_for_point(point, geometry.polyline, require_bearing_consensus=True) == expected
        assert _route_bearing_for_point(point, geometry.polyline) == legacy
    assert geometry.summary.distance_m == 112
    assert geometry.summary.duration_s == 100
    assert features == before


@pytest.mark.parametrize(("east_longitude", "east_distance_m", "expected_bearing"), [
    (127.0001, 9, 0.0),
    (127.001, 90, None),
])
def test_tmap_terminal_zero_edges_without_outgoing_keep_global_fallback(east_longitude, east_distance_m, expected_bearing):
    features, origin, _, destination = _tmap_loop_bearing_fixture(
        east_longitude=east_longitude, east_distance_m=east_distance_m,
    )
    features.extend([
        _tmap_guide_fixture(destination, "Finish before zero edges"),
        _tmap_line_fixture([destination, destination], 0, 0),
        _tmap_line_fixture([destination, destination], 0, 0),
    ])

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    terminal = _loop_original_guides(result)[-1]
    assert terminal.bearing_deg is None
    assert _route_bearing_for_point(terminal.point, result.polyline) == expected_bearing
    assert terminal.distance_from_start_m == result.summary.distance_m
    assert terminal.remaining_distance_m == 0
    assert len(result.steps) == 5
    assert result.summary.distance_m == 22 + east_distance_m
    assert result.summary.duration_s == 100


def test_tmap_zero_edge_binding_preserves_provider_step_distance_and_time():
    features, origin, _, destination = _tmap_loop_bearing_fixture()
    features[5:5] = [
        _tmap_line_fixture([origin, origin], 11, 10),
        _tmap_guide_fixture(origin, "Same visit after provider step progress"),
    ]
    before = deepcopy(features)

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert [guide.bearing_deg for guide in _loop_original_guides(result)] == pytest.approx([0.0, 90.0, 90.0], abs=0.001)
    assert [guide.distance_from_start_m for guide in _loop_original_guides(result)] == [0, 25, 25]
    assert [guide.remaining_distance_m for guide in _loop_original_guides(result)] == [123, 98, 98]
    assert [step.distance_m for step in result.steps] == [12, 13, 0, 98]
    assert [step.duration_s for step in result.steps] == [10, 10, 10, 80]
    assert result.summary.distance_m == 123
    assert result.summary.duration_s == 110
    assert features == before


@pytest.mark.parametrize(("destination", "expected_bearing"), [
    ([0.0, 0.0001], 0.0),
    ([0.0001, 0.0], None),
    ([0.0000000001, 0.0001], None),
])
@pytest.mark.parametrize("guide_offset", [0.0, 0.0000005])
def test_tmap_unbound_match_bearings_require_exact_consensus(destination, expected_bearing, guide_offset):
    origin = [0.0, 0.0]
    north = [0.0, 0.0001]
    coordinates = [origin, north, origin, destination]
    features = [
        _tmap_line_fixture(coordinates, 40, 30),
        _tmap_guide_fixture([guide_offset, guide_offset], "Unbound repeated location"),
    ]
    before = deepcopy(features)

    geometry = _normalize_tmap_feature_fixture(features[:1], origin, destination)
    guide_point = RoutePoint(latitude=guide_offset, longitude=guide_offset)
    assert _route_bearing_for_point(guide_point, geometry.polyline, require_bearing_consensus=True) == expected_bearing
    assert _route_bearing_for_point(guide_point, geometry.polyline) == 0.0
    # Even the slightly tilted final edge is within the existing 1 m
    # projection tie of the earlier visits; float nearest alone is no proof.
    with pytest.raises(TmapPedestrianRouteError, match="guide position is ambiguous or out of order"):
        _normalize_tmap_feature_fixture(features, origin, destination)
    assert geometry.summary.distance_m == 40
    assert geometry.summary.duration_s == 30
    assert [[point.longitude, point.latitude] for point in geometry.polyline] == coordinates
    assert features == before


@pytest.mark.parametrize(("origin", "expected_bearing"), [
    ([-0.000001, -0.000001], 0.0),
    ([-0.000001000001, -0.000001], None),
    ([-0.000001, -0.000001000001], None),
])
def test_tmap_unbound_match_tier_preserves_each_axis_limit_and_precedence(origin, expected_bearing):
    north = [origin[0], origin[1] + 0.0001]
    other = [0.000001000001, 0.0]
    destination = [0.0001, 0.0]
    features = [
        _tmap_line_fixture([origin, north, other, destination], 40, 30),
        _tmap_guide_fixture([0.0, 0.0], "Match tier precedes rounded nearest ties"),
    ]

    result = _normalize_tmap_feature_fixture(features[:1], origin, destination)
    point = RoutePoint(latitude=0.0, longitude=0.0)
    # The two near-coincident legs revisit this position within the existing
    # 1 m projection tie; bearing-match precedence cannot prove the visit.
    with pytest.raises(TmapPedestrianRouteError, match="guide position is ambiguous or out of order"):
        _normalize_tmap_feature_fixture(features, origin, destination)

    assert _haversine_m(0.0, 0.0, origin[1], origin[0]) == 0
    assert _haversine_m(0.0, 0.0, other[1], other[0]) == 0
    assert _route_bearing_for_point(point, result.polyline, require_bearing_consensus=True) == expected_bearing
    assert _route_bearing_for_point(point, result.polyline) == 0.0


@pytest.mark.parametrize("directions_agree", [False, True])
@pytest.mark.parametrize("reverse_candidate_order", [False, True])
def test_tmap_unbound_rounded_nearest_ties_compare_different_coordinates(directions_agree, reverse_candidate_order):
    first = [-0.00005, 0.0]
    first_end = [-0.00005, 0.0001]
    second = [0.000051, 0.0]
    second_end = [0.000051, 0.0001] if directions_agree else [0.000151, 0.0]
    coordinates = [second, second_end, first, first_end] if reverse_candidate_order else [first, first_end, second, second_end]
    polyline = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
    guide = RoutePoint(latitude=0.0, longitude=0.0)

    assert first != second
    assert _haversine_m(0.0, 0.0, first[1], first[0]) == 6
    assert _haversine_m(0.0, 0.0, second[1], second[0]) == 6
    assert _route_bearing_for_point(guide, polyline, require_bearing_consensus=True) == (
        0.0 if directions_agree else None
    )
    expected_legacy = 90.0 if reverse_candidate_order and not directions_agree else 0.0
    assert _route_bearing_for_point(guide, polyline) == expected_legacy


@pytest.mark.parametrize("later_nearest_conflict", [False, True])
def test_tmap_unbound_nearer_candidate_resets_previous_tie_disagreement(later_nearest_conflict):
    coordinates = [
        [-0.00005, 0.0], [-0.00005, 0.0001],
        [0.00005, 0.0], [0.00015, 0.0],
        [0.00001, 0.0], [0.00001, -0.0001],
    ]
    if later_nearest_conflict:
        coordinates.extend([[-0.00001, 0.0], [-0.00001, 0.0001]])
    polyline = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
    guide = RoutePoint(latitude=0.0, longitude=0.0)

    assert _haversine_m(0.0, 0.0, 0.0, 0.00005) == 6
    assert _haversine_m(0.0, 0.0, 0.0, 0.00001) == 1
    assert _route_bearing_for_point(guide, polyline, require_bearing_consensus=True) == (
        None if later_nearest_conflict else 180.0
    )
    assert _route_bearing_for_point(guide, polyline) == 180.0


@pytest.mark.parametrize(("latitude", "rounded_distance", "legacy_bearing"), [
    (0.00018, 20, 0.0),
    (0.000185, 21, None),
])
def test_tmap_guide_bearing_uses_valid_30m_projection_not_legacy_vertex_cutoff(latitude, rounded_distance, legacy_bearing):
    origin = [0.0, latitude]
    destination = [0.0, latitude + 0.0001]
    features = [
        _tmap_line_fixture([origin, destination], 11, 10),
        _tmap_guide_fixture([0.0, 0.0], "Nearest range boundary"),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, destination)

    assert _haversine_m(0.0, 0.0, latitude, 0.0) == rounded_distance
    assert result.guide_points[0].bearing_deg == 0.0
    assert _route_bearing_for_point(result.guide_points[0].point, result.polyline) == legacy_bearing


def test_tmap_bound_span_keeps_first_match_while_same_unbound_span_requires_consensus():
    origin = [0.0, 0.0]
    north = [0.0, 0.0001]
    destination = [0.0001, 0.0]
    features = [
        _tmap_guide_fixture(origin, "Bound start of provided loop segment"),
        _tmap_line_fixture([origin, north, origin, destination], 40, 30),
        _tmap_guide_fixture(origin, "Unbound same coordinate after segment"),
    ]

    result = _normalize_tmap_feature_fixture(
        [*features[:2], _tmap_guide_fixture(north, "Turn back", 14),
         _tmap_guide_fixture(origin, "Turn east", 12)], origin, destination,
    )
    assert result.guide_points[0].bearing_deg == 0.0
    assert result.guide_points[0].distance_from_start_m == 0
    assert result.guide_points[0].remaining_distance_m == 40
    assert _route_bearing_for_point(result.guide_points[0].point, result.polyline) == 0.0
    assert _route_bearing_for_point(result.guide_points[0].point, result.polyline, require_bearing_consensus=True) is None
    assert len(result.steps) == 1
    with pytest.raises(TmapPedestrianRouteError, match="guide position is ambiguous or out of order"):
        _normalize_tmap_feature_fixture(features, origin, destination)


def test_tmap_terminal_coordinate_has_no_outgoing_bearing():
    origin = [0.0, 0.0]
    north = [0.0, 0.0001]
    features = [
        _tmap_line_fixture([origin, north, origin], 22, 20),
        _tmap_guide_fixture(north, "Turn back", 14),
        _tmap_guide_fixture(origin, "Terminal duplicate is not an outgoing candidate"),
    ]

    result = _normalize_tmap_feature_fixture(features, origin, origin)

    assert result.guide_points[-1].bearing_deg is None
    assert _route_bearing_for_point(result.guide_points[-1].point, result.polyline) == 0.0


@pytest.mark.parametrize("point_count", [0, 1])
def test_tmap_bearing_consensus_and_default_keep_short_polyline_unknown(point_count):
    point = RoutePoint(latitude=0.0, longitude=0.0)
    polyline = [point] * point_count

    assert _route_bearing_for_point(point, polyline) is None
    assert _route_bearing_for_point(point, polyline, require_bearing_consensus=True) is None


@pytest.mark.parametrize(("latitude", "expected_checks"), [(0.0, 2), (-0.0001, 3)])
def test_tmap_bearing_consensus_keeps_deadline_checkpoints(monkeypatch, latitude, expected_checks):
    checkpoints = []
    monkeypatch.setattr("backend.app.services.tmap_pedestrian._ensure_tmap_deadline", checkpoints.append)
    point = RoutePoint(latitude=latitude, longitude=0.0)
    polyline = [RoutePoint(latitude=index * 0.000000001, longitude=0.0) for index in range(258)]

    bearing = _route_bearing_for_point(
        point, polyline, deadline_at=12345.0, require_bearing_consensus=True,
    )

    assert bearing == 0.0
    assert checkpoints == [12345.0] * expected_checks


@pytest.mark.parametrize("matched_offset", [0.0000005, 0.000001])
def test_tmap_unbound_consensus_includes_distinct_coordinates_within_match_tolerance(matched_offset):
    origin = [0.0, 0.0]
    north = [0.0, 0.0001]
    other = [matched_offset, matched_offset]
    destination = [0.0001, matched_offset]
    features = [
        _tmap_line_fixture([origin, north, other, destination], 40, 30),
        _tmap_guide_fixture(origin, "All eligible coordinate matches contribute"),
    ]

    result = _normalize_tmap_feature_fixture(features[:1], origin, destination)
    point = RoutePoint(latitude=0.0, longitude=0.0)
    # The two near-coincident legs revisit this position within the existing
    # 1 m projection tie; bearing-match precedence cannot prove the visit.
    with pytest.raises(TmapPedestrianRouteError, match="guide position is ambiguous or out of order"):
        _normalize_tmap_feature_fixture(features, origin, destination)

    assert _route_bearing_for_point(point, result.polyline, require_bearing_consensus=True) is None
    assert _route_bearing_for_point(point, result.polyline) == 0.0


# S20-22/23: owned application runtime and actual spawn-worker observations.
# No main.py import/lifespan side effects or external provider/DB are exercised.


def _runtime_settings(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "tmap_app_key", "runtime-fixture-key")
    monkeypatch.setattr(settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_timeout_seconds", 10.0)
    monkeypatch.setattr(settings, "tmap_pedestrian_route_url", "https://example.test/routes")
    monkeypatch.setattr(settings, "tmap_poi_search_url", "https://example.test/pois")
    return settings


def _runtime_request():
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=37.0, longitude=127.0),
        destination=RoutePoint(latitude=37.0, longitude=127.001),
        priority="RECOMMEND",
    )


def _runtime_route_payload():
    return {
        "type": "FeatureCollection",
        "features": [
            _tmap_guide_fixture([127.0, 37.0], "Start"),
            _tmap_line_fixture([[127.0, 37.0], [127.001, 37.0]], 90, 80),
        ],
    }


def test_tmap_disconnected_geometry_error_crosses_worker_boundary(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    payload = _runtime_route_payload()
    payload["features"][1] = _tmap_line_fixture(
        [[127.0, 37.0], [127.0005, 37.0]], 45, 40,
    )
    payload["features"].append(_tmap_line_fixture(
        [[127.0005, 37.00045], [127.001, 37.0]], 45, 40,
    ))
    body = json.dumps(payload).encode("utf-8")

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings,
            lambda provider_request: httpx.Response(200, content=body),
        )
        reset_tmap_success_cache()
        try:
            with pytest.raises(TmapPedestrianRouteError) as error:
                await _fetch_tmap_pedestrian_route(_runtime_request(), settings, runtime=owner)
            assert error.value.code == "route_geometry_disconnected"
            assert error.value.http_status == 502
            assert error.value.message == "TMAP pedestrian route LineString segments are disconnected."
        finally:
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())


@pytest.mark.parametrize("coordinates", [
    [["127.0", 37.0], [127.001, 37.0]],
    [[127.0, 37.0], ["127.0005", 37.0004], [127.001, 37.0]],
    [[127.0, 37.0], ["127.001", 37.0]],
    [[None, None]],
], ids=["invalid-first", "invalid-middle-no-straight-bridge", "invalid-last", "all-invalid"])
def test_tmap_linestring_invalid_coordinate_fails_closed(coordinates):
    payload = _runtime_route_payload()
    payload["features"][1]["geometry"]["coordinates"] = coordinates
    original = deepcopy(payload)

    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _runtime_request())

    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert caught.value.message == "TMAP pedestrian route LineString has invalid coordinates."
    assert payload == original


@pytest.mark.parametrize("coordinates", [
    None,
    {"longitude": 127.0, "latitude": 37.0},
    ((127.0, 37.0), (127.001, 37.0)),
    [[127.0, 37.0], object(), [127.001, 37.0]],
], ids=["missing-list", "object-list", "tuple-list", "non-json-leaf"])
def test_tmap_linestring_invalid_coordinate_types_fail_closed(coordinates):
    payload = _runtime_route_payload()
    payload["features"][1]["geometry"]["coordinates"] = coordinates

    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _runtime_request())

    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert caught.value.message == "TMAP pedestrian route LineString has invalid coordinates."


@pytest.mark.parametrize("coordinate", [
    [True, 37.0],
    [127.0, False],
    [float("nan"), 37.0],
    [127.0, float("inf")],
    [181.0, 37.0],
    [127.0, 91.0],
], ids=["bool-longitude", "bool-latitude", "nan", "infinity", "longitude-range", "latitude-range"])
def test_tmap_linestring_invalid_numeric_coordinates_fail_closed(coordinate):
    with pytest.raises(TmapPedestrianRouteError) as caught:
        tmap_service._line_points([[127.0, 37.0], coordinate, [127.001, 37.0]])

    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert caught.value.message == "TMAP pedestrian route LineString has invalid coordinates."


def test_tmap_linestring_valid_middle_coordinate_preserves_shape():
    payload = _runtime_route_payload()
    coordinates = [[127.0, 37.0], [127.0005, 37.0], [127.001, 37.0]]
    payload["features"][1]["geometry"]["coordinates"] = coordinates

    result = _normalize_tmap_response(payload, _runtime_request())

    assert [[point.longitude, point.latitude] for point in result.polyline] == coordinates
    assert [[point.longitude, point.latitude] for point in result.steps[0].points] == coordinates


def test_tmap_linestring_bool_middle_coordinate_fails_without_changing_valid_line():
    coordinates = [[0, 0], [False, False], [0.001, 0]]

    with pytest.raises(TmapPedestrianRouteError) as caught:
        tmap_service._line_points(coordinates)

    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert caught.value.message == "TMAP pedestrian route LineString has invalid coordinates."

    valid_coordinates = [[0, 0], [0.0005, 0], [0.001, 0]]
    assert [
        [point.longitude, point.latitude]
        for point in tmap_service._line_points(valid_coordinates)
    ] == valid_coordinates


def test_tmap_linestring_invalid_middle_coordinate_survives_spawn_worker(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    payload = _runtime_route_payload()
    payload["features"][1]["geometry"]["coordinates"] = [
        [127.0, 37.0], ["127.0005", 37.0004], [127.001, 37.0],
    ]
    body = json.dumps(payload).encode("utf-8")
    hooks = _runtime_hooks(body)
    hooks.release.set()

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=body),
            hooks=hooks,
        )
        reset_tmap_success_cache()
        try:
            with pytest.raises(TmapPedestrianRouteError) as caught:
                await _fetch_tmap_pedestrian_route(_runtime_request(), settings, runtime=owner)
            assert caught.value.code == "invalid_tmap_response"
            assert caught.value.http_status == 502
            assert caught.value.message == "TMAP pedestrian route LineString has invalid coordinates."
            assert hooks.entered.is_set()
            assert hooks.child_pid.value > 0
            assert hooks.child_pid.value != os.getpid()
            assert tmap_dependency_readiness(30.0)["route_recent"] is False
        finally:
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())


# Canonical identity JSON (S06-15: display fields excluded): ["runtime-poi",null,"Runtime POI",37.0,127.0]
_RUNTIME_POI_PUBLIC_ID = "tmap-poi:v1:3028d12f223c3c157e12b96bd2913e6d5ea42a89eda650e11eb59f122bec6e45"


def _runtime_poi_body():
    return json.dumps({
        "searchPoiInfo": {"pois": {"poi": [
            {"id": "runtime-poi", "name": "Runtime POI", "noorLat": "37.0", "noorLon": "127.0"},
        ]}},
    }).encode()


def _runtime_hooks(body):
    context = multiprocessing.get_context("spawn")
    return TmapWorkerTestHooks(
        body=body, entered=context.Event(), release=context.Event(),
        child_pid=context.Value("i", 0), guard_checks=context.Value("i", 0),
        entered_count=context.Value("i", 0),
    )


async def _wait_runtime_condition(predicate):
    # Test watchdog, not an operational timeout or process-kill policy.
    async with asyncio.timeout(10.0):
        while not predicate():
            await asyncio.sleep(0.001)


async def _start_runtime_with_transport(monkeypatch, settings, handler, *, hooks=None):
    real_client = httpx.AsyncClient
    construction = {}
    transport = httpx.MockTransport(handler)

    def build_client(*, timeout, limits):
        construction["timeout"] = timeout
        construction["limits"] = limits
        instance = real_client(transport=transport, timeout=timeout, limits=limits, trust_env=False)
        construction["client"] = instance
        return instance

    owner = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds, worker_test_hooks=hooks)
    with monkeypatch.context() as context:
        context.setattr(runtime_module.httpx, "AsyncClient", build_client)
        await owner.start()
    return owner, construction


@pytest.mark.parametrize("mode", ["route", "poi"])
@pytest.mark.parametrize("state", ["absent", "unstarted", "closed"])
def test_tmap_fetch_requires_live_explicit_runtime(monkeypatch, mode, state):
    settings = _runtime_settings(monkeypatch)

    async def run():
        owner = None if state == "absent" else TmapRuntime(timeout_seconds=10.0)
        if state == "closed":
            await owner.start()
            await owner.aclose()
        reset_tmap_success_cache()
        try:
            with pytest.raises((TmapPedestrianRouteError, TmapPoiSearchError)) as caught:
                if mode == "route":
                    await _fetch_tmap_pedestrian_route(_runtime_request(), settings, runtime=owner)
                else:
                    await _fetch_tmap_poi_search("Runtime", settings, runtime=owner)
            assert caught.value.code == "tmap_processing_unavailable"
            assert caught.value.http_status == 503
            assert caught.value.message == "TMAP request processing is unavailable."
            assert caught.value.provider_status is None
            assert caught.value.provider_result_code is None
            assert tmap_dependency_readiness(30.0)["ready"] is False
        finally:
            if owner is not None:
                await owner.aclose()

    asyncio.run(run())


def test_tmap_runtime_spawn_process_keeps_async_probe_responsive(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    body = _runtime_poi_body()
    hooks = _runtime_hooks(body)

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=body), hooks=hooks,
        )
        request_task = asyncio.create_task(_fetch_tmap_poi_search("Runtime", settings, runtime=owner))
        try:
            await _wait_runtime_condition(hooks.entered.is_set)
            async_probe = asyncio.Event()

            async def observe_event_loop():
                await asyncio.sleep(0)
                async_probe.set()

            await observe_event_loop()
            assert async_probe.is_set()
            assert not request_task.done()
            assert hooks.child_pid.value > 0
            assert hooks.child_pid.value != os.getpid()
            assert hooks.guard_checks.value == 2
            hooks.release.set()
            result = await request_task
            assert result.results[0].id == _RUNTIME_POI_PUBLIC_ID
            assert result.results[0].point.latitude == 37.0
            assert result.results[0].distance_m is None
        finally:
            hooks.release.set()
            await asyncio.gather(request_task, return_exceptions=True)
            await owner.aclose()

    asyncio.run(run())


def test_tmap_runtime_cancelled_awaiters_terminate_cpu_and_reuse_slots(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    body = _runtime_poi_body()
    hooks = _runtime_hooks(body)
    provider_calls = []
    other_body = b'{"searchPoiInfo":{"pois":{"poi":[]}}}'

    def handler(request):
        provider_calls.append(request)
        return httpx.Response(200, content=body if len(provider_calls) <= 2 else other_body)

    async def run():
        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler, hooks=hooks)
        executor, shared_client = owner._executor, owner.client
        reset_tmap_success_cache()
        requests = [asyncio.create_task(_fetch_tmap_poi_search("Runtime", settings, runtime=owner)) for _ in range(2)]
        processes = ()
        try:
            await _wait_runtime_condition(lambda: hooks.entered_count.value == 2)
            processes = tuple(executor._processes.values())
            assert len(processes) == 2 and all(process.is_alive() for process in processes)
            with pytest.raises(TmapPoiSearchError) as caught:
                await _fetch_tmap_poi_search("No queue", settings, runtime=owner)
            assert caught.value.code == "tmap_capacity_exceeded"
            assert len(provider_calls) == 2
            for request in requests:
                request.cancel()
            results = await asyncio.gather(*requests, return_exceptions=True)
            assert all(isinstance(result, asyncio.CancelledError) for result in results)
            assert tmap_dependency_readiness(30.0)["poi_recent"] is False
            await _wait_runtime_condition(lambda: not owner._leases)
            assert all(not process.is_alive() for process in processes)
            assert tmap_dependency_readiness(30.0)["poi_recent"] is False
            result = await _fetch_tmap_poi_search("Reuse", settings, runtime=owner)
            assert result.results == []
            assert owner._executor is executor
            assert owner.client is shared_client
            assert len(provider_calls) == 3
            assert tmap_dependency_readiness(30.0)["poi_recent"] is True
        finally:
            if any(process.is_alive() for process in processes):
                hooks.release.set()
            await asyncio.gather(*requests, return_exceptions=True)
            await owner.aclose()

    asyncio.run(run())


def test_tmap_runtime_cancelled_cpu_job_exits_without_stopping_other_request(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    gated_body = _runtime_poi_body()
    hooks = _runtime_hooks(gated_body)
    other_body = b'{"searchPoiInfo":{"pois":{"poi":[]}}}'
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(200, content=gated_body if len(calls) == 1 else other_body)

    def process_exists(pid):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        return True

    async def run():
        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler, hooks=hooks)
        abandoned = asyncio.create_task(_fetch_tmap_poi_search("Blocked", settings, runtime=owner))
        try:
            await _wait_runtime_condition(hooks.entered.is_set)
            pid = hooks.child_pid.value
            assert pid > 0 and process_exists(pid)
            jobs = tuple(owner._executor._jobs.values())
            assert len(jobs) == 1 and jobs[0].process.pid == pid
            other = await _fetch_tmap_poi_search("Other", settings, runtime=owner)
            assert other.results == []
            abandoned.cancel()
            with pytest.raises(asyncio.CancelledError):
                await abandoned
            await _wait_runtime_condition(lambda: not process_exists(pid))
            await _wait_runtime_condition(lambda: not owner._leases)
            assert jobs[0].receiver.closed
            assert owner._executor._jobs == {}
            reused = await _fetch_tmap_poi_search("Reused", settings, runtime=owner)
            assert reused.results == []
            assert len(calls) == 3
        finally:
            if hooks.child_pid.value <= 0 or process_exists(hooks.child_pid.value):
                hooks.release.set()
            abandoned.cancel()
            await asyncio.gather(abandoned, return_exceptions=True)
            await owner.aclose()

    asyncio.run(run())


def test_tmap_runtime_cancelled_http_retains_slots_until_response_cleanup(monkeypatch):
    settings = _runtime_settings(monkeypatch)

    async def run():
        body_release, close_release = asyncio.Event(), asyncio.Event()
        entered, closing, closed, calls = [], [], [], []

        class OwnedStream(httpx.AsyncByteStream):
            async def __aiter__(self):
                entered.append(self)
                await body_release.wait()
                yield _runtime_poi_body()

            async def aclose(self):
                closing.append(self)
                await close_release.wait()
                closed.append(self)

        def handler(request):
            calls.append(request)
            return httpx.Response(200, stream=OwnedStream())

        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler)
        requests = [asyncio.create_task(_fetch_tmap_poi_search("Runtime", settings, runtime=owner)) for _ in range(2)]
        try:
            await _wait_runtime_condition(lambda: len(entered) == 2)
            for request in requests:
                request.cancel()
            await asyncio.gather(*requests, return_exceptions=True)
            with pytest.raises(TmapPoiSearchError, match="capacity is currently exhausted"):
                await _fetch_tmap_poi_search("No body", settings, runtime=owner)
            assert len(calls) == 2
            body_release.set()
            await _wait_runtime_condition(lambda: len(closing) == 2)
            assert closed == []
            assert len(owner._leases) == 2
            with pytest.raises(TmapPoiSearchError, match="capacity is currently exhausted"):
                await _fetch_tmap_poi_search("No cleanup bypass", settings, runtime=owner)
            assert len(calls) == 2
            close_release.set()
            await _wait_runtime_condition(lambda: not owner._leases)
            assert len(closed) == 2
            assert owner.client.is_closed is False
        finally:
            body_release.set()
            close_release.set()
            await asyncio.gather(*requests, return_exceptions=True)
            await owner.aclose()

    asyncio.run(run())


def test_tmap_runtime_preserves_absolute_deadline_across_http_and_cpu(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    body = _runtime_poi_body()
    hooks = _runtime_hooks(body)
    deadlines, submitted_deadlines = [], []

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=body), hooks=hooks,
        )
        try:
            # Warm the actual spawn worker using a different, non-gated response.
            async def warm():
                return await owner.process_response(
                    "poi", 200, b'{"searchPoiInfo":{"pois":{"poi":[]}}}',
                    {"query": "Warm", "limit": 5, "origin_lat": None, "origin_lng": None},
                    deadline_at=warm_deadline,
                )
            warm_deadline = monotonic() + 10.0
            await owner.execute(warm, deadline_at=warm_deadline)
            original_stream = tmap_service._stream_tmap_response
            original_submit = owner._executor.submit

            async def observed_stream(client, method, url, *, deadline_at, params, headers, runtime, json_body=None, timeout_seconds=None):
                assert runtime is owner
                deadlines.append(deadline_at)
                await asyncio.sleep(0.02)
                return await original_stream(
                    client, method, url, deadline_at=deadline_at, params=params,
                    headers=headers, runtime=runtime, json_body=json_body, timeout_seconds=timeout_seconds,
                )

            def observed_submit(function, *arguments, **keywords):
                submitted_deadlines.append(arguments[-1])
                return original_submit(function, *arguments, **keywords)

            monkeypatch.setattr(tmap_service, "_stream_tmap_response", observed_stream)
            monkeypatch.setattr(owner._executor, "submit", observed_submit)
            monkeypatch.setattr(settings, "tmap_timeout_seconds", 0.2)
            request = asyncio.create_task(_fetch_tmap_poi_search("Runtime", settings, runtime=owner))
            await _wait_runtime_condition(hooks.entered.is_set)
            with pytest.raises(TmapPoiSearchError) as caught:
                await request
            assert caught.value.code == "tmap_timeout"
            assert caught.value.http_status == 504
            assert deadlines == submitted_deadlines
            assert len(deadlines) == 1
            assert len(owner._leases) == 1
            hooks.release.set()
            await _wait_runtime_condition(lambda: not owner._leases)
        finally:
            hooks.release.set()
            await owner.aclose()

    asyncio.run(run())


@pytest.mark.parametrize("failure", ["executor_closed", "worker_exited"])
def test_tmap_runtime_submit_or_broken_process_is_local_unavailable(monkeypatch, failure):
    settings = _runtime_settings(monkeypatch)

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=_runtime_poi_body()),
        )
        reset_tmap_success_cache()
        try:
            if failure == "executor_closed":
                await asyncio.to_thread(owner._executor.shutdown, wait=True)
            else:
                submit = owner._executor.submit

                def crash_worker(*args):
                    return submit(os._exit, 0)

                monkeypatch.setattr(owner._executor, "submit", crash_worker)
            with pytest.raises(TmapPoiSearchError) as caught:
                await _fetch_tmap_poi_search("Runtime", settings, runtime=owner)
            assert caught.value.code == "tmap_processing_unavailable"
            assert caught.value.http_status == 503
            assert caught.value.message == "TMAP request processing is unavailable."
            assert caught.value.provider_status is None
            assert caught.value.provider_result_code is None
            assert owner._leases == {}
            assert tmap_dependency_readiness(30.0)["poi_recent"] is False
            with pytest.raises(TmapPoiSearchError, match="processing is unavailable"):
                await _fetch_tmap_poi_search("No restart", settings, runtime=owner)
        finally:
            await owner.aclose()

    asyncio.run(run())


def test_tmap_runtime_partial_start_failure_closes_actual_client(monkeypatch):
    real_client = httpx.AsyncClient
    created = []

    def build_client(*, timeout, limits):
        client = real_client(timeout=timeout, limits=limits, trust_env=False)
        created.append(client)
        return client

    def failed_executor(loop, hooks):
        assert loop is asyncio.get_running_loop()
        assert hooks is None
        raise RuntimeError("private-startup-detail")

    async def run():
        monkeypatch.setattr(runtime_module.httpx, "AsyncClient", build_client)
        monkeypatch.setattr(runtime_module, "_IsolatedProcessExecutor", failed_executor)
        owner = TmapRuntime(timeout_seconds=4.0)
        with pytest.raises(TmapRuntimeError) as caught:
            await owner.start()
        assert caught.value.code == "tmap_processing_unavailable"
        assert caught.value.message == "TMAP request processing is unavailable."
        assert len(created) == 1
        assert created[0].is_closed
        await owner.aclose()
        owner.stop_admission()

    asyncio.run(run())


@pytest.mark.parametrize("envelope", [
    [],
    {"kind": "success", "value": {}},
    {"kind": "domain_error", "code": "tmap_rate_limited", "message": "private-provider-details",
     "http_status": 503, "provider_status": 429, "provider_result_code": None},
])
def test_tmap_runtime_rejects_actual_process_malformed_envelope(monkeypatch, envelope):
    settings = _runtime_settings(monkeypatch)

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=_runtime_poi_body()),
        )
        original_submit = owner._executor.submit

        def malformed_reply(function, *arguments):
            assert function is runtime_module._process_response_job
            # Fault injection still uses a real child and a real completed Future.
            return original_submit(type(envelope), envelope)

        monkeypatch.setattr(owner._executor, "submit", malformed_reply)
        try:
            with pytest.raises(TmapPoiSearchError) as caught:
                await _fetch_tmap_poi_search("Runtime", settings, runtime=owner)
            assert caught.value.code == "tmap_processing_unavailable"
            assert caught.value.provider_status is None
            assert caught.value.provider_result_code is None
            assert caught.value.message == "TMAP request processing is unavailable."
            assert not owner._accepting
            assert not owner._leases
        finally:
            await owner.aclose()

    asyncio.run(run())


@pytest.mark.parametrize("mode", ["route", "poi"])
@pytest.mark.parametrize("failure", ["invalid_json", "provider_429"])
def test_tmap_runtime_preserves_allowed_domain_error_and_reuses_pool(monkeypatch, mode, failure):
    settings = _runtime_settings(monkeypatch)
    attempts = []

    def handler(request):
        attempts.append(request)
        if len(attempts) == 1:
            return httpx.Response(200, content=b"{") if failure == "invalid_json" else httpx.Response(
                429, json={"error": {"message": "private-provider-details"}},
            )
        return httpx.Response(200, json=_runtime_route_payload()) if mode == "route" else httpx.Response(
            200, content=_runtime_poi_body(),
        )

    async def run():
        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler)
        executor = owner._executor

        async def fetch():
            if mode == "route":
                return await _fetch_tmap_pedestrian_route(_runtime_request(), settings, runtime=owner)
            return await _fetch_tmap_poi_search("Runtime", settings, runtime=owner)

        try:
            with pytest.raises((TmapPedestrianRouteError, TmapPoiSearchError)) as caught:
                await fetch()
            assert caught.value.code == ("invalid_tmap_response" if failure == "invalid_json" else "tmap_rate_limited")
            assert caught.value.http_status == (502 if failure == "invalid_json" else 503)
            assert caught.value.provider_status == (None if failure == "invalid_json" else 429)
            assert "private-provider-details" not in caught.value.message
            assert caught.value.provider_result_code is None
            assert owner._accepting
            assert not owner._leases
            if failure == "provider_429":
                with pytest.raises((TmapPedestrianRouteError, TmapPoiSearchError)) as blocked:
                    await fetch()
                assert blocked.value.code == "tmap_rate_limited"
                assert blocked.value.http_status == 503
                assert blocked.value.provider_status is None
                assert len(attempts) == 1
                owner._rate_limited_until = runtime_module.monotonic() - 1
            result = await fetch()
            assert isinstance(result, WalkingRouteResponse if mode == "route" else DestinationSearchResponse)
            assert owner._executor is executor
            assert len(attempts) == 2
        finally:
            await owner.aclose()

    asyncio.run(run())


def test_tmap_rate_limit_retry_after_blocks_both_live_endpoints_without_retry(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    attempts = []

    def handler(request):
        attempts.append(request)
        if len(attempts) == 1:
            return httpx.Response(429, headers={"Retry-After": "60"}, json={"error": {"message": "private"}})
        return httpx.Response(200, content=_runtime_poi_body())

    async def run():
        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler)
        try:
            with pytest.raises(TmapPedestrianRouteError) as limited:
                await _fetch_tmap_pedestrian_route(_runtime_request(), settings, runtime=owner)
            assert limited.value.code == "tmap_rate_limited"
            assert limited.value.provider_status == 429
            remaining = owner._rate_limited_until - runtime_module.monotonic()
            assert 45 < remaining <= 60

            with pytest.raises(TmapPoiSearchError) as blocked:
                await _fetch_tmap_poi_search("Runtime", settings, runtime=owner)
            assert blocked.value.code == "tmap_rate_limited"
            assert blocked.value.http_status == 503
            assert blocked.value.provider_status is None
            assert len(attempts) == 1

            owner._rate_limited_until = runtime_module.monotonic() - 1
            result = await _fetch_tmap_poi_search("Runtime", settings, runtime=owner)
            assert isinstance(result, DestinationSearchResponse)
            assert len(attempts) == 2
        finally:
            await owner.aclose()

    asyncio.run(run())


@pytest.mark.parametrize(("retry_after", "expected_seconds"), [
    ("10000000000", 300),
    ("9" * 5000, 300),
    ("000000000000000299", 299),
    ("000000000000000001", 30),
])
def test_tmap_rate_limit_long_numeric_retry_after_preserves_bounded_delay(
    monkeypatch, retry_after, expected_seconds,
):
    monkeypatch.setattr(runtime_module, "monotonic", lambda: 1000.0)
    owner = runtime_module.TmapRuntime(timeout_seconds=1.0)
    owner.record_rate_limit_response(429, retry_after)
    assert owner._rate_limited_until == 1000.0 + expected_seconds


def test_tmap_runtime_shared_client_two_endpoints_and_response_close_ownership(monkeypatch):
    settings = _runtime_settings(monkeypatch)

    async def run():
        responses_closed, client_closed, runtime_arguments = [], [], []

        class TrackedBody(httpx.AsyncByteStream):
            def __init__(self, content):
                self.content = content

            async def __aiter__(self):
                yield self.content

            async def aclose(self):
                responses_closed.append(self)

        def handler(request):
            body = json.dumps(_runtime_route_payload()).encode() if request.url.path == "/routes" else _runtime_poi_body()
            return httpx.Response(200, stream=TrackedBody(body))

        owner, construction = await _start_runtime_with_transport(monkeypatch, settings, handler)
        shared = owner.client
        actual_close = shared.aclose

        async def observed_close():
            client_closed.append(shared)
            await actual_close()

        async def route_fetch(request, current_settings, *, runtime):
            runtime_arguments.append(runtime)
            return await _fetch_tmap_pedestrian_route(request, current_settings, runtime=runtime)

        async def poi_fetch(query, current_settings, *, limit, origin_lat=None, origin_lng=None, runtime):
            runtime_arguments.append(runtime)
            return await _fetch_tmap_poi_search(
                query, current_settings, limit=limit, origin_lat=origin_lat, origin_lng=origin_lng,
                runtime=runtime,
            )

        monkeypatch.setattr(shared, "aclose", observed_close)
        monkeypatch.setattr(navigation_api, "fetch_tmap_pedestrian_route", route_fetch)
        monkeypatch.setattr(navigation_api, "fetch_tmap_poi_search", poi_fetch)
        async with _navigation_runtime_app(settings, runtime=owner) as (application, _):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=application), base_url="http://testserver",
            ) as api:
                route = await api.post("/navigation/walking", json=_runtime_request().model_dump(mode="json"))
                poi = await api.get("/navigation/destinations/search", params={"query": "Runtime"})
            assert route.status_code == 200, route.text
            assert poi.status_code == 200, poi.text
            assert route.json()["summary"] == {"distance_m": 90, "duration_s": 80}
            assert poi.json()["results"][0]["id"] == _RUNTIME_POI_PUBLIC_ID
            assert runtime_arguments == [owner, owner]
            assert construction["client"] is shared
            assert construction["limits"].max_connections == 2
            assert construction["limits"].max_keepalive_connections == 2
            assert construction["timeout"] == settings.tmap_timeout_seconds
            assert shared.timeout.pool == settings.tmap_timeout_seconds
            assert len(responses_closed) == 2
            assert client_closed == []
            assert not shared.is_closed
        assert client_closed == [shared]
        assert shared.is_closed
        assert owner._executor is None
        assert not owner._leases

    asyncio.run(run())


def test_tmap_runtime_does_not_close_borrowed_http_client(monkeypatch):
    settings = _runtime_settings(monkeypatch)

    async def run():
        owner = TmapRuntime(timeout_seconds=10.0)
        borrowed = httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, content=_runtime_poi_body())),
            trust_env=False,
        )
        try:
            await owner.start()
            result = await _fetch_tmap_poi_search("Runtime", settings, runtime=owner, http_client=borrowed)
            assert result.results
            assert not borrowed.is_closed
            await owner.aclose()
            assert not borrowed.is_closed
        finally:
            await owner.aclose()
            await borrowed.aclose()
        assert borrowed.is_closed

    asyncio.run(run())


def test_tmap_runtime_stop_and_cancelled_shutdown_wait_for_real_cpu(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    hooks = _runtime_hooks(_runtime_poi_body())

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=hooks.body), hooks=hooks,
        )
        shared = owner.client
        request = asyncio.create_task(_fetch_tmap_poi_search("Runtime", settings, runtime=owner))
        closing = None
        try:
            await _wait_runtime_condition(hooks.entered.is_set)
            processes = tuple(owner._executor._processes.values())
            owner.stop_admission()
            owner.stop_admission()
            with pytest.raises(TmapPoiSearchError, match="processing is unavailable"):
                await _fetch_tmap_poi_search("After stop", settings, runtime=owner)
            closing = asyncio.create_task(owner.aclose())
            await asyncio.sleep(0)
            closing.cancel()
            await asyncio.sleep(0)
            assert not closing.done()
            assert not shared.is_closed
            assert any(process.is_alive() for process in processes)
            hooks.release.set()
            result = await request
            assert result.results[0].id == _RUNTIME_POI_PUBLIC_ID
            with pytest.raises(asyncio.CancelledError):
                await closing
            assert shared.is_closed
            assert not owner._leases
            assert owner._executor is None
            assert all(not process.is_alive() for process in processes)
            await owner.aclose()
        finally:
            hooks.release.set()
            await asyncio.gather(request, return_exceptions=True)
            if closing is not None:
                await asyncio.gather(closing, return_exceptions=True)
            await owner.aclose()

    asyncio.run(run())


def test_tmap_runtime_rejects_cross_loop_owner_without_provider_io(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, content=_runtime_poi_body())

    async def run():
        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler)
        try:
            def another_loop():
                with pytest.raises(TmapPoiSearchError) as caught:
                    asyncio.run(_fetch_tmap_poi_search("Runtime", settings, runtime=owner))
                return caught.value
            error = await asyncio.to_thread(another_loop)
            assert error.code == "tmap_processing_unavailable"
            assert requests == []
            assert not owner._leases
        finally:
            await owner.aclose()

    asyncio.run(run())


def _probe_transport_response(request, observed):
    observed.append(request)
    if request.method == "POST":
        sent = json.loads(request.content)
        origin = [sent["startX"], sent["startY"]]
        destination = [sent["endX"], sent["endY"]]
        return httpx.Response(200, json={
            "type": "FeatureCollection",
            "features": [
                _tmap_guide_fixture(origin, "Start"),
                _tmap_line_fixture([origin, destination], 30, 30),
            ],
        })
    return httpx.Response(200, content=_runtime_poi_body())


def test_tmap_app_probe_uses_owner_client_and_preserves_phase_timeout(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    monkeypatch.setattr(settings, "tmap_readiness_probe_timeout_seconds", 3.0)
    observed = []

    async def run():
        owner, construction = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: _probe_transport_response(request, observed),
        )
        shared = owner.client
        reset_tmap_success_cache()
        try:
            result = await probe_tmap_dependencies(settings, runtime=owner)
            assert result["ready"] is True
            assert len(observed) == 2
            assert [request.method for request in observed] == ["POST", "GET"]
            assert all(request.extensions["timeout"]["pool"] == 3.0 for request in observed)
            assert shared.timeout.pool == 10.0
            assert owner.client is construction["client"]
            assert not shared.is_closed
        finally:
            await owner.aclose()

    asyncio.run(run())


def test_tmap_standalone_probe_explicitly_closes_single_scoped_owner(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    real_client = httpx.AsyncClient
    owners, clients, observed = [], [], []

    class ObservedRuntime(TmapRuntime):
        def __init__(self, *, timeout_seconds):
            super().__init__(timeout_seconds=timeout_seconds)
            owners.append(self)

    def build_client(*, timeout, limits):
        client = real_client(
            transport=httpx.MockTransport(lambda request: _probe_transport_response(request, observed)),
            timeout=timeout, limits=limits, trust_env=False,
        )
        clients.append(client)
        return client

    async def run():
        monkeypatch.setattr(tmap_service, "TmapRuntime", ObservedRuntime)
        monkeypatch.setattr(runtime_module.httpx, "AsyncClient", build_client)
        reset_tmap_success_cache()
        result = await probe_tmap_dependencies(settings)
        assert result["ready"] is True
        assert len(owners) == len(clients) == 1
        assert len(observed) == 2
        assert clients[0].is_closed
        assert owners[0]._executor is None
        assert not owners[0]._leases

    asyncio.run(run())


@pytest.mark.parametrize("mode", ["route", "poi"])
def test_tmap_response_job_walks_strings_once_and_direct_normalizer_still_checks(monkeypatch, mode):
    walked = []
    original_walk = tmap_service._json_has_oversized_string

    def observed_walk(payload, *, deadline_at=None):
        walked.append(payload)
        return original_walk(payload, deadline_at=deadline_at)

    monkeypatch.setattr(tmap_service, "_json_has_oversized_string", observed_walk)
    if mode == "route":
        payload = _runtime_route_payload()
        arguments = {"request": _runtime_request().model_dump(mode="json")}
        direct = lambda value: _normalize_tmap_response(value, _runtime_request())
    else:
        payload = json.loads(_runtime_poi_body())
        arguments = {"query": "Runtime", "limit": 5, "origin_lat": None, "origin_lng": None}
        direct = lambda value: _normalize_poi_search_response(value, "Runtime")
    result = tmap_service._process_tmap_response(
        mode, 200, json.dumps(payload).encode(), arguments, deadline_at=monotonic() + 10.0,
    )
    assert isinstance(result, WalkingRouteResponse if mode == "route" else DestinationSearchResponse)
    assert len(walked) == 1
    direct(payload)
    assert len(walked) == 2
    oversized = deepcopy(payload)
    oversized["extra"] = "x" * (TMAP_MAX_STRING_LENGTH + 1)
    with pytest.raises((TmapPedestrianRouteError, TmapPoiSearchError)) as caught:
        direct(oversized)
    assert caught.value.code == "invalid_tmap_response"
    assert len(walked) == 3


def test_tmap_bound_guides_compute_each_proven_outgoing_edge_once(monkeypatch):
    features, origin, north, destination = _tmap_loop_bearing_fixture()
    features.insert(1, _tmap_guide_fixture(origin, "First duplicate"))
    features.insert(5, _tmap_guide_fixture(origin, "Second duplicate"))
    actual_bearing = tmap_service._bearing_deg
    edges = []

    def observed_bearing(start, end):
        edges.append((start.latitude, start.longitude, end.latitude, end.longitude))
        return actual_bearing(start, end)

    def unexpected_global_scan(*args, **kwargs):
        pytest.fail("A proven bound outgoing edge must not rescan/slice the polyline")

    monkeypatch.setattr(tmap_service, "_bearing_deg", observed_bearing)
    monkeypatch.setattr(tmap_service, "_route_bearing_for_point", unexpected_global_scan)
    result = _normalize_tmap_feature_fixture(features, origin, destination)
    assert len(edges) == 3  # north U-turn is now a required guided edge
    original_guides = _loop_original_guides(result)
    assert [guide.bearing_deg for guide in original_guides[:2]] == [0.0, 0.0]
    assert original_guides[2].bearing_deg == pytest.approx(90.0, abs=0.001)
    assert original_guides[3].bearing_deg == original_guides[2].bearing_deg
    assert result.summary.distance_m == 112
    assert len(result.steps) == 3


@pytest.mark.parametrize("directions_agree", [False, True])
def test_tmap_visit_bearing_memo_is_per_edge_and_per_response(monkeypatch, directions_agree):
    origin, north = [127.0, 37.0], [127.0, 37.0001]
    destination = north if directions_agree else [127.001, 37.0]
    features = [
        _tmap_guide_fixture(origin, "A"), _tmap_guide_fixture(origin, "A duplicate"),
        _tmap_guide_fixture(north, "B", 14),
        _tmap_guide_fixture(origin, "A again", 14 if directions_agree else 12),
        _tmap_line_fixture([origin, north], 11, 10),
        _tmap_line_fixture([north, origin], 11, 10),
        _tmap_line_fixture([origin, destination], 11 if directions_agree else 90, 80),
    ]
    measured_edges = []
    original_bearing = tmap_service._bearing_deg

    def observed_bearing(start, end):
        measured_edges.append((start.latitude, start.longitude, end.latitude, end.longitude))
        return original_bearing(start, end)

    def unexpected_global_scan(*args, **kwargs):
        pytest.fail("Normalized guide bearings must use resolved visits, not a coordinate-wide scan")

    monkeypatch.setattr(tmap_service, "_bearing_deg", observed_bearing)
    monkeypatch.setattr(tmap_service, "_route_bearing_for_point", unexpected_global_scan)
    for repeat in (1, 2):
        result = _normalize_tmap_feature_fixture(features, origin, destination)
        expected_later = 0.0 if directions_agree else pytest.approx(90.0, abs=0.001)
        assert [result.guide_points[index].bearing_deg for index in (0, 1)] == [0.0, 0.0]
        assert result.guide_points[3].bearing_deg == expected_later
        assert result.guide_points[2].bearing_deg == 180.0
        assert len(measured_edges) == repeat * 3
        assert len(result.steps) == 3
        assert len(result.guide_points) == 4


# Ownership repair: a real HTTPX stream close can outlive the request deadline.


@pytest.mark.parametrize("interruption", ["deadline", "cancel"])
def test_tmap_http_cleanup_keeps_each_lease_until_actual_close(monkeypatch, interruption):
    settings = _runtime_settings(monkeypatch)
    monkeypatch.setattr(settings, "tmap_timeout_seconds", 0.1)

    async def run():
        streams, provider_calls, submissions, released, successes = [], [], [], [], []
        original_record = tmap_service._record_tmap_success

        def observed_record(dependency, *, now=None):
            successes.append(dependency)
            original_record(dependency, now=now)

        monkeypatch.setattr(tmap_service, "_record_tmap_success", observed_record)

        class ObservedLeases(dict):
            def pop(self, key, *default):
                released.append(key)
                return super().pop(key, *default)

        class ClosingStream(httpx.AsyncByteStream):
            def __init__(self):
                self.release = asyncio.Event()
                self.entered = asyncio.Event()
                self.close_calls = 0
                self.completed = False
                self.owner_task = asyncio.current_task()

            async def __aiter__(self):
                yield _runtime_poi_body()

            async def aclose(self):
                self.close_calls += 1
                self.entered.set()
                await self.release.wait()
                self.completed = True

        def handler(request):
            provider_calls.append(request)
            if len(provider_calls) <= 2:
                stream = ClosingStream()
                streams.append(stream)
                return httpx.Response(200, stream=stream)
            return httpx.Response(200, content=_runtime_poi_body())

        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler)
        owner._leases = ObservedLeases()
        original_submit = owner._executor.submit

        def observed_submit(function, *arguments, **keywords):
            submissions.append((function, arguments))
            return original_submit(function, *arguments, **keywords)

        monkeypatch.setattr(owner._executor, "submit", observed_submit)
        reset_tmap_success_cache()
        requests = [
            asyncio.create_task(_fetch_tmap_poi_search("Closing", settings, runtime=owner))
            for _ in range(2)
        ]
        try:
            await _wait_runtime_condition(
                lambda: len(streams) == 2 and all(stream.entered.is_set() for stream in streams),
            )
            if interruption == "cancel":
                for request in requests:
                    request.cancel()
            results = await asyncio.gather(*requests, return_exceptions=True)
            if interruption == "deadline":
                assert all(isinstance(result, TmapPoiSearchError) for result in results)
                assert all(result.code == "tmap_timeout" and result.http_status == 504 for result in results)
            else:
                assert all(isinstance(result, asyncio.CancelledError) for result in results)
                # Also let the original HTTP deadlines expire while close is gated.
                await asyncio.sleep(settings.tmap_timeout_seconds)
            assert len(owner._leases) == 2
            assert all(not stream.completed and stream.close_calls == 1 for stream in streams)
            assert released == []
            assert submissions == []
            assert successes == []
            with pytest.raises(TmapPoiSearchError) as caught:
                await _fetch_tmap_poi_search("Still full", settings, runtime=owner)
            assert caught.value.code == "tmap_capacity_exceeded"
            assert len(provider_calls) == 2

            streams[0].release.set()
            await _wait_runtime_condition(lambda: len(owner._leases) == 1)
            assert streams[0].completed
            assert released.count(streams[0].owner_task) == 1
            assert released.count(streams[1].owner_task) == 0
            assert submissions == []
            assert successes == []

            # Only the confirmed free slot can now receive new real CPU work.
            monkeypatch.setattr(settings, "tmap_timeout_seconds", 10.0)
            result = await _fetch_tmap_poi_search("One free slot", settings, runtime=owner)
            assert result.results[0].id == _RUNTIME_POI_PUBLIC_ID
            assert len(submissions) == 1
            assert successes == ["poi"]
            assert len(provider_calls) == 3
            assert len(owner._leases) == 1

            streams[1].release.set()
            await _wait_runtime_condition(lambda: not owner._leases)
            assert all(stream.completed and stream.close_calls == 1 for stream in streams)
            assert all(released.count(stream.owner_task) == 1 for stream in streams)
            assert successes == ["poi"]
            assert len(submissions) == 1
        finally:
            for stream in streams:
                stream.release.set()
            await asyncio.gather(*requests, return_exceptions=True)
            await owner.aclose()

    asyncio.run(run())


def test_tmap_failed_http_cleanup_quarantines_response_and_lease(monkeypatch):
    settings = _runtime_settings(monkeypatch)

    async def run():
        responses, streams, submissions = [], [], []

        class FailingCloseStream(httpx.AsyncByteStream):
            def __init__(self):
                self.close_calls = 0

            async def __aiter__(self):
                yield _runtime_poi_body()

            async def aclose(self):
                self.close_calls += 1
                raise RuntimeError("private-response-cleanup-detail")

        def handler(request):
            stream = FailingCloseStream()
            response = httpx.Response(200, stream=stream)
            streams.append(stream)
            responses.append(response)
            return response

        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler)
        shared = owner.client
        original_submit = owner._executor.submit

        def observed_submit(function, *arguments, **keywords):
            submissions.append(function)
            return original_submit(function, *arguments, **keywords)

        monkeypatch.setattr(owner._executor, "submit", observed_submit)
        reset_tmap_success_cache()
        try:
            with pytest.raises(TmapPoiSearchError) as caught:
                await _fetch_tmap_poi_search("Cleanup failure", settings, runtime=owner)
            assert caught.value.code == "tmap_processing_unavailable"
            assert caught.value.message == "TMAP request processing is unavailable."
            assert caught.value.provider_status is None
            assert caught.value.provider_result_code is None
            assert len(owner._leases) == 1
            lease = next(iter(owner._leases.values()))
            assert lease.http_cleanup_failed
            assert lease.http_response is responses[0]
            assert streams[0].close_calls == 1
            assert not owner._accepting
            assert submissions == []
            assert tmap_dependency_readiness(30.0)["poi_recent"] is False
            with pytest.raises(TmapPoiSearchError, match="processing is unavailable"):
                await _fetch_tmap_poi_search("No replacement", settings, runtime=owner)
            assert len(responses) == 1
            for _ in range(2):
                with pytest.raises(TmapRuntimeError) as closed:
                    await owner.aclose()
                assert closed.value.code == "tmap_processing_unavailable"
                assert not owner._closed
                assert owner._close_failures == ("http_cleanup",)
                assert owner._executor is None
                assert owner._client is shared
                assert shared.is_closed
                assert len(owner._leases) == 1
                assert streams[0].close_calls == 1
        finally:
            # This synthetic failing response owns no external socket. Runtime
            # still attempts its real pool/client cleanup and reports quarantine.
            try:
                await owner.aclose()
            except TmapRuntimeError:
                pass

    asyncio.run(run())


@pytest.mark.parametrize("failure", ["executor", "client", "client_after_close", "both"])
def test_tmap_shutdown_failure_keeps_unconfirmed_refs_and_repeated_failure(monkeypatch, failure):
    settings = _runtime_settings(monkeypatch)

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=_runtime_poi_body()),
        )
        executor, shared = owner._executor, owner.client
        actual_shutdown, actual_close = executor.shutdown, shared.aclose
        attempts = []
        executor_fails = failure in {"executor", "both"}
        client_fails = failure in {"client", "client_after_close", "both"}

        def observed_shutdown(*, wait, cancel_futures):
            attempts.append("executor")
            assert wait is True and cancel_futures is True
            if executor_fails:
                raise RuntimeError("private-executor-cleanup-detail")
            return actual_shutdown(wait=wait, cancel_futures=cancel_futures)

        async def observed_close():
            attempts.append("client")
            if failure == "client_after_close":
                await actual_close()
                raise RuntimeError("private-client-acknowledgement-detail")
            if client_fails:
                raise RuntimeError("private-client-cleanup-detail")
            await actual_close()

        monkeypatch.setattr(executor, "shutdown", observed_shutdown)
        monkeypatch.setattr(shared, "aclose", observed_close)
        try:
            with pytest.raises(TmapRuntimeError) as first:
                await owner.aclose()
            close_task = owner._close_task
            assert first.value.code == "tmap_processing_unavailable"
            assert first.value.message == "TMAP request processing is unavailable."
            assert attempts == ["executor", "client"]
            assert not owner._closed
            assert not owner._accepting
            assert owner._executor is (executor if executor_fails else None)
            assert owner._client is shared
            expected_failures = tuple(
                name for name, failed in (("executor", executor_fails), ("client", client_fails)) if failed
            )
            assert owner._close_failures == expected_failures
            if failure == "client_after_close":
                # HTTPX's is_closed flag alone is not a successful close return.
                assert shared.is_closed
            with pytest.raises(TmapRuntimeError) as second:
                await owner.aclose()
            assert second.value.code == first.value.code
            assert owner._close_task is close_task
            assert attempts == ["executor", "client"]
            assert not owner._closed
            assert owner._executor is (executor if executor_fails else None)
            with pytest.raises(TmapPoiSearchError, match="processing is unavailable"):
                await _fetch_tmap_poi_search("Stopped owner", settings, runtime=owner)
        finally:
            # Explicit test-owned cleanup of injected failures, not an implicit
            # product retry or a replacement runtime.
            monkeypatch.setattr(executor, "shutdown", actual_shutdown)
            monkeypatch.setattr(shared, "aclose", actual_close)
            await asyncio.to_thread(actual_shutdown, wait=True, cancel_futures=True)
            await actual_close()

    asyncio.run(run())


def test_tmap_successful_shutdown_confirms_resources_exactly_once(monkeypatch):
    settings = _runtime_settings(monkeypatch)

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, lambda request: httpx.Response(200, content=_runtime_poi_body()),
        )
        executor, shared = owner._executor, owner.client
        actual_shutdown, actual_close = executor.shutdown, shared.aclose
        attempts = []

        def observed_shutdown(*, wait, cancel_futures):
            attempts.append("executor")
            return actual_shutdown(wait=wait, cancel_futures=cancel_futures)

        async def observed_close():
            attempts.append("client")
            await actual_close()

        monkeypatch.setattr(executor, "shutdown", observed_shutdown)
        monkeypatch.setattr(shared, "aclose", observed_close)
        try:
            result = await _fetch_tmap_poi_search("Successful cleanup", settings, runtime=owner)
            assert result.results[0].id == _RUNTIME_POI_PUBLIC_ID
            processes = tuple(executor._processes.values())
            await owner.aclose()
            await owner.aclose()
            assert attempts == ["executor", "client"]
            assert owner._closed
            assert owner._close_failures == ()
            assert owner._executor is None
            assert owner._client is shared
            assert shared.is_closed
            assert not owner._leases
            assert all(not process.is_alive() for process in processes)
        finally:
            await owner.aclose()

    asyncio.run(run())


def _tmap_waypoint_fixture(coordinates, waypoints, *, distance_m, duration_s=None):
    if duration_s is None:
        # S07-10: default fixture time is a plausible walking estimate (<=1 m/s).
        duration_s = max(100, distance_m)
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=coordinates[0][1], longitude=coordinates[0][0]),
        destination=RoutePoint(latitude=coordinates[-1][1], longitude=coordinates[-1][0]),
        waypoints=[RoutePoint(latitude=point[1], longitude=point[0]) for point in waypoints],
        priority="RECOMMEND",
    )
    payload = {
        "type": "FeatureCollection",
        "features": [
            _tmap_guide_fixture(coordinates[0], "Waypoint route start"),
            *_tmap_geometry_turn_guides([
                RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates
            ]),
            _tmap_line_fixture(coordinates, distance_m, duration_s),
        ],
    }
    return payload, request


def _assert_tmap_waypoint_mismatch(error):
    assert error.code == "route_waypoint_mismatch"
    assert error.http_status == 502
    assert error.message == (
        "TMAP pedestrian route geometry could not be verified against the requested waypoint order."
    )
    assert error.provider_status is None
    assert error.provider_result_code is None


def test_tmap_waypoint_empty_list_preserves_previous_normalization():
    payload = _runtime_route_payload()
    original = deepcopy(payload)
    request = _runtime_request()
    assert request.waypoints == []
    result = _normalize_tmap_response(payload, request)
    assert result.provider == "tmap_pedestrian"
    assert result.provider_result_code == 0
    assert result.summary.distance_m == 90
    assert result.summary.duration_s == 80
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        (127.0, 37.0), (127.001, 37.0),
    ]
    assert result.steps[0].distance_m == 90
    assert result.guide_points[0].distance_from_start_m == 0
    assert result.guide_points[0].remaining_distance_m == 90
    assert payload == original


@pytest.mark.parametrize("count", [1, 5])
def test_tmap_waypoint_segment_interior_and_five_in_order(count):
    coordinates = [[0.0, 0.0], [0.01, 0.0]]
    waypoints = [[0.005, 0.0]] if count == 1 else [
        [0.001, 0.0], [0.003, 0.0], [0.005, 0.0], [0.007, 0.0], [0.009, 0.0],
    ]
    payload, request = _tmap_waypoint_fixture(
        coordinates, waypoints, distance_m=1112, duration_s=1000,
    )
    before_payload, before_request = deepcopy(payload), request.model_dump()
    result = _normalize_tmap_response(payload, request)
    assert len(request.waypoints) == count
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        (0.0, 0.0), (0.01, 0.0),
    ]
    assert result.summary.distance_m == 1112
    assert result.summary.duration_s == 1000
    assert result.steps[0].distance_m == 1112
    assert result.steps[0].duration_s == 1000
    assert result.guide_points[0].distance_from_start_m == 0
    assert result.guide_points[0].remaining_distance_m == 1112
    assert payload == before_payload
    assert request.model_dump() == before_request


@pytest.mark.parametrize("waypoints", [
    [[0.005, 0.001]],
    [[0.012, 0.0]],
    [[0.008, 0.0], [0.002, 0.0]],
], ids=["off-route", "beyond-endpoint", "reversed-disjoint-intervals"])
def test_tmap_waypoint_missing_or_reversed_order_is_fixed_domain_error(waypoints):
    payload, request = _tmap_waypoint_fixture(
        [[0.0, 0.0], [0.01, 0.0]], waypoints, distance_m=1112,
    )
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, request)
    _assert_tmap_waypoint_mismatch(caught.value)


@pytest.mark.parametrize("waypoints", [
    [[0.002, 0.0], [0.008, 0.0]],
    [[0.005, 0.0], [0.005, 0.0]],
    [[0.0, 0.0], [0.01, 0.0]],
], ids=["forward", "duplicate-shared-vertex", "endpoints"])
def test_tmap_waypoint_forward_order_duplicate_and_shared_vertex(waypoints):
    coordinates = [[0.0, 0.0], [0.005, 0.0], [0.01, 0.0]]
    payload, request = _tmap_waypoint_fixture(coordinates, waypoints, distance_m=1112)
    result = _normalize_tmap_response(payload, request)
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        (0.0, 0.0), (0.005, 0.0), (0.01, 0.0),
    ]


def test_tmap_waypoint_loop_uses_later_visit_without_sorting():
    coordinates = [
        [0.0, 0.0], [0.004, 0.0], [0.0, 0.0],
        [-0.004, 0.0], [0.0, 0.0], [0.004, 0.0],
    ]
    payload, request = _tmap_waypoint_fixture(
        coordinates, [[0.003, 0.0], [-0.003, 0.0], [0.003, 0.0]], distance_m=2224,
    )
    result = _normalize_tmap_response(payload, request)
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        tuple(point) for point in coordinates
    ]
    _, reversed_visits = _tmap_waypoint_fixture(
        coordinates, [[-0.003, 0.0], [0.003, 0.0], [-0.003, 0.0]], distance_m=2224,
    )
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, reversed_visits)
    _assert_tmap_waypoint_mismatch(caught.value)


@pytest.mark.parametrize("direction", [1, -1])
def test_tmap_waypoint_overlapping_intervals_not_nearest_points(direction):
    # Each waypoint is about 10 m from longitude zero. Both can use that
    # same progress, although their unique nearest projections are reversed.
    coordinates = [[-0.001 * direction, 0.0], [0.001 * direction, 0.0]]
    payload, request = _tmap_waypoint_fixture(
        coordinates, [[0.00009 * direction, 0.0], [-0.00009 * direction, 0.0]],
        distance_m=222,
    )
    result = _normalize_tmap_response(payload, request)
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        tuple(point) for point in coordinates
    ]


@pytest.mark.parametrize("distance_m", [19.999, 20.0, 20.001])
@pytest.mark.parametrize("hemisphere", [1, -1])
def test_tmap_waypoint_twenty_metre_spherical_boundary(distance_m, hemisphere):
    from math import degrees

    # A meridian is perpendicular to this equatorial arc. Its spherical
    # length is R * latitude_radians, independently of the implementation.
    latitude = hemisphere * degrees(distance_m / 6371000.0)
    payload, request = _tmap_waypoint_fixture(
        [[-0.001, 0.0], [0.001, 0.0]], [[0.0, latitude]], distance_m=222,
    )
    assert tmap_service.TMAP_WAYPOINT_MAX_DISTANCE_M == 20.0
    if distance_m <= 20.0:
        assert _normalize_tmap_response(payload, request).summary.distance_m == 222
    else:
        with pytest.raises(TmapPedestrianRouteError) as caught:
            _normalize_tmap_response(payload, request)
        _assert_tmap_waypoint_mismatch(caught.value)


@pytest.mark.parametrize("reverse", [False, True])
def test_tmap_waypoint_antimeridian_uses_minor_arc(reverse):
    coordinates = [[179.998, 0.0], [-179.998, 0.0]]
    if reverse:
        coordinates.reverse()
    payload, request = _tmap_waypoint_fixture(
        coordinates, [[180.0, 0.0], [-180.0, 0.0]], distance_m=445,
    )
    result = _normalize_tmap_response(payload, request)
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        tuple(point) for point in coordinates
    ]
    _, opposite_side = _tmap_waypoint_fixture(coordinates, [[0.0, 0.0]], distance_m=445)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, opposite_side)
    _assert_tmap_waypoint_mismatch(caught.value)


@pytest.mark.parametrize("hemisphere", [1, -1])
def test_tmap_waypoint_minor_arc_passes_over_pole(hemisphere):
    coordinates = [[0.0, hemisphere * 89.999], [180.0, hemisphere * 89.999]]
    payload, request = _tmap_waypoint_fixture(
        coordinates, [[123.0, hemisphere * 90.0]], distance_m=222,
    )
    result = _normalize_tmap_response(payload, request)
    assert result.summary.distance_m == 222
    assert len(result.polyline) == 2
    assert request.waypoints[0].longitude == 123.0


@pytest.mark.parametrize(("coordinates", "outside"), [
    ([[180.0, 0.0], [-180.0, 0.0]], [0.0, 0.0]),
    ([[15.0, 90.0], [-40.0, 90.0]], [0.0, 89.0]),
    ([[127.0, 37.0], [127.0, 37.0]], [127.001, 37.0]),
], ids=["dateline-equivalence", "pole-equivalence", "identical-point"])
def test_tmap_waypoint_zero_edges_are_point_candidates(coordinates, outside):
    points = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
    assert tmap_service._validate_route_waypoint_order(points, [points[-1], points[0]]) is None
    assert tmap_service._validate_route_waypoint_order([points[0]], [points[-1]]) is None
    far = RoutePoint(latitude=outside[1], longitude=outside[0])
    with pytest.raises(TmapPedestrianRouteError) as caught:
        tmap_service._validate_route_waypoint_order(points, [far])
    _assert_tmap_waypoint_mismatch(caught.value)


@pytest.mark.parametrize("coordinates", [
    [[0.0, 0.0], [180.0, 0.0]],
    [[0.0, 0.0], [179.999999999999, 0.0]],
    [[25.0, 90.0], [-130.0, -90.0]],
    [[0.0, 0.0], [0.000000000001, 0.0]],
], ids=["antipodal", "ill-conditioned-antipodal", "opposite-poles", "ill-conditioned-short"])
def test_tmap_waypoint_indeterminate_minor_arc_fails_closed(coordinates):
    points = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
    with pytest.raises(TmapPedestrianRouteError) as caught:
        tmap_service._validate_route_waypoint_order(points, [points[0]])
    _assert_tmap_waypoint_mismatch(caught.value)
    # Empty waypoints do not impose this new condition on legacy routes.
    assert tmap_service._validate_route_waypoint_order(points, []) is None


@pytest.mark.parametrize("reverse", [False, True])
def test_tmap_waypoint_long_unambiguous_minor_arc_is_not_blanket_rejected(reverse):
    coordinates = [[0.0, 0.0], [170.0, 0.0]]
    if reverse:
        coordinates.reverse()
    payload, request = _tmap_waypoint_fixture(
        coordinates, [[85.0, 0.0]], distance_m=18903138, duration_s=20000000,
    )
    result = _normalize_tmap_response(payload, request)
    assert result.summary.distance_m == 18903138
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        tuple(point) for point in coordinates
    ]


@pytest.mark.parametrize("side", ["start", "end"])
@pytest.mark.parametrize("distance_m", [19.9, 20.1])
def test_tmap_waypoint_endpoint_clips_the_allowed_interval(side, distance_m):
    from math import degrees

    offset = degrees(distance_m / 6371000.0)
    longitude = -offset if side == "start" else 0.002 + offset
    payload, request = _tmap_waypoint_fixture(
        [[0.0, 0.0], [0.002, 0.0]], [[longitude, 0.0]], distance_m=222,
    )
    if distance_m < 20.0:
        assert _normalize_tmap_response(payload, request).summary.distance_m == 222
    else:
        with pytest.raises(TmapPedestrianRouteError) as caught:
            _normalize_tmap_response(payload, request)
        _assert_tmap_waypoint_mismatch(caught.value)


@pytest.mark.parametrize("expire_on", [2, 5, 6], ids=["edge-preparation", "waypoint", "edge-scan"])
def test_tmap_waypoint_deadline_is_checked_during_preparation_and_matching(monkeypatch, expire_on):
    points = [RoutePoint(latitude=0.0, longitude=value) for value in (0.0, 0.001, 0.002, 0.003)]
    waypoint = RoutePoint(latitude=0.0, longitude=0.0005)
    clock_reads = []

    def clock():
        clock_reads.append(None)
        return 10.0 if len(clock_reads) >= expire_on else 9.0

    monkeypatch.setattr(tmap_service, "monotonic", clock)
    with pytest.raises(TimeoutError, match="TMAP request exceeded its wall-clock deadline"):
        tmap_service._validate_route_waypoint_order(points, [waypoint], deadline_at=10.0)
    assert len(clock_reads) == expire_on


def test_tmap_waypoint_normalization_keeps_zero_edge_and_provider_distance_policy():
    coordinates = [[0.0, 0.0], [0.0, 0.0], [0.002, 0.0]]
    payload, request = _tmap_waypoint_fixture(
        coordinates, [[0.0, 0.0], [0.001, 0.0]], distance_m=222, duration_s=123,
    )
    original = deepcopy(payload)
    result = _normalize_tmap_response(payload, request)
    assert [(point.longitude, point.latitude) for point in result.polyline] == [(0.0, 0.0), (0.002, 0.0)]
    assert result.summary.distance_m == 222
    assert result.summary.duration_s == 123
    assert result.steps[0].distance_m == 222
    assert result.steps[0].duration_s == 123
    assert payload == original


def test_tmap_waypoint_spawn_error_preserves_success_cache_and_reuses_owner(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    body = json.dumps(_runtime_route_payload()).encode("utf-8")
    hooks = _runtime_hooks(body)
    provider_calls = []

    def handler(request):
        provider_calls.append(request)
        return httpx.Response(200, content=body)

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, handler, hooks=hooks,
        )
        executor, shared = owner._executor, owner.client
        reset_tmap_success_cache()
        request = _runtime_request()
        # At latitude 37, these two requested positions are over 50 m apart:
        # their 20 m intervals cannot be satisfied in this reversed order.
        west = RoutePoint(latitude=37.0, longitude=127.0002)
        east = RoutePoint(latitude=37.0, longitude=127.0008)
        failure_task = asyncio.create_task(_fetch_tmap_pedestrian_route(
            request.model_copy(update={"waypoints": [east, west]}), settings, runtime=owner,
        ))
        try:
            await _wait_runtime_condition(hooks.entered.is_set)
            assert hooks.child_pid.value > 0
            assert hooks.child_pid.value != os.getpid()
            assert hooks.guard_checks.value == 2
            assert not failure_task.done()
            hooks.release.set()
            with pytest.raises(TmapPedestrianRouteError) as caught:
                await failure_task
            _assert_tmap_waypoint_mismatch(caught.value)
            assert tmap_dependency_readiness(30.0)["route_recent"] is False
            assert not owner._leases
            assert owner._executor is executor
            assert owner.client is shared
            result = await _fetch_tmap_pedestrian_route(
                request.model_copy(update={"waypoints": [west, east]}), settings, runtime=owner,
            )
            assert result.provider == "tmap_pedestrian"
            assert result.summary.distance_m == 90
            assert result.summary.duration_s == 80
            assert hooks.entered_count.value == 2
            assert len(provider_calls) == 2
            assert tmap_dependency_readiness(30.0)["route_recent"] is True
            assert owner._executor is executor
            assert owner.client is shared
            assert not shared.is_closed
            assert not owner._leases
        finally:
            hooks.release.set()
            await asyncio.gather(failure_task, return_exceptions=True)
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())


def _tmap_micro_edge_independent_lower_bound(coordinates, outside):
    from decimal import Decimal, localcontext
    from math import atan2, cos, pi, radians, sin, sqrt

    start, end = coordinates
    # Independently bound arc length by explicit surface paths, not by the
    # product's local frame or cap intervals. Exact float-to-decimal conversion
    # retains the tiny residual when longitudes straddle the dateline.
    with localcontext() as context:
        context.prec = 60
        start_lon, start_lat = (Decimal.from_float(float(value)) for value in start)
        end_lon, end_lat = (Decimal.from_float(float(value)) for value in end)
        raw_longitude = abs(end_lon - start_lon)
        longitude = min(raw_longitude, Decimal(360) - raw_longitude)
        coordinate_path = abs(end_lat - start_lat) + longitude
        north_pole_path = (Decimal(90) - start_lat) + (Decimal(90) - end_lat)
        south_pole_path = (Decimal(90) + start_lat) + (Decimal(90) + end_lat)
        path_degrees = min(coordinate_path, north_pole_path, south_pole_path)
    arc_upper_bound_m = 6371000.0 * pi / 180.0 * float(path_degrees)
    assert 0.0 < arc_upper_bound_m < 0.001

    # The spherical triangle inequality bounds distance to every point of
    # the arc by distance to its start minus the entire arc length.
    delta_latitude = radians(outside[1] - start[1])
    delta_longitude = radians(outside[0] - start[0])
    haversine = (
        sin(delta_latitude / 2.0) ** 2
        + cos(radians(start[1])) * cos(radians(outside[1]))
        * sin(delta_longitude / 2.0) ** 2
    )
    start_distance_m = 6371000.0 * 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
    return start_distance_m - arc_upper_bound_m


def _assert_tmap_micro_edge_rejection_and_endpoint_control(coordinates, outside):
    assert _tmap_micro_edge_independent_lower_bound(coordinates, outside) > 20.0
    payload, request = _tmap_waypoint_fixture(
        coordinates, [outside], distance_m=1, duration_s=1,
    )
    points = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
    with pytest.raises(TmapPedestrianRouteError) as direct:
        tmap_service._validate_route_waypoint_order(points, request.waypoints)
    _assert_tmap_waypoint_mismatch(direct.value)
    with pytest.raises(TmapPedestrianRouteError) as normalized:
        _normalize_tmap_response(payload, request)
    _assert_tmap_waypoint_mismatch(normalized.value)

    # Both requested coordinates are actual arc endpoints. The on-route
    # control prevents hiding the failure by denying all these tiny edges.
    _, on_route = _tmap_waypoint_fixture(
        coordinates, coordinates, distance_m=1, duration_s=1,
    )
    assert tmap_service._validate_route_waypoint_order(points, on_route.waypoints) is None
    result = _normalize_tmap_response(payload, on_route)
    assert result.summary.distance_m == 1
    assert result.summary.duration_s == 1
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        tuple(point) for point in coordinates
    ]
    assert result.steps[0].distance_m == 1
    assert result.steps[0].duration_s == 1


def test_tmap_waypoint_numerical_reproduced_micro_meridian_rejects_far_point():
    coordinates = [[45.0, 45.0], [45.0, 45.0000000001]]
    outside = [45.001, 45.0]
    lower_bound_m = _tmap_micro_edge_independent_lower_bound(coordinates, outside)
    # Root's independent diagnostic lower bound was 78.62667554353487 m.
    assert 78.62 < lower_bound_m < 78.63
    _assert_tmap_micro_edge_rejection_and_endpoint_control(coordinates, outside)


@pytest.mark.parametrize(("start", "end", "outside"), [
    ([135.0, 45.0], [135.0, 45.0000000001], [135.001, 45.0]),
    ([-45.0, -45.0], [-45.0, -45.0000000001], [-44.999, -45.0]),
    ([45.0, 45.0], [45.00000000012, 45.0], [45.0, 45.001]),
    ([135.0, 45.0], [135.00000000008, 45.00000000008], [135.0, 45.001]),
    ([-135.0, -45.0], [-135.00000000008, -45.00000000008], [-135.0, -44.999]),
], ids=["longitude-rotation", "southern-meridian", "east-west", "northeast", "southwest"])
@pytest.mark.parametrize("reverse", [False, True])
def test_tmap_waypoint_numerical_micro_edge_rotations_and_directions(start, end, outside, reverse):
    coordinates = [list(start), list(end)]
    if reverse:
        coordinates.reverse()
    _assert_tmap_micro_edge_rejection_and_endpoint_control(coordinates, outside)


@pytest.mark.parametrize("reverse", [False, True])
def test_tmap_waypoint_numerical_micro_dateline_preserves_wrapped_difference(reverse):
    coordinates = [[179.99999999995, 45.0], [-179.99999999995, 45.0]]
    outside = [179.99999999995, 45.001]
    if reverse:
        coordinates.reverse()
    _assert_tmap_micro_edge_rejection_and_endpoint_control(coordinates, outside)


@pytest.mark.parametrize("hemisphere", [1, -1])
@pytest.mark.parametrize("reverse", [False, True])
def test_tmap_waypoint_numerical_near_pole_short_edge_keeps_local_plane(hemisphere, reverse):
    latitude = hemisphere * 89.9999999999
    coordinates = [[45.0, latitude], [135.0, latitude]]
    outside = [45.0, hemisphere * 89.999]
    if reverse:
        coordinates.reverse()
    _assert_tmap_micro_edge_rejection_and_endpoint_control(coordinates, outside)


@pytest.mark.parametrize("reverse", [False, True])
def test_tmap_waypoint_numerical_near_antipodal_meridian_uses_north_minor_arc(reverse):
    coordinates = [[0.0, 45.0], [180.0, -45.0 + 0.0000000001]]
    if reverse:
        coordinates.reverse()
    # These endpoints lie on one meridian. The north-going arc is just
    # shorter than pi; the south-going arc is longer than pi. On the minor
    # arc every latitude is above -45, so the south pole is over 45 deg away.
    payload, north_request = _tmap_waypoint_fixture(
        coordinates, [[123.0, 90.0]], distance_m=20015087, duration_s=20000000,
    )
    points = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
    assert tmap_service._validate_route_waypoint_order(points, north_request.waypoints) is None
    result = _normalize_tmap_response(payload, north_request)
    assert result.summary.distance_m == 20015087
    assert result.summary.duration_s == 20000000
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        tuple(point) for point in coordinates
    ]
    _, south_request = _tmap_waypoint_fixture(
        coordinates, [[-75.0, -90.0]], distance_m=20015087, duration_s=20000000,
    )
    with pytest.raises(TmapPedestrianRouteError) as direct:
        tmap_service._validate_route_waypoint_order(points, south_request.waypoints)
    _assert_tmap_waypoint_mismatch(direct.value)
    with pytest.raises(TmapPedestrianRouteError) as normalized:
        _normalize_tmap_response(payload, south_request)
    _assert_tmap_waypoint_mismatch(normalized.value)


@pytest.mark.parametrize("multiple", [0.9, 1.1], ids=["below-unchanged-floor", "above-unchanged-floor"])
def test_tmap_waypoint_numerical_conditioning_floor_is_not_raised(multiple):
    from math import degrees, radians, sin

    coordinates = [[45.0, 45.0], [45.0, 45.0 + degrees(multiple * 1e-12)]]
    # Same-longitude h = sin(abs(latitude difference)), independent of the
    # implemented frame. Check the represented input stayed on its intended
    # side of the original conditioning floor.
    independent_h = sin(radians(coordinates[1][1] - coordinates[0][1]))
    assert (independent_h <= 1e-12) == (multiple < 1.0)
    assert tmap_service._TMAP_WAYPOINT_MIN_ARC_CROSS_NORM == 1e-12
    if multiple > 1.0:
        _assert_tmap_micro_edge_rejection_and_endpoint_control(coordinates, [45.001, 45.0])
    else:
        payload, request = _tmap_waypoint_fixture(
            coordinates, [coordinates[0]], distance_m=1, duration_s=1,
        )
        points = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
        with pytest.raises(TmapPedestrianRouteError) as direct:
            tmap_service._validate_route_waypoint_order(points, request.waypoints)
        _assert_tmap_waypoint_mismatch(direct.value)
        with pytest.raises(TmapPedestrianRouteError) as normalized:
            _normalize_tmap_response(payload, request)
        _assert_tmap_waypoint_mismatch(normalized.value)
        _, without_waypoints = _tmap_waypoint_fixture(
            coordinates, [], distance_m=1, duration_s=1,
        )
        assert _normalize_tmap_response(payload, without_waypoints).summary.distance_m == 1


@pytest.mark.parametrize("sign", [1.0, -1.0], ids=["positive-near-endpoint", "negative-near-endpoint"])
@pytest.mark.parametrize("reverse", [False, True], ids=["forward", "reversed"])
def test_tmap_waypoint_dateline_equivalence_does_not_round_nonidentical_endpoints(sign, reverse):
    from decimal import Decimal, localcontext
    from math import cos, nextafter, radians, sin, sqrt

    coordinates = [[-180.0 * sign, 45.0], [nextafter(180.0 * sign, 0.0), 45.0]]
    if reverse:
        coordinates.reverse()
    start_longitude, end_longitude = coordinates[0][0], coordinates[1][0]
    assert abs(start_longitude - end_longitude) == 360.0
    with localcontext() as context:
        context.prec = 60
        gap_degrees = Decimal(360) - abs(
            Decimal.from_float(start_longitude) - Decimal.from_float(end_longitude)
        )
    assert gap_degrees == Decimal("2.8421709430404007434844970703125E-14")
    assert gap_degrees > 0
    # Independent same-latitude haversine identity: this is not a zero edge,
    # but sin(central angle) is below the unchanged conditioning floor.
    half_chord = cos(radians(45.0)) * sin(radians(float(gap_degrees)) / 2.0)
    independent_h = 2.0 * half_chord * sqrt(1.0 - half_chord * half_chord)
    assert 0.0 < independent_h <= 1e-12

    payload, request = _tmap_waypoint_fixture(
        coordinates, [coordinates[0]], distance_m=1, duration_s=1,
    )
    points = [RoutePoint(latitude=point[1], longitude=point[0]) for point in coordinates]
    with pytest.raises(TmapPedestrianRouteError) as direct:
        tmap_service._validate_route_waypoint_order(points, request.waypoints)
    _assert_tmap_waypoint_mismatch(direct.value)
    with pytest.raises(TmapPedestrianRouteError) as normalized:
        _normalize_tmap_response(payload, request)
    _assert_tmap_waypoint_mismatch(normalized.value)

    _, without_waypoints = _tmap_waypoint_fixture(
        coordinates, [], distance_m=1, duration_s=1,
    )
    baseline = _normalize_tmap_response(payload, without_waypoints)
    assert baseline.summary.distance_m == 1
    assert baseline.summary.duration_s == 1
    assert [(point.longitude, point.latitude) for point in baseline.polyline] == [
        tuple(point) for point in coordinates
    ]

    exact_coordinates = [[-180.0 * sign, 45.0], [180.0 * sign, 45.0]]
    if reverse:
        exact_coordinates.reverse()
    exact_points = [
        RoutePoint(latitude=point[1], longitude=point[0])
        for point in exact_coordinates
    ]
    assert tmap_service._validate_route_waypoint_order(
        exact_points, [exact_points[-1], exact_points[0]],
    ) is None


def _tmap_remaining_summary_fixture(early_summary, final_summary):
    origin = [127.0, 37.0]
    middle = [127.0005, 37.0]
    destination = [127.001, 37.0]
    first = _tmap_guide_fixture(origin, "Start")
    arrival = _tmap_guide_fixture(destination, "Arrival")
    last = _tmap_guide_fixture(destination, "Arrival tied")
    if early_summary is not None:
        first["properties"]["totalDistance"] = early_summary
    if final_summary is not None:
        arrival["properties"]["totalDistance"] = final_summary
    # A later zero must not replace the existing last-nonzero summary choice.
    last["properties"]["totalDistance"] = 0
    return {
        "type": "FeatureCollection",
        "features": [
            first,
            _tmap_line_fixture([origin, middle], 60, 30),
            _tmap_guide_fixture(middle, "Mid first"),
            _tmap_line_fixture([middle, middle], 0, 0),
            _tmap_guide_fixture(middle, "Mid tied"),
            _tmap_line_fixture([middle, destination], 60, 30),
            arrival,
            last,
        ],
    }, _runtime_request()


@pytest.mark.parametrize(("early_summary", "final_summary", "expected_summary", "expected_remaining"), [
    # S07-10: a different later positive total is now route_summary_conflict
    # (see test_tmap_conflicting_positive_route_totals_are_rejected_not_overwritten).
    (180, 180, 180, [180, 90, 90, 0, 0]),
    (0, 0, 120, [120, 60, 60, 0, 0]),
    (120, None, 120, [120, 60, 60, 0, 0]),
], ids=["equal-duplicate-summary", "step-sum-fallback", "single-summary-control"])
def test_tmap_guide_remaining_uses_final_summary_without_changing_prefixes(
    early_summary, final_summary, expected_summary, expected_remaining,
):
    payload, request = _tmap_remaining_summary_fixture(early_summary, final_summary)
    original = deepcopy(payload)
    result = _normalize_tmap_response(payload, request)
    assert result.summary.distance_m == expected_summary
    assert result.summary.duration_s == 60
    assert [step.index for step in result.steps] == [0, 1, 2]
    assert [step.distance_m for step in result.steps] == [expected_summary // 2, 0, expected_summary // 2]
    assert [step.duration_s for step in result.steps] == [30, 0, 30]
    assert [guide.index for guide in result.guide_points] == [0, 1, 2, 3, 4]
    assert [guide.instruction for guide in result.guide_points] == [
        "Start", "Mid first", "Mid tied", "Arrival", "Arrival tied",
    ]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, expected_summary // 2, expected_summary // 2, expected_summary, expected_summary]
    assert [guide.remaining_distance_m for guide in result.guide_points] == expected_remaining
    assert [(guide.point.longitude, guide.point.latitude) for guide in result.guide_points] == [
        (127.0, 37.0), (127.0005, 37.0), (127.0005, 37.0),
        (127.001, 37.0), (127.001, 37.0),
    ]
    assert [(point.longitude, point.latitude) for point in result.polyline] == [
        (127.0, 37.0), (127.0005, 37.0), (127.001, 37.0),
    ]
    assert payload == original


def test_tmap_guide_distances_use_decreased_final_summary_without_clamping_raw_prefixes():
    # S07-10: one provider summary smaller than the raw step sum (no conflicting duplicate).
    payload, request = _tmap_remaining_summary_fixture(None, 90)
    original = deepcopy(payload)
    result = _normalize_tmap_response(payload, request)
    assert result.distance_source == "provider_total_geometry_scaled"
    assert result.summary.distance_m == 90
    assert [step.distance_m for step in result.steps] == [45, 0, 45]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 45, 45, 90, 90]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [90, 45, 45, 0, 0]
    assert payload == original


@pytest.mark.parametrize(("field", "early", "later"), [
    ("totalDistance", 90, 180),
    ("totalDistance", 180, 90),
    ("totalTime", 60, 120),
], ids=["distance-increase", "distance-decrease", "time-conflict"])
def test_tmap_conflicting_positive_route_totals_are_rejected_not_overwritten(field, early, later):
    # S07-10 (originals/07.txt:118): totals belong to the start-point feature; a
    # different positive duplicate elsewhere must fail closed instead of winning.
    payload, request = _tmap_remaining_summary_fixture(None, None)
    payload["features"][0]["properties"]["pointType"] = "SP"
    payload["features"][0]["properties"][field] = early
    payload["features"][6]["properties"][field] = later
    original = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.code == "route_summary_conflict"
    assert error.value.http_status == 502
    assert payload == original


def test_tmap_equal_duplicate_route_totals_and_later_zero_are_accepted():
    payload, request = _tmap_remaining_summary_fixture(120, 120)
    payload["features"][0]["properties"].update({"pointType": "SP", "totalTime": 90})
    payload["features"][6]["properties"]["totalTime"] = 90
    payload["features"][7]["properties"]["totalTime"] = 0
    result = _normalize_tmap_response(payload, request)
    assert result.summary.distance_m == 120
    assert result.summary.duration_s == 90


def _tmap_pass_list_summary_fixture(pass_point_type, arrival_point_type):
    # passList-like layout: SP carries the route totals; the pass point and the
    # arrival may carry different per-leg totals (R19-GB-07).
    payload, request = _tmap_remaining_summary_fixture(None, None)
    features = payload["features"]
    features[0]["properties"].update({"pointType": "SP", "totalDistance": 120, "totalTime": 90})
    for index, point_type in ((2, pass_point_type), (6, arrival_point_type)):
        features[index]["properties"].update({"totalDistance": 60, "totalTime": 45})
        if point_type is not None:
            features[index]["properties"]["pointType"] = point_type
    request = request.model_copy(
        update={"waypoints": [RoutePoint(latitude=37.0, longitude=127.0005)]},
    )
    return payload, request


def test_tmap_pass_list_per_leg_totals_on_non_sp_features_are_not_conflicts():
    payload, request = _tmap_pass_list_summary_fixture("PP", "EP")
    original = deepcopy(payload)
    result = _normalize_tmap_response(payload, request)
    assert result.distance_source == "provider_total_geometry_scaled"
    assert (result.summary.distance_m, result.summary.duration_s) == (120, 90)
    assert [guide.point_type for guide in result.guide_points] == ["SP", "PP", None, "EP", None]
    assert payload == original


@pytest.mark.parametrize(("pass_point_type", "arrival_point_type"), [
    (None, "EP"),   # pointType absent: unchanged S07-10 behaviour
    ("SP", "EP"),   # conflicting SP features still fail closed
], ids=["untyped-duplicate", "sp-vs-sp"])
def test_tmap_sp_or_untyped_total_conflicts_still_fail_closed(pass_point_type, arrival_point_type):
    payload, request = _tmap_pass_list_summary_fixture(pass_point_type, arrival_point_type)
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.code == "route_summary_conflict"


def test_tmap_non_sp_totals_remain_fallback_with_conflict_rule():
    payload, request = _tmap_remaining_summary_fixture(None, None)
    features = payload["features"]
    features[0]["properties"].update({"pointType": "S", "totalDistance": 120, "totalTime": 90})
    result = _normalize_tmap_response(payload, request)
    assert result.distance_source == "provider_total_geometry_scaled"
    assert (result.summary.distance_m, result.summary.duration_s) == (120, 90)
    features[6]["properties"].update({"pointType": "EP", "totalDistance": 60})
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.code == "route_summary_conflict"


@pytest.mark.parametrize(("distance_m", "duration_s", "accepted"), [
    (1000, 60, False),     # 16.7 m/s: not a walking estimate
    (1000, 240, False),    # 4.2 m/s: faster than a plausible pedestrian estimate
    (1000, 248, True),     # 4.0 m/s + rounding tolerance: boundary stays accepted
    (1000, 720, True),     # 1.39 m/s: ordinary walking
    (1000, 1800, True),    # signal waits / slow walking must remain valid
    (1000, 7200, True),    # long waits are not rejected by a speed rule
    (20, 5, True),         # short-route second rounding stays tolerated
], ids=["far-too-fast", "too-fast", "boundary", "walking", "signal-wait", "very-slow", "short-rounding"])
def test_walking_route_duration_rejects_only_implausibly_fast_estimates(distance_m, duration_s, accepted):
    from backend.app.services.walking_route_sanity import (
        WalkingRouteSanityError,
        validate_normalized_walking_route,
    )

    origin = RoutePoint(latitude=37.0, longitude=127.0)
    # ~distance_m metres east at latitude 37.
    destination = RoutePoint(latitude=37.0, longitude=127.0 + distance_m / 88_800.0)
    arguments = dict(
        origin=origin, destination=destination, polyline=[origin, destination],
        distance_m=distance_m, duration_s=duration_s,
    )
    if accepted:
        assert validate_normalized_walking_route(**arguments) is None
        return
    with pytest.raises(WalkingRouteSanityError) as error:
        validate_normalized_walking_route(**arguments)
    assert error.value.code == "route_duration_implausible"


@pytest.mark.parametrize(("speed_kmh", "distance_m", "duration_s", "accepted"), [
    (20.0, 2000, 360, True),    # TMAP at 20 km/h: 2000 m / 5.56 m/s
    (20.0, 2000, 287, True),    # 1.25 x 20 km/h cap + 10 m rounding boundary
    (20.0, 2000, 280, False),   # faster than the requested speed's headroom
    (20.0, 2000, 60, False),    # 33 m/s: impossible even at 20 km/h
    (15.0, 2000, 480, True),    # 15 km/h estimate (previously rejected)
    (4.0, 1000, 240, False),    # default 4 km/h keeps the 4 m/s cap
    (4.0, 1000, 248, True),
    (None, 2000, 360, False),   # no requested speed: 4 m/s cap unchanged
], ids=[
    "20kmh-tmap", "20kmh-boundary", "20kmh-too-fast", "20kmh-impossible",
    "15kmh-tmap", "4kmh-too-fast", "4kmh-boundary", "unspecified-default-cap",
])
def test_walking_route_duration_cap_follows_requested_speed(speed_kmh, distance_m, duration_s, accepted):
    # R19-GB-02: default_speed / TMAP_PEDESTRIAN_SPEED_KMH accept up to 20 km/h
    # and TMAP totalTime follows that speed, so the fast-estimate cap must too.
    from backend.app.services.walking_route_sanity import (
        WalkingRouteSanityError,
        route_max_plausible_speed_mps,
        validate_normalized_walking_route,
    )

    assert route_max_plausible_speed_mps(None) == route_max_plausible_speed_mps(4.0) == 4.0
    origin = RoutePoint(latitude=37.0, longitude=127.0)
    destination = RoutePoint(latitude=37.0, longitude=127.0 + distance_m / 88_800.0)
    arguments = dict(
        origin=origin, destination=destination, polyline=[origin, destination],
        distance_m=distance_m, duration_s=duration_s, speed_kmh=speed_kmh,
    )
    if accepted:
        assert validate_normalized_walking_route(**arguments) is None
        return
    with pytest.raises(WalkingRouteSanityError) as error:
        validate_normalized_walking_route(**arguments)
    assert error.value.code == "route_duration_implausible"


def _tmap_fast_speed_route_payload(total_time_s):
    origin = [127.0, 37.0]
    destination = [127.0 + 2000 / 88_800.0, 37.0]
    start = _tmap_guide_fixture(origin, "Start")
    start["properties"].update({"pointType": "SP", "totalDistance": 2000, "totalTime": total_time_s})
    payload = {
        "type": "FeatureCollection",
        "features": [start, _tmap_line_fixture([origin, destination], 2000, total_time_s)],
    }
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=origin[1], longitude=origin[0]),
        destination=RoutePoint(latitude=destination[1], longitude=destination[0]),
        priority="RECOMMEND",
    )
    return payload, request


def test_tmap_route_duration_cap_uses_request_default_speed():
    payload, request = _tmap_fast_speed_route_payload(360)
    fast_request = request.model_copy(update={"default_speed": 20.0})
    result = _normalize_tmap_response(payload, fast_request)
    assert (result.summary.distance_m, result.summary.duration_s) == (2000, 360)
    # Explicit effective speed (settings path) is honoured the same way.
    assert _normalize_tmap_response(payload, request, speed_kmh=20).summary.duration_s == 360
    # Default speed: the same 5.56 m/s estimate stays implausible.
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.code == "route_duration_implausible"
    impossible, _ = _tmap_fast_speed_route_payload(60)
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(impossible, fast_request)
    assert error.value.code == "route_duration_implausible"
    assert error.value.http_status == 502


def test_tmap_settings_speed_reaches_worker_duration_cap(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    monkeypatch.setattr(settings, "tmap_pedestrian_speed_kmh", 20.0)
    payload, request = _tmap_fast_speed_route_payload(360)
    body = json.dumps(payload).encode("utf-8")
    sent_speeds = []

    def handler(provider_request):
        sent_speeds.append(json.loads(provider_request.content.decode("utf-8"))["speed"])
        return httpx.Response(200, content=body)

    async def run():
        owner, _ = await _start_runtime_with_transport(monkeypatch, settings, handler)
        reset_tmap_success_cache()
        try:
            result = await _fetch_tmap_pedestrian_route(request, settings, runtime=owner)
            assert (result.summary.distance_m, result.summary.duration_s) == (2000, 360)
        finally:
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())
    assert sent_speeds == [20]


def test_tmap_implausible_duration_error_crosses_worker_boundary(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    payload, request = _tmap_remaining_summary_fixture(None, None)
    payload["features"][0]["properties"].update({"pointType": "SP", "totalDistance": 120, "totalTime": 10})
    body = json.dumps(payload).encode("utf-8")

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings,
            lambda provider_request: httpx.Response(200, content=body),
        )
        reset_tmap_success_cache()
        try:
            with pytest.raises(TmapPedestrianRouteError) as error:
                await _fetch_tmap_pedestrian_route(request, settings, runtime=owner)
            assert error.value.code == "route_duration_implausible"
            assert error.value.http_status == 502
        finally:
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())


def test_tmap_summary_conflict_error_crosses_worker_boundary(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    payload, request = _tmap_remaining_summary_fixture(None, None)
    payload["features"][0]["properties"].update({"pointType": "SP", "totalTime": 90})
    payload["features"][6]["properties"]["totalTime"] = 120
    body = json.dumps(payload).encode("utf-8")

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings,
            lambda provider_request: httpx.Response(200, content=body),
        )
        reset_tmap_success_cache()
        try:
            with pytest.raises(TmapPedestrianRouteError) as error:
                await _fetch_tmap_pedestrian_route(request, settings, runtime=owner)
            assert error.value.code == "route_summary_conflict"
            assert error.value.http_status == 502
        finally:
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())


def test_tmap_guide_final_remaining_survives_existing_spawn_worker_path(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    payload, request = _tmap_remaining_summary_fixture(None, 180)
    body = json.dumps(payload).encode("utf-8")

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings,
            lambda provider_request: httpx.Response(200, content=body),
        )
        processes = []
        submit = owner._executor.submit

        def observe_spawn(*args):
            future = submit(*args)
            processes.extend(owner._executor._processes.values())
            return future

        monkeypatch.setattr(owner._executor, "submit", observe_spawn)
        reset_tmap_success_cache()
        try:
            result = await _fetch_tmap_pedestrian_route(request, settings, runtime=owner)
            assert processes
            assert all(
                process.pid is not None and process.pid != os.getpid() and not process.is_alive()
                for process in processes
            )
            assert result.summary.distance_m == 180
            assert result.summary.duration_s == 60
            assert [guide.index for guide in result.guide_points] == [0, 1, 2, 3, 4]
            assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 90, 90, 180, 180]
            assert [guide.remaining_distance_m for guide in result.guide_points] == [180, 90, 90, 0, 0]
            assert [step.distance_m for step in result.steps] == [90, 0, 90]
            assert [step.duration_s for step in result.steps] == [30, 0, 30]
        finally:
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())


def _assert_tmap_required_distance_error(error):
    assert error.code == "invalid_tmap_response"
    assert error.http_status == 502
    assert error.message == "TMAP pedestrian route LineString distance is missing or invalid."
    assert error.provider_status is None
    assert error.provider_result_code is None


def _tmap_required_distance_step_fixture(properties, shape):
    origin = [127.0, 37.0]
    middle = [127.0005, 37.0]
    destination = [127.001, 37.0]
    target_coordinates = {
        "moving": [origin, middle],
        "zero-edge": [origin, origin],
        "single-point": [origin],
    }[shape]
    target_end = target_coordinates[-1]
    start = _tmap_guide_fixture(origin, "Start")
    start["properties"].update({"totalDistance": 180, "totalTime": 80})
    target = {
        "type": "Feature",
        "geometry": {"type": "LineString", "coordinates": target_coordinates},
        "properties": {"time": 0, **properties},
    }
    return {
        "type": "FeatureCollection",
        "features": [
            start,
            target,
            _tmap_guide_fixture(target_end, "After target step"),
            _tmap_line_fixture([target_end, destination], 90, 80),
            _tmap_guide_fixture(destination, "Arrival"),
        ],
    }, _runtime_request()


@pytest.mark.parametrize("properties", [
    {},
    {"distance": None},
    {"distance": False},
    {"distance": True},
    {"distance": -1},
    {"distance": -0.1},
    {"distance": float("nan")},
    {"distance": float("inf")},
    {"distance": float("-inf")},
    {"distance": "-1"},
    {"distance": "1.5"},
    {"distance": "1e2"},
    {"distance": ""},
    {"distance": " "},
    {"distance": "provider-private-distance-detail"},
    {"distance": "NaN"},
    {"distance": "Infinity"},
    {"distance": "0x10"},
    {"distance": []},
    {"distance": {}},
], ids=[
    "missing", "null", "false", "true", "negative-int", "negative-truncated-float",
    "nan", "positive-infinity", "negative-infinity", "negative-int-string",
    "fraction-string", "exponent-string", "empty-string", "space-string",
    "private-invalid-string", "nan-string", "infinity-string", "nondecimal-string",
    "list", "dict",
])
def test_tmap_required_step_distance_rejects_invalid_json_leaves(properties):
    with pytest.raises(TmapPedestrianRouteError) as caught:
        tmap_service._required_tmap_step_distance(properties)
    _assert_tmap_required_distance_error(caught.value)


@pytest.mark.parametrize(("raw", "expected"), [
    (0, 0),
    (12, 12),
    (10 ** 400, 10 ** 400),
    (0.0, 0),
    (-0.0, 0),
    (0.75, 0),
    (12.75, 12),
    (1e20, 100000000000000000000),
    ("0", 0),
    ("-0", 0),
    (" -0 ", 0),
    ("+12", 12),
    (" 12 ", 12),
    ("１２", 12),
    ("١٢", 12),
    ("1_000", 1000),
    ("00012", 12),
], ids=[
    "zero-int", "positive-int", "large-int-no-float-conversion", "zero-float",
    "negative-zero-float", "positive-fraction-to-zero", "positive-fraction",
    "large-finite-float", "zero-string", "minus-zero-string", "spaced-minus-zero",
    "plus-string", "spaced-string", "fullwidth-decimal", "arabic-decimal",
    "underscore-string", "leading-zero-string",
])
def test_tmap_required_step_distance_preserves_existing_numeric_conversion(raw, expected):
    properties = {"distance": raw}
    actual = tmap_service._required_tmap_step_distance(properties)
    assert type(actual) is int
    assert actual == expected
    assert properties == {"distance": raw}


@pytest.mark.parametrize("kind", ["object", "int", "float", "str"])
def test_tmap_required_step_distance_rejects_non_json_conversion_objects_without_calling_them(kind):
    calls = []

    def conversion(value):
        calls.append(value)
        raise AssertionError("A non-JSON distance conversion must not run")

    base = {"object": object, "int": int, "float": float, "str": str}[kind]
    trap_type = type(
        "DistanceConversionTrap", (base,),
        {"__int__": conversion, "__index__": conversion},
    )
    value = trap_type() if kind == "object" else trap_type("1")
    with pytest.raises(TmapPedestrianRouteError) as caught:
        tmap_service._required_tmap_step_distance({"distance": value})
    _assert_tmap_required_distance_error(caught.value)
    assert calls == []


@pytest.mark.parametrize("shape", ["moving", "zero-edge", "single-point"])
@pytest.mark.parametrize("properties", [
    {},
    {"distance": None},
    {"distance": True},
    {"distance": -0.1},
    {"distance": float("inf")},
    {"distance": "bad-distance"},
], ids=["missing", "null", "bool", "negative-before-truncation", "nonfinite", "invalid-string"])
def test_tmap_required_distance_applies_to_every_emitted_step(shape, properties):
    payload, request = _tmap_required_distance_step_fixture(properties, shape)
    original = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, request)
    _assert_tmap_required_distance_error(caught.value)
    assert payload == original


@pytest.mark.parametrize("shape", ["moving", "zero-edge", "single-point"])
@pytest.mark.parametrize(("raw", "expected", "prefixes", "remaining"), [
    (0, 0, [0, 0, 90], [180, 180, 90]),
    (0.75, 0, [0, 0, 90], [180, 180, 90]),
    (12.75, 12, [0, 12, 102], [180, 168, 78]),
    (" +12 ", 12, [0, 12, 102], [180, 168, 78]),
], ids=["explicit-zero", "fraction-to-zero", "truncated-fraction", "compatible-string"])
def test_tmap_required_distance_preserves_step_prefix_and_remaining_contracts(
    shape, raw, expected, prefixes, remaining,
):
    payload, request = _tmap_required_distance_step_fixture({"distance": raw}, shape)
    original = deepcopy(payload)
    result = _normalize_tmap_response(payload, request)
    assert result.summary.distance_m == 180
    assert result.summary.duration_s == 80
    assert [step.index for step in result.steps] == [0, 1]
    assert tmap_service._required_tmap_step_distance({"distance": raw}) == expected
    scaled_middle = 90 if shape == "moving" else 0
    assert [step.distance_m for step in result.steps] == [scaled_middle, 180 - scaled_middle]
    assert [step.duration_s for step in result.steps] == [0, 80]
    assert [guide.index for guide in result.guide_points] == [0, 1, 2]
    assert [guide.instruction for guide in result.guide_points] == [
        "Start", "After target step", "Arrival",
    ]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, scaled_middle, 180]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [180, 180 - scaled_middle, 0]
    expected_polyline = (
        [(127.0, 37.0), (127.0005, 37.0), (127.001, 37.0)]
        if shape == "moving" else [(127.0, 37.0), (127.001, 37.0)]
    )
    assert [(point.longitude, point.latitude) for point in result.polyline] == expected_polyline
    assert payload == original


@pytest.mark.parametrize("kind", [
    "point", "unknown", "empty-line", "missing-geometry", "nondict",
])
def test_tmap_required_distance_does_not_validate_skipped_or_non_step_features(kind):
    payload = _runtime_route_payload()
    properties = {"distance": None, "time": None}
    if kind == "point":
        feature = {
            "geometry": {"type": "Point", "coordinates": [127.0, 37.0]},
            "properties": properties,
        }
    elif kind == "unknown":
        feature = {"geometry": {"type": "Polygon", "coordinates": []}, "properties": properties}
    elif kind == "empty-line":
        feature = {"geometry": {"type": "LineString", "coordinates": []}, "properties": properties}
    elif kind == "missing-geometry":
        feature = {"properties": properties}
    else:
        feature = None
    payload["features"].insert(1, feature)
    original = deepcopy(payload)
    result = _normalize_tmap_response(payload, _runtime_request())
    assert result.summary.distance_m == 90
    assert result.summary.duration_s == 80
    assert [step.distance_m for step in result.steps] == [90]
    assert [step.duration_s for step in result.steps] == [80]
    assert [guide.distance_from_start_m for guide in result.guide_points] == (
        [0, 0] if kind == "point" else [0]
    )
    assert [guide.remaining_distance_m for guide in result.guide_points] == (
        [90, 90] if kind == "point" else [90]
    )
    assert payload == original


def test_tmap_required_distance_keeps_unrelated_point_distance_and_line_turn_ignored():
    payload = _runtime_route_payload()
    payload["features"][0]["properties"].update({
        "totalTime": 80, "distance": None, "turnType": 200, "facilityType": 11,
    })
    payload["features"][1]["properties"].update({
        "time": 80, "turnType": "ignored-line-turn", "facilityType": 15,
    })
    result = _normalize_tmap_response(payload, _runtime_request())
    assert result.summary.distance_m == 90
    assert result.summary.duration_s == 80
    assert result.steps[0].distance_m == 90
    assert result.steps[0].duration_s == 80
    assert result.steps[0].turn_type is None
    assert result.steps[0].facility_type == 15
    assert result.guide_points[0].turn_type == 200
    assert result.guide_points[0].facility_type == 11
    assert result.guide_points[0].distance_from_start_m == 0
    assert result.guide_points[0].remaining_distance_m == 90
    assert tmap_service._int_field({}, "distance") == 0
    assert tmap_service._int_field({"distance": 12.9}, "distance") == 12


def test_tmap_required_distance_preserves_step_limit_guard_precedence():
    origin, destination = [127.0, 37.0], [127.001, 37.0]
    features = [
        _tmap_line_fixture([origin, origin], 0, 0)
        for _ in range(TMAP_MAX_STEPS)
    ]
    features.append({
        "geometry": {"type": "LineString", "coordinates": [origin, destination]},
        "properties": {"time": 1},
    })
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(
            {"type": "FeatureCollection", "features": features}, _runtime_request(),
        )
    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert caught.value.message == "TMAP pedestrian route response exceeds the safe step limit."


def test_tmap_required_distance_spawn_error_keeps_cache_empty_and_owner_reusable(monkeypatch):
    settings = _runtime_settings(monkeypatch)
    bad_payload = _runtime_route_payload()
    del bad_payload["features"][1]["properties"]["distance"]
    bad_body = json.dumps(bad_payload).encode("utf-8")
    good_body = json.dumps(_runtime_route_payload()).encode("utf-8")
    hooks = _runtime_hooks(bad_body)
    hooks.release.set()
    provider_calls = []

    def handler(request):
        provider_calls.append(request)
        return httpx.Response(200, content=bad_body if len(provider_calls) == 1 else good_body)

    async def run():
        owner, _ = await _start_runtime_with_transport(
            monkeypatch, settings, handler, hooks=hooks,
        )
        executor, shared = owner._executor, owner.client
        reset_tmap_success_cache()
        try:
            with pytest.raises(TmapPedestrianRouteError) as caught:
                await _fetch_tmap_pedestrian_route(_runtime_request(), settings, runtime=owner)
            _assert_tmap_required_distance_error(caught.value)
            assert hooks.entered.is_set()
            assert hooks.child_pid.value > 0
            assert hooks.child_pid.value != os.getpid()
            assert hooks.guard_checks.value == 2
            assert tmap_dependency_readiness(30.0)["route_recent"] is False
            assert not owner._leases
            assert owner._executor is executor
            assert owner.client is shared

            result = await _fetch_tmap_pedestrian_route(_runtime_request(), settings, runtime=owner)
            assert result.summary.distance_m == 90
            assert result.summary.duration_s == 80
            assert [step.distance_m for step in result.steps] == [90]
            assert [guide.distance_from_start_m for guide in result.guide_points] == [0]
            assert [guide.remaining_distance_m for guide in result.guide_points] == [90]
            assert len(provider_calls) == 2
            assert tmap_dependency_readiness(30.0)["route_recent"] is True
            assert owner._executor is executor
            assert owner.client is shared
            assert not shared.is_closed
            assert not owner._leases
        finally:
            hooks.release.set()
            await owner.aclose()
            reset_tmap_success_cache()

    asyncio.run(run())
