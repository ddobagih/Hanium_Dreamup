"""Administrator-only minimum report-list API."""

from __future__ import annotations

import hashlib
import uuid

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request, Response
from pydantic import AwareDatetime
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.field_test_security import ADMIN_RECONFIRM_NONCE_HEADER_NAME
from backend.app.models import (
    Report,
    ReportDeliveryPackage,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
)
from backend.app.schemas import (
    AdminAuditEventType,
    AdminAuditListPageV1,
    AdminReportDeliveryPackageProofV1,
    AdminReportDetailV2,
    AdminReportListPageV1,
    AdminReportPackageCreateRequest,
    AdminReportStatusUpdateV1,
    AdminReportStatusV1,
    ClassName,
    ReportStatus,
)
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof
from backend.app.services.admin_report_projection import (
    AdminReportCursorError,
    AdminReportFilters,
    admin_report_delivery_columns,
    admin_report_detail_columns,
    admin_report_list_columns,
    admin_report_review_columns,
    admin_report_cursor_predicate,
    admin_report_filter_sha256,
    coerce_admin_report_delivery_row,
    coerce_admin_report_detail_row,
    coerce_admin_report_list_row,
    coerce_admin_report_review_row,
    decode_admin_report_cursor,
    encode_admin_report_cursor,
    project_admin_report_detail,
    project_admin_report_summary,
)
from backend.app.services.admin_audit_projection import (
    AdminAuditCursorError,
    admin_audit_filter_sha256,
    decode_admin_audit_cursor,
    encode_admin_audit_cursor,
    project_admin_audit_event,
    query_admin_audit_events,
)
from backend.app.services.admin_report_delivery_package import (
    create_admin_report_delivery_package,
    get_admin_report_delivery_package_proof,
)
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    allowed_next_statuses,
    update_admin_report_status,
)
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_read_audit import persist_admin_operation_audit


ADMIN_REPORT_LIST_OPERATION = "admin.report.list"
ADMIN_REPORT_LIST_RESOURCE_TYPE = "admin_report_list"
ADMIN_REPORT_LIST_RESOURCE_ID = "admin/reports"
ADMIN_REPORT_DETAIL_OPERATION = "admin.report.detail"
ADMIN_REPORT_DETAIL_RESOURCE_TYPE = "admin_report_detail"
ADMIN_REPORT_STATUS_OPERATION = "admin.report.status.update"
ADMIN_REPORT_PACKAGE_OPERATION = "admin.report.delivery_package.create"
ADMIN_REPORT_PACKAGE_PROOF_OPERATION = "admin.report.delivery_package.proof"
ADMIN_AUDIT_LIST_OPERATION = "admin.audit.list"
ADMIN_REPORT_LIST_QUERY_FIELDS = frozenset(
    {
        "limit",
        "cursor",
        "report_id",
        "status",
        "class_name",
        "created_from",
        "created_to",
    }
)
ADMIN_AUDIT_LIST_QUERY_FIELDS = frozenset(
    {"limit", "cursor", "event_type", "actor_id"}
)


def _require_admin_report_read_context(
    request: Request,
    *,
    expected_operation: str,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    identity = getattr(request.state, "admin_security_identity", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(
            status_code=401,
            detail={
                "code": "admin_session_required",
                "message": "An authenticated administrator session is required.",
            },
        )
    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(proof, VerifiedAdminDeviceProof):
        raise HTTPException(
            status_code=403,
            detail={
                "code": "admin_device_proof_required",
                "message": "A verified administrator device proof is required.",
            },
        )
    if (
        proof.admin_id != identity.admin_id
        or proof.session_id != identity.session_id
        or proof.device_id != identity.device_id
        or proof.correlation_id is None
        or proof.action is not None
        or proof.purpose != "ACTION"
        or proof.read_purpose != expected_operation
        or proof.method != request.method.upper()
        or proof.path != request.url.path
    ):
        raise HTTPException(
            status_code=403,
            detail={
                "code": "admin_device_proof_invalid",
                "message": "The administrator device proof does not match this request.",
            },
        )
    return identity, proof


def _require_admin_report_action_context(
    request: Request,
    *,
    expected_action: str,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    identity = getattr(request.state, "admin_security_identity", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(status_code=401, detail={"code": "admin_session_required", "message": "An authenticated administrator session is required."})
    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(proof, VerifiedAdminDeviceProof):
        raise HTTPException(status_code=403, detail={"code": "admin_device_proof_required", "message": "A verified administrator device proof is required."})
    if identity.step_up_verified_at is None:
        raise HTTPException(status_code=403, detail={"code": "admin_step_up_required", "message": "Recent administrator reauthentication is required."})
    if (
        proof.admin_id != identity.admin_id
        or proof.session_id != identity.session_id
        or proof.device_id != identity.device_id
        or proof.correlation_id is None
        or proof.action != expected_action
        or proof.read_purpose is not None
        or proof.purpose != "ACTION"
        or proof.method != request.method.upper()
        or proof.path != request.url.path
    ):
        raise HTTPException(status_code=403, detail={"code": "admin_device_proof_invalid", "message": "The administrator device proof does not match this request."})
    return identity, proof


def require_admin_report_list_context(
    request: Request,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    return _require_admin_report_read_context(
        request,
        expected_operation=ADMIN_REPORT_LIST_OPERATION,
    )


def require_admin_report_detail_context(
    request: Request,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    return _require_admin_report_read_context(
        request,
        expected_operation=ADMIN_REPORT_DETAIL_OPERATION,
    )


def require_admin_report_package_proof_context(
    request: Request,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    return _require_admin_report_read_context(
        request,
        expected_operation=ADMIN_REPORT_PACKAGE_PROOF_OPERATION,
    )


def _persist_list_audit(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    proof: VerifiedAdminDeviceProof,
    outcome: str,
    result_count: int | None,
    error_code: str | None,
) -> None:
    persist_admin_operation_audit(
        db,
        actor_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=proof.correlation_id,
        operation=ADMIN_REPORT_LIST_OPERATION,
        outcome=outcome,
        resource_type=ADMIN_REPORT_LIST_RESOURCE_TYPE,
        resource_id=ADMIN_REPORT_LIST_RESOURCE_ID,
        query_sha256=proof.query_sha256,
        result_count=result_count,
        error_code=error_code,
    )


def _persist_detail_audit(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    proof: VerifiedAdminDeviceProof,
    resource_id: str,
    outcome: str,
    result_count: int | None,
    error_code: str | None,
) -> None:
    persist_admin_operation_audit(
        db,
        actor_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=proof.correlation_id,
        operation=ADMIN_REPORT_DETAIL_OPERATION,
        outcome=outcome,
        resource_type=ADMIN_REPORT_DETAIL_RESOURCE_TYPE,
        resource_id=resource_id,
        query_sha256=proof.query_sha256,
        result_count=result_count,
        error_code=error_code,
    )


def _persist_package_proof_audit(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    proof: VerifiedAdminDeviceProof,
    resource_id: str,
    outcome: str,
    result_count: int | None,
    error_code: str | None,
) -> None:
    persist_admin_operation_audit(
        db,
        actor_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=proof.correlation_id,
        operation=ADMIN_REPORT_PACKAGE_PROOF_OPERATION,
        outcome=outcome,
        resource_type="delivery_package",
        resource_id=resource_id,
        query_sha256=proof.query_sha256,
        result_count=result_count,
        error_code=error_code,
    )


def persist_admin_report_request_validation_audit(
    request: Request,
    db: Session,
) -> None:
    if request.url.path == "/admin/reports":
        identity, proof = require_admin_report_list_context(request)
        _persist_list_audit(
            db,
            identity=identity,
            proof=proof,
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_filter_invalid",
        )
        return
    identity, proof = require_admin_report_detail_context(request)
    _persist_detail_audit(
        db,
        identity=identity,
        proof=proof,
        resource_id="invalid-report-id",
        outcome="DENIED",
        result_count=None,
        error_code="admin_report_id_invalid",
    )


def _parse_canonical_report_id(value: str | None) -> uuid.UUID | None:
    if value is None:
        return None
    try:
        parsed = uuid.UUID(value)
    except (AttributeError, ValueError) as exc:
        raise ValueError("report_id must be a canonical UUID") from exc
    if str(parsed) != value:
        raise ValueError("report_id must be a canonical UUID")
    return parsed


def _query_shape_is_allowed(request: Request) -> bool:
    seen: set[str] = set()
    for key, _value in request.query_params.multi_items():
        if key not in ADMIN_REPORT_LIST_QUERY_FIELDS or key in seen:
            return False
        seen.add(key)
    return True


def _audit_query_shape_is_allowed(request: Request) -> bool:
    seen: set[str] = set()
    for key, _value in request.query_params.multi_items():
        if key not in ADMIN_AUDIT_LIST_QUERY_FIELDS or key in seen:
            return False
        seen.add(key)
    return True


def list_admin_reports(
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
    limit: int = Query(default=25, ge=1, le=100),
    cursor: str | None = Query(default=None, min_length=1, max_length=1024),
    report_id: str | None = Query(
        default=None,
        min_length=36,
        max_length=36,
        pattern=(
            r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            r"[0-9a-f]{4}-[0-9a-f]{12}$"
        ),
    ),
    status: ReportStatus | None = Query(default=None),
    class_name: ClassName | None = Query(default=None),
    created_from: AwareDatetime | None = Query(default=None),
    created_to: AwareDatetime | None = Query(default=None),
) -> AdminReportListPageV1:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = require_admin_report_list_context(request)
    try:
        if not _query_shape_is_allowed(request):
            raise ValueError("administrator report query fields are invalid")
        parsed_report_id = _parse_canonical_report_id(report_id)
        if (
            created_from is not None
            and created_to is not None
            and created_from > created_to
        ):
            raise ValueError("created_from must not be after created_to")
        filters = AdminReportFilters(
            report_id=parsed_report_id,
            status=status,
            class_name=class_name,
            created_from=created_from,
            created_to=created_to,
        )
        filter_sha256 = admin_report_filter_sha256(filters)
        decoded_cursor = (
            decode_admin_report_cursor(
                cursor,
                expected_filter_sha256=filter_sha256,
            )
            if cursor is not None
            else None
        )
    except AdminReportCursorError as exc:
        _persist_list_audit(
            db,
            identity=identity,
            proof=proof,
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_cursor_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_report_cursor_invalid",
                "message": "The administrator report cursor is invalid for these filters.",
            },
        ) from exc
    except ValueError as exc:
        _persist_list_audit(
            db,
            identity=identity,
            proof=proof,
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_filter_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_report_filter_invalid",
                "message": "The administrator report filters are invalid.",
            },
        ) from exc

    statement = select(*admin_report_list_columns())
    if filters.report_id is not None:
        statement = statement.where(Report.id == filters.report_id)
    if filters.status is not None:
        statement = statement.where(Report.status == filters.status)
    if filters.class_name is not None:
        statement = statement.where(Report.class_name == filters.class_name)
    if filters.created_from is not None:
        statement = statement.where(Report.created_at >= filters.created_from)
    if filters.created_to is not None:
        statement = statement.where(Report.created_at <= filters.created_to)
    if decoded_cursor is not None:
        statement = statement.where(
            admin_report_cursor_predicate(
                decoded_cursor.created_at,
                decoded_cursor.report_id,
            )
        )
    statement = statement.order_by(Report.created_at.desc(), Report.id.desc()).limit(
        limit + 1
    )

    try:
        rows = [
            coerce_admin_report_list_row(row)
            for row in db.execute(statement).all()
        ]
    except SQLAlchemyError as exc:
        db.rollback()
        _persist_list_audit(
            db,
            identity=identity,
            proof=proof,
            outcome="ERROR",
            result_count=None,
            error_code="admin_report_list_unavailable",
        )
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_report_list_unavailable",
                "message": "The administrator report list is temporarily unavailable.",
            },
        ) from exc

    page_rows = rows[:limit]
    try:
        items = [project_admin_report_summary(report) for report in page_rows]
        next_cursor = (
            encode_admin_report_cursor(
                created_at=page_rows[-1].created_at,
                report_id=page_rows[-1].id,
                filter_sha256=filter_sha256,
            )
            if len(rows) > limit
            else None
        )
        page = AdminReportListPageV1(
            schema_version="walksafe.admin-report-list.v1",
            items=items,
            next_cursor=next_cursor,
        )
    except (AttributeError, TypeError, ValueError) as exc:
        db.rollback()
        _persist_list_audit(
            db,
            identity=identity,
            proof=proof,
            outcome="ERROR",
            result_count=None,
            error_code="admin_report_projection_invalid",
        )
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_report_projection_invalid",
                "message": "The administrator report list is temporarily unavailable.",
            },
        ) from exc

    _persist_list_audit(
        db,
        identity=identity,
        proof=proof,
        outcome="SUCCEEDED",
        result_count=len(items),
        error_code=None,
    )
    return page


def get_admin_report_detail(
    request: Request,
    response: Response,
    report_id: str = Path(
        ...,
        min_length=36,
        max_length=36,
        pattern=(
            r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            r"[0-9a-f]{4}-[0-9a-f]{12}$"
        ),
    ),
    db: Session = Depends(get_db),
) -> AdminReportDetailV2:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = require_admin_report_detail_context(request)
    try:
        parsed_report_id = _parse_canonical_report_id(report_id)
        if parsed_report_id is None:
            raise ValueError("report_id is required")
    except ValueError as exc:
        _persist_detail_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id="invalid-report-id",
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_id_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_report_id_invalid",
                "message": "The administrator report identifier is invalid.",
            },
        ) from exc
    if request.query_params:
        _persist_detail_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id=str(parsed_report_id),
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_query_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_report_query_invalid",
                "message": "The administrator report detail does not accept query fields.",
            },
        )

    try:
        report_row = db.execute(
            select(*admin_report_detail_columns()).where(Report.id == parsed_report_id)
        ).one_or_none()
        if report_row is not None:
            report = coerce_admin_report_detail_row(report_row)
            review_row = db.execute(
                select(*admin_report_review_columns())
                .where(
                    ReportReviewDecision.report_id == parsed_report_id,
                    ReportReviewDecision.content_revision
                    == report.content_revision,
                )
                .order_by(ReportReviewDecision.revision.desc())
                .limit(1)
            ).one_or_none()
            delivery_row = db.execute(
                select(*admin_report_delivery_columns())
                .join(
                    ReportDeliveryPackage,
                    ReportDeliveryPackage.id
                    == ReportInstitutionDeliveryEvent.package_id,
                )
                .where(ReportInstitutionDeliveryEvent.report_id == parsed_report_id)
                .where(
                    ReportDeliveryPackage.content_revision
                    == report.content_revision
                )
                .order_by(ReportInstitutionDeliveryEvent.revision.desc())
                .limit(1)
            ).one_or_none()
            review = (
                coerce_admin_report_review_row(review_row)
                if review_row is not None else None
            )
            delivery = (
                coerce_admin_report_delivery_row(delivery_row)
                if delivery_row is not None else None
            )
        else:
            report = None
            review = None
            delivery = None
    except SQLAlchemyError as exc:
        db.rollback()
        _persist_detail_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id=str(parsed_report_id),
            outcome="ERROR",
            result_count=None,
            error_code="admin_report_detail_unavailable",
        )
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_report_detail_unavailable",
                "message": "The administrator report detail is temporarily unavailable.",
            },
        ) from exc

    if report is None:
        _persist_detail_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id=str(parsed_report_id),
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_not_found",
        )
        raise HTTPException(
            status_code=404,
            detail={
                "code": "admin_report_not_found",
                "message": "The administrator report was not found.",
            },
        )

    try:
        detail = project_admin_report_detail(report, review, delivery)
    except (AttributeError, TypeError, ValueError) as exc:
        db.rollback()
        _persist_detail_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id=str(parsed_report_id),
            outcome="ERROR",
            result_count=None,
            error_code="admin_report_projection_invalid",
        )
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_report_projection_invalid",
                "message": "The administrator report detail is temporarily unavailable.",
            },
        ) from exc

    _persist_detail_audit(
        db,
        identity=identity,
        proof=proof,
        resource_id=str(parsed_report_id),
        outcome="SUCCEEDED",
        result_count=1,
        error_code=None,
    )
    return detail


def get_delivery_package_proof(
    request: Request,
    response: Response,
    report_id: str = Path(
        ...,
        min_length=36,
        max_length=36,
        pattern=(
            r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            r"[0-9a-f]{4}-[0-9a-f]{12}$"
        ),
    ),
    package_revision: int = Path(..., ge=1),
    db: Session = Depends(get_db),
) -> AdminReportDeliveryPackageProofV1:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = require_admin_report_package_proof_context(request)
    try:
        parsed_report_id = _parse_canonical_report_id(report_id)
        if parsed_report_id is None:
            raise ValueError("report_id is required")
    except ValueError as exc:
        _persist_package_proof_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id="invalid-delivery-package",
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_id_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_report_id_invalid",
                "message": "The administrator report identifier is invalid.",
            },
        ) from exc
    if request.query_params:
        _persist_package_proof_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id=f"{parsed_report_id}:{package_revision}",
            outcome="DENIED",
            result_count=None,
            error_code="admin_report_query_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_report_query_invalid",
                "message": "The delivery package proof does not accept query fields.",
            },
        )
    try:
        package = get_admin_report_delivery_package_proof(
            db,
            report_id=parsed_report_id,
            package_revision=package_revision,
        )
    except AdminReportWorkflowError as exc:
        _persist_package_proof_audit(
            db,
            identity=identity,
            proof=proof,
            resource_id=f"{parsed_report_id}:{package_revision}",
            outcome="ERROR" if exc.status_code >= 500 else "DENIED",
            result_count=None,
            error_code=exc.code,
        )
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": exc.message},
        ) from exc
    _persist_package_proof_audit(
        db,
        identity=identity,
        proof=proof,
        resource_id=f"{parsed_report_id}:{package_revision}",
        outcome="SUCCEEDED",
        result_count=1,
        error_code=None,
    )
    return AdminReportDeliveryPackageProofV1(
        schema_version="walksafe.admin-report-delivery-package-proof.v1",
        package_revision=package.package_revision,
        content_revision=package.content_revision,
        review_revision=package.review_revision,
        package_schema_version=package.package_schema_version,
        package_byte_count=package.package_byte_count,
        package_sha256=package.package_sha256,
    )


def patch_admin_report_status(
    report_id: uuid.UUID,
    payload: AdminReportStatusUpdateV1,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
) -> AdminReportStatusV1:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = _require_admin_report_action_context(
        request, expected_action=ADMIN_REPORT_STATUS_OPERATION
    )
    try:
        report = update_admin_report_status(
            db,
            report_id=report_id,
            payload=payload,
            identity=identity,
            correlation_id=proof.correlation_id,
            query_sha256=proof.query_sha256,
        )
    except AdminReportWorkflowError as exc:
        persist_admin_operation_audit(
            db,
            actor_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            correlation_id=proof.correlation_id,
            operation=ADMIN_REPORT_STATUS_OPERATION,
            outcome="ERROR" if exc.status_code >= 500 else "DENIED",
            resource_type="report_status",
            resource_id=str(report_id),
            query_sha256=proof.query_sha256,
            result_count=None,
            error_code=exc.code,
        )
        detail: dict[str, object] = {"code": exc.code, "message": exc.message}
        if exc.latest_status is not None:
            detail["latest"] = exc.latest_status
        raise HTTPException(status_code=exc.status_code, detail=detail) from exc
    return AdminReportStatusV1(
        schema_version="walksafe.admin-report-status.v1",
        id=report.id,
        status=report.status,
        status_version=report.status_version,
        allowed_next_statuses=list(allowed_next_statuses(report.status)),
        updated_at=report.updated_at,
    )


def create_delivery_package(
    report_id: uuid.UUID,
    payload: AdminReportPackageCreateRequest,
    request: Request,
    db: Session = Depends(get_db),
    runtime_totp_secret: str | None = None,
) -> Response:
    identity, proof = _require_admin_report_action_context(
        request, expected_action=ADMIN_REPORT_PACKAGE_OPERATION
    )
    try:
        created = create_admin_report_delivery_package(
            db,
            report_id=report_id,
            expected_content_revision=payload.expected_content_revision,
            expected_review_revision=payload.expected_review_revision,
            identity=identity,
            correlation_id=proof.correlation_id,
            query_sha256=proof.query_sha256,
            proof_challenge_id=getattr(proof, "challenge_id", None),
            proof_request_body=proof.request_body,
            reconfirmation_nonce_sha256=hashlib.sha256(
                request.headers[ADMIN_RECONFIRM_NONCE_HEADER_NAME]
                .strip()
                .encode("ascii")
            ).hexdigest(),
            runtime_totp_secret=runtime_totp_secret,
        )
    except AdminReportWorkflowError as exc:
        persist_admin_operation_audit(
            db,
            actor_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            correlation_id=proof.correlation_id,
            operation=ADMIN_REPORT_PACKAGE_OPERATION,
            outcome="ERROR" if exc.status_code >= 500 else "DENIED",
            resource_type="delivery_package",
            resource_id=str(report_id),
            query_sha256=proof.query_sha256,
            result_count=None,
            error_code=exc.code,
        )
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": exc.message},
        ) from exc
    return Response(
        content=created.package_bytes,
        status_code=201,
        media_type="application/zip",
        headers={
            "Cache-Control": "no-store",
            "Pragma": "no-cache",
            "Content-Disposition": f'attachment; filename="report-{report_id}-r{created.record.revision}.zip"',
            "X-WalkSafe-Package-Id": str(created.record.id),
            "X-WalkSafe-Package-Revision": str(created.record.revision),
            "X-WalkSafe-Content-Revision": str(created.record.content_revision),
            "X-WalkSafe-Review-Revision": str(created.review_revision),
            "X-WalkSafe-Package-Byte-Count": str(created.record.package_byte_count),
            "X-WalkSafe-Supersedes-Package-Id": (
                str(created.record.supersedes_package_id)
                if created.record.supersedes_package_id is not None
                else ""
            ),
            "X-WalkSafe-Export-Audit-Id": str(created.record.export_audit_id),
            "X-WalkSafe-Package-SHA256": created.record.package_sha256,
            "X-WalkSafe-CSV-SHA256": created.record.csv_sha256,
            "X-WalkSafe-Manifest-SHA256": created.record.manifest_sha256,
        },
    )


def list_admin_audit_events(
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
    limit: int = Query(default=25, ge=1, le=100),
    cursor: str | None = Query(default=None, min_length=1, max_length=1024),
    event_type: AdminAuditEventType | None = Query(default=None),
    actor_id: str | None = Query(default=None, min_length=3, max_length=64),
) -> AdminAuditListPageV1:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = _require_admin_report_read_context(
        request, expected_operation=ADMIN_AUDIT_LIST_OPERATION
    )
    filter_sha256 = admin_audit_filter_sha256(
        event_type=event_type, actor_id=actor_id
    )
    try:
        if not _audit_query_shape_is_allowed(request):
            raise AdminAuditCursorError("administrator audit query is invalid")
        decoded = (
            decode_admin_audit_cursor(cursor, expected_filter_sha256=filter_sha256)
            if cursor is not None else None
        )
    except AdminAuditCursorError as exc:
        persist_admin_operation_audit(
            db, actor_id=identity.admin_id, session_id=identity.session_id,
            device_id=identity.device_id, correlation_id=proof.correlation_id,
            operation=ADMIN_AUDIT_LIST_OPERATION, outcome="DENIED",
            resource_type="admin_audit_list", resource_id="admin/reports/audits",
            query_sha256=proof.query_sha256, result_count=None,
            error_code="admin_audit_cursor_invalid",
        )
        raise HTTPException(status_code=422, detail={"code": "admin_audit_cursor_invalid", "message": "The administrator audit cursor is invalid for these filters."}) from exc
    try:
        rows = query_admin_audit_events(
            db, limit=limit, cursor=decoded,
            event_type=event_type, actor_id=actor_id,
        )
        page_rows = rows[:limit]
        page = AdminAuditListPageV1(
            schema_version="walksafe.admin-audit-list.v1",
            items=[project_admin_audit_event(row) for row in page_rows],
            next_cursor=(
                encode_admin_audit_cursor(row=page_rows[-1], filter_sha256=filter_sha256)
                if len(rows) > limit else None
            ),
        )
    except (SQLAlchemyError, AttributeError, TypeError, ValueError) as exc:
        db.rollback()
        persist_admin_operation_audit(
            db, actor_id=identity.admin_id, session_id=identity.session_id,
            device_id=identity.device_id, correlation_id=proof.correlation_id,
            operation=ADMIN_AUDIT_LIST_OPERATION, outcome="ERROR",
            resource_type="admin_audit_list", resource_id="admin/reports/audits",
            query_sha256=proof.query_sha256, result_count=None,
            error_code="admin_audit_list_unavailable",
        )
        raise HTTPException(status_code=503, detail={"code": "admin_audit_list_unavailable", "message": "The administrator audit list is temporarily unavailable."}) from exc
    persist_admin_operation_audit(
        db, actor_id=identity.admin_id, session_id=identity.session_id,
        device_id=identity.device_id, correlation_id=proof.correlation_id,
        operation=ADMIN_AUDIT_LIST_OPERATION, outcome="SUCCEEDED",
        resource_type="admin_audit_list", resource_id="admin/reports/audits",
        query_sha256=proof.query_sha256, result_count=len(page.items), error_code=None,
    )
    return page


def create_router(settings: Settings | None = None) -> APIRouter:
    def create_delivery_package_route(
        report_id: uuid.UUID,
        payload: AdminReportPackageCreateRequest,
        request: Request,
        db: Session = Depends(get_db),
    ) -> Response:
        return create_delivery_package(
            report_id,
            payload,
            request,
            db=db,
            runtime_totp_secret=getattr(settings, "admin_totp_secret", None),
        )

    router = APIRouter()
    router.add_api_route(
        "/admin/reports",
        list_admin_reports,
        methods=["GET"],
        response_model=AdminReportListPageV1,
    )
    router.add_api_route(
        "/admin/reports/audits",
        list_admin_audit_events,
        methods=["GET"],
        response_model=AdminAuditListPageV1,
    )
    router.add_api_route(
        "/admin/reports/{report_id}/status",
        patch_admin_report_status,
        methods=["PATCH"],
        response_model=AdminReportStatusV1,
    )
    router.add_api_route(
        "/admin/reports/{report_id}/delivery-packages",
        create_delivery_package_route,
        methods=["POST"],
        name="create_delivery_package",
        status_code=201,
        response_class=Response,
        responses={
            201: {
                "description": "A deterministic report delivery package.",
                "content": {
                    "application/zip": {
                        "schema": {"type": "string", "format": "binary"},
                    },
                },
                "headers": {
                    "X-WalkSafe-Package-Id": {
                        "schema": {"type": "string", "format": "uuid"}
                    },
                    "X-WalkSafe-Package-Revision": {
                        "schema": {"type": "integer", "minimum": 1}
                    },
                    "X-WalkSafe-Content-Revision": {
                        "schema": {"type": "integer", "minimum": 0}
                    },
                    "X-WalkSafe-Review-Revision": {
                        "schema": {"type": "integer", "minimum": 1}
                    },
                    "X-WalkSafe-Package-Byte-Count": {
                        "schema": {"type": "integer", "minimum": 1}
                    },
                    "X-WalkSafe-Supersedes-Package-Id": {
                        "schema": {
                            "type": "string",
                            "pattern": "^$|^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                        }
                    },
                    "X-WalkSafe-Export-Audit-Id": {
                        "schema": {"type": "string", "format": "uuid"}
                    },
                    "X-WalkSafe-Package-SHA256": {
                        "schema": {"type": "string", "pattern": "^[0-9a-f]{64}$"}
                    },
                    "X-WalkSafe-CSV-SHA256": {
                        "schema": {"type": "string", "pattern": "^[0-9a-f]{64}$"}
                    },
                    "X-WalkSafe-Manifest-SHA256": {
                        "schema": {"type": "string", "pattern": "^[0-9a-f]{64}$"}
                    },
                },
            },
        },
    )
    router.add_api_route(
        "/admin/reports/{report_id}/delivery-packages/{package_revision}/proof",
        get_delivery_package_proof,
        methods=["GET"],
        response_model=AdminReportDeliveryPackageProofV1,
    )
    router.add_api_route(
        "/admin/reports/{report_id}",
        get_admin_report_detail,
        methods=["GET"],
        response_model=AdminReportDetailV2,
    )
    return router
