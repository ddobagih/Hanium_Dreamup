"""A delivery recorded during account deletion must not be omitted from the copy inventory."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import uuid

import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError

from backend.app.models import AccountDeletionRequest
from backend.app.services.privacy_lifecycle import privacy_subject_hmac
from scripts.account_deletion_worker import (
    AccountDeletionWorkerError,
    process_request,
)
from test_account_deletion_server_inventory_postgres import (
    _roots,
    _seed_report,
    _server_items,
)
from test_fp046_postgres_integration import (
    PRIVACY_SECRET,
    _accept,
    _record_consent,
    _session_factory,
    _worker_session_factory,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _record_delivery(connection, report_id: uuid.UUID) -> uuid.UUID:
    # The admin function first locks this report row, then records the delivery.
    assert connection.execute(
        text("SELECT id FROM reports WHERE id = :id FOR UPDATE NOWAIT"),
        {"id": report_id},
    ).scalar_one() == report_id
    delivery_id = uuid.uuid4()
    connection.execute(
        text(
            "INSERT INTO report_institution_delivery_events "
            "(id, report_id, review_decision_id, revision, institution, channel, "
            "recipient, status, reason, observed_at, expected_revision, "
            "idempotency_key, admin_id, session_id, device_id, correlation_id) "
            "VALUES (:id, :report_id, :decision, 1, '기관-copy-race', 'EMAIL', "
            "'recipient@example.invalid', 'SUBMITTED', 'manual delivery', "
            "clock_timestamp(), 0, :idempotency, 'admin-copy-race', :session, "
            "'device-copy-race', :correlation)"
        ),
        {
            "id": delivery_id,
            "report_id": report_id,
            "decision": uuid.uuid4(),
            "idempotency": uuid.uuid4(),
            "session": uuid.uuid4(),
            "correlation": uuid.uuid4(),
        },
    )
    return delivery_id


@pytest.mark.parametrize("delivery_phase", ["none", "before_worker", "after_quarantine"])
def test_delivery_inventory_stays_consistent_across_report_delete(
    tmp_path: Path, delivery_phase: str
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"copy-race.{suffix}"
    request_id = f"delete_copy_race_{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    upload_dir, raw_object_dir, journal_root = _roots(tmp_path)
    _record_consent(SessionFactory, actor_id=actor_id)
    report_id, storage_name = _seed_report(
        SessionFactory, subject=subject, upload_dir=upload_dir, suffix=suffix
    )
    _accept(SessionFactory, actor_id=actor_id, request_id=request_id)
    manifest_path = journal_root / "manifests" / f"{request_id}.json"
    request_digest = hashlib.sha256(request_id.encode("ascii")).hexdigest()
    journal_path = journal_root / "journals" / f"{request_digest}.json"
    deliveries: list[uuid.UUID] = []

    def interleave(point: str) -> None:
        if point == "after_quarantine" and delivery_phase == point:
            with engine.begin() as connection:
                deliveries.append(_record_delivery(connection, report_id))
        if point == "before_database_commit":
            # DELETE holds the report lock until commit; a later delivery cannot
            # acquire it and slip past the final copy recount.
            with engine.connect() as connection:
                with pytest.raises(DBAPIError) as locked:
                    connection.execute(
                        text("SELECT id FROM reports WHERE id = :id FOR UPDATE NOWAIT"),
                        {"id": report_id},
                    ).all()
                assert locked.value.orig.sqlstate == "55P03"

    try:
        if delivery_phase == "before_worker":
            with engine.begin() as connection:
                deliveries.append(_record_delivery(connection, report_id))

        with _worker_session_factory(engine, suffix) as WorkerSessionFactory:
            if delivery_phase == "after_quarantine":
                with pytest.raises(AccountDeletionWorkerError, match="external copy inventory changed"):
                    process_request(
                        WorkerSessionFactory,
                        request_id=request_id,
                        upload_dir=upload_dir,
                        raw_object_dir=raw_object_dir,
                        root=journal_root,
                        fault=interleave,
                    )
                assert not manifest_path.exists()
                assert not journal_path.exists()
                assert (upload_dir / storage_name).exists()
                with SessionFactory() as db:
                    request = db.get(AccountDeletionRequest, request_id)
                    assert request is not None
                    assert request.overall_status != "COMPLETED"
                    assert request.completion_receipt_sha256 is None
                    assert db.execute(
                        text("SELECT count(*) FROM reports WHERE id = :id"),
                        {"id": report_id},
                    ).scalar_one() == 1
                    assert db.execute(
                        text(
                            "SELECT count(*) FROM report_institution_delivery_events "
                            "WHERE report_id = :id"
                        ),
                        {"id": report_id},
                    ).scalar_one() == 1
                assert _server_items(SessionFactory, request_id)["server_copies"].state != "NOT_APPLICABLE"
                # Retry sees the committed delivery and keeps external copies open.
                process_request(
                    WorkerSessionFactory,
                    request_id=request_id,
                    upload_dir=upload_dir,
                    raw_object_dir=raw_object_dir,
                    root=journal_root,
                )
                expected_copies = 1
            else:
                process_request(
                    WorkerSessionFactory,
                    request_id=request_id,
                    upload_dir=upload_dir,
                    raw_object_dir=raw_object_dir,
                    root=journal_root,
                    fault=interleave,
                )
                expected_copies = len(deliveries)

        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        assert manifest["external_copy_count"] == expected_copies
        assert _server_items(SessionFactory, request_id)["server_copies"].state == (
            "EXTERNAL_PENDING" if expected_copies else "NOT_APPLICABLE"
        )
    finally:
        if deliveries:
            with engine.begin() as connection:
                connection.execute(text("ALTER TABLE report_institution_delivery_events DISABLE TRIGGER USER"))
                try:
                    connection.execute(
                        text("DELETE FROM report_institution_delivery_events WHERE id = ANY(:ids)"),
                        {"ids": deliveries},
                    )
                finally:
                    connection.execute(text("ALTER TABLE report_institution_delivery_events ENABLE TRIGGER USER"))
        engine.dispose()
