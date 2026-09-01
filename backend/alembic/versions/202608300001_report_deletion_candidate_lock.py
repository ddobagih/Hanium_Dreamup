"""add a least-privilege report deletion candidate lock

Revision ID: 202608300001
Revises: 202608290016
Create Date: 2026-08-30
"""

from __future__ import annotations

from alembic import op


revision = "202608300001"
down_revision = "202608290016"
branch_labels = None
depends_on = None


_LOCK_FUNCTION_SIGNATURE = (
    "public.walksafe_lock_report_deletion_candidate("
    "uuid, uuid, bigint, text, bigint)"
)


def upgrade() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_lock_report_deletion_candidate(
            p_request_id uuid,
            p_report_id uuid,
            p_request_status_version bigint,
            p_privacy_subject_hmac text,
            p_account_generation bigint
        )
        RETURNS boolean
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $lock$
        BEGIN
            IF NOT pg_catalog.pg_has_role(
                session_user, 'walksafe_report_deletion_worker', 'USAGE'
            ) OR pg_catalog.pg_has_role(
                session_user, 'walksafe_backend_runtime', 'USAGE'
            ) OR pg_catalog.pg_has_role(
                session_user, 'walksafe_account_deletion_worker', 'USAGE'
            ) THEN
                RAISE EXCEPTION 'report deletion candidate lock is worker-only'
                    USING ERRCODE = '42501';
            END IF;

            PERFORM request.id
            FROM public.report_user_requests AS request
            JOIN public.reports AS report ON report.id = request.report_id
            WHERE request.id = p_request_id
              AND request.report_id = p_report_id
              AND request.request_type = 'DELETE'
              AND request.status = 'ACKNOWLEDGED'
              AND request.status_version = p_request_status_version
              AND report.privacy_subject_hmac = p_privacy_subject_hmac
              AND report.account_generation = p_account_generation
            FOR UPDATE OF request, report;
            RETURN FOUND;
        END
        $lock$
        """
    )
    op.execute(
        f"REVOKE ALL ON FUNCTION {_LOCK_FUNCTION_SIGNATURE} "
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_account_deletion_worker"
    )
    op.execute(
        f"GRANT EXECUTE ON FUNCTION {_LOCK_FUNCTION_SIGNATURE} "
        "TO walksafe_report_deletion_worker"
    )


def downgrade() -> None:
    op.execute(
        f"REVOKE ALL ON FUNCTION {_LOCK_FUNCTION_SIGNATURE} "
        "FROM walksafe_report_deletion_worker"
    )
    op.execute(f"DROP FUNCTION {_LOCK_FUNCTION_SIGNATURE}")
