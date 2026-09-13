from __future__ import annotations

from fastapi import HTTPException

from backend.app.schemas import ReportV2Metadata


LOW_CONFIDENCE_THRESHOLD = 0.7
HIGH_LOCATION_ACCURACY_M = 15.0
MEDIUM_LOCATION_ACCURACY_M = 50.0
REPORT_V2_ALLOWED_CLASSES = {"damaged_tactile_block"}
REPORT_V2_CLASS_IDS = {
    "custom_tactile": {
        "damaged_tactile_block": {1},
    },
    "unified_walksafe": {
        "damaged_tactile_block": {8},
    },
    # Both WalkMate damage subtypes use the shared report category, with their original ids.
    "walkmate_21cls": {
        "damaged_tactile_block": {1, 3},
    },
}
REPORT_V2_ALLOWED_MODEL_KEYS = set(REPORT_V2_CLASS_IDS)


def ensure_report_v2_allowed(metadata: ReportV2Metadata) -> None:
    if metadata.model_key not in REPORT_V2_ALLOWED_MODEL_KEYS or metadata.class_name not in REPORT_V2_ALLOWED_CLASSES:
        raise HTTPException(
            status_code=422,
            detail="reports/v2 only accepts damaged tactile block detections from tactile-capable models",
        )
    expected_class_ids = REPORT_V2_CLASS_IDS.get(metadata.model_key, {}).get(metadata.class_name, set())
    if metadata.model_class_id not in expected_class_ids:
        expected_ids_description = " or ".join(str(class_id) for class_id in sorted(expected_class_ids))
        raise HTTPException(
            status_code=422,
            detail=f"reports/v2 expected model_class_id={expected_ids_description} for {metadata.class_name}",
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
