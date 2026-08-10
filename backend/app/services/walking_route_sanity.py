"""Fail-closed checks shared by normalized walking-route providers."""

from __future__ import annotations

from math import atan2, cos, radians, sin, sqrt

from backend.app.schemas import RoutePoint


ROUTE_ENDPOINT_MAX_DISTANCE_M = 100.0
ROUTE_SUMMARY_GEOMETRY_MIN_RATIO = 0.5
ROUTE_SUMMARY_GEOMETRY_MAX_RATIO = 3.0
ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M = 50.0


class WalkingRouteSanityError(ValueError):
    def __init__(self, *, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def route_point_distance_m(start: RoutePoint, end: RoutePoint) -> float:
    earth_radius_m = 6_371_000.0
    start_lat = radians(start.latitude)
    end_lat = radians(end.latitude)
    delta_lat = end_lat - start_lat
    delta_lng = radians(end.longitude - start.longitude)
    value = (
        sin(delta_lat / 2) ** 2
        + cos(start_lat) * cos(end_lat) * sin(delta_lng / 2) ** 2
    )
    value = min(1.0, max(0.0, value))
    return earth_radius_m * 2 * atan2(sqrt(value), sqrt(1 - value))


def validate_normalized_walking_route(
    *,
    origin: RoutePoint,
    destination: RoutePoint,
    polyline: list[RoutePoint],
    distance_m: int,
    duration_s: int,
) -> None:
    if len(polyline) < 2:
        raise WalkingRouteSanityError(
            code="route_geometry_missing",
            message="Walking route has no usable geometry.",
        )

    start_gap_m = route_point_distance_m(origin, polyline[0])
    end_gap_m = route_point_distance_m(destination, polyline[-1])
    if start_gap_m > ROUTE_ENDPOINT_MAX_DISTANCE_M or end_gap_m > ROUTE_ENDPOINT_MAX_DISTANCE_M:
        raise WalkingRouteSanityError(
            code="route_endpoint_mismatch",
            message="Walking route geometry does not connect to the requested endpoints.",
        )

    if distance_m <= 0 or duration_s <= 0:
        raise WalkingRouteSanityError(
            code="route_summary_invalid",
            message="Walking route distance and duration must be positive.",
        )

    geometry_distance_m = sum(
        route_point_distance_m(start, end)
        for start, end in zip(polyline, polyline[1:])
    )
    if geometry_distance_m <= 0:
        raise WalkingRouteSanityError(
            code="route_geometry_missing",
            message="Walking route geometry has no measurable length.",
        )

    minimum_summary_m = (
        geometry_distance_m * ROUTE_SUMMARY_GEOMETRY_MIN_RATIO
        - ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M
    )
    maximum_summary_m = (
        geometry_distance_m * ROUTE_SUMMARY_GEOMETRY_MAX_RATIO
        + ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M
    )
    if distance_m < minimum_summary_m or distance_m > maximum_summary_m:
        raise WalkingRouteSanityError(
            code="route_geometry_summary_mismatch",
            message="Walking route summary distance is inconsistent with its geometry.",
        )
