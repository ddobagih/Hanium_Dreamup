"""add structured report content corrections and correction-aware packages

Revision ID: 202608290012
Revises: 202608290011
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290012"
down_revision = "202608290011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "reports",
        sa.Column(
            "content_revision",
            sa.BigInteger(),
            server_default=sa.text("0"),
            nullable=False,
        ),
    )
    op.create_check_constraint(
        "ck_reports_content_revision", "reports", "content_revision >= 0"
    )
    op.create_unique_constraint(
        "uq_reports_content_subject_binding",
        "reports",
        ["id", "privacy_subject_hmac", "account_generation"],
    )

    op.create_table(
        "report_content_revisions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("expected_revision", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("user_description", sa.String(length=500), nullable=True),
        sa.Column("category_hint", sa.String(length=32), nullable=True),
        sa.Column("intent_sha256", sa.String(length=64), nullable=False),
        sa.Column("content_sha256", sa.String(length=64), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "expected_revision >= 0 AND revision = expected_revision + 1",
            name="ck_report_content_revisions_sequence",
        ),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1",
            name="ck_report_content_revisions_subject",
        ),
        sa.CheckConstraint(
            "user_description IS NULL OR length(user_description) BETWEEN 1 AND 500",
            name="ck_report_content_revisions_description",
        ),
        sa.CheckConstraint(
            "category_hint IS NULL OR category_hint IN ("
            "'SIDEWALK_OBSTRUCTION', 'ROAD_DAMAGE', "
            "'ACCESSIBILITY_BARRIER', 'OTHER')",
            name="ck_report_content_revisions_category",
        ),
        sa.CheckConstraint(
            "intent_sha256 ~ '^[0-9a-f]{64}$' AND "
            "content_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_content_revisions_sha256",
        ),
        sa.ForeignKeyConstraint(
            ["report_id", "privacy_subject_hmac", "account_generation"],
            ["reports.id", "reports.privacy_subject_hmac", "reports.account_generation"],
            name="fk_report_content_revisions_subject",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "report_id",
            "revision",
            name="uq_report_content_revisions_report_revision",
        ),
        sa.UniqueConstraint(
            "report_id",
            "idempotency_key",
            name="uq_report_content_revisions_idempotency",
        ),
    )
    op.create_index(
        "ix_report_content_revisions_subject_created_at",
        "report_content_revisions",
        ["privacy_subject_hmac", "account_generation", "created_at"],
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_content_revision_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_OP = 'DELETE' AND pg_has_role(
                current_user,
                'walksafe_account_deletion_worker',
                'USAGE'
            ) THEN
                RETURN NULL;
            END IF;
            RAISE EXCEPTION 'report content revisions are append-only';
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_content_revision_guard() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER report_content_revisions_append_only "
        "BEFORE UPDATE OR DELETE OR TRUNCATE ON report_content_revisions "
        "FOR EACH STATEMENT EXECUTE FUNCTION "
        "public.walksafe_report_content_revision_guard()"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.report_content_revisions "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.report_content_revisions "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_content_projection_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_TABLE_NAME = 'reports' THEN
                IF NEW.content_revision = OLD.content_revision THEN
                    RETURN NEW;
                END IF;
                IF NEW.content_revision <> OLD.content_revision + 1 OR NOT EXISTS (
                    SELECT 1 FROM public.report_content_revisions AS revision
                    WHERE revision.report_id = NEW.id
                      AND revision.revision = NEW.content_revision
                      AND revision.expected_revision = OLD.content_revision
                      AND revision.privacy_subject_hmac = NEW.privacy_subject_hmac
                      AND revision.account_generation = NEW.account_generation
                ) THEN
                    RAISE EXCEPTION 'report content projection is missing its revision';
                END IF;
            ELSIF NOT EXISTS (
                SELECT 1 FROM public.reports AS report
                WHERE report.id = NEW.report_id
                  AND report.content_revision = NEW.revision
                  AND report.privacy_subject_hmac = NEW.privacy_subject_hmac
                  AND report.account_generation = NEW.account_generation
            ) THEN
                RAISE EXCEPTION 'report content revision is not current';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_content_projection_guard() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER report_content_projection_revision_guard "
        "AFTER UPDATE ON public.reports "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_report_content_projection_guard()"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER report_content_revision_projection_guard "
        "AFTER INSERT ON public.report_content_revisions "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_report_content_projection_guard()"
    )

    op.add_column(
        "report_review_decisions",
        sa.Column(
            "content_revision",
            sa.BigInteger(),
            server_default=sa.text("0"),
            nullable=False,
        ),
    )
    op.create_check_constraint(
        "ck_report_review_decisions_content_revision",
        "report_review_decisions",
        "content_revision >= 0",
    )

    op.drop_constraint(
        "ck_report_delivery_packages_package_version",
        "report_delivery_packages",
        type_="check",
    )
    op.drop_constraint(
        "ck_report_delivery_packages_schema_version",
        "report_delivery_packages",
        type_="check",
    )
    op.add_column(
        "report_delivery_packages",
        sa.Column(
            "content_revision",
            sa.BigInteger(),
            server_default=sa.text("0"),
            nullable=False,
        ),
    )
    op.add_column(
        "report_delivery_packages",
        sa.Column("content_sha256", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "report_delivery_packages",
        sa.Column(
            "supersedes_package_id",
            postgresql.UUID(as_uuid=True),
            nullable=True,
        ),
    )
    op.create_check_constraint(
        "ck_report_delivery_packages_version_content",
        "report_delivery_packages",
        "(package_version = 1 AND "
        "schema_version = 'walksafe.admin-report-delivery-package.v1' AND "
        "content_revision = 0 AND content_sha256 IS NULL AND "
        "supersedes_package_id IS NULL) OR "
        "(package_version = 2 AND "
        "schema_version = 'walksafe.admin-report-delivery-package.v2' AND "
        "content_revision >= 0 AND content_sha256 ~ '^[0-9a-f]{64}$')",
    )
    op.create_check_constraint(
        "ck_report_delivery_packages_supersedes_self",
        "report_delivery_packages",
        "supersedes_package_id IS NULL OR supersedes_package_id <> id",
    )
    op.create_foreign_key(
        "fk_report_delivery_packages_supersedes",
        "report_delivery_packages",
        "report_delivery_packages",
        ["supersedes_package_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index(
        "ix_report_delivery_packages_supersedes_package_id",
        "report_delivery_packages",
        ["supersedes_package_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_report_delivery_packages_supersedes_package_id",
        table_name="report_delivery_packages",
    )
    op.drop_constraint(
        "fk_report_delivery_packages_supersedes",
        "report_delivery_packages",
        type_="foreignkey",
    )
    op.drop_constraint(
        "ck_report_delivery_packages_supersedes_self",
        "report_delivery_packages",
        type_="check",
    )
    op.drop_constraint(
        "ck_report_delivery_packages_version_content",
        "report_delivery_packages",
        type_="check",
    )
    op.drop_column("report_delivery_packages", "supersedes_package_id")
    op.drop_column("report_delivery_packages", "content_sha256")
    op.drop_column("report_delivery_packages", "content_revision")
    op.create_check_constraint(
        "ck_report_delivery_packages_package_version",
        "report_delivery_packages",
        "package_version = 1",
    )
    op.create_check_constraint(
        "ck_report_delivery_packages_schema_version",
        "report_delivery_packages",
        "schema_version = 'walksafe.admin-report-delivery-package.v1'",
    )
    op.drop_constraint(
        "ck_report_review_decisions_content_revision",
        "report_review_decisions",
        type_="check",
    )
    op.drop_column("report_review_decisions", "content_revision")
    op.execute(
        "DROP TRIGGER IF EXISTS report_content_revision_projection_guard "
        "ON public.report_content_revisions"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS report_content_projection_revision_guard "
        "ON public.reports"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS public.walksafe_report_content_projection_guard()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS report_content_revisions_append_only "
        "ON report_content_revisions"
    )
    op.execute("DROP FUNCTION IF EXISTS public.walksafe_report_content_revision_guard()")
    op.drop_index(
        "ix_report_content_revisions_subject_created_at",
        table_name="report_content_revisions",
    )
    op.drop_table("report_content_revisions")
    op.drop_constraint(
        "uq_reports_content_subject_binding", "reports", type_="unique"
    )
    op.drop_constraint("ck_reports_content_revision", "reports", type_="check")
    op.drop_column("reports", "content_revision")
