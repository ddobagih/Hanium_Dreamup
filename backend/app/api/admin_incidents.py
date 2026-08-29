"""Administrator-only record view and status observations for CRITICAL incidents."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request, Response
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.database import get_db
from backend.app.models import CriticalIncident, CriticalIncidentEvent
from backend.app.schemas import (
    AdminIncidentDetailV1,
    AdminIncidentListPageV1,
    AdminIncidentStatusUpdateV1,
    AdminIncidentStatusV1,
    CriticalIncidentStatus,
)
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof
from backend.app.services.admin_incident_projection import (
    AdminIncidentCursorError,
    AdminIncidentFilters,
    admin_incident_cursor_predicate,
    admin_incident_filter_sha256,
    allowed_next_incident_states,
    decode_admin_incident_cursor,
    encode_admin_incident_cursor,
    project_admin_incident_detail,
    project_admin_incident_summary,
)
from backend.app.services.admin_incident_workflow import (
    CriticalIncidentWorkflowError,
    update_critical_incident_status,
)
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_read_audit import persist_admin_operation_audit


ADMIN_INCIDENT_LIST_OPERATION = "admin.incident.list"
ADMIN_INCIDENT_DETAIL_OPERATION = "admin.incident.detail"
ADMIN_INCIDENT_STATUS_OPERATION = "admin.incident.status.update"
_LIST_QUERY_FIELDS = frozenset({"limit", "cursor", "status"})
_CANONICAL_UUID_PATTERN = (
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


def _require_read_context(
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


def _require_action_context(
    request: Request,
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
    if identity.step_up_verified_at is None:
        raise HTTPException(
            status_code=403,
            detail={
                "code": "admin_step_up_required",
                "message": "Recent administrator reauthentication is required.",
            },
        )
    if (
        proof.admin_id != identity.admin_id
        or proof.session_id != identity.session_id
        or proof.device_id != identity.device_id
        or proof.correlation_id is None
        or proof.action != ADMIN_INCIDENT_STATUS_OPERATION
        or proof.read_purpose is not None
        or proof.purpose != "ACTION"
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


def _query_shape_is_allowed(request: Request) -> bool:
    seen: set[str] = set()
    for key, _value in request.query_params.multi_items():
        if key not in _LIST_QUERY_FIELDS or key in seen:
            return False
        seen.add(key)
    return True


def _persist_audit(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    proof: VerifiedAdminDeviceProof,
    operation: str,
    resource_type: str,
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
        operation=operation,
        outcome=outcome,
        resource_type=resource_type,
        resource_id=resource_id,
        query_sha256=proof.query_sha256,
        result_count=result_count,
        error_code=error_code,
    )


def persist_admin_incident_validation_audit(
    request: Request,
    db: Session,
) -> None:
    if request.method.upper() == "PATCH":
        identity, proof = _require_action_context(request)
        operation = ADMIN_INCIDENT_STATUS_OPERATION
        resource_type = "critical_incident"
        resource_id = request.url.path.removeprefix("/admin/incidents/").removesuffix(
            "/status"
        )
    elif request.url.path == "/admin/incidents":
        identity, proof = _require_read_context(
            request,
            expected_operation=ADMIN_INCIDENT_LIST_OPERATION,
        )
        operation = ADMIN_INCIDENT_LIST_OPERATION
        resource_type = "incident_list"
        resource_id = "admin/incidents"
    else:
        identity, proof = _require_read_context(
            request,
            expected_operation=ADMIN_INCIDENT_DETAIL_OPERATION,
        )
        operation = ADMIN_INCIDENT_DETAIL_OPERATION
        resource_type = "critical_incident"
        resource_id = "invalid-incident-id"
    _persist_audit(
        db,
        identity=identity,
        proof=proof,
        operation=operation,
        resource_type=resource_type,
        resource_id=resource_id[:160] or "invalid-incident-id",
        outcome="DENIED",
        result_count=None,
        error_code="admin_incident_request_invalid",
    )


def list_admin_incidents(
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
    limit: int = Query(default=25, ge=1, le=100),
    cursor: str | None = Query(default=None, min_length=1, max_length=1024),
    status: CriticalIncidentStatus | None = Query(default=None),
) -> AdminIncidentListPageV1:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = _require_read_context(
        request,
        expected_operation=ADMIN_INCIDENT_LIST_OPERATION,
    )
    filters = AdminIncidentFilters(status=status)
    filter_sha256 = admin_incident_filter_sha256(filters)
    try:
        if not _query_shape_is_allowed(request):
            raise ValueError("administrator incident query fields are invalid")
        decoded = (
            decode_admin_incident_cursor(
                cursor,
                expected_filter_sha256=filter_sha256,
            )
            if cursor is not None
            else None
        )
    except AdminIncidentCursorError as exc:
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_LIST_OPERATION,
            resource_type="incident_list",
            resource_id="admin/incidents",
            outcome="DENIED",
            result_count=None,
            error_code="admin_incident_cursor_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_incident_cursor_invalid",
                "message": "The administrator incident cursor is invalid for these filters.",
            },
        ) from exc
    except ValueError as exc:
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_LIST_OPERATION,
            resource_type="incident_list",
            resource_id="admin/incidents",
            outcome="DENIED",
            result_count=None,
            error_code="admin_incident_filter_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_incident_filter_invalid",
                "message": "The administrator incident filters are invalid.",
            },
        ) from exc

    statement = select(CriticalIncident)
    if status is not None:
        statement = statement.where(CriticalIncident.status == status)
    if decoded is not None:
        statement = statement.where(
            admin_incident_cursor_predicate(
                decoded.detected_at,
                decoded.incident_id,
            )
        )
    statement = statement.order_by(
        CriticalIncident.detected_at.desc(),
        CriticalIncident.id.desc(),
    ).limit(limit + 1)
    try:
        rows = list(db.scalars(statement).all())
        page_rows = rows[:limit]
        items = [project_admin_incident_summary(row) for row in page_rows]
        next_cursor = (
            encode_admin_incident_cursor(
                detected_at=page_rows[-1].detected_at,
                incident_id=page_rows[-1].id,
                filter_sha256=filter_sha256,
            )
            if len(rows) > limit
            else None
        )
        page = AdminIncidentListPageV1(
            schema_version="walksafe.admin-incident-list.v1",
            items=items,
            next_cursor=next_cursor,
        )
    except (SQLAlchemyError, AttributeError, TypeError, ValueError) as exc:
        db.rollback()
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_LIST_OPERATION,
            resource_type="incident_list",
            resource_id="admin/incidents",
            outcome="ERROR",
            result_count=None,
            error_code="admin_incident_list_unavailable",
        )
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_incident_list_unavailable",
                "message": "The administrator incident list is temporarily unavailable.",
            },
        ) from exc
    _persist_audit(
        db,
        identity=identity,
        proof=proof,
        operation=ADMIN_INCIDENT_LIST_OPERATION,
        resource_type="incident_list",
        resource_id="admin/incidents",
        outcome="SUCCEEDED",
        result_count=len(items),
        error_code=None,
    )
    return page


def get_admin_incident_detail(
    request: Request,
    response: Response,
    incident_id: str = Path(
        ...,
        min_length=36,
        max_length=36,
        pattern=_CANONICAL_UUID_PATTERN,
    ),
    db: Session = Depends(get_db),
) -> AdminIncidentDetailV1:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = _require_read_context(
        request,
        expected_operation=ADMIN_INCIDENT_DETAIL_OPERATION,
    )
    parsed_id = uuid.UUID(incident_id)
    if request.query_params:
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_DETAIL_OPERATION,
            resource_type="critical_incident",
            resource_id=incident_id,
            outcome="DENIED",
            result_count=None,
            error_code="admin_incident_query_invalid",
        )
        raise HTTPException(
            status_code=422,
            detail={
                "code": "admin_incident_query_invalid",
                "message": "The administrator incident detail does not accept query fields.",
            },
        )
    try:
        incident = db.get(CriticalIncident, parsed_id)
        events = (
            list(
                db.scalars(
                    select(CriticalIncidentEvent)
                    .where(CriticalIncidentEvent.incident_id == parsed_id)
                    .order_by(CriticalIncidentEvent.revision)
                ).all()
            )
            if incident is not None
            else []
        )
    except SQLAlchemyError as exc:
        db.rollback()
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_DETAIL_OPERATION,
            resource_type="critical_incident",
            resource_id=incident_id,
            outcome="ERROR",
            result_count=None,
            error_code="admin_incident_detail_unavailable",
        )
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_incident_detail_unavailable",
                "message": "The administrator incident detail is temporarily unavailable.",
            },
        ) from exc
    if incident is None:
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_DETAIL_OPERATION,
            resource_type="critical_incident",
            resource_id=incident_id,
            outcome="DENIED",
            result_count=None,
            error_code="admin_incident_not_found",
        )
        raise HTTPException(
            status_code=404,
            detail={
                "code": "admin_incident_not_found",
                "message": "The administrator incident was not found.",
            },
        )
    try:
        detail = project_admin_incident_detail(incident, events)
    except (AttributeError, TypeError, ValueError) as exc:
        db.rollback()
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_DETAIL_OPERATION,
            resource_type="critical_incident",
            resource_id=incident_id,
            outcome="ERROR",
            result_count=None,
            error_code="admin_incident_projection_invalid",
        )
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_incident_projection_invalid",
                "message": "The administrator incident detail is temporarily unavailable.",
            },
        ) from exc
    _persist_audit(
        db,
        identity=identity,
        proof=proof,
        operation=ADMIN_INCIDENT_DETAIL_OPERATION,
        resource_type="critical_incident",
        resource_id=incident_id,
        outcome="SUCCEEDED",
        result_count=1,
        error_code=None,
    )
    return detail


def patch_admin_incident_status(
    incident_id: uuid.UUID,
    payload: AdminIncidentStatusUpdateV1,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
) -> AdminIncidentStatusV1:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    identity, proof = _require_action_context(request)
    try:
        snapshot = update_critical_incident_status(
            db,
            incident_id=incident_id,
            payload=payload,
            identity=identity,
            correlation_id=proof.correlation_id,
            query_sha256=proof.query_sha256,
        )
    except CriticalIncidentWorkflowError as exc:
        _persist_audit(
            db,
            identity=identity,
            proof=proof,
            operation=ADMIN_INCIDENT_STATUS_OPERATION,
            resource_type="critical_incident",
            resource_id=str(incident_id),
            outcome="ERROR" if exc.status_code >= 500 else "DENIED",
            result_count=None,
            error_code=exc.code,
        )
        detail: dict[str, object] = {"code": exc.code, "message": exc.message}
        if exc.latest is not None:
            detail["latest"] = exc.latest
        raise HTTPException(status_code=exc.status_code, detail=detail) from exc
    return AdminIncidentStatusV1(
        schema_version="walksafe.admin-incident-status.v1",
        incident_id=snapshot.incident_id,
        status=snapshot.status,
        status_version=snapshot.status_version,
        allowed_next_states=list(allowed_next_incident_states(snapshot.status)),
        updated_at=snapshot.updated_at,
    )


def create_router() -> APIRouter:
    router = APIRouter()
    router.add_api_route(
        "/admin/incidents",
        list_admin_incidents,
        methods=["GET"],
        response_model=AdminIncidentListPageV1,
    )
    router.add_api_route(
        "/admin/incidents/{incident_id}/status",
        patch_admin_incident_status,
        methods=["PATCH"],
        response_model=AdminIncidentStatusV1,
    )
    router.add_api_route(
        "/admin/incidents/{incident_id}",
        get_admin_incident_detail,
        methods=["GET"],
        response_model=AdminIncidentDetailV1,
    )
    return router


__all__ = [
    "ADMIN_INCIDENT_DETAIL_OPERATION",
    "ADMIN_INCIDENT_LIST_OPERATION",
    "ADMIN_INCIDENT_STATUS_OPERATION",
    "create_router",
    "persist_admin_incident_validation_audit",
]
