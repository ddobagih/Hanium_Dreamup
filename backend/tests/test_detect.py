from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import sys
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.config import get_settings  # noqa: E402
import backend.app.detector as detector  # noqa: E402
from backend.app.main import app  # noqa: E402
from backend.app.schemas import CLASS_ORDER  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


JPEG_BYTES = b"\xff\xd8\xff\xe0" + (b"0" * 16)
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
)
DEFAULT_MODEL_CONTRACT = {
    "model_artifact_path": None,
    "model_class_order": list(CLASS_ORDER),
    "model_confidence_threshold": 0.35,
    "model_iou_threshold": 0.7,
    "model_image_size": 640,
}
MODEL_SETTING_KEYS = (
    "model_artifact_path",
    "model_version",
    "model_class_order",
    "model_confidence_threshold",
    "model_iou_threshold",
    "model_image_size",
    "max_upload_bytes",
)


class FakeBox:
    def __init__(self, xyxy: list[float], confidence: float, class_id: int) -> None:
        self.xyxy = [xyxy]
        self.conf = [confidence]
        self.cls = [class_id]


class FakeResult:
    def __init__(self, boxes: list[FakeBox]) -> None:
        self.orig_shape = (480, 640)
        self.boxes = boxes


def fake_yolo_class(names: dict[int, str], boxes: list[FakeBox]) -> type:
    class FakeYOLO:
        task = "detect"
        load_count = 0
        last_call_kwargs: dict[str, Any] | None = None

        def __init__(self, model_path: str) -> None:
            self.model_path = model_path
            self.names = names
            type(self).load_count += 1

        def __call__(self, image: Any, **kwargs: Any) -> list[FakeResult]:
            type(self).last_call_kwargs = kwargs
            return [FakeResult(boxes)]

    return FakeYOLO


@pytest.fixture(autouse=True)
def reset_detector_state() -> None:
    settings = get_settings()
    previous_values = {key: getattr(settings, key) for key in MODEL_SETTING_KEYS}
    settings.model_artifact_path = None
    settings.model_version = None
    settings.model_class_order = CLASS_ORDER
    settings.model_confidence_threshold = 0.35
    settings.model_iou_threshold = 0.7
    settings.model_image_size = 640
    detector._reset_adapter_cache()

    yield

    for key, value in previous_values.items():
        setattr(settings, key, value)
    detector._reset_adapter_cache()


def configure_model_settings(model_path: Path, version: str = "walksafe-test") -> None:
    settings = get_settings()
    settings.model_artifact_path = model_path.resolve()
    settings.model_version = version
    settings.model_class_order = CLASS_ORDER


def test_detect_health_is_explicitly_unavailable() -> None:
    client = ASGITestClient(app)

    response = client.get("/detect/health")

    assert response.status_code == 200
    assert response.json() == {
        "model_status": "unavailable",
        "model_version": None,
        "reason": "model_not_configured",
        **DEFAULT_MODEL_CONTRACT,
    }


def test_detect_health_reports_dependency_missing_for_existing_artifact(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = ASGITestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    configure_model_settings(model_path)

    def missing_yolo() -> None:
        raise detector.ModelUnavailable(detector.MODEL_DEPENDENCY_MISSING_REASON)

    monkeypatch.setattr(detector, "_load_yolo_class", missing_yolo)

    response = client.get("/detect/health")

    assert response.status_code == 200
    assert response.json() == {
        "model_status": "unavailable",
        "model_version": "walksafe-test",
        "reason": "model_dependency_missing",
        "model_artifact_path": str(model_path.resolve()),
        "model_class_order": list(CLASS_ORDER),
        "model_confidence_threshold": 0.35,
        "model_iou_threshold": 0.7,
        "model_image_size": 640,
    }


def test_detect_health_is_ready_after_fake_model_loads(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = ASGITestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    configure_model_settings(model_path)
    settings = get_settings()
    settings.model_confidence_threshold = 0.5
    settings.model_iou_threshold = 0.6
    settings.model_image_size = 512
    fake_yolo = fake_yolo_class(
        names={class_id: class_name for class_id, class_name in enumerate(CLASS_ORDER)},
        boxes=[],
    )
    monkeypatch.setattr(detector, "_load_yolo_class", lambda: fake_yolo)

    first_response = client.get("/detect/health")
    second_response = client.get("/detect/health")

    assert first_response.status_code == 200
    assert first_response.json() == {
        "model_status": "ready",
        "model_version": "walksafe-test",
        "reason": None,
        "model_artifact_path": str(model_path.resolve()),
        "model_class_order": list(CLASS_ORDER),
        "model_confidence_threshold": 0.5,
        "model_iou_threshold": 0.6,
        "model_image_size": 512,
    }
    assert second_response.json()["model_status"] == "ready"
    assert fake_yolo.load_count == 1


def test_detect_health_rejects_class_order_mismatch(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = ASGITestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    configure_model_settings(model_path)
    fake_yolo = fake_yolo_class(names={0: "pothole"}, boxes=[])
    monkeypatch.setattr(detector, "_load_yolo_class", lambda: fake_yolo)

    response = client.get("/detect/health")

    assert response.status_code == 200
    assert response.json()["model_status"] == "unavailable"
    assert response.json()["reason"] == "model_class_order_mismatch"


def test_detect_returns_model_unavailable_without_configured_model() -> None:
    client = ASGITestClient(app)
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


def test_detect_returns_model_unavailable_when_model_load_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = ASGITestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    configure_model_settings(model_path)

    class BrokenYOLO:
        def __init__(self, model_path: str) -> None:
            raise RuntimeError(model_path)

    monkeypatch.setattr(detector, "_load_yolo_class", lambda: BrokenYOLO)

    response = client.post(
        "/detect",
        data={"context": "{}"},
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "model_unavailable"
    assert response.json()["detail"]["reason"] == "model_load_failed"


def test_detect_returns_server_detections_with_normalized_bbox(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = ASGITestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    configure_model_settings(model_path)
    settings = get_settings()
    settings.model_confidence_threshold = 0.5
    settings.model_iou_threshold = 0.6
    settings.model_image_size = 512
    fake_yolo = fake_yolo_class(
        names={class_id: class_name for class_id, class_name in enumerate(CLASS_ORDER)},
        boxes=[
            FakeBox([64, 96, 320, 288], 0.88, 0),
            FakeBox([10, 10, 20, 20], 0.2, 0),
        ],
    )
    monkeypatch.setattr(detector, "_load_yolo_class", lambda: fake_yolo)
    context = {
        "captured_at": "2026-05-12T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 180.0,
    }

    response = client.post(
        "/detect",
        data={"context": json.dumps(context)},
        files={"image": ("frame.png", PNG_BYTES, "image/png")},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["model_status"] == "ready"
    assert body["model_version"] == "walksafe-test"
    assert len(body["detections"]) == 1
    detection = body["detections"][0]
    assert detection["class_id"] == 0
    assert detection["class_name"] == "damaged_tactile_block"
    assert detection["confidence"] == 0.88
    assert detection["bbox"] == {
        "x": 0.1,
        "y": 0.2,
        "width": 0.4,
        "height": 0.4,
    }
    assert detection["source"] == "server"
    assert detection["gps"] == context["gps"]
    assert detection["heading"] == 180.0
    assert detection["captured_at"].startswith("2026-05-12T12:00:00")
    assert fake_yolo.last_call_kwargs == {
        "conf": 0.5,
        "iou": 0.6,
        "imgsz": 512,
        "verbose": False,
    }


def test_detect_single_class_model_only_maps_damaged_tactile_block(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = ASGITestClient(app)
    model_path = tmp_path / "best.pt"
    model_path.write_bytes(b"placeholder")
    configure_model_settings(model_path)
    fake_yolo = fake_yolo_class(
        names={0: "damaged_tactile_block"},
        boxes=[
            FakeBox([64, 96, 320, 288], 0.88, 0),
            FakeBox([10, 10, 20, 20], 0.88, 1),
        ],
    )
    monkeypatch.setattr(detector, "_load_yolo_class", lambda: fake_yolo)

    response = client.post(
        "/detect",
        data={"context": "{}"},
        files={"image": ("frame.png", PNG_BYTES, "image/png")},
    )

    assert response.status_code == 200
    detections = response.json()["detections"]
    assert len(detections) == 1
    assert detections[0]["class_id"] == 0
    assert detections[0]["class_name"] == "damaged_tactile_block"


def test_detect_rejects_non_image_upload() -> None:
    client = ASGITestClient(app)

    response = client.post(
        "/detect",
        data={"context": "{}"},
        files={"image": ("frame.txt", b"not an image", "text/plain")},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "unsupported_image_type"


def test_detect_rejects_image_over_size_limit() -> None:
    client = ASGITestClient(app)
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
    client = ASGITestClient(app)

    response = client.post(
        "/detect",
        data={"context": "{not-json"},
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 422
