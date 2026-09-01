"""add immutable raw collection commit receipts

Revision ID: 202608290002
Revises: 202608290001
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202608290002"
down_revision = "202608290001"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"
_DOWNGRADE_LOCK_SQL = (
    "LOCK TABLE public.raw_collections IN ACCESS EXCLUSIVE MODE"
)
_UNSAFE_DOWNGRADE_SQL = """
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.raw_collections WHERE receipt_sha256 IS NOT NULL
  ) THEN
    RAISE EXCEPTION
      'cannot downgrade B1c while committed raw collection receipts exist'
      USING ERRCODE = '55000';
  END IF;
END
$$
"""


def _install_b1c_state_guard() -> None:
    op.execute(
        """
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
               NEW.purpose, NEW.consent_receipt_sha256, NEW.manifest_sha256,
               NEW.captured_started_at, NEW.captured_ended_at,
               NEW.object_count, NEW.chunk_count, NEW.total_bytes,
               NEW.created_at
             ) IS DISTINCT FROM ROW(
               OLD.collection_id, OLD.privacy_subject_hmac,
               OLD.account_generation, OLD.walk_id, OLD.segment_id,
               OLD.purpose, OLD.consent_receipt_sha256, OLD.manifest_sha256,
               OLD.captured_started_at, OLD.captured_ended_at,
               OLD.object_count, OLD.chunk_count, OLD.total_bytes,
               OLD.created_at
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


def _install_b1b_state_guard() -> None:
    op.execute(
        """
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
               NEW.purpose, NEW.consent_receipt_sha256, NEW.manifest_sha256,
               NEW.captured_started_at, NEW.captured_ended_at,
               NEW.object_count, NEW.chunk_count, NEW.total_bytes,
               NEW.created_at
             ) IS DISTINCT FROM ROW(
               OLD.collection_id, OLD.privacy_subject_hmac,
               OLD.account_generation, OLD.walk_id, OLD.segment_id,
               OLD.purpose, OLD.consent_receipt_sha256, OLD.manifest_sha256,
               OLD.captured_started_at, OLD.captured_ended_at,
               OLD.object_count, OLD.chunk_count, OLD.total_bytes,
               OLD.created_at
             ) THEN
            RAISE EXCEPTION 'raw collection manifest metadata is immutable'
              USING ERRCODE = '23514';
          END IF;
          IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
            OLD.state = 'MANIFEST_ACCEPTED'
            AND NEW.state = 'READY_TO_COMMIT'
          ) THEN
            RAISE EXCEPTION 'raw collection state transition is not allowed in B1b'
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
        sa.Column("committed_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "raw_collections",
        sa.Column("retention_expires_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "raw_collections",
        sa.Column("receipt_sha256", sa.String(length=64), nullable=True),
    )
    op.create_check_constraint(
        "ck_raw_collections_receipt_sha256",
        "raw_collections",
        "receipt_sha256 IS NULL OR receipt_sha256 ~ '^[0-9a-f]{64}$'",
    )
    op.create_check_constraint(
        "ck_raw_collections_receipt_all_or_none",
        "raw_collections",
        "(state = 'COMMITTED' AND committed_at IS NOT NULL AND "
        "retention_expires_at IS NOT NULL AND receipt_sha256 IS NOT NULL) OR "
        "(state <> 'COMMITTED' AND committed_at IS NULL AND "
        "retention_expires_at IS NULL AND receipt_sha256 IS NULL)",
    )
    op.create_check_constraint(
        "ck_raw_collections_retention_exact",
        "raw_collections",
        "retention_expires_at IS NULL OR "
        "retention_expires_at = committed_at + INTERVAL '180 days'",
    )
    op.create_check_constraint(
        "ck_raw_collections_receipt_whole_seconds",
        "raw_collections",
        "(committed_at IS NULL OR "
        "committed_at = date_trunc('second', committed_at)) AND "
        "(retention_expires_at IS NULL OR "
        "retention_expires_at = date_trunc('second', retention_expires_at))",
    )
    op.create_unique_constraint(
        "uq_raw_collections_receipt_sha256",
        "raw_collections",
        ["receipt_sha256"],
    )
    op.create_index(
        "ix_raw_collections_retention_expires_at",
        "raw_collections",
        ["retention_expires_at"],
    )
    _install_b1c_state_guard()
    op.execute(
        "GRANT UPDATE (committed_at, retention_expires_at, receipt_sha256) "
        f"ON TABLE public.raw_collections TO {_RUNTIME_ROLE}"
    )


def downgrade() -> None:
    op.execute(_DOWNGRADE_LOCK_SQL)
    op.execute(_UNSAFE_DOWNGRADE_SQL)
    op.execute(
        "REVOKE UPDATE (committed_at, retention_expires_at, receipt_sha256) "
        f"ON TABLE public.raw_collections FROM {_RUNTIME_ROLE}"
    )
    _install_b1b_state_guard()
    op.drop_index(
        "ix_raw_collections_retention_expires_at",
        table_name="raw_collections",
    )
    op.drop_constraint(
        "uq_raw_collections_receipt_sha256",
        "raw_collections",
        type_="unique",
    )
    op.drop_constraint(
        "ck_raw_collections_receipt_whole_seconds",
        "raw_collections",
        type_="check",
    )
    op.drop_constraint(
        "ck_raw_collections_retention_exact",
        "raw_collections",
        type_="check",
    )
    op.drop_constraint(
        "ck_raw_collections_receipt_all_or_none",
        "raw_collections",
        type_="check",
    )
    op.drop_constraint(
        "ck_raw_collections_receipt_sha256",
        "raw_collections",
        type_="check",
    )
    op.drop_column("raw_collections", "receipt_sha256")
    op.drop_column("raw_collections", "retention_expires_at")
    op.drop_column("raw_collections", "committed_at")
