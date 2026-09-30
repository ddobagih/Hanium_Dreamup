"""S1617-56: bounded purge of expired account deletion completion receipts.

Receipts are deletion evidence kept for three years after processing
(``_three_year_expiry``). Only the existing SECURITY DEFINER database function
``walksafe_purge_expired_account_deletion_receipts`` deletes them, and only
rows whose ``expires_at`` has passed. Tombstones, requests, items, events and
consent events are not touched, so an old request of a deleted account
generation stays blocked after its receipt is gone.
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import text
from sqlalchemy.orm import Session


RECEIPT_PURGE_ROLE = "walksafe_receipt_purger"
MAX_BATCH_LIMIT = 10_000
MAX_BATCHES = 1_000


class ReceiptPurgeError(RuntimeError):
    """The purge cannot run safely with this connection or these limits."""


@dataclass(frozen=True)
class ReceiptPurgeResult:
    deleted: int
    batches: int
    exhausted: bool

    def as_dict(self) -> dict[str, object]:
        return {
            "schema_version": "walksafe.account-deletion-receipt-purge.v1",
            "deleted": self.deleted,
            "batches": self.batches,
            "exhausted": self.exhausted,
        }


def _validate_limits(batch_limit: int, max_batches: int) -> None:
    if type(batch_limit) is not int or not 1 <= batch_limit <= MAX_BATCH_LIMIT:
        raise ReceiptPurgeError(f"batch_limit must be between 1 and {MAX_BATCH_LIMIT}")
    if type(max_batches) is not int or not 1 <= max_batches <= MAX_BATCHES:
        raise ReceiptPurgeError(f"max_batches must be between 1 and {MAX_BATCHES}")


def assert_receipt_purge_database_role(db: Session) -> None:
    """Refuse superusers, the runtime role and non-purger connections."""

    role = db.execute(
        text(
            "SELECT role.rolsuper, "
            "current_user = session_user AS direct_login, "
            "pg_has_role(current_user, :purger, 'MEMBER') AS is_purger, "
            "pg_has_role(current_user, 'walksafe_backend_runtime', 'MEMBER') "
            "AS is_runtime, "
            "pg_has_role(current_user, 'walksafe_account_deletion_worker', 'MEMBER') "
            "AS is_worker, "
            "pg_has_role(current_user, 'walksafe_receipt_purge_owner', 'MEMBER') "
            "AS is_purge_owner "
            "FROM pg_roles AS role WHERE role.rolname = current_user"
        ),
        {"purger": RECEIPT_PURGE_ROLE},
    ).mappings().one()
    if (
        role["rolsuper"]
        or not role["direct_login"]
        or not role["is_purger"]
        or role["is_runtime"]
        or role["is_worker"]
        or role["is_purge_owner"]
    ):
        raise ReceiptPurgeError(
            "receipt purge requires a dedicated walksafe_receipt_purger login"
        )


def purge_expired_account_deletion_receipts(
    db: Session,
    *,
    batch_limit: int = 1_000,
    max_batches: int = 100,
) -> ReceiptPurgeResult:
    """Delete expired receipts in committed batches until none are left."""

    _validate_limits(batch_limit, max_batches)
    assert_receipt_purge_database_role(db)
    db.rollback()
    deleted = 0
    for batch in range(1, max_batches + 1):
        count = int(
            db.execute(
                text("SELECT walksafe_purge_expired_account_deletion_receipts(:limit)"),
                {"limit": batch_limit},
            ).scalar_one()
        )
        db.commit()
        deleted += count
        if count < batch_limit:
            return ReceiptPurgeResult(deleted=deleted, batches=batch, exhausted=True)
    return ReceiptPurgeResult(deleted=deleted, batches=max_batches, exhausted=False)


__all__ = [
    "MAX_BATCHES",
    "MAX_BATCH_LIMIT",
    "RECEIPT_PURGE_ROLE",
    "ReceiptPurgeError",
    "ReceiptPurgeResult",
    "assert_receipt_purge_database_role",
    "purge_expired_account_deletion_receipts",
]
