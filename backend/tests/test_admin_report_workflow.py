from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime
from types import SimpleNamespace
import uuid

import pytest
from fastapi import HTTPException, Response
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from starlette.requests import Request

from backend.app.api import reports as reports_api
from backend.app.models import (
    Report,
    ReportDeliveryPackage,
    ReportInstitutionDeliveryEvent,
    ReportOriginalAccessGrant,
    ReportReviewDecision,
)
from backend.app.schemas import (
    ReportInstitutionDeliveryRequest,
    ReportInstitutionDeliveryResponse,
    ReportOriginalAccessGrantRequest,
    ReportReviewDecisionRequest,
)
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    append_report_institution_delivery_event,
    append_report_review_decision,
)
from backend.app.services.admin_security import (
    AdminSecurityStoreUnavailable,
    AdminSessionIdentity,
)


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
IDEMPOTENCY_KEY = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
SESSION_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
CORRELATION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
DECISION_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
EVIDENCE_GRANT_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")
PACKAGE_ID = uuid.UUID("99999999-9999-4999-8999-999999999999")
PREVIOUS_PACKAGE_ID = uuid.UUID("77777777-7777-4777-8777-777777777777")


def _approved_request(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "decision": "APPROVED",
        "reason": "  확인 완료  ",
        "duplicate_of_report_id": None,
        "location_reviewed": True,
        "photo_reviewed": True,
        "privacy_reviewed": True,
        "evidence_grant_id": str(EVIDENCE_GRANT_ID),
    }
    payload.update(overrides)
    if payload["decision"] in {"REJECTED", "DUPLICATE"}:
        payload.setdefault("user_visible_reason", "신고 처리 결과를 확인해 주세요")
        payload["evidence_grant_id"] = None
    return payload


def _delivery_request(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "institution": "  서울시청  ",
        "channel": "  WEB_PORTAL  ",
        "recipient": "  safety-desk  ",
        "status": "SUBMITTED",
        "external_receipt_id": None,
        "reason": "  관리자가 공식 창구에 수동 제출함  ",
        "evidence_sha256": "a" * 64,
        "observed_at": "2026-08-09T03:00:00Z",
        "package_revision": 1,
        "expected_revision": 0,
        "idempotency_key": str(IDEMPOTENCY_KEY),
    }
    payload.update(overrides)
    return payload


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="reviewer@example.com",
        session_id=SESSION_ID,
        device_id="admin-device-0001",
        device_label="review tablet",
        expires_at=datetime(2026, 8, 9, 6, tzinfo=UTC),
        step_up_verified_at=datetime(2026, 8, 9, 3, tzinfo=UTC),
    )


def _evidence_grant(**overrides: object) -> ReportOriginalAccessGrant:
    values: dict[str, object] = {
        "id": EVIDENCE_GRANT_ID,
        "report_id": REPORT_ID,
        "admin_id": _identity().admin_id,
        "session_id": SESSION_ID,
        "device_id": _identity().device_id,
        "purpose": "report_review",
        "content_revision": 0,
        "issued_at": datetime(2026, 8, 9, 2, 58, tzinfo=UTC),
        "expires_at": datetime(2030, 8, 9, 3, 4, tzinfo=UTC),
        "location_disclosed_at": datetime(2026, 8, 9, 2, 58, tzinfo=UTC),
        "consumed_at": datetime(2026, 8, 9, 2, 59, tzinfo=UTC),
        "access_granted_at": datetime(2026, 8, 9, 2, 59, tzinfo=UTC),
        "review_decision_id": None,
        "review_bound_at": None,
    }
    values.update(overrides)
    return ReportOriginalAccessGrant(**values)


def _proof(
    *,
    method: str = "POST",
    path: str = f"/reports/{REPORT_ID}/review-decisions",
    action: str | None = "report.review.decide",
    read_purpose: str | None = None,
) -> VerifiedAdminDeviceProof:
    identity = _identity()
    return VerifiedAdminDeviceProof(
        admin_id=identity.admin_id,
        device_id=identity.device_id,
        session_id=identity.session_id,
        challenge_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        correlation_id=CORRELATION_ID,
        action=action,
        purpose="ACTION",
        read_purpose=read_purpose,
        method=method,
        path=path,
        body_sha256="0" * 64,
        query_sha256="0" * 64,
        device_key_marker="1" * 64,
        device_key_version=1,
    )


def _request(
    method: str,
    path: str,
    *,
    identity: object | None = None,
    proof: object | None = None,
    reconfirm_nonce: str | None = None,
) -> Request:
    headers = []
    if reconfirm_nonce is not None:
        headers.append(
            (b"x-walksafe-reconfirm-nonce", reconfirm_nonce.encode("ascii"))
        )
    request = Request(
        {
            "type": "http",
            "http_version": "1.1",
            "method": method,
            "scheme": "https",
            "path": path,
            "raw_path": path.encode("ascii"),
            "query_string": b"",
            "headers": headers,
            "client": ("127.0.0.1", 12345),
            "server": ("testserver", 443),
            "state": {},
        }
    )
    if identity is not None:
        request.state.admin_security_identity = identity
    if proof is not None:
        request.state.admin_device_proof = proof
    return request


class _ScalarResult:
    def __init__(self, value: object | None) -> None:
        self.value = value

    def scalar_one_or_none(self) -> object | None:
        return self.value

    def scalar_one(self) -> object:
        return self.value


class _FakeSession:
    def __init__(
        self,
        execute_results: list[object | None],
        *,
        get_results: dict[tuple[type[object], uuid.UUID], object | None] | None = None,
        evidence_audit_count: int = 2,
    ) -> None:
        self.execute_results = list(execute_results)
        self.get_results = get_results or {}
        self.evidence_audit_count = evidence_audit_count
        self.added: list[object] = []
        self.commit_count = 0
        self.rollback_count = 0
        self.expunge_count = 0

    def execute(self, _statement: object) -> _ScalarResult:
        if "report_original_access_audits" in str(_statement):
            return _ScalarResult(self.evidence_audit_count)
        assert self.execute_results, "unexpected database execute"
        return _ScalarResult(self.execute_results.pop(0))

    def get(
        self,
        model: type[object],
        key: uuid.UUID,
        **_kwargs: object,
    ) -> object | None:
        lookup = (model, key)
        if lookup in self.get_results:
            return self.get_results[lookup]
        if model is ReportOriginalAccessGrant and key == EVIDENCE_GRANT_ID:
            return _evidence_grant()
        return None

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commit_count += 1

    def rollback(self) -> None:
        self.rollback_count += 1

    def refresh(self, value: object) -> None:
        if getattr(value, "id", None) is None:
            setattr(value, "id", uuid.UUID("66666666-6666-4666-8666-666666666666"))
        if isinstance(value, ReportInstitutionDeliveryEvent) and getattr(value, "recorded_at", None) is None:
            setattr(value, "recorded_at", datetime(2026, 8, 9, 3, 1, tzinfo=UTC))
        elif getattr(value, "created_at", None) is None:
            setattr(value, "created_at", datetime(2026, 8, 9, 3, 1, tzinfo=UTC))

    def expunge(self, _value: object) -> None:
        self.expunge_count += 1


class _UnavailableSession(_FakeSession):
    def execute(self, _statement: object) -> _ScalarResult:
        raise SQLAlchemyError("simulated workflow store failure")


class _OperationFailureSession(_FakeSession):
    def __init__(
        self,
        execute_results: list[object | None],
        *,
        fail_operation: str | None = None,
        fail_execute_call: int = 1,
        rollback_fails: bool = False,
    ) -> None:
        super().__init__(execute_results)
        self.fail_operation = fail_operation
        self.fail_execute_call = fail_execute_call
        self.rollback_fails = rollback_fails
        self.execute_count = 0

    def execute(self, statement: object) -> _ScalarResult:
        self.execute_count += 1
        if (
            self.fail_operation == "execute"
            and self.execute_count == self.fail_execute_call
        ):
            raise SQLAlchemyError("sensitive workflow store detail")
        return super().execute(statement)

    def add(self, value: object) -> None:
        if self.fail_operation == "add":
            raise SQLAlchemyError("sensitive workflow store detail")
        super().add(value)

    def commit(self) -> None:
        self.commit_count += 1
        if self.fail_operation == "integrity_commit":
            raise IntegrityError(
                "sensitive statement",
                {"sensitive": "parameter"},
                RuntimeError("sensitive workflow store detail"),
            )
        if self.fail_operation == "commit":
            raise SQLAlchemyError("sensitive workflow store detail")

    def rollback(self) -> None:
        self.rollback_count += 1
        if self.rollback_fails:
            raise SQLAlchemyError("sensitive rollback detail")

    def refresh(self, value: object) -> None:
        if self.fail_operation == "refresh":
            raise SQLAlchemyError("sensitive workflow store detail")
        super().refresh(value)

    def expunge(self, value: object) -> None:
        self.expunge_count += 1
        if self.fail_operation == "expunge":
            raise SQLAlchemyError("sensitive workflow store detail")


def test_workflow_request_contracts_have_exact_fields_and_normalize_bounded_text() -> None:
    assert set(ReportReviewDecisionRequest.model_fields) == {
        "decision",
        "reason",
        "user_visible_reason",
        "duplicate_of_report_id",
        "location_reviewed",
        "photo_reviewed",
        "privacy_reviewed",
        "content_revision",
        "evidence_grant_id",
    }
    assert set(ReportInstitutionDeliveryRequest.model_fields) == {
        "institution",
        "channel",
        "recipient",
        "status",
        "external_receipt_id",
        "reason",
        "evidence_sha256",
        "observed_at",
        "package_revision",
        "expected_revision",
        "idempotency_key",
    }
    assert "recorded_at" in ReportInstitutionDeliveryResponse.model_fields
    assert "created_at" not in ReportInstitutionDeliveryResponse.model_fields

    review = ReportReviewDecisionRequest.model_validate(_approved_request())
    delivery = ReportInstitutionDeliveryRequest.model_validate(_delivery_request())

    assert review.reason == "확인 완료"
    assert review.user_visible_reason is None
    assert delivery.institution == "서울시청"
    assert delivery.channel == "WEB_PORTAL"
    assert delivery.recipient == "safety-desk"
    assert delivery.reason == "관리자가 공식 창구에 수동 제출함"
    assert delivery.observed_at.tzinfo is UTC
    assert delivery.idempotency_key == IDEMPOTENCY_KEY


@pytest.mark.parametrize(
    "observed_at",
    [
        0,
        1.5,
        True,
        None,
        b"2026-08-09T03:00:00Z",
        datetime(2026, 8, 9, 3, tzinfo=UTC),
    ],
)
def test_delivery_observed_at_rejects_non_text_before_datetime_coercion(
    observed_at: object,
) -> None:
    with pytest.raises(ValidationError, match="observed_at must be RFC3339 UTC text"):
        ReportInstitutionDeliveryRequest.model_validate(
            _delivery_request(observed_at=observed_at)
        )


@pytest.mark.parametrize(
    "observed_at",
    [
        "2026-08-09T03:00:00Z",
        "2026-08-09T03:00:00+00:00",
        "2026-08-09T03:00:00.123456Z",
    ],
)
def test_delivery_observed_at_accepts_exact_rfc3339_utc_text(observed_at: str) -> None:
    delivery = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(observed_at=observed_at)
    )

    assert delivery.observed_at.utcoffset() is not None
    assert delivery.observed_at.utcoffset().total_seconds() == 0


def test_workflow_database_failure_is_normalized_to_structured_503() -> None:
    db = _UnavailableSession([])

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "admin_report_workflow_unavailable"
    assert captured.value.status_code == 503
    assert db.rollback_count == 1


@pytest.mark.parametrize(
    ("fail_operation", "fail_execute_call"),
    [
        ("execute", 2),
        ("add", 1),
        ("commit", 1),
        ("refresh", 1),
    ],
)
def test_review_store_failures_after_lock_are_normalized_and_rolled_back(
    fail_operation: str,
    fail_execute_call: int,
) -> None:
    db = _OperationFailureSession(
        [Report(id=REPORT_ID), None],
        fail_operation=fail_operation,
        fail_execute_call=fail_execute_call,
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "admin_report_workflow_unavailable"
    assert captured.value.status_code == 503
    assert captured.value.message == (
        "The administrator report workflow is temporarily unavailable."
    )
    assert "sensitive" not in str(captured.value)
    assert db.rollback_count == 1


@pytest.mark.parametrize(
    "fail_operation",
    ["execute", "commit"],
)
def test_primary_store_and_rollback_failures_still_return_structured_503(
    fail_operation: str,
) -> None:
    db = _OperationFailureSession(
        [Report(id=REPORT_ID), None],
        fail_operation=fail_operation,
        rollback_fails=True,
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "admin_report_workflow_unavailable"
    assert captured.value.status_code == 503
    assert "sensitive" not in str(captured.value)
    assert db.rollback_count == 1


def test_business_rejection_rollback_failure_becomes_structured_503() -> None:
    db = _OperationFailureSession(
        [Report(id=REPORT_ID)],
        rollback_fails=True,
    )
    payload = ReportReviewDecisionRequest.model_validate(
        _approved_request(
            decision="DUPLICATE",
            duplicate_of_report_id=str(REPORT_ID),
            location_reviewed=False,
            photo_reviewed=False,
            privacy_reviewed=False,
        )
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "admin_report_workflow_unavailable"
    assert captured.value.status_code == 503
    assert "sensitive" not in str(captured.value)
    assert db.rollback_count == 1


@pytest.mark.parametrize(
    ("rollback_fails", "expected_code", "expected_status"),
    [
        (False, "review_decision_conflict", 409),
        (True, "admin_report_workflow_unavailable", 503),
    ],
)
def test_integrity_conflict_preserves_409_unless_rollback_fails(
    rollback_fails: bool,
    expected_code: str,
    expected_status: int,
) -> None:
    db = _OperationFailureSession(
        [Report(id=REPORT_ID), None],
        fail_operation="integrity_commit",
        rollback_fails=rollback_fails,
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == expected_code
    assert captured.value.status_code == expected_status
    assert "sensitive" not in str(captured.value)
    assert db.commit_count == 1
    assert db.rollback_count == 1


@pytest.mark.parametrize(
    "payload",
    [
        _approved_request(location_reviewed=False),
        _approved_request(duplicate_of_report_id=str(REPORT_ID)),
        _approved_request(decision="DUPLICATE", duplicate_of_report_id=None),
        _approved_request(decision="REJECTED", duplicate_of_report_id=str(REPORT_ID)),
        _approved_request(location_reviewed="true"),
        _approved_request(server_revision=1),
    ],
)
def test_review_request_rejects_incomplete_or_server_owned_input(payload: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        ReportReviewDecisionRequest.model_validate(payload)


def test_review_request_requires_evidence_only_for_approval() -> None:
    with pytest.raises(ValidationError, match="evidence_grant_id"):
        ReportReviewDecisionRequest.model_validate(
            _approved_request(evidence_grant_id=None)
        )
    rejected = _approved_request(decision="REJECTED")
    rejected["evidence_grant_id"] = str(EVIDENCE_GRANT_ID)
    with pytest.raises(ValidationError, match="evidence_grant_id"):
        ReportReviewDecisionRequest.model_validate(rejected)


@pytest.mark.parametrize(
    "payload",
    [
        _delivery_request(observed_at="2026-08-09T12:00:00+09:00"),
        _delivery_request(observed_at="2026-08-09 03:00:00Z"),
        _delivery_request(evidence_sha256="A" * 64),
        _delivery_request(expected_revision="0"),
        _delivery_request(idempotency_key="AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
        _delivery_request(status="ACKNOWLEDGED", external_receipt_id=None),
        _delivery_request(correlation_id=str(uuid.uuid4())),
    ],
)
def test_delivery_request_rejects_noncanonical_or_server_owned_input(payload: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        ReportInstitutionDeliveryRequest.model_validate(payload)


@pytest.mark.parametrize(
    ("method", "suffix", "action", "read_purpose"),
    [
        ("POST", "review-decisions", "report.review.decide", None),
        ("POST", "original-access-grants", "report.original.grant", None),
        ("GET", "review-decisions", None, "report.review_decisions"),
        ("POST", "deliveries", "report.delivery.create", None),
        ("GET", "deliveries", None, "report.delivery_events"),
    ],
)
def test_workflow_context_accepts_only_route_bound_verified_proof(
    method: str,
    suffix: str,
    action: str | None,
    read_purpose: str | None,
) -> None:
    path = f"/reports/{REPORT_ID}/{suffix}"
    identity = _identity()
    proof = _proof(
        method=method,
        path=path,
        action=action,
        read_purpose=read_purpose,
    )
    request = _request(method, path, identity=identity, proof=proof)

    observed_identity, observed_proof = reports_api._require_admin_report_workflow_context(
        request,
        expected_action=action,
        expected_read_purpose=read_purpose,
    )

    assert observed_identity is identity
    assert observed_proof is proof


@pytest.mark.parametrize(
    "proof",
    [
        replace(_proof(), admin_id="other@example.com"),
        replace(_proof(), device_id="admin-device-9999"),
        replace(_proof(), session_id=uuid.UUID("77777777-7777-4777-8777-777777777777")),
        replace(_proof(), action="report.delivery.create"),
        replace(_proof(), purpose="LOGIN"),
        replace(_proof(), read_purpose="report.review_decisions"),
        replace(_proof(), method="GET"),
        replace(_proof(), path=f"/reports/{REPORT_ID}/deliveries"),
    ],
)
def test_workflow_context_rejects_identity_or_request_binding_mismatch(
    proof: VerifiedAdminDeviceProof,
) -> None:
    path = f"/reports/{REPORT_ID}/review-decisions"
    request = _request("POST", path, identity=_identity(), proof=proof)

    with pytest.raises(HTTPException) as captured:
        reports_api._require_admin_report_workflow_context(
            request,
            expected_action="report.review.decide",
            expected_read_purpose=None,
        )

    assert captured.value.status_code == 403
    assert captured.value.detail["code"] == "admin_device_proof_invalid"


def test_workflow_context_fails_closed_without_typed_session_or_proof() -> None:
    path = f"/reports/{REPORT_ID}/review-decisions"
    missing_session = _request("POST", path, proof=_proof())
    wrong_proof_type = _request("POST", path, identity=_identity(), proof=SimpleNamespace())

    with pytest.raises(HTTPException) as session_error:
        reports_api._require_admin_report_workflow_context(
            missing_session,
            expected_action="report.review.decide",
            expected_read_purpose=None,
        )
    with pytest.raises(HTTPException) as proof_error:
        reports_api._require_admin_report_workflow_context(
            wrong_proof_type,
            expected_action="report.review.decide",
            expected_read_purpose=None,
        )

    assert session_error.value.status_code == 401
    assert session_error.value.detail["code"] == "admin_session_required"
    assert proof_error.value.status_code == 403
    assert proof_error.value.detail["code"] == "admin_device_proof_required"


def test_report_workflow_routes_are_registered_before_generic_detail() -> None:
    router = reports_api.create_router(
        SimpleNamespace(
            admin_security_enabled=True,
            report_original_grant_ttl_seconds=120,
        ),
        SimpleNamespace(),
    )
    routes = {
        (route.path, method): route
        for route in router.routes
        for method in getattr(route, "methods", set())
    }
    expected = {
        (f"/reports/{{report_id}}/review-decisions", "POST"): 201,
        (f"/reports/{{report_id}}/review-decisions", "GET"): None,
        (f"/reports/{{report_id}}/deliveries", "POST"): 201,
        (f"/reports/{{report_id}}/deliveries", "GET"): None,
    }

    for key, status_code in expected.items():
        assert key in routes
        assert routes[key].status_code == status_code


def _route_endpoint(path: str, method: str) -> object:
    router = reports_api.create_router(
        SimpleNamespace(
            admin_security_enabled=True,
            report_original_grant_ttl_seconds=120,
        ),
        SimpleNamespace(),
    )
    return next(
        route.endpoint
        for route in router.routes
        if route.path == path and method in route.methods
    )


def test_post_endpoints_pass_verified_identity_and_correlation_to_services(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, dict[str, object]] = {}
    review_result = object()
    delivery_result = object()

    def append_review(_db: object, **kwargs: object) -> object:
        captured["review"] = kwargs
        return review_result

    def append_delivery(_db: object, **kwargs: object) -> object:
        captured["delivery"] = kwargs
        return delivery_result

    monkeypatch.setattr(reports_api, "append_report_review_decision", append_review)
    monkeypatch.setattr(reports_api, "append_report_institution_delivery_event", append_delivery)
    identity = _identity()
    review_path = f"/reports/{REPORT_ID}/review-decisions"
    delivery_path = f"/reports/{REPORT_ID}/deliveries"
    review_response = Response()
    delivery_response = Response()

    observed_review = _route_endpoint("/reports/{report_id}/review-decisions", "POST")(
        report_id=REPORT_ID,
        payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
        request=_request(
            "POST",
            review_path,
            identity=identity,
            proof=_proof(path=review_path),
        ),
        response=review_response,
        db=object(),
    )
    observed_delivery = _route_endpoint("/reports/{report_id}/deliveries", "POST")(
        report_id=REPORT_ID,
        payload=ReportInstitutionDeliveryRequest.model_validate(_delivery_request()),
        request=_request(
            "POST",
            delivery_path,
            identity=identity,
            proof=_proof(
                path=delivery_path,
                action="report.delivery.create",
            ),
        ),
        response=delivery_response,
        db=object(),
    )

    assert observed_review is review_result
    assert observed_delivery is delivery_result
    for operation in ("review", "delivery"):
        assert captured[operation]["report_id"] == REPORT_ID
        assert captured[operation]["identity"] is identity
        assert captured[operation]["correlation_id"] == CORRELATION_ID
    for response in (review_response, delivery_response):
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["pragma"] == "no-cache"


def test_original_grant_route_requires_device_proof_and_returns_v2_no_store(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    endpoint = _route_endpoint(
        "/reports/{report_id}/original-access-grants",
        "POST",
    )
    path = f"/reports/{REPORT_ID}/original-access-grants"
    identity = _identity()
    payload = ReportOriginalAccessGrantRequest(
        purpose="report_review",
        reason="Review the original report evidence.",
        expected_content_revision=4,
    )
    captured: dict[str, object] = {}

    monkeypatch.setattr(
        reports_api,
        "_reauthorize_admin_high_risk_in_transaction",
        lambda *_args, **_kwargs: identity,
    )

    def issue(_db: object, **kwargs: object) -> object:
        captured.update(kwargs)
        return SimpleNamespace(
            grant_id=EVIDENCE_GRANT_ID,
            content_revision=4,
            expires_at=datetime(2030, 8, 9, 3, 4, tzinfo=UTC),
            latitude=37.5665,
            longitude=126.978,
            accuracy_m=3.5,
            resource_path=f"/uploads/{REPORT_ID}.jpg",
            content_type="image/jpeg",
            image_sha256="a" * 64,
            image_byte_count=123,
            access_token="A" * 43,
        )

    monkeypatch.setattr(reports_api, "issue_report_original_access_grant", issue)
    with pytest.raises(HTTPException) as missing_proof:
        endpoint(
            report_id=REPORT_ID,
            payload=payload,
            request=_request("POST", path, identity=identity),
            response=Response(),
            db=object(),
        )
    assert missing_proof.value.detail["code"] == "admin_device_proof_required"

    response = Response()
    observed = endpoint(
        report_id=REPORT_ID,
        payload=payload,
        request=_request(
            "POST",
            path,
            identity=identity,
            proof=_proof(path=path, action="report.original.grant"),
            reconfirm_nonce="A" * 22,
        ),
        response=response,
        db=object(),
    )

    assert observed.schema_version == "walksafe.report-original-access-grant.v2"
    assert observed.content_revision == 4
    assert observed.exact_location.model_dump() == {
        "lat": 37.5665,
        "lon": 126.978,
        "accuracy": 3.5,
    }
    assert observed.image.access_token == "A" * 43
    assert captured["expected_content_revision"] == 4
    assert captured["identity"] is identity
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"


@pytest.mark.parametrize(
    ("status_code", "expected_outcome"),
    [(409, "DENIED"), (503, "ERROR")],
)
def test_workflow_failure_is_audited_and_returned_as_admin_alert(
    monkeypatch: pytest.MonkeyPatch,
    status_code: int,
    expected_outcome: str,
) -> None:
    audits: list[dict[str, object]] = []
    monkeypatch.setattr(
        reports_api,
        "record_admin_security_failure",
        lambda **kwargs: audits.append(kwargs),
    )
    identity = _identity()
    proof = _proof()
    request = _request(
        "POST",
        f"/reports/{REPORT_ID}/review-decisions",
        identity=identity,
        proof=proof,
    )
    error = AdminReportWorkflowError(
        "workflow_failed",
        "The administrator was notified.",
        status_code=status_code,
    )

    with pytest.raises(HTTPException) as captured:
        reports_api._raise_admin_report_workflow_error(
            error,
            request=request,
            identity=identity,
            proof=proof,
            audit_action="report.review.decide",
        )

    assert captured.value.status_code == status_code
    assert captured.value.detail["code"] == "workflow_failed"
    assert audits == [
        {
            "action": "report.review.decide",
            "reason": "workflow_failed",
            "outcome": expected_outcome,
            "method": "POST",
            "path": f"/reports/{REPORT_ID}/review-decisions",
            "admin_id": identity.admin_id,
            "session_id": identity.session_id,
            "device_id": identity.device_id,
            "correlation_id": proof.correlation_id,
        }
    ]


def test_store_failure_and_unavailable_audit_store_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def unavailable(**_kwargs: object) -> None:
        raise AdminSecurityStoreUnavailable()

    monkeypatch.setattr(
        reports_api,
        "record_admin_security_failure",
        unavailable,
    )
    identity = _identity()
    proof = _proof()
    request = _request(
        "POST",
        f"/reports/{REPORT_ID}/review-decisions",
        identity=identity,
        proof=proof,
    )
    db = _OperationFailureSession(
        [Report(id=REPORT_ID), None],
        fail_operation="commit",
        rollback_fails=True,
    )

    with pytest.raises(AdminReportWorkflowError) as workflow_failure:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=identity,
            correlation_id=CORRELATION_ID,
        )

    assert workflow_failure.value.code == "admin_report_workflow_unavailable"
    assert workflow_failure.value.status_code == 503
    assert db.rollback_count == 1

    with pytest.raises(HTTPException) as captured:
        reports_api._raise_admin_report_workflow_error(
            workflow_failure.value,
            request=request,
            identity=identity,
            proof=proof,
            audit_action="report.review.decide",
        )

    assert captured.value.status_code == 503
    assert captured.value.detail["code"] == "admin_security_store_unavailable"


def test_get_endpoints_require_read_bound_proof_and_call_history_services(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, object, uuid.UUID]] = []
    audits: list[dict[str, object]] = []

    def list_reviews(db: object, *, report_id: uuid.UUID) -> list[object]:
        calls.append(("review", db, report_id))
        return []

    def list_deliveries(db: object, *, report_id: uuid.UUID) -> list[object]:
        calls.append(("delivery", db, report_id))
        return []

    monkeypatch.setattr(reports_api, "list_report_review_decisions", list_reviews)
    monkeypatch.setattr(reports_api, "list_report_institution_delivery_events", list_deliveries)
    monkeypatch.setattr(
        reports_api,
        "persist_report_read_audit",
        lambda _db, **kwargs: audits.append(kwargs),
    )
    identity = _identity()
    db = object()
    review_path = f"/reports/{REPORT_ID}/review-decisions"
    delivery_path = f"/reports/{REPORT_ID}/deliveries"

    assert _route_endpoint("/reports/{report_id}/review-decisions", "GET")(
        report_id=REPORT_ID,
        request=_request(
            "GET",
            review_path,
            identity=identity,
            proof=_proof(
                method="GET",
                path=review_path,
                action=None,
                read_purpose="report.review_decisions",
            ),
        ),
        response=Response(),
        db=db,
    ) == []
    assert _route_endpoint("/reports/{report_id}/deliveries", "GET")(
        report_id=REPORT_ID,
        request=_request(
            "GET",
            delivery_path,
            identity=identity,
            proof=_proof(
                method="GET",
                path=delivery_path,
                action=None,
                read_purpose="report.delivery_events",
            ),
        ),
        response=Response(),
        db=db,
    ) == []

    assert calls == [("review", db, REPORT_ID), ("delivery", db, REPORT_ID)]
    assert [audit["purpose"] for audit in audits] == [
        "report.review_decisions",
        "report.delivery_events",
    ]
    assert all(audit["actor_id"] == identity.admin_id for audit in audits)
    assert all(audit["resource_id"] == str(REPORT_ID) for audit in audits)
    assert all(audit["details"]["correlation_id"] == str(CORRELATION_ID) for audit in audits)


def test_review_append_is_monotonic_and_does_not_change_legacy_authority() -> None:
    report = Report(
        id=REPORT_ID,
        status="reviewed",
        payload={"agency_review_verified": True},
    )
    previous = ReportReviewDecision(revision=2)
    db = _FakeSession([report, previous])
    decided_at = datetime(2026, 8, 9, 3, 2, tzinfo=UTC)

    decision = append_report_review_decision(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        now=decided_at,
    )

    assert decision.revision == 3
    assert decision.admin_id == _identity().admin_id
    assert decision.session_id == SESSION_ID
    assert decision.device_id == _identity().device_id
    assert decision.correlation_id == CORRELATION_ID
    assert decision.decided_at == decided_at
    assert decision.evidence_grant_id == EVIDENCE_GRANT_ID
    assert report.status == "reviewed"
    assert report.payload == {"agency_review_verified": True}
    assert db.added == [decision]
    assert db.commit_count == 1
    assert db.rollback_count == 0


def test_approval_atomically_binds_one_fresh_consumed_evidence_grant() -> None:
    evidence = _evidence_grant()
    db = _FakeSession(
        [Report(id=REPORT_ID), None],
        get_results={(ReportOriginalAccessGrant, EVIDENCE_GRANT_ID): evidence},
    )
    decided_at = datetime(2026, 8, 9, 3, 2, tzinfo=UTC)

    decision = append_report_review_decision(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        now=decided_at,
    )

    assert decision.evidence_grant_id == evidence.id
    assert evidence.review_decision_id == decision.id
    assert evidence.review_bound_at == decided_at
    assert db.commit_count == 1

    replay_db = _FakeSession(
        [Report(id=REPORT_ID)],
        get_results={(ReportOriginalAccessGrant, EVIDENCE_GRANT_ID): evidence},
    )
    with pytest.raises(AdminReportWorkflowError) as replayed:
        append_report_review_decision(
            replay_db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            now=decided_at,
        )
    assert replayed.value.code == "review_evidence_grant_invalid"
    assert replay_db.rollback_count == 1


def test_approval_fails_closed_without_both_committed_evidence_audits() -> None:
    evidence = _evidence_grant()
    db = _FakeSession(
        [Report(id=REPORT_ID)],
        get_results={(ReportOriginalAccessGrant, EVIDENCE_GRANT_ID): evidence},
        evidence_audit_count=1,
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            now=datetime(2026, 8, 9, 3, 2, tzinfo=UTC),
        )

    assert captured.value.code == "review_evidence_audit_required"
    assert captured.value.status_code == 409
    assert db.rollback_count == 1
    assert evidence.review_decision_id is None


@pytest.mark.parametrize(
    "overrides",
    [
        {"access_granted_at": None},
        {"consumed_at": None},
        {"location_disclosed_at": None, "content_revision": None},
        {"report_id": uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")},
        {"admin_id": "other@example.com"},
        {"purpose": "security_incident"},
        {"content_revision": 1},
        {"session_id": uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")},
        {"device_id": "admin-device-other"},
        {"expires_at": datetime(2026, 8, 9, 3, 2, tzinfo=UTC)},
    ],
)
def test_approval_rejects_unverified_or_mismatched_evidence_grant(
    overrides: dict[str, object],
) -> None:
    evidence = _evidence_grant(**overrides)
    db = _FakeSession(
        [Report(id=REPORT_ID)],
        get_results={(ReportOriginalAccessGrant, EVIDENCE_GRANT_ID): evidence},
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportReviewDecisionRequest.model_validate(_approved_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            now=datetime(2026, 8, 9, 3, 2, tzinfo=UTC),
        )

    assert captured.value.code == "review_evidence_grant_invalid"
    assert captured.value.status_code == 409
    assert db.rollback_count == 1
    assert db.added == []


@pytest.mark.parametrize("missing_target", [False, True])
def test_duplicate_decision_rejects_self_or_missing_target(missing_target: bool) -> None:
    target_id = uuid.UUID("88888888-8888-4888-8888-888888888888") if missing_target else REPORT_ID
    report = Report(id=REPORT_ID)
    db = _FakeSession([report])
    payload = ReportReviewDecisionRequest.model_validate(
        _approved_request(
            decision="DUPLICATE",
            duplicate_of_report_id=str(target_id),
            location_reviewed=False,
            photo_reviewed=False,
            privacy_reviewed=False,
        )
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == (
        "duplicate_report_not_found" if missing_target else "duplicate_report_self_reference"
    )
    assert captured.value.status_code == 422
    assert db.rollback_count == 1
    assert db.added == []


def _typed_decision(decision: str = "APPROVED") -> ReportReviewDecision:
    return ReportReviewDecision(
        id=DECISION_ID,
        report_id=REPORT_ID,
        revision=1,
        decision=decision,
        reason="검수 결과",
        duplicate_of_report_id=None,
        evidence_grant_id=EVIDENCE_GRANT_ID if decision == "APPROVED" else None,
        location_reviewed=decision == "APPROVED",
        photo_reviewed=decision == "APPROVED",
        privacy_reviewed=decision == "APPROVED",
    )


def _package() -> ReportDeliveryPackage:
    return ReportDeliveryPackage(
        id=PACKAGE_ID,
        report_id=REPORT_ID,
        review_decision_id=DECISION_ID,
        revision=1,
        content_revision=0,
        package_version=2,
    )


def _corrected_delivery_state(
    *, previous_content_revision: int = 0
) -> tuple[
    Report,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
    ReportDeliveryPackage,
    ReportDeliveryPackage,
]:
    report = Report(id=REPORT_ID, content_revision=1)
    previous = ReportInstitutionDeliveryEvent(
        report_id=REPORT_ID,
        revision=1,
        status="SUBMITTED",
        package_id=PREVIOUS_PACKAGE_ID,
        package_revision=1,
    )
    approval = ReportReviewDecision(
        id=DECISION_ID,
        report_id=REPORT_ID,
        revision=2,
        content_revision=1,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=EVIDENCE_GRANT_ID,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    package = ReportDeliveryPackage(
        id=PACKAGE_ID,
        report_id=REPORT_ID,
        review_decision_id=DECISION_ID,
        revision=2,
        content_revision=1,
        package_version=2,
        supersedes_package_id=PREVIOUS_PACKAGE_ID,
    )
    previous_package = ReportDeliveryPackage(
        id=PREVIOUS_PACKAGE_ID,
        report_id=REPORT_ID,
        revision=1,
        content_revision=previous_content_revision,
        package_version=2,
    )
    return report, previous, approval, package, previous_package


@pytest.mark.parametrize(
    ("fail_operation", "fail_execute_call"),
    [
        ("execute", 2),
        ("execute", 3),
        ("execute", 4),
        ("add", 1),
        ("commit", 1),
        ("refresh", 1),
    ],
)
def test_delivery_store_failures_are_normalized_and_rolled_back(
    fail_operation: str,
    fail_execute_call: int,
) -> None:
    db = _OperationFailureSession(
        [Report(id=REPORT_ID), None, None, _typed_decision(), _package()],
        fail_operation=fail_operation,
        fail_execute_call=fail_execute_call,
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportInstitutionDeliveryRequest.model_validate(_delivery_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "admin_report_workflow_unavailable"
    assert captured.value.status_code == 503
    assert captured.value.message == (
        "The administrator report workflow is temporarily unavailable."
    )
    assert "sensitive" not in str(captured.value)
    assert db.rollback_count == 1


def test_delivery_append_uses_exact_transition_and_latest_typed_approval() -> None:
    report = Report(
        id=REPORT_ID,
        status="new",
        payload={"agency_review_verified": False},
    )
    previous = ReportInstitutionDeliveryEvent(
        report_id=REPORT_ID,
        revision=1,
        status="SUBMITTED",
        package_id=PACKAGE_ID,
        package_revision=1,
    )
    approval = _typed_decision()
    db = _FakeSession([report, None, previous, approval, _package()])
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            status="ACKNOWLEDGED",
            external_receipt_id="receipt-001",
            expected_revision=1,
        )
    )

    event = append_report_institution_delivery_event(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=payload,
        identity=_identity(),
        correlation_id=CORRELATION_ID,
    )

    assert event.revision == 2
    assert event.expected_revision == 1
    assert event.status == "ACKNOWLEDGED"
    assert event.review_decision_id == DECISION_ID
    assert event.admin_id == _identity().admin_id
    assert event.correlation_id == CORRELATION_ID
    assert report.status == "new"
    assert report.payload == {"agency_review_verified": False}
    assert db.added == [event]
    assert db.commit_count == 1


def test_delivery_new_content_package_starts_a_new_manual_delivery_cycle() -> None:
    report, previous, approval, package, previous_package = _corrected_delivery_state()
    db = _FakeSession([report, None, previous, approval, package, previous_package])
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            package_revision=2,
            expected_revision=1,
            idempotency_key="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        )
    )

    event = append_report_institution_delivery_event(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=payload,
        identity=_identity(),
        correlation_id=CORRELATION_ID,
    )

    assert event.revision == 2
    assert event.expected_revision == 1
    assert event.status == "SUBMITTED"
    assert event.package_id == PACKAGE_ID
    assert event.package_revision == 2
    assert event.review_decision_id == DECISION_ID
    assert db.added == [event]
    assert db.commit_count == 1


def test_delivery_new_content_package_keeps_global_revision_cas() -> None:
    report, previous, _, _, _ = _corrected_delivery_state()
    db = _FakeSession([report, None, previous])
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            package_revision=2,
            expected_revision=0,
            idempotency_key="cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        )
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "delivery_revision_conflict"
    assert db.rollback_count == 1


def test_delivery_rejects_switching_packages_for_the_same_content_revision() -> None:
    report, previous, approval, package, previous_package = _corrected_delivery_state(
        previous_content_revision=1
    )
    db = _FakeSession([report, None, previous, approval, package, previous_package])
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            package_revision=2,
            expected_revision=1,
            idempotency_key="dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        )
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "delivery_package_revision_conflict"
    assert db.rollback_count == 1
    assert db.added == []


def test_delivery_rejects_reusing_a_legacy_v1_package() -> None:
    report = Report(id=REPORT_ID, content_revision=0)
    approval = _typed_decision()
    legacy = ReportDeliveryPackage(
        id=PACKAGE_ID,
        report_id=REPORT_ID,
        review_decision_id=DECISION_ID,
        revision=1,
        content_revision=0,
        package_version=1,
    )
    db = _FakeSession([report, None, None, approval, legacy])

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportInstitutionDeliveryRequest.model_validate(_delivery_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "delivery_package_invalid"
    assert captured.value.status_code == 409
    assert db.rollback_count == 1
    assert db.added == []


def test_delivery_allows_explicit_legacy_v1_to_v2_switch_for_same_content() -> None:
    report = Report(id=REPORT_ID, content_revision=0)
    previous = ReportInstitutionDeliveryEvent(
        report_id=REPORT_ID,
        revision=1,
        status="FAILED",
        package_id=PREVIOUS_PACKAGE_ID,
        package_revision=1,
    )
    approval = _typed_decision()
    package = _package()
    legacy = ReportDeliveryPackage(
        id=PREVIOUS_PACKAGE_ID,
        report_id=REPORT_ID,
        review_decision_id=DECISION_ID,
        revision=1,
        content_revision=0,
        package_version=1,
    )
    db = _FakeSession([report, None, previous, approval, package, legacy])
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            expected_revision=1,
            idempotency_key="abababab-abab-4bab-8bab-abababababab",
        )
    )

    event = append_report_institution_delivery_event(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=payload,
        identity=_identity(),
        correlation_id=CORRELATION_ID,
    )

    assert event.status == "SUBMITTED"
    assert event.revision == 2
    assert event.package_id == PACKAGE_ID
    assert db.commit_count == 1


def test_delivery_new_content_package_must_start_with_an_initial_status() -> None:
    report, previous, approval, package, previous_package = _corrected_delivery_state()
    db = _FakeSession([report, None, previous, approval, package, previous_package])
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            status="ACKNOWLEDGED",
            external_receipt_id="receipt-002",
            package_revision=2,
            expected_revision=1,
            idempotency_key="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
        )
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "delivery_transition_invalid"
    assert db.rollback_count == 1
    assert db.added == []


def test_delivery_previous_package_lookup_failure_is_normalized() -> None:
    report, previous, approval, package, previous_package = _corrected_delivery_state()
    db = _OperationFailureSession(
        [report, None, previous, approval, package, previous_package],
        fail_operation="execute",
        fail_execute_call=6,
    )
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            package_revision=2,
            expected_revision=1,
            idempotency_key="ffffffff-ffff-4fff-8fff-ffffffffffff",
        )
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "admin_report_workflow_unavailable"
    assert captured.value.status_code == 503
    assert "sensitive" not in str(captured.value)
    assert db.rollback_count == 1
    assert db.added == []


def test_delivery_rechecks_latest_typed_decision_and_ignores_legacy_flag() -> None:
    report = Report(
        id=REPORT_ID,
        status="reviewed",
        payload={"agency_review_verified": True},
    )
    db = _FakeSession([report, None, None, _typed_decision("REJECTED")])

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=ReportInstitutionDeliveryRequest.model_validate(_delivery_request()),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "latest_review_approval_required"
    assert captured.value.status_code == 409
    assert db.rollback_count == 1
    assert db.added == []


def test_delivery_retry_returns_original_before_revision_and_approval_checks() -> None:
    payload = ReportInstitutionDeliveryRequest.model_validate(_delivery_request())
    existing = ReportInstitutionDeliveryEvent(
        report_id=REPORT_ID,
        institution=payload.institution,
        channel=payload.channel,
        recipient=payload.recipient,
        status=payload.status,
        external_receipt_id=payload.external_receipt_id,
        reason=payload.reason,
        evidence_sha256=payload.evidence_sha256,
        package_revision=payload.package_revision,
        observed_at=payload.observed_at,
        expected_revision=payload.expected_revision,
        idempotency_key=payload.idempotency_key,
    )
    db = _FakeSession([Report(id=REPORT_ID), existing])

    observed = append_report_institution_delivery_event(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=payload,
        identity=_identity(),
        correlation_id=CORRELATION_ID,
    )

    assert observed is existing
    assert db.execute_results == []
    assert db.rollback_count == 1
    assert db.expunge_count == 1
    assert db.added == []


def test_delivery_retry_expunge_failure_is_normalized_and_rolled_back() -> None:
    payload = ReportInstitutionDeliveryRequest.model_validate(_delivery_request())
    existing = ReportInstitutionDeliveryEvent(
        report_id=REPORT_ID,
        institution=payload.institution,
        channel=payload.channel,
        recipient=payload.recipient,
        status=payload.status,
        external_receipt_id=payload.external_receipt_id,
        reason=payload.reason,
        evidence_sha256=payload.evidence_sha256,
        package_revision=payload.package_revision,
        observed_at=payload.observed_at,
        expected_revision=payload.expected_revision,
        idempotency_key=payload.idempotency_key,
    )
    db = _OperationFailureSession(
        [Report(id=REPORT_ID), existing],
        fail_operation="expunge",
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "admin_report_workflow_unavailable"
    assert captured.value.status_code == 503
    assert "sensitive" not in str(captured.value)
    assert db.expunge_count == 1
    assert db.rollback_count == 1


def test_delivery_retry_rejects_same_key_with_different_intent() -> None:
    payload = ReportInstitutionDeliveryRequest.model_validate(_delivery_request())
    existing = ReportInstitutionDeliveryEvent(
        report_id=REPORT_ID,
        institution=payload.institution,
        channel=payload.channel,
        recipient=payload.recipient,
        status=payload.status,
        external_receipt_id=payload.external_receipt_id,
        reason="다른 제출 사유",
        evidence_sha256=payload.evidence_sha256,
        package_revision=payload.package_revision,
        observed_at=payload.observed_at,
        expected_revision=payload.expected_revision,
        idempotency_key=payload.idempotency_key,
    )
    db = _FakeSession([Report(id=REPORT_ID), existing])

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == "delivery_idempotency_conflict"
    assert captured.value.status_code == 409
    assert db.rollback_count == 1


@pytest.mark.parametrize(
    ("previous_status", "previous_revision", "next_status", "expected_revision", "error_code"),
    [
        ("SUBMITTED", 1, "ACKNOWLEDGED", 0, "delivery_revision_conflict"),
        ("SUBMITTED", 1, "RESOLVED", 1, "delivery_transition_invalid"),
        ("RESOLVED", 3, "FAILED", 3, "delivery_transition_invalid"),
    ],
)
def test_delivery_rejects_stale_revision_or_invalid_transition(
    previous_status: str,
    previous_revision: int,
    next_status: str,
    expected_revision: int,
    error_code: str,
) -> None:
    receipt = "receipt-001" if next_status in {"ACKNOWLEDGED", "RESOLVED"} else None
    payload = ReportInstitutionDeliveryRequest.model_validate(
        _delivery_request(
            status=next_status,
            external_receipt_id=receipt,
            expected_revision=expected_revision,
        )
    )
    previous = ReportInstitutionDeliveryEvent(
        report_id=REPORT_ID,
        revision=previous_revision,
        status=previous_status,
        package_id=PACKAGE_ID,
        package_revision=1,
    )
    db = _FakeSession(
        [Report(id=REPORT_ID), None, previous, _typed_decision(), _package()]
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        append_report_institution_delivery_event(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=payload,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
        )

    assert captured.value.code == error_code
    assert captured.value.status_code == 409
    assert db.rollback_count == 1
