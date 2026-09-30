"""S15-14: report GPS is the capture-device position; only that meaning is accepted and kept.

Schema and allowlist checks only; no database is opened.
"""

from __future__ import annotations

from pathlib import Path
import sys

import pytest
from pydantic import ValidationError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from backend.app.api.reports import (  # noqa: E402
    REPORT_V2_METADATA_ALLOWLIST,
    _sanitized_v2_payload,
)
from backend.app.schemas import ReportV2Metadata  # noqa: E402


def _metadata(**extra: object) -> dict[str, object]:
    body: dict[str, object] = {
        "schema_version": "detect.v2",
        "model_key": "unified_walksafe",
        "source_model": "fake/unified",
        "model_class_id": 8,
        "class_name": "damaged_tactile_block",
        "category": "tactile_damage",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "threshold_used": 0.25,
        "captured_at": "2026-05-22T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "trigger": "auto",
        "auto_reported": True,
    }
    body.update(extra)
    return body


def test_capture_device_location_kind_is_validated_and_persisted() -> None:
    parsed = ReportV2Metadata.model_validate(_metadata(location_kind="CAPTURE_DEVICE"))
    payload = _sanitized_v2_payload(parsed)
    assert "location_kind" in REPORT_V2_METADATA_ALLOWLIST
    assert payload["location_kind"] == "CAPTURE_DEVICE"
    assert payload["gps"] == {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5}


def test_missing_location_kind_stays_accepted_for_existing_clients() -> None:
    payload = _sanitized_v2_payload(ReportV2Metadata.model_validate(_metadata()))
    assert "location_kind" not in payload
    assert payload["gps"]["accuracy_m"] == 9.5


@pytest.mark.parametrize("value", ["FACILITY", "facility_estimate", "capture_device", "", 1])
def test_other_location_meanings_are_rejected(value: object) -> None:
    with pytest.raises(ValidationError):
        ReportV2Metadata.model_validate(_metadata(location_kind=value))
