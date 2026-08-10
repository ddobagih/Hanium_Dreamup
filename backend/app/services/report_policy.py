from __future__ import annotations

from fastapi import HTTPException

from backend.app.schemas import ReportV2Metadata


LOW_CONFIDENCE_THRESHOLD = 0.7
HIGH_LOCATION_ACCURACY_M = 15.0
MEDIUM_LOCATION_ACCURACY_M = 50.0
REPORT_V2_ALLOWED_CLASSES = {"damaged_tactile_block"}
REPORT_V2_ALLOWED_MODEL_KEYS = {"custom_tactile", "unified_walksafe"}
REPORT_V2_CLASS_IDS = {
    "custom_tactile": {
        "damaged_tactile_block": 1,
    },
    "unified_walksafe": {
        "damaged_tactile_block": 8,
    },
}


def ensure_report_v2_allowed(metadata: ReportV2Metadata) -> None:
    if metadata.model_key not in REPORT_V2_ALLOWED_MODEL_KEYS or metadata.class_name not in REPORT_V2_ALLOWED_CLASSES:
        raise HTTPException(
            status_code=422,
            detail="reports/v2 only accepts damaged tactile block detections from tactile-capable models",
        )
    expected_class_id = REPORT_V2_CLASS_IDS.get(metadata.model_key, {}).get(metadata.class_name)
    if expected_class_id is not None and metadata.model_class_id != expected_class_id:
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
    if metadata.source == "android":
        return "android"
    return "fake" if metadata.source_model.startswith("fake") else "server"
