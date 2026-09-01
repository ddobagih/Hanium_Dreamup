"""User-visible deletion status and manual physical report deletion."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import PurePosixPath
from typing import Protocol
import uuid

from sqlalchemy import delete, func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    Report,
    ReportDeletionExternalCopyState,
    ReportDeletionLegalHold,
    ReportDeletionTombstone,
    ReportImageObject,
    ReportInstitutionDeliveryEvent,
    ReportUserRequest,
)
from backend.app.services.privacy_lifecycle import (
    lock_privacy_subject_exclusive,
    lock_privacy_subject_shared,
)
from backend.app.services.report_user_requests import assert_subject_active


class ReportDeletionError(RuntimeError):
    def __init__(self, code: str, message: str, *, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class ReportDeletionStatus:
    request_id: uuid.UUID
    report_id: uuid.UUID
    state: str
    request_status_version: int
    external_copy_count: int
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class ReportDeletionCandidate:
    request_id: uuid.UUID
    report_id: uuid.UUID
    request_status_version: int
    privacy_subject_hmac: str
    account_generation: int


@dataclass(frozen=True, slots=True)
class ExternalCopySnapshot:
    source_delivery_event_id: uuid.UUID
    package_id: uuid.UUID | None
    institution: str
    status: str
    observed_at: datetime


class ReportDeletionStorageEffect(Protocol):
    """Crash-recoverable filesystem boundary owned by the manual worker."""

    def stage(self, storage_names: tuple[str, ...]) -> None: ...

    def restore(self) -> None: ...

    def finalize(self) -> None: ...


def _not_found() -> ReportDeletionError:
    return ReportDeletionError(
        "report_deletion_not_found",
        "Report deletion was not found.",
        status_code=404,
    )


def list_report_deletion_candidates(
    db: Session,
    *,
    limit: int,
) -> list[ReportDeletionCandidate]:
    if not 1 <= limit <= 100:
        raise ValueError("candidate limit must be between 1 and 100")
    ranked = (
        select(
            ReportUserRequest.id.label("request_id"),
            ReportUserRequest.report_id,
            ReportUserRequest.status_version.label("request_status_version"),
            Report.privacy_subject_hmac,
            Report.account_generation,
            ReportUserRequest.updated_at,
            func.row_number()
            .over(
                partition_by=ReportUserRequest.report_id,
                order_by=(ReportUserRequest.updated_at, ReportUserRequest.id),
            )
            .label("ordinal"),
        )
        .join(Report, Report.id == ReportUserRequest.report_id)
        .where(
            ReportUserRequest.request_type == "DELETE",
            ReportUserRequest.status == "ACKNOWLEDGED",
            Report.privacy_subject_hmac.is_not(None),
            Report.account_generation.is_not(None),
            ~select(ReportDeletionTombstone.id)
            .where(ReportDeletionTombstone.request_id == ReportUserRequest.id)
            .exists(),
        )
    ).subquery()
    rows = db.execute(
        select(
            ranked.c.request_id,
            ranked.c.report_id,
            ranked.c.request_status_version,
            ranked.c.privacy_subject_hmac,
            ranked.c.account_generation,
        )
        .where(ranked.c.ordinal == 1)
        .order_by(ranked.c.updated_at, ranked.c.request_id)
        .limit(limit)
    ).all()
    return [
        ReportDeletionCandidate(
            request_id=row.request_id,
            report_id=row.report_id,
            request_status_version=row.request_status_version,
            privacy_subject_hmac=row.privacy_subject_hmac,
            account_generation=row.account_generation,
        )
        for row in rows
    ]


def get_owned_report_deletion_status(
    db: Session,
    *,
    request_id: uuid.UUID,
    privacy_subject: str,
    account_generation: int,
) -> ReportDeletionStatus:
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    assert_subject_active(
        db,
        privacy_subject=privacy_subject,
        account_generation=account_generation,
    )
    tombstone = db.execute(
        select(ReportDeletionTombstone).where(
            ReportDeletionTombstone.request_id == request_id,
            ReportDeletionTombstone.privacy_subject_hmac == privacy_subject,
            ReportDeletionTombstone.account_generation == account_generation,
        )
    ).scalar_one_or_none()
    if tombstone is not None:
        return ReportDeletionStatus(
            request_id=tombstone.request_id,
            report_id=tombstone.report_id,
            state="DELETED",
            request_status_version=tombstone.request_status_version,
            external_copy_count=tombstone.external_copy_count,
            updated_at=tombstone.deleted_at,
        )
    row = db.execute(
        select(
            ReportUserRequest.id.label("request_id"),
            ReportUserRequest.report_id,
            ReportUserRequest.status_version.label("request_status_version"),
            ReportUserRequest.updated_at,
        )
        .join(Report, Report.id == ReportUserRequest.report_id)
        .where(
            ReportUserRequest.id == request_id,
            ReportUserRequest.request_type == "DELETE",
            Report.privacy_subject_hmac == privacy_subject,
            Report.account_generation == account_generation,
        )
    ).one_or_none()
    if row is None:
        raise _not_found()
    request_status = db.scalar(
        select(ReportUserRequest.status).where(ReportUserRequest.id == request_id)
    )
    if request_status == "RESOLVED":
        raise ReportDeletionError(
            "report_deletion_effect_missing",
            "The deletion request has no durable physical-deletion effect.",
            status_code=503,
        )
    held = db.scalar(
        select(ReportDeletionLegalHold.report_id).where(
            ReportDeletionLegalHold.report_id == row.report_id,
            ReportDeletionLegalHold.expires_at > func.clock_timestamp(),
        )
    )
    return ReportDeletionStatus(
        request_id=row.request_id,
        report_id=row.report_id,
        state=(
            "REJECTED"
            if request_status == "REJECTED"
            else "LEGAL_HOLD"
            if held is not None
            else "PENDING"
        ),
        request_status_version=row.request_status_version,
        external_copy_count=0,
        updated_at=row.updated_at,
    )


def _logical_storage_name(report_id: uuid.UUID, image_path: str) -> str | None:
    path = PurePosixPath(image_path)
    if (
        path.parent != PurePosixPath("/uploads")
        or path.name.split(".", 1)[0] != str(report_id)
        or path.suffix not in {".jpg", ".png", ".webp"}
    ):
        return None
    return path.name


def _external_copy_rows(
    db: Session, report_id: uuid.UUID
) -> list[ExternalCopySnapshot]:
    events = db.execute(
        select(
            ReportInstitutionDeliveryEvent.id,
            ReportInstitutionDeliveryEvent.package_id,
            ReportInstitutionDeliveryEvent.institution,
            ReportInstitutionDeliveryEvent.status,
            ReportInstitutionDeliveryEvent.observed_at,
            ReportInstitutionDeliveryEvent.revision,
        )
        .where(ReportInstitutionDeliveryEvent.report_id == report_id)
        .order_by(
            ReportInstitutionDeliveryEvent.revision,
            ReportInstitutionDeliveryEvent.id,
        )
    ).all()
    latest: dict[uuid.UUID, ExternalCopySnapshot] = {}
    for event in events:
        latest[event.package_id or event.id] = ExternalCopySnapshot(
            source_delivery_event_id=event.id,
            package_id=event.package_id,
            institution=event.institution,
            status=event.status,
            observed_at=event.observed_at,
        )
    return list(latest.values())


def apply_report_deletion(
    db: Session,
    *,
    candidate: ReportDeletionCandidate,
    storage_effect: ReportDeletionStorageEffect,
    now: datetime | None = None,
) -> ReportDeletionStatus:
    """Apply one candidate; retries return the same content-free tombstone."""

    existing = db.execute(
        select(ReportDeletionTombstone).where(
            ReportDeletionTombstone.request_id == candidate.request_id
        )
    ).scalar_one_or_none()
    if existing is not None:
        if (
            existing.report_id != candidate.report_id
            or existing.privacy_subject_hmac != candidate.privacy_subject_hmac
            or existing.account_generation != candidate.account_generation
            or existing.request_status_version != candidate.request_status_version
        ):
            db.rollback()
            raise ReportDeletionError(
                "report_deletion_candidate_conflict",
                "The deletion candidate differs from its completed effect.",
                status_code=409,
            )
        state = ReportDeletionStatus(
            request_id=existing.request_id,
            report_id=existing.report_id,
            state="DELETED",
            request_status_version=existing.request_status_version,
            external_copy_count=existing.external_copy_count,
            updated_at=existing.deleted_at,
        )
        db.rollback()
        return state

    lock_privacy_subject_exclusive(
        db,
        candidate.privacy_subject_hmac,
        candidate.account_generation,
    )
    candidate_locked = db.scalar(
        select(
            func.walksafe_lock_report_deletion_candidate(
                candidate.request_id,
                candidate.report_id,
                candidate.request_status_version,
                candidate.privacy_subject_hmac,
                candidate.account_generation,
            )
        )
    )
    if candidate_locked is not True:
        db.rollback()
        raise ReportDeletionError(
            "report_deletion_candidate_stale",
            "The deletion candidate changed before it was applied.",
            status_code=409,
        )
    row = db.execute(
        select(
            ReportUserRequest.id.label("request_id"),
            ReportUserRequest.status_version.label("request_status_version"),
            Report.id.label("report_id"),
            Report.privacy_subject_hmac,
            Report.account_generation,
            Report.image_path,
            ReportImageObject.storage_name,
        )
        .join(Report, Report.id == ReportUserRequest.report_id)
        .outerjoin(ReportImageObject, ReportImageObject.report_id == Report.id)
        .where(
            ReportUserRequest.id == candidate.request_id,
            ReportUserRequest.report_id == candidate.report_id,
            ReportUserRequest.request_type == "DELETE",
            ReportUserRequest.status == "ACKNOWLEDGED",
            ReportUserRequest.status_version == candidate.request_status_version,
            Report.privacy_subject_hmac == candidate.privacy_subject_hmac,
            Report.account_generation == candidate.account_generation,
        )
    ).one_or_none()
    if row is None:
        db.rollback()
        raise ReportDeletionError(
            "report_deletion_candidate_stale",
            "The deletion candidate changed before it was applied.",
            status_code=409,
        )
    held = db.scalar(
        select(ReportDeletionLegalHold.report_id).where(
            ReportDeletionLegalHold.report_id == row.report_id,
            ReportDeletionLegalHold.expires_at > func.clock_timestamp(),
        )
    )
    if held is not None:
        db.rollback()
        return ReportDeletionStatus(
            request_id=row.request_id,
            report_id=row.report_id,
            state="LEGAL_HOLD",
            request_status_version=row.request_status_version,
            external_copy_count=0,
            updated_at=(now or datetime.now(UTC)).astimezone(UTC),
        )

    storage_names: set[str] = set()
    if row.storage_name is not None:
        storage_names.add(row.storage_name)
    logical_name = _logical_storage_name(row.report_id, row.image_path)
    if logical_name is not None:
        storage_names.add(logical_name)

    external_rows = _external_copy_rows(db, row.report_id)
    deleted_at = (now or datetime.now(UTC)).astimezone(UTC)
    tombstone = ReportDeletionTombstone(
        id=uuid.uuid4(),
        request_id=row.request_id,
        report_id=row.report_id,
        privacy_subject_hmac=row.privacy_subject_hmac,
        account_generation=row.account_generation,
        request_status_version=row.request_status_version,
        external_copy_count=len(external_rows),
        deleted_at=deleted_at,
    )
    db.add(tombstone)
    for event in external_rows:
        db.add(
            ReportDeletionExternalCopyState(
                id=uuid.uuid4(),
                deletion_tombstone_id=tombstone.id,
                source_delivery_event_id=event.source_delivery_event_id,
                package_id=event.package_id,
                institution=event.institution,
                status=event.status,
                observed_at=event.observed_at,
                recorded_at=deleted_at,
            )
        )
    db.flush()
    storage_started = True
    try:
        storage_effect.stage(tuple(sorted(storage_names)))
        deleted = db.execute(
            delete(Report).where(
                Report.id == row.report_id,
                Report.privacy_subject_hmac == candidate.privacy_subject_hmac,
                Report.account_generation == candidate.account_generation,
            )
        )
        if deleted.rowcount != 1:
            raise ReportDeletionError(
                "report_deletion_candidate_stale",
                "The report changed before deletion completed.",
                status_code=409,
            )
        db.commit()
    except ReportDeletionError:
        db.rollback()
        try:
            storage_effect.restore()
        except Exception as restore_exc:
            raise ReportDeletionError(
                "report_deletion_storage_recovery_required",
                "The staged report files require manual reconciliation.",
                status_code=503,
            ) from restore_exc
        raise
    except SQLAlchemyError as exc:
        db.rollback()
        try:
            completed = db.execute(
                select(ReportDeletionTombstone).where(
                    ReportDeletionTombstone.request_id == candidate.request_id
                )
            ).scalar_one_or_none()
        except SQLAlchemyError as lookup_exc:
            db.rollback()
            raise ReportDeletionError(
                "report_deletion_storage_recovery_required",
                "The staged report files require manual reconciliation.",
                status_code=503,
            ) from lookup_exc
        if completed is None:
            try:
                storage_effect.restore()
            except Exception as restore_exc:
                raise ReportDeletionError(
                    "report_deletion_storage_recovery_required",
                    "The staged report files require manual reconciliation.",
                    status_code=503,
                ) from restore_exc
            raise ReportDeletionError(
                "report_deletion_store_unavailable",
                "The report deletion could not be stored.",
                status_code=503,
            ) from exc
        try:
            storage_effect.finalize()
        except Exception as finalize_exc:
            raise ReportDeletionError(
                "report_deletion_storage_cleanup_pending",
                "The report was deleted but quarantined files require reconciliation.",
                status_code=503,
            ) from finalize_exc
        completed_state = ReportDeletionStatus(
            request_id=completed.request_id,
            report_id=completed.report_id,
            state="DELETED",
            request_status_version=completed.request_status_version,
            external_copy_count=completed.external_copy_count,
            updated_at=completed.deleted_at,
        )
        db.rollback()
        return completed_state
    except Exception as exc:
        db.rollback()
        if storage_started:
            try:
                storage_effect.restore()
            except Exception as restore_exc:
                raise ReportDeletionError(
                    "report_deletion_storage_recovery_required",
                    "The staged report files require manual reconciliation.",
                    status_code=503,
                ) from restore_exc
        raise ReportDeletionError(
            "report_deletion_storage_unavailable",
            "The report files could not be staged safely.",
            status_code=503,
        ) from exc
    state = ReportDeletionStatus(
        request_id=row.request_id,
        report_id=row.report_id,
        state="DELETED",
        request_status_version=row.request_status_version,
        external_copy_count=len(external_rows),
        updated_at=deleted_at,
    )
    try:
        storage_effect.finalize()
    except Exception as exc:
        raise ReportDeletionError(
            "report_deletion_storage_cleanup_pending",
            "The report was deleted but quarantined files require reconciliation.",
            status_code=503,
        ) from exc
    return state


__all__ = [
    "ReportDeletionCandidate",
    "ReportDeletionError",
    "ReportDeletionStatus",
    "ReportDeletionStorageEffect",
    "apply_report_deletion",
    "get_owned_report_deletion_status",
    "list_report_deletion_candidates",
]
