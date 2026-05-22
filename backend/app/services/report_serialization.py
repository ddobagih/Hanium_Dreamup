from __future__ import annotations

from typing import Optional, Sequence

from backend.app.models import Report
from backend.app.schemas import LocationQuality, ReportResponse
from backend.app.services.report_policy import (
    HIGH_LOCATION_ACCURACY_M,
    LOW_CONFIDENCE_THRESHOLD,
    MEDIUM_LOCATION_ACCURACY_M,
)


def location_quality(report: Report) -> LocationQuality:
    if report.latitude is None or report.longitude is None:
        return "missing"
    if report.accuracy_m is None or report.accuracy_m > MEDIUM_LOCATION_ACCURACY_M:
        return "low"
    if report.accuracy_m > HIGH_LOCATION_ACCURACY_M:
        return "medium"
    return "high"


def review_flags(report: Report) -> list[str]:
    flags: list[str] = []
    quality = location_quality(report)

    if report.source == "fake":
        flags.append("fake_source")
    if report.confidence < LOW_CONFIDENCE_THRESHOLD:
        flags.append("low_confidence")
    if quality == "missing":
        flags.append("missing_location")
    elif quality == "low":
        flags.append("low_location_accuracy")
    if report.heading is None:
        flags.append("missing_heading")

    return flags


def report_to_response(report: Report, duplicate_report_ids: Optional[Sequence[str]] = None) -> ReportResponse:
    gps = None
    if report.latitude is not None and report.longitude is not None:
        gps = {
            "latitude": report.latitude,
            "longitude": report.longitude,
            "accuracy_m": report.accuracy_m,
        }

    return ReportResponse(
        id=str(report.id),
        status=report.status,
        class_id=report.class_id,
        class_name=report.class_name,
        confidence=report.confidence,
        bbox={
            "x": report.bbox_x,
            "y": report.bbox_y,
            "width": report.bbox_width,
            "height": report.bbox_height,
        },
        captured_at=report.captured_at,
        source=report.source,
        gps=gps,
        heading=report.heading,
        image_path=report.image_path,
        image_content_type=report.image_content_type,
        metadata=report.payload,
        location_quality=location_quality(report),
        review_flags=review_flags(report),
        duplicate_report_ids=list(duplicate_report_ids or []),
        created_at=report.created_at,
        updated_at=report.updated_at,
    )
