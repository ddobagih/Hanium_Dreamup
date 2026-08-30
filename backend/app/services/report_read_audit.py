"""Durable, fail-closed audit records for exact report and image reads."""

from __future__ import annotations

import re
from typing import Any
import uuid

from fastapi import HTTPException
from sqlalchemy.orm import Session

from backend.app.field_test_security import ACTOR_ID_PATTERN
from backend.app.models import AdminOperationAudit, ReportReadAudit


READ_PURPOSE_PATTERN = re.compile(r"^[a-z][a-z0-9_.:-]{2,63}$")
LOCAL_ACTOR_ID = "local-development"
LOCAL_READ_PURPOSE = "local_development"
ADMIN_OPERATION_PATTERN = re.compile(r"^[a-z][a-z0-9_.:-]{2,63}$")
ADMIN_RESOURCE_PATTERN = re.compile(r"^[a-z][a-z0-9_:-]{2,31}$")
ADMIN_ERROR_CODE_PATTERN = re.compile(r"^[a-z][a-z0-9_:-]{2,63}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def resolve_read_audit_identity(
    actor_header: str | None,
    purpose_header: str | None,
    *,
    required: bool,
) -> tuple[str, str]:
    actor_id = (actor_header or "").strip()
    purpose = (purpose_header or "").strip()
    if required and not actor_id:
        raise HTTPException(
            status_code=422,
            detail={"code": "missing_actor_id", "message": "x-walksafe-actor-id is required"},
        )
    if actor_id and ACTOR_ID_PATTERN.fullmatch(actor_id) is None:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_actor_id", "message": "x-walksafe-actor-id has an invalid format"},
        )
    if required and not purpose:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "missing_read_purpose",
                "message": "x-walksafe-read-purpose is required for sensitive report reads",
            },
        )
    if purpose and READ_PURPOSE_PATTERN.fullmatch(purpose) is None:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "invalid_read_purpose",
                "message": "x-walksafe-read-purpose has an invalid format",
            },
        )
    return actor_id or LOCAL_ACTOR_ID, purpose or LOCAL_READ_PURPOSE


def persist_report_read_audit(
    db: Session,
    *,
    actor_id: str,
    purpose: str,
    resource_type: str,
    resource_id: str,
    details: dict[str, Any] | None = None,
) -> None:
    db.add(
        ReportReadAudit(
            actor_id=actor_id,
            purpose=purpose,
            resource_type=resource_type,
            resource_id=resource_id,
            details=details or {},
        )
    )
    try:
        db.commit()
    except BaseException as exc:
        db.rollback()
        raise HTTPException(
            status_code=503,
            detail={
                "code": "read_audit_unavailable",
                "message": "Sensitive report data was withheld because its audit record could not be stored.",
            },
        ) from exc


def persist_admin_operation_audit(
    db: Session,
    *,
    actor_id: str,
    session_id: uuid.UUID,
    device_id: str,
    correlation_id: uuid.UUID,
    operation: str,
    outcome: str,
    resource_type: str,
    resource_id: str,
    query_sha256: str,
    result_count: int | None,
    error_code: str | None,
) -> None:
    valid_result = (
        outcome == "SUCCEEDED"
        and isinstance(result_count, int)
        and result_count >= 0
        and error_code is None
    ) or (
        outcome in {"DENIED", "ERROR"}
        and result_count is None
        and isinstance(error_code, str)
        and ADMIN_ERROR_CODE_PATTERN.fullmatch(error_code) is not None
    )
    if (
        ACTOR_ID_PATTERN.fullmatch(actor_id) is None
        or not isinstance(session_id, uuid.UUID)
        or not isinstance(correlation_id, uuid.UUID)
        or not isinstance(device_id, str)
        or not 8 <= len(device_id) <= 128
        or ADMIN_OPERATION_PATTERN.fullmatch(operation) is None
        or ADMIN_RESOURCE_PATTERN.fullmatch(resource_type) is None
        or not isinstance(resource_id, str)
        or not 1 <= len(resource_id) <= 160
        or SHA256_PATTERN.fullmatch(query_sha256) is None
        or not valid_result
    ):
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_operation_audit_unavailable",
                "message": "Administrator operation audit binding is invalid.",
            },
        )
    db.add(
        AdminOperationAudit(
            actor_id=actor_id,
            session_id=session_id,
            device_id=device_id,
            correlation_id=correlation_id,
            operation=operation,
            outcome=outcome,
            resource_type=resource_type,
            resource_id=resource_id,
            query_sha256=query_sha256,
            result_count=result_count,
            error_code=error_code,
        )
    )
    try:
        db.commit()
    except BaseException as exc:
        db.rollback()
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_operation_audit_unavailable",
                "message": (
                    "Administrator operation data was withheld because its "
                    "audit record could not be stored."
                ),
            },
        ) from exc
