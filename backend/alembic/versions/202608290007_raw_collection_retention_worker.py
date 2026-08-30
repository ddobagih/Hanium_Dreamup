"""add least-privilege raw collection retention worker role

Revision ID: 202608290007
Revises: 202608290006
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op


revision = "202608290007"
down_revision = "202608290006"
branch_labels = None
depends_on = None


_WORKER_ROLE = "walksafe_raw_retention_worker"
_RAW_TABLES = (
    "public.raw_collections, public.raw_collection_objects, "
    "public.raw_collection_chunks"
)
_DENIED_TABLES = (
    "public.reports, public.report_image_objects, "
    "public.report_status_audits, public.report_read_audits, "
    "public.report_export_audits, public.admin_operation_audits, "
    "public.admin_security_audits, public.privacy_consent_events, "
    "public.account_deletion_tombstones, public.account_deletion_requests, "
    "public.account_deletion_items, public.account_deletion_events, "
    "public.account_deletion_receipts, public.account_deletion_device_targets"
)


def upgrade() -> None:
    op.execute(
        f"""
        DO $$
        DECLARE role_record record;
        BEGIN
          SELECT rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
                 rolinherit, rolreplication, rolbypassrls
            INTO role_record
            FROM pg_roles
           WHERE rolname = '{_WORKER_ROLE}';
          IF NOT FOUND THEN
            CREATE ROLE {_WORKER_ROLE}
              NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
              INHERIT NOREPLICATION NOBYPASSRLS;
          ELSIF role_record.rolsuper OR role_record.rolcreaterole
             OR role_record.rolcreatedb OR role_record.rolcanlogin
             OR NOT role_record.rolinherit OR role_record.rolreplication
             OR role_record.rolbypassrls THEN
            RAISE EXCEPTION '{_WORKER_ROLE} has unsafe role attributes';
          END IF;
        END
        $$
        """
    )
    op.execute(f"GRANT USAGE ON SCHEMA public TO {_WORKER_ROLE}")
    op.execute(f"REVOKE CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    op.execute(f"REVOKE ALL PRIVILEGES ON TABLE {_RAW_TABLES} FROM {_WORKER_ROLE}")
    op.execute(f"GRANT SELECT ON TABLE {_RAW_TABLES} TO {_WORKER_ROLE}")
    op.execute(
        f"GRANT DELETE ON TABLE public.raw_collections TO {_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE INSERT, UPDATE, TRUNCATE ON TABLE "
        f"{_RAW_TABLES} FROM {_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE DELETE ON TABLE public.raw_collection_objects, "
        f"public.raw_collection_chunks FROM {_WORKER_ROLE}"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON TABLE {_DENIED_TABLES} FROM {_WORKER_ROLE}"
    )


def downgrade() -> None:
    op.execute(
        f"REVOKE ALL PRIVILEGES ON TABLE {_RAW_TABLES} FROM {_WORKER_ROLE}"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON TABLE {_DENIED_TABLES} FROM {_WORKER_ROLE}"
    )
    op.execute(f"REVOKE USAGE, CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    # PostgreSQL roles are cluster-wide; another database may still use this
    # NOLOGIN role, so downgrade removes only this database's grants.
