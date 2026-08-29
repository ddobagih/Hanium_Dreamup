"""add shared account authentication rate-limit groups

Revision ID: 202608290011
Revises: 202608290010
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op


revision = "202608290011"
down_revision = "202608290010"
branch_labels = None
depends_on = None


_PREVIOUS_RATE_GROUPS = (
    "report",
    "navigation",
    "detect",
    "export",
    "admin_read",
    "privacy",
    "raw_collection",
    "account_enrollment_global",
    "account_enrollment_ip",
    "account_enrollment_email",
)
_RATE_GROUPS = _PREVIOUS_RATE_GROUPS + (
    "account_authentication_global",
    "account_authentication_email",
)


def _rate_group_expression(groups: tuple[str, ...]) -> str:
    return "rate_group IN (" + ", ".join(f"'{group}'" for group in groups) + ")"


def _replace_rate_group_constraint(groups: tuple[str, ...]) -> None:
    op.drop_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        type_="check",
    )
    op.create_check_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        _rate_group_expression(groups),
    )


def upgrade() -> None:
    _replace_rate_group_constraint(_RATE_GROUPS)


def downgrade() -> None:
    op.execute(
        "DELETE FROM actor_rate_limit_events WHERE rate_group IN ("
        "'account_authentication_global', 'account_authentication_email')"
    )
    _replace_rate_group_constraint(_PREVIOUS_RATE_GROUPS)
