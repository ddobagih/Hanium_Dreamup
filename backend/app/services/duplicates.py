"""Find nearby report candidates by class, capture time, and PostGIS distance.

The query only returns bounded duplicate candidates for review metadata; it
does not merge reports or decide which record an administrator should submit.
Coordinates use PostGIS geography so the configured radius is measured in
meters rather than geometry degrees.
"""

from __future__ import annotations

import math
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy import not_, or_, select, text
from sqlalchemy.orm import Session

from backend.app.models import Report
from backend.app.schemas import ReportMetadata, ReportV2Metadata


DUPLICATE_RADIUS_M = 10.0
DUPLICATE_WINDOW_MINUTES = 1
AUTO_REPORT_COOLDOWN_RADIUS_M = 25.0
AUTO_REPORT_COOLDOWN_MINUTES = 10

# Spatial lock grid for duplicate lookups. The grid only chooses advisory lock
# keys; candidates are still decided by the PostGIS ST_DWithin query below.
# 2000 cells per degree (0.0005 deg, about 55 m north-south) divides 360 exactly,
# so longitude cells wrap cleanly across the antimeridian.
DUPLICATE_LOCK_CELLS_PER_DEGREE = 2000
_DUPLICATE_LOCK_LONGITUDE_CELLS = 360 * DUPLICATE_LOCK_CELLS_PER_DEGREE
# Lower bounds for WGS84 metres per degree (meridian arc at the equator is about
# 110.57 km; a parallel is at least 111.3 km * cos(latitude)). The reach margin
# also absorbs geodesic-versus-box and floating-point error.
_MIN_METRES_PER_LATITUDE_DEGREE = 110_000.0
_MIN_METRES_PER_EQUATORIAL_LONGITUDE_DEGREE = 111_000.0
_DUPLICATE_LOCK_REACH_FACTOR = 1.5
# Poleward of this latitude, longitude cells degenerate; use hemisphere keys.
DUPLICATE_LOCK_POLAR_LATITUDE = 85.0


def _lock_cell(value_degrees: float) -> int:
    return math.floor(value_degrees * DUPLICATE_LOCK_CELLS_PER_DEGREE)


def duplicate_candidate_lock_cells(
    latitude: float,
    longitude: float,
    *,
    radius_m: float = DUPLICATE_RADIUS_M,
) -> list[str]:
    """Return the sorted spatial lock cells covering a report's duplicate radius.

    Every report locks all grid cells touched by a conservative bounding box of
    its radius. A report within ``radius_m`` of another lies inside that box, so
    its own cell is in the other's set and the two lookups always share a key.
    Near the poles a hemisphere key is used instead of degenerate longitude
    cells; boxes within one box height of the polar band take that key too, so
    a neighbour just outside the band still meets a report inside it.
    """

    reach_m = radius_m * _DUPLICATE_LOCK_REACH_FACTOR
    latitude_reach = reach_m / _MIN_METRES_PER_LATITUDE_DEGREE
    south = max(-90.0, latitude - latitude_reach)
    north = min(90.0, latitude + latitude_reach)
    cells: set[str] = set()
    if north >= DUPLICATE_LOCK_POLAR_LATITUDE - latitude_reach:
        cells.add("polar-n")
    if south <= -(DUPLICATE_LOCK_POLAR_LATITUDE - latitude_reach):
        cells.add("polar-s")
    if north < DUPLICATE_LOCK_POLAR_LATITUDE and south > -DUPLICATE_LOCK_POLAR_LATITUDE:
        widest_latitude = max(abs(south), abs(north))
        longitude_reach = reach_m / (
            _MIN_METRES_PER_EQUATORIAL_LONGITUDE_DEGREE * math.cos(math.radians(widest_latitude))
        )
        for latitude_cell in range(_lock_cell(south + 90.0), _lock_cell(north + 90.0) + 1):
            for longitude_cell in range(
                _lock_cell(longitude - longitude_reach + 180.0),
                _lock_cell(longitude + longitude_reach + 180.0) + 1,
            ):
                cells.add(f"{latitude_cell}:{longitude_cell % _DUPLICATE_LOCK_LONGITUDE_CELLS}")
    return sorted(cells)


def lock_duplicate_candidate_window(
    db: Session,
    *,
    class_name: str,
    captured_at: datetime,
    is_fake: bool,
    latitude: float | None,
    longitude: float | None,
    minutes: int = DUPLICATE_WINDOW_MINUTES,
    radius_m: float = DUPLICATE_RADIUS_M,
) -> None:
    """Serialize overlapping duplicate lookups until the transaction commits.

    Keys combine class, fake flag, minute bucket and spatial cell, so distant
    reports no longer wait on each other. A report without a location neither
    searches for candidates nor can become one, so it takes no lock.
    """

    if latitude is None or longitude is None:
        return
    normalized = captured_at.astimezone(timezone.utc)
    minute = normalized.replace(second=0, microsecond=0)
    cells = duplicate_candidate_lock_cells(latitude, longitude, radius_m=radius_m)
    lock_keys = [
        f"report-duplicate\n{class_name}\n{int(is_fake)}\n"
        f"{(minute + timedelta(minutes=offset)).isoformat()}\n{cell}"
        for offset in range(-minutes, minutes + 1)
        for cell in cells
    ]
    # One global order for every writer keeps the multi-key acquisition deadlock-free.
    for lock_key in sorted(lock_keys):
        db.execute(
            text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
            {"lock_key": lock_key},
        )


def lock_auto_report_cooldown(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    class_name: str,
    is_fake: bool,
) -> None:
    """Serialize the authoritative actor/class cooldown decision until commit."""

    lock_key = (
        f"report-auto-cooldown\n{privacy_subject}\n"
        f"{account_generation}\n{class_name}\n{int(is_fake)}"
    )
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": lock_key},
    )


def find_active_auto_report_cooldown(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    class_name: str,
    is_fake: bool,
    lat: float,
    lng: float,
    now: datetime,
    radius_m: float = AUTO_REPORT_COOLDOWN_RADIUS_M,
    minutes: int = AUTO_REPORT_COOLDOWN_MINUTES,
) -> Report | None:
    starts_at = now.astimezone(timezone.utc) - timedelta(minutes=minutes)
    statement = (
        select(Report)
        .where(Report.class_name == class_name)
        .where(Report.location.is_not(None))
        .where(Report.created_at >= starts_at)
        .where(Report.privacy_subject_hmac == privacy_subject)
        .where(Report.account_generation == account_generation)
        .where(Report.payload.contains({"trigger": "auto"}))
        .where(_fake_report_condition() if is_fake else not_(_fake_report_condition()))
        .where(
            text(
                "ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(:cooldown_lng, :cooldown_lat), "
                "4326)::geography, :cooldown_radius_m)"
            )
        )
        .params(cooldown_lat=lat, cooldown_lng=lng, cooldown_radius_m=radius_m)
        .order_by(Report.created_at.desc())
        .limit(1)
    )
    return db.scalar(statement)


def duplicate_candidate_statement(
    class_name: str,
    captured_at: datetime,
    lat: float,
    lng: float,
    radius_m: float,
    minutes: int,
    is_fake: bool,
    exclude_id: Optional[uuid.UUID] = None,
):
    if captured_at.tzinfo is None:
        captured_at = captured_at.replace(tzinfo=timezone.utc)
    starts_at = captured_at - timedelta(minutes=minutes)
    ends_at = captured_at + timedelta(minutes=minutes)

    statement = (
        select(Report)
        .where(Report.class_name == class_name)
        .where(Report.location.is_not(None))
        .where(Report.captured_at >= starts_at)
        .where(Report.captured_at <= ends_at)
        .where(_fake_report_condition() if is_fake else not_(_fake_report_condition()))
        .where(
            text(
                "ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius_m)"
            )
        )
        .params(lat=lat, lng=lng, radius_m=radius_m)
        .order_by(Report.created_at.desc())
        .limit(20)
    )

    if exclude_id is not None:
        statement = statement.where(Report.id != exclude_id)

    return statement


def _fake_report_condition():
    return or_(
        Report.source == "fake",
        Report.payload.contains({"fake_source": True}),
        Report.payload.contains({"data_origin": "demo"}),
        Report.payload.contains({"review_flags": ["fake_source"]}),
    )


def find_duplicate_candidates(
    db: Session,
    metadata: ReportMetadata,
    radius_m: float = DUPLICATE_RADIUS_M,
    minutes: int = DUPLICATE_WINDOW_MINUTES,
) -> list[Report]:
    if metadata.gps is None:
        return []

    statement = duplicate_candidate_statement(
        class_name=metadata.class_name,
        captured_at=metadata.captured_at,
        lat=metadata.gps.latitude,
        lng=metadata.gps.longitude,
        radius_m=radius_m,
        minutes=minutes,
        is_fake=metadata.source == "fake",
    )
    return list(db.scalars(statement).all())


def find_duplicate_candidates_v2(
    db: Session,
    metadata: ReportV2Metadata,
    *,
    is_fake: bool,
    radius_m: float = DUPLICATE_RADIUS_M,
    minutes: int = DUPLICATE_WINDOW_MINUTES,
) -> list[Report]:
    if metadata.gps is None:
        return []

    statement = duplicate_candidate_statement(
        class_name=metadata.class_name,
        captured_at=metadata.captured_at,
        lat=metadata.gps.latitude,
        lng=metadata.gps.longitude,
        radius_m=radius_m,
        minutes=minutes,
        is_fake=is_fake,
    )
    return list(db.scalars(statement).all())
