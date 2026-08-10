"""add FP-008 administrator device proof, review, and delivery records

Revision ID: 202608080001
Revises: 202608020001
Create Date: 2026-08-08
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608080001"
down_revision = "202608020001"
branch_labels = None
depends_on = None


def _append_only(table_name: str) -> None:
    op.execute(
        f"CREATE TRIGGER {table_name}_append_only "
        f"BEFORE UPDATE OR DELETE OR TRUNCATE ON {table_name} "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )


def upgrade() -> None:
    op.create_table(
        "admin_device_keys",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("key_version", sa.Integer(), nullable=False),
        sa.Column("public_key_spki_der", sa.LargeBinary(), nullable=False),
        sa.Column("key_marker", sa.String(length=64), nullable=False),
        sa.Column(
            "status",
            sa.String(length=16),
            server_default=sa.text("'ACTIVE'"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("key_version >= 1", name="ck_admin_device_keys_key_version"),
        sa.CheckConstraint(
            "key_marker ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_keys_key_marker",
        ),
        sa.CheckConstraint(
            "octet_length(public_key_spki_der) BETWEEN 1 AND 4096",
            name="ck_admin_device_keys_public_key",
        ),
        sa.CheckConstraint(
            "status IN ('ACTIVE', 'REVOKED')",
            name="ck_admin_device_keys_status",
        ),
        sa.CheckConstraint(
            "(status = 'ACTIVE' AND revoked_at IS NULL) OR "
            "(status = 'REVOKED' AND revoked_at IS NOT NULL)",
            name="ck_admin_device_keys_revocation_state",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "admin_id",
            "device_id",
            "key_version",
            name="uq_admin_device_keys_admin_device_version",
        ),
        sa.UniqueConstraint("key_marker", name="uq_admin_device_keys_key_marker"),
    )
    op.create_index(
        "ix_admin_device_keys_active_lookup",
        "admin_device_keys",
        ["admin_id", "device_id", "status"],
    )
    op.create_index(
        "uq_admin_device_keys_one_active",
        "admin_device_keys",
        ["admin_id", "device_id"],
        unique=True,
        postgresql_where=sa.text("status = 'ACTIVE'"),
    )

    op.create_table(
        "admin_device_proof_challenges",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("challenge_type", sa.String(length=32), nullable=False),
        sa.Column("action", sa.String(length=64), nullable=True),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("body_sha256", sa.String(length=64), nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("device_key_marker", sa.String(length=64), nullable=False),
        sa.Column("device_key_version", sa.Integer(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("issued_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("method", sa.String(length=8), nullable=False),
        sa.Column("nonce", sa.String(length=128), nullable=False),
        sa.Column("purpose", sa.String(length=64), nullable=False),
        sa.Column("path", sa.String(length=512), nullable=False),
        sa.Column("query_sha256", sa.String(length=64), nullable=False),
        sa.Column("read_purpose", sa.String(length=64), nullable=True),
        sa.Column("schema_version", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("signing_payload", sa.Text(), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "length(challenge_type) BETWEEN 1 AND 32",
            name="ck_admin_device_proof_challenges_type",
        ),
        sa.CheckConstraint(
            "action IS NULL OR length(action) BETWEEN 1 AND 64",
            name="ck_admin_device_proof_challenges_action",
        ),
        sa.CheckConstraint(
            "body_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_proof_challenges_body_sha256",
        ),
        sa.CheckConstraint(
            "device_key_marker ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_proof_challenges_key_marker",
        ),
        sa.CheckConstraint(
            "device_key_version >= 1",
            name="ck_admin_device_proof_challenges_key_version",
        ),
        sa.CheckConstraint(
            "expires_at > issued_at",
            name="ck_admin_device_proof_challenges_expiry",
        ),
        sa.CheckConstraint(
            "method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')",
            name="ck_admin_device_proof_challenges_method",
        ),
        sa.CheckConstraint(
            "length(nonce) BETWEEN 1 AND 128",
            name="ck_admin_device_proof_challenges_nonce",
        ),
        sa.CheckConstraint(
            "length(path) BETWEEN 1 AND 512",
            name="ck_admin_device_proof_challenges_path",
        ),
        sa.CheckConstraint(
            "query_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_proof_challenges_query_sha256",
        ),
        sa.CheckConstraint(
            "purpose IN ('LOGIN', 'ACTION', 'RECOVERY_COMPLETE')",
            name="ck_admin_device_proof_challenges_purpose",
        ),
        sa.CheckConstraint(
            "length(schema_version) BETWEEN 1 AND 64",
            name="ck_admin_device_proof_challenges_schema_version",
        ),
        sa.CheckConstraint(
            "length(signing_payload) >= 1",
            name="ck_admin_device_proof_challenges_payload",
        ),
        sa.CheckConstraint(
            "consumed_at IS NULL OR "
            "(consumed_at >= issued_at AND consumed_at <= expires_at)",
            name="ck_admin_device_proof_challenges_consumed_at",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("nonce", name="uq_admin_device_proof_challenges_nonce"),
    )
    op.create_index(
        "ix_admin_device_proof_challenges_expiration",
        "admin_device_proof_challenges",
        ["expires_at", "consumed_at"],
    )
    op.create_index(
        "ix_admin_device_proof_challenges_correlation_id",
        "admin_device_proof_challenges",
        ["correlation_id"],
    )
    op.create_index(
        "ix_admin_device_proof_challenges_session_id",
        "admin_device_proof_challenges",
        ["session_id"],
    )

    op.create_table(
        "report_review_decisions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("decision", sa.String(length=16), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("duplicate_of_report_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("location_reviewed", sa.Boolean(), nullable=False),
        sa.Column("photo_reviewed", sa.Boolean(), nullable=False),
        sa.Column("privacy_reviewed", sa.Boolean(), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint("revision >= 1", name="ck_report_review_decisions_revision"),
        sa.CheckConstraint(
            "decision IN ('APPROVED', 'REJECTED', 'DUPLICATE')",
            name="ck_report_review_decisions_decision",
        ),
        sa.CheckConstraint(
            "length(reason) BETWEEN 1 AND 500",
            name="ck_report_review_decisions_reason",
        ),
        sa.CheckConstraint(
            "(decision = 'DUPLICATE' AND duplicate_of_report_id IS NOT NULL "
            "AND duplicate_of_report_id <> report_id) OR "
            "(decision <> 'DUPLICATE' AND duplicate_of_report_id IS NULL)",
            name="ck_report_review_decisions_duplicate",
        ),
        sa.CheckConstraint(
            "decision <> 'APPROVED' OR "
            "(location_reviewed AND photo_reviewed AND privacy_reviewed)",
            name="ck_report_review_decisions_approved_reviewed",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "report_id",
            "revision",
            name="uq_report_review_decisions_report_revision",
        ),
    )
    op.create_index(
        "ix_report_review_decisions_duplicate_of_report_id",
        "report_review_decisions",
        ["duplicate_of_report_id"],
    )
    op.create_index(
        "ix_report_review_decisions_admin_decided_at",
        "report_review_decisions",
        ["admin_id", "decided_at"],
    )
    op.create_index(
        "ix_report_review_decisions_correlation_id",
        "report_review_decisions",
        ["correlation_id"],
    )
    _append_only("report_review_decisions")

    op.create_table(
        "report_institution_delivery_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("review_decision_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("institution", sa.String(length=160), nullable=False),
        sa.Column("channel", sa.String(length=32), nullable=False),
        sa.Column("recipient", sa.String(length=255), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("external_receipt_id", sa.String(length=160), nullable=True),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("evidence_sha256", sa.String(length=64), nullable=True),
        sa.Column("observed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expected_revision", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "revision >= 1",
            name="ck_report_institution_delivery_revision",
        ),
        sa.CheckConstraint(
            "expected_revision >= 0 AND revision = expected_revision + 1",
            name="ck_report_institution_delivery_expected_revision",
        ),
        sa.CheckConstraint(
            "length(institution) BETWEEN 1 AND 160",
            name="ck_report_institution_delivery_institution",
        ),
        sa.CheckConstraint(
            "length(channel) BETWEEN 1 AND 32",
            name="ck_report_institution_delivery_channel",
        ),
        sa.CheckConstraint(
            "length(recipient) BETWEEN 1 AND 255",
            name="ck_report_institution_delivery_recipient",
        ),
        sa.CheckConstraint(
            "status IN ('SUBMITTED', 'ACKNOWLEDGED', 'RESOLVED', 'FAILED')",
            name="ck_report_institution_delivery_status",
        ),
        sa.CheckConstraint(
            "external_receipt_id IS NULL OR "
            "length(external_receipt_id) BETWEEN 1 AND 160",
            name="ck_report_institution_delivery_receipt",
        ),
        sa.CheckConstraint(
            "status NOT IN ('ACKNOWLEDGED', 'RESOLVED') OR "
            "external_receipt_id IS NOT NULL",
            name="ck_report_institution_delivery_receipt_required",
        ),
        sa.CheckConstraint(
            "length(reason) BETWEEN 1 AND 500",
            name="ck_report_institution_delivery_reason",
        ),
        sa.CheckConstraint(
            "evidence_sha256 IS NULL OR evidence_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_institution_delivery_evidence_sha256",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "report_id",
            "revision",
            name="uq_report_institution_delivery_report_revision",
        ),
        sa.UniqueConstraint(
            "report_id",
            "idempotency_key",
            name="uq_report_institution_delivery_idempotency",
        ),
    )
    op.create_index(
        "ix_report_institution_delivery_review_decision_id",
        "report_institution_delivery_events",
        ["review_decision_id"],
    )
    op.create_index(
        "ix_report_institution_delivery_status_observed_at",
        "report_institution_delivery_events",
        ["status", "observed_at"],
    )
    op.create_index(
        "ix_report_institution_delivery_admin_observed_at",
        "report_institution_delivery_events",
        ["admin_id", "observed_at"],
    )
    op.create_index(
        "ix_report_institution_delivery_correlation_id",
        "report_institution_delivery_events",
        ["correlation_id"],
    )
    _append_only("report_institution_delivery_events")


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS report_institution_delivery_events_append_only "
        "ON report_institution_delivery_events"
    )
    op.drop_table("report_institution_delivery_events")
    op.execute(
        "DROP TRIGGER IF EXISTS report_review_decisions_append_only "
        "ON report_review_decisions"
    )
    op.drop_table("report_review_decisions")
    op.drop_table("admin_device_proof_challenges")
    op.drop_table("admin_device_keys")
