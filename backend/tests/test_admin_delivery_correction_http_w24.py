"""S18-40 (originals/18.txt:276-280), w24: HTTP end-to-end delivery correction.

The w23 tests exercise the correction service and the route function directly.
This test sends the administrator requests through the real ASGI stack instead:
FieldTestSecurityMiddleware with administrator security and device proof on, its
default bearer authorizer and device-proof verifier, and the real routers, all on
the runtime database role. Flow: HTTP reauthentication for the exact correction
path -> HTTP ACTION device-proof challenge -> signed HIGH-risk POST
/reports/{id}/delivery-corrections (201). The same reconfirmation nonce cannot be
spent a second time and the reuse writes no row; the same body with a fresh nonce
passes (positive control), so each correction row matches one authorized nonce.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
import pyotp
import pytest
from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import sessionmaker

from asgi_client import ASGITestClient
from test_admin_delivery_correction_w23 import (
    ADMIN_ID,
    DEVICE_ID,
    _b64,
    _canonical_body,
    _delivery_request,
    _pg_environment,
    _seed_approved_report,
)

_PG = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
PASSWORD = "s18-40 http correct horse"


def _runtime_session_factory():
    """Every pooled connection runs as the Backend runtime role, as in production."""
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)

    @event.listens_for(engine, "connect")
    def _runtime_role(dbapi_connection, _record) -> None:
        with dbapi_connection.cursor() as cursor:
            cursor.execute("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        dbapi_connection.commit()

    return engine, sessionmaker(bind=engine, expire_on_commit=False)


@_PG
def test_s18_40_http_correction_passes_the_real_middleware_once_per_nonce(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    from backend.app import database as database_api
    from backend.app.api import admin_security as admin_security_api
    from backend.app.api import reports as reports_api
    from backend.app.field_test_security import FieldTestSecurityMiddleware
    from backend.app.models import (
        ReportInstitutionDeliveryCorrection,
        ReportInstitutionDeliveryEvent,
    )
    from backend.app.services import admin_report_delivery_package as package_service
    from backend.app.services import admin_report_workflow as workflow
    from backend.app.services.admin_device_proof import provision_admin_device_key
    from backend.app.services.admin_security import (
        ADMIN_APP_KIND,
        ADMIN_AUDIENCE,
        ADMIN_ROLE,
        AdminSecurityService,
        authorize_admin_bearer,
        provision_admin_security,
    )

    clean_test_storage()
    engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path
    )
    runtime_engine, RuntimeSessionFactory = _runtime_session_factory()
    real_now = datetime.now(timezone.utc).replace(microsecond=0)
    started_at = real_now - timedelta(seconds=150)
    private_key = ec.generate_private_key(ec.SECP256R1())
    spki = private_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    try:
        # --- provisioning and seeding (owner role; not the path under test) ---
        monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
        with SessionFactory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password=PASSWORD,
                totp_secret=totp_secret, recovery_codes=["RECOVERY-2026-S18-40-HTTP-1"],
                credential_issuer_key=issuer_key, now=started_at,
            )
        with SessionFactory() as db:
            key = provision_admin_device_key(
                db, admin_id=ADMIN_ID, device_id=DEVICE_ID, key_version=1,
                public_key_spki_der=spki, security_level="TRUSTED_ENVIRONMENT",
                now=started_at,
            )
        grant = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).login(
                admin_id=ADMIN_ID, password=PASSWORD,
                totp_code=pyotp.TOTP(totp_secret).at(started_at), device_id=DEVICE_ID,
                device_label="관리자 휴대전화", source="198.51.100.41", now=started_at,
            )
        )
        with SessionFactory() as db:
            identity = authorize_admin_bearer(
                db, grant.access_token, runtime_totp_secret=totp_secret,
                credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                now=started_at,
            )
            db.commit()
        run_as_runtime(
            lambda db: AdminSecurityService(db, settings).attest_recovery_custody(
                identity,
                custody_reference="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                material_kind="RECOVERY_CODE", storage_location="OFF_PHONE",
                separate_encrypted_backup_confirmed=True, now=started_at,
            )
        )
        monkeypatch.setattr(workflow, "admin_report_integrity_boundary_state", lambda _db: None)
        monkeypatch.setattr(
            package_service, "admin_report_integrity_boundary_state", lambda _db: None
        )
        report_id, package, _approval = _seed_approved_report(SessionFactory, identity, started_at)
        for revision, status in enumerate(("SUBMITTED", "ACKNOWLEDGED", "RESOLVED")):
            with SessionFactory() as db:
                workflow.append_report_institution_delivery_event(
                    db, report_id=report_id,
                    payload=_delivery_request(
                        status, package.revision, revision,
                        started_at + timedelta(seconds=2 + revision),
                    ),
                    identity=identity, correlation_id=uuid.uuid4(),
                )
        monkeypatch.undo()
        with SessionFactory() as db:
            event_ids = list(db.execute(
                select(ReportInstitutionDeliveryEvent.id)
                .where(ReportInstitutionDeliveryEvent.report_id == report_id)
                .order_by(ReportInstitutionDeliveryEvent.revision)
            ).scalars())
        assert len(event_ids) == 3

        # --- the path under test: real middleware, routers and runtime role ---
        monkeypatch.setattr(database_api, "SessionLocal", RuntimeSessionFactory)
        # The route resolves the issuer key from the process settings, as in production.
        from backend.app.config import get_settings

        monkeypatch.setattr(
            get_settings(), "admin_credential_issuer_key_file",
            settings.admin_credential_issuer_key_file,
        )
        http_settings = SimpleNamespace(**vars(settings))
        for name, value in {
            "admin_security_enabled": True,
            "field_test_security_enabled": True,
            "field_test_token": "field-token-for-s18-40-http-0123456789",
            "admin_token": "unused-static-admin-token-s18-40-http",
            "allow_insecure_local_dev": False,
            "gateway_session_secret": "",
            "actor_rate_limit_store": "memory",
            "max_upload_bytes": 1024 * 1024,
            "max_report_metadata_bytes": 64 * 1024,
            "report_original_grant_ttl_seconds": 120,
        }.items():
            setattr(http_settings, name, value)
        app = FastAPI()
        app.include_router(admin_security_api.create_router(http_settings))
        app.include_router(reports_api.create_router(http_settings, SimpleNamespace()))
        app.add_middleware(FieldTestSecurityMiddleware, settings=http_settings)
        client = ASGITestClient(app)
        base_headers = {
            "Authorization": f"Bearer {grant.access_token}",
            "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
            "X-WalkSafe-Role": ADMIN_ROLE,
            "X-WalkSafe-Audience": ADMIN_AUDIENCE,
            "X-WalkSafe-Device-Id": DEVICE_ID,
        }
        path = f"/reports/{report_id}/delivery-corrections"

        def reauthenticate(nonce: str, *, next_totp_step: bool = False) -> None:
            totp = pyotp.TOTP(totp_secret)
            # A TOTP time step is accepted once; a second reauthentication uses the
            # next step (inside the server's +/-1 step window).
            code = (
                totp.at(datetime.now(timezone.utc) + timedelta(seconds=totp.interval))
                if next_totp_step
                else totp.now()
            )
            response = client.post(
                "/admin/security/reauthenticate",
                json={
                    "password": PASSWORD,
                    "totp_code": code,
                    "action": "report.delivery.correct",
                    "method": "POST",
                    "path": path,
                    "nonce": nonce,
                },
                headers={**base_headers, "X-WalkSafe-Correlation-Id": str(uuid.uuid4())},
            )
            assert response.status_code == 200, response.text
            assert response.json()["action"] == "report.delivery.correct"
            assert response.json()["path"] == path

        def signed_correction(body: bytes, nonce: str | None):
            correlation_id = str(uuid.uuid4())
            challenge = client.post(
                "/admin/security/device-proof/challenges",
                json={
                    "action": "report.delivery.correct",
                    "admin_id": ADMIN_ID,
                    "body_sha256": hashlib.sha256(body).hexdigest(),
                    "correlation_id": correlation_id,
                    "device_id": DEVICE_ID,
                    "device_key_marker": key.key_marker,
                    "device_key_version": 1,
                    "method": "POST",
                    "path": path,
                    "purpose": "ACTION",
                    "query_sha256": hashlib.sha256(b"").hexdigest(),
                    "read_purpose": None,
                    "session_id": str(identity.session_id),
                },
                headers={**base_headers, "X-WalkSafe-Correlation-Id": correlation_id},
            )
            assert challenge.status_code == 200, challenge.text
            issued = challenge.json()
            signature = _b64(private_key.sign(
                issued["signing_payload"].encode("utf-8"), ec.ECDSA(hashes.SHA256())
            ))
            return client.post(
                path,
                content=body,
                headers={
                    **base_headers,
                    "Content-Type": "application/json",
                    "X-WalkSafe-Correlation-Id": correlation_id,
                    "X-WalkSafe-Device-Challenge-Id": issued["challenge_id"],
                    "X-WalkSafe-Device-Signature": signature,
                    **({} if nonce is None else {"X-WalkSafe-Reconfirm-Nonce": nonce}),
                },
            )

        def correction_body(target: uuid.UUID, expected_revision: int) -> bytes:
            return _canonical_body({
                "correction_id": str(uuid.uuid4()),
                "corrects_event_id": str(target),
                "expected_revision": expected_revision,
                "reason_code": "WRONG_REPORT",
                "memo": "다른 신고의 기관 해결 회신을 이 건에 잘못 기록함",
            })

        nonce = _b64(os.urandom(16))
        reauthenticate(nonce)
        # Without the reconfirmation nonce the HIGH-risk write never reaches the route.
        missing = signed_correction(correction_body(event_ids[2], 3), None)
        assert missing.status_code == 403, missing.text
        assert missing.json()["detail"]["code"] == "admin_reconfirmation_invalid"
        created = signed_correction(correction_body(event_ids[2], 3), nonce)
        assert created.status_code == 201, created.text
        assert created.headers["cache-control"] == "no-store"
        payload = created.json()
        assert payload["schema_version"] == "walksafe.report-delivery-correction.v1"
        assert payload["corrects_event_id"] == str(event_ids[2])
        assert payload["corrected_revision"] == 3
        assert payload["reason_code"] == "WRONG_REPORT"

        # The same nonce cannot authorize another correction (next effective event),
        # even with a fresh, correctly signed device proof.
        second_body = correction_body(event_ids[1], 3)
        reused = signed_correction(second_body, nonce)
        assert reused.status_code == 403, reused.text
        assert reused.json()["detail"]["code"] == "admin_reconfirmation_required"
        # w24 misc-F5 positive control: the same body with a fresh reconfirmation
        # nonce passes, so the 403 above came from the spent nonce, not the body.
        fresh_nonce = _b64(os.urandom(16))
        reauthenticate(fresh_nonce, next_totp_step=True)
        second = signed_correction(second_body, fresh_nonce)
        assert second.status_code == 201, second.text
        assert second.json()["corrects_event_id"] == str(event_ids[1])

        with SessionFactory() as db:
            stored = list(db.execute(
                select(ReportInstitutionDeliveryCorrection.corrects_event_id)
                .join(
                    ReportInstitutionDeliveryEvent,
                    ReportInstitutionDeliveryEvent.id
                    == ReportInstitutionDeliveryCorrection.corrects_event_id,
                )
                .where(ReportInstitutionDeliveryEvent.report_id == report_id)
            ).scalars())
        # One row per authorized nonce; the rejected reuse wrote nothing.
        assert sorted(stored) == sorted([event_ids[2], event_ids[1]])
    finally:
        runtime_engine.dispose()
        engine.dispose()
