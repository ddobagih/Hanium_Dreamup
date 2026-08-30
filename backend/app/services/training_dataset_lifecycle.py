"""Fail-closed approved-training artifact and dataset lifecycle operations."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import uuid

from sqlalchemy import desc, func, select, text
from sqlalchemy.orm import Session

from backend.app.models import (
    AccountDeletionTombstone,
    ApprovedTrainingArtifact,
    PrivacyConsentEvent,
    RawCollection,
    RawCollectionObject,
    RawCollectionPurposeDecision,
    TrainingDatasetLifecycleEvent,
    TrainingDatasetMember,
    TrainingDatasetRevision,
)
from backend.app.services.privacy_lifecycle import consent_event_is_current


class TrainingDatasetLifecycleError(RuntimeError):
    pass


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_KEY_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


@dataclass(frozen=True, slots=True)
class DatasetMemberInput:
    artifact_id: uuid.UUID
    split: str


def _canonical_digest(payload: object, *, domain: str) -> str:
    body = json.dumps(
        payload, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("ascii")
    return hashlib.sha256(domain.encode("ascii") + b"\0" + body).hexdigest()


def _require_current_consent(
    db: Session,
    *,
    privacy_subject_hmac: str,
    account_generation: int,
    receipt_sha256: str,
) -> None:
    tombstone = db.scalar(
        select(AccountDeletionTombstone.tombstone_id).where(
            AccountDeletionTombstone.privacy_subject_hmac
            == privacy_subject_hmac,
            AccountDeletionTombstone.account_generation == account_generation,
        )
    )
    if tombstone is not None:
        raise TrainingDatasetLifecycleError("training source account is deleted")
    event = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject_hmac,
            PrivacyConsentEvent.account_generation == account_generation,
        )
        .order_by(desc(PrivacyConsentEvent.subject_revision))
        .limit(1)
    )
    if (
        event is None
        or not consent_event_is_current(event)
        or not event.training_reuse
        or event.receipt_sha256 != receipt_sha256
    ):
        raise TrainingDatasetLifecycleError(
            "training consent is withdrawn, absent, or stale"
        )


def register_sanitized_artifact(
    db: Session,
    *,
    training_root: Path,
    decision_id: uuid.UUID,
    source_object_id: uuid.UUID,
    kind: str,
    content_type: str,
    content_sha256: str,
    content_size: int,
    storage_name: str,
    key_id: str,
    nonce: bytes,
    envelope_sha256: str,
    envelope_size: int,
) -> ApprovedTrainingArtifact:
    if (
        kind not in {"SANITIZED_IMAGE", "LABEL", "METADATA"}
        or _SHA256.fullmatch(content_sha256) is None
        or _SHA256.fullmatch(envelope_sha256) is None
        or _KEY_ID.fullmatch(key_id) is None
        or len(nonce) != 12
        or content_size <= 0
        or envelope_size <= content_size
    ):
        raise TrainingDatasetLifecycleError(
            "sanitized artifact metadata is invalid"
        )
    decision = db.get(RawCollectionPurposeDecision, decision_id)
    if (
        decision is None
        or decision.scope != "TRAINING"
        or decision.decision != "APPROVED"
        or decision.deidentification_receipt_sha256 is None
        or decision.training_consent_receipt_sha256 is None
    ):
        raise TrainingDatasetLifecycleError(
            "a human-approved TRAINING decision with de-identification PASS is required"
        )
    latest = db.scalar(
        select(RawCollectionPurposeDecision)
        .where(
            RawCollectionPurposeDecision.collection_id == decision.collection_id,
            RawCollectionPurposeDecision.scope == "TRAINING",
        )
        .order_by(desc(RawCollectionPurposeDecision.revision))
        .limit(1)
    )
    if latest is None or latest.id != decision.id:
        raise TrainingDatasetLifecycleError("training approval is no longer current")
    collection = db.get(RawCollection, decision.collection_id)
    source = db.scalar(
        select(RawCollectionObject).where(
            RawCollectionObject.collection_id == decision.collection_id,
            RawCollectionObject.object_id == source_object_id,
        )
    )
    if collection is None or source is None:
        raise TrainingDatasetLifecycleError("training source does not exist")
    _require_current_consent(
        db,
        privacy_subject_hmac=collection.privacy_subject_hmac,
        account_generation=collection.account_generation,
        receipt_sha256=decision.training_consent_receipt_sha256,
    )
    root = training_root.resolve(strict=True)
    artifact_path = (root / storage_name).resolve(strict=True)
    if (
        Path(storage_name).is_absolute()
        or ".." in Path(storage_name).parts
        or not artifact_path.is_relative_to(root)
        or not artifact_path.is_file()
        or artifact_path.is_symlink()
    ):
        raise TrainingDatasetLifecycleError(
            "sanitized artifact must be a regular file inside the training store"
        )
    digest = hashlib.sha256()
    with artifact_path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != envelope_sha256 or artifact_path.stat().st_size != envelope_size:
        raise TrainingDatasetLifecycleError(
            "sanitized artifact envelope metadata does not match storage"
        )
    artifact = ApprovedTrainingArtifact(
        source_collection_id=collection.collection_id,
        source_object_id=source.object_id,
        source_sha256=source.sha256,
        privacy_subject_hmac=collection.privacy_subject_hmac,
        account_generation=collection.account_generation,
        consent_receipt_sha256=decision.training_consent_receipt_sha256,
        approval_decision_id=decision.id,
        deidentification_receipt_sha256=decision.deidentification_receipt_sha256,
        kind=kind,
        content_type=content_type,
        content_sha256=content_sha256,
        content_size=content_size,
        storage_name=storage_name,
        key_id=key_id,
        nonce=nonce,
        envelope_sha256=envelope_sha256,
        envelope_size=envelope_size,
    )
    db.add(artifact)
    db.commit()
    db.refresh(artifact)
    return artifact


def approve_dataset_revision(
    db: Session,
    *,
    dataset_id: uuid.UUID,
    expected_parent_revision_id: uuid.UUID | None,
    manifest_sha256: str,
    members: list[DatasetMemberInput],
    admin_id: str,
    correlation_id: uuid.UUID,
) -> TrainingDatasetRevision:
    if not members:
        raise TrainingDatasetLifecycleError("dataset revision requires members")
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))"),
        {"key": f"walksafe-training-dataset:{dataset_id}"},
    )
    parent = db.scalar(
        select(TrainingDatasetRevision)
        .where(TrainingDatasetRevision.dataset_id == dataset_id)
        .order_by(desc(TrainingDatasetRevision.revision))
        .limit(1)
    )
    actual_parent_id = None if parent is None else parent.id
    if actual_parent_id != expected_parent_revision_id:
        raise TrainingDatasetLifecycleError("dataset parent revision changed")
    artifact_ids = [item.artifact_id for item in members]
    if len(set(artifact_ids)) != len(artifact_ids):
        raise TrainingDatasetLifecycleError("dataset artifact membership is duplicated")
    artifacts = {
        item.id: item
        for item in db.scalars(
            select(ApprovedTrainingArtifact).where(
                ApprovedTrainingArtifact.id.in_(artifact_ids)
            )
        ).all()
    }
    if set(artifacts) != set(artifact_ids):
        raise TrainingDatasetLifecycleError("dataset artifact is missing")
    normalized = []
    for member in members:
        if member.split not in {"train", "val", "test"}:
            raise TrainingDatasetLifecycleError("dataset split is invalid")
        artifact = artifacts[member.artifact_id]
        _require_current_consent(
            db,
            privacy_subject_hmac=artifact.privacy_subject_hmac,
            account_generation=artifact.account_generation,
            receipt_sha256=artifact.consent_receipt_sha256,
        )
        normalized.append(
            {
                "artifact_id": str(artifact.id),
                "content_sha256": artifact.content_sha256,
                "source_collection_id": str(artifact.source_collection_id),
                "source_object_id": str(artifact.source_object_id),
                "split": member.split,
            }
        )
    normalized.sort(key=lambda item: item["artifact_id"])
    member_set_sha256 = _canonical_digest(
        normalized, domain="walksafe/training-dataset-member-set/v1"
    )
    approved_at = db.scalar(
        select(func.date_trunc("second", func.clock_timestamp()))
    )
    expires_at = db.scalar(
        select(text(":approved_at + INTERVAL '3 years'")).params(
            approved_at=approved_at
        )
    )
    revision = TrainingDatasetRevision(
        dataset_id=dataset_id,
        revision=1 if parent is None else parent.revision + 1,
        parent_revision_id=expected_parent_revision_id,
        manifest_sha256=manifest_sha256,
        member_set_sha256=member_set_sha256,
        approved_at=approved_at,
        expires_at=expires_at,
        admin_id=admin_id,
        correlation_id=correlation_id,
    )
    db.add(revision)
    db.flush()
    for row, member in zip(normalized, sorted(members, key=lambda item: str(item.artifact_id))):
        artifact = artifacts[member.artifact_id]
        db.add(
            TrainingDatasetMember(
                dataset_revision_id=revision.id,
                artifact_id=artifact.id,
                split=member.split,
                content_sha256=artifact.content_sha256,
                source_collection_id=artifact.source_collection_id,
                source_object_id=artifact.source_object_id,
            )
        )
    lifecycle_payload = {
        "dataset_id": str(dataset_id),
        "dataset_revision_id": str(revision.id),
        "manifest_sha256": manifest_sha256,
        "member_set_sha256": member_set_sha256,
        "state": "APPROVED",
    }
    db.add(
        TrainingDatasetLifecycleEvent(
            dataset_revision_id=revision.id,
            revision=1,
            state="APPROVED",
            reason="Human-approved sanitized dataset revision.",
            receipt_sha256=_canonical_digest(
                lifecycle_payload,
                domain="walksafe/training-dataset-lifecycle/v1",
            ),
        )
    )
    if parent is not None:
        retire_payload = {
            "dataset_revision_id": str(parent.id),
            "state": "RETIRED",
            "superseded_by": str(revision.id),
        }
        db.add(
            TrainingDatasetLifecycleEvent(
                dataset_revision_id=parent.id,
                revision=2,
                state="RETIRED",
                reason=f"Superseded by dataset revision {revision.revision}.",
                receipt_sha256=_canonical_digest(
                    retire_payload,
                    domain="walksafe/training-dataset-lifecycle/v1",
                ),
            )
        )
    db.commit()
    db.refresh(revision)
    return revision


def require_current_dataset_revision(
    db: Session,
    *,
    dataset_id: uuid.UUID,
    revision: int,
    manifest_sha256: str,
) -> TrainingDatasetRevision:
    row = db.scalar(
        select(TrainingDatasetRevision).where(
            TrainingDatasetRevision.dataset_id == dataset_id,
            TrainingDatasetRevision.revision == revision,
            TrainingDatasetRevision.manifest_sha256 == manifest_sha256,
        )
    )
    if row is None:
        raise TrainingDatasetLifecycleError("approved dataset revision not found")
    latest_revision = db.scalar(
        select(TrainingDatasetRevision)
        .where(TrainingDatasetRevision.dataset_id == dataset_id)
        .order_by(desc(TrainingDatasetRevision.revision))
        .limit(1)
    )
    if latest_revision is None or latest_revision.id != row.id:
        raise TrainingDatasetLifecycleError("dataset revision is not current")
    now = db.scalar(select(func.clock_timestamp()))
    if now >= row.expires_at:
        raise TrainingDatasetLifecycleError("approved dataset revision is expired")
    latest = db.scalar(
        select(TrainingDatasetLifecycleEvent)
        .where(TrainingDatasetLifecycleEvent.dataset_revision_id == row.id)
        .order_by(desc(TrainingDatasetLifecycleEvent.revision))
        .limit(1)
    )
    if latest is None or latest.state != "APPROVED":
        raise TrainingDatasetLifecycleError("dataset revision is not currently approved")
    members = db.scalars(
        select(TrainingDatasetMember).where(
            TrainingDatasetMember.dataset_revision_id == row.id
        )
    ).all()
    if not members:
        raise TrainingDatasetLifecycleError("dataset revision has no members")
    artifacts = {
        artifact.id: artifact
        for artifact in db.scalars(
            select(ApprovedTrainingArtifact).where(
                ApprovedTrainingArtifact.id.in_([member.artifact_id for member in members])
            )
        ).all()
    }
    for member in members:
        artifact = artifacts.get(member.artifact_id)
        if artifact is None or artifact.content_sha256 != member.content_sha256:
            raise TrainingDatasetLifecycleError("dataset artifact was removed or changed")
        _require_current_consent(
            db,
            privacy_subject_hmac=artifact.privacy_subject_hmac,
            account_generation=artifact.account_generation,
            receipt_sha256=artifact.consent_receipt_sha256,
        )
    return row


__all__ = [
    "DatasetMemberInput",
    "TrainingDatasetLifecycleError",
    "approve_dataset_revision",
    "register_sanitized_artifact",
    "require_current_dataset_revision",
]
