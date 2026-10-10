"""Runtime read boundary for data restored from a pre-deletion backup.

Physical deletion workers deliberately do not use this predicate: they must still
see the restored rows to remove them. Legacy unbound rows remain visible.
"""
from sqlalchemy import exists, select
from backend.app.models import AccountDeletionTombstone


def account_subject_visible(model):
    return ~exists(select(AccountDeletionTombstone.tombstone_id).where(
        AccountDeletionTombstone.privacy_subject_hmac == model.privacy_subject_hmac,
        AccountDeletionTombstone.account_generation == model.account_generation,
    ).correlate(model))
