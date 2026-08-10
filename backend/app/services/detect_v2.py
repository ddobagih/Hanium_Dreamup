"""WalkSafe v2 fake and YOLO detection providers.

The server prefers one unified 13-class checkpoint when configured. The
custom-tactile plus COCO pair remains a legacy fallback. Model libraries and
weights load on the first detection request or readiness warmup. The readiness
probe performs one blank-frame inference and verifies the checkpoint class map.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
from io import BytesIO
from pathlib import Path
from threading import Lock
from typing import Any, Mapping, Protocol, Sequence

from backend.app.schemas import DetectContext, DetectV2Detection, DetectV2Response
from backend.app.services.yolo_inference_adapter import load_yolo_model, ultralytics_result_to_raw_detections
from model.two_model_runtime import (
    DEFAULT_RUNTIME_CONFIG,
    COCO_GENERAL_ALLOWLIST,
    CUSTOM_TACTILE_CLASSES,
    Detection,
    UNIFIED_WALKSAFE_CLASSES,
    filter_detections,
    filter_and_merge_detections,
    load_threshold_config,
    validate_threshold_config,
)

DETECT_V2_MODE_FAKE = "fake"
DETECT_V2_REAL_MODES = {"yolo", "real"}
DETECT_V2_READY_STATUS = "ready"
DETECT_V2_UNAVAILABLE_STATUS = "unavailable"
DETECT_V2_UNSUPPORTED_MODE_REASON = "detect_v2_mode_unsupported"
DETECT_V2_MODEL_NOT_CONFIGURED_REASON = "detect_v2_model_not_configured"
DETECT_V2_RUNTIME_CONFIG_NOT_CONFIGURED_REASON = "detect_v2_runtime_config_not_configured"
DETECT_V2_RUNTIME_CONFIG_INVALID_REASON = "detect_v2_runtime_config_invalid"
DETECT_V2_ARTIFACT_BINDING_INVALID_REASON = "detect_v2_artifact_binding_invalid"
DETECT_V2_MODEL_CLASS_ORDER_MISMATCH_REASON = "detect_v2_model_class_order_mismatch"
CUSTOM_TACTILE_INFERENCE_CONF = 0.15
CUSTOM_TACTILE_INFERENCE_IMGSZ = 960
COCO_GENERAL_INFERENCE_CONF = 0.10
COCO_GENERAL_INFERENCE_IMGSZ = 640
UNIFIED_WALKSAFE_INFERENCE_CONF = 0.10
DEFAULT_UNIFIED_WALKSAFE_INFERENCE_IMGSZ = 768
WARMUP_PNG_BYTES = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDAT\x08\xd7c\xf8\xcf\xc0\x00"
    b"\x00\x03\x01\x01\x00\x18\xdd\x8d\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
)

_PROVIDER_CACHE_LOCK = Lock()
_PROVIDER_CACHE_STAT_KEY: tuple[object, ...] | None = None
_PROVIDER_CACHE_KEY: tuple[object, ...] | None = None
_PROVIDER_CACHE: "YoloDetectV2Provider | None" = None


class DetectV2Provider(Protocol):
    def detect(self, image_bytes: bytes, content_type: str, context: DetectContext) -> DetectV2Response:
        ...


class DetectV2Runtime(Protocol):
    @property
    def runtime_config(self) -> Mapping[str, Any]:
        ...

    def detect(self, image_bytes: bytes, content_type: str) -> Sequence[Detection | Mapping[str, Any]]:
        ...

    def warmup(self) -> None:
        ...


@dataclass(frozen=True)
class DetectV2ProviderState:
    provider: DetectV2Provider | None
    status: str
    reason: str | None = None


class FakeDetectV2Provider:
    def detect(self, image_bytes: bytes, content_type: str, context: DetectContext) -> DetectV2Response:
        return fake_detect_v2_detections(context)


class LazyYoloDetectV2Runtime:
    """Lazy YOLO runtime for legacy two-model or unified single-model detection.

    Ultralytics and model weights are loaded only when ``detect`` is called.
    Tests can avoid that path by injecting a fake runtime into
    ``YoloDetectV2Provider``.
    """

    def __init__(
        self,
        *,
        custom_tactile_model_path: Path | None = None,
        coco_model_path: Path | None = None,
        unified_model_path: Path | None = None,
        runtime_config_path: Path | None = None,
        unified_image_size: int = DEFAULT_UNIFIED_WALKSAFE_INFERENCE_IMGSZ,
    ) -> None:
        self.custom_tactile_model_path = custom_tactile_model_path
        self.coco_model_path = coco_model_path
        self.unified_model_path = unified_model_path
        self.runtime_config_path = runtime_config_path
        self.unified_image_size = unified_image_size
        self._custom_model: Any | None = None
        self._coco_model: Any | None = None
        self._unified_model: Any | None = None
        self._runtime_config: dict[str, Any] | None = None
        self._warmed = False

    @property
    def runtime_config(self) -> Mapping[str, Any]:
        if self._runtime_config is None:
            self._runtime_config = load_detect_v2_runtime_config(self.runtime_config_path)
        return self._runtime_config

    def detect(self, image_bytes: bytes, content_type: str) -> list[Detection]:
        image = _open_image(image_bytes)
        # A configured unified path is authoritative. Legacy models are not run
        # alongside it, so their class-id spaces never enter the unified result.
        if self.unified_model_path is not None:
            return self._detect_unified(image)

        if self.custom_tactile_model_path is None or self.coco_model_path is None:
            raise RuntimeError(DETECT_V2_MODEL_NOT_CONFIGURED_REASON)
        custom_results = self._custom_model_lazy()(
            image,
            conf=CUSTOM_TACTILE_INFERENCE_CONF,
            imgsz=CUSTOM_TACTILE_INFERENCE_IMGSZ,
            verbose=False,
        )
        coco_results = self._coco_model_lazy()(
            image,
            conf=COCO_GENERAL_INFERENCE_CONF,
            imgsz=COCO_GENERAL_INFERENCE_IMGSZ,
            verbose=False,
        )
        config = self.runtime_config
        custom_config = config.get("models", {}).get(
            "custom_tactile",
            DEFAULT_RUNTIME_CONFIG["models"]["custom_tactile"],
        )
        coco_config = config.get("models", {}).get(
            "coco_general",
            DEFAULT_RUNTIME_CONFIG["models"]["coco_general"],
        )

        custom_raw = _results_to_raw_detections(
            custom_results,
            model_key="custom_tactile",
            source_model=custom_config["source_model"],
        )
        coco_raw = _results_to_raw_detections(
            coco_results,
            model_key="coco_general",
            source_model=coco_config["source_model"],
            allowlist=coco_config.get("allowlist", COCO_GENERAL_ALLOWLIST),
        )
        return filter_and_merge_detections(custom_raw, coco_raw, config)

    def warmup(self) -> None:
        if self._warmed:
            return
        self.detect(WARMUP_PNG_BYTES, "image/png")
        self._warmed = True

    def _detect_unified(self, image: Any) -> list[Detection]:
        config = self.runtime_config
        unified_config = config.get("models", {}).get(
            "unified_walksafe",
            DEFAULT_RUNTIME_CONFIG["models"]["unified_walksafe"],
        )
        unified_results = self._unified_model_lazy()(
            image,
            conf=UNIFIED_WALKSAFE_INFERENCE_CONF,
            imgsz=self.unified_image_size,
            verbose=False,
        )
        _validate_unified_checkpoint_classes(
            unified_results,
            unified_config.get("classes", UNIFIED_WALKSAFE_CLASSES),
        )
        unified_raw = _results_to_raw_detections(
            unified_results,
            model_key="unified_walksafe",
            source_model=unified_config["source_model"],
            allowlist=unified_config.get("classes", UNIFIED_WALKSAFE_CLASSES),
        )
        return filter_detections(unified_raw, config)

    def _custom_model_lazy(self) -> Any:
        if self._custom_model is None:
            if self.custom_tactile_model_path is None:
                raise RuntimeError(DETECT_V2_MODEL_NOT_CONFIGURED_REASON)
            self._custom_model = load_yolo_model(
                str(self.custom_tactile_model_path),
                artifact_identity=_file_sha256(self.custom_tactile_model_path),
            )
        return self._custom_model

    def _coco_model_lazy(self) -> Any:
        if self._coco_model is None:
            if self.coco_model_path is None:
                raise RuntimeError(DETECT_V2_MODEL_NOT_CONFIGURED_REASON)
            self._coco_model = load_yolo_model(
                str(self.coco_model_path),
                artifact_identity=_file_sha256(self.coco_model_path),
            )
        return self._coco_model

    def _unified_model_lazy(self) -> Any:
        if self._unified_model is None:
            if self.unified_model_path is None:
                raise RuntimeError(DETECT_V2_MODEL_NOT_CONFIGURED_REASON)
            self._unified_model = load_yolo_model(
                str(self.unified_model_path),
                artifact_identity=_file_sha256(self.unified_model_path),
            )
        return self._unified_model


class YoloDetectV2Provider:
    def __init__(
        self,
        *,
        custom_tactile_model_path: Path | None = None,
        coco_model_path: Path | None = None,
        unified_model_path: Path | None = None,
        runtime_config_path: Path | None = None,
        unified_image_size: int = DEFAULT_UNIFIED_WALKSAFE_INFERENCE_IMGSZ,
        runtime: DetectV2Runtime | None = None,
    ) -> None:
        self.runtime = runtime or LazyYoloDetectV2Runtime(
            custom_tactile_model_path=custom_tactile_model_path,
            coco_model_path=coco_model_path,
            unified_model_path=unified_model_path,
            runtime_config_path=runtime_config_path,
            unified_image_size=unified_image_size,
        )

    def detect(self, image_bytes: bytes, content_type: str, context: DetectContext) -> DetectV2Response:
        captured_at = context.captured_at or datetime.now(timezone.utc)
        detections = [
            _detect_v2_detection(_detection_mapping(detection), context, captured_at, self.runtime.runtime_config)
            for detection in self.runtime.detect(image_bytes=image_bytes, content_type=content_type)
        ]
        return DetectV2Response(schema_version="detect.v2", detections=detections)

    def warmup(self) -> None:
        warmup = getattr(self.runtime, "warmup", None)
        if callable(warmup):
            warmup()
            return
        self.runtime.detect(image_bytes=WARMUP_PNG_BYTES, content_type="image/png")


def _path_exists(path: Path | None) -> bool:
    return path is not None and path.exists() and path.is_file() and not path.is_symlink()


def _file_stat_key(path: Path) -> tuple[str, int, int, int, int, int]:
    stat = path.stat()
    return (
        str(path.resolve()),
        stat.st_dev,
        stat.st_ino,
        stat.st_size,
        stat.st_mtime_ns,
        stat.st_ctime_ns,
    )


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_unified_checkpoint_classes(results: Sequence[Any], expected_classes: Sequence[str]) -> None:
    if not results:
        raise RuntimeError(DETECT_V2_MODEL_CLASS_ORDER_MISMATCH_REASON)
    names = getattr(results[0], "names", None)
    if isinstance(names, Mapping):
        try:
            numeric_names = {int(class_id): str(name) for class_id, name in names.items()}
        except (TypeError, ValueError) as exc:
            raise RuntimeError(DETECT_V2_MODEL_CLASS_ORDER_MISMATCH_REASON) from exc
        if set(numeric_names) != set(range(len(numeric_names))):
            raise RuntimeError(DETECT_V2_MODEL_CLASS_ORDER_MISMATCH_REASON)
        actual_classes = tuple(numeric_names[index] for index in range(len(numeric_names)))
    elif isinstance(names, Sequence) and not isinstance(names, (str, bytes)):
        actual_classes = tuple(str(name) for name in names)
    else:
        raise RuntimeError(DETECT_V2_MODEL_CLASS_ORDER_MISMATCH_REASON)
    if actual_classes != tuple(str(name) for name in expected_classes):
        raise RuntimeError(DETECT_V2_MODEL_CLASS_ORDER_MISMATCH_REASON)


def _file_identity(path: Path) -> tuple[object, ...]:
    return (*_file_stat_key(path), _file_sha256(path))


def _configured_paths(settings: Any) -> tuple[Path | None, Path | None, Path | None, Path | None]:
    return (
        getattr(settings, "detect_v2_custom_tactile_model_path", None),
        getattr(settings, "detect_v2_coco_model_path", None),
        getattr(settings, "detect_v2_unified_model_path", None),
        getattr(settings, "detect_v2_runtime_config_path", None),
    )


def _missing_model_reason(settings: Any) -> str | None:
    unified_model_path = getattr(settings, "detect_v2_unified_model_path", None)
    if _path_exists(unified_model_path):
        return None
    if unified_model_path is not None:
        return f"{DETECT_V2_MODEL_NOT_CONFIGURED_REASON}:unified_walksafe"

    missing = []
    if not _path_exists(getattr(settings, "detect_v2_custom_tactile_model_path", None)):
        missing.append("custom_tactile")
    if not _path_exists(getattr(settings, "detect_v2_coco_model_path", None)):
        missing.append("coco_general")
    if not missing:
        return None
    return f"{DETECT_V2_MODEL_NOT_CONFIGURED_REASON}:{','.join(missing)}"


def _runtime_config_reason(settings: Any) -> str | None:
    path = getattr(settings, "detect_v2_runtime_config_path", None)
    if not _path_exists(path):
        return DETECT_V2_RUNTIME_CONFIG_NOT_CONFIGURED_REASON
    try:
        load_detect_v2_runtime_config(path)
    except Exception:
        return DETECT_V2_RUNTIME_CONFIG_INVALID_REASON
    return None


def _cached_yolo_provider(settings: Any) -> "YoloDetectV2Provider":
    paths = _configured_paths(settings)
    existing_paths = tuple(path for path in paths if path is not None)
    stat_key = (
        *(item for path in existing_paths for item in _file_stat_key(path)),
        getattr(settings, "detect_v2_image_size", DEFAULT_UNIFIED_WALKSAFE_INFERENCE_IMGSZ),
    )

    global _PROVIDER_CACHE_STAT_KEY, _PROVIDER_CACHE_KEY, _PROVIDER_CACHE
    with _PROVIDER_CACHE_LOCK:
        if _PROVIDER_CACHE_STAT_KEY == stat_key and _PROVIDER_CACHE is not None:
            return _PROVIDER_CACHE

    if getattr(settings, "walksafe_environment", "development") in {"field", "staging", "production"}:
        from backend.app.config import _validate_runtime_artifact_bindings

        unified_path = getattr(settings, "detect_v2_unified_model_path", None)
        legacy_ready = _path_exists(getattr(settings, "detect_v2_custom_tactile_model_path", None)) and _path_exists(
            getattr(settings, "detect_v2_coco_model_path", None)
        )
        _validate_runtime_artifact_bindings(
            getattr(settings, "detect_v2_runtime_config_path"),
            unified_model_path=unified_path if _path_exists(unified_path) else None,
            custom_model_path=getattr(settings, "detect_v2_custom_tactile_model_path", None) if legacy_ready else None,
            coco_model_path=getattr(settings, "detect_v2_coco_model_path", None) if legacy_ready else None,
        )

    # Hashing happens only when file metadata changes. The digest is part of
    # the cache identity, while the cheap stat key keeps normal inference from
    # re-reading large checkpoints on every frame.
    cache_key = (
        *(_file_identity(path) for path in existing_paths),
        getattr(settings, "detect_v2_image_size", DEFAULT_UNIFIED_WALKSAFE_INFERENCE_IMGSZ),
    )
    provider = YoloDetectV2Provider(
        custom_tactile_model_path=getattr(settings, "detect_v2_custom_tactile_model_path", None),
        coco_model_path=getattr(settings, "detect_v2_coco_model_path", None),
        unified_model_path=getattr(settings, "detect_v2_unified_model_path", None),
        runtime_config_path=getattr(settings, "detect_v2_runtime_config_path", None),
        unified_image_size=getattr(
            settings,
            "detect_v2_image_size",
            DEFAULT_UNIFIED_WALKSAFE_INFERENCE_IMGSZ,
        ),
    )
    with _PROVIDER_CACHE_LOCK:
        _PROVIDER_CACHE_STAT_KEY = stat_key
        _PROVIDER_CACHE_KEY = cache_key
        _PROVIDER_CACHE = provider
        return provider


def _reset_detect_v2_provider_cache() -> None:
    global _PROVIDER_CACHE_STAT_KEY, _PROVIDER_CACHE_KEY, _PROVIDER_CACHE
    with _PROVIDER_CACHE_LOCK:
        _PROVIDER_CACHE_STAT_KEY = None
        _PROVIDER_CACHE_KEY = None
        _PROVIDER_CACHE = None


def get_detect_v2_provider_state(settings: Any) -> DetectV2ProviderState:
    mode = getattr(settings, "detect_v2_mode", DETECT_V2_MODE_FAKE)
    # Fake mode is an explicit API/demo contract. Deployment must opt into a
    # real mode; merely placing model files in the repository changes nothing.
    if mode == DETECT_V2_MODE_FAKE:
        return DetectV2ProviderState(provider=FakeDetectV2Provider(), status=DETECT_V2_READY_STATUS)

    if mode in DETECT_V2_REAL_MODES:
        missing_reason = _missing_model_reason(settings)
        if missing_reason is not None:
            return DetectV2ProviderState(
                provider=None,
                status=DETECT_V2_UNAVAILABLE_STATUS,
                reason=missing_reason,
            )
        runtime_config_reason = _runtime_config_reason(settings)
        if runtime_config_reason is not None:
            return DetectV2ProviderState(
                provider=None,
                status=DETECT_V2_UNAVAILABLE_STATUS,
                reason=runtime_config_reason,
            )
        try:
            provider = _cached_yolo_provider(settings)
        except Exception:
            return DetectV2ProviderState(
                provider=None,
                status=DETECT_V2_UNAVAILABLE_STATUS,
                reason=DETECT_V2_ARTIFACT_BINDING_INVALID_REASON,
            )
        return DetectV2ProviderState(provider=provider, status=DETECT_V2_READY_STATUS)

    return DetectV2ProviderState(
        provider=None,
        status=DETECT_V2_UNAVAILABLE_STATUS,
        reason=f"{DETECT_V2_UNSUPPORTED_MODE_REASON}:{mode}",
    )


def detect_v2_health(settings: Any) -> dict[str, Any]:
    provider_state = get_detect_v2_provider_state(settings)
    runtime_config_path = getattr(settings, "detect_v2_runtime_config_path", None)
    if getattr(settings, "detect_v2_mode", DETECT_V2_MODE_FAKE) == DETECT_V2_MODE_FAKE:
        runtime_config = load_detect_v2_runtime_config(runtime_config_path, allow_default=True)
    elif provider_state.status == DETECT_V2_READY_STATUS:
        runtime_config = load_detect_v2_runtime_config(runtime_config_path)
    else:
        runtime_config = {}
    configured_runtime = _configured_runtime(settings)
    return {
        "schema_version": "detect.v2",
        "mode": getattr(settings, "detect_v2_mode", DETECT_V2_MODE_FAKE),
        "status": provider_state.status,
        "reason": provider_state.reason,
        "runtime_primary_model": runtime_config.get("primary_model"),
        "runtime_fallback_model": runtime_config.get("fallback_model"),
        "configured_runtime": configured_runtime,
        "image_size": getattr(
            settings,
            "detect_v2_image_size",
            DEFAULT_UNIFIED_WALKSAFE_INFERENCE_IMGSZ,
        ),
        "custom_tactile_model_path": (
            settings.detect_v2_custom_tactile_model_path.name
            if settings.detect_v2_custom_tactile_model_path
            else None
        ),
        "coco_model_path": settings.detect_v2_coco_model_path.name if settings.detect_v2_coco_model_path else None,
        "unified_model_path": (
            settings.detect_v2_unified_model_path.name
            if getattr(settings, "detect_v2_unified_model_path", None)
            else None
        ),
        "runtime_config_path": (
            settings.detect_v2_runtime_config_path.name
            if settings.detect_v2_runtime_config_path
            else None
        ),
    }


def _configured_runtime(settings: Any) -> str | None:
    if _path_exists(getattr(settings, "detect_v2_unified_model_path", None)):
        return "unified_walksafe"
    if _path_exists(getattr(settings, "detect_v2_custom_tactile_model_path", None)) and _path_exists(
        getattr(settings, "detect_v2_coco_model_path", None)
    ):
        return "legacy_two_model"
    return None


def run_detect_v2(image_bytes: bytes, content_type: str, context: DetectContext, settings: Any) -> DetectV2Response:
    provider_state = get_detect_v2_provider_state(settings)
    if provider_state.provider is None:
        raise RuntimeError(provider_state.reason or DETECT_V2_MODEL_NOT_CONFIGURED_REASON)
    return provider_state.provider.detect(image_bytes=image_bytes, content_type=content_type, context=context)


def warm_detect_v2(settings: Any) -> None:
    provider_state = get_detect_v2_provider_state(settings)
    if provider_state.provider is None:
        raise RuntimeError(provider_state.reason or DETECT_V2_MODEL_NOT_CONFIGURED_REASON)
    warmup = getattr(provider_state.provider, "warmup", None)
    if callable(warmup):
        warmup()


def load_detect_v2_runtime_config(
    path: str | Path | None = None,
    *,
    allow_default: bool = False,
) -> dict[str, Any]:
    if path is None or not Path(path).is_file():
        if allow_default:
            return validate_threshold_config(DEFAULT_RUNTIME_CONFIG)
        raise RuntimeError(DETECT_V2_RUNTIME_CONFIG_NOT_CONFIGURED_REASON)
    return load_threshold_config(path)


def threshold_for_detection(
    model_key: str,
    class_name: str,
    config: Mapping[str, Any] | None = None,
) -> float:
    runtime_config = config if config is not None else DEFAULT_RUNTIME_CONFIG
    default_config = DEFAULT_RUNTIME_CONFIG["models"][model_key]["thresholds"]
    model_config = runtime_config.get("models", {}).get(model_key, {})
    thresholds = model_config.get("thresholds", {})
    default_threshold = thresholds.get("default", default_config["default"])
    return thresholds.get(class_name, default_threshold)


def _threshold_for_detection(raw_detection: Mapping[str, Any], config: Mapping[str, Any] | None = None) -> float:
    return threshold_for_detection(raw_detection["model_key"], raw_detection["class_name"], config)


def _model_class_id(raw_detection: Mapping[str, Any]) -> int:
    if "model_class_id" in raw_detection:
        return int(raw_detection["model_class_id"])
    if raw_detection["model_key"] == "custom_tactile":
        return CUSTOM_TACTILE_CLASSES.index(raw_detection["class_name"])
    if raw_detection["model_key"] == "unified_walksafe":
        return UNIFIED_WALKSAFE_CLASSES.index(raw_detection["class_name"])
    return COCO_GENERAL_ALLOWLIST.index(raw_detection["class_name"])


def _detect_v2_detection(
    raw_detection: Mapping[str, Any],
    context: DetectContext,
    captured_at: datetime,
    runtime_config: Mapping[str, Any] | None = None,
) -> DetectV2Detection:
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
        distance_m=raw_detection.get("distance_m"),
        distance_source=raw_detection.get("distance_source"),
        distance_confidence=raw_detection.get("distance_confidence"),
        approach_state=raw_detection.get("approach_state"),
        threshold_used=_threshold_for_detection(raw_detection, runtime_config),
        captured_at=captured_at,
        gps=context.gps,
        heading=context.heading,
    )


def fake_detect_v2_detections(context: DetectContext) -> DetectV2Response:
    captured_at = context.captured_at or datetime.now(timezone.utc)
    unified_config = DEFAULT_RUNTIME_CONFIG["models"]["unified_walksafe"]
    class_categories = unified_config["class_categories"]
    raw_unified_detections = [
        {
            "model_key": "unified_walksafe",
            "source_model": "fake/unified-walksafe-contract",
            "class_name": "damaged_tactile_block",
            "category": class_categories["damaged_tactile_block"],
            "confidence": 0.93,
            "bbox": (0.1, 0.16, 0.46, 0.4),
        },
        {
            "model_key": "unified_walksafe",
            "source_model": "fake/unified-walksafe-contract",
            "class_name": "person",
            "category": class_categories["person"],
            "confidence": 0.84,
            "bbox": (0.12, 0.18, 0.42, 0.36),
        },
        {
            "model_key": "unified_walksafe",
            "source_model": "fake/unified-walksafe-contract",
            "class_name": "crosswalk",
            "category": class_categories["crosswalk"],
            "confidence": 0.78,
            "bbox": (0.08, 0.64, 0.72, 0.18),
        },
        {
            "model_key": "unified_walksafe",
            "source_model": "fake/unified-walksafe-contract",
            "class_name": "dog",
            "category": "unknown",
            "confidence": 0.99,
            "bbox": (0.55, 0.1, 0.22, 0.58),
        },
    ]

    filtered = filter_detections(raw_unified_detections, DEFAULT_RUNTIME_CONFIG)
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

def _open_image(image_bytes: bytes) -> Any:
    try:
        from PIL import Image  # type: ignore[import-not-found]
    except ImportError as exc:  # pragma: no cover - depends on runtime env
        raise RuntimeError("Pillow is required to run YOLO detect v2 inference") from exc
    return Image.open(BytesIO(image_bytes))


def _results_to_raw_detections(
    results: Any,
    *,
    model_key: str,
    source_model: str,
    allowlist: Sequence[str] | None = None,
) -> list[dict[str, Any]]:
    raw: list[dict[str, Any]] = []
    for result in _iter_results(results):
        raw.extend(
            ultralytics_result_to_raw_detections(
                result,
                model_key=model_key,  # type: ignore[arg-type]
                source_model=source_model,
                allowlist=allowlist,
            )
        )
    return raw


def _iter_results(results: Any) -> Sequence[Any]:
    if isinstance(results, Sequence) and not isinstance(results, (str, bytes)):
        return results
    return (results,)


def _detection_mapping(detection: Detection | Mapping[str, Any]) -> Mapping[str, Any]:
    if isinstance(detection, Detection):
        mapped: dict[str, Any] = {
            "model_key": detection.model_key,
            "source_model": detection.source_model,
            "class_name": detection.class_name,
            "category": detection.category,
            "confidence": detection.confidence,
            "bbox": detection.bbox,
        }
        distance_m = getattr(detection, "distance_m", None)
        if distance_m is not None:
            mapped["distance_m"] = distance_m
        distance_source = getattr(detection, "distance_source", None)
        if distance_source is not None:
            mapped["distance_source"] = distance_source
        distance_confidence = getattr(detection, "distance_confidence", None)
        if distance_confidence is not None:
            mapped["distance_confidence"] = distance_confidence
        approach_state = getattr(detection, "approach_state", None)
        if approach_state is not None:
            mapped["approach_state"] = approach_state
        return mapped
    return detection
