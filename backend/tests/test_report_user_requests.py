from __future__ import annotations

from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI, HTTPException
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError
from starlette.requests import Request

from backend.app.database import get_db
from backend.app.api import report_user_requests as api
from backend.app.field_test_security import (
    FieldTestAccess,
    _concealed_report_response,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
)
from backend.app.models import (
    AdminOperationAudit,
    ReportUserRequest,
    ReportUserRequestStatusEvent,
)
from backend.app.openapi_contract import (
    _DEVICE_PROOF_WORKFLOW_PATHS,
    _HIGH_RISK_DEVICE_PROOF_WORKFLOWS,
)
from backend.app.schemas import (
    AdminReportUserRequestStatusUpdateV1,
    ReportReviewDecisionRequest,
    ReportUserRequestCreateV1,
    UserReportSummaryV1,
)
from backend.app.services.admin_device_proof import (
    is_admin_device_proof_workflow_request,
)
from backend.app.services.admin_security import (
    AdminSessionIdentity,
    classify_admin_operation,
)
from backend.app.services.privacy_lifecycle import privacy_subject_hmac
from backend.app.services.report_user_requests import (
    ReportUserRequestError,
    admin_request_filter_digest,
    admin_request_summary_columns,
    allowed_request_statuses,
    decode_cursor,
    derive_user_report_status,
    encode_cursor,
    get_owned_user_report,
    request_intent_sha256,
    create_report_user_request,
    update_admin_request_status,
    user_report_filter_digest,
)
from backend.tests.asgi_client import ASGITestClient


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
REQUEST_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
REQUEST_ID_2 = uuid.UUID("66666666-6666-4666-8666-666666666666")
SESSION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
CORRELATION_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
CREATED_AT = datetime(2026, 8, 29, 2, 0, tzinfo=UTC)


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="admin@example.com",
        session_id=SESSION_ID,
        device_id="device-0001",
        device_label="관리자 기기",
        expires_at=CREATED_AT + timedelta(hours=1),
        step_up_verified_at=CREATED_AT,
    )


class _Result:
    def __init__(self, item: object | None) -> None:
        self.item = item

    def scalar_one_or_none(self) -> object | None:
        return self.item

    def one_or_none(self) -> object | None:
        return self.item

    def all(self) -> list[object]:
        return []


class _StatusSession:
    def __init__(self, item: ReportUserRequest) -> None:
        self.item = item
        self.added: list[object] = []
        self.commit_count = 0
        self.rollback_count = 0

    def execute(self, _statement: object) -> _Result:
        return _Result(self.item)

    def add(self, item: object) -> None:
        self.added.append(item)

    def commit(self) -> None:
        self.commit_count += 1

    def rollback(self) -> None:
        self.rollback_count += 1

    def refresh(self, _item: object) -> None:
        raise AssertionError("successful commits must not perform refresh I/O")


class _OwnedSession:
    def __init__(self, *, tombstoned: bool = False) -> None:
        self.tombstoned = tombstoned
        self.statements: list[object] = []
        self.rollback_count = 0

    def scalar(self, statement: object) -> uuid.UUID | None:
        self.statements.append(statement)
        return REQUEST_ID if self.tombstoned else None

    def execute(
        self, statement: object, _params: object | None = None
    ) -> SimpleNamespace:
        self.statements.append(statement)
        return SimpleNamespace(one_or_none=lambda: None, all=lambda: [])

    def rollback(self) -> None:
        self.rollback_count += 1


class _OwnedRequestSession:
    def __init__(
        self,
        items: list[ReportUserRequest],
        *,
        report_id: uuid.UUID,
        privacy_subject: str,
        account_generation: int,
    ) -> None:
        self.items = {item.id: item for item in items}
        self.report_id = report_id
        self.privacy_subject = privacy_subject
        self.account_generation = account_generation
        self.statements: list[object] = []
        self.rollback_count = 0
        self.commit_count = 0
        self.added: list[object] = []

    def execute(
        self, statement: object, params: object | None = None
    ) -> _Result:
        self.statements.append(statement)
        if params is not None:
            return _Result(None)
        sql = str(statement)
        values = set(statement.compile().params.values())
        if "JOIN reports" not in sql and "FROM reports" in sql:
            if {
                self.report_id,
                self.privacy_subject,
                self.account_generation,
            }.issubset(values):
                return _Result(SimpleNamespace(id=self.report_id))
            return _Result(None)
        if "JOIN reports" not in sql and "FROM report_user_requests" in sql:
            item = next(
                (
                    item
                    for item in self.items.values()
                    if item.id in values or item.client_request_id in values
                ),
                None,
            )
            return _Result(item)
        if not {
            self.report_id,
            self.privacy_subject,
            self.account_generation,
        }.issubset(values):
            return _Result(None)
        item = next((item for item in self.items.values() if item.id in values), None)
        if item is None or item.report_id != self.report_id:
            return _Result(None)
        return _Result(
            SimpleNamespace(
                request_id=item.id,
                report_id=item.report_id,
                request_type=item.request_type,
                status=item.status,
                status_version=item.status_version,
                public_response=item.public_response,
                created_at=item.created_at,
                updated_at=item.updated_at,
            )
        )

    def scalar(self, statement: object) -> None:
        self.statements.append(statement)
        return None

    def rollback(self) -> None:
        self.rollback_count += 1

    def add(self, item: object) -> None:
        self.added.append(item)
        if isinstance(item, ReportUserRequest):
            self.items[item.id] = item

    def commit(self) -> None:
        self.commit_count += 1


class _CreateIntegritySession:
    def __init__(self, concurrent: ReportUserRequest | None) -> None:
        self.concurrent = concurrent
        self.execute_count = 0
        self.added: list[object] = []
        self.rollback_count = 0

    def execute(self, _statement: object, _params: object | None = None) -> _Result:
        self.execute_count += 1
        if self.execute_count == 1:
            return _Result(None)  # shared advisory lock
        if self.execute_count == 2:
            return _Result(SimpleNamespace(id=REPORT_ID))
        if self.execute_count == 3:
            return _Result(None)
        return _Result(self.concurrent)

    def scalar(self, _statement: object) -> None:
        return None

    def add(self, item: object) -> None:
        self.added.append(item)

    def commit(self) -> None:
        raise IntegrityError("commit", {}, RuntimeError("integrity"))

    def rollback(self) -> None:
        self.rollback_count += 1


class _AdminIntegritySession(_StatusSession):
    def __init__(self, item: ReportUserRequest, latest_version: int) -> None:
        super().__init__(item)
        self.latest_version = latest_version
        self.execute_count = 0

    def execute(self, _statement: object) -> _Result:
        self.execute_count += 1
        if self.execute_count == 1:
            return _Result(self.item)
        return _Result(
            SimpleNamespace(
                request_id=REQUEST_ID,
                report_id=REPORT_ID,
                request_type=self.item.request_type,
                status=("ACKNOWLEDGED" if self.latest_version > 1 else "RECEIVED"),
                status_version=self.latest_version,
                public_response=None,
                updated_at=CREATED_AT,
            )
        )

    def commit(self) -> None:
        self.commit_count += 1
        raise IntegrityError("commit", {}, RuntimeError("integrity"))


def _request_item() -> ReportUserRequest:
    return ReportUserRequest(
        id=REQUEST_ID,
        report_id=REPORT_ID,
        client_request_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        request_type="CORRECTION",
        request_text="신고 내용을 정정해 주세요",
        intent_sha256="a" * 64,
        status="RECEIVED",
        status_version=1,
        public_response=None,
        internal_note=None,
        created_at=CREATED_AT,
        updated_at=CREATED_AT,
    )


def _owned_request_client(db: _OwnedRequestSession) -> ASGITestClient:
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


@pytest.mark.parametrize(
    ("deliveries", "review", "expected"),
    [
        (("FAILED",), "APPROVED", "RECEIVED"),
        (("FAILED",), "REJECTED", "REJECTED"),
        (("SUBMITTED", "FAILED"), "REJECTED", "INSTITUTION_SUBMITTED"),
        (("ACKNOWLEDGED", "FAILED"), "APPROVED", "INSTITUTION_SUBMITTED"),
        (("SUBMITTED", "RESOLVED", "FAILED"), "REJECTED", "RESOLVED"),
    ],
)
def test_user_report_status_precedence_ignores_failed_regression(
    deliveries: tuple[str, ...], review: str, expected: str
) -> None:
    assert derive_user_report_status(deliveries, review) == expected


def test_cursor_is_stable_and_bound_to_filters() -> None:
    digest = user_report_filter_digest(
        privacy_subject="a" * 64,
        account_generation=2,
        user_status="RECEIVED",
    )
    cursor = encode_cursor(
        created_at=CREATED_AT,
        row_id=REPORT_ID,
        filter_digest=digest,
    )
    assert encode_cursor(
        created_at=CREATED_AT,
        row_id=REPORT_ID,
        filter_digest=digest,
    ) == cursor
    assert decode_cursor(cursor, expected_filter_digest=digest).row_id == REPORT_ID
    with pytest.raises(ValueError):
        decode_cursor(
            cursor,
            expected_filter_digest=user_report_filter_digest(
                privacy_subject="a" * 64,
                account_generation=2,
                user_status="REJECTED",
            ),
        )

    admin_digest = admin_request_filter_digest(
        report_id=REPORT_ID,
        request_type="CORRECTION",
        status="RECEIVED",
    )
    assert admin_digest != admin_request_filter_digest(
        report_id=REPORT_ID,
        request_type="CORRECTION",
        status="ACKNOWLEDGED",
    )


@pytest.mark.parametrize("tombstoned", [False, True])
def test_foreign_generation_and_tombstone_are_concealed_as_not_found(
    tombstoned: bool,
) -> None:
    db = _OwnedSession(tombstoned=tombstoned)
    with pytest.raises(ReportUserRequestError) as captured:
        get_owned_user_report(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            privacy_subject="c" * 64,
            account_generation=7,
        )
    assert captured.value.status_code == 404
    assert captured.value.code == "report_not_found"
    assert "pg_advisory_xact_lock_shared" in str(db.statements[0])
    if not tombstoned:
        parameters = db.statements[-1].compile().params.values()
        assert REPORT_ID in parameters
        assert "c" * 64 in parameters
        assert 7 in parameters
    else:
        assert len(db.statements) == 2


def test_tombstoned_detail_actual_route_returns_concealed_404() -> None:
    db = _OwnedSession(tombstoned=True)
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
    response = ASGITestClient(app).get(
        f"/reports/mine/{REPORT_ID}",
        headers={
            "x-walksafe-actor-id": "field@example.com",
            "x-walksafe-account-generation": "1",
        },
    )
    assert response.status_code == 404
    assert response.json() == {"detail": {"code": "report_not_found", "message": "Report was not found."}}
    assert response.headers["cache-control"] == "no-store"
    assert "account_deletion_tombstones.tombstone_id" in str(db.statements[1])


def test_user_list_holds_shared_subject_lock_before_tombstone_and_report_reads() -> None:
    db = _OwnedSession()
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
    response = ASGITestClient(app).get(
        "/reports/mine",
        headers={
            "x-walksafe-actor-id": "field@example.com",
            "x-walksafe-account-generation": "1",
        },
    )
    assert response.status_code == 200
    assert response.json()["items"] == []
    assert "pg_advisory_xact_lock_shared" in str(db.statements[0])
    assert "account_deletion_tombstones.tombstone_id" in str(db.statements[1])
    assert "FROM reports" in str(db.statements[2])


def test_user_report_dto_has_only_public_minimum_fields() -> None:
    fields = set(UserReportSummaryV1.model_fields)
    assert fields == {
        "report_id",
        "created_at",
        "user_status",
        "public_rejection_reason",
        "latest_request",
    }
    assert fields.isdisjoint(
        {
            "latitude",
            "longitude",
            "image",
            "reason",
            "internal_reason",
            "internal_note",
            "institution",
            "recipient",
            "external_receipt_id",
        }
    )


def test_user_can_retrieve_each_request_and_observe_admin_status_immediately() -> None:
    secret = "test-privacy-secret-at-least-32-bytes"
    subject = privacy_subject_hmac("field@example.com", 1, secret)
    db = _OwnedRequestSession(
        [],
        report_id=REPORT_ID,
        privacy_subject=subject,
        account_generation=1,
    )
    first_state, first_created = create_report_user_request(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=ReportUserRequestCreateV1(
            client_request_id=REQUEST_ID,
            request_type="CORRECTION",
            request_text="신고 내용을 정정해 주세요",
        ),
        privacy_subject=subject,
        account_generation=1,
        now=CREATED_AT,
    )
    second_state, second_created = create_report_user_request(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=ReportUserRequestCreateV1(
            client_request_id=REQUEST_ID_2,
            request_type="DELETE",
            request_text="신고를 삭제해 주세요",
        ),
        privacy_subject=subject,
        account_generation=1,
        now=CREATED_AT,
    )
    assert first_created is second_created is True
    assert "pg_advisory_xact_lock_shared" in str(db.statements[0])
    client = _owned_request_client(db)
    headers = {
        "x-walksafe-actor-id": "field@example.com",
        "x-walksafe-account-generation": "1",
    }

    first_response = client.get(
        f"/reports/mine/{REPORT_ID}/requests/{first_state.id}", headers=headers
    )
    second_response = client.get(
        f"/reports/mine/{REPORT_ID}/requests/{second_state.id}", headers=headers
    )
    assert first_response.status_code == second_response.status_code == 200
    assert first_response.json()["request_id"] == str(first_state.id)
    assert second_response.json()["request_id"] == str(second_state.id)
    assert second_response.json()["request_type"] == "DELETE"

    second = db.items[second_state.id]
    update_admin_request_status(
        db,  # type: ignore[arg-type]
        request_id=second_state.id,
        payload=AdminReportUserRequestStatusUpdateV1(
            status="ACKNOWLEDGED",
            expected_version=1,
            public_response="삭제 요청을 확인했습니다",
            internal_note="사용자에게 노출하면 안 되는 메모",
        ),
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        query_sha256="b" * 64,
        now=CREATED_AT + timedelta(minutes=1),
    )
    updated_response = client.get(
        f"/reports/mine/{REPORT_ID}/requests/{second_state.id}", headers=headers
    )
    assert updated_response.status_code == 200
    assert updated_response.headers["cache-control"] == "no-store"
    assert updated_response.json() == {
        "request_id": str(second_state.id),
        "request_type": "DELETE",
        "status": "ACKNOWLEDGED",
        "status_version": 2,
        "public_response": "삭제 요청을 확인했습니다",
        "created_at": CREATED_AT.isoformat().replace("+00:00", "Z"),
        "updated_at": (CREATED_AT + timedelta(minutes=1))
        .isoformat()
        .replace("+00:00", "Z"),
    }
    assert set(updated_response.json()).isdisjoint(
        {"request_text", "internal_note", "intent_sha256", "client_request_id"}
    )
    lookup_sql = str(db.statements[-1])
    assert "JOIN reports" in lookup_sql
    assert "request_text" not in lookup_sql
    assert "internal_note" not in lookup_sql
    assert "intent_sha256" not in lookup_sql


@pytest.mark.parametrize(
    ("path", "headers"),
    [
        (
            f"/reports/mine/{REPORT_ID}/requests/{REQUEST_ID}",
            {
                "x-walksafe-actor-id": "other@example.com",
                "x-walksafe-account-generation": "1",
            },
        ),
        (
            f"/reports/mine/{REPORT_ID}/requests/{REQUEST_ID}",
            {
                "x-walksafe-actor-id": "field@example.com",
                "x-walksafe-account-generation": "2",
            },
        ),
        (
            f"/reports/mine/{uuid.UUID('77777777-7777-4777-8777-777777777777')}/requests/{REQUEST_ID}",
            {
                "x-walksafe-actor-id": "field@example.com",
                "x-walksafe-account-generation": "1",
            },
        ),
        (
            f"/reports/mine/{REPORT_ID}/requests/{uuid.UUID('88888888-8888-4888-8888-888888888888')}",
            {
                "x-walksafe-actor-id": "field@example.com",
                "x-walksafe-account-generation": "1",
            },
        ),
    ],
)
def test_user_request_lookup_conceals_owner_generation_report_and_id_mismatch(
    path: str, headers: dict[str, str]
) -> None:
    secret = "test-privacy-secret-at-least-32-bytes"
    db = _OwnedRequestSession(
        [_request_item()],
        report_id=REPORT_ID,
        privacy_subject=privacy_subject_hmac("field@example.com", 1, secret),
        account_generation=1,
    )
    response = _owned_request_client(db).get(path, headers=headers)
    assert response.status_code == 404
    assert response.json() == {"detail": {"code": "report_not_found"}}
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize(
    "path",
    [
        f"/reports/mine/{REPORT_ID}/requests/{REQUEST_ID}?debug=1",
        f"/reports/mine/{REPORT_ID}/requests/22222222-2222-4222-8222-22222222222A",
    ],
)
def test_user_request_lookup_rejects_query_and_noncanonical_uuid(path: str) -> None:
    secret = "test-privacy-secret-at-least-32-bytes"
    db = _OwnedRequestSession(
        [_request_item()],
        report_id=REPORT_ID,
        privacy_subject=privacy_subject_hmac("field@example.com", 1, secret),
        account_generation=1,
    )
    response = _owned_request_client(db).get(
        path,
        headers={
            "x-walksafe-actor-id": "field@example.com",
            "x-walksafe-account-generation": "1",
        },
    )
    assert response.status_code == 404
    assert response.json() == {"detail": {"code": "report_not_found"}}
    assert response.headers["cache-control"] == "no-store"


def test_review_reason_requires_public_internal_separation() -> None:
    common = {
        "decision_id": uuid.UUID("55555555-5555-4555-8555-555555555555"),
        "reason": "관리자 내부 검토 사유",
        "duplicate_of_report_id": None,
        "location_reviewed": True,
        "photo_reviewed": True,
        "privacy_reviewed": True,
    }
    with pytest.raises(ValidationError):
        ReportReviewDecisionRequest(decision="REJECTED", **common)
    rejected = ReportReviewDecisionRequest(
        decision="REJECTED",
        user_visible_reason="사용자에게 공개할 처리 사유",
        **common,
    )
    assert rejected.reason == "관리자 내부 검토 사유"
    assert rejected.user_visible_reason == "사용자에게 공개할 처리 사유"
    with pytest.raises(ValidationError):
        ReportReviewDecisionRequest(
            decision="APPROVED",
            user_visible_reason="승인에는 공개 거절 사유가 없어야 함",
            **common,
        )


def test_client_request_uuid_replay_digest_is_exact_intent_bound() -> None:
    base = ReportUserRequestCreateV1(
        client_request_id=REQUEST_ID,
        request_type="CORRECTION",
        request_text="표면 손상 범위를 정정해 주세요",
    )
    replay = ReportUserRequestCreateV1.model_validate(base.model_dump())
    conflict = ReportUserRequestCreateV1(
        client_request_id=REQUEST_ID,
        request_type="DELETE",
        request_text="신고를 삭제해 주세요",
    )
    assert request_intent_sha256(REPORT_ID, base) == request_intent_sha256(
        REPORT_ID, replay
    )
    assert request_intent_sha256(REPORT_ID, base) != request_intent_sha256(
        REPORT_ID, conflict
    )


@pytest.mark.parametrize(
    ("concurrent_kind", "expected_created", "expected_status"),
    [("same", False, None), ("different", None, 409), ("missing", None, 503)],
)
def test_create_integrity_error_rechecks_exact_idempotency_intent(
    concurrent_kind: str,
    expected_created: bool | None,
    expected_status: int | None,
) -> None:
    payload = ReportUserRequestCreateV1(
        client_request_id=REQUEST_ID,
        request_type="CORRECTION",
        request_text="표면 손상 범위를 정정해 주세요",
    )
    concurrent = _request_item() if concurrent_kind != "missing" else None
    if concurrent is not None:
        concurrent.client_request_id = REQUEST_ID
        concurrent.intent_sha256 = request_intent_sha256(REPORT_ID, payload)
        if concurrent_kind == "different":
            concurrent.intent_sha256 = "f" * 64
    db = _CreateIntegritySession(concurrent)
    if expected_status is None:
        _state, created = create_report_user_request(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            privacy_subject="d" * 64,
            account_generation=3,
            now=CREATED_AT,
        )
        assert created is expected_created
    else:
        with pytest.raises(ReportUserRequestError) as captured:
            create_report_user_request(
                db,  # type: ignore[arg-type]
                report_id=REPORT_ID,
                payload=payload,
                privacy_subject="d" * 64,
                account_generation=3,
                now=CREATED_AT,
            )
        assert captured.value.status_code == expected_status
    field_event = next(
        item for item in db.added if isinstance(item, ReportUserRequestStatusEvent)
    )
    assert field_event.actor_id is None
    assert field_event.privacy_subject_hmac == "d" * 64
    assert field_event.account_generation == 3


def test_request_status_transition_cas_writes_event_and_audit_in_one_commit() -> None:
    item = _request_item()
    db = _StatusSession(item)
    updated = update_admin_request_status(
        db,  # type: ignore[arg-type]
        request_id=REQUEST_ID,
        payload=AdminReportUserRequestStatusUpdateV1(
            status="ACKNOWLEDGED",
            expected_version=1,
            public_response="요청을 확인했습니다",
            internal_note="담당자 배정 완료",
        ),
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        query_sha256="b" * 64,
        now=CREATED_AT + timedelta(minutes=1),
    )
    assert (updated.status, updated.status_version) == ("ACKNOWLEDGED", 2)
    assert db.commit_count == 1
    assert db.rollback_count == 0
    assert len(db.added) == 2
    assert isinstance(db.added[0], ReportUserRequestStatusEvent)
    assert isinstance(db.added[1], AdminOperationAudit)
    assert db.added[1].operation == api.ADMIN_REQUEST_STATUS_OPERATION


def test_request_status_version_conflict_returns_latest_without_mutation() -> None:
    item = _request_item()
    item.status = "ACKNOWLEDGED"
    item.status_version = 2
    db = _StatusSession(item)
    with pytest.raises(ReportUserRequestError) as captured:
        update_admin_request_status(
            db,  # type: ignore[arg-type]
            request_id=REQUEST_ID,
            payload=AdminReportUserRequestStatusUpdateV1(
                status="RESOLVED",
                expected_version=1,
            ),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="b" * 64,
        )
    assert captured.value.status_code == 409
    assert captured.value.latest == {
        "request_id": str(REQUEST_ID),
        "report_id": str(REPORT_ID),
        "status": "ACKNOWLEDGED",
        "status_version": 2,
        "allowed_next_statuses": ["RESOLVED", "REJECTED"],
        "public_response": None,
        "updated_at": CREATED_AT,
    }
    assert db.added == []
    assert db.commit_count == 0
    assert db.rollback_count == 1


@pytest.mark.parametrize(
    ("latest_version", "expected_status", "expected_code"),
    [
        (2, 409, "report_request_version_conflict"),
        (1, 503, "report_request_store_unavailable"),
    ],
)
def test_admin_integrity_error_is_conflict_only_when_latest_version_changed(
    latest_version: int,
    expected_status: int,
    expected_code: str,
) -> None:
    db = _AdminIntegritySession(_request_item(), latest_version)
    with pytest.raises(ReportUserRequestError) as captured:
        update_admin_request_status(
            db,  # type: ignore[arg-type]
            request_id=REQUEST_ID,
            payload=AdminReportUserRequestStatusUpdateV1(
                status="ACKNOWLEDGED",
                expected_version=1,
            ),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="b" * 64,
            now=CREATED_AT + timedelta(minutes=1),
        )
    assert captured.value.status_code == expected_status
    assert captured.value.code == expected_code
    if expected_status == 409:
        assert captured.value.latest is not None
        assert captured.value.latest["status_version"] == 2
    else:
        assert captured.value.latest is None


def test_request_transitions_and_account_deletion_cascade_are_explicit() -> None:
    assert allowed_request_statuses("RECEIVED") == ("ACKNOWLEDGED",)
    assert allowed_request_statuses("ACKNOWLEDGED") == ("RESOLVED", "REJECTED")
    assert allowed_request_statuses("RESOLVED") == ()
    assert allowed_request_statuses("REJECTED") == ()
    request_fk = next(iter(ReportUserRequest.__table__.foreign_keys))
    event_fk = next(iter(ReportUserRequestStatusEvent.__table__.foreign_keys))
    assert request_fk.target_fullname == "reports.id"
    assert request_fk.ondelete == "CASCADE"
    assert event_fk.target_fullname == "report_user_requests.id"
    assert event_fk.ondelete == "CASCADE"


def test_migration_runtime_acl_is_minimal_and_events_are_append_only() -> None:
    source = Path(
        "backend/alembic/versions/202608290008_report_user_requests.py"
    ).read_text(encoding="utf-8")
    assert (
        '"GRANT SELECT, INSERT ON TABLE public.report_user_requests "'
        in source
    )
    assert (
        '"GRANT INSERT ON TABLE public.report_user_request_status_events "'
        in source
    )
    assert "GRANT UPDATE (status, status_version, public_response, internal_note, updated_at)" in source
    assert "DEFERRABLE INITIALLY DEFERRED" in source
    assert "VALIDATE CONSTRAINT" in source
    assert "SET user_visible_reason =" in source
    assert "SET user_visible_reason = reason" not in source
    assert "GRANT DELETE" not in source
    assert "GRANT TRUNCATE" not in source
    assert 'ondelete="CASCADE"' in source


def test_admin_list_projection_does_not_select_request_or_internal_text() -> None:
    selected = {column.name for column in admin_request_summary_columns()}
    assert selected.isdisjoint({"request_text", "public_response", "internal_note"})


def test_field_and_admin_security_contracts_are_route_exact() -> None:
    field_routes = (
        ("GET", "/reports/mine"),
        ("GET", "/reports/mine/requests/history"),
        ("GET", f"/reports/mine/{REPORT_ID}"),
        ("POST", f"/reports/mine/{REPORT_ID}/requests"),
        ("GET", f"/reports/mine/{REPORT_ID}/requests/{REQUEST_ID}"),
    )
    for method, path in field_routes:
        assert required_field_test_access(path, method) is FieldTestAccess.FIELD
        assert requires_actor_identity(path, method)
        assert requires_account_generation(path, method)
        concealed = _concealed_report_response(path, method)
        assert concealed is not None
        assert concealed.status_code == 404
        assert concealed.headers["cache-control"] == "no-store"

    status_path = f"/admin/report-requests/{REQUEST_ID}/status"
    operation = classify_admin_operation("PATCH", status_path)
    assert operation is not None
    assert (operation.action, operation.risk) == (
        api.ADMIN_REQUEST_STATUS_OPERATION,
        "HIGH",
    )
    assert is_admin_device_proof_workflow_request("GET", "/admin/report-requests")
    assert is_admin_device_proof_workflow_request(
        "GET", f"/admin/report-requests/{REQUEST_ID}"
    )
    assert is_admin_device_proof_workflow_request("PATCH", status_path)
    assert _DEVICE_PROOF_WORKFLOW_PATHS["/admin/report-requests"]["get"] == (
        None,
        api.ADMIN_REQUEST_LIST_OPERATION,
    )
    assert _HIGH_RISK_DEVICE_PROOF_WORKFLOWS[
        ("patch", "/admin/report-requests/{request_id}/status")
    ] == api.ADMIN_REQUEST_STATUS_OPERATION


def test_admin_authentication_errors_are_no_store() -> None:
    request = Request(
        {
            "type": "http",
            "method": "GET",
            "scheme": "https",
            "path": "/admin/report-requests",
            "raw_path": b"/admin/report-requests",
            "query_string": b"",
            "headers": [],
            "state": {},
        }
    )
    with pytest.raises(HTTPException) as captured:
        api._admin_read_context(
            request,
            operation=api.ADMIN_REQUEST_LIST_OPERATION,
        )
    assert captured.value.headers == api._NO_STORE


def test_user_routes_are_registered_before_dynamic_report_detail() -> None:
    source = Path("backend/app/main.py").read_text(encoding="utf-8")
    assert source.index(
        "app.include_router(report_user_requests.create_router(settings))"
    ) < source.index(
        "app.include_router(reports.create_router(settings, report_image_key_manager))"
    )
