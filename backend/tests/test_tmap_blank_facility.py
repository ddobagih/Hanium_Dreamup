"""Actual TMAP destination metadata may contain an empty optional facility string."""

from copy import deepcopy
import json
from pathlib import Path
from time import monotonic

import pytest

from backend.app.schemas import RoutePoint, WalkingRouteRequest
from backend.app.services.tmap_pedestrian import TmapPedestrianRouteError, _normalize_tmap_response
from backend.app.services.tmap_runtime import TmapRuntimeError, _decode_response, _process_response_job


def provider_response():
    # Public Seoul City Hall readiness coordinates, not a user's location/history.
    return json.loads(Path(__file__).with_name("fixtures").joinpath(
        "tmap_pedestrian_blank_facility.json"
    ).read_text())


def route_request():
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=37.5662952, longitude=126.9779451, name="readiness-start"),
        destination=RoutePoint(latitude=37.5664852, longitude=126.9781451, name="readiness-end"),
        priority="STAIR_AVOID",
    )


def worker_job(payload):
    return _process_response_job(
        "route", 200, json.dumps(payload).encode(),
        {"request": route_request().model_dump(mode="json"), "speed_kmh": 4},
        monotonic() + 10,
    )


def test_live_destination_blank_facility_is_unknown_not_safe_zero():
    payload = provider_response()
    original = deepcopy(payload)
    assert payload["features"][-1]["properties"]["facilityType"] == ""
    route = _decode_response("route", worker_job(payload))
    assert route.guide_points[-1].point_type == "EP"
    assert route.guide_points[-1].turn_type == 201
    assert route.guide_points[-1].facility_type is None
    assert all(step.facility_type == 11 for step in route.steps)
    assert [step.road_type for step in route.steps] == [0, 0, 23]
    # The optional blank has exactly the same meaning as an omitted facility.
    omitted = deepcopy(payload)
    omitted["features"][-1]["properties"].pop("facilityType")
    assert route == _normalize_tmap_response(omitted, route_request(), speed_kmh=4)
    assert payload == original


@pytest.mark.parametrize("feature_index", [0, 1, -1], ids=["start", "line", "end"])
@pytest.mark.parametrize("bad", [None, True, -1, 17.5, "bad", " ", 2_147_483_648])
def test_malformed_facility_still_rejected_and_remains_a_provider_input_error(feature_index, bad):
    payload = provider_response()
    payload["features"][feature_index]["properties"]["facilityType"] = bad
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", worker_job(payload))
    assert (caught.value.code, caught.value.http_status) == ("invalid_tmap_response", 502)
    assert caught.value.message == "TMAP pedestrian route response violates the WalkSafe schema."


@pytest.mark.parametrize("field,feature_index", [("roadType", 1), ("turnType", 0)])
def test_other_code_fields_do_not_gain_blank_string_compatibility(field, feature_index):
    payload = provider_response()
    payload["features"][feature_index]["properties"][field] = ""
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", worker_job(payload))
    assert (caught.value.code, caught.value.http_status) == ("invalid_tmap_response", 502)


@pytest.mark.parametrize("code,expected", [(17, "route_stairs_present"), (0, "route_safety_code_unknown"), (999, "route_safety_code_unknown")])
@pytest.mark.parametrize("feature_index", [1, -1], ids=["line", "end"])
def test_blank_compatibility_does_not_hide_stairs_or_unknown_codes(feature_index, code, expected):
    payload = provider_response()
    payload["features"][feature_index]["properties"]["facilityType"] = code
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request(), speed_kmh=4)
    assert caught.value.code == expected


def test_blank_facility_does_not_hide_stair_turn_instruction():
    payload = provider_response()
    payload["features"][-1]["properties"]["turnType"] = 127
    with pytest.raises(TmapPedestrianRouteError) as caught:
        _normalize_tmap_response(payload, route_request(), speed_kmh=4)
    assert caught.value.code == "route_stairs_present"
