from __future__ import annotations

import json
import io
import os
from pathlib import Path
import stat
import sys

import pytest
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.main import app, settings  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402

_JPEG_OUTPUT = io.BytesIO()
Image.new("RGB", (4, 4), color=(180, 160, 40)).save(_JPEG_OUTPUT, format="JPEG")
JPEG_BYTES = _JPEG_OUTPUT.getvalue()


def test_android_depth_debug_logs_store_metadata_only_jsonl(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.android_debug_log_enabled = True
    payload = {
        "schema_version": "android.depth_debug.v1",
        "session_id": "session-1",
        "device_model": "Pixel Test",
        "android_version": "16",
        "app_version_name": "0.1.0",
        "entries": [
            {
                "frame_timestamp_ms": 1234,
                "detector_frame_timestamp_ms": 1200,
                "detector_age_ms": 34,
                "detector_source_age_ms": 54,
                "detector_completed_age_ms": 4,
                "detector_frame_delta_ms": 34,
                "detect_duration_ms": 90,
                "detector_yuv_decode_ms": 10,
                "detector_model_key": "unified_walksafe",
                "detector_loaded_model_key": "legacy_two_model",
                "detector_model_fallback_used": True,
                "detector_model_load_reason": "unified_unavailable_legacy_loaded",
                "detector_model_preprocess_ms": 20,
                "detector_model_inference_ms": 40,
                "detector_model_parse_ms": 5,
                "detector_coco_preprocess_ms": 20,
                "detector_coco_inference_ms": 55,
                "detector_coco_parse_ms": 5,
                "detector_completed_models": ["unified_walksafe"],
                "detector_skipped_models": [],
                "detector_partial": False,
                "detection_count": 1,
                "detections_used_for_depth": True,
                "top_detection_class_name": "person",
                "top_detection_confidence": 0.91,
                "top_detection_bbox": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
                "best_depth_class_name": "person",
                "best_depth_track_id": "track-1",
                "best_depth_source": "ARCORE_RAW_DEPTH",
                "best_depth_detection_confidence": 0.91,
                "best_depth_confidence_score": 0.80,
                "best_depth_median_m": 1.2,
                "best_depth_p20_m": 1.1,
                "best_depth_risk_distance_m": 1.1,
                "best_depth_iqr_m": 0.1,
                "best_depth_valid_sample_count": 32,
                "best_depth_valid_sample_ratio": 0.75,
                "best_depth_bbox": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
                "preview_width": 1080,
                "preview_height": 1920,
                "camera_image_width": 640,
                "camera_image_height": 480,
                "display_rotation": 0,
                "depth_width": 160,
                "depth_height": 120,
                "overlay_transform_path": "arcore_image_to_view",
                "depth_transform_path": "identity",
                "transform_path": "identity",
                "fallback_reason": "arcore_mapper_not_connected",
            }
        ],
    }

    try:
        response = ASGITestClient(app).post("/android/debug/depth-logs", json=payload)
        recent = ASGITestClient(app).get("/android/debug/depth-logs/recent")
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 200
    assert response.json()["stored"] == 1
    log_path = tmp_path / "android_depth_debug.jsonl"
    assert log_path.exists()
    assert stat.S_IMODE(tmp_path.stat().st_mode) == 0o700
    assert stat.S_IMODE(log_path.stat().st_mode) == 0o600
    log_text = log_path.read_text(encoding="utf-8")
    assert "person" in log_text
    assert "rgb_frame" not in log_text
    assert "raw_depth_ref" not in log_text
    assert recent.status_code == 200
    assert recent.json()["items"][0]["entry"]["transform_path"] == "identity"
    assert recent.json()["items"][0]["entry"]["detector_model_key"] == "unified_walksafe"
    assert recent.json()["items"][0]["entry"]["detector_loaded_model_key"] == "legacy_two_model"
    assert recent.json()["items"][0]["entry"]["detector_model_fallback_used"] is True
    assert recent.json()["items"][0]["entry"]["detector_model_load_reason"] == "unified_unavailable_legacy_loaded"
    assert recent.json()["items"][0]["entry"]["detector_model_inference_ms"] == 40
    assert recent.json()["items"][0]["entry"]["detector_completed_models"] == ["unified_walksafe"]
    assert recent.json()["items"][0]["entry"]["detector_frame_delta_ms"] == 34
    assert recent.json()["items"][0]["entry"]["best_depth_risk_distance_m"] == 1.1


@pytest.mark.parametrize("field_name", ["rgb_frame_ref", "raw_depth_ref"])
def test_android_depth_debug_logs_reject_unknown_media_fields(tmp_path: Path, field_name: str) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.android_debug_log_enabled = True
    payload = {
        "schema_version": "android.depth_debug.v1",
        "session_id": "session-1",
        "entries": [
            {
                "frame_timestamp_ms": 1234,
                "detection_count": 0,
                "detections_used_for_depth": False,
                field_name: "forbidden.jpg",
            }
        ],
    }

    try:
        response = ASGITestClient(app).post("/android/debug/depth-logs", json=payload)
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 422
    assert not (tmp_path / "android_depth_debug.jsonl").exists()


def test_android_depth_debug_logs_enforce_payload_size_limit(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_max_bytes = settings.max_android_debug_log_bytes
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.max_android_debug_log_bytes = 128
    settings.android_debug_log_enabled = True
    payload = {
        "schema_version": "android.depth_debug.v1",
        "session_id": "session-1",
        "entries": [
            {
                "frame_timestamp_ms": 1234,
                "detection_count": 0,
                "detections_used_for_depth": False,
                "stale_reason": "age>2500ms",
            }
        ],
    }

    try:
        response = ASGITestClient(app).post("/android/debug/depth-logs", json=payload)
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.max_android_debug_log_bytes = previous_max_bytes
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 413


def test_android_depth_debug_logs_are_disabled_by_default(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.android_debug_log_enabled = False
    payload = {
        "schema_version": "android.depth_debug.v1",
        "session_id": "session-1",
        "entries": [
            {
                "frame_timestamp_ms": 1234,
                "detection_count": 0,
                "detections_used_for_depth": False,
            }
        ],
    }

    try:
        response = ASGITestClient(app).post("/android/debug/depth-logs", json=payload)
        recent = ASGITestClient(app).get("/android/debug/depth-logs/recent")
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 404
    assert recent.status_code == 404
    assert not (tmp_path / "android_depth_debug.jsonl").exists()


def test_android_frame_captures_are_disabled_by_default(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.android_debug_log_enabled = False

    try:
        response = ASGITestClient(app).post(
            "/android/debug/frame-captures",
            data={"metadata": json.dumps({"schema_version": "android.frame_capture.v1"})},
            files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
        )
        recent = ASGITestClient(app).get("/android/debug/frame-captures/recent")
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 404
    assert recent.status_code == 404
    assert not (tmp_path / "android_frame_captures.jsonl").exists()


def test_android_frame_captures_enforce_metadata_size_limit(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_max_bytes = settings.max_android_debug_log_bytes
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.max_android_debug_log_bytes = 32
    settings.android_debug_log_enabled = True

    try:
        response = ASGITestClient(app).post(
            "/android/debug/frame-captures",
            data={"metadata": json.dumps({"schema_version": "android.frame_capture.v1", "padding": "x" * 64})},
            files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
        )
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.max_android_debug_log_bytes = previous_max_bytes
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 413
    assert not (tmp_path / "android_frame_captures.jsonl").exists()


def test_android_frame_captures_store_image_and_metadata_when_enabled(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.android_debug_log_enabled = True

    try:
        response = ASGITestClient(app).post(
            "/android/debug/frame-captures",
            data={
                "metadata": json.dumps(
                    {
                        "schema_version": "android.frame_capture.v1",
                        "capture_reason": "explicit_debug_opt_in",
                    }
                )
            },
            files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
        )
        recent = ASGITestClient(app).get("/android/debug/frame-captures/recent")
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True
    assert Path(body["image_file"]).exists()
    log_path = tmp_path / "android_frame_captures.jsonl"
    assert log_path.exists()
    assert stat.S_IMODE(tmp_path.stat().st_mode) == 0o700
    assert stat.S_IMODE(log_path.stat().st_mode) == 0o600
    assert stat.S_IMODE(Path(body["image_file"]).stat().st_mode) == 0o600
    assert recent.status_code == 200
    item = recent.json()["items"][0]
    assert item["metadata"]["capture_reason"] == "explicit_debug_opt_in"
