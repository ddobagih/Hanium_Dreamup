"""make report audit tables append-only

Revision ID: 202607110004
Revises: 202607110003
Create Date: 2026-07-11
"""

from __future__ import annotations

from alembic import op


revision = "202607110004"
down_revision = "202607110003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        "CREATE FUNCTION walksafe_reject_audit_mutation() RETURNS trigger AS $$ "
        "BEGIN RAISE EXCEPTION 'WalkSafe audit rows are append-only'; END; "
        "$$ LANGUAGE plpgsql"
    )
    for table_name in ("report_export_audits", "report_status_audits"):
        op.execute(
            f"CREATE TRIGGER {table_name}_append_only "
            f"BEFORE UPDATE OR DELETE ON {table_name} "
            "FOR EACH ROW EXECUTE FUNCTION walksafe_reject_audit_mutation()"
        )


def downgrade() -> None:
    for table_name in ("report_status_audits", "report_export_audits"):
        op.execute(f"DROP TRIGGER IF EXISTS {table_name}_append_only ON {table_name}")
    op.execute("DROP FUNCTION walksafe_reject_audit_mutation()")
