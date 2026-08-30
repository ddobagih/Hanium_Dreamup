"""bind raw capacity retention and account-deletion worker access

Revision ID: 202608290003
Revises: 202608290002
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202608290003"
down_revision = "202608290002"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"
_DELETION_WORKER_ROLE = "walksafe_account_deletion_worker"
_DOWNGRADE_LOCK_SQL = "LOCK TABLE public.raw_collections IN ACCESS EXCLUSIVE MODE"
_UNSAFE_DOWNGRADE_SQL = """
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.raw_collections) THEN
    RAISE EXCEPTION
      'cannot downgrade B1e while raw collection retention data exists'
      USING ERRCODE = '55000';
  END IF;
END
$$
"""


def _install_state_guard(*, include_retention_class: bool) -> None:
    retention_new = ", NEW.retention_class" if include_retention_class else ""
    retention_old = ", OLD.retention_class" if include_retention_class else ""
    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_guard_raw_collection_state()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $$
        BEGIN
          IF TG_OP = 'INSERT' THEN
            IF NEW.state <> 'MANIFEST_ACCEPTED' THEN
              RAISE EXCEPTION 'raw collection must start in MANIFEST_ACCEPTED'
                USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;
          IF ROW(
               NEW.collection_id, NEW.privacy_subject_hmac,
               NEW.account_generation, NEW.walk_id, NEW.segment_id,
               NEW.purpose{retention_new}, NEW.consent_receipt_sha256,
               NEW.manifest_sha256, NEW.captured_started_at,
               NEW.captured_ended_at, NEW.object_count, NEW.chunk_count,
               NEW.total_bytes, NEW.created_at
             ) IS DISTINCT FROM ROW(
               OLD.collection_id, OLD.privacy_subject_hmac,
               OLD.account_generation, OLD.walk_id, OLD.segment_id,
               OLD.purpose{retention_old}, OLD.consent_receipt_sha256,
               OLD.manifest_sha256, OLD.captured_started_at,
               OLD.captured_ended_at, OLD.object_count, OLD.chunk_count,
               OLD.total_bytes, OLD.created_at
             ) THEN
            RAISE EXCEPTION 'raw collection manifest metadata is immutable'
              USING ERRCODE = '23514';
          END IF;
          IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
            (OLD.state = 'MANIFEST_ACCEPTED' AND NEW.state = 'READY_TO_COMMIT')
            OR (OLD.state = 'READY_TO_COMMIT' AND NEW.state = 'COMMITTED')
          ) THEN
            RAISE EXCEPTION 'raw collection state transition is not allowed'
              USING ERRCODE = '23514';
          END IF;
          IF ROW(
               NEW.committed_at, NEW.retention_expires_at, NEW.receipt_sha256
             ) IS DISTINCT FROM ROW(
               OLD.committed_at, OLD.retention_expires_at, OLD.receipt_sha256
             ) AND NOT (
               OLD.state = 'READY_TO_COMMIT'
               AND NEW.state = 'COMMITTED'
               AND OLD.committed_at IS NULL
               AND OLD.retention_expires_at IS NULL
               AND OLD.receipt_sha256 IS NULL
             ) THEN
            RAISE EXCEPTION 'raw collection receipt metadata is immutable'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END
        $$
        """
    )


def upgrade() -> None:
    op.add_column(
        "raw_collections",
        sa.Column("retention_class", sa.String(length=32), nullable=True),
    )
    op.execute(
        "UPDATE public.raw_collections "
        "SET retention_class = 'RAW_ORIGINAL_180D' "
        "WHERE retention_class IS NULL"
    )
    op.alter_column("raw_collections", "retention_class", nullable=False)
    op.create_check_constraint(
        "ck_raw_collections_retention_class",
        "raw_collections",
        "retention_class = 'RAW_ORIGINAL_180D'",
    )
    _install_state_guard(include_retention_class=True)
    op.execute(
        "GRANT SELECT ON TABLE public.raw_collections, "
        "public.raw_collection_objects, public.raw_collection_chunks "
        f"TO {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "GRANT DELETE ON TABLE public.raw_collections "
        f"TO {_DELETION_WORKER_ROLE}"
    )


def downgrade() -> None:
    op.execute(_DOWNGRADE_LOCK_SQL)
    op.execute(_UNSAFE_DOWNGRADE_SQL)
    op.execute(
        "REVOKE DELETE ON TABLE public.raw_collections "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE SELECT ON TABLE public.raw_collections, "
        "public.raw_collection_objects, public.raw_collection_chunks "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    _install_state_guard(include_retention_class=False)
    op.drop_constraint(
        "ck_raw_collections_retention_class",
        "raw_collections",
        type_="check",
    )
    op.drop_column("raw_collections", "retention_class")
