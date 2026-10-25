"""Real PostgreSQL fences at RAW/admin and training/consent commit boundaries.

The RAW calls resolve endpoints from create_app and supply the already-verified
middleware context. Device-proof cryptography and HTTP middleware are outside
this test; session authorization, revocation, lifecycle writes, and locks are
real production operations under the migrated runtime role.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from copy import copy
from datetime import UTC, datetime, timedelta
import hashlib
import os
from queue import Queue
import threading
import time
from types import SimpleNamespace
import uuid

from fastapi import HTTPException, Response
import pyotp
import pytest
from sqlalchemy import create_engine, event, func, select, text
from sqlalchemy.orm import sessionmaker
from starlette.requests import Request

from backend.app import database as database_api
from backend.app.config import get_settings
from backend.app.main import create_app
from backend.app.models import (
    AdminSecurityAudit, AdminSecuritySession, ApprovedTrainingArtifact,
    PrivacyConsentEvent, RawCollectionLegalHoldEvent, RawCollectionPurposeDecision,
    TrainingDatasetRevision,
)
from backend.app.schemas import RawLegalHoldRequestV1, RawPurposeDecisionRequestV1
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof
from backend.app.services.admin_security import (
    ADMIN_APP_KIND, ADMIN_AUDIENCE, ADMIN_ROLE, AdminSecurityService,
    authorize_admin_bearer, provision_admin_security,
)
from backend.app.services.privacy_lifecycle import (
    PRIVACY_CONSENT_ITEM_VERSIONS, PRIVACY_CONSENT_POLICY_VERSION,
    privacy_subject_hmac, record_consent_event,
)
from backend.app.services.raw_collection_ingest import RawCollectionAdmission
from backend.app.services.raw_collection_lifecycle import (
    LifecycleAdminIdentity, record_purpose_decision,
)
from backend.app.services.training_dataset_lifecycle import (
    DatasetMemberInput, TrainingDatasetLifecycleError, approve_dataset_revision,
    register_sanitized_artifact, require_current_dataset_revision,
)
from test_admin_sweep_w22 import _pg_environment
from test_fp046_postgres_integration import PRIVACY_SECRET, _record_consent, _session_factory
from test_raw_collection_postgres_integration import (
    _authorize_raw_write, _manifest, _prepare_ready_raw_collection, _storage,
)

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


@pytest.fixture
def pg(clean_test_storage):
    clean_test_storage()
    engine, owner = _session_factory()
    runtime_engine = create_engine(engine.url, pool_pre_ping=True)

    @event.listens_for(runtime_engine, "connect")
    def as_runtime(connection, _record):
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            cursor.execute("SET statement_timeout = '15s'")
        connection.commit()

    runtime = sessionmaker(bind=runtime_engine, expire_on_commit=False)
    with runtime() as db:
        assert db.scalar(text("SELECT current_user")) == "walksafe_backend_runtime"
    try:
        yield SimpleNamespace(owner=owner, runtime=runtime)
    finally:
        runtime_engine.dispose()
        engine.dispose()


def _serialized_race(pg, first, second):
    """Pause the real first Session immediately before its production commit."""
    pending_commit = threading.Event()
    release_commit = threading.Event()
    first_pid = Queue()
    second_pid = Queue()

    def run_first():
        with pg.runtime() as db:
            first_pid.put(db.scalar(text("SELECT pg_backend_pid()")))

            def before_commit(_db):
                pending_commit.set()
                assert release_commit.wait(12), "first transaction commit was not released"

            event.listen(db, "before_commit", before_commit, once=True)
            return first(db)

    def run_second():
        with pg.runtime() as db:
            second_pid.put(db.scalar(text("SELECT pg_backend_pid()")))
            return second(db)

    with ThreadPoolExecutor(max_workers=2) as pool:
        first_future = pool.submit(run_first)
        try:
            assert pending_commit.wait(8), (
                first_future.result(timeout=1) if first_future.done()
                else "first production operation did not reach commit"
            )
            owner_pid = first_pid.get(timeout=2)
            second_future = pool.submit(run_second)
            contender_pid = second_pid.get(timeout=2)
            deadline = time.monotonic() + 6
            blockers = []
            with pg.owner() as observer:
                while time.monotonic() < deadline:
                    blockers = observer.scalar(
                        text("SELECT pg_blocking_pids(:pid)"), {"pid": contender_pid}
                    )
                    if owner_pid in blockers:
                        break
                    if second_future.done():
                        second_future.result()  # expose a product or fixture error
                        pytest.fail("contender committed without waiting for the protected transaction")
                    time.sleep(0.01)
            assert owner_pid in blockers, "PostgreSQL did not report the intended transaction blocker"
            assert not first_future.done() and not second_future.done()
        finally:
            release_commit.set()
        return first_future.result(timeout=18), second_future.result(timeout=18)


def _quarantined_source(pg, tmp_path):
    actor = "followup." + uuid.uuid4().hex
    recording = _record_consent(pg.runtime, actor_id=actor, training_reuse=True)
    admission = RawCollectionAdmission(
        actor_id=actor, account_generation=1,
        privacy_subject_hmac=privacy_subject_hmac(actor, 1, PRIVACY_SECRET),
        purpose="GENERAL_RAW", consent_receipt_sha256=recording.event.receipt_sha256,
    )
    content = b"isolated raw lifecycle transaction fixture"
    manifest = _manifest(admission=admission, content=content)
    storage = _storage(tmp_path / ("raw-" + uuid.uuid4().hex))
    payload = _prepare_ready_raw_collection(
        pg.runtime, storage=storage, admission=admission, manifest=manifest, content=content,
    )
    with pg.runtime() as db:
        receipt = storage.commit(
            db, _authorize_raw_write(db, admission), payload, walk_id=manifest.walk_id,
        )
    assert receipt.schema_version == "walksafe.raw-collection-receipt.v2"
    return SimpleNamespace(
        actor=actor, collection_id=uuid.UUID(manifest.collection_id),
        object_id=uuid.UUID(manifest.objects[0].object_id),
        consent_receipt=recording.event.receipt_sha256,
    )


def _admin(pg, tmp_path, monkeypatch):
    fixture_engine, _, _, fixture_settings, totp, issuer = _pg_environment(
        tmp_path, device_proof_enabled=False,
    )
    fixture_engine.dispose()
    settings = copy(get_settings())
    settings.__dict__.update(vars(fixture_settings))
    settings.database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    now = datetime.now(UTC).replace(microsecond=0)
    with pg.owner() as db:
        provision_admin_security(
            db, admin_id=settings.admin_id, password="isolated lifecycle test password",
            totp_secret=totp, recovery_codes=["RECOVERY-FOLLOWUP-TEST-0001"],
            credential_issuer_key=issuer, now=now,
        )
    with pg.runtime() as db:
        grant = AdminSecurityService(db, settings).login(
            admin_id=settings.admin_id, password="isolated lifecycle test password",
            totp_code=pyotp.TOTP(totp).at(now), device_id="followup-admin-device",
            device_label="격리 검증 기기", source="127.0.0.1", now=now,
        )
    with pg.runtime() as db:
        identity = authorize_admin_bearer(
            db, grant.access_token, runtime_totp_secret=totp, credential_issuer_key=issuer,
            device_id="followup-admin-device", app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE, audience=ADMIN_AUDIENCE, now=now,
        )
    with pg.runtime() as db:
        attested = AdminSecurityService(db, settings).attest_recovery_custody(
            identity, custody_reference="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
            material_kind="RECOVERY_CODE", storage_location="OFF_PHONE",
            separate_encrypted_backup_confirmed=True, now=now,
        )
    assert attested["recovery_custody_state"] == "ATTESTED"
    monkeypatch.setattr(database_api, "SessionLocal", pg.runtime)
    return SimpleNamespace(settings=settings, app=create_app(settings), grant=grant, identity=identity)


def _raw_call(admin, source, suffix):
    path = f"/admin/raw-collections/{source.collection_id}/{suffix}"
    action = "admin.raw_collection." + ("purpose_decide" if suffix == "decisions" else "legal_hold")
    endpoint = next(route.endpoint for route in admin.app.routes if route.path.endswith("/{collection_id}/" + suffix))
    identity = admin.identity
    request = Request({
        "type": "http", "method": "POST", "path": path, "app": admin.app,
        "query_string": b"", "scheme": "https", "server": ("test.invalid", 443),
        "headers": [(b"authorization", ("Bearer " + admin.grant.access_token).encode()),
                    (b"x-walksafe-device-id", identity.device_id.encode()),
                    (b"x-walksafe-app-kind", ADMIN_APP_KIND.encode()),
                    (b"x-walksafe-role", ADMIN_ROLE.encode()),
                    (b"x-walksafe-audience", ADMIN_AUDIENCE.encode())],
    })
    request.state.admin_security_identity = identity
    request.state.admin_device_proof = VerifiedAdminDeviceProof(
        admin_id=identity.admin_id, device_id=identity.device_id, session_id=identity.session_id,
        challenge_id=uuid.uuid4(), correlation_id=uuid.uuid4(), action=action, purpose="ACTION",
        read_purpose=None, method="POST", path=path, body_sha256="a" * 64,
        query_sha256="b" * 64, device_key_marker="c" * 64, device_key_version=1,
    )
    common = dict(expected_revision=0, idempotency_key=uuid.uuid4(), reason="격리 동시성 검증")
    payload = (
        RawPurposeDecisionRequestV1(scope="REPORT", decision="APPROVED", **common)
        if suffix == "decisions" else RawLegalHoldRequestV1(
            action="APPLY", legal_basis="검증용 법적 근거", authority_reference="검증 참조",
            contact="검증 담당", expires_at=datetime.now(UTC) + timedelta(days=2), **common,
        )
    )

    def call(db):
        response = Response()
        try:
            endpoint(request=request, response=response, collection_id=source.collection_id, payload=payload, db=db)
            return response.status_code
        except HTTPException as exc:
            return exc
    return call, action


@pytest.mark.parametrize("suffix", ["decisions", "legal-holds"])
@pytest.mark.parametrize("revoke_first", [False, True])
def test_raw_endpoint_admin_revocation_is_serialized_through_commit(
    pg, tmp_path, monkeypatch, suffix, revoke_first,
):
    source = _quarantined_source(pg, tmp_path)
    admin = _admin(pg, tmp_path, monkeypatch)
    mutate, action = _raw_call(admin, source, suffix)

    def revoke(db):
        return AdminSecurityService(db, admin.settings).revoke_session(
            admin.identity, admin.identity.session_id,
        )

    if revoke_first:
        _, result = _serialized_race(pg, revoke, mutate)
        assert isinstance(result, HTTPException)
        assert result.status_code == 401 and result.detail["code"] == "admin_session_invalid"
    else:
        result, _ = _serialized_race(pg, mutate, revoke)
        assert result == 201
    model = RawCollectionPurposeDecision if suffix == "decisions" else RawCollectionLegalHoldEvent
    with pg.owner() as db:
        assert db.scalar(select(func.count()).select_from(model).where(model.collection_id == source.collection_id)) == (0 if revoke_first else 1)
        assert db.get(AdminSecuritySession, admin.identity.session_id).revoked_at is not None
        if revoke_first:
            assert db.scalar(select(func.count()).select_from(AdminSecurityAudit).where(
                AdminSecurityAudit.action == action, AdminSecurityAudit.outcome == "DENIED",
            )) == 1


def _training_registration(pg, tmp_path, source):
    with pg.runtime() as db:
        decision, created = record_purpose_decision(
            db, collection_id=source.collection_id,
            identity=LifecycleAdminIdentity("training-test-admin", uuid.uuid4(), "training-test-device", uuid.uuid4()),
            payload=RawPurposeDecisionRequestV1(
                scope="TRAINING", decision="APPROVED", expected_revision=0,
                idempotency_key=uuid.uuid4(), reason="검증용 학습 승인",
                training_consent_receipt_sha256=source.consent_receipt,
                deidentification_receipt_sha256="d" * 64, sanitized_manifest_sha256="e" * 64,
                target_dataset_id=uuid.uuid4(), exact_location_excluded=True,
                raw_audio_excluded=True, third_party_faces_excluded=True,
            ),
        )
    assert created
    root = tmp_path / "training"
    root.mkdir(mode=0o700)
    content = b"isolated-sanitized-envelope-transaction-evidence"
    name = uuid.uuid4().hex + ".bin"
    (root / name).write_bytes(content)

    def register(db):
        return register_sanitized_artifact(
            db, training_root=root, decision_id=decision.id, source_object_id=source.object_id,
            kind="METADATA", content_type="application/octet-stream", content_sha256="f" * 64,
            content_size=8, storage_name=name, key_id="test-key", nonce=b"t" * 12,
            envelope_sha256=hashlib.sha256(content).hexdigest(), envelope_size=len(content),
        )
    return register


def _withdraw(source):
    def withdraw(db):
        return record_consent_event(
            db, actor_id=source.actor, account_generation=1,
            installation_id="install_" + source.actor.rsplit(".", 1)[-1],
            request_id="withdraw_" + uuid.uuid4().hex, client_revision=2,
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS, raw_source_collection=True,
            automatic_reporting=True, mobile_network_transfer=False, training_reuse=False,
            secret=PRIVACY_SECRET, expected_previous_backend_receipt_sha256=source.consent_receipt,
        )
    return withdraw


@pytest.mark.parametrize("operation", ["register", "approve"])
@pytest.mark.parametrize("withdraw_first", [False, True])
def test_training_consent_withdrawal_is_serialized_through_commit(
    pg, tmp_path, operation, withdraw_first,
):
    source = _quarantined_source(pg, tmp_path)
    register = _training_registration(pg, tmp_path, source)
    dataset_id = uuid.uuid4()
    manifest = hashlib.sha256(str(dataset_id).encode()).hexdigest()
    if operation == "approve":
        with pg.runtime() as db:
            artifact = register(db)

        def mutate(db):
            return approve_dataset_revision(
                db, dataset_id=dataset_id, expected_parent_revision_id=None,
                manifest_sha256=manifest, members=[DatasetMemberInput(artifact.id, "train")],
                admin_id="training-test-admin", correlation_id=uuid.uuid4(),
            )
    else:
        mutate = register

    def checked_mutate(db):
        try:
            return mutate(db)
        except TrainingDatasetLifecycleError as exc:
            db.rollback()
            return exc

    withdraw = _withdraw(source)
    if withdraw_first:
        _, result = _serialized_race(pg, withdraw, checked_mutate)
        assert isinstance(result, TrainingDatasetLifecycleError)
        assert "consent is withdrawn" in str(result)
    else:
        result, _ = _serialized_race(pg, checked_mutate, withdraw)
        assert isinstance(result, ApprovedTrainingArtifact if operation == "register" else TrainingDatasetRevision)
    with pg.owner() as db:
        model = ApprovedTrainingArtifact if operation == "register" else TrainingDatasetRevision
        predicate = model.source_collection_id == source.collection_id if operation == "register" else model.dataset_id == dataset_id
        assert db.scalar(select(func.count()).select_from(model).where(predicate)) == (0 if withdraw_first else 1)
        latest = db.scalar(select(PrivacyConsentEvent).order_by(PrivacyConsentEvent.subject_revision.desc()).limit(1))
        assert latest.training_reuse is False
    if operation == "approve" and not withdraw_first:
        with pg.runtime() as db, pytest.raises(TrainingDatasetLifecycleError, match="consent is withdrawn"):
            require_current_dataset_revision(db, dataset_id=dataset_id, revision=1, manifest_sha256=manifest)
