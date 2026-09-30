"""S1617-58: a model trained on a dataset revision is re-checked per member.

Training-consent withdrawal, account deletion, artifact removal or a retired
revision make the model RETRAIN_REQUIRED and block any new deployment; a newer
revision of the same dataset alone does not.
"""

from __future__ import annotations

import hashlib
import os
import uuid

import pytest
from sqlalchemy import select, text

from backend.app.models import ApprovedTrainingArtifact, PrivacyConsentEvent
from backend.app.services.privacy_lifecycle import privacy_subject_hmac
from backend.app.services.training_model_lineage import (
    TrainingLineageError,
    TrainingModelLineage,
    assess_training_model_lineage,
    require_model_deployable,
)
from test_fp046_postgres_integration import (
    PRIVACY_SECRET,
    _accept,
    _record_consent,
    _session_factory,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _sha(label: str) -> str:
    return hashlib.sha256(label.encode()).hexdigest()


def _artifact(db, actor: str, suffix: str) -> tuple[uuid.UUID, str]:
    subject = privacy_subject_hmac(actor, 1, PRIVACY_SECRET)
    receipt = db.scalar(
        select(PrivacyConsentEvent.receipt_sha256)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == subject,
            PrivacyConsentEvent.account_generation == 1,
        )
        .order_by(PrivacyConsentEvent.subject_revision.desc())
        .limit(1)
    )
    artifact_id = uuid.uuid4()
    content = _sha(f"content-{artifact_id}")
    db.add(
        ApprovedTrainingArtifact(
            id=artifact_id,
            source_collection_id=uuid.uuid4(),
            source_object_id=uuid.uuid4(),
            source_sha256=_sha(f"source-{artifact_id}"),
            privacy_subject_hmac=subject,
            account_generation=1,
            consent_receipt_sha256=receipt,
            approval_decision_id=uuid.uuid4(),
            deidentification_receipt_sha256=_sha(f"deid-{artifact_id}"),
            kind="SANITIZED_IMAGE",
            content_type="image/png",
            content_sha256=content,
            content_size=10,
            storage_name=f"w25-lineage/{suffix}/{artifact_id}.bin",
            key_id="w25-lineage-key",
            nonce=uuid.uuid4().bytes[:12],
            envelope_sha256=_sha(f"envelope-{artifact_id}"),
            envelope_size=50,
        )
    )
    db.flush()
    return artifact_id, content


def _revision(db, dataset_id: uuid.UUID, revision: int, members, *, state: str = "APPROVED") -> str:
    manifest = _sha(f"manifest-{dataset_id}-{revision}")
    revision_id = uuid.uuid4()
    db.execute(
        text(
            "WITH t AS (SELECT clock_timestamp() AS now) "
            "INSERT INTO training_dataset_revisions (id, dataset_id, revision, "
            "parent_revision_id, manifest_sha256, member_set_sha256, approved_at, "
            "expires_at, admin_id, correlation_id) "
            "SELECT :id, :dataset, :revision, NULL, :manifest, :members, t.now, "
            "t.now + interval '3 years', 'admin-w25', :correlation FROM t"
        ),
        {
            "id": revision_id,
            "dataset": dataset_id,
            "revision": revision,
            "manifest": manifest,
            "members": _sha(f"members-{revision_id}"),
            "correlation": uuid.uuid4(),
        },
    )
    for artifact_id, content in members:
        db.execute(
            text(
                "INSERT INTO training_dataset_members (dataset_revision_id, artifact_id, "
                "split, content_sha256, source_collection_id, source_object_id) "
                "SELECT :revision_id, a.id, 'train', :content, a.source_collection_id, "
                "a.source_object_id FROM approved_training_artifacts AS a WHERE a.id = :artifact"
            ),
            {"revision_id": revision_id, "artifact": artifact_id, "content": content},
        )
    db.execute(
        text(
            "INSERT INTO training_dataset_lifecycle_events (id, dataset_revision_id, "
            "revision, state, reason, receipt_sha256) VALUES "
            "(:id, :revision_id, 1, :state, 'w25 lineage fixture', :receipt)"
        ),
        {
            "id": uuid.uuid4(),
            "revision_id": revision_id,
            "state": state,
            "receipt": _sha(f"lifecycle-{revision_id}"),
        },
    )
    return manifest


def _lineage(dataset_id: uuid.UUID, revision: int, manifest: str, job: str) -> TrainingModelLineage:
    return TrainingModelLineage(
        training_job_id=job,
        model_artifact_sha256=_sha(f"model-{job}"),
        dataset_id=dataset_id,
        dataset_revision=revision,
        dataset_manifest_sha256=manifest,
    )


def test_s1617_58_withdrawal_deletion_and_retirement_require_retraining() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_a = f"lineage-a.{suffix}"
    actor_b = f"lineage-b.{suffix}"
    actor_c = f"lineage-c.{suffix}"
    for actor in (actor_a, actor_b, actor_c):
        _record_consent(SessionFactory, actor_id=actor, training_reuse=True)
    dataset = uuid.uuid4()
    retired_dataset = uuid.uuid4()
    superseded_dataset = uuid.uuid4()
    with SessionFactory.begin() as db:
        member_a = _artifact(db, actor_a, suffix)
        member_b = _artifact(db, actor_b, suffix)
        member_c = _artifact(db, actor_c, suffix)
        manifest = _revision(db, dataset, 1, [member_a, member_b])
        retired_manifest = _revision(db, retired_dataset, 1, [member_c], state="RETIRED")
        superseded_manifest = _revision(db, superseded_dataset, 1, [member_c])
        _revision(db, superseded_dataset, 2, [member_c])
    model = _lineage(dataset, 1, manifest, f"job-{suffix}")
    try:
        with SessionFactory() as db:
            intact = require_model_deployable(db, model)
        assert (intact.status, intact.member_count, intact.affected_member_count) == (
            "DEPLOYABLE", 2, 0,
        )
        # A newer revision of the same dataset is not a withdrawal.
        with SessionFactory() as db:
            superseded = assess_training_model_lineage(
                db, _lineage(superseded_dataset, 1, superseded_manifest, f"job-old-{suffix}")
            )
        assert superseded.status == "DEPLOYABLE"

        # B withdraws training consent after training: retrain, no new deployment.
        _record_consent(
            SessionFactory, actor_id=actor_b, client_revision=2, training_reuse=False
        )
        with SessionFactory() as db:
            withdrawn = assess_training_model_lineage(db, model)
        assert withdrawn.status == "RETRAIN_REQUIRED"
        assert withdrawn.reasons == ("training_consent_withdrawn",)
        assert withdrawn.affected_member_count == 1
        with pytest.raises(TrainingLineageError) as blocked:
            with SessionFactory() as db:
                require_model_deployable(db, model)
        assert blocked.value.reasons == ("training_consent_withdrawn",)

        # A deletes the account: both members now affected.
        _accept(SessionFactory, actor_id=actor_a, request_id=f"delete_lineage_{suffix}")
        with SessionFactory() as db:
            deleted = assess_training_model_lineage(db, model)
        assert deleted.reasons == ("source_account_deleted", "training_consent_withdrawn")
        assert deleted.affected_member_count == 2

        with SessionFactory() as db:
            retired = assess_training_model_lineage(
                db, _lineage(retired_dataset, 1, retired_manifest, f"job-retired-{suffix}")
            )
            missing = assess_training_model_lineage(
                db, _lineage(dataset, 1, _sha(f"unknown-{suffix}"), f"job-missing-{suffix}")
            )
        assert retired.reasons == ("dataset_revision_retired",)
        assert missing.status == "RETRAIN_REQUIRED"
        assert missing.reasons == ("dataset_revision_missing",)
        with pytest.raises(TrainingLineageError):
            TrainingModelLineage(
                training_job_id="x",
                model_artifact_sha256="0" * 64,
                dataset_id=dataset,
                dataset_revision=1,
                dataset_manifest_sha256=manifest,
            )
    finally:
        engine.dispose()
