"""add raw quarantine and approved training lifecycle

Revision ID: 202608290014
Revises: 202608290013
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290014"
down_revision = "202608290013"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"
_RAW_WORKER_ROLE = "walksafe_raw_retention_worker"
_TRAINING_WORKER_ROLE = "walksafe_training_lifecycle_worker"


def _uuid() -> postgresql.UUID:
    return postgresql.UUID(as_uuid=True)


def _append_only(table: str) -> None:
    op.execute(
        f"CREATE TRIGGER {table}_append_only BEFORE UPDATE OR DELETE ON {table} "
        "FOR EACH ROW EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        f"CREATE TRIGGER {table}_no_truncate BEFORE TRUNCATE ON {table} "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )


def _install_state_guard() -> None:
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
                 NEW.walk_id, NEW.segment_id, NEW.purpose, NEW.retention_class,
                 NEW.consent_receipt_sha256, NEW.manifest_sha256,
                 NEW.captured_started_at, NEW.captured_ended_at, NEW.object_count,
                 NEW.chunk_count, NEW.total_bytes, NEW.created_at)
             IS DISTINCT FROM
             ROW(OLD.collection_id, OLD.privacy_subject_hmac, OLD.account_generation,
                 OLD.walk_id, OLD.segment_id, OLD.purpose, OLD.retention_class,
                 OLD.consent_receipt_sha256, OLD.manifest_sha256,
                 OLD.captured_started_at, OLD.captured_ended_at, OLD.object_count,
                 OLD.chunk_count, OLD.total_bytes, OLD.created_at) THEN
            RAISE EXCEPTION 'raw collection manifest metadata is immutable' USING ERRCODE = '23514';
          END IF;
          IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
            OLD.state = 'MANIFEST_ACCEPTED' AND NEW.state = 'READY_TO_COMMIT'
            OR OLD.state = 'READY_TO_COMMIT' AND NEW.state = 'COMMITTED'
          ) THEN
            RAISE EXCEPTION 'raw collection state transition is not allowed' USING ERRCODE = '23514';
          END IF;
          IF ROW(NEW.committed_at, NEW.retention_expires_at, NEW.receipt_sha256)
             IS DISTINCT FROM ROW(OLD.committed_at, OLD.retention_expires_at, OLD.receipt_sha256)
             AND NOT (OLD.state = 'READY_TO_COMMIT' AND NEW.state = 'COMMITTED'
                      AND OLD.committed_at IS NULL AND OLD.retention_expires_at IS NULL
                      AND OLD.receipt_sha256 IS NULL) THEN
            RAISE EXCEPTION 'raw collection receipt metadata is immutable' USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END $$
        """
    )


def upgrade() -> None:
    op.add_column("raw_collections", sa.Column("lifecycle_version", sa.SmallInteger(), nullable=True))
    op.execute("UPDATE raw_collections SET lifecycle_version = 1 WHERE lifecycle_version IS NULL")
    op.alter_column("raw_collections", "lifecycle_version", nullable=False, server_default="2")
    op.add_column("raw_collections", sa.Column("quarantine_expires_at", sa.DateTime(timezone=True), nullable=True))
    op.create_index("ix_raw_collections_quarantine_expires_at", "raw_collections", ["quarantine_expires_at"])
    for name in (
        "ck_raw_collections_retention_class",
        "ck_raw_collections_state",
        "ck_raw_collections_receipt_all_or_none",
        "ck_raw_collections_retention_exact",
        "ck_raw_collections_receipt_whole_seconds",
    ):
        op.drop_constraint(name, "raw_collections", type_="check")
    op.create_check_constraint("ck_raw_collections_lifecycle_version", "raw_collections", "lifecycle_version IN (1, 2)")
    op.create_check_constraint(
        "ck_raw_collections_state", "raw_collections",
        "state IN ('MANIFEST_ACCEPTED','RECEIVING','READY_TO_COMMIT','COMMITTED','QUARANTINED')",
    )
    op.create_check_constraint(
        "ck_raw_collections_retention_class", "raw_collections",
        "(lifecycle_version = 1 AND retention_class = 'RAW_ORIGINAL_180D') OR "
        "(lifecycle_version = 2 AND retention_class = 'RAW_QUARANTINE_14D')",
    )
    op.create_check_constraint(
        "ck_raw_collections_receipt_all_or_none", "raw_collections",
        "(lifecycle_version = 1 AND state = 'COMMITTED' AND committed_at IS NOT NULL "
        "AND retention_expires_at IS NOT NULL AND quarantine_expires_at IS NULL AND receipt_sha256 IS NOT NULL) OR "
        "(lifecycle_version = 2 AND state = 'QUARANTINED' AND committed_at IS NOT NULL "
        "AND retention_expires_at IS NULL AND quarantine_expires_at IS NOT NULL AND receipt_sha256 IS NOT NULL) OR "
        "(state NOT IN ('COMMITTED', 'QUARANTINED') AND committed_at IS NULL AND retention_expires_at IS NULL "
        "AND quarantine_expires_at IS NULL AND receipt_sha256 IS NULL)",
    )
    op.create_check_constraint(
        "ck_raw_collections_retention_exact", "raw_collections",
        "(retention_expires_at IS NULL OR retention_expires_at = committed_at + INTERVAL '180 days') AND "
        "(quarantine_expires_at IS NULL OR quarantine_expires_at = committed_at + INTERVAL '14 days')",
    )
    op.create_check_constraint(
        "ck_raw_collections_receipt_whole_seconds", "raw_collections",
        "(committed_at IS NULL OR committed_at = date_trunc('second', committed_at)) AND "
        "(retention_expires_at IS NULL OR retention_expires_at = date_trunc('second', retention_expires_at)) AND "
        "(quarantine_expires_at IS NULL OR quarantine_expires_at = date_trunc('second', quarantine_expires_at))",
    )
    _install_state_guard()
    op.execute(f"GRANT UPDATE (quarantine_expires_at) ON raw_collections TO {_RUNTIME_ROLE}")

    op.create_table(
        "raw_collection_purpose_decisions",
        sa.Column("id", _uuid(), primary_key=True), sa.Column("collection_id", _uuid(), nullable=False),
        sa.Column("scope", sa.String(16), nullable=False), sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("expected_revision", sa.BigInteger(), nullable=False), sa.Column("idempotency_key", _uuid(), nullable=False),
        sa.Column("decision", sa.String(16), nullable=False), sa.Column("reason", sa.String(500), nullable=False),
        sa.Column("source_manifest_sha256", sa.String(64), nullable=False), sa.Column("source_receipt_sha256", sa.String(64), nullable=False),
        sa.Column("training_consent_receipt_sha256", sa.String(64)), sa.Column("deidentification_receipt_sha256", sa.String(64)),
        sa.Column("sanitized_manifest_sha256", sa.String(64)), sa.Column("target_dataset_id", _uuid()),
        sa.Column("exact_location_excluded", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("raw_audio_excluded", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("third_party_faces_excluded", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("admin_id", sa.String(64), nullable=False), sa.Column("session_id", _uuid(), nullable=False),
        sa.Column("device_id", sa.String(128), nullable=False), sa.Column("correlation_id", _uuid(), nullable=False),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.clock_timestamp()),
        sa.UniqueConstraint("collection_id", "scope", "revision", name="uq_raw_purpose_decision_revision"),
        sa.UniqueConstraint("collection_id", "scope", "idempotency_key", name="uq_raw_purpose_decision_idempotency"),
        sa.CheckConstraint("scope IN ('REPORT', 'TRAINING')", name="ck_raw_purpose_decision_scope"),
        sa.CheckConstraint("decision IN ('APPROVED', 'REJECTED')", name="ck_raw_purpose_decision_value"),
        sa.CheckConstraint("expected_revision >= 0 AND revision = expected_revision + 1", name="ck_raw_purpose_decision_sequence"),
        sa.CheckConstraint("length(reason) BETWEEN 1 AND 500", name="ck_raw_purpose_decision_reason"),
        sa.CheckConstraint("source_manifest_sha256 ~ '^[0-9a-f]{64}$' AND source_receipt_sha256 ~ '^[0-9a-f]{64}$'", name="ck_raw_purpose_decision_source_sha"),
        sa.CheckConstraint("(scope = 'TRAINING' AND decision = 'APPROVED' AND training_consent_receipt_sha256 ~ '^[0-9a-f]{64}$' AND deidentification_receipt_sha256 ~ '^[0-9a-f]{64}$' AND sanitized_manifest_sha256 ~ '^[0-9a-f]{64}$' AND exact_location_excluded AND raw_audio_excluded AND third_party_faces_excluded) OR NOT (scope = 'TRAINING' AND decision = 'APPROVED')", name="ck_raw_purpose_decision_training_approval"),
    )
    op.create_index("ix_raw_purpose_decision_collection_scope", "raw_collection_purpose_decisions", ["collection_id", "scope", "revision"])

    op.create_table(
        "raw_collection_legal_hold_events",
        sa.Column("id", _uuid(), primary_key=True), sa.Column("collection_id", _uuid(), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False), sa.Column("expected_revision", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", _uuid(), nullable=False), sa.Column("action", sa.String(16), nullable=False),
        sa.Column("reason", sa.String(500), nullable=False), sa.Column("legal_basis", sa.String(500)),
        sa.Column("authority_reference", sa.String(160)), sa.Column("contact", sa.String(160)),
        sa.Column("expires_at", sa.DateTime(timezone=True)), sa.Column("admin_id", sa.String(64), nullable=False),
        sa.Column("session_id", _uuid(), nullable=False), sa.Column("device_id", sa.String(128), nullable=False),
        sa.Column("correlation_id", _uuid(), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.clock_timestamp()),
        sa.UniqueConstraint("collection_id", "revision", name="uq_raw_legal_hold_revision"),
        sa.UniqueConstraint("collection_id", "idempotency_key", name="uq_raw_legal_hold_idempotency"),
        sa.CheckConstraint("action IN ('APPLY', 'RELEASE')", name="ck_raw_legal_hold_action"),
        sa.CheckConstraint("expected_revision >= 0 AND revision = expected_revision + 1", name="ck_raw_legal_hold_sequence"),
        sa.CheckConstraint("length(reason) BETWEEN 1 AND 500", name="ck_raw_legal_hold_reason"),
        sa.CheckConstraint("(action = 'APPLY' AND length(legal_basis) BETWEEN 1 AND 500 AND length(authority_reference) BETWEEN 1 AND 160 AND length(contact) BETWEEN 1 AND 160 AND expires_at > recorded_at) OR (action = 'RELEASE' AND legal_basis IS NULL AND authority_reference IS NULL AND contact IS NULL AND expires_at IS NULL)", name="ck_raw_legal_hold_fields"),
    )
    op.create_index("ix_raw_legal_hold_collection", "raw_collection_legal_hold_events", ["collection_id", "revision"])
    op.create_index("ix_raw_legal_hold_expires", "raw_collection_legal_hold_events", ["expires_at"])

    op.create_table(
        "raw_collection_deletion_receipts",
        sa.Column("collection_id", _uuid(), primary_key=True), sa.Column("reason", sa.String(48), nullable=False),
        sa.Column("source_manifest_sha256", sa.String(64), nullable=False), sa.Column("source_receipt_sha256", sa.String(64), nullable=False),
        sa.Column("inventory_sha256", sa.String(64), nullable=False), sa.Column("chunk_count", sa.Integer(), nullable=False),
        sa.Column("total_bytes", sa.BigInteger(), nullable=False), sa.Column("receipt_sha256", sa.String(64), nullable=False, unique=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("reason IN ('LEGACY_RETENTION_EXPIRED','UNAPPROVED_EXPIRED','PROMOTED_SOURCE_EXPIRED','PROMOTION_INCOMPLETE_EXPIRED','REJECTED')", name="ck_raw_deletion_receipt_reason"),
        sa.CheckConstraint("source_manifest_sha256 ~ '^[0-9a-f]{64}$' AND source_receipt_sha256 ~ '^[0-9a-f]{64}$' AND inventory_sha256 ~ '^[0-9a-f]{64}$' AND receipt_sha256 ~ '^[0-9a-f]{64}$'", name="ck_raw_deletion_receipt_sha"),
        sa.CheckConstraint("chunk_count >= 1 AND total_bytes >= 1", name="ck_raw_deletion_receipt_counts"),
    )

    op.create_table(
        "approved_training_artifacts",
        sa.Column("id", _uuid(), primary_key=True), sa.Column("source_collection_id", _uuid(), nullable=False),
        sa.Column("source_object_id", _uuid(), nullable=False), sa.Column("source_sha256", sa.String(64), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(64), nullable=False), sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("consent_receipt_sha256", sa.String(64), nullable=False), sa.Column("approval_decision_id", _uuid(), nullable=False),
        sa.Column("deidentification_receipt_sha256", sa.String(64), nullable=False), sa.Column("kind", sa.String(32), nullable=False),
        sa.Column("content_type", sa.String(128), nullable=False), sa.Column("content_sha256", sa.String(64), nullable=False),
        sa.Column("content_size", sa.BigInteger(), nullable=False), sa.Column("storage_name", sa.String(160), nullable=False, unique=True),
        sa.Column("key_id", sa.String(64), nullable=False), sa.Column("nonce", sa.LargeBinary(12), nullable=False),
        sa.Column("envelope_sha256", sa.String(64), nullable=False), sa.Column("envelope_size", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.clock_timestamp()),
        sa.CheckConstraint("kind IN ('SANITIZED_IMAGE','LABEL','METADATA')", name="ck_training_artifact_kind"),
        sa.CheckConstraint("privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1", name="ck_training_artifact_subject"),
        sa.CheckConstraint("source_sha256 ~ '^[0-9a-f]{64}$' AND content_sha256 ~ '^[0-9a-f]{64}$' AND consent_receipt_sha256 ~ '^[0-9a-f]{64}$' AND deidentification_receipt_sha256 ~ '^[0-9a-f]{64}$' AND envelope_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_artifact_sha"),
        sa.CheckConstraint("content_size > 0 AND envelope_size > content_size", name="ck_training_artifact_sizes"),
        sa.CheckConstraint("storage_name ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,159}$' AND position('..' in storage_name) = 0 AND position('//' in storage_name) = 0 AND right(storage_name, 1) <> '/'", name="ck_training_artifact_storage_name"),
        sa.CheckConstraint("key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' AND octet_length(nonce) = 12", name="ck_training_artifact_envelope_key"),
    )
    op.create_index("ix_training_artifact_subject", "approved_training_artifacts", ["privacy_subject_hmac", "account_generation"])

    op.create_table(
        "training_dataset_revisions",
        sa.Column("id", _uuid(), primary_key=True), sa.Column("dataset_id", _uuid(), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("parent_revision_id", _uuid(), sa.ForeignKey("training_dataset_revisions.id", ondelete="RESTRICT")),
        sa.Column("manifest_sha256", sa.String(64), nullable=False, unique=True), sa.Column("member_set_sha256", sa.String(64), nullable=False),
        sa.Column("approved_at", sa.DateTime(timezone=True), nullable=False), sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("admin_id", sa.String(64), nullable=False), sa.Column("correlation_id", _uuid(), nullable=False),
        sa.UniqueConstraint("dataset_id", "revision", name="uq_training_dataset_revision"),
        sa.CheckConstraint("revision >= 1", name="ck_training_dataset_revision_positive"),
        sa.CheckConstraint("manifest_sha256 ~ '^[0-9a-f]{64}$' AND member_set_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_dataset_revision_sha"),
        sa.CheckConstraint("expires_at = approved_at + INTERVAL '3 years'", name="ck_training_dataset_revision_expiry"),
    )
    op.create_index("ix_training_dataset_id_revision", "training_dataset_revisions", ["dataset_id", "revision"])
    op.create_index("ix_training_dataset_expires", "training_dataset_revisions", ["expires_at"])
    op.create_table(
        "training_dataset_members",
        sa.Column("dataset_revision_id", _uuid(), sa.ForeignKey("training_dataset_revisions.id", ondelete="RESTRICT"), primary_key=True),
        sa.Column("artifact_id", _uuid(), primary_key=True), sa.Column("split", sa.String(8), nullable=False),
        sa.Column("content_sha256", sa.String(64), nullable=False), sa.Column("source_collection_id", _uuid(), nullable=False),
        sa.Column("source_object_id", _uuid(), nullable=False),
        sa.CheckConstraint("split IN ('train','val','test')", name="ck_training_dataset_member_split"),
        sa.CheckConstraint("content_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_dataset_member_sha"),
    )
    op.create_table(
        "training_dataset_lifecycle_events",
        sa.Column("id", _uuid(), primary_key=True),
        sa.Column("dataset_revision_id", _uuid(), sa.ForeignKey("training_dataset_revisions.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False), sa.Column("state", sa.String(16), nullable=False),
        sa.Column("reason", sa.String(500), nullable=False), sa.Column("receipt_sha256", sa.String(64), nullable=False, unique=True),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.clock_timestamp()),
        sa.UniqueConstraint("dataset_revision_id", "revision", name="uq_training_dataset_lifecycle_revision"),
        sa.CheckConstraint("state IN ('APPROVED','RETIRED','EXPIRED')", name="ck_training_dataset_lifecycle_state"),
        sa.CheckConstraint("revision >= 1 AND receipt_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_dataset_lifecycle_receipt"),
    )
    op.create_table(
        "training_artifact_deletion_receipts",
        sa.Column("artifact_id", _uuid(), primary_key=True), sa.Column("reason", sa.String(32), nullable=False),
        sa.Column("content_sha256", sa.String(64), nullable=False), sa.Column("receipt_sha256", sa.String(64), nullable=False, unique=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("reason IN ('CONSENT_WITHDRAWN','ACCOUNT_DELETED','DATASET_EXPIRED')", name="ck_training_artifact_deletion_reason"),
        sa.CheckConstraint("content_sha256 ~ '^[0-9a-f]{64}$' AND receipt_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_artifact_deletion_sha"),
    )

    append_tables = (
        "raw_collection_purpose_decisions", "raw_collection_legal_hold_events",
        "raw_collection_deletion_receipts", "training_dataset_revisions",
        "training_dataset_members", "training_dataset_lifecycle_events",
        "training_artifact_deletion_receipts",
    )
    for table in append_tables:
        _append_only(table)
    op.execute(
        "CREATE TRIGGER approved_training_artifacts_no_update "
        "BEFORE UPDATE ON approved_training_artifacts FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE TRIGGER approved_training_artifacts_no_truncate "
        "BEFORE TRUNCATE ON approved_training_artifacts FOR EACH STATEMENT "
        "EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )

    op.execute(
        f"""DO $$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{_TRAINING_WORKER_ROLE}') THEN
          CREATE ROLE {_TRAINING_WORKER_ROLE} NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        END $$"""
    )
    lifecycle_tables = "raw_collection_purpose_decisions, raw_collection_legal_hold_events, raw_collection_deletion_receipts, approved_training_artifacts, training_dataset_revisions, training_dataset_members, training_dataset_lifecycle_events, training_artifact_deletion_receipts"
    op.execute(f"GRANT SELECT, INSERT ON {lifecycle_tables} TO {_RUNTIME_ROLE}")
    op.execute(f"GRANT SELECT ON raw_collection_purpose_decisions, raw_collection_legal_hold_events, approved_training_artifacts TO {_RAW_WORKER_ROLE}")
    op.execute(f"GRANT INSERT ON raw_collection_deletion_receipts TO {_RAW_WORKER_ROLE}")
    op.execute(f"GRANT USAGE ON SCHEMA public TO {_TRAINING_WORKER_ROLE}")
    op.execute(f"GRANT SELECT ON privacy_consent_events, account_deletion_tombstones, approved_training_artifacts, training_dataset_revisions, training_dataset_members, training_dataset_lifecycle_events, training_artifact_deletion_receipts TO {_TRAINING_WORKER_ROLE}")
    op.execute(f"GRANT INSERT ON training_dataset_revisions, training_dataset_members, training_dataset_lifecycle_events, training_artifact_deletion_receipts TO {_TRAINING_WORKER_ROLE}")
    op.execute(f"GRANT DELETE ON approved_training_artifacts TO {_TRAINING_WORKER_ROLE}")


def downgrade() -> None:
    op.execute("DO $$ BEGIN IF EXISTS (SELECT 1 FROM raw_collections WHERE lifecycle_version = 2) THEN RAISE EXCEPTION 'cannot downgrade while v2 raw collections exist'; END IF; END $$")
    op.execute(
        "DO $$ BEGIN IF EXISTS (SELECT 1 FROM raw_collection_purpose_decisions) "
        "OR EXISTS (SELECT 1 FROM raw_collection_legal_hold_events) "
        "OR EXISTS (SELECT 1 FROM raw_collection_deletion_receipts) "
        "OR EXISTS (SELECT 1 FROM approved_training_artifacts) "
        "OR EXISTS (SELECT 1 FROM training_dataset_revisions) "
        "OR EXISTS (SELECT 1 FROM training_artifact_deletion_receipts) THEN "
        "RAISE EXCEPTION 'cannot downgrade while raw/training lifecycle evidence exists'; "
        "END IF; END $$"
    )
    op.execute("DROP TRIGGER IF EXISTS approved_training_artifacts_no_update ON approved_training_artifacts")
    op.execute("DROP TRIGGER IF EXISTS approved_training_artifacts_no_truncate ON approved_training_artifacts")
    for table in (
        "training_artifact_deletion_receipts", "training_dataset_lifecycle_events",
        "training_dataset_members", "training_dataset_revisions", "raw_collection_deletion_receipts",
        "raw_collection_legal_hold_events", "raw_collection_purpose_decisions",
    ):
        op.execute(f"DROP TRIGGER IF EXISTS {table}_no_truncate ON {table}")
        op.execute(f"DROP TRIGGER IF EXISTS {table}_append_only ON {table}")
    for table in (
        "training_artifact_deletion_receipts", "training_dataset_lifecycle_events",
        "training_dataset_members", "training_dataset_revisions", "approved_training_artifacts",
        "raw_collection_deletion_receipts", "raw_collection_legal_hold_events",
        "raw_collection_purpose_decisions",
    ):
        op.drop_table(table)
    op.drop_constraint("ck_raw_collections_lifecycle_version", "raw_collections", type_="check")
    for name in ("ck_raw_collections_retention_class", "ck_raw_collections_state", "ck_raw_collections_receipt_all_or_none", "ck_raw_collections_retention_exact", "ck_raw_collections_receipt_whole_seconds"):
        op.drop_constraint(name, "raw_collections", type_="check")
    op.drop_index("ix_raw_collections_quarantine_expires_at", table_name="raw_collections")
    op.drop_column("raw_collections", "quarantine_expires_at")
    op.drop_column("raw_collections", "lifecycle_version")
    op.create_check_constraint("ck_raw_collections_retention_class", "raw_collections", "retention_class = 'RAW_ORIGINAL_180D'")
    op.create_check_constraint("ck_raw_collections_state", "raw_collections", "state IN ('MANIFEST_ACCEPTED','RECEIVING','READY_TO_COMMIT','COMMITTED')")
    op.create_check_constraint("ck_raw_collections_receipt_all_or_none", "raw_collections", "(state = 'COMMITTED' AND committed_at IS NOT NULL AND retention_expires_at IS NOT NULL AND receipt_sha256 IS NOT NULL) OR (state <> 'COMMITTED' AND committed_at IS NULL AND retention_expires_at IS NULL AND receipt_sha256 IS NULL)")
    op.create_check_constraint("ck_raw_collections_retention_exact", "raw_collections", "retention_expires_at IS NULL OR retention_expires_at = committed_at + INTERVAL '180 days'")
    op.create_check_constraint("ck_raw_collections_receipt_whole_seconds", "raw_collections", "(committed_at IS NULL OR committed_at = date_trunc('second', committed_at)) AND (retention_expires_at IS NULL OR retention_expires_at = date_trunc('second', retention_expires_at))")
    _install_legacy_state_guard()
