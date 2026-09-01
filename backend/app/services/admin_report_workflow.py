"""Append-only administrator review decisions and manual delivery events."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import NoReturn
import uuid

from sqlalchemy import func, select, text
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminOperationAudit,
    Report,
    ReportDeliveryPackage,
    ReportInstitutionDeliveryEvent,
    ReportOriginalAccessAudit,
    ReportOriginalAccessGrant,
    ReportReviewDecision,
    ReportStatusAudit,
)
from backend.app.schemas import (
    AdminReportStatusUpdateV1,
    ReportInstitutionDeliveryRequest,
    ReportReviewDecisionRequest,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    AdminSessionIdentity,
    _is_postgresql_session,
    _resolve_admin_credential_issuer_key,
)
from backend.app.services.admin_report_integrity import (
    admin_report_integrity_boundary_state,
)


_DELIVERY_TRANSITIONS: dict[str | None, frozenset[str]] = {
    None: frozenset({"SUBMITTED", "FAILED"}),
    "FAILED": frozenset({"FAILED", "SUBMITTED"}),
    "SUBMITTED": frozenset({"ACKNOWLEDGED", "FAILED"}),
    "ACKNOWLEDGED": frozenset({"RESOLVED"}),
    "RESOLVED": frozenset(),
}
_REPORT_STATUS_TRANSITIONS: dict[str, tuple[str, ...]] = {
    "new": ("reviewed", "resolved"),
    "reviewed": ("new", "resolved"),
    "resolved": ("reviewed",),
}


class AdminReportWorkflowError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int,
        latest_status: dict[str, object] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.latest_status = latest_status


def _database_sqlstate(exc: BaseException) -> str | None:
    return getattr(getattr(exc, "orig", None), "sqlstate", None)


def allowed_next_statuses(status: str) -> tuple[str, ...]:
    try:
        return _REPORT_STATUS_TRANSITIONS[status]
    except KeyError as exc:
        raise ValueError("unknown report status") from exc


def update_admin_report_status(
    db: Session,
    *,
    report_id: uuid.UUID,
    payload: AdminReportStatusUpdateV1,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    query_sha256: str,
    now: datetime | None = None,
) -> Report:
    """Apply one row-locked CAS transition and its action audit atomically."""

    report = _locked_report(db, report_id)
    current_version = report.status_version
    if payload.expected_version != current_version:
        latest = {
            "id": str(report.id),
            "status": report.status,
            "status_version": current_version,
            "allowed_next_statuses": list(allowed_next_statuses(report.status)),
            "updated_at": report.updated_at,
        }
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "report_status_version_conflict",
            "The report status changed before this request was applied.",
            status_code=409,
            latest_status=latest,
        )
    if payload.status == report.status or payload.status not in allowed_next_statuses(report.status):
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "report_status_transition_invalid",
            f"Report status transition {report.status}->{payload.status} is not allowed.",
            status_code=422,
            latest_status={
                "id": str(report.id),
                "status": report.status,
                "status_version": current_version,
                "allowed_next_statuses": list(allowed_next_statuses(report.status)),
                "updated_at": report.updated_at,
            },
        )

    changed_at = _as_utc(now or datetime.now(UTC))
    previous_status = report.status
    report.status = payload.status
    report.status_version = current_version + 1
    report.updated_at = changed_at
    try:
        db.add(
            ReportStatusAudit(
                report_id=report.id,
                previous_status=previous_status,
                next_status=report.status,
                previous_version=current_version,
                next_version=report.status_version,
                actor_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=identity.device_id,
                correlation_id=correlation_id,
                note=payload.note,
                resolution_reason=payload.resolution_reason,
                created_at=changed_at,
            )
        )
        db.add(
            AdminOperationAudit(
                actor_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=identity.device_id,
                correlation_id=correlation_id,
                operation="admin.report.status.update",
                outcome="SUCCEEDED",
                resource_type="report_status",
                resource_id=str(report.id),
                query_sha256=query_sha256,
                result_count=1,
                error_code=None,
                created_at=changed_at,
            )
        )
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    _commit_append(db, conflict_code="report_status_version_conflict")
    try:
        db.refresh(report)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    return report


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


def allowed_delivery_statuses_for_package(
    db: Session,
    *,
    report_id: uuid.UUID,
    previous: ReportInstitutionDeliveryEvent | None,
    package: ReportDeliveryPackage,
) -> frozenset[str]:
    """Return the one append state machine used by delivery and package proof."""

    if previous is None:
        return _DELIVERY_TRANSITIONS[None]
    if (
        previous.package_id == package.id
        and previous.package_revision == package.revision
    ):
        return _DELIVERY_TRANSITIONS[previous.status]
    previous_package = None
    if previous.package_id is not None and previous.package_revision is not None:
        try:
            previous_package = db.execute(
                select(ReportDeliveryPackage).where(
                    ReportDeliveryPackage.id == previous.package_id,
                    ReportDeliveryPackage.report_id == report_id,
                    ReportDeliveryPackage.revision == previous.package_revision,
                )
            ).scalar_one_or_none()
        except SQLAlchemyError as exc:
            _raise_database_unavailable(db, exc)
    if previous_package is None:
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "delivery_package_revision_conflict",
            "A delivery workflow can switch packages only after the report content changes.",
            status_code=409,
        )
    switching_from_legacy_v1 = (
        previous.status != "RESOLVED"
        and int(previous_package.package_version or 0) == 1
        and previous_package.review_decision_id == package.review_decision_id
        and int(previous_package.content_revision or 0)
        == int(package.content_revision or 0)
    )
    if switching_from_legacy_v1:
        return frozenset({"SUBMITTED"})
    if int(previous_package.content_revision or 0) >= int(
        package.content_revision or 0
    ):
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "delivery_package_revision_conflict",
            "A delivery workflow can switch packages only after the report content changes.",
            status_code=409,
        )
    return _DELIVERY_TRANSITIONS[None]


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


def _secured_review_dml_required(db: Session) -> bool:
    if not _is_postgresql_session(db):
        return False
    boundary_ready = admin_report_integrity_boundary_state(db)
    if boundary_ready is not None:
        if not boundary_ready:
            raise AdminReportWorkflowError(
                "admin_report_workflow_unavailable",
                "The administrator report integrity boundary is unavailable.",
                status_code=503,
            )
        return True
    return bool(
        db.execute(
            text(
                "SELECT NOT ("
                "pg_catalog.has_any_column_privilege(current_user, "
                "'public.report_review_decisions', 'INSERT') AND "
                "pg_catalog.has_any_column_privilege(current_user, "
                "'public.report_original_access_grants', 'UPDATE'))"
            )
        ).scalar_one()
    )


def append_report_review_decision(
    db: Session,
    *,
    report_id: uuid.UUID,
    payload: ReportReviewDecisionRequest,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    proof_challenge_id: uuid.UUID | None = None,
    proof_request_body: bytes | None = None,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
    now: datetime | None = None,
) -> ReportReviewDecision:
    """Append one decision without changing the report lifecycle projection."""

    try:
        secured_dml = _secured_review_dml_required(db)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if secured_dml:
        try:
            report = db.get(Report, report_id)
        except SQLAlchemyError as exc:
            _raise_database_unavailable(db, exc)
        if report is None:
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                "report_not_found", "Report was not found.", status_code=404
            )
    else:
        report = _locked_report(db, report_id)
    current_content_revision = int(report.content_revision or 0)
    if payload.content_revision != current_content_revision:
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "report_content_revision_conflict",
            "The report content changed before this review was recorded.",
            status_code=409,
        )
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

    decided_at = _as_utc(now or datetime.now(UTC))
    if secured_dml:
        if proof_challenge_id is None or proof_request_body is None:
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                "admin_device_proof_invalid",
                "The administrator device proof binding is unavailable.",
                status_code=503,
            )
        try:
            resolved_issuer_key = _resolve_admin_credential_issuer_key(
                db,
                credential_issuer_key,
            )
            decision_id = uuid.uuid4()
            stored = db.execute(
                text(
                    "SELECT * FROM public.walksafe_append_report_review_decision_v3("
                    "CAST(:decision_id AS uuid), CAST(:report_id AS uuid), "
                    "CAST(:content_revision AS bigint), CAST(:decision AS text), "
                    "CAST(:reason AS text), CAST(:user_visible_reason AS text), "
                    "CAST(:duplicate_of_report_id AS uuid), "
                    "CAST(:location_reviewed AS boolean), "
                    "CAST(:photo_reviewed AS boolean), "
                    "CAST(:privacy_reviewed AS boolean), "
                    "CAST(:evidence_grant_id AS uuid), CAST(:admin_id AS text), "
                    "CAST(:session_id AS uuid), CAST(:device_id AS text), "
                    "CAST(:correlation_id AS uuid), "
                    "CAST(:proof_challenge_id AS uuid), "
                    "CAST(:proof_request_body AS bytea), "
                    "CAST(:runtime_totp_secret AS text), "
                    "CAST(:credential_issuer_key AS text))"
                ),
                {
                    "decision_id": decision_id,
                    "report_id": report_id,
                    "content_revision": current_content_revision,
                    "decision": payload.decision,
                    "reason": payload.reason,
                    "user_visible_reason": payload.user_visible_reason,
                    "duplicate_of_report_id": payload.duplicate_of_report_id,
                    "location_reviewed": payload.location_reviewed,
                    "photo_reviewed": payload.photo_reviewed,
                    "privacy_reviewed": payload.privacy_reviewed,
                    "evidence_grant_id": payload.evidence_grant_id,
                    "admin_id": identity.admin_id,
                    "session_id": identity.session_id,
                    "device_id": identity.device_id,
                    "correlation_id": correlation_id,
                    "proof_challenge_id": proof_challenge_id,
                    "proof_request_body": proof_request_body,
                    "runtime_totp_secret": runtime_totp_secret,
                    "credential_issuer_key": resolved_issuer_key,
                },
            ).mappings().one()
        except (AdminSecurityError, SQLAlchemyError) as exc:
            sqlstate = _database_sqlstate(exc)
            if sqlstate in {"40001", "42501"}:
                try:
                    db.rollback()
                except SQLAlchemyError:
                    pass
                if sqlstate == "42501":
                    raise AdminReportWorkflowError(
                        "admin_device_proof_invalid",
                        "The administrator device proof is not valid for this review decision.",
                        status_code=403,
                    ) from exc
                raise AdminReportWorkflowError(
                    "review_evidence_grant_invalid",
                    "Approval requires fresh original evidence reviewed in this administrator session.",
                    status_code=409,
                ) from exc
            _raise_database_unavailable(db, exc)
        result_status = stored["result_status"]
        if result_status not in {"CREATED", "EXISTING"}:
            error_contract = {
                "CONTENT_CONFLICT": (
                    "report_content_revision_conflict",
                    "The report content changed before this review was recorded.",
                    409,
                ),
                "DUPLICATE_NOT_FOUND": (
                    "duplicate_report_not_found",
                    "The duplicate target report was not found.",
                    422,
                ),
                "EVIDENCE_INVALID": (
                    "review_evidence_grant_invalid",
                    "Approval requires fresh original evidence reviewed in this administrator session.",
                    409,
                ),
                "AUDIT_REQUIRED": (
                    "review_evidence_audit_required",
                    "Approval requires committed original evidence access audits.",
                    409,
                ),
                "AUTH_INVALID": (
                    "review_evidence_grant_invalid",
                    "The administrator session changed before this review was recorded.",
                    409,
                ),
            }
            code, message, status_code = error_contract.get(
                result_status,
                (
                    "admin_report_workflow_unavailable",
                    "The administrator report workflow is temporarily unavailable.",
                    503,
                ),
            )
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                code,
                message,
                status_code=status_code,
            )
        _commit_append(db, conflict_code="review_decision_conflict")
        try:
            decision = db.get(ReportReviewDecision, stored["decision_id"])
        except SQLAlchemyError as exc:
            _raise_database_unavailable(db, exc)
        if decision is None:
            raise AdminReportWorkflowError(
                "admin_report_workflow_unavailable",
                "The administrator report workflow is temporarily unavailable.",
                status_code=503,
            )
        return decision

    evidence_grant: ReportOriginalAccessGrant | None = None
    if payload.decision == "APPROVED":
        assert payload.evidence_grant_id is not None
        try:
            evidence_grant = db.get(
                ReportOriginalAccessGrant,
                payload.evidence_grant_id,
                with_for_update=True,
            )
        except SQLAlchemyError as exc:
            _raise_database_unavailable(db, exc)
        if (
            evidence_grant is None
            or evidence_grant.report_id != report_id
            or evidence_grant.admin_id != identity.admin_id
            or evidence_grant.session_id != identity.session_id
            or evidence_grant.device_id != identity.device_id
            or evidence_grant.purpose != "report_review"
            or evidence_grant.content_revision is None
            or int(evidence_grant.content_revision) != current_content_revision
            or evidence_grant.location_disclosed_at is None
            or evidence_grant.access_granted_at is None
            or evidence_grant.consumed_at is None
            or _as_utc(evidence_grant.location_disclosed_at) > decided_at
            or _as_utc(evidence_grant.access_granted_at) > decided_at
            or _as_utc(evidence_grant.expires_at) <= decided_at
            or evidence_grant.review_decision_id is not None
            or evidence_grant.review_bound_at is not None
        ):
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                "review_evidence_grant_invalid",
                "Approval requires fresh original evidence reviewed in this administrator session.",
                status_code=409,
            )
        try:
            evidence_audit_count = db.execute(
                select(func.count(func.distinct(ReportOriginalAccessAudit.action))).where(
                    ReportOriginalAccessAudit.grant_id == evidence_grant.id,
                    ReportOriginalAccessAudit.report_id == report_id,
                    ReportOriginalAccessAudit.admin_id == identity.admin_id,
                    ReportOriginalAccessAudit.session_id == identity.session_id,
                    ReportOriginalAccessAudit.device_id == identity.device_id,
                    ReportOriginalAccessAudit.purpose == "report_review",
                    ReportOriginalAccessAudit.action.in_(
                        ("LOCATION_DISCLOSED", "ACCESS_GRANTED")
                    ),
                    ReportOriginalAccessAudit.outcome == "SUCCESS",
                    ReportOriginalAccessAudit.created_at <= decided_at,
                )
            ).scalar_one()
        except SQLAlchemyError as exc:
            _raise_database_unavailable(db, exc)
        if int(evidence_audit_count or 0) != 2:
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                "review_evidence_audit_required",
                "Approval requires committed original evidence access audits.",
                status_code=409,
            )

    previous = _latest_review_decision(db, report_id)
    decision_id = uuid.uuid4()
    decision = ReportReviewDecision(
        id=decision_id,
        report_id=report_id,
        revision=1 if previous is None else previous.revision + 1,
        content_revision=current_content_revision,
        decision=payload.decision,
        reason=payload.reason,
        user_visible_reason=payload.user_visible_reason,
        duplicate_of_report_id=payload.duplicate_of_report_id,
        evidence_grant_id=payload.evidence_grant_id,
        location_reviewed=payload.location_reviewed,
        photo_reviewed=payload.photo_reviewed,
        privacy_reviewed=payload.privacy_reviewed,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
        decided_at=decided_at,
    )
    if evidence_grant is not None:
        evidence_grant.review_decision_id = decision_id
        evidence_grant.review_bound_at = decided_at
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
        and event.package_revision == payload.package_revision
        and _as_utc(event.observed_at) == payload.observed_at
        and event.expected_revision == payload.expected_revision
        and event.idempotency_key == payload.idempotency_key
    )


def _secured_delivery_event_dml_required(db: Session) -> bool:
    if not _is_postgresql_session(db):
        return False
    boundary_ready = admin_report_integrity_boundary_state(db)
    if boundary_ready is not None:
        if not boundary_ready:
            raise AdminReportWorkflowError(
                "admin_report_workflow_unavailable",
                "The administrator report integrity boundary is unavailable.",
                status_code=503,
            )
        return True
    return not bool(
        db.execute(
            text(
                "SELECT pg_catalog.has_table_privilege("
                "current_user, 'public.report_institution_delivery_events', "
                "'INSERT')"
            )
        ).scalar_one()
    )


def _require_latest_approval(
    db: Session,
    report_id: uuid.UUID,
    *,
    content_revision: int,
) -> ReportReviewDecision:
    decision = _latest_review_decision(db, report_id)
    if (
        decision is None
        or decision.decision != "APPROVED"
        or not decision.location_reviewed
        or not decision.photo_reviewed
        or not decision.privacy_reviewed
        or decision.duplicate_of_report_id is not None
        or getattr(decision, "evidence_grant_id", None) is None
        or int(decision.content_revision or 0) != content_revision
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
    proof_challenge_id: uuid.UUID | None = None,
    proof_request_body: bytes | None = None,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
) -> ReportInstitutionDeliveryEvent:
    """Record a manual delivery observation; this function never sends data."""

    try:
        secured_dml = _secured_delivery_event_dml_required(db)
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if secured_dml:
        try:
            report = db.get(Report, report_id)
        except SQLAlchemyError as exc:
            _raise_database_unavailable(db, exc)
        if report is None:
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                "report_not_found", "Report was not found.", status_code=404
            )
    else:
        report = _locked_report(db, report_id)
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

    try:
        approval = _require_latest_approval(
            db,
            report_id,
            content_revision=int(report.content_revision or 0),
        )
    except AdminReportWorkflowError as exc:
        if exc.code != "admin_report_workflow_unavailable":
            _rollback_or_database_unavailable(db)
        raise
    try:
        package = db.execute(
            select(ReportDeliveryPackage).where(
                ReportDeliveryPackage.report_id == report_id,
                ReportDeliveryPackage.revision == payload.package_revision,
            )
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _raise_database_unavailable(db, exc)
    if (
        package is None
        or package.review_decision_id != approval.id
        or int(package.content_revision or 0) != int(report.content_revision or 0)
        or int(package.package_version or 0) != 2
    ):
        _rollback_or_database_unavailable(db)
        raise AdminReportWorkflowError(
            "delivery_package_invalid",
            "Manual delivery requires a package for the latest approved review.",
            status_code=409,
        )
    allowed_statuses = allowed_delivery_statuses_for_package(
        db,
        report_id=report_id,
        previous=previous,
        package=package,
    )
    if payload.status not in allowed_statuses:
        _rollback_or_database_unavailable(db)
        transition = "none" if previous is None else previous.status
        raise AdminReportWorkflowError(
            "delivery_transition_invalid",
            f"Delivery transition {transition}->{payload.status} is not allowed.",
            status_code=409,
        )
    event_id = uuid.uuid4()
    if secured_dml:
        try:
            resolved_issuer_key = _resolve_admin_credential_issuer_key(
                db,
                credential_issuer_key,
            )
            stored = db.execute(
                text(
                    "SELECT * FROM public."
                    "walksafe_append_report_delivery_event_v3("
                    "CAST(:event_id AS uuid), CAST(:report_id AS uuid), "
                    "CAST(:package_revision AS bigint), "
                    "CAST(:expected_revision AS bigint), "
                    "CAST(:idempotency_key AS uuid), "
                    "CAST(:institution AS text), CAST(:channel AS text), "
                    "CAST(:recipient AS text), CAST(:status AS text), "
                    "CAST(:external_receipt_id AS text), "
                    "CAST(:reason AS text), CAST(:evidence_sha256 AS text), "
                    "CAST(:observed_at AS timestamptz), "
                    "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                    "CAST(:device_id AS text), CAST(:correlation_id AS uuid), "
                    "CAST(:proof_challenge_id AS uuid), "
                    "CAST(:proof_request_body AS bytea), "
                    "CAST(:runtime_totp_secret AS text), "
                    "CAST(:credential_issuer_key AS text))"
                ),
                {
                    "event_id": event_id,
                    "report_id": report_id,
                    "package_revision": payload.package_revision,
                    "expected_revision": payload.expected_revision,
                    "idempotency_key": idempotency_key,
                    "institution": payload.institution,
                    "channel": payload.channel,
                    "recipient": payload.recipient,
                    "status": payload.status,
                    "external_receipt_id": payload.external_receipt_id,
                    "reason": payload.reason,
                    "evidence_sha256": payload.evidence_sha256,
                    "observed_at": payload.observed_at,
                    "admin_id": identity.admin_id,
                    "session_id": identity.session_id,
                    "device_id": identity.device_id,
                    "correlation_id": correlation_id,
                    "proof_challenge_id": proof_challenge_id,
                    "proof_request_body": proof_request_body,
                    "runtime_totp_secret": runtime_totp_secret,
                    "credential_issuer_key": resolved_issuer_key,
                },
            ).mappings().one()
        except (AdminSecurityError, SQLAlchemyError) as exc:
            try:
                db.rollback()
            except SQLAlchemyError:
                pass
            if _database_sqlstate(exc) == "42501":
                raise AdminReportWorkflowError(
                    "admin_device_proof_invalid",
                    "The administrator device proof is not valid for this delivery event.",
                    status_code=403,
                ) from exc
            raise AdminReportWorkflowError(
                "admin_report_workflow_unavailable",
                "The administrator report workflow is temporarily unavailable.",
                status_code=503,
            ) from exc
        result_status = stored["result_status"]
        if result_status not in {"CREATED", "EXISTING"}:
            error_contract = {
                "REPORT_NOT_FOUND": (
                    "report_not_found",
                    "Report was not found.",
                    404,
                ),
                "IDEMPOTENCY_CONFLICT": (
                    "delivery_idempotency_conflict",
                    "The idempotency key was already used for a different delivery event.",
                    409,
                ),
                "REVISION_CONFLICT": (
                    "delivery_revision_conflict",
                    "The delivery event revision changed before this request was applied.",
                    409,
                ),
                "REVIEW_INVALID": (
                    "latest_review_approval_required",
                    "Manual delivery requires the latest typed decision to be fully reviewed and APPROVED.",
                    409,
                ),
                "PACKAGE_INVALID": (
                    "delivery_package_invalid",
                    "Manual delivery requires a package for the latest approved review.",
                    409,
                ),
                "CONTENT_DIGEST_CONFLICT": (
                    "delivery_package_invalid",
                    "Manual delivery requires a package for the latest approved review.",
                    409,
                ),
                "PACKAGE_REVISION_CONFLICT": (
                    "delivery_package_revision_conflict",
                    "A delivery workflow can switch packages only after the report content changes.",
                    409,
                ),
                "TRANSITION_INVALID": (
                    "delivery_transition_invalid",
                    "The requested delivery transition is not allowed.",
                    409,
                ),
                "AUTH_INVALID": (
                    "admin_device_proof_invalid",
                    "The administrator device proof is not valid for this delivery event.",
                    403,
                ),
                "CLAIM_CONFLICT": (
                    "admin_device_proof_invalid",
                    "The administrator device proof is not valid for this delivery event.",
                    403,
                ),
            }
            code, message, status_code = error_contract.get(
                result_status,
                (
                    "admin_report_workflow_unavailable",
                    "The administrator report workflow is temporarily unavailable.",
                    503,
                ),
            )
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                code,
                message,
                status_code=status_code,
            )
        stored_event_id = stored["event_id"]
        stored_revision = int(stored["event_revision"])
        if stored_event_id is None or stored_revision < 1:
            _rollback_or_database_unavailable(db)
            raise AdminReportWorkflowError(
                "admin_report_workflow_unavailable",
                "The administrator report workflow is temporarily unavailable.",
                status_code=503,
            )
        _commit_append(db, conflict_code="delivery_event_conflict")
        try:
            event = db.get(ReportInstitutionDeliveryEvent, stored_event_id)
        except SQLAlchemyError as exc:
            _raise_database_unavailable(db, exc)
        if event is None:
            raise AdminReportWorkflowError(
                "admin_report_workflow_unavailable",
                "The administrator report workflow is temporarily unavailable.",
                status_code=503,
            )
        return event

    event = ReportInstitutionDeliveryEvent(
        id=event_id,
        report_id=report_id,
        review_decision_id=approval.id,
        package_id=package.id,
        package_revision=package.revision,
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
    "allowed_delivery_statuses_for_package",
    "append_report_institution_delivery_event",
    "append_report_review_decision",
    "allowed_next_statuses",
    "list_report_institution_delivery_events",
    "list_report_review_decisions",
    "update_admin_report_status",
]
