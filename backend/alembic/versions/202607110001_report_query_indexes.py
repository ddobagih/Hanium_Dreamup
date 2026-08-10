"""add report query indexes

Revision ID: 202607110001
Revises: 202605120001
Create Date: 2026-07-11
"""

from __future__ import annotations

from alembic import op


revision = "202607110001"
down_revision = "202605120001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        "CREATE INDEX ix_reports_location_geography "
        "ON reports USING GIST ((location::geography)) WHERE location IS NOT NULL"
    )
    op.execute(
        "CREATE INDEX ix_reports_class_captured_at "
        "ON reports (class_name, captured_at DESC)"
    )
    op.execute(
        "CREATE INDEX ix_reports_status_class_created_at "
        "ON reports (status, class_name, created_at DESC)"
    )
    op.execute(
        "CREATE INDEX ix_reports_metadata_path_ops "
        "ON reports USING GIN (metadata jsonb_path_ops)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_reports_metadata_path_ops")
    op.execute("DROP INDEX IF EXISTS ix_reports_status_class_created_at")
    op.execute("DROP INDEX IF EXISTS ix_reports_class_captured_at")
    op.execute("DROP INDEX IF EXISTS ix_reports_location_geography")
