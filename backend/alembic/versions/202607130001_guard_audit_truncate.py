"""reject TRUNCATE on append-only audit tables

Revision ID: 202607130001
Revises: 202607110004
Create Date: 2026-07-13
"""

from __future__ import annotations

from alembic import op


revision = "202607130001"
down_revision = "202607110004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    for table_name in ("report_export_audits", "report_status_audits"):
        op.execute(
            f"CREATE TRIGGER {table_name}_reject_truncate "
            f"BEFORE TRUNCATE ON {table_name} "
            "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
        )


def downgrade() -> None:
    for table_name in ("report_status_audits", "report_export_audits"):
        op.execute(f"DROP TRIGGER IF EXISTS {table_name}_reject_truncate ON {table_name}")
