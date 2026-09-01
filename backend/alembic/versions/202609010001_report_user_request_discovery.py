"""add stable user report request discovery revisions

Revision ID: 202609010001
Revises: 202608300006
Create Date: 2026-09-01
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202609010001"
down_revision = "202608300006"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"
_DELETION_WORKER_ROLE = "walksafe_report_deletion_worker"
_SEQUENCE = "report_user_request_discovery_revision_seq"


def _install_deletion_admission_guard(*, require_revision: bool) -> None:
    revision_clause = (
        "AND request.discovery_revision = NEW.discovery_revision"
        if require_revision
        else ""
    )
    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_report_deletion_admission_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF NOT EXISTS (
                SELECT 1
                FROM public.report_user_requests AS request
                JOIN public.reports AS report ON report.id = request.report_id
                WHERE request.id = NEW.request_id
                  AND request.report_id = NEW.report_id
                  AND request.request_type = 'DELETE'
                  AND request.status = 'ACKNOWLEDGED'
                  AND request.status_version = NEW.request_status_version
                  {revision_clause}
                  AND report.privacy_subject_hmac = NEW.privacy_subject_hmac
                  AND report.account_generation = NEW.account_generation
            ) THEN
                RAISE EXCEPTION 'report deletion effect lacks an acknowledged request';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_deletion_admission_guard() "
        f"FROM PUBLIC, {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )


def upgrade() -> None:
    op.execute(
        f"CREATE SEQUENCE public.{_SEQUENCE} AS bigint MINVALUE 1 "
        "MAXVALUE 9007199254740991 NO CYCLE"
    )
    op.add_column(
        "report_user_requests",
        sa.Column("discovery_revision", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "report_deletion_tombstones",
        sa.Column("discovery_revision", sa.BigInteger(), nullable=True),
    )
    op.execute(
        "ALTER TABLE public.report_user_requests DISABLE TRIGGER "
        "report_user_request_projection_event_guard"
    )
    op.execute(
        "ALTER TABLE public.report_deletion_tombstones DISABLE TRIGGER "
        "report_deletion_tombstones_append_only"
    )
    op.execute(
        f"""
        WITH ordered AS (
            SELECT source_kind, source_id,
                   row_number() OVER (
                       ORDER BY occurred_at, source_kind, source_id
                   )::bigint AS discovery_revision
              FROM (
                    SELECT 'ACTIVE_REQUEST'::text AS source_kind,
                           request.id AS source_id,
                           request.created_at AS occurred_at
                      FROM public.report_user_requests AS request
                    UNION ALL
                    SELECT 'DELETION_TOMBSTONE'::text AS source_kind,
                           tombstone.id AS source_id,
                           tombstone.deleted_at AS occurred_at
                      FROM public.report_deletion_tombstones AS tombstone
              ) AS sources
        )
        UPDATE public.report_user_requests AS request
           SET discovery_revision = ordered.discovery_revision
          FROM ordered
         WHERE ordered.source_kind = 'ACTIVE_REQUEST'
           AND ordered.source_id = request.id
        """
    )
    op.execute(
        "ALTER TABLE public.report_user_requests ENABLE TRIGGER "
        "report_user_request_projection_event_guard"
    )
    op.execute(
        f"""
        WITH ordered AS (
            SELECT source_kind, source_id,
                   row_number() OVER (
                       ORDER BY occurred_at, source_kind, source_id
                   )::bigint AS discovery_revision
              FROM (
                    SELECT 'ACTIVE_REQUEST'::text AS source_kind,
                           request.id AS source_id,
                           request.created_at AS occurred_at
                      FROM public.report_user_requests AS request
                    UNION ALL
                    SELECT 'DELETION_TOMBSTONE'::text AS source_kind,
                           tombstone.id AS source_id,
                           tombstone.deleted_at AS occurred_at
                      FROM public.report_deletion_tombstones AS tombstone
              ) AS sources
        )
        UPDATE public.report_deletion_tombstones AS tombstone
           SET discovery_revision = ordered.discovery_revision
          FROM ordered
         WHERE ordered.source_kind = 'DELETION_TOMBSTONE'
           AND ordered.source_id = tombstone.id
        """
    )
    op.execute(
        "ALTER TABLE public.report_deletion_tombstones ENABLE TRIGGER "
        "report_deletion_tombstones_append_only"
    )
    op.execute(
        f"""
        SELECT pg_catalog.setval(
            'public.{_SEQUENCE}'::regclass,
            COALESCE(maximum, 1),
            maximum IS NOT NULL
        )
        FROM (
            SELECT max(discovery_revision) AS maximum
              FROM (
                    SELECT discovery_revision FROM public.report_user_requests
                    UNION ALL
                    SELECT discovery_revision FROM public.report_deletion_tombstones
              ) AS revisions
        ) AS current_revision
        """
    )
    op.alter_column(
        "report_user_requests",
        "discovery_revision",
        existing_type=sa.BigInteger(),
        nullable=False,
        server_default=sa.text(
            f"nextval('public.{_SEQUENCE}'::regclass)"
        ),
    )
    op.alter_column(
        "report_deletion_tombstones",
        "discovery_revision",
        existing_type=sa.BigInteger(),
        nullable=False,
        server_default=sa.text(
            f"nextval('public.{_SEQUENCE}'::regclass)"
        ),
    )
    op.create_check_constraint(
        "ck_report_user_requests_discovery_revision",
        "report_user_requests",
        "discovery_revision BETWEEN 1 AND 9007199254740991",
    )
    op.create_unique_constraint(
        "uq_report_user_requests_discovery_revision",
        "report_user_requests",
        ["discovery_revision"],
    )
    op.create_check_constraint(
        "ck_report_deletion_tombstones_discovery_revision",
        "report_deletion_tombstones",
        "discovery_revision BETWEEN 1 AND 9007199254740991",
    )
    op.create_unique_constraint(
        "uq_report_deletion_tombstones_discovery_revision",
        "report_deletion_tombstones",
        ["discovery_revision"],
    )
    _install_deletion_admission_guard(require_revision=True)
    op.execute(
        f"REVOKE ALL ON SEQUENCE public.{_SEQUENCE} FROM PUBLIC, "
        f"{_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        f"GRANT USAGE, SELECT ON SEQUENCE public.{_SEQUENCE} TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "REVOKE INSERT ON TABLE public.report_user_requests "
        f"FROM {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT INSERT (id, report_id, client_request_id, request_type, "
        "request_text, intent_sha256, status, status_version, public_response, "
        "internal_note, created_at, updated_at) ON TABLE "
        f"public.report_user_requests TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT SELECT (discovery_revision) ON TABLE public.report_user_requests "
        f"TO {_DELETION_WORKER_ROLE}"
    )


def downgrade() -> None:
    _install_deletion_admission_guard(require_revision=False)
    op.execute(
        "REVOKE INSERT (id, report_id, client_request_id, request_type, "
        "request_text, intent_sha256, status, status_version, public_response, "
        "internal_note, created_at, updated_at) ON TABLE "
        f"public.report_user_requests FROM {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.report_user_requests "
        f"TO {_RUNTIME_ROLE}"
    )
    op.drop_constraint(
        "uq_report_deletion_tombstones_discovery_revision",
        "report_deletion_tombstones",
        type_="unique",
    )
    op.drop_constraint(
        "ck_report_deletion_tombstones_discovery_revision",
        "report_deletion_tombstones",
        type_="check",
    )
    op.drop_constraint(
        "uq_report_user_requests_discovery_revision",
        "report_user_requests",
        type_="unique",
    )
    op.drop_constraint(
        "ck_report_user_requests_discovery_revision",
        "report_user_requests",
        type_="check",
    )
    op.execute(
        "REVOKE SELECT (discovery_revision) ON TABLE public.report_user_requests "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    op.drop_column("report_deletion_tombstones", "discovery_revision")
    op.drop_column("report_user_requests", "discovery_revision")
    op.execute(f"DROP SEQUENCE public.{_SEQUENCE}")
