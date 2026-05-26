from __future__ import annotations

from fastapi import HTTPException

from backend.app.schemas import ReportV2Metadata


LOW_CONFIDENCE_THRESHOLD = 0.7
HIGH_LOCATION_ACCURACY_M = 15.0
MEDIUM_LOCATION_ACCURACY_M = 50.0
REPORT_V2_ALLOWED_CLASSES = {"damaged_tactile_block"}
REPORT_V2_CLASS_IDS = {
    "damaged_tactile_block": 1,
}


def ensure_report_v2_allowed(metadata: ReportV2Metadata) -> None:
    if metadata.model_key != "custom_tactile" or metadata.class_name not in REPORT_V2_ALLOWED_CLASSES:
        raise HTTPException(
            status_code=422,
            detail="reports/v2 only accepts custom damaged tactile block detections",
        )
    expected_class_id = REPORT_V2_CLASS_IDS[metadata.class_name]
    if metadata.model_class_id != expected_class_id:
        raise HTTPException(
            status_code=422,
            detail=f"reports/v2 expected model_class_id={expected_class_id} for {metadata.class_name}",
        )
    if metadata.gps is None:
        raise HTTPException(
            status_code=422,
            detail="reports/v2 requires gps for tactile damage reports",
        )


def report_v2_source(metadata: ReportV2Metadata) -> str:
    return "fake" if metadata.source_model.startswith("fake") else "server"
