"""The report row and consent decision share one PostgreSQL transaction fence."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from copy import copy
from datetime import UTC, datetime
import io
import os
from pathlib import Path
import threading
import time
import uuid

from alembic.config import Config
from alembic.script import ScriptDirectory
from fastapi import HTTPException
from PIL import Image
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import sessionmaker

from backend.app.api import reports
from backend.app.services import report_application
from backend.app.config import get_settings
from backend.app.models import Report, ReportImageObject
from backend.app.schemas import (
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    ReportV2Metadata,
)
from backend.app.services.privacy_lifecycle import (
    assert_privacy_runtime_database_role,
    lock_privacy_subject_exclusive,
    privacy_subject_hmac,
    record_consent_event,
)
from backend.app.services.report_image_keys import create_report_image_key_manager


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _record_consent(db, actor: str, *, automatic: bool, previous: str | None):
    return record_consent_event(
        db,
        actor_id=actor,
        account_generation=1,
        installation_id=f"install_{actor}",
        request_id=f"consent_{uuid.uuid4().hex}",
        client_revision=1 if previous is None else 2,
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
        raw_source_collection=True,
        automatic_reporting=automatic,
        mobile_network_transfer=False,
        training_reuse=False,
        secret=get_settings().privacy_hmac_secret,
        expected_previous_backend_receipt_sha256=previous,
    )


def _assert_advisory_wait(
    engine, *, waiting_pid: int, holding_pid: int, waiting_mode: str,
) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        with engine.connect() as connection:
            row = connection.execute(
                text(
                    "SELECT activity.wait_event_type, activity.wait_event, "
                    "pg_blocking_pids(activity.pid) AS blockers, "
                    "(SELECT lock.mode FROM pg_locks AS lock "
                    "WHERE lock.pid = activity.pid AND lock.locktype = 'advisory' "
                    "AND NOT lock.granted LIMIT 1) AS waiting_mode "
                    "FROM pg_stat_activity AS activity WHERE activity.pid = :waiting_pid"
                ),
                {"waiting_pid": waiting_pid},
            ).one_or_none()
        if (
            row is not None
            and row.wait_event_type == "Lock"
            and row.wait_event == "advisory"
            and row.waiting_mode == waiting_mode
            and holding_pid in row.blockers
        ):
            print(f"s19-33 advisory_wait=OBSERVED mode={waiting_mode} blocker_match=YES")
            return
        time.sleep(0.02)
    raise AssertionError("the second transaction did not wait on the first transaction's advisory lock")


@pytest.mark.parametrize("first", ["withdrawal", "report"])
def test_s19_33_consent_and_final_report_commit_serialize(
    tmp_path, monkeypatch, first: str,
) -> None:
    runtime_url = os.environ.get("WALKSAFE_S19_33_RUNTIME_DATABASE_URL", "").strip()
    if not runtime_url:
        pytest.skip("WALKSAFE_S19_33_RUNTIME_DATABASE_URL is required for this runtime-role race")
    parsed_url = make_url(runtime_url)
    assert parsed_url.database and "test" in parsed_url.database.lower()
    engine = create_engine(runtime_url, pool_pre_ping=True)
    head = ScriptDirectory.from_config(
        Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    ).get_current_head()
    with engine.connect() as connection:
        assert connection.dialect.name == "postgresql"
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == head
    factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    with factory() as db:
        assert_privacy_runtime_database_role(db)
        runtime_user = db.scalar(text("SELECT current_user"))
        assert runtime_user == parsed_url.username
        print(f"s19-33 runtime_login={runtime_user} role_guard=PASS")
    actor = f"s19-33-{uuid.uuid4().hex[:12]}"
    subject = privacy_subject_hmac(actor, 1, get_settings().privacy_hmac_secret)
    with factory() as db:
        initial = _record_consent(db, actor, automatic=True, previous=None)
    report_id = uuid.uuid4()
    image = io.BytesIO()
    Image.new("RGB", (4, 4), color=(60, 80, 100)).save(image, format="JPEG")
    image_bytes = image.getvalue()
    metadata = {
        "schema_version": "detect.v2",
        "model_key": "custom_tactile",
        "source_model": "fake/s19-33-consent-commit",
        "model_class_id": 1,
        "class_name": "damaged_tactile_block",
        "category": "tactile",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.3, "width": 0.4, "height": 0.2},
        "threshold_used": 0.25,
        "captured_at": datetime.now(UTC).isoformat(),
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "trigger": "auto",
        "auto_reported": True,
        "coordinate_gate_status": "pass",
    }
    parsed = ReportV2Metadata.model_validate(metadata)
    metadata_bytes = parsed.model_dump_json().encode()
    contract = reports.ReportTransportContract(
        report_id=report_id,
        payload_sha256=reports.report_transport_payload_sha256(
            report_id, metadata_bytes, image_bytes,
        ),
        payload_bytes=len(metadata_bytes) + len(image_bytes),
    )
    settings = copy(get_settings())
    settings.field_test_security_enabled = True
    settings.upload_dir = tmp_path
    settings.maintenance_lock_path = None
    settings.maintenance_lock_group_gid = None
    key_manager = create_report_image_key_manager(settings)
    lock_held = threading.Event()
    release_first = threading.Event()
    second_started = threading.Event()
    pids: dict[str, int] = {}

    def submit():
        with factory() as db:
            pids["report"] = db.scalar(text("SELECT pg_backend_pid()"))
            if first == "withdrawal":
                second_started.set()
            return reports._persist_v2_report(
                db,
                parsed=parsed,
                content=image_bytes,
                content_type="image/jpeg",
                actor_id=actor,
                account_generation=1,
                settings=settings,
                key_manager=key_manager,
                transport_contract=contract,
            )

    def withdraw():
        with factory() as db:
            pids["withdrawal"] = db.scalar(text("SELECT pg_backend_pid()"))
            if first == "withdrawal":
                lock_privacy_subject_exclusive(db, subject, 1)
                lock_held.set()
                assert release_first.wait(timeout=10)
            else:
                second_started.set()
            return _record_consent(
                db,
                actor,
                automatic=False,
                previous=initial.event.receipt_sha256,
            )

    if first == "report":
        original_lock = report_application.lock_report_ingest_transaction

        def lock_and_pause(*args, **kwargs):
            original_lock(*args, **kwargs)
            lock_held.set()
            assert release_first.wait(timeout=10)

        monkeypatch.setattr(report_application, "lock_report_ingest_transaction", lock_and_pause)

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            first_future = executor.submit(withdraw if first == "withdrawal" else submit)
            assert lock_held.wait(timeout=10)
            second_future = executor.submit(submit if first == "withdrawal" else withdraw)
            assert second_started.wait(timeout=10)
            _assert_advisory_wait(
                engine,
                waiting_pid=pids["report" if first == "withdrawal" else "withdrawal"],
                holding_pid=pids[first],
                waiting_mode="ShareLock" if first == "withdrawal" else "ExclusiveLock",
            )
            assert not second_future.done(), "the second transaction must wait for the consent fence"
            release_first.set()
            first_result = first_future.result(timeout=20)
            if first == "withdrawal":
                assert first_result.event.automatic_reporting is False
                with pytest.raises(HTTPException) as denied:
                    second_future.result(timeout=20)
                assert denied.value.status_code == 409
                assert denied.value.detail["code"] == "automatic_reporting_consent_required"
            else:
                assert first_result.creation_outcome == "CREATED"
                assert first_result.transport_receipt.report_id == report_id
                assert second_future.result(timeout=20).event.automatic_reporting is False

        with factory() as db:
            stored = db.get(Report, report_id)
            image_row = db.get(ReportImageObject, report_id)
            status_row = db.execute(
                reports._report_transport_status_statement(
                    report_id, privacy_subject=subject, account_generation=1,
                )
            ).one_or_none()
            assert (stored is not None) is (first == "report")
            assert (image_row is not None) is (first == "report")
            assert (status_row is not None) is (first == "report")
            if status_row is not None:
                status = reports._report_transport_status_response(status_row)
                assert status.persistence_state == "PERSISTED"
                assert status.transport_receipt.report_id == report_id
        assert (tmp_path / f"{report_id}.wse").exists() is (first == "report")
    finally:
        release_first.set()
        engine.dispose()
