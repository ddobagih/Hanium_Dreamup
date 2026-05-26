"""CPU-testable adapter for Ultralytics YOLO detection results.

This module intentionally keeps Ultralytics imports out of the pure conversion
path. Tests should pass fake result/box objects and must not load YOLO models.
"""

from __future__ import annotations

from typing import Any, Iterable, Literal, Mapping, Sequence

from model.two_model_runtime import (
    COCO_GENERAL_ALLOWLIST,
    CUSTOM_TACTILE_CLASSES,
    DEFAULT_RUNTIME_CONFIG,
)

ModelKey = Literal["custom_tactile", "coco_general"]
RawDetection = dict[str, Any]

CUSTOM_TACTILE_CLASS_MAP: dict[int, str] = {
    index: class_name for index, class_name in enumerate(CUSTOM_TACTILE_CLASSES)
}
DEFAULT_COCO_ALLOWLIST: tuple[str, ...] = tuple(COCO_GENERAL_ALLOWLIST)


def ultralytics_result_to_raw_detections(
    result: Any,
    *,
    model_key: ModelKey,
    source_model: str | None = None,
    class_name_by_id: Mapping[int, str] | None = None,
    allowlist: Sequence[str] | None = None,
) -> list[RawDetection]:
    """Convert one Ultralytics result object into v2 raw detection dictionaries.

    Returned ``bbox`` values are normalized ``(x, y, width, height)`` coordinates
    clipped to ``[0, 1]``. This matches ``model.two_model_runtime.Detection`` and
    the `/detect/v2` response contract. The original normalized xyxy box is kept
    in ``bbox_xyxy`` for debugging/handoff only. Malformed boxes, unknown classes,
    and empty boxes are skipped instead of raising so the caller can safely
    process partial results.
    """

    if model_key not in ("custom_tactile", "coco_general"):
        raise ValueError(f"unsupported model_key: {model_key}")

    boxes = getattr(result, "boxes", None)
    if boxes is None:
        return []

    class_names = _class_name_map(result, model_key, class_name_by_id)
    allowed_names = set(allowlist if allowlist is not None else DEFAULT_COCO_ALLOWLIST)
    model_config = DEFAULT_RUNTIME_CONFIG["models"][model_key]
    resolved_source_model = source_model or model_config["source_model"]
    category = model_config["category"]

    detections: list[RawDetection] = []
    for box in _iter_boxes(boxes):
        class_id = _box_class_id(box)
        confidence = _box_confidence(box)
        bbox = _box_xyxyn(box)

        if class_id is None or confidence is None or bbox is None:
            continue

        class_name = class_names.get(class_id)
        if class_name is None:
            continue
        if model_key == "coco_general" and class_name not in allowed_names:
            continue

        clipped_xyxy = _clip_bbox_xyxy(bbox)
        if clipped_xyxy is None:
            continue
        bbox_xywh = _xyxy_to_xywh(clipped_xyxy)

        detections.append(
            {
                "model_key": model_key,
                "source_model": resolved_source_model,
                "model_class_id": class_id,
                "class_name": class_name,
                "category": category,
                "confidence": _clip(confidence),
                "bbox": bbox_xywh,
                "bbox_xyxy": clipped_xyxy,
            }
        )

    return detections


def load_yolo_model(model_path: str | None = None, **kwargs: Any) -> Any:
    """Lazily import and construct an Ultralytics YOLO model.

    This helper is for future runtime wiring only. Adapter tests must not call it.
    """

    try:
        from ultralytics import YOLO  # type: ignore[import-not-found]
    except ImportError as exc:  # pragma: no cover - depends on runtime env
        raise RuntimeError("ultralytics is required to load YOLO models") from exc
    return YOLO(model_path, **kwargs)


def _class_name_map(
    result: Any,
    model_key: ModelKey,
    class_name_by_id: Mapping[int, str] | None,
) -> dict[int, str]:
    if class_name_by_id is not None:
        return {int(class_id): str(name) for class_id, name in class_name_by_id.items()}
    if model_key == "custom_tactile":
        return dict(CUSTOM_TACTILE_CLASS_MAP)

    names = getattr(result, "names", None)
    if isinstance(names, Mapping):
        return {int(class_id): str(name) for class_id, name in names.items()}
    if isinstance(names, Sequence) and not isinstance(names, (str, bytes)):
        return {index: str(name) for index, name in enumerate(names)}
    return {}


def _iter_boxes(boxes: Any) -> Iterable[Any]:
    if isinstance(boxes, Iterable):
        return boxes
    if callable(getattr(boxes, "__len__", None)) and callable(getattr(boxes, "__getitem__", None)):
        return (boxes[index] for index in range(len(boxes)))
    return ()


def _box_class_id(box: Any) -> int | None:
    value = _first_number(getattr(box, "cls", None))
    if value is None:
        return None
    return int(value)


def _box_confidence(box: Any) -> float | None:
    return _first_number(getattr(box, "conf", None))


def _box_xyxyn(box: Any) -> tuple[float, float, float, float] | None:
    values = _flatten_numbers(getattr(box, "xyxyn", None))
    if len(values) < 4:
        return None
    return (values[0], values[1], values[2], values[3])


def _clip_bbox_xyxy(bbox: tuple[float, float, float, float]) -> tuple[float, float, float, float] | None:
    x1, y1, x2, y2 = (_clip(value) for value in bbox)
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1, y1, x2, y2)


def _xyxy_to_xywh(bbox: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
    x1, y1, x2, y2 = bbox
    return (x1, y1, x2 - x1, y2 - y1)


def _clip(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def _first_number(value: Any) -> float | None:
    values = _flatten_numbers(value)
    return values[0] if values else None


def _flatten_numbers(value: Any) -> list[float]:
    plain = _to_plain(value)
    values: list[float] = []

    def visit(item: Any) -> None:
        item = _to_plain(item)
        if isinstance(item, bool) or item is None:
            return
        if isinstance(item, (int, float)):
            values.append(float(item))
            return
        if isinstance(item, Sequence) and not isinstance(item, (str, bytes)):
            for child in item:
                visit(child)

    visit(plain)
    return values


def _to_plain(value: Any) -> Any:
    for method_name in ("detach", "cpu"):
        method = getattr(value, method_name, None)
        if callable(method):
            value = method()
    for method_name in ("numpy", "tolist"):
        method = getattr(value, method_name, None)
        if callable(method):
            value = method()
    return value


__all__ = [
    "CUSTOM_TACTILE_CLASS_MAP",
    "DEFAULT_COCO_ALLOWLIST",
    "RawDetection",
    "load_yolo_model",
    "ultralytics_result_to_raw_detections",
]
