"""Route segments must satisfy the route's existing geometry distance bounds."""

import asyncio
from copy import deepcopy
from math import ceil, floor
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest

from backend.app.api import navigation
from backend.app.schemas import RoutePoint, WalkingRouteRequest
from backend.app.services.tmap_pedestrian import (
    TmapPedestrianRouteError,
    _normalize_tmap_response,
    fetch_tmap_pedestrian_route,
    reset_tmap_success_cache,
    tmap_dependency_readiness,
)
from backend.app.services.tmap_runtime import TmapRuntime
from backend.app.services.walking_route_sanity import route_point_distance_m


DISTANCE_MESSAGE = "TMAP pedestrian route LineString distance is missing or invalid."


def route_fixture(distances=(500, 500), *, middle_x=0.0045, end_x=0.009):
    coordinates = [[0.0, 0.0], [middle_x, 0.0], [end_x, 0.0]]
    features = []
    for index, coordinate in enumerate(coordinates):
        features.append({
            "type": "Feature", "geometry": {"type": "Point", "coordinates": coordinate},
            "properties": {"description": f"Guide {index}"},
        })
        if index < 2:
            features.append({
                "type": "Feature", "geometry": {
                    "type": "LineString", "coordinates": [coordinate, coordinates[index + 1]],
                },
                "properties": {"distance": distances[index], "time": 400},
            })
    features[0]["properties"].update(totalDistance=1000, totalTime=800)
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=0.0, longitude=0.0),
        destination=RoutePoint(latitude=0.0, longitude=end_x), priority="RECOMMEND",
    )
    return {"type": "FeatureCollection", "features": features}, request


@pytest.mark.parametrize("distances", [(0, 1000), (1000, 0), (1, 999), (5000, 500)])
def test_segment_distance_cannot_hide_behind_a_valid_whole_route_summary(distances):
    payload, request = route_fixture(distances)
    before = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request)
    assert error.value.code == "invalid_tmap_response"
    assert error.value.http_status == 502
    assert error.value.message == DISTANCE_MESSAGE
    assert error.value.provider_status is None
    assert error.value.provider_result_code is None
    assert payload == before


@pytest.mark.parametrize("raw, expected", [(0, 0), (0.75, 0), (12.75, 12), ("+12", 12)])
@pytest.mark.parametrize("shape", ["moving", "zero-edge", "single-point"])
def test_short_segment_rounding_and_zero_edges_keep_provider_distances(raw, expected, shape):
    middle_x = 0.0004 if shape == "moving" else 0.0
    payload, request = route_fixture((raw, 90), middle_x=middle_x, end_x=0.0008)
    payload["features"][0]["properties"]["totalDistance"] = 180
    if shape == "single-point":
        payload["features"][1]["geometry"]["coordinates"] = [[0.0, 0.0]]
    before = deepcopy(payload)
    result = _normalize_tmap_response(payload, request)
    assert result.summary.distance_m == 180
    from backend.app.services.tmap_pedestrian import _required_tmap_step_distance
    assert _required_tmap_step_distance({"distance": raw}) == expected
    middle = 90 if shape == "moving" else 0
    assert [step.distance_m for step in result.steps] == [middle, 180 - middle]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, middle, 180]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [180, 180 - middle, 0]
    assert payload == before


@pytest.mark.parametrize("edge", ["lower", "upper"])
@pytest.mark.parametrize("inside", [False, True])
def test_segment_uses_existing_route_bounds_without_a_new_rounding_tolerance(edge, inside):
    payload, request = route_fixture()
    geometry_m = route_point_distance_m(request.origin, RoutePoint(latitude=0.0, longitude=0.0045))
    # Independent expected boundary: the existing whole-route policy, in metres.
    distance = ceil(geometry_m * 0.5 - 50) if edge == "lower" else floor(geometry_m * 3 + 50)
    if not inside:
        distance += -1 if edge == "lower" else 1
    payload["features"][1]["properties"]["distance"] = distance
    payload["features"][0]["properties"]["totalDistance"] = distance + 500
    if inside:
        result = _normalize_tmap_response(payload, request)
        assert result.steps[0].distance_m == floor((distance + 500) / 2 + 0.5)
        assert result.summary.distance_m == distance + 500
    else:
        with pytest.raises(TmapPedestrianRouteError, match=DISTANCE_MESSAGE):
            _normalize_tmap_response(payload, request)


@pytest.mark.parametrize("distances", [(0, 1000), (1, 999), (5000, 500)])
def test_segment_failure_survives_real_worker_and_api_then_accepts_valid_route(monkeypatch, distances):
    async def scenario():
        invalid, request = route_fixture(distances)
        valid, _ = route_fixture()
        responses = iter([invalid, valid])
        calls = []
        def provider(provider_request):
            assert str(provider_request.url) == "https://provider.invalid/pedestrian?version=1"
            calls.append(provider_request)
            return httpx.Response(200, json=next(responses))
        settings = SimpleNamespace(
            walking_route_provider="tmap_pedestrian", tmap_app_key="fixture-key",
            tmap_pedestrian_api_version="1", tmap_pedestrian_speed_kmh=4,
            tmap_pedestrian_route_url="https://provider.invalid/pedestrian", tmap_timeout_seconds=5,
        )
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
                    failure = await client.post("/navigation/walking", json=request.model_dump())
                    assert failure.status_code == 502, failure.text
                    assert failure.json()["detail"] == {
                        "code": "invalid_tmap_response", "message": DISTANCE_MESSAGE,
                        "provider_status": None, "provider_result_code": None,
                    }
                    assert tmap_dependency_readiness(30)["route_recent"] is False
                    success = await client.post("/navigation/walking", json=request.model_dump())
            assert success.status_code == 200, success.text
            result = success.json()
            assert result["summary"]["distance_m"] == 1000
            assert [step["distance_m"] for step in result["steps"]] == [500, 500]
            assert [guide["distance_from_start_m"] for guide in result["guide_points"]] == [0, 500, 1000]
            assert [guide["remaining_distance_m"] for guide in result["guide_points"]] == [1000, 500, 0]
            assert len(calls) == 2
            assert runtime._executor is executor
            assert tmap_dependency_readiness(30)["route_recent"] is True
        finally:
            await runtime.aclose()
            reset_tmap_success_cache()
    asyncio.run(scenario())
