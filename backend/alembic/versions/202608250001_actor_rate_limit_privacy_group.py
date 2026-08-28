"""Allow privacy requests in the shared actor rate-limit store.

Revision ID: 202608250001
Revises: 202608150002
"""

from __future__ import annotations

from alembic import op


revision = "202608250001"
down_revision = "202608150002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.drop_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        type_="check",
    )
    op.create_check_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        "rate_group IN ("
        "'report', 'navigation', 'detect', 'export', 'admin_read', 'privacy'"
        ")",
    )


def downgrade() -> None:
    op.drop_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        type_="check",
    )
    op.create_check_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        "rate_group IN ("
        "'report', 'navigation', 'detect', 'export', 'admin_read'"
        ")",
    )
