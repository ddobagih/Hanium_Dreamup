"""add typed append-only administrator operation audit

Revision ID: 202608290004
Revises: 202608290003
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290004"
down_revision = "202608290003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "admin_operation_audits",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("actor_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("operation", sa.String(length=64), nullable=False),
        sa.Column("outcome", sa.String(length=16), nullable=False),
        sa.Column("resource_type", sa.String(length=32), nullable=False),
        sa.Column("resource_id", sa.String(length=160), nullable=False),
        sa.Column("query_sha256", sa.String(length=64), nullable=False),
        sa.Column("result_count", sa.BigInteger(), nullable=True),
        sa.Column("error_code", sa.String(length=64), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "outcome IN ('SUCCEEDED', 'DENIED', 'ERROR')",
            name="ck_admin_operation_audits_outcome",
        ),
        sa.CheckConstraint(
            "query_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_operation_audits_query_sha256",
        ),
        sa.CheckConstraint(
            "error_code IS NULL OR error_code ~ '^[a-z][a-z0-9_:-]{2,63}$'",
            name="ck_admin_operation_audits_error_code",
        ),
        sa.CheckConstraint(
            "result_count IS NULL OR result_count >= 0",
            name="ck_admin_operation_audits_result_count",
        ),
        sa.CheckConstraint(
            "(outcome = 'SUCCEEDED' AND result_count IS NOT NULL AND error_code IS NULL) OR "
            "(outcome IN ('DENIED', 'ERROR') AND result_count IS NULL AND error_code IS NOT NULL)",
            name="ck_admin_operation_audits_result",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    for column in ("actor_id", "session_id", "device_id"):
        op.create_index(
            f"ix_admin_operation_audits_{column}",
            "admin_operation_audits",
            [column],
        )
    op.create_index(
        "ix_admin_operation_audits_operation_created_at",
        "admin_operation_audits",
        ["operation", "created_at"],
    )
    op.create_index(
        "ix_admin_operation_audits_correlation_id",
        "admin_operation_audits",
        ["correlation_id"],
    )
    op.execute(
        "CREATE TRIGGER admin_operation_audits_append_only "
        "BEFORE UPDATE OR DELETE OR TRUNCATE ON admin_operation_audits "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.admin_operation_audits "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.admin_operation_audits "
        "TO walksafe_backend_runtime"
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS admin_operation_audits_append_only "
        "ON admin_operation_audits"
    )
    op.drop_index(
        "ix_admin_operation_audits_correlation_id",
        table_name="admin_operation_audits",
    )
    op.drop_index(
        "ix_admin_operation_audits_operation_created_at",
        table_name="admin_operation_audits",
    )
    for column in ("device_id", "session_id", "actor_id"):
        op.drop_index(
            f"ix_admin_operation_audits_{column}",
            table_name="admin_operation_audits",
        )
    op.drop_table("admin_operation_audits")
