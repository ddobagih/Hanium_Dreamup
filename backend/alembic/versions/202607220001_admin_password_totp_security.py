"""add password and TOTP administrator security state

Revision ID: 202607220001
Revises: 202607160001
Create Date: 2026-07-22
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202607220001"
down_revision = "202607160001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "admin_security_controls",
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("password_hash", sa.Text(), nullable=False),
        sa.Column("totp_secret_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("last_totp_timecode", sa.BigInteger(), nullable=True),
        sa.Column("security_state", sa.String(length=32), nullable=False),
        sa.Column("state_version", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint(
            "security_state IN ('NORMAL', 'RECOVERY_REQUIRED', 'RECOVERY_IN_PROGRESS')",
            name="ck_admin_security_controls_state",
        ),
        sa.CheckConstraint("state_version >= 1", name="ck_admin_security_controls_version"),
        sa.CheckConstraint(
            "totp_secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_controls_totp_fingerprint",
        ),
        sa.PrimaryKeyConstraint("admin_id"),
    )
    op.create_table(
        "admin_security_sessions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("device_label", sa.String(length=128), nullable=False),
        sa.Column("token_sha256", sa.String(length=64), nullable=False),
        sa.Column("issued_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("step_up_verified_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoked_reason", sa.String(length=64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint(
            "token_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_sessions_token_sha256",
        ),
        sa.CheckConstraint("expires_at > issued_at", name="ck_admin_security_sessions_expiry"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_admin_security_sessions_admin_id", "admin_security_sessions", ["admin_id"])
    op.create_index("ix_admin_security_sessions_device_id", "admin_security_sessions", ["device_id"])
    op.create_index("ix_admin_security_sessions_expires_at", "admin_security_sessions", ["expires_at"])
    op.create_index("ix_admin_security_sessions_revoked_at", "admin_security_sessions", ["revoked_at"])
    op.create_index("ix_admin_security_sessions_token_sha256", "admin_security_sessions", ["token_sha256"], unique=True)

    op.create_table(
        "admin_security_recovery_codes",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("code_sha256", sa.String(length=64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "code_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_recovery_codes_sha256",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_admin_security_recovery_codes_admin_id", "admin_security_recovery_codes", ["admin_id"])
    op.create_index("ix_admin_security_recovery_codes_code_sha256", "admin_security_recovery_codes", ["code_sha256"], unique=True)
    op.create_index("ix_admin_security_recovery_codes_used_at", "admin_security_recovery_codes", ["used_at"])

    op.create_table(
        "admin_security_recovery_transactions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("recovery_code_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("recovery_token_sha256", sa.String(length=64), nullable=False),
        sa.Column("previous_totp_secret_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("device_label", sa.String(length=128), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint(
            "recovery_token_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_recovery_transactions_token_sha256",
        ),
        sa.CheckConstraint(
            "previous_totp_secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_recovery_transactions_totp_fingerprint",
        ),
        sa.CheckConstraint(
            "expires_at > started_at",
            name="ck_admin_security_recovery_transactions_expiry",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_admin_security_recovery_transactions_admin_id", "admin_security_recovery_transactions", ["admin_id"])
    op.create_index("ix_admin_security_recovery_transactions_recovery_code_id", "admin_security_recovery_transactions", ["recovery_code_id"])
    op.create_index("ix_admin_security_recovery_transactions_recovery_token_sha256", "admin_security_recovery_transactions", ["recovery_token_sha256"], unique=True)
    op.create_index("ix_admin_security_recovery_transactions_expires_at", "admin_security_recovery_transactions", ["expires_at"])
    op.create_index("ix_admin_security_recovery_transactions_completed_at", "admin_security_recovery_transactions", ["completed_at"])

    op.create_table(
        "admin_security_auth_attempts",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("action", sa.String(length=32), nullable=False),
        sa.Column("principal_sha256", sa.String(length=64), nullable=False),
        sa.Column("source_sha256", sa.String(length=64), nullable=False),
        sa.Column("success", sa.Boolean(), nullable=False),
        sa.Column("observed_at", sa.DateTime(timezone=True), server_default=sa.text("clock_timestamp()"), nullable=False),
        sa.CheckConstraint(
            "action IN ('login', 'reauthenticate', 'recovery_start', 'recovery_complete')",
            name="ck_admin_security_auth_attempts_action",
        ),
        sa.CheckConstraint(
            "principal_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_auth_attempts_principal_sha256",
        ),
        sa.CheckConstraint(
            "source_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_auth_attempts_source_sha256",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_admin_security_auth_attempts_lookup",
        "admin_security_auth_attempts",
        ["action", "principal_sha256", "source_sha256", "success", "observed_at"],
    )
    op.create_index("ix_admin_security_auth_attempts_observed_at", "admin_security_auth_attempts", ["observed_at"])

    op.create_table(
        "admin_security_audits",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=True),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("device_id", sa.String(length=128), nullable=True),
        sa.Column("action", sa.String(length=64), nullable=False),
        sa.Column("outcome", sa.String(length=16), nullable=False),
        sa.Column("details", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("clock_timestamp()"), nullable=False),
        sa.CheckConstraint(
            "outcome IN ('SUCCESS', 'DENIED', 'ERROR')",
            name="ck_admin_security_audits_outcome",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_admin_security_audits_admin_id", "admin_security_audits", ["admin_id"])
    op.create_index("ix_admin_security_audits_session_id", "admin_security_audits", ["session_id"])
    op.create_index("ix_admin_security_audits_device_id", "admin_security_audits", ["device_id"])
    op.create_index("ix_admin_security_audits_action", "admin_security_audits", ["action"])
    op.create_index("ix_admin_security_audits_created_at", "admin_security_audits", ["created_at"])
    op.execute(
        "CREATE TRIGGER admin_security_audits_append_only "
        "BEFORE UPDATE OR DELETE ON admin_security_audits "
        "FOR EACH ROW EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE TRIGGER admin_security_audits_reject_truncate "
        "BEFORE TRUNCATE ON admin_security_audits "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS admin_security_audits_reject_truncate ON admin_security_audits")
    op.execute("DROP TRIGGER IF EXISTS admin_security_audits_append_only ON admin_security_audits")
    op.drop_table("admin_security_audits")
    op.drop_table("admin_security_auth_attempts")
    op.drop_table("admin_security_recovery_transactions")
    op.drop_table("admin_security_recovery_codes")
    op.drop_table("admin_security_sessions")
    op.drop_table("admin_security_controls")
