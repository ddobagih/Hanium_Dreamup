from __future__ import annotations

import json
import os
from pathlib import Path
import sys

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.config import get_settings  # noqa: E402
from backend.app.main import app  # noqa: E402


def test_detect_health_is_explicitly_unavailable() -> None:
    client = TestClient(app)

    response = client.get("/detect/health")

    assert response.status_code == 200
    assert response.json() == {
        "model_status": "unavailable",
        "model_version": None,
        "reason": "model_not_configured",
    }


def test_detect_returns_model_unavailable_until_adapter_exists() -> None:
    client = TestClient(app)
    context = {
        "captured_at": "2026-05-12T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 180.0,
    }

    response = client.post(
        "/detect",
        data={"context": json.dumps(context)},
        files={"image": ("frame.jpg", b"fake image bytes", "image/jpeg")},
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "model_unavailable"
    assert response.json()["detail"]["reason"] == "model_not_configured"


def test_detect_rejects_non_image_upload() -> None:
    client = TestClient(app)

    response = client.post(
        "/detect",
        data={"context": "{}"},
        files={"image": ("frame.txt", b"not an image", "text/plain")},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "unsupported_image_type"


def test_detect_rejects_image_over_size_limit() -> None:
    client = TestClient(app)
    settings = get_settings()
    previous_limit = settings.max_upload_bytes
    settings.max_upload_bytes = 8

    try:
        response = client.post(
            "/detect",
            data={"context": "{}"},
            files={"image": ("frame.jpg", b"x" * 9, "image/jpeg")},
        )
    finally:
        settings.max_upload_bytes = previous_limit

    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "upload_too_large"


def test_detect_rejects_invalid_context() -> None:
    client = TestClient(app)

    response = client.post(
        "/detect",
        data={"context": "{not-json"},
        files={"image": ("frame.jpg", b"fake image bytes", "image/jpeg")},
    )

    assert response.status_code == 422
