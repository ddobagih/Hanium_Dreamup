"""Diagnostic retention and deletion within the caller-owned privacy transaction."""
from __future__ import annotations
from datetime import datetime, timedelta
from typing import Any
from sqlalchemy import delete, inspect, select
from sqlalchemy.orm import Session
from backend.app.field_session_log_models import GuidanceDiagnosticPreference, GuidanceDiagnosticSession, GuidanceDiagnosticBatch

RETENTION = timedelta(days=14)

def purge_owned_diagnostics(db: Session, subjects: Any, generation: int, *, delete_preference: bool = False) -> None:
    """Called under the existing actor-generation deletion/withdrawal fence."""
    owners = tuple(subjects)
    ids = select(GuidanceDiagnosticSession.session_id).where(GuidanceDiagnosticSession.privacy_subject_hmac.in_(owners), GuidanceDiagnosticSession.account_generation == generation)
    db.execute(delete(GuidanceDiagnosticBatch).where(GuidanceDiagnosticBatch.session_id.in_(ids)))
    db.execute(delete(GuidanceDiagnosticSession).where(GuidanceDiagnosticSession.privacy_subject_hmac.in_(owners), GuidanceDiagnosticSession.account_generation == generation))
    if delete_preference:
        db.execute(delete(GuidanceDiagnosticPreference).where(GuidanceDiagnosticPreference.privacy_subject_hmac.in_(owners), GuidanceDiagnosticPreference.account_generation == generation))


def diagnostic_schema_present(db: Session) -> bool:
    return inspect(db.connection()).has_table(GuidanceDiagnosticPreference.__tablename__)


def purge_diagnostics_on_account_deletion(db: Session, subjects: Any, generation: int) -> None:
    if diagnostic_schema_present(db):
        purge_owned_diagnostics(db, subjects, generation, delete_preference=True)


def prune_diagnostics(db: Session, now: datetime) -> None:
    db.execute(delete(GuidanceDiagnosticBatch).where(GuidanceDiagnosticBatch.received_at <= now - RETENTION))
    db.execute(delete(GuidanceDiagnosticSession).where(GuidanceDiagnosticSession.last_received_at <= now - RETENTION))
