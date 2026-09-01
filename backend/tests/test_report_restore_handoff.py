from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import os
from pathlib import Path
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
import pytest

from backend.app.services import report_restore_tombstones as tombstones
from backend.app.services import report_restore_handoff as handoff
from backend.app.services.report_restore_handoff import (
    prepare_signed_evidence_paths,
    wait_for_stable_signed_evidence,
)


def _public_bytes(private_key: Ed25519PrivateKey) -> bytes:
    return private_key.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )


def _request(*, acquired_at: datetime) -> tombstones.ReportRestoreSourceFenceRequest:
    return tombstones.ReportRestoreSourceFenceRequest(
        fence_id=uuid.UUID("00000000-0000-4000-8000-000000000101"),
        restore_run_id=uuid.UUID("00000000-0000-4000-8000-000000000102"),
        backup_run_id="backup-run-handoff",
        backup_manifest_sha256="1" * 64,
        source_identity_sha256="2" * 64,
        data_boundary_id="walksafe-report-production",
        source_backend_pid=4321,
        maintenance_lock_identity_sha256="3" * 64,
        maintenance_lock_device_inode="8:9",
        report_deletion_lock_key=tombstones.REPORT_DELETION_ADVISORY_LOCK_KEY,
        report_deletion_lock_identity_sha256="4" * 64,
        acquired_at=acquired_at,
    )


def _signed_handoff(
    request: tombstones.ReportRestoreSourceFenceRequest,
) -> tuple[
    tombstones.VerifiedReportTombstoneBundle,
    tombstones.VerifiedReportRestoreFenceBinding,
]:
    private_key = Ed25519PrivateKey.generate()
    public_bytes = _public_bytes(private_key)
    ledger = tombstones.build_report_tombstone_ledger_bytes(
        (),
        ledger_id=uuid.UUID("00000000-0000-4000-8000-000000000103"),
        data_boundary_id=request.data_boundary_id,
        privacy_hmac_key_version=7,
        cutoff_at=datetime(2026, 8, 30, 3, tzinfo=UTC),
    )
    head = tombstones.build_report_tombstone_head_bytes(
        ledger,
        issued_at=datetime(2026, 8, 30, 3, 0, 1, tzinfo=UTC),
        predecessor_head_sha256=None,
    )
    binding = tombstones.build_report_restore_source_fence_binding_bytes(
        request,
        ledger_bytes=ledger,
        trusted_head_bytes=head,
    )
    key = tombstones.build_key_descriptor(public_bytes)
    key_id = tombstones.ed25519_key_id(public_bytes)
    verified_binding = tombstones.verify_report_restore_source_fence_binding(
        request=request,
        binding_bytes=binding,
        binding_signature_bytes=tombstones.build_signature_descriptor(
            binding,
            public_key_bytes=public_bytes,
            signature=private_key.sign(
                tombstones.SOURCE_FENCE_BINDING_SIGNATURE_DOMAIN + binding
            ),
        ),
        ledger_bytes=ledger,
        trusted_head_bytes=head,
        key_descriptor_bytes=key,
        expected_key_id=key_id,
    )
    bundle = tombstones.verify_report_tombstone_bundle(
        ledger_bytes=ledger,
        ledger_signature_bytes=tombstones.build_signature_descriptor(
            ledger,
            public_key_bytes=public_bytes,
            signature=private_key.sign(
                tombstones.LEDGER_SIGNATURE_DOMAIN + ledger
            ),
        ),
        trusted_head_bytes=head,
        head_signature_bytes=tombstones.build_signature_descriptor(
            head,
            public_key_bytes=public_bytes,
            signature=private_key.sign(
                tombstones.HEAD_SIGNATURE_DOMAIN + head
            ),
        ),
        key_descriptor_bytes=key,
        expected_key_id=key_id,
        expected_head_sha256=hashlib.sha256(head).hexdigest(),
    )
    return bundle, verified_binding


def test_signed_binding_requires_cutoff_after_exact_fence_request() -> None:
    request = _request(acquired_at=datetime(2026, 8, 30, 2, 59, tzinfo=UTC))
    bundle, binding = _signed_handoff(request)
    fence = tombstones.bind_report_restore_source_fence(
        request,
        bundle=bundle,
        binding=binding,
    )
    assert fence.source_backend_pid == request.source_backend_pid
    assert fence.maintenance_lock_device_inode == "8:9"
    assert fence.source_fence_request_sha256 == (
        tombstones.report_restore_source_fence_request_sha256(request)
    )

    changed_request = _request(
        acquired_at=datetime(2026, 8, 30, 2, 59, 1, tzinfo=UTC)
    )
    with pytest.raises(tombstones.ReportRestoreTombstoneError) as changed:
        tombstones.bind_report_restore_source_fence(
            changed_request,
            bundle=bundle,
            binding=binding,
        )
    assert changed.value.code == "restore_source_fence_binding_invalid"


def test_binding_builder_rejects_cutoff_before_lock_acquisition() -> None:
    request = _request(acquired_at=datetime(2026, 8, 30, 3, 0, 1, tzinfo=UTC))
    private_key = Ed25519PrivateKey.generate()
    ledger = tombstones.build_report_tombstone_ledger_bytes(
        (),
        ledger_id=uuid.uuid4(),
        data_boundary_id=request.data_boundary_id,
        privacy_hmac_key_version=7,
        cutoff_at=datetime(2026, 8, 30, 3, tzinfo=UTC),
    )
    head = tombstones.build_report_tombstone_head_bytes(
        ledger,
        issued_at=datetime(2026, 8, 30, 3, 0, 2, tzinfo=UTC),
        predecessor_head_sha256=None,
    )
    del private_key
    with pytest.raises(tombstones.ReportRestoreTombstoneError) as stale:
        tombstones.build_report_restore_source_fence_binding_bytes(
            request,
            ledger_bytes=ledger,
            trusted_head_bytes=head,
        )
    assert stale.value.code == "restore_source_fence_binding_invalid"


def _evidence_paths(parent: Path) -> dict[str, Path]:
    return {
        "ledger": parent / "ledger.json",
        "ledger_signature": parent / "ledger.sig.json",
        "trusted_head": parent / "head.json",
        "head_signature": parent / "head.sig.json",
        "fence_binding": parent / "binding.json",
        "fence_binding_signature": parent / "binding.sig.json",
    }


def _publish(paths: dict[str, Path]) -> None:
    for index, path in enumerate(paths.values(), start=1):
        path.write_bytes(f"evidence-{index}".encode("ascii"))
        path.chmod(0o600)


def test_evidence_paths_are_absent_then_pinned_against_replacement(
    tmp_path: Path,
) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    paths = _evidence_paths(parent)
    checks = 0

    def verify() -> None:
        nonlocal checks
        checks += 1

    with prepare_signed_evidence_paths(paths) as prepared:
        _publish(paths)
        with wait_for_stable_signed_evidence(
            paths,
            timeout_seconds=1,
            verify_source_fence=verify,
            prepared=prepared,
        ) as evidence:
            assert evidence.raw["ledger"] == b"evidence-1"
            assert checks >= len(paths)
            replacement = parent / "replacement"
            replacement.write_bytes(b"changed")
            replacement.chmod(0o600)
            os.replace(replacement, paths["ledger"])
            with pytest.raises(tombstones.ReportRestoreTombstoneError) as changed:
                evidence.verify_unchanged()
            assert changed.value.code == "restore_tombstone_evidence_unavailable"


def test_evidence_must_still_be_absent_immediately_before_request(
    tmp_path: Path,
) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    paths = _evidence_paths(parent)
    with prepare_signed_evidence_paths(paths) as prepared:
        paths["ledger"].write_bytes(b"too-early")
        paths["ledger"].chmod(0o600)
        with pytest.raises(tombstones.ReportRestoreTombstoneError) as early:
            prepared.verify_absent_unchanged()
    assert early.value.code == "restore_tombstone_evidence_preexisting"


def test_evidence_rejects_preexisting_or_hardlinked_leaf(tmp_path: Path) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    paths = _evidence_paths(parent)
    paths["ledger"].write_bytes(b"preexisting")
    paths["ledger"].chmod(0o600)
    with pytest.raises(tombstones.ReportRestoreTombstoneError) as preexisting:
        prepare_signed_evidence_paths(paths)
    assert preexisting.value.code == "restore_tombstone_evidence_preexisting"

    paths["ledger"].unlink()
    with prepare_signed_evidence_paths(paths) as prepared:
        _publish(paths)
        os.link(paths["ledger"], parent / "ledger.alias")
        with pytest.raises(tombstones.ReportRestoreTombstoneError) as unsafe:
            wait_for_stable_signed_evidence(
                paths,
                timeout_seconds=1,
                verify_source_fence=lambda: None,
                prepared=prepared,
            )
    assert unsafe.value.code == "restore_tombstone_evidence_unavailable"


def test_evidence_parent_rejects_acl_bearing_authority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    monkeypatch.setattr(handoff, "descriptor_acl_is_absent", lambda _fd: False)
    with pytest.raises(tombstones.ReportRestoreTombstoneError) as unsafe:
        prepare_signed_evidence_paths(_evidence_paths(parent))
    assert unsafe.value.code == "restore_tombstone_evidence_path_unsafe"


def test_inventory_builder_represents_anonymous_rows_without_weakening_scope() -> None:
    report_id = uuid.uuid4()
    raw = tombstones.build_restored_report_inventory_bytes(
        restore_run_id=uuid.uuid4(),
        backup_run_id="backup-run-anonymous",
        backup_manifest_sha256="1" * 64,
        restore_receipt_sha256="2" * 64,
        data_boundary_id="walksafe-report-production",
        privacy_hmac_key_version=7,
        source_identity_sha256="3" * 64,
        target_identity_sha256="4" * 64,
        source_backup_created_at=datetime(2026, 8, 30, 1, tzinfo=UTC),
        observed_at=datetime(2026, 8, 30, 2, tzinfo=UTC),
        items=(
            tombstones.RestoredReportInventoryItem(
                report_id=report_id,
                privacy_subject_hmac=None,
                account_generation=None,
                report_row_present=True,
                bound_artifact_count=1,
                image_path=f"/uploads/{report_id}.jpg",
                image_content_type="image/jpeg",
                artifact_storage_name=f"{report_id}.wse",
                artifact_sha256="5" * 64,
                artifact_size=401,
                artifact_device_inode="1:2",
            ),
        ),
    )
    inventory = tombstones.parse_restored_report_inventory(raw)
    assert inventory.items[0].report_row_present is True
    assert inventory.items[0].privacy_subject_hmac is None
