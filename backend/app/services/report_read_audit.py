"""Durable, fail-closed audit records for exact report and image reads."""

from __future__ import annotations

import re
from typing import Any

from fastapi import HTTPException
from sqlalchemy.orm import Session

from backend.app.field_test_security import ACTOR_ID_PATTERN
from backend.app.models import ReportReadAudit


READ_PURPOSE_PATTERN = re.compile(r"^[a-z][a-z0-9_.:-]{2,63}$")
LOCAL_ACTOR_ID = "local-development"
LOCAL_READ_PURPOSE = "local_development"


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
