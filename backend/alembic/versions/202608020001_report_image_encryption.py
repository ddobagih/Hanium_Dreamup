"""encrypt report image originals and add approved access audit

Revision ID: 202608020001
Revises: 202607260001
Create Date: 2026-08-02
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608020001"
down_revision = "202607260001"
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
        "report_image_objects",
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("storage_name", sa.String(length=160), nullable=False),
        sa.Column("envelope_version", sa.SmallInteger(), nullable=False),
        sa.Column("algorithm", sa.String(length=16), nullable=False),
        sa.Column("aad_version", sa.SmallInteger(), nullable=False),
        sa.Column("key_id", sa.String(length=64), nullable=False),
        sa.Column("nonce", sa.LargeBinary(length=12), nullable=False),
        sa.Column("plaintext_sha256", sa.String(length=64), nullable=False),
        sa.Column("plaintext_size", sa.BigInteger(), nullable=False),
        sa.Column("envelope_sha256", sa.String(length=64), nullable=False),
        sa.Column("envelope_size", sa.BigInteger(), nullable=False),
        sa.Column("content_type", sa.String(length=128), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "storage_name ~ '^[0-9a-f-]{36}\\.wse$'",
            name="ck_report_image_objects_storage_name",
        ),
        sa.CheckConstraint("envelope_version = 1", name="ck_report_image_objects_envelope_version"),
        sa.CheckConstraint("algorithm = 'AES-256-GCM'", name="ck_report_image_objects_algorithm"),
        sa.CheckConstraint("aad_version = 1", name="ck_report_image_objects_aad_version"),
        sa.CheckConstraint("octet_length(nonce) = 12", name="ck_report_image_objects_nonce"),
        sa.CheckConstraint(
            "plaintext_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_objects_plaintext_sha256",
        ),
        sa.CheckConstraint(
            "envelope_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_objects_envelope_sha256",
        ),
        sa.CheckConstraint("plaintext_size > 0", name="ck_report_image_objects_plaintext_size"),
        sa.CheckConstraint(
            "envelope_size > plaintext_size + 16",
            name="ck_report_image_objects_envelope_size",
        ),
        sa.CheckConstraint(
            "content_type IN ('image/jpeg', 'image/png', 'image/webp')",
            name="ck_report_image_objects_content_type",
        ),
        sa.ForeignKeyConstraint(["report_id"], ["reports.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("report_id"),
        sa.UniqueConstraint("key_id", "nonce", name="uq_report_image_objects_key_nonce"),
    )
    op.create_index("ix_report_image_objects_storage_name", "report_image_objects", ["storage_name"], unique=True)
    op.create_index("ix_report_image_objects_key_id", "report_image_objects", ["key_id"])
    op.create_index("ix_report_image_objects_created_at", "report_image_objects", ["created_at"])

    op.create_table(
        "report_original_access_grants",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("purpose", sa.String(length=32), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("token_sha256", sa.String(length=64), nullable=False),
        sa.Column("issued_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "purpose IN ('report_review', 'security_incident', 'data_subject_request')",
            name="ck_report_original_access_grants_purpose",
        ),
        sa.CheckConstraint(
            "length(reason) BETWEEN 8 AND 500",
            name="ck_report_original_access_grants_reason",
        ),
        sa.CheckConstraint(
            "token_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_original_access_grants_token_sha256",
        ),
        sa.CheckConstraint(
            "expires_at > issued_at",
            name="ck_report_original_access_grants_expiry",
        ),
        sa.ForeignKeyConstraint(["report_id"], ["reports.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    for column in ("report_id", "admin_id", "session_id", "device_id", "purpose", "expires_at", "consumed_at"):
        op.create_index(f"ix_report_original_access_grants_{column}", "report_original_access_grants", [column])
    op.create_index(
        "ix_report_original_access_grants_token_sha256",
        "report_original_access_grants",
        ["token_sha256"],
        unique=True,
    )

    op.create_table(
        "report_original_access_audits",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("grant_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("purpose", sa.String(length=32), nullable=True),
        sa.Column("action", sa.String(length=32), nullable=False),
        sa.Column("outcome", sa.String(length=16), nullable=False),
        sa.Column("reason_code", sa.String(length=64), nullable=False),
        sa.Column("key_id", sa.String(length=64), nullable=True),
        sa.Column("envelope_version", sa.SmallInteger(), nullable=True),
        sa.Column("plaintext_size", sa.BigInteger(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "action IN ('GRANT_ISSUED', 'GRANT_DENIED', 'GRANT_ERROR', "
            "'ACCESS_GRANTED', 'ACCESS_DENIED', 'ACCESS_ERROR')",
            name="ck_report_original_access_audits_action",
        ),
        sa.CheckConstraint(
            "outcome IN ('SUCCESS', 'DENIED', 'ERROR')",
            name="ck_report_original_access_audits_outcome",
        ),
        sa.CheckConstraint(
            "purpose IS NULL OR purpose IN ('report_review', 'security_incident', 'data_subject_request')",
            name="ck_report_original_access_audits_purpose",
        ),
        sa.CheckConstraint(
            "plaintext_size IS NULL OR plaintext_size > 0",
            name="ck_report_original_access_audits_plaintext_size",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    for column in (
        "grant_id",
        "report_id",
        "admin_id",
        "session_id",
        "device_id",
        "purpose",
        "action",
        "created_at",
    ):
        op.create_index(f"ix_report_original_access_audits_{column}", "report_original_access_audits", [column])
    _append_only("report_original_access_audits")

    op.create_table(
        "report_image_keyring_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("generation", sa.BigInteger(), nullable=False),
        sa.Column("manifest_sha256", sa.String(length=64), nullable=False),
        sa.Column("previous_manifest_sha256", sa.String(length=64), nullable=True),
        sa.Column("active_key_id", sa.String(length=64), nullable=False),
        sa.Column("key_states", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column(
            "observed_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint("generation >= 1", name="ck_report_image_keyring_events_generation"),
        sa.CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_keyring_events_manifest_sha256",
        ),
        sa.CheckConstraint(
            "previous_manifest_sha256 IS NULL OR previous_manifest_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_keyring_events_previous_manifest_sha256",
        ),
        sa.CheckConstraint(
            "(generation = 1 AND previous_manifest_sha256 IS NULL) OR "
            "(generation > 1 AND previous_manifest_sha256 IS NOT NULL)",
            name="ck_report_image_keyring_events_chain_position",
        ),
        sa.CheckConstraint(
            "jsonb_typeof(key_states) = 'array' AND "
            "jsonb_array_length(key_states) BETWEEN 1 AND 64",
            name="ck_report_image_keyring_events_key_states_array",
        ),
        sa.CheckConstraint(
            "NOT jsonb_path_exists(key_states, '$.**.material')",
            name="ck_report_image_keyring_events_no_material",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_report_image_keyring_events_generation",
        "report_image_keyring_events",
        ["generation"],
        unique=True,
    )
    op.create_index(
        "ix_report_image_keyring_events_manifest_sha256",
        "report_image_keyring_events",
        ["manifest_sha256"],
        unique=True,
    )
    op.create_index(
        "ix_report_image_keyring_events_observed_at",
        "report_image_keyring_events",
        ["observed_at"],
    )
    _append_only("report_image_keyring_events")


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS report_image_keyring_events_append_only "
        "ON report_image_keyring_events"
    )
    op.drop_table("report_image_keyring_events")
    op.execute(
        "DROP TRIGGER IF EXISTS report_original_access_audits_append_only "
        "ON report_original_access_audits"
    )
    op.drop_table("report_original_access_audits")
    op.drop_table("report_original_access_grants")
    op.drop_table("report_image_objects")
