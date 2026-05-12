from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path


class Settings:
    def __init__(self) -> None:
        backend_root = Path(__file__).resolve().parents[1]
        self.database_url = os.getenv(
            "DATABASE_URL",
            "postgresql+psycopg://walksafe:walksafe@localhost:5432/walksafe",
        )
        self.upload_dir = Path(os.getenv("UPLOAD_DIR", str(backend_root / "uploads"))).resolve()
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
