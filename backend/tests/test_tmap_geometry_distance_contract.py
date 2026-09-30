"""One scaled geometry metric for route, steps and unambiguous guide visits."""

import asyncio
from copy import deepcopy
from math import degrees
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest
from pydantic import ValidationError

from backend.app.api import navigation
from backend.app.schemas import RoutePoint, WalkingRouteRequest, WalkingRouteResponse
from backend.app.services.tmap_pedestrian import (
    TmapPedestrianRouteError, _normalize_tmap_response, fetch_tmap_pedestrian_route,
    reset_tmap_success_cache, tmap_dependency_readiness,
)
from backend.app.services.tmap_runtime import TmapRuntime

TOTAL = "provider_total_geometry_scaled"
SUM = "provider_step_sum_geometry_scaled"
GUIDE_ERROR = "TMAP pedestrian route guide position is ambiguous or out of order."


def point(coordinate, turn_type=None):
    properties = {"description": "Guide"}
    if turn_type is not None:
        properties["turnType"] = turn_type
    return {"geometry": {"type": "Point", "coordinates": coordinate}, "properties": properties}


def line(start, end, distance):
    return {"geometry": {"type": "LineString", "coordinates": [start, end]}, "properties": {"distance": distance, "time": 1000}}


def fixture(*, total=200, distances=(160, 40), guides_first=False):
    start, middle, end = [0.0, 0.0], [0.0009, 0.0], [0.0018, 0.0]
    features = [point(start), line(start, middle, distances[0]), point(middle), line(middle, end, distances[1]), point(end)]
    if total is not None:
        features[0]["properties"]["totalDistance"] = total
    if guides_first:
        features = [features[index] for index in (0, 2, 4, 1, 3)]
    request = WalkingRouteRequest(origin=RoutePoint(latitude=0, longitude=0), destination=RoutePoint(latitude=0, longitude=0.0018), priority="RECOMMEND")
    return {"type": "FeatureCollection", "features": features}, request


@pytest.mark.parametrize("total, source", [(200, TOTAL), (None, SUM), (0, SUM)])
@pytest.mark.parametrize("guides_first", [False, True])
def test_total_and_step_sources_share_one_geometry_metric(total, source, guides_first):
    payload, request = fixture(total=total, guides_first=guides_first)
    before = deepcopy(payload)
    result = _normalize_tmap_response(payload, request)
    assert result.distance_source == source
    assert result.summary.distance_m == 200
    assert [step.distance_m for step in result.steps] == [100, 100]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 100, 200]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [200, 100, 0]
    assert payload == before


def test_repeated_short_zero_provider_distances_do_not_erase_progress():
    positions = [[index * 0.00009, 0.0] for index in range(21)]
    features = []
    for index, coordinate in enumerate(positions):
        features.append(point(coordinate))
        if index < 20:
            features.append(line(coordinate, positions[index + 1], 0))
    features[0]["properties"]["totalDistance"] = 200
    _, request = fixture()
    result = _normalize_tmap_response({"type": "FeatureCollection", "features": features}, request)
    assert result.distance_source == TOTAL
    assert [step.distance_m for step in result.steps] == [10] * 20
    assert [guide.distance_from_start_m for guide in result.guide_points] == list(range(0, 201, 10))
    assert [guide.remaining_distance_m for guide in result.guide_points] == list(range(200, -1, -10))


def test_cumulative_rounding_keeps_step_sum_equal_to_summary():
    positions = [[index * 0.000001, 0.0] for index in range(4)]
    features = [point(positions[0])]
    features[0]["properties"]["totalDistance"] = 1
    for start, end in zip(positions, positions[1:]):
        features.extend([line(start, end, 0), point(end)])
    request = WalkingRouteRequest(origin=RoutePoint(latitude=0, longitude=0), destination=RoutePoint(latitude=0, longitude=0.000003), priority="RECOMMEND")
    result = _normalize_tmap_response({"type": "FeatureCollection", "features": features}, request)
    assert [step.distance_m for step in result.steps] == [0, 1, 0]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 0, 1, 1]
    assert sum(step.distance_m for step in result.steps) == result.summary.distance_m == 1


@pytest.mark.parametrize("offset", [0.0, 0.0001])
def test_unique_interior_and_offset_guide_positions_are_preserved(offset):
    payload, request = fixture()
    payload["features"] = [point([0.00045, offset]), line([0, 0], [0.0018, 0], 200)]
    payload["features"][0]["properties"]["totalDistance"] = 200
    result = _normalize_tmap_response(payload, request)
    assert result.guide_points[0].point == RoutePoint(latitude=offset, longitude=0.00045)
    assert result.guide_points[0].distance_from_start_m == 50
    assert result.guide_points[0].remaining_distance_m == 150


def loop_fixture(*, mixed=False):
    origin, north, end = [0, 0], [0, 0.0001], [0.001, 0]
    guides = [point(origin), point(north, 14), point(origin, 12)]
    features = [guides[0], line(origin, north, 11), guides[1], line(north, origin, 11), guides[2], line(origin, end, 111)]
    if mixed:
        features = [point(origin), point(end), *[feature for feature in features if feature["geometry"]["type"] == "LineString"]]
    request = WalkingRouteRequest(origin=RoutePoint(latitude=0, longitude=0), destination=RoutePoint(latitude=0, longitude=0.001), priority="RECOMMEND")
    return {"type": "FeatureCollection", "features": features}, request


def test_source_order_distinguishes_repeated_coordinate_visits():
    payload, request = loop_fixture()
    result = _normalize_tmap_response(payload, request)
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 11, 22]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [133, 122, 111]


@pytest.mark.parametrize("kind", ["ambiguous-loop", "reverse-order", "outside-route"])
def test_only_unresolvable_or_invalid_guide_positions_are_rejected(kind):
    if kind == "ambiguous-loop":
        payload, request = loop_fixture(mixed=True)
    else:
        payload, request = fixture(guides_first=True)
        if kind == "reverse-order":
            payload["features"][0], payload["features"][2] = payload["features"][2], payload["features"][0]
        else:
            payload["features"][1]["geometry"]["coordinates"] = [0.0009, 0.001]
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.code == "invalid_tmap_response"
    assert error.value.http_status == 502
    assert error.value.message == GUIDE_ERROR


def test_provenance_optional_legacy_contract_does_not_invent_a_source():
    payload, request = fixture()
    legacy = _normalize_tmap_response(payload, request).model_dump()
    legacy.pop("distance_source", None)
    assert WalkingRouteResponse.model_validate(legacy).distance_source is None
    legacy["distance_source"] = None
    assert WalkingRouteResponse.model_validate(legacy).distance_source is None


@pytest.mark.parametrize("source", ["", "provider", 7, True, {}, []])
def test_provenance_rejects_unknown_or_malformed_values(source):
    payload, request = fixture()
    response = _normalize_tmap_response(payload, request).model_dump()
    response["distance_source"] = source
    with pytest.raises(ValidationError):
        WalkingRouteResponse.model_validate(response)


@pytest.mark.parametrize("gap_m, accepted", [(29.999, True), (30.001, False)])
def test_offset_uses_the_existing_thirty_metre_consumer_boundary(gap_m, accepted):
    payload, request = fixture()
    latitude = degrees(gap_m / 6371000.0)
    payload["features"] = [point([0.00045, latitude]), line([0, 0], [0.0018, 0], 200)]
    if accepted:
        result = _normalize_tmap_response(payload, request)
        assert result.guide_points[0].distance_from_start_m == 50
        assert result.guide_points[0].remaining_distance_m == 150
    else:
        with pytest.raises(TmapPedestrianRouteError, match=GUIDE_ERROR):
            _normalize_tmap_response(payload, request)


@pytest.mark.parametrize("offset", [0.0, 0.000005])
def test_corner_shared_vertex_and_normal_offset_keep_one_visit(offset):
    start, corner, end = [0, 0], [0.0009, 0], [0.0009, 0.0009]
    guide = [corner[0] - offset, corner[1] + 2 * offset]
    features = [point(start), line(start, corner, 100), point(guide, 12), line(corner, end, 100), point(end)]
    request = WalkingRouteRequest(origin=RoutePoint(latitude=0, longitude=0), destination=RoutePoint(latitude=0.0009, longitude=0.0009), priority="RECOMMEND")
    result = _normalize_tmap_response({"type": "FeatureCollection", "features": features}, request)
    assert [step.distance_m for step in result.steps] == [100, 100]
    middle = 100 if offset == 0 else 101
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, middle, 200]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [200, 200 - middle, 0]


def test_later_unique_guide_disambiguates_earlier_repeated_coordinates():
    payload, request = loop_fixture()
    lines = [feature for feature in payload["features"] if feature["geometry"]["type"] == "LineString"]
    payload["features"] = [point([0, 0]), point([0, 0]), point([0, 0.0001], 14), point([0, 0], 12), *lines]
    result = _normalize_tmap_response(payload, request)
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 0, 11, 22]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [133, 133, 122, 111]


def test_zero_provider_total_and_zero_step_sum_remain_invalid():
    payload, request = fixture(total=0, distances=(0, 0))
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.code == "route_summary_invalid"
    assert error.value.http_status == 502


def projection_boundary_fixture(coordinates, guides, distance):
    features = [point(guide) for guide in guides]
    features.append({
        "geometry": {"type": "LineString", "coordinates": coordinates},
        "properties": {"distance": distance, "time": 1000, "totalDistance": distance},
    })
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=coordinates[0][1], longitude=coordinates[0][0]),
        destination=RoutePoint(latitude=coordinates[-1][1], longitude=coordinates[-1][0]),
        priority="RECOMMEND",
    )
    return {"type": "FeatureCollection", "features": features}, request


@pytest.mark.parametrize("coordinates, guide", [
    ([[0, 0], [0.001, 0], [0, 0], [0.001, 0.001]], [0.0003, 0]),
    ([[0, 0], [0.001, 0], [0.0001, 0], [0.001, 0.001]], [0.0003, 0]),
    ([[0, 0], [0.001, 0], [0.0001, 0.0000000001], [0.001, 0.001]], [0.0003, 0]),
    ([[0, 0], [0, 0.001], [0.0000000001, 0.0001], [0.001, 0.001]], [0, 0.0003]),
    ([[0, 0], [0.001, 0], [0.0001, 0.00002], [0.001, 0.001]], [0.0003, 0.0000078]),
    ([[0, 0], [0, 0.001], [0.00002, 0.0001], [0.001, 0.001]], [0.0000078, 0.0003]),
    ([[0, 0], [0.001, 0.001], [0, 0], [0.002, 0]], [0.0003, 0.0003]),
])
def test_reversed_edge_roundoff_does_not_hide_a_second_visit(coordinates, guide):
    payload, request = projection_boundary_fixture(coordinates, [guide, coordinates[-1]], 500)
    before = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError, match=GUIDE_ERROR) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.http_status == 502
    assert payload == before


@pytest.mark.parametrize("guide, prefix", [([-0.0001, 0], 132), ([0, 0], 140), ([-0.0000001, 0], 140)])
def test_single_linestring_corner_adjacent_projections_are_one_visit(guide, prefix):
    coordinates = [[-0.0009, -0.0009], [0, 0], [-0.0009, 0.0009]]
    payload, request = projection_boundary_fixture(coordinates, [guide], 280)
    if guide == [-0.0001, 0]:
        # The original offset guide remains the projection under test; a
        # separate corner guide makes this sharp turn voice-complete.
        payload["features"].insert(1, point([0, 0], 12))
    else:
        payload["features"][0]["properties"]["turnType"] = 12
    result = _normalize_tmap_response(payload, request)
    assert result.distance_source == TOTAL
    assert [step.distance_m for step in result.steps] == [280]
    assert result.guide_points[0].distance_from_start_m == prefix
    assert result.guide_points[0].remaining_distance_m == 280 - prefix


@pytest.mark.parametrize("invalid_kind", ["loop", "reversed-interior"])
def test_actual_worker_api_rejects_ambiguous_guide_then_returns_scaled_payload(monkeypatch, invalid_kind):
    async def scenario():
        if invalid_kind == "loop":
            invalid, invalid_request = loop_fixture(mixed=True)
        else:
            invalid, invalid_request = projection_boundary_fixture(
                [[0, 0], [0.001, 0], [0, 0], [0.001, 0.001]], [[0.0003, 0], [0.001, 0.001]], 500,
            )
        valid, request = fixture()
        responses = iter([invalid, valid])
        def provider(provider_request):
            assert str(provider_request.url) == "https://provider.invalid/pedestrian?version=1"
            return httpx.Response(200, json=next(responses))
        settings = SimpleNamespace(walking_route_provider="tmap_pedestrian", tmap_app_key="fixture", tmap_pedestrian_api_version="1", tmap_pedestrian_speed_kmh=4, tmap_pedestrian_route_url="https://provider.invalid/pedestrian", tmap_timeout_seconds=5)
        runtime = TmapRuntime(timeout_seconds=5)
        await runtime.start()
        reset_tmap_success_cache()
        try:
            executor = runtime._executor
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as provider_client:
                async def controlled_fetch(request, settings, **kwargs):
                    return await fetch_tmap_pedestrian_route(request, settings, http_client=provider_client, **kwargs)
                monkeypatch.setattr(navigation, "fetch_tmap_pedestrian_route", controlled_fetch)
                app = FastAPI()
                app.include_router(navigation.create_router(settings, runtime_provider=lambda: runtime))
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
                    failure = await client.post("/navigation/walking", json=invalid_request.model_dump())
                    assert failure.status_code == 502, failure.text
                    assert failure.json()["detail"]["message"] == GUIDE_ERROR
                    assert tmap_dependency_readiness(30)["route_recent"] is False
                    success = await client.post("/navigation/walking", json=request.model_dump())
            assert success.status_code == 200, success.text
            result = success.json()
            assert result["distance_source"] == TOTAL
            assert [step["distance_m"] for step in result["steps"]] == [100, 100]
            assert [guide["distance_from_start_m"] for guide in result["guide_points"]] == [0, 100, 200]
            assert [guide["remaining_distance_m"] for guide in result["guide_points"]] == [200, 100, 0]
            assert runtime._executor is executor
            assert tmap_dependency_readiness(30)["route_recent"] is True
        finally:
            await runtime.aclose()
            reset_tmap_success_cache()
    asyncio.run(scenario())
