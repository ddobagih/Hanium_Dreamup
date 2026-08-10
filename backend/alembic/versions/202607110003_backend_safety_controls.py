"""add durable export audit and strengthen report location constraints

Revision ID: 202607110003
Revises: 202607110002
Create Date: 2026-07-11
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202607110003"
down_revision = "202607110002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "report_export_audits",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("audit_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("actor_id", sa.String(length=64), nullable=False),
        sa.Column("export_format", sa.String(length=16), nullable=False),
        sa.Column("profile", sa.String(length=16), nullable=False),
        sa.Column("aggregate", sa.String(length=16), nullable=True),
        sa.Column("row_count", sa.Integer(), nullable=False),
        sa.Column("location_precision", sa.String(length=32), nullable=False),
        sa.Column("filters", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint(
            "export_format IN ('csv', 'json', 'geojson')",
            name="ck_report_export_audits_format",
        ),
        sa.CheckConstraint(
            "profile IN ('internal', 'minimum', 'agency')",
            name="ck_report_export_audits_profile",
        ),
        sa.CheckConstraint("row_count >= 0", name="ck_report_export_audits_row_count"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_report_export_audits_audit_id", "report_export_audits", ["audit_id"])
    op.create_index("ix_report_export_audits_actor_id", "report_export_audits", ["actor_id"])
    op.create_index("ix_report_export_audits_created_at", "report_export_audits", ["created_at"])
    op.create_table(
        "report_status_audits",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("previous_status", sa.String(length=16), nullable=False),
        sa.Column("next_status", sa.String(length=16), nullable=False),
        sa.Column("actor_id", sa.String(length=64), nullable=False),
        sa.Column("note", sa.String(length=500), nullable=True),
        sa.Column("resolution_reason", sa.String(length=500), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint(
            "previous_status IN ('new', 'reviewed', 'resolved')",
            name="ck_report_status_audits_previous",
        ),
        sa.CheckConstraint(
            "next_status IN ('new', 'reviewed', 'resolved')",
            name="ck_report_status_audits_next",
        ),
        sa.CheckConstraint("previous_status <> next_status", name="ck_report_status_audits_changed"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_report_status_audits_report_id", "report_status_audits", ["report_id"])
    op.create_index("ix_report_status_audits_actor_id", "report_status_audits", ["actor_id"])
    op.create_index("ix_report_status_audits_created_at", "report_status_audits", ["created_at"])

    op.execute("ALTER TABLE reports DROP CONSTRAINT IF EXISTS ck_reports_accuracy")
    op.execute("ALTER TABLE reports DROP CONSTRAINT IF EXISTS ck_reports_heading")
    op.execute("ALTER TABLE reports DROP CONSTRAINT IF EXISTS ck_reports_location_consistency")
    op.execute(
        "ALTER TABLE reports ADD CONSTRAINT ck_reports_accuracy CHECK ("
        "accuracy_m IS NULL OR (accuracy_m >= 0 AND accuracy_m < 'Infinity'::double precision)"
        ") NOT VALID"
    )
    op.execute(
        "ALTER TABLE reports ADD CONSTRAINT ck_reports_heading CHECK ("
        "heading IS NULL OR (heading >= 0 AND heading < 360 AND heading < 'Infinity'::double precision)"
        ") NOT VALID"
    )
    op.execute(
        "ALTER TABLE reports ADD CONSTRAINT ck_reports_location_consistency CHECK ("
        "(latitude IS NULL AND longitude IS NULL AND location IS NULL) OR "
        "(latitude IS NOT NULL AND longitude IS NOT NULL AND location IS NOT NULL "
        "AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 "
        "AND ST_SRID(location) = 4326 "
        "AND ST_X(location) = longitude AND ST_Y(location) = latitude)"
        ") NOT VALID"
    )
    for name in ("ck_reports_accuracy", "ck_reports_heading", "ck_reports_location_consistency"):
        op.execute(f"ALTER TABLE reports VALIDATE CONSTRAINT {name}")


def downgrade() -> None:
    for name in ("ck_reports_accuracy", "ck_reports_heading", "ck_reports_location_consistency"):
        op.execute(f"ALTER TABLE reports DROP CONSTRAINT IF EXISTS {name}")
    op.execute(
        "ALTER TABLE reports ADD CONSTRAINT ck_reports_accuracy "
        "CHECK (accuracy_m IS NULL OR accuracy_m >= 0)"
    )
    op.execute(
        "ALTER TABLE reports ADD CONSTRAINT ck_reports_heading "
        "CHECK (heading IS NULL OR (heading >= 0 AND heading < 360))"
    )
    op.execute(
        "ALTER TABLE reports ADD CONSTRAINT ck_reports_location_consistency CHECK ("
        "(latitude IS NULL AND longitude IS NULL AND location IS NULL) OR "
        "(latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 "
        "AND location IS NOT NULL AND ST_SRID(location) = 4326 "
        "AND ST_X(location) = longitude AND ST_Y(location) = latitude)"
        ")"
    )
    op.drop_index("ix_report_status_audits_created_at", table_name="report_status_audits")
    op.drop_index("ix_report_status_audits_actor_id", table_name="report_status_audits")
    op.drop_index("ix_report_status_audits_report_id", table_name="report_status_audits")
    op.drop_table("report_status_audits")
    op.drop_index("ix_report_export_audits_created_at", table_name="report_export_audits")
    op.drop_index("ix_report_export_audits_actor_id", table_name="report_export_audits")
    op.drop_index("ix_report_export_audits_audit_id", table_name="report_export_audits")
    op.drop_table("report_export_audits")
