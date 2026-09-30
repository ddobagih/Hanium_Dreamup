"""let the account deletion worker inventory institution copies

S1617-59 / S1617-55: the account deletion item ``server_copies`` was always
closed as NOT_APPLICABLE. Institution deliveries are recorded (manual
delivery facts, never external I/O) per report in
report_institution_delivery_events and survive the report row. Before the
worker deletes a subject's reports it now counts their delivered copies, so a
request with a delivered copy keeps ``server_copies`` open (EXTERNAL_PENDING)
instead of reaching COMPLETED.

The worker only needs the copy identity: SELECT on (id, report_id,
package_id) as a column grant, never the recipient, reason or evidence
columns, and never table-level SELECT.

Revision ID: 202609300007
Revises: 202609300006
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op


revision = "202609300007"
down_revision = "202609300006"
branch_labels = None
depends_on = None

_WORKER_ROLE = "walksafe_account_deletion_worker"
_TABLE = "public.report_institution_delivery_events"
_COLUMNS = "id, report_id, package_id"


def upgrade() -> None:
    op.execute(f"GRANT SELECT ({_COLUMNS}) ON TABLE {_TABLE} TO {_WORKER_ROLE}")


def downgrade() -> None:
    op.execute(f"REVOKE SELECT ({_COLUMNS}) ON TABLE {_TABLE} FROM {_WORKER_ROLE}")
