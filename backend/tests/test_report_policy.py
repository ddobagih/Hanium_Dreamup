from __future__ import annotations

from datetime import datetime, timezone
import pytest
from fastapi import HTTPException

from backend.app.api.reports import _report_export_row
from backend.app.models import Report
from backend.app.schemas import ReportV2Metadata
from backend.app.services.report_policy import ensure_report_v2_allowed, report_v2_source
from backend.app.services.report_serialization import report_to_response


def report_metadata(**overrides: object) -> ReportV2Metadata:
    payload = {
        "schema_version": "detect.v2",
        "model_key": "unified_walksafe",
        "source_model": "fake/unified-walksafe-contract",
        "model_class_id": 8,
        "class_name": "damaged_tactile_block",
        "category": "tactile_damage",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "threshold_used": 0.35,
        "captured_at": "2026-06-02T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
        "trigger": "auto",
        "auto_reported": True,
        "reporter_user_id": "user-123",
    }
    payload.update(overrides)
    return ReportV2Metadata.model_validate(payload)


def test_report_v2_allows_unified_damaged_tactile_block() -> None:
    ensure_report_v2_allowed(report_metadata())


def test_report_v2_maps_android_source() -> None:
    metadata = report_metadata(
        source="android",
        source_model="android/unified-walksafe",
    )

    ensure_report_v2_allowed(metadata)

    assert report_v2_source(metadata) == "android"
    assert metadata.reporter_user_id == "user-123"


def test_report_v2_rejects_unified_damaged_tactile_block_wrong_class_id() -> None:
    with pytest.raises(HTTPException) as exc_info:
        ensure_report_v2_allowed(report_metadata(model_class_id=1))

    assert exc_info.value.status_code == 422
    assert "model_class_id=8" in exc_info.value.detail


def test_export_row_includes_duplicate_candidate_ids_without_database() -> None:
    report = Report(
        status="new",
        class_id=8,
        class_name="damaged_tactile_block",
        confidence=0.91,
        bbox_x=0.2,
        bbox_y=0.35,
        bbox_width=0.4,
        bbox_height=0.22,
        captured_at=datetime(2026, 6, 2, 12, 0, tzinfo=timezone.utc),
        source="android",
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=9.5,
        heading=181.0,
        image_path="reports/example.jpg",
        image_content_type="image/jpeg",
        payload={
            "review_flags": ["duplicate_candidate"],
            "duplicate_report_ids": [
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
            ],
            "reporter_user_id": "user-123",
        },
        created_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
        updated_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
    )

    row = _report_export_row(report)

    assert "duplicate_candidate" in row["review_flags"]
    assert row["duplicate_report_ids"] == (
        "11111111-1111-1111-1111-111111111111,"
        "22222222-2222-2222-2222-222222222222"
    )
    assert row["reporter_user_id"] == "user-123"


def test_report_response_restores_duplicate_ids_from_metadata_without_database() -> None:
    report = Report(
        status="new",
        class_id=8,
        class_name="damaged_tactile_block",
        confidence=0.91,
        bbox_x=0.2,
        bbox_y=0.35,
        bbox_width=0.4,
        bbox_height=0.22,
        captured_at=datetime(2026, 6, 2, 12, 0, tzinfo=timezone.utc),
        source="android",
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=9.5,
        heading=181.0,
        image_path="reports/example.jpg",
        image_content_type="image/jpeg",
        payload={
            "review_flags": ["duplicate_candidate"],
            "duplicate_report_ids": ["11111111-1111-1111-1111-111111111111"],
        },
        created_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
        updated_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
    )

    response = report_to_response(report)

    assert response.duplicate_report_ids == ["11111111-1111-1111-1111-111111111111"]
    assert "duplicate_candidate" in response.review_flags
