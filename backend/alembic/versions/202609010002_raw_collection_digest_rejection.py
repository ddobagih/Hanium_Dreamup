"""add private one-way raw object digest rejection marker

Revision ID: 202609010002
Revises: 202609010001
Create Date: 2026-09-01
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202609010002"
down_revision = "202609010001"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"


def _install_digest_rejection_state_guard() -> None:
    op.execute(
        """
        CREATE OR REPLACE FUNCTION public.walksafe_guard_raw_collection_state()
        RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
        BEGIN
          IF TG_OP = 'INSERT' THEN
            IF NEW.state <> 'MANIFEST_ACCEPTED' THEN
              RAISE EXCEPTION 'raw collection must start in MANIFEST_ACCEPTED' USING ERRCODE = '23514';
            END IF;
            IF NEW.digest_rejected_at IS NOT NULL
               OR NEW.digest_rejected_object_id IS NOT NULL THEN
              RAISE EXCEPTION 'raw collection must start without a digest rejection' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;
          IF ROW(NEW.collection_id, NEW.privacy_subject_hmac, NEW.account_generation,
                 NEW.walk_id, NEW.segment_id, NEW.purpose, NEW.lifecycle_version,
                 NEW.retention_class, NEW.consent_receipt_sha256, NEW.manifest_sha256,
                 NEW.captured_started_at, NEW.captured_ended_at, NEW.object_count,
                 NEW.chunk_count, NEW.total_bytes, NEW.created_at)
             IS DISTINCT FROM
             ROW(OLD.collection_id, OLD.privacy_subject_hmac, OLD.account_generation,
                 OLD.walk_id, OLD.segment_id, OLD.purpose, OLD.lifecycle_version,
                 OLD.retention_class, OLD.consent_receipt_sha256, OLD.manifest_sha256,
                 OLD.captured_started_at, OLD.captured_ended_at, OLD.object_count,
                 OLD.chunk_count, OLD.total_bytes, OLD.created_at) THEN
            RAISE EXCEPTION 'raw collection manifest metadata is immutable' USING ERRCODE = '23514';
          END IF;
          IF NEW.state IS DISTINCT FROM OLD.state
             AND (OLD.digest_rejected_at IS NOT NULL
                  OR NEW.digest_rejected_at IS NOT NULL) THEN
            RAISE EXCEPTION 'digest-rejected raw collection state is immutable' USING ERRCODE = '23514';
          END IF;
          IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
            OLD.state = 'MANIFEST_ACCEPTED' AND NEW.state = 'READY_TO_COMMIT'
            OR OLD.state = 'READY_TO_COMMIT' AND NEW.state = 'COMMITTED' AND OLD.lifecycle_version = 1
            OR OLD.state = 'READY_TO_COMMIT' AND NEW.state = 'QUARANTINED' AND OLD.lifecycle_version = 2
          ) THEN
            RAISE EXCEPTION 'raw collection state transition is not allowed' USING ERRCODE = '23514';
          END IF;
          IF ROW(NEW.committed_at, NEW.retention_expires_at,
                 NEW.quarantine_expires_at, NEW.receipt_sha256)
             IS DISTINCT FROM
             ROW(OLD.committed_at, OLD.retention_expires_at,
                 OLD.quarantine_expires_at, OLD.receipt_sha256)
             AND NOT (OLD.state = 'READY_TO_COMMIT'
                      AND NEW.state IN ('COMMITTED', 'QUARANTINED')
                      AND OLD.committed_at IS NULL
                      AND OLD.retention_expires_at IS NULL
                      AND OLD.quarantine_expires_at IS NULL
                      AND OLD.receipt_sha256 IS NULL) THEN
            RAISE EXCEPTION 'raw collection receipt metadata is immutable' USING ERRCODE = '23514';
          END IF;
          IF ROW(NEW.digest_rejected_at, NEW.digest_rejected_object_id)
             IS DISTINCT FROM
             ROW(OLD.digest_rejected_at, OLD.digest_rejected_object_id) THEN
            IF OLD.digest_rejected_at IS NOT NULL
               OR OLD.digest_rejected_object_id IS NOT NULL
               OR NEW.digest_rejected_at IS NULL
               OR NEW.digest_rejected_object_id IS NULL
               OR NEW.state IN ('COMMITTED', 'QUARANTINED')
               OR NEW.committed_at IS NOT NULL
               OR NEW.retention_expires_at IS NOT NULL
               OR NEW.quarantine_expires_at IS NOT NULL
               OR NEW.receipt_sha256 IS NOT NULL
               OR NOT EXISTS (
                    SELECT 1
                      FROM public.raw_collection_objects AS item
                     WHERE item.collection_id = NEW.collection_id
                       AND item.object_id = NEW.digest_rejected_object_id
                       AND item.manifest_sha256 = NEW.manifest_sha256
                       AND item.consent_receipt_sha256 = NEW.consent_receipt_sha256
               ) THEN
              RAISE EXCEPTION 'raw collection digest rejection transition is not allowed' USING ERRCODE = '23514';
            END IF;
          END IF;
          RETURN NEW;
        END $$
        """
    )


def _install_legacy_state_guard() -> None:
    op.execute(
        """
        CREATE OR REPLACE FUNCTION public.walksafe_guard_raw_collection_state()
        RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
        BEGIN
          IF TG_OP = 'INSERT' THEN
            IF NEW.state <> 'MANIFEST_ACCEPTED' THEN
              RAISE EXCEPTION 'raw collection must start in MANIFEST_ACCEPTED' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;
          IF ROW(NEW.collection_id, NEW.privacy_subject_hmac, NEW.account_generation,
                 NEW.walk_id, NEW.segment_id, NEW.purpose, NEW.lifecycle_version,
                 NEW.retention_class, NEW.consent_receipt_sha256, NEW.manifest_sha256,
                 NEW.captured_started_at, NEW.captured_ended_at, NEW.object_count,
                 NEW.chunk_count, NEW.total_bytes, NEW.created_at)
             IS DISTINCT FROM
             ROW(OLD.collection_id, OLD.privacy_subject_hmac, OLD.account_generation,
                 OLD.walk_id, OLD.segment_id, OLD.purpose, OLD.lifecycle_version,
                 OLD.retention_class, OLD.consent_receipt_sha256, OLD.manifest_sha256,
                 OLD.captured_started_at, OLD.captured_ended_at, OLD.object_count,
                 OLD.chunk_count, OLD.total_bytes, OLD.created_at) THEN
            RAISE EXCEPTION 'raw collection manifest metadata is immutable' USING ERRCODE = '23514';
          END IF;
          IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
            OLD.state = 'MANIFEST_ACCEPTED' AND NEW.state = 'READY_TO_COMMIT'
            OR OLD.state = 'READY_TO_COMMIT' AND NEW.state = 'COMMITTED' AND OLD.lifecycle_version = 1
            OR OLD.state = 'READY_TO_COMMIT' AND NEW.state = 'QUARANTINED' AND OLD.lifecycle_version = 2
          ) THEN
            RAISE EXCEPTION 'raw collection state transition is not allowed' USING ERRCODE = '23514';
          END IF;
          IF ROW(NEW.committed_at, NEW.retention_expires_at,
                 NEW.quarantine_expires_at, NEW.receipt_sha256)
             IS DISTINCT FROM
             ROW(OLD.committed_at, OLD.retention_expires_at,
                 OLD.quarantine_expires_at, OLD.receipt_sha256)
             AND NOT (OLD.state = 'READY_TO_COMMIT'
                      AND NEW.state IN ('COMMITTED', 'QUARANTINED')
                      AND OLD.committed_at IS NULL
                      AND OLD.retention_expires_at IS NULL
                      AND OLD.quarantine_expires_at IS NULL
                      AND OLD.receipt_sha256 IS NULL) THEN
            RAISE EXCEPTION 'raw collection receipt metadata is immutable' USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END $$
        """
    )


def upgrade() -> None:
    op.add_column(
        "raw_collections",
        sa.Column("digest_rejected_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "raw_collections",
        sa.Column(
            "digest_rejected_object_id",
            postgresql.UUID(as_uuid=True),
            nullable=True,
        ),
    )
    op.create_check_constraint(
        "ck_raw_collections_digest_rejection_all_or_none",
        "raw_collections",
        "(digest_rejected_at IS NULL AND digest_rejected_object_id IS NULL) OR "
        "(digest_rejected_at IS NOT NULL AND digest_rejected_object_id IS NOT NULL)",
    )
    op.create_check_constraint(
        "ck_raw_collections_digest_rejection_no_receipt",
        "raw_collections",
        "digest_rejected_at IS NULL OR "
        "(state NOT IN ('COMMITTED', 'QUARANTINED') "
        "AND committed_at IS NULL AND retention_expires_at IS NULL "
        "AND quarantine_expires_at IS NULL AND receipt_sha256 IS NULL)",
    )
    _install_digest_rejection_state_guard()
    op.execute(
        "GRANT UPDATE (digest_rejected_at, digest_rejected_object_id) "
        f"ON TABLE public.raw_collections TO {_RUNTIME_ROLE}"
    )


def downgrade() -> None:
    op.execute("LOCK TABLE public.raw_collections IN ACCESS EXCLUSIVE MODE")
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1 FROM public.raw_collections
             WHERE digest_rejected_at IS NOT NULL
                OR digest_rejected_object_id IS NOT NULL
          ) THEN
            RAISE EXCEPTION 'cannot downgrade while raw digest rejections exist'
              USING ERRCODE = '55000';
          END IF;
        END
        $$
        """
    )
    op.execute(
        "REVOKE UPDATE (digest_rejected_at, digest_rejected_object_id) "
        f"ON TABLE public.raw_collections FROM {_RUNTIME_ROLE}"
    )
    _install_legacy_state_guard()
    op.drop_constraint(
        "ck_raw_collections_digest_rejection_no_receipt",
        "raw_collections",
        type_="check",
    )
    op.drop_constraint(
        "ck_raw_collections_digest_rejection_all_or_none",
        "raw_collections",
        type_="check",
    )
    op.drop_column("raw_collections", "digest_rejected_object_id")
    op.drop_column("raw_collections", "digest_rejected_at")
