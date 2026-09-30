"""record device deletion evidence against the server clock

S1617-53: the device completion time is kept as an observation
(device_observed_completed_at + device_clock_anomaly). terminal_at of new
DEVICE_EVIDENCE events is the server receipt time, so a wrong device clock no
longer rejects evidence of a deletion that already happened. Legacy rows keep
both new columns NULL.

Revision ID: 202609300001
Revises: 202609290002
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202609300001"
down_revision = "202609290002"
branch_labels = None
depends_on = None

_CONSTRAINT = "ck_account_deletion_event_device_observed_time"


def upgrade() -> None:
    op.add_column(
        "account_deletion_events",
        sa.Column(
            "device_observed_completed_at",
            sa.DateTime(timezone=True),
            nullable=True,
        ),
    )
    op.add_column(
        "account_deletion_events",
        sa.Column("device_clock_anomaly", sa.Boolean(), nullable=True),
    )
    op.create_check_constraint(
        _CONSTRAINT,
        "account_deletion_events",
        "(device_observed_completed_at IS NULL AND device_clock_anomaly IS NULL) OR "
        "(event_type = 'DEVICE_EVIDENCE' "
        "AND device_observed_completed_at IS NOT NULL "
        "AND device_clock_anomaly IS NOT NULL "
        "AND terminal_at = recorded_at)",
    )


def downgrade() -> None:
    # Downgrade drops only the observation; terminal_at (server time) and the
    # append-only evidence rows stay intact.
    op.drop_constraint(_CONSTRAINT, "account_deletion_events", type_="check")
    op.drop_column("account_deletion_events", "device_clock_anomaly")
    op.drop_column("account_deletion_events", "device_observed_completed_at")
