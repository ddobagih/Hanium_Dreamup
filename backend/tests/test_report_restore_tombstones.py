from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
from pathlib import Path
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
SUBJECT_HMAC = "1" * 64
TARGET_IDENTITY = "2" * 64
SOURCE_IDENTITY = "3" * 64
BACKUP_MANIFEST_SHA256 = "4" * 64
RESTORE_RECEIPT_SHA256 = "5" * 64
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
        "bound_artifact_count": artifacts,
        "privacy_subject_hmac": subject_hmac,
        "report_id": str(report_id),
        "report_row_present": row_present,
    }


def _inventory_bytes(
    items: list[dict[str, object]],
    *,
    observed_at: str = "2026-08-29T11:00:00Z",
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
            "source_backup_created_at": "2026-08-29T08:00:00Z",
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


def _plan() -> tuple[
    subject.VerifiedReportTombstoneBundle,
    subject.RestoredReportInventory,
    subject.ReportRestoreReapplyPlan,
]:
    _evidence, bundle = _signed_evidence()
    inventory = subject.parse_restored_report_inventory(_inventory_bytes([_item()]))
    return bundle, inventory, subject.plan_report_restore_reapply(bundle, inventory)


class _AtomicTarget:
    def __init__(self) -> None:
        self.calls: list[str] = []
        self.receipt: bytes | None = None
        self.present = {REPORT_ID}
        self.snapshot: set[uuid.UUID] | None = None
        self.fail_reapply = False
        self.fail_rollback = False
        self.lose_commit_response = False

    def load_receipt(self, _plan_sha256: str) -> bytes | None:
        self.calls.append("load_receipt")
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
        if self.lose_commit_response:
            raise RuntimeError("injected commit response loss")

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
        subject.plan_report_restore_reapply(bundle, inventory)
    assert mismatch.value.code == "restore_tombstone_owner_mismatch"


def test_stale_cutoff_and_unbound_orphan_are_fail_closed() -> None:
    _evidence, bundle = _signed_evidence()
    late = subject.parse_restored_report_inventory(
        _inventory_bytes([_item()], observed_at="2026-08-29T12:00:01Z")
    )
    with pytest.raises(subject.ReportRestoreTombstoneError) as stale:
        subject.plan_report_restore_reapply(bundle, late)
    assert stale.value.code == "restore_tombstone_head_stale"

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
        subject.plan_report_restore_reapply(bundle, orphan)
    assert unbound.value.code == "restore_inventory_orphan_unbound"


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
    )
    assert outcome.mode == "REPLAYED"
    assert target.present == set()
    assert target.receipt is not None
    assert "rollback" not in target.calls


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
        )
    assert rollback.value.code == "restore_reapply_rollback_failed"


def test_publish_gate_requires_exact_receipt_and_zero_remaining_matches() -> None:
    bundle, before, plan = _plan()
    target = _AtomicTarget()
    applied = subject.execute_report_restore_reapply(
        plan,
        target,
        dry_run=False,
        confirmation=subject.APPLY_CONFIRMATION,
        applied_at=datetime(2026, 8, 29, 11, 5, tzinfo=UTC),
    )
    post = subject.parse_restored_report_inventory(_inventory_bytes([]))
    with pytest.raises(subject.ReportRestoreTombstoneError) as missing:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=post,
            receipt_bytes=None,
        )
    assert missing.value.code == "restore_reapply_receipt_missing"

    gate = subject.assert_report_restore_publishable(
        bundle,
        before_inventory=before,
        post_inventory=post,
        receipt_bytes=target.receipt,
    )
    assert gate.verdict == "PASS"
    assert gate.receipt_sha256 == applied.receipt.receipt_sha256  # type: ignore[union-attr]

    with pytest.raises(subject.ReportRestoreTombstoneError) as pending:
        subject.assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=before,
            receipt_bytes=target.receipt,
        )
    assert pending.value.code == "restore_publish_reapply_pending"


def test_read_only_cli_blocks_pending_reapply_and_reports_not_run(
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
    output = json.loads(completed.stdout)
    assert output["verdict"] == "BLOCKED_REAPPLY_REQUIRED"
    assert len(output["actions"]) == 1

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
