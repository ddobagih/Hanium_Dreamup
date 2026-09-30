"""W20A-R2: every HIGH administrator operation can be reconfirmed on PostgreSQL.

classify_admin_operation marks an operation HIGH, which makes the protected
route require a one-time reconfirmation. On PostgreSQL the only issuance path
is public.walksafe_issue_admin_reconfirmation, whose allowlist must therefore
accept every HIGH action with the same method and path shape.
"""

from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import importlib
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

import pyotp
import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import sessionmaker

from backend.app import database as database_api
from backend.app.models import AdminSecurityReconfirmation
import backend.app.services.admin_security as admin_security
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityService,
    authorize_admin_bearer,
    authorize_database_bound_high_risk_bearer,
    classify_admin_operation,
    provision_admin_security,
    sha256_text,
)

REPORT_ID = "11111111-1111-4111-8111-111111111111"
# One sample request per HIGH action the classifier can return.
HIGH_RISK_SAMPLES = (
    ("admin.raw_collection.list", "GET", "/admin/raw-collections/quarantine"),
    (
        "admin.raw_collection.purpose_decide",
        "POST",
        f"/admin/raw-collections/{REPORT_ID}/decisions",
    ),
    (
        "admin.raw_collection.legal_hold",
        "POST",
        f"/admin/raw-collections/{REPORT_ID}/legal-holds",
    ),
    ("report.export", "GET", "/reports/export"),
    ("release.approval", "POST", "/admin/operations/release-approvals"),
    ("privilege.change", "POST", "/admin/operations/privilege-changes"),
    ("data.delete", "POST", "/admin/operations/data-deletions"),
    ("report.status.patch", "PATCH", f"/reports/{REPORT_ID}/status"),
    ("admin.report.status.update", "PATCH", f"/admin/reports/{REPORT_ID}/status"),
    (
        "admin.report.delivery_package.create",
        "POST",
        f"/admin/reports/{REPORT_ID}/delivery-packages",
    ),
    (
        "admin.report_request.status.update",
        "PATCH",
        f"/admin/report-requests/{REPORT_ID}/status",
    ),
    (
        "admin.incident.status.update",
        "PATCH",
        f"/admin/incidents/{REPORT_ID}/status",
    ),
    (
        "report.original.grant",
        "POST",
        f"/reports/{REPORT_ID}/original-access-grants",
    ),
    (
        "device.report_lost",
        "POST",
        "/admin/security/devices/android-device-0009/report-lost",
    ),
)
_MIGRATION = importlib.import_module(
    "backend.alembic.versions.202609280002_admin_high_risk_reconfirmation_allowlist"
)
_VERSIONS = Path(__file__).resolve().parents[1] / "alembic" / "versions"


def _nonce(label: str) -> str:
    digest = hashlib.sha256(f"w20a-r2-{label}".encode("utf-8")).digest()[:16]
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def _allowlisted_actions(source: str) -> set[str]:
    return {
        line.strip().split("'")[1]
        for line in source.splitlines()
        if "(p_action = '" in line
    }


def test_samples_cover_every_high_risk_classification() -> None:
    for action, method, path in HIGH_RISK_SAMPLES:
        operation = classify_admin_operation(method, path)
        assert operation is not None and (operation.action, operation.risk) == (
            action,
            "HIGH",
        )
    assert len({action for action, _, _ in HIGH_RISK_SAMPLES}) == len(HIGH_RISK_SAMPLES)


def test_migration_allowlist_uses_the_exact_python_path_patterns() -> None:
    fragment = _MIGRATION._HIGH_RISK_ADMIN_ALLOWLIST
    for pattern in (
        admin_security.ADMIN_RAW_COLLECTION_DECISION_PATH_PATTERN,
        admin_security.ADMIN_RAW_COLLECTION_HOLD_PATH_PATTERN,
        admin_security.ADMIN_REPORT_STATUS_PATH_PATTERN,
        admin_security.ADMIN_REPORT_DELIVERY_PACKAGE_PATH_PATTERN,
        admin_security.ADMIN_REPORT_REQUEST_STATUS_PATH_PATTERN,
        admin_security.ADMIN_INCIDENT_STATUS_PATH_PATTERN,
    ):
        assert fragment.count(f"'{pattern.pattern}'") == 1
    assert fragment.count("AND p_path = '/admin/raw-collections/quarantine'") == 1
    assert fragment.count("AND p_method = 'GET'") == 1
    assert fragment.count("AND p_method = 'POST'") == 3
    assert fragment.count("AND p_method = 'PATCH'") == 3
    assert _MIGRATION.down_revision == "202609280001"


def test_migrated_allowlist_names_exactly_the_high_risk_actions() -> None:
    sources = (
        (_VERSIONS / "202608120001_admin_recovery_custody.py").read_text(encoding="utf-8"),
        (_VERSIONS / "202609280001_admin_review_delivery_v4.py").read_text(encoding="utf-8"),
        _MIGRATION._HIGH_RISK_ADMIN_ALLOWLIST,
    )
    issue_body = sources[0].split("CREATE FUNCTION walksafe_issue_admin_reconfirmation(", 1)[1]
    issue_body = issue_body.split("$$", 2)[0] if "$$" in issue_body[:200] else issue_body
    issue_actions = _allowlisted_actions(
        issue_body.split("THEN\n            RAISE EXCEPTION\n              "
                         "'administrator reconfirmation issuance payload is invalid'", 1)[0]
    )
    actions = issue_actions | _allowlisted_actions(sources[1].split("_LOST_DEVICE_ALLOWLIST = ", 1)[1].split('"""', 2)[1]) | _allowlisted_actions(sources[2])
    assert actions == {action for action, _, _ in HIGH_RISK_SAMPLES}


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_every_high_risk_operation_reauthenticates_then_consumes(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True
    )
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)

    def run_as_runtime(callback):
        with engine.connect() as runtime_connection:
            runtime_connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            runtime_connection.commit()
            try:
                with sessionmaker(
                    bind=runtime_connection, expire_on_commit=False
                )() as runtime_db:
                    return callback(runtime_db)
            finally:
                if runtime_connection.in_transaction():
                    runtime_connection.rollback()
                runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
                runtime_connection.commit()

    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    totp_secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    credential_issuer_key = base64.urlsafe_b64encode(bytes(range(32))).decode(
        "ascii"
    ).rstrip("=")
    issuer_directory = tmp_path / "admin-issuer-authority"
    issuer_directory.mkdir(mode=0o700)
    issuer_key_file = issuer_directory / "issuer.key"
    issuer_key_file.write_text(credential_issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_id="walksafe.admin",
        admin_device_proof_enabled=False,
        admin_credential_issuer_key_file=issuer_key_file,
        admin_totp_secret=totp_secret,
        admin_session_ttl_seconds=43_200,
        admin_step_up_ttl_seconds=300,
        admin_recovery_ttl_seconds=900,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
        walksafe_environment="test",
    )
    device_id = "android-device-0001"
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db,
                admin_id=settings.admin_id,
                password="allowlist correct horse",
                totp_secret=totp_secret,
                recovery_codes=["RECOVERY-2026-ALLOWLIST-0001"],
                credential_issuer_key=credential_issuer_key,
                now=started_at,
            )
        grant = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).login(
                admin_id=settings.admin_id,
                password="allowlist correct horse",
                totp_code=pyotp.TOTP(totp_secret).at(started_at),
                device_id=device_id,
                device_label="관리자 휴대전화",
                source="198.51.100.20",
                now=started_at,
            )
        )
        with SessionFactory() as db:
            identity = authorize_admin_bearer(
                db,
                grant.access_token,
                runtime_totp_secret=totp_secret,
                credential_issuer_key=credential_issuer_key,
                device_id=device_id,
                app_kind=ADMIN_APP_KIND,
                role=ADMIN_ROLE,
                audience=ADMIN_AUDIENCE,
                now=started_at,
            )
            db.commit()
        attested = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).attest_recovery_custody(
                identity,
                custody_reference="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                material_kind="RECOVERY_CODE",
                storage_location="OFF_PHONE",
                separate_encrypted_backup_confirmed=True,
                now=started_at,
            )
        )
        assert attested["recovery_custody_state"] == "ATTESTED"

        outcomes: dict[str, object] = {}
        for index, (action, method, path) in enumerate(HIGH_RISK_SAMPLES, start=1):
            observed_at = started_at + timedelta(seconds=30 * index)
            nonce = _nonce(action)
            try:
                reconfirmation = run_as_runtime(
                    lambda db: AdminSecurityService(db, settings).reauthenticate(
                        identity,
                        password="allowlist correct horse",
                        totp_code=pyotp.TOTP(totp_secret).at(observed_at),
                        action=action,
                        method=method,
                        path=path,
                        nonce=nonce,
                        source="198.51.100.20",
                        now=observed_at,
                    )
                )
            except admin_security.AdminSecurityError as exc:
                outcomes[action] = ("reauthenticate", exc.code)
                continue
            assert (reconfirmation.action, reconfirmation.method, reconfirmation.path) == (
                action,
                method,
                path,
            )

            def consume(db):
                consumed_identity = authorize_database_bound_high_risk_bearer(
                    db,
                    grant.access_token,
                    action,
                    observed_at,
                    method=method,
                    path=path,
                    nonce=nonce,
                    runtime_totp_secret=totp_secret,
                    credential_issuer_key=credential_issuer_key,
                    device_id=device_id,
                )
                db.commit()
                return consumed_identity

            try:
                consumed = run_as_runtime(consume)
            except admin_security.AdminSecurityError as exc:
                outcomes[action] = ("consume", exc.code)
                continue
            assert consumed.session_id == identity.session_id
            with SessionFactory() as db:
                stored = db.execute(
                    select(AdminSecurityReconfirmation).where(
                        AdminSecurityReconfirmation.nonce_sha256 == sha256_text(nonce)
                    )
                ).scalar_one()
            assert (stored.action, stored.method, stored.path) == (action, method, path)
            outcomes[action] = ("consumed", stored.consumed_at == observed_at)

        assert outcomes == {
            action: ("consumed", True) for action, _, _ in HIGH_RISK_SAMPLES
        }
    finally:
        engine.dispose()
