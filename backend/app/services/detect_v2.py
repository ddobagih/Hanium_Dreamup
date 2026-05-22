from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Mapping

from backend.app.schemas import DetectContext, DetectV2Detection, DetectV2Response
from model.two_model_runtime import (
    DEFAULT_RUNTIME_CONFIG,
    COCO_GENERAL_ALLOWLIST,
    CUSTOM_TACTILE_CLASSES,
    filter_and_merge_detections,
)


def _threshold_for_detection(raw_detection: Mapping[str, Any]) -> float:
    model_config = DEFAULT_RUNTIME_CONFIG["models"][raw_detection["model_key"]]
    thresholds = model_config["thresholds"]
    return thresholds.get(raw_detection["class_name"], thresholds["default"])


def _model_class_id(raw_detection: Mapping[str, Any]) -> int:
    if raw_detection["model_key"] == "custom_tactile":
        return CUSTOM_TACTILE_CLASSES.index(raw_detection["class_name"])
    return COCO_GENERAL_ALLOWLIST.index(raw_detection["class_name"])


def _detect_v2_detection(raw_detection: Mapping[str, Any], context: DetectContext, captured_at: datetime) -> DetectV2Detection:
    bbox = raw_detection["bbox"]
    return DetectV2Detection(
        schema_version="detect.v2",
        model_key=raw_detection["model_key"],
        source_model=raw_detection["source_model"],
        model_class_id=_model_class_id(raw_detection),
        class_name=raw_detection["class_name"],
        category=raw_detection["category"],
        confidence=raw_detection["confidence"],
        bbox={"x": bbox[0], "y": bbox[1], "width": bbox[2], "height": bbox[3]},
        threshold_used=_threshold_for_detection(raw_detection),
        captured_at=captured_at,
        gps=context.gps,
        heading=context.heading,
    )


def fake_detect_v2_detections(context: DetectContext) -> DetectV2Response:
    captured_at = context.captured_at or datetime.now(timezone.utc)
    overlapping_bbox = (0.12, 0.18, 0.42, 0.36)
    raw_custom_detections = [
        {
            "model_key": "custom_tactile",
            "source_model": "fake/custom-tactile-contract",
            "class_name": "tactile_damage_area",
            "category": DEFAULT_RUNTIME_CONFIG["models"]["custom_tactile"]["category"],
            "confidence": 0.91,
            "bbox": overlapping_bbox,
        },
    ]
    raw_coco_detections = [
        {
            "model_key": "coco_general",
            "source_model": "fake/coco-general-contract",
            "class_name": "person",
            "category": DEFAULT_RUNTIME_CONFIG["models"]["coco_general"]["category"],
            "confidence": 0.84,
            "bbox": overlapping_bbox,
        },
        {
            "model_key": "coco_general",
            "source_model": "fake/coco-general-contract",
            "class_name": "dog",
            "category": DEFAULT_RUNTIME_CONFIG["models"]["coco_general"]["category"],
            "confidence": 0.99,
            "bbox": (0.55, 0.1, 0.22, 0.58),
        },
    ]

    filtered = filter_and_merge_detections(
        raw_custom_detections,
        raw_coco_detections,
        DEFAULT_RUNTIME_CONFIG,
    )
    detections = [
        _detect_v2_detection(
            {
                "model_key": detection.model_key,
                "source_model": detection.source_model,
                "class_name": detection.class_name,
                "category": detection.category,
                "confidence": detection.confidence,
                "bbox": detection.bbox,
            },
            context,
            captured_at,
        )
        for detection in filtered
    ]
    return DetectV2Response(schema_version="detect.v2", detections=detections)
