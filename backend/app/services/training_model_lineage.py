"""S1617-58: model lineage check from a dataset revision to its members.

A trained model is described by its lineage: training job id, model artifact
SHA-256 and the approved dataset revision (id, revision, manifest SHA-256) it
was trained on. ``assess_training_model_lineage`` re-checks every member of
that revision against the current deletion fence, integrated consent and
artifact inventory.

Policy (root choice, not an approved policy): a model whose revision lost a
member through account deletion, training-consent withdrawal or artifact
removal, or whose revision is retired/expired/missing, is RETRAIN_REQUIRED.
It is not deleted automatically; ``require_model_deployable`` refuses any new
deployment or promotion of it, and retraining on a cleaned revision is an
operations decision. A job that was running when consent was withdrawn is
covered because its output is assessed when it is registered or promoted.
A newer revision of the same dataset alone does not make a model
RETRAIN_REQUIRED.
"""

from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Literal
import uuid

from sqlalchemy import desc, func, select
from sqlalchemy.orm import Session

from backend.app.models import (
    AccountDeletionTombstone,
    ApprovedTrainingArtifact,
    PrivacyConsentEvent,
    TrainingDatasetLifecycleEvent,
    TrainingDatasetMember,
    TrainingDatasetRevision,
)
from backend.app.services.privacy_lifecycle import (
    TRAINING_SOURCE_WALK_RAW_DIAGNOSTIC,
    consent_event_is_current,
    training_consent_allows,
)


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_JOB_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$")

LineageStatus = Literal["DEPLOYABLE", "RETRAIN_REQUIRED"]


class TrainingLineageError(RuntimeError):
    def __init__(self, message: str, *, reasons: tuple[str, ...] = ()) -> None:
        super().__init__(message)
        self.reasons = reasons


@dataclass(frozen=True)
class TrainingModelLineage:
    training_job_id: str
    model_artifact_sha256: str
    dataset_id: uuid.UUID
    dataset_revision: int
    dataset_manifest_sha256: str

    def __post_init__(self) -> None:
        if (
            _JOB_ID.fullmatch(self.training_job_id) is None
            or _SHA256.fullmatch(self.model_artifact_sha256) is None
            or _SHA256.fullmatch(self.dataset_manifest_sha256) is None
            or type(self.dataset_revision) is not int
            or self.dataset_revision < 1
            or not isinstance(self.dataset_id, uuid.UUID)
        ):
            raise TrainingLineageError("model lineage record is invalid")


@dataclass(frozen=True)
class TrainingLineageAssessment:
    status: LineageStatus
    reasons: tuple[str, ...]
    member_count: int
    affected_member_count: int


def _member_reason(db: Session, artifact: ApprovedTrainingArtifact | None, member) -> str | None:
    if artifact is None or artifact.content_sha256 != member.content_sha256:
        return "training_artifact_removed"
    tombstone = db.scalar(
        select(AccountDeletionTombstone.tombstone_id).where(
            AccountDeletionTombstone.privacy_subject_hmac == artifact.privacy_subject_hmac,
            AccountDeletionTombstone.account_generation == artifact.account_generation,
        )
    )
    if tombstone is not None:
        return "source_account_deleted"
    event = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == artifact.privacy_subject_hmac,
            PrivacyConsentEvent.account_generation == artifact.account_generation,
        )
        .order_by(desc(PrivacyConsentEvent.subject_revision))
        .limit(1)
    )
    if (
        event is None
        or not consent_event_is_current(event)
        or not training_consent_allows(event, TRAINING_SOURCE_WALK_RAW_DIAGNOSTIC)
    ):
        return "training_consent_withdrawn"
    if event.receipt_sha256 != artifact.consent_receipt_sha256:
        return "training_consent_changed"
    return None


def assess_training_model_lineage(
    db: Session,
    lineage: TrainingModelLineage,
) -> TrainingLineageAssessment:
    """Read-only re-check of the dataset revision a model was trained on."""

    revision = db.scalar(
        select(TrainingDatasetRevision).where(
            TrainingDatasetRevision.dataset_id == lineage.dataset_id,
            TrainingDatasetRevision.revision == lineage.dataset_revision,
            TrainingDatasetRevision.manifest_sha256 == lineage.dataset_manifest_sha256,
        )
    )
    if revision is None:
        return TrainingLineageAssessment(
            "RETRAIN_REQUIRED", ("dataset_revision_missing",), 0, 0
        )
    reasons: set[str] = set()
    latest = db.scalar(
        select(TrainingDatasetLifecycleEvent)
        .where(TrainingDatasetLifecycleEvent.dataset_revision_id == revision.id)
        .order_by(desc(TrainingDatasetLifecycleEvent.revision))
        .limit(1)
    )
    if latest is None or latest.state != "APPROVED":
        reasons.add(
            "dataset_revision_expired"
            if latest is not None and latest.state == "EXPIRED"
            else "dataset_revision_retired"
        )
    if db.scalar(select(func.clock_timestamp())) >= revision.expires_at:
        reasons.add("dataset_revision_expired")
    members = db.scalars(
        select(TrainingDatasetMember)
        .where(TrainingDatasetMember.dataset_revision_id == revision.id)
        .order_by(TrainingDatasetMember.artifact_id)
    ).all()
    if not members:
        reasons.add("dataset_revision_has_no_members")
    artifacts = {
        artifact.id: artifact
        for artifact in (
            db.scalars(
                select(ApprovedTrainingArtifact).where(
                    ApprovedTrainingArtifact.id.in_([member.artifact_id for member in members])
                )
            ).all()
            if members
            else []
        )
    }
    affected = 0
    for member in members:
        reason = _member_reason(db, artifacts.get(member.artifact_id), member)
        if reason is not None:
            affected += 1
            reasons.add(reason)
    return TrainingLineageAssessment(
        status="RETRAIN_REQUIRED" if reasons else "DEPLOYABLE",
        reasons=tuple(sorted(reasons)),
        member_count=len(members),
        affected_member_count=affected,
    )


def require_model_deployable(
    db: Session,
    lineage: TrainingModelLineage,
) -> TrainingLineageAssessment:
    """Refuse a new deployment/promotion of a RETRAIN_REQUIRED model."""

    assessment = assess_training_model_lineage(db, lineage)
    if assessment.status != "DEPLOYABLE":
        raise TrainingLineageError(
            "model is RETRAIN_REQUIRED and cannot be newly deployed",
            reasons=assessment.reasons,
        )
    return assessment


__all__ = [
    "TrainingLineageAssessment",
    "TrainingLineageError",
    "TrainingModelLineage",
    "assess_training_model_lineage",
    "require_model_deployable",
]
