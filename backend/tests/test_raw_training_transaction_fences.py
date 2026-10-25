"""Unit transaction ordering; these fakes do not establish PostgreSQL races."""

from datetime import UTC, datetime, timedelta
import hashlib
from types import SimpleNamespace
import uuid

from fastapi import HTTPException, Response
import pytest
from starlette.requests import Request

from backend.app.api import admin_raw_collections as api
from backend.app.models import (
    ApprovedTrainingArtifact, RawCollection, RawCollectionPurposeDecision,
)
from backend.app.schemas import RawLegalHoldRequestV1, RawPurposeDecisionRequestV1
from backend.app.services import training_dataset_lifecycle as training
from backend.app.services.admin_security import AdminSecurityError, AdminSecurityStoreUnavailable
from backend.app.services.raw_collection_lifecycle import LifecycleAdminIdentity


NOW = datetime.now(UTC)
COLLECTION_ID = uuid.uuid4()
IDENTITY = LifecycleAdminIdentity("fence-admin", uuid.uuid4(), "fence-device", uuid.uuid4())


class MutationDb:
    def __init__(self, suffix):
        self.events = []
        self.pending = []
        self.committed = []
        collection = SimpleNamespace(
            collection_id=COLLECTION_ID, lifecycle_version=2, state="QUARANTINED",
            quarantine_expires_at=NOW + timedelta(days=1),
            manifest_sha256="a" * 64, receipt_sha256="b" * 64,
        )
        self.results = iter(
            (collection, NOW, None, None) if suffix == "decisions"
            else (collection, None, None, NOW)
        )

    def scalar(self, statement):
        self.events.append("collection" if "FOR UPDATE" in str(statement) else "read")
        return next(self.results)

    def add(self, row):
        row.id = uuid.uuid4()
        row.decided_at = row.recorded_at = NOW
        self.pending.append(row)
        self.events.append("add")

    def commit(self):
        self.events.append("commit")
        self.committed.extend(self.pending)

    def rollback(self):
        self.events.append("rollback")
        self.pending.clear()

    def refresh(self, row):
        self.events.append("refresh")


def _raw_request(suffix):
    path = f"/admin/raw-collections/{COLLECTION_ID}/{suffix}"
    settings = SimpleNamespace(admin_totp_secret="configured-test-factor")
    return Request({
        "type": "http", "method": "POST", "path": path,
        "headers": [(b"authorization", b"Bearer test-only-token"),
                    (b"x-walksafe-device-id", IDENTITY.device_id.encode()),
                    (b"x-walksafe-app-kind", b"android-admin"),
                    (b"x-walksafe-role", b"admin"),
                    (b"x-walksafe-audience", b"walksafe-backend")],
        "app": SimpleNamespace(state=SimpleNamespace(runtime=SimpleNamespace(settings=settings))),
        "query_string": b"", "scheme": "https", "server": ("test.invalid", 443),
    })


def _raw_payload(suffix):
    common = dict(expected_revision=0, idempotency_key=uuid.uuid4(), reason="reviewed")
    if suffix == "decisions":
        return RawPurposeDecisionRequestV1(scope="REPORT", decision="APPROVED", **common)
    return RawLegalHoldRequestV1(
        action="APPLY", legal_basis="test legal basis", authority_reference="test reference",
        contact="test contact", expires_at=NOW + timedelta(days=2), **common,
    )


@pytest.mark.parametrize("suffix", ["decisions", "legal-holds"])
@pytest.mark.parametrize("authority", ["current", "revoked", "different_session", "audit_unavailable"])
def test_raw_mutation_reauthorizes_after_proof_before_collection_commit(monkeypatch, suffix, authority):
    db = MutationDb(suffix)
    request = _raw_request(suffix)
    action = "admin.raw_collection." + ("purpose_decide" if suffix == "decisions" else "legal_hold")

    def verified_identity(_request, *, action):
        db.events.append("proof")
        return IDENTITY

    def authorize(session, token, requested_action, **context):
        assert session is db and token == "test-only-token" and requested_action == action
        assert context["path"] == request.url.path and context["method"] == "POST"
        assert context["credential_issuer_key"] == "test-only-key"
        assert context["runtime_totp_secret"] == "configured-test-factor"
        assert context["device_id"] == IDENTITY.device_id
        db.events.append("authority_lock")
        if authority in {"revoked", "audit_unavailable"}:
            raise AdminSecurityError("admin_session_invalid", "revoked", status_code=401)
        if authority == "different_session":
            return LifecycleAdminIdentity(
                IDENTITY.admin_id, uuid.uuid4(), IDENTITY.device_id, IDENTITY.correlation_id
            )
        return IDENTITY

    def audit(**context):
        assert context["reason"] == "admin_session_invalid"
        db.events.append("denial_audit")
        if authority == "audit_unavailable":
            raise AdminSecurityStoreUnavailable()

    monkeypatch.setattr(api, "_identity", verified_identity)
    monkeypatch.setattr(api, "authorize_admin_protected_work", authorize, raising=False)
    monkeypatch.setattr(api, "load_admin_credential_issuer_key_for_settings", lambda _: "test-only-key", raising=False)
    monkeypatch.setattr(api, "record_admin_security_denial", audit, raising=False)
    endpoint = next(route.endpoint for route in api.create_router().routes if route.path.endswith("/" + suffix))
    response = Response()
    if authority != "current":
        with pytest.raises(HTTPException) as denied:
            endpoint(request=request, response=response, collection_id=COLLECTION_ID, payload=_raw_payload(suffix), db=db)
        if authority == "audit_unavailable":
            assert denied.value.status_code == 503
            assert denied.value.detail == {
                "code": "admin_security_store_unavailable",
                "message": "Administrator security state is temporarily unavailable.",
            }
            assert denied.value.headers is None
        else:
            assert denied.value.status_code == 401
        assert db.events == ["proof", "authority_lock", "rollback", "denial_audit"]
        assert db.committed == []
    else:
        result = endpoint(request=request, response=response, collection_id=COLLECTION_ID, payload=_raw_payload(suffix), db=db)
        assert response.status_code == 201 and result.admin_id == IDENTITY.admin_id
        assert db.events[:3] == ["proof", "authority_lock", "collection"]
        assert db.events[-3:] == ["add", "commit", "refresh"]
        assert len(db.committed) == 1


class DatasetDb:
    def __init__(self, scalar_results=(), artifacts=()):
        self.results = iter(scalar_results)
        self.artifacts = artifacts
        self.events = []
        self.added = []

    def scalar(self, statement):
        self.events.append(("read", str(statement)))
        return next(self.results)

    def scalars(self, statement):
        return SimpleNamespace(all=lambda: self.artifacts)

    def execute(self, statement, params):
        self.events.append(("lock", params))

    def add(self, row):
        row.id = uuid.uuid4()
        self.added.append(row)
        self.events.append(("add", type(row).__name__))

    def flush(self):
        self.events.append(("flush", None))

    def commit(self):
        self.events.append(("commit", None))

    def refresh(self, row):
        pass


def _consent(monkeypatch, db, *, withdrawn=False):
    def current(session, **kwargs):
        assert session is db
        subject = kwargs["privacy_subject_hmac"]
        generation = kwargs["account_generation"]
        key = f"walksafe-privacy-subject-v2\n{subject}\n{generation}"
        assert ("lock", {"lock_key": key}) in db.events
        db.events.append(("consent", subject))
        if withdrawn:
            raise training.TrainingDatasetLifecycleError("training consent is withdrawn")
    monkeypatch.setattr(training, "_require_current_consent", current)


@pytest.mark.parametrize("withdrawn", [False, True])
def test_registration_locks_subject_before_consent_and_storage_check(tmp_path, monkeypatch, withdrawn):
    source_id, decision_id = uuid.uuid4(), uuid.uuid4()
    decision = SimpleNamespace(
        id=decision_id, collection_id=COLLECTION_ID, scope="TRAINING", decision="APPROVED",
        deidentification_receipt_sha256="e" * 64, training_consent_receipt_sha256="c" * 64,
    )
    collection = SimpleNamespace(collection_id=COLLECTION_ID, privacy_subject_hmac="a" * 64, account_generation=2)
    source = SimpleNamespace(object_id=source_id, sha256="d" * 64)
    db = DatasetDb((decision, source))
    db.get = lambda model, _: decision if model is RawCollectionPurposeDecision else collection
    _consent(monkeypatch, db, withdrawn=withdrawn)
    envelope = b"test encrypted envelope"
    (tmp_path / "artifact.bin").write_bytes(envelope)
    kwargs = dict(
        training_root=tmp_path, decision_id=decision_id, source_object_id=source_id,
        kind="METADATA", content_type="application/json", content_sha256="f" * 64,
        content_size=1, storage_name="artifact.bin", key_id="test-key", nonce=b"n" * 12,
        envelope_sha256=hashlib.sha256(envelope).hexdigest(), envelope_size=len(envelope),
    )
    if withdrawn:
        with pytest.raises(training.TrainingDatasetLifecycleError, match="withdrawn"):
            training.register_sanitized_artifact(db, **kwargs)
        assert db.added == [] and ("commit", None) not in db.events
    else:
        row = training.register_sanitized_artifact(db, **kwargs)
        assert isinstance(row, ApprovedTrainingArtifact)
        assert db.events[-3:] == [("consent", "a" * 64), ("add", "ApprovedTrainingArtifact"), ("commit", None)]


@pytest.mark.parametrize("withdrawn", [False, True])
def test_dataset_approval_locks_unique_subjects_in_order_before_any_consent(monkeypatch, withdrawn):
    artifacts = [SimpleNamespace(
        id=uuid.uuid4(), privacy_subject_hmac=subject, account_generation=generation,
        consent_receipt_sha256="c" * 64, content_sha256="d" * 64,
        source_collection_id=COLLECTION_ID, source_object_id=uuid.uuid4(),
    ) for subject, generation in [("b" * 64, 2), ("a" * 64, 1), ("b" * 64, 2)]]
    db = DatasetDb((None, NOW, NOW + timedelta(days=1096)), artifacts)
    _consent(monkeypatch, db, withdrawn=withdrawn)
    kwargs = dict(
        dataset_id=uuid.uuid4(), expected_parent_revision_id=None, manifest_sha256="e" * 64,
        members=[training.DatasetMemberInput(a.id, "train") for a in artifacts],
        admin_id=IDENTITY.admin_id, correlation_id=IDENTITY.correlation_id,
    )
    if withdrawn:
        with pytest.raises(training.TrainingDatasetLifecycleError, match="withdrawn"):
            training.approve_dataset_revision(db, **kwargs)
        assert db.added == []
    else:
        training.approve_dataset_revision(db, **kwargs)
        assert db.events[-1] == ("commit", None)
    first_consent = next(i for i, event in enumerate(db.events) if event[0] == "consent")
    privacy_locks = [event[1]["lock_key"] for event in db.events[:first_consent]
                     if event[0] == "lock" and "lock_key" in event[1]]
    assert privacy_locks == [f"walksafe-privacy-subject-v2\n{'a' * 64}\n1",
                             f"walksafe-privacy-subject-v2\n{'b' * 64}\n2"]
