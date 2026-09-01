from __future__ import annotations

from datetime import UTC, datetime, timedelta
import hashlib
import hmac
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from starlette.requests import Request

import backend.app.api.admin_raw_collections as admin_raw_collections_api
from backend.app.schemas import (
    RawCollectionReceiptV2,
    RawCollectionStatusV1,
    raw_collection_receipt_v2_sha256,
)
from scripts.walksafe_dataset_integrity import (
    DatasetIntegrityError,
    verify_approved_dataset_gate,
    verify_current_approved_dataset_database,
)
from scripts.manage_training_artifact_retention import (
    _decode_recovery_name,
    _recovery_name,
)
from backend.app.services.admin_security import classify_admin_operation


COLLECTION_ID = "11111111-1111-4111-8111-111111111111"
OBJECT_ID = "22222222-2222-4222-8222-222222222222"


def _receipt_v2() -> dict[str, object]:
    committed = datetime(2026, 8, 29, 1, 2, 3, tzinfo=UTC)
    payload: dict[str, object] = {
        "schema_version": "walksafe.raw-collection-receipt.v2",
        "collection_id": COLLECTION_ID,
        "manifest_sha256": "a" * 64,
        "purpose": "GENERAL_RAW",
        "persistence_marker": "DATABASE_AND_ENCRYPTED_CHUNK_STORE",
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": 4,
        "objects": [{
            "object_id": OBJECT_ID,
            "kind": "DETECTION",
            "size_bytes": 4,
            "sha256": "b" * 64,
            "chunk_count": 1,
        }],
        "retention_class": "RAW_QUARANTINE_14D",
        "committed_at": committed.isoformat().replace("+00:00", "Z"),
        "quarantine_expires_at": (committed + timedelta(days=14)).isoformat().replace("+00:00", "Z"),
    }
    payload["receipt_sha256"] = raw_collection_receipt_v2_sha256(payload)
    return payload


def test_receipt_v2_is_domain_separated_and_exactly_14_days() -> None:
    receipt = RawCollectionReceiptV2.model_validate(_receipt_v2())
    assert receipt.quarantine_expires_at == receipt.committed_at + timedelta(days=14)
    assert receipt.receipt_sha256 == raw_collection_receipt_v2_sha256(receipt)


def test_quarantined_status_requires_receipt_v2() -> None:
    receipt = _receipt_v2()
    status = RawCollectionStatusV1.model_validate({
        "schema_version": "walksafe.raw-collection-status.v1",
        "collection_id": COLLECTION_ID,
        "manifest_sha256": "a" * 64,
        "purpose": "GENERAL_RAW",
        "state": "QUARANTINED",
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": 4,
        "received_chunk_count": 1,
        "received_bytes": 4,
        "objects": [{
            "object_id": OBJECT_ID,
            "kind": "DETECTION",
            "sha256": "b" * 64,
            "chunk_count": 1,
            "received_chunk_count": 1,
            "size_bytes": 4,
            "received_bytes": 4,
            "missing_ranges": [],
        }],
        "receipt": receipt,
    })
    assert isinstance(status.receipt, RawCollectionReceiptV2)


def test_dataset_gate_rejects_expiry_and_manifest_mismatch(tmp_path: Path) -> None:
    manifest = tmp_path / "manifest.csv"
    manifest.write_text("header\n", encoding="utf-8")
    now = datetime(2026, 8, 29, tzinfo=UTC)
    body: dict[str, object] = {
        "schema_version": "walksafe.approved-training-dataset.v1",
        "dataset_id": str(uuid.uuid4()),
        "revision": 1,
        "state": "APPROVED",
        "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
        "member_set_sha256": "c" * 64,
        "dataset_expires_at": (now + timedelta(days=365)).isoformat().replace("+00:00", "Z"),
        "gate_expires_at": (now + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"),
        "withdrawn_at": None,
    }
    canonical = json.dumps(body, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("ascii")
    key = b"k" * 32
    body["receipt_sha256"] = hmac.new(
        key,
        b"walksafe/approved-training-dataset/v1\0" + canonical,
        hashlib.sha256,
    ).hexdigest()
    gate = tmp_path / "gate.json"
    gate.write_text(json.dumps(body), encoding="ascii")
    assert verify_approved_dataset_gate(
        gate, manifest_path=manifest, as_of=now, hmac_key=key
    )["revision"] == 1
    with pytest.raises(DatasetIntegrityError, match="expired"):
        verify_approved_dataset_gate(
            gate, manifest_path=manifest, as_of=now + timedelta(minutes=11), hmac_key=key
        )
    manifest.write_text("changed\n", encoding="utf-8")
    with pytest.raises(DatasetIntegrityError, match="manifest"):
        verify_approved_dataset_gate(
            gate, manifest_path=manifest, as_of=now, hmac_key=key
        )


def test_migration_preserves_legacy_and_adds_v2_constraints() -> None:
    migration = (
        Path(__file__).resolve().parents[1]
        / "alembic/versions/202608290014_raw_quarantine_training_lifecycle.py"
    ).read_text(encoding="utf-8")
    assert 'down_revision = "202608290013"' in migration
    assert "RAW_ORIGINAL_180D" in migration
    assert "RAW_QUARANTINE_14D" in migration
    assert "INTERVAL '14 days'" in migration
    assert "INTERVAL '3 years'" in migration


def test_training_artifact_recovery_name_round_trips_nested_storage() -> None:
    artifact_id = uuid.uuid4()
    name = _recovery_name(artifact_id, "sanitized/segment-1.wsta")
    assert _decode_recovery_name(name) == (
        artifact_id,
        "sanitized/segment-1.wsta",
    )


def test_current_dataset_database_gate_fails_without_database_authority() -> None:
    with pytest.raises(DatasetIntegrityError, match="DATABASE_URL"):
        verify_current_approved_dataset_database({}, database_url="")


def test_admin_raw_review_operations_are_high_risk_and_exactly_classified() -> None:
    collection_id = "11111111-1111-4111-8111-111111111111"
    decision = classify_admin_operation(
        "POST", f"/admin/raw-collections/{collection_id}/decisions"
    )
    hold = classify_admin_operation(
        "POST", f"/admin/raw-collections/{collection_id}/legal-holds"
    )
    listing = classify_admin_operation("GET", "/admin/raw-collections/quarantine")
    assert decision is not None and decision.action == "admin.raw_collection.purpose_decide" and decision.risk == "HIGH"
    assert hold is not None and hold.action == "admin.raw_collection.legal_hold" and hold.risk == "HIGH"
    assert listing is not None and listing.action == "admin.raw_collection.list"


def test_admin_quarantine_list_projects_latest_cas_revisions(monkeypatch) -> None:
    now = datetime.now(UTC)
    row = SimpleNamespace(
        collection_id=uuid.UUID(COLLECTION_ID),
        purpose="GENERAL_RAW",
        state="QUARANTINED",
        manifest_sha256="a" * 64,
        receipt_sha256="b" * 64,
        object_count=1,
        total_bytes=4,
        committed_at=now,
        quarantine_expires_at=now + timedelta(days=14),
    )
    report = SimpleNamespace(decision="APPROVED", revision=2)
    hold = SimpleNamespace(
        action="APPLY", revision=4, expires_at=now + timedelta(days=7)
    )

    class Rows:
        def all(self):
            return [row]

    class FakeDb:
        def __init__(self) -> None:
            self.latest = iter((report, None, hold))

        def scalars(self, _statement):
            return Rows()

        def scalar(self, _statement):
            return next(self.latest)

    monkeypatch.setattr(
        admin_raw_collections_api,
        "_identity",
        lambda _request, *, action: None,
    )
    endpoint = next(
        route.endpoint
        for route in admin_raw_collections_api.create_router().routes
        if route.path == "/admin/raw-collections/quarantine"
    )
    request = Request({
        "type": "http",
        "method": "GET",
        "path": "/admin/raw-collections/quarantine",
        "headers": [],
        "query_string": b"",
        "scheme": "https",
        "server": ("example.invalid", 443),
        "client": ("127.0.0.1", 1),
        "root_path": "",
    })

    result = endpoint(request=request, state="QUARANTINED", limit=100, db=FakeDb())
    summary = result.items[0]

    assert summary.report_decision == "APPROVED"
    assert summary.report_decision_revision == 2
    assert summary.training_decision is None
    assert summary.training_decision_revision == 0
    assert summary.legal_hold_active is True
    assert summary.legal_hold_revision == 4
