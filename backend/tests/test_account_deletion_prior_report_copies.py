"""S1617-59: account deletion must retain copies of reports deleted earlier."""
from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
import os
from pathlib import Path
import uuid

import pytest
from sqlalchemy import select, text

from backend.app.models import (
    AccountDeletionRequest, Report, ReportDeletionTombstone,
    ReportDeletionExternalCopyState,
)
from backend.app.services.privacy_lifecycle import privacy_subject_hmac
from backend.app.schemas import (
    ReportUserRequestCreateV1, AdminReportUserRequestStatusUpdateV1,
    AdminReportDeletionExternalCopyEventCreateV1,
)
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_user_requests import create_report_user_request, update_admin_request_status
from backend.app.services.report_deletion import ReportDeletionCandidate, apply_report_deletion
from backend.app.services.report_external_copy_deletions import record_admin_external_copy_deletion_event
from scripts.account_deletion_worker import process_request, reconcile_journal, AccountDeletionWorkerError
from test_account_deletion_copy_race_postgres import _record_delivery
from test_account_deletion_server_inventory_postgres import _roots, _seed_report, _server_items
from test_fp046_postgres_integration import PRIVACY_SECRET, _accept, _record_consent, _session_factory, _worker_session_factory

pytestmark = pytest.mark.skipif(not os.environ.get("WALKSAFE_TEST_DATABASE_URL"), reason="isolated PostgreSQL required")


def _copy_observation(sessions, report_id, states):
    with sessions() as db:
        request_id, copy_id = db.execute(
            select(ReportDeletionTombstone.request_id, ReportDeletionExternalCopyState.id)
            .join(ReportDeletionExternalCopyState,
                  ReportDeletionExternalCopyState.deletion_tombstone_id == ReportDeletionTombstone.id)
            .where(ReportDeletionTombstone.report_id == report_id)
        ).one()
    for revision, state in enumerate(states):
        now = datetime.now(UTC)
        payload = AdminReportDeletionExternalCopyEventCreateV1(
            state=state, expected_revision=revision, idempotency_key=uuid.uuid4(),
            observed_at=now.isoformat().replace("+00:00", "Z"),
            institution_reference="synthetic-copy-reply" if state.startswith("REPLY_") else None,
            evidence_sha256="a" * 64 if state == "REPLY_DELETION_CONFIRMED" else None,
        )
        with sessions() as db:
            record_admin_external_copy_deletion_event(
                db, request_id=request_id, copy_id=copy_id, payload=payload,
                identity=AdminSessionIdentity(
                    admin_id="admin@example.com", session_id=uuid.uuid4(),
                    device_id="prior-copy-test-device", device_label="synthetic test",
                    expires_at=now + timedelta(hours=1), step_up_verified_at=now,
                ), correlation_id=uuid.uuid4(), query_sha256="e" * 64,
            )


@pytest.mark.parametrize("states,confirmed", [
    ([], False), (["REQUEST_SENT"], False),
    (["REQUEST_SENT", "REPLY_ACKNOWLEDGED"], False),
    (["REQUEST_SENT", "REPLY_DECLINED"], False),
    (["REQUEST_SENT", "REPLY_ACKNOWLEDGED", "REPLY_DELETION_CONFIRMED"], True),
])
def test_prior_copy_projection_closes_only_confirmed_copies(tmp_path, states, confirmed):
    engine, sessions = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor = f"prior-projection.{suffix}"
    subject = privacy_subject_hmac(actor, 1, PRIVACY_SECRET)
    request_id = f"delete_projection_{suffix}"
    uploads, raw, worker = _roots(tmp_path)
    try:
        _record_consent(sessions, actor_id=actor)
        report_id = _prior_report(sessions, uploads, subject, suffix)
        _copy_observation(sessions, report_id, states)
        _accept(sessions, actor_id=actor, request_id=request_id)
        with _worker_session_factory(engine, suffix) as worker_sessions:
            with worker_sessions() as db:
                projection = db.execute(text(
                    "SELECT * FROM public.walksafe_account_prior_report_copy_inventory(:subject, 1)"
                ), {"subject": subject}).mappings().one()
                assert dict(projection) == {"total_count": 1, "pending_count": int(not confirmed), "consistent": True}
                assert db.scalar(text("SELECT has_table_privilege(current_user, "
                    "'public.report_deletion_external_copy_states', 'SELECT')")) is False
                assert db.scalar(text("SELECT has_table_privilege(current_user, "
                    "'public.report_deletion_external_copy_events', 'SELECT')")) is False
            process_request(worker_sessions, request_id=request_id, upload_dir=uploads,
                            raw_object_dir=raw, root=worker)
        assert _server_items(sessions, request_id)["server_copies"].state == (
            "COMPLETED" if confirmed else "EXTERNAL_PENDING"
        )
        manifest = json.loads((worker / "manifests" / f"{request_id}.json").read_text())
        assert manifest["external_copy_count"] == 1
        assert bool(list((worker / "journals").glob("*.json"))) is not confirmed
    finally:
        engine.dispose()


@pytest.mark.parametrize("live_copy", [False, True])
def test_late_confirmation_reconciles_without_rewriting_frozen_inventory(tmp_path, live_copy):
    engine, sessions = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor = f"prior-late.{suffix}"
    subject = privacy_subject_hmac(actor, 1, PRIVACY_SECRET)
    request_id = f"delete_late_projection_{suffix}"
    uploads, raw, worker = _roots(tmp_path)
    try:
        _record_consent(sessions, actor_id=actor)
        prior = _prior_report(sessions, uploads, subject, suffix)
        if live_copy:
            current, _ = _seed_report(sessions, subject=subject, upload_dir=uploads, suffix=suffix + "live")
            with sessions.begin() as db:
                _record_delivery(db, current)
        _accept(sessions, actor_id=actor, request_id=request_id)
        with _worker_session_factory(engine, suffix) as worker_sessions:
            original_sha = process_request(worker_sessions, request_id=request_id,
                upload_dir=uploads, raw_object_dir=raw, root=worker)
            journal = next((worker / "journals").glob("*.json"))
            journal_bytes = journal.read_bytes()
            manifest = worker / "manifests" / f"{request_id}.json"
            manifest_bytes = manifest.read_bytes()
            _copy_observation(sessions, prior, ["REQUEST_SENT", "REPLY_DELETION_CONFIRMED"])
            assert journal.read_bytes() == journal_bytes
            reconciled_sha = reconcile_journal(worker_sessions, worker, journal)
            assert reconciled_sha == original_sha
            assert manifest.read_bytes() == manifest_bytes
            assert _server_items(sessions, request_id)["server_copies"].state == (
                "EXTERNAL_PENDING" if live_copy else "COMPLETED"
            )
            assert not journal.exists()
            before = _server_items(sessions, request_id)["server_copies"].item_revision
            with worker_sessions() as db:
                from backend.app.services.privacy_lifecycle import complete_server_deletion_inventory_from_manifest
                complete_server_deletion_inventory_from_manifest(
                    db, request_id=request_id, manifest_sha256=original_sha,
                    terminal_at=datetime.fromisoformat(json.loads(journal_bytes)["deleted_at"].replace("Z", "+00:00")),
                    quarantined_raw_collection_count=0, external_copy_count=1 + int(live_copy),
                    reconcile_prior_report_copies=True,
                )
            assert _server_items(sessions, request_id)["server_copies"].item_revision == before
    finally:
        engine.dispose()


def test_inconsistent_prior_copy_state_count_fails_closed_before_account_mutation(tmp_path):
    engine, sessions = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor = f"prior-inconsistent.{suffix}"
    subject = privacy_subject_hmac(actor, 1, PRIVACY_SECRET)
    request_id = f"delete_inconsistent_{suffix}"
    uploads, raw, worker = _roots(tmp_path)
    try:
        _record_consent(sessions, actor_id=actor)
        prior = _prior_report(sessions, uploads, subject, suffix)
        # Deliberately corrupt only the disposable DB, bypassing immutable triggers.
        with engine.begin() as db:
            db.execute(text("ALTER TABLE report_deletion_external_copy_states DISABLE TRIGGER USER"))
            db.execute(text("DELETE FROM report_deletion_external_copy_states WHERE deletion_tombstone_id IN "
                            "(SELECT id FROM report_deletion_tombstones WHERE report_id=:report)"), {"report": prior})
            db.execute(text("ALTER TABLE report_deletion_external_copy_states ENABLE TRIGGER USER"))
        _accept(sessions, actor_id=actor, request_id=request_id)
        with _worker_session_factory(engine, suffix) as worker_sessions:
            with pytest.raises(AccountDeletionWorkerError, match="prior report copy inventory"):
                process_request(worker_sessions, request_id=request_id,
                                upload_dir=uploads, raw_object_dir=raw, root=worker)
        assert not (worker / "manifests" / f"{request_id}.json").exists()
        with sessions() as db:
            assert db.scalar(text("SELECT count(*) FROM privacy_consent_events WHERE privacy_subject_hmac=:subject"),
                             {"subject": subject}) == 1
    finally:
        engine.dispose()


def _prior_report(SessionFactory, root: Path, subject: str, suffix: str, generation: int = 1):
    report_id, _ = _seed_report(SessionFactory, subject=subject, upload_dir=root, suffix=suffix)
    request_id = uuid.uuid4()
    with SessionFactory.begin() as db:
        db.get(Report, report_id).account_generation = generation
        _record_delivery(db, report_id)
    now = datetime.now(UTC)
    with SessionFactory() as db:
        created, _ = create_report_user_request(db, report_id=report_id,
            payload=ReportUserRequestCreateV1(client_request_id=request_id, request_type="DELETE",
                request_text="synthetic deletion"), privacy_subject=subject,
            account_generation=generation, now=now)
    request_id = created.id
    with SessionFactory() as db:
        acknowledged = update_admin_request_status(db, request_id=request_id,
            payload=AdminReportUserRequestStatusUpdateV1(status="ACKNOWLEDGED", expected_version=1),
            identity=AdminSessionIdentity(admin_id="admin@example.com", session_id=uuid.uuid4(),
                device_id="prior-copy-test-device", device_label="synthetic test",
                expires_at=now+timedelta(hours=1), step_up_verified_at=now),
            correlation_id=uuid.uuid4(), query_sha256="e"*64, now=now+timedelta(seconds=1))

    class Storage:
        def stage(self, names): self.names = names
        def restore(self): pass
        def finalize(self):
            for name in self.names: (root / name).unlink(missing_ok=True)

    role = "prior_report_worker_" + uuid.uuid4().hex
    engine = SessionFactory.kw["bind"]
    with engine.begin() as connection:
        connection.exec_driver_sql(f'CREATE ROLE "{role}" NOLOGIN')
        connection.exec_driver_sql(f'GRANT walksafe_report_deletion_worker TO "{role}"')
    try:
        with engine.connect() as connection:
            try:
                connection.exec_driver_sql(f'SET SESSION AUTHORIZATION "{role}"')
                connection.commit()
                with SessionFactory(bind=connection) as db:
                    result = apply_report_deletion(db, candidate=ReportDeletionCandidate(
                        request_id=request_id, report_id=report_id,
                        request_status_version=acknowledged.status_version,
                        privacy_subject_hmac=subject, account_generation=generation), storage_effect=Storage())
                    assert result.external_copy_count == 1
            finally:
                connection.rollback()
                connection.exec_driver_sql("RESET SESSION AUTHORIZATION")
                connection.commit()
    finally:
        with engine.begin() as connection:
            connection.exec_driver_sql(f'DROP ROLE "{role}"')
    with SessionFactory() as db:
        assert db.get(Report, report_id) is None
        assert db.scalar(select(ReportDeletionTombstone.external_copy_count).where(
            ReportDeletionTombstone.report_id == report_id)) == 1
    return report_id


@pytest.mark.parametrize("live_copy", [False, True])
def test_prior_report_copy_prevents_account_deletion_completion(tmp_path, live_copy):
    engine, sessions = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor = f"prior-copy.{suffix}"
    subject = privacy_subject_hmac(actor, 1, PRIVACY_SECRET)
    request_id = f"delete_prior_copy_{suffix}"
    uploads, raw, worker = _roots(tmp_path)
    try:
        _record_consent(sessions, actor_id=actor)
        prior = _prior_report(sessions, uploads, subject, suffix)
        # Neither another subject nor another generation belongs to this request.
        _prior_report(sessions, uploads, "b" * 64, suffix + "other")
        _prior_report(sessions, uploads, subject, suffix + "generation", generation=2)
        if live_copy:
            report, _ = _seed_report(sessions, subject=subject, upload_dir=uploads, suffix=suffix + "live")
            with sessions.begin() as db: _record_delivery(db, report)
        _accept(sessions, actor_id=actor, request_id=request_id)
        with _worker_session_factory(engine, suffix) as worker_sessions:
            with worker_sessions() as db:
                assert db.scalar(text("SELECT has_column_privilege(current_user, "
                    "'public.report_deletion_tombstones', 'external_copy_count', 'SELECT')")) is True
                assert db.scalar(text("SELECT has_table_privilege(current_user, "
                    "'public.report_deletion_tombstones', 'SELECT')")) is False
                assert db.scalar(text("SELECT has_column_privilege(current_user, "
                    "'public.report_deletion_tombstones', 'request_id', 'SELECT')")) is False
            process_request(worker_sessions, request_id=request_id, upload_dir=uploads,
                            raw_object_dir=raw, root=worker)
        manifest = json.loads((worker / "manifests" / f"{request_id}.json").read_text())
        assert manifest["external_copy_count"] == 1 + int(live_copy)
        assert manifest["server_inventory"]["server_copies"] == "EXTERNAL_PENDING"
        assert _server_items(sessions, request_id)["server_copies"].state == "EXTERNAL_PENDING"
        with sessions() as db:
            request = db.get(AccountDeletionRequest, request_id)
            assert request.overall_status != "COMPLETED"
            assert request.completion_receipt_sha256 is None
            assert db.scalar(select(ReportDeletionTombstone.report_id).where(
                ReportDeletionTombstone.report_id == prior)) == prior
    finally:
        engine.dispose()


def test_recovery_does_not_publish_an_old_journal_missing_prior_copies(tmp_path):
    engine, sessions = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor = f"prior-copy-recovery.{suffix}"
    subject = privacy_subject_hmac(actor, 1, PRIVACY_SECRET)
    request_id = f"delete_prior_recovery_{suffix}"
    uploads, raw, worker = _roots(tmp_path)

    class Interrupted(RuntimeError): pass

    def interrupt(point):
        if point == "before_object_removal": raise Interrupted()

    try:
        _record_consent(sessions, actor_id=actor)
        _prior_report(sessions, uploads, subject, suffix)
        _accept(sessions, actor_id=actor, request_id=request_id)
        with _worker_session_factory(engine, suffix) as worker_sessions:
            with pytest.raises(Interrupted):
                process_request(worker_sessions, request_id=request_id, upload_dir=uploads,
                    raw_object_dir=raw, root=worker, fault=interrupt)
            journal_path = next((worker / "journals").glob("*.json"))
            original = journal_path.read_text()
            stale = json.loads(original)
            stale["schema_version"] = "walksafe.account-deletion-worker-journal.v4"
            stale["external_copy_count"] = 0
            journal_path.write_text(json.dumps(stale))
            with pytest.raises(AccountDeletionWorkerError, match="prior report copy inventory"):
                reconcile_journal(worker_sessions, worker, journal_path)
            assert not (worker / "manifests" / f"{request_id}.json").exists()
            assert _server_items(sessions, request_id)["server_copies"].state not in {"COMPLETED", "NOT_APPLICABLE"}
            # Restore the original test journal, whose inventory actually included the copy.
            journal_path.write_text(original)
            reconcile_journal(worker_sessions, worker, journal_path)
            assert _server_items(sessions, request_id)["server_copies"].state == "EXTERNAL_PENDING"
    finally:
        engine.dispose()
