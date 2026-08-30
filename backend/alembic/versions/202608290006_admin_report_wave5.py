"""add versioned admin status, delivery packages, and package-bound delivery

Revision ID: 202608290006
Revises: 202608290005
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290006"
down_revision = "202608290005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "reports",
        sa.Column(
            "status_version",
            sa.BigInteger(),
            server_default=sa.text("1"),
            nullable=False,
        ),
    )
    op.create_check_constraint(
        "ck_reports_status_version",
        "reports",
        "status_version >= 1",
    )
    for name, column_type in (
        ("previous_version", sa.BigInteger()),
        ("next_version", sa.BigInteger()),
        ("session_id", postgresql.UUID(as_uuid=True)),
        ("device_id", sa.String(length=128)),
        ("correlation_id", postgresql.UUID(as_uuid=True)),
    ):
        op.add_column(
            "report_status_audits",
            sa.Column(name, column_type, nullable=True),
        )
    op.create_check_constraint(
        "ck_report_status_audits_versions",
        "report_status_audits",
        "(previous_version IS NULL AND next_version IS NULL) OR "
        "(previous_version >= 1 AND next_version = previous_version + 1)",
    )

    op.create_table(
        "report_delivery_packages",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("review_decision_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("export_audit_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("schema_version", sa.String(length=64), nullable=False),
        sa.Column("package_version", sa.SmallInteger(), nullable=False),
        sa.Column("csv_sha256", sa.String(length=64), nullable=False),
        sa.Column("manifest_sha256", sa.String(length=64), nullable=False),
        sa.Column("package_sha256", sa.String(length=64), nullable=False),
        sa.Column("csv_byte_count", sa.BigInteger(), nullable=False),
        sa.Column("manifest_byte_count", sa.BigInteger(), nullable=False),
        sa.Column("package_byte_count", sa.BigInteger(), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "generated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint("revision >= 1", name="ck_report_delivery_packages_revision"),
        sa.CheckConstraint("package_version = 1", name="ck_report_delivery_packages_package_version"),
        sa.CheckConstraint(
            "schema_version = 'walksafe.admin-report-delivery-package.v1'",
            name="ck_report_delivery_packages_schema_version",
        ),
        sa.CheckConstraint(
            "csv_sha256 ~ '^[0-9a-f]{64}$' AND "
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "package_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_delivery_packages_sha256",
        ),
        sa.CheckConstraint(
            "csv_byte_count > 0 AND manifest_byte_count > 0 "
            "AND package_byte_count > 0",
            name="ck_report_delivery_packages_byte_counts",
        ),
        sa.ForeignKeyConstraint(
            ["export_audit_id"],
            ["report_export_audits.audit_id"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "report_id", "revision",
            name="uq_report_delivery_packages_report_revision",
        ),
        sa.UniqueConstraint(
            "id", "revision",
            name="uq_report_delivery_packages_id_revision",
        ),
        sa.UniqueConstraint(
            "export_audit_id",
            name="uq_report_delivery_packages_export_audit_id",
        ),
    )
    op.create_index(
        "ix_report_delivery_packages_report_generated_at",
        "report_delivery_packages",
        ["report_id", "generated_at"],
    )
    op.create_index(
        "ix_report_delivery_packages_correlation_id",
        "report_delivery_packages",
        ["correlation_id"],
    )
    op.execute(
        "CREATE TRIGGER report_delivery_packages_append_only "
        "BEFORE UPDATE OR DELETE OR TRUNCATE ON report_delivery_packages "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.report_delivery_packages "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.report_delivery_packages "
        "TO walksafe_backend_runtime"
    )

    op.add_column(
        "report_institution_delivery_events",
        sa.Column("package_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "report_institution_delivery_events",
        sa.Column("package_revision", sa.BigInteger(), nullable=True),
    )
    op.create_check_constraint(
        "ck_report_institution_delivery_package_binding",
        "report_institution_delivery_events",
        "(package_id IS NULL AND package_revision IS NULL) OR "
        "(package_id IS NOT NULL AND package_revision >= 1)",
    )
    op.create_foreign_key(
        "fk_report_institution_delivery_package_revision",
        "report_institution_delivery_events",
        "report_delivery_packages",
        ["package_id", "package_revision"],
        ["id", "revision"],
        ondelete="RESTRICT",
    )
    op.create_index(
        "ix_report_institution_delivery_package_id",
        "report_institution_delivery_events",
        ["package_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_report_institution_delivery_package_id",
        table_name="report_institution_delivery_events",
    )
    op.drop_constraint(
        "fk_report_institution_delivery_package_revision",
        "report_institution_delivery_events",
        type_="foreignkey",
    )
    op.drop_constraint(
        "ck_report_institution_delivery_package_binding",
        "report_institution_delivery_events",
        type_="check",
    )
    op.drop_column("report_institution_delivery_events", "package_revision")
    op.drop_column("report_institution_delivery_events", "package_id")

    op.execute(
        "DROP TRIGGER IF EXISTS report_delivery_packages_append_only "
        "ON report_delivery_packages"
    )
    op.drop_index(
        "ix_report_delivery_packages_correlation_id",
        table_name="report_delivery_packages",
    )
    op.drop_index(
        "ix_report_delivery_packages_report_generated_at",
        table_name="report_delivery_packages",
    )
    op.drop_table("report_delivery_packages")
    op.drop_constraint(
        "ck_report_status_audits_versions",
        "report_status_audits",
        type_="check",
    )
    for name in (
        "correlation_id", "device_id", "session_id", "next_version", "previous_version"
    ):
        op.drop_column("report_status_audits", name)
    op.drop_constraint("ck_reports_status_version", "reports", type_="check")
    op.drop_column("reports", "status_version")
