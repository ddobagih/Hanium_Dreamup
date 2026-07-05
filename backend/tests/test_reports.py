from __future__ import annotations

import json
import os
from pathlib import Path
import sys
from typing import Optional

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
PNG_BYTES = b"\x89PNG\r\n\x1a\n" + (b"0" * 16)
WEBP_BYTES = b"RIFF" + (b"0" * 4) + b"WEBP" + (b"0" * 16)


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


def sample_metadata(**overrides: object) -> dict[str, object]:
    metadata = {
        "class_id": 0,
        "class_name": "damaged_tactile_block",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "captured_at": "2026-05-12T12:00:00.000Z",
        "source": "fake",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
    }
    metadata.update(overrides)
    return metadata


def create_report(
    client: ASGITestClient,
    metadata: Optional[dict[str, object]] = None,
    filename: str = "sample.jpg",
    image_bytes: bytes = JPEG_BYTES,
    content_type: str = "image/jpeg",
) -> dict[str, object]:
    response = client.post(
        "/reports",
        data={"metadata": json.dumps(metadata or sample_metadata())},
        files={"image": (filename, image_bytes, content_type)},
    )
    assert response.status_code == 201, response.text
    return response.json()


def ids(response: object) -> set[str]:
    assert isinstance(response, list)
    return {str(report["id"]) for report in response}


def test_health(client: ASGITestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_create_get_list_and_update_report(client: ASGITestClient) -> None:
    created = create_report(client)
    report_id = created["id"]

    detail = client.get(f"/reports/{report_id}")
    assert detail.status_code == 200
    assert detail.json()["class_name"] == "damaged_tactile_block"
    assert detail.json()["location_quality"] == "high"
    assert "fake_source" in detail.json()["review_flags"]

    nearby = client.get("/reports", params={"lat": 37.5665, "lng": 126.978, "radius_m": 100})
    assert nearby.status_code == 200
    assert report_id in ids(nearby.json())

    patched = client.patch(f"/reports/{report_id}/status", json={"status": "reviewed"})
    assert patched.status_code == 200
    assert patched.json()["status"] == "reviewed"


def test_list_reports_filters(client: ASGITestClient) -> None:
    tactile = create_report(client)
    obstacle = create_report(
        client,
        sample_metadata(
            class_id=2,
            class_name="construction_obstacle",
            confidence=0.83,
            captured_at="2026-05-12T13:00:00.000Z",
            source="server",
            gps={"latitude": 37.57, "longitude": 126.98, "accuracy_m": 8.0},
        ),
    )
    client.patch(f"/reports/{obstacle['id']}/status", json={"status": "resolved"})

    by_status = client.get("/reports", params={"status": "resolved", "limit": 100})
    assert by_status.status_code == 200
    assert obstacle["id"] in ids(by_status.json())
    assert tactile["id"] not in ids(by_status.json())

    by_class = client.get("/reports", params={"class_name": "construction_obstacle", "limit": 100})
    assert by_class.status_code == 200
    assert obstacle["id"] in ids(by_class.json())
    assert tactile["id"] not in ids(by_class.json())

    by_source = client.get("/reports", params={"source": "server", "limit": 100})
    assert by_source.status_code == 200
    assert obstacle["id"] in ids(by_source.json())
    assert tactile["id"] not in ids(by_source.json())

    by_date = client.get("/reports", params={"created_from": "2026-01-01T00:00:00Z", "limit": 100})
    assert by_date.status_code == 200
    assert obstacle["id"] in ids(by_date.json())


def test_list_reports_limit_and_created_to_filter(client: ASGITestClient) -> None:
    created = create_report(client)

    limited = client.get("/reports", params={"limit": 1})
    assert limited.status_code == 200
    assert len(limited.json()) == 1

    by_created_to = client.get("/reports", params={"created_to": "2100-01-01T00:00:00Z", "limit": 100})
    assert by_created_to.status_code == 200
    assert created["id"] in ids(by_created_to.json())

    before_reports_existed = client.get("/reports", params={"created_to": "2000-01-01T00:00:00Z", "limit": 100})
    assert before_reports_existed.status_code == 200
    assert created["id"] not in ids(before_reports_existed.json())


@pytest.mark.parametrize(
    ("filename", "image_bytes", "content_type", "expected_suffix"),
    [
        ("sample.jpg", JPEG_BYTES, "image/jpeg", ".jpg"),
        ("sample.png", PNG_BYTES, "image/png", ".png"),
        ("sample.webp", WEBP_BYTES, "image/webp", ".webp"),
    ],
)
def test_create_report_accepts_supported_image_types(
    client: ASGITestClient,
    filename: str,
    image_bytes: bytes,
    content_type: str,
    expected_suffix: str,
) -> None:
    created = create_report(
        client,
        filename=filename,
        image_bytes=image_bytes,
        content_type=content_type,
    )

    assert created["image_content_type"] == content_type
    assert created["image_path"].endswith(expected_suffix)


def test_report_status_sequential_transition(client: ASGITestClient) -> None:
    created = create_report(client)
    report_id = created["id"]
    assert created["status"] == "new"

    reviewed = client.patch(f"/reports/{report_id}/status", json={"status": "reviewed"})
    assert reviewed.status_code == 200
    assert reviewed.json()["status"] == "reviewed"

    resolved = client.patch(f"/reports/{report_id}/status", json={"status": "resolved"})
    assert resolved.status_code == 200
    assert resolved.json()["status"] == "resolved"

    detail = client.get(f"/reports/{report_id}")
    assert detail.status_code == 200
    assert detail.json()["status"] == "resolved"


def test_report_quality_flags(client: ASGITestClient) -> None:
    created = create_report(
        client,
        sample_metadata(
            confidence=0.62,
            gps=None,
            heading=None,
        ),
    )

    assert created["location_quality"] == "missing"
    assert set(created["review_flags"]) >= {"fake_source", "low_confidence", "missing_location", "missing_heading"}


def test_duplicate_candidate_support(client: ASGITestClient) -> None:
    first = create_report(
        client,
        sample_metadata(
            class_id=3,
            class_name="pothole",
            captured_at="2026-05-12T15:00:00.000Z",
            gps={"latitude": 37.5, "longitude": 127.0, "accuracy_m": 10.0},
        ),
    )
    second = create_report(
        client,
        sample_metadata(
            class_id=3,
            class_name="pothole",
            captured_at="2026-05-12T15:00:30.000Z",
            gps={"latitude": 37.50003, "longitude": 127.00003, "accuracy_m": 10.0},
        ),
    )

    assert first["id"] in second["duplicate_report_ids"]
    assert first["id"] in second["metadata"]["duplicate_report_ids"]
    assert "duplicate_candidate" in second["review_flags"]
    assert "duplicate_candidate" in second["metadata"]["review_flags"]

    duplicate_check = client.get(
        "/reports/duplicate-check",
        params={
            "class_name": "pothole",
            "captured_at": "2026-05-12T15:00:20Z",
            "lat": 37.50002,
            "lng": 127.00002,
            "radius_m": 10,
            "minutes": 1,
        },
    )
    assert duplicate_check.status_code == 200
    assert first["id"] in duplicate_check.json()["duplicate_report_ids"]
    assert second["id"] in duplicate_check.json()["duplicate_report_ids"]


def test_requires_complete_radius_query(client: ASGITestClient) -> None:
    response = client.get("/reports", params={"lat": 37.5665, "lng": 126.978})
    assert response.status_code == 400


def test_rejects_unsupported_report_image_type(client: ASGITestClient) -> None:
    response = client.post(
        "/reports",
        data={"metadata": json.dumps(sample_metadata())},
        files={"image": ("sample.txt", b"not an image", "text/plain")},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "unsupported_image_type"


def test_rejects_report_image_extension_mismatch(client: ASGITestClient) -> None:
    response = client.post(
        "/reports",
        data={"metadata": json.dumps(sample_metadata())},
        files={"image": ("sample.png", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "image_extension_mismatch"


def test_rejects_report_image_content_mismatch(client: ASGITestClient) -> None:
    response = client.post(
        "/reports",
        data={"metadata": json.dumps(sample_metadata())},
        files={"image": ("sample.jpg", b"not an actual jpeg", "image/jpeg")},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "image_content_mismatch"


def test_rejects_empty_report_image(client: ASGITestClient) -> None:
    response = client.post(
        "/reports",
        data={"metadata": json.dumps(sample_metadata())},
        files={"image": ("sample.jpg", b"", "image/jpeg")},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "empty_image"


def test_rejects_report_image_over_size_limit(client: ASGITestClient) -> None:
    settings = get_settings()
    previous_limit = settings.max_upload_bytes
    settings.max_upload_bytes = 8

    try:
        response = client.post(
            "/reports",
            data={"metadata": json.dumps(sample_metadata())},
            files={"image": ("sample.jpg", b"x" * 9, "image/jpeg")},
        )
    finally:
        settings.max_upload_bytes = previous_limit

    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "upload_too_large"


def test_rejects_mismatched_class(client: ASGITestClient) -> None:
    metadata = sample_metadata()
    metadata["class_name"] = "pothole"

    response = client.post(
        "/reports",
        data={"metadata": json.dumps(metadata)},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )
    assert response.status_code == 422
