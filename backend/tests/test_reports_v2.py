from __future__ import annotations

import asyncio
import csv
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import sys
import threading
import uuid
import zipfile

import httpx
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import Session
from sqlalchemy.exc import DBAPIError, IntegrityError, SQLAlchemyError
from PIL import Image
from fastapi import HTTPException

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from backend.app.config import get_settings  # noqa: E402
from backend.app.database import SessionLocal  # noqa: E402
from backend.app.field_test_security import FieldTestAccess, create_actor_assertion  # noqa: E402
from backend.app.main import app, settings as app_settings  # noqa: E402
from backend.app.models import ReportExportAudit, ReportImageObject, ReportStatusAudit  # noqa: E402
from backend.app.schemas import (  # noqa: E402
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
)
from backend.app.services.privacy_lifecycle import record_consent_event  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


_JPEG_OUTPUT = io.BytesIO()
Image.new("RGB", (4, 4), color=(180, 160, 40)).save(_JPEG_OUTPUT, format="JPEG")
JPEG_BYTES = _JPEG_OUTPUT.getvalue()
GATEWAY_SECRET = "gateway-actor-assertion-secret-for-report-tests"


@pytest.fixture(autouse=True)
def gateway_actor_assertion_secret(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(app_settings, "gateway_session_secret", GATEWAY_SECRET)


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


def sample_v2_metadata(**overrides: object) -> dict[str, object]:
    metadata = {
        "schema_version": "detect.v2",
        "model_key": "custom_tactile",
        "source_model": "fake/custom-tactile-contract",
        "model_class_id": 1,
        "class_name": "damaged_tactile_block",
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


def ids(response: object) -> set[str]:
    assert isinstance(response, list)
    return {str(report["id"]) for report in response}


def csv_rows(response) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(response.text.lstrip("\ufeff"))))


def fresh_created_from() -> str:
    return (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()


def secured_field_headers(token: str, actor_id: str) -> dict[str, str]:
    return {
        "x-walksafe-field-test-token": token,
        "x-walksafe-actor-id": actor_id,
        "x-walksafe-account-generation": "1",
        "x-walksafe-actor-assertion": create_actor_assertion(
            actor_id,
            FieldTestAccess.FIELD,
            GATEWAY_SECRET,
            account_generation=1,
        ),
    }


def record_report_consent(actor_id: str) -> None:
    suffix = uuid.uuid4().hex
    with SessionLocal() as db:
        record_consent_event(
            db,
            actor_id=actor_id,
            account_generation=1,
            installation_id=f"install_{suffix}",
            request_id=f"consent_{suffix}",
            client_revision=1,
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=dict(PRIVACY_CONSENT_ITEM_VERSIONS),
            raw_source_collection=True,
            automatic_reporting=True,
            mobile_network_transfer=False,
            training_reuse=False,
            secret=app_settings.privacy_hmac_secret,
            key_version=app_settings.privacy_hmac_key_version,
        )


def post_report_v2(client: ASGITestClient, metadata: dict[str, object]):
    return client.post(
        "/reports/v2",
        data={"metadata": json.dumps(metadata)},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )


def test_report_v2_persistence_does_not_block_the_event_loop(monkeypatch: pytest.MonkeyPatch) -> None:
    started = threading.Event()
    release = threading.Event()

    def blocking_persistence(*_args, **_kwargs):  # noqa: ANN002, ANN003
        started.set()
        assert release.wait(timeout=2), "test persistence was not released"
        raise HTTPException(status_code=503, detail={"code": "test_persistence_released"})

    monkeypatch.setattr("backend.app.api.reports._persist_v2_report", blocking_persistence)

    async def exercise() -> tuple[httpx.Response, httpx.Response]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as async_client:
            report_task = asyncio.create_task(
                async_client.post(
                    "/reports/v2",
                    data={"metadata": json.dumps(sample_v2_metadata())},
                    files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
                )
            )
            assert await asyncio.to_thread(started.wait, 1), "persistence worker did not start"
            health = await async_client.get("/health")
            release.set()
            return await report_task, health

    try:
        report_response, health_response = asyncio.run(exercise())
    finally:
        release.set()

    assert health_response.status_code == 200
    assert report_response.status_code == 503


def test_create_report_v2_stores_allowed_tactile_damage(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(distance_m=1.4, trace_id="trace-policy-1")

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == metadata["model_class_id"]
    assert body["class_name"] == "damaged_tactile_block"
    assert body["source"] == "fake"
    assert body["metadata"]["distance_m"] == 1.4
    for key, value in metadata.items():
        if key == "captured_at":
            assert datetime.fromisoformat(body["metadata"][key].replace("Z", "+00:00")) == datetime.fromisoformat(
                str(value).replace("Z", "+00:00")
            )
        else:
            assert body["metadata"][key] == value
    assert body["metadata"]["trace_id"] == "trace-policy-1"
    assert len(body["metadata"]["payload_sha256"]) == 64
    assert len(body["metadata"]["image_sha256"]) == 64
    assert body["metadata"]["data_origin"] == "demo"
    assert body["metadata"]["runtime_mode"] == "fake"
    assert body["metadata"]["performance_excluded"] is True
    assert "fake_source" in body["review_flags"]
    with SessionLocal() as db:
        image_object = db.get(ReportImageObject, uuid.UUID(body["id"]))
    assert image_object is not None
    envelope = (app_settings.upload_dir / image_object.storage_name).read_bytes()
    assert image_object.storage_name == f"{body['id']}.wse"
    assert envelope.startswith(b"WSRI")
    assert JPEG_BYTES not in envelope


def test_database_constraints_reject_partial_location_and_nan_accuracy(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T12:00:30.000Z", trace_id="constraint-test"),
    )
    assert created.status_code == 201, created.text
    report_id = uuid.UUID(created.json()["id"])
    engine = create_engine(get_settings().database_url)
    try:
        for statement in (
            text("UPDATE reports SET longitude = NULL WHERE id = :report_id"),
            text("UPDATE reports SET accuracy_m = 'NaN'::double precision WHERE id = :report_id"),
        ):
            with engine.connect() as connection:
                transaction = connection.begin()
                try:
                    with pytest.raises(IntegrityError):
                        connection.execute(statement, {"report_id": report_id})
                finally:
                    transaction.rollback()
    finally:
        engine.dispose()


@pytest.mark.parametrize("table_name", ["report_status_audits", "report_export_audits"])
def test_append_only_audit_tables_reject_truncate(table_name: str) -> None:
    engine = create_engine(get_settings().database_url)
    try:
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                with pytest.raises(DBAPIError, match="append-only"):
                    connection.execute(text(f"TRUNCATE TABLE {table_name} CASCADE"))
            finally:
                transaction.rollback()
    finally:
        engine.dispose()


def test_create_report_v2_drops_unknown_extra_metadata_but_keeps_review_flags(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        source="android",
        source_model="android/custom-tactile-contract",
        review_flags=["fake_source"],
        guardianPhone="010-1234-5678",
        unknown_extra="drop-me",
        raw_depth_ref="drop-depth",
        rgb_frame_ref="drop-rgb",
        raw_debug_payload={"drop": True},
        apk_sha256="a" * 64,
        source_commit="c" * 40,
        model_config_sha256="b" * 64,
        android_model_version="1.0.0",
        bbox_coordinate_space="normalized_xywh",
        depth_coordinate_space="zed_disp16",
        depth_sample_count=123,
        depth_valid_sample_ratio=0.82,
        detection_age_ms=150,
        coordinate_gate_status="pass",
        fallback_used=True,
        loaded_model_key="legacy_two_model",
        model_load_reason="unified_unavailable_legacy_loaded",
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert "guardianPhone" not in body["metadata"]
    assert "unknown_extra" not in body["metadata"]
    assert "raw_depth_ref" not in body["metadata"]
    assert "rgb_frame_ref" not in body["metadata"]
    assert "raw_debug_payload" not in body["metadata"]
    assert "fake_source" in body["metadata"]["review_flags"]
    assert "fake_source" in body["review_flags"]
    assert body["metadata"]["apk_sha256"] == metadata["apk_sha256"]
    assert body["metadata"]["source_commit"] == metadata["source_commit"]
    assert body["metadata"]["model_config_sha256"] == metadata["model_config_sha256"]
    assert body["metadata"]["android_model_version"] == metadata["android_model_version"]
    assert body["metadata"]["bbox_coordinate_space"] == metadata["bbox_coordinate_space"]
    assert body["metadata"]["depth_coordinate_space"] == metadata["depth_coordinate_space"]
    assert body["metadata"]["depth_sample_count"] == metadata["depth_sample_count"]
    assert body["metadata"]["depth_valid_sample_ratio"] == metadata["depth_valid_sample_ratio"]
    assert body["metadata"]["detection_age_ms"] == metadata["detection_age_ms"]
    assert body["metadata"]["coordinate_gate_status"] == metadata["coordinate_gate_status"]
    assert body["metadata"]["fallback_used"] is True
    assert body["metadata"]["loaded_model_key"] == metadata["loaded_model_key"]
    assert body["metadata"]["model_load_reason"] == metadata["model_load_reason"]


def test_create_report_v2_marks_coordinate_gate_pending_as_performance_excluded(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        source="android",
        source_model="android/custom-tactile-contract",
        coordinate_gate_status="pending",
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["metadata"]["performance_excluded"] is True
    assert "coordinate_gate_status=pending" in body["metadata"]["performance_exclusion_reason"]
    assert "fake_source" not in body["review_flags"]


def test_create_report_v2_marks_missing_coordinate_gate_as_performance_excluded(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        source="android",
        source_model="android/custom-tactile-contract",
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["metadata"]["performance_excluded"] is True
    assert "coordinate_gate_status=pending" in body["metadata"]["performance_exclusion_reason"]
    assert "fake_source" not in body["review_flags"]


def test_create_report_v2_keeps_client_asserted_detection_excluded_when_coordinate_gate_passes(
    client: ASGITestClient,
) -> None:
    metadata = sample_v2_metadata(
        source="android",
        source_model="android/custom-tactile-contract",
        coordinate_gate_status="pass",
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["metadata"]["performance_excluded"] is True
    assert body["metadata"]["performance_exclusion_reason"] == "client_asserted_detection"


@pytest.mark.parametrize(
    ("override", "reason"),
    [
        ({"confidence": 0.69}, "confidence<0.70"),
        ({"gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 15.1}}, "gps_accuracy>15m"),
        ({"coordinate_gate_status": "pending"}, "coordinate_gate_status!=pass"),
        ({"captured_at": "2020-01-01T00:00:00Z"}, "captured_at_stale"),
    ],
)
def test_secured_auto_report_rejects_server_gate_failures(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
    override: dict[str, object],
    reason: str,
) -> None:
    field_token = "field-token-for-auto-report-tests-123456"
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-auto-report-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", "f" * 40)
    metadata = sample_v2_metadata(
        source="android",
        source_model="android/custom-tactile-contract",
        source_commit="f" * 40,
        captured_at=datetime.now(timezone.utc).isoformat(),
        coordinate_gate_status="pass",
    )
    metadata.update(override)

    response = client.post(
        "/reports/v2",
        headers=secured_field_headers(field_token, "field.user@example.com"),
        data={"metadata": json.dumps(metadata)},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 422, response.text
    assert response.json()["detail"]["code"] == "auto_report_gate_failed"
    assert reason in response.json()["detail"]["reasons"]


def test_secured_auto_report_cooldown_is_authoritative_by_actor_class_radius_and_time(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    field_token = "field-token-for-cooldown-tests-123456"
    source_commit = "f" * 40
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-cooldown-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", source_commit)
    actor_id = "cooldown.user@example.com"
    record_report_consent(actor_id)
    headers = secured_field_headers(field_token, actor_id)
    captured_at = datetime.now(timezone.utc)

    def submit(*, latitude: float, trigger: str = "auto", seconds: int = 0):
        metadata = sample_v2_metadata(
            source="android",
            source_model="android/custom-tactile-contract",
            source_commit=source_commit,
            captured_at=(captured_at + timedelta(seconds=seconds)).isoformat(),
            coordinate_gate_status="pass",
            gps={"latitude": latitude, "longitude": 127.0, "accuracy_m": 5.0},
            trigger=trigger,
            auto_reported=trigger == "auto",
        )
        return client.post(
            "/reports/v2",
            headers=headers,
            data={"metadata": json.dumps(metadata)},
            files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
        )

    first = submit(latitude=37.5)
    inside_radius = submit(latitude=37.50021, seconds=1)
    outside_radius = submit(latitude=37.50025, seconds=2)
    voice_override = submit(latitude=37.5, trigger="voice", seconds=3)

    assert first.status_code == 201, first.text
    assert inside_radius.status_code == 201, inside_radius.text
    assert inside_radius.json()["id"] == first.json()["id"]
    assert outside_radius.status_code == 201, outside_radius.text
    assert voice_override.status_code == 201, voice_override.text


def test_concurrent_secured_auto_reports_return_one_idempotent_cooldown_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    field_token = "field-token-for-concurrent-cooldown-123456"
    source_commit = "e" * 40
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-concurrent-cooldown-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", source_commit)
    captured_at = datetime.now(timezone.utc)
    actor_id = "concurrent.cooldown@example.com"
    record_report_consent(actor_id)
    headers = secured_field_headers(field_token, actor_id)

    async def exercise() -> list[httpx.Response]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as async_client:
            requests = []
            for seconds in (0, 1):
                metadata = sample_v2_metadata(
                    source="android",
                    source_model="android/custom-tactile-contract",
                    source_commit=source_commit,
                    captured_at=(captured_at + timedelta(seconds=seconds)).isoformat(),
                    coordinate_gate_status="pass",
                    gps={"latitude": 37.61, "longitude": 127.11, "accuracy_m": 5.0},
                )
                requests.append(
                    async_client.post(
                        "/reports/v2",
                        headers=headers,
                        data={"metadata": json.dumps(metadata)},
                        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
                    )
                )
            return list(await asyncio.gather(*requests))

    responses = asyncio.run(exercise())

    assert [response.status_code for response in responses] == [201, 201]
    assert len({response.json()["id"] for response in responses}) == 1


def test_report_v2_retry_by_same_actor_is_idempotent(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        captured_at="2026-05-22T14:26:31.123Z",
        source="android",
        source_model="android/custom-tactile-idempotency",
        coordinate_gate_status="pass",
    )
    request = {
        "headers": {"x-walksafe-actor-id": "idempotency.user@example.com"},
        "data": {"metadata": json.dumps(metadata)},
        "files": {"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    }

    first = client.post("/reports/v2", **request)
    second = client.post("/reports/v2", **request)

    assert first.status_code == 201, first.text
    assert second.status_code == 201, second.text
    assert second.json()["id"] == first.json()["id"]


@pytest.mark.parametrize(
    "source_model",
    [
        "/srv/models/private.pt",
        "../models/private.pt",
        r"C:\\models\\private.pt",
        "https://models.example/private.pt",
    ],
)
def test_create_report_v2_rejects_source_model_paths_and_urls(
    client: ASGITestClient,
    source_model: str,
) -> None:
    response = post_report_v2(client, sample_v2_metadata(source_model=source_model))

    assert response.status_code == 422
    assert "source_model" in response.text


@pytest.mark.parametrize(
    "trigger,auto_reported",
    [("auto", False), ("voice", True)],
)
def test_create_report_v2_rejects_trigger_auto_report_mismatch(
    client: ASGITestClient,
    trigger: str,
    auto_reported: bool,
) -> None:
    response = post_report_v2(
        client,
        sample_v2_metadata(trigger=trigger, auto_reported=auto_reported),
    )

    assert response.status_code == 422
    assert "auto_reported must be true" in response.text


def test_create_report_v2_rejects_unknown_coordinate_gate_status(client: ASGITestClient) -> None:
    response = post_report_v2(
        client,
        sample_v2_metadata(coordinate_gate_status="client_says_ok"),
    )

    assert response.status_code == 422


def test_create_report_v2_rejects_malformed_provenance_hash(client: ASGITestClient) -> None:
    response = post_report_v2(client, sample_v2_metadata(apk_sha256="not-a-sha256"))

    assert response.status_code == 422
    assert "apk_sha256" in response.text

    source_response = post_report_v2(client, sample_v2_metadata(source_commit="short"))
    assert source_response.status_code == 422
    assert "source_commit" in source_response.text


def test_secured_android_report_requires_matching_source_commit(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    field_token = "field-token-for-provenance-tests-123456"
    expected_commit = "d" * 40
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-provenance-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", expected_commit)
    request = {
        "headers": secured_field_headers(field_token, "field.user@example.com"),
        "data": {
            "metadata": json.dumps(
                sample_v2_metadata(
                    source="android",
                    source_model="android/custom-tactile-contract",
                    captured_at=datetime.now(timezone.utc).isoformat(),
                    coordinate_gate_status="pass",
                    source_commit="e" * 40,
                )
            )
        },
        "files": {"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    }

    response = client.post("/reports/v2", **request)

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "android_provenance_gate_failed"


def test_create_report_v2_overwrites_client_provenance_fields(client: ASGITestClient) -> None:
    response = post_report_v2(
        client,
        sample_v2_metadata(
            data_origin="field_candidate",
            runtime_mode="server",
            review_flags=["resolved_by_client", "fake_source"],
        ),
    )

    assert response.status_code == 201, response.text
    metadata = response.json()["metadata"]
    assert metadata["data_origin"] == "demo"
    assert metadata["runtime_mode"] == "fake"
    assert metadata["provenance_status"] == "client_asserted"
    assert metadata["received_at"].endswith("Z")
    assert "fake_source" in metadata["review_flags"]
    assert "resolved_by_client" not in metadata["review_flags"]


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


def test_create_report_v2_preserves_android_source_metadata(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        source="android",
        source_model="android/custom-tactile",
        trace_id="android-trace-1",
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["source"] == "android"
    assert body["metadata"]["source"] == "android"
    assert body["metadata"]["trace_id"] == "android-trace-1"


def test_create_report_v2_records_gateway_actor(client: ASGITestClient) -> None:
    response = client.post(
        "/reports/v2",
        headers={"x-walksafe-actor-id": "field.user@example.com"},
        data={"metadata": json.dumps(sample_v2_metadata())},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 201, response.text
    metadata = response.json()["metadata"]
    assert "ingested_by_actor_id" not in metadata
    assert metadata["actor_provenance"] == "gateway_forwarded"
    assert metadata["actor_provenance"] == "gateway_forwarded"


@pytest.mark.parametrize("actor_id", ["contains space", "../admin", ".admin", "admin!", "a" * 65])
def test_create_report_v2_rejects_invalid_gateway_actor(client: ASGITestClient, actor_id: str) -> None:
    response = client.post(
        "/reports/v2",
        headers={"x-walksafe-actor-id": actor_id},
        data={"metadata": json.dumps(sample_v2_metadata())},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "invalid_actor_id"


@pytest.mark.parametrize(
    "class_name,model_class_id",
    [
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
    for key, value in metadata.items():
        if key == "captured_at":
            assert datetime.fromisoformat(body["metadata"][key].replace("Z", "+00:00")) == datetime.fromisoformat(
                str(value).replace("Z", "+00:00")
            )
        else:
            assert body["metadata"][key] == value


@pytest.mark.parametrize(
    "metadata",
    [
        sample_v2_metadata(class_name="tactile_damage_area", model_class_id=2),
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


def test_create_report_v2_rejects_mismatched_damage_class_id(client: ASGITestClient) -> None:
    response = post_report_v2(client, sample_v2_metadata(model_class_id=2))

    assert response.status_code == 422
    assert "model_class_id=1" in response.text


def test_create_report_v2_rejects_missing_gps(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(gps=None)

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
            captured_at="2026-05-22T13:00:30.000Z",
            gps={"latitude": 37.50003, "longitude": 127.00003, "accuracy_m": 10.0},
        ),
    )

    assert second.status_code == 201, second.text
    second_body = second.json()
    assert second_body["duplicate_report_ids"] == []
    assert "duplicate_report_ids" not in second_body["metadata"]
    assert second_body["duplicate_count"] >= 1
    assert "duplicate_candidate" in second_body["review_flags"]
    assert "duplicate_candidate" in second_body["metadata"]["review_flags"]
    admin_detail = client.get(f"/reports/{second_body['id']}")
    assert first.json()["id"] in admin_detail.json()["duplicate_report_ids"]


@pytest.mark.parametrize(
    "bbox",
    [
        {"x": 0.8, "y": 0.35, "width": 0.4, "height": 0.1},
        {"x": 0.2, "y": 0.9, "width": 0.4, "height": 0.2},
    ],
)
def test_create_report_v2_rejects_bbox_overflow(client: ASGITestClient, bbox: dict[str, float]) -> None:
    metadata = sample_v2_metadata(bbox=bbox)

    response = post_report_v2(client, metadata)

    assert response.status_code == 422


def test_list_reports_filters_v2_metadata(client: ASGITestClient) -> None:
    auto = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T14:00:00.000Z"),
    )
    assert auto.status_code == 201, auto.text
    auto_id = auto.json()["id"]

    manual = post_report_v2(
        client,
        sample_v2_metadata(
            class_name="damaged_tactile_block",
            model_class_id=1,
            captured_at="2026-05-22T14:10:00.000Z",
            trigger="voice",
            auto_reported=False,
        ),
    )
    assert manual.status_code == 201, manual.text
    manual_id = manual.json()["id"]

    by_class = client.get("/reports", params={"class_name": "damaged_tactile_block", "limit": 100})
    assert by_class.status_code == 200, by_class.text
    assert auto_id in ids(by_class.json())

    by_model = client.get("/reports", params={"model_key": "custom_tactile", "limit": 100})
    assert by_model.status_code == 200, by_model.text
    assert {auto_id, manual_id} <= ids(by_model.json())

    by_trigger = client.get("/reports", params={"trigger": "voice", "limit": 100})
    assert by_trigger.status_code == 200, by_trigger.text
    assert manual_id in ids(by_trigger.json())
    assert auto_id not in ids(by_trigger.json())

    by_manual = client.get("/reports", params={"auto_reported": "false", "limit": 100})
    assert by_manual.status_code == 200, by_manual.text
    assert manual_id in ids(by_manual.json())
    assert auto_id not in ids(by_manual.json())

    no_match = client.get(
        "/reports",
        params={"class_name": "not_a_class", "model_key": "unknown_model", "trigger": "unknown", "limit": 100},
    )
    assert no_match.status_code == 200, no_match.text
    assert no_match.json() == []


def test_list_reports_filters_demo_mode(client: ASGITestClient) -> None:
    created_from = fresh_created_from()
    source_fake = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T14:20:00.000Z", source_model="fake/demo-list"),
    )
    assert source_fake.status_code == 201, source_fake.text
    source_fake_id = source_fake.json()["id"]

    metadata_fake = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T14:21:00.000Z",
            source_model="YOLO26s custom",
            fake_source=True,
        ),
    )
    assert metadata_fake.status_code == 201, metadata_fake.text
    metadata_fake_id = metadata_fake.json()["id"]

    real = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T14:22:00.000Z", source_model="YOLO26s custom"),
    )
    assert real.status_code == 201, real.text
    real_id = real.json()["id"]

    explicit_non_fake = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T14:23:00.000Z",
            source_model="YOLO26s custom",
            fake_source=False,
        ),
    )
    assert explicit_non_fake.status_code == 201, explicit_non_fake.text
    explicit_non_fake_id = explicit_non_fake.json()["id"]

    exclude_fake = client.get(
        "/reports",
        params={"created_from": created_from, "demo_filter": "exclude_fake", "limit": 100},
    )
    assert exclude_fake.status_code == 200, exclude_fake.text
    exclude_fake_ids = ids(exclude_fake.json())
    assert real_id in exclude_fake_ids
    assert explicit_non_fake_id in exclude_fake_ids
    assert source_fake_id not in exclude_fake_ids
    assert metadata_fake_id not in exclude_fake_ids

    only_fake = client.get(
        "/reports",
        params={"created_from": created_from, "demo_filter": "only_fake", "limit": 100},
    )
    assert only_fake.status_code == 200, only_fake.text
    only_fake_ids = ids(only_fake.json())
    assert {source_fake_id, metadata_fake_id} <= only_fake_ids
    assert real_id not in only_fake_ids
    assert explicit_non_fake_id not in only_fake_ids


def test_list_reports_keeps_all_client_asserted_detections_out_of_performance_metrics(
    client: ASGITestClient,
) -> None:
    created_from = fresh_created_from()
    included = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T14:24:00.000Z",
            source_model="YOLO26s custom",
            coordinate_gate_status="pass",
        ),
    )
    excluded = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T14:25:00.000Z",
            source_model="YOLO26s custom",
            coordinate_gate_status="pending",
        ),
    )
    assert included.status_code == 201, included.text
    assert excluded.status_code == 201, excluded.text

    included_response = client.get(
        "/reports",
        params={"created_from": created_from, "performance_excluded": "false", "limit": 100},
    )
    excluded_response = client.get(
        "/reports",
        params={"created_from": created_from, "performance_excluded": "true", "limit": 100},
    )

    assert included_response.status_code == 200, included_response.text
    assert excluded_response.status_code == 200, excluded_response.text
    assert included.json()["id"] not in ids(included_response.json())
    assert excluded.json()["id"] not in ids(included_response.json())
    assert included.json()["id"] in ids(excluded_response.json())
    assert excluded.json()["id"] in ids(excluded_response.json())


def test_export_reports_csv_filters_v2_metadata(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:00:00.000Z", distance_m=1.4),
    )
    assert created.status_code == 201, created.text
    created_body = created.json()
    missing_distance = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:00:30.000Z"),
    )
    assert missing_distance.status_code == 201, missing_distance.text
    missing_distance_body = missing_distance.json()

    response = client.get("/reports/export", params={"model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("text/csv")
    rows = csv_rows(response)
    row = next(row for row in rows if row["id"] == created_body["id"])
    missing_distance_row = next(row for row in rows if row["id"] == missing_distance_body["id"])
    assert row["model_key"] == "custom_tactile"
    assert row["source_model"] == "fake/custom-tactile-contract"
    assert row["trigger"] == "auto"
    assert row["auto_reported"] == "true"
    assert row["distance_m"] == "1.4"
    assert len(row["payload_sha256"]) == 64
    assert len(row["image_sha256"]) == 64
    assert row["data_origin"] == "demo"
    assert row["runtime_mode"] == "fake"
    assert row["performance_excluded"] == "true"
    assert missing_distance_row["distance_m"] == ""
    assert row["image_path"] == created_body["image_path"]


def test_export_reports_json_filters_v2_metadata(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:05:00.000Z", distance_m=2.5),
    )
    assert created.status_code == 201, created.text
    created_body = created.json()

    response = client.get("/reports/export", params={"format": "json", "model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    assert response.headers["content-disposition"] == 'attachment; filename="walksafe-reports-demo-included.json"'
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-walksafe-demo-filter"] == "all"
    row = next(row for row in response.json() if row["id"] == created_body["id"])
    assert row["latitude"] == 37.5665
    assert row["longitude"] == 126.978
    assert row["model_key"] == "custom_tactile"
    assert row["distance_m"] == 2.5
    assert len(row["payload_sha256"]) == 64
    assert len(row["image_sha256"]) == 64
    assert row["performance_excluded"] == "true"
    assert row["location_quality"] == "high"
    assert isinstance(row["review_flags"], str)


def test_export_reports_geojson_filters_v2_metadata(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:06:00.000Z", distance_m=3.75),
    )
    assert created.status_code == 201, created.text
    created_body = created.json()

    response = client.get("/reports/export", params={"format": "geojson", "model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("application/geo+json")
    assert response.headers["cache-control"] == "no-store"
    body = response.json()
    assert body["type"] == "FeatureCollection"
    feature = next(feature for feature in body["features"] if feature["id"] == created_body["id"])
    assert feature["type"] == "Feature"
    assert feature["geometry"] == {"type": "Point", "coordinates": [126.978, 37.5665]}
    assert feature["properties"]["model_key"] == "custom_tactile"
    assert feature["properties"]["source_model"] == "fake/custom-tactile-contract"
    assert feature["properties"]["trigger"] == "auto"
    assert feature["properties"]["distance_m"] == 3.75
    assert len(feature["properties"]["payload_sha256"]) == 64
    assert feature["properties"]["data_origin"] == "demo"
    assert "latitude" not in feature["properties"]
    assert "longitude" not in feature["properties"]


def test_export_reports_filters_demo_mode(client: ASGITestClient) -> None:
    created_from = fresh_created_from()
    source_fake = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:07:00.000Z", source_model="fake/demo-export"),
    )
    assert source_fake.status_code == 201, source_fake.text
    source_fake_id = source_fake.json()["id"]

    metadata_fake = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:08:00.000Z",
            source_model="YOLO26s custom",
            review_flags=["fake_source"],
        ),
    )
    assert metadata_fake.status_code == 201, metadata_fake.text
    metadata_fake_id = metadata_fake.json()["id"]

    real = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:09:00.000Z", source_model="YOLO26s custom"),
    )
    assert real.status_code == 201, real.text
    real_id = real.json()["id"]

    exclude_fake = client.get(
        "/reports/export",
        params={"format": "json", "created_from": created_from, "demo_filter": "exclude_fake"},
    )
    assert exclude_fake.status_code == 200, exclude_fake.text
    exclude_fake_ids = {row["id"] for row in exclude_fake.json()}
    assert real_id in exclude_fake_ids
    assert source_fake_id not in exclude_fake_ids
    assert metadata_fake_id not in exclude_fake_ids

    only_fake = client.get(
        "/reports/export",
        params={"format": "json", "created_from": created_from, "demo_filter": "only_fake"},
    )
    assert only_fake.status_code == 200, only_fake.text
    only_fake_ids = {row["id"] for row in only_fake.json()}
    assert {source_fake_id, metadata_fake_id} <= only_fake_ids
    assert real_id not in only_fake_ids


def test_export_reports_csv_no_match_writes_header_only(client: ASGITestClient) -> None:
    response = client.get("/reports/export", params={"model_key": "no_such_export_model"})

    assert response.status_code == 200, response.text
    header = response.text.splitlines()[0].split(",")
    for expected in [
        "id",
        "status",
        "class_name",
        "model_key",
        "payload_sha256",
        "image_sha256",
        "data_origin",
        "performance_excluded",
        "status_history_count",
        "image_path",
    ]:
        assert expected in header
    assert csv_rows(response) == []


def test_export_reports_csv_bom_and_redacted_mode(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:11:00.000Z"),
    )
    assert created.status_code == 201, created.text

    response = client.get("/reports/export", params={"format": "csv", "bom": "true", "redacted": "true"})

    assert response.status_code == 200, response.text
    assert response.text.startswith("\ufeff")
    row = next(row for row in csv_rows(response) if row["id"] == created.json()["id"])
    assert row["image_path"] == ""
    assert len(row["latitude"].split(".")[-1]) <= 4


def test_export_reports_minimum_profile_removes_internal_fields(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:11:30.000Z",
            reporter_user_id="user-private",
            trace_id="trace-private",
        ),
    )
    assert created.status_code == 201, created.text

    response = client.get(
        "/reports/export",
        params={"format": "json", "profile": "minimum", "model_key": "custom_tactile"},
    )

    assert response.status_code == 200, response.text
    assert response.headers["x-walksafe-export-profile"] == "minimum"
    rows = response.json()
    assert rows
    row = rows[0]
    assert row["id"] == ""
    assert row["image_path"] == ""
    assert row["reporter_user_id"] == ""
    assert row["trace_id"] == ""
    assert row["heading"] == ""
    assert row["bbox_x"] == ""
    assert row["latitude"] == round(created.json()["gps"]["latitude"], 2)
    assert row["captured_at"] == created.json()["captured_at"][:10]
    for sensitive_field in {
        "source",
        "accuracy_m",
        "model_key",
        "source_model",
        "payload_sha256",
        "image_sha256",
        "performance_excluded",
        "review_flags",
    }:
        assert row[sensitive_field] == ""


def test_export_reports_agency_profile_keeps_exact_location_and_removes_internal_fields(
    client: ASGITestClient,
) -> None:
    export_audit_id = "11111111-1111-4111-8111-111111111111"
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:11:45.000Z",
            source_model="YOLO26s custom",
            coordinate_gate_status="pass",
            gps={"latitude": 37.56651234, "longitude": 126.97801234, "accuracy_m": 7.5},
            reporter_user_id="user-private",
            trace_id="trace-private",
        ),
    )
    assert created.status_code == 201, created.text
    reviewed = client.patch(
        f"/reports/{created.json()['id']}/status",
        headers={"x-walksafe-actor-id": "agency.operator@example.com"},
        json={"status": "reviewed", "expected_updated_at": created.json()["updated_at"]},
    )
    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["metadata"]["agency_review_verified"] is True
    assert reviewed.json()["metadata"]["agency_reviewed_by_actor_id"] == "agency.operator@example.com"
    assert reviewed.json()["metadata"]["performance_excluded"] is True

    response = client.get(
        "/reports/export",
        headers={"x-walksafe-actor-id": "agency.operator@example.com"},
        params={
            "format": "csv",
            "profile": "agency",
            "model_key": "custom_tactile",
            "audit_id": export_audit_id,
            "manifest": "true",
            "bundle": "true",
        },
    )

    assert response.status_code == 200, response.text
    assert response.headers["x-walksafe-export-profile"] == "agency"
    assert response.headers["x-walksafe-demo-filter"] == "exclude_fake"
    assert response.headers["x-walksafe-location-precision"] == "exact-report"
    assert response.headers["x-walksafe-audit-id"] == export_audit_id
    assert "-agency.bundle.zip" in response.headers["content-disposition"]
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        manifest = json.loads(archive.read("manifest.json"))
    row = next(row for row in manifest["rows"] if row["id"] == created.json()["id"])
    assert row["latitude"] == 37.56651234
    assert row["longitude"] == 126.97801234
    assert row["accuracy_m"] == 7.5
    for field in {
        "image_path",
        "reporter_user_id",
        "trace_id",
        "heading",
        "bbox_x",
        "bbox_y",
        "bbox_width",
        "bbox_height",
        "review_flags",
        "review_note",
        "source_model",
    }:
        assert row[field] == ""

    duplicate_response = client.get(
        "/reports/export",
        headers={"x-walksafe-actor-id": "agency.operator@example.com"},
        params={
            "format": "csv",
            "profile": "agency",
            "model_key": "custom_tactile",
            "manifest": "true",
            "bundle": "true",
            "audit_id": export_audit_id,
        },
    )
    assert duplicate_response.status_code == 409, duplicate_response.text
    assert manifest["audit_id"] == export_audit_id
    assert manifest["filters"]["performance_excluded"] is None
    assert manifest["filters"]["agency_review_verified"] is True
    assert any(item["id"] == created.json()["id"] for item in manifest["rows"])


def test_agency_export_excludes_reviewed_report_without_high_accuracy(
    client: ASGITestClient,
) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:11:46.000Z",
            source_model="YOLO26s medium-location",
            coordinate_gate_status="pass",
            gps={"latitude": 37.56651234, "longitude": 126.97801234, "accuracy_m": 15.1},
        ),
    )
    reviewed = client.patch(
        f"/reports/{created.json()['id']}/status",
        headers={"x-walksafe-actor-id": "agency.operator@example.com"},
        json={"status": "reviewed", "expected_updated_at": created.json()["updated_at"]},
    )
    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["metadata"].get("agency_review_verified") is not True

    response = client.get(
        "/reports/export",
        headers={"x-walksafe-actor-id": "agency.operator@example.com"},
        params={"format": "csv", "profile": "agency", "manifest": "true", "bundle": "true"},
    )

    assert response.status_code == 200, response.text
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        rows = json.loads(archive.read("manifest.json"))["rows"]
    assert created.json()["id"] not in {row["id"] for row in rows}


@pytest.mark.parametrize("actor_id", ["admin-shared", "field-shared", "system", "unknown", "anonymous"])
def test_agency_review_rejects_shared_and_system_actors(
    client: ASGITestClient,
    actor_id: str,
) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at=f"2026-05-22T15:12:{20 + len(actor_id)}.000Z",
            source_model=f"YOLO26s actor-{actor_id}",
            coordinate_gate_status="pass",
            gps={"latitude": 37.5667, "longitude": 126.9782, "accuracy_m": 7.5},
        ),
    )
    reviewed = client.patch(
        f"/reports/{created.json()['id']}/status",
        headers={"x-walksafe-actor-id": actor_id},
        json={"status": "reviewed", "expected_updated_at": created.json()["updated_at"]},
    )

    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["metadata"].get("agency_review_verified") is not True


def test_agency_export_excludes_a_forged_reserved_reviewer_actor(
    client: ASGITestClient,
) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:12:49.000Z",
            source_model="YOLO26s forged-reviewer",
            coordinate_gate_status="pass",
            gps={"latitude": 37.5668, "longitude": 126.9783, "accuracy_m": 7.5},
        ),
    )
    reviewed = client.patch(
        f"/reports/{created.json()['id']}/status",
        headers={"x-walksafe-actor-id": "operator.kim"},
        json={"status": "reviewed", "expected_updated_at": created.json()["updated_at"]},
    )
    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["metadata"]["agency_review_verified"] is True

    engine = create_engine(get_settings().database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE reports
                SET metadata = jsonb_set(
                    metadata,
                    '{agency_reviewed_by_actor_id}',
                    to_jsonb(CAST(:actor_id AS text)),
                    true
                )
                WHERE id = CAST(:report_id AS uuid)
                """
            ),
            {"actor_id": "anonymous", "report_id": created.json()["id"]},
        )

    exported = client.get(
        "/reports/export",
        headers={"x-walksafe-actor-id": "operator.lee"},
        params={"format": "csv", "profile": "agency", "manifest": "true", "bundle": "true"},
    )
    assert exported.status_code == 200, exported.text
    with zipfile.ZipFile(io.BytesIO(exported.content)) as archive:
        rows = json.loads(archive.read("manifest.json"))["rows"]
    assert created.json()["id"] not in {row["id"] for row in rows}


def test_export_reports_agency_profile_rejects_coordinate_redaction(client: ASGITestClient) -> None:
    response = client.get(
        "/reports/export",
        params={"format": "json", "profile": "agency", "redacted": "true"},
    )

    assert response.status_code == 400
    assert "requires exact report coordinates" in response.text


@pytest.mark.parametrize("actor_id", [None, "unknown", "anonymous", "admin-shared"])
def test_export_reports_agency_profile_requires_named_actor(
    client: ASGITestClient,
    actor_id: str | None,
) -> None:
    headers = {"x-walksafe-actor-id": actor_id} if actor_id is not None else {}
    response = client.get(
        "/reports/export",
        headers=headers,
        params={"format": "json", "profile": "agency"},
    )

    assert response.status_code == 403
    assert "named admin actor" in response.text


def test_export_reports_route_does_not_conflict_with_report_detail(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:10:00.000Z"),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]

    export_response = client.get("/reports/export", params={"model_key": "no_such_export_model"})
    detail_response = client.get(f"/reports/{report_id}")

    assert export_response.status_code == 200, export_response.text
    assert detail_response.status_code == 200, detail_response.text
    assert detail_response.json()["id"] == report_id


def test_export_reports_manifest_and_demo_filename(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audit_records: list[dict[str, object]] = []
    monkeypatch.setattr(
        "backend.app.api.reports.logger.info",
        lambda _message, *, extra: audit_records.append(extra["walksafe_audit"]),
    )
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:12:00.000Z", source_model="YOLO26s custom"),
    )
    assert created.status_code == 201, created.text

    response = client.get(
        "/reports/export",
        headers={"x-walksafe-actor-id": "admin@example.com"},
        params={
            "format": "json",
            "manifest": "true",
            "demo_filter": "exclude_fake",
            "model_key": "custom_tactile",
            "lat": 37.5665,
            "lng": 126.978,
            "radius_m": 100,
        },
    )

    assert response.status_code == 200, response.text
    assert response.headers["content-disposition"] == 'attachment; filename="walksafe-reports-demo-excluded.json"'
    body = response.json()
    assert body["schema_version"] == "walksafe.reports.export_manifest.v1"
    assert body["actor_id"] == "admin@example.com"
    assert body["audit_id"] == response.headers["x-walksafe-audit-id"]
    assert response.headers["x-walksafe-actor-id"] == "admin@example.com"
    assert body["filters"]["demo_filter"] == "exclude_fake"
    assert len(body["rows_sha256"]) == 64
    assert any(row["id"] == created.json()["id"] for row in body["rows"])
    assert any(record["audit_id"] == body["audit_id"] for record in audit_records)
    engine = create_engine(get_settings().database_url)
    try:
        with Session(engine) as session:
            durable_audit = session.scalar(
                select(ReportExportAudit)
                .where(ReportExportAudit.audit_id == uuid.UUID(body["audit_id"]))
                .order_by(ReportExportAudit.created_at.desc())
                .limit(1)
            )
            assert durable_audit is not None
            assert durable_audit.actor_id == "admin@example.com"
            assert durable_audit.row_count == len(body["rows"])
            assert durable_audit.location_precision == "exact-internal"
            assert durable_audit.rows_sha256 == body["rows_sha256"]
            assert "lat" not in durable_audit.filters
            assert "lng" not in durable_audit.filters
            assert durable_audit.filters["exact_location_filter_redacted"] is True
    finally:
        engine.dispose()


def test_export_bundle_contains_csv_and_manifest_from_one_snapshot(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:12:30.000Z", source_model="YOLO26s custom"),
    )
    assert created.status_code == 201, created.text

    response = client.get(
        "/reports/export",
        headers={"x-walksafe-actor-id": "bundle.admin@example.com"},
        params={"format": "csv", "manifest": "true", "bundle": "true", "model_key": "custom_tactile"},
    )

    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("application/zip")
    assert response.headers["x-walksafe-export-bundle"] == "csv+manifest"
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert set(archive.namelist()) == {"reports.csv", "manifest.json"}
        bundled_rows = list(csv.DictReader(io.StringIO(archive.read("reports.csv").decode("utf-8"))))
        bundled_manifest = json.loads(archive.read("manifest.json"))
    assert bundled_manifest["audit_id"] == response.headers["x-walksafe-audit-id"]
    assert bundled_manifest["count"] == len(bundled_rows)
    assert {row["id"] for row in bundled_rows} == {row["id"] for row in bundled_manifest["rows"]}
    assert created.json()["id"] in {row["id"] for row in bundled_rows}


def test_export_reports_geojson_grid_aggregate(client: ASGITestClient) -> None:
    first = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:13:00.000Z",
            gps={"latitude": 37.56651, "longitude": 126.97801, "accuracy_m": 8.0},
        ),
    )
    second = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:13:30.000Z",
            gps={"latitude": 37.56654, "longitude": 126.97804, "accuracy_m": 8.0},
        ),
    )
    assert first.status_code == 201, first.text
    assert second.status_code == 201, second.text

    response = client.get(
        "/reports/export",
        params={"format": "geojson", "aggregate": "grid", "model_key": "custom_tactile"},
    )

    assert response.status_code == 200, response.text
    assert response.headers["content-disposition"] == (
        'attachment; filename="walksafe-reports-demo-included-grid.geojson"'
    )
    body = response.json()
    assert body["type"] == "FeatureCollection"
    assert body["properties"]["aggregate"] == "grid"
    assert body["properties"]["location_precision"] == "exact-source-grid"
    assert response.headers["x-walksafe-location-precision"] == "exact-source-grid"
    assert any(feature["properties"]["count"] >= 2 for feature in body["features"])


@pytest.mark.parametrize("params", [{"redacted": "true"}, {"profile": "minimum"}])
def test_export_reports_grid_rejects_misleading_redaction_modes(
    client: ASGITestClient,
    params: dict[str, str],
) -> None:
    response = client.get(
        "/reports/export",
        params={"format": "geojson", "aggregate": "grid", **params},
    )

    assert response.status_code == 400
    assert "admin exact-location" in response.text


def test_reports_summary_uses_full_filtered_query_and_cluster_breakdown(client: ASGITestClient) -> None:
    created_from = fresh_created_from()
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:14:00.000Z",
            source_model="YOLO26s custom",
            gps={"latitude": 37.56651, "longitude": 126.97801, "accuracy_m": 8.0},
        ),
    )
    assert created.status_code == 201, created.text

    response = client.get(
        "/reports/summary",
        params={
            "created_from": created_from,
            "demo_filter": "exclude_fake",
            "grid_size_degrees": "0.001",
            "top_limit": "3",
        },
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] >= 1
    assert body["fake"] == 0
    assert body["non_fake"] >= 1
    assert body["located"] >= 1
    assert body["bounds"]["min_latitude"] <= 37.56651 <= body["bounds"]["max_latitude"]
    cluster = body["top_clusters"][0]
    assert "status_counts" in cluster
    assert "source_counts" in cluster
    assert "bounds" in cluster


def test_export_reports_csv_escapes_formula_like_values(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:15:00.000Z",
            trace_id="=IMPORTXML(\"https://example.test\")",
        ),
    )
    assert created.status_code == 201, created.text

    response = client.get("/reports/export", params={"format": "csv", "model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    row = next(row for row in csv_rows(response) if row["id"] == created.json()["id"])
    assert row["trace_id"].startswith("'=")


def test_report_status_history_note_reason_and_conflict(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:16:00.000Z", source_model="YOLO26s custom"),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]
    expected_updated_at = created.json()["updated_at"]

    reviewed = client.patch(
        f"/reports/{report_id}/status",
        headers={"x-walksafe-actor-id": "reviewer@example.com"},
        json={"status": "reviewed", "note": "현장 사진 확인", "expected_updated_at": expected_updated_at},
    )
    assert reviewed.status_code == 200, reviewed.text
    reviewed_body = reviewed.json()
    assert reviewed_body["status"] == "reviewed"
    assert reviewed_body["metadata"]["review_note"] == "현장 사진 확인"
    assert reviewed_body["metadata"]["status_history"][-1]["from"] == "new"
    assert reviewed_body["metadata"]["status_history"][-1]["to"] == "reviewed"
    assert reviewed_body["metadata"]["status_history"][-1]["actor_id"] == "reviewer@example.com"

    stale = client.patch(
        f"/reports/{report_id}/status",
        json={"status": "resolved", "expected_updated_at": expected_updated_at},
    )
    assert stale.status_code == 409, stale.text

    resolved = client.patch(
        f"/reports/{report_id}/status",
        json={
            "status": "resolved",
            "resolution_reason": "접수 후보로 정리",
            "expected_updated_at": reviewed_body["updated_at"],
        },
    )
    assert resolved.status_code == 200, resolved.text
    assert resolved.json()["metadata"]["resolution_reason"] == "접수 후보로 정리"
    assert len(resolved.json()["metadata"]["status_history"]) >= 2
    assert resolved.json()["metadata"]["status_history"][-1]["actor_id"] == "unknown"

    no_op = client.patch(f"/reports/{report_id}/status", json={"status": "resolved"})
    assert no_op.status_code == 409
    assert no_op.json()["detail"]["code"] == "report_status_unchanged"

    engine = create_engine(get_settings().database_url)
    try:
        with Session(engine) as session:
            status_audits = list(
                session.scalars(
                    select(ReportStatusAudit)
                    .where(ReportStatusAudit.report_id == uuid.UUID(report_id))
                    .order_by(ReportStatusAudit.created_at.asc())
                ).all()
            )
        assert [(audit.previous_status, audit.next_status) for audit in status_audits] == [
            ("new", "reviewed"),
            ("reviewed", "resolved"),
        ]
        assert status_audits[0].actor_id == "reviewer@example.com"
        assert status_audits[1].resolution_reason == "접수 후보로 정리"
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                with pytest.raises(DBAPIError):
                    connection.execute(
                        text("UPDATE report_status_audits SET actor_id = 'tampered' WHERE id = :audit_id"),
                        {"audit_id": status_audits[0].id},
                    )
            finally:
                transaction.rollback()
    finally:
        engine.dispose()
