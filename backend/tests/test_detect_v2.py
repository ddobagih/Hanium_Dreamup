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

import pytest  # noqa: E402

from backend.app.main import app, settings as app_settings  # noqa: E402
from backend.app.schemas import DetectV2Detection  # noqa: E402
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
    assert len(detections) == 3
    assert {detection["model_key"] for detection in detections} == {"custom_tactile", "coco_general"}
    assert {detection["class_name"] for detection in detections} == {
        "damaged_tactile_block",
        "tactile_damage_area",
        "person",
    }
    assert "dog" not in {detection["class_name"] for detection in detections}

    by_class = {detection["class_name"]: detection for detection in detections}
    assert by_class["damaged_tactile_block"]["model_class_id"] == 1
    assert by_class["tactile_damage_area"]["model_class_id"] == 2
    assert by_class["person"]["model_class_id"] == 0
    assert by_class["damaged_tactile_block"]["threshold_used"] == DEFAULT_RUNTIME_CONFIG["models"]["custom_tactile"]["thresholds"]["damaged_tactile_block"]
    assert by_class["tactile_damage_area"]["threshold_used"] == DEFAULT_RUNTIME_CONFIG["models"]["custom_tactile"]["thresholds"]["tactile_damage_area"]
    assert by_class["person"]["threshold_used"] == DEFAULT_RUNTIME_CONFIG["models"]["coco_general"]["thresholds"]["person"]
    assert by_class["tactile_damage_area"]["bbox"] == by_class["person"]["bbox"]

    for detection in detections:
        assert detection["schema_version"] == "detect.v2"
        assert detection["source_model"].startswith("fake/")
        assert detection["model_class_id"] >= 0
        assert 0 <= detection["confidence"] <= 1
        assert 0 <= detection["threshold_used"] <= 1
        assert detection["distance_m"] is None
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
    assert all(detection["distance_m"] is None for detection in detections)


def test_detect_v2_detection_distance_m_accepts_only_non_negative_values() -> None:
    payload = {
        "schema_version": "detect.v2",
        "model_key": "coco_general",
        "source_model": "test/coco",
        "model_class_id": 0,
        "class_name": "person",
        "category": "general_obstacle",
        "confidence": 0.84,
        "bbox": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
        "distance_m": 0,
        "distance_source": "sensor_depth",
        "distance_confidence": 0.8,
        "threshold_used": 0.25,
        "captured_at": "2026-05-12T12:00:00.000Z",
    }

    assert DetectV2Detection.model_validate(payload).distance_m == 0
    assert DetectV2Detection.model_validate(payload).distance_source == "sensor_depth"

    with pytest.raises(ValueError):
        DetectV2Detection.model_validate({**payload, "distance_m": -0.01})
    with pytest.raises(ValueError):
        DetectV2Detection.model_validate({**payload, "distance_m": 51})
    with pytest.raises(ValueError):
        DetectV2Detection.model_validate({**payload, "distance_confidence": 1.1})


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


def test_detect_v2_health_defaults_to_fake_ready(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "detect_v2_mode", "fake")
    monkeypatch.setattr(app_settings, "detect_v2_custom_tactile_model_path", None)
    monkeypatch.setattr(app_settings, "detect_v2_coco_model_path", None)
    monkeypatch.setattr(app_settings, "detect_v2_runtime_config_path", None)
    client = ASGITestClient(app)

    response = client.get("/detect/v2/health")

    assert response.status_code == 200
    body = response.json()
    assert body["schema_version"] == "detect.v2"
    assert body["mode"] == "fake"
    assert body["status"] == "ready"
    assert body["reason"] is None
    assert body["custom_tactile_model_path"] is None
    assert body["coco_model_path"] is None


def test_detect_v2_yolo_mode_without_model_paths_returns_503(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "detect_v2_mode", "yolo")
    monkeypatch.setattr(app_settings, "detect_v2_custom_tactile_model_path", None)
    monkeypatch.setattr(app_settings, "detect_v2_coco_model_path", None)
    client = ASGITestClient(app)

    health_response = client.get("/detect/v2/health")
    detect_response = client.post(
        "/detect/v2",
        data={"context": "{}"},
        files={"image": ("frame.png", PNG_BYTES, "image/png")},
    )

    assert health_response.status_code == 200
    health_body = health_response.json()
    assert health_body["mode"] == "yolo"
    assert health_body["status"] == "unavailable"
    assert health_body["reason"] == "detect_v2_model_not_configured:custom_tactile,coco_general"

    assert detect_response.status_code == 503
    detail = detect_response.json()["detail"]
    assert detail["code"] == "detect_v2_unavailable"
    assert detail["reason"] == "detect_v2_model_not_configured:custom_tactile,coco_general"


def test_detect_v2_yolo_mode_with_model_paths_is_ready_without_loading_models(tmp_path, monkeypatch) -> None:
    custom_model = tmp_path / "custom.pt"
    coco_model = tmp_path / "coco.pt"
    runtime_config = tmp_path / "runtime.json"
    custom_model.write_bytes(b"placeholder")
    coco_model.write_bytes(b"placeholder")
    runtime_config.write_text(json.dumps(DEFAULT_RUNTIME_CONFIG), encoding="utf-8")

    def fail_load(*args, **kwargs):  # noqa: ANN002, ANN003
        raise AssertionError("YOLO model load must stay lazy during health checks")

    monkeypatch.setattr("backend.app.services.detect_v2.load_yolo_model", fail_load)
    monkeypatch.setattr(app_settings, "detect_v2_mode", "yolo")
    monkeypatch.setattr(app_settings, "detect_v2_custom_tactile_model_path", custom_model)
    monkeypatch.setattr(app_settings, "detect_v2_coco_model_path", coco_model)
    monkeypatch.setattr(app_settings, "detect_v2_runtime_config_path", runtime_config)
    client = ASGITestClient(app)

    response = client.get("/detect/v2/health")

    assert response.status_code == 200
    body = response.json()
    assert body["mode"] == "yolo"
    assert body["status"] == "ready"
    assert body["reason"] is None
    assert body["custom_tactile_model_path"] == str(custom_model)
    assert body["coco_model_path"] == str(coco_model)
    assert body["runtime_config_path"] == str(runtime_config)


def test_yolo_detect_v2_provider_uses_injected_runtime_and_config_thresholds() -> None:
    from backend.app.services.detect_v2 import YoloDetectV2Provider

    runtime_config = json.loads(json.dumps(DEFAULT_RUNTIME_CONFIG))
    runtime_config["models"]["custom_tactile"]["thresholds"]["tactile_damage_area"] = 0.77
    runtime_config["models"]["coco_general"]["thresholds"] = {"default": 0.42}

    class FakeRuntime:
        def __init__(self, runtime_config):
            self.runtime_config = runtime_config

        def detect(self, image_bytes: bytes, content_type: str):
            assert image_bytes == PNG_BYTES
            assert content_type == "image/png"
            return [
                {
                    "model_key": "custom_tactile",
                    "source_model": "fake/custom",
                    "model_class_id": 2,
                    "class_name": "tactile_damage_area",
                    "category": "tactile",
                    "confidence": 0.91,
                    "bbox": (0.1, 0.2, 0.3, 0.4),
                    "distance_m": 1.4,
                    "distance_source": "manual_fixture",
                    "distance_confidence": 0.9,
                },
                {
                    "model_key": "coco_general",
                    "source_model": "fake/coco",
                    "model_class_id": 0,
                    "class_name": "person",
                    "category": "general_obstacle",
                    "confidence": 0.84,
                    "bbox": (0.5, 0.2, 0.1, 0.3),
                },
            ]

    context = {
        "captured_at": "2026-05-12T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 180.0,
    }
    provider = YoloDetectV2Provider(
        custom_tactile_model_path=Path("custom.pt"),
        coco_model_path=Path("coco.pt"),
        runtime=FakeRuntime(runtime_config),
    )

    from backend.app.schemas import DetectContext

    response = provider.detect(PNG_BYTES, "image/png", context=DetectContext.model_validate(context))

    assert response.schema_version == "detect.v2"
    assert [d.model_key for d in response.detections] == ["custom_tactile", "coco_general"]
    assert response.detections[0].threshold_used == 0.77
    assert response.detections[1].threshold_used == 0.42
    assert response.detections[0].distance_m == 1.4
    assert response.detections[0].distance_source == "manual_fixture"
    assert response.detections[0].distance_confidence == 0.9
    assert response.detections[1].distance_m is None
    assert response.detections[0].bbox.model_dump() == {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4}


def test_detect_v2_runtime_config_helper_falls_back_to_defaults_for_missing_config_and_class_threshold(tmp_path) -> None:
    from backend.app.services.detect_v2 import load_detect_v2_runtime_config, threshold_for_detection

    assert load_detect_v2_runtime_config(None)["models"]["coco_general"]["thresholds"]["person"] == 0.25
    assert load_detect_v2_runtime_config(tmp_path / "missing.json")["models"]["custom_tactile"]["thresholds"]["default"] == 0.25

    config = json.loads(json.dumps(DEFAULT_RUNTIME_CONFIG))
    config["models"]["coco_general"]["thresholds"] = {"default": 0.61}

    assert threshold_for_detection("coco_general", "person", config) == 0.61


def test_detect_v2_yolo_mode_endpoint_uses_lazy_runtime_factory(tmp_path, monkeypatch) -> None:
    custom_model = tmp_path / "custom.pt"
    coco_model = tmp_path / "coco.pt"
    custom_model.write_bytes(b"placeholder")
    coco_model.write_bytes(b"placeholder")

    class FakeRuntime:
        runtime_config = DEFAULT_RUNTIME_CONFIG

        def detect(self, image_bytes: bytes, content_type: str):
            return [
                {
                    "model_key": "custom_tactile",
                    "source_model": "fake/custom",
                    "model_class_id": 2,
                    "class_name": "tactile_damage_area",
                    "category": "tactile",
                    "confidence": 0.91,
                    "bbox": (0.1, 0.2, 0.3, 0.4),
                }
            ]

    def fake_runtime_factory(**kwargs):  # noqa: ANN003
        assert kwargs["custom_tactile_model_path"] == custom_model
        assert kwargs["coco_model_path"] == coco_model
        return FakeRuntime()

    monkeypatch.setattr("backend.app.services.detect_v2.LazyYoloDetectV2Runtime", fake_runtime_factory)
    monkeypatch.setattr(app_settings, "detect_v2_mode", "real")
    monkeypatch.setattr(app_settings, "detect_v2_custom_tactile_model_path", custom_model)
    monkeypatch.setattr(app_settings, "detect_v2_coco_model_path", coco_model)
    monkeypatch.setattr(app_settings, "detect_v2_runtime_config_path", None)
    client = ASGITestClient(app)

    response = client.post(
        "/detect/v2",
        data={"context": "{}"},
        files={"image": ("frame.png", PNG_BYTES, "image/png")},
    )

    assert response.status_code == 200
    detections = response.json()["detections"]
    assert len(detections) == 1
    assert detections[0]["model_key"] == "custom_tactile"
    assert detections[0]["class_name"] == "tactile_damage_area"
    assert detections[0]["distance_m"] is None
