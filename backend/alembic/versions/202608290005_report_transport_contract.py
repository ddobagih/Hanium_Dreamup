"""add fixed-id report transport receipt contract

Revision ID: 202608290005
Revises: 202608290004
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290005"
down_revision = "202608290004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "reports",
        sa.Column("client_payload_sha256", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "reports",
        sa.Column("client_payload_bytes", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "reports",
        sa.Column(
            "persistence_marker",
            postgresql.UUID(as_uuid=True),
            nullable=True,
        ),
    )
    op.create_check_constraint(
        "ck_reports_transport_contract_all_or_none",
        "reports",
        "(client_payload_sha256 IS NULL AND client_payload_bytes IS NULL "
        "AND persistence_marker IS NULL) OR "
        "(client_payload_sha256 IS NOT NULL "
        "AND client_payload_bytes IS NOT NULL "
        "AND persistence_marker IS NOT NULL "
        "AND client_payload_sha256 ~ '^[0-9a-f]{64}$' "
        "AND client_payload_bytes > 0)",
    )
    op.create_unique_constraint(
        "uq_reports_persistence_marker",
        "reports",
        ["persistence_marker"],
    )


def downgrade() -> None:
    op.drop_constraint(
        "uq_reports_persistence_marker",
        "reports",
        type_="unique",
    )
    op.drop_constraint(
        "ck_reports_transport_contract_all_or_none",
        "reports",
        type_="check",
    )
    op.drop_column("reports", "persistence_marker")
    op.drop_column("reports", "client_payload_bytes")
    op.drop_column("reports", "client_payload_sha256")
