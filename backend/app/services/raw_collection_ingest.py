"""Pre-storage admission boundary for EPIC-07 raw collection writes.

B1a intentionally contains no successful storage implementation. This module
only binds a Gateway-authenticated actor generation to purpose-specific
consent continuity and the account-deletion tombstone fence.
"""

from __future__ import annotations

from dataclasses import dataclass
from secrets import compare_digest
from typing import Any, Sequence

from sqlalchemy import exists, or_, select
from sqlalchemy.orm import Session

from backend.app.models import PrivacyConsentEvent
from backend.app.schemas import (
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    RawCollectionPurpose,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    bind_or_verify_privacy_hmac_key,
    configured_privacy_hmac_lookup_keys,
    consent_event_is_current,
    lock_report_ingest_transaction,
    privacy_subject_hmac,
    resolve_privacy_subject,
)


@dataclass(frozen=True, slots=True)
class RawCollectionAdmission:
    actor_id: str
    account_generation: int
    privacy_subject_hmac: str
    purpose: str
    consent_receipt_sha256: str


class RawCollectionStorageError(Exception):
    """Transport-independent raw collection storage/domain failure."""

    def __init__(self, *, code: str, message: str, status_code: int) -> None:
        if (
            type(code) is not str
            or not 1 <= len(code) <= 128
            or type(message) is not str
            or not 1 <= len(message) <= 500
            or type(status_code) is not int
            or status_code not in {400, 404, 409, 413, 422, 429, 503}
        ):
            raise ValueError(
                "raw storage errors require public code, message, and allowed status"
            )
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


def load_raw_collection_consent_events(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    bound_receipt_sha256: str,
) -> tuple[PrivacyConsentEvent, ...]:
    bound = db.scalar(
        select(PrivacyConsentEvent).where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
            PrivacyConsentEvent.receipt_sha256 == bound_receipt_sha256,
        )
    )
    if bound is None:
        return ()
    return (bound,)


def first_raw_collection_consent_interruption(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    bound_revision: int,
    purpose: RawCollectionPurpose,
) -> PrivacyConsentEvent | None:
    scope = (
        PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
        PrivacyConsentEvent.account_generation == account_generation,
        PrivacyConsentEvent.subject_revision > bound_revision,
    )
    interruptions = [
        PrivacyConsentEvent.policy_version.is_distinct_from(
            PRIVACY_CONSENT_POLICY_VERSION
        ),
        PrivacyConsentEvent.item_versions.is_distinct_from(
            PRIVACY_CONSENT_ITEM_VERSIONS
        ),
        PrivacyConsentEvent.raw_source_collection.is_not(True),
    ]
    if purpose == "AUTO_REPORT":
        interruptions.append(PrivacyConsentEvent.automatic_reporting.is_not(True))
    interrupted = or_(*interruptions)
    if not db.scalar(select(exists().where(*scope, interrupted))):
        return None
    return db.scalar(
        select(PrivacyConsentEvent)
        .where(*scope, interrupted)
        .order_by(PrivacyConsentEvent.subject_revision.asc())
        .limit(1)
    )


def validate_raw_collection_consent_chain(
    events: Sequence[Any],
    *,
    purpose: RawCollectionPurpose,
    bound_receipt_sha256: str,
) -> Any:
    bound = next(
        (
            event
            for event in events
            if compare_digest(event.receipt_sha256, bound_receipt_sha256)
        ),
        None,
    )
    if bound is None:
        raise PrivacyLifecycleError(
            "raw_consent_receipt_not_found",
            "The bound consent receipt does not exist for the same account generation.",
            status_code=409,
        )
    if not consent_event_is_current(bound):
        raise PrivacyLifecycleError(
            "raw_consent_policy_not_current",
            "The bound consent receipt requires current integrated consent.",
            status_code=409,
        )
    if bound.raw_source_collection is not True:
        raise PrivacyLifecycleError(
            "raw_source_collection_consent_required",
            "The bound receipt does not grant raw-source collection consent.",
            status_code=409,
        )
    if purpose == "AUTO_REPORT" and bound.automatic_reporting is not True:
        raise PrivacyLifecycleError(
            "automatic_reporting_consent_required",
            "The bound receipt does not grant automatic-reporting consent.",
            status_code=409,
        )
    for event in events:
        if event.subject_revision < bound.subject_revision:
            continue
        if not consent_event_is_current(event):
            raise PrivacyLifecycleError(
                "raw_consent_policy_not_current",
                "Raw consent continuity requires current integrated consent.",
                status_code=409,
            )
        if event.raw_source_collection is not True:
            raise PrivacyLifecycleError(
                "raw_source_collection_consent_interrupted",
                "The bound raw-source consent continuity was interrupted.",
                status_code=409,
            )
        if purpose == "AUTO_REPORT" and event.automatic_reporting is not True:
            raise PrivacyLifecycleError(
                "automatic_reporting_consent_interrupted",
                "The bound automatic-reporting consent continuity was interrupted.",
                status_code=409,
            )
    return bound


def authorize_raw_collection_write(
    db: Session,
    *,
    actor_id: str,
    account_generation: int,
    purpose: RawCollectionPurpose,
    consent_receipt_sha256: str,
    settings: Any,
) -> RawCollectionAdmission:
    """Hold the deletion fence and verify purpose-specific consent continuity."""

    bind_or_verify_privacy_hmac_key(
        db,
        secret=settings.privacy_hmac_secret,
        key_version=settings.privacy_hmac_key_version,
    )
    lock_options: dict[str, object] = {}
    if configured_privacy_hmac_lookup_keys():
        # S1617-50: after a rotation RAW joins the subject that owns the actor
        # generation, and every candidate subject is fenced and checked.
        resolved = resolve_privacy_subject(
            db,
            actor_id,
            account_generation,
            secret=settings.privacy_hmac_secret,
            key_version=settings.privacy_hmac_key_version,
        )
        privacy_subject = resolved.subject
        lock_options["lookup_subjects"] = resolved.candidates
    else:
        privacy_subject = privacy_subject_hmac(
            actor_id,
            account_generation,
            settings.privacy_hmac_secret,
        )
    lock_report_ingest_transaction(
        db,
        privacy_subject,
        account_generation,
        enforce_consent=False,
        **lock_options,
    )
    consent = validate_raw_collection_consent_chain(
        load_raw_collection_consent_events(
            db,
            privacy_subject=privacy_subject,
            account_generation=account_generation,
            bound_receipt_sha256=consent_receipt_sha256,
        ),
        purpose=purpose,
        bound_receipt_sha256=consent_receipt_sha256,
    )
    interruption = first_raw_collection_consent_interruption(
        db,
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        bound_revision=consent.subject_revision,
        purpose=purpose,
    )
    if interruption is not None:
        validate_raw_collection_consent_chain(
            (consent, interruption),
            purpose=purpose,
            bound_receipt_sha256=consent_receipt_sha256,
        )
    return RawCollectionAdmission(
        actor_id=actor_id,
        account_generation=account_generation,
        privacy_subject_hmac=privacy_subject,
        purpose=purpose,
        consent_receipt_sha256=consent.receipt_sha256,
    )


__all__ = [
    "RawCollectionAdmission",
    "RawCollectionStorageError",
    "authorize_raw_collection_write",
    "first_raw_collection_consent_interruption",
    "load_raw_collection_consent_events",
    "validate_raw_collection_consent_chain",
]
