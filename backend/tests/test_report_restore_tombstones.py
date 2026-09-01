from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
from typing import Literal
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
import pytest

from backend.app.services import report_restore_tombstones as subject


LEDGER_ID = uuid.UUID("00000000-0000-4000-8000-000000000010")
TOMBSTONE_ID = uuid.UUID("00000000-0000-4000-8000-000000000011")
REQUEST_ID = uuid.UUID("00000000-0000-4000-8000-000000000012")
REPORT_ID = uuid.UUID("00000000-0000-4000-8000-000000000013")
RESTORE_RUN_ID = uuid.UUID("00000000-0000-4000-8000-000000000014")
FENCE_ID = uuid.UUID("00000000-0000-4000-8000-000000000015")
SUBJECT_HMAC = "1" * 64
TARGET_IDENTITY = "2" * 64
SOURCE_IDENTITY = "3" * 64
BACKUP_MANIFEST_SHA256 = "4" * 64
RESTORE_RECEIPT_SHA256 = "5" * 64
MAINTENANCE_LOCK_IDENTITY = "6" * 64
REPORT_DELETION_LOCK_IDENTITY = "7" * 64
BOUNDARY = "walksafe-report-production"
CUTOFF = "2026-08-29T12:00:00Z"


def _public_bytes(private_key: Ed25519PrivateKey) -> bytes:
    return private_key.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )


def _signed_evidence() -> tuple[dict[str, bytes | str], subject.VerifiedReportTombstoneBundle]:
    private_key = Ed25519PrivateKey.generate()
    public_bytes = _public_bytes(private_key)
    ledger = subject.build_report_tombstone_ledger_bytes(
        (
            subject.ReportDeletionTombstoneRecord(
                tombstone_id=TOMBSTONE_ID,
                request_id=REQUEST_ID,
                report_id=REPORT_ID,
                privacy_subject_hmac=SUBJECT_HMAC,
                account_generation=3,
                request_status_version=2,
                external_copy_count=0,
                deleted_at=datetime(2026, 8, 29, 9, tzinfo=UTC),
            ),
        ),
        ledger_id=LEDGER_ID,
        data_boundary_id=BOUNDARY,
        privacy_hmac_key_version=7,
        cutoff_at=datetime(2026, 8, 29, 12, tzinfo=UTC),
    )
    head = subject.build_report_tombstone_head_bytes(
        ledger,
        issued_at=datetime(2026, 8, 29, 12, 0, 1, tzinfo=UTC),
        predecessor_head_sha256=None,
    )
    evidence: dict[str, bytes | str] = {
        "ledger": ledger,
        "ledger_signature": subject.build_signature_descriptor(
            ledger,
            public_key_bytes=public_bytes,
            signature=private_key.sign(subject.LEDGER_SIGNATURE_DOMAIN + ledger),
        ),
        "head": head,
        "head_signature": subject.build_signature_descriptor(
            head,
            public_key_bytes=public_bytes,
            signature=private_key.sign(subject.HEAD_SIGNATURE_DOMAIN + head),
        ),
        "key": subject.build_key_descriptor(public_bytes),
        "key_id": subject.ed25519_key_id(public_bytes),
        "head_sha256": hashlib.sha256(head).hexdigest(),
    }
    bundle = subject.verify_report_tombstone_bundle(
        ledger_bytes=ledger,
        ledger_signature_bytes=evidence["ledger_signature"],  # type: ignore[arg-type]
        trusted_head_bytes=head,
        head_signature_bytes=evidence["head_signature"],  # type: ignore[arg-type]
        key_descriptor_bytes=evidence["key"],  # type: ignore[arg-type]
        expected_key_id=evidence["key_id"],  # type: ignore[arg-type]
        expected_head_sha256=evidence["head_sha256"],  # type: ignore[arg-type]
    )
    return evidence, bundle


def _item(
    *,
    report_id: uuid.UUID = REPORT_ID,
    subject_hmac: str | None = SUBJECT_HMAC,
    generation: int | None = 3,
    row_present: bool = True,
    artifacts: int = 1,
) -> dict[str, object]:
    return {
        "account_generation": generation,
        "artifact_device_inode": "1:2",
        "artifact_sha256": "d" * 64,
        "artifact_size": 401,
        "artifact_storage_name": f"{report_id}.wse",
        "bound_artifact_count": artifacts,
        "image_content_type": "image/jpeg" if row_present else None,
        "image_path": f"/uploads/{report_id}.jpg" if row_present else None,
        "privacy_subject_hmac": subject_hmac,
        "report_id": str(report_id),
        "report_row_present": row_present,
    }


def _inventory_bytes(
    items: list[dict[str, object]],
    *,
    observed_at: str = "2026-08-29T11:00:00Z",
    source_backup_created_at: str = "2026-08-29T08:00:00Z",
    capture_status: str = "COMPLETE",
    covered_stores: list[str] | None = None,
) -> bytes:
    ordered = sorted(items, key=lambda item: str(item["report_id"]))
    return subject.canonical_json_bytes(
        {
            "backup_manifest_sha256": BACKUP_MANIFEST_SHA256,
            "backup_run_id": "backup-run-1",
            "capture_status": capture_status,
            "covered_stores": covered_stores or list(subject.REQUIRED_STORES),
            "data_boundary_id": BOUNDARY,
            "items": ordered,
            "observed_at": observed_at,
            "privacy_hmac_key_version": 7,
            "restore_receipt_sha256": RESTORE_RECEIPT_SHA256,
            "restore_run_id": str(RESTORE_RUN_ID),
            "schema_version": subject.INVENTORY_SCHEMA,
            "source_backup_created_at": source_backup_created_at,
            "source_identity_sha256": SOURCE_IDENTITY,
            "target_identity_sha256": TARGET_IDENTITY,
            "total_bound_artifact_count": sum(
                int(item["bound_artifact_count"]) for item in ordered
            ),
            "total_report_row_count": sum(
                1 for item in ordered if item["report_row_present"] is True
            ),
        }
    )


def _source_fence(
    bundle: subject.VerifiedReportTombstoneBundle,
    inventory: subject.RestoredReportInventory,
    *,
    fence_id: uuid.UUID = FENCE_ID,
    acquired_at: datetime = datetime(2026, 8, 29, 10, tzinfo=UTC),
) -> subject.ReportRestoreSourceFence:
    request = subject.ReportRestoreSourceFenceRequest(
        fence_id=fence_id,
        restore_run_id=inventory.restore_run_id,
        backup_run_id=inventory.backup_run_id,
        backup_manifest_sha256=inventory.backup_manifest_sha256,
        source_identity_sha256=inventory.source_identity_sha256,
        data_boundary_id=inventory.data_boundary_id,
        source_backend_pid=4321,
        maintenance_lock_identity_sha256=MAINTENANCE_LOCK_IDENTITY,
        maintenance_lock_device_inode="8:9",
        report_deletion_lock_key=subject.REPORT_DELETION_ADVISORY_LOCK_KEY,
        report_deletion_lock_identity_sha256=REPORT_DELETION_LOCK_IDENTITY,
        acquired_at=acquired_at,
    )
    return subject.ReportRestoreSourceFence(
        **{
            field: getattr(request, field)
            for field in request.__dataclass_fields__
        },
        source_fence_request_sha256=(
            subject.report_restore_source_fence_request_sha256(request)
        ),
        trusted_head_sha256=bundle.head_sha256,
    )


def _plan() -> tuple[
    subject.VerifiedReportTombstoneBundle,
    subject.RestoredReportInventory,
    subject.ReportRestoreReapplyPlan,
]:
    _evidence, bundle = _signed_evidence()
    inventory = subject.parse_restored_report_inventory(_inventory_bytes([_item()]))
    return (
        bundle,
        inventory,
        subject.plan_report_restore_reapply(
            bundle,
            inventory,
            source_fence=_source_fence(bundle, inventory),
        ),
    )


class _FenceVerifier:
    def __init__(self, *states: bool) -> None:
        self.states = list(states or (True, True))
        self.calls: list[subject.ReportRestoreSourceFence] = []

    def is_held(self, fence: subject.ReportRestoreSourceFence) -> bool:
        self.calls.append(fence)
        return self.states.pop(0) if self.states else True


class _AtomicTarget:
    def __init__(self) -> None:
        self.calls: list[str] = []
        self.receipt: bytes | None = None
        self.present = {REPORT_ID}
        self.snapshot: set[uuid.UUID] | None = None
        self.fail_reapply = False
        self.fail_rollback = False
        self.lose_commit_response = False
        self.reconciliation_required = False
        self.fail_reconciliation_after_commit = False
        self.fail_semantic_postcommit = False

    def load_receipt(self, _plan_sha256: str) -> bytes | None:
        self.calls.append("load_receipt")
        if self.reconciliation_required:
            raise subject.ReportRestoreTombstoneError(
                "restore_artifact_reconciliation_required",
                "injected artifact reconciliation failure",
            )
        return self.receipt

    def begin(self, _plan: subject.ReportRestoreReapplyPlan) -> None:
        self.calls.append("begin")
        self.snapshot = set(self.present)

    def reapply(
        self, action: subject.ReportRestoreReapplyAction
    ) -> Literal["DELETED", "ALREADY_ABSENT"]:
        self.calls.append(f"reapply:{action.report_id}")
        if self.fail_reapply:
            raise RuntimeError("injected reapply failure")
        if action.report_id not in self.present:
            return "ALREADY_ABSENT"
        self.present.remove(action.report_id)
        return "DELETED"

    def commit(self, receipt: bytes) -> None:
        self.calls.append("commit")
        self.receipt = receipt
        self.snapshot = None
        if self.fail_reconciliation_after_commit:
            self.reconciliation_required = True
            raise subject.ReportRestoreTombstoneError(
                "restore_artifact_reconciliation_required",
                "injected post-commit artifact reconciliation failure",
            )
        if self.lose_commit_response:
            raise RuntimeError("injected commit response loss")
        if self.fail_semantic_postcommit:
            raise subject.ReportRestorePostCommitError(
                "restore_target_not_isolated",
                "injected semantic post-commit fence failure",
            )

    def rollback(self) -> None:
        self.calls.append("rollback")
        if self.fail_rollback:
            raise RuntimeError("injected rollback failure")
        assert self.snapshot is not None
        self.present = self.snapshot
        self.snapshot = None


def test_signed_ledger_is_content_free_chained_and_exactly_head_bound() -> None:
    evidence, bundle = _signed_evidence()
    assert bundle.head_sequence == 1
    assert bundle.entries[0].report_id == REPORT_ID
    ledger = json.loads(evidence["ledger"])
    entry_keys = set(ledger["entries"][0])
    assert entry_keys == {
        "account_generation",
        "deleted_at",
        "entry_sha256",
        "external_copy_count",
        "previous_entry_sha256",
        "privacy_subject_hmac",
        "report_id",
        "request_id",
        "request_status_version",
        "sequence",
        "tombstone_id",
    }
    assert not any(
        fragment in key
        for key in entry_keys
        for fragment in ("content", "location", "path", "photo", "payload")
    )


def test_signature_tamper_and_trusted_head_rollback_are_fail_closed() -> None:
    evidence, _bundle = _signed_evidence()
    tampered = bytearray(evidence["ledger"])  # type: ignore[arg-type]
    tampered[-2] ^= 1
    with pytest.raises(subject.ReportRestoreTombstoneError) as invalid_signature:
        subject.verify_report_tombstone_bundle(
            ledger_bytes=bytes(tampered),
            ledger_signature_bytes=evidence["ledger_signature"],  # type: ignore[arg-type]
            trusted_head_bytes=evidence["head"],  # type: ignore[arg-type]
            head_signature_bytes=evidence["head_signature"],  # type: ignore[arg-type]
            key_descriptor_bytes=evidence["key"],  # type: ignore[arg-type]
            expected_key_id=evidence["key_id"],  # type: ignore[arg-type]
            expected_head_sha256=evidence["head_sha256"],  # type: ignore[arg-type]
        )
    assert invalid_signature.value.code == "restore_tombstone_signature_invalid"

    with pytest.raises(subject.ReportRestoreTombstoneError) as rollback:
        subject.verify_report_tombstone_bundle(
            ledger_bytes=evidence["ledger"],  # type: ignore[arg-type]
            ledger_signature_bytes=evidence["ledger_signature"],  # type: ignore[arg-type]
            trusted_head_bytes=evidence["head"],  # type: ignore[arg-type]
            head_signature_bytes=evidence["head_signature"],  # type: ignore[arg-type]
            key_descriptor_bytes=evidence["key"],  # type: ignore[arg-type]
            expected_key_id=evidence["key_id"],  # type: ignore[arg-type]
            expected_head_sha256="f" * 64,
        )
    assert rollback.value.code == "restore_tombstone_head_rollback"


def test_validly_signed_chain_gap_is_still_rejected() -> None:
    evidence, _bundle = _signed_evidence()
    ledger_document = json.loads(evidence["ledger"])
    ledger_document["entries"][0]["previous_entry_sha256"] = "f" * 64
    ledger = subject.canonical_json_bytes(ledger_document)
    private_key = Ed25519PrivateKey.generate()
    public_bytes = _public_bytes(private_key)
    with pytest.raises(subject.ReportRestoreTombstoneError) as chain:
        subject.verify_report_tombstone_bundle(
            ledger_bytes=ledger,
            ledger_signature_bytes=subject.build_signature_descriptor(
                ledger,
                public_key_bytes=public_bytes,
                signature=private_key.sign(subject.LEDGER_SIGNATURE_DOMAIN + ledger),
            ),
            trusted_head_bytes=evidence["head"],  # type: ignore[arg-type]
            head_signature_bytes=subject.build_signature_descriptor(
                evidence["head"],  # type: ignore[arg-type]
                public_key_bytes=public_bytes,
                signature=private_key.sign(
                    subject.HEAD_SIGNATURE_DOMAIN + evidence["head"]  # type: ignore[operator]
                ),
            ),
            key_descriptor_bytes=subject.build_key_descriptor(public_bytes),
            expected_key_id=subject.ed25519_key_id(public_bytes),
            expected_head_sha256=evidence["head_sha256"],  # type: ignore[arg-type]
        )
    assert chain.value.code == "restore_tombstone_chain_invalid"


def test_missing_ledger_key_or_head_is_fail_closed(tmp_path: Path) -> None:
    evidence, _bundle = _signed_evidence()
    names = {
        "ledger": "ledger.json",
        "ledger_signature": "ledger.sig.json",
        "head": "head.json",
        "head_signature": "head.sig.json",
        "key": "key.json",
    }
    for evidence_key, name in names.items():
        (tmp_path / name).write_bytes(evidence[evidence_key])  # type: ignore[arg-type]
    (tmp_path / names["head"]).unlink()
    with pytest.raises(subject.ReportRestoreTombstoneError) as missing:
        subject.load_verified_report_tombstone_bundle(
            ledger_path=tmp_path / names["ledger"],
            ledger_signature_path=tmp_path / names["ledger_signature"],
            trusted_head_path=tmp_path / names["head"],
            head_signature_path=tmp_path / names["head_signature"],
            key_descriptor_path=tmp_path / names["key"],
            expected_key_id=evidence["key_id"],  # type: ignore[arg-type]
            expected_head_sha256=evidence["head_sha256"],  # type: ignore[arg-type]
        )
    assert missing.value.code == "restore_tombstone_evidence_unavailable"


@pytest.mark.parametrize("unsafe_kind", ["symlink", "hardlink"])
def test_ledger_path_aliases_are_rejected(tmp_path: Path, unsafe_kind: str) -> None:
    evidence, _bundle = _signed_evidence()
    original = tmp_path / "original-ledger.json"
    original.write_bytes(evidence["ledger"])  # type: ignore[arg-type]
    alias = tmp_path / "ledger.json"
    if unsafe_kind == "symlink":
        alias.symlink_to(original)
    else:
        alias.hardlink_to(original)
    for key in ("ledger_signature", "head", "head_signature", "key"):
        (tmp_path / f"{key}.json").write_bytes(evidence[key])  # type: ignore[arg-type]
    with pytest.raises(subject.ReportRestoreTombstoneError) as unsafe:
        subject.load_verified_report_tombstone_bundle(
            ledger_path=alias,
            ledger_signature_path=tmp_path / "ledger_signature.json",
            trusted_head_path=tmp_path / "head.json",
            head_signature_path=tmp_path / "head_signature.json",
            key_descriptor_path=tmp_path / "key.json",
            expected_key_id=evidence["key_id"],  # type: ignore[arg-type]
            expected_head_sha256=evidence["head_sha256"],  # type: ignore[arg-type]
        )
    assert unsafe.value.code == "restore_tombstone_evidence_unavailable"


def test_evidence_reader_rejects_fifo_without_blocking(tmp_path: Path) -> None:
    fifo = tmp_path / "ledger.json"
    os.mkfifo(fifo, mode=0o600)

    with pytest.raises(subject.ReportRestoreTombstoneError) as unsafe:
        subject._read_stable_regular(
            fifo,
            maximum=subject._MAX_LEDGER_BYTES,
            label="tombstone ledger",
        )

    assert unsafe.value.code == "restore_tombstone_evidence_unavailable"


def test_evidence_reader_rejects_unsafe_metadata_before_open(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fifo = tmp_path / "fifo.json"
    os.mkfifo(fifo, mode=0o600)
    device = Path("/dev/null")
    assert stat.S_ISCHR(os.lstat(device).st_mode)
    original = tmp_path / "original.json"
    original.write_bytes(b"{}")
    symlink = tmp_path / "symlink.json"
    symlink.symlink_to(original)
    hardlink = tmp_path / "hardlink.json"
    hardlink.hardlink_to(original)
    oversized = tmp_path / "oversized.json"
    with oversized.open("wb") as stream:
        stream.truncate(9)

    def unexpected_open(*_args: object, **_kwargs: object) -> int:
        raise AssertionError("unsafe evidence reached open")

    monkeypatch.setattr(subject.os, "open", unexpected_open)
    for unsafe in (fifo, device, symlink, hardlink, oversized):
        with pytest.raises(subject.ReportRestoreTombstoneError) as rejected:
            subject._read_stable_regular(unsafe, maximum=8, label="unsafe evidence")
        assert rejected.value.code == "restore_tombstone_evidence_unavailable"


@pytest.mark.parametrize("mutation", ["fifo", "symlink", "hardlink", "oversize"])
def test_evidence_reader_revalidates_after_open_race(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    evidence = tmp_path / "evidence.json"
    evidence.write_bytes(b"{}")
    real_open = os.open

    def adversarial_open(path: object, flags: int, *args: object) -> int:
        if mutation == "fifo":
            evidence.unlink()
            os.mkfifo(evidence, mode=0o600)
        elif mutation == "symlink":
            original = tmp_path / "replacement.json"
            original.write_bytes(b"{}")
            evidence.unlink()
            evidence.symlink_to(original)
        elif mutation == "hardlink":
            os.link(evidence, tmp_path / "alias.json")
        else:
            with evidence.open("r+b") as stream:
                stream.truncate(9)
        return real_open(path, flags, *args)

    monkeypatch.setattr(subject.os, "open", adversarial_open)
    with pytest.raises(subject.ReportRestoreTombstoneError) as rejected:
        subject._read_stable_regular(evidence, maximum=8, label="raced evidence")
    assert rejected.value.code == "restore_tombstone_evidence_unavailable"


def test_inventory_requires_canonical_complete_database_and_upload_scope(
    tmp_path: Path,
) -> None:
    canonical = _inventory_bytes([_item()])
    noncanonical = json.dumps(json.loads(canonical)).encode("ascii")
    with pytest.raises(subject.ReportRestoreTombstoneError) as encoding:
        subject.parse_restored_report_inventory(noncanonical)
    assert encoding.value.code == "restore_tombstone_evidence_invalid"

    with pytest.raises(subject.ReportRestoreTombstoneError) as incomplete:
        subject.parse_restored_report_inventory(
            _inventory_bytes(
                [_item()], covered_stores=["REPORT_DATABASE"]
            )
        )
    assert incomplete.value.code == "restore_inventory_incomplete"

    inventory_path = tmp_path / "inventory.json"
    inventory_path.write_bytes(canonical)
    with pytest.raises(subject.ReportRestoreTombstoneError) as anchor:
        subject.load_restored_report_inventory(
            inventory_path, expected_inventory_sha256="f" * 64
        )
    assert anchor.value.code == "restore_inventory_anchor_mismatch"


@pytest.mark.parametrize(
    ("owner", "generation"),
    [("9" * 64, 3), (SUBJECT_HMAC, 4)],
)
def test_reappeared_report_must_match_owner_and_account_generation(
    owner: str, generation: int
) -> None:
    _evidence, bundle = _signed_evidence()
    inventory = subject.parse_restored_report_inventory(
        _inventory_bytes([_item(subject_hmac=owner, generation=generation)])
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as mismatch:
        subject.plan_report_restore_reapply(
            bundle,
            inventory,
            source_fence=_source_fence(bundle, inventory),
        )
    assert mismatch.value.code == "restore_tombstone_owner_mismatch"


def test_source_fence_and_backup_cutoff_are_required_while_late_inventory_is_valid() -> None:
    _evidence, bundle = _signed_evidence()
    late = subject.parse_restored_report_inventory(
        _inventory_bytes([_item()], observed_at="2026-08-29T12:00:01Z")
    )
    plan = subject.plan_report_restore_reapply(
        bundle,
        late,
        source_fence=_source_fence(bundle, late),
    )
    assert plan.actions[0].report_id == REPORT_ID

    with pytest.raises(subject.ReportRestoreTombstoneError) as missing_fence:
        subject.plan_report_restore_reapply(bundle, late)
    assert missing_fence.value.code == "restore_source_fence_required"

    future_backup = subject.parse_restored_report_inventory(
        _inventory_bytes(
            [_item()],
            source_backup_created_at="2026-08-29T12:00:01Z",
            observed_at="2026-08-29T12:00:02Z",
        )
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as stale:
        subject.plan_report_restore_reapply(
            bundle,
            future_backup,
            source_fence=_source_fence(bundle, future_backup),
        )
    assert stale.value.code == "restore_tombstone_head_stale"

    with pytest.raises(subject.ReportRestoreTombstoneError) as late_fence:
        subject.plan_report_restore_reapply(
            bundle,
            late,
            source_fence=_source_fence(
                bundle,
                late,
                acquired_at=datetime(2026, 8, 29, 12, 0, 1, tzinfo=UTC),
            ),
        )
    assert late_fence.value.code == "restore_source_fence_invalid"


def test_unbound_orphan_is_fail_closed() -> None:
    _evidence, bundle = _signed_evidence()
    unknown_report = uuid.UUID("00000000-0000-4000-8000-000000000020")
    orphan = subject.parse_restored_report_inventory(
        _inventory_bytes(
            [
                _item(
                    report_id=unknown_report,
                    subject_hmac=None,
                    generation=None,
                    row_present=False,
                    artifacts=1,
                )
            ]
        )
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as unbound:
        subject.plan_report_restore_reapply(
            bundle,
            orphan,
            source_fence=_source_fence(bundle, orphan),
        )
    assert unbound.value.code == "restore_inventory_orphan_unbound"


def test_plan_binds_exact_artifact_and_database_metadata_and_invalidates_old_receipt() -> None:
    _evidence, bundle = _signed_evidence()
    before = subject.parse_restored_report_inventory(_inventory_bytes([_item()]))
    fence = _source_fence(bundle, before)
    original = subject.plan_report_restore_reapply(
        bundle,
        before,
        source_fence=fence,
    )
    action = original.actions[0]
    assert action.image_path == f"/uploads/{REPORT_ID}.jpg"
    assert action.image_content_type == "image/jpeg"
    assert action.artifact_storage_name == f"{REPORT_ID}.wse"
    assert action.artifact_sha256 == "d" * 64
    assert action.artifact_size == 401
    assert action.artifact_device_inode == "1:2"
    action_document = json.loads(subject.report_restore_reapply_plan_bytes(original))["actions"][0]
    assert action_document["image_path"] == action.image_path
    assert action_document["image_content_type"] == action.image_content_type
    assert action_document["artifact_storage_name"] == action.artifact_storage_name
    assert action_document["artifact_sha256"] == action.artifact_sha256
    assert action_document["artifact_size"] == action.artifact_size
    assert action_document["artifact_device_inode"] == action.artifact_device_inode

    changed_item = _item()
    changed_item.update(
        {
            "artifact_device_inode": "3:4",
            "artifact_sha256": "e" * 64,
            "artifact_size": 402,
            "image_content_type": "image/png",
            "image_path": f"/uploads/{REPORT_ID}.png",
        }
    )
    changed = subject.parse_restored_report_inventory(
        _inventory_bytes([changed_item], observed_at="2026-08-29T11:00:01Z")
    )
    changed_plan = subject.plan_report_restore_reapply(
        bundle,
        changed,
        source_fence=fence,
    )
    assert changed.inventory_sha256 != before.inventory_sha256
    assert changed_plan.actions[0] != original.actions[0]
    assert changed_plan.plan_sha256 != original.plan_sha256

    target = _AtomicTarget()
    subject.execute_report_restore_reapply(
        original,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    post = subject.parse_restored_report_inventory(
        _inventory_bytes([], observed_at="2026-08-29T12:00:01Z")
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as stale_receipt:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=changed,
            post_inventory=post,
            receipt_bytes=target.receipt,
            source_fence=fence,
            source_fence_verifier=_FenceVerifier(),
        )
    assert stale_receipt.value.code == "restore_reapply_receipt_invalid"


def test_dry_run_is_default_and_performs_no_target_calls() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    outcome = subject.execute_report_restore_reapply(plan, target)
    assert outcome.mode == "DRY_RUN"
    assert target.calls == []
    assert target.present == {REPORT_ID}

    with pytest.raises(subject.ReportRestoreTombstoneError) as confirmation:
        subject.execute_report_restore_reapply(plan, target, dry_run=False)
    assert confirmation.value.code == "restore_reapply_confirmation_required"
    assert target.calls == []


def test_apply_is_atomic_and_response_loss_retry_returns_same_receipt() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    applied = subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    assert applied.mode == "APPLIED"
    assert applied.receipt is not None
    assert target.present == set()
    immutable_receipt = target.receipt

    replayed = subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        source_fence_verifier=_FenceVerifier(),
    )
    assert replayed.mode == "REPLAYED"
    assert target.receipt == immutable_receipt
    assert replayed.receipt == applied.receipt
    assert target.calls.count("begin") == 1
    assert target.calls.count("commit") == 1


def test_commit_response_loss_recovers_exact_atomic_receipt_without_rollback() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    target.lose_commit_response = True
    outcome = subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    assert outcome.mode == "REPLAYED"
    assert target.present == set()
    assert target.receipt is not None
    assert "rollback" not in target.calls


def test_committed_receipt_with_pending_artifact_reconciliation_is_not_success() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    target.receipt = b"durable receipt placeholder"
    target.reconciliation_required = True

    with pytest.raises(subject.ReportRestoreTombstoneError) as pending:
        subject.execute_report_restore_reapply(
            plan,
            target,
            dry_run=False,
            confirmation=subject.APPLY_CONFIRMATION,
            source_fence_verifier=_FenceVerifier(),
        )
    assert pending.value.code == "restore_artifact_reconciliation_required"
    assert target.calls == ["load_receipt"]


def test_post_commit_artifact_failure_preserves_receipt_and_blocks_success() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    target.fail_reconciliation_after_commit = True

    with pytest.raises(subject.ReportRestoreTombstoneError) as pending:
        subject.execute_report_restore_reapply(
            plan,
            target,
            dry_run=False,
            confirmation=subject.APPLY_CONFIRMATION,
            source_fence_verifier=_FenceVerifier(),
        )
    assert pending.value.code == "restore_artifact_reconciliation_required"
    assert target.receipt is not None
    assert target.present == set()
    assert "rollback" not in target.calls


def test_semantic_postcommit_failure_is_not_replayed_or_rolled_back() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    target.fail_semantic_postcommit = True

    with pytest.raises(subject.ReportRestorePostCommitError) as failed:
        subject.execute_report_restore_reapply(
            plan,
            target,
            dry_run=False,
            confirmation=subject.APPLY_CONFIRMATION,
            source_fence_verifier=_FenceVerifier(),
        )

    assert failed.value.code == "restore_target_not_isolated"
    assert target.calls.count("load_receipt") == 1
    assert "rollback" not in target.calls
    assert target.receipt is not None


def test_source_fence_loss_before_commit_rolls_back_without_receipt() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    verifier = _FenceVerifier(True, True, True, False)
    with pytest.raises(subject.ReportRestoreTombstoneError) as lost:
        subject.execute_report_restore_reapply(
            plan,
            target,
            dry_run=False,
            confirmation=subject.APPLY_CONFIRMATION,
            source_fence_verifier=verifier,
        )
    assert lost.value.code == "restore_source_fence_not_held"
    assert "commit" not in target.calls
    assert target.receipt is None
    assert target.present == {REPORT_ID}


def test_source_fence_loss_after_commit_preserves_receipt_but_blocks_success() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    verifier = _FenceVerifier(True, True, True, True, False)
    with pytest.raises(subject.ReportRestoreTombstoneError) as lost:
        subject.execute_report_restore_reapply(
            plan,
            target,
            dry_run=False,
            confirmation=subject.APPLY_CONFIRMATION,
            source_fence_verifier=verifier,
        )
    assert lost.value.code == "restore_source_fence_not_held"
    assert target.receipt is not None
    assert target.present == set()


def test_failed_apply_rolls_back_and_rollback_failure_has_distinct_blocker() -> None:
    _bundle, _inventory, plan = _plan()
    target = _AtomicTarget()
    target.fail_reapply = True
    with pytest.raises(subject.ReportRestoreTombstoneError) as failed:
        subject.execute_report_restore_reapply(
            plan,
            target,
            dry_run=False,
            confirmation=subject.APPLY_CONFIRMATION,
            source_fence_verifier=_FenceVerifier(),
        )
    assert failed.value.code == "restore_reapply_failed"
    assert target.present == {REPORT_ID}
    assert target.calls[-1] == "rollback"

    target = _AtomicTarget()
    target.fail_reapply = True
    target.fail_rollback = True
    with pytest.raises(subject.ReportRestoreTombstoneError) as rollback:
        subject.execute_report_restore_reapply(
            plan,
            target,
            dry_run=False,
            confirmation=subject.APPLY_CONFIRMATION,
            source_fence_verifier=_FenceVerifier(),
        )
    assert rollback.value.code == "restore_reapply_rollback_failed"


def test_publish_gate_requires_exact_receipt_and_zero_remaining_matches() -> None:
    bundle, before, plan = _plan()
    fence = plan.source_fence
    target = _AtomicTarget()
    applied = subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    post = subject.parse_restored_report_inventory(
        _inventory_bytes([], observed_at="2026-08-29T12:00:01Z")
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as missing:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=post,
            receipt_bytes=None,
            source_fence=fence,
            source_fence_verifier=_FenceVerifier(),
        )
    assert missing.value.code == "restore_reapply_receipt_missing"

    verifier = _FenceVerifier()
    gate = subject.assert_report_restore_publishable(
        bundle,
        before_inventory=before,
        post_inventory=post,
        receipt_bytes=target.receipt,
        source_fence=fence,
        source_fence_verifier=verifier,
    )
    assert gate.verdict == "PASS"
    assert gate.receipt_sha256 == applied.receipt.receipt_sha256  # type: ignore[union-attr]
    assert gate.reapply_plan_sha256 == plan.plan_sha256
    assert gate.source_fence_id == fence.fence_id
    assert gate.source_fence_sha256 == subject.report_restore_source_fence_sha256(
        fence
    )
    gate_document = json.loads(subject.report_restore_publish_gate_bytes(gate))
    assert gate_document["schema_version"] == subject.GATE_SCHEMA
    assert gate_document["source_fence_id"] == str(fence.fence_id)
    assert gate_document["source_fence_sha256"] == gate.source_fence_sha256
    assert gate_document["reapply_plan_sha256"] == plan.plan_sha256
    assert gate_document["trusted_head_sha256"] == bundle.head_sha256
    assert verifier.calls == [fence, fence]

    with pytest.raises(subject.ReportRestoreTombstoneError) as pending:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=before,
            receipt_bytes=target.receipt,
            source_fence=fence,
            source_fence_verifier=_FenceVerifier(),
        )
    assert pending.value.code == "restore_publish_reapply_pending"


def test_publish_requires_live_fence_verifier_and_rechecks_before_pass() -> None:
    bundle, before, plan = _plan()
    target = _AtomicTarget()
    subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    post = subject.parse_restored_report_inventory(
        _inventory_bytes([], observed_at="2026-08-29T12:00:01Z")
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as missing:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=post,
            receipt_bytes=target.receipt,
            source_fence=plan.source_fence,
        )
    assert missing.value.code == "restore_source_fence_verifier_required"

    with pytest.raises(subject.ReportRestoreTombstoneError) as released:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=post,
            receipt_bytes=target.receipt,
            source_fence=plan.source_fence,
            source_fence_verifier=_FenceVerifier(True, False),
        )
    assert released.value.code == "restore_source_fence_not_held"


def test_publish_rejects_stale_post_inventory() -> None:
    bundle, before, plan = _plan()
    target = _AtomicTarget()
    subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    stale_post = subject.parse_restored_report_inventory(
        _inventory_bytes([], observed_at="2026-08-29T10:59:59Z")
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as stale:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=stale_post,
            receipt_bytes=target.receipt,
            source_fence=plan.source_fence,
            source_fence_verifier=_FenceVerifier(),
        )
    assert stale.value.code == "restore_publish_inventory_stale"


def test_fence_and_exact_head_change_plan_and_invalidate_previous_receipt() -> None:
    bundle, before, plan = _plan()
    target = _AtomicTarget()
    subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    different_request = subject.ReportRestoreSourceFenceRequest(
        **{
            field: getattr(plan.source_fence, field)
            for field in subject.ReportRestoreSourceFenceRequest.__dataclass_fields__
            if field != "fence_id"
        },
        fence_id=uuid.uuid4(),
    )
    different_fence = replace(
        plan.source_fence,
        fence_id=different_request.fence_id,
        source_fence_request_sha256=(
            subject.report_restore_source_fence_request_sha256(
                different_request
            )
        ),
    )
    different_plan = subject.plan_report_restore_reapply(
        bundle,
        before,
        source_fence=different_fence,
    )
    assert different_plan.plan_sha256 != plan.plan_sha256
    with pytest.raises(subject.ReportRestoreTombstoneError) as old_receipt:
        subject.parse_report_restore_reapply_receipt(
            target.receipt,  # type: ignore[arg-type]
            plan=different_plan,
        )
    assert old_receipt.value.code == "restore_reapply_receipt_invalid"

    mismatched_head = replace(plan.source_fence, trusted_head_sha256="f" * 64)
    with pytest.raises(subject.ReportRestoreTombstoneError) as head:
        subject.plan_report_restore_reapply(
            bundle,
            before,
            source_fence=mismatched_head,
        )
    assert head.value.code == "restore_source_fence_mismatch"


def test_large_reapply_receipt_is_parseable_and_loadable(tmp_path: Path) -> None:
    _bundle, _before, original = _plan()
    base = original.actions[0]
    actions = tuple(
        replace(
            base,
            tombstone_id=uuid.uuid4(),
            request_id=uuid.uuid4(),
            report_id=uuid.uuid4(),
        )
        for _ in range(500)
    )
    plan = replace(original, actions=actions, plan_sha256="a" * 64)
    target = _AtomicTarget()
    outcome = subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
        source_fence_verifier=_FenceVerifier(),
    )
    assert outcome.receipt is not None
    receipt_bytes = subject.report_restore_reapply_receipt_bytes(outcome.receipt)
    assert len(receipt_bytes) > 64 * 1024
    assert subject.parse_report_restore_reapply_receipt(
        receipt_bytes,
        plan=plan,
    ) == outcome.receipt

    receipt_path = tmp_path / "large-reapply-receipt.json"
    receipt_path.write_bytes(receipt_bytes)
    assert subject.load_report_restore_reapply_receipt_bytes(receipt_path) == receipt_bytes


def test_read_only_cli_fails_closed_until_source_fence_inputs_are_added(
    tmp_path: Path,
) -> None:
    evidence, _bundle = _signed_evidence()
    paths: dict[str, Path] = {}
    for key in ("ledger", "ledger_signature", "head", "head_signature", "key"):
        path = tmp_path / f"{key}.json"
        path.write_bytes(evidence[key])  # type: ignore[arg-type]
        paths[key] = path
    inventory = tmp_path / "inventory.json"
    inventory_bytes = _inventory_bytes([_item()])
    inventory.write_bytes(inventory_bytes)
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(__file__).resolve().parents[2] / "scripts/check_report_restore_tombstones.py"),
            "--ledger",
            str(paths["ledger"]),
            "--ledger-signature",
            str(paths["ledger_signature"]),
            "--trusted-head",
            str(paths["head"]),
            "--head-signature",
            str(paths["head_signature"]),
            "--trusted-key",
            str(paths["key"]),
            "--expected-key-id",
            evidence["key_id"],  # type: ignore[list-item]
            "--expected-head-sha256",
            evidence["head_sha256"],  # type: ignore[list-item]
            "--inventory",
            str(inventory),
            "--expected-inventory-sha256",
            subject.restored_report_inventory_sha256(inventory_bytes),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 2
    output = json.loads(completed.stderr)
    assert output["code"] == "restore_source_fence_required"
    assert output["publish_verdict"] == "BLOCKED"

    paths["head"].unlink()
    missing = subprocess.run(
        completed.args,
        check=False,
        capture_output=True,
        text=True,
    )
    assert missing.returncode == 2
    error = json.loads(missing.stderr)
    assert error == {
        "code": "restore_tombstone_evidence_unavailable",
        "external_restore_status": "NOT_RUN",
        "publish_verdict": "BLOCKED",
        "schema_version": "walksafe.report-restore-tombstone-check.v1",
    }
