"""S07-12: preserve provided road codes through the existing normalized response DTO."""

from copy import deepcopy
import json

import pytest
from pydantic import ValidationError

from backend.app.schemas import RoutePoint, WalkingRouteRequest, WalkingRouteResponse
from backend.app.services.tmap_pedestrian import TmapPedestrianRouteError, _normalize_tmap_response


MISSING = object()


def route_request():
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=37.0, longitude=127.0),
        destination=RoutePoint(latitude=37.0, longitude=127.001),
        priority="STAIR_AVOID",
    )


def provider_fixture(code=MISSING):
    line_properties = {
        "distance": 90, "time": 80, "name": "보행길",
        "description": "제공된 구간 설명", "facilityType": 15,
    }
    if code is not MISSING:
        line_properties["roadType"] = code
    return {"type": "FeatureCollection", "features": [
        {"geometry": {"type": "Point", "coordinates": [127.0, 37.0]},
         "properties": {"totalDistance": 90, "totalTime": 80, "pointType": "SP",
                        "turnType": 200, "facilityType": 11, "description": "출발"}},
        {"geometry": {"type": "LineString", "coordinates": [[127.0, 37.0], [127.001, 37.0]]},
         "properties": line_properties},
    ]}


def serialized_route(payload):
    # This is the actual production normalizer's WalkingRouteResponse serialization.
    return json.loads(_normalize_tmap_response(payload, route_request()).model_dump_json())


@pytest.mark.parametrize("code", [16, 999, 0, 2_147_483_647])
def test_provided_road_code_survives_normalizer_and_response_json(code):
    payload = provider_fixture(code)
    original = deepcopy(payload)
    body = serialized_route(payload)
    assert body["steps"][0].get("road_type") == code
    assert payload == original
    assert body["priority"] == "STAIR_AVOID"
    assert body["summary"] == {"distance_m": 90, "duration_s": 80}
    assert body["steps"][0]["facility_type"] == 15
    assert body["steps"][0]["road_name"] == "보행길"
    assert body["steps"][0]["instruction"] == "제공된 구간 설명"
    assert body["steps"][0]["turn_type"] is None
    assert body["guide_points"][0]["point_type"] == "SP"
    assert body["guide_points"][0]["turn_type"] == 200
    assert body["guide_points"][0]["facility_type"] == 11
    assert body["polyline"] == [
        {"latitude": 37.0, "longitude": 127.0, "name": None},
        {"latitude": 37.0, "longitude": 127.001, "name": None},
    ]


@pytest.mark.parametrize(("raw", "expected"), [("16", 16), (16.0, 16), ("00016", 16)])
def test_provider_code_forms_follow_the_existing_tmap_helper(raw, expected):
    assert serialized_route(provider_fixture(raw))["steps"][0].get("road_type") == expected


@pytest.mark.parametrize("bad", [None, True, -1, 16.5, 2_147_483_648, "bad", "16.5", {}, float("inf")])
def test_invalid_present_provider_code_follows_the_existing_helper(bad):
    payload = provider_fixture(bad)
    original = deepcopy(payload)
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request())
    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502
    assert payload == original


@pytest.mark.parametrize("bad", [True, -1, 16.0, 16.5, 2_147_483_648, "16", {}])
def test_normalized_response_dto_keeps_the_existing_strict_integer_rule(bad):
    body = serialized_route(provider_fixture())
    body["steps"][0]["road_type"] = bad
    with pytest.raises(ValidationError):
        WalkingRouteResponse.model_validate(body)


@pytest.mark.parametrize("explicit_null", [False, True])
def test_absent_or_null_normalized_metadata_stays_unknown(explicit_null):
    body = serialized_route(provider_fixture())
    if explicit_null:
        body["steps"][0]["road_type"] = None
    else:
        body["steps"][0].pop("road_type", None)
    restored = WalkingRouteResponse.model_validate(body)
    actual = json.loads(restored.model_dump_json())
    assert actual["steps"][0].get("road_type") is None
    assert actual["steps"][0]["facility_type"] == 15
    assert actual["summary"] == body["summary"]


def test_two_provided_step_codes_retain_their_order_and_geometry():
    payload = provider_fixture(16)
    line = payload["features"][1]
    middle = [127.0005, 37.0]
    line["geometry"]["coordinates"] = [[127.0, 37.0], middle]
    line["properties"].update({"distance": 45, "time": 40})
    following = deepcopy(line)
    following["geometry"]["coordinates"] = [middle, [127.001, 37.0]]
    following["properties"].update({"name": "다음길", "roadType": 999})
    payload["features"].append(following)
    body = serialized_route(payload)
    assert [step.get("road_type") for step in body["steps"]] == [16, 999]
    assert [step["index"] for step in body["steps"]] == [0, 1]
    assert [step["road_name"] for step in body["steps"]] == ["보행길", "다음길"]
    assert [step["distance_m"] for step in body["steps"]] == [45, 45]
    assert body["steps"][0]["points"][-1] == body["steps"][1]["points"][0]
    assert body["summary"] == {"distance_m": 90, "duration_s": 80}


def test_unrelated_properties_do_not_change_the_normalized_route():
    payload = provider_fixture()
    expected = serialized_route(payload)
    payload["features"][1]["properties"]["futureRoadAttribute"] = "opaque"
    assert serialized_route(payload) == expected


def test_known_stairs_are_still_rejected_when_a_road_code_is_present():
    payload = provider_fixture(999)
    payload["features"][1]["properties"]["facilityType"] = 17
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request())
    assert caught.value.code == "route_stairs_present"


def test_unknown_facility_safety_is_not_resolved_by_a_road_code():
    payload = provider_fixture(16)
    payload["features"][1]["properties"]["facilityType"] = 999
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request())
    assert caught.value.code == "route_safety_code_unknown"
