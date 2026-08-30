"""FIELD self-service report lifecycle and administrator request handling."""

from __future__ import annotations

import re
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response
from sqlalchemy import and_, or_, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.field_test_security import FieldTestAccess, VerifiedActorAssertion
from backend.app.models import ReportUserRequest
from backend.app.schemas import (
    AdminReportUserRequestDetailV1,
    AdminReportUserRequestListPageV1,
    AdminReportUserRequestStatusUpdateV1,
    AdminReportUserRequestStatusV1,
    ReportUserRequestCreateV1,
    ReportContentCorrectionRequestV1,
    ReportContentCurrentV1,
    ReportContentRevisionV1,
    ReportDeletionStatusV1,
    ReportUserRequestStatus,
    ReportUserRequestSummaryV1,
    ReportUserRequestType,
    ReportUserStatus,
    UserReportDetailV1,
    UserReportListPageV1,
)
from backend.app.services.report_content_corrections import (
    ReportContentCorrectionError,
    apply_owned_report_correction,
    get_owned_report_content,
)
from backend.app.services.report_deletion import (
    ReportDeletionError,
    get_owned_report_deletion_status,
)
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.privacy_lifecycle import (
    lock_privacy_subject_shared,
    privacy_subject_hmac,
)
from backend.app.services.report_read_audit import persist_admin_operation_audit
from backend.app.services.report_user_requests import (
    ReportUserCursorError,
    ReportUserRequestError,
    admin_request_columns,
    admin_request_summary_columns,
    admin_request_filter_digest,
    allowed_request_statuses,
    assert_subject_active,
    coerce_admin_request,
    coerce_admin_request_summary,
    coerce_user_report,
    create_report_user_request,
    decode_cursor,
    encode_cursor,
    get_owned_user_report,
    latest_request_summaries,
    project_admin_request_detail,
    project_admin_request_summary,
    project_user_report,
    update_admin_request_status,
    user_report_filter_digest,
    user_report_statement,
)


_ACTOR_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
_GENERATION = re.compile(r"^[1-9][0-9]{0,18}$")
_NO_STORE = {"Cache-Control": "no-store", "Pragma": "no-cache"}
ADMIN_REQUEST_LIST_OPERATION = "admin.report_request.list"
ADMIN_REQUEST_DETAIL_OPERATION = "admin.report_request.detail"
ADMIN_REQUEST_STATUS_OPERATION = "admin.report_request.status.update"
_ADMIN_LIST_QUERY_FIELDS = frozenset(
    {"limit", "cursor", "report_id", "request_type", "status"}
)
_USER_LIST_QUERY_FIELDS = frozenset({"limit", "cursor", "user_status"})


def _not_found() -> None:
    raise HTTPException(
        status_code=404,
        detail={"code": "report_not_found"},
        headers=_NO_STORE,
    )


def _canonical_uuid(value: str) -> uuid.UUID:
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        _not_found()
    if str(parsed) != value:
        _not_found()
    return parsed


def _query_shape(request: Request, allowed: frozenset[str]) -> bool:
    seen: set[str] = set()
    for key, _value in request.query_params.multi_items():
        if key not in allowed or key in seen:
            return False
        seen.add(key)
    return True


def _field_binding(
    request: Request,
    settings: Settings,
    *,
    actor_id: str,
    account_generation: str,
) -> tuple[str, int, str]:
    if _ACTOR_ID.fullmatch(actor_id) is None or _GENERATION.fullmatch(
        account_generation
    ) is None:
        _not_found()
    generation = int(account_generation)
    if generation > 9_223_372_036_854_775_807:
        _not_found()
    assertion = getattr(request.state, "verified_actor_assertion", None)
    if settings.field_test_security_enabled and (
        not isinstance(assertion, VerifiedActorAssertion)
        or assertion.access is not FieldTestAccess.FIELD
        or assertion.actor_id != actor_id
        or assertion.account_generation != generation
    ):
        _not_found()
    subject = privacy_subject_hmac(
        actor_id,
        generation,
        settings.privacy_hmac_secret,
    )
    return actor_id, generation, subject


def _raise_user_error(db: Session, exc: ReportUserRequestError) -> None:
    db.rollback()
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
        headers=_NO_STORE,
    ) from exc


def _raise_content_error(db: Session, exc: ReportContentCorrectionError) -> None:
    db.rollback()
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
        headers=_NO_STORE,
    ) from exc


def _raise_deletion_error(db: Session, exc: ReportDeletionError) -> None:
    db.rollback()
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
        headers=_NO_STORE,
    ) from exc


def _admin_read_context(
    request: Request, *, operation: str
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    identity = getattr(request.state, "admin_security_identity", None)
    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(
            status_code=401,
            detail={"code": "admin_session_required"},
            headers=_NO_STORE,
        )
    if not isinstance(proof, VerifiedAdminDeviceProof):
        raise HTTPException(
            status_code=403,
            detail={"code": "admin_device_proof_required"},
            headers=_NO_STORE,
        )
    if (
        proof.admin_id != identity.admin_id
        or proof.session_id != identity.session_id
        or proof.device_id != identity.device_id
        or proof.correlation_id is None
        or proof.action is not None
        or proof.read_purpose != operation
        or proof.purpose != "ACTION"
        or proof.method != request.method.upper()
        or proof.path != request.url.path
    ):
        raise HTTPException(
            status_code=403,
            detail={"code": "admin_device_proof_invalid"},
            headers=_NO_STORE,
        )
    return identity, proof


def _admin_action_context(
    request: Request,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    identity = getattr(request.state, "admin_security_identity", None)
    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(
            status_code=401,
            detail={"code": "admin_session_required"},
            headers=_NO_STORE,
        )
    if not isinstance(proof, VerifiedAdminDeviceProof):
        raise HTTPException(
            status_code=403,
            detail={"code": "admin_device_proof_required"},
            headers=_NO_STORE,
        )
    if identity.step_up_verified_at is None:
        raise HTTPException(
            status_code=403,
            detail={"code": "admin_step_up_required"},
            headers=_NO_STORE,
        )
    if (
        proof.admin_id != identity.admin_id
        or proof.session_id != identity.session_id
        or proof.device_id != identity.device_id
        or proof.correlation_id is None
        or proof.action != ADMIN_REQUEST_STATUS_OPERATION
        or proof.read_purpose is not None
        or proof.purpose != "ACTION"
        or proof.method != request.method.upper()
        or proof.path != request.url.path
    ):
        raise HTTPException(
            status_code=403,
            detail={"code": "admin_device_proof_invalid"},
            headers=_NO_STORE,
        )
    return identity, proof


def _audit(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    proof: VerifiedAdminDeviceProof,
    operation: str,
    resource_id: str,
    outcome: str,
    result_count: int | None,
    error_code: str | None,
) -> None:
    try:
        persist_admin_operation_audit(
            db,
            actor_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            correlation_id=proof.correlation_id,
            operation=operation,
            outcome=outcome,
            resource_type="report_request",
            resource_id=resource_id,
            query_sha256=proof.query_sha256,
            result_count=result_count,
            error_code=error_code,
        )
    except HTTPException as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail=exc.detail,
            headers=_NO_STORE,
        ) from exc


def persist_admin_report_request_validation_audit(
    request: Request,
    db: Session,
) -> None:
    if request.method.upper() == "GET" and request.url.path == "/admin/report-requests":
        operation = ADMIN_REQUEST_LIST_OPERATION
        identity, proof = _admin_read_context(request, operation=operation)
    elif request.method.upper() == "GET":
        operation = ADMIN_REQUEST_DETAIL_OPERATION
        identity, proof = _admin_read_context(request, operation=operation)
    elif request.method.upper() == "PATCH":
        operation = ADMIN_REQUEST_STATUS_OPERATION
        identity, proof = _admin_action_context(request)
    else:
        raise HTTPException(
            status_code=404,
            detail={"code": "report_request_not_found"},
            headers=_NO_STORE,
        )
    _audit(
        db,
        identity=identity,
        proof=proof,
        operation=operation,
        resource_id=request.url.path.removeprefix("/")[:160],
        outcome="DENIED",
        result_count=None,
        error_code="report_request_validation_failed",
    )


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    @router.get("/reports/mine", response_model=UserReportListPageV1)
    def list_my_reports(
        request: Request,
        response: Response,
        limit: int = Query(default=25, ge=1, le=100),
        cursor: str | None = Query(default=None, min_length=1, max_length=1024),
        user_status: ReportUserStatus | None = Query(default=None),
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        db: Session = Depends(get_db),
    ) -> UserReportListPageV1:
        response.headers.update(_NO_STORE)
        actor_id, generation, subject = _field_binding(
            request,
            settings,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
        )
        del actor_id
        digest = user_report_filter_digest(
            privacy_subject=subject,
            account_generation=generation,
            user_status=user_status,
        )
        try:
            if not _query_shape(request, _USER_LIST_QUERY_FIELDS):
                raise ReportUserCursorError("query is invalid")
            decoded = (
                decode_cursor(cursor, expected_filter_digest=digest)
                if cursor is not None
                else None
            )
            lock_privacy_subject_shared(db, subject, generation)
            assert_subject_active(
                db, privacy_subject=subject, account_generation=generation
            )
            rows = [
                row
                for row in db.execute(
                    user_report_statement(
                        privacy_subject=subject,
                        account_generation=generation,
                        user_status=user_status,
                        cursor=decoded,
                        limit=limit,
                    )
                ).all()
            ]
            page_rows = rows[:limit]
            report_ids = [row.report_id for row in page_rows]
            latest = latest_request_summaries(db, report_ids)
            items = [
                project_user_report(
                    row=coerce_user_report(row),
                    latest_request=latest.get(row.report_id),
                )
                for row in page_rows
            ]
            next_cursor = (
                encode_cursor(
                    created_at=page_rows[-1].created_at,
                    row_id=page_rows[-1].report_id,
                    filter_digest=digest,
                )
                if len(rows) > limit
                else None
            )
        except ReportUserCursorError as exc:
            raise HTTPException(
                status_code=422,
                detail={"code": "report_cursor_invalid"},
                headers=_NO_STORE,
            ) from exc
        except ReportUserRequestError as exc:
            _raise_user_error(db, exc)
        except SQLAlchemyError as exc:
            db.rollback()
            raise HTTPException(
                status_code=503,
                detail={"code": "report_user_view_unavailable"},
                headers=_NO_STORE,
            ) from exc
        return UserReportListPageV1(
            schema_version="walksafe.user-report-list.v1",
            items=items,
            next_cursor=next_cursor,
        )

    @router.get(
        "/reports/mine/deletions/{request_id}",
        response_model=ReportDeletionStatusV1,
    )
    def get_my_report_deletion(
        request_id: str,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        db: Session = Depends(get_db),
    ) -> ReportDeletionStatusV1:
        response.headers.update(_NO_STORE)
        if request.query_params:
            _not_found()
        parsed_id = _canonical_uuid(request_id)
        _actor, generation, subject = _field_binding(
            request,
            settings,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
        )
        try:
            state = get_owned_report_deletion_status(
                db,
                request_id=parsed_id,
                privacy_subject=subject,
                account_generation=generation,
            )
        except ReportDeletionError as exc:
            _raise_deletion_error(db, exc)
        except ReportUserRequestError as exc:
            _raise_user_error(db, exc)
        except SQLAlchemyError as exc:
            db.rollback()
            raise HTTPException(
                status_code=503,
                detail={"code": "report_deletion_status_unavailable"},
                headers=_NO_STORE,
            ) from exc
        return ReportDeletionStatusV1(
            schema_version="walksafe.report-deletion-status.v1",
            request_id=state.request_id,
            report_id=state.report_id,
            state=state.state,
            request_status_version=state.request_status_version,
            external_copy_count=state.external_copy_count,
            updated_at=state.updated_at,
        )

    @router.get("/reports/mine/{report_id}", response_model=UserReportDetailV1)
    def get_my_report(
        report_id: str,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        db: Session = Depends(get_db),
    ) -> UserReportDetailV1:
        response.headers.update(_NO_STORE)
        if request.query_params:
            _not_found()
        parsed_id = _canonical_uuid(report_id)
        _actor, generation, subject = _field_binding(
            request,
            settings,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
        )
        try:
            return get_owned_user_report(
                db,
                report_id=parsed_id,
                privacy_subject=subject,
                account_generation=generation,
            )
        except ReportUserRequestError as exc:
            _raise_user_error(db, exc)
        except SQLAlchemyError as exc:
            db.rollback()
            raise HTTPException(
                status_code=503,
                detail={"code": "report_user_view_unavailable"},
                headers=_NO_STORE,
            ) from exc

    @router.post(
        "/reports/mine/{report_id}/requests",
        response_model=ReportUserRequestSummaryV1,
        status_code=201,
    )
    def create_my_report_request(
        report_id: str,
        payload: ReportUserRequestCreateV1,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        db: Session = Depends(get_db),
    ) -> ReportUserRequestSummaryV1:
        response.headers.update(_NO_STORE)
        if request.query_params:
            _not_found()
        parsed_id = _canonical_uuid(report_id)
        _actor, generation, subject = _field_binding(
            request,
            settings,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
        )
        try:
            item, created = create_report_user_request(
                db,
                report_id=parsed_id,
                payload=payload,
                privacy_subject=subject,
                account_generation=generation,
            )
        except ReportUserRequestError as exc:
            _raise_user_error(db, exc)
        except SQLAlchemyError as exc:
            db.rollback()
            raise HTTPException(
                status_code=503,
                detail={"code": "report_request_store_unavailable"},
                headers=_NO_STORE,
            ) from exc
        response.status_code = 201 if created else 200
        return ReportUserRequestSummaryV1(
            request_id=item.id,
            request_type=item.request_type,
            status=item.status,
            status_version=item.status_version,
            public_response=item.public_response,
            created_at=item.created_at,
            updated_at=item.updated_at,
        )

    @router.get(
        "/reports/mine/{report_id}/content",
        response_model=ReportContentCurrentV1,
    )
    def get_my_report_content(
        report_id: str,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        db: Session = Depends(get_db),
    ) -> ReportContentCurrentV1:
        response.headers.update(_NO_STORE)
        if request.query_params:
            _not_found()
        parsed_id = _canonical_uuid(report_id)
        _actor, generation, subject = _field_binding(
            request,
            settings,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
        )
        try:
            state = get_owned_report_content(
                db,
                report_id=parsed_id,
                privacy_subject=subject,
                account_generation=generation,
            )
        except ReportContentCorrectionError as exc:
            _raise_content_error(db, exc)
        except ReportUserRequestError as exc:
            _raise_user_error(db, exc)
        return ReportContentCurrentV1(
            schema_version="walksafe.report-content-current.v1",
            report_id=state.report_id,
            revision=state.revision,
            content_sha256=state.content_sha256,
            user_description=state.user_description,
            category_hint=state.category_hint,
            corrected_at=state.corrected_at,
        )

    @router.post(
        "/reports/mine/{report_id}/corrections",
        response_model=ReportContentRevisionV1,
        status_code=201,
    )
    def correct_my_report_content(
        report_id: str,
        payload: ReportContentCorrectionRequestV1,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        db: Session = Depends(get_db),
    ) -> ReportContentRevisionV1:
        response.headers.update(_NO_STORE)
        if request.query_params:
            _not_found()
        parsed_id = _canonical_uuid(report_id)
        _actor, generation, subject = _field_binding(
            request,
            settings,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
        )
        try:
            state, created = apply_owned_report_correction(
                db,
                report_id=parsed_id,
                payload=payload,
                privacy_subject=subject,
                account_generation=generation,
            )
        except ReportContentCorrectionError as exc:
            _raise_content_error(db, exc)
        except ReportUserRequestError as exc:
            _raise_user_error(db, exc)
        response.status_code = 201 if created else 200
        return ReportContentRevisionV1(
            schema_version="walksafe.report-content-revision.v1",
            report_id=state.report_id,
            revision=state.revision,
            expected_revision=state.expected_revision,
            idempotency_key=state.idempotency_key,
            content_sha256=state.content_sha256,
            user_description=state.user_description,
            category_hint=state.category_hint,
            corrected_at=state.corrected_at,
        )

    @router.get(
        "/admin/report-requests",
        response_model=AdminReportUserRequestListPageV1,
    )
    def list_admin_report_requests(
        request: Request,
        response: Response,
        limit: int = Query(default=25, ge=1, le=100),
        cursor: str | None = Query(default=None, min_length=1, max_length=1024),
        report_id: uuid.UUID | None = Query(default=None),
        request_type: ReportUserRequestType | None = Query(default=None),
        status: ReportUserRequestStatus | None = Query(default=None),
        db: Session = Depends(get_db),
    ) -> AdminReportUserRequestListPageV1:
        response.headers.update(_NO_STORE)
        identity, proof = _admin_read_context(
            request, operation=ADMIN_REQUEST_LIST_OPERATION
        )
        digest = admin_request_filter_digest(
            report_id=report_id, request_type=request_type, status=status
        )
        try:
            if not _query_shape(request, _ADMIN_LIST_QUERY_FIELDS):
                raise ReportUserCursorError("query is invalid")
            decoded = (
                decode_cursor(cursor, expected_filter_digest=digest)
                if cursor is not None
                else None
            )
        except ReportUserCursorError as exc:
            _audit(
                db, identity=identity, proof=proof,
                operation=ADMIN_REQUEST_LIST_OPERATION,
                resource_id="admin/report-requests", outcome="DENIED",
                result_count=None, error_code="report_request_cursor_invalid",
            )
            raise HTTPException(
                status_code=422,
                detail={"code": "report_request_cursor_invalid"},
                headers=_NO_STORE,
            ) from exc
        statement = select(*admin_request_summary_columns())
        if report_id is not None:
            statement = statement.where(ReportUserRequest.report_id == report_id)
        if request_type is not None:
            statement = statement.where(ReportUserRequest.request_type == request_type)
        if status is not None:
            statement = statement.where(ReportUserRequest.status == status)
        if decoded is not None:
            statement = statement.where(
                or_(
                    ReportUserRequest.created_at < decoded.created_at,
                    and_(
                        ReportUserRequest.created_at == decoded.created_at,
                        ReportUserRequest.id < decoded.row_id,
                    ),
                )
            )
        statement = statement.order_by(
            ReportUserRequest.created_at.desc(), ReportUserRequest.id.desc()
        ).limit(limit + 1)
        try:
            rows = [
                coerce_admin_request_summary(row)
                for row in db.execute(statement).all()
            ]
            page_rows = rows[:limit]
            page = AdminReportUserRequestListPageV1(
                schema_version="walksafe.admin-report-request-list.v1",
                items=[project_admin_request_summary(row) for row in page_rows],
                next_cursor=(
                    encode_cursor(
                        created_at=page_rows[-1].created_at,
                        row_id=page_rows[-1].request_id,
                        filter_digest=digest,
                    )
                    if len(rows) > limit else None
                ),
            )
        except (SQLAlchemyError, AttributeError, TypeError, ValueError) as exc:
            db.rollback()
            _audit(
                db, identity=identity, proof=proof,
                operation=ADMIN_REQUEST_LIST_OPERATION,
                resource_id="admin/report-requests", outcome="ERROR",
                result_count=None, error_code="report_request_list_unavailable",
            )
            raise HTTPException(
                status_code=503,
                detail={"code": "report_request_list_unavailable"},
                headers=_NO_STORE,
            ) from exc
        _audit(
            db, identity=identity, proof=proof,
            operation=ADMIN_REQUEST_LIST_OPERATION,
            resource_id="admin/report-requests", outcome="SUCCEEDED",
            result_count=len(page.items), error_code=None,
        )
        return page

    @router.get(
        "/admin/report-requests/{request_id}",
        response_model=AdminReportUserRequestDetailV1,
    )
    def get_admin_report_request(
        request_id: uuid.UUID,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> AdminReportUserRequestDetailV1:
        response.headers.update(_NO_STORE)
        identity, proof = _admin_read_context(
            request, operation=ADMIN_REQUEST_DETAIL_OPERATION
        )
        if request.query_params:
            _audit(
                db, identity=identity, proof=proof,
                operation=ADMIN_REQUEST_DETAIL_OPERATION,
                resource_id=str(request_id), outcome="DENIED",
                result_count=None, error_code="report_request_query_invalid",
            )
            raise HTTPException(
                status_code=422,
                detail={"code": "report_request_query_invalid"},
                headers=_NO_STORE,
            )
        try:
            row = db.execute(
                select(*admin_request_columns()).where(
                    ReportUserRequest.id == request_id
                )
            ).one_or_none()
            detail = project_admin_request_detail(coerce_admin_request(row)) if row else None
        except (SQLAlchemyError, AttributeError, TypeError, ValueError) as exc:
            db.rollback()
            _audit(
                db, identity=identity, proof=proof,
                operation=ADMIN_REQUEST_DETAIL_OPERATION,
                resource_id=str(request_id), outcome="ERROR",
                result_count=None, error_code="report_request_detail_unavailable",
            )
            raise HTTPException(
                status_code=503,
                detail={"code": "report_request_detail_unavailable"},
                headers=_NO_STORE,
            ) from exc
        if detail is None:
            _audit(
                db, identity=identity, proof=proof,
                operation=ADMIN_REQUEST_DETAIL_OPERATION,
                resource_id=str(request_id), outcome="DENIED",
                result_count=None, error_code="report_request_not_found",
            )
            raise HTTPException(
                status_code=404,
                detail={"code": "report_request_not_found"},
                headers=_NO_STORE,
            )
        _audit(
            db, identity=identity, proof=proof,
            operation=ADMIN_REQUEST_DETAIL_OPERATION,
            resource_id=str(request_id), outcome="SUCCEEDED",
            result_count=1, error_code=None,
        )
        return detail

    @router.patch(
        "/admin/report-requests/{request_id}/status",
        response_model=AdminReportUserRequestStatusV1,
    )
    def patch_admin_report_request_status(
        request_id: uuid.UUID,
        payload: AdminReportUserRequestStatusUpdateV1,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> AdminReportUserRequestStatusV1:
        response.headers.update(_NO_STORE)
        identity, proof = _admin_action_context(request)
        try:
            item = update_admin_request_status(
                db,
                request_id=request_id,
                payload=payload,
                identity=identity,
                correlation_id=proof.correlation_id,
                query_sha256=proof.query_sha256,
            )
        except ReportUserRequestError as exc:
            _audit(
                db, identity=identity, proof=proof,
                operation=ADMIN_REQUEST_STATUS_OPERATION,
                resource_id=str(request_id),
                outcome="ERROR" if exc.status_code >= 500 else "DENIED",
                result_count=None, error_code=exc.code,
            )
            detail: dict[str, object] = {"code": exc.code, "message": exc.message}
            if exc.latest is not None:
                detail["latest"] = exc.latest
            raise HTTPException(
                status_code=exc.status_code,
                detail=detail,
                headers=_NO_STORE,
            ) from exc
        return AdminReportUserRequestStatusV1(
            schema_version="walksafe.admin-report-request-status.v1",
            request_id=item.id,
            report_id=item.report_id,
            status=item.status,
            status_version=item.status_version,
            allowed_next_statuses=list(
                allowed_request_statuses(
                    item.status, request_type=item.request_type
                )
            ),
            public_response=item.public_response,
            updated_at=item.updated_at,
        )

    return router


__all__ = [
    "ADMIN_REQUEST_DETAIL_OPERATION",
    "ADMIN_REQUEST_LIST_OPERATION",
    "ADMIN_REQUEST_STATUS_OPERATION",
    "create_router",
    "persist_admin_report_request_validation_audit",
]
