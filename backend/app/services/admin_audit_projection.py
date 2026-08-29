"""Allowlisted cross-source administrator audit projection and stable cursors."""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import re
import uuid

from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminOperationAudit,
    AdminSecurityAudit,
    ReportExportAudit,
    ReportInstitutionDeliveryEvent,
    ReportReadAudit,
    ReportReviewDecision,
    ReportStatusAudit,
)
from backend.app.schemas import AdminAuditEventV1


_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,1024}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_EVENT_RANK = {
    "SECURITY": 6,
    "READ": 5,
    "STATUS": 4,
    "REVIEW": 3,
    "EXPORT": 2,
    "DELIVERY": 1,
}


class AdminAuditCursorError(ValueError):
    pass


@dataclass(frozen=True)
class AdminAuditEventRow:
    event_id: str
    event_type: str
    action: str
    outcome: str
    actor_id: str | None
    resource_type: str
    resource_id: str
    occurred_at: datetime
    correlation_id: uuid.UUID | None
    source_rank: int = 1


@dataclass(frozen=True)
class AdminAuditCursor:
    occurred_at: datetime
    event_type: str
    event_id: str
    source_rank: int


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("audit timestamp must include a timezone")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def admin_audit_filter_sha256(
    *, event_type: str | None, actor_id: str | None
) -> str:
    canonical = json.dumps(
        {"actor_id": actor_id, "event_type": event_type},
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    return hashlib.sha256(canonical).hexdigest()


def encode_admin_audit_cursor(
    *, row: AdminAuditEventRow, filter_sha256: str
) -> str:
    if _SHA256_PATTERN.fullmatch(filter_sha256) is None:
        raise ValueError("filter_sha256 must be lowercase SHA-256")
    payload = {
        "event_id": row.event_id,
        "event_type": row.event_type,
        "filter_sha256": filter_sha256,
        "occurred_at": _utc_text(row.occurred_at),
        "source_rank": row.source_rank,
        "v": 1,
    }
    canonical = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode(
        "ascii"
    )
    return base64.urlsafe_b64encode(canonical).rstrip(b"=").decode("ascii")


def decode_admin_audit_cursor(
    value: str, *, expected_filter_sha256: str
) -> AdminAuditCursor:
    if (
        _CURSOR_PATTERN.fullmatch(value) is None
        or _SHA256_PATTERN.fullmatch(expected_filter_sha256) is None
    ):
        raise AdminAuditCursorError("administrator audit cursor is invalid")
    try:
        padded = value + "=" * ((4 - len(value) % 4) % 4)
        payload = json.loads(
            base64.b64decode(
                padded.encode("ascii"), altchars=b"-_", validate=True
            ).decode("ascii")
        )
        if set(payload) != {
            "event_id",
            "event_type",
            "filter_sha256",
            "occurred_at",
            "source_rank",
            "v",
        }:
            raise ValueError
        occurred_at = datetime.fromisoformat(payload["occurred_at"].replace("Z", "+00:00"))
        event_id = uuid.UUID(payload["event_id"])
    except (AttributeError, TypeError, ValueError, UnicodeError, json.JSONDecodeError) as exc:
        raise AdminAuditCursorError("administrator audit cursor is invalid") from exc
    if (
        payload["v"] != 1
        or payload["filter_sha256"] != expected_filter_sha256
        or payload["event_type"] not in _EVENT_RANK
        or not isinstance(payload["event_id"], str)
        or type(payload["source_rank"]) is not int
        or not 1 <= payload["source_rank"] <= 16
        or str(event_id) != payload["event_id"]
        or _utc_text(occurred_at) != payload["occurred_at"]
    ):
        raise AdminAuditCursorError("administrator audit cursor does not match the filters")
    cursor = AdminAuditCursor(
        occurred_at=occurred_at,
        event_type=payload["event_type"],
        event_id=payload["event_id"],
        source_rank=payload["source_rank"],
    )
    canonical_row = AdminAuditEventRow(
        event_id=cursor.event_id,
        event_type=cursor.event_type,
        action="cursor",
        outcome="SUCCEEDED",
        actor_id=None,
        resource_type="cursor",
        resource_id="cursor",
        occurred_at=cursor.occurred_at,
        correlation_id=None,
        source_rank=cursor.source_rank,
    )
    if encode_admin_audit_cursor(row=canonical_row, filter_sha256=expected_filter_sha256) != value:
        raise AdminAuditCursorError("administrator audit cursor is invalid")
    return cursor


def project_admin_audit_event(row: AdminAuditEventRow) -> AdminAuditEventV1:
    return AdminAuditEventV1(
        event_id=row.event_id,
        event_type=row.event_type,
        action=row.action,
        outcome=row.outcome,
        actor_id=row.actor_id,
        resource_type=row.resource_type,
        resource_id=row.resource_id,
        occurred_at=row.occurred_at,
        correlation_id=row.correlation_id,
    )


def _after_cursor(row: AdminAuditEventRow, cursor: AdminAuditCursor | None) -> bool:
    if cursor is None:
        return True
    return (
        row.occurred_at,
        _EVENT_RANK[row.event_type],
        row.source_rank,
        row.event_id,
    ) < (
        cursor.occurred_at,
        _EVENT_RANK[cursor.event_type],
        cursor.source_rank,
        cursor.event_id,
    )


def _cursor_clause(
    time_column: object,
    id_column: object,
    event_type: str,
    cursor: AdminAuditCursor | None,
    source_rank: int = 1,
):
    if cursor is None:
        return None
    rank = _EVENT_RANK[event_type]
    cursor_rank = _EVENT_RANK[cursor.event_type]
    if rank < cursor_rank:
        return time_column <= cursor.occurred_at
    if rank > cursor_rank:
        return time_column < cursor.occurred_at
    if source_rank < cursor.source_rank:
        return time_column <= cursor.occurred_at
    if source_rank > cursor.source_rank:
        return time_column < cursor.occurred_at
    cursor_id = uuid.UUID(cursor.event_id)
    return or_(
        time_column < cursor.occurred_at,
        and_(time_column == cursor.occurred_at, id_column < cursor_id),
    )


def query_admin_audit_events(
    db: Session,
    *,
    limit: int,
    cursor: AdminAuditCursor | None,
    event_type: str | None,
    actor_id: str | None,
) -> list[AdminAuditEventRow]:
    """Read only named audit fields into a common DTO; never expose JSON details."""

    rows: list[AdminAuditEventRow] = []
    selected = set(_EVENT_RANK) if event_type is None else {event_type}

    if "SECURITY" in selected:
        statement = select(
            AdminSecurityAudit.id,
            AdminSecurityAudit.action,
            AdminSecurityAudit.outcome,
            AdminSecurityAudit.admin_id,
            AdminSecurityAudit.created_at,
        )
        if actor_id is not None:
            statement = statement.where(AdminSecurityAudit.admin_id == actor_id)
        cursor_clause = _cursor_clause(
            AdminSecurityAudit.created_at, AdminSecurityAudit.id, "SECURITY", cursor
        )
        if cursor_clause is not None:
            statement = statement.where(cursor_clause)
        for item in db.execute(
            statement.order_by(AdminSecurityAudit.created_at.desc(), AdminSecurityAudit.id.desc()).limit(limit + 1)
        ).all():
            rows.append(AdminAuditEventRow(
                event_id=str(item.id), event_type="SECURITY", action=item.action,
                outcome="SUCCEEDED" if item.outcome == "SUCCESS" else item.outcome,
                actor_id=item.admin_id, resource_type="admin_security",
                resource_id=str(item.id), occurred_at=item.created_at, correlation_id=None,
                source_rank=1,
            ))

    if "READ" in selected:
        operation = select(
            AdminOperationAudit.id, AdminOperationAudit.operation,
            AdminOperationAudit.outcome, AdminOperationAudit.actor_id,
            AdminOperationAudit.resource_type, AdminOperationAudit.resource_id,
            AdminOperationAudit.created_at, AdminOperationAudit.correlation_id,
        ).where(AdminOperationAudit.operation.in_((
            "admin.report.list", "admin.report.detail", "admin.audit.list"
        )))
        read = select(
            ReportReadAudit.id, ReportReadAudit.purpose, ReportReadAudit.actor_id,
            ReportReadAudit.resource_type, ReportReadAudit.resource_id,
            ReportReadAudit.created_at,
        )
        if actor_id is not None:
            operation = operation.where(AdminOperationAudit.actor_id == actor_id)
            read = read.where(ReportReadAudit.actor_id == actor_id)
        operation_cursor = _cursor_clause(
            AdminOperationAudit.created_at, AdminOperationAudit.id, "READ", cursor, 2
        )
        read_cursor = _cursor_clause(
            ReportReadAudit.created_at, ReportReadAudit.id, "READ", cursor
        )
        if operation_cursor is not None:
            operation = operation.where(operation_cursor)
        if read_cursor is not None:
            read = read.where(read_cursor)
        for item in db.execute(operation.order_by(AdminOperationAudit.created_at.desc(), AdminOperationAudit.id.desc()).limit(limit + 1)).all():
            rows.append(AdminAuditEventRow(
                event_id=str(item.id), event_type="READ", action=item.operation,
                outcome=item.outcome, actor_id=item.actor_id,
                resource_type=item.resource_type, resource_id=item.resource_id,
                occurred_at=item.created_at, correlation_id=item.correlation_id,
                source_rank=2,
            ))
        for item in db.execute(read.order_by(ReportReadAudit.created_at.desc(), ReportReadAudit.id.desc()).limit(limit + 1)).all():
            rows.append(AdminAuditEventRow(
                event_id=str(item.id), event_type="READ", action=f"report.read.{item.purpose}",
                outcome="SUCCEEDED", actor_id=item.actor_id,
                resource_type=item.resource_type, resource_id=item.resource_id,
                occurred_at=item.created_at, correlation_id=None,
                source_rank=1,
            ))

    if "STATUS" in selected:
        statement = select(
            ReportStatusAudit.id, ReportStatusAudit.next_status,
            ReportStatusAudit.actor_id, ReportStatusAudit.report_id,
            ReportStatusAudit.created_at, ReportStatusAudit.correlation_id,
        )
        if actor_id is not None:
            statement = statement.where(ReportStatusAudit.actor_id == actor_id)
        clause = _cursor_clause(ReportStatusAudit.created_at, ReportStatusAudit.id, "STATUS", cursor)
        if clause is not None:
            statement = statement.where(clause)
        for item in db.execute(statement.order_by(ReportStatusAudit.created_at.desc(), ReportStatusAudit.id.desc()).limit(limit + 1)).all():
            rows.append(AdminAuditEventRow(str(item.id), "STATUS", f"report.status.{item.next_status}", "SUCCEEDED", item.actor_id, "report", str(item.report_id), item.created_at, item.correlation_id))

    if "REVIEW" in selected:
        statement = select(
            ReportReviewDecision.id, ReportReviewDecision.decision,
            ReportReviewDecision.admin_id, ReportReviewDecision.report_id,
            ReportReviewDecision.decided_at, ReportReviewDecision.correlation_id,
        )
        if actor_id is not None:
            statement = statement.where(ReportReviewDecision.admin_id == actor_id)
        clause = _cursor_clause(ReportReviewDecision.decided_at, ReportReviewDecision.id, "REVIEW", cursor)
        if clause is not None:
            statement = statement.where(clause)
        for item in db.execute(statement.order_by(ReportReviewDecision.decided_at.desc(), ReportReviewDecision.id.desc()).limit(limit + 1)).all():
            rows.append(AdminAuditEventRow(str(item.id), "REVIEW", f"report.review.{item.decision}", "SUCCEEDED", item.admin_id, "report", str(item.report_id), item.decided_at, item.correlation_id))

    if "EXPORT" in selected:
        statement = select(
            ReportExportAudit.audit_id, ReportExportAudit.export_format,
            ReportExportAudit.actor_id, ReportExportAudit.created_at,
        )
        if actor_id is not None:
            statement = statement.where(ReportExportAudit.actor_id == actor_id)
        clause = _cursor_clause(ReportExportAudit.created_at, ReportExportAudit.audit_id, "EXPORT", cursor)
        if clause is not None:
            statement = statement.where(clause)
        for item in db.execute(statement.order_by(ReportExportAudit.created_at.desc(), ReportExportAudit.audit_id.desc()).limit(limit + 1)).all():
            rows.append(AdminAuditEventRow(str(item.audit_id), "EXPORT", f"report.export.{item.export_format}", "SUCCEEDED", item.actor_id, "report_export", str(item.audit_id), item.created_at, None))

    if "DELIVERY" in selected:
        statement = select(
            ReportInstitutionDeliveryEvent.id, ReportInstitutionDeliveryEvent.status,
            ReportInstitutionDeliveryEvent.admin_id, ReportInstitutionDeliveryEvent.report_id,
            ReportInstitutionDeliveryEvent.recorded_at,
            ReportInstitutionDeliveryEvent.correlation_id,
        )
        if actor_id is not None:
            statement = statement.where(ReportInstitutionDeliveryEvent.admin_id == actor_id)
        clause = _cursor_clause(ReportInstitutionDeliveryEvent.recorded_at, ReportInstitutionDeliveryEvent.id, "DELIVERY", cursor)
        if clause is not None:
            statement = statement.where(clause)
        for item in db.execute(statement.order_by(ReportInstitutionDeliveryEvent.recorded_at.desc(), ReportInstitutionDeliveryEvent.id.desc()).limit(limit + 1)).all():
            rows.append(AdminAuditEventRow(str(item.id), "DELIVERY", f"report.delivery.{item.status}", "SUCCEEDED", item.admin_id, "report", str(item.report_id), item.recorded_at, item.correlation_id))

    rows = [row for row in rows if _after_cursor(row, cursor)]
    rows.sort(
        key=lambda row: (
            row.occurred_at,
            _EVENT_RANK[row.event_type],
            row.source_rank,
            row.event_id,
        ),
        reverse=True,
    )
    return rows[: limit + 1]


__all__ = [
    "AdminAuditCursor",
    "AdminAuditCursorError",
    "AdminAuditEventRow",
    "admin_audit_filter_sha256",
    "decode_admin_audit_cursor",
    "encode_admin_audit_cursor",
    "project_admin_audit_event",
    "query_admin_audit_events",
]
