"""bind successful account creation to an exact enrollment replay

Revision ID: 202609210001
Revises: 202609010003
Create Date: 2026-09-21
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202609210001"
down_revision = "202609010003"
branch_labels = None
depends_on = None


_BINDING_CHECK = (
    "(created_account_id IS NULL AND creation_binding_hmac IS NULL "
    "AND creation_binding_key_version IS NULL) OR "
    "(created_account_id IS NOT NULL AND creation_binding_hmac IS NOT NULL "
    "AND creation_binding_key_version IS NOT NULL AND state = 'CONSUMED' "
    "AND creation_binding_hmac ~ '^[0-9a-f]{64}$' "
    "AND creation_binding_key_version > 0)"
)


def upgrade() -> None:
    op.add_column(
        "account_enrollments",
        sa.Column("created_account_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "account_enrollments",
        sa.Column("creation_binding_hmac", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "account_enrollments",
        sa.Column("creation_binding_key_version", sa.BigInteger(), nullable=True),
    )
    op.create_check_constraint(
        "ck_account_enrollments_creation_binding",
        "account_enrollments",
        _BINDING_CHECK,
    )
    op.create_unique_constraint(
        "uq_account_enrollments_created_account",
        "account_enrollments",
        ["created_account_id"],
    )


def downgrade() -> None:
    op.execute("LOCK TABLE public.account_enrollments IN ACCESS EXCLUSIVE MODE")
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1 FROM public.account_enrollments
             WHERE created_account_id IS NOT NULL
                OR creation_binding_hmac IS NOT NULL
                OR creation_binding_key_version IS NOT NULL
          ) THEN
            RAISE EXCEPTION 'cannot discard account creation replay bindings'
              USING ERRCODE = '55000';
          END IF;
        END
        $$
        """
    )
    op.drop_constraint(
        "uq_account_enrollments_created_account",
        "account_enrollments",
        type_="unique",
    )
    op.drop_constraint(
        "ck_account_enrollments_creation_binding",
        "account_enrollments",
        type_="check",
    )
    op.drop_column("account_enrollments", "creation_binding_key_version")
    op.drop_column("account_enrollments", "creation_binding_hmac")
    op.drop_column("account_enrollments", "created_account_id")
