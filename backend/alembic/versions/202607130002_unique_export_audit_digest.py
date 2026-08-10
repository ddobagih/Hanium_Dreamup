"""make export audit ids unique and retain canonical row digests

Revision ID: 202607130002
Revises: 202607130001
Create Date: 2026-07-13
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202607130002"
down_revision = "202607130001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("report_export_audits", sa.Column("rows_sha256", sa.String(length=64), nullable=True))
    op.add_column(
        "report_export_audits",
        sa.Column("requested_audit_id", sa.Uuid(), nullable=True),
    )
    # Historical clients could reuse one caller-supplied id for CSV and manifest.
    # Keep the oldest id and give later immutable rows their already-unique row id.
    op.execute("ALTER TABLE report_export_audits DISABLE TRIGGER report_export_audits_append_only")
    op.execute("UPDATE report_export_audits SET requested_audit_id = audit_id")
    op.execute(
        "WITH ranked AS ("
        " SELECT id, row_number() OVER (PARTITION BY audit_id ORDER BY created_at, id) AS position"
        " FROM report_export_audits"
        ") UPDATE report_export_audits AS audit SET audit_id = audit.id"
        " FROM ranked WHERE ranked.id = audit.id AND ranked.position > 1"
    )
    op.execute("ALTER TABLE report_export_audits ENABLE TRIGGER report_export_audits_append_only")
    op.create_unique_constraint(
        "uq_report_export_audits_audit_id",
        "report_export_audits",
        ["audit_id"],
    )
    op.create_check_constraint(
        "ck_report_export_audits_rows_sha256",
        "report_export_audits",
        "rows_sha256 IS NULL OR rows_sha256 ~ '^[0-9a-f]{64}$'",
    )
    op.create_index(
        "ix_report_export_audits_requested_audit_id",
        "report_export_audits",
        ["requested_audit_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_report_export_audits_requested_audit_id",
        table_name="report_export_audits",
    )
    op.drop_constraint(
        "ck_report_export_audits_rows_sha256",
        "report_export_audits",
        type_="check",
    )
    op.drop_constraint(
        "uq_report_export_audits_audit_id",
        "report_export_audits",
        type_="unique",
    )
    op.drop_column("report_export_audits", "rows_sha256")
    op.drop_column("report_export_audits", "requested_audit_id")
