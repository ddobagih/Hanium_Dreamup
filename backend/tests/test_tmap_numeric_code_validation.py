"""Malformed TMAP numbers and unmapped safety codes must not become safe zeroes."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from backend.app.schemas import RoutePoint, WalkingRouteRequest, WalkingRouteResponse
from backend.app.services.tmap_pedestrian import TmapPedestrianRouteError, _normalize_tmap_response


def _request(priority="RECOMMEND"):
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=37.0, longitude=127.0),
        destination=RoutePoint(latitude=37.0, longitude=127.001),
        priority=priority,
    )


def _straight_route():
    return {"type": "FeatureCollection", "features": [
        {"geometry": {"type": "Point", "coordinates": [127.0, 37.0]},
         "properties": {"description": "출발", "totalDistance": 90, "totalTime": 80}},
        {"geometry": {"type": "LineString", "coordinates": [[127.0, 37.0], [127.001, 37.0]]},
         "properties": {"distance": 90, "time": 80}},
    ]}


def _turn_route():
    corner = [127.0005, 37.0003]
    return {"type": "FeatureCollection", "features": [
        {"geometry": {"type": "Point", "coordinates": [127.0, 37.0]},
         "properties": {"description": "출발"}},
        {"geometry": {"type": "LineString", "coordinates": [[127.0, 37.0], corner]},
         "properties": {"distance": 50, "time": 60}},
        {"geometry": {"type": "Point", "coordinates": corner},
         "properties": {"turnType": 13, "description": "오른쪽으로 꺾으세요."}},
        {"geometry": {"type": "LineString", "coordinates": [corner, [127.001, 37.0]]},
         "properties": {"distance": 50, "time": 60}},
    ]}


@pytest.mark.parametrize("field,feature_index", [
    ("totalDistance", 0), ("totalTime", 0), ("time", 1),
])
@pytest.mark.parametrize("bad", [None, True, -0.1, float("inf"), "not-a-number"])
def test_present_invalid_summary_or_step_number_is_not_silently_zero(field, feature_index, bad):
    payload = _straight_route()
    payload["features"][feature_index]["properties"][field] = bad
    original = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _request())
    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert payload == original


@pytest.mark.parametrize("field,feature_index", [
    ("turnType", 0), ("facilityType", 0), ("facilityType", 1),
])
@pytest.mark.parametrize("bad", [None, True, -7, 12.5, "bad-code"])
def test_present_invalid_turn_or_facility_code_is_not_normalized_to_zero(field, feature_index, bad):
    payload = _straight_route()
    payload["features"][feature_index]["properties"][field] = bad
    original = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _request())
    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert payload == original


def test_valid_unknown_code_retains_identity_but_cannot_prove_stair_avoidance():
    payload = _straight_route()
    payload["features"][0]["properties"]["turnType"] = 999
    payload["features"][1]["properties"]["facilityType"] = 999
    route = _normalize_tmap_response(payload, _request())
    assert route.guide_points[0].turn_type == 999
    assert route.steps[0].facility_type == 999
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _request("STAIR_AVOID"))
    assert caught.value.code == "route_safety_code_unknown"
    assert caught.value.http_status == 502

    payload["features"][1]["properties"].pop("facilityType")
    with pytest.raises(TmapPedestrianRouteError) as unknown_turn:
        _normalize_tmap_response(payload, _request("STAIR_AVOID"))
    assert unknown_turn.value.code == "route_safety_code_unknown"


@pytest.mark.parametrize("coordinates", [None, [], [127.0005, 91.0]])
@pytest.mark.parametrize("metadata", [
    {"turnType": 127}, {"facilityType": 17}, {"turnType": 999},
    {"facilityType": 0}, {"description": "계단 진입"}, {"turnType": 218},
])
@pytest.mark.parametrize("priority", ["STAIR_AVOID", "RECOMMEND"])
def test_invalid_guide_coordinates_cannot_discard_safety_or_action_metadata(coordinates, metadata, priority):
    payload = _straight_route()
    payload["features"].insert(1, {
        "geometry": {"type": "Point", "coordinates": coordinates},
        "properties": metadata,
    })
    original = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _request(priority))
    assert (caught.value.code, caught.value.http_status) == ("invalid_tmap_response", 502)
    assert caught.value.message == "TMAP pedestrian route guide coordinates are invalid."
    assert payload == original


@pytest.mark.parametrize("feature_index", [0, 1], ids=["guide", "line"])
def test_undocumented_pedestrian_facility_zero_is_unknown(feature_index):
    payload = _straight_route()
    payload["features"][feature_index]["properties"]["facilityType"] = 0
    route = _normalize_tmap_response(payload, _request())
    actual = route.guide_points[0] if feature_index == 0 else route.steps[0]
    assert actual.facility_type == 0
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _request("STAIR_AVOID"))
    assert caught.value.code == "route_safety_code_unknown"
    assert caught.value.http_status == 502


def test_unknown_turn_with_direction_text_does_not_cover_a_required_corner():
    payload = _turn_route()
    valid = _normalize_tmap_response(payload, _request())
    assert valid.guide_points[1].turn_type == 13
    payload["features"][2]["properties"]["turnType"] = 999
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _request())
    assert caught.value.code == "route_guidance_incomplete"

    route_data = valid.model_dump()
    route_data["guide_points"][1]["turn_type"] = 999
    with pytest.raises(ValidationError, match="route_guidance_incomplete"):
        WalkingRouteResponse.model_validate(route_data)


def test_missing_turn_code_with_direction_text_does_not_cover_a_required_corner():
    payload = _turn_route()
    valid = _normalize_tmap_response(payload, _request())
    payload["features"][2]["properties"].pop("turnType")
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, _request())
    assert caught.value.code == "route_guidance_incomplete"

    route_data = valid.model_dump()
    route_data["guide_points"][1]["turn_type"] = None
    with pytest.raises(ValidationError, match="route_guidance_incomplete"):
        WalkingRouteResponse.model_validate(route_data)


@pytest.mark.parametrize("target,field", [
    ("guide_points", "turn_type"),
    ("guide_points", "facility_type"),
    ("steps", "facility_type"),
])
@pytest.mark.parametrize("bad", [True, -1, 2_147_483_648, "17"])
def test_route_schema_rejects_invalid_code_values(target, field, bad):
    route = _normalize_tmap_response(_straight_route(), _request()).model_dump()
    route[target][0][field] = bad
    with pytest.raises(ValidationError):
        WalkingRouteResponse.model_validate(route)
