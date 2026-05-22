from __future__ import annotations

import base64
from datetime import datetime
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.main import app  # noqa: E402
from model.two_model_runtime import DEFAULT_RUNTIME_CONFIG  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


JPEG_BYTES = b"\xff\xd8\xff\xe0" + (b"0" * 16)
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
)


def test_detect_v2_returns_fake_contract_detections_with_new_classes() -> None:
    client = ASGITestClient(app)
    context = {
        "captured_at": "2026-05-12T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 180.0,
    }

    response = client.post(
        "/detect/v2",
        data={"context": json.dumps(context)},
        files={"image": ("frame.png", PNG_BYTES, "image/png")},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["schema_version"] == "detect.v2"
    detections = body["detections"]
    assert len(detections) == 2
    assert {detection["model_key"] for detection in detections} == {"custom_tactile", "coco_general"}
    assert {detection["class_name"] for detection in detections} == {"tactile_damage_area", "person"}
    assert "dog" not in {detection["class_name"] for detection in detections}

    by_model = {detection["model_key"]: detection for detection in detections}
    assert by_model["custom_tactile"]["model_class_id"] == 2
    assert by_model["coco_general"]["model_class_id"] == 0
    assert by_model["custom_tactile"]["threshold_used"] == DEFAULT_RUNTIME_CONFIG["models"]["custom_tactile"]["thresholds"]["tactile_damage_area"]
    assert by_model["coco_general"]["threshold_used"] == DEFAULT_RUNTIME_CONFIG["models"]["coco_general"]["thresholds"]["person"]
    assert by_model["custom_tactile"]["bbox"] == by_model["coco_general"]["bbox"]

    for detection in detections:
        assert detection["schema_version"] == "detect.v2"
        assert detection["source_model"].startswith("fake/")
        assert detection["model_class_id"] >= 0
        assert 0 <= detection["confidence"] <= 1
        assert 0 <= detection["threshold_used"] <= 1
        assert detection["captured_at"].startswith("2026-05-12T12:00:00")
        assert detection["gps"] == context["gps"]
        assert detection["heading"] == 180.0
        bbox = detection["bbox"]
        assert 0 <= bbox["x"] <= 1
        assert 0 <= bbox["y"] <= 1
        assert 0 < bbox["width"] <= 1
        assert 0 < bbox["height"] <= 1


def test_detect_v2_uses_server_time_when_captured_at_missing() -> None:
    client = ASGITestClient(app)

    response = client.post(
        "/detect/v2",
        data={"context": "{}"},
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 200
    detections = response.json()["detections"]
    captured_values = {detection["captured_at"] for detection in detections}
    assert len(captured_values) == 1
    captured_at = datetime.fromisoformat(captured_values.pop().replace("Z", "+00:00"))
    assert captured_at.tzinfo is not None
    assert all(detection["gps"] is None for detection in detections)
    assert all(detection["heading"] is None for detection in detections)


def test_detect_v2_does_not_change_v1_model_unavailable_contract() -> None:
    client = ASGITestClient(app)

    v2_response = client.post(
        "/detect/v2",
        data={"context": "{}"},
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )
    v1_response = client.post(
        "/detect",
        data={"context": "{}"},
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert v2_response.status_code == 200
    assert v1_response.status_code == 503
    assert v1_response.json()["detail"]["code"] == "model_unavailable"
    assert v1_response.json()["detail"]["reason"] == "model_not_configured"


def test_detect_v2_reuses_image_validation() -> None:
    client = ASGITestClient(app)

    response = client.post(
        "/detect/v2",
        data={"context": "{}"},
        files={"image": ("frame.txt", b"not an image", "text/plain")},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "unsupported_image_type"
