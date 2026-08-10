"""Allow a distinct durable audit type for duplicate-check reads."""

from __future__ import annotations

from alembic import op


revision = "202607160001"
down_revision = "202607130004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.drop_constraint(
        "ck_report_read_audits_resource_type",
        "report_read_audits",
        type_="check",
    )
    op.create_check_constraint(
        "ck_report_read_audits_resource_type",
        "report_read_audits",
        "resource_type IN ('report_list', 'report_detail', 'report_image', 'report_duplicate_check')",
    )


def downgrade() -> None:
    op.drop_constraint(
        "ck_report_read_audits_resource_type",
        "report_read_audits",
        type_="check",
    )
    op.create_check_constraint(
        "ck_report_read_audits_resource_type",
        "report_read_audits",
        "resource_type IN ('report_list', 'report_detail', 'report_image')",
    )
