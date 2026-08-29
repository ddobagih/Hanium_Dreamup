"""Human-reviewed quarantine decisions and legal holds for raw collections."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import hmac
import uuid

from sqlalchemy import desc, func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AccountDeletionTombstone,
    PrivacyConsentEvent,
    RawCollection,
    RawCollectionLegalHoldEvent,
    RawCollectionObject,
    RawCollectionPurposeDecision,
)
from backend.app.schemas import RawLegalHoldRequestV1, RawPurposeDecisionRequestV1
from backend.app.services.privacy_lifecycle import consent_event_is_current


TRAINING_FORBIDDEN_SOURCE_KINDS = frozenset(
    {"VIDEO", "AUDIO", "EXACT_LOCATION", "ROUTE"}
)


class RawCollectionLifecycleError(RuntimeError):
    def __init__(self, code: str, message: str, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class LifecycleAdminIdentity:
    admin_id: str
    session_id: uuid.UUID
    device_id: str
    correlation_id: uuid.UUID


def _fail(code: str, message: str, status_code: int = 409) -> None:
    raise RawCollectionLifecycleError(code, message, status_code)


def _latest_training_consent(
    db: Session, collection: RawCollection
) -> PrivacyConsentEvent:
    if db.scalar(
        select(AccountDeletionTombstone.tombstone_id).where(
            AccountDeletionTombstone.privacy_subject_hmac
            == collection.privacy_subject_hmac,
            AccountDeletionTombstone.account_generation
            == collection.account_generation,
        )
    ) is not None:
        _fail("raw_training_subject_deleted", "The source account was deleted.")
    event = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac
            == collection.privacy_subject_hmac,
            PrivacyConsentEvent.account_generation
            == collection.account_generation,
        )
        .order_by(desc(PrivacyConsentEvent.subject_revision))
        .limit(1)
    )
    if (
        event is None
        or not consent_event_is_current(event)
        or not event.training_reuse
    ):
        _fail(
            "raw_training_consent_not_current",
            "Current Backend training consent is required.",
        )
    return event


def _same_decision(
    event: RawCollectionPurposeDecision, payload: RawPurposeDecisionRequestV1
) -> bool:
    return all(
        (
            event.scope == payload.scope,
            event.expected_revision == payload.expected_revision,
            event.decision == payload.decision,
            event.reason == payload.reason,
            event.training_consent_receipt_sha256
            == payload.training_consent_receipt_sha256,
            event.deidentification_receipt_sha256
            == payload.deidentification_receipt_sha256,
            event.sanitized_manifest_sha256 == payload.sanitized_manifest_sha256,
            event.target_dataset_id == payload.target_dataset_id,
            event.exact_location_excluded == payload.exact_location_excluded,
            event.raw_audio_excluded == payload.raw_audio_excluded,
            event.third_party_faces_excluded
            == payload.third_party_faces_excluded,
        )
    )


def record_purpose_decision(
    db: Session,
    *,
    collection_id: uuid.UUID,
    payload: RawPurposeDecisionRequestV1,
    identity: LifecycleAdminIdentity,
) -> tuple[RawCollectionPurposeDecision, bool]:
    collection = db.scalar(
        select(RawCollection)
        .where(RawCollection.collection_id == collection_id)
        .with_for_update()
    )
    if collection is None:
        _fail("raw_collection_not_found", "Raw collection was not found.", 404)
    if (
        collection.lifecycle_version != 2
        or collection.state != "QUARANTINED"
        or collection.quarantine_expires_at is None
    ):
        _fail(
            "raw_collection_not_reviewable",
            "Only a v2 quarantined collection can be reviewed.",
        )
    now = db.scalar(select(func.clock_timestamp()))
    assert isinstance(now, datetime)
    if now >= collection.quarantine_expires_at:
        _fail("raw_quarantine_expired", "The raw quarantine has expired.")

    replay = db.scalar(
        select(RawCollectionPurposeDecision).where(
            RawCollectionPurposeDecision.collection_id == collection_id,
            RawCollectionPurposeDecision.scope == payload.scope,
            RawCollectionPurposeDecision.idempotency_key == payload.idempotency_key,
        )
    )
    if replay is not None:
        if not _same_decision(replay, payload):
            _fail(
                "raw_decision_idempotency_conflict",
                "The idempotency key is bound to another decision.",
            )
        return replay, False

    latest = db.scalar(
        select(RawCollectionPurposeDecision)
        .where(
            RawCollectionPurposeDecision.collection_id == collection_id,
            RawCollectionPurposeDecision.scope == payload.scope,
        )
        .order_by(desc(RawCollectionPurposeDecision.revision))
        .limit(1)
    )
    current_revision = 0 if latest is None else latest.revision
    if payload.expected_revision != current_revision:
        _fail(
            "raw_decision_revision_conflict",
            "The purpose decision revision changed.",
        )

    if payload.scope == "TRAINING" and payload.decision == "APPROVED":
        consent = _latest_training_consent(db, collection)
        assert payload.training_consent_receipt_sha256 is not None
        if not hmac.compare_digest(
            consent.receipt_sha256, payload.training_consent_receipt_sha256
        ):
            _fail(
                "raw_training_consent_receipt_stale",
                "The approval does not bind the latest Backend consent receipt.",
            )
        source_kinds = set(
            db.scalars(
                select(RawCollectionObject.kind).where(
                    RawCollectionObject.collection_id == collection_id
                )
            ).all()
        )
        if source_kinds & TRAINING_FORBIDDEN_SOURCE_KINDS:
            _fail(
                "raw_training_source_kind_forbidden",
                "This raw source kind cannot be promoted for training.",
                422,
            )

    assert collection.receipt_sha256 is not None
    event = RawCollectionPurposeDecision(
        collection_id=collection_id,
        scope=payload.scope,
        revision=current_revision + 1,
        expected_revision=payload.expected_revision,
        idempotency_key=payload.idempotency_key,
        decision=payload.decision,
        reason=payload.reason,
        source_manifest_sha256=collection.manifest_sha256,
        source_receipt_sha256=collection.receipt_sha256,
        training_consent_receipt_sha256=payload.training_consent_receipt_sha256,
        deidentification_receipt_sha256=payload.deidentification_receipt_sha256,
        sanitized_manifest_sha256=payload.sanitized_manifest_sha256,
        target_dataset_id=payload.target_dataset_id,
        exact_location_excluded=payload.exact_location_excluded,
        raw_audio_excluded=payload.raw_audio_excluded,
        third_party_faces_excluded=payload.third_party_faces_excluded,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=identity.correlation_id,
    )
    db.add(event)
    try:
        db.commit()
        db.refresh(event)
    except SQLAlchemyError as exc:
        db.rollback()
        raise RawCollectionLifecycleError(
            "raw_decision_persistence_ambiguous",
            "The decision persistence result is temporarily ambiguous.",
            503,
        ) from exc
    return event, True


def _same_hold(
    event: RawCollectionLegalHoldEvent, payload: RawLegalHoldRequestV1
) -> bool:
    return all(
        (
            event.action == payload.action,
            event.expected_revision == payload.expected_revision,
            event.reason == payload.reason,
            event.legal_basis == payload.legal_basis,
            event.authority_reference == payload.authority_reference,
            event.contact == payload.contact,
            event.expires_at == payload.expires_at,
        )
    )


def record_legal_hold(
    db: Session,
    *,
    collection_id: uuid.UUID,
    payload: RawLegalHoldRequestV1,
    identity: LifecycleAdminIdentity,
) -> tuple[RawCollectionLegalHoldEvent, bool]:
    collection = db.scalar(
        select(RawCollection)
        .where(RawCollection.collection_id == collection_id)
        .with_for_update()
    )
    if collection is None:
        _fail("raw_collection_not_found", "Raw collection was not found.", 404)
    replay = db.scalar(
        select(RawCollectionLegalHoldEvent).where(
            RawCollectionLegalHoldEvent.collection_id == collection_id,
            RawCollectionLegalHoldEvent.idempotency_key == payload.idempotency_key,
        )
    )
    if replay is not None:
        if not _same_hold(replay, payload):
            _fail(
                "raw_hold_idempotency_conflict",
                "The idempotency key is bound to another legal hold event.",
            )
        return replay, False
    latest = db.scalar(
        select(RawCollectionLegalHoldEvent)
        .where(RawCollectionLegalHoldEvent.collection_id == collection_id)
        .order_by(desc(RawCollectionLegalHoldEvent.revision))
        .limit(1)
    )
    current_revision = 0 if latest is None else latest.revision
    if payload.expected_revision != current_revision:
        _fail("raw_hold_revision_conflict", "The legal hold revision changed.")
    now = db.scalar(select(func.clock_timestamp()))
    assert isinstance(now, datetime)
    if payload.action == "APPLY" and (
        payload.expires_at is None
        or payload.expires_at.astimezone(UTC) <= now.astimezone(UTC)
    ):
        _fail("raw_hold_expiry_invalid", "A legal hold expiry must be in the future.", 422)
    if payload.action == "RELEASE" and (
        latest is None
        or latest.action != "APPLY"
        or latest.expires_at is None
        or latest.expires_at <= now
    ):
        _fail("raw_hold_not_active", "There is no active legal hold to release.")
    event = RawCollectionLegalHoldEvent(
        collection_id=collection_id,
        revision=current_revision + 1,
        expected_revision=payload.expected_revision,
        idempotency_key=payload.idempotency_key,
        action=payload.action,
        reason=payload.reason,
        legal_basis=payload.legal_basis,
        authority_reference=payload.authority_reference,
        contact=payload.contact,
        expires_at=payload.expires_at,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=identity.correlation_id,
    )
    db.add(event)
    try:
        db.commit()
        db.refresh(event)
    except SQLAlchemyError as exc:
        db.rollback()
        raise RawCollectionLifecycleError(
            "raw_hold_persistence_ambiguous",
            "The legal hold persistence result is temporarily ambiguous.",
            503,
        ) from exc
    return event, True


__all__ = [
    "LifecycleAdminIdentity",
    "RawCollectionLifecycleError",
    "TRAINING_FORBIDDEN_SOURCE_KINDS",
    "record_legal_hold",
    "record_purpose_decision",
]
