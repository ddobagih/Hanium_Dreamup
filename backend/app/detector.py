from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from threading import Lock
from typing import Any, Optional

from backend.app.config import get_settings
from backend.app.schemas import CLASS_NAMES, DetectContext, DetectHealthResponse, DetectResponse, ReportMetadata


MODEL_NOT_CONFIGURED_REASON = "model_not_configured"
MODEL_ARTIFACT_UNSUPPORTED_REASON = "model_artifact_unsupported"
MODEL_CLASS_ORDER_MISMATCH_REASON = "model_class_order_mismatch"
MODEL_DEPENDENCY_MISSING_REASON = "model_dependency_missing"
MODEL_LOAD_FAILED_REASON = "model_load_failed"
MODEL_TASK_UNSUPPORTED_REASON = "model_task_unsupported"

_ADAPTER_LOCK = Lock()
_ADAPTER_CACHE_KEY: Optional[tuple[str, int, int, tuple[str, ...]]] = None
_ADAPTER_LOAD_RESULT: Optional["AdapterLoadResult"] = None


class ModelUnavailable(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class AdapterLoadResult:
    adapter: Optional["YoloPtAdapter"] = None
    reason: Optional[str] = None


def _reset_adapter_cache() -> None:
    global _ADAPTER_CACHE_KEY, _ADAPTER_LOAD_RESULT
    with _ADAPTER_LOCK:
        _ADAPTER_CACHE_KEY = None
        _ADAPTER_LOAD_RESULT = None


def _load_yolo_class() -> Any:
    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise ModelUnavailable(MODEL_DEPENDENCY_MISSING_REASON) from exc
    return YOLO


def _load_pillow_image_module() -> Any:
    try:
        from PIL import Image
    except ImportError as exc:
        raise ModelUnavailable(MODEL_DEPENDENCY_MISSING_REASON) from exc
    return Image


def _health_response(reason: Optional[str], model_status: str = "unavailable") -> DetectHealthResponse:
    settings = get_settings()
    model_artifact_path = str(settings.model_artifact_path) if settings.model_artifact_path else None

    return DetectHealthResponse(
        model_status=model_status,
        model_version=settings.model_version,
        reason=reason,
        model_artifact_path=model_artifact_path,
        model_class_order=list(settings.model_class_order),
        model_confidence_threshold=settings.model_confidence_threshold,
        model_iou_threshold=settings.model_iou_threshold,
        model_image_size=settings.model_image_size,
    )


def detect_health() -> DetectHealthResponse:
    settings = get_settings()
    load_result = _adapter_load_result(settings)
    if load_result.adapter is not None:
        return _health_response(reason=None, model_status="ready")

    return _health_response(load_result.reason or MODEL_NOT_CONFIGURED_REASON)


async def run_detection(image_bytes: bytes, content_type: str, context: DetectContext) -> DetectResponse:
    settings = get_settings()
    load_result = _adapter_load_result(settings)
    if load_result.adapter is None:
        raise RuntimeError(load_result.reason or MODEL_NOT_CONFIGURED_REASON)

    return load_result.adapter.predict(image_bytes, settings, context)


def _adapter_load_result(settings: Any) -> AdapterLoadResult:
    model_path = settings.model_artifact_path
    if model_path is None or not model_path.exists() or not model_path.is_file():
        return AdapterLoadResult(reason=MODEL_NOT_CONFIGURED_REASON)

    if model_path.suffix.lower() != ".pt":
        return AdapterLoadResult(reason=MODEL_ARTIFACT_UNSUPPORTED_REASON)

    stat = model_path.stat()
    cache_key = (
        str(model_path),
        stat.st_mtime_ns,
        stat.st_size,
        tuple(settings.model_class_order),
    )

    global _ADAPTER_CACHE_KEY, _ADAPTER_LOAD_RESULT
    with _ADAPTER_LOCK:
        if _ADAPTER_CACHE_KEY == cache_key and _ADAPTER_LOAD_RESULT is not None:
            return _ADAPTER_LOAD_RESULT

        try:
            adapter = YoloPtAdapter.load(
                artifact_path=model_path,
                backend_class_order=tuple(settings.model_class_order),
            )
            load_result = AdapterLoadResult(adapter=adapter)
        except ModelUnavailable as exc:
            load_result = AdapterLoadResult(reason=exc.reason)
        except Exception:
            load_result = AdapterLoadResult(reason=MODEL_LOAD_FAILED_REASON)

        _ADAPTER_CACHE_KEY = cache_key
        _ADAPTER_LOAD_RESULT = load_result
        return load_result


class YoloPtAdapter:
    def __init__(
        self,
        model: Any,
        image_module: Any,
        class_id_map: dict[int, int],
    ) -> None:
        self._model = model
        self._image_module = image_module
        self._class_id_map = class_id_map

    @classmethod
    def load(cls, artifact_path: Path, backend_class_order: tuple[str, ...]) -> "YoloPtAdapter":
        yolo_class = _load_yolo_class()
        image_module = _load_pillow_image_module()

        try:
            model = yolo_class(str(artifact_path))
        except Exception as exc:
            raise ModelUnavailable(MODEL_LOAD_FAILED_REASON) from exc

        task = getattr(model, "task", None)
        if task is not None and task != "detect":
            raise ModelUnavailable(MODEL_TASK_UNSUPPORTED_REASON)

        model_class_order = _model_class_order(model)
        class_id_map = _compatible_class_id_map(model_class_order, backend_class_order, artifact_path)
        return cls(model=model, image_module=image_module, class_id_map=class_id_map)

    def predict(self, image_bytes: bytes, settings: Any, context: DetectContext) -> DetectResponse:
        try:
            image = self._image_module.open(BytesIO(image_bytes)).convert("RGB")
        except Exception as exc:
            raise RuntimeError("image_decode_failed") from exc

        results = self._model(
            image,
            conf=settings.model_confidence_threshold,
            iou=settings.model_iou_threshold,
            imgsz=settings.model_image_size,
            verbose=False,
        )
        detections = self._detections_from_results(results, image.width, image.height, settings, context)

        return DetectResponse(
            model_status="ready",
            model_version=_response_model_version(settings),
            detections=detections,
        )

    def _detections_from_results(
        self,
        results: Any,
        image_width: int,
        image_height: int,
        settings: Any,
        context: DetectContext,
    ) -> list[ReportMetadata]:
        captured_at = context.captured_at or datetime.now(timezone.utc)
        detections: list[ReportMetadata] = []

        for result in results or []:
            result_height, result_width = _result_image_shape(result, image_width, image_height)
            boxes = getattr(result, "boxes", None)
            if boxes is None:
                continue

            for box in boxes:
                detection = self._detection_from_box(
                    box=box,
                    image_width=result_width,
                    image_height=result_height,
                    confidence_threshold=settings.model_confidence_threshold,
                    captured_at=captured_at,
                    context=context,
                )
                if detection is not None:
                    detections.append(detection)

        return detections

    def _detection_from_box(
        self,
        box: Any,
        image_width: int,
        image_height: int,
        confidence_threshold: float,
        captured_at: datetime,
        context: DetectContext,
    ) -> Optional[ReportMetadata]:
        xyxy = _box_xyxy(box)
        confidence = _box_scalar(box, "conf")
        model_class_id_value = _box_scalar(box, "cls")
        if xyxy is None or confidence is None or model_class_id_value is None:
            return None

        confidence = _clamp(confidence, 0.0, 1.0)
        if confidence < confidence_threshold:
            return None

        model_class_id = int(model_class_id_value)
        backend_class_id = self._class_id_map.get(model_class_id)
        if backend_class_id is None:
            return None

        bbox = _normalized_bbox(xyxy, image_width, image_height)
        if bbox is None:
            return None

        return ReportMetadata(
            class_id=backend_class_id,
            class_name=CLASS_NAMES[backend_class_id],
            confidence=confidence,
            bbox=bbox,
            captured_at=captured_at,
            source="server",
            gps=context.gps,
            heading=context.heading,
        )


def _compatible_class_id_map(
    model_class_order: Optional[tuple[str, ...]],
    backend_class_order: tuple[str, ...],
    artifact_path: Path,
) -> dict[int, int]:
    if model_class_order == backend_class_order:
        return {class_id: class_id for class_id in range(len(backend_class_order))}

    if model_class_order is not None and len(model_class_order) == 1:
        if model_class_order[0] == backend_class_order[0]:
            return {0: 0}

        training_class_order = _training_data_class_order(artifact_path)
        if training_class_order == (backend_class_order[0],):
            return {0: 0}

    raise ModelUnavailable(MODEL_CLASS_ORDER_MISMATCH_REASON)


def _model_class_order(model: Any) -> Optional[tuple[str, ...]]:
    for source in (model, getattr(model, "model", None)):
        if source is None:
            continue
        class_order = _normalize_class_names(getattr(source, "names", None))
        if class_order:
            return class_order
    return None


def _normalize_class_names(raw_names: Any) -> Optional[tuple[str, ...]]:
    if isinstance(raw_names, dict):
        pairs: list[tuple[int, str]] = []
        for key, value in raw_names.items():
            try:
                class_id = int(key)
            except (TypeError, ValueError):
                return None
            pairs.append((class_id, str(value).strip()))

        pairs.sort(key=lambda item: item[0])
        if [class_id for class_id, _ in pairs] != list(range(len(pairs))):
            return None
        names = tuple(name for _, name in pairs)
        return names if all(names) else None

    if isinstance(raw_names, (list, tuple)):
        names = tuple(str(value).strip() for value in raw_names)
        return names if names and all(names) else None

    return None


def _training_data_class_order(artifact_path: Path) -> Optional[tuple[str, ...]]:
    for data_path in _candidate_data_yaml_paths(artifact_path):
        class_order = _read_data_yaml_class_order(data_path)
        if class_order:
            return class_order
    return None


def _candidate_data_yaml_paths(artifact_path: Path) -> list[Path]:
    run_dir = artifact_path.parent.parent if artifact_path.parent.name == "weights" else artifact_path.parent
    candidates = [run_dir / "data.yaml"]
    args_path = run_dir / "args.yaml"

    args = _read_yaml(args_path)
    raw_data_path = args.get("data") if isinstance(args, dict) else None
    if isinstance(raw_data_path, str) and raw_data_path.strip():
        data_path = Path(raw_data_path).expanduser()
        if not data_path.is_absolute():
            data_path = (run_dir / data_path).resolve()
        candidates.append(data_path)

    return candidates


def _read_data_yaml_class_order(data_path: Path) -> Optional[tuple[str, ...]]:
    data = _read_yaml(data_path)
    if not isinstance(data, dict):
        return None
    return _normalize_class_names(data.get("names"))


def _read_yaml(path: Path) -> Optional[Any]:
    if not path.exists() or not path.is_file():
        return None

    try:
        import yaml
    except ImportError:
        return None

    try:
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def _result_image_shape(result: Any, fallback_width: int, fallback_height: int) -> tuple[int, int]:
    orig_shape = getattr(result, "orig_shape", None)
    if isinstance(orig_shape, (list, tuple)) and len(orig_shape) >= 2:
        try:
            height = int(orig_shape[0])
            width = int(orig_shape[1])
            if width > 0 and height > 0:
                return height, width
        except (TypeError, ValueError):
            pass

    return fallback_height, fallback_width


def _box_xyxy(box: Any) -> Optional[tuple[float, float, float, float]]:
    xyxy = _plain_value(getattr(box, "xyxy", None))
    if isinstance(xyxy, (list, tuple)) and xyxy and isinstance(xyxy[0], (list, tuple)):
        xyxy = xyxy[0]
    if not isinstance(xyxy, (list, tuple)) or len(xyxy) != 4:
        return None

    try:
        return tuple(float(value) for value in xyxy)  # type: ignore[return-value]
    except (TypeError, ValueError):
        return None


def _box_scalar(box: Any, field_name: str) -> Optional[float]:
    value = _plain_value(getattr(box, field_name, None))
    while isinstance(value, (list, tuple)):
        if not value:
            return None
        value = value[0]

    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _plain_value(value: Any) -> Any:
    if value is None:
        return None
    if hasattr(value, "detach"):
        value = value.detach()
    if hasattr(value, "cpu"):
        value = value.cpu()
    if hasattr(value, "tolist"):
        return value.tolist()
    if hasattr(value, "item"):
        return value.item()
    return value


def _normalized_bbox(
    xyxy: tuple[float, float, float, float],
    image_width: int,
    image_height: int,
) -> Optional[dict[str, float]]:
    if image_width <= 0 or image_height <= 0:
        return None

    x1, y1, x2, y2 = xyxy
    left = _clamp(min(x1, x2), 0.0, float(image_width))
    right = _clamp(max(x1, x2), 0.0, float(image_width))
    top = _clamp(min(y1, y2), 0.0, float(image_height))
    bottom = _clamp(max(y1, y2), 0.0, float(image_height))

    width = (right - left) / image_width
    height = (bottom - top) / image_height
    if width <= 0 or height <= 0:
        return None

    return {
        "x": left / image_width,
        "y": top / image_height,
        "width": width,
        "height": height,
    }


def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def _response_model_version(settings: Any) -> str:
    if settings.model_version:
        return settings.model_version
    if settings.model_artifact_path:
        return settings.model_artifact_path.stem
    return "unknown"
