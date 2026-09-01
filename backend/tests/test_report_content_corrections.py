from __future__ import annotations

from datetime import UTC, datetime
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from pydantic import ValidationError

from backend.app.field_test_security import (
    FieldTestAccess,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
)
from backend.app.models import (
    Report,
    ReportContentRevision,
    ReportDeliveryPackage,
    ReportOriginalAccessGrant,
)
from backend.app.request_limits import (
    REPORT_CORRECTION_BODY_LIMIT_BYTES,
    max_request_body_bytes,
)
from backend.app.schemas import (
    ReportContentCorrectionRequestV1,
    ReportReviewDecisionRequest,
)
from backend.app.services.admin_report_delivery_package import (
    DELIVERY_PACKAGE_SCHEMA_VERSION,
    DELIVERY_PACKAGE_SCHEMA_VERSION_V2,
    build_admin_report_delivery_package,
    build_admin_report_delivery_package_v2,
    create_admin_report_delivery_package,
)
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    append_report_review_decision,
)
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_content_corrections import (
    ReportContentCorrectionError,
    ReportContentState,
    apply_owned_report_correction,
    correction_intent_sha256,
    get_owned_report_content,
    report_content_sha256,
)
from backend.app.services.report_user_requests import ReportUserRequestError


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
KEY = uuid.UUID("22222222-2222-4222-8222-222222222222")
SUBJECT = "a" * 64
NOW = datetime(2026, 8, 29, 8, tzinfo=UTC)
ADMIN_SESSION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
EVIDENCE_GRANT_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")


def _payload(**overrides: object) -> ReportContentCorrectionRequestV1:
    values: dict[str, object] = {
        "expected_revision": 0,
        "idempotency_key": str(KEY),
        "user_description": "  점자블록   옆에 장애물이 있습니다  ",
        "category_hint": "ACCESSIBILITY_BARRIER",
    }
    values.update(overrides)
    return ReportContentCorrectionRequestV1.model_validate(values)


def test_structured_patch_rejects_original_fields_unknown_category_and_controls() -> None:
    assert _payload().user_description == "점자블록 옆에 장애물이 있습니다"
    for patch in (
        {"class_name": "pothole"},
        {"latitude": 37.5},
        {"image_path": "/changed.jpg"},
        {"category_hint": "POTHOLE"},
        {"user_description": "줄바꿈\n금지"},
    ):
        values = _payload().model_dump(mode="json")
        values.update(patch)
        with pytest.raises(ValidationError):
            ReportContentCorrectionRequestV1.model_validate(values)
    with pytest.raises(ValidationError):
        ReportContentCorrectionRequestV1.model_validate(
            {"expected_revision": 0, "idempotency_key": str(KEY)}
        )


def test_explicit_null_is_a_clear_but_omission_is_not_part_of_intent() -> None:
    clear = ReportContentCorrectionRequestV1.model_validate(
        {
            "expected_revision": 1,
            "idempotency_key": str(KEY),
            "user_description": None,
        }
    )
    assert clear.model_fields_set == {
        "expected_revision",
        "idempotency_key",
        "user_description",
    }
    category_only = ReportContentCorrectionRequestV1.model_validate(
        {
            "expected_revision": 1,
            "idempotency_key": str(KEY),
            "category_hint": "OTHER",
        }
    )
    assert correction_intent_sha256(REPORT_ID, clear) != correction_intent_sha256(
        REPORT_ID, category_only
    )


class _Result:
    def __init__(self, value: object | None) -> None:
        self.value = value

    def scalar_one_or_none(self) -> object | None:
        return self.value

    def scalar_one(self) -> object:
        return self.value


class _Session:
    def __init__(self, results: list[object | None]) -> None:
        self.results = results
        self.added: list[object] = []
        self.commits = 0
        self.rollbacks = 0

    def execute(self, _statement: object, _params: object | None = None) -> _Result:
        if "report_original_access_audits" in str(_statement):
            return _Result(2)
        return _Result(self.results.pop(0))

    def scalar(self, _statement: object) -> None:
        return None

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1


class _TombstonedSession(_Session):
    def scalar(self, _statement: object) -> uuid.UUID:
        return uuid.uuid4()


def _report(*, revision: int = 0) -> Report:
    return Report(
        id=REPORT_ID,
        content_revision=revision,
        privacy_subject_hmac=SUBJECT,
        account_generation=3,
    )


def test_apply_is_cas_idempotent_and_only_updates_content_projection() -> None:
    report = _report()
    db = _Session([None, report, None])
    state, created = apply_owned_report_correction(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=_payload(),
        privacy_subject=SUBJECT,
        account_generation=3,
        now=NOW,
    )
    assert created is True
    assert (report.content_revision, state.revision) == (1, 1)
    assert state.user_description == "점자블록 옆에 장애물이 있습니다"
    assert state.category_hint == "ACCESSIBILITY_BARRIER"
    item = db.added[0]
    assert isinstance(item, ReportContentRevision)
    assert item.expected_revision == 0
    assert db.commits == 1

    replay_db = _Session([None, report, item])
    replay, replay_created = apply_owned_report_correction(
        replay_db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=_payload(),
        privacy_subject=SUBJECT,
        account_generation=3,
    )
    assert replay_created is False
    assert replay.content_sha256 == state.content_sha256
    assert replay_db.rollbacks == 1

    conflict_db = _Session([None, report, item])
    with pytest.raises(ReportContentCorrectionError) as captured:
        apply_owned_report_correction(
            conflict_db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=_payload(user_description="다른 내용"),
            privacy_subject=SUBJECT,
            account_generation=3,
        )
    assert (captured.value.status_code, captured.value.code) == (
        409,
        "report_content_idempotency_conflict",
    )


def test_stale_revision_fails_before_append() -> None:
    current = ReportContentRevision(
        report_id=REPORT_ID,
        revision=1,
        expected_revision=0,
        idempotency_key=uuid.uuid4(),
        privacy_subject_hmac=SUBJECT,
        account_generation=3,
        user_description="현재 내용",
        category_hint="OTHER",
        intent_sha256="b" * 64,
        content_sha256="c" * 64,
        created_at=NOW,
    )
    db = _Session([None, _report(revision=1), None, current])
    with pytest.raises(ReportContentCorrectionError) as captured:
        apply_owned_report_correction(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=_payload(expected_revision=0),
            privacy_subject=SUBJECT,
            account_generation=3,
        )
    assert captured.value.code == "report_content_revision_conflict"
    assert db.added == []


def test_foreign_actor_and_deletion_fence_are_concealed_before_mutation() -> None:
    foreign = _Session([None, None])
    with pytest.raises(ReportContentCorrectionError) as hidden:
        get_owned_report_content(
            foreign,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            privacy_subject="f" * 64,
            account_generation=9,
        )
    assert (hidden.value.status_code, hidden.value.code) == (404, "report_not_found")

    tombstoned = _TombstonedSession([None])
    with pytest.raises(ReportUserRequestError) as deleted:
        get_owned_report_content(
            tombstoned,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            privacy_subject=SUBJECT,
            account_generation=3,
        )
    assert (deleted.value.status_code, deleted.value.code) == (404, "report_not_found")
    assert tombstoned.added == []


def test_package_v2_binds_content_and_supersedes_without_changing_v1_bytes() -> None:
    report = SimpleNamespace(
        id=REPORT_ID,
        status="reviewed",
        class_name="damaged_tactile_block",
        confidence=0.75,
        latitude=37.5,
        longitude=127.0,
        accuracy_m=4.0,
        captured_at=NOW,
        created_at=NOW,
    )
    kwargs = {
        "report": report,
        "review_decision_id": uuid.uuid4(),
        "review_revision": 2,
        "export_audit_id": uuid.uuid4(),
        "package_revision": 1,
    }
    old_before = build_admin_report_delivery_package(**kwargs)
    content = ReportContentState(
        report_id=REPORT_ID,
        revision=1,
        expected_revision=0,
        idempotency_key=KEY,
        content_sha256=report_content_sha256(
            report_id=REPORT_ID,
            revision=1,
            user_description="보행로를 막고 있습니다",
            category_hint="SIDEWALK_OBSTRUCTION",
        ),
        user_description="보행로를 막고 있습니다",
        category_hint="SIDEWALK_OBSTRUCTION",
        corrected_at=NOW,
    )
    previous_id = uuid.uuid4()
    built = build_admin_report_delivery_package_v2(
        **kwargs,
        content=content,
        supersedes_package_id=previous_id,
    )
    old_after = build_admin_report_delivery_package(**kwargs)
    assert old_before.package_bytes == old_after.package_bytes
    assert json.loads(old_before.manifest_bytes)["schema_version"] == DELIVERY_PACKAGE_SCHEMA_VERSION
    manifest = json.loads(built.manifest_bytes)
    assert manifest["schema_version"] == DELIVERY_PACKAGE_SCHEMA_VERSION_V2
    assert manifest["content_revision"] == 1
    assert manifest["content_sha256"] == content.content_sha256
    assert manifest["supersedes_package_id"] == str(previous_id)
    assert "보행로를 막고 있습니다" in built.csv_bytes.decode("utf-8")


class _AdminSession(_Session):
    def flush(self) -> None:
        return None

    def refresh(self, _value: object) -> None:
        return None

    def get(self, model: object, key: object, **_kwargs: object) -> object | None:
        if model is ReportOriginalAccessGrant and key == EVIDENCE_GRANT_ID:
            identity = _identity()
            return ReportOriginalAccessGrant(
                id=EVIDENCE_GRANT_ID,
                report_id=REPORT_ID,
                admin_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=identity.device_id,
                purpose="report_review",
                content_revision=1,
                issued_at=NOW,
                expires_at=datetime(2030, 8, 29, 8, tzinfo=UTC),
                location_disclosed_at=NOW,
                consumed_at=NOW,
                access_granted_at=NOW,
            )
        return None


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="reviewer@example.com",
        session_id=ADMIN_SESSION_ID,
        device_id="admin-device-1",
        device_label="review tablet",
        expires_at=NOW,
        step_up_verified_at=NOW,
    )


def _review_payload(content_revision: int) -> ReportReviewDecisionRequest:
    return ReportReviewDecisionRequest(
        decision="APPROVED",
        reason="정정 내용을 재검수했습니다",
        user_visible_reason=None,
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        content_revision=content_revision,
        evidence_grant_id=EVIDENCE_GRANT_ID,
    )


def test_correction_invalidates_old_approval_then_new_package_supersedes() -> None:
    report = Report(
        id=REPORT_ID,
        status="reviewed",
        content_revision=1,
        class_name="damaged_tactile_block",
        confidence=0.75,
        latitude=37.5,
        longitude=127.0,
        accuracy_m=4.0,
        captured_at=NOW,
        created_at=NOW,
    )
    stale_review = SimpleNamespace(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        revision=1,
        content_revision=0,
        decision="APPROVED",
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        duplicate_of_report_id=None,
    )
    content = ReportContentRevision(
        report_id=REPORT_ID,
        revision=1,
        expected_revision=0,
        idempotency_key=KEY,
        privacy_subject_hmac=SUBJECT,
        account_generation=3,
        user_description="보행로를 막고 있습니다",
        category_hint="SIDEWALK_OBSTRUCTION",
        intent_sha256="b" * 64,
        content_sha256="c" * 64,
        created_at=NOW,
    )
    with pytest.raises(AdminReportWorkflowError) as stale:
        create_admin_report_delivery_package(
            _AdminSession([report, None, content, stale_review]),  # type: ignore[arg-type]
            report_id=REPORT_ID,
            expected_content_revision=1,
            expected_review_revision=1,
            identity=_identity(),
            correlation_id=uuid.uuid4(),
            query_sha256="d" * 64,
        )
    assert stale.value.code == "latest_review_approval_required"

    review_db = _AdminSession([report, stale_review])
    current_review = append_report_review_decision(
        review_db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=_review_payload(1),
        identity=_identity(),
        correlation_id=uuid.uuid4(),
        now=NOW,
    )
    assert current_review.content_revision == 1

    previous = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        revision=1,
        package_sha256="1" * 64,
        csv_sha256="2" * 64,
        manifest_sha256="3" * 64,
    )
    old_hashes = (
        previous.package_sha256,
        previous.csv_sha256,
        previous.manifest_sha256,
    )
    package_db = _AdminSession([report, previous, content, current_review])
    created = create_admin_report_delivery_package(
        package_db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        expected_content_revision=1,
        expected_review_revision=2,
        identity=_identity(),
        correlation_id=uuid.uuid4(),
        query_sha256="e" * 64,
    )
    assert created.record.package_version == 2
    assert created.record.content_revision == 1
    assert created.record.supersedes_package_id == previous.id
    assert old_hashes == (
        previous.package_sha256,
        previous.csv_sha256,
        previous.manifest_sha256,
    )


def test_corrected_report_rejects_stale_package_create_content_revision_zero() -> None:
    report = Report(
        id=REPORT_ID,
        status="reviewed",
        content_revision=1,
        class_name="damaged_tactile_block",
        confidence=0.75,
        latitude=37.5,
        longitude=127.0,
        accuracy_m=4.0,
        captured_at=NOW,
        created_at=NOW,
    )
    content = ReportContentRevision(
        report_id=REPORT_ID,
        revision=1,
        expected_revision=0,
        idempotency_key=KEY,
        privacy_subject_hmac=SUBJECT,
        account_generation=3,
        user_description="보행로를 막고 있습니다",
        category_hint="SIDEWALK_OBSTRUCTION",
        intent_sha256="b" * 64,
        content_sha256="c" * 64,
        created_at=NOW,
    )
    approved = SimpleNamespace(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        revision=2,
        content_revision=1,
        decision="APPROVED",
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        duplicate_of_report_id=None,
        evidence_grant_id=EVIDENCE_GRANT_ID,
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        create_admin_report_delivery_package(
            _AdminSession([report, None, content, approved]),  # type: ignore[arg-type]
            report_id=REPORT_ID,
            expected_content_revision=0,
            expected_review_revision=2,
            identity=_identity(),
            correlation_id=uuid.uuid4(),
            query_sha256="f" * 64,
        )

    assert captured.value.code == "delivery_package_content_revision_conflict"
    assert captured.value.status_code == 409


def test_routes_and_migration_keep_field_binding_and_deletion_cascade() -> None:
    for method, path in (
        ("GET", f"/reports/mine/{REPORT_ID}/content"),
        ("POST", f"/reports/mine/{REPORT_ID}/corrections"),
    ):
        assert required_field_test_access(path, method) is FieldTestAccess.FIELD
        assert requires_actor_identity(path, method)
        assert requires_account_generation(path, method)
    assert max_request_body_bytes(
        SimpleNamespace(),
        path=f"/reports/mine/{REPORT_ID}/corrections",
    ) == REPORT_CORRECTION_BODY_LIMIT_BYTES
    foreign_key = next(iter(ReportContentRevision.__table__.foreign_key_constraints))
    assert foreign_key.ondelete == "CASCADE"
    source = Path(
        "backend/alembic/versions/202608290012_report_content_corrections.py"
    ).read_text(encoding="utf-8")
    assert "GRANT SELECT, INSERT ON TABLE public.report_content_revisions" in source
    assert "GRANT DELETE" not in source
    assert "walksafe_account_deletion_worker" in source
