"""Hide restored account data at runtime; deletion inventories retain raw access."""

from sqlalchemy import exists, select

from backend.app.models import AccountDeletionTombstone


def account_subject_visible(model):
    """Legacy unbound rows stay visible; exact subject and generation are fenced."""
    return ~exists(
        select(AccountDeletionTombstone.tombstone_id)
        .where(
            AccountDeletionTombstone.privacy_subject_hmac == model.privacy_subject_hmac,
            AccountDeletionTombstone.account_generation == model.account_generation,
        )
        .correlate(model)
    )
