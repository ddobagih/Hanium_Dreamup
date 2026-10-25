"""Real PG consumer queries; synthetic admin context keeps auth testing separate."""

from copy import copy
from datetime import datetime, timezone
from types import SimpleNamespace
import uuid

from fastapi import FastAPI
import pytest

from asgi_client import ASGITestClient
from backend.app.api import admin_raw_collections, admin_reports, report_user_requests, reports
from backend.app.database import get_db
from backend.app.main import settings as app_settings
from backend.app.models import RawCollection, Report
from backend.app.schemas import (
    AdminReportUserRequestStatusUpdateV1,
    RawLegalHoldRequestV1,
    RawPurposeDecisionRequestV1,
    ReportInstitutionDeliveryCorrectionRequest,
)
from backend.app.services.admin_report_delivery_package import create_admin_report_delivery_package
from backend.app.services.admin_report_workflow import (
    append_report_review_decision,
    correct_report_institution_delivery_event,
    list_report_review_decisions,
)
from backend.app.services.raw_collection_lifecycle import (
    LifecycleAdminIdentity,
    record_legal_hold,
    record_purpose_decision,
)
from backend.app.services.raw_collection_storage import RawCollectionStorage
from backend.app.services.report_content_corrections import get_owned_report_content
from backend.app.services.report_original_access import (
    access_report_original,
    issue_report_original_access_grant,
)
from backend.app.services.report_user_request_history import list_owned_report_request_history
from backend.app.services.report_user_requests import update_admin_request_status


def assert_restored_consumers_hidden(
    monkeypatch, sessions, *, report_id, kept_report_id, subject, raw_id,
    user_request_id, identity, review_payload, upload_root,
):
    # Authentication is exercised by separate real middleware/PG tests. These
    # route requests test the actual restored-store projections and streaming.
    settings = copy(app_settings)
    settings.field_test_security_enabled = False
    settings.admin_security_enabled = False
    app = FastAPI()
    app.include_router(reports.create_router(settings))
    app.include_router(admin_reports.create_router(settings))
    app.include_router(report_user_requests.create_router(settings))
    app.include_router(admin_raw_collections.create_router())
    proof = SimpleNamespace(correlation_id=uuid.uuid4(), query_sha256="e" * 64)
    monkeypatch.setattr(admin_reports, "_require_admin_report_read_context", lambda *a, **k: (identity, proof))
    monkeypatch.setattr(report_user_requests, "_admin_read_context", lambda *a, **k: (identity, proof))
    raw_identity = LifecycleAdminIdentity(
        admin_id=identity.admin_id, session_id=identity.session_id,
        device_id=identity.device_id, correlation_id=proof.correlation_id,
    )
    monkeypatch.setattr(admin_raw_collections, "_identity", lambda *a, **k: raw_identity)

    def database():
        with sessions() as db:
            yield db

    app.dependency_overrides[get_db] = database
    client = ASGITestClient(app)
    listed = client.get("/reports")
    assert listed.status_code == 200
    assert [row["id"] for row in listed.json()] == [str(kept_report_id)]
    assert client.get(f"/reports/{report_id}").status_code == 404
    assert client.get(f"/reports/{kept_report_id}").status_code == 200
    exported = client.get("/reports/export?format=json")
    assert exported.status_code == 200
    assert str(report_id) not in exported.text
    assert str(kept_report_id) in exported.text
    summary = client.get("/reports/summary")
    assert summary.status_code == 200
    assert summary.json()["total"] == 1
    assert client.patch(f"/reports/{report_id}/status", json={"status": "reviewed"}).status_code == 404
    admin_list = client.get("/admin/reports")
    assert admin_list.status_code == 200
    assert str(report_id) not in admin_list.text
    assert str(kept_report_id) in admin_list.text
    assert client.get(f"/admin/reports/{report_id}").status_code == 404
    admin_requests = client.get("/admin/report-requests")
    assert admin_requests.status_code == 200
    assert admin_requests.json()["items"] == []
    assert client.get(f"/admin/report-requests/{user_request_id}").status_code == 404
    raw_list = client.get("/admin/raw-collections/quarantine?state=ALL")
    assert raw_list.status_code == 200
    assert raw_list.json()["items"] == []

    def hidden(operation):
        with sessions() as db:
            with pytest.raises(Exception) as rejected:
                operation(db)
            assert rejected.value.status_code == 404

    hidden(lambda db: list_report_review_decisions(db, report_id=report_id))
    hidden(lambda db: append_report_review_decision(
        db, report_id=report_id, payload=review_payload,
        identity=identity, correlation_id=uuid.uuid4(),
    ))
    hidden(lambda db: correct_report_institution_delivery_event(
        db, report_id=report_id, payload=ReportInstitutionDeliveryCorrectionRequest(
            correction_id=uuid.uuid4(), corrects_event_id=uuid.uuid4(),
            expected_revision=0, reason_code="OTHER", memo="synthetic restore test",
        ),
        identity=identity, correlation_id=uuid.uuid4(),
    ))
    hidden(lambda db: create_admin_report_delivery_package(
        db, report_id=report_id, expected_content_revision=0,
        expected_review_revision=1, identity=identity,
        correlation_id=uuid.uuid4(), query_sha256="e" * 64,
    ))
    hidden(lambda db: get_owned_report_content(
        db, report_id=report_id, privacy_subject=subject, account_generation=1,
    ))
    hidden(lambda db: list_owned_report_request_history(
        db, privacy_subject=subject, account_generation=1,
        report_id=report_id, limit=25, cursor=None,
    ))
    hidden(lambda db: update_admin_request_status(
        db, request_id=user_request_id,
        payload=AdminReportUserRequestStatusUpdateV1(status="ACKNOWLEDGED", expected_version=1),
        identity=identity, correlation_id=uuid.uuid4(), query_sha256="e" * 64,
    ))
    # Original denial audits also recheck live administrator authority. Seed
    # the existing synthetic admin control/session/custody fixture in this
    # restored database, so 404 is observed with real PG audit enforcement.
    import test_report_original_access as original_fixture
    monkeypatch.setattr(original_fixture, "SessionLocal", sessions)
    original_identity = original_fixture._identity(f"restore-{uuid.uuid4().hex}")
    original_fixture._prepare_identity(original_identity, observed_at=datetime.now(timezone.utc))
    hidden(lambda db: issue_report_original_access_grant(
        db, report_id=report_id, purpose="REVIEW", reason="synthetic review",
        expected_content_revision=0, identity=original_identity, ttl_seconds=120, key_manager=None,
        runtime_totp_secret=original_fixture.TEST_TOTP_SECRET,
        credential_issuer_key=original_fixture.TEST_CREDENTIAL_ISSUER_KEY,
    ))
    hidden(lambda db: access_report_original(
        db, upload_root=upload_root, filename=f"{report_id}.wse",
        raw_access_token="unused", identity=original_identity, key_manager=None,
        runtime_totp_secret=original_fixture.TEST_TOTP_SECRET,
        credential_issuer_key=original_fixture.TEST_CREDENTIAL_ISSUER_KEY,
    ))
    hidden(lambda db: record_legal_hold(
        db, collection_id=raw_id,
        payload=RawLegalHoldRequestV1(action="RELEASE", expected_revision=0,
                                    idempotency_key=uuid.uuid4(), reason="synthetic"),
        identity=raw_identity,
    ))
    hidden(lambda db: record_purpose_decision(
        db, collection_id=raw_id, payload=RawPurposeDecisionRequestV1(
            scope="REPORT", decision="REJECTED", expected_revision=0,
            idempotency_key=uuid.uuid4(), reason="synthetic restore test",
        ), identity=raw_identity,
    ))
    with sessions() as db:
        assert RawCollectionStorage._rows_for_collection(db, raw_id, for_update=False) is None
        # Physical deletion scans deliberately retain unfiltered access.
        assert db.get(Report, report_id) is not None
        assert db.get(RawCollection, raw_id) is not None
