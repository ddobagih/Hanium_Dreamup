"""add append-only sensitive report read audits

Revision ID: 202607130003
Revises: 202607130002
Create Date: 2026-07-13
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202607130003"
down_revision = "202607130002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "report_read_audits",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("actor_id", sa.String(length=64), nullable=False),
        sa.Column("purpose", sa.String(length=64), nullable=False),
        sa.Column("resource_type", sa.String(length=32), nullable=False),
        sa.Column("resource_id", sa.String(length=160), nullable=False),
        sa.Column("details", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint(
            "resource_type IN ('report_list', 'report_detail', 'report_image')",
            name="ck_report_read_audits_resource_type",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    for column in ("actor_id", "purpose", "resource_type", "created_at"):
        op.create_index(f"ix_report_read_audits_{column}", "report_read_audits", [column])
    op.execute(
        "CREATE TRIGGER report_read_audits_append_only "
        "BEFORE UPDATE OR DELETE ON report_read_audits "
        "FOR EACH ROW EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE TRIGGER report_read_audits_reject_truncate "
        "BEFORE TRUNCATE ON report_read_audits "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS report_read_audits_reject_truncate ON report_read_audits")
    op.execute("DROP TRIGGER IF EXISTS report_read_audits_append_only ON report_read_audits")
    for column in ("created_at", "resource_type", "purpose", "actor_id"):
        op.drop_index(f"ix_report_read_audits_{column}", table_name="report_read_audits")
    op.drop_table("report_read_audits")
