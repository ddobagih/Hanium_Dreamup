"""Exact body, action, method, path and one-use nonce on migrated PostgreSQL."""

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import os

import pyotp
import pytest
from sqlalchemy import select

from backend.app import database
from backend.app.models import AdminSecurityReconfirmation
from backend.app.services.admin_device_proof import raw_body_sha256
from backend.app.services.admin_operation_policy import classify_admin_operation
from backend.app.services.admin_security import (
    ADMIN_APP_KIND, ADMIN_AUDIENCE, ADMIN_ROLE, AdminSecurityError,
    AdminSecurityService, authorize_admin_bearer,
    authorize_database_bound_high_risk_bearer,
    current_admin_request_body_sha256, provision_admin_security,
    reconfirmation_nonce_sha256,
)
from test_admin_sweep_w22 import ADMIN_ID, DEVICE_ID, _pg_environment

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL"), reason="isolated PostgreSQL required",
)

ID = "11111111-1111-4111-8111-111111111111"
OPERATIONS = [
    ("admin.report.status.update", "PATCH", f"/admin/reports/{ID}/status", b'{"status":"in_review","expected_status_version":1}'),
    ("admin.report_request.status.update", "PATCH", f"/admin/report-requests/{ID}/status", b'{"status":"in_review","expected_status_version":1}'),
    ("admin.incident.status.update", "PATCH", f"/admin/incidents/{ID}/status", b'{"status":"resolved","expected_status_version":1}'),
    ("admin.raw_collection.list", "GET", "/admin/raw-collections/quarantine", b""),
    ("admin.raw_collection.purpose_decide", "POST", f"/admin/raw-collections/{ID}/decisions", b'{"decision":"REJECT","expected_status_version":1}'),
    ("admin.raw_collection.legal_hold", "POST", f"/admin/raw-collections/{ID}/legal-holds", b'{"legal_hold":true,"expected_status_version":1}'),
    ("report.status.patch", "PATCH", f"/reports/{ID}/status", b'{"status":"in_review","expected_status_version":1}'),
]


@pytest.mark.parametrize("action,method,path,body", OPERATIONS)
def test_seven_admin_actions_reject_changed_binding_and_nonce_replay(
    monkeypatch, tmp_path, clean_test_storage, action, method, path, body,
):
    clean_test_storage()
    engine, factory, runtime, settings, totp, issuer = _pg_environment(
        tmp_path, device_proof_enabled=False,
    )
    monkeypatch.setattr(database, "SessionLocal", factory)
    at = datetime.now(timezone.utc).replace(microsecond=0)
    password = "synthetic-body-bound-password-20261011"
    try:
        operation = classify_admin_operation(method, path)
        assert operation is not None and operation.action == action
        with factory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password=password, totp_secret=totp,
                recovery_codes=["RECOVERY-BODY-BOUND-20261011"],
                credential_issuer_key=issuer, now=at,
            )
        grant = runtime(lambda db: AdminSecurityService(db, settings).login(
            admin_id=ADMIN_ID, password=password,
            totp_code=pyotp.TOTP(totp).at(at), device_id=DEVICE_ID,
            device_label="격리 시험 장치", source="198.51.100.41", now=at,
        ))
        with factory() as db:
            identity = authorize_admin_bearer(
                db, grant.access_token, runtime_totp_secret=totp,
                credential_issuer_key=issuer, device_id=DEVICE_ID,
                app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                now=at,
            )
            db.commit()
        runtime(lambda db: AdminSecurityService(db, settings).attest_recovery_custody(
            identity, custody_reference="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
            material_kind="RECOVERY_CODE", storage_location="OFF_PHONE",
            separate_encrypted_backup_confirmed=True, now=at,
        ))
        nonce = base64.urlsafe_b64encode(hashlib.sha256(action.encode()).digest()[:16]).decode().rstrip("=")
        approved_at = at + timedelta(seconds=30)
        digest = raw_body_sha256(body)
        runtime(lambda db: AdminSecurityService(db, settings).reauthenticate(
            identity, password=password, totp_code=pyotp.TOTP(totp).at(approved_at),
            action=action, method=method, path=path, nonce=nonce,
            source="198.51.100.41", now=approved_at, intent_sha256=digest,
        ))

        def consume(*, selected_body=body, selected_action=action,
                    selected_method=method, selected_path=path, selected_nonce=nonce):
            def run(db):
                context = current_admin_request_body_sha256.set(raw_body_sha256(selected_body))
                try:
                    result = authorize_database_bound_high_risk_bearer(
                        db, grant.access_token, selected_action,
                        approved_at + timedelta(seconds=1), method=selected_method,
                        path=selected_path, nonce=selected_nonce,
                        runtime_totp_secret=totp, credential_issuer_key=issuer,
                        device_id=DEVICE_ID,
                    )
                    db.commit()
                    return result
                finally:
                    current_admin_request_body_sha256.reset(context)
            return runtime(run)

        variants = [
            {"selected_body": body + b" "},
            {"selected_action": "device.report_lost"},
            {"selected_method": "DELETE"},
            {"selected_path": path + "/changed"},
            {"selected_nonce": "AAECAwQFBgcICQoLDA0ODw"},
        ]
        for changed in variants:
            with pytest.raises(AdminSecurityError):
                consume(**changed)
            with factory() as db:
                assert db.scalar(select(AdminSecurityReconfirmation.consumed_at).where(
                    AdminSecurityReconfirmation.nonce_sha256 == reconfirmation_nonce_sha256(nonce, digest),
                )) is None
        assert consume().session_id == identity.session_id
        with pytest.raises(AdminSecurityError):
            consume()
    finally:
        clean_test_storage()
        engine.dispose()
