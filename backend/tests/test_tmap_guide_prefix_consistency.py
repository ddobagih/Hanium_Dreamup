"""Geometry-scaled guide prefixes use the selected total, including smaller totals."""

import json
from time import monotonic

import pytest

from backend.app.schemas import RoutePoint, WalkingRouteRequest
from backend.app.services.tmap_pedestrian import _normalize_tmap_response
from backend.app.services.tmap_runtime import (
    _decode_response,
    _process_response_job,
)


def _request() -> WalkingRouteRequest:
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=37.0, longitude=127.0),
        destination=RoutePoint(latitude=37.0, longitude=127.001),
        priority="RECOMMEND",
    )


def _route_payload(
    *, first_distance_m: int, last_distance_m: int, summary_distance_m: int | None,
) -> dict:
    start = [127.0, 37.0]
    middle = [127.0005, 37.0]
    end = [127.001, 37.0]
    final_properties = {"distance": last_distance_m, "time": 40, "totalTime": 80}
    if summary_distance_m is not None:
        final_properties["totalDistance"] = summary_distance_m
    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": start},
                "properties": {"description": "Start"},
            },
            {
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": [start, middle]},
                "properties": {"distance": first_distance_m, "time": 40},
            },
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": middle},
                "properties": {"description": "Continue"},
            },
            {
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": [middle, end]},
                "properties": final_properties,
            },
        ],
    }


def test_raw_prefix_exceeding_summary_is_recomputed_in_the_worker_envelope() -> None:
    request = _request()
    payload = _route_payload(first_distance_m=91, last_distance_m=1, summary_distance_m=90)

    envelope = _process_response_job(
        "route", 200, json.dumps(payload).encode(),
        {"request": request.model_dump()}, monotonic() + 5,
    )

    assert envelope["kind"] == "success"
    result = _decode_response("route", envelope)
    assert result.distance_source == "provider_total_geometry_scaled"
    assert result.summary.distance_m == 90
    assert [step.distance_m for step in result.steps] == [45, 45]
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 45]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [90, 45]


@pytest.mark.parametrize(
    ("first_distance_m", "last_distance_m", "expected_remaining_m"),
    [(60, 30, 45), (90, 1, 45)],
)
def test_guide_prefix_at_or_below_selected_summary_is_accepted(
    first_distance_m: int, last_distance_m: int, expected_remaining_m: int,
) -> None:
    result = _normalize_tmap_response(
        _route_payload(
            first_distance_m=first_distance_m,
            last_distance_m=last_distance_m,
            summary_distance_m=90,
        ),
        _request(),
    )

    assert result.summary.distance_m == 90
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 45]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [90, expected_remaining_m]


def test_missing_summary_still_falls_back_to_step_distances() -> None:
    result = _normalize_tmap_response(
        _route_payload(first_distance_m=60, last_distance_m=30, summary_distance_m=None),
        _request(),
    )

    assert result.summary.distance_m == 90
    assert [guide.distance_from_start_m for guide in result.guide_points] == [0, 45]
    assert [guide.remaining_distance_m for guide in result.guide_points] == [90, 45]
