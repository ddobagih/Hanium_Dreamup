"""Bounded account-owned discovery of report correction and deletion requests."""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
import hashlib
import json
import re
import uuid

from sqlalchemy import DateTime, String, func, literal, select, union_all
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import Report, ReportDeletionTombstone, ReportUserRequest
from backend.app.schemas import (
    ReportDeletionExternalCopyStatusV2,
    ReportDeletionStatusV2,
    ReportUserRequestSummaryV1,
    UserReportRequestHistoryItemV1,
    UserReportRequestHistoryPageV1,
)
from backend.app.services.privacy_lifecycle import lock_privacy_subject_exclusive
from backend.app.services.report_deletion import (
    ReportDeletionError,
    ReportDeletionStatus,
    get_owned_report_deletion_status,
)
from backend.app.services.report_user_requests import (
    ReportUserRequestError,
    assert_subject_active,
)


USER_REQUEST_HISTORY_DEFAULT_LIMIT = 10
USER_REQUEST_HISTORY_MAX_LIMIT = 25
USER_REQUEST_HISTORY_RESPONSE_BUDGET_BYTES = 48 * 1024
_MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991
_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,1024}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class UserReportRequestHistoryError(RuntimeError):
    def __init__(self, code: str, message: str, *, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


class UserReportRequestHistoryCursorError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class UserReportRequestHistoryCursor:
    resource: str
    filter_sha256: str
    snapshot_revision: int
    snapshot_count: int
    before_revision: int
    seen_count: int


@dataclass(frozen=True, slots=True)
class _HistorySourceRow:
    source: str
    discovery_revision: int
    report_id: uuid.UUID
    request_id: uuid.UUID
    request_type: str
    status: str | None
    status_version: int
    public_response: str | None
    created_at: object | None
    updated_at: object


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise UserReportRequestHistoryCursorError(
                "report request history cursor is invalid"
            )
        value[key] = item
    return value


def user_request_history_filter_sha256(
    *,
    privacy_subject: str,
    account_generation: int,
    report_id: uuid.UUID | None,
) -> str:
    payload = {
        "account_generation": account_generation,
        "privacy_subject": privacy_subject,
        "report_id": str(report_id) if report_id is not None else None,
    }
    return hashlib.sha256(
        json.dumps(
            payload,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("ascii")
    ).hexdigest()


def _resource(report_id: uuid.UUID | None) -> str:
    return str(report_id) if report_id is not None else "account"


def encode_user_request_history_cursor(
    *,
    report_id: uuid.UUID | None,
    filter_sha256: str,
    snapshot_revision: int,
    snapshot_count: int,
    before_revision: int,
    seen_count: int,
) -> str:
    if (
        _SHA256_PATTERN.fullmatch(filter_sha256) is None
        or type(snapshot_revision) is not int
        or type(snapshot_count) is not int
        or type(before_revision) is not int
        or type(seen_count) is not int
        or not 0 < before_revision <= snapshot_revision <= _MAX_SAFE_JSON_INTEGER
        or not 1 < snapshot_count <= _MAX_SAFE_JSON_INTEGER
        or not 0 < seen_count < snapshot_count
    ):
        raise ValueError("report request history cursor fields are invalid")
    payload = {
        "before_revision": before_revision,
        "filter_sha256": filter_sha256,
        "resource": _resource(report_id),
        "snapshot_count": snapshot_count,
        "snapshot_revision": snapshot_revision,
        "seen_count": seen_count,
        "v": 1,
    }
    raw = json.dumps(
        payload,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def decode_user_request_history_cursor(
    value: str,
    *,
    expected_report_id: uuid.UUID | None,
    expected_filter_sha256: str,
) -> UserReportRequestHistoryCursor:
    if (
        not isinstance(value, str)
        or _CURSOR_PATTERN.fullmatch(value) is None
        or _SHA256_PATTERN.fullmatch(expected_filter_sha256) is None
    ):
        raise UserReportRequestHistoryCursorError(
            "report request history cursor is invalid"
        )
    try:
        padded = value + "=" * ((4 - len(value) % 4) % 4)
        raw = base64.b64decode(
            padded.encode("ascii"),
            altchars=b"-_",
            validate=True,
        )
        payload = json.loads(raw.decode("ascii"), object_pairs_hook=_strict_object)
    except (binascii.Error, UnicodeError, ValueError, json.JSONDecodeError) as exc:
        raise UserReportRequestHistoryCursorError(
            "report request history cursor is invalid"
        ) from exc
    if not isinstance(payload, dict) or set(payload) != {
        "before_revision",
        "filter_sha256",
        "resource",
        "snapshot_count",
        "snapshot_revision",
        "seen_count",
        "v",
    }:
        raise UserReportRequestHistoryCursorError(
            "report request history cursor is invalid"
        )
    before_revision = payload["before_revision"]
    seen_count = payload["seen_count"]
    snapshot_count = payload["snapshot_count"]
    snapshot_revision = payload["snapshot_revision"]
    if (
        payload["v"] != 1
        or payload["resource"] != _resource(expected_report_id)
        or payload["filter_sha256"] != expected_filter_sha256
        or type(before_revision) is not int
        or type(seen_count) is not int
        or type(snapshot_count) is not int
        or type(snapshot_revision) is not int
        or not 0 < before_revision <= snapshot_revision <= _MAX_SAFE_JSON_INTEGER
        or not 1 < snapshot_count <= _MAX_SAFE_JSON_INTEGER
        or not 0 < seen_count < snapshot_count
    ):
        raise UserReportRequestHistoryCursorError(
            "report request history cursor is invalid"
        )
    try:
        canonical = encode_user_request_history_cursor(
            report_id=expected_report_id,
            filter_sha256=expected_filter_sha256,
            snapshot_revision=snapshot_revision,
            snapshot_count=snapshot_count,
            before_revision=before_revision,
            seen_count=seen_count,
        )
    except ValueError as exc:
        raise UserReportRequestHistoryCursorError(
            "report request history cursor is invalid"
        ) from exc
    if canonical != value:
        raise UserReportRequestHistoryCursorError(
            "report request history cursor is invalid"
        )
    return UserReportRequestHistoryCursor(
        resource=payload["resource"],
        filter_sha256=expected_filter_sha256,
        snapshot_revision=snapshot_revision,
        snapshot_count=snapshot_count,
        before_revision=before_revision,
        seen_count=seen_count,
    )


def _owned_source_statement(
    *,
    privacy_subject: str,
    account_generation: int,
    report_id: uuid.UUID | None,
):
    active = (
        select(
            literal("ACTIVE_REQUEST").label("source"),
            ReportUserRequest.discovery_revision,
            ReportUserRequest.report_id,
            ReportUserRequest.id.label("request_id"),
            ReportUserRequest.request_type,
            ReportUserRequest.status,
            ReportUserRequest.status_version,
            ReportUserRequest.public_response,
            ReportUserRequest.created_at,
            ReportUserRequest.updated_at,
        )
        .join(Report, Report.id == ReportUserRequest.report_id)
        .where(
            Report.privacy_subject_hmac == privacy_subject,
            Report.account_generation == account_generation,
        )
    )
    tombstones = select(
        literal("DELETION_TOMBSTONE").label("source"),
        ReportDeletionTombstone.discovery_revision,
        ReportDeletionTombstone.report_id,
        ReportDeletionTombstone.request_id,
        literal("DELETE").label("request_type"),
        literal(None, type_=String()).label("status"),
        ReportDeletionTombstone.request_status_version.label("status_version"),
        literal(None, type_=String()).label("public_response"),
        literal(None, type_=DateTime(timezone=True)).label("created_at"),
        ReportDeletionTombstone.deleted_at.label("updated_at"),
    ).where(
        ReportDeletionTombstone.privacy_subject_hmac == privacy_subject,
        ReportDeletionTombstone.account_generation == account_generation,
    )
    if report_id is not None:
        active = active.where(ReportUserRequest.report_id == report_id)
        tombstones = tombstones.where(
            ReportDeletionTombstone.report_id == report_id
        )
    return union_all(active, tombstones).subquery()


def _owned_report_exists(
    db: Session,
    *,
    report_id: uuid.UUID,
    privacy_subject: str,
    account_generation: int,
) -> bool:
    current = select(Report.id.label("report_id")).where(
        Report.id == report_id,
        Report.privacy_subject_hmac == privacy_subject,
        Report.account_generation == account_generation,
    )
    deleted = select(ReportDeletionTombstone.report_id).where(
        ReportDeletionTombstone.report_id == report_id,
        ReportDeletionTombstone.privacy_subject_hmac == privacy_subject,
        ReportDeletionTombstone.account_generation == account_generation,
    )
    return db.execute(union_all(current, deleted).limit(1)).first() is not None


def _deletion_projection(state: ReportDeletionStatus) -> ReportDeletionStatusV2:
    return ReportDeletionStatusV2(
        schema_version="walksafe.report-deletion-status.v2",
        request_id=state.request_id,
        report_id=state.report_id,
        state=state.state,
        request_status_version=state.request_status_version,
        external_copy_count=state.external_copy_count,
        external_copies=[
            ReportDeletionExternalCopyStatusV2(
                institution=item.institution,
                state=item.state,
                status_recorded_at=item.status_recorded_at,
            )
            for item in state.external_copies
        ],
        updated_at=state.updated_at,
    )


def _coerce_source(row: object) -> _HistorySourceRow:
    return _HistorySourceRow(
        **{
            field: getattr(row, field)
            for field in _HistorySourceRow.__dataclass_fields__
        }
    )


def _project_item(
    db: Session,
    *,
    row: _HistorySourceRow,
    privacy_subject: str,
    account_generation: int,
) -> UserReportRequestHistoryItemV1:
    request = None
    if row.source == "ACTIVE_REQUEST":
        request = ReportUserRequestSummaryV1(
            request_id=row.request_id,
            request_type=row.request_type,
            status=row.status,
            status_version=row.status_version,
            public_response=row.public_response,
            created_at=row.created_at,
            updated_at=row.updated_at,
        )
    deletion_status = None
    if row.request_type == "DELETE":
        deletion_status = _deletion_projection(
            get_owned_report_deletion_status(
                db,
                request_id=row.request_id,
                privacy_subject=privacy_subject,
                account_generation=account_generation,
            )
        )
    return UserReportRequestHistoryItemV1(
        revision=row.discovery_revision,
        source=row.source,
        report_id=row.report_id,
        request_id=row.request_id,
        request=request,
        deletion_status=deletion_status,
    )


def _page_within_budget(
    items: list[UserReportRequestHistoryItemV1],
    *,
    report_id: uuid.UUID | None,
    filter_sha256: str,
    snapshot_revision: int,
    snapshot_count: int,
    already_seen: int,
) -> UserReportRequestHistoryPageV1:
    if not items:
        page = UserReportRequestHistoryPageV1(
            schema_version="walksafe.user-report-request-history-page.v1",
            report_id=report_id,
            snapshot_revision=snapshot_revision,
            total_count=snapshot_count,
            items=[],
            next_cursor=None,
        )
        if (
            len(page.model_dump_json().encode("utf-8"))
            > USER_REQUEST_HISTORY_RESPONSE_BUDGET_BYTES
        ):
            raise UserReportRequestHistoryError(
                "report_request_history_response_too_large",
                "The report request history cannot fit the response limit.",
                status_code=503,
            )
        return page
    for length in range(len(items), 0, -1):
        page_items = items[:length]
        last_revision = page_items[-1].revision
        seen_count = already_seen + length
        next_cursor = (
            encode_user_request_history_cursor(
                report_id=report_id,
                filter_sha256=filter_sha256,
                snapshot_revision=snapshot_revision,
                snapshot_count=snapshot_count,
                before_revision=last_revision,
                seen_count=seen_count,
            )
            if seen_count < snapshot_count
            else None
        )
        page = UserReportRequestHistoryPageV1(
            schema_version="walksafe.user-report-request-history-page.v1",
            report_id=report_id,
            snapshot_revision=snapshot_revision,
            total_count=snapshot_count,
            items=page_items,
            next_cursor=next_cursor,
        )
        if (
            len(page.model_dump_json().encode("utf-8"))
            <= USER_REQUEST_HISTORY_RESPONSE_BUDGET_BYTES
        ):
            return page
    raise UserReportRequestHistoryError(
        "report_request_history_response_too_large",
        "One report request history item exceeds the response limit.",
        status_code=503,
    )


def list_owned_report_request_history(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    report_id: uuid.UUID | None,
    limit: int,
    cursor: str | None,
) -> UserReportRequestHistoryPageV1:
    if type(limit) is not int or not 1 <= limit <= USER_REQUEST_HISTORY_MAX_LIMIT:
        raise UserReportRequestHistoryError(
            "report_request_history_query_invalid",
            "The report request history query is invalid.",
            status_code=422,
        )
    filter_digest = user_request_history_filter_sha256(
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        report_id=report_id,
    )
    try:
        decoded = (
            decode_user_request_history_cursor(
                cursor,
                expected_report_id=report_id,
                expected_filter_sha256=filter_digest,
            )
            if cursor is not None
            else None
        )
    except UserReportRequestHistoryCursorError as exc:
        raise UserReportRequestHistoryError(
            "report_request_history_cursor_invalid",
            "The report request history cursor is invalid.",
            status_code=422,
        ) from exc
    try:
        lock_privacy_subject_exclusive(db, privacy_subject, account_generation)
        assert_subject_active(
            db,
            privacy_subject=privacy_subject,
            account_generation=account_generation,
        )
        if report_id is not None and not _owned_report_exists(
            db,
            report_id=report_id,
            privacy_subject=privacy_subject,
            account_generation=account_generation,
        ):
            raise ReportUserRequestError(
                "report_not_found",
                "Report was not found.",
                status_code=404,
            )
        sources = _owned_source_statement(
            privacy_subject=privacy_subject,
            account_generation=account_generation,
            report_id=report_id,
        )
        snapshot_limit = (
            decoded.snapshot_revision
            if decoded is not None
            else _MAX_SAFE_JSON_INTEGER
        )
        stats = db.execute(
            select(
                func.count().label("total_count"),
                func.max(sources.c.discovery_revision).label("maximum_revision"),
                func.count(func.distinct(sources.c.discovery_revision)).label(
                    "distinct_revisions"
                ),
                func.count(func.distinct(sources.c.request_id)).label(
                    "distinct_requests"
                ),
                func.count()
                .filter(
                    sources.c.discovery_revision
                    >= (
                        decoded.before_revision
                        if decoded is not None
                        else _MAX_SAFE_JSON_INTEGER + 1
                    )
                )
                .label("seen_count"),
            )
            .select_from(sources)
            .where(sources.c.discovery_revision <= snapshot_limit)
        ).one()
        total_count = int(stats.total_count)
        maximum_revision = (
            0 if stats.maximum_revision is None else int(stats.maximum_revision)
        )
        if (
            int(stats.distinct_revisions) != total_count
            or int(stats.distinct_requests) != total_count
            or (
                decoded is not None
                and (
                    total_count != decoded.snapshot_count
                    or maximum_revision != decoded.snapshot_revision
                    or int(stats.seen_count) != decoded.seen_count
                )
            )
        ):
            raise UserReportRequestHistoryError(
                "report_request_history_integrity_invalid",
                "The report request history is temporarily unavailable.",
                status_code=503,
            )
        snapshot_revision = (
            decoded.snapshot_revision if decoded is not None else maximum_revision
        )
        snapshot_count = decoded.snapshot_count if decoded is not None else total_count
        already_seen = decoded.seen_count if decoded is not None else 0
        page_statement = select(sources).where(
            sources.c.discovery_revision <= snapshot_revision
        )
        if decoded is not None:
            page_statement = page_statement.where(
                sources.c.discovery_revision < decoded.before_revision
            )
        raw_rows = db.execute(
            page_statement.order_by(sources.c.discovery_revision.desc()).limit(limit)
        ).all()
        rows = [_coerce_source(row) for row in raw_rows]
        revisions = [row.discovery_revision for row in rows]
        expected_row_count = min(limit, max(0, snapshot_count - already_seen))
        if (
            len(rows) != expected_row_count
            or already_seen > snapshot_count
            or revisions != sorted(set(revisions), reverse=True)
            or any(
                revision <= 0 or revision > snapshot_revision
                for revision in revisions
            )
            or (already_seen < snapshot_count and not rows)
        ):
            raise UserReportRequestHistoryError(
                "report_request_history_integrity_invalid",
                "The report request history is temporarily unavailable.",
                status_code=503,
            )
        items = [
            _project_item(
                db,
                row=row,
                privacy_subject=privacy_subject,
                account_generation=account_generation,
            )
            for row in rows
        ]
        return _page_within_budget(
            items,
            report_id=report_id,
            filter_sha256=filter_digest,
            snapshot_revision=snapshot_revision,
            snapshot_count=snapshot_count,
            already_seen=already_seen,
        )
    except ReportUserRequestError:
        raise
    except UserReportRequestHistoryError:
        raise
    except ReportDeletionError as exc:
        raise UserReportRequestHistoryError(
            "report_request_history_integrity_invalid",
            "The report request history is temporarily unavailable.",
            status_code=503,
        ) from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise UserReportRequestHistoryError(
            "report_request_history_unavailable",
            "The report request history is temporarily unavailable.",
            status_code=503,
        ) from exc
    except (AttributeError, TypeError, ValueError) as exc:
        raise UserReportRequestHistoryError(
            "report_request_history_integrity_invalid",
            "The report request history is temporarily unavailable.",
            status_code=503,
        ) from exc


__all__ = [
    "USER_REQUEST_HISTORY_DEFAULT_LIMIT",
    "USER_REQUEST_HISTORY_MAX_LIMIT",
    "USER_REQUEST_HISTORY_RESPONSE_BUDGET_BYTES",
    "UserReportRequestHistoryCursor",
    "UserReportRequestHistoryCursorError",
    "UserReportRequestHistoryError",
    "decode_user_request_history_cursor",
    "encode_user_request_history_cursor",
    "list_owned_report_request_history",
    "user_request_history_filter_sha256",
]
