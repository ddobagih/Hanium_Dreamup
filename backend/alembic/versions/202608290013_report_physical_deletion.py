"""add manual physical report deletion and content-free tombstones

Revision ID: 202608290013
Revises: 202608290012
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290013"
down_revision = "202608290012"
branch_labels = None
depends_on = None


_WORKER_ROLE = "walksafe_report_deletion_worker"


def upgrade() -> None:
    op.create_table(
        "report_deletion_legal_holds",
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("legal_basis_code", sa.String(length=64), nullable=False),
        sa.Column("authority_reference", sa.String(length=160), nullable=False),
        sa.Column("reason_code", sa.String(length=64), nullable=False),
        sa.Column("approved_by", sa.String(length=64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "reason_code ~ '^[a-z][a-z0-9_:-]{2,63}$'",
            name="ck_report_deletion_legal_holds_reason",
        ),
        sa.CheckConstraint(
            "legal_basis_code ~ '^[A-Z][A-Z0-9_:-]{2,63}$'",
            name="ck_report_deletion_legal_holds_basis",
        ),
        sa.CheckConstraint(
            "authority_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'",
            name="ck_report_deletion_legal_holds_authority",
        ),
        sa.CheckConstraint(
            "approved_by ~ '^[A-Za-z0-9][A-Za-z0-9._@-]{2,63}$'",
            name="ck_report_deletion_legal_holds_approver",
        ),
        sa.CheckConstraint(
            "expires_at > created_at",
            name="ck_report_deletion_legal_holds_expires_at",
        ),
        sa.ForeignKeyConstraint(
            ["report_id"], ["reports.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("report_id"),
    )

    op.create_table(
        "report_deletion_tombstones",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("request_status_version", sa.BigInteger(), nullable=False),
        sa.Column("external_copy_count", sa.BigInteger(), nullable=False),
        sa.Column(
            "deleted_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1",
            name="ck_report_deletion_tombstones_subject",
        ),
        sa.CheckConstraint(
            "request_status_version >= 1 AND external_copy_count >= 0",
            name="ck_report_deletion_tombstones_counts",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "request_id", name="uq_report_deletion_tombstones_request"
        ),
        sa.UniqueConstraint(
            "report_id", name="uq_report_deletion_tombstones_report"
        ),
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_deletion_hold_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF NEW.expires_at <= clock_timestamp() THEN
                RAISE EXCEPTION 'report deletion legal hold must expire in the future';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_deletion_hold_guard() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER report_deletion_legal_holds_future_guard "
        "BEFORE INSERT OR UPDATE ON public.report_deletion_legal_holds "
        "FOR EACH ROW EXECUTE FUNCTION public.walksafe_report_deletion_hold_guard()"
    )
    op.create_index(
        "ix_report_deletion_tombstones_subject_deleted_at",
        "report_deletion_tombstones",
        ["privacy_subject_hmac", "account_generation", "deleted_at"],
    )

    op.create_table(
        "report_deletion_external_copy_states",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "deletion_tombstone_id", postgresql.UUID(as_uuid=True), nullable=False
        ),
        sa.Column(
            "source_delivery_event_id", postgresql.UUID(as_uuid=True), nullable=False
        ),
        sa.Column("package_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("institution", sa.String(length=160), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("observed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "length(institution) BETWEEN 1 AND 160",
            name="ck_report_deletion_external_copy_institution",
        ),
        sa.CheckConstraint(
            "status IN ('SUBMITTED', 'ACKNOWLEDGED', 'RESOLVED', 'FAILED')",
            name="ck_report_deletion_external_copy_status",
        ),
        sa.ForeignKeyConstraint(
            ["deletion_tombstone_id"],
            ["report_deletion_tombstones.id"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "deletion_tombstone_id",
            "source_delivery_event_id",
            name="uq_report_deletion_external_copy_source",
        ),
    )
    op.create_index(
        "ix_report_deletion_external_copy_states_deletion_tombstone_id",
        "report_deletion_external_copy_states",
        ["deletion_tombstone_id"],
    )

    for table_name in (
        "report_deletion_tombstones",
        "report_deletion_external_copy_states",
    ):
        op.execute(
            f"CREATE TRIGGER {table_name}_append_only "
            f"BEFORE UPDATE OR DELETE OR TRUNCATE ON public.{table_name} "
            "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
        )

    op.execute(
        f"""
        DO $$
        DECLARE role_record record;
        BEGIN
          SELECT rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
                 rolreplication, rolbypassrls
            INTO role_record
            FROM pg_roles
           WHERE rolname = '{_WORKER_ROLE}';
          IF NOT FOUND THEN
            CREATE ROLE {_WORKER_ROLE}
              NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOINHERIT NOREPLICATION NOBYPASSRLS;
          ELSIF role_record.rolsuper OR role_record.rolcreaterole
             OR role_record.rolcreatedb OR role_record.rolcanlogin
             OR role_record.rolreplication OR role_record.rolbypassrls THEN
            RAISE EXCEPTION '{_WORKER_ROLE} has unsafe role attributes';
          END IF;
        END
        $$
        """
    )
    op.execute(f"GRANT USAGE ON SCHEMA public TO {_WORKER_ROLE}")
    op.execute(f"REVOKE CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    op.execute(
        "GRANT SELECT (id, privacy_subject_hmac, account_generation, image_path) "
        f"ON TABLE public.reports TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (report_id, storage_name) "
        f"ON TABLE public.report_image_objects TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (id, report_id, request_type, status, status_version, updated_at) "
        f"ON TABLE public.report_user_requests TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (report_id, expires_at) "
        "ON TABLE public.report_deletion_legal_holds "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.report_deletion_tombstones "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (id, report_id, package_id, revision, institution, status, observed_at) "
        "ON TABLE public.report_institution_delivery_events "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(f"GRANT DELETE ON TABLE public.reports TO {_WORKER_ROLE}")
    op.execute(
        "GRANT INSERT ON TABLE public.report_deletion_tombstones, "
        "public.report_deletion_external_copy_states "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (deletion_tombstone_id, source_delivery_event_id) ON TABLE "
        "public.report_deletion_external_copy_states "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE UPDATE, DELETE, TRUNCATE ON TABLE "
        "public.report_deletion_tombstones, "
        "public.report_deletion_external_copy_states, "
        "public.report_deletion_legal_holds "
        f"FROM {_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE "
        "public.report_institution_delivery_events, public.report_delivery_packages "
        f"FROM {_WORKER_ROLE}"
    )

    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.report_deletion_legal_holds, "
        "public.report_deletion_tombstones, "
        "public.report_deletion_external_copy_states "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.report_deletion_legal_holds, "
        "public.report_deletion_tombstones, "
        "public.report_deletion_external_copy_states "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION public.walksafe_report_content_revision_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_OP = 'DELETE' AND (
                pg_has_role(session_user, 'walksafe_account_deletion_worker', 'USAGE')
                OR pg_has_role(session_user, 'walksafe_report_deletion_worker', 'USAGE')
            ) THEN
                RETURN NULL;
            END IF;
            RAISE EXCEPTION 'report content revisions are append-only';
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_content_revision_guard() "
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker"
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_deletion_admission_guard()
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
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker"
    )
    op.execute(
        "CREATE TRIGGER report_deletion_tombstones_admission_guard "
        "BEFORE INSERT ON public.report_deletion_tombstones FOR EACH ROW "
        "EXECUTE FUNCTION public.walksafe_report_deletion_admission_guard()"
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_deletion_external_copy_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF NOT EXISTS (
                SELECT 1
                FROM public.report_deletion_tombstones AS tombstone
                JOIN public.report_institution_delivery_events AS event
                  ON event.id = NEW.source_delivery_event_id
                 AND event.report_id = tombstone.report_id
                WHERE tombstone.id = NEW.deletion_tombstone_id
                  AND EXISTS (
                      SELECT 1 FROM public.reports AS report
                      WHERE report.id = tombstone.report_id
                  )
                  AND event.package_id IS NOT DISTINCT FROM NEW.package_id
                  AND event.institution = NEW.institution
                  AND event.status = NEW.status
                  AND event.observed_at = NEW.observed_at
            ) THEN
                RAISE EXCEPTION 'external copy state does not match delivery evidence';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_deletion_external_copy_guard() "
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker"
    )
    op.execute(
        "CREATE TRIGGER report_deletion_external_copy_states_evidence_guard "
        "BEFORE INSERT ON public.report_deletion_external_copy_states FOR EACH ROW "
        "EXECUTE FUNCTION public.walksafe_report_deletion_external_copy_guard()"
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_deletion_completion_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM public.reports WHERE id = NEW.report_id
            ) OR EXISTS (
                SELECT 1 FROM public.report_user_requests WHERE id = NEW.request_id
            ) OR (
                SELECT count(*)
                FROM public.report_deletion_external_copy_states AS copy
                WHERE copy.deletion_tombstone_id = NEW.id
            ) <> NEW.external_copy_count OR NEW.external_copy_count <> (
                SELECT count(*)
                FROM (
                    SELECT DISTINCT ON (COALESCE(event.package_id, event.id))
                           event.id
                    FROM public.report_institution_delivery_events AS event
                    WHERE event.report_id = NEW.report_id
                    ORDER BY COALESCE(event.package_id, event.id),
                             event.revision DESC, event.id DESC
                ) AS latest
            ) OR EXISTS (
                SELECT 1
                FROM (
                    SELECT DISTINCT ON (COALESCE(event.package_id, event.id))
                           event.id
                    FROM public.report_institution_delivery_events AS event
                    WHERE event.report_id = NEW.report_id
                    ORDER BY COALESCE(event.package_id, event.id),
                             event.revision DESC, event.id DESC
                ) AS latest
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM public.report_deletion_external_copy_states AS copy
                    WHERE copy.deletion_tombstone_id = NEW.id
                      AND copy.source_delivery_event_id = latest.id
                )
            ) THEN
                RAISE EXCEPTION 'report deletion effect is incomplete';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_deletion_completion_guard() "
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER report_deletion_tombstones_completion_guard "
        "AFTER INSERT ON public.report_deletion_tombstones "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
        "EXECUTE FUNCTION public.walksafe_report_deletion_completion_guard()"
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_deletion_report_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            PERFORM hold.report_id
            FROM public.report_deletion_legal_holds AS hold
            WHERE hold.report_id = OLD.id
            FOR UPDATE;
            IF EXISTS (
                SELECT 1
                FROM public.report_deletion_legal_holds AS hold
                WHERE hold.report_id = OLD.id
                  AND hold.expires_at > clock_timestamp()
            ) THEN
                RAISE EXCEPTION 'report deletion is blocked by an active legal hold';
            END IF;
            IF pg_has_role(
                session_user, 'walksafe_report_deletion_worker', 'USAGE'
            ) AND NOT EXISTS (
                SELECT 1
                FROM public.report_deletion_tombstones AS tombstone
                WHERE tombstone.report_id = OLD.id
                  AND tombstone.privacy_subject_hmac = OLD.privacy_subject_hmac
                  AND tombstone.account_generation = OLD.account_generation
            ) THEN
                RAISE EXCEPTION 'report deletion requires its durable effect';
            END IF;
            RETURN OLD;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_deletion_report_guard() "
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker"
    )
    op.execute(
        "CREATE TRIGGER reports_deletion_effect_guard "
        "BEFORE DELETE ON public.reports FOR EACH ROW "
        "EXECUTE FUNCTION public.walksafe_report_deletion_report_guard()"
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS reports_deletion_effect_guard ON public.reports")
    op.execute("DROP FUNCTION IF EXISTS public.walksafe_report_deletion_report_guard()")
    op.execute(
        "DROP TRIGGER IF EXISTS report_deletion_tombstones_completion_guard "
        "ON public.report_deletion_tombstones"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS public.walksafe_report_deletion_completion_guard()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS report_deletion_external_copy_states_evidence_guard "
        "ON public.report_deletion_external_copy_states"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS public.walksafe_report_deletion_external_copy_guard()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS report_deletion_tombstones_admission_guard "
        "ON public.report_deletion_tombstones"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS public.walksafe_report_deletion_admission_guard()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS report_deletion_legal_holds_future_guard "
        "ON public.report_deletion_legal_holds"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS public.walksafe_report_deletion_hold_guard()"
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION public.walksafe_report_content_revision_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_OP = 'DELETE' AND pg_has_role(
                session_user,
                'walksafe_account_deletion_worker',
                'USAGE'
            ) THEN
                RETURN NULL;
            END IF;
            RAISE EXCEPTION 'report content revisions are append-only';
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_content_revision_guard() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON TABLE public.reports, "
        "public.report_image_objects, public.report_user_requests, "
        "public.report_deletion_legal_holds, public.report_deletion_tombstones, "
        "public.report_deletion_external_copy_states, "
        f"public.report_institution_delivery_events FROM {_WORKER_ROLE}"
    )
    op.execute(f"REVOKE USAGE, CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    for table_name in (
        "report_deletion_external_copy_states",
        "report_deletion_tombstones",
    ):
        op.execute(
            f"DROP TRIGGER IF EXISTS {table_name}_append_only "
            f"ON public.{table_name}"
        )
    op.drop_index(
        "ix_report_deletion_external_copy_states_deletion_tombstone_id",
        table_name="report_deletion_external_copy_states",
    )
    op.drop_table("report_deletion_external_copy_states")
    op.drop_index(
        "ix_report_deletion_tombstones_subject_deleted_at",
        table_name="report_deletion_tombstones",
    )
    op.drop_table("report_deletion_tombstones")
    op.drop_table("report_deletion_legal_holds")
