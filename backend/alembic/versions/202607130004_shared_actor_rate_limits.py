"""add shared actor rate-limit events

Revision ID: 202607130004
Revises: 202607130003
Create Date: 2026-07-13
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202607130004"
down_revision = "202607130003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "actor_rate_limit_events",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("actor_digest", sa.String(length=64), nullable=False),
        sa.Column("rate_group", sa.String(length=32), nullable=False),
        sa.Column(
            "observed_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "rate_group IN ('report', 'navigation', 'detect', 'export', 'admin_read')",
            name="ck_actor_rate_limit_events_group",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_actor_rate_limit_events_actor_group_time",
        "actor_rate_limit_events",
        ["actor_digest", "rate_group", "observed_at"],
    )
    op.create_index(
        "ix_actor_rate_limit_events_observed_at",
        "actor_rate_limit_events",
        ["observed_at"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_actor_rate_limit_events_observed_at",
        table_name="actor_rate_limit_events",
    )
    op.drop_index(
        "ix_actor_rate_limit_events_actor_group_time",
        table_name="actor_rate_limit_events",
    )
    op.drop_table("actor_rate_limit_events")
