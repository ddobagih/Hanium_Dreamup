"""Find nearby report candidates by class, capture time, and PostGIS distance.

The query only returns bounded duplicate candidates for review metadata; it
does not merge reports or decide which record an administrator should submit.
Coordinates use PostGIS geography so the configured radius is measured in
meters rather than geometry degrees.
"""

from __future__ import annotations

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


def lock_duplicate_candidate_window(
    db: Session,
    *,
    class_name: str,
    captured_at: datetime,
    is_fake: bool,
    minutes: int = DUPLICATE_WINDOW_MINUTES,
) -> None:
    """Serialize overlapping duplicate lookups until the transaction commits."""

    normalized = captured_at.astimezone(timezone.utc)
    minute = normalized.replace(second=0, microsecond=0)
    lock_keys = [
        f"report-duplicate\n{class_name}\n{int(is_fake)}\n{(minute + timedelta(minutes=offset)).isoformat()}"
        for offset in range(-minutes, minutes + 1)
    ]
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
) -> None:
    """Serialize the authoritative actor/class cooldown decision until commit."""

    lock_key = (
        f"report-auto-cooldown\n{privacy_subject}\n"
        f"{account_generation}\n{class_name}"
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
