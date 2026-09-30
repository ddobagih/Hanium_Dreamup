"""S18-46: the administrator runtime role boundary on the migrated head.

Python-only tests cannot prove the PostgreSQL security of authentication,
recovery, reconfirmation, review and delivery. This file pins, on the real
migrated database, the exact table privileges of ``walksafe_backend_runtime``
for every administrator security/review/delivery table and executes the
direct writes it must not have (as that role, not as the owner): they fail
with 42501. Writes go only through the SECURITY DEFINER functions exercised
end to end as the runtime role in test_admin_report_lost_reauth_postgres,
test_admin_reconfirmation_allowlist_postgres, test_admin_security
(full recovery/high-risk path), test_fp008_postgres_integration (review and
delivery) and test_admin_delivery_correction_w23.
"""

from __future__ import annotations

import os

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import DBAPIError


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

# (SELECT, INSERT, UPDATE, DELETE, TRUNCATE) for walksafe_backend_runtime.
_EXPECTED = {
    "admin_device_keys": (True, False, False, False, False),
    "admin_device_proof_challenges": (True, True, False, False, False),
    "admin_operation_audits": (True, True, False, False, False),
    "admin_report_mutation_claims": (True, False, False, False, False),
    "admin_security_audits": (True, True, False, False, False),
    "admin_security_auth_attempts": (True, True, False, False, False),
    "admin_security_controls": (True, False, False, False, False),
    "admin_security_reconfirmations": (True, False, False, False, False),
    "admin_security_recovery_codes": (False, False, False, False, False),
    "admin_security_recovery_transactions": (False, False, False, False, False),
    "admin_security_sessions": (True, False, False, False, False),
    "report_delivery_packages": (True, False, False, False, False),
    "report_institution_delivery_corrections": (True, False, False, False, False),
    "report_institution_delivery_events": (True, False, False, False, False),
    "report_review_decisions": (True, False, False, False, False),
}
# The one column-level UPDATE: one-way device proof consumption.
_EXPECTED_COLUMN_UPDATES = {("admin_device_proof_challenges", "consumed_at")}
_INSERTABLE = {
    table for table, privileges in _EXPECTED.items() if privileges[1]
}


def test_s18_46_runtime_role_privileges_are_exact_at_head_and_enforced() -> None:
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            head = connection.execute(text("SELECT version_num FROM alembic_version")).scalar_one()
            from backend.app.api.health import EXPECTED_ALEMBIC_HEAD

            assert head == EXPECTED_ALEMBIC_HEAD
            role = connection.execute(
                text(
                    "SELECT rolsuper, rolcreaterole, rolcreatedb, rolbypassrls, "
                    "pg_has_role('walksafe_backend_runtime', "
                    "'walksafe_report_evidence_owner', 'MEMBER') AS evidence_owner "
                    "FROM pg_roles WHERE rolname = 'walksafe_backend_runtime'"
                )
            ).mappings().one()
            assert not any(role.values())
            rows = connection.execute(
                text(
                    "SELECT c.relname, "
                    "has_table_privilege('walksafe_backend_runtime', c.oid, 'SELECT'), "
                    "has_table_privilege('walksafe_backend_runtime', c.oid, 'INSERT'), "
                    "has_table_privilege('walksafe_backend_runtime', c.oid, 'UPDATE'), "
                    "has_table_privilege('walksafe_backend_runtime', c.oid, 'DELETE'), "
                    "has_table_privilege('walksafe_backend_runtime', c.oid, 'TRUNCATE'), "
                    "pg_has_role('walksafe_backend_runtime', c.relowner, 'MEMBER') "
                    "FROM pg_class AS c JOIN pg_namespace AS n ON n.oid = c.relnamespace "
                    "WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname = ANY(:names)"
                ),
                {"names": sorted(_EXPECTED)},
            ).all()
            actual = {row[0]: tuple(row[1:6]) for row in rows}
            assert actual == _EXPECTED
            assert not any(row[6] for row in rows)  # runtime never owns them
            column_updates = {
                (row[0], row[1])
                for row in connection.execute(
                    text(
                        "SELECT table_name, column_name FROM information_schema.column_privileges "
                        "WHERE grantee = 'walksafe_backend_runtime' AND privilege_type = 'UPDATE' "
                        "AND table_name = ANY(:names)"
                    ),
                    {"names": sorted(_EXPECTED)},
                ).all()
            }
            assert column_updates == _EXPECTED_COLUMN_UPDATES
            first_column = dict(
                connection.execute(
                    text(
                        "SELECT table_name, column_name FROM information_schema.columns "
                        "WHERE table_schema = 'public' AND table_name = ANY(:names) "
                        "AND ordinal_position = 1"
                    ),
                    {"names": sorted(_EXPECTED)},
                ).all()
            )

        # Execute the forbidden direct writes as the runtime role itself.
        refused: dict[str, set[str]] = {}
        with engine.connect() as connection:
            connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
            connection.commit()
            try:
                for table in sorted(_EXPECTED):
                    statements = {
                        "UPDATE": (
                            f"UPDATE public.{table} SET {first_column[table]} = "
                            f"{first_column[table]} WHERE false"
                        ),
                        "DELETE": f"DELETE FROM public.{table} WHERE false",
                        "TRUNCATE": f"TRUNCATE TABLE public.{table}",
                    }
                    if table not in _INSERTABLE:
                        statements["INSERT"] = (
                            f"INSERT INTO public.{table} SELECT * FROM public.{table} WHERE false"
                        )
                    for verb, statement in statements.items():
                        try:
                            connection.execute(text(statement))
                        except DBAPIError as exc:
                            assert getattr(exc.orig, "sqlstate", None) == "42501", (table, verb)
                            refused.setdefault(table, set()).add(verb)
                        finally:
                            connection.rollback()
            finally:
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()
        assert {
            table: verbs for table, verbs in refused.items()
        } == {
            table: {"UPDATE", "DELETE", "TRUNCATE"} | (
                set() if table in _INSERTABLE else {"INSERT"}
            )
            for table in _EXPECTED
        }
    finally:
        engine.dispose()
