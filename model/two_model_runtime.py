#!/usr/bin/env python3
"""CPU-only helpers for filtering and merging detect.v2 detections.

This module intentionally does not import Ultralytics, PIL, or GPU-backed
runtime libraries. It only works with detections that were already produced
elsewhere.
"""

from __future__ import annotations

import copy
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, Mapping, Sequence


ModelKey = Literal["custom_tactile", "coco_general", "unified_walksafe"]
BBox = tuple[float, float, float, float]

CUSTOM_TACTILE_CLASSES = (
    "normal_tactile_block",
    "damaged_tactile_block",
    "tactile_damage_area",
)
COCO_GENERAL_ALLOWLIST = (
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "bus",
    "truck",
    "traffic light",
    "bench",
)
UNIFIED_GENERAL_CLASSES = (
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "bus",
    "truck",
    "traffic light",
)
UNIFIED_CUSTOM_CLASSES = (
    "normal_tactile_block",
    "damaged_tactile_block",
    "crosswalk",
    "curb_step",
    "uneven_sidewalk",
    "e_scooter_obstruction",
)
UNIFIED_TACTILE_CLASSES = UNIFIED_CUSTOM_CLASSES
UNIFIED_WALKSAFE_CLASSES = UNIFIED_GENERAL_CLASSES + UNIFIED_CUSTOM_CLASSES
UNIFIED_WALKSAFE_CLASS_CATEGORIES = {
    "person": "vulnerable_road_user",
    "bicycle": "vulnerable_road_user",
    "car": "vehicle",
    "motorcycle": "vehicle",
    "bus": "vehicle",
    "truck": "vehicle",
    "traffic light": "traffic_signal",
    "normal_tactile_block": "tactile_normal",
    "damaged_tactile_block": "tactile_damage",
    "crosswalk": "path_guidance",
    "curb_step": "surface_hazard",
    "uneven_sidewalk": "surface_hazard",
    "e_scooter_obstruction": "obstruction",
}
MODEL_KEYS = ("custom_tactile", "coco_general", "unified_walksafe")
PRIMARY_MODEL_KEYS = ("legacy_two_model", "unified_walksafe")

DEFAULT_RUNTIME_CONFIG: dict[str, Any] = {
    "version": 1,
    "primary_model": "unified_walksafe",
    "fallback_model": "legacy_two_model",
    "models": {
        "custom_tactile": {
            "source_model": "YOLO26s custom",
            "category": "tactile",
            "classes": list(CUSTOM_TACTILE_CLASSES),
            "thresholds": {
                "default": 0.25,
                "normal_tactile_block": 0.25,
                "damaged_tactile_block": 0.25,
                "tactile_damage_area": 0.25,
            },
        },
        "coco_general": {
            "source_model": "YOLO26n COCO pretrained",
            "category": "general_obstacle",
            "allowlist": list(COCO_GENERAL_ALLOWLIST),
            "thresholds": {
                "default": 0.25,
                "person": 0.25,
                "bicycle": 0.25,
                "car": 0.25,
                "motorcycle": 0.25,
                "bus": 0.25,
                "truck": 0.25,
                "traffic light": 0.25,
                "bench": 0.25,
            },
        },
        "unified_walksafe": {
            "source_model": "YOLO26n COCO+WalkSafe tactile unified",
            "category": "unified",
            "classes": list(UNIFIED_WALKSAFE_CLASSES),
            "class_categories": dict(UNIFIED_WALKSAFE_CLASS_CATEGORIES),
            "thresholds": {
                "default": 0.25,
                "person": 0.25,
                "bicycle": 0.25,
                "car": 0.25,
                "motorcycle": 0.25,
                "bus": 0.25,
                "truck": 0.25,
                "traffic light": 0.25,
                "normal_tactile_block": 0.25,
                "damaged_tactile_block": 0.25,
                "crosswalk": 0.25,
                "curb_step": 0.25,
                "uneven_sidewalk": 0.25,
                "e_scooter_obstruction": 0.25,
            },
        },
    },
}


@dataclass(frozen=True)
class Detection:
    """Simple normalized detection payload.

    bbox uses normalized ``(x, y, width, height)`` coordinates.
    """

    model_key: ModelKey
    class_name: str
    confidence: float
    bbox: BBox
    source_model: str
    category: str
    distance_m: float | None = None
    distance_source: str | None = None
    distance_confidence: float | None = None

    def __post_init__(self) -> None:
        if self.model_key not in MODEL_KEYS:
            raise ValueError(f"unknown model_key: {self.model_key}")
        if not self.class_name:
            raise ValueError("class_name must not be empty")
        if not self.source_model:
            raise ValueError("source_model must not be empty")
        if not self.category:
            raise ValueError("category must not be empty")

        confidence = _as_float(self.confidence, "confidence")
        if confidence < 0 or confidence > 1:
            raise ValueError("confidence must be between 0 and 1")
        object.__setattr__(self, "confidence", confidence)

        if len(self.bbox) != 4:
            raise ValueError("bbox must have 4 values: x, y, width, height")
        bbox = tuple(_as_float(value, "bbox") for value in self.bbox)
        if any(value < 0 or value > 1 for value in bbox):
            raise ValueError("bbox values must be normalized between 0 and 1")
        if bbox[2] <= 0 or bbox[3] <= 0:
            raise ValueError("bbox width and height must be greater than 0")
        object.__setattr__(self, "bbox", bbox)

        if self.distance_m is not None:
            distance_m = _as_float(self.distance_m, "distance_m")
            if distance_m < 0 or distance_m > 50:
                raise ValueError("distance_m must be between 0 and 50")
            object.__setattr__(self, "distance_m", distance_m)
        if self.distance_confidence is not None:
            distance_confidence = _as_float(self.distance_confidence, "distance_confidence")
            if distance_confidence < 0 or distance_confidence > 1:
                raise ValueError("distance_confidence must be between 0 and 1")
            object.__setattr__(self, "distance_confidence", distance_confidence)


def load_threshold_config(path: str | Path | None = None) -> dict[str, Any]:
    """Load and validate a threshold config.

    JSON is supported with the standard library. YAML files require PyYAML; if
    PyYAML is not installed, pass a JSON config or install PyYAML.
    """

    if path is None:
        return validate_threshold_config(DEFAULT_RUNTIME_CONFIG)

    config_path = Path(path)
    text = config_path.read_text(encoding="utf-8")
    suffix = config_path.suffix.lower()

    if suffix == ".json":
        data = json.loads(text)
    elif suffix in {".yaml", ".yml"}:
        try:
            import yaml  # type: ignore[import-not-found]
        except ImportError as exc:
            raise RuntimeError(
                "PyYAML is required to load YAML configs. "
                "Install PyYAML or provide a .json threshold config."
            ) from exc
        data = yaml.safe_load(text)
    else:
        raise ValueError("config file must use .json, .yaml, or .yml")

    return validate_threshold_config(data)


def validate_threshold_config(config: Mapping[str, Any]) -> dict[str, Any]:
    """Validate and return a normalized copy of the runtime config."""

    if not isinstance(config, Mapping):
        raise ValueError("config must be a mapping")

    models = config.get("models")
    if not isinstance(models, Mapping):
        raise ValueError("config.models must be a mapping")

    primary_model = str(config.get("primary_model", "unified_walksafe"))
    if primary_model not in PRIMARY_MODEL_KEYS:
        raise ValueError("config.primary_model must be legacy_two_model or unified_walksafe")
    raw_fallback_model = config.get("fallback_model", "legacy_two_model")
    fallback_model = None if raw_fallback_model in {None, ""} else str(raw_fallback_model)
    if fallback_model is not None and fallback_model != "legacy_two_model":
        raise ValueError("config.fallback_model must be absent/null or legacy_two_model")

    normalized: dict[str, Any] = {
        "version": config.get("version", 1),
        "primary_model": primary_model,
        "fallback_model": fallback_model,
        "models": {},
    }

    configured_model_keys = tuple(str(model_key) for model_key in models.keys())
    supported_configured_keys = tuple(model_key for model_key in configured_model_keys if model_key in MODEL_KEYS)
    if not supported_configured_keys:
        raise ValueError("config.models must include at least one supported detect.v2 model")
    unsupported_keys = sorted(set(configured_model_keys) - set(MODEL_KEYS))
    if unsupported_keys:
        raise ValueError("config.models contains unsupported model keys: " + ", ".join(unsupported_keys))

    for model_key in supported_configured_keys:
        model_config = models.get(model_key)
        if not isinstance(model_config, Mapping):
            raise ValueError(f"config.models.{model_key} must be a mapping")

        source_model = _require_string(model_config.get("source_model"), f"{model_key}.source_model")
        category = _require_string(model_config.get("category"), f"{model_key}.category")
        thresholds = _validate_thresholds(model_config.get("thresholds"), model_key)

        normalized_model: dict[str, Any] = {
            "source_model": source_model,
            "category": category,
            "thresholds": thresholds,
        }

        if model_key == "custom_tactile":
            classes = _require_string_list(model_config.get("classes"), f"{model_key}.classes")
            if tuple(classes) != CUSTOM_TACTILE_CLASSES:
                raise ValueError(
                    "custom_tactile.classes must be exactly: "
                    + ", ".join(CUSTOM_TACTILE_CLASSES)
                )
            normalized_model["classes"] = classes
        elif model_key == "coco_general":
            allowlist = _require_string_list(model_config.get("allowlist"), f"{model_key}.allowlist")
            if tuple(allowlist) != COCO_GENERAL_ALLOWLIST:
                raise ValueError(
                    "coco_general.allowlist must be exactly: "
                    + ", ".join(COCO_GENERAL_ALLOWLIST)
                )
            normalized_model["allowlist"] = allowlist
        else:
            classes = _require_string_list(model_config.get("classes"), f"{model_key}.classes")
            if tuple(classes) != UNIFIED_WALKSAFE_CLASSES:
                raise ValueError(
                    "unified_walksafe.classes must be exactly: "
                    + ", ".join(UNIFIED_WALKSAFE_CLASSES)
                )
            normalized_model["classes"] = classes
            class_categories = model_config.get("class_categories")
            if class_categories is not None:
                normalized_model["class_categories"] = _validate_class_categories(
                    class_categories,
                    classes,
                    model_key,
                )

        allowed_names = set(normalized_model.get("classes", normalized_model.get("allowlist", [])))
        unknown_thresholds = sorted(set(thresholds) - allowed_names - {"default"})
        if unknown_thresholds:
            raise ValueError(
                f"{model_key}.thresholds contains unknown classes: "
                + ", ".join(unknown_thresholds)
            )

        normalized["models"][model_key] = normalized_model

    return copy.deepcopy(normalized)


def filter_detections(
    detections: Sequence[Detection | Mapping[str, Any]],
    config: Mapping[str, Any] | None = None,
) -> list[Detection]:
    """Filter already-produced detections by their model config.

    This accepts legacy two-model detections and the single-model
    ``unified_walksafe`` detections. It is intentionally NMS-free.
    """

    runtime_config = validate_threshold_config(config if config is not None else DEFAULT_RUNTIME_CONFIG)
    filtered: list[Detection] = []
    for raw_detection in detections:
        detection = _to_detection(raw_detection)
        if _passes_filters(detection, runtime_config):
            filtered.append(detection)
    return filtered


def filter_and_merge_detections(
    custom_detections: Sequence[Detection | Mapping[str, Any]],
    coco_detections: Sequence[Detection | Mapping[str, Any]],
    config: Mapping[str, Any] | None = None,
) -> list[Detection]:
    """Filter by allowlist and thresholds, then concatenate both model outputs.

    Cross-model NMS is intentionally not applied. The custom tactile classes and
    COCO classes have different meanings, so suppressing by bbox overlap alone
    could delete a valid tactile finding or a valid general obstacle finding.
    """

    runtime_config = validate_threshold_config(config if config is not None else DEFAULT_RUNTIME_CONFIG)
    merged: list[Detection] = []

    for raw_detection in custom_detections:
        detection = _to_detection(raw_detection)
        if detection.model_key != "custom_tactile":
            raise ValueError("custom_detections must use model_key='custom_tactile'")
        if _passes_filters(detection, runtime_config):
            merged.append(detection)

    for raw_detection in coco_detections:
        detection = _to_detection(raw_detection)
        if detection.model_key != "coco_general":
            raise ValueError("coco_detections must use model_key='coco_general'")
        if _passes_filters(detection, runtime_config):
            merged.append(detection)

    return merged


def to_response_dict(
    detections: Sequence[Detection | Mapping[str, Any]],
    image_id: str | None = None,
) -> dict[str, Any]:
    """Return a JSON-serializable response example for filtered detections."""

    response_detections = [_detection_to_dict(_to_detection(detection)) for detection in detections]
    response: dict[str, Any] = {
        "detection_count": len(response_detections),
        "detections": response_detections,
    }
    if image_id is not None:
        response["image_id"] = image_id
    return response


def _passes_filters(detection: Detection, config: Mapping[str, Any]) -> bool:
    model_config = config.get("models", {}).get(
        detection.model_key,
        DEFAULT_RUNTIME_CONFIG["models"][detection.model_key],
    )

    if detection.model_key == "custom_tactile":
        if detection.class_name not in model_config["classes"]:
            return False
    elif detection.model_key == "coco_general" and detection.class_name not in model_config["allowlist"]:
        return False
    elif detection.model_key == "unified_walksafe" and detection.class_name not in model_config["classes"]:
        return False

    thresholds = model_config["thresholds"]
    threshold = thresholds.get(detection.class_name, thresholds["default"])
    return detection.confidence >= threshold


def _to_detection(raw_detection: Detection | Mapping[str, Any]) -> Detection:
    if isinstance(raw_detection, Detection):
        return raw_detection
    if not isinstance(raw_detection, Mapping):
        raise TypeError("detection must be a Detection or mapping")

    try:
        bbox = raw_detection["bbox"]
        if isinstance(bbox, Mapping):
            bbox_values = (
                bbox["x"],
                bbox["y"],
                bbox["width"],
                bbox["height"],
            )
        else:
            bbox_values = tuple(bbox)

        return Detection(
            model_key=raw_detection["model_key"],
            class_name=raw_detection["class_name"],
            confidence=raw_detection["confidence"],
            bbox=bbox_values,
            source_model=raw_detection["source_model"],
            category=raw_detection["category"],
            distance_m=raw_detection.get("distance_m"),
            distance_source=raw_detection.get("distance_source"),
            distance_confidence=raw_detection.get("distance_confidence"),
        )
    except KeyError as exc:
        raise ValueError(f"missing detection field: {exc.args[0]}") from exc
    except TypeError as exc:
        raise ValueError("bbox must be a 4-item sequence or bbox mapping") from exc


def _detection_to_dict(detection: Detection) -> dict[str, Any]:
    x, y, width, height = detection.bbox
    payload = {
        "model_key": detection.model_key,
        "class_name": detection.class_name,
        "confidence": detection.confidence,
        "bbox": {
            "x": x,
            "y": y,
            "width": width,
            "height": height,
        },
        "source_model": detection.source_model,
        "category": detection.category,
    }
    if detection.distance_m is not None:
        payload["distance_m"] = detection.distance_m
    if detection.distance_source is not None:
        payload["distance_source"] = detection.distance_source
    if detection.distance_confidence is not None:
        payload["distance_confidence"] = detection.distance_confidence
    return payload


def _validate_thresholds(raw_thresholds: Any, model_key: str) -> dict[str, float]:
    if not isinstance(raw_thresholds, Mapping):
        raise ValueError(f"{model_key}.thresholds must be a mapping")
    if "default" not in raw_thresholds:
        raise ValueError(f"{model_key}.thresholds.default is required")

    return {
        str(class_name): _validate_threshold(value, f"{model_key}.thresholds.{class_name}")
        for class_name, value in raw_thresholds.items()
    }


def _validate_threshold(value: Any, field_name: str) -> float:
    threshold = _as_float(value, field_name)
    if threshold < 0 or threshold > 1:
        raise ValueError(f"{field_name} must be between 0 and 1")
    return threshold


def _require_string(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field_name} must be a non-empty string")
    return value


def _require_string_list(value: Any, field_name: str) -> list[str]:
    if isinstance(value, str) or not isinstance(value, Sequence):
        raise ValueError(f"{field_name} must be a list of strings")
    items = list(value)
    if not items or any(not isinstance(item, str) or not item.strip() for item in items):
        raise ValueError(f"{field_name} must be a list of non-empty strings")
    return items


def _validate_class_categories(raw_categories: Any, classes: Sequence[str], model_key: str) -> dict[str, str]:
    if not isinstance(raw_categories, Mapping):
        raise ValueError(f"{model_key}.class_categories must be a mapping")
    categories = {
        str(class_name): _require_string(category, f"{model_key}.class_categories.{class_name}")
        for class_name, category in raw_categories.items()
    }
    unknown_categories = sorted(set(categories) - set(classes))
    if unknown_categories:
        raise ValueError(
            f"{model_key}.class_categories contains unknown classes: "
            + ", ".join(unknown_categories)
        )
    return categories


def _as_float(value: Any, field_name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field_name} must be a number")
    return float(value)


__all__ = [
    "BBox",
    "COCO_GENERAL_ALLOWLIST",
    "CUSTOM_TACTILE_CLASSES",
    "DEFAULT_RUNTIME_CONFIG",
    "Detection",
    "ModelKey",
    "PRIMARY_MODEL_KEYS",
    "UNIFIED_WALKSAFE_CLASSES",
    "UNIFIED_WALKSAFE_CLASS_CATEGORIES",
    "filter_detections",
    "filter_and_merge_detections",
    "load_threshold_config",
    "to_response_dict",
    "validate_threshold_config",
]
