"""Include prior report-deletion copy counts in account deletion inventory.

Only subject, generation and count are readable; no request identity or copy details.
"""
from alembic import op

revision = "202610070001"
down_revision = "202609300009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("GRANT SELECT (privacy_subject_hmac, account_generation, external_copy_count) "
               "ON TABLE public.report_deletion_tombstones TO walksafe_account_deletion_worker")


def downgrade() -> None:
    op.execute("REVOKE SELECT (privacy_subject_hmac, account_generation, external_copy_count) "
               "ON TABLE public.report_deletion_tombstones FROM walksafe_account_deletion_worker")
