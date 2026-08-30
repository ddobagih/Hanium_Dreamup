from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import importlib
import json
import uuid

import pytest
from pydantic import ValidationError

from backend.app.config import _has_minimum_privacy_hmac_bytes
from backend.app.field_test_security import (
    FieldTestAccess,
    _privacy_json_object,
    create_actor_assertion,
    create_privacy_deletion_assertion,
    verify_actor_assertion,
    verify_privacy_deletion_assertion,
)
from backend.app.schemas import (
    AccountDeletionRequestV2,
    AccountDeletionStatusV2,
    DeviceDeletionEvidenceV2,
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    PrivacyConsentEventV2,
)
from backend.app.services.privacy_lifecycle import (
    DELETION_ITEM_KEYS,
    DELETION_ITEM_STATES,
    DELETION_OVERALL_STATES,
    EXTERNAL_ITEM_KEYS,
    ITEM_SLA,
    PrivacyLifecycleError,
    bind_or_verify_privacy_hmac_key,
    bound_deletion_access_digest,
    deletion_evidence_sha256,
    deletion_request_body_sha256,
    overall_deletion_status,
    privacy_subject_hmac,
)


PRIVACY_SECRET = "privacy-hmac-test-secret-with-independent-boundary"
GATEWAY_SECRET = "gateway-session-test-secret-with-independent-boundary"


def _request() -> AccountDeletionRequestV2:
    return AccountDeletionRequestV2(
        schema_version="walksafe.account-deletion-request.v2",
        request_id="delete_request_0001",
        client_revision=1,
        confirmation="DELETE_MY_ACCOUNT",
    )


def test_exact_inventory_states_and_slas() -> None:
    assert DELETION_ITEM_KEYS == (
        "device_untransmitted_data",
        "server_originals",
        "server_quarantine",
        "server_copies",
        "report_records",
        "training_datasets",
        "training_labels",
        "derived_artifacts",
        "backups",
    )
    assert DELETION_ITEM_STATES == {
        "PENDING",
        "IN_PROGRESS",
        "EXTERNAL_PENDING",
        "RETRY_WAIT",
        "LEGAL_HOLD",
        "FAILED",
        "COMPLETED",
        "NOT_APPLICABLE",
    }
    assert DELETION_OVERALL_STATES == {
        "PROCESSING",
        "RETRY_WAIT",
        "PARTIAL",
        "RESTRICTED",
        "FAILED",
        "COMPLETED",
    }
    assert EXTERNAL_ITEM_KEYS == {
        "device_untransmitted_data",
        "training_datasets",
        "training_labels",
        "derived_artifacts",
        "backups",
    }
    assert ITEM_SLA == {
        "device_untransmitted_data": timedelta(hours=24),
        "server_originals": timedelta(hours=168),
        "server_quarantine": timedelta(hours=168),
        "server_copies": timedelta(hours=168),
        "report_records": timedelta(hours=168),
        "training_datasets": timedelta(hours=720),
        "training_labels": timedelta(hours=720),
        "derived_artifacts": timedelta(hours=720),
        "backups": timedelta(hours=840),
    }


def test_overall_status_never_completes_external_pending_or_missing_inventory() -> None:
    states = {key: "COMPLETED" for key in DELETION_ITEM_KEYS}
    states["device_untransmitted_data"] = "EXTERNAL_PENDING"
    assert overall_deletion_status(states) == "PARTIAL"
    states = {key: "PENDING" for key in DELETION_ITEM_KEYS}
    assert overall_deletion_status(states) == "PROCESSING"
    states["server_originals"] = "COMPLETED"
    assert overall_deletion_status(states) == "PARTIAL"
    states["device_untransmitted_data"] = "RETRY_WAIT"
    assert overall_deletion_status(states) == "RETRY_WAIT"
    states["device_untransmitted_data"] = "FAILED"
    assert overall_deletion_status(states) == "FAILED"
    states["device_untransmitted_data"] = "LEGAL_HOLD"
    assert overall_deletion_status(states) == "RESTRICTED"
    states = {key: "COMPLETED" for key in DELETION_ITEM_KEYS}
    states["device_untransmitted_data"] = "NOT_APPLICABLE"
    assert overall_deletion_status(states) == "COMPLETED"
    del states["backups"]
    with pytest.raises(PrivacyLifecycleError) as exc:
        overall_deletion_status(states)
    assert exc.value.code == "account_deletion_inventory_invalid"


def test_request_and_device_evidence_are_exact_strict_contracts() -> None:
    payload = _request()
    digest = deletion_request_body_sha256(payload)
    assert len(digest) == 64
    with pytest.raises(ValidationError):
        AccountDeletionRequestV2.model_validate(
            {**payload.model_dump(), "actor_id": "must-not-be-in-body"}
        )


def test_consent_contract_accepts_only_the_approved_exact_version_set() -> None:
    valid = {
        "schema_version": "walksafe.privacy-consent-event.v2",
        "installation_id": "installation_0001",
        "request_id": "consent_request_0001",
        "client_revision": 1,
        "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
        "item_versions": PRIVACY_CONSENT_ITEM_VERSIONS,
        "raw_source_collection": True,
        "automatic_reporting": False,
        "mobile_network_transfer": False,
        "training_reuse": False,
        "expected_previous_backend_receipt_sha256": None,
    }
    consent = PrivacyConsentEventV2.model_validate(valid)
    assert consent.item_versions.model_dump() == PRIVACY_CONSENT_ITEM_VERSIONS

    invalid_payloads = []
    missing = dict(PRIVACY_CONSENT_ITEM_VERSIONS)
    missing.pop("training_reuse")
    invalid_payloads.append({**valid, "item_versions": missing})
    invalid_payloads.append(
        {
            **valid,
            "item_versions": {
                **PRIVACY_CONSENT_ITEM_VERSIONS,
                "unexpected": "FP-013-UNAPPROVED-1.0.0",
            },
        }
    )
    invalid_payloads.append({**valid, "policy_version": "FP-013-2.0.0"})
    invalid_payloads.append(
        {
            **valid,
            "item_versions": {
                **PRIVACY_CONSENT_ITEM_VERSIONS,
                "training_reuse": "FP-013-TRAINING-2.0.0",
            },
        }
    )
    for invalid in invalid_payloads:
        with pytest.raises(ValidationError):
            PrivacyConsentEventV2.model_validate(invalid)


def test_hmac_binding_refuses_a_receipt_only_store_when_binding_is_missing() -> None:
    class ReceiptOnlySession:
        def __init__(self) -> None:
            self.queried_tables: list[str] = []
            self.added = False

        def get(self, _model, _key, **_kwargs):
            return None

        def execute(self, _statement, _parameters=None):
            return None

        def scalar(self, statement):
            table_names = [table.name for table in statement.get_final_froms()]
            self.queried_tables.extend(table_names)
            return object() if "account_deletion_receipts" in table_names else None

        def add(self, _value) -> None:
            self.added = True

    db = ReceiptOnlySession()
    with pytest.raises(PrivacyLifecycleError) as missing_binding:
        bind_or_verify_privacy_hmac_key(
            db,  # type: ignore[arg-type]
            secret=PRIVACY_SECRET,
            key_version=1,
        )
    assert missing_binding.value.code == "privacy_hmac_key_binding_missing"
    assert "account_deletion_receipts" in db.queried_tables
    assert db.added is False


def test_device_evidence_shared_canonical_digest_vector() -> None:
    evidence = DeviceDeletionEvidenceV2.model_validate(
        {
            "schema_version": "walksafe.device-deletion-evidence.v2",
            "request_id": "account_delete_request_0001",
            "tombstone_id": "tombstone-00000001",
            "request_receipt_sha256": "1" * 64,
            "installation_id": "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
            "evidence_id": "device-evidence-0001",
            "client_revision": 1,
            "expected_status_revision": 3,
            "item": "device_untransmitted_data",
            "result": "DELETED",
            "completed_at": "2026-07-25T12:05:00Z",
            "evidence_sha256": "0" * 64,
        }
    )
    assert deletion_evidence_sha256(evidence) == (
        "26de7610980e8cd30277b6864db213758e873d139e654eb447d40dd35496d335"
    )
    for invalid in (
        "2026-07-25T12:05:00.000Z",
        "2026-07-25T12:05:00+00:00",
        "2026-07-25T21:05:00+09:00",
    ):
        with pytest.raises(ValidationError):
            DeviceDeletionEvidenceV2.model_validate(
                {**evidence.model_dump(mode="json"), "completed_at": invalid}
            )
    with pytest.raises(ValidationError):
        DeviceDeletionEvidenceV2.model_validate(
            {
                "schema_version": "walksafe.device-deletion-evidence.v2",
                "request_id": "account_delete_request_0001",
                "tombstone_id": str(uuid.uuid4()),
                "request_receipt_sha256": "a" * 64,
                "installation_id": "install_0001",
                "evidence_id": "evidence_0001",
                "client_revision": 1,
                "expected_status_revision": 1,
                "item": "device_untransmitted_data",
                "result": "DELETED",
                "completed_at": "2026-08-09T12:00:00+09:00",
                "evidence_sha256": "b" * 64,
            }
        )


def test_privacy_middleware_json_parser_rejects_duplicate_keys() -> None:
    assert _privacy_json_object(
        b'{"request_id":"delete_request_0001","request_id":"delete_request_0002"}'
    ) is None


def test_subject_and_capability_digests_are_domain_and_binding_specific() -> None:
    subject_one = privacy_subject_hmac("actor.one", 1, PRIVACY_SECRET)
    subject_two = privacy_subject_hmac("actor.one", 2, PRIVACY_SECRET)
    assert subject_one != subject_two
    tombstone = uuid.uuid4()
    pre_digest = "a" * 64
    bound = bound_deletion_access_digest(
        privacy_subject=subject_one,
        account_generation=1,
        request_id="delete_request_0001",
        tombstone_id=tombstone,
        access_pre_digest=pre_digest,
        secret=PRIVACY_SECRET,
    )
    assert bound != subject_one
    assert bound != bound_deletion_access_digest(
        privacy_subject=subject_one,
        account_generation=1,
        request_id="delete_request_0002",
        tombstone_id=tombstone,
        access_pre_digest=pre_digest,
        secret=PRIVACY_SECRET,
    )


def test_legacy_migration_subject_digest_matches_runtime_generation_one() -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202608090001_fp046_privacy_lifecycle"
    )
    actor_id = "legacy.gateway.actor"
    assert migration._legacy_privacy_subject_hmac(
        actor_id,
        PRIVACY_SECRET,
    ) == privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    assert all(
        migration._legacy_actor_is_unbound(actor_id)
        for actor_id in ("unknown", "UNKNOWN", "System", "ANONYMOUS")
    )
    assert migration._legacy_actor_is_unbound("legacy.gateway.actor") is False


def test_privacy_hmac_minimum_uses_utf8_bytes_across_runtime_and_config() -> None:
    below_minimum = "가" * 10
    at_minimum = "가" * 11

    assert len(at_minimum) < 32
    assert len(below_minimum.encode("utf-8")) == 30
    assert len(at_minimum.encode("utf-8")) == 33
    assert _has_minimum_privacy_hmac_bytes(below_minimum) is False
    assert _has_minimum_privacy_hmac_bytes(at_minimum) is True
    with pytest.raises(PrivacyLifecycleError) as too_short:
        privacy_subject_hmac("utf8.actor", 1, below_minimum)
    assert too_short.value.code == "privacy_lifecycle_unavailable"
    assert len(privacy_subject_hmac("utf8.actor", 1, at_minimum)) == 64


def test_service_and_deletion_assertions_cannot_cross_authorize() -> None:
    body = json.dumps(_request().model_dump(), separators=(",", ":")).encode()
    body_sha256 = hashlib.sha256(body).hexdigest()
    deletion_assertion = create_privacy_deletion_assertion(
        actor_id="field.user",
        account_generation=3,
        request_id="delete_request_0001",
        tombstone_id=None,
        access_pre_digest="a" * 64,
        method="POST",
        path="/privacy/account-deletions",
        body_sha256=body_sha256,
        secret=GATEWAY_SECRET,
        issued_at=1_000,
    )
    assert verify_privacy_deletion_assertion(
        deletion_assertion,
        actor_id="field.user",
        account_generation=3,
        request_id="delete_request_0001",
        tombstone_id=None,
        access_pre_digest="a" * 64,
        method="POST",
        path="/privacy/account-deletions",
        body_sha256=body_sha256,
        secret=GATEWAY_SECRET,
        now=1_010,
    )
    assert not verify_actor_assertion(
        deletion_assertion,
        actor_id="field.user",
        account_generation=3,
        access=FieldTestAccess.FIELD,
        secret=GATEWAY_SECRET,
        now=1_010,
    )
    service_assertion = create_actor_assertion(
        "field.user",
        FieldTestAccess.FIELD,
        GATEWAY_SECRET,
        account_generation=3,
        issued_at=1_000,
    )
    assert verify_actor_assertion(
        service_assertion,
        actor_id="field.user",
        account_generation=3,
        access=FieldTestAccess.FIELD,
        secret=GATEWAY_SECRET,
        now=1_010,
    )
    assert not verify_privacy_deletion_assertion(
        service_assertion,
        actor_id="field.user",
        account_generation=3,
        request_id="delete_request_0001",
        tombstone_id=None,
        access_pre_digest="a" * 64,
        method="POST",
        path="/privacy/account-deletions",
        body_sha256=body_sha256,
        secret=GATEWAY_SECRET,
        now=1_010,
    )


def test_status_contract_requires_all_nine_items_in_canonical_order() -> None:
    now = datetime.now(timezone.utc)
    base = {
        "schema_version": "walksafe.account-deletion-status.v2",
        "request_id": "delete_request_0001",
        "client_revision": 1,
        "revision": 1,
        "accepted_at": now,
        "updated_at": now,
        "account_generation": 1,
        "tombstone_id": str(uuid.uuid4()),
        "request_receipt_sha256": "a" * 64,
        "overall_status": "PARTIAL",
        "completion_receipt_sha256": None,
    }
    items = [
        {
            "key": key,
            "status": "EXTERNAL_PENDING" if key in EXTERNAL_ITEM_KEYS else "PENDING",
            "item_revision": 1,
            "due_at": now + ITEM_SLA[key],
            "updated_at": now,
            "evidence_sha256": None,
            "disposition_basis": None,
            "retry_after": None,
            "restriction_reason": None,
            "legal_hold_review_at": None,
            "legal_hold_contact": None,
            "terminal_at": None,
        }
        for key in DELETION_ITEM_KEYS
    ]
    assert AccountDeletionStatusV2.model_validate({**base, "items": items}).items[4].key == "report_records"
    with pytest.raises(ValidationError):
        AccountDeletionStatusV2.model_validate(
            {**base, "items": [items[1], items[0], *items[2:]]}
        )
