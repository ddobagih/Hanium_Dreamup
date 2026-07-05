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
    metadata = report.payload if isinstance(report.payload, dict) else {}
    metadata_flags = metadata.get("review_flags") if isinstance(metadata.get("review_flags"), list) else []
    fake_by_metadata = (
        metadata.get("fake_source") is True
        or metadata.get("data_origin") == "demo"
        or metadata.get("demo") is True
        or metadata.get("is_demo") is True
        or metadata.get("is_fake") is True
        or "fake_source" in metadata_flags
        or (
            isinstance(metadata.get("source_model"), str)
            and ("fake" in metadata["source_model"].lower() or "demo" in metadata["source_model"].lower())
        )
    )

    def append_flag(flag: str) -> None:
        if flag not in flags:
            flags.append(flag)

    if report.source == "fake" or fake_by_metadata:
        append_flag("fake_source")
    if report.confidence < LOW_CONFIDENCE_THRESHOLD:
        append_flag("low_confidence")
    if quality == "missing":
        append_flag("missing_location")
    elif quality == "low":
        append_flag("low_location_accuracy")
    if report.heading is None:
        append_flag("missing_heading")
    for flag in metadata_flags:
        if isinstance(flag, str):
            append_flag(flag)

    return flags


def metadata_duplicate_report_ids(report: Report) -> list[str]:
    metadata = report.payload if isinstance(report.payload, dict) else {}
    raw_ids = metadata.get("duplicate_report_ids")
    if not isinstance(raw_ids, list):
        return []
    return [str(value) for value in raw_ids if isinstance(value, str) and value]


def report_to_response(report: Report, duplicate_report_ids: Optional[Sequence[str]] = None) -> ReportResponse:
    gps = None
    if report.latitude is not None and report.longitude is not None:
        gps = {
            "latitude": report.latitude,
            "longitude": report.longitude,
            "accuracy_m": report.accuracy_m,
        }

    resolved_duplicate_report_ids = (
        [str(value) for value in duplicate_report_ids]
        if duplicate_report_ids is not None
        else metadata_duplicate_report_ids(report)
    )

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
        duplicate_report_ids=resolved_duplicate_report_ids,
        created_at=report.created_at,
        updated_at=report.updated_at,
    )
