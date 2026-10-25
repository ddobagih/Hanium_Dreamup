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
from backend.app.main import app, settings as app_settings  # noqa: E402
from backend.app.models import ReportExportAudit, ReportImageObject, ReportStatusAudit  # noqa: E402
from backend.app.schemas import (  # noqa: E402
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
)
from backend.app.services.privacy_lifecycle import record_consent_event  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402
from actor_assertion_v4_signing import GatewayV4Signer, V4_SIGN_AT_SEND  # noqa: E402


_JPEG_OUTPUT = io.BytesIO()
Image.new("RGB", (4, 4), color=(180, 160, 40)).save(_JPEG_OUTPUT, format="JPEG")
JPEG_BYTES = _JPEG_OUTPUT.getvalue()
GATEWAY_SECRET = "gateway-actor-assertion-secret-for-report-tests"
# w24 S20-41: writes need a request-bound v4 actor assertion (safe-methods default);
# the test Gateway stand-in signs each marked request from the exact bytes sent.
SIGNED_APP = GatewayV4Signer(app, GATEWAY_SECRET)


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
    return ASGITestClient(SIGNED_APP)


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
        "x-walksafe-actor-assertion": V4_SIGN_AT_SEND,
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
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
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


def jpeg_with_exif_orientation(orientation: int) -> bytes:
    output = io.BytesIO()
    exif = Image.Exif()
    exif[274] = orientation
    Image.new("RGB", (4, 4), color=(180, 160, 40)).save(output, format="JPEG", exif=exif)
    return output.getvalue()


@pytest.mark.parametrize("orientation", [2, 6, 8])
def test_report_v2_rejects_oriented_bbox_before_persistence(orientation: int) -> None:
    def unexpected_persistence(*_args, **_kwargs):  # noqa: ANN002, ANN003
        raise AssertionError("oriented report reached persistence")

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr("backend.app.api.reports._persist_v2_report", unexpected_persistence)
        response = ASGITestClient(app).post(
            "/reports/v2",
            data={"metadata": json.dumps(sample_v2_metadata())},
            files={"image": ("sample.jpg", jpeg_with_exif_orientation(orientation), "image/jpeg")},
        )

    assert response.status_code == 422
    assert response.json() == {"detail": {"code": "report_bbox_exif_orientation_unsupported"}}


@pytest.mark.parametrize("orientation", [None, 1])
def test_report_v2_normal_orientation_reaches_persistence(orientation: int | None) -> None:
    def persistence_reached(*_args, **_kwargs):  # noqa: ANN002, ANN003
        raise HTTPException(status_code=503, detail={"code": "persistence_reached"})

    image_bytes = JPEG_BYTES if orientation is None else jpeg_with_exif_orientation(orientation)
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr("backend.app.api.reports._persist_v2_report", persistence_reached)
        response = ASGITestClient(app).post(
            "/reports/v2",
            data={"metadata": json.dumps(sample_v2_metadata())},
            files={"image": ("sample.jpg", image_bytes, "image/jpeg")},
        )

    assert response.status_code == 503
    assert response.json() == {"detail": {"code": "persistence_reached"}}


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

    actor_id = f"gate-failure-{uuid.uuid4().hex}"
    record_report_consent(actor_id)
    response = client.post(
        "/reports/v2",
        headers=secured_field_headers(field_token, actor_id),
        data={"metadata": json.dumps(metadata)},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )

    assert response.status_code == 422, response.text
    assert response.json()["detail"]["code"] == "auto_report_gate_failed"
    assert reason in response.json()["detail"]["reasons"]


@pytest.mark.parametrize("transport_contract", [False, True], ids=["legacy", "transport"])
def test_secured_auto_report_cooldown_is_authoritative_by_actor_class_radius_and_time(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    transport_contract: bool,
) -> None:
    import base64
    from backend.app.api import reports

    field_token = "field-token-for-cooldown-tests-123456"
    source_commit = "f" * 40
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-cooldown-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", source_commit)
    actor_id = f"cooldown-{uuid.uuid4().hex}@example.com"
    record_report_consent(actor_id)
    headers = secured_field_headers(field_token, actor_id)
    captured_at = datetime.now(timezone.utc)
    transport_ids: dict[str, uuid.UUID] = {}
    requests: list[dict[str, object]] = []

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
        metadata_json = json.dumps(metadata)
        request_headers = dict(headers)
        if transport_contract:
            report_id = transport_ids.setdefault(metadata_json, uuid.uuid4())
            request_headers.update({
                "x-walksafe-report-id": str(report_id),
                "x-walksafe-report-payload-sha256": reports.report_transport_payload_sha256(
                    report_id, metadata_json.encode(), JPEG_BYTES,
                ),
                "x-walksafe-report-payload-bytes": str(len(metadata_json.encode()) + len(JPEG_BYTES)),
            })
        requests.append({
            "report_id": request_headers.get("x-walksafe-report-id"),
            "metadata_utf8_base64": base64.b64encode(metadata_json.encode()).decode(),
            "image_jpeg_base64": base64.b64encode(JPEG_BYTES).decode(),
        })
        return client.post(
            "/reports/v2",
            headers=request_headers,
            data={"metadata": metadata_json},
            files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
        )

    first = submit(latitude=37.5)
    replayed = submit(latitude=37.5)
    inside_radius = submit(latitude=37.50021, seconds=1)
    outside_radius = submit(latitude=37.50025, seconds=2)
    voice_override = submit(latitude=37.5, trigger="voice", seconds=3)

    assert first.status_code == 201, first.text
    assert first.json()["creation_outcome"] == "CREATED"
    assert replayed.status_code == 201, replayed.text
    assert replayed.json()["creation_outcome"] == "REPLAYED"
    assert replayed.json()["id"] == first.json()["id"]
    assert replayed.json()["transport_receipt"] == first.json()["transport_receipt"]
    if transport_contract:
        assert inside_radius.status_code == 429, inside_radius.text
        assert inside_radius.headers["Retry-After"] == "600"
        assert inside_radius.json() == {"detail": {
            "code": "automatic_report_cooldown_active",
            "creation_outcome": "SUPPRESSED_BY_COOLDOWN",
        }}
    else:
        assert inside_radius.status_code == 201, inside_radius.text
        assert inside_radius.json()["id"] == first.json()["id"]
        assert inside_radius.json()["creation_outcome"] == "SUPPRESSED_BY_COOLDOWN"
        assert inside_radius.json()["transport_receipt"] is None
    assert outside_radius.status_code == 201, outside_radius.text
    assert outside_radius.json()["creation_outcome"] == "CREATED"
    assert voice_override.status_code == 201, voice_override.text
    assert voice_override.json()["creation_outcome"] == "CREATED"
    fixtures = {
        name: {"status": response.status_code, "body": response.json(),
               "retry_after": response.headers.get("Retry-After"), "request": request}
        for (name, response), request in zip(
            (("created", first), ("replayed", replayed), ("suppressed", inside_radius)), requests,
        )
    }
    fixture_path = tmp_path / "creation-outcomes.json"
    fixture_path.write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + "\n")
    print(f"S20_6_HTTP_FIXTURE={fixture_path}")


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
        transport = httpx.ASGITransport(app=SIGNED_APP)
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
    assert {response.json()["creation_outcome"] for response in responses} == {
        "CREATED", "SUPPRESSED_BY_COOLDOWN",
    }


def test_secured_auto_report_exact_retry_survives_capture_age_without_new_storage(
    client: ASGITestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    field_token = "field-token-for-stale-retry-123456"
    source_commit = "e" * 40
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-stale-retry-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", source_commit)
    actor_id = f"stale.retry.{uuid.uuid4().hex}@example.com"
    record_report_consent(actor_id)
    headers = secured_field_headers(field_token, actor_id)
    captured_at = datetime.now(timezone.utc)
    metadata = sample_v2_metadata(
        source="android",
        source_model="android/custom-tactile-contract",
        source_commit=source_commit,
        captured_at=captured_at.isoformat(),
        coordinate_gate_status="pass",
        gps={"latitude": 37.61, "longitude": 127.11, "accuracy_m": 5.0},
    )

    def submit(
        value: dict[str, object], *,
        request_headers: dict[str, str] | None = None,
        image: bytes = JPEG_BYTES,
    ):
        return client.post(
            "/reports/v2", headers=request_headers or headers,
            data={"metadata": json.dumps(value)},
            files={"image": ("sample.jpg", image, "image/jpeg")},
        )

    first = submit(metadata)
    assert first.status_code == 201, first.text
    with SessionLocal() as db:
        stored_rows = db.execute(text("SELECT count(*) FROM reports")).scalar_one()
    stored_images = set(app_settings.upload_dir.glob("*.wse"))

    advanced_now = captured_at + timedelta(minutes=6)

    class AdvancedDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return advanced_now.replace(tzinfo=None) if tz is None else advanced_now.astimezone(tz)

    monkeypatch.setattr("backend.app.services.report_application.datetime", AdvancedDatetime)
    retry = submit(metadata)
    assert retry.status_code == 201, retry.text
    assert retry.json()["id"] == first.json()["id"]
    with SessionLocal() as db:
        assert db.execute(text("SELECT count(*) FROM reports")).scalar_one() == stored_rows
    assert set(app_settings.upload_dir.glob("*.wse")) == stored_images

    new_stale_report = submit({
        **metadata, "captured_at": (captured_at + timedelta(seconds=1)).isoformat(),
    })
    assert new_stale_report.status_code == 422, new_stale_report.text
    assert "captured_at_stale" in new_stale_report.json()["detail"]["reasons"]

    different_image = io.BytesIO()
    Image.new("RGB", (4, 4), color=(40, 160, 180)).save(different_image, format="JPEG")
    wrong_image = submit(metadata, image=different_image.getvalue())
    wrong_metadata = submit({**metadata, "heading": 182.0})
    other_actor = f"other.stale.retry.{uuid.uuid4().hex}@example.com"
    record_report_consent(other_actor)
    wrong_actor = submit(metadata, request_headers=secured_field_headers(field_token, other_actor))
    assert wrong_image.status_code == 422, wrong_image.text
    assert "captured_at_stale" in wrong_image.json()["detail"]["reasons"]
    assert wrong_metadata.status_code == 409, wrong_metadata.text
    assert wrong_actor.status_code == 422, wrong_actor.text
    assert "captured_at_stale" in wrong_actor.json()["detail"]["reasons"]
    with SessionLocal() as db:
        assert db.execute(text("SELECT count(*) FROM reports")).scalar_one() == stored_rows
    assert set(app_settings.upload_dir.glob("*.wse")) == stored_images


@pytest.mark.parametrize("changed", [
    {"gps": {"latitude": 37.5666, "longitude": 126.978, "accuracy_m": 9.5}},
    {"bbox": {"x": 0.1, "y": 0.35, "width": 0.4, "height": 0.22}},
    {"source_model": "fake/changed-model-contract"},
    {"confidence": 0.90},
    {"heading": 182.0},
], ids=["gps", "bbox", "model", "confidence", "heading"])
def test_legacy_report_replay_binds_sanitized_metadata(
    client: ASGITestClient, changed: dict[str, object],
) -> None:
    metadata = sample_v2_metadata(trigger="on_screen", auto_reported=False)
    headers = {"x-walksafe-actor-id": f"legacy-replay-{uuid.uuid4().hex}"}

    def submit(value: dict[str, object]):
        return client.post(
            "/reports/v2", headers=headers,
            data={"metadata": json.dumps(value)},
            files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
        )

    first = submit(metadata)
    changed_metadata = {**metadata, **changed}
    second = submit(changed_metadata)
    retry = submit(changed_metadata)
    assert first.status_code == 201, first.text
    for response in (second, retry):
        assert response.status_code == 409, response.text
        assert response.json() == {"detail": {"code": "report_idempotency_conflict"}}

    original_retry = submit(metadata)
    assert original_retry.status_code == 201, original_retry.text
    assert original_retry.json()["id"] == first.json()["id"]


def test_legacy_report_replay_keeps_normalization_and_actor_generation_boundaries(
    client: ASGITestClient,
) -> None:
    metadata = sample_v2_metadata(trigger="on_screen", auto_reported=False)
    actor = f"legacy-boundary-{uuid.uuid4().hex}"

    def submit(actor_id: str, generation: int, value: dict[str, object]):
        return client.post(
            "/reports/v2", headers={
                "x-walksafe-actor-id": actor_id,
                "x-walksafe-account-generation": str(generation),
            }, data={"metadata": json.dumps(value)},
            files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
        )

    first = submit(actor, 1, metadata)
    normalized_retry = submit(actor, 1, {
        **metadata, "client_private_unused_field": "must-be-discarded"
    })
    other_actor = submit(f"other-{uuid.uuid4().hex}", 1, metadata)
    other_generation = submit(actor, 2, metadata)
    for response in (first, normalized_retry, other_actor, other_generation):
        assert response.status_code == 201, response.text
    assert normalized_retry.json()["id"] == first.json()["id"]
    assert "client_private_unused_field" not in normalized_retry.json()["metadata"]
    assert len({first.json()["id"], other_actor.json()["id"], other_generation.json()["id"]}) == 3


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
    [("auto", False), ("on_screen", True), ("voice", True)],
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
    assert response.json() == {
        "detail": {
            "code": "report_metadata_invalid",
            "errors": [{"field": ["metadata"], "type": "invalid_value"}],
        },
    }


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


@pytest.mark.parametrize(
    ("security_enabled", "source", "report_commit", "backend_commit", "accepted"),
    [
        (True, "android", "d" * 40, "d" * 40, True),
        (True, "android", "D" * 40, "d" * 40, True),
        (True, "android", "e" * 40, "d" * 40, False),
        (True, "android", None, "d" * 40, False),
        (True, "android", "d" * 40, "", False),
        (True, "server", "e" * 40, "d" * 40, True),
        (False, "android", "e" * 40, "d" * 40, True),
    ],
    ids=[
        "same-commit", "same-commit-case", "other-commit", "missing-commit",
        "backend-commit-unset", "non-android-source", "security-disabled",
    ],
)
def test_s20_7_secured_android_provenance_keeps_current_combination_only(
    security_enabled: bool,
    source: str,
    report_commit: str | None,
    backend_commit: str,
    accepted: bool,
) -> None:
    # S20-7 / FP-039-DETAIL-03 interim: only the current approved app+server
    # combination is active. Exact full-commit matching stays fail-closed until an
    # approved previous-combination allowlist exists; this is not relaxed here.
    from types import SimpleNamespace

    from backend.app.api.reports import _enforce_secured_android_provenance

    parsed = SimpleNamespace(source=source, source_commit=report_commit)
    settings = SimpleNamespace(
        field_test_security_enabled=security_enabled,
        walksafe_source_commit=backend_commit,
    )
    if accepted:
        assert _enforce_secured_android_provenance(parsed, settings) is None
        return
    with pytest.raises(HTTPException) as rejected:
        _enforce_secured_android_provenance(parsed, settings)
    assert rejected.value.status_code == 422
    assert rejected.value.detail["code"] == "android_provenance_gate_failed"


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


@pytest.mark.parametrize("trigger", ["on_screen", "voice"])
@pytest.mark.parametrize(
    "class_name,model_class_id",
    [
        ("damaged_tactile_block", 1),
    ],
)
def test_create_report_v2_stores_explicit_damage_without_auto_report(
    client: ASGITestClient,
    trigger: str,
    class_name: str,
    model_class_id: int,
) -> None:
    metadata = sample_v2_metadata(
        class_name=class_name,
        model_class_id=model_class_id,
        captured_at="2026-05-22T12:40:00.000Z",
        trigger=trigger,
        auto_reported=False,
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == model_class_id
    assert body["class_name"] == class_name
    assert body["metadata"]["trigger"] == trigger
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
    assert response.headers["cache-control"] == "private, no-store"
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
    assert response.headers["cache-control"] == "private, no-store"
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
    assert response.headers["x-walksafe-export-profile"] == "internal"
    assert response.headers["x-walksafe-audit-id"]
    assert response.headers["x-walksafe-actor-id"]
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


@pytest.mark.parametrize(
    ("export_format", "include_manifest", "bundle"),
    [
        ("csv", False, False),
        ("json", False, False),
        ("geojson", False, False),
        ("json", True, False),
        ("csv", True, True),
    ],
)
def test_export_reports_minimum_public_response_metadata_allowlist(
    client: ASGITestClient,
    export_format: str,
    include_manifest: bool,
    bundle: bool,
) -> None:
    actor_id = "minimum.public.actor@example.com"
    audit_id = str(uuid.uuid4())
    created_from = fresh_created_from()
    created_to = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:11:45.000Z"),
    )
    assert created.status_code == 201, created.text

    response = client.get(
        "/reports/export",
        headers={"x-walksafe-actor-id": actor_id},
        params={
            "format": export_format,
            "profile": "minimum",
            "manifest": str(include_manifest).lower(),
            "bundle": str(bundle).lower(),
            "audit_id": audit_id,
            "model_key": "custom_tactile",
            "trigger": "auto",
            "created_from": created_from,
            "created_to": created_to,
            "lat": 37.5665,
            "lng": 126.978,
            "radius_m": 100,
        },
    )

    assert response.status_code == 200, response.text
    assert response.headers["x-walksafe-export-profile"] == "minimum"
    assert response.headers["x-walksafe-location-precision"] == "rounded"
    assert "x-walksafe-audit-id" not in response.headers
    assert "x-walksafe-actor-id" not in response.headers
    assert "x-walksafe-demo-filter" not in response.headers
    extension = "bundle.zip" if bundle else export_format
    assert response.headers["content-disposition"] == (
        f'attachment; filename="walksafe-reports-minimum.{extension}"'
    )
    assert actor_id not in str(response.headers)
    assert audit_id not in str(response.headers)

    if bundle:
        with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
            assert set(archive.namelist()) == {"reports.csv", "manifest.json"}
            assert all(member.date_time == (1980, 1, 1, 0, 0, 0) for member in archive.infolist())
            csv_text = archive.read("reports.csv").decode("utf-8")
            manifest_text = archive.read("manifest.json").decode("utf-8")
        rows = list(csv.DictReader(io.StringIO(csv_text)))
        body = json.loads(manifest_text)
        public_texts = [csv_text, manifest_text]
    elif export_format == "csv":
        rows = csv_rows(response)
        body = None
        public_texts = [response.text]
    else:
        body = response.json()
        rows = body.get("rows", []) if include_manifest else body
        public_texts = [response.text]

    for public_text in public_texts:
        for private_value in (actor_id, audit_id, created_from, created_to, "custom_tactile", "37.5665", "126.978"):
            assert private_value not in public_text

    if export_format == "geojson":
        assert body["type"] == "FeatureCollection"
        assert body["properties"] == {"location_precision": "rounded"}
        assert body["features"]
        assert all(feature["id"] == "" for feature in body["features"])
        assert all(feature["properties"]["trigger"] == "" for feature in body["features"])
        assert any(feature["geometry"] == {"type": "Point", "coordinates": [126.98, 37.57]} for feature in body["features"])
    else:
        assert rows
        assert all(row["id"] == "" and row["trigger"] == "" for row in rows)
        assert any(float(row["latitude"]) == 37.57 and float(row["longitude"]) == 126.98 for row in rows)

    if include_manifest:
        assert set(body) == {"schema_version", "profile", "format", "count", "rows"}
        assert body["schema_version"] == "walksafe.reports.export_manifest.v1"
        assert body["profile"] == "minimum"
        assert body["format"] == export_format
        assert body["count"] == len(body["rows"])
        assert body["rows"]


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


def _s20_7_table(*entries: dict[str, object], current: str = "d" * 40):
    from backend.app.services.android_release_allowlist import parse_approved_android_releases
    return parse_approved_android_releases(json.dumps(list(entries)), current_commit=current)


@pytest.mark.parametrize(
    ("report_commit", "apk", "model", "accepted"),
    [
        ("d" * 40, None, None, True),
        ("d" * 40, "1" * 64, "2" * 64, True),
        ("e" * 40, "b" * 64, "a" * 64, True),
        ("E" * 40, "B" * 64, "A" * 64, True),
        ("e" * 40, "b" * 64, "c" * 64, False),
        ("e" * 40, "b" * 64, None, False),
        ("e" * 40, "0" * 64, "a" * 64, False),
        ("e" * 40, None, "a" * 64, False),
        ("f" * 40, "b" * 64, "a" * 64, False),
        (None, None, None, False),
    ],
    ids=[
        "current-unpinned", "current-any-contract", "previous-bundle", "previous-bundle-case",
        "previous-other-model", "previous-missing-model", "previous-other-apk",
        "previous-missing-apk", "unlisted-commit", "missing-commit",
    ],
)
def test_s20_7_approved_release_table_accepts_only_listed_bundles(
    report_commit: str | None, apk: str | None, model: str | None, accepted: bool,
) -> None:
    from types import SimpleNamespace
    from backend.app.api.reports import _enforce_secured_android_provenance
    settings = SimpleNamespace(
        field_test_security_enabled=True,
        walksafe_source_commit="d" * 40,
        approved_android_releases=_s20_7_table(
            {"source_commit": "d" * 40, "report_schema": "reports.v2"},
            {
                "source_commit": "e" * 40, "report_schema": "reports.v2",
                "apk_sha256": "b" * 64, "model_config_sha256": "a" * 64,
            },
        ),
    )
    parsed = SimpleNamespace(
        source="android", source_commit=report_commit, apk_sha256=apk, model_config_sha256=model,
    )
    if accepted:
        assert _enforce_secured_android_provenance(parsed, settings) is None
        return
    with pytest.raises(HTTPException) as rejected:
        _enforce_secured_android_provenance(parsed, settings)
    assert rejected.value.status_code == 422
    assert rejected.value.detail["code"] == "android_provenance_gate_failed"


@pytest.mark.parametrize("table", [[("d" * 40,)], ["not-a-table"], ({"source_commit": "d" * 40},)])
def test_s20_7_malformed_runtime_table_fails_closed(table: object) -> None:
    from types import SimpleNamespace
    from backend.app.services.android_release_allowlist import approved_android_release
    settings = SimpleNamespace(walksafe_source_commit="d" * 40, approved_android_releases=table)
    assert approved_android_release(
        settings, source_commit="d" * 40, apk_sha256=None, model_config_sha256=None,
    ) is False


@pytest.mark.parametrize(
    "raw",
    [
        "not json",
        "{}",
        "[]",
        json.dumps([{"source_commit": "e" * 40, "report_schema": "reports.v2"}]),
        json.dumps([{"source_commit": "d" * 40, "report_schema": "reports.v2"}] * 2),
        json.dumps([{"source_commit": "D" * 40, "report_schema": "reports.v2"}]),
        json.dumps([{"source_commit": "d" * 40}]),
        json.dumps([{"source_commit": "d" * 40, "report_schema": "reports.v1"}]),
        json.dumps([{"source_commit": "d" * 40, "report_schema": "reports.v2", "app": "1"}]),
        json.dumps([{"source_commit": "d" * 40, "report_schema": "reports.v2", "apk_sha256": "A" * 64}]),
        json.dumps([{"source_commit": "d" * 40, "report_schema": "reports.v2", "model_config_sha256": 1}]),
        '[{"source_commit":"' + "d" * 40 + '","source_commit":"' + "d" * 40 + '","report_schema":"reports.v2"}]',
        json.dumps(
            [{"source_commit": "d" * 40, "report_schema": "reports.v2"}]
            + [{"source_commit": f"{index:040x}", "report_schema": "reports.v2"} for index in range(8)]
        ),
    ],
    ids=[
        "not-json", "object", "empty", "missing-current", "duplicate-commit", "uppercase-commit",
        "missing-schema", "other-schema", "unknown-key", "uppercase-digest", "non-string-digest",
        "duplicate-json-key", "too-many",
    ],
)
def test_s20_7_invalid_approved_release_table_refuses_startup(
    monkeypatch: pytest.MonkeyPatch, raw: str,
) -> None:
    from backend.app.config import Settings
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "d" * 40)
    monkeypatch.setenv("WALKSAFE_APPROVED_ANDROID_RELEASES", raw)
    with pytest.raises(ValueError, match="WALKSAFE_APPROVED_ANDROID_RELEASES"):
        Settings()


def test_s20_7_unset_table_is_the_current_release_only(monkeypatch: pytest.MonkeyPatch) -> None:
    from backend.app.config import Settings
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "d" * 40)
    monkeypatch.delenv("WALKSAFE_APPROVED_ANDROID_RELEASES", raising=False)
    assert Settings().approved_android_releases is None
    monkeypatch.setenv(
        "WALKSAFE_APPROVED_ANDROID_RELEASES",
        json.dumps([{"source_commit": "d" * 40, "report_schema": "reports.v2"}]),
    )
    configured = Settings().approved_android_releases
    assert configured is not None and [entry.source_commit for entry in configured] == ["d" * 40]


def test_s20_7_secured_http_accepts_approved_previous_bundle_and_keeps_commit_for_audit(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    field_token = "field-token-for-release-table-tests-123456"
    current_commit, previous_commit = "d" * 40, "e" * 40
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-release-table-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", current_commit)
    monkeypatch.setattr(app_settings, "approved_android_releases", _s20_7_table(
        {"source_commit": current_commit, "report_schema": "reports.v2"},
        {"source_commit": previous_commit, "report_schema": "reports.v2", "model_config_sha256": "a" * 64},
    ))
    actor_id = f"release-table-{uuid.uuid4().hex}@example.com"
    record_report_consent(actor_id)

    def submit(model_config_sha256: str, latitude: float):
        metadata = sample_v2_metadata(
            source="android",
            source_model="android/custom-tactile-contract",
            source_commit=previous_commit,
            model_config_sha256=model_config_sha256,
            captured_at=datetime.now(timezone.utc).isoformat(),
            coordinate_gate_status="pass",
            gps={"latitude": latitude, "longitude": 127.0, "accuracy_m": 5.0},
            trigger="voice",
            auto_reported=False,
        )
        return client.post(
            "/reports/v2",
            headers=secured_field_headers(field_token, actor_id),
            data={"metadata": json.dumps(metadata)},
            files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
        )

    mismatched = submit("c" * 64, 37.41)
    assert mismatched.status_code == 422, mismatched.text
    assert mismatched.json()["detail"]["code"] == "android_provenance_gate_failed"
    accepted = submit("a" * 64, 37.42)
    assert accepted.status_code == 201, accepted.text
    assert accepted.json()["metadata"]["source_commit"] == previous_commit
    from backend.app.models import Report
    with SessionLocal() as db:
        stored = db.get(Report, uuid.UUID(str(accepted.json()["id"])))
        assert stored is not None
        assert stored.payload["source_commit"] == previous_commit


# --- S20-7 follow-up (be-F4, be-F5) ---


@pytest.mark.parametrize(
    "previous",
    [
        {"source_commit": "e" * 40, "report_schema": "reports.v2"},
        {"source_commit": "e" * 40, "report_schema": "reports.v2", "apk_sha256": "b" * 64},
    ],
    ids=["unpinned-previous", "apk-only-previous"],
)
def test_s20_7_previous_release_entries_must_pin_the_model_contract(
    monkeypatch: pytest.MonkeyPatch, previous: dict[str, object],
) -> None:
    from backend.app.config import Settings
    from backend.app.services.android_release_allowlist import parse_approved_android_releases

    current = {"source_commit": "d" * 40, "report_schema": "reports.v2"}
    with pytest.raises(ValueError, match="must pin model_config_sha256"):
        parse_approved_android_releases(json.dumps([current, previous]), current_commit="d" * 40)
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "d" * 40)
    monkeypatch.setenv("WALKSAFE_APPROVED_ANDROID_RELEASES", json.dumps([current, previous]))
    with pytest.raises(ValueError, match="WALKSAFE_APPROVED_ANDROID_RELEASES"):
        Settings()
    # The current release may stay unpinned; a pinned previous bundle is accepted.
    pinned = {**previous, "model_config_sha256": "a" * 64}
    table = parse_approved_android_releases(json.dumps([current, pinned]), current_commit="d" * 40)
    assert [(entry.source_commit, entry.model_config_sha256) for entry in table] == [
        ("d" * 40, None), ("e" * 40, "a" * 64),
    ]


def test_s20_7_runtime_check_has_no_always_true_schema_comparison() -> None:
    import inspect
    from backend.app.services import android_release_allowlist as allowlist

    assert "report_schema" not in inspect.signature(allowlist.approved_android_release).parameters
    # The module states the trust boundary: compatibility, not authenticity.
    assert "self-declared compatibility gate" in (allowlist.__doc__ or "")
    assert "does not prove authenticity" in (allowlist.__doc__ or "")


def _legacy_v1_metadata(**overrides: object) -> dict[str, object]:
    metadata = {
        "class_id": 0,
        "class_name": "damaged_tactile_block",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "source": "android",
        "gps": {"latitude": 37.43, "longitude": 127.0, "accuracy_m": 5.0},
        "heading": 181.0,
    }
    metadata.update(overrides)
    return metadata


def test_s20_7_secured_legacy_reports_endpoint_rejects_android_source(
    client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from sqlalchemy import func

    from backend.app.models import Report

    field_token = "field-token-for-legacy-android-tests-123456"
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "admin_token", "admin-token-for-legacy-android-tests-123456")
    monkeypatch.setattr(app_settings, "walksafe_source_commit", "d" * 40)
    actor_id = f"legacy-android-{uuid.uuid4().hex}@example.com"
    record_report_consent(actor_id)

    def submit(metadata: dict[str, object]):
        return client.post(
            "/reports",
            headers=secured_field_headers(field_token, actor_id),
            data={"metadata": json.dumps(metadata)},
            files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
        )

    with SessionLocal() as db:
        before = db.scalar(select(func.count()).select_from(Report).where(Report.source == "android"))
    # Even a bundle that /reports/v2 would approve cannot enter through v1.
    rejected = submit(_legacy_v1_metadata(source_commit="d" * 40))
    assert rejected.status_code == 422, rejected.text
    assert rejected.json()["detail"]["code"] == "android_provenance_gate_failed"
    with SessionLocal() as db:
        after = db.scalar(select(func.count()).select_from(Report).where(Report.source == "android"))
    assert after == before
    # Other legacy sources keep their existing path.
    other = submit(_legacy_v1_metadata(source="server"))
    assert other.status_code != 422 or other.json()["detail"].get("code") != "android_provenance_gate_failed"
    assert other.status_code == 201, other.text
