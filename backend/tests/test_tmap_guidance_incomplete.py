"""A route without provider guide points cannot be served as voice guidance."""

import asyncio
from copy import deepcopy
from math import cos, radians
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest
from pydantic import ValidationError

from backend.app.api import navigation
from backend.app.schemas import RoutePoint, WalkingRouteRequest, WalkingRouteResponse
from backend.app.services.tmap_pedestrian import (
    TmapPedestrianRouteError,
    _normalize_tmap_response,
    fetch_tmap_pedestrian_route,
    reset_tmap_success_cache,
    tmap_dependency_readiness,
)
from backend.app.services.tmap_runtime import TmapRuntime


GUIDANCE_ERROR = "TMAP pedestrian route lacks required guide points for voice guidance."


def route_request() -> WalkingRouteRequest:
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=37.0, longitude=127.0),
        destination=RoutePoint(latitude=37.0, longitude=127.001),
        priority="RECOMMEND",
    )


def provider_route(*, turn: bool, guides: bool, turn_guide: bool = False,
                   all_points_first: bool = False, bend_latitude: float = 37.0003) -> dict:
    origin = [127.0, 37.0]
    destination = [127.001, 37.0]
    corners = [origin, [127.0005, bend_latitude], destination] if turn else [origin, destination]
    features = []
    if guides:
        features.append({
            "type": "Feature", "geometry": {"type": "Point", "coordinates": origin},
            "properties": {"description": "Start"},
        })
    corner_feature = {
        "type": "Feature", "geometry": {"type": "Point", "coordinates": corners[1]},
        "properties": {"turnType": 13, "description": "오른쪽으로 꺾으세요."},
    } if turn and turn_guide else None
    if corner_feature and all_points_first:
        features.append(corner_feature)
    for index, (start, end) in enumerate(zip(corners, corners[1:])):
        if index == 1 and corner_feature and not all_points_first:
            features.append(corner_feature)
        features.append({
            "type": "Feature", "geometry": {"type": "LineString", "coordinates": [start, end]},
            "properties": {"distance": 50 if turn else 90, "time": 60 if turn else 80},
        })
    return {"type": "FeatureCollection", "features": features}


@pytest.mark.parametrize("guide_field", ["missing", "null", "empty"])
def test_schema_rejects_missing_or_empty_guide_points(guide_field):
    response = _normalize_tmap_response(provider_route(turn=False, guides=True), route_request())
    payload = response.model_dump()
    if guide_field == "missing":
        payload.pop("guide_points")
    else:
        payload["guide_points"] = None if guide_field == "null" else []
    with pytest.raises(ValidationError):
        WalkingRouteResponse.model_validate(payload)


@pytest.mark.parametrize("turn", [False, True], ids=["straight", "turn"])
def test_normalizer_rejects_line_only_route_without_guide_points(turn):
    payload = provider_route(turn=turn, guides=False)
    original = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request())
    assert caught.value.code == "route_guidance_incomplete"
    assert caught.value.http_status == 502
    assert caught.value.message == GUIDANCE_ERROR
    assert payload == original


@pytest.mark.parametrize("turn", [False, True], ids=["straight", "turn"])
def test_normalizer_keeps_short_and_turning_route_with_guide_points(turn):
    response = _normalize_tmap_response(
        provider_route(turn=turn, guides=True, turn_guide=turn), route_request(),
    )
    assert len(response.steps) == (2 if turn else 1)
    assert len(response.guide_points) == (2 if turn else 1)


def test_normalizer_rejects_missing_turn_guide_when_start_guide_exists():
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(provider_route(turn=True, guides=True), route_request())
    assert caught.value.code == "route_guidance_incomplete"
    assert caught.value.http_status == 502


def test_schema_rejects_removed_turn_guide_from_normalized_route():
    route = _normalize_tmap_response(
        provider_route(turn=True, guides=True, turn_guide=True), route_request(),
    ).model_dump()
    route["guide_points"] = route["guide_points"][:1]
    with pytest.raises(ValidationError, match="route_guidance_incomplete"):
        WalkingRouteResponse.model_validate(route)


def test_turn_guide_requires_a_mapped_direction_type():
    payload = provider_route(turn=True, guides=True, turn_guide=True)
    payload["features"][2]["properties"] = {}
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request())
    assert caught.value.code == "route_guidance_incomplete"

    payload["features"][2]["properties"] = {"description": "Guide"}
    with pytest.raises(TmapPedestrianRouteError) as generic:
        _normalize_tmap_response(payload, route_request())
    assert generic.value.code == "route_guidance_incomplete"

    payload["features"][2]["properties"] = {"description": "오른쪽으로 꺾으세요."}
    with pytest.raises(TmapPedestrianRouteError) as raw_direction:
        _normalize_tmap_response(payload, route_request())
    assert raw_direction.value.code == "route_guidance_incomplete"

    payload["features"][2]["properties"] = {"turnType": 13}
    route = _normalize_tmap_response(payload, route_request()).model_dump()
    assert WalkingRouteResponse.model_validate(route).guide_points[1].turn_type == 13
    route["guide_points"][1]["turn_type"] = None
    with pytest.raises(ValidationError, match="route_guidance_incomplete"):
        WalkingRouteResponse.model_validate(route)


@pytest.mark.parametrize("properties", [
    {"turnType": 211, "description": "횡단보도에서 우회전하세요."},
    {"pointType": "crosswalk", "description": "오른쪽으로 꺾으세요."},
    {"description": "zebra crossing에서 우회전하세요."},
])
def test_crosswalk_reference_text_cannot_replace_a_spoken_turn(properties):
    payload = provider_route(turn=True, guides=True, turn_guide=True)
    payload["features"][2]["properties"] = properties
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request())
    assert caught.value.code == "route_guidance_incomplete"
    assert caught.value.http_status == 502


def test_crosswalk_marker_on_point_name_is_not_a_spoken_turn_in_schema():
    route = _normalize_tmap_response(
        provider_route(turn=True, guides=True, turn_guide=True), route_request(),
    ).model_dump()
    route["guide_points"][1]["point"]["name"] = "횡단보도"
    route["guide_points"][1]["turn_type"] = 211
    with pytest.raises(ValidationError, match="route_guidance_incomplete"):
        WalkingRouteResponse.model_validate(route)


def test_crosswalk_with_mapped_turn_or_separate_coded_guide_stays_valid():
    payload = provider_route(turn=True, guides=True, turn_guide=True)
    payload["features"][2]["properties"] = {
        "turnType": 211, "description": "횡단보도에서 우회전하세요.", "pointType": "crosswalk",
    }
    payload["features"].insert(3, {
        "geometry": {"type": "Point", "coordinates": [127.0005, 37.0003]},
        "properties": {"turnType": 12, "description": "좌 회전하세요."},
    })
    assert len(_normalize_tmap_response(payload, route_request()).guide_points) == 3

    payload["features"].pop(3)
    payload["features"][2]["properties"]["turnType"] = 13
    assert len(_normalize_tmap_response(payload, route_request()).guide_points) == 2

    payload["features"][2]["properties"] = {"description": "좌 회전하세요."}
    with pytest.raises(TmapPedestrianRouteError) as text_only:
        _normalize_tmap_response(payload, route_request())
    assert text_only.value.code == "route_guidance_incomplete"


def test_walking_api_fails_closed_for_crosswalk_text_only_turn(monkeypatch):
    async def scenario():
        payload = provider_route(turn=True, guides=True, turn_guide=True)
        payload["features"][2]["properties"] = {
            "turnType": 211, "description": "횡단보도에서 우회전하세요.",
        }
        responses = iter([payload, provider_route(turn=True, guides=True, turn_guide=True)])

        def provider(_request):
            return httpx.Response(200, json=next(responses))

        settings = SimpleNamespace(
            walking_route_provider="tmap_pedestrian", tmap_app_key="fixture",
            tmap_pedestrian_api_version="1", tmap_pedestrian_speed_kmh=4,
            tmap_pedestrian_route_url="https://provider.invalid/pedestrian",
            tmap_timeout_seconds=5,
        )
        runtime = TmapRuntime(timeout_seconds=5)
        await runtime.start()
        reset_tmap_success_cache()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as provider_client:
                async def controlled_fetch(request, settings, **kwargs):
                    return await fetch_tmap_pedestrian_route(
                        request, settings, http_client=provider_client, **kwargs,
                    )

                monkeypatch.setattr(navigation, "fetch_tmap_pedestrian_route", controlled_fetch)
                app = FastAPI()
                app.include_router(navigation.create_router(settings, runtime_provider=lambda: runtime))
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
                    failure = await client.post("/navigation/walking", json=route_request().model_dump())
                    assert failure.status_code == 502, failure.text
                    assert failure.json()["detail"]["code"] == "route_guidance_incomplete"
                    success = await client.post("/navigation/walking", json=route_request().model_dump())
                    assert success.status_code == 200, success.text
        finally:
            await runtime.aclose()
            reset_tmap_success_cache()

    asyncio.run(scenario())


def test_all_points_first_guide_block_still_covers_turn():
    route = _normalize_tmap_response(
        provider_route(turn=True, guides=True, turn_guide=True, all_points_first=True), route_request(),
    )
    assert len(route.guide_points) == 2


def test_small_step_boundary_bend_and_zero_edge_do_not_require_turn_guide():
    shallow = provider_route(turn=True, guides=True, bend_latitude=37.00002)
    assert len(_normalize_tmap_response(shallow, route_request()).guide_points) == 1
    zero_edge = provider_route(turn=False, guides=True)
    zero_edge["features"].insert(1, {
        "type": "Feature", "geometry": {"type": "LineString", "coordinates": [[127.0, 37.0], [127.0, 37.0]]},
        "properties": {"distance": 0, "time": 0},
    })
    assert len(_normalize_tmap_response(zero_edge, route_request()).guide_points) == 1


def test_single_step_internal_corner_requires_its_own_guide():
    route = provider_route(turn=False, guides=True)
    route["features"][1]["geometry"]["coordinates"] = [
        [127.0, 37.0], [127.0005, 37.0003], [127.001, 37.0],
    ]
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(route, route_request())
    assert caught.value.code == "route_guidance_incomplete"

    route["features"].insert(1, {
        "type": "Feature", "geometry": {"type": "Point", "coordinates": [127.0005, 37.0003]},
        "properties": {"turnType": 13},
    })
    assert len(_normalize_tmap_response(route, route_request()).guide_points) == 2


def test_short_right_angle_step_boundary_needs_a_corner_guide():
    short_request = WalkingRouteRequest(
        origin=RoutePoint(latitude=37.0, longitude=127.0),
        destination=RoutePoint(latitude=37.0001, longitude=127.000125),
        priority="RECOMMEND",
    )
    route = provider_route(turn=True, guides=True)
    route["features"][1]["geometry"]["coordinates"] = [[127.0, 37.0], [127.0, 37.0001]]
    route["features"][1]["properties"]["distance"] = 11
    route["features"][2]["geometry"]["coordinates"] = [[127.0, 37.0001], [127.000125, 37.0001]]
    route["features"][2]["properties"]["distance"] = 11
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(route, short_request)
    assert caught.value.code == "route_guidance_incomplete"


def test_split_straight_legs_cannot_hide_an_internal_turn():
    origin = [127.0, 37.0]
    north_six = [127.0, 37.000054]
    corner = [127.0, 37.000108]
    east_six = [127.0000675, 37.000108]
    destination = [127.000135, 37.000108]
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=origin[1], longitude=origin[0]),
        destination=RoutePoint(latitude=destination[1], longitude=destination[0]),
        priority="RECOMMEND",
    )
    payload = {
        "type": "FeatureCollection",
        "features": [
            {"geometry": {"type": "Point", "coordinates": origin}, "properties": {"description": "Start"}},
            {"geometry": {"type": "LineString", "coordinates": [
                origin, north_six, corner, east_six, destination,
            ]}, "properties": {"distance": 24, "time": 60}},
        ],
    }
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, request)
    assert caught.value.code == "route_guidance_incomplete"

    payload["features"].insert(1, {
        "geometry": {"type": "Point", "coordinates": corner},
        "properties": {"turnType": 13},
    })
    assert len(_normalize_tmap_response(payload, request).guide_points) == 2


def _single_line_route(points, *, corner=None):
    physical_m = sum(
        ((end[0] - start[0]) ** 2 * cos(radians((start[1] + end[1]) / 2)) ** 2
         + (end[1] - start[1]) ** 2) ** 0.5 * 111_195
        for start, end in zip(points, points[1:])
    )
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=points[0][1], longitude=points[0][0]),
        destination=RoutePoint(latitude=points[-1][1], longitude=points[-1][0]),
        priority="RECOMMEND",
    )
    features = [{"geometry": {"type": "Point", "coordinates": points[0]},
                 "properties": {"description": "Start"}}]
    if corner is not None:
        features.append({"geometry": {"type": "Point", "coordinates": corner},
                         "properties": {"turnType": 13}})
    features.append({"geometry": {"type": "LineString", "coordinates": points},
                     "properties": {"distance": max(1, round(physical_m)), "time": 60}})
    return {"type": "FeatureCollection", "features": features}, request


@pytest.mark.parametrize("north_count,east_count", [(19, 20), (20, 19)])
def test_submetre_segments_still_reveal_long_right_angle_legs(north_count, east_count):
    # Individual 0.6 m edges are sub-threshold, but each straight run exceeds 10 m.
    north_step = 0.6 / 111_195
    east_step = 0.6 / (111_195 * cos(radians(37)))
    origin = [127.0, 37.0]
    points = [origin]
    points.extend([127.0, 37.0 + index * north_step] for index in range(1, north_count + 1))
    corner = points[-1]
    points.extend([127.0 + index * east_step, corner[1]] for index in range(1, east_count + 1))
    payload, request = _single_line_route(points)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, request)
    assert caught.value.code == "route_guidance_incomplete"

    guided, request = _single_line_route(points, corner=corner)
    assert len(_normalize_tmap_response(guided, request).guide_points) == 2


def test_short_diagonal_spur_cannot_hide_a_long_right_angle_turn():
    origin = [127.0, 37.0]
    before = [127.0, 37.0 + 22 / 111_195]
    spur = [before[0] + 0.05 / (111_195 * cos(radians(37))), before[1] + 0.05 / 111_195]
    destination = [spur[0] + 26 / (111_195 * cos(radians(37))), spur[1]]
    points = [origin, before, spur, destination]
    payload, request = _single_line_route(points)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, request)
    assert caught.value.code == "route_guidance_incomplete"

    guided, request = _single_line_route(points, corner=before)
    assert len(_normalize_tmap_response(guided, request).guide_points) == 2


def test_two_gradual_forty_five_degree_bends_need_no_turn_guide():
    origin = [127.0, 37.0]
    north = [127.0, 37.0 + 12 / 111_195]
    diagonal = [north[0] + 12 / (111_195 * cos(radians(37)) * 2**0.5),
                north[1] + 12 / (111_195 * 2**0.5)]
    destination = [diagonal[0] + 12 / (111_195 * cos(radians(37))), diagonal[1]]
    payload, request = _single_line_route([origin, north, diagonal, destination])
    assert len(_normalize_tmap_response(payload, request).guide_points) == 1


def test_worker_and_api_report_incomplete_guidance_then_accept_guided_route(monkeypatch):
    async def scenario():
        responses = iter([
            provider_route(turn=True, guides=True),
            provider_route(turn=True, guides=True, turn_guide=True),
        ])

        def provider(_request):
            return httpx.Response(200, json=next(responses))

        settings = SimpleNamespace(
            walking_route_provider="tmap_pedestrian", tmap_app_key="fixture",
            tmap_pedestrian_api_version="1", tmap_pedestrian_speed_kmh=4,
            tmap_pedestrian_route_url="https://provider.invalid/pedestrian",
            tmap_timeout_seconds=5,
        )
        runtime = TmapRuntime(timeout_seconds=5)
        await runtime.start()
        reset_tmap_success_cache()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as provider_client:
                async def controlled_fetch(request, settings, **kwargs):
                    return await fetch_tmap_pedestrian_route(
                        request, settings, http_client=provider_client, **kwargs,
                    )

                monkeypatch.setattr(navigation, "fetch_tmap_pedestrian_route", controlled_fetch)
                app = FastAPI()
                app.include_router(navigation.create_router(settings, runtime_provider=lambda: runtime))
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
                    failure = await client.post("/navigation/walking", json=route_request().model_dump())
                    assert failure.status_code == 502, failure.text
                    assert failure.json()["detail"]["code"] == "route_guidance_incomplete"
                    assert failure.json()["detail"]["message"] == GUIDANCE_ERROR
                    assert tmap_dependency_readiness(30)["route_recent"] is False
                    success = await client.post("/navigation/walking", json=route_request().model_dump())
                    assert success.status_code == 200, success.text
                    assert len(success.json()["guide_points"]) == 2
        finally:
            await runtime.aclose()
            reset_tmap_success_cache()

    asyncio.run(scenario())
