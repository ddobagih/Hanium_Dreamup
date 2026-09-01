from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI
from pydantic import ValidationError

from backend.app.api import report_user_requests as api
from backend.app.database import get_db
from backend.app.field_test_security import (
    FieldTestAccess,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
)
from backend.app.models import ReportDeletionTombstone, ReportUserRequest
from backend.app.schemas import (
    ReportDeletionExternalCopyStatusV2,
    ReportDeletionStatusV2,
    ReportUserRequestSummaryV1,
    UserReportRequestHistoryItemV1,
    UserReportRequestHistoryPageV1,
)
from backend.app.services import report_user_request_history as history
from backend.app.services.report_deletion import (
    ReportDeletionExternalCopyStatus,
    ReportDeletionStatus,
)
from backend.tests.asgi_client import ASGITestClient


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
REQUEST_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
DELETION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
CREATED_AT = datetime(2026, 9, 1, 3, 0, tzinfo=UTC)
SUBJECT = "a" * 64


def _summary(
    *,
    request_id: uuid.UUID = REQUEST_ID,
    request_type: str = "CORRECTION",
    status_version: int = 1,
    public_response: str | None = None,
) -> ReportUserRequestSummaryV1:
    return ReportUserRequestSummaryV1(
        request_id=request_id,
        request_type=request_type,
        status="RECEIVED",
        status_version=status_version,
        public_response=public_response,
        created_at=CREATED_AT,
        updated_at=CREATED_AT,
    )


def _deletion(
    *,
    request_id: uuid.UUID = DELETION_ID,
    status_version: int = 2,
) -> ReportDeletionStatusV2:
    return ReportDeletionStatusV2(
        schema_version="walksafe.report-deletion-status.v2",
        request_id=request_id,
        report_id=REPORT_ID,
        state="DELETED",
        request_status_version=status_version,
        external_copy_count=1,
        external_copies=[
            ReportDeletionExternalCopyStatusV2(
                institution="테스트 기관",
                state="REQUEST_SENT",
                status_recorded_at=CREATED_AT,
            )
        ],
        updated_at=CREATED_AT,
    )


def test_cursor_is_canonical_and_bound_to_owner_generation_and_report() -> None:
    digest = history.user_request_history_filter_sha256(
        privacy_subject=SUBJECT,
        account_generation=7,
        report_id=REPORT_ID,
    )
    cursor = history.encode_user_request_history_cursor(
        report_id=REPORT_ID,
        filter_sha256=digest,
        snapshot_revision=101,
        snapshot_count=3,
        before_revision=91,
        seen_count=1,
    )
    decoded = history.decode_user_request_history_cursor(
        cursor,
        expected_report_id=REPORT_ID,
        expected_filter_sha256=digest,
    )
    assert (decoded.snapshot_revision, decoded.before_revision) == (101, 91)
    assert decoded.seen_count == 1
    assert cursor == history.encode_user_request_history_cursor(
        report_id=REPORT_ID,
        filter_sha256=digest,
        snapshot_revision=101,
        snapshot_count=3,
        before_revision=91,
        seen_count=1,
    )
    for wrong_digest, wrong_report in (
        (
            history.user_request_history_filter_sha256(
                privacy_subject=SUBJECT,
                account_generation=8,
                report_id=REPORT_ID,
            ),
            REPORT_ID,
        ),
        (digest, uuid.UUID("44444444-4444-4444-8444-444444444444")),
    ):
        with pytest.raises(history.UserReportRequestHistoryCursorError):
            history.decode_user_request_history_cursor(
                cursor,
                expected_report_id=wrong_report,
                expected_filter_sha256=wrong_digest,
            )
    with pytest.raises(history.UserReportRequestHistoryCursorError):
        history.decode_user_request_history_cursor(
            cursor + "=",
            expected_report_id=REPORT_ID,
            expected_filter_sha256=digest,
        )


def test_history_schema_keeps_gapped_revisions_newest_first() -> None:
    newer = UserReportRequestHistoryItemV1(
        revision=100,
        source="ACTIVE_REQUEST",
        report_id=REPORT_ID,
        request_id=REQUEST_ID,
        request=_summary(),
        deletion_status=None,
    )
    older = UserReportRequestHistoryItemV1(
        revision=3,
        source="DELETION_TOMBSTONE",
        report_id=REPORT_ID,
        request_id=DELETION_ID,
        request=None,
        deletion_status=_deletion(),
    )
    page = UserReportRequestHistoryPageV1(
        schema_version="walksafe.user-report-request-history-page.v1",
        report_id=None,
        snapshot_revision=100,
        total_count=2,
        items=[newer, older],
        next_cursor=None,
    )
    assert [item.revision for item in page.items] == [100, 3]
    with pytest.raises(ValidationError):
        UserReportRequestHistoryPageV1(
            schema_version="walksafe.user-report-request-history-page.v1",
            report_id=None,
            snapshot_revision=100,
            total_count=2,
            items=[older, newer],
            next_cursor=None,
        )
    beyond_snapshot = UserReportRequestHistoryItemV1(
        revision=101,
        source="ACTIVE_REQUEST",
        report_id=REPORT_ID,
        request_id=REQUEST_ID,
        request=_summary(),
        deletion_status=None,
    )
    with pytest.raises(ValidationError):
        UserReportRequestHistoryPageV1(
            schema_version="walksafe.user-report-request-history-page.v1",
            report_id=None,
            snapshot_revision=100,
            total_count=2,
            items=[beyond_snapshot, older],
            next_cursor=None,
        )


def test_history_schema_never_synthesizes_or_mixes_tombstone_status() -> None:
    with pytest.raises(ValidationError):
        UserReportRequestHistoryItemV1(
            revision=3,
            source="DELETION_TOMBSTONE",
            report_id=REPORT_ID,
            request_id=DELETION_ID,
            request=_summary(request_id=DELETION_ID, request_type="DELETE"),
            deletion_status=_deletion(status_version=1),
        )
    with pytest.raises(ValidationError):
        UserReportRequestHistoryItemV1(
            revision=4,
            source="ACTIVE_REQUEST",
            report_id=REPORT_ID,
            request_id=DELETION_ID,
            request=_summary(
                request_id=DELETION_ID,
                request_type="DELETE",
                status_version=1,
            ),
            deletion_status=_deletion(status_version=2),
        )


class _Result:
    def __init__(self, value: object) -> None:
        self.value = value

    def one(self) -> object:
        return self.value

    def all(self) -> list[object]:
        return list(self.value)  # type: ignore[arg-type]

    def first(self) -> object | None:
        values = list(self.value)  # type: ignore[arg-type]
        return values[0] if values else None


class _HistorySession:
    def __init__(self, stats: object, rows: list[object]) -> None:
        self.results = [_Result(stats), _Result(rows)]
        self.statements: list[object] = []
        self.rollback_count = 0

    def execute(self, statement: object) -> _Result:
        self.statements.append(statement)
        return self.results.pop(0)

    def rollback(self) -> None:
        self.rollback_count += 1


def _source_row(
    *,
    source: str,
    revision: int,
    request_id: uuid.UUID,
    request_type: str,
    status: str | None,
    status_version: int,
) -> SimpleNamespace:
    return SimpleNamespace(
        source=source,
        discovery_revision=revision,
        report_id=REPORT_ID,
        request_id=request_id,
        request_type=request_type,
        status=status,
        status_version=status_version,
        public_response=None,
        created_at=CREATED_AT if source == "ACTIVE_REQUEST" else None,
        updated_at=CREATED_AT,
    )


def _disable_subject_checks(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(history, "lock_privacy_subject_exclusive", lambda *_args: None)
    monkeypatch.setattr(
        history,
        "assert_subject_active",
        lambda *_args, **_kwargs: None,
    )


def test_history_service_fences_subject_writers_with_exclusive_lock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, int]] = []
    monkeypatch.setattr(
        history,
        "lock_privacy_subject_exclusive",
        lambda _db, subject, generation: calls.append((subject, generation)),
    )
    monkeypatch.setattr(
        history,
        "assert_subject_active",
        lambda *_args, **_kwargs: None,
    )
    db = _HistorySession(
        SimpleNamespace(
            total_count=0,
            maximum_revision=None,
            distinct_revisions=0,
            distinct_requests=0,
            seen_count=0,
        ),
        [],
    )
    page = history.list_owned_report_request_history(
        db,  # type: ignore[arg-type]
        privacy_subject=SUBJECT,
        account_generation=7,
        report_id=None,
        limit=10,
        cursor=None,
    )
    assert calls == [(SUBJECT, 7)]
    assert page.total_count == 0


def test_history_service_pages_active_and_tombstone_sources_without_duplicates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _disable_subject_checks(monkeypatch)
    first_db = _HistorySession(
        SimpleNamespace(
            total_count=2,
            maximum_revision=10,
            distinct_revisions=2,
            distinct_requests=2,
            seen_count=0,
        ),
        [
            _source_row(
                source="ACTIVE_REQUEST",
                revision=10,
                request_id=REQUEST_ID,
                request_type="CORRECTION",
                status="RECEIVED",
                status_version=1,
            )
        ],
    )
    first = history.list_owned_report_request_history(
        first_db,  # type: ignore[arg-type]
        privacy_subject=SUBJECT,
        account_generation=7,
        report_id=None,
        limit=1,
        cursor=None,
    )
    assert [item.revision for item in first.items] == [10]
    assert first.items[0].source == "ACTIVE_REQUEST"
    assert first.next_cursor is not None

    second_db = _HistorySession(
        SimpleNamespace(
            total_count=2,
            maximum_revision=10,
            distinct_revisions=2,
            distinct_requests=2,
            seen_count=1,
        ),
        [
            _source_row(
                source="DELETION_TOMBSTONE",
                revision=3,
                request_id=DELETION_ID,
                request_type="DELETE",
                status=None,
                status_version=2,
            )
        ],
    )
    monkeypatch.setattr(
        history,
        "get_owned_report_deletion_status",
        lambda *_args, **_kwargs: ReportDeletionStatus(
            request_id=DELETION_ID,
            report_id=REPORT_ID,
            state="DELETED",
            request_status_version=2,
            external_copy_count=1,
            updated_at=CREATED_AT,
            external_copies=(
                ReportDeletionExternalCopyStatus(
                    institution="테스트 기관",
                    state="REQUEST_SENT",
                    status_recorded_at=CREATED_AT,
                ),
            ),
        ),
    )
    second = history.list_owned_report_request_history(
        second_db,  # type: ignore[arg-type]
        privacy_subject=SUBJECT,
        account_generation=7,
        report_id=None,
        limit=1,
        cursor=first.next_cursor,
    )
    assert second.next_cursor is None
    assert second.items[0].source == "DELETION_TOMBSTONE"
    assert second.items[0].request is None
    assert second.items[0].deletion_status is not None
    assert second.items[0].deletion_status.external_copy_count == 1
    assert "discovery_revision <" in str(second_db.statements[-1])


def test_history_service_fails_closed_on_mixed_delete_versions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _disable_subject_checks(monkeypatch)
    db = _HistorySession(
        SimpleNamespace(
            total_count=1,
            maximum_revision=8,
            distinct_revisions=1,
            distinct_requests=1,
            seen_count=0,
        ),
        [
            _source_row(
                source="ACTIVE_REQUEST",
                revision=8,
                request_id=DELETION_ID,
                request_type="DELETE",
                status="RECEIVED",
                status_version=1,
            )
        ],
    )
    monkeypatch.setattr(
        history,
        "get_owned_report_deletion_status",
        lambda *_args, **_kwargs: ReportDeletionStatus(
            request_id=DELETION_ID,
            report_id=REPORT_ID,
            state="PENDING",
            request_status_version=2,
            external_copy_count=0,
            updated_at=CREATED_AT,
        ),
    )
    with pytest.raises(history.UserReportRequestHistoryError) as captured:
        history.list_owned_report_request_history(
            db,  # type: ignore[arg-type]
            privacy_subject=SUBJECT,
            account_generation=7,
            report_id=None,
            limit=1,
            cursor=None,
        )
    assert captured.value.status_code == 503
    assert captured.value.code == "report_request_history_integrity_invalid"


def test_history_service_rejects_duplicate_snapshot_revisions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _disable_subject_checks(monkeypatch)
    duplicate = _HistorySession(
        SimpleNamespace(
            total_count=2,
            maximum_revision=10,
            distinct_revisions=1,
            distinct_requests=2,
            seen_count=0,
        ),
        [],
    )
    with pytest.raises(history.UserReportRequestHistoryError) as captured:
        history.list_owned_report_request_history(
            duplicate,  # type: ignore[arg-type]
            privacy_subject=SUBJECT,
            account_generation=7,
            report_id=None,
            limit=2,
            cursor=None,
        )
    assert captured.value.code == "report_request_history_integrity_invalid"


def test_history_service_conceals_foreign_report_filter(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _disable_subject_checks(monkeypatch)
    db = _HistorySession(SimpleNamespace(), [])
    db.results = [_Result([])]
    with pytest.raises(history.ReportUserRequestError) as captured:
        history.list_owned_report_request_history(
            db,  # type: ignore[arg-type]
            privacy_subject=SUBJECT,
            account_generation=7,
            report_id=REPORT_ID,
            limit=10,
            cursor=None,
        )
    assert captured.value.status_code == 404
    assert captured.value.code == "report_not_found"


def test_utf8_history_page_is_bounded_and_continues(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        history,
        "USER_REQUEST_HISTORY_RESPONSE_BUDGET_BYTES",
        24 * 1024,
    )
    items: list[UserReportRequestHistoryItemV1] = []
    for index in range(25):
        request_id = uuid.uuid4()
        items.append(
            UserReportRequestHistoryItemV1(
                revision=100 - index,
                source="ACTIVE_REQUEST",
                report_id=REPORT_ID,
                request_id=request_id,
                request=_summary(
                    request_id=request_id,
                    public_response="가" * 500,
                ),
                deletion_status=None,
            )
        )
    digest = history.user_request_history_filter_sha256(
        privacy_subject=SUBJECT,
        account_generation=7,
        report_id=None,
    )
    page = history._page_within_budget(
        items,
        report_id=None,
        filter_sha256=digest,
        snapshot_revision=100,
        snapshot_count=25,
        already_seen=0,
    )
    assert len(page.model_dump_json().encode("utf-8")) <= 24 * 1024
    assert len(page.items) < 25
    assert page.next_cursor is not None


class _RouteDb:
    def __init__(self) -> None:
        self.rollback_count = 0

    def rollback(self) -> None:
        self.rollback_count += 1


def _route_client(db: _RouteDb) -> ASGITestClient:
    app = FastAPI()
    app.include_router(
        api.create_router(
            SimpleNamespace(
                field_test_security_enabled=False,
                privacy_hmac_secret="test-privacy-secret-at-least-32-bytes",
            )
        )
    )

    def database_override():
        yield db

    app.dependency_overrides[get_db] = database_override
    return ASGITestClient(app)


def test_history_route_is_strict_canonical_no_store_and_field_bound(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    def list_history(_db, **kwargs):
        captured.update(kwargs)
        return UserReportRequestHistoryPageV1(
            schema_version="walksafe.user-report-request-history-page.v1",
            report_id=kwargs["report_id"],
            snapshot_revision=0,
            total_count=0,
            items=[],
            next_cursor=None,
        )

    monkeypatch.setattr(api, "list_owned_report_request_history", list_history)
    headers = {
        "x-walksafe-actor-id": "field@example.com",
        "x-walksafe-account-generation": "7",
    }
    client = _route_client(_RouteDb())
    response = client.get(
        f"/reports/mine/requests/history?limit=25&report_id={REPORT_ID}",
        headers=headers,
    )
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert captured["report_id"] == REPORT_ID
    assert captured["limit"] == 25
    duplicate = client.get(
        "/reports/mine/requests/history?limit=1&limit=2",
        headers=headers,
    )
    assert duplicate.status_code == 422
    assert duplicate.json()["detail"]["code"] == (
        "report_request_history_query_invalid"
    )
    uppercase = client.get(
        "/reports/mine/requests/history?report_id="
        "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF",
        headers=headers,
    )
    assert uppercase.status_code == 422

    path = "/reports/mine/requests/history"
    assert required_field_test_access(path, "GET") is FieldTestAccess.FIELD
    assert requires_actor_identity(path, "GET")
    assert requires_account_generation(path, "GET")


def test_discovery_migration_is_minimal_stable_and_worker_preserves_revision() -> None:
    root = Path(__file__).resolve().parents[2]
    migration = (
        root
        / "backend/alembic/versions/202609010001_report_user_request_discovery.py"
    ).read_text(encoding="utf-8")
    deletion_service = (
        root / "backend/app/services/report_deletion.py"
    ).read_text(encoding="utf-8")
    assert 'down_revision = "202608300006"' in migration
    assert "CREATE SEQUENCE public." in migration
    assert "MAXVALUE 9007199254740991" in migration
    assert "row_number() OVER" in migration
    assert "ORDER BY occurred_at, source_kind, source_id" in migration
    assert "ALTER TABLE public.report_user_requests DISABLE TRIGGER" in migration
    assert "ALTER TABLE public.report_user_requests ENABLE TRIGGER" in migration
    assert migration.count("report_user_request_projection_event_guard") == 2
    assert "pg_catalog.setval" in migration
    assert "GRANT USAGE, SELECT ON SEQUENCE" in migration
    assert "REVOKE INSERT ON TABLE public.report_user_requests" in migration
    assert (
        "GRANT INSERT (id, report_id, client_request_id, request_type" in migration
    )
    assert "request.discovery_revision = NEW.discovery_revision" in migration
    assert migration.count("BETWEEN 1 AND 9007199254740991") == 2
    assert "ReportUserRequest.discovery_revision" in deletion_service
    assert "discovery_revision=row.discovery_revision" in deletion_service
    request_default = ReportUserRequest.__table__.c.discovery_revision.server_default
    assert request_default is not None
    assert "public.report_user_request_discovery_revision_seq" in str(
        request_default.arg
    )
    assert "discovery_revision" in ReportDeletionTombstone.__table__.c
