from __future__ import annotations

import json
import os
from pathlib import Path
import sys

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.config import get_settings  # noqa: E402
from backend.app.main import app  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


JPEG_BYTES = b"\xff\xd8\xff\xe0" + (b"0" * 16)


@pytest.fixture(scope="session", autouse=True)
def migrated_database() -> None:
    settings = get_settings()
    engine = create_engine(settings.database_url)
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except SQLAlchemyError as exc:
        pytest.skip(f"PostGIS test database is not reachable: {exc}")

    config = Config(str(ROOT / "backend" / "alembic.ini"))
    command.upgrade(config, "head")


@pytest.fixture
def client() -> ASGITestClient:
    return ASGITestClient(app)


def sample_v2_metadata(**overrides: object) -> dict[str, object]:
    metadata = {
        "schema_version": "detect.v2",
        "model_key": "custom_tactile",
        "source_model": "fake/custom-tactile-contract",
        "model_class_id": 2,
        "class_name": "tactile_damage_area",
        "category": "tactile",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "threshold_used": 0.25,
        "captured_at": "2026-05-22T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
        "trigger": "auto",
        "auto_reported": True,
    }
    metadata.update(overrides)
    return metadata


def post_report_v2(client: ASGITestClient, metadata: dict[str, object]):
    return client.post(
        "/reports/v2",
        data={"metadata": json.dumps(metadata)},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )


def test_create_report_v2_stores_allowed_tactile_damage(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata()

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == metadata["model_class_id"]
    assert body["class_name"] == "tactile_damage_area"
    assert body["source"] == "fake"
    assert body["metadata"] == metadata
    assert "fake_source" in body["review_flags"]


def test_create_report_v2_maps_non_fake_source_to_server(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        source_model="YOLO26s custom",
        class_name="damaged_tactile_block",
        model_class_id=1,
        captured_at="2026-05-22T12:20:00.000Z",
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == 1
    assert body["class_name"] == "damaged_tactile_block"
    assert body["source"] == "server"


@pytest.mark.parametrize(
    "class_name,model_class_id",
    [
        ("tactile_damage_area", 2),
        ("damaged_tactile_block", 1),
    ],
)
def test_create_report_v2_stores_voice_damage_without_auto_report(
    client: ASGITestClient,
    class_name: str,
    model_class_id: int,
) -> None:
    metadata = sample_v2_metadata(
        class_name=class_name,
        model_class_id=model_class_id,
        captured_at="2026-05-22T12:40:00.000Z",
        trigger="voice",
        auto_reported=False,
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == model_class_id
    assert body["class_name"] == class_name
    assert body["metadata"]["trigger"] == "voice"
    assert body["metadata"]["auto_reported"] is False
    assert body["metadata"] == metadata


@pytest.mark.parametrize(
    "metadata",
    [
        sample_v2_metadata(class_name="normal_tactile_block", model_class_id=0),
        sample_v2_metadata(model_key="coco_general", class_name="person", model_class_id=0, category="general_obstacle"),
        sample_v2_metadata(model_key="coco_general", class_name="car", model_class_id=1, category="general_obstacle"),
    ],
)
def test_create_report_v2_rejects_non_damage_targets(client: ASGITestClient, metadata: dict[str, object]) -> None:
    response = post_report_v2(client, metadata)

    assert response.status_code == 422


def test_create_report_v2_rejects_voice_non_damage_target(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        class_name="normal_tactile_block",
        model_class_id=0,
        trigger="voice",
        auto_reported=False,
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 422


def test_create_report_v2_reuses_duplicate_policy(client: ASGITestClient) -> None:
    first = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T13:00:00.000Z",
            gps={"latitude": 37.5, "longitude": 127.0, "accuracy_m": 10.0},
        ),
    )
    assert first.status_code == 201, first.text

    second = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T13:04:00.000Z",
            gps={"latitude": 37.50003, "longitude": 127.00003, "accuracy_m": 10.0},
        ),
    )

    assert second.status_code == 201, second.text
    assert first.json()["id"] in second.json()["duplicate_report_ids"]
