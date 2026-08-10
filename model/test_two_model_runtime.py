#!/usr/bin/env python3
"""Pure-Python tests for two_model_runtime."""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from two_model_runtime import (  # noqa: E402
    DEFAULT_RUNTIME_CONFIG,
    Detection,
    filter_detections,
    filter_and_merge_detections,
    load_threshold_config,
    to_response_dict,
    validate_threshold_config,
)


BBOX = (0.1, 0.2, 0.3, 0.4)


def make_detection(model_key: str, class_name: str, confidence: float) -> Detection:
    if model_key == "custom_tactile":
        source_model = "YOLO26s custom"
        category = "tactile"
    else:
        source_model = "YOLO26n COCO pretrained"
        category = "general_obstacle"
    return Detection(model_key, class_name, confidence, BBOX, source_model, category)


def test_filter_applies_thresholds_and_coco_allowlist() -> None:
    config = copy.deepcopy(DEFAULT_RUNTIME_CONFIG)
    config["models"]["custom_tactile"]["thresholds"]["default"] = 0.50
    config["models"]["custom_tactile"]["thresholds"]["damaged_tactile_block"] = 0.70
    config["models"]["coco_general"]["thresholds"]["default"] = 0.40
    config["models"]["coco_general"]["thresholds"]["person"] = 0.80

    custom_detections = [
        make_detection("custom_tactile", "normal_tactile_block", 0.50),
        make_detection("custom_tactile", "damaged_tactile_block", 0.69),
        make_detection("custom_tactile", "tactile_damage_area", 0.51),
    ]
    coco_detections = [
        make_detection("coco_general", "person", 0.79),
        make_detection("coco_general", "car", 0.40),
        make_detection("coco_general", "dog", 0.99),
    ]

    merged = filter_and_merge_detections(custom_detections, coco_detections, config)

    assert [d.class_name for d in merged] == [
        "normal_tactile_block",
        "tactile_damage_area",
        "car",
    ]
    assert [d.model_key for d in merged] == ["custom_tactile", "custom_tactile", "coco_general"]
    assert [d.category for d in merged] == ["tactile", "tactile", "general_obstacle"]


def test_filter_does_not_run_cross_model_nms() -> None:
    custom_detection = make_detection("custom_tactile", "damaged_tactile_block", 0.90)
    coco_detection = make_detection("coco_general", "person", 0.90)

    merged = filter_and_merge_detections([custom_detection], [coco_detection])

    assert merged == [custom_detection, coco_detection]


def test_unified_walksafe_filters_general_and_tactile_classes() -> None:
    config = copy.deepcopy(DEFAULT_RUNTIME_CONFIG)
    config["models"]["unified_walksafe"]["thresholds"]["normal_tactile_block"] = 0.55
    detections = [
        Detection("unified_walksafe", "car", 0.50, BBOX, "YOLO26n unified", "vehicle"),
        Detection("unified_walksafe", "normal_tactile_block", 0.54, BBOX, "YOLO26n unified", "tactile_normal"),
        Detection("unified_walksafe", "damaged_tactile_block", 0.80, BBOX, "YOLO26n unified", "tactile_damage"),
        Detection("unified_walksafe", "dog", 0.99, BBOX, "YOLO26n unified", "unknown"),
    ]

    filtered = filter_detections(detections, config)

    assert [d.class_name for d in filtered] == ["car", "damaged_tactile_block"]
    assert {d.model_key for d in filtered} == {"unified_walksafe"}


def test_mapping_input_and_response_dict_are_json_serializable() -> None:
    raw_detection = {
        "model_key": "coco_general",
        "class_name": "bus",
        "confidence": 0.88,
        "bbox": {"x": 0.2, "y": 0.3, "width": 0.4, "height": 0.5},
        "source_model": "YOLO26n COCO pretrained",
        "category": "general_obstacle",
        "distance_m": 1.2,
        "distance_source": "manual_fixture",
        "distance_confidence": 0.9,
    }

    response = to_response_dict([raw_detection], image_id="sample-001")

    assert response == {
        "image_id": "sample-001",
        "detection_count": 1,
        "detections": [
            {
                "model_key": "coco_general",
                "class_name": "bus",
                "confidence": 0.88,
                "bbox": {"x": 0.2, "y": 0.3, "width": 0.4, "height": 0.5},
                "source_model": "YOLO26n COCO pretrained",
                "category": "general_obstacle",
                "distance_m": 1.2,
                "distance_source": "manual_fixture",
                "distance_confidence": 0.9,
            }
        ],
    }
    json.dumps(response)


def test_load_threshold_config_accepts_json(tmp_path: Path) -> None:
    config_path = tmp_path / "runtime_config.json"
    config_path.write_text(json.dumps(DEFAULT_RUNTIME_CONFIG), encoding="utf-8")

    config = load_threshold_config(config_path)

    assert config["models"]["custom_tactile"]["classes"] == [
        "normal_tactile_block",
        "damaged_tactile_block",
        "tactile_damage_area",
    ]
    assert config["models"]["coco_general"]["allowlist"] == [
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "traffic light",
        "bench",
    ]


def test_repository_yaml_config_loads_when_pyyaml_is_available() -> None:
    try:
        import yaml  # noqa: F401
    except ImportError:
        return

    config_path = Path(__file__).resolve().parents[1] / "configs" / "walksafe_two_model_runtime_20260522.yaml"

    config = load_threshold_config(config_path)

    assert config["models"]["custom_tactile"]["source_model"] == "YOLO26s custom"
    assert config["models"]["coco_general"]["source_model"] == "YOLO26n COCO pretrained"


def test_validate_threshold_config_rejects_bad_threshold() -> None:
    config = copy.deepcopy(DEFAULT_RUNTIME_CONFIG)
    config["models"]["coco_general"]["thresholds"]["car"] = 1.01

    with pytest.raises(ValueError, match="between 0 and 1"):
        validate_threshold_config(config)


def test_detection_rejects_invalid_bbox() -> None:
    with pytest.raises(ValueError, match="width and height"):
        Detection("custom_tactile", "normal_tactile_block", 0.9, (0.1, 0.2, 0.0, 0.4), "YOLO26s custom", "tactile")


def test_detection_rejects_unrealistic_distance_metadata() -> None:
    with pytest.raises(ValueError, match="distance_m"):
        Detection("coco_general", "person", 0.9, BBOX, "YOLO26n COCO pretrained", "general_obstacle", distance_m=51)
    with pytest.raises(ValueError, match="distance_confidence"):
        Detection(
            "coco_general",
            "person",
            0.9,
            BBOX,
            "YOLO26n COCO pretrained",
            "general_obstacle",
            distance_m=1.0,
            distance_confidence=1.2,
        )
