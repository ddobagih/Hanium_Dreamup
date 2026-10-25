"""Directional crossing references cover a corner without permission to cross."""

from copy import deepcopy
from math import cos, radians, sin

import pytest
from pydantic import ValidationError

from backend.app.schemas import RoutePoint, WalkingRouteRequest, WalkingRouteResponse
from backend.app.services.tmap_pedestrian import (
    TmapPedestrianRouteError,
    _normalize_tmap_response,
)


def coordinate(east_m: float, north_m: float) -> list[float]:
    # Local metre offsets remove provider place names and absolute coordinates.
    metre_degrees = 180 / (6_371_000 * 3.141592653589793)
    return [127 + east_m * metre_degrees / cos(radians(37)), 37 + north_m * metre_degrees]


def feature(kind, coordinates, **properties):
    return {
        "type": "Feature",
        "geometry": {"type": kind, "coordinates": coordinates},
        "properties": properties,
    }


def request_for(payload):
    start = payload["features"][0]["geometry"]["coordinates"]
    end = payload["features"][-1]["geometry"]["coordinates"]
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=start[1], longitude=start[0]),
        destination=RoutePoint(latitude=end[1], longitude=end[0]),
        priority="STAIR_AVOID",
    )


def captured_right_crosswalk():
    # Two adjacent LineStrings and their middle Point from a 2026-10-06
    # real TMAP response. Preserve lengths, shape and codes; omit names/prose.
    incoming = [coordinate(*point) for point in (
        (0, 0), (0.7341, 16.0596), (1.2230, 45.7082),
        (1.2229, 50.6496), (-0.0012, 52.8114),
    )]
    outgoing = [incoming[-1], coordinate(9.0565, 58.9884), coordinate(17.8694, 65.7831)]
    return {"type": "FeatureCollection", "features": [
        feature("Point", incoming[0], turnType=200, pointType="SP",
                facilityType="11", totalDistance=75, totalTime=82),
        feature("LineString", incoming, distance=53, time=67, roadType=23, facilityType="11"),
        feature("Point", incoming[-1], turnType=213, pointType="GP", facilityType="15"),
        feature("LineString", outgoing, distance=22, time=15, roadType=21, facilityType="15"),
        feature("Point", outgoing[-1], turnType=201, pointType="EP", facilityType=""),
    ]}


def crossing_route(turn_type, angle):
    start, corner = coordinate(0, 0), coordinate(0, 50)
    end = coordinate(30 * sin(radians(angle)), 50 + 30 * cos(radians(angle)))
    return {"type": "FeatureCollection", "features": [
        feature("Point", start, turnType=200, pointType="SP", totalDistance=80, totalTime=90),
        feature("LineString", [start, corner], distance=50, time=55, facilityType="11"),
        feature("Point", corner, turnType=turn_type, pointType="GP", facilityType="15"),
        feature("LineString", [corner, end], distance=30, time=35, facilityType="15"),
        feature("Point", end, turnType=201, pointType="EP", facilityType=""),
    ]}


def test_captured_directional_crosswalk_preserves_provider_identity_and_geometry():
    payload = captured_right_crosswalk()
    original = deepcopy(payload)

    route = _normalize_tmap_response(payload, request_for(payload))

    assert payload == original
    assert route.guide_points[1].turn_type == 213
    assert route.guide_points[1].facility_type == 15
    assert route.guide_points[1].instruction is None
    assert route.guide_points[1].distance_from_start_m == 53
    assert len(route.polyline) == 7
    assert sum(step.distance_m for step in route.steps) == 75
    assert WalkingRouteResponse.model_validate(route.model_dump()) == route


@pytest.mark.parametrize(("turn_type", "angle"), [
    (212, -90), (213, 90), (214, -120), (215, -65), (216, 65), (217, 120),
])
def test_known_directional_crosswalk_reference_covers_corresponding_corner(turn_type, angle):
    payload = crossing_route(turn_type, angle)
    route = _normalize_tmap_response(payload, request_for(payload))
    assert route.guide_points[1].turn_type == turn_type
    assert WalkingRouteResponse.model_validate(route.model_dump()) == route


@pytest.mark.parametrize("replacement", [
    {"turnType": 211, "description": "우측 횡단보도 후 이동"},
    {"description": "우측 횡단보도 후 이동"},
    {"turnType": 11, "description": "우측 횡단보도 후 이동"},
    {"turnType": 218, "description": "우측 횡단보도 후 이동"},
])
def test_nondirectional_codes_and_provider_text_still_cannot_cover_corner(replacement):
    payload = captured_right_crosswalk()
    payload["features"][2]["properties"] = replacement
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request_for(payload))
    assert error.value.code == "route_guidance_incomplete"


@pytest.mark.parametrize("replacement", [None, 11, 211, 218])
def test_response_schema_does_not_accept_removed_directional_code(replacement):
    payload = captured_right_crosswalk()
    route = _normalize_tmap_response(payload, request_for(payload)).model_dump()
    route["guide_points"][1]["turn_type"] = replacement
    route["guide_points"][1]["instruction"] = "오른쪽 횡단보도"
    with pytest.raises(ValidationError, match="route_guidance_incomplete"):
        WalkingRouteResponse.model_validate(route)


def test_directional_crosswalk_does_not_cover_a_missing_internal_right_angle_guide():
    payload = crossing_route(213, 90)
    # A clear 90-degree turn more than 5 m away remains unannounced, even
    # when the earlier crossing now has a supported directional reference.
    corner, bend, end = coordinate(0, 50), coordinate(30, 50), coordinate(30, 80)
    payload["features"][3] = feature(
        "LineString", [corner, bend, end], distance=60, time=70, facilityType="15",
    )
    payload["features"][-1]["geometry"]["coordinates"] = end
    payload["features"][0]["properties"].update(totalDistance=110, totalTime=125)
    with pytest.raises(TmapPedestrianRouteError) as error:
        _normalize_tmap_response(payload, request_for(payload))
    assert error.value.code == "route_guidance_incomplete"
