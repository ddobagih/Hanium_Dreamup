from __future__ import annotations

import asyncio
from collections.abc import Callable
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
import json
import io
import os
from pathlib import Path
import sqlite3
import stat
import sys
from threading import Event
import uuid

from fastapi import FastAPI
import httpx
import pytest
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.main import app, settings  # noqa: E402
from backend.app import android_debug_storage  # noqa: E402
from backend.app.api import android_debug  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402

_JPEG_OUTPUT = io.BytesIO()
Image.new("RGB", (4, 4), color=(180, 160, 40)).save(_JPEG_OUTPUT, format="JPEG")
JPEG_BYTES = _JPEG_OUTPUT.getvalue()
_MISSING = object()


@contextmanager
def _debug_settings(**overrides: object):
    previous = {name: getattr(settings, name, _MISSING) for name in overrides}
    for name, value in overrides.items():
        setattr(settings, name, value)
    try:
        yield
    finally:
        for name, value in previous.items():
            if value is _MISSING:
                delattr(settings, name)
            else:
                setattr(settings, name, value)


def _frame_capture_metadata(**entry_overrides: object) -> dict[str, object]:
    """Mirror the Android debug uploader envelope and MainActivity capture entry."""

    entry: dict[str, object] = {
        "frame_timestamp_ms": 1234,
        "camera_image_width": 640,
        "camera_image_height": 480,
        "detect_duration_ms": 90,
        "detection_count": 1,
        "capture_policy": "manual_jpeg_no_gps_no_depth_raw_full_detector",
        "force_custom_tactile": False,
        "detector_completed_models": ["unified_walksafe"],
        "detector_skipped_models": [],
        "detector_partial": False,
        "detector_loaded_model_key": "unified_walksafe",
        "detector_model_fallback_used": False,
        "detector_model_load_reason": "unified_loaded",
        "detector_model_key": "unified_walksafe",
        "detector_model_inference_ms": 40,
        "detections": [
            {
                "class_name": "person",
                "confidence": 0.91,
                "bbox": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
            }
        ],
        "top_detection_class_name": "person",
        "top_detection_confidence": 0.91,
        "top_detection_bbox": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
        "top_detection_threshold_used": 0.5,
    }
    entry.update(entry_overrides)
    return {
        "schema_version": "android.frame_capture.v1",
        "session_id": "android-frame-1",
        "device_model": None,
        "android_version": "16",
        "app_version_name": "0.1.0",
        "entry": entry,
    }


def _post_frame_capture(metadata: object) -> httpx.Response:
    return ASGITestClient(app).post(
        "/android/debug/frame-captures",
        data={"metadata": metadata if isinstance(metadata, str) else json.dumps(metadata)},
        files={"image": ("frame.jpg", JPEG_BYTES, "image/jpeg")},
    )


def _raw_rows(root: Path, kind: str) -> list[dict[str, object]]:
    """Read stored rows without going through the storage API, which prunes first."""

    with sqlite3.connect(root / android_debug_storage.DB_FILENAME) as connection:
        return [
            json.loads(row[0])
            for row in connection.execute(
                "SELECT payload FROM records WHERE kind = ? ORDER BY seq", (kind,)
            )
        ]


def test_android_depth_debug_logs_store_metadata_only_in_private_database(tmp_path: Path) -> None:
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
    assert response.json() == {"ok": True, "stored": 1}
    database = tmp_path / android_debug_storage.DB_FILENAME
    assert database.exists()
    assert not (tmp_path / android_debug_storage.DEPTH_JSONL_FILENAME).exists()
    assert stat.S_IMODE(tmp_path.stat().st_mode) == 0o700
    assert stat.S_IMODE(database.stat().st_mode) == 0o600
    item_text = json.dumps(recent.json()["items"])
    assert "person" in item_text
    assert "rgb_frame" not in item_text
    assert "raw_depth_ref" not in item_text
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


def _depth_record(received_at: datetime, number: int) -> dict[str, object]:
    return {
        "received_at": received_at.isoformat(),
        "schema_version": "android.depth_debug.v1",
        "session_id": "legacy-session",
        "entry": {"frame_timestamp_ms": number, "detection_count": 0},
    }


def test_android_debug_legacy_import_is_atomic_and_idempotent(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    previous_dir = settings.android_debug_log_dir
    settings.android_debug_log_dir = tmp_path
    now = datetime.now(UTC)
    records = [_depth_record(now, index) for index in range(3)]
    legacy = tmp_path / android_debug_storage.DEPTH_JSONL_FILENAME
    legacy.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
    legacy.chmod(0o600)
    original_bytes = legacy.read_bytes()
    original_insert = android_debug_storage.AndroidDebugStorage._insert_record
    calls = 0

    def interrupted(connection: sqlite3.Connection, kind: str, record: dict[str, object]) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise RuntimeError("simulated import interruption")
        original_insert(connection, kind, record)

    try:
        with monkeypatch.context() as context:
            context.setattr(android_debug_storage.AndroidDebugStorage, "_insert_record", staticmethod(interrupted))
            with pytest.raises(RuntimeError, match="simulated import interruption"):
                android_debug_storage.AndroidDebugStorage(settings).recent_depth_logs(10)
        restarted = android_debug_storage.AndroidDebugStorage(settings)
        assert [item["entry"]["frame_timestamp_ms"] for item in restarted.recent_depth_logs(10)] == [0, 1, 2]
        assert len(android_debug_storage.AndroidDebugStorage(settings).recent_depth_logs(10)) == 3
        with sqlite3.connect(tmp_path / android_debug_storage.DB_FILENAME) as connection:
            assert connection.execute("SELECT count(*) FROM records").fetchone()[0] == 3
            assert connection.execute(
                "SELECT count(*) FROM meta WHERE key = 'legacy_import_complete'"
            ).fetchone()[0] == 1
        assert legacy.read_bytes() == original_bytes
    finally:
        settings.android_debug_log_dir = previous_dir


def test_android_debug_large_legacy_log_is_not_read_or_rewritten_after_import(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    previous_dir = settings.android_debug_log_dir
    settings.android_debug_log_dir = tmp_path
    now = datetime.now(UTC)
    legacy = tmp_path / android_debug_storage.DEPTH_JSONL_FILENAME
    legacy.write_text(
        "".join(json.dumps(_depth_record(now, index)) + "\n" for index in range(4000)),
        encoding="utf-8",
    )
    legacy.chmod(0o600)
    original_stat = legacy.stat()
    storage = android_debug_storage.AndroidDebugStorage(settings)
    try:
        assert [item["entry"]["frame_timestamp_ms"] for item in storage.recent_depth_logs(3)] == [3997, 3998, 3999]
        legacy_opens = 0
        original_open = android_debug_storage.os.open

        def counted_open(path: object, *args: object, **kwargs: object) -> int:
            nonlocal legacy_opens
            if isinstance(path, (str, bytes, os.PathLike)) and Path(path).name == legacy.name:
                legacy_opens += 1
            return original_open(path, *args, **kwargs)

        with monkeypatch.context() as context:
            context.setattr(android_debug_storage.os, "open", counted_open)
            for _ in range(3):
                assert len(android_debug_storage.AndroidDebugStorage(settings).recent_depth_logs(2)) == 2
                assert storage.append_depth_logs([_depth_record(now, 4000)]) == 1
        assert legacy_opens == 0
        current_stat = legacy.stat()
        assert (current_stat.st_size, current_stat.st_mtime_ns, current_stat.st_ino) == (
            original_stat.st_size, original_stat.st_mtime_ns, original_stat.st_ino
        )
        with sqlite3.connect(tmp_path / android_debug_storage.DB_FILENAME) as connection:
            plan = connection.execute(
                "EXPLAIN QUERY PLAN SELECT payload FROM records WHERE kind = 'depth' ORDER BY seq DESC LIMIT 2"
            ).fetchall()
            assert any("records_recent" in step[3] for step in plan)
    finally:
        settings.android_debug_log_dir = previous_dir


def test_android_debug_retention_uses_exact_utc_boundary(tmp_path: Path) -> None:
    previous_dir = settings.android_debug_log_dir
    settings.android_debug_log_dir = tmp_path
    storage = android_debug_storage.AndroidDebugStorage(settings)
    now = datetime.now(UTC) + timedelta(days=1)
    cutoff = now - timedelta(days=settings.android_debug_log_retention_days)
    try:
        storage.append_depth_logs([
            _depth_record(cutoff - timedelta(microseconds=1), 1),
            _depth_record(cutoff, 2),
        ])
        storage.prune(now=now)
        assert [item["entry"]["frame_timestamp_ms"] for item in storage.recent_depth_logs(10)] == [2]
    finally:
        settings.android_debug_log_dir = previous_dir


def test_android_debug_capture_cleanup_recovers_after_interrupted_delete(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    previous_dir = settings.android_debug_log_dir
    settings.android_debug_log_dir = tmp_path
    storage = android_debug_storage.AndroidDebugStorage(settings)
    capture_id = str(uuid.uuid4())
    image = tmp_path / "frame_captures" / f"{capture_id}.jpg"
    try:
        storage.append_frame_capture(capture_id, JPEG_BYTES, "image/jpeg", {"reason": "test"})
        assert image.exists()
        with monkeypatch.context() as context:
            context.setattr(android_debug_storage.AndroidDebugStorage, "_remove_image", lambda self, root, path: None)
            storage.prune(now=datetime.now(UTC) + timedelta(days=settings.android_debug_log_retention_days + 1))
        assert image.exists()
        assert android_debug_storage.AndroidDebugStorage(settings).recent_frame_captures(10) == []
        assert not image.exists()
        assert stat.S_IMODE((tmp_path / "frame_captures").stat().st_mode) == 0o700
    finally:
        settings.android_debug_log_dir = previous_dir


def test_android_debug_rejects_legacy_writer_after_migration(tmp_path: Path) -> None:
    previous_dir = settings.android_debug_log_dir
    settings.android_debug_log_dir = tmp_path
    legacy = tmp_path / android_debug_storage.DEPTH_JSONL_FILENAME
    legacy.write_text(json.dumps(_depth_record(datetime.now(UTC), 1)) + "\n", encoding="utf-8")
    legacy.chmod(0o600)
    try:
        storage = android_debug_storage.AndroidDebugStorage(settings)
        assert len(storage.recent_depth_logs(10)) == 1
        with legacy.open("a", encoding="utf-8") as output:
            output.write(json.dumps(_depth_record(datetime.now(UTC), 2)) + "\n")
        with pytest.raises(OSError, match="legacy debug log changed"):
            storage.recent_depth_logs(10)
    finally:
        settings.android_debug_log_dir = previous_dir

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
    assert not (tmp_path / android_debug_storage.DB_FILENAME).exists()


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


@pytest.mark.parametrize("declared_length", [None, "1"])
def test_android_depth_debug_logs_reject_received_bytes_before_json_parsing(
    tmp_path: Path, declared_length: str | None
) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_max_bytes = settings.max_android_debug_log_bytes
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.max_android_debug_log_bytes = 128
    settings.android_debug_log_enabled = True
    headers = {"content-type": "application/json"}
    if declared_length is not None:
        headers["content-length"] = declared_length

    async def oversized_invalid_json():
        yield b'{"entries":['
        yield b" " * 200

    try:
        response = ASGITestClient(app).post(
            "/android/debug/depth-logs",
            headers=headers,
            content=oversized_invalid_json(),
        )
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.max_android_debug_log_bytes = previous_max_bytes
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 413
    assert response.json()["detail"] == {"code": "android_debug_log_too_large", "max_bytes": 128}
    assert not (tmp_path / android_debug_storage.DB_FILENAME).exists()


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
    assert not (tmp_path / android_debug_storage.DB_FILENAME).exists()


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
    assert not (tmp_path / android_debug_storage.DB_FILENAME).exists()


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
    assert not (tmp_path / android_debug_storage.DB_FILENAME).exists()


def test_android_frame_captures_do_not_use_depth_body_limit(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_max_bytes = settings.max_android_debug_log_bytes
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.max_android_debug_log_bytes = 128
    settings.android_debug_log_enabled = True
    compact_metadata = json.dumps(
        {
            "schema_version": "android.frame_capture.v1",
            "session_id": "s",
            "entry": {"frame_timestamp_ms": 1, "detection_count": 0},
        },
        separators=(",", ":"),
    )
    assert len(compact_metadata.encode("utf-8")) <= 128

    try:
        response = _post_frame_capture(compact_metadata)
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.max_android_debug_log_bytes = previous_max_bytes
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 200
    assert response.json()["ok"] is True


def test_android_frame_captures_store_image_and_metadata_when_enabled(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.android_debug_log_enabled = True

    try:
        response = _post_frame_capture(_frame_capture_metadata(capture_policy="explicit_debug_opt_in"))
        recent = ASGITestClient(app).get("/android/debug/frame-captures/recent")
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.android_debug_log_enabled = previous_enabled

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True
    assert set(body) == {"ok", "capture_id"}
    capture_files = list((tmp_path / "frame_captures").iterdir())
    assert len(capture_files) == 1
    assert capture_files[0].stem == body["capture_id"]
    database = tmp_path / android_debug_storage.DB_FILENAME
    assert database.exists()
    assert not (tmp_path / android_debug_storage.CAPTURE_JSONL_FILENAME).exists()
    assert stat.S_IMODE(tmp_path.stat().st_mode) == 0o700
    assert stat.S_IMODE(database.stat().st_mode) == 0o600
    assert stat.S_IMODE(capture_files[0].stat().st_mode) == 0o600
    assert recent.status_code == 200
    item = recent.json()["items"][0]
    assert "image_file" not in item
    assert item["capture_id"] == body["capture_id"]
    assert item["metadata"]["entry"]["capture_policy"] == "explicit_debug_opt_in"


def test_android_debug_storage_refuses_symlinked_directories_and_database(tmp_path: Path) -> None:
    previous_log_dir = settings.android_debug_log_dir
    debug_root = tmp_path / "debug"
    debug_root.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    survivor = outside / "unrelated.jpg"
    survivor.write_bytes(b"outside debug storage")
    (debug_root / "frame_captures").symlink_to(outside, target_is_directory=True)

    try:
        settings.android_debug_log_dir = debug_root
        with pytest.raises(OSError):
            android_debug_storage.AndroidDebugStorage(settings).recent_depth_logs(1)
        assert survivor.read_bytes() == b"outside debug storage"

        linked_root = tmp_path / "linked-debug"
        linked_root.symlink_to(outside, target_is_directory=True)
        settings.android_debug_log_dir = linked_root
        with pytest.raises(OSError):
            android_debug_storage.AndroidDebugStorage(settings).recent_depth_logs(1)
        assert survivor.read_bytes() == b"outside debug storage"

        database_root = tmp_path / "database-debug"
        database_root.mkdir()
        (database_root / android_debug_storage.DB_FILENAME).symlink_to(survivor)
        settings.android_debug_log_dir = database_root
        with pytest.raises(OSError):
            android_debug_storage.AndroidDebugStorage(settings).recent_depth_logs(1)
        assert survivor.read_bytes() == b"outside debug storage"
    finally:
        settings.android_debug_log_dir = previous_log_dir


def test_android_debug_file_work_does_not_block_unrelated_async_endpoint(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    previous_log_dir = settings.android_debug_log_dir
    previous_enabled = settings.android_debug_log_enabled
    settings.android_debug_log_dir = tmp_path
    settings.android_debug_log_enabled = True
    entered_append = Event()
    release_append = Event()
    original_insert = android_debug_storage.AndroidDebugStorage._insert_record

    def blocked_insert(connection: sqlite3.Connection, kind: str, record: dict[str, object]) -> None:
        if kind == "depth":
            entered_append.set()
            if not release_append.wait(timeout=3):
                raise TimeoutError("debug database insert was not released")
        original_insert(connection, kind, record)

    monkeypatch.setattr(android_debug_storage.AndroidDebugStorage, "_insert_record", staticmethod(blocked_insert))
    isolated_app = FastAPI()
    isolated_app.include_router(android_debug.create_router(settings))

    @isolated_app.get("/loop-probe")
    async def loop_probe() -> dict[str, bool]:
        return {"progressed": True}

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

    async def exercise() -> None:
        transport = httpx.ASGITransport(app=isolated_app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            debug_request = asyncio.create_task(client.post("/android/debug/depth-logs", json=payload))
            try:
                assert await asyncio.to_thread(entered_append.wait, 1)
                assert not debug_request.done()
                probe = await asyncio.wait_for(client.get("/loop-probe"), timeout=1)
                assert probe.status_code == 200
                assert probe.json() == {"progressed": True}
                assert not debug_request.done()
            finally:
                release_append.set()
                await asyncio.gather(debug_request, return_exceptions=True)

            response = debug_request.result()
            assert response.status_code == 200
            assert response.json() == {"ok": True, "stored": 1}
            recent = await client.get("/android/debug/depth-logs/recent")
            assert recent.status_code == 200
            assert recent.json()["items"][0]["entry"]["frame_timestamp_ms"] == 1234

    try:
        asyncio.run(exercise())
    finally:
        settings.android_debug_log_dir = previous_log_dir
        settings.android_debug_log_enabled = previous_enabled


# S1617-30: frame-capture metadata is a versioned allow-listed DTO, rejected before storage.
@pytest.mark.parametrize(
    "metadata",
    [
        {**_frame_capture_metadata(), "latitude": 37.5},
        _frame_capture_metadata(gps_latitude=37.5, gps_longitude=127.0),
        _frame_capture_metadata(search_query="home address"),
        _frame_capture_metadata(access_token="secret-token"),
        _frame_capture_metadata(
            detections=[
                {
                    "class_name": "person",
                    "confidence": 0.9,
                    "bbox": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
                    "route_id": "route-1",
                }
            ]
        ),
        {**_frame_capture_metadata(), "schema_version": "android.frame_capture.v2"},
        {key: value for key, value in _frame_capture_metadata().items() if key != "entry"},
        {"schema_version": "android.frame_capture.v1", "capture_reason": "explicit_debug_opt_in"},
    ],
    ids=[
        "top_level_location",
        "entry_gps",
        "entry_search_query",
        "entry_token",
        "nested_detection_route",
        "unknown_schema_version",
        "missing_entry",
        "free_form_object",
    ],
)
def test_android_frame_capture_rejects_metadata_outside_allowlist_before_storage(
    tmp_path: Path, metadata: dict[str, object]
) -> None:
    with _debug_settings(android_debug_log_dir=tmp_path, android_debug_log_enabled=True):
        response = _post_frame_capture(metadata)

    assert response.status_code == 422
    assert response.json()["detail"] == {"code": "android_frame_capture_metadata_not_allowed"}
    assert "secret-token" not in response.text
    assert not (tmp_path / android_debug_storage.DB_FILENAME).exists()
    assert not (tmp_path / "frame_captures").exists()


def test_android_frame_capture_stores_only_the_validated_metadata_shape(tmp_path: Path) -> None:
    metadata = _frame_capture_metadata()
    with _debug_settings(android_debug_log_dir=tmp_path, android_debug_log_enabled=True):
        response = _post_frame_capture(metadata)
        recent = ASGITestClient(app).get("/android/debug/frame-captures/recent")

    assert response.status_code == 200
    stored = recent.json()["items"][0]["metadata"]
    expected = {key: value for key, value in metadata.items() if value is not None}
    assert stored == expected
    assert _raw_rows(tmp_path, "capture")[0]["metadata"] == expected


# S1617-28: total bytes, frame-capture count and daily growth are bounded.
def _padded_depth_record(received_at: datetime, number: int, padding: int = 2_000) -> dict[str, object]:
    record = _depth_record(received_at, number)
    record["entry"] = {**record["entry"], "fallback_reason": "x" * padding}
    return record


def test_android_debug_total_bytes_quota_rejects_new_writes_and_keeps_existing(tmp_path: Path) -> None:
    cap = 128 * 1024
    with _debug_settings(
        android_debug_log_dir=tmp_path,
        android_debug_max_total_bytes=cap,
        android_debug_max_daily_bytes=64 * 1024 * 1024,
    ):
        storage = android_debug_storage.AndroidDebugStorage(settings)
        now = datetime.now(UTC)
        stored = 0
        with pytest.raises(android_debug_storage.AndroidDebugQuotaExceeded) as rejected:
            for number in range(1_000):
                storage.append_depth_logs([_padded_depth_record(now, number)])
                stored += 1
        assert rejected.value.quota == "total_bytes"
        assert 0 < stored < 1_000
        assert len(_raw_rows(tmp_path, "depth")) == stored
        database = tmp_path / android_debug_storage.DB_FILENAME
        assert database.stat().st_size <= cap + 8 * 4096

        storage.prune(now=now + timedelta(days=settings.android_debug_log_retention_days + 1))
        assert storage.append_depth_logs([_padded_depth_record(datetime.now(UTC), 1)]) == 1


def test_android_debug_frame_capture_count_quota_is_checked_before_image_write(tmp_path: Path) -> None:
    with _debug_settings(
        android_debug_log_dir=tmp_path,
        android_debug_log_enabled=True,
        android_debug_max_frame_captures=2,
    ):
        responses = [_post_frame_capture(_frame_capture_metadata()) for _ in range(3)]

    assert [response.status_code for response in responses] == [200, 200, 507]
    assert responses[2].json()["detail"] == {
        "code": "android_debug_storage_quota_exceeded",
        "quota": "frame_captures",
    }
    assert len(list((tmp_path / "frame_captures").iterdir())) == 2
    assert len(_raw_rows(tmp_path, "capture")) == 2


def test_android_debug_daily_growth_quota_counts_only_the_current_utc_day(tmp_path: Path) -> None:
    with _debug_settings(
        android_debug_log_dir=tmp_path,
        android_debug_max_daily_bytes=8 * 1024,
        android_debug_max_total_bytes=64 * 1024 * 1024,
    ):
        storage = android_debug_storage.AndroidDebugStorage(settings)
        stored = 0
        with pytest.raises(android_debug_storage.AndroidDebugQuotaExceeded) as rejected:
            for number in range(100):
                storage.append_depth_logs([_padded_depth_record(datetime.now(UTC), number)])
                stored += 1
        assert rejected.value.quota == "daily_bytes"
        assert 0 < stored < 100
        database = tmp_path / android_debug_storage.DB_FILENAME
        with sqlite3.connect(database) as connection:
            connection.execute("UPDATE daily_usage SET day = day - 1")
        assert storage.append_depth_logs([_padded_depth_record(datetime.now(UTC), 100)]) == 1
        storage.prune()
        with sqlite3.connect(database) as connection:
            days = [row[0] for row in connection.execute("SELECT day FROM daily_usage ORDER BY day")]
        assert len(days) == 1


def test_android_depth_debug_logs_map_storage_quota_to_507(tmp_path: Path) -> None:
    payload = {
        "schema_version": "android.depth_debug.v1",
        "session_id": "session-1",
        "entries": [{"frame_timestamp_ms": 1, "detection_count": 0, "detections_used_for_depth": False}],
    }
    with _debug_settings(
        android_debug_log_dir=tmp_path,
        android_debug_log_enabled=True,
        android_debug_max_daily_bytes=16,
    ):
        response = ASGITestClient(app).post("/android/debug/depth-logs", json=payload)

    assert response.status_code == 507
    assert response.json()["detail"] == {
        "code": "android_debug_storage_quota_exceeded",
        "quota": "daily_bytes",
    }
    assert _raw_rows(tmp_path, "depth") == []


# S1617-29: retention cleanup is independent of the collection switch.
def _run_router_lifespan(settle: Callable[[], bool], timeout_seconds: float = 3.0) -> bool:
    isolated_app = FastAPI()
    isolated_app.include_router(android_debug.create_router(settings))

    async def exercise() -> bool:
        async with isolated_app.router.lifespan_context(isolated_app):
            deadline = asyncio.get_running_loop().time() + timeout_seconds
            while asyncio.get_running_loop().time() < deadline:
                if settle():
                    return True
                await asyncio.sleep(0.02)
            return settle()

    return asyncio.run(exercise())


def test_android_debug_retention_runs_while_collection_is_disabled(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(android_debug, "RETENTION_SWEEP_SECONDS", 0.05, raising=False)
    with _debug_settings(android_debug_log_dir=tmp_path, android_debug_log_enabled=True):
        storage = android_debug_storage.AndroidDebugStorage(settings)
        expired_at = datetime.now(UTC) - timedelta(days=settings.android_debug_log_retention_days + 1)
        storage.append_depth_logs([_depth_record(expired_at, 1), _depth_record(datetime.now(UTC), 2)])
        capture_id = str(uuid.uuid4())
        storage.append_frame_capture(capture_id, JPEG_BYTES, "image/jpeg", {"reason": "test"})
        image = tmp_path / "frame_captures" / f"{capture_id}.jpg"
        with sqlite3.connect(tmp_path / android_debug_storage.DB_FILENAME) as connection:
            connection.execute(
                "UPDATE records SET received_us = ? WHERE kind = 'capture'",
                (android_debug_storage._timestamp_us(expired_at),),
            )
        assert image.exists()

    with _debug_settings(android_debug_log_dir=tmp_path, android_debug_log_enabled=False):
        settled = _run_router_lifespan(
            lambda: not image.exists()
            and [row["entry"]["frame_timestamp_ms"] for row in _raw_rows(tmp_path, "depth")] == [2]
        )

    assert settled
    assert _raw_rows(tmp_path, "capture") == []


def test_android_debug_retention_sweep_never_creates_missing_storage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(android_debug, "RETENTION_SWEEP_SECONDS", 0.05, raising=False)
    root = tmp_path / "absent"
    with _debug_settings(android_debug_log_dir=root, android_debug_log_enabled=False):
        assert android_debug_storage.AndroidDebugStorage(settings).prune_existing() is False
        _run_router_lifespan(lambda: False, timeout_seconds=0.2)
    assert not root.exists()


def test_android_debug_retention_sweep_failure_keeps_app_lifespan_running(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(android_debug, "RETENTION_SWEEP_SECONDS", 0.05, raising=False)
    real_root = tmp_path / "real"
    real_root.mkdir(mode=0o700)
    (real_root / android_debug_storage.DB_FILENAME).write_bytes(b"")
    linked_root = tmp_path / "linked"
    linked_root.symlink_to(real_root, target_is_directory=True)
    attempts = 0
    original = android_debug_storage.AndroidDebugStorage.prune_existing

    def counted(self: android_debug_storage.AndroidDebugStorage, **kwargs: object) -> bool:
        nonlocal attempts
        attempts += 1
        return original(self, **kwargs)

    monkeypatch.setattr(android_debug_storage.AndroidDebugStorage, "prune_existing", counted)
    with _debug_settings(android_debug_log_dir=linked_root, android_debug_log_enabled=False):
        assert _run_router_lifespan(lambda: attempts >= 2)
    assert (real_root / android_debug_storage.DB_FILENAME).read_bytes() == b""


# S1617-27: separate worker processes share one debug store without lost or torn writes.
_MULTIPROCESS_WRITER = r"""
import sys
import types
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, sys.argv[1])
from backend.app.android_debug_storage import AndroidDebugStorage

settings = types.SimpleNamespace(
    android_debug_log_dir=Path(sys.argv[2]),
    android_debug_log_retention_days=7,
    android_debug_max_total_bytes=1 << 30,
    android_debug_max_frame_captures=1000,
    android_debug_max_daily_bytes=1 << 30,
)
storage = AndroidDebugStorage(settings)
for number in range(int(sys.argv[4])):
    storage.append_depth_logs([{
        "received_at": datetime.now(UTC).isoformat(),
        "schema_version": "android.depth_debug.v1",
        "session_id": sys.argv[3],
        "entry": {"frame_timestamp_ms": number, "detection_count": 0},
    }])
    if number % 25 == 0:
        storage.prune()
"""


def test_android_debug_store_is_consistent_across_worker_processes(tmp_path: Path) -> None:
    import subprocess

    per_process = 150
    writers = [
        subprocess.Popen(
            [sys.executable, "-B", "-c", _MULTIPROCESS_WRITER, str(ROOT), str(tmp_path), f"worker-{index}", str(per_process)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        for index in range(3)
    ]
    results = [writer.communicate(timeout=120) for writer in writers]
    assert [writer.returncode for writer in writers] == [0, 0, 0], [err.decode()[-2000:] for _, err in results]

    rows = _raw_rows(tmp_path, "depth")
    for index in range(3):
        numbers = [row["entry"]["frame_timestamp_ms"] for row in rows if row["session_id"] == f"worker-{index}"]
        assert numbers == list(range(per_process))
    with sqlite3.connect(tmp_path / android_debug_storage.DB_FILENAME) as connection:
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert connection.execute("SELECT count(*) FROM daily_usage").fetchone()[0] == 1


def test_frame_capture_metadata_accepts_producer_maximum_detections_and_negative_duration() -> None:
    """The detector parser allows 300 detections and the device duration is a wall-clock delta."""

    from backend.app.schemas import AndroidDebugFrameCaptureMetadata

    metadata = _frame_capture_metadata(detection_count=300, detect_duration_ms=-5)
    validated = AndroidDebugFrameCaptureMetadata.model_validate(metadata)
    assert validated.model_dump(mode="json", exclude_none=True)["entry"]["detection_count"] == 300
    with pytest.raises(Exception):
        AndroidDebugFrameCaptureMetadata.model_validate(_frame_capture_metadata(detection_count=301))
