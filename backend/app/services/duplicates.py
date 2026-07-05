from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from backend.app.models import Report
from backend.app.schemas import ReportMetadata, ReportV2Metadata


DUPLICATE_RADIUS_M = 10.0
DUPLICATE_WINDOW_MINUTES = 1


def duplicate_candidate_statement(
    class_name: str,
    captured_at: datetime,
    lat: float,
    lng: float,
    radius_m: float,
    minutes: int,
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
    )
    return list(db.scalars(statement).all())


def find_duplicate_candidates_v2(
    db: Session,
    metadata: ReportV2Metadata,
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
    )
    return list(db.scalars(statement).all())
