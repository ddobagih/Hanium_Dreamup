from __future__ import annotations

from types import SimpleNamespace
import uuid

import pytest
from pydantic import ValidationError

from asgi_client import ASGITestClient
from backend.app.api import privacy as privacy_api
from backend.app.field_test_security import (
    FieldTestAccess,
    _rate_limit_group,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
)
from backend.app.main import app
from backend.app.schemas import (
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    PrivacyConsentBootstrapV1,
    PrivacyConsentEventV2,
)
from backend.app.services import privacy_lifecycle
from backend.app.services import raw_collection_lifecycle
from backend.app.services import training_dataset_lifecycle
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError


PRIVACY_SECRET = "integrated-consent-v11-test-secret-boundary"
SIGNUP_VERSIONS = {
    "terms_of_service": "walksafe.terms-of-service.v1",
    "privacy_notice": "walksafe.privacy-notice.v1",
    "location_terms": "walksafe.location-terms.v1",
    "raw_original": "FP-013-RAW-1.1.0",
    "automatic_reporting": "FP-013-AUTO-1.1.0",
    "training_reuse": "FP-013-TRAINING-1.1.0",
}


class FakeSession:
    def __init__(self, scalars: list[object | None]) -> None:
        self._scalars = iter(scalars)
        self.scalar_count = 0
        self.added: list[object] = []
        self.commits = 0

    def scalar(self, _statement):
        self.scalar_count += 1
        return next(self._scalars)

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commits += 1

    def refresh(self, _value: object) -> None:
        return None


@pytest.fixture
def isolated_consent_service(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        privacy_lifecycle,
        "bind_or_verify_privacy_hmac_key",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        privacy_lifecycle,
        "privacy_subject_hmac",
        lambda *_args, **_kwargs: "a" * 64,
    )
    monkeypatch.setattr(
        privacy_lifecycle,
        "installation_subject_hmac",
        lambda *_args, **_kwargs: "b" * 64,
    )
    monkeypatch.setattr(
        privacy_lifecycle,
        "lock_privacy_subject_shared",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        privacy_lifecycle,
        "lock_privacy_subject_exclusive",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        privacy_lifecycle,
        "assert_report_ingest_active",
        lambda *_args, **_kwargs: None,
    )


def _event(**updates: object) -> SimpleNamespace:
    values = {
        "request_id": "consent_request_0001",
        "privacy_subject_hmac": "a" * 64,
        "account_generation": 1,
        "installation_subject_hmac": "b" * 64,
        "client_revision": 1,
        "subject_revision": 1,
        "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
        "item_versions": dict(PRIVACY_CONSENT_ITEM_VERSIONS),
        "raw_source_collection": True,
        "automatic_reporting": False,
        "mobile_network_transfer": False,
        "training_reuse": True,
        "receipt_sha256": "c" * 64,
    }
    values.update(updates)
    return SimpleNamespace(**values)


def _record(db: FakeSession, **updates: object):
    values = {
        "actor_id": "field.actor",
        "account_generation": 1,
        "installation_id": "installation_0001",
        "request_id": "consent_request_0001",
        "client_revision": 1,
        "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
        "item_versions": PRIVACY_CONSENT_ITEM_VERSIONS,
        "raw_source_collection": True,
        "automatic_reporting": False,
        "mobile_network_transfer": False,
        "training_reuse": True,
        "expected_previous_backend_receipt_sha256": None,
        "secret": PRIVACY_SECRET,
    }
    values.update(updates)
    return privacy_lifecycle.record_consent_event(db, **values)  # type: ignore[arg-type]


def test_v11_request_is_required_nullable_and_strict() -> None:
    payload = {
        "schema_version": "walksafe.privacy-consent-event.v2",
        "installation_id": "installation_0001",
        "request_id": "consent_request_0001",
        "client_revision": 1,
        "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
        "item_versions": PRIVACY_CONSENT_ITEM_VERSIONS,
        "raw_source_collection": True,
        "automatic_reporting": False,
        "mobile_network_transfer": False,
        "training_reuse": True,
        "expected_previous_backend_receipt_sha256": None,
    }
    assert PrivacyConsentEventV2.model_validate(payload).policy_version == (
        "FP-013-1.1.0"
    )
    for invalid in (
        {key: value for key, value in payload.items() if key != "expected_previous_backend_receipt_sha256"},
        {**payload, "raw_source_collection": "true"},
        {**payload, "expected_previous_backend_receipt_sha256": "A" * 64},
        {**payload, "policy_version": "FP-013-1.0.0"},
    ):
        with pytest.raises(ValidationError):
            PrivacyConsentEventV2.model_validate(invalid)


def test_bootstrap_response_enforces_exact_source_relationships() -> None:
    ready = PrivacyConsentBootstrapV1.model_validate(
        {
            "schema_version": "walksafe.integrated-consent-bootstrap.v1",
            "status": "READY",
            "source": "CURRENT_CONSENT",
            "installation_id": "installation_0001",
            "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
            "item_versions": PRIVACY_CONSENT_ITEM_VERSIONS,
            "client_revision_floor": 1,
            "selections": {
                "raw_source_collection": True,
                "automatic_reporting": False,
                "mobile_network_transfer": False,
                "training_reuse": True,
            },
            "source_receipt_sha256": "c" * 64,
            "expected_previous_backend_receipt_sha256": "c" * 64,
        }
    )
    assert set(ready.model_dump()) == {
        "schema_version",
        "status",
        "source",
        "installation_id",
        "policy_version",
        "item_versions",
        "client_revision_floor",
        "selections",
        "source_receipt_sha256",
        "expected_previous_backend_receipt_sha256",
    }
    with pytest.raises(ValidationError):
        PrivacyConsentBootstrapV1.model_validate(
            {**ready.model_dump(), "status": "RECONSENT_REQUIRED"}
        )


def test_bootstrap_uses_subject_latest_but_installation_floor(
    isolated_consent_service: None,
) -> None:
    latest_subject = _event(
        installation_subject_hmac="d" * 64,
        client_revision=7,
        subject_revision=4,
        receipt_sha256="e" * 64,
    )
    target_installation = _event(client_revision=2, subject_revision=2)
    db = FakeSession([latest_subject, target_installation])
    response = privacy_lifecycle.get_consent_bootstrap(
        db,  # type: ignore[arg-type]
        actor_id="field.actor",
        account_generation=1,
        installation_id="installation_0001",
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=SIGNUP_VERSIONS,
        secret=PRIVACY_SECRET,
    )
    assert response.status == "READY"
    assert response.source == "CURRENT_CONSENT"
    assert response.client_revision_floor == 2
    assert response.source_receipt_sha256 == "e" * 64
    assert response.expected_previous_backend_receipt_sha256 == "e" * 64


def test_bootstrap_marks_legacy_latest_stale_and_keeps_only_cas_receipt(
    isolated_consent_service: None,
) -> None:
    stale = _event(
        policy_version="FP-013-1.0.0",
        item_versions={
            "raw_source_collection": "FP-013-RAW-1.0.0",
            "automatic_reporting": "FP-013-AUTO-1.0.0",
            "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
            "training_reuse": "FP-013-TRAINING-1.0.0",
        },
    )
    response = privacy_lifecycle.get_consent_bootstrap(
        FakeSession([stale, stale]),  # type: ignore[arg-type]
        actor_id="field.actor",
        account_generation=1,
        installation_id="installation_0001",
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=SIGNUP_VERSIONS,
        secret=PRIVACY_SECRET,
    )
    assert response.status == "RECONSENT_REQUIRED"
    assert response.source == "NONE"
    assert response.selections is None
    assert response.source_receipt_sha256 is None
    assert response.expected_previous_backend_receipt_sha256 == "c" * 64


def test_bootstrap_uses_exact_current_signup_only_with_zero_events(
    isolated_consent_service: None,
) -> None:
    account = SimpleNamespace(id=uuid.uuid4())
    signup = SimpleNamespace(
        document_versions=dict(SIGNUP_VERSIONS),
        selections={
            "terms_of_service": True,
            "privacy_notice": True,
            "location_terms": True,
            "raw_original": True,
            "automatic_reporting": False,
            "training_reuse": True,
        },
        receipt_sha256="f" * 64,
    )
    response = privacy_lifecycle.get_consent_bootstrap(
        FakeSession([None, None, account, signup]),  # type: ignore[arg-type]
        actor_id="field.actor",
        account_generation=1,
        installation_id="installation_0001",
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=SIGNUP_VERSIONS,
        secret=PRIVACY_SECRET,
    )
    assert response.source == "SIGNUP_CONSENT"
    assert response.client_revision_floor == 0
    assert response.selections is not None
    assert response.selections.raw_source_collection is True
    assert response.selections.mobile_network_transfer is False
    assert response.source_receipt_sha256 == "f" * 64
    assert response.expected_previous_backend_receipt_sha256 is None

    stale_signup = SimpleNamespace(
        document_versions={
            **SIGNUP_VERSIONS,
            "training_reuse": "FP-013-TRAINING-1.0.0",
        },
        selections=signup.selections,
        receipt_sha256="e" * 64,
    )
    stale_response = privacy_lifecycle.get_consent_bootstrap(
        FakeSession([None, None, account, stale_signup]),  # type: ignore[arg-type]
        actor_id="field.actor",
        account_generation=1,
        installation_id="installation_0001",
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=SIGNUP_VERSIONS,
        secret=PRIVACY_SECRET,
    )
    assert stale_response.status == "RECONSENT_REQUIRED"
    assert stale_response.source == "NONE"


def test_record_cas_runs_before_installation_revision_validation(
    isolated_consent_service: None,
) -> None:
    latest = _event(receipt_sha256="d" * 64)
    db = FakeSession([None, latest])
    with pytest.raises(PrivacyLifecycleError) as conflict:
        _record(db, client_revision=99)
    assert conflict.value.code == "privacy_consent_previous_receipt_conflict"
    assert conflict.value.details == {}
    assert db.scalar_count == 2


def test_record_exact_replay_checks_original_previous_receipt_before_latest_cas(
    isolated_consent_service: None,
) -> None:
    previous = _event(subject_revision=1, receipt_sha256="d" * 64)
    existing = _event(subject_revision=2, client_revision=2)
    existing.receipt_sha256 = privacy_lifecycle._consent_receipt_sha256(
        account_generation=1,
        automatic_reporting=False,
        client_revision=2,
        installation_subject_hmac="b" * 64,
        item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
        mobile_network_transfer=False,
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        privacy_subject_hmac="a" * 64,
        raw_source_collection=True,
        request_id="consent_request_0001",
        subject_revision=2,
        training_reuse=True,
    )
    replay_db = FakeSession([existing, previous])
    replay = _record(
        replay_db,
        client_revision=2,
        expected_previous_backend_receipt_sha256="d" * 64,
    )
    assert replay.created is False
    assert replay_db.scalar_count == 2

    conflict_db = FakeSession([existing, previous])
    with pytest.raises(PrivacyLifecycleError) as conflict:
        _record(
            conflict_db,
            client_revision=2,
            expected_previous_backend_receipt_sha256="e" * 64,
        )
    assert conflict.value.code == "privacy_consent_request_conflict"


def test_direct_report_uses_current_consent_without_optional_raw_selection(
    isolated_consent_service: None,
) -> None:
    current = _event(
        raw_source_collection=False,
        automatic_reporting=False,
    )

    assert privacy_lifecycle.assert_report_consent_active(
        FakeSession([current]),  # type: ignore[arg-type]
        "a" * 64,
        1,
        automatic_reporting=False,
    ) is current
    with pytest.raises(PrivacyLifecycleError) as automatic:
        privacy_lifecycle.assert_report_consent_active(
            FakeSession([current]),  # type: ignore[arg-type]
            "a" * 64,
            1,
            automatic_reporting=True,
        )
    assert automatic.value.code == "automatic_reporting_consent_required"
    with pytest.raises(PrivacyLifecycleError) as missing:
        privacy_lifecycle.assert_report_consent_active(
            FakeSession([None]),  # type: ignore[arg-type]
            "a" * 64,
            1,
            automatic_reporting=False,
        )
    assert missing.value.code == "privacy_consent_reconsent_required"


def test_legacy_event_is_evidence_only_for_report_and_training_admission(
    isolated_consent_service: None,
) -> None:
    legacy = _event(
        policy_version="FP-013-1.0.0",
        item_versions={
            "raw_source_collection": "FP-013-RAW-1.0.0",
            "automatic_reporting": "FP-013-AUTO-1.0.0",
            "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
            "training_reuse": "FP-013-TRAINING-1.0.0",
        },
    )
    with pytest.raises(PrivacyLifecycleError) as report:
        privacy_lifecycle.assert_report_consent_active(
            FakeSession([legacy]),  # type: ignore[arg-type]
            "a" * 64,
            1,
            automatic_reporting=False,
        )
    assert report.value.code == "privacy_consent_reconsent_required"
    assert privacy_lifecycle.training_ingest_allowed(
        FakeSession([None, legacy]),  # type: ignore[arg-type]
        privacy_subject="a" * 64,
        account_generation=1,
    ) is False

    collection = SimpleNamespace(
        privacy_subject_hmac="a" * 64,
        account_generation=1,
    )
    with pytest.raises(raw_collection_lifecycle.RawCollectionLifecycleError):
        raw_collection_lifecycle._latest_training_consent(
            FakeSession([None, legacy]),  # type: ignore[arg-type]
            collection,
        )
    with pytest.raises(training_dataset_lifecycle.TrainingDatasetLifecycleError):
        training_dataset_lifecycle._require_current_consent(
            FakeSession([None, legacy]),  # type: ignore[arg-type]
            privacy_subject_hmac="a" * 64,
            account_generation=1,
            receipt_sha256=legacy.receipt_sha256,
        )


def test_bootstrap_security_is_actor_generation_and_privacy_rate_limited() -> None:
    path = "/privacy/consent-bootstrap"
    assert required_field_test_access(path, "GET") is FieldTestAccess.FIELD
    assert requires_actor_identity(path, "GET") is True
    assert requires_account_generation(path, "GET") is True
    assert _rate_limit_group(path, "GET") == "privacy"


def test_bootstrap_endpoint_is_exact_and_no_store(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def stub_bootstrap(_db, **kwargs):
        assert kwargs["actor_id"] == "field.actor"
        assert kwargs["account_generation"] == 2
        assert kwargs["installation_id"] == "installation_0001"
        return PrivacyConsentBootstrapV1(
            schema_version="walksafe.integrated-consent-bootstrap.v1",
            status="RECONSENT_REQUIRED",
            source="NONE",
            installation_id=kwargs["installation_id"],
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
            client_revision_floor=0,
            selections=None,
            source_receipt_sha256=None,
            expected_previous_backend_receipt_sha256=None,
        )

    monkeypatch.setattr(privacy_api, "get_consent_bootstrap", stub_bootstrap)
    response = ASGITestClient(app).get(
        "/privacy/consent-bootstrap",
        params={
            "installation_id": "installation_0001",
            "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
        },
        headers={
            "x-walksafe-actor-id": "field.actor",
            "x-walksafe-account-generation": "2",
        },
    )
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert set(response.json()) == {
        "schema_version",
        "status",
        "source",
        "installation_id",
        "policy_version",
        "item_versions",
        "client_revision_floor",
        "selections",
        "source_receipt_sha256",
        "expected_previous_backend_receipt_sha256",
    }

    stale_policy = ASGITestClient(app).get(
        "/privacy/consent-bootstrap",
        params={
            "installation_id": "installation_0001",
            "policy_version": "FP-013-1.0.0",
        },
        headers={
            "x-walksafe-actor-id": "field.actor",
            "x-walksafe-account-generation": "2",
        },
    )
    assert stale_policy.status_code == 422
    assert stale_policy.headers["cache-control"] == "no-store"
