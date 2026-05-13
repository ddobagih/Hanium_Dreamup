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
from backend.app.schemas import CLASS_ORDER  # noqa: E402


JPEG_BYTES = b"\xff\xd8\xff\xe0" + (b"0" * 16)
DEFAULT_MODEL_CONTRACT = {
    "model_artifact_path": None,
    "model_class_order": list(CLASS_ORDER),
    "model_confidence_threshold": 0.35,
    "model_iou_threshold": 0.7,
    "model_image_size": 640,
}


def test_detect_health_is_explicitly_unavailable() -> None:
    client = TestClient(app)

    response = client.get("/detect/health")

    assert response.status_code == 200
    assert response.json() == {
        "model_status": "unavailable",
        "model_version": None,
        "reason": "model_not_configured",
        **DEFAULT_MODEL_CONTRACT,
    }


def test_detect_health_surfaces_contract_without_ready_for_existing_artifact(tmp_path: Path) -> None:
    client = TestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    settings = get_settings()
    previous_values = {
        "model_artifact_path": settings.model_artifact_path,
        "model_version": settings.model_version,
        "model_class_order": settings.model_class_order,
        "model_confidence_threshold": settings.model_confidence_threshold,
        "model_iou_threshold": settings.model_iou_threshold,
        "model_image_size": settings.model_image_size,
    }
    settings.model_artifact_path = model_path.resolve()
    settings.model_version = "walksafe-test"
    settings.model_class_order = CLASS_ORDER
    settings.model_confidence_threshold = 0.5
    settings.model_iou_threshold = 0.6
    settings.model_image_size = 512

    try:
        response = client.get("/detect/health")
    finally:
        for key, value in previous_values.items():
            setattr(settings, key, value)

    assert response.status_code == 200
    assert response.json() == {
        "model_status": "unavailable",
        "model_version": "walksafe-test",
        "reason": "model_adapter_not_implemented",
        "model_artifact_path": str(model_path.resolve()),
        "model_class_order": list(CLASS_ORDER),
        "model_confidence_threshold": 0.5,
        "model_iou_threshold": 0.6,
        "model_image_size": 512,
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
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "model_unavailable"
    assert response.json()["detail"]["reason"] == "model_not_configured"


def test_detect_does_not_become_ready_when_artifact_exists(tmp_path: Path) -> None:
    client = TestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    settings = get_settings()
    previous_path = settings.model_artifact_path
    settings.model_artifact_path = model_path.resolve()

    try:
        response = client.post(
            "/detect",
            data={"context": "{}"},
            files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
        )
    finally:
        settings.model_artifact_path = previous_path

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "model_unavailable"
    assert response.json()["detail"]["reason"] == "model_adapter_not_implemented"


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
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 422
