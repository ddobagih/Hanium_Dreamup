from __future__ import annotations

import json
import io
import fcntl
import os
from pathlib import Path
import sys
from typing import Optional
import uuid

import pytest
from alembic import command
from alembic.config import Config
from fastapi import HTTPException
from sqlalchemy import create_engine, select, text
from sqlalchemy.exc import SQLAlchemyError
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from backend.app.config import get_settings  # noqa: E402
from backend.app.database import SessionLocal  # noqa: E402
from backend.app.main import app, settings as app_settings  # noqa: E402
from backend.app.models import (  # noqa: E402
    Report,
    ReportImageObject,
    ReportReadAudit,
    ReportStatusAudit,
)
from asgi_client import ASGITestClient  # noqa: E402


def encoded_image(format_name: str) -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (4, 4), color=(180, 160, 40)).save(output, format=format_name)
    return output.getvalue()


JPEG_BYTES = encoded_image("JPEG")
PNG_BYTES = encoded_image("PNG")
WEBP_BYTES = encoded_image("WEBP")


@pytest.fixture(scope="session", autouse=True)
def migrated_database() -> None:
    settings = get_settings()
    engine = create_engine(settings.database_url)
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except SQLAlchemyError as exc:
        pytest.fail(f"PostGIS test database is not reachable: {exc}", pytrace=False)

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


def test_sensitive_reads_persist_actor_resource_time_and_purpose(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    created = create_report(client)
    actor_id = "audit.reader@example.com"
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", "field-token-for-read-audit-tests-123456")
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-read-audit-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_environment", "development")
    monkeypatch.setattr(app_settings, "allow_insecure_local_dev", True)
    base_headers = {
        "x-walksafe-admin-token": app_settings.admin_token,
        "x-walksafe-actor-id": actor_id,
    }

    report_list = client.get(
        "/reports",
        headers={**base_headers, "x-walksafe-read-purpose": "admin_report_list"},
    )
    report_detail = client.get(
        f"/reports/{created['id']}",
        headers={**base_headers, "x-walksafe-read-purpose": "admin_report_detail"},
    )
    report_summary = client.get(
        "/reports/summary",
        headers={**base_headers, "x-walksafe-read-purpose": "admin_report_summary"},
    )
    report_image = client.get(
        str(created["image_path"]),
        headers={
            **base_headers,
            "x-walksafe-read-purpose": "admin_report_image",
            "x-walksafe-original-access-grant": "A" * 43,
        },
    )
    duplicate_check = client.get(
        "/reports/duplicate-check",
        params={
            "class_name": created["class_name"],
            "captured_at": created["captured_at"],
            "lat": created["gps"]["latitude"],
            "lng": created["gps"]["longitude"],
        },
        headers={**base_headers, "x-walksafe-read-purpose": "admin_report_duplicate_check"},
    )

    assert report_list.status_code == 200
    assert report_detail.status_code == 200
    assert report_summary.status_code == 200
    assert report_image.status_code == 503
    assert report_image.json()["detail"]["code"] == "admin_security_required"
    assert duplicate_check.status_code == 200
    with SessionLocal() as db:
        audits = list(
            db.scalars(
                select(ReportReadAudit)
                .where(ReportReadAudit.actor_id == actor_id)
                .order_by(ReportReadAudit.created_at, ReportReadAudit.id)
            )
        )
    assert [(audit.resource_type, audit.purpose) for audit in audits] == [
        ("report_list", "admin_report_list"),
        ("report_detail", "admin_report_detail"),
        ("report_list", "admin_report_summary"),
        ("report_duplicate_check", "admin_report_duplicate_check"),
    ]
    assert audits[0].resource_id == "reports"
    assert audits[1].resource_id == created["id"]
    assert audits[2].resource_id == "reports/summary"
    assert audits[3].resource_id == "reports/duplicate-check"
    assert audits[3].details["result_count"] == duplicate_check.json()["duplicate_count"]
    assert "lat" not in audits[3].details and "lng" not in audits[3].details
    assert all(audit.created_at is not None for audit in audits)


def test_report_summary_is_withheld_when_read_audit_cannot_be_persisted(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    create_report(client)
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", "field-token-for-read-audit-tests-123456")
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-read-audit-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_environment", "development")
    monkeypatch.setattr(app_settings, "allow_insecure_local_dev", True)

    def fail_audit(*_args, **_kwargs) -> None:
        raise HTTPException(
            status_code=503,
            detail={"code": "read_audit_unavailable"},
        )

    monkeypatch.setattr("backend.app.api.reports.persist_report_read_audit", fail_audit)
    response = client.get(
        "/reports/summary",
        headers={
            "x-walksafe-admin-token": app_settings.admin_token,
            "x-walksafe-actor-id": "audit.reader@example.com",
            "x-walksafe-read-purpose": "admin_report_summary",
        },
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "read_audit_unavailable"


def test_duplicate_check_is_withheld_when_read_audit_cannot_be_persisted(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    created = create_report(client)
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", "field-token-for-read-audit-tests-123456")
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-read-audit-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_environment", "development")
    monkeypatch.setattr(app_settings, "allow_insecure_local_dev", True)

    def fail_audit(*_args, **_kwargs) -> None:
        raise HTTPException(status_code=503, detail={"code": "read_audit_unavailable"})

    monkeypatch.setattr("backend.app.api.reports.persist_report_read_audit", fail_audit)
    response = client.get(
        "/reports/duplicate-check",
        params={
            "class_name": created["class_name"],
            "captured_at": created["captured_at"],
            "lat": created["gps"]["latitude"],
            "lng": created["gps"]["longitude"],
        },
        headers={
            "x-walksafe-admin-token": app_settings.admin_token,
            "x-walksafe-actor-id": "audit.reader@example.com",
            "x-walksafe-read-purpose": "admin_report_duplicate_check",
        },
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "read_audit_unavailable"


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


def test_list_reports_supports_stable_offset_pagination(client: ASGITestClient) -> None:
    created_ids = {create_report(client)["id"] for _ in range(3)}

    first = client.get("/reports", params={"limit": 2, "offset": 0})
    second = client.get("/reports", params={"limit": 2, "offset": 2})

    assert first.status_code == 200
    assert second.status_code == 200
    first_ids = ids(first.json())
    second_ids = ids(second.json())
    assert first_ids.isdisjoint(second_ids)
    assert created_ids.issubset(first_ids | second_ids)

    invalid = client.get("/reports", params={"offset": -1})
    assert invalid.status_code == 422


def test_report_and_query_timestamps_require_timezone(client: ASGITestClient) -> None:
    response = client.post(
        "/reports",
        data={"metadata": json.dumps(sample_metadata(captured_at="2026-05-12T12:00:00"))},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )
    assert response.status_code == 422

    response = client.get("/reports", params={"created_from": "2026-05-12T00:00:00"})
    assert response.status_code == 422


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
    with SessionLocal() as db:
        image_object = db.get(ReportImageObject, uuid.UUID(created["id"]))
    assert image_object is not None
    envelope = (app_settings.upload_dir / image_object.storage_name).read_bytes()
    assert image_object.storage_name == f"{created['id']}.wse"
    assert envelope.startswith(b"WSRI")
    assert image_bytes not in envelope


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
    with SessionLocal() as db:
        stored = db.get(Report, uuid.UUID(report_id))
        audits = db.scalars(
            select(ReportStatusAudit)
            .where(ReportStatusAudit.report_id == uuid.UUID(report_id))
            .order_by(ReportStatusAudit.created_at)
        ).all()
    assert stored is not None
    assert stored.status_version == 3
    assert [(item.previous_version, item.next_version) for item in audits] == [
        (1, 2),
        (2, 3),
    ]


def test_report_status_write_fails_closed_during_maintenance(
    client: ASGITestClient,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    created = create_report(client)
    monkeypatch.setattr("backend.app.api.reports._trusted_maintenance_lock_parent", lambda *_args: True)
    lock_path = tmp_path / "maintenance.lock"
    lock_path.touch(mode=0o600)
    descriptor = os.open(lock_path, os.O_RDONLY | os.O_NOFOLLOW)
    monkeypatch.setattr(get_settings(), "maintenance_lock_path", lock_path)
    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    try:
        response = client.patch(
            f"/reports/{created['id']}/status",
            json={"status": "reviewed", "expected_updated_at": created["updated_at"]},
        )
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "maintenance_in_progress"
    unchanged = client.get(f"/reports/{created['id']}")
    assert unchanged.status_code == 200
    assert unchanged.json()["status"] == "new"
    assert unchanged.json()["metadata"].get("status_history") is None

def test_legacy_report_rejects_missing_location(client: ASGITestClient) -> None:
    response = client.post(
        "/reports",
        data={"metadata": json.dumps(sample_metadata(confidence=0.62, gps=None, heading=None))},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 422
    assert "require gps" in response.text


def test_legacy_report_uses_server_provenance_and_actor(client: ASGITestClient) -> None:
    metadata = sample_metadata()
    metadata.update(
        {
            "data_origin": "field_candidate",
            "performance_excluded": False,
            "unknown_extra": "drop-me",
        }
    )

    response = client.post(
        "/reports",
        headers={"x-walksafe-actor-id": "field.user@example.com"},
        data={"metadata": json.dumps(metadata)},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 201, response.text
    stored = response.json()["metadata"]
    assert stored["data_origin"] == "demo"
    assert stored["performance_excluded"] is True
    assert stored["performance_exclusion_reason"] == "legacy_v1_unverified_provenance"
    assert stored["coordinate_gate_status"] == "legacy_v1_unverified"
    assert "ingested_by_actor_id" not in stored
    assert stored["actor_provenance"] == "gateway_forwarded"
    assert "unknown_extra" not in stored
    assert len(stored["payload_sha256"]) == 64
    assert len(stored["image_sha256"]) == 64


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

    assert second["duplicate_report_ids"] == []
    assert "duplicate_report_ids" not in second["metadata"]
    assert second["duplicate_count"] >= 1
    assert "duplicate_candidate" in second["review_flags"]
    assert "duplicate_candidate" in second["metadata"]["review_flags"]
    admin_detail = client.get(f"/reports/{second['id']}")
    assert first["id"] in admin_detail.json()["duplicate_report_ids"]
    assert admin_detail.json()["duplicate_count"] >= 1

    duplicate_check = client.get(
        "/reports/duplicate-check",
        params={
            "class_name": "pothole",
            "captured_at": "2026-05-12T15:00:20Z",
            "lat": 37.50002,
            "lng": 127.00002,
            "radius_m": 10,
            "minutes": 1,
            "fake_source": "true",
        },
    )
    assert duplicate_check.status_code == 200
    assert first["id"] in duplicate_check.json()["duplicate_report_ids"]
    assert second["id"] in duplicate_check.json()["duplicate_report_ids"]
    assert duplicate_check.json()["duplicate_count"] == len(duplicate_check.json()["duplicate_report_ids"])
    assert "reports" not in duplicate_check.json()


def test_duplicate_candidates_do_not_mix_fake_and_real_reports(client: ASGITestClient) -> None:
    fake = create_report(
        client,
        sample_metadata(
            class_id=3,
            class_name="pothole",
            source="fake",
            captured_at="2026-05-12T15:03:11.000Z",
            gps={"latitude": 37.50123, "longitude": 127.00456, "accuracy_m": 8.0},
        ),
    )
    real = create_report(
        client,
        sample_metadata(
            class_id=3,
            class_name="pothole",
            source="server",
            captured_at="2026-05-12T15:03:21.000Z",
            gps={"latitude": 37.50123, "longitude": 127.00456, "accuracy_m": 8.0},
        ),
    )

    assert fake["id"] not in real["duplicate_report_ids"]


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
