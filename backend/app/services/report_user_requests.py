"""Minimum user report lifecycle and correction/deletion request workflow."""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import re
import uuid

from sqlalchemy import and_, case, exists, func, or_, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AccountDeletionTombstone,
    AdminOperationAudit,
    Report,
    ReportDeliveryPackage,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
    ReportUserRequest,
    ReportUserRequestStatusEvent,
)
from backend.app.schemas import (
    AdminReportUserRequestDetailV1,
    AdminReportUserRequestStatusUpdateV1,
    AdminReportUserRequestSummaryV1,
    ReportUserRequestCreateV1,
    ReportUserRequestSummaryV1,
    UserReportDetailV1,
    UserReportSummaryV1,
)
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.privacy_lifecycle import lock_privacy_subject_shared


_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,1024}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_REQUEST_TRANSITIONS: dict[str, tuple[str, ...]] = {
    "RECEIVED": ("ACKNOWLEDGED",),
    "ACKNOWLEDGED": ("RESOLVED", "REJECTED"),
    "RESOLVED": (),
    "REJECTED": (),
}


class ReportUserRequestError(RuntimeError):
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


class ReportUserCursorError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class ReportUserCursor:
    created_at: datetime
    row_id: uuid.UUID


@dataclass(frozen=True, slots=True)
class UserReportRow:
    report_id: uuid.UUID
    created_at: datetime
    user_status: str
    public_rejection_reason: str | None


@dataclass(frozen=True, slots=True)
class AdminRequestRow:
    request_id: uuid.UUID
    report_id: uuid.UUID
    request_type: str
    status: str
    status_version: int
    request_text: str
    public_response: str | None
    internal_note: str | None
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class AdminRequestSummaryRow:
    request_id: uuid.UUID
    report_id: uuid.UUID
    request_type: str
    status: str
    status_version: int
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class ReportUserRequestState:
    id: uuid.UUID
    report_id: uuid.UUID
    request_type: str
    status: str
    status_version: int
    public_response: str | None
    updated_at: datetime
    created_at: datetime


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("cursor timestamp must be timezone-aware")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ReportUserCursorError("report cursor is invalid")
        result[key] = value
    return result


def filter_sha256(payload: dict[str, object]) -> str:
    return hashlib.sha256(
        json.dumps(
            payload,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("ascii")
    ).hexdigest()


def encode_cursor(
    *, created_at: datetime, row_id: uuid.UUID, filter_digest: str
) -> str:
    if _SHA256_PATTERN.fullmatch(filter_digest) is None:
        raise ValueError("cursor filter digest is invalid")
    payload = {
        "created_at": _utc_text(created_at),
        "filter_sha256": filter_digest,
        "id": str(row_id),
        "v": 1,
    }
    raw = json.dumps(
        payload, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def decode_cursor(value: str, *, expected_filter_digest: str) -> ReportUserCursor:
    if (
        _CURSOR_PATTERN.fullmatch(value or "") is None
        or _SHA256_PATTERN.fullmatch(expected_filter_digest) is None
    ):
        raise ReportUserCursorError("report cursor is invalid")
    try:
        raw = base64.b64decode(
            (value + "=" * (-len(value) % 4)).encode("ascii"),
            altchars=b"-_",
            validate=True,
        )
        payload = json.loads(raw.decode("ascii"), object_pairs_hook=_strict_object)
        if not isinstance(payload, dict) or set(payload) != {
            "created_at", "filter_sha256", "id", "v"
        }:
            raise ValueError
        created_at = datetime.fromisoformat(str(payload["created_at"]).replace("Z", "+00:00"))
        row_id = uuid.UUID(str(payload["id"]))
    except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
        raise ReportUserCursorError("report cursor is invalid") from exc
    if (
        payload["v"] != 1
        or payload["filter_sha256"] != expected_filter_digest
        or payload["created_at"] != _utc_text(created_at)
        or payload["id"] != str(row_id)
        or value != encode_cursor(
            created_at=created_at,
            row_id=row_id,
            filter_digest=expected_filter_digest,
        )
    ):
        raise ReportUserCursorError("report cursor does not match the filters")
    return ReportUserCursor(created_at=created_at, row_id=row_id)


def _user_status_expression():
    any_resolved = exists(
        select(1)
        .select_from(ReportInstitutionDeliveryEvent)
        .join(
            ReportDeliveryPackage,
            ReportDeliveryPackage.id == ReportInstitutionDeliveryEvent.package_id,
        )
        .where(
            ReportInstitutionDeliveryEvent.report_id == Report.id,
            ReportInstitutionDeliveryEvent.status == "RESOLVED",
            ReportDeliveryPackage.content_revision == Report.content_revision,
        )
    )
    any_submitted = exists(
        select(1)
        .select_from(ReportInstitutionDeliveryEvent)
        .join(
            ReportDeliveryPackage,
            ReportDeliveryPackage.id == ReportInstitutionDeliveryEvent.package_id,
        )
        .where(
            ReportInstitutionDeliveryEvent.report_id == Report.id,
            ReportInstitutionDeliveryEvent.status.in_(
                ("SUBMITTED", "ACKNOWLEDGED")
            ),
            ReportDeliveryPackage.content_revision == Report.content_revision,
        )
    )
    latest_decision = (
        select(ReportReviewDecision.decision)
        .where(
            ReportReviewDecision.report_id == Report.id,
            ReportReviewDecision.content_revision == Report.content_revision,
        )
        .order_by(ReportReviewDecision.revision.desc())
        .limit(1)
        .scalar_subquery()
    )
    return case(
        (any_resolved, "RESOLVED"),
        (any_submitted, "INSTITUTION_SUBMITTED"),
        (latest_decision.in_(("REJECTED", "DUPLICATE")), "REJECTED"),
        else_="RECEIVED",
    )


def user_report_columns() -> tuple[object, ...]:
    user_status = _user_status_expression()
    latest_reason = (
        select(ReportReviewDecision.user_visible_reason)
        .where(
            ReportReviewDecision.report_id == Report.id,
            ReportReviewDecision.content_revision == Report.content_revision,
        )
        .order_by(ReportReviewDecision.revision.desc())
        .limit(1)
        .scalar_subquery()
    )
    return (
        Report.id.label("report_id"),
        Report.created_at,
        user_status.label("user_status"),
        case(
            (user_status == "REJECTED", latest_reason),
            else_=None,
        ).label("public_rejection_reason"),
    )


def user_report_filter_digest(
    *, privacy_subject: str, account_generation: int, user_status: str | None
) -> str:
    return filter_sha256(
        {
            "account_generation": account_generation,
            "privacy_subject_hmac": privacy_subject,
            "user_status": user_status,
        }
    )


def admin_request_filter_digest(
    *, report_id: uuid.UUID | None, request_type: str | None, status: str | None
) -> str:
    return filter_sha256(
        {
            "report_id": str(report_id) if report_id is not None else None,
            "request_type": request_type,
            "status": status,
        }
    )


def derive_user_report_status(
    delivery_statuses: list[str] | tuple[str, ...],
    latest_review_decision: str | None,
) -> str:
    observed = set(delivery_statuses)
    if "RESOLVED" in observed:
        return "RESOLVED"
    if observed.intersection({"SUBMITTED", "ACKNOWLEDGED"}):
        return "INSTITUTION_SUBMITTED"
    if latest_review_decision in {"REJECTED", "DUPLICATE"}:
        return "REJECTED"
    return "RECEIVED"


def _not_found() -> ReportUserRequestError:
    return ReportUserRequestError(
        "report_not_found",
        "Report was not found.",
        status_code=404,
    )


def assert_subject_active(
    db: Session, *, privacy_subject: str, account_generation: int
) -> None:
    tombstone = db.scalar(
        select(AccountDeletionTombstone.tombstone_id).where(
            AccountDeletionTombstone.privacy_subject_hmac == privacy_subject,
            AccountDeletionTombstone.account_generation == account_generation,
        ).limit(1)
    )
    if tombstone is not None:
        raise _not_found()


def user_report_statement(
    *,
    privacy_subject: str,
    account_generation: int,
    user_status: str | None,
    cursor: ReportUserCursor | None,
    limit: int,
):
    status_expression = _user_status_expression()
    statement = select(*user_report_columns()).where(
        Report.privacy_subject_hmac == privacy_subject,
        Report.account_generation == account_generation,
    )
    if user_status is not None:
        statement = statement.where(status_expression == user_status)
    if cursor is not None:
        statement = statement.where(
            or_(
                Report.created_at < cursor.created_at,
                and_(
                    Report.created_at == cursor.created_at,
                    Report.id < cursor.row_id,
                ),
            )
        )
    return statement.order_by(Report.created_at.desc(), Report.id.desc()).limit(limit + 1)


def coerce_user_report(row: object) -> UserReportRow:
    return UserReportRow(
        report_id=row.report_id,
        created_at=row.created_at,
        user_status=row.user_status,
        public_rejection_reason=row.public_rejection_reason,
    )


def latest_request_summaries(
    db: Session, report_ids: list[uuid.UUID]
) -> dict[uuid.UUID, ReportUserRequestSummaryV1]:
    if not report_ids:
        return {}
    ranked = select(
        ReportUserRequest.id.label("request_id"),
        ReportUserRequest.report_id,
        ReportUserRequest.request_type,
        ReportUserRequest.status,
        ReportUserRequest.status_version,
        ReportUserRequest.public_response,
        ReportUserRequest.created_at,
        ReportUserRequest.updated_at,
        func.row_number().over(
            partition_by=ReportUserRequest.report_id,
            order_by=(
                ReportUserRequest.created_at.desc(),
                ReportUserRequest.id.desc(),
            ),
        ).label("ordinal"),
    ).where(ReportUserRequest.report_id.in_(report_ids)).subquery()
    rows = db.execute(
        select(
            ranked.c.request_id,
            ranked.c.report_id,
            ranked.c.request_type,
            ranked.c.status,
            ranked.c.status_version,
            ranked.c.public_response,
            ranked.c.created_at,
            ranked.c.updated_at,
        )
        .where(ranked.c.ordinal == 1)
        .order_by(ranked.c.report_id)
    ).all()
    result: dict[uuid.UUID, ReportUserRequestSummaryV1] = {}
    for row in rows:
        result.setdefault(
            row.report_id,
            ReportUserRequestSummaryV1(
                request_id=row.request_id,
                request_type=row.request_type,
                status=row.status,
                status_version=row.status_version,
                public_response=row.public_response,
                created_at=row.created_at,
                updated_at=row.updated_at,
            ),
        )
    return result


def project_user_report(
    row: UserReportRow,
    latest_request: ReportUserRequestSummaryV1 | None,
) -> UserReportSummaryV1:
    return UserReportSummaryV1(
        report_id=row.report_id,
        created_at=row.created_at,
        user_status=row.user_status,
        public_rejection_reason=row.public_rejection_reason,
        latest_request=latest_request,
    )


def get_owned_user_report(
    db: Session,
    *,
    report_id: uuid.UUID,
    privacy_subject: str,
    account_generation: int,
) -> UserReportDetailV1:
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    assert_subject_active(
        db,
        privacy_subject=privacy_subject,
        account_generation=account_generation,
    )
    row = db.execute(
        select(*user_report_columns()).where(
            Report.id == report_id,
            Report.privacy_subject_hmac == privacy_subject,
            Report.account_generation == account_generation,
        )
    ).one_or_none()
    if row is None:
        raise _not_found()
    coerced = coerce_user_report(row)
    latest = latest_request_summaries(db, [report_id]).get(report_id)
    return UserReportDetailV1(
        schema_version="walksafe.user-report-detail.v1",
        **project_user_report(coerced, latest).model_dump(),
    )


def get_owned_report_user_request(
    db: Session,
    *,
    report_id: uuid.UUID,
    request_id: uuid.UUID,
    privacy_subject: str,
    account_generation: int,
) -> ReportUserRequestState:
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    assert_subject_active(
        db,
        privacy_subject=privacy_subject,
        account_generation=account_generation,
    )
    row = db.execute(
        select(
            ReportUserRequest.id.label("request_id"),
            ReportUserRequest.report_id,
            ReportUserRequest.request_type,
            ReportUserRequest.status,
            ReportUserRequest.status_version,
            ReportUserRequest.public_response,
            ReportUserRequest.created_at,
            ReportUserRequest.updated_at,
        )
        .join(Report, Report.id == ReportUserRequest.report_id)
        .where(
            ReportUserRequest.id == request_id,
            ReportUserRequest.report_id == report_id,
            Report.id == report_id,
            Report.privacy_subject_hmac == privacy_subject,
            Report.account_generation == account_generation,
        )
    ).one_or_none()
    if row is None:
        raise _not_found()
    return ReportUserRequestState(
        id=row.request_id,
        report_id=row.report_id,
        request_type=row.request_type,
        status=row.status,
        status_version=row.status_version,
        public_response=row.public_response,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


def request_intent_sha256(
    report_id: uuid.UUID, payload: ReportUserRequestCreateV1
) -> str:
    canonical = json.dumps(
        {
            "client_request_id": str(payload.client_request_id),
            "report_id": str(report_id),
            "request_text": payload.request_text,
            "request_type": payload.request_type,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(b"walksafe.report-user-request.v1\0" + canonical).hexdigest()


def create_report_user_request(
    db: Session,
    *,
    report_id: uuid.UUID,
    payload: ReportUserRequestCreateV1,
    privacy_subject: str,
    account_generation: int,
    now: datetime | None = None,
) -> tuple[ReportUserRequestState, bool]:
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    assert_subject_active(
        db,
        privacy_subject=privacy_subject,
        account_generation=account_generation,
    )
    report = db.execute(
        select(Report).where(
            Report.id == report_id,
            Report.privacy_subject_hmac == privacy_subject,
            Report.account_generation == account_generation,
        ).with_for_update()
    ).scalar_one_or_none()
    if report is None:
        db.rollback()
        raise _not_found()
    digest = request_intent_sha256(report_id, payload)
    existing = db.execute(
        select(ReportUserRequest).where(
            ReportUserRequest.report_id == report_id,
            ReportUserRequest.client_request_id == payload.client_request_id,
        )
    ).scalar_one_or_none()
    if existing is not None:
        if existing.intent_sha256 != digest:
            db.rollback()
            raise ReportUserRequestError(
                "report_request_intent_conflict",
                "The client request identifier is bound to another intent.",
                status_code=409,
            )
        state = _request_state(existing)
        db.rollback()
        return state, False
    observed_at = (now or datetime.now(UTC)).astimezone(UTC)
    item = ReportUserRequest(
        report_id=report_id,
        client_request_id=payload.client_request_id,
        request_type=payload.request_type,
        request_text=payload.request_text,
        intent_sha256=digest,
        status="RECEIVED",
        status_version=1,
        created_at=observed_at,
        updated_at=observed_at,
    )
    item.id = uuid.uuid4()
    db.add(item)
    db.add(
        ReportUserRequestStatusEvent(
            request_id=item.id,
            previous_status=None,
            next_status="RECEIVED",
            previous_version=0,
            next_version=1,
            actor_kind="FIELD",
            actor_id=None,
            privacy_subject_hmac=privacy_subject,
            account_generation=account_generation,
            public_response=None,
            internal_note=None,
            created_at=observed_at,
        )
    )
    state = _request_state(item)
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        try:
            concurrent = db.execute(
                select(ReportUserRequest).where(
                    ReportUserRequest.report_id == report_id,
                    ReportUserRequest.client_request_id == payload.client_request_id,
                )
            ).scalar_one_or_none()
        except SQLAlchemyError as lookup_exc:
            db.rollback()
            raise ReportUserRequestError(
                "report_request_store_unavailable",
                "The report request store is temporarily unavailable.",
                status_code=503,
            ) from lookup_exc
        if concurrent is None:
            db.rollback()
            raise ReportUserRequestError(
                "report_request_store_unavailable",
                "The report request store is temporarily unavailable.",
                status_code=503,
            ) from exc
        concurrent_state = _request_state(concurrent)
        concurrent_digest = concurrent.intent_sha256
        db.rollback()
        if concurrent_digest == digest:
            return concurrent_state, False
        raise ReportUserRequestError(
            "report_request_intent_conflict",
            "The client request identifier is bound to another intent.",
            status_code=409,
        ) from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportUserRequestError(
            "report_request_store_unavailable",
            "The report request store is temporarily unavailable.",
            status_code=503,
        ) from exc
    return state, True


def _request_state(item: ReportUserRequest) -> ReportUserRequestState:
    return ReportUserRequestState(
        id=item.id,
        report_id=item.report_id,
        request_type=item.request_type,
        status=item.status,
        status_version=item.status_version,
        public_response=item.public_response,
        created_at=item.created_at,
        updated_at=item.updated_at,
    )


def admin_request_columns() -> tuple[object, ...]:
    return (
        ReportUserRequest.id.label("request_id"),
        ReportUserRequest.report_id,
        ReportUserRequest.request_type,
        ReportUserRequest.status,
        ReportUserRequest.status_version,
        ReportUserRequest.request_text,
        ReportUserRequest.public_response,
        ReportUserRequest.internal_note,
        ReportUserRequest.created_at,
        ReportUserRequest.updated_at,
    )


def admin_request_summary_columns() -> tuple[object, ...]:
    return (
        ReportUserRequest.id.label("request_id"),
        ReportUserRequest.report_id,
        ReportUserRequest.request_type,
        ReportUserRequest.status,
        ReportUserRequest.status_version,
        ReportUserRequest.created_at,
        ReportUserRequest.updated_at,
    )


def coerce_admin_request(row: object) -> AdminRequestRow:
    return AdminRequestRow(
        **{field: getattr(row, field) for field in AdminRequestRow.__dataclass_fields__}
    )


def coerce_admin_request_summary(row: object) -> AdminRequestSummaryRow:
    return AdminRequestSummaryRow(
        **{
            field: getattr(row, field)
            for field in AdminRequestSummaryRow.__dataclass_fields__
        }
    )


def project_admin_request_summary(
    row: AdminRequestRow | AdminRequestSummaryRow,
) -> AdminReportUserRequestSummaryV1:
    return AdminReportUserRequestSummaryV1(
        request_id=row.request_id,
        report_id=row.report_id,
        request_type=row.request_type,
        status=row.status,
        status_version=row.status_version,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


def project_admin_request_detail(row: AdminRequestRow) -> AdminReportUserRequestDetailV1:
    return AdminReportUserRequestDetailV1(
        schema_version="walksafe.admin-report-request-detail.v1",
        **project_admin_request_summary(row).model_dump(),
        request_text=row.request_text,
        public_response=row.public_response,
        internal_note=row.internal_note,
    )


def allowed_request_statuses(
    status: str, *, request_type: str | None = None
) -> tuple[str, ...]:
    try:
        allowed = _REQUEST_TRANSITIONS[status]
    except KeyError as exc:
        raise ValueError("unknown report request status") from exc
    if request_type == "DELETE" and status == "ACKNOWLEDGED":
        return tuple(value for value in allowed if value != "RESOLVED")
    return allowed


def update_admin_request_status(
    db: Session,
    *,
    request_id: uuid.UUID,
    payload: AdminReportUserRequestStatusUpdateV1,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    query_sha256: str,
    now: datetime | None = None,
) -> ReportUserRequestState:
    try:
        item = db.execute(
            select(ReportUserRequest)
            .where(ReportUserRequest.id == request_id)
            .with_for_update()
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportUserRequestError(
            "report_request_store_unavailable",
            "The report request store is temporarily unavailable.",
            status_code=503,
        ) from exc
    if item is None:
        db.rollback()
        raise ReportUserRequestError(
            "report_request_not_found",
            "Report request was not found.",
            status_code=404,
        )
    latest = {
        "request_id": str(item.id),
        "report_id": str(item.report_id),
        "status": item.status,
        "status_version": item.status_version,
        "allowed_next_statuses": list(
            allowed_request_statuses(item.status, request_type=item.request_type)
        ),
        "public_response": item.public_response,
        "updated_at": item.updated_at,
    }
    if payload.expected_version != item.status_version:
        db.rollback()
        raise ReportUserRequestError(
            "report_request_version_conflict",
            "The report request changed before this update.",
            status_code=409,
            latest=latest,
        )
    if payload.status not in allowed_request_statuses(
        item.status, request_type=item.request_type
    ):
        db.rollback()
        raise ReportUserRequestError(
            "report_request_transition_invalid",
            "The report request status transition is not allowed.",
            status_code=422,
            latest=latest,
        )
    changed_at = (now or datetime.now(UTC)).astimezone(UTC)
    previous_status = item.status
    previous_version = item.status_version
    item.status = payload.status
    item.status_version += 1
    item.public_response = payload.public_response
    item.internal_note = payload.internal_note
    item.updated_at = changed_at
    db.add(
        ReportUserRequestStatusEvent(
            request_id=item.id,
            previous_status=previous_status,
            next_status=item.status,
            previous_version=previous_version,
            next_version=item.status_version,
            actor_kind="ADMIN",
            actor_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            correlation_id=correlation_id,
            public_response=payload.public_response,
            internal_note=payload.internal_note,
            created_at=changed_at,
        )
    )
    db.add(
        AdminOperationAudit(
            actor_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            correlation_id=correlation_id,
            operation="admin.report_request.status.update",
            outcome="SUCCEEDED",
            resource_type="report_request",
            resource_id=str(item.id),
            query_sha256=query_sha256,
            result_count=1,
            error_code=None,
            created_at=changed_at,
        )
    )
    state = _request_state(item)
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        latest_after_rollback = _latest_request_status(db, request_id)
        if (
            latest_after_rollback is not None
            and latest_after_rollback["status_version"] != payload.expected_version
        ):
            db.rollback()
            raise ReportUserRequestError(
                "report_request_version_conflict",
                "The report request changed before this update.",
                status_code=409,
                latest=latest_after_rollback,
            ) from exc
        db.rollback()
        raise ReportUserRequestError(
            "report_request_store_unavailable",
            "The report request store is temporarily unavailable.",
            status_code=503,
        ) from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportUserRequestError(
            "report_request_store_unavailable",
            "The report request store is temporarily unavailable.",
            status_code=503,
        ) from exc
    return state


def _latest_request_status(
    db: Session,
    request_id: uuid.UUID,
) -> dict[str, object] | None:
    try:
        row = db.execute(
            select(
                ReportUserRequest.id.label("request_id"),
                ReportUserRequest.report_id,
                ReportUserRequest.request_type,
                ReportUserRequest.status,
                ReportUserRequest.status_version,
                ReportUserRequest.public_response,
                ReportUserRequest.updated_at,
            ).where(ReportUserRequest.id == request_id)
        ).one_or_none()
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportUserRequestError(
            "report_request_store_unavailable",
            "The report request store is temporarily unavailable.",
            status_code=503,
        ) from exc
    if row is None:
        return None
    return {
        "request_id": str(row.request_id),
        "report_id": str(row.report_id),
        "status": row.status,
        "status_version": row.status_version,
        "allowed_next_statuses": list(
            allowed_request_statuses(row.status, request_type=row.request_type)
        ),
        "public_response": row.public_response,
        "updated_at": row.updated_at,
    }


__all__ = [
    "AdminRequestRow",
    "AdminRequestSummaryRow",
    "ReportUserCursor",
    "ReportUserCursorError",
    "ReportUserRequestError",
    "ReportUserRequestState",
    "admin_request_columns",
    "admin_request_summary_columns",
    "admin_request_filter_digest",
    "allowed_request_statuses",
    "assert_subject_active",
    "coerce_admin_request",
    "coerce_admin_request_summary",
    "coerce_user_report",
    "create_report_user_request",
    "decode_cursor",
    "derive_user_report_status",
    "encode_cursor",
    "get_owned_report_user_request",
    "get_owned_user_report",
    "latest_request_summaries",
    "project_admin_request_detail",
    "project_admin_request_summary",
    "project_user_report",
    "update_admin_request_status",
    "user_report_filter_digest",
    "user_report_statement",
]
