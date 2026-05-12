from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv


DEFAULT_MAX_UPLOAD_BYTES = 8 * 1024 * 1024
DEFAULT_ALLOWED_IMAGE_CONTENT_TYPES = "image/jpeg,image/png,image/webp"


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
        model_path = os.getenv("MODEL_ARTIFACT_PATH", "")
        self.model_artifact_path = Path(model_path).expanduser().resolve() if model_path else None
        self.cors_origins = [
            origin.strip()
            for origin in os.getenv("CORS_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000").split(",")
            if origin.strip()
        ]


@lru_cache
def get_settings() -> Settings:
    return Settings()
