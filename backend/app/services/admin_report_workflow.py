"""Append-only administrator review decisions and manual delivery events."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import NoReturn
import uuid

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    Report,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
)
from backend.app.schemas import (
    ReportInstitutionDeliveryRequest,
    ReportReviewDecisionRequest,
)
from backend.app.services.admin_security import AdminSessionIdentity


_DELIVERY_TRANSITIONS: dict[str | None, frozenset[str]] = {
    None: frozenset({"SUBMITTED", "FAILED"}),
    "FAILED": frozenset({"FAILED", "SUBMITTED"}),
    "SUBMITTED": frozenset({"ACKNOWLEDGED", "FAILED"}),
    "ACKNOWLEDGED": frozenset({"RESOLVED"}),
    "RESOLVED": frozenset(),
}


class AdminReportWorkflowError(RuntimeError):
    def __init__(self, code: str, message: str, *, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


def _raise_workflow_store_unavailable(exc: SQLAlchemyError) -> NoReturn:
    raise AdminReportWorkflowError(
        "admin_report_workflow_unavailable",
        "The administrator report workflow is temporarily unavailable.",
        status_code=503,
    ) from exc


def _raise_database_unavailable(db: Session, exc: SQLAlchemyError) -> NoReturn:
    try:
        db.rollback()
    except SQLAlchemyError:
        pass
    _raise_workflow_store_unavailable(exc)


def _rollback_or_database_unavailable(db: Session) -> None:
    try:
        db.rollback()
    except SQLAlchemyError as exc:
        _raise_workflow_store_unavailable(exc)


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def _locked_report(db: Session, report_id: uuid.UUID) -> Report:
    try:
        report = db.execute(
            select(Report).where(Report.id == report_id).with_for_update()
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if report is None:
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "report_not_found",
            "Report was not found.",
            status_code=404,
        )
    return report


def _latest_review_decision(
    db: Session,
    report_id: uuid.UUID,
) -> ReportReviewDecision | None:
    try:
        return db.execute(
            select(ReportReviewDecision)
            .where(ReportReviewDecision.report_id == report_id)
            .order_by(ReportReviewDecision.revision.desc())
            .limit(1)
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)


def _latest_delivery_event(
    db: Session,
    report_id: uuid.UUID,
) -> ReportInstitutionDeliveryEvent | None:
    try:
        return db.execute(
            select(ReportInstitutionDeliveryEvent)
            .where(ReportInstitutionDeliveryEvent.report_id == report_id)
            .order_by(ReportInstitutionDeliveryEvent.revision.desc())
            .limit(1)
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)


def _commit_append(db: Session, *, conflict_code: str) -> None:
    try:
        db.commit()
    except IntegrityError as exc:
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            conflict_code,
            "The report workflow changed concurrently.",
            status_code=409,
        ) from exc
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)


def append_report_review_decision(
    db: Session,
    *,
    report_id: uuid.UUID,
    payload: ReportReviewDecisionRequest,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    now: datetime | None = None,
) -> ReportReviewDecision:
    """Append one decision without changing the report lifecycle projection."""

    _locked_report(db, report_id)
    if payload.duplicate_of_report_id == report_id:
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "duplicate_report_self_reference",
            "A report cannot be marked as a duplicate of itself.",
            status_code=422,
        )
    try:
        duplicate_target_missing = (
            payload.duplicate_of_report_id is not None
            and db.get(Report, payload.duplicate_of_report_id) is None
        )
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if duplicate_target_missing:
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "duplicate_report_not_found",
            "The duplicate target report was not found.",
            status_code=422,
        )

    previous = _latest_review_decision(db, report_id)
    decision = ReportReviewDecision(
        report_id=report_id,
        revision=1 if previous is None else previous.revision + 1,
        decision=payload.decision,
        reason=payload.reason,
        duplicate_of_report_id=payload.duplicate_of_report_id,
        location_reviewed=payload.location_reviewed,
        photo_reviewed=payload.photo_reviewed,
        privacy_reviewed=payload.privacy_reviewed,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
        decided_at=_as_utc(now or datetime.now(UTC)),
    )
    try:
        db.add(decision)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    _commit_append(db, conflict_code="review_decision_conflict")
    try:
        db.refresh(decision)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    return decision


def list_report_review_decisions(
    db: Session,
    *,
    report_id: uuid.UUID,
) -> list[ReportReviewDecision]:
    try:
        report = db.get(Report, report_id)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if report is None:
        raise AdminReportWorkflowError(
            "report_not_found",
            "Report was not found.",
            status_code=404,
        )
    try:
        return list(
            db.scalars(
                select(ReportReviewDecision)
                .where(ReportReviewDecision.report_id == report_id)
                .order_by(ReportReviewDecision.revision)
            ).all()
        )
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)


def _delivery_intent_matches(
    event: ReportInstitutionDeliveryEvent,
    payload: ReportInstitutionDeliveryRequest,
) -> bool:
    return (
        event.institution == payload.institution
        and event.channel == payload.channel
        and event.recipient == payload.recipient
        and event.status == payload.status
        and event.external_receipt_id == payload.external_receipt_id
        and event.reason == payload.reason
        and event.evidence_sha256 == payload.evidence_sha256
        and _as_utc(event.observed_at) == payload.observed_at
        and event.expected_revision == payload.expected_revision
        and event.idempotency_key == payload.idempotency_key
    )


def _require_latest_approval(
    db: Session,
    report_id: uuid.UUID,
) -> ReportReviewDecision:
    decision = _latest_review_decision(db, report_id)
    if (
        decision is None
        or decision.decision != "APPROVED"
        or not decision.location_reviewed
        or not decision.photo_reviewed
        or not decision.privacy_reviewed
        or decision.duplicate_of_report_id is not None
    ):
        raise AdminReportWorkflowError(
            "latest_review_approval_required",
            "Manual delivery requires the latest typed decision to be fully reviewed and APPROVED.",
            status_code=409,
        )
    return decision


def append_report_institution_delivery_event(
    db: Session,
    *,
    report_id: uuid.UUID,
    payload: ReportInstitutionDeliveryRequest,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
) -> ReportInstitutionDeliveryEvent:
    """Record a manual delivery observation; this function never sends data."""

    _locked_report(db, report_id)
    idempotency_key = payload.idempotency_key
    try:
        existing = db.execute(
            select(ReportInstitutionDeliveryEvent).where(
                ReportInstitutionDeliveryEvent.report_id == report_id,
                ReportInstitutionDeliveryEvent.idempotency_key == idempotency_key,
            )
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if existing is not None:
        if _delivery_intent_matches(existing, payload):
            try:
                db.expunge(existing)
            except SQLAlchemyError as exc:
                _raise_database_unavailable(db, exc)
            _rollback_or_database_unavailable(db)
            return existing
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "delivery_idempotency_conflict",
            "The idempotency key was already used for a different delivery event.",
            status_code=409,
        )

    previous = _latest_delivery_event(db, report_id)
    current_revision = 0 if previous is None else previous.revision
    if payload.expected_revision != current_revision:
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "delivery_revision_conflict",
            "The delivery event revision changed before this request was applied.",
            status_code=409,
        )

    previous_status = None if previous is None else previous.status
    if payload.status not in _DELIVERY_TRANSITIONS[previous_status]:
        _rollback_or_database_unavailable(db)
        transition = "none" if previous_status is None else previous_status
        raise AdminReportWorkflowError(
            "delivery_transition_invalid",
            f"Delivery transition {transition}->{payload.status} is not allowed.",
            status_code=409,
        )

    try:
        approval = _require_latest_approval(db, report_id)
    except AdminReportWorkflowError as exc:
        if exc.code != "admin_report_workflow_unavailable":
            _rollback_or_database_unavailable(db)
        raise
    event = ReportInstitutionDeliveryEvent(
        report_id=report_id,
        review_decision_id=approval.id,
        revision=current_revision + 1,
        institution=payload.institution,
        channel=payload.channel,
        recipient=payload.recipient,
        status=payload.status,
        external_receipt_id=payload.external_receipt_id,
        reason=payload.reason,
        evidence_sha256=payload.evidence_sha256,
        observed_at=payload.observed_at,
        expected_revision=payload.expected_revision,
        idempotency_key=idempotency_key,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
    )
    try:
        db.add(event)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    _commit_append(db, conflict_code="delivery_event_conflict")
    try:
        db.refresh(event)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    return event


def list_report_institution_delivery_events(
    db: Session,
    *,
    report_id: uuid.UUID,
) -> list[ReportInstitutionDeliveryEvent]:
    try:
        report = db.get(Report, report_id)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if report is None:
        raise AdminReportWorkflowError(
            "report_not_found",
            "Report was not found.",
            status_code=404,
        )
    try:
        return list(
            db.scalars(
                select(ReportInstitutionDeliveryEvent)
                .where(ReportInstitutionDeliveryEvent.report_id == report_id)
                .order_by(ReportInstitutionDeliveryEvent.revision)
            ).all()
        )
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)


__all__ = [
    "AdminReportWorkflowError",
    "append_report_institution_delivery_event",
    "append_report_review_decision",
    "list_report_institution_delivery_events",
    "list_report_review_decisions",
]
