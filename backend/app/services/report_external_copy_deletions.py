"""Record-only administrator workflow for institution-held report copies."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import uuid

from sqlalchemy import and_, func, or_, select, text
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminOperationAudit,
    ReportDeletionExternalCopyEvent,
    ReportDeletionExternalCopyState,
    ReportDeletionTombstone,
)
from backend.app.schemas import AdminReportDeletionExternalCopyEventCreateV1
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_user_requests import (
    ReportUserCursorError,
    decode_cursor,
    encode_cursor,
    filter_sha256,
)


ADMIN_EXTERNAL_COPY_LIST_OPERATION = "admin.report_deletion.external_copy.list"
ADMIN_EXTERNAL_COPY_RECORD_OPERATION = "report.external_copy_deletion.record"

_TRANSITIONS: dict[str, tuple[str, ...]] = {
    "NOT_REQUESTED": ("REQUEST_SENT",),
    "REQUEST_SENT": (
        "REPLY_ACKNOWLEDGED",
        "REPLY_DELETION_CONFIRMED",
        "REPLY_DECLINED",
    ),
    "REPLY_ACKNOWLEDGED": (
        "REPLY_DELETION_CONFIRMED",
        "REPLY_DECLINED",
    ),
    "REPLY_DELETION_CONFIRMED": (),
    "REPLY_DECLINED": ("REQUEST_SENT",),
}


class ReportExternalCopyDeletionError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int,
        latest: dict[str, object] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.latest = latest


@dataclass(frozen=True, slots=True)
class AdminExternalCopyDeletionStatus:
    request_id: uuid.UUID
    copy_id: uuid.UUID
    institution: str
    delivery_status_at_local_deletion: str
    state: str
    revision: int
    allowed_next_states: tuple[str, ...]
    status_observed_at: datetime | None
    status_recorded_at: datetime | None


@dataclass(frozen=True, slots=True)
class AdminExternalCopyDeletionPage:
    items: tuple[AdminExternalCopyDeletionStatus, ...]
    next_cursor: str | None


def allowed_external_copy_deletion_states(state: str) -> tuple[str, ...]:
    try:
        return _TRANSITIONS[state]
    except KeyError as exc:
        raise ValueError("unknown external-copy deletion state") from exc


def admin_external_copy_filter_digest(request_id: uuid.UUID | None) -> str:
    return filter_sha256(
        {"request_id": str(request_id) if request_id is not None else None}
    )


def _ranked_events():
    return select(
        ReportDeletionExternalCopyEvent.external_copy_state_id,
        ReportDeletionExternalCopyEvent.state,
        ReportDeletionExternalCopyEvent.revision,
        ReportDeletionExternalCopyEvent.observed_at,
        ReportDeletionExternalCopyEvent.recorded_at,
        func.row_number()
        .over(
            partition_by=ReportDeletionExternalCopyEvent.external_copy_state_id,
            order_by=(
                ReportDeletionExternalCopyEvent.revision.desc(),
                ReportDeletionExternalCopyEvent.id.desc(),
            ),
        )
        .label("ordinal"),
    ).subquery()


def list_admin_external_copy_deletions(
    db: Session,
    *,
    limit: int,
    cursor: str | None,
    request_id: uuid.UUID | None,
) -> AdminExternalCopyDeletionPage:
    if not 1 <= limit <= 100:
        raise ValueError("external-copy list limit must be between 1 and 100")
    digest = admin_external_copy_filter_digest(request_id)
    decoded = (
        decode_cursor(cursor, expected_filter_digest=digest)
        if cursor is not None
        else None
    )
    ranked = _ranked_events()
    snapshot_count = (
        select(func.count(ReportDeletionExternalCopyState.id))
        .where(
            ReportDeletionExternalCopyState.deletion_tombstone_id
            == ReportDeletionTombstone.id
        )
        .correlate(ReportDeletionTombstone)
        .scalar_subquery()
    )
    mismatch_statement = select(ReportDeletionTombstone.id).where(
        ReportDeletionTombstone.external_copy_count != snapshot_count
    )
    if request_id is not None:
        mismatch_statement = mismatch_statement.where(
            ReportDeletionTombstone.request_id == request_id
        )
    if db.scalar(mismatch_statement.limit(1)) is not None:
        raise ReportExternalCopyDeletionError(
            "report_external_copy_evidence_inconsistent",
            "The external-copy deletion evidence is incomplete.",
            status_code=503,
        )
    statement = (
        select(
            ReportDeletionTombstone.request_id,
            ReportDeletionTombstone.deleted_at,
            ReportDeletionTombstone.external_copy_count,
            snapshot_count.label("snapshot_count"),
            ReportDeletionExternalCopyState.id.label("copy_id"),
            ReportDeletionExternalCopyState.institution,
            ReportDeletionExternalCopyState.status.label(
                "delivery_status_at_local_deletion"
            ),
            ranked.c.state,
            ranked.c.revision,
            ranked.c.observed_at.label("status_observed_at"),
            ranked.c.recorded_at.label("status_recorded_at"),
        )
        .join(
            ReportDeletionExternalCopyState,
            ReportDeletionExternalCopyState.deletion_tombstone_id
            == ReportDeletionTombstone.id,
        )
        .outerjoin(
            ranked,
            and_(
                ranked.c.external_copy_state_id
                == ReportDeletionExternalCopyState.id,
                ranked.c.ordinal == 1,
            ),
        )
    )
    if request_id is not None:
        statement = statement.where(
            ReportDeletionTombstone.request_id == request_id
        )
    if decoded is not None:
        statement = statement.where(
            or_(
                ReportDeletionTombstone.deleted_at < decoded.created_at,
                and_(
                    ReportDeletionTombstone.deleted_at == decoded.created_at,
                    ReportDeletionExternalCopyState.id < decoded.row_id,
                ),
            )
        )
    rows = db.execute(
        statement.order_by(
            ReportDeletionTombstone.deleted_at.desc(),
            ReportDeletionExternalCopyState.id.desc(),
        ).limit(limit + 1)
    ).all()
    page_rows = rows[:limit]
    for row in page_rows:
        if int(row.external_copy_count) != int(row.snapshot_count):
            raise ReportExternalCopyDeletionError(
                "report_external_copy_evidence_inconsistent",
                "The external-copy deletion evidence is incomplete.",
                status_code=503,
            )
    items = tuple(_project_row(row) for row in page_rows)
    next_cursor = (
        encode_cursor(
            created_at=page_rows[-1].deleted_at,
            row_id=page_rows[-1].copy_id,
            filter_digest=digest,
        )
        if len(rows) > limit
        else None
    )
    return AdminExternalCopyDeletionPage(items=items, next_cursor=next_cursor)


def _project_row(row: object) -> AdminExternalCopyDeletionStatus:
    state = getattr(row, "state") or "NOT_REQUESTED"
    revision = int(getattr(row, "revision") or 0)
    return AdminExternalCopyDeletionStatus(
        request_id=getattr(row, "request_id"),
        copy_id=getattr(row, "copy_id"),
        institution=getattr(row, "institution"),
        delivery_status_at_local_deletion=getattr(
            row, "delivery_status_at_local_deletion"
        ),
        state=state,
        revision=revision,
        allowed_next_states=allowed_external_copy_deletion_states(state),
        status_observed_at=getattr(row, "status_observed_at"),
        status_recorded_at=getattr(row, "status_recorded_at"),
    )


def _project_event(
    *,
    tombstone: ReportDeletionTombstone,
    copy: ReportDeletionExternalCopyState,
    event: ReportDeletionExternalCopyEvent,
) -> AdminExternalCopyDeletionStatus:
    return AdminExternalCopyDeletionStatus(
        request_id=tombstone.request_id,
        copy_id=copy.id,
        institution=copy.institution,
        delivery_status_at_local_deletion=copy.status,
        state=event.state,
        revision=int(event.revision),
        allowed_next_states=allowed_external_copy_deletion_states(event.state),
        status_observed_at=event.observed_at,
        status_recorded_at=event.recorded_at,
    )


def _latest_dict(
    *,
    tombstone: ReportDeletionTombstone,
    copy: ReportDeletionExternalCopyState,
    event: ReportDeletionExternalCopyEvent | None,
) -> dict[str, object]:
    state = "NOT_REQUESTED" if event is None else event.state

    def utc_text(value: datetime | None) -> str | None:
        if value is None:
            return None
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")

    return {
        "request_id": str(tombstone.request_id),
        "copy_id": str(copy.id),
        "institution": copy.institution,
        "delivery_status_at_local_deletion": copy.status,
        "state": state,
        "revision": 0 if event is None else int(event.revision),
        "allowed_next_states": list(allowed_external_copy_deletion_states(state)),
        "status_observed_at": (
            None if event is None else utc_text(event.observed_at)
        ),
        "status_recorded_at": (
            None if event is None else utc_text(event.recorded_at)
        ),
    }


def _intent_matches(
    event: ReportDeletionExternalCopyEvent,
    payload: AdminReportDeletionExternalCopyEventCreateV1,
) -> bool:
    return (
        event.state == payload.state
        and int(event.expected_revision) == payload.expected_revision
        and event.observed_at.astimezone(UTC) == payload.observed_at.astimezone(UTC)
        and event.institution_reference == payload.institution_reference
        and event.evidence_sha256 == payload.evidence_sha256
    )


def _success_audit(
    *,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    query_sha256: str,
    copy_id: uuid.UUID,
    now: datetime,
) -> AdminOperationAudit:
    return AdminOperationAudit(
        actor_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
        operation=ADMIN_EXTERNAL_COPY_RECORD_OPERATION,
        outcome="SUCCEEDED",
        resource_type="report_deletion_external_copy",
        resource_id=str(copy_id),
        query_sha256=query_sha256,
        result_count=1,
        error_code=None,
        created_at=now,
    )


def _copy_and_tombstone(
    db: Session,
    *,
    request_id: uuid.UUID,
    copy_id: uuid.UUID,
) -> tuple[ReportDeletionExternalCopyState, ReportDeletionTombstone] | None:
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {
            "lock_key": f"walksafe-report-external-copy-deletion-v1\n{copy_id}"
        },
    )
    return db.execute(
        select(ReportDeletionExternalCopyState, ReportDeletionTombstone)
        .join(
            ReportDeletionTombstone,
            ReportDeletionTombstone.id
            == ReportDeletionExternalCopyState.deletion_tombstone_id,
        )
        .where(
            ReportDeletionExternalCopyState.id == copy_id,
            ReportDeletionTombstone.request_id == request_id,
        )
    ).one_or_none()


def _latest_event(
    db: Session,
    copy_id: uuid.UUID,
) -> ReportDeletionExternalCopyEvent | None:
    return db.execute(
        select(ReportDeletionExternalCopyEvent)
        .where(ReportDeletionExternalCopyEvent.external_copy_state_id == copy_id)
        .order_by(
            ReportDeletionExternalCopyEvent.revision.desc(),
            ReportDeletionExternalCopyEvent.id.desc(),
        )
        .limit(1)
    ).scalar_one_or_none()


def record_admin_external_copy_deletion_event(
    db: Session,
    *,
    request_id: uuid.UUID,
    copy_id: uuid.UUID,
    payload: AdminReportDeletionExternalCopyEventCreateV1,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    query_sha256: str,
    now: datetime | None = None,
) -> tuple[AdminExternalCopyDeletionStatus, bool]:
    recorded_at = (now or datetime.now(UTC)).astimezone(UTC)
    try:
        bound = _copy_and_tombstone(
            db,
            request_id=request_id,
            copy_id=copy_id,
        )
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportExternalCopyDeletionError(
            "report_external_copy_store_unavailable",
            "The external-copy deletion store is temporarily unavailable.",
            status_code=503,
        ) from exc
    if bound is None:
        db.rollback()
        raise ReportExternalCopyDeletionError(
            "report_external_copy_not_found",
            "External copy was not found.",
            status_code=404,
        )
    copy, tombstone = bound
    snapshot_count = db.scalar(
        select(func.count(ReportDeletionExternalCopyState.id)).where(
            ReportDeletionExternalCopyState.deletion_tombstone_id == tombstone.id
        )
    )
    if int(snapshot_count or 0) != int(tombstone.external_copy_count):
        db.rollback()
        raise ReportExternalCopyDeletionError(
            "report_external_copy_evidence_inconsistent",
            "The external-copy deletion evidence is incomplete.",
            status_code=503,
        )

    existing = db.execute(
        select(ReportDeletionExternalCopyEvent).where(
            ReportDeletionExternalCopyEvent.external_copy_state_id == copy_id,
            ReportDeletionExternalCopyEvent.idempotency_key
            == payload.idempotency_key,
        )
    ).scalar_one_or_none()
    if existing is not None:
        if not _intent_matches(existing, payload):
            latest = _latest_event(db, copy_id)
            latest_payload = _latest_dict(
                tombstone=tombstone,
                copy=copy,
                event=latest,
            )
            db.rollback()
            raise ReportExternalCopyDeletionError(
                "report_external_copy_idempotency_conflict",
                "The idempotency key is bound to another external-copy event.",
                status_code=409,
                latest=latest_payload,
            )
        result = _project_event(tombstone=tombstone, copy=copy, event=existing)
        db.add(
            _success_audit(
                identity=identity,
                correlation_id=correlation_id,
                query_sha256=query_sha256,
                copy_id=copy_id,
                now=recorded_at,
            )
        )
        try:
            db.commit()
        except SQLAlchemyError as exc:
            db.rollback()
            raise ReportExternalCopyDeletionError(
                "report_external_copy_store_unavailable",
                "The external-copy deletion store is temporarily unavailable.",
                status_code=503,
            ) from exc
        return result, False

    latest = _latest_event(db, copy_id)
    current_revision = 0 if latest is None else int(latest.revision)
    current_state = "NOT_REQUESTED" if latest is None else latest.state
    latest_payload = _latest_dict(tombstone=tombstone, copy=copy, event=latest)
    if payload.expected_revision != current_revision:
        db.rollback()
        raise ReportExternalCopyDeletionError(
            "report_external_copy_revision_conflict",
            "The external-copy deletion state changed before this event.",
            status_code=409,
            latest=latest_payload,
        )
    if payload.state not in allowed_external_copy_deletion_states(current_state):
        db.rollback()
        raise ReportExternalCopyDeletionError(
            "report_external_copy_transition_invalid",
            "The external-copy deletion state transition is not allowed.",
            status_code=422,
            latest=latest_payload,
        )
    if payload.observed_at > recorded_at or (
        latest is not None and payload.observed_at < latest.observed_at
    ):
        db.rollback()
        raise ReportExternalCopyDeletionError(
            "report_external_copy_observed_at_invalid",
            "The external-copy observation time is invalid.",
            status_code=422,
            latest=latest_payload,
        )

    event = ReportDeletionExternalCopyEvent(
        id=uuid.uuid4(),
        external_copy_state_id=copy_id,
        revision=current_revision + 1,
        expected_revision=payload.expected_revision,
        idempotency_key=payload.idempotency_key,
        state=payload.state,
        institution_reference=payload.institution_reference,
        evidence_sha256=payload.evidence_sha256,
        observed_at=payload.observed_at,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
    )
    db.add(event)
    db.add(
        _success_audit(
            identity=identity,
            correlation_id=correlation_id,
            query_sha256=query_sha256,
            copy_id=copy_id,
            now=recorded_at,
        )
    )
    try:
        db.flush()
        result = _project_event(tombstone=tombstone, copy=copy, event=event)
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        concurrent = db.execute(
            select(ReportDeletionExternalCopyEvent).where(
                ReportDeletionExternalCopyEvent.external_copy_state_id == copy_id,
                ReportDeletionExternalCopyEvent.idempotency_key
                == payload.idempotency_key,
            )
        ).scalar_one_or_none()
        if concurrent is not None and _intent_matches(concurrent, payload):
            bound_after = _copy_and_tombstone(
                db,
                request_id=request_id,
                copy_id=copy_id,
            )
            if bound_after is None:
                db.rollback()
                raise ReportExternalCopyDeletionError(
                    "report_external_copy_not_found",
                    "External copy was not found.",
                    status_code=404,
                ) from exc
            copy_after, tombstone_after = bound_after
            replay = _project_event(
                tombstone=tombstone_after,
                copy=copy_after,
                event=concurrent,
            )
            db.add(
                _success_audit(
                    identity=identity,
                    correlation_id=correlation_id,
                    query_sha256=query_sha256,
                    copy_id=copy_id,
                    now=recorded_at,
                )
            )
            try:
                db.commit()
            except SQLAlchemyError as audit_exc:
                db.rollback()
                raise ReportExternalCopyDeletionError(
                    "report_external_copy_store_unavailable",
                    "The external-copy deletion store is temporarily unavailable.",
                    status_code=503,
                ) from audit_exc
            return replay, False
        latest_after = _latest_event(db, copy_id)
        latest_after_payload = _latest_dict(
            tombstone=tombstone,
            copy=copy,
            event=latest_after,
        )
        db.rollback()
        if latest_after is not None and int(latest_after.revision) != current_revision:
            raise ReportExternalCopyDeletionError(
                "report_external_copy_revision_conflict",
                "The external-copy deletion state changed before this event.",
                status_code=409,
                latest=latest_after_payload,
            ) from exc
        raise ReportExternalCopyDeletionError(
            "report_external_copy_store_unavailable",
            "The external-copy deletion store is temporarily unavailable.",
            status_code=503,
        ) from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportExternalCopyDeletionError(
            "report_external_copy_store_unavailable",
            "The external-copy deletion store is temporarily unavailable.",
            status_code=503,
        ) from exc
    return result, True


__all__ = [
    "ADMIN_EXTERNAL_COPY_LIST_OPERATION",
    "ADMIN_EXTERNAL_COPY_RECORD_OPERATION",
    "AdminExternalCopyDeletionPage",
    "AdminExternalCopyDeletionStatus",
    "ReportExternalCopyDeletionError",
    "ReportUserCursorError",
    "admin_external_copy_filter_digest",
    "allowed_external_copy_deletion_states",
    "list_admin_external_copy_deletions",
    "record_admin_external_copy_deletion_event",
]
