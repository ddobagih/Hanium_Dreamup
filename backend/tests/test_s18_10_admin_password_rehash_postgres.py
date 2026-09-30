"""PostgreSQL regression for atomic legacy administrator login rehash."""

from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import os
from types import SimpleNamespace
import uuid

import pyotp
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import AdminSecuritySession
from backend.app.services import admin_security


_PASSWORD = "synthetic-admin-password"
_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
_ISSUER_KEY = base64.urlsafe_b64encode(bytes(range(32))).decode("ascii").rstrip("=")
_REHASH_SIGNATURE = (
    "public.walksafe_issue_admin_session_with_password_rehash("
    "uuid,text,text,text,text,timestamptz,timestamptz,timestamptz,"
    "timestamptz,text,bigint,bigint,text,text,text,text)"
)
_ORIGINAL_SIGNATURE = (
    "public.walksafe_issue_admin_session("
    "uuid,text,text,text,text,timestamptz,timestamptz,timestamptz,"
    "timestamptz,text,bigint,bigint,text,text)"
)


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_legacy_login_rehash_and_session_are_atomic_as_postgres_runtime(
    monkeypatch: pytest.MonkeyPatch, clean_test_storage
) -> None:
    assert clean_test_storage is not None
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"], pool_pre_ping=True)
    admin_id = f"s18-10-rehash-{uuid.uuid4().hex[:12]}"
    device_id = "s18-10-synthetic-device"
    source = "s18-10-postgres-regression"
    observed_at = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(seconds=70)
    collision_at = observed_at + timedelta(seconds=31)
    next_login_at = collision_at + timedelta(seconds=31)
    salt = b"L" * 16
    derived = hashlib.scrypt(
        _PASSWORD.encode("utf-8"), salt=salt, n=16384, r=8, p=1, dklen=32
    )
    encode = lambda value: base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")
    legacy_hash = f"scrypt-v1$16384$8$1${encode(salt)}${encode(derived)}"
    collision_session_id = uuid.uuid4()

    settings = SimpleNamespace(
        admin_id=admin_id,
        admin_totp_secret=_TOTP_SECRET,
        admin_device_proof_enabled=False,
        admin_session_ttl_seconds=3600,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
        walksafe_environment="test",
    )

    def login(runtime, *, password: str, now: datetime):
        with Session(runtime) as db:
            service = admin_security.AdminSecurityService(db, settings)
            monkeypatch.setattr(service, "_credential_issuer_key", lambda: _ISSUER_KEY)
            return service.login(
                admin_id=admin_id,
                password=password,
                totp_code=pyotp.TOTP(_TOTP_SECRET).at(now),
                device_id=device_id,
                device_label="S18-10 synthetic device",
                source=source,
                now=now,
            )

    def persisted_state():
        with engine.connect() as owner:
            control = owner.execute(
                text(
                    "SELECT password_hash, last_totp_timecode "
                    "FROM admin_security_controls WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).one()
            sessions = owner.execute(
                text(
                    "SELECT id, token_sha256, revoked_at "
                    "FROM admin_security_sessions WHERE admin_id = :admin_id "
                    "ORDER BY issued_at, id"
                ),
                {"admin_id": admin_id},
            ).all()
            return control, sessions

    try:
        with engine.connect() as owner:
            assert "test" in owner.execute(text("SELECT current_database() ")).scalar_one().lower()
            for signature in (_REHASH_SIGNATURE, _ORIGINAL_SIGNATURE):
                assert owner.execute(
                    text("SELECT to_regprocedure(:signature) IS NOT NULL"),
                    {"signature": signature},
                ).scalar_one()
                assert owner.execute(
                    text(
                        "SELECT has_function_privilege('walksafe_backend_runtime', "
                        "to_regprocedure(:signature), 'EXECUTE')"
                    ),
                    {"signature": signature},
                ).scalar_one()
            assert not owner.execute(
                text(
                    "SELECT EXISTS ("
                    "SELECT 1 FROM pg_proc AS p "
                    "CROSS JOIN LATERAL aclexplode("
                    "COALESCE(p.proacl, acldefault('f', p.proowner))) AS acl "
                    "WHERE p.oid = to_regprocedure(:signature) "
                    "AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE')"
                ),
                {"signature": _REHASH_SIGNATURE},
            ).scalar_one()

        issued_hash_password = admin_security.hash_password
        with monkeypatch.context() as patcher:
            patcher.setattr(
                admin_security,
                "hash_password",
                lambda value: legacy_hash if value == _PASSWORD else issued_hash_password(value),
            )
            with Session(engine) as db:
                admin_security.provision_admin_security(
                    db,
                    admin_id=admin_id,
                    password=_PASSWORD,
                    totp_secret=_TOTP_SECRET,
                    recovery_codes=["S18-10-SYNTHETIC-RECOVERY-CODE-0001"],
                    credential_issuer_key=_ISSUER_KEY,
                    now=observed_at,
                )
        with Session(engine) as db:
            db.add(
                AdminSecuritySession(
                    id=collision_session_id,
                    admin_id=admin_id,
                    device_id=device_id,
                    device_label="Existing synthetic device",
                    token_sha256=admin_security.sha256_text("s18-10-existing-token"),
                    issued_at=observed_at - timedelta(seconds=1),
                    expires_at=observed_at + timedelta(hours=1),
                    step_up_verified_at=observed_at - timedelta(seconds=1),
                    last_seen_at=observed_at - timedelta(seconds=1),
                )
            )
            db.commit()

        with engine.connect() as runtime:
            runtime.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
            runtime.commit()
            try:
                assert runtime.execute(text("SELECT current_user")).scalar_one() == (
                    "walksafe_backend_runtime"
                )
                runtime.commit()

                with pytest.raises(admin_security.AdminSecurityError) as denied:
                    login(runtime, password="incorrect password", now=observed_at)
                assert denied.value.code == "admin_credentials_invalid"
                control, sessions = persisted_state()
                assert control.password_hash == legacy_hash
                assert control.last_totp_timecode is None
                assert len(sessions) == 1
                assert sessions[0].revoked_at is None

                replacement_hash = admin_security.hash_password(_PASSWORD)
                with pytest.raises(SQLAlchemyError) as collision:
                    with runtime.begin():
                        runtime.execute(
                            text(
                                "SELECT public.walksafe_issue_admin_session_with_password_rehash("
                                "CAST(:session_id AS uuid), CAST(:admin_id AS text), "
                                "CAST(:device_id AS text), CAST(:device_label AS text), "
                                "CAST(:token_sha256 AS text), "
                                "CAST(:issued_at AS timestamptz), "
                                "CAST(:expires_at AS timestamptz), "
                                "CAST(:step_up_verified_at AS timestamptz), "
                                "CAST(:last_seen_at AS timestamptz), "
                                "CAST(:authentication_kind AS text), "
                                "CAST(:expected_last_totp_timecode AS bigint), "
                                "CAST(:next_last_totp_timecode AS bigint), "
                                "CAST(:runtime_totp_secret AS text), "
                                "CAST(:credential_issuer_key AS text), "
                                "CAST(:expected_password_hash AS text), "
                                "CAST(:replacement_password_hash AS text))"
                            ),
                            {
                                "session_id": collision_session_id,
                                "admin_id": admin_id,
                                "device_id": device_id,
                                "device_label": "S18-10 synthetic device",
                                "token_sha256": admin_security.sha256_text(
                                    "s18-10-collision-token"
                                ),
                                "issued_at": collision_at,
                                "expires_at": collision_at + timedelta(hours=1),
                                "step_up_verified_at": collision_at,
                                "last_seen_at": collision_at,
                                "authentication_kind": "LOGIN",
                                "expected_last_totp_timecode": None,
                                "next_last_totp_timecode": int(collision_at.timestamp()) // 30,
                                "runtime_totp_secret": _TOTP_SECRET,
                                "credential_issuer_key": _ISSUER_KEY,
                                "expected_password_hash": legacy_hash,
                                "replacement_password_hash": replacement_hash,
                            },
                        )
                assert getattr(collision.value.orig, "sqlstate", None) == "23505"
                control, sessions = persisted_state()
                assert control.password_hash == legacy_hash
                assert control.last_totp_timecode is None
                assert len(sessions) == 1
                assert sessions[0].id == collision_session_id
                assert sessions[0].revoked_at is None

                first = login(runtime, password=_PASSWORD, now=collision_at)
                control, sessions = persisted_state()
                assert control.password_hash != legacy_hash
                assert control.password_hash.split("$")[1:4] == ["16384", "8", "5"]
                assert admin_security.verify_password(_PASSWORD, control.password_hash)
                assert control.last_totp_timecode == int(collision_at.timestamp()) // 30
                assert len(sessions) == 2
                assert sum(row.revoked_at is None for row in sessions) == 1
                assert any(
                    str(row.id) == first.current_session_id
                    and row.token_sha256 == admin_security.sha256_text(first.access_token)
                    and row.revoked_at is None
                    for row in sessions
                )
                upgraded_hash = control.password_hash

                second = login(runtime, password=_PASSWORD, now=next_login_at)
                control, sessions = persisted_state()
                assert second.current_session_id != first.current_session_id
                assert control.password_hash == upgraded_hash
                assert control.last_totp_timecode == int(next_login_at.timestamp()) // 30
                assert len(sessions) == 3
                assert sum(row.revoked_at is None for row in sessions) == 1
                assert any(
                    str(row.id) == second.current_session_id
                    and row.token_sha256 == admin_security.sha256_text(second.access_token)
                    and row.revoked_at is None
                    for row in sessions
                )
            finally:
                if runtime.in_transaction():
                    runtime.rollback()
                runtime.execute(text("RESET SESSION AUTHORIZATION"))
                runtime.commit()
    finally:
        engine.dispose()
