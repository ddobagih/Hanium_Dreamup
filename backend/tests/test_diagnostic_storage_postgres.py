"""Caller transaction and owner fences for the extracted diagnostic purge service."""
from datetime import UTC, datetime, timedelta
import os
import uuid

import pytest
from sqlalchemy import func, select

from backend.app.database import SessionLocal
from backend.app.field_session_log_models import GuidanceDiagnosticBatch, GuidanceDiagnosticPreference, GuidanceDiagnosticSession
from backend.app.services.diagnostic_storage import purge_diagnostics_on_account_deletion, prune_diagnostics

pytestmark = pytest.mark.skipif(not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(), reason="dedicated test database required")


def add_rows(db, subject, generation, now):
    session_id, batch_id = str(uuid.uuid4()), str(uuid.uuid4())
    db.add(GuidanceDiagnosticPreference(privacy_subject_hmac=subject, account_generation=generation, enabled=True, revision=1, updated_at=now))
    db.flush()
    db.add(GuidanceDiagnosticSession(session_id=session_id, privacy_subject_hmac=subject, account_generation=generation, build_id="owned-test-build", created_at=now, last_received_at=now))
    db.flush()
    db.add(GuidanceDiagnosticBatch(session_id=session_id, batch_id=batch_id, payload_sha256="a" * 64, payload_bytes=10, event_count=1, events=[{"name": "test"}], received_at=now))
    db.flush()
    return session_id


def test_purge_remains_inside_callers_transaction_and_does_not_cross_subject_or_generation():
    now = datetime.now(UTC)
    subject, other = uuid.uuid4().hex * 2, uuid.uuid4().hex * 2
    with SessionLocal() as db:
        ids = [add_rows(db, subject, 1, now), add_rows(db, subject, 2, now), add_rows(db, other, 1, now)]
        savepoint = db.begin_nested()
        purge_diagnostics_on_account_deletion(db, [subject], 1)
        assert db.scalars(select(GuidanceDiagnosticSession.session_id).where(GuidanceDiagnosticSession.session_id.in_(ids))).all() == ids[1:]
        assert db.scalar(select(func.count()).select_from(GuidanceDiagnosticBatch).where(GuidanceDiagnosticBatch.session_id == ids[0])) == 0
        assert db.scalar(select(func.count()).select_from(GuidanceDiagnosticPreference).where(GuidanceDiagnosticPreference.privacy_subject_hmac == subject, GuidanceDiagnosticPreference.account_generation == 1)) == 0
        savepoint.rollback()
        assert set(db.scalars(select(GuidanceDiagnosticSession.session_id).where(GuidanceDiagnosticSession.session_id.in_(ids)))) == set(ids)
        assert db.scalar(select(func.count()).select_from(GuidanceDiagnosticBatch).where(GuidanceDiagnosticBatch.session_id.in_(ids))) == 3
        db.rollback()


def test_retention_keeps_preferences_and_uses_original_fourteen_day_inclusive_cutoff():
    now = datetime.now(UTC)
    with SessionLocal() as db:
        expired = add_rows(db, uuid.uuid4().hex * 2, 1, now - timedelta(days=14))
        retained = add_rows(db, uuid.uuid4().hex * 2, 1, now - timedelta(days=14) + timedelta(microseconds=1))
        before = db.scalar(select(func.count()).select_from(GuidanceDiagnosticPreference))
        prune_diagnostics(db, now)
        assert db.scalar(select(func.count()).select_from(GuidanceDiagnosticSession).where(GuidanceDiagnosticSession.session_id == expired)) == 0
        assert db.scalar(select(func.count()).select_from(GuidanceDiagnosticSession).where(GuidanceDiagnosticSession.session_id == retained)) == 1
        assert db.scalar(select(func.count()).select_from(GuidanceDiagnosticPreference)) == before
        db.rollback()


@pytest.mark.parametrize("mutation", [
    "ALTER TABLE public.report_review_decisions DISABLE TRIGGER report_review_duplicate_representative",
    "CREATE OR REPLACE FUNCTION public.walksafe_enforce_duplicate_representative() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$",
])
def test_diagnostic_head_preserves_latest_report_integrity_guard(mutation):
    from sqlalchemy import text
    from backend.app.services.admin_report_integrity import admin_report_integrity_boundary_state

    engine = SessionLocal.kw["bind"]
    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "202610110001"
            assert admin_report_integrity_boundary_state(connection) is True
            connection.exec_driver_sql(mutation)
            assert admin_report_integrity_boundary_state(connection) is False
        finally:
            transaction.rollback()
        assert admin_report_integrity_boundary_state(connection) is True
