"""bind original evidence grants to one reviewed approval

Revision ID: 202608300002
Revises: 202608300001
Create Date: 2026-08-30
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608300002"
down_revision = "202608300001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "report_original_access_grants",
        sa.Column("content_revision", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "report_original_access_grants",
        sa.Column("location_disclosed_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "report_original_access_grants",
        sa.Column("access_granted_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "report_original_access_grants",
        sa.Column("review_decision_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "report_original_access_grants",
        sa.Column("review_bound_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_check_constraint(
        "ck_report_original_access_grants_location_disclosure",
        "report_original_access_grants",
        "(content_revision IS NULL AND location_disclosed_at IS NULL) OR "
        "(content_revision >= 0 AND location_disclosed_at IS NOT NULL)",
    )
    op.create_check_constraint(
        "ck_report_original_access_grants_access_granted",
        "report_original_access_grants",
        "access_granted_at IS NULL OR "
        "(consumed_at IS NOT NULL AND content_revision IS NOT NULL)",
    )
    op.create_check_constraint(
        "ck_report_original_access_grants_review_binding",
        "report_original_access_grants",
        "(review_decision_id IS NULL AND review_bound_at IS NULL) OR "
        "(review_decision_id IS NOT NULL AND review_bound_at IS NOT NULL "
        "AND access_granted_at IS NOT NULL "
        "AND location_disclosed_at IS NOT NULL "
        "AND content_revision IS NOT NULL)",
    )
    op.create_index(
        "uq_report_original_access_grants_review_decision_id",
        "report_original_access_grants",
        ["review_decision_id"],
        unique=True,
    )

    op.add_column(
        "report_review_decisions",
        sa.Column("evidence_grant_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.create_index(
        "uq_report_review_decisions_evidence_grant_id",
        "report_review_decisions",
        ["evidence_grant_id"],
        unique=True,
    )
    op.execute(
        "ALTER TABLE report_review_decisions ADD CONSTRAINT "
        "ck_report_review_decisions_evidence_grant CHECK ("
        "(decision = 'APPROVED' AND evidence_grant_id IS NOT NULL) OR "
        "(decision <> 'APPROVED' AND evidence_grant_id IS NULL)) NOT VALID"
    )

    op.drop_constraint(
        "ck_report_original_access_audits_action",
        "report_original_access_audits",
        type_="check",
    )
    op.create_check_constraint(
        "ck_report_original_access_audits_action",
        "report_original_access_audits",
        "action IN ('GRANT_ISSUED', 'GRANT_DENIED', 'GRANT_ERROR', "
        "'LOCATION_DISCLOSED', 'ACCESS_GRANTED', 'ACCESS_DENIED', 'ACCESS_ERROR')",
    )


def downgrade() -> None:
    op.execute("SET LOCAL search_path = pg_catalog, public")
    op.execute(
        """
        DO $guard$
        BEGIN
          LOCK TABLE report_original_access_grants,
                     report_original_access_audits,
                     report_review_decisions
            IN ACCESS EXCLUSIVE MODE;
          IF EXISTS (
               SELECT 1 FROM report_original_access_grants AS evidence_grant
                WHERE evidence_grant.content_revision IS NOT NULL
                   OR evidence_grant.location_disclosed_at IS NOT NULL
                   OR evidence_grant.access_granted_at IS NOT NULL
                   OR evidence_grant.review_decision_id IS NOT NULL
                   OR evidence_grant.review_bound_at IS NOT NULL
             )
            OR EXISTS (
               SELECT 1 FROM report_original_access_audits AS audit
                WHERE audit.action = 'LOCATION_DISCLOSED'
             )
            OR EXISTS (
               SELECT 1 FROM report_review_decisions AS decision
                WHERE decision.evidence_grant_id IS NOT NULL
             )
          THEN
            RAISE EXCEPTION
              'cannot downgrade original evidence v2 while evidence exists'
              USING ERRCODE = '55000';
          END IF;
        END
        $guard$
        """
    )
    op.drop_constraint(
        "ck_report_original_access_audits_action",
        "report_original_access_audits",
        type_="check",
    )
    op.create_check_constraint(
        "ck_report_original_access_audits_action",
        "report_original_access_audits",
        "action IN ('GRANT_ISSUED', 'GRANT_DENIED', 'GRANT_ERROR', "
        "'ACCESS_GRANTED', 'ACCESS_DENIED', 'ACCESS_ERROR')",
    )

    op.drop_constraint(
        "ck_report_review_decisions_evidence_grant",
        "report_review_decisions",
        type_="check",
    )
    op.drop_index(
        "uq_report_review_decisions_evidence_grant_id",
        table_name="report_review_decisions",
    )
    op.drop_column("report_review_decisions", "evidence_grant_id")

    op.drop_index(
        "uq_report_original_access_grants_review_decision_id",
        table_name="report_original_access_grants",
    )
    op.drop_constraint(
        "ck_report_original_access_grants_review_binding",
        "report_original_access_grants",
        type_="check",
    )
    op.drop_constraint(
        "ck_report_original_access_grants_access_granted",
        "report_original_access_grants",
        type_="check",
    )
    op.drop_constraint(
        "ck_report_original_access_grants_location_disclosure",
        "report_original_access_grants",
        type_="check",
    )
    op.drop_column("report_original_access_grants", "review_bound_at")
    op.drop_column("report_original_access_grants", "review_decision_id")
    op.drop_column("report_original_access_grants", "access_granted_at")
    op.drop_column("report_original_access_grants", "location_disclosed_at")
    op.drop_column("report_original_access_grants", "content_revision")
