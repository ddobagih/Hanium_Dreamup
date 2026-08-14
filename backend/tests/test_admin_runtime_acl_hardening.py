from __future__ import annotations

import base64
import hashlib
import os
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from backend.app.services.admin_security import (
    AdminSecurityService,
    AdminSecurityStoreUnavailable,
    provision_admin_security,
)

CURRENT_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
CANDIDATE_TOTP = "KRUGS4ZANFZSAYJAORUGS4ZANFZSAYJA"
OTHER_TOTP = "KRSXG5DSNFXGOIDBNZQWY5BAMFZXGZJA"
ISSUER_KEY = base64.urlsafe_b64encode(bytes(range(32))).decode("ascii").rstrip("=")


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_device_proof_runtime_acl_allows_only_one_way_consumption() -> None:
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"], pool_pre_ping=True)
    challenge_id = uuid.uuid4()
    issued_at = datetime.now(timezone.utc).replace(microsecond=0)
    expires_at = issued_at + timedelta(minutes=2)
    consumed_at = issued_at + timedelta(seconds=1)
    try:
        with engine.begin() as connection:
            assert connection.execute(
                text("SELECT current_database()")
            ).scalar_one().lower().find("test") >= 0
            connection.execute(
                text(
                    "INSERT INTO admin_device_proof_challenges ("
                    "id, challenge_type, action, admin_id, body_sha256, "
                    "correlation_id, device_id, device_key_marker, "
                    "device_key_version, expires_at, issued_at, method, nonce, "
                    "purpose, path, query_sha256, read_purpose, schema_version, "
                    "session_id, signing_payload, consumed_at, created_at) "
                    "VALUES (:id, 'LOGIN', NULL, 'walksafe.admin', :empty_sha, "
                    ":correlation_id, 'runtime-acl-device', :marker, 1, "
                    ":expires_at, :issued_at, 'POST', :nonce, 'LOGIN', "
                    "'/admin/security/sessions', :empty_sha, NULL, "
                    "'walksafe.admin-device-proof.v2', NULL, '{}', NULL, "
                    ":issued_at)"
                ),
                {
                    "id": challenge_id,
                    "empty_sha": _sha256(""),
                    "correlation_id": uuid.uuid4(),
                    "marker": "a" * 64,
                    "expires_at": expires_at,
                    "issued_at": issued_at,
                    "nonce": base64.urlsafe_b64encode(bytes(range(32)))
                    .decode("ascii")
                    .rstrip("="),
                },
            )

        def runtime_update(sql: str, **parameters: object) -> None:
            with engine.connect() as connection:
                transaction = connection.begin()
                try:
                    connection.execute(
                        text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
                    )
                    connection.execute(text(sql), parameters)
                    transaction.commit()
                except Exception:
                    transaction.rollback()
                    raise

        with pytest.raises(SQLAlchemyError) as nonce_change:
            runtime_update(
                "UPDATE admin_device_proof_challenges SET nonce = :nonce "
                "WHERE id = :id",
                nonce="changed-nonce",
                id=challenge_id,
            )
        assert getattr(nonce_change.value.orig, "sqlstate", None) == "42501"

        runtime_update(
            "UPDATE admin_device_proof_challenges SET consumed_at = :consumed_at "
            "WHERE id = :id",
            consumed_at=consumed_at,
            id=challenge_id,
        )
        with pytest.raises(SQLAlchemyError) as unconsume:
            runtime_update(
                "UPDATE admin_device_proof_challenges SET consumed_at = NULL "
                "WHERE id = :id",
                id=challenge_id,
            )
        assert getattr(unconsume.value.orig, "sqlstate", None) == "23514"
        with pytest.raises(SQLAlchemyError) as reconsume:
            runtime_update(
                "UPDATE admin_device_proof_challenges SET consumed_at = :consumed_at "
                "WHERE id = :id",
                consumed_at=consumed_at + timedelta(seconds=1),
                id=challenge_id,
            )
        assert getattr(reconsume.value.orig, "sqlstate", None) == "23514"

        with engine.connect() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            assert connection.execute(
                text(
                    "SELECT has_table_privilege('walksafe_backend_runtime', "
                    "'public.admin_device_proof_challenges', 'UPDATE')"
                )
            ).scalar_one() is False
            assert connection.execute(
                text(
                    "SELECT has_column_privilege('walksafe_backend_runtime', "
                    "'public.admin_device_proof_challenges', 'consumed_at', "
                    "'UPDATE')"
                )
            ).scalar_one() is True
            assert connection.execute(
                text(
                    "SELECT has_column_privilege('walksafe_backend_runtime', "
                    "'public.admin_device_proof_challenges', 'nonce', 'UPDATE')"
                )
            ).scalar_one() is False
            assert connection.execute(
                text(
                    "SELECT has_function_privilege('walksafe_backend_runtime', "
                    "'public.walksafe_require_admin_device_proof_consumption()', "
                    "'EXECUTE')"
                )
            ).scalar_one() is False
    finally:
        with engine.begin() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.execute(
                text("DELETE FROM admin_device_proof_challenges WHERE id = :id"),
                {"id": challenge_id},
            )
        engine.dispose()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_startup_totp_candidate_exact_binding_and_normal_ops_blocked() -> None:
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"], pool_pre_ping=True)
    admin_id = f"startup-candidate-{uuid.uuid4().hex[:12]}"
    transaction_id = uuid.uuid4()
    observed_at = datetime.now(timezone.utc).replace(microsecond=0)
    expires_at = observed_at + timedelta(minutes=10)
    pending_token_sha256 = _sha256("startup-candidate-token")
    current_fingerprint = _sha256(CURRENT_TOTP)

    def classify(
        runtime_totp_secret: str,
        *,
        issuer_key: str = ISSUER_KEY,
    ) -> str:
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                connection.execute(
                    text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
                )
                result = str(
                    connection.execute(
                        text(
                            "SELECT public."
                            "walksafe_classify_admin_startup_totp_binding("
                            ":admin_id, :runtime_totp_secret, :issuer_key)"
                        ),
                        {
                            "admin_id": admin_id,
                            "runtime_totp_secret": runtime_totp_secret,
                            "issuer_key": issuer_key,
                        },
                    ).scalar_one()
                )
                transaction.commit()
                return result
            except Exception:
                transaction.rollback()
                raise

    try:
        with Session(engine) as db:
            provision_admin_security(
                db,
                admin_id=admin_id,
                password="startup candidate password",
                totp_secret=CURRENT_TOTP,
                recovery_codes=["STARTUP-CANDIDATE-RECOVERY-CODE-0001"],
                credential_issuer_key=ISSUER_KEY,
                now=observed_at,
            )
        with engine.begin() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.execute(
                text(
                    "ALTER TABLE admin_security_controls DISABLE TRIGGER "
                    "admin_security_controls_initial_recovery_marker"
                )
            )
            connection.execute(
                text(
                    "ALTER TABLE admin_security_controls DISABLE TRIGGER "
                    "admin_security_controls_initial_recovery_audited"
                )
            )
            recovery_code_id = connection.execute(
                text(
                    "SELECT id FROM admin_security_recovery_codes "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).scalar_one()
            connection.execute(
                text(
                    "UPDATE walksafe_recovery_custody_capabilities SET "
                    "pending_recovery_token_sha256 = :token, "
                    "pending_recovery_expires_at = :expires_at "
                    "WHERE admin_id = :admin_id"
                ),
                {
                    "admin_id": admin_id,
                    "token": pending_token_sha256,
                    "expires_at": expires_at,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO admin_security_recovery_transactions ("
                    "id, admin_id, recovery_code_id, recovery_token_sha256, "
                    "previous_totp_secret_fingerprint, device_id, device_label, "
                    "started_at, expires_at) VALUES ("
                    ":id, :admin_id, :recovery_code_id, :token, :fingerprint, "
                    "'startup-device', 'Startup device', :observed_at, :expires_at)"
                ),
                {
                    "id": transaction_id,
                    "admin_id": admin_id,
                    "recovery_code_id": recovery_code_id,
                    "token": pending_token_sha256,
                    "fingerprint": current_fingerprint,
                    "observed_at": observed_at,
                    "expires_at": expires_at,
                },
            )
            connection.execute(
                text(
                    "UPDATE admin_security_controls SET "
                    "security_state = 'RECOVERY_IN_PROGRESS', "
                    "state_version = state_version + 1, "
                    "updated_at = :observed_at WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id, "observed_at": observed_at},
            )
        with engine.begin() as connection:
            connection.execute(
                text(
                    "ALTER TABLE admin_security_controls ENABLE TRIGGER "
                    "admin_security_controls_initial_recovery_audited"
                )
            )
            connection.execute(
                text(
                    "ALTER TABLE admin_security_controls ENABLE TRIGGER "
                    "admin_security_controls_initial_recovery_marker"
                )
            )

        assert classify(CURRENT_TOTP) == "CURRENT"
        assert classify(CANDIDATE_TOTP, issuer_key="A" * 43) == "INVALID"
        assert classify(CANDIDATE_TOTP) == "RECOVERY_CANDIDATE"
        assert classify(CANDIDATE_TOTP) == "RECOVERY_CANDIDATE"
        assert classify(CURRENT_TOTP) == "INVALID"
        assert classify(OTHER_TOTP) == "INVALID"

        with engine.connect() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            assert connection.execute(
                text(
                    "SELECT pending_next_totp_fingerprint "
                    "FROM walksafe_recovery_custody_capabilities "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).scalar_one() == _sha256(CANDIDATE_TOTP)
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            assert connection.execute(
                text(
                    "SELECT public.walksafe_assert_admin_totp_capability("
                    ":admin_id, :runtime_totp_secret, :issuer_key)"
                ),
                {
                    "admin_id": admin_id,
                    "runtime_totp_secret": CANDIDATE_TOTP,
                    "issuer_key": ISSUER_KEY,
                },
            ).scalar_one() is False

        with engine.begin() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.execute(
                text(
                    "UPDATE walksafe_recovery_custody_capabilities "
                    "SET pending_recovery_expires_at = "
                    "statement_timestamp() - interval '1 second' "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            )
        assert classify(CANDIDATE_TOTP) == "INVALID"

        with engine.begin() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.execute(
                text(
                    "UPDATE walksafe_recovery_custody_capabilities "
                    "SET pending_recovery_expires_at = :expires_at "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id, "expires_at": expires_at},
            )
            second_code_id = uuid.uuid4()
            second_transaction_id = uuid.uuid4()
            connection.execute(
                text(
                    "INSERT INTO admin_security_recovery_codes ("
                    "id, admin_id, code_sha256, created_at) "
                    "VALUES (:id, :admin_id, :code_sha256, :observed_at)"
                ),
                {
                    "id": second_code_id,
                    "admin_id": admin_id,
                    "code_sha256": _sha256("second-startup-recovery-code"),
                    "observed_at": observed_at,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO admin_security_recovery_transactions ("
                    "id, admin_id, recovery_code_id, recovery_token_sha256, "
                    "previous_totp_secret_fingerprint, device_id, device_label, "
                    "started_at, expires_at) VALUES ("
                    ":id, :admin_id, :recovery_code_id, :token, :fingerprint, "
                    "'startup-device-2', 'Startup device 2', :observed_at, "
                    ":expires_at)"
                ),
                {
                    "id": second_transaction_id,
                    "admin_id": admin_id,
                    "recovery_code_id": second_code_id,
                    "token": _sha256("second-startup-token"),
                    "fingerprint": current_fingerprint,
                    "observed_at": observed_at + timedelta(seconds=1),
                    "expires_at": expires_at,
                },
            )
        assert classify(CANDIDATE_TOTP) == "INVALID"
    finally:
        with engine.begin() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            for table_name in (
                "admin_security_recovery_transactions",
                "admin_security_recovery_codes",
                "walksafe_recovery_custody_capabilities",
                "admin_security_controls",
            ):
                connection.execute(
                    text(f"DELETE FROM {table_name} WHERE admin_id = :admin_id"),
                    {"admin_id": admin_id},
                )
        engine.dispose()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_initial_unattested_recovery_requires_same_transaction_audit(
    tmp_path: Path,
) -> None:
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"], pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    admin_id = f"initial-unattested-{uuid.uuid4().hex[:12]}"
    recovery_code = "INITIAL-UNATTESTED-RECOVERY-0001"
    observed_at = datetime.now(timezone.utc).replace(microsecond=0)
    issuer_directory = tmp_path / "issuer-authority"
    issuer_directory.mkdir(mode=0o700)
    issuer_key_file = issuer_directory / "issuer.key"
    issuer_key_file.write_text(ISSUER_KEY, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_id=admin_id,
        admin_device_proof_enabled=False,
        admin_credential_issuer_key_file=issuer_key_file,
        admin_totp_secret=CURRENT_TOTP,
        admin_recovery_ttl_seconds=900,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
        walksafe_environment="test",
    )

    def run_as_runtime(callback):
        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            try:
                with sessionmaker(
                    bind=connection,
                    expire_on_commit=False,
                )() as db:
                    return callback(db)
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    try:
        with SessionFactory() as db:
            provision_admin_security(
                db,
                admin_id=admin_id,
                password="initial unattested password",
                totp_secret=CURRENT_TOTP,
                recovery_codes=[recovery_code],
                credential_issuer_key=ISSUER_KEY,
                now=observed_at,
            )

        def start_without_audit(db: Session):
            service = AdminSecurityService(db, settings)
            service._audit = lambda *_args, **_kwargs: None
            return service.start_recovery(
                admin_id=admin_id,
                recovery_code=recovery_code,
                device_id="initial-unattested-device",
                device_label="Initial unattested device",
                source="198.51.100.30",
                now=observed_at + timedelta(seconds=1),
            )

        with pytest.raises(AdminSecurityStoreUnavailable) as missing_audit:
            run_as_runtime(start_without_audit)
        database_error = missing_audit.value.__cause__
        assert isinstance(database_error, SQLAlchemyError)
        assert getattr(database_error.orig, "sqlstate", None) == "23514"

        with engine.connect() as connection:
            assert connection.execute(
                text(
                    "SELECT security_state, state_version "
                    "FROM admin_security_controls WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).one() == ("NORMAL", 1)
            assert connection.execute(
                text(
                    "SELECT count(*) FROM admin_security_recovery_transactions "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).scalar_one() == 0
            assert connection.execute(
                text(
                    "SELECT count(*) FROM admin_security_audits "
                    "WHERE admin_id = :admin_id AND action = 'recovery.start'"
                ),
                {"admin_id": admin_id},
            ).scalar_one() == 0
            assert connection.execute(
                text(
                    "SELECT count(*) FROM walksafe_recovery_custody_markers "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).scalar_one() == 0

        recovery_grant = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).start_recovery(
                admin_id=admin_id,
                recovery_code=recovery_code,
                device_id="initial-unattested-device",
                device_label="Initial unattested device",
                source="198.51.100.30",
                now=observed_at + timedelta(seconds=2),
            )
        )
        assert recovery_grant.security_state == "RECOVERY_IN_PROGRESS"

        with engine.connect() as connection:
            assert connection.execute(
                text(
                    "SELECT security_state, recovery_custody_state "
                    "FROM admin_security_controls WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).one() == ("RECOVERY_IN_PROGRESS", "UNATTESTED")
            assert connection.execute(
                text(
                    "SELECT count(*) FROM admin_security_recovery_transactions "
                    "WHERE admin_id = :admin_id AND completed_at IS NULL"
                ),
                {"admin_id": admin_id},
            ).scalar_one() == 1
            assert connection.execute(
                text(
                    "SELECT count(*) FROM admin_security_audits "
                    "WHERE admin_id = :admin_id AND action = 'recovery.start' "
                    "AND outcome = 'SUCCESS'"
                ),
                {"admin_id": admin_id},
            ).scalar_one() == 1
            assert connection.execute(
                text(
                    "SELECT count(*) FROM walksafe_recovery_custody_markers "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            ).scalar_one() == 0
    finally:
        with engine.begin() as connection:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.execute(
                text(
                    "ALTER TABLE admin_security_auth_attempts "
                    "DISABLE TRIGGER USER"
                )
            )
            connection.execute(
                text(
                    "DELETE FROM admin_security_auth_attempts "
                    "WHERE action = 'recovery_start' "
                    "AND principal_sha256 = :principal_sha256"
                ),
                {"principal_sha256": _sha256(admin_id)},
            )
            connection.execute(
                text(
                    "ALTER TABLE admin_security_auth_attempts "
                    "ENABLE TRIGGER USER"
                )
            )
            for table_name in (
                "walksafe_recovery_custody_markers",
                "admin_security_audits",
                "admin_security_recovery_transactions",
                "admin_security_recovery_codes",
                "walksafe_recovery_custody_capabilities",
                "admin_security_controls",
            ):
                connection.execute(
                    text(f"ALTER TABLE {table_name} DISABLE TRIGGER USER")
                )
                connection.execute(
                    text(f"DELETE FROM {table_name} WHERE admin_id = :admin_id"),
                    {"admin_id": admin_id},
                )
                connection.execute(
                    text(f"ALTER TABLE {table_name} ENABLE TRIGGER USER")
                )
        engine.dispose()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_runtime_acl_migration_downgrade_and_reupgrade_on_fresh_database(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    configured_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    database_name = f"walksafe_acl_migration_test_{uuid.uuid4().hex}"
    assert database_name.startswith("walksafe_acl_migration_test_")
    disposable_url = configured_url.set(database=database_name)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    disposable_engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted_database_name = admin_engine.dialect.identifier_preparer.quote(database_name)
    created = False
    try:
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            current_database = connection.execute(
                text("SELECT current_database()")
            ).scalar_one()
            assert "test" in current_database.lower()
            connection.execute(text(f"CREATE DATABASE {quoted_database_name}"))
            created = True

        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT to_regclass('public.alembic_version')")
            ).scalar_one() is None

        migration_url = disposable_url.render_as_string(hide_password=False)
        monkeypatch.setenv("DATABASE_URL", migration_url)
        monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
        config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
        command.upgrade(config, "head")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608130001"
            assert connection.execute(
                text(
                    "SELECT EXISTS (SELECT 1 FROM pg_extension "
                    "WHERE extname = 'postgis')"
                )
            ).scalar_one() is True
            assert connection.execute(
                text(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                    "WHERE table_schema = 'public' "
                    "AND table_name = "
                    "'walksafe_recovery_custody_capabilities' "
                    "AND column_name = 'pending_next_totp_fingerprint')"
                )
            ).scalar_one() is True
            for signature in (
                "walksafe_lock_admin_security_control(text,text)",
                (
                    "walksafe_lock_admin_original_access_session("
                    "text,uuid,text,timestamptz,text,text)"
                ),
                (
                    "walksafe_lock_admin_device_proof_context("
                    "text,text,bigint,text,timestamptz,text,text,text)"
                ),
            ):
                assert connection.execute(
                    text("SELECT to_regprocedure(:signature)"),
                    {"signature": f"public.{signature}"},
                ).scalar_one() is not None
            assert connection.execute(
                text(
                    "SELECT to_regprocedure("
                    "'public.walksafe_lock_admin_security_control()')"
                )
            ).scalar_one() is None

        command.downgrade(config, "202608120001")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608120001"
            assert connection.execute(
                text(
                    "SELECT to_regprocedure("
                    "'public.walksafe_require_initial_unattested_recovery_audit()')"
                )
            ).scalar_one() is None

        command.upgrade(config, "head")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608130001"
            assert connection.execute(
                text(
                    "SELECT count(*) FROM pg_trigger "
                    "WHERE tgrelid = 'public.admin_security_controls'::regclass "
                    "AND tgname = "
                    "'admin_security_controls_initial_recovery_audited' "
                    "AND NOT tgisinternal"
                )
            ).scalar_one() == 1
    finally:
        disposable_engine.dispose()
        if created:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.execute(
                    text(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        "WHERE datname = :database_name AND pid <> pg_backend_pid()"
                    ),
                    {"database_name": database_name},
                )
                connection.execute(
                    text(f"DROP DATABASE IF EXISTS {quoted_database_name}")
                )
        admin_engine.dispose()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_runtime_acl_migration_rejects_reduced_same_revision_predecessor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    configured_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    database_name = f"walksafe_acl_reduced_test_{uuid.uuid4().hex}"
    assert database_name.startswith("walksafe_acl_reduced_test_")
    disposable_url = configured_url.set(database=database_name)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    disposable_engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted_database_name = admin_engine.dialect.identifier_preparer.quote(database_name)
    created = False
    try:
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            current_database = connection.execute(
                text("SELECT current_database()")
            ).scalar_one()
            assert "test" in current_database.lower()
            connection.execute(text(f"CREATE DATABASE {quoted_database_name}"))
            created = True

        migration_url = disposable_url.render_as_string(hide_password=False)
        monkeypatch.setenv("DATABASE_URL", migration_url)
        monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
        config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
        command.upgrade(config, "202608120001")

        with disposable_engine.begin() as connection:
            for constraint_name in (
                "ck_walksafe_recovery_custody_capability_pending_next_binding",
                "ck_walksafe_recovery_custody_capability_pending_next_is_new",
                "ck_walksafe_recovery_custody_capability_pending_next_totp",
            ):
                connection.execute(
                    text(
                        "ALTER TABLE walksafe_recovery_custody_capabilities "
                        f"DROP CONSTRAINT {constraint_name}"
                    )
                )
            connection.execute(
                text(
                    "ALTER TABLE walksafe_recovery_custody_capabilities "
                    "DROP COLUMN pending_next_totp_fingerprint"
                )
            )
            connection.execute(
                text(
                    "DROP FUNCTION walksafe_lock_admin_device_proof_context("
                    "text, text, bigint, text, timestamptz, text, text, text)"
                )
            )
            connection.execute(
                text(
                    "DROP FUNCTION walksafe_lock_admin_original_access_session("
                    "text, uuid, text, timestamptz, text, text)"
                )
            )
            connection.execute(
                text(
                    "DROP FUNCTION walksafe_lock_admin_security_control(text, text)"
                )
            )
            connection.execute(
                text(
                    "CREATE FUNCTION walksafe_lock_admin_security_control() "
                    "RETURNS text AS $$ BEGIN RETURN NULL; END; $$ "
                    "LANGUAGE plpgsql SECURITY DEFINER "
                    "SET search_path = pg_catalog, pg_temp"
                )
            )
            connection.execute(
                text(
                    "REVOKE ALL ON FUNCTION "
                    "walksafe_lock_admin_security_control() FROM PUBLIC"
                )
            )
            connection.execute(
                text(
                    "GRANT EXECUTE ON FUNCTION "
                    "walksafe_lock_admin_security_control() "
                    "TO walksafe_backend_runtime"
                )
            )

        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608120001"

        with pytest.raises(RuntimeError) as rejected:
            command.upgrade(config, "head")
        assert "does not match the current predecessor contract" in str(rejected.value)
        assert "recreate it with the current migrations" in str(rejected.value)

        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608120001"
            assert connection.execute(
                text(
                    "SELECT to_regprocedure("
                    "'public.walksafe_lock_admin_security_control()')"
                )
            ).scalar_one() is not None
            assert connection.execute(
                text(
                    "SELECT to_regprocedure("
                    "'public.walksafe_lock_admin_security_control(text,text)')"
                )
            ).scalar_one() is None
            assert connection.execute(
                text(
                    "SELECT to_regprocedure("
                    "'public.walksafe_classify_admin_startup_totp_binding("
                    "text,text,text)')"
                )
            ).scalar_one() is None
            assert connection.execute(
                text(
                    "SELECT count(*) FROM pg_trigger "
                    "WHERE tgrelid = "
                    "'public.admin_device_proof_challenges'::regclass "
                    "AND tgname = "
                    "'admin_device_proof_challenges_consume_once' "
                    "AND NOT tgisinternal"
                )
            ).scalar_one() == 0
    finally:
        disposable_engine.dispose()
        if created:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.execute(
                    text(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        "WHERE datname = :database_name AND pid <> pg_backend_pid()"
                    ),
                    {"database_name": database_name},
                )
                connection.execute(
                    text(f"DROP DATABASE IF EXISTS {quoted_database_name}")
                )
        admin_engine.dispose()
