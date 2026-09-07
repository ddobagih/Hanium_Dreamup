from __future__ import annotations

import asyncio
import json
from pathlib import Path
import sys
from urllib.parse import unquote

import httpx
import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from backend.app.api import navigation as navigation_api  # noqa: E402
from backend.app.config import Settings, get_settings  # noqa: E402
from backend.app.main import app  # noqa: E402
from backend.app.schemas import (  # noqa: E402
    DestinationSearchResponse,
    DestinationSearchResult,
    RoutePoint,
    WalkingRouteRequest,
    WalkingRouteResponse,
    WalkingRouteStep,
    WalkingRouteSummary,
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
    fetch_tmap_pedestrian_route,
    fetch_tmap_poi_search,
    normalize_destination_query,
    reset_tmap_success_cache,
    tmap_dependency_readiness,
)
from asgi_client import ASGITestClient  # noqa: E402


def client() -> ASGITestClient:
    return ASGITestClient(app)


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
        origin_lat: float | None = None,
        origin_lng: float | None = None,
    ) -> DestinationSearchResponse:
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
        origin_lat: float | None = None,
        origin_lng: float | None = None,
    ) -> DestinationSearchResponse:
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

    async def fetch_with_transport(query: str, current_settings, **kwargs) -> DestinationSearchResponse:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http_client:
            return await fetch_tmap_poi_search(query, current_settings, http_client=http_client, **kwargs)

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

    async def fake_fetch_tmap_pedestrian_route(request: WalkingRouteRequest, settings) -> WalkingRouteResponse:
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
    assert body["guide_points"] == []


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
                            "facilityType": 0,
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
    assert result.guide_points[1].distance_from_start_m == 400
    assert result.guide_points[1].remaining_distance_m == 500
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
                    "coordinates": [origin, destination],
                },
                "properties": {"distance": 1, "time": 1},
            }
        ] * (TMAP_MAX_STEPS + 1)
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
