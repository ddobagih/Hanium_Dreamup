from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv

from backend.app.schemas import CLASS_ORDER


DEFAULT_MAX_UPLOAD_BYTES = 8 * 1024 * 1024
DEFAULT_ALLOWED_IMAGE_CONTENT_TYPES = "image/jpeg,image/png,image/webp"
DEFAULT_MODEL_CLASS_ORDER = CLASS_ORDER
DEFAULT_MODEL_CLASS_ORDER_ENV = ",".join(DEFAULT_MODEL_CLASS_ORDER)
DEFAULT_MODEL_CONFIDENCE_THRESHOLD = 0.35
DEFAULT_MODEL_IOU_THRESHOLD = 0.7
DEFAULT_MODEL_IMAGE_SIZE = 640
DEFAULT_DETECT_V2_MODE = "fake"
DEFAULT_WALKING_ROUTE_PROVIDER = "tmap_pedestrian"
DEFAULT_KAKAO_MOBILITY_WALKING_DIRECTIONS_URL = "https://apis-navi.kakaomobility.com/affiliate/walking/v1/directions"
DEFAULT_KAKAO_MOBILITY_SERVICE_NAME = "walksafe"
DEFAULT_KAKAO_MOBILITY_TIMEOUT_SECONDS = 4.0
DEFAULT_TMAP_PEDESTRIAN_ROUTE_URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian"
DEFAULT_TMAP_POI_SEARCH_URL = "https://apis.openapi.sk.com/tmap/pois"
DEFAULT_TMAP_PEDESTRIAN_API_VERSION = "1"
DEFAULT_TMAP_TIMEOUT_SECONDS = 4.0
DEFAULT_TMAP_PEDESTRIAN_SPEED_KMH = 4.0
DEFAULT_TMAP_POI_PROVIDER = "live"
DEFAULT_MAX_REPORT_METADATA_BYTES = 64 * 1024
DEFAULT_MAX_ANDROID_DEBUG_LOG_BYTES = 64 * 1024
DEFAULT_ANDROID_DEBUG_LOG_ENABLED = "false"


def _env_text(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip()


def _parse_model_class_order(raw_value: str) -> tuple[str, ...]:
    if not raw_value:
        return DEFAULT_MODEL_CLASS_ORDER

    class_order = tuple(
        class_name.strip() for class_name in raw_value.split(",") if class_name.strip()
    )
    if class_order != DEFAULT_MODEL_CLASS_ORDER:
        raise ValueError(f"MODEL_CLASS_ORDER must be {DEFAULT_MODEL_CLASS_ORDER_ENV}")
    return class_order


def _parse_unit_float(name: str, default: float) -> float:
    raw_value = _env_text(name, str(default))
    if not raw_value:
        return default

    value = float(raw_value)
    if value < 0 or value > 1:
        raise ValueError(f"{name} must be between 0 and 1")
    return value


def _parse_positive_int(name: str, default: int) -> int:
    raw_value = _env_text(name, str(default))
    if not raw_value:
        return default

    value = int(raw_value)
    if value <= 0:
        raise ValueError(f"{name} must be greater than 0")
    return value


def _parse_positive_float(name: str, default: float) -> float:
    raw_value = _env_text(name, str(default))
    if not raw_value:
        return default

    value = float(raw_value)
    if value <= 0:
        raise ValueError(f"{name} must be greater than 0")
    return value


def _parse_bool(name: str, default: str = "false") -> bool:
    value = _env_text(name, default).lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off", ""}:
        return False
    raise ValueError(f"{name} must be a boolean")


class Settings:
    def __init__(self) -> None:
        backend_root = Path(__file__).resolve().parents[1]
        load_dotenv(backend_root / ".env")
        self.database_url = os.getenv(
            "DATABASE_URL",
            "postgresql+psycopg://walksafe:walksafe@localhost:5432/walksafe",
        )
        self.upload_dir = Path(os.getenv("UPLOAD_DIR", str(backend_root / "uploads"))).resolve()
        self.max_upload_bytes = int(os.getenv("MAX_UPLOAD_BYTES", str(DEFAULT_MAX_UPLOAD_BYTES)))
        self.allowed_image_content_types = {
            content_type.strip()
            for content_type in os.getenv("ALLOWED_IMAGE_CONTENT_TYPES", DEFAULT_ALLOWED_IMAGE_CONTENT_TYPES).split(",")
            if content_type.strip()
        }
        model_path = _env_text("MODEL_ARTIFACT_PATH")
        self.model_artifact_path = Path(model_path).expanduser().resolve() if model_path else None
        self.model_version = _env_text("MODEL_VERSION") or None
        self.model_class_order = _parse_model_class_order(
            _env_text("MODEL_CLASS_ORDER", DEFAULT_MODEL_CLASS_ORDER_ENV)
        )
        self.model_confidence_threshold = _parse_unit_float(
            "MODEL_CONFIDENCE_THRESHOLD",
            DEFAULT_MODEL_CONFIDENCE_THRESHOLD,
        )
        self.model_iou_threshold = _parse_unit_float(
            "MODEL_IOU_THRESHOLD",
            DEFAULT_MODEL_IOU_THRESHOLD,
        )
        self.model_image_size = _parse_positive_int("MODEL_IMAGE_SIZE", DEFAULT_MODEL_IMAGE_SIZE)
        self.detect_v2_mode = _env_text("DETECT_V2_MODE", DEFAULT_DETECT_V2_MODE).lower() or DEFAULT_DETECT_V2_MODE
        detect_v2_custom_tactile_model_path = _env_text("DETECT_V2_CUSTOM_TACTILE_MODEL_PATH")
        self.detect_v2_custom_tactile_model_path = (
            Path(detect_v2_custom_tactile_model_path).expanduser().resolve()
            if detect_v2_custom_tactile_model_path
            else None
        )
        detect_v2_coco_model_path = _env_text("DETECT_V2_COCO_MODEL_PATH")
        self.detect_v2_coco_model_path = (
            Path(detect_v2_coco_model_path).expanduser().resolve()
            if detect_v2_coco_model_path
            else None
        )
        detect_v2_unified_model_path = _env_text("DETECT_V2_UNIFIED_MODEL_PATH")
        self.detect_v2_unified_model_path = (
            Path(detect_v2_unified_model_path).expanduser().resolve()
            if detect_v2_unified_model_path
            else None
        )
        detect_v2_runtime_config_path = _env_text("DETECT_V2_RUNTIME_CONFIG_PATH")
        self.detect_v2_runtime_config_path = (
            Path(detect_v2_runtime_config_path).expanduser().resolve()
            if detect_v2_runtime_config_path
            else None
        )
        self.walking_route_provider = _env_text("WALKING_ROUTE_PROVIDER", DEFAULT_WALKING_ROUTE_PROVIDER)
        self.tmap_app_key = _env_text("TMAP_APP_KEY")
        self.tmap_pedestrian_route_url = _env_text(
            "TMAP_PEDESTRIAN_ROUTE_URL",
            DEFAULT_TMAP_PEDESTRIAN_ROUTE_URL,
        )
        self.tmap_pedestrian_api_version = _env_text(
            "TMAP_PEDESTRIAN_API_VERSION",
            DEFAULT_TMAP_PEDESTRIAN_API_VERSION,
        )
        self.tmap_poi_search_url = _env_text(
            "TMAP_POI_SEARCH_URL",
            DEFAULT_TMAP_POI_SEARCH_URL,
        )
        self.tmap_poi_provider = _env_text("TMAP_POI_PROVIDER", DEFAULT_TMAP_POI_PROVIDER).lower() or DEFAULT_TMAP_POI_PROVIDER
        self.tmap_timeout_seconds = _parse_positive_float(
            "TMAP_TIMEOUT_SECONDS",
            DEFAULT_TMAP_TIMEOUT_SECONDS,
        )
        self.tmap_pedestrian_speed_kmh = _parse_positive_float(
            "TMAP_PEDESTRIAN_SPEED_KMH",
            DEFAULT_TMAP_PEDESTRIAN_SPEED_KMH,
        )
        self.kakao_mobility_rest_api_key = _env_text("KAKAO_MOBILITY_REST_API_KEY")
        self.kakao_mobility_walking_directions_url = _env_text(
            "KAKAO_MOBILITY_WALKING_DIRECTIONS_URL",
            DEFAULT_KAKAO_MOBILITY_WALKING_DIRECTIONS_URL,
        )
        self.kakao_mobility_service_name = _env_text(
            "KAKAO_MOBILITY_SERVICE_NAME",
            DEFAULT_KAKAO_MOBILITY_SERVICE_NAME,
        )
        self.kakao_mobility_timeout_seconds = _parse_positive_float(
            "KAKAO_MOBILITY_TIMEOUT_SECONDS",
            DEFAULT_KAKAO_MOBILITY_TIMEOUT_SECONDS,
        )
        self.max_report_metadata_bytes = _parse_positive_int(
            "MAX_REPORT_METADATA_BYTES",
            DEFAULT_MAX_REPORT_METADATA_BYTES,
        )
        self.android_debug_log_dir = Path(
            os.getenv("ANDROID_DEBUG_LOG_DIR", str(backend_root / "android_debug_logs"))
        ).resolve()
        self.android_debug_log_enabled = _parse_bool(
            "ANDROID_DEBUG_LOG_ENABLED",
            DEFAULT_ANDROID_DEBUG_LOG_ENABLED,
        )
        self.max_android_debug_log_bytes = _parse_positive_int(
            "MAX_ANDROID_DEBUG_LOG_BYTES",
            DEFAULT_MAX_ANDROID_DEBUG_LOG_BYTES,
        )
        self.cors_origins = [
            origin.strip()
            for origin in os.getenv("CORS_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000").split(",")
            if origin.strip()
        ]


@lru_cache
def get_settings() -> Settings:
    return Settings()
