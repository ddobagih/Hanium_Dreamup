"""Structured, append-only user corrections for report-owned auxiliary content."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import uuid

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import Report, ReportContentRevision
from backend.app.schemas import ReportContentCorrectionRequestV1
from backend.app.services.privacy_lifecycle import lock_privacy_subject_shared
from backend.app.services.report_user_requests import assert_subject_active


class ReportContentCorrectionError(RuntimeError):
    def __init__(self, code: str, message: str, *, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class ReportContentState:
    report_id: uuid.UUID
    revision: int
    expected_revision: int | None
    idempotency_key: uuid.UUID | None
    content_sha256: str
    user_description: str | None
    category_hint: str | None
    corrected_at: datetime | None


def _canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def report_content_sha256(
    *,
    report_id: uuid.UUID,
    revision: int,
    user_description: str | None,
    category_hint: str | None,
) -> str:
    return hashlib.sha256(
        b"walksafe.report-content.v1\0"
        + _canonical_json(
            {
                "category_hint": category_hint,
                "report_id": str(report_id),
                "revision": revision,
                "user_description": user_description,
            }
        )
    ).hexdigest()


def correction_intent_sha256(
    report_id: uuid.UUID, payload: ReportContentCorrectionRequestV1
) -> str:
    patch = {
        field: getattr(payload, field)
        for field in ("category_hint", "user_description")
        if field in payload.model_fields_set
    }
    return hashlib.sha256(
        b"walksafe.report-content-correction.v1\0"
        + _canonical_json(
            {
                "expected_revision": payload.expected_revision,
                "idempotency_key": str(payload.idempotency_key),
                "patch": patch,
                "report_id": str(report_id),
            }
        )
    ).hexdigest()


def _state_from_revision(item: ReportContentRevision) -> ReportContentState:
    return ReportContentState(
        report_id=item.report_id,
        revision=item.revision,
        expected_revision=item.expected_revision,
        idempotency_key=item.idempotency_key,
        content_sha256=item.content_sha256,
        user_description=item.user_description,
        category_hint=item.category_hint,
        corrected_at=item.created_at,
    )


def current_content_for_locked_report(
    db: Session, report: Report
) -> ReportContentState:
    revision = int(getattr(report, "content_revision", 0) or 0)
    if revision == 0:
        return ReportContentState(
            report_id=report.id,
            revision=0,
            expected_revision=None,
            idempotency_key=None,
            content_sha256=report_content_sha256(
                report_id=report.id,
                revision=0,
                user_description=None,
                category_hint=None,
            ),
            user_description=None,
            category_hint=None,
            corrected_at=None,
        )
    item = db.execute(
        select(ReportContentRevision).where(
            ReportContentRevision.report_id == report.id,
            ReportContentRevision.revision == revision,
        )
    ).scalar_one_or_none()
    if item is None:
        raise ReportContentCorrectionError(
            "report_content_projection_invalid",
            "The current report content projection is unavailable.",
            status_code=503,
        )
    return _state_from_revision(item)


def get_owned_report_content(
    db: Session,
    *,
    report_id: uuid.UUID,
    privacy_subject: str,
    account_generation: int,
) -> ReportContentState:
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    assert_subject_active(
        db,
        privacy_subject=privacy_subject,
        account_generation=account_generation,
    )
    try:
        report = db.execute(
            select(Report).where(
                Report.id == report_id,
                Report.privacy_subject_hmac == privacy_subject,
                Report.account_generation == account_generation,
            )
        ).scalar_one_or_none()
        if report is None:
            raise ReportContentCorrectionError(
                "report_not_found", "Report was not found.", status_code=404
            )
        return current_content_for_locked_report(db, report)
    except ReportContentCorrectionError:
        raise
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportContentCorrectionError(
            "report_content_store_unavailable",
            "The report content store is temporarily unavailable.",
            status_code=503,
        ) from exc


def apply_owned_report_correction(
    db: Session,
    *,
    report_id: uuid.UUID,
    payload: ReportContentCorrectionRequestV1,
    privacy_subject: str,
    account_generation: int,
    now: datetime | None = None,
) -> tuple[ReportContentState, bool]:
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    assert_subject_active(
        db,
        privacy_subject=privacy_subject,
        account_generation=account_generation,
    )
    try:
        report = db.execute(
            select(Report)
            .where(
                Report.id == report_id,
                Report.privacy_subject_hmac == privacy_subject,
                Report.account_generation == account_generation,
            )
            .with_for_update()
        ).scalar_one_or_none()
        if report is None:
            db.rollback()
            raise ReportContentCorrectionError(
                "report_not_found", "Report was not found.", status_code=404
            )
        intent_sha256 = correction_intent_sha256(report_id, payload)
        existing = db.execute(
            select(ReportContentRevision).where(
                ReportContentRevision.report_id == report_id,
                ReportContentRevision.idempotency_key == payload.idempotency_key,
            )
        ).scalar_one_or_none()
        if existing is not None:
            state = _state_from_revision(existing)
            db.rollback()
            if existing.intent_sha256 == intent_sha256:
                return state, False
            raise ReportContentCorrectionError(
                "report_content_idempotency_conflict",
                "The idempotency key is bound to another correction intent.",
                status_code=409,
            )

        current = current_content_for_locked_report(db, report)
        if payload.expected_revision != current.revision:
            db.rollback()
            raise ReportContentCorrectionError(
                "report_content_revision_conflict",
                "The report content changed before this correction was applied.",
                status_code=409,
            )
        user_description = (
            payload.user_description
            if "user_description" in payload.model_fields_set
            else current.user_description
        )
        category_hint = (
            payload.category_hint
            if "category_hint" in payload.model_fields_set
            else current.category_hint
        )
        if (
            user_description == current.user_description
            and category_hint == current.category_hint
        ):
            db.rollback()
            raise ReportContentCorrectionError(
                "report_content_no_change",
                "The correction does not change report content.",
                status_code=422,
            )

        revision = current.revision + 1
        item = ReportContentRevision(
            id=uuid.uuid4(),
            report_id=report_id,
            revision=revision,
            expected_revision=current.revision,
            idempotency_key=payload.idempotency_key,
            privacy_subject_hmac=privacy_subject,
            account_generation=account_generation,
            user_description=user_description,
            category_hint=category_hint,
            intent_sha256=intent_sha256,
            content_sha256=report_content_sha256(
                report_id=report_id,
                revision=revision,
                user_description=user_description,
                category_hint=category_hint,
            ),
            created_at=(now or datetime.now(UTC)).astimezone(UTC),
        )
        report.content_revision = revision
        db.add(item)
        db.commit()
        return _state_from_revision(item), True
    except ReportContentCorrectionError:
        raise
    except IntegrityError as exc:
        db.rollback()
        raise ReportContentCorrectionError(
            "report_content_revision_conflict",
            "The report content changed concurrently.",
            status_code=409,
        ) from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportContentCorrectionError(
            "report_content_store_unavailable",
            "The report content store is temporarily unavailable.",
            status_code=503,
        ) from exc


__all__ = [
    "ReportContentCorrectionError",
    "ReportContentState",
    "apply_owned_report_correction",
    "correction_intent_sha256",
    "current_content_for_locked_report",
    "get_owned_report_content",
    "report_content_sha256",
]
