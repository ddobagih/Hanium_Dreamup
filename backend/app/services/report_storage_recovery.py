"""Shared lock order and commit-state lookup for report journal recovery."""

from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator
import uuid

from sqlalchemy.orm import Session

from backend.app.models import Report, ReportImageObject
from backend.app.services.report_storage import (
    ReportImageCommitMetadata,
    ReportStorageCommitState,
    ReportStorageReconciliation,
    lock_report_storage_reconciliation_transaction,
    reconcile_pending_report_writes,
)


@contextmanager
def locked_report_storage_session(
    session_factory,
    maintenance_lock,
    maintenance_lock_path: Path | None,
    maintenance_lock_group_gid: int | None,
    *,
    transaction_lock: Callable[[Session], None] = lock_report_storage_reconciliation_transaction,
) -> Iterator[Session]:
    """Match report writes' file-lock order, then take the exclusive DB lock."""

    with maintenance_lock(
        maintenance_lock_path,
        expected_group_gid=maintenance_lock_group_gid,
    ):
        with session_factory.begin() as db:
            transaction_lock(db)
            yield db


def recover_pending_report_writes(
    db: Session,
    upload_root: Path,
    *,
    reconcile: Callable[..., ReportStorageReconciliation] = reconcile_pending_report_writes,
    max_items: int | None = None,
    deadline: float | None = None,
) -> ReportStorageReconciliation:
    """Resolve only absent rows or exactly verified committed encrypted objects."""

    def commit_state(report_id: uuid.UUID) -> ReportStorageCommitState:
        report = db.get(Report, report_id)
        image_object = db.get(ReportImageObject, report_id)
        metadata = (
            ReportImageCommitMetadata(
                storage_name=image_object.storage_name,
                envelope_version=image_object.envelope_version,
                algorithm=image_object.algorithm,
                aad_version=image_object.aad_version,
                key_id=image_object.key_id,
                nonce=image_object.nonce,
                envelope_sha256=image_object.envelope_sha256,
                envelope_size=image_object.envelope_size,
                plaintext_sha256=image_object.plaintext_sha256,
                plaintext_size=image_object.plaintext_size,
                content_type=image_object.content_type,
            )
            if image_object is not None
            else None
        )
        return ReportStorageCommitState(report is not None, metadata)

    if max_items is None and deadline is None:
        return reconcile(upload_root, commit_state)
    return reconcile(
        upload_root,
        commit_state,
        max_items=max_items,
        deadline=deadline,
    )
