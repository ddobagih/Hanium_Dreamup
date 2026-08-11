from __future__ import annotations

import asyncio
import json
from pathlib import Path
import sys

import httpx

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from backend.app.api import navigation as navigation_api  # noqa: E402
from backend.app.config import get_settings  # noqa: E402
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
from backend.app.services.kakao_mobility import fetch_kakao_walking_route  # noqa: E402
from backend.app.services.tmap_pedestrian import fetch_tmap_pedestrian_route, fetch_tmap_poi_search  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


def client() -> ASGITestClient:
    return ASGITestClient(app)


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
                                "bizCatName": "교통",
                                "radius": "120",
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
    assert result.results[0].id == "poi-1"
    assert result.results[0].point.latitude == 37.3947
    assert result.results[0].point.longitude == 127.1112
    assert result.results[0].address == "경기 성남시 분당구 백현동"
    assert result.results[0].road_address == "경기 성남시 분당구 판교역로 160"
    assert result.results[0].category == "교통"
    assert result.results[0].distance_m == 120


def test_walking_navigation_health_reports_missing_tmap_key(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(settings, "tmap_app_key", "")

    response = client().get("/navigation/walking/health")

    assert response.status_code == 200, response.text
    assert response.json()["provider"] == "tmap_pedestrian"
    assert response.json()["status"] == "unavailable"
    assert response.json()["reason"] == "tmap_app_key_missing"


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
        return httpx.Response(
            200,
            json={
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "geometry": {"type": "Point", "coordinates": [127.11015314141542, 37.39472714688412]},
                        "properties": {
                            "totalDistance": 30,
                            "totalTime": 27,
                            "index": 1,
                            "pointIndex": 1,
                            "name": "출발지",
                            "description": "출발",
                            "turnType": 200,
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
                            "description": "테스트길, 20m",
                            "distance": 20,
                            "time": 18,
                            "roadType": 16,
                            "facilityType": 0,
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
                            "description": ", 10m",
                            "distance": 10,
                            "time": 9,
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
    assert result.summary.distance_m == 30
    assert result.summary.duration_s == 27
    assert len(result.steps) == 2
    assert result.steps[0].instruction == "테스트길, 20m"
    assert result.steps[1].instruction is None
    assert len(result.guide_points) == 2
    assert result.guide_points[0].instruction == "출발"
    assert result.guide_points[0].turn_type == 200
    assert result.guide_points[0].point_type == "S"
    assert result.guide_points[0].distance_from_start_m == 0
    assert result.guide_points[0].remaining_distance_m == 30
    assert result.guide_points[0].bearing_deg is not None
    assert 0 <= result.guide_points[0].bearing_deg < 360
    assert result.guide_points[1].instruction is None
    assert result.guide_points[1].turn_type == 13
    assert result.guide_points[1].point_type == "GP"
    assert result.guide_points[1].distance_from_start_m == 20
    assert result.guide_points[1].remaining_distance_m == 10
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


def test_walking_navigation_can_still_report_missing_kakao_key(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "walking_route_provider", "kakao_mobility")
    monkeypatch.setattr(settings, "kakao_mobility_rest_api_key", "")

    response = client().post("/navigation/walking", json={**sample_route_request(), "priority": "DISTANCE"})

    assert response.status_code == 503, response.text
    assert response.json()["detail"]["code"] == "kakao_api_key_missing"


def test_kakao_walking_route_rejects_unsupported_priority(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "kakao_mobility_rest_api_key", "test-key")

    async def run() -> WalkingRouteResponse:
        return await fetch_kakao_walking_route(
            WalkingRouteRequest.model_validate(sample_route_request()),
            settings,
        )

    try:
        asyncio.run(run())
    except Exception as exc:  # noqa: BLE001
        assert getattr(exc, "code", None) == "route_priority_unsupported"
        assert getattr(exc, "http_status", None) == 422
    else:
        raise AssertionError("Kakao provider accepted unsupported priority")


def test_kakao_walking_route_service_normalizes_provider_response(monkeypatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "kakao_mobility_rest_api_key", "test-key")
    monkeypatch.setattr(settings, "kakao_mobility_walking_directions_url", "https://example.test/walking")
    monkeypatch.setattr(settings, "kakao_mobility_service_name", "walksafe-test")

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["authorization"] == "KakaoAK test-key"
        assert request.headers["service"] == "walksafe-test"
        assert request.url.params["origin"] == "127.11015314141542,37.39472714688412"
        assert request.url.params["destination"] == "127.10824367964793,37.401937080111644"
        assert request.url.params["priority"] == "DISTANCE"
        return httpx.Response(
            200,
            json={
                "trans_id": "provider-trans-id",
                "routes": [
                    {
                        "result_code": 0,
                        "result_message": "길찾기 성공",
                        "summary": {"distance": 30, "duration": 27},
                        "sections": [
                            {
                                "roads": [
                                    {
                                        "distance": 20,
                                        "duration": 18,
                                        "vertexes": [
                                            127.11015314141542,
                                            37.39472714688412,
                                            127.109,
                                            37.398,
                                        ],
                                    },
                                    {
                                        "distance": 10,
                                        "duration": 9,
                                        "vertexes": [
                                            127.109,
                                            37.398,
                                            127.10824367964793,
                                            37.401937080111644,
                                        ],
                                    },
                                ]
                            }
                        ],
                    }
                ],
            },
        )

    async def run() -> WalkingRouteResponse:
        transport = httpx.MockTransport(handler)
        async with httpx.AsyncClient(transport=transport) as http_client:
            return await fetch_kakao_walking_route(
                WalkingRouteRequest.model_validate({**sample_route_request(), "priority": "DISTANCE"}),
                settings,
                http_client=http_client,
            )

    result = asyncio.run(run())

    assert result.provider_route_id == "provider-trans-id"
    assert result.summary.distance_m == 30
    assert result.summary.duration_s == 27
    assert len(result.steps) == 2
    assert result.guide_points == []
    assert len(result.polyline) == 3
