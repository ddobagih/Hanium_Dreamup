"""S20-27: the walking-route response states how far its ends are from the requested points."""
from __future__ import annotations

from pathlib import Path
import sys

import pytest
from pydantic import ValidationError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from backend.app.schemas import RoutePoint, WalkingRouteRequest, WalkingRouteResponse  # noqa: E402
from backend.app.services.tmap_pedestrian import (  # noqa: E402
    TmapPedestrianRouteError,
    _normalize_tmap_response,
)
from backend.app.services.walking_route_sanity import route_point_distance_m  # noqa: E402

ORIGIN = {"latitude": 37.39472714688412, "longitude": 127.11015314141542}
DESTINATION = {"latitude": 37.401937080111644, "longitude": 127.10824367964793, "name": "테스트 목적지"}
METERS_PER_LATITUDE_DEGREE = 111_320.0


def _request() -> WalkingRouteRequest:
    return WalkingRouteRequest.model_validate(
        {"origin": ORIGIN, "destination": DESTINATION, "priority": "STAIR_AVOID"}
    )


def _payload(start: RoutePoint, end: RoutePoint) -> dict[str, object]:
    length_m = round(route_point_distance_m(start, end))
    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [start.longitude, start.latitude],
                },
                "properties": {"description": "출발"},
            },
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[start.longitude, start.latitude], [end.longitude, end.latitude]],
                },
                "properties": {
                    "totalDistance": length_m,
                    "totalTime": round(length_m / 1.2),
                    "distance": length_m,
                    "time": round(length_m / 1.2),
                },
            }
        ],
    }


def _shifted(point: dict[str, float], north_m: float) -> RoutePoint:
    return RoutePoint(
        latitude=point["latitude"] + north_m / METERS_PER_LATITUDE_DEGREE,
        longitude=point["longitude"],
    )


def test_route_reports_start_and_end_gaps_in_metres() -> None:
    request = _request()
    start = _shifted(ORIGIN, 30.0)
    end = _shifted(DESTINATION, -45.0)
    response = _normalize_tmap_response(_payload(start, end), request)
    assert response.start_gap_m == round(route_point_distance_m(request.origin, start))
    assert response.end_gap_m == round(route_point_distance_m(request.destination, end))
    assert abs(response.start_gap_m - 30) <= 1
    assert abs(response.end_gap_m - 45) <= 1
    dumped = response.model_dump()
    assert dumped["start_gap_m"] == response.start_gap_m
    assert dumped["end_gap_m"] == response.end_gap_m


def test_exact_endpoints_report_zero_gaps() -> None:
    request = _request()
    response = _normalize_tmap_response(
        _payload(request.origin, RoutePoint(latitude=DESTINATION["latitude"], longitude=DESTINATION["longitude"])),
        request,
    )
    assert (response.start_gap_m, response.end_gap_m) == (0, 0)


@pytest.mark.parametrize("north_m", [36.0, 60.0, 99.0])
def test_large_start_gap_is_reported_without_inventing_a_connector(north_m: float) -> None:
    request = _request()
    start = _shifted(ORIGIN, north_m)
    end = _shifted(DESTINATION, 0.0)
    response = _normalize_tmap_response(_payload(start, end), request)
    assert response.start_gap_m == round(route_point_distance_m(request.origin, start))
    assert response.polyline[0] == start
    assert response.end_gap_m == 0


@pytest.mark.parametrize("north_m", [-101.0, 101.0])
def test_the_100_m_endpoint_bound_still_rejects(north_m: float) -> None:
    request = _request()
    with pytest.raises(TmapPedestrianRouteError) as rejected:
        _normalize_tmap_response(_payload(request.origin, _shifted(DESTINATION, north_m)), request)
    assert rejected.value.code == "route_endpoint_mismatch"


def test_gap_fields_are_optional_and_non_negative() -> None:
    base = {
        "schema_version": "walksafe.walking_route.v1",
        "provider": "tmap_pedestrian",
        "priority": "STAIR_AVOID",
        "summary": {"distance_m": 10, "duration_s": 10},
        "polyline": [ORIGIN, DESTINATION],
        "steps": [],
        "guide_points": [{"index": 0, "point": ORIGIN, "instruction": "출발"}],
        "provider_result_code": 0,
        "provider_result_message": "OK",
    }
    legacy = WalkingRouteResponse.model_validate(base)
    assert legacy.start_gap_m is None and legacy.end_gap_m is None
    with pytest.raises(ValidationError):
        WalkingRouteResponse.model_validate({**base, "end_gap_m": -1})
