"""Normalize TMAP walking geometry and voice guides without transport or health state.

The provider's feature order, distance checks and unknown-code policy belong
here; HTTP admission and worker ownership remain in the adapter/runtime.
"""

from __future__ import annotations

from bisect import bisect_right
from math import cos, floor, isfinite, radians
import re
from typing import Any

from pydantic import ValidationError

from backend.app.schemas import (
    RoutePoint,
    WalkingRouteGuidePoint,
    WalkingRouteRequest,
    WalkingRouteResponse,
    WalkingRouteStep,
    WalkingRouteSummary,
    ROUTE_TURN_GUIDE_CORNER_TOLERANCE_M,
    ROUTE_TURN_GUIDE_PROGRESS_TOLERANCE_M,
    route_required_turn_boundaries,
    route_required_geometry_turns,
    route_turn_guide_speaks,
)
from backend.app.services.tmap_response_normalization import (
    TmapPedestrianRouteError,
    _ensure_tmap_deadline,
    _haversine_m,
    _json_has_oversized_string,
)
from backend.app.services.walking_route_sanity import (
    ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M,
    ROUTE_SUMMARY_GEOMETRY_MAX_RATIO,
    ROUTE_SUMMARY_GEOMETRY_MIN_RATIO,
    WalkingRouteSanityError,
    route_point_distance_m,
    validate_normalized_walking_route,
)


USELESS_GUIDE_DESCRIPTION_RE = re.compile(r"^\s*,?\s*\d+(?:\.\d+)?\s*m\s*$", re.IGNORECASE)

TMAP_MAX_FEATURES = 2048
TMAP_MAX_POLYLINE_POINTS = 8192
TMAP_MAX_STEPS = 1024
TMAP_MAX_GUIDE_POINTS = 1024
# At six-decimal coordinates, independent rounding of a shared endpoint is
# roughly 0.15 m or less. Allow 0.5 m for that small representation error,
# but at most 1 m over the whole route so many seams cannot hide missing legs.
TMAP_SEGMENT_SEAM_ROUNDING_MAX_M = 0.5
TMAP_SEGMENT_SEAM_ROUNDING_TOTAL_MAX_M = 1.0
# New waypoint verification policy, independent of endpoint and bearing gates.
TMAP_WAYPOINT_MAX_DISTANCE_M = 20.0
_TMAP_WAYPOINT_EARTH_RADIUS_M = 6371000.0
# TMAP pedestrian response code table. Facility 0 is absent from that table,
# so keep it unknown for pedestrian stair-avoidance decisions.
# https://tmap-skopenapi.readme.io/reference/경로안내-샘플예제
# A well-formed code outside this table retains its identity but cannot prove
# stair safety. Only Android-mapped directions prove spoken corner guidance.
_TMAP_KNOWN_TURN_TYPES = frozenset({
    *range(1, 8), 11, 12, 13, 14, 16, 17, 18, 19,
    125, 126, 127, 128, 129, *range(184, 190), 200, 201,
    *range(211, 219), 233,
})
_TMAP_KNOWN_FACILITY_TYPES = frozenset({1, 2, 3, 11, 12, 14, 15, 16, 17})
# Dimensionless cross-product conditioning floor; never added to the 20 m cap.
_TMAP_WAYPOINT_MIN_ARC_CROSS_NORM = 1e-12
_TMAP_WAYPOINT_MISMATCH_MESSAGE = (
    "TMAP pedestrian route geometry could not be verified against the requested waypoint order."
)


def _int_field(payload: dict[str, Any], name: str) -> int:
    if name not in payload:
        return 0
    value = payload[name]
    if type(value) is int and value >= 0:
        return value
    if type(value) is float and isfinite(value) and value >= 0:
        return int(value)
    if type(value) is str:
        try:
            parsed = int(value)
        except (ValueError, OverflowError):
            pass
        else:
            if parsed >= 0:
                return parsed
    raise TmapPedestrianRouteError(
        code="invalid_tmap_response",
        message=f"TMAP pedestrian route {name} is invalid.",
    )


def _tmap_code_field(payload: dict[str, Any], name: str) -> int | None:
    if name not in payload:
        return None
    value = payload[name]
    # TMAP's optional facility string is empty on actual arrival points.
    # Preserve missing metadata as unknown; never invent a safe facility code.
    if name == "facilityType" and value == "":
        return None
    if type(value) is int:
        parsed = value
    elif type(value) is float and isfinite(value) and value.is_integer():
        parsed = int(value)
    elif type(value) is str and re.fullmatch(r"[0-9]+", value.strip()):
        try:
            parsed = int(value)
        except ValueError:
            parsed = -1
    else:
        parsed = -1
    if parsed < 0 or parsed > 2_147_483_647:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message=f"TMAP pedestrian route {name} is invalid.",
        )
    return parsed


def _required_tmap_step_distance(properties: dict[str, Any]) -> int:
    if "distance" in properties:
        value = properties["distance"]
        value_type = type(value)
        if value_type is int:
            if value >= 0:
                return value
        elif value_type is float:
            if isfinite(value) and value >= 0:
                return int(value)
        elif value_type is str:
            try:
                distance = int(value)
            except (ValueError, OverflowError):
                pass
            else:
                if distance >= 0:
                    return distance
    raise TmapPedestrianRouteError(
        code="invalid_tmap_response",
        message="TMAP pedestrian route LineString distance is missing or invalid.",
        http_status=502,
    )


def _validate_tmap_step_geometry_distance(
    points: list[RoutePoint], distance_m: int, *, deadline_at: float | None,
) -> None:
    geometry_distance_m = 0.0
    for index, (start, end) in enumerate(zip(points, points[1:])):
        if index % 64 == 0:
            _ensure_tmap_deadline(deadline_at)
        geometry_distance_m += route_point_distance_m(start, end)
    # Apply the existing route bounds to its segments too. This preserves
    # provider distances and short/zero-edge rounding within those bounds.
    minimum_m = (
        geometry_distance_m * ROUTE_SUMMARY_GEOMETRY_MIN_RATIO
        - ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M
    )
    maximum_m = (
        geometry_distance_m * ROUTE_SUMMARY_GEOMETRY_MAX_RATIO
        + ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M
    )
    if distance_m < minimum_m or distance_m > maximum_m:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route LineString distance is missing or invalid.",
        )


def _str_field(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    return value if isinstance(value, str) and value else None


def _clean_guide_instruction(description: str | None) -> str | None:
    if description is None:
        return None
    instruction = description.strip()
    if not instruction or USELESS_GUIDE_DESCRIPTION_RE.fullmatch(instruction):
        return None
    return instruction


def _route_waypoint_mismatch() -> TmapPedestrianRouteError:
    return TmapPedestrianRouteError(
        code="route_waypoint_mismatch",
        message=_TMAP_WAYPOINT_MISMATCH_MESSAGE,
        http_status=502,
    )


def _waypoint_sin_cos_degrees(*terms: float) -> tuple[float, float]:
    import math

    # Use the rounded sum only to select a well-conditioned quadrant.
    # Include its shift in the original sum, rather than subtracting from
    # an already rounded angle near 90/180/360 degrees. No distance is rounded.
    quadrant = math.floor((math.fsum(terms) + 45.0) / 90.0)
    reduced = math.fsum((*terms, -90.0 * quadrant))
    angle = math.radians(reduced)
    sine, cosine = math.sin(angle), math.cos(angle)
    quadrant %= 4
    if quadrant == 0:
        return sine, cosine
    if quadrant == 1:
        return cosine, -sine
    if quadrant == 2:
        return -sine, -cosine
    return -cosine, sine


def _waypoint_local_coordinates(
    start: RoutePoint,
    point: RoutePoint,
) -> tuple[float, float, float]:
    import math

    start_sine, start_cosine = _waypoint_sin_cos_degrees(start.latitude)
    _, point_cosine = _waypoint_sin_cos_degrees(point.latitude)
    longitude_delta = math.fsum((point.longitude, -start.longitude))
    complementary = False
    east_sign = 1.0
    if longitude_delta > 270.0:
        longitude_terms = (point.longitude, -start.longitude, -360.0)
    elif longitude_delta < -270.0:
        longitude_terms = (point.longitude, -start.longitude, 360.0)
    elif abs(longitude_delta) <= 90.0:
        longitude_terms = (point.longitude, -start.longitude)
    elif longitude_delta > 0.0:
        complementary = True
        longitude_terms = (180.0, start.longitude, -point.longitude)
    else:
        complementary = True
        east_sign = -1.0
        longitude_terms = (180.0, -start.longitude, point.longitude)

    longitude_sine, _ = _waypoint_sin_cos_degrees(*longitude_terms)
    half_sine, _ = _waypoint_sin_cos_degrees(
        *(term * 0.5 for term in longitude_terms)
    )
    half_angle_term = 2.0 * half_sine * half_sine
    east = east_sign * point_cosine * longitude_sine
    if complementary:
        # The signed complement is formed from original longitude terms.
        # The latitude sum is reduced before radians too, including near poles.
        latitude_sine, latitude_cosine = _waypoint_sin_cos_degrees(
            start.latitude, point.latitude,
        )
        north = math.fsum((
            latitude_sine, -start_sine * point_cosine * half_angle_term,
        ))
        radial = math.fsum((
            -latitude_cosine, start_cosine * point_cosine * half_angle_term,
        ))
    else:
        latitude_sine, latitude_cosine = _waypoint_sin_cos_degrees(
            point.latitude, -start.latitude,
        )
        north = math.fsum((
            latitude_sine, start_sine * point_cosine * half_angle_term,
        ))
        radial = math.fsum((
            latitude_cosine, -start_cosine * point_cosine * half_angle_term,
        ))
    # These identities express the original point in the start's orthonormal
    # radial/east/north frame. Normalize only the resulting unit-coordinate
    # roundoff; do not project an inaccurate endpoint cross product.
    length = math.hypot(radial, east, north)
    return radial / length, east / length, north / length


def _waypoint_allowed_intervals(
    waypoint: RoutePoint,
    start: RoutePoint,
    tangent: tuple[float, float] | None,
    arc_angle: float,
) -> list[tuple[float, float]]:
    import math

    radius = TMAP_WAYPOINT_MAX_DISTANCE_M / _TMAP_WAYPOINT_EARTH_RADIUS_M
    radial, east, north = _waypoint_local_coordinates(start, waypoint)
    if tangent is None:
        distance = math.atan2(math.hypot(east, north), radial)
        return [(0.0, 0.0)] if distance <= radius else []

    # In this same local frame, t=(0,tE,tN) and q=(0,-tN,tE).
    # Both use the same two direction components, with no independently
    # normalized global normal or loss of orthogonality at a short edge.
    tangent_east, tangent_north = tangent
    cross_track_sine = abs(math.fsum((
        north * tangent_east, -east * tangent_north,
    )))
    radius_sine = math.sin(radius)
    if cross_track_sine > radius_sine:
        return []
    cross_track_cosine = math.sqrt(
        (1.0 - cross_track_sine) * (1.0 + cross_track_sine)
    )
    # Keep the complete cap interval and the unchanged inclusive 20 m radius.
    half_width = 2.0 * math.asin(math.sqrt(
        ((radius_sine - cross_track_sine) * (radius_sine + cross_track_sine))
        / (2.0 * cross_track_cosine * (cross_track_cosine + math.cos(radius)))
    ))
    centre = math.atan2(
        math.fsum((east * tangent_east, north * tangent_north)), radial,
    )
    intervals: list[tuple[float, float]] = []
    for turn in (-1, 0, 1):
        shifted_centre = centre + turn * math.tau
        lower = max(0.0, shifted_centre - half_width)
        upper = min(arc_angle, shifted_centre + half_width)
        if lower <= upper:
            intervals.append((lower, upper))
    return intervals


def _validate_route_waypoint_order(
    polyline: list[RoutePoint],
    waypoints: list[RoutePoint],
    *,
    deadline_at: float | None = None,
) -> None:
    import math

    if not waypoints:
        return
    _ensure_tmap_deadline(deadline_at)
    if not polyline:
        raise _route_waypoint_mismatch()

    # Keep segment index and angle instead of adding a large accumulated
    # distance. The end of one segment and start of the next are the same
    # progress; loop visits at later segment indices remain distinct.
    segments = []
    start_point = polyline[0]
    for end_point in polyline[1:]:
        _ensure_tmap_deadline(deadline_at)
        same_position = start_point.latitude == end_point.latitude and (
            abs(start_point.latitude) == 90.0
            or start_point.longitude == end_point.longitude
            or (start_point.longitude, end_point.longitude) in (
                (-180.0, 180.0), (180.0, -180.0),
            )
        )
        if same_position:
            segments.append((start_point, None, 0.0))
        else:
            radial, east, north = _waypoint_local_coordinates(start_point, end_point)
            cross_norm = math.hypot(east, north)
            if cross_norm <= _TMAP_WAYPOINT_MIN_ARC_CROSS_NORM:
                # Same conditioning boundary as before: h = |u cross v|.
                # Neither short nor long arcs are rejected by their length
                # alone when their oriented minor-arc direction is reliable.
                raise _route_waypoint_mismatch()
            tangent = (east / cross_norm, north / cross_norm)
            angle = math.atan2(cross_norm, radial)
            segments.append((start_point, tangent, angle))
        start_point = end_point
    if not segments:
        segments.append((start_point, None, 0.0))

    cursor_index = 0
    cursor_angle = 0.0
    for point in waypoints:
        _ensure_tmap_deadline(deadline_at)
        found = False
        for index in range(cursor_index, len(segments)):
            _ensure_tmap_deadline(deadline_at)
            minimum = cursor_angle if index == cursor_index else 0.0
            for lower, upper in _waypoint_allowed_intervals(point, *segments[index]):
                earliest = max(minimum, lower)
                if earliest <= upper:
                    cursor_index, cursor_angle = index, earliest
                    found = True
                    break
            if found:
                break
        if not found:
            raise _route_waypoint_mismatch()
    _ensure_tmap_deadline(deadline_at)


def _bearing_deg(start: RoutePoint, end: RoutePoint) -> float:
    from math import atan2, cos, degrees, radians, sin

    start_lat = radians(start.latitude)
    end_lat = radians(end.latitude)
    delta_lng = radians(end.longitude - start.longitude)
    x = sin(delta_lng) * cos(end_lat)
    y = cos(start_lat) * sin(end_lat) - sin(start_lat) * cos(end_lat) * cos(delta_lng)
    return (degrees(atan2(x, y)) + 360.0) % 360.0


def _route_bearing_for_point(
    point: RoutePoint,
    polyline: list[RoutePoint],
    *,
    deadline_at: float | None = None,
    require_bearing_consensus: bool = False,
) -> float | None:
    if len(polyline) < 2:
        return None
    matched_bearing: float | None = None
    matched_bearings_disagree = False
    for index, route_point in enumerate(polyline[:-1]):
        if index % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        if abs(route_point.latitude - point.latitude) <= 0.000001 and abs(route_point.longitude - point.longitude) <= 0.000001:
            bearing = _bearing_deg(route_point, polyline[index + 1])
            if not require_bearing_consensus:
                return bearing
            if matched_bearing is None:
                matched_bearing = bearing
            elif bearing != matched_bearing:
                matched_bearings_disagree = True
    if matched_bearing is not None:
        return None if matched_bearings_disagree else matched_bearing

    nearest_index = 0
    nearest_distance = _haversine_m(
        point.latitude,
        point.longitude,
        polyline[0].latitude,
        polyline[0].longitude,
    )
    nearest_bearing = _bearing_deg(polyline[0], polyline[1]) if require_bearing_consensus else None
    nearest_bearings_disagree = False
    for index, route_point in enumerate(polyline[1:-1], start=1):
        if index % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        distance = _haversine_m(
            point.latitude,
            point.longitude,
            route_point.latitude,
            route_point.longitude,
        )
        if distance < nearest_distance:
            nearest_index = index
            nearest_distance = distance
            if require_bearing_consensus:
                nearest_bearing = _bearing_deg(route_point, polyline[index + 1])
                nearest_bearings_disagree = False
        elif require_bearing_consensus and distance == nearest_distance:
            if _bearing_deg(route_point, polyline[index + 1]) != nearest_bearing:
                nearest_bearings_disagree = True
    if nearest_distance > 20:
        return None
    if require_bearing_consensus:
        return None if nearest_bearings_disagree else nearest_bearing
    return _bearing_deg(polyline[nearest_index], polyline[nearest_index + 1])


def _route_point_from_coordinate(coordinate: object) -> RoutePoint | None:
    if not isinstance(coordinate, list) or len(coordinate) < 2:
        return None
    longitude = coordinate[0]
    latitude = coordinate[1]
    if type(longitude) not in (int, float) or type(latitude) not in (int, float):
        return None
    try:
        return RoutePoint(latitude=float(latitude), longitude=float(longitude))
    except (ValidationError, OverflowError):
        return None


def _line_points(
    coordinates: object,
    *,
    deadline_at: float | None = None,
) -> list[RoutePoint]:
    # Only an empty coordinate list can be skipped without losing geometry.
    if not isinstance(coordinates, list):
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route LineString has invalid coordinates.",
        )
    if len(coordinates) > TMAP_MAX_POLYLINE_POINTS:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route polyline exceeds the safe point limit.",
        )

    points: list[RoutePoint] = []
    for index, coordinate in enumerate(coordinates):
        if index % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        point = _route_point_from_coordinate(coordinate)
        if point is None:
            raise TmapPedestrianRouteError(
                code="invalid_tmap_response",
                message="TMAP pedestrian route LineString has invalid coordinates.",
            )
        points.append(point)
    return points


def _append_unique_point(points: list[RoutePoint], point: RoutePoint) -> None:
    if points and points[-1].latitude == point.latitude and points[-1].longitude == point.longitude:
        return
    points.append(point)


def _tmap_guide_projections(
    point: RoutePoint, polyline: list[RoutePoint], geometry_prefixes: list[float],
    *, deadline_at: float | None,
) -> list[tuple[int, float, float, tuple[float, float]]]:
    candidates = []
    for index, (start, end) in enumerate(zip(polyline, polyline[1:])):
        if index % 64 == 0:
            _ensure_tmap_deadline(deadline_at)
        length_m = geometry_prefixes[index + 1] - geometry_prefixes[index]
        if length_m <= 0:
            continue
        # Use the Android route client's local projection and its existing
        # 30 m guide-to-route boundary; distances along the route remain spherical.
        longitude_scale = max(0.01, cos(radians((start.latitude + end.latitude + point.latitude) / 3)))
        dx = (end.longitude - start.longitude) * longitude_scale
        dy = end.latitude - start.latitude
        px = (point.longitude - start.longitude) * longitude_scale
        py = point.latitude - start.latitude
        fraction = max(0.0, min(1.0, (px * dx + py * dy) / (dx * dx + dy * dy)))
        if fraction == 0.0:
            projected, progress = start, geometry_prefixes[index]
        elif fraction == 1.0:
            projected, progress = end, geometry_prefixes[index + 1]
        else:
            projected = RoutePoint(
                latitude=start.latitude + (end.latitude - start.latitude) * fraction,
                longitude=start.longitude + (end.longitude - start.longitude) * fraction,
            )
            progress = geometry_prefixes[index] + length_m * fraction
        gap_m = route_point_distance_m(point, projected)
        if gap_m <= 30.0:
            candidates.append((index, progress, gap_m, (projected.latitude, projected.longitude)))
    return candidates


def _normalize_tmap_geometry_distances(
    summary_m: int, polyline: list[RoutePoint], steps: list[WalkingRouteStep],
    guides: list[WalkingRouteGuidePoint], step_ranges: list[tuple[int, int]],
    guide_slots: list[int], bound_visits: dict[int, tuple[int, int]],
    *, deadline_at: float | None,
) -> tuple[list[float], list[float]]:
    geometry_prefixes = [0.0]
    for index, (start, end) in enumerate(zip(polyline, polyline[1:])):
        if index % 64 == 0:
            _ensure_tmap_deadline(deadline_at)
        geometry_prefixes.append(geometry_prefixes[-1] + route_point_distance_m(start, end))
    geometry_total = geometry_prefixes[-1]

    def scaled(progress: float) -> int:
        return summary_m if progress == geometry_total else floor(summary_m * progress / geometry_total + 0.5)

    scaled_prefixes = [scaled(progress) for progress in geometry_prefixes]
    for step, (start, end) in zip(steps, step_ranges):
        step.distance_m = scaled_prefixes[end] - scaled_prefixes[start]

    slot_coordinates: dict[int, set[tuple[float, float]]] = {}
    for guide, slot in zip(guides, guide_slots):
        slot_coordinates.setdefault(slot, set()).add((guide.point.latitude, guide.point.longitude))
    projections: dict[tuple[float, float], list[tuple[int, float, float, tuple[float, float]]]] = {}
    guide_visits: list[list[float]] = []
    for guide, slot in zip(guides, guide_slots):
        _ensure_tmap_deadline(deadline_at)
        bound = bound_visits.get(guide.index)
        if bound is not None:
            guide_visits.append([geometry_prefixes[bound[0]]])
        else:
            key = (guide.point.latitude, guide.point.longitude)
            if key not in projections:
                projections[key] = _tmap_guide_projections(
                    guide.point, polyline, geometry_prefixes, deadline_at=deadline_at,
                )
            candidates = projections[key]
            if not candidates:
                raise _tmap_guide_position_error()
            nearest = min(candidate[2] for candidate in candidates)
            if slot == len(steps):
                terminal = [candidate for candidate in candidates if candidate[1] == geometry_total]
                if terminal and min(candidate[2] for candidate in terminal) <= nearest + 1.0:
                    guide_visits.append([geometry_total])
                    continue
            # A homogeneous provider guide block can disambiguate visits on
            # its following segment, including an offset or interior guide.
            if slot > 0 or len(slot_coordinates[slot]) == 1:
                source_step = min(slot, len(steps) - 1)
                start, end = step_ranges[source_step]
                while start == end and source_step + 1 < len(steps):
                    source_step += 1
                    start, end = step_ranges[source_step]
                preferred = [candidate for candidate in candidates if start <= candidate[0] < end]
                # A spoken turn Point immediately before the next LineString
                # can lie a few metres on the incoming edge. Its source slot
                # and proximity to that LineString's start prove the outgoing
                # visit even when the incoming projection has zero gap.
                near_next_turn_start = (
                    0 < slot < len(steps)
                    and route_turn_guide_speaks(guide)
                    and route_point_distance_m(guide.point, polyline[start])
                    <= ROUTE_TURN_GUIDE_CORNER_TOLERANCE_M
                )
                preference_gap_m = (
                    ROUTE_TURN_GUIDE_CORNER_TOLERANCE_M if near_next_turn_start else 1.0
                )
                if preferred and min(candidate[2] for candidate in preferred) <= nearest + preference_gap_m:
                    candidates = preferred
                    nearest = min(candidate[2] for candidate in candidates)
            tied = [candidate for candidate in candidates if candidate[2] <= nearest + 1.0]
            guide_visits.append(_tmap_projection_visits(tied, polyline, guide.point, deadline_at=deadline_at))
    # Both previous and later guide positions can prove a visit in a loop.
    # Do not commit greedily to the first occurrence of a repeated coordinate.
    earliest: list[float] = []
    cursor = 0.0
    for visits in guide_visits:
        _ensure_tmap_deadline(deadline_at)
        feasible = [progress for progress in visits if progress >= cursor]
        if not feasible:
            raise _tmap_guide_position_error()
        cursor = feasible[0]
        earliest.append(cursor)
    latest: list[float] = []
    cursor = geometry_total
    for visits in reversed(guide_visits):
        _ensure_tmap_deadline(deadline_at)
        feasible = [progress for progress in visits if progress <= cursor]
        if not feasible:
            raise _tmap_guide_position_error()
        cursor = feasible[-1]
        latest.append(cursor)
    resolved_progresses: list[float] = []
    for guide, visits, lower, upper in zip(guides, guide_visits, earliest, reversed(latest)):
        feasible = [progress for progress in visits if lower <= progress <= upper]
        if len(feasible) != 1:
            raise _tmap_guide_position_error()
        progress = feasible[0]
        resolved_progresses.append(progress)
        guide.distance_from_start_m = scaled(progress)
        guide.remaining_distance_m = summary_m - guide.distance_from_start_m
    return geometry_prefixes, resolved_progresses


def _tmap_projection_visits(
    candidates: list[tuple[int, float, float, tuple[float, float]]],
    polyline: list[RoutePoint],
    guide_point: RoutePoint,
    *, deadline_at: float | None,
) -> list[float]:
    from fractions import Fraction

    groups: list[list[tuple[int, float, float, tuple[float, float]]]] = []
    vertices: set[tuple[float, float]] = set()
    for candidate_number, candidate in enumerate(candidates):
        if candidate_number % 64 == 0:
            _ensure_tmap_deadline(deadline_at)
        index = candidate[0]
        start, end = polyline[index], polyline[index + 1]
        end_key = (end.latitude, end.longitude)
        join = False
        if (
            groups and groups[-1][-1][0] + 1 == index
            and end_key not in vertices
            and route_point_distance_m(guide_point, start) <= 30.0
        ):
            previous = polyline[index - 1]
            # Adjacency alone is not a corner visit: the shared vertex must
            # also be within the guide's existing 30 m route corridor.
            # Exact arithmetic over the supplied coordinates distinguishes a
            # normal corner from immediately retracing the same line. This is
            # a topology check, not a new distance or angle tolerance.
            ax = Fraction(start.longitude) - Fraction(previous.longitude)
            ay = Fraction(start.latitude) - Fraction(previous.latitude)
            bx = Fraction(end.longitude) - Fraction(start.longitude)
            by = Fraction(end.latitude) - Fraction(start.latitude)
            join = ax * by != ay * bx or ax * bx + ay * by >= 0
            previous_projection = groups[-1][-1][3]
            current_projection = candidate[3]
            previous_point = RoutePoint(latitude=previous_projection[0], longitude=previous_projection[1])
            current_point = RoutePoint(latitude=current_projection[0], longitude=current_projection[1])
            # A near-retrace need not be exactly collinear. Leaving a tied
            # projection's 1 m neighbourhood and returning to it is another
            # visit, even when the two adjacent edges differ by tiny angles.
            if (
                route_point_distance_m(previous_point, current_point) <= 1.0
                and route_point_distance_m(start, previous_point) > 1.0
                and route_point_distance_m(start, current_point) > 1.0
            ):
                join = False
        if join:
            groups[-1].append(candidate)
            vertices.add(end_key)
        else:
            groups.append([candidate])
            vertices = {(start.latitude, start.longitude), end_key}
    # Adjacent projections around one corner are one visit. Preserve distinct
    # retraces/loops, including sub-metre ties and reversed-edge roundoff.
    return sorted({min(group, key=lambda candidate: (candidate[2], candidate[1]))[1] for group in groups})


def _tmap_summary_conflict_error() -> TmapPedestrianRouteError:
    return TmapPedestrianRouteError(
        code="route_summary_conflict",
        message="TMAP pedestrian route summary totals conflict across features.",
    )


def _merge_tmap_summary_total(current: int, value: int) -> int:
    if not value:
        return current
    if current and value != current:
        raise _tmap_summary_conflict_error()
    return value


def _tmap_guide_position_error() -> TmapPedestrianRouteError:
    return TmapPedestrianRouteError(
        code="invalid_tmap_response",
        message="TMAP pedestrian route guide position is ambiguous or out of order.",
    )


def _normalize_tmap_response(
    payload: Any,
    request: WalkingRouteRequest,
    *,
    deadline_at: float | None = None,
    speed_kmh: float | None = None,
    _strings_checked: bool = False,
) -> WalkingRouteResponse:
    if not isinstance(payload, dict):
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not JSON object.")

    if payload.get("type") != "FeatureCollection":
        raise TmapPedestrianRouteError(code="invalid_tmap_response", message="TMAP pedestrian route response is not GeoJSON FeatureCollection.")

    features = payload.get("features")
    if not isinstance(features, list) or not features:
        raise TmapPedestrianRouteError(code="route_unavailable", message="TMAP pedestrian route response has no route features.", http_status=422)
    if len(features) > TMAP_MAX_FEATURES:
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route response exceeds the safe feature limit.",
        )
    if not _strings_checked and _json_has_oversized_string(payload, deadline_at=deadline_at):
        raise TmapPedestrianRouteError(
            code="invalid_tmap_response",
            message="TMAP pedestrian route response contains an oversized string.",
        )

    summary_distance_m = 0
    summary_duration_s = 0
    fallback_distance_m = 0
    fallback_duration_s = 0
    steps: list[WalkingRouteStep] = []
    guide_points: list[WalkingRouteGuidePoint] = []
    pending_guide_indices: list[int] = []
    guide_bearing_ranges: dict[int, tuple[int, int]] = {}
    guide_slots: list[int] = []
    step_ranges: list[tuple[int, int]] = []
    polyline: list[RoutePoint] = []
    step_index = 0
    guide_index = 0
    step_point_count = 0
    total_seam_correction_m = 0.0

    # TMAP interleaves Point guide features and LineString route segments.
    # Retain source slots so geometry normalization can distinguish loop visits.
    for feature_index, feature in enumerate(features):
        if feature_index % 64 == 0:
            _ensure_tmap_deadline(deadline_at)
        if not isinstance(feature, dict):
            pending_guide_indices.clear()
            continue
        geometry = feature.get("geometry") if isinstance(feature.get("geometry"), dict) else {}
        properties = feature.get("properties") if isinstance(feature.get("properties"), dict) else {}

        # TMAP documents totals on the start-point (SP) feature. The first
        # positive value is the reference; a different positive duplicate on any
        # other feature is a provider conflict, never a silent overwrite (S07-10).
        # Features explicitly typed as another point (e.g. passList PP legs) form
        # a separate fallback tier with the same rule, used only when no SP or
        # untyped feature supplies that total (R19-GB-07).
        total_distance = _int_field(properties, "totalDistance")
        total_time = _int_field(properties, "totalTime")
        point_type = _str_field(properties, "pointType")
        if point_type is None or point_type == "SP":
            summary_distance_m = _merge_tmap_summary_total(summary_distance_m, total_distance)
            summary_duration_s = _merge_tmap_summary_total(summary_duration_s, total_time)
        else:
            fallback_distance_m = _merge_tmap_summary_total(fallback_distance_m, total_distance)
            fallback_duration_s = _merge_tmap_summary_total(fallback_duration_s, total_time)

        if geometry.get("type") == "Point":
            point = _route_point_from_coordinate(geometry.get("coordinates"))
            if point is None:
                pending_guide_indices.clear()
                continue
            if len(guide_points) >= TMAP_MAX_GUIDE_POINTS:
                raise TmapPedestrianRouteError(
                    code="invalid_tmap_response",
                    message="TMAP pedestrian route response exceeds the safe guide limit.",
                )
            guide_points.append(
                WalkingRouteGuidePoint(
                    index=guide_index,
                    point=point,
                    instruction=_clean_guide_instruction(_str_field(properties, "description")),
                    turn_type=_tmap_code_field(properties, "turnType"),
                    point_type=_str_field(properties, "pointType"),
                    facility_type=_tmap_code_field(properties, "facilityType"),
                )
            )
            pending_guide_indices.append(guide_index)
            guide_slots.append(len(steps))
            guide_index += 1
            continue

        if geometry.get("type") != "LineString":
            pending_guide_indices.clear()
            continue

        points = _line_points(
            geometry.get("coordinates"),
            deadline_at=deadline_at,
        )
        if not points:
            pending_guide_indices.clear()
            continue
        step_point_count += len(points)
        if step_point_count > TMAP_MAX_POLYLINE_POINTS:
            raise TmapPedestrianRouteError(
                code="invalid_tmap_response",
                message="TMAP pedestrian route response exceeds the safe polyline limit.",
            )
        if polyline and (
            polyline[-1].latitude != points[0].latitude
            or polyline[-1].longitude != points[0].longitude
        ):
            seam_correction_m = route_point_distance_m(polyline[-1], points[0])
            if (
                seam_correction_m > TMAP_SEGMENT_SEAM_ROUNDING_MAX_M
                or total_seam_correction_m + seam_correction_m > TMAP_SEGMENT_SEAM_ROUNDING_TOTAL_MAX_M
            ):
                raise TmapPedestrianRouteError(
                    code="route_geometry_disconnected",
                    message="TMAP pedestrian route LineString segments are disconnected.",
                )
            total_seam_correction_m += seam_correction_m
            # Use the existing endpoint coordinates in both representations.
            # Appending the provider's near-duplicate would invent a connector.
            points[0] = polyline[-1].model_copy()
        bearing_start_index = max(len(polyline) - 1, 0)
        for point in points:
            _append_unique_point(polyline, point)
            if len(polyline) > TMAP_MAX_POLYLINE_POINTS:
                raise TmapPedestrianRouteError(
                    code="invalid_tmap_response",
                    message="TMAP pedestrian route response exceeds the safe polyline limit.",
                )

        # Only a contiguous guide block at this segment's start proves the visit
        # immediately. Other layouts are resolved later by ordered projection.
        # Same-start zero-edge segments preserve the block until an outgoing edge.
        if (
            pending_guide_indices
            and all(
                guide_points[index].point.latitude == points[0].latitude
                and guide_points[index].point.longitude == points[0].longitude
                for index in pending_guide_indices
            )
        ):
            if len(polyline) > bearing_start_index + 1:
                for index in pending_guide_indices:
                    guide_bearing_ranges[index] = (bearing_start_index, len(polyline))
                pending_guide_indices.clear()
        else:
            pending_guide_indices.clear()

        duration_s = _int_field(properties, "time")
        if len(steps) >= TMAP_MAX_STEPS:
            raise TmapPedestrianRouteError(
                code="invalid_tmap_response",
                message="TMAP pedestrian route response exceeds the safe step limit.",
            )
        distance_m = _required_tmap_step_distance(properties)
        steps.append(
            WalkingRouteStep(
                index=step_index,
                distance_m=distance_m,
                duration_s=duration_s,
                points=points,
                instruction=_clean_guide_instruction(_str_field(properties, "description")),
                road_name=_str_field(properties, "name"),
                turn_type=None,
                facility_type=_tmap_code_field(properties, "facilityType"),
                road_type=_tmap_code_field(properties, "roadType"),
            )
        )
        step_index += 1
        step_ranges.append((bearing_start_index, len(polyline) - 1))

    summary_distance_m = summary_distance_m or fallback_distance_m
    summary_duration_s = summary_duration_s or fallback_duration_s
    distance_source = "provider_total_geometry_scaled"
    if not summary_distance_m:
        summary_distance_m = sum(step.distance_m for step in steps)
        distance_source = "provider_step_sum_geometry_scaled"
    if not summary_duration_s:
        summary_duration_s = sum(step.duration_s for step in steps)
    try:
        validate_normalized_walking_route(
            origin=request.origin,
            destination=request.destination,
            polyline=polyline,
            distance_m=summary_distance_m,
            duration_s=summary_duration_s,
            speed_kmh=speed_kmh if speed_kmh is not None else request.default_speed,
        )
    except WalkingRouteSanityError as exc:
        raise TmapPedestrianRouteError(
            code=exc.code,
            message=f"TMAP {exc.message}",
        ) from exc
    for step in steps:
        _validate_tmap_step_geometry_distance(step.points, step.distance_m, deadline_at=deadline_at)
    if request.priority == "STAIR_AVOID" and (
        any(step.facility_type == 17 for step in steps)
        or any(
            guide.facility_type == 17 or guide.turn_type in {127, 129}
            for guide in guide_points
        )
    ):
        raise TmapPedestrianRouteError(
            code="route_stairs_present",
            message="TMAP returned stairs despite the stair-exclusion request.",
            http_status=422,
        )
    if request.priority == "STAIR_AVOID" and (
        any(step.facility_type is not None and step.facility_type not in _TMAP_KNOWN_FACILITY_TYPES
            for step in steps)
        or any(
            (guide.facility_type is not None and guide.facility_type not in _TMAP_KNOWN_FACILITY_TYPES)
            or (guide.turn_type is not None and guide.turn_type not in _TMAP_KNOWN_TURN_TYPES)
            for guide in guide_points
        )
    ):
        raise TmapPedestrianRouteError(
            code="route_safety_code_unknown",
            message="TMAP returned a route code whose stair safety is unknown.",
        )
    geometry_prefixes, guide_progresses = _normalize_tmap_geometry_distances(
        summary_distance_m, polyline, steps, guide_points, step_ranges,
        guide_slots, guide_bearing_ranges, deadline_at=deadline_at,
    )
    if not guide_points:
        raise TmapPedestrianRouteError(
            code="route_guidance_incomplete",
            message="TMAP pedestrian route lacks required guide points for voice guidance.",
        )
    geometry_total_m = sum(
        route_point_distance_m(start, end)
        for step in steps for start, end in zip(step.points, step.points[1:])
    )
    required_turns = [(fraction, corner) for _, fraction, corner in route_required_turn_boundaries(steps)]
    required_turns.extend(route_required_geometry_turns(polyline))
    for fraction, corner in required_turns:
        corner_progress_m = fraction * geometry_total_m
        if not any(
            guide.distance_from_start_m is not None
            and abs(
                guide.distance_from_start_m * geometry_total_m / summary_distance_m
                - corner_progress_m
            ) <= ROUTE_TURN_GUIDE_PROGRESS_TOLERANCE_M
            and route_point_distance_m(guide.point, corner) <= ROUTE_TURN_GUIDE_CORNER_TOLERANCE_M
            and route_turn_guide_speaks(guide)
            for guide in guide_points
        ):
            raise TmapPedestrianRouteError(
                code="route_guidance_incomplete",
                message="TMAP pedestrian route lacks required guide points for voice guidance.",
            )
    normalized_guides: list[WalkingRouteGuidePoint] = []
    edge_bearings: dict[int, float] = {}
    for guide_point, progress in zip(guide_points, guide_progresses):
        _ensure_tmap_deadline(deadline_at)
        # Use the visit proven by the ordered geometry projection, before its
        # provider-distance scaling can round distinct positions together.
        # bisect_right selects the outgoing edge at an exact vertex. The last
        # vertex has no outgoing bearing, including on a short final segment.
        edge_index = bisect_right(geometry_prefixes, progress) - 1
        if edge_index >= len(polyline) - 1:
            bearing = None
        else:
            if edge_index not in edge_bearings:
                edge_bearings[edge_index] = _bearing_deg(polyline[edge_index], polyline[edge_index + 1])
            bearing = edge_bearings[edge_index]
        normalized_guides.append(guide_point.model_copy(update={"bearing_deg": bearing}))
    _ensure_tmap_deadline(deadline_at)
    _validate_route_waypoint_order(
        polyline, request.waypoints, deadline_at=deadline_at,
    )

    try:
        return WalkingRouteResponse(
            schema_version="walksafe.walking_route.v1",
            provider="tmap_pedestrian",
            provider_route_id=None,
            distance_source=distance_source,
            priority=request.priority,
            summary=WalkingRouteSummary(distance_m=summary_distance_m, duration_s=summary_duration_s),
            polyline=polyline,
            steps=steps,
            guide_points=normalized_guides,
            # S20-27: how far the route's ends are from the requested points (already bounded by
            # validate_normalized_walking_route). Reaching polyline[-1] is the route end, not arrival.
            start_gap_m=round(route_point_distance_m(request.origin, polyline[0])),
            end_gap_m=round(route_point_distance_m(request.destination, polyline[-1])),
            provider_result_code=0,
            provider_result_message="OK",
        )
    except ValidationError as exc:
        if any("route_guidance_incomplete" in error["msg"] for error in exc.errors()):
            raise TmapPedestrianRouteError(
                code="route_guidance_incomplete",
                message="TMAP pedestrian route lacks required guide points for voice guidance.",
            ) from exc
        raise

