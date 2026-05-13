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
        self.cors_origins = [
            origin.strip()
            for origin in os.getenv("CORS_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000").split(",")
            if origin.strip()
        ]


@lru_cache
def get_settings() -> Settings:
    return Settings()
