from __future__ import annotations

import os
from pathlib import Path
import importlib
import uuid

from alembic import command
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.operations import Operations
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError

from backend.app.api.health import _database_readiness
from backend.app.services.admin_report_integrity import (
    admin_report_integrity_boundary_state,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _set_migration_url(monkeypatch: pytest.MonkeyPatch, url: str) -> Config:
    monkeypatch.setenv("DATABASE_URL", url)
    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", url)
    return Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))


def _runtime_report_insert_sql(report_id: uuid.UUID) -> tuple[object, dict[str, object]]:
    return (
        text(
            "INSERT INTO public.reports ("
            "id, class_id, class_name, confidence, bbox_x, bbox_y, "
            "bbox_width, bbox_height, captured_at, source, image_path, "
            "image_content_type, metadata) VALUES ("
            ":report_id, 0, 'damaged_tactile_block', 0.9, 0.1, 0.1, "
            "0.5, 0.5, clock_timestamp(), 'android', :image_path, "
            "'image/jpeg', '{}'::jsonb)"
        ),
        {
            "report_id": report_id,
            "image_path": f"/uploads/{report_id}.jpg",
        },
    )


def test_integrity_successor_role_graph_login_and_downgrade_boundaries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    configured_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    database_name = f"walksafe_admin_integrity_migration_test_{uuid.uuid4().hex}"
    disposable_url = configured_url.set(database=database_name)
    migration_url = disposable_url.render_as_string(hide_password=False)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    disposable_engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted_database = admin_engine.dialect.identifier_preparer.quote(database_name)
    suffix = uuid.uuid4().hex[:12]
    bridge_role = f"walksafe_integrity_test_bridge_{suffix}"
    attacker_role = f"walksafe_integrity_test_attacker_{suffix}"
    inherit_login = f"walksafe_integrity_test_inherit_{suffix}"
    set_login = f"walksafe_integrity_test_set_{suffix}"
    transitive_login = f"walksafe_integrity_test_transitive_{suffix}"
    roles = [
        attacker_role,
        bridge_role,
        inherit_login,
        set_login,
        transitive_login,
    ]
    created_database = False

    def drop_roles() -> None:
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            for role in roles:
                connection.exec_driver_sql(f"DROP ROLE IF EXISTS {role}")

    try:
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            assert "test" in connection.execute(
                text("SELECT current_database()")
            ).scalar_one().lower()
            connection.exec_driver_sql(f"CREATE DATABASE {quoted_database}")
            created_database = True

        config = _set_migration_url(monkeypatch, migration_url)
        command.upgrade(config, "202608300004")

        safe_owner_attributes = {
            "rolsuper": False,
            "rolcreaterole": False,
            "rolcreatedb": False,
            "rolcanlogin": False,
            "rolinherit": False,
            "rolreplication": False,
            "rolbypassrls": False,
        }
        safe_owner_membership = {
            "admin_option": False,
            "inherit_option": True,
            "set_option": True,
        }

        def read_owner_contract() -> tuple[dict[str, object], list[dict[str, object]]]:
            with admin_engine.connect() as connection:
                attributes = dict(
                    connection.execute(
                        text(
                            "SELECT rolsuper, rolcreaterole, rolcreatedb, "
                            "rolcanlogin, rolinherit, rolreplication, rolbypassrls "
                            "FROM pg_catalog.pg_roles WHERE rolname = "
                            "'walksafe_report_evidence_owner'"
                        )
                    ).mappings().one()
                )
                memberships = [
                    dict(row)
                    for row in connection.execute(
                        text(
                            "SELECT membership.admin_option, "
                            "membership.inherit_option, membership.set_option "
                            "FROM pg_catalog.pg_auth_members AS membership "
                            "WHERE membership.roleid = "
                            "'walksafe_report_evidence_owner'::regrole::oid "
                            "AND membership.member = current_user::regrole::oid"
                        )
                    ).mappings()
                ]
            return attributes, memberships

        def execute_role_change(statement: str) -> None:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.exec_driver_sql(statement)

        def assert_owner_drift_rejected(
            mutation: str,
            repair: str,
            *,
            expected_attributes: dict[str, object] = safe_owner_attributes,
            expected_membership: dict[str, object] = safe_owner_membership,
        ) -> None:
            execute_role_change(mutation)
            try:
                attributes, memberships = read_owner_contract()
                assert attributes == expected_attributes
                assert memberships == [expected_membership]
                with pytest.raises(SQLAlchemyError) as rejected:
                    command.upgrade(config, "202608300005")
                assert getattr(rejected.value.orig, "sqlstate", None) == "42501"
                with disposable_engine.connect() as connection:
                    assert connection.execute(
                        text("SELECT version_num FROM alembic_version")
                    ).scalar_one() == "202608300004"
            finally:
                execute_role_change(repair)
            assert read_owner_contract() == (
                safe_owner_attributes,
                [safe_owner_membership],
            )

        assert read_owner_contract() == (
            safe_owner_attributes,
            [safe_owner_membership],
        )
        assert_owner_drift_rejected(
            "ALTER ROLE walksafe_report_evidence_owner INHERIT",
            "ALTER ROLE walksafe_report_evidence_owner NOINHERIT",
            expected_attributes={**safe_owner_attributes, "rolinherit": True},
        )
        exact_membership_grant = (
            "GRANT walksafe_report_evidence_owner TO CURRENT_USER "
            "WITH ADMIN FALSE, INHERIT TRUE, SET TRUE"
        )
        for unsafe_options, expected_membership in (
            (
                "WITH ADMIN TRUE, INHERIT TRUE, SET TRUE",
                {**safe_owner_membership, "admin_option": True},
            ),
            (
                "WITH ADMIN FALSE, INHERIT FALSE, SET TRUE",
                {**safe_owner_membership, "inherit_option": False},
            ),
            (
                "WITH ADMIN FALSE, INHERIT TRUE, SET FALSE",
                {**safe_owner_membership, "set_option": False},
            ),
        ):
            assert_owner_drift_rejected(
                "GRANT walksafe_report_evidence_owner TO CURRENT_USER "
                + unsafe_options,
                exact_membership_grant,
                expected_membership=expected_membership,
            )

        owner_privilege_sql = text(
            "SELECT has_table_privilege('walksafe_report_evidence_owner', "
            ":relation, :privilege)"
        )
        added_privileges = [
            ("public.admin_device_proof_challenges", "SELECT"),
            ("public.admin_security_reconfirmations", "SELECT"),
            ("public.privacy_consent_events", "SELECT"),
            ("public.account_deletion_tombstones", "SELECT"),
            ("public.report_content_revisions", "SELECT"),
            ("public.report_delivery_packages", "SELECT"),
            ("public.report_institution_delivery_events", "SELECT"),
            ("public.report_export_audits", "SELECT"),
            ("public.report_delivery_packages", "INSERT"),
            ("public.report_institution_delivery_events", "INSERT"),
        ]
        with disposable_engine.connect() as connection:
            predecessor_privileges = [
                connection.execute(
                    owner_privilege_sql,
                    {"relation": relation, "privilege": privilege},
                ).scalar_one()
                for relation, privilege in added_privileges
            ]

        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            connection.exec_driver_sql(f"CREATE ROLE {bridge_role} NOLOGIN")
            connection.exec_driver_sql(
                f"CREATE ROLE {attacker_role} LOGIN NOSUPERUSER NOCREATEDB "
                "NOCREATEROLE NOREPLICATION NOBYPASSRLS"
            )
            connection.exec_driver_sql(
                f"GRANT walksafe_report_evidence_owner TO {bridge_role}"
            )
            connection.exec_driver_sql(f"GRANT {bridge_role} TO {attacker_role}")
        with pytest.raises(SQLAlchemyError) as membership_rejected:
            command.upgrade(config, "202608300005")
        assert getattr(membership_rejected.value.orig, "sqlstate", None) == "42501"
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608300004"

        drop_roles()
        command.upgrade(config, "202608300005")
        assert read_owner_contract() == (
            safe_owner_attributes,
            [safe_owner_membership],
        )

        with disposable_engine.connect() as connection:
            assert admin_report_integrity_boundary_state(connection) is True
        assert _database_readiness(migration_url) == {
            "ready": False,
            "reason": "migration_not_at_head",
            "current_revision": "202608300005",
            "expected_revision": "202609010002",
        }

        with disposable_engine.begin() as connection:
            connection.execute(
                text(
                    "GRANT INSERT ON TABLE public.report_review_decisions "
                    "TO walksafe_backend_runtime"
                )
            )
        try:
            with disposable_engine.connect() as connection:
                assert admin_report_integrity_boundary_state(connection) is False
            assert _database_readiness(migration_url) == {
                "ready": False,
                "reason": "migration_not_at_head",
                "current_revision": "202608300005",
                "expected_revision": "202609010002",
            }
        finally:
            with disposable_engine.begin() as connection:
                connection.execute(
                    text(
                        "REVOKE INSERT ON TABLE public.report_review_decisions "
                        "FROM walksafe_backend_runtime"
                    )
                )

        with disposable_engine.connect() as connection:
            transaction = connection.begin()
            connection.execute(
                text(
                    "GRANT EXECUTE ON FUNCTION "
                    "public.walksafe_append_report_review_decision_v3("
                    "uuid,uuid,bigint,text,text,text,uuid,boolean,boolean,"
                    "boolean,uuid,text,uuid,text,uuid,uuid,bytea,text,text) "
                    "TO PUBLIC"
                )
            )
            assert admin_report_integrity_boundary_state(connection) is False
            transaction.rollback()

        with disposable_engine.connect() as connection:
            transaction = connection.begin()
            connection.execute(
                text(
                    "ALTER FUNCTION "
                    "public.walksafe_append_report_review_decision_v3("
                    "uuid,uuid,bigint,text,text,text,uuid,boolean,boolean,"
                    "boolean,uuid,text,uuid,text,uuid,uuid,bytea,text,text) "
                    "OWNER TO CURRENT_USER"
                )
            )
            assert admin_report_integrity_boundary_state(connection) is False
            transaction.rollback()

        old_issue_signature = (
            "public.walksafe_issue_report_original_evidence_grant("
            "uuid,uuid,uuid,uuid,text,uuid,text,text,text,text,bigint,"
            "timestamptz,timestamptz,text,text)"
        )
        with disposable_engine.begin() as connection:
            connection.execute(
                text(
                    f"GRANT EXECUTE ON FUNCTION {old_issue_signature} "
                    "TO walksafe_backend_runtime"
                )
            )
        try:
            with disposable_engine.connect() as connection:
                assert admin_report_integrity_boundary_state(connection) is False
            assert _database_readiness(migration_url) == {
                "ready": False,
                "reason": "migration_not_at_head",
                "current_revision": "202608300005",
                "expected_revision": "202609010002",
            }
        finally:
            with disposable_engine.begin() as connection:
                connection.execute(
                    text(
                        f"REVOKE EXECUTE ON FUNCTION {old_issue_signature} "
                        "FROM walksafe_backend_runtime"
                    )
                )

        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            connection.exec_driver_sql(
                f"CREATE ROLE {attacker_role} LOGIN NOSUPERUSER NOCREATEDB "
                "NOCREATEROLE NOREPLICATION NOBYPASSRLS"
            )
        try:
            with disposable_engine.connect() as connection:
                transaction = connection.begin()
                connection.execute(
                    text(
                        f"GRANT EXECUTE ON FUNCTION {old_issue_signature} "
                        f"TO {attacker_role}"
                    )
                )
                assert admin_report_integrity_boundary_state(connection) is False
                transaction.rollback()
            for third_party_dml in (
                (
                    "GRANT INSERT ON TABLE public.report_review_decisions "
                    f"TO {attacker_role}"
                ),
                (
                    "GRANT UPDATE (reason) ON TABLE "
                    "public.report_review_decisions "
                    f"TO {attacker_role}"
                ),
                f"GRANT INSERT ON TABLE public.reports TO {attacker_role}",
                (
                    "GRANT INSERT (source) ON TABLE public.reports "
                    f"TO {attacker_role}"
                ),
                (
                    "GRANT SELECT ON TABLE public.report_original_access_grants "
                    f"TO {attacker_role}"
                ),
                (
                    "GRANT SELECT (storage_name) ON TABLE "
                    "public.report_image_objects "
                    f"TO {attacker_role}"
                ),
                f"GRANT TRIGGER ON TABLE public.reports TO {attacker_role}",
                (
                    "GRANT REFERENCES (id) ON TABLE public.reports "
                    f"TO {attacker_role}"
                ),
                (
                    "GRANT INSERT ON TABLE public.privacy_consent_events "
                    f"TO {attacker_role}"
                ),
                (
                    "GRANT UPDATE ON TABLE public.admin_security_controls "
                    f"TO {attacker_role}"
                ),
            ):
                with disposable_engine.connect() as connection:
                    transaction = connection.begin()
                    connection.execute(text(third_party_dml))
                    assert (
                        admin_report_integrity_boundary_state(connection) is False
                    )
                    transaction.rollback()
        finally:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.exec_driver_sql(f"DROP ROLE {attacker_role}")

        boundary_drift_statements = (
            "ALTER ROLE walksafe_backend_runtime LOGIN",
            "ALTER ROLE walksafe_backend_runtime CREATEROLE",
            (
                "GRANT walksafe_report_deletion_worker "
                "TO walksafe_backend_runtime"
            ),
            "ALTER ROLE walksafe_report_deletion_worker LOGIN",
            (
                "GRANT EXECUTE ON FUNCTION "
                "public.walksafe_claim_admin_report_mutation("
                "uuid,uuid,text,uuid,text,uuid,text,text,text,uuid,bytea,"
                "text,text,text) TO walksafe_backend_runtime"
            ),
            (
                "ALTER FUNCTION "
                "public.walksafe_enforce_runtime_report_ingest_integrity() "
                "SECURITY INVOKER"
            ),
            "ALTER TABLE public.reports DISABLE TRIGGER "
            "reports_runtime_ingest_integrity",
            (
                "ALTER TABLE public.report_review_decisions DROP CONSTRAINT "
                "ck_report_review_decisions_evidence_grant"
            ),
            "DROP INDEX public.uq_admin_report_mutation_claim_resource",
            (
                "ALTER TABLE public.admin_report_mutation_claims "
                "DROP CONSTRAINT admin_report_mutation_claims_pkey"
            ),
            (
                "ALTER TABLE public.admin_report_mutation_claims "
                "DROP CONSTRAINT ck_admin_report_mutation_claim_action"
            ),
            f"DROP FUNCTION {old_issue_signature}",
            f"ALTER FUNCTION {old_issue_signature} SECURITY INVOKER",
            (
                f"REVOKE EXECUTE ON FUNCTION {old_issue_signature} "
                "FROM walksafe_report_evidence_owner"
            ),
            (
                "REVOKE EXECUTE ON FUNCTION "
                "public.walksafe_prepare_report_original_evidence_access_v3("
                "uuid,text,text,uuid,text,text,text) "
                "FROM walksafe_report_evidence_owner"
            ),
            (
                "REVOKE EXECUTE ON FUNCTION "
                "public.walksafe_claim_admin_report_mutation("
                "uuid,uuid,text,uuid,text,uuid,text,text,text,uuid,bytea,"
                "text,text,text) FROM walksafe_report_evidence_owner"
            ),
            "DROP FUNCTION public.walksafe_python_strip(text)",
            (
                "ALTER FUNCTION public.walksafe_python_split_join(text) "
                "OWNER TO CURRENT_USER"
            ),
            (
                "GRANT EXECUTE ON FUNCTION public.walksafe_python_strip(text) "
                "TO walksafe_backend_runtime"
            ),
            (
                "DROP FUNCTION public.walksafe_lock_admin_original_access_session("
                "text,uuid,text,timestamptz,text,text)"
            ),
            (
                "ALTER FUNCTION "
                "public.walksafe_lock_admin_original_access_session("
                "text,uuid,text,timestamptz,text,text) "
                "OWNER TO walksafe_report_evidence_owner"
            ),
            (
                "REVOKE EXECUTE ON FUNCTION "
                "public.walksafe_lock_admin_original_access_session("
                "text,uuid,text,timestamptz,text,text) "
                "FROM walksafe_report_evidence_owner"
            ),
            (
                "DROP FUNCTION "
                "public.walksafe_assert_admin_totp_capability(text,text,text)"
            ),
            (
                "REVOKE EXECUTE ON FUNCTION "
                "public.walksafe_assert_admin_totp_capability(text,text,text) "
                "FROM walksafe_backend_runtime"
            ),
            (
                "CREATE OR REPLACE FUNCTION "
                "public.walksafe_enforce_runtime_report_ingest_integrity() "
                "RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER "
                "SET search_path = pg_catalog, pg_temp "
                "AS 'BEGIN RETURN NEW; END'"
            ),
            (
                "REVOKE INSERT ON TABLE public.report_delivery_packages "
                "FROM walksafe_report_evidence_owner"
            ),
            (
                "GRANT DELETE ON TABLE public.report_delivery_packages "
                "TO walksafe_report_evidence_owner"
            ),
            (
                "GRANT UPDATE (reason) ON TABLE public.report_review_decisions "
                "TO walksafe_report_evidence_owner"
            ),
            (
                "REVOKE SELECT ON TABLE public.report_export_audits "
                "FROM walksafe_report_evidence_owner"
            ),
            (
                "REVOKE USAGE ON SCHEMA public "
                "FROM walksafe_report_evidence_owner"
            ),
            (
                "GRANT CREATE ON SCHEMA public "
                "TO walksafe_report_evidence_owner"
            ),
            (
                "REVOKE SELECT ON TABLE public.report_delivery_packages "
                "FROM walksafe_backend_runtime"
            ),
            (
                "REVOKE INSERT ON TABLE public.report_image_objects "
                "FROM walksafe_backend_runtime"
            ),
            (
                "REVOKE SELECT ON TABLE public.reports "
                "FROM walksafe_backend_runtime"
            ),
            (
                "REVOKE UPDATE ON TABLE public.reports "
                "FROM walksafe_backend_runtime"
            ),
            (
                "REVOKE USAGE ON SCHEMA public "
                "FROM walksafe_backend_runtime"
            ),
            "GRANT CREATE ON SCHEMA public TO walksafe_backend_runtime",
            (
                "REVOKE INSERT ON TABLE public.report_export_audits "
                "FROM walksafe_backend_runtime"
            ),
            (
                "REVOKE UPDATE (consumed_at) ON TABLE "
                "public.admin_device_proof_challenges "
                "FROM walksafe_backend_runtime"
            ),
        )
        for drift_statement in boundary_drift_statements:
            with disposable_engine.connect() as connection:
                transaction = connection.begin()
                connection.execute(text(drift_statement))
                assert (
                    admin_report_integrity_boundary_state(connection) is False
                ), drift_statement
                transaction.rollback()

        with disposable_engine.connect() as connection:
            transaction = connection.begin()
            connection.execute(
                text(
                    "ALTER TABLE public.admin_report_mutation_claims "
                    "DROP CONSTRAINT "
                    "ck_admin_report_mutation_claim_resource_type"
                )
            )
            connection.execute(
                text(
                    "ALTER TABLE public.admin_report_mutation_claims "
                    "ADD CONSTRAINT "
                    "ck_admin_report_mutation_claim_resource_type "
                    "CHECK (resource_type IN ('original_access_grant', "
                    "'review_decision', 'delivery_package', "
                    "'delivery_event')) NOT VALID"
                )
            )
            assert admin_report_integrity_boundary_state(connection) is False
            transaction.rollback()

        with disposable_engine.connect() as connection:
            transaction = connection.begin()
            connection.execute(
                text(
                    "ALTER TABLE public.admin_report_mutation_claims "
                    "DROP CONSTRAINT "
                    "fk_admin_report_mutation_claim_reconfirmation"
                )
            )
            connection.execute(
                text(
                    "ALTER TABLE public.admin_report_mutation_claims "
                    "ADD CONSTRAINT "
                    "fk_admin_report_mutation_claim_reconfirmation "
                    "FOREIGN KEY (reconfirmation_id) REFERENCES "
                    "public.admin_security_reconfirmations(id) "
                    "ON DELETE RESTRICT NOT VALID"
                )
            )
            assert admin_report_integrity_boundary_state(connection) is False
            transaction.rollback()

        trigger_variants = (
            (
                "BEFORE DELETE ON public.reports FOR EACH ROW EXECUTE FUNCTION "
                "public.walksafe_enforce_runtime_report_ingest_integrity()"
            ),
            (
                "BEFORE INSERT ON public.reports FOR EACH ROW WHEN (false) "
                "EXECUTE FUNCTION "
                "public.walksafe_enforce_runtime_report_ingest_integrity()"
            ),
        )
        for trigger_variant in trigger_variants:
            with disposable_engine.connect() as connection:
                transaction = connection.begin()
                connection.execute(
                    text(
                        "DROP TRIGGER reports_runtime_ingest_integrity "
                        "ON public.reports"
                    )
                )
                connection.execute(
                    text(
                        "CREATE TRIGGER reports_runtime_ingest_integrity "
                        + trigger_variant
                    )
                )
                assert admin_report_integrity_boundary_state(connection) is False
                transaction.rollback()

        with disposable_engine.connect() as connection:
            assert admin_report_integrity_boundary_state(connection) is True

        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            connection.exec_driver_sql(
                f"CREATE ROLE {inherit_login} LOGIN INHERIT NOSUPERUSER "
                "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"
            )
            connection.exec_driver_sql(
                f"GRANT walksafe_backend_runtime TO {inherit_login} "
                "WITH SET TRUE, INHERIT TRUE"
            )
            connection.exec_driver_sql(
                f"CREATE ROLE {set_login} LOGIN NOINHERIT NOSUPERUSER "
                "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"
            )
            connection.exec_driver_sql(
                f"GRANT walksafe_backend_runtime TO {set_login} "
                "WITH SET TRUE, INHERIT FALSE"
            )
            connection.exec_driver_sql(
                f"CREATE ROLE {bridge_role} NOLOGIN INHERIT NOSUPERUSER "
                "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"
            )
            connection.exec_driver_sql(
                f"GRANT walksafe_backend_runtime TO {bridge_role} "
                "WITH SET FALSE, INHERIT TRUE"
            )
            connection.exec_driver_sql(
                f"CREATE ROLE {transitive_login} LOGIN NOINHERIT NOSUPERUSER "
                "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"
            )
            connection.exec_driver_sql(
                f"GRANT {bridge_role} TO {transitive_login} "
                "WITH SET TRUE, INHERIT FALSE"
            )

        for login_role, role_to_set in (
            (inherit_login, None),
            (set_login, "walksafe_backend_runtime"),
            (transitive_login, bridge_role),
        ):
            statement, parameters = _runtime_report_insert_sql(uuid.uuid4())
            with disposable_engine.connect() as connection:
                connection.exec_driver_sql(
                    f"SET SESSION AUTHORIZATION {login_role}"
                )
                connection.commit()
                try:
                    if role_to_set is not None:
                        connection.exec_driver_sql(f"SET ROLE {role_to_set}")
                    with pytest.raises(SQLAlchemyError) as insert_rejected:
                        connection.execute(statement, parameters)
                    assert getattr(insert_rejected.value.orig, "sqlstate", None) == (
                        "23514"
                    )
                    connection.rollback()
                finally:
                    connection.exec_driver_sql("RESET SESSION AUTHORIZATION")
                    connection.commit()

        challenge_id = uuid.uuid4()
        claim_id = uuid.uuid4()
        claim_report_id = uuid.uuid4()
        claim_session_id = uuid.uuid4()
        claim_correlation_id = uuid.uuid4()
        with disposable_engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO public.admin_device_proof_challenges ("
                    "id, challenge_type, action, admin_id, body_sha256, "
                    "correlation_id, device_id, device_key_marker, "
                    "device_key_version, expires_at, issued_at, method, nonce, "
                    "purpose, path, query_sha256, read_purpose, schema_version, "
                    "session_id, signing_payload, consumed_at) VALUES ("
                    ":challenge_id, 'ACTION', 'report.original.grant', "
                    "'walksafe.admin', :body_sha256, :correlation_id, "
                    "'android-admin-test', :device_key_marker, 1, "
                    "statement_timestamp() + interval '5 minutes', "
                    "statement_timestamp(), 'POST', :nonce, 'ACTION', "
                    "'/admin/reports/original-access-grants', :query_sha256, "
                    "NULL, 'walksafe.admin.device-proof.v1', :session_id, "
                    "'{}', statement_timestamp())"
                ),
                {
                    "challenge_id": challenge_id,
                    "body_sha256": "a" * 64,
                    "correlation_id": claim_correlation_id,
                    "device_key_marker": "b" * 64,
                    "nonce": uuid.uuid4().hex,
                    "query_sha256": "c" * 64,
                    "session_id": claim_session_id,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO public.admin_report_mutation_claims ("
                    "challenge_id, reconfirmation_id, report_id, resource_type, "
                    "resource_id, action, admin_id, session_id, device_id, "
                    "correlation_id) VALUES ("
                    ":challenge_id, NULL, :report_id, 'original_access_grant', "
                    ":resource_id, 'report.original.grant', 'walksafe.admin', "
                    ":session_id, 'android-admin-test', :correlation_id)"
                ),
                {
                    "challenge_id": challenge_id,
                    "report_id": claim_report_id,
                    "resource_id": claim_id,
                    "session_id": claim_session_id,
                    "correlation_id": claim_correlation_id,
                },
            )

        with pytest.raises(SQLAlchemyError) as downgrade_rejected:
            command.downgrade(config, "202608300004")
        assert getattr(downgrade_rejected.value.orig, "sqlstate", None) == "55000"
        with disposable_engine.connect() as connection:
            preserved_state = connection.execute(
                text(
                    "SELECT "
                    "(SELECT version_num FROM public.alembic_version) AS revision, "
                    "(SELECT count(*) FROM public.admin_report_mutation_claims "
                    " WHERE challenge_id = :challenge_id) AS claim_count, "
                    "to_regclass('public.admin_report_mutation_claims') "
                    "IS NOT NULL AS claim_table_exists, "
                    "to_regprocedure("
                    "'public.walksafe_append_report_review_decision_v3("
                    "uuid,uuid,bigint,text,text,text,uuid,boolean,boolean,"
                    "boolean,uuid,text,uuid,text,uuid,uuid,bytea,text,text)') "
                    "IS NOT NULL AS v3_function_exists, "
                    "EXISTS (SELECT 1 FROM pg_catalog.pg_trigger "
                    "WHERE tgrelid = 'public.reports'::regclass "
                    "AND tgname = 'reports_runtime_ingest_integrity' "
                    "AND NOT tgisinternal) AS ingest_trigger_exists"
                ),
                {"challenge_id": challenge_id},
            ).mappings().one()
            assert dict(preserved_state) == {
                "revision": "202608300005",
                "claim_count": 1,
                "claim_table_exists": True,
                "v3_function_exists": True,
                "ingest_trigger_exists": True,
            }
        with disposable_engine.begin() as connection:
            connection.execute(
                text(
                    "DELETE FROM public.admin_report_mutation_claims "
                    "WHERE challenge_id = :challenge_id"
                ),
                {"challenge_id": challenge_id},
            )
            connection.execute(
                text(
                    "DELETE FROM public.admin_device_proof_challenges "
                    "WHERE id = :challenge_id"
                ),
                {"challenge_id": challenge_id},
            )

        command.downgrade(config, "202608300004")
        with disposable_engine.connect() as connection:
            restored_privileges = [
                connection.execute(
                    owner_privilege_sql,
                    {"relation": relation, "privilege": privilege},
                ).scalar_one()
                for relation, privilege in added_privileges
            ]
            assert restored_privileges == predecessor_privileges
            assert connection.execute(
                text(
                    "SELECT convalidated FROM pg_constraint WHERE conname = "
                    "'ck_report_review_decisions_evidence_grant'"
                )
            ).scalar_one() is False
            assert connection.execute(
                text(
                    "SELECT to_regprocedure("
                    "'public.walksafe_append_report_review_decision_v3("
                    "uuid,uuid,bigint,text,text,text,uuid,boolean,boolean,"
                    "boolean,uuid,text,uuid,text,uuid,uuid,bytea,text,text)')"
                )
            ).scalar_one() is None

        report_id = uuid.uuid4()
        grant_id = uuid.uuid4()
        with disposable_engine.begin() as connection:
            statement, parameters = _runtime_report_insert_sql(report_id)
            connection.execute(statement, parameters)
            connection.execute(
                text(
                    "INSERT INTO public.report_original_access_grants ("
                    "id, report_id, admin_id, session_id, device_id, purpose, "
                    "reason, token_sha256, issued_at, expires_at, "
                    "content_revision, location_disclosed_at) VALUES ("
                    ":grant_id, :report_id, 'walksafe.admin', :session_id, "
                    "'android-admin-test', 'report_review', "
                    "'Review original evidence.', :token_sha256, "
                    "clock_timestamp(), clock_timestamp() + interval '2 minutes', "
                    "0, clock_timestamp())"
                ),
                {
                    "grant_id": grant_id,
                    "report_id": report_id,
                    "session_id": uuid.uuid4(),
                    "token_sha256": uuid.uuid4().hex + uuid.uuid4().hex,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO public.report_original_access_audits ("
                    "id, grant_id, report_id, admin_id, session_id, device_id, "
                    "purpose, action, outcome, reason_code) VALUES ("
                    ":audit_id, :grant_id, :report_id, 'walksafe.admin', "
                    ":session_id, 'android-admin-test', 'report_review', "
                    "'LOCATION_DISCLOSED', 'SUCCESS', "
                    "'exact_location_disclosed')"
                ),
                {
                    "audit_id": uuid.uuid4(),
                    "grant_id": grant_id,
                    "report_id": report_id,
                    "session_id": uuid.uuid4(),
                },
            )
        command.downgrade(config, "202608300003")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608300003"
            assert connection.execute(
                text(
                    "SELECT count(*) FROM report_original_access_grants "
                    "WHERE id = :grant_id AND content_revision = 0"
                ),
                {"grant_id": grant_id},
            ).scalar_one() == 1
            assert connection.execute(
                text(
                    "SELECT count(*) FROM report_original_access_audits "
                    "WHERE grant_id = :grant_id AND action = 'LOCATION_DISCLOSED'"
                ),
                {"grant_id": grant_id},
            ).scalar_one() == 1
    finally:
        disposable_engine.dispose()
        drop_roles()
        if created_database:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.execute(
                    text(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        "WHERE datname = :database_name AND pid <> pg_backend_pid()"
                    ),
                    {"database_name": database_name},
                ).all()
                connection.exec_driver_sql(
                    f"DROP DATABASE IF EXISTS {quoted_database}"
                )
        admin_engine.dispose()


@pytest.mark.parametrize(
    ("revision_module", "column_name", "column_value"),
    [
        ("202608290006_admin_report_wave5", "status_version", 2),
        ("202608290012_report_content_corrections", "content_revision", 1),
    ],
)
def test_lossy_predecessor_downgrades_refuse_before_ddl_and_preserve_rows(
    monkeypatch: pytest.MonkeyPatch,
    revision_module: str,
    column_name: str,
    column_value: int,
) -> None:
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine = create_engine(database_url, pool_pre_ping=True)
    report_id = uuid.uuid4()
    statement, parameters = _runtime_report_insert_sql(report_id)
    try:
        with engine.begin() as connection:
            connection.execute(statement, parameters)
            if column_name == "content_revision":
                connection.execute(
                    text("ALTER TABLE public.reports DISABLE TRIGGER USER")
                )
            connection.execute(
                text(
                    f"UPDATE public.reports SET {column_name} = :value "
                    "WHERE id = :report_id"
                ),
                {"value": column_value, "report_id": report_id},
            )
            if column_name == "content_revision":
                connection.execute(
                    text("ALTER TABLE public.reports ENABLE TRIGGER USER")
                )

        migration = importlib.import_module(
            f"backend.alembic.versions.{revision_module}"
        )
        with engine.connect() as connection:
            transaction = connection.begin()
            monkeypatch.setattr(
                migration,
                "op",
                Operations(MigrationContext.configure(connection)),
            )
            try:
                with pytest.raises(SQLAlchemyError) as rejected:
                    migration.downgrade()
                assert getattr(rejected.value.orig, "sqlstate", None) == "55000"
            finally:
                transaction.rollback()

        with engine.connect() as connection:
            assert connection.execute(
                text(
                    f"SELECT {column_name} FROM public.reports "
                    "WHERE id = :report_id"
                ),
                {"report_id": report_id},
            ).scalar_one() == column_value
            assert connection.execute(
                text(
                    "SELECT count(*) FROM information_schema.columns "
                    "WHERE table_schema = 'public' AND table_name = 'reports' "
                    "AND column_name = :column_name"
                ),
                {"column_name": column_name},
            ).scalar_one() == 1
    finally:
        with engine.begin() as connection:
            connection.execute(
                text("ALTER TABLE public.reports DISABLE TRIGGER USER")
            )
            connection.execute(
                text("DELETE FROM public.reports WHERE id = :report_id"),
                {"report_id": report_id},
            )
            connection.execute(
                text("ALTER TABLE public.reports ENABLE TRIGGER USER")
            )
        engine.dispose()
