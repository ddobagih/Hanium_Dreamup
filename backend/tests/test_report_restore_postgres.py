from __future__ import annotations

import os
from pathlib import Path
import uuid

import pytest

from backend.app.services import report_restore_postgres as subject
from backend.app.services import report_restore_tombstones as tombstones
from scripts.delete_reports import ReportDeletionQuarantine, ReportDeletionWorkerError


def test_artifact_roots_reject_ancestor_relationship(tmp_path: Path) -> None:
    source = tmp_path / "source"
    target = source / "target-drill"
    target.mkdir(parents=True)
    with pytest.raises(tombstones.ReportRestoreTombstoneError) as unsafe:
        subject._validated_artifact_roots(source, target)
    assert unsafe.value.code == "restore_artifact_target_unsafe"


def test_migration_requires_independent_authorization_without_direct_worker_delete() -> None:
    migration = (
        Path(__file__).parents[1]
        / "alembic/versions/202608300003_report_restore_reapply.py"
    ).read_text(encoding="utf-8")

    assert "walksafe_report_restore_authorizer" in migration
    assert "report_restore_reapply_authorized_actions" in migration
    assert "report_restore_reapply_postchecks" in migration
    assert "walksafe_execute_report_restore_action" in migration
    assert "walksafe_finish_report_restore_reapply" in migration
    assert "walksafe_record_report_restore_postcheck" in migration
    assert "restore postcheck outcome is immutable" in migration
    assert "must not inherit another role" in migration
    assert "must not own database objects" in migration
    assert "IN ACCESS EXCLUSIVE MODE" in migration
    assert "report restore reapply history blocks lossy downgrade" in migration
    assert "USING ERRCODE = '55000'" in migration
    assert "walksafe/report-restore-reapply-receipt/v1" in migration
    assert "pg_catalog.convert_to(canonical_payload, 'UTF8')" in migration
    assert "pg_catalog.convert_to(canonical_receipt, 'UTF8')" in migration
    assert "IS DISTINCT FROM expected_receipt_sha256" in migration
    assert "IS DISTINCT FROM p_receipt_bytes" in migration
    assert "row_number() OVER (" in migration
    assert "ORDER BY action.report_id" in migration
    assert "WITH ORDINALITY AS item(value, ordinality)" in migration
    assert "item.ordinality = expected.ordinality" in migration
    assert "GRANT DELETE ON TABLE public.reports" not in migration
    assert "GRANT SELECT, INSERT ON TABLE public.report_restore_reapply_receipts" not in migration
    assert "GRANT SELECT, INSERT ON TABLE public.report_restore_reapply_postchecks" not in migration


def test_validated_root_identity_rejects_path_exchange_before_pin(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    target = tmp_path / "target-drill"
    source.mkdir(mode=0o700)
    target.mkdir(mode=0o700)
    (
        source_path,
        target_path,
        source_identity,
        target_identity,
    ) = subject._validated_artifact_roots(source, target)

    displaced = tmp_path / "displaced"
    source.rename(displaced)
    target.rename(source)
    displaced.rename(target)

    with pytest.raises(OSError, match="artifact root is unsafe"):
        subject._open_pinned_artifact_root(
            source_path,
            expected_identity=source_identity,
        )
    with pytest.raises(OSError, match="artifact root is unsafe"):
        subject._open_pinned_artifact_root(
            target_path,
            expected_identity=target_identity,
        )


def test_quarantine_rejects_swapped_root_before_mutating_replacement(
    tmp_path: Path,
) -> None:
    upload_root = tmp_path / "target-drill"
    upload_root.mkdir(mode=0o700)
    identity = (upload_root.stat().st_dev, upload_root.stat().st_ino)
    displaced = tmp_path / "displaced"
    upload_root.rename(displaced)
    upload_root.mkdir(mode=0o700)
    report_id = uuid.uuid4()
    artifact = upload_root / f"{report_id}.wse"
    artifact.write_bytes(b"must-not-delete")
    artifact.chmod(0o600)
    quarantine = ReportDeletionQuarantine(
        upload_root,
        request_id=uuid.uuid4(),
        report_id=report_id,
        expected_upload_root_identity=identity,
    )
    with pytest.raises(ReportDeletionWorkerError, match="identity changed"):
        quarantine.stage((artifact.name,))
    assert artifact.read_bytes() == b"must-not-delete"
    assert not (upload_root / ".report-deletion-quarantine").exists()


def test_quarantine_path_swap_cannot_delete_a_source_artifact(tmp_path: Path) -> None:
    upload_root = tmp_path / "target-drill"
    source_root = tmp_path / "source"
    upload_root.mkdir(mode=0o700)
    source_root.mkdir(mode=0o700)
    identity = (upload_root.stat().st_dev, upload_root.stat().st_ino)
    report_id = uuid.uuid4()
    source_artifact = source_root / f"{report_id}.wse"
    source_artifact.write_bytes(b"source-must-survive")
    source_artifact.chmod(0o600)

    upload_root.rename(tmp_path / "displaced-target")
    source_root.rename(upload_root)
    quarantine = ReportDeletionQuarantine(
        upload_root,
        request_id=uuid.uuid4(),
        report_id=report_id,
        expected_upload_root_identity=identity,
    )
    with pytest.raises(ReportDeletionWorkerError, match="identity changed"):
        quarantine.stage((source_artifact.name,))

    assert (upload_root / source_artifact.name).read_bytes() == b"source-must-survive"
    assert not (upload_root / ".report-deletion-quarantine").exists()


def test_artifact_hash_rejects_fifo_and_oversize_before_read(tmp_path: Path) -> None:
    fifo = tmp_path / "artifact.wse"
    os.mkfifo(fifo, mode=0o600)
    with pytest.raises(tombstones.ReportRestoreTombstoneError) as unsafe_type:
        subject._sha256_file(fifo)
    assert unsafe_type.value.code == "restore_inventory_incomplete"

    oversized = tmp_path / "oversized.wse"
    with oversized.open("wb") as stream:
        stream.truncate(subject.MAX_ENVELOPE_BYTES + 1)
    oversized.chmod(0o600)
    with pytest.raises(tombstones.ReportRestoreTombstoneError) as unsafe_size:
        subject._sha256_file(oversized)
    assert unsafe_size.value.code == "restore_inventory_incomplete"


def test_upload_inventory_rejects_fifo_without_blocking(tmp_path: Path) -> None:
    upload_root = tmp_path / "target-drill"
    upload_root.mkdir(mode=0o700)
    os.mkfifo(upload_root / f"{uuid.uuid4()}.wse", mode=0o600)

    with pytest.raises(RuntimeError, match="legacy or unknown entry"):
        with subject._opened_flat_upload_inventory(upload_root):
            pass


def test_quarantine_reconcile_rejects_fifo_journal_without_blocking(
    tmp_path: Path,
) -> None:
    upload_root = tmp_path / "target-drill"
    quarantine_root = upload_root / ".report-deletion-quarantine"
    quarantine_root.mkdir(parents=True, mode=0o700)
    os.mkfifo(quarantine_root / f"{uuid.uuid4()}.json", mode=0o600)

    with pytest.raises(ReportDeletionWorkerError, match="journal is unsafe"):
        ReportDeletionQuarantine.reconcile_all(
            upload_root,
            is_committed=lambda _request_id, _report_id: False,
        )


def test_artifact_hash_rejects_symlink_and_hardlink_before_read(
    tmp_path: Path,
) -> None:
    original = tmp_path / "original.wse"
    original.write_bytes(b"private artifact")
    original.chmod(0o600)
    symlink = tmp_path / "symlink.wse"
    symlink.symlink_to(original)
    hardlink = tmp_path / "hardlink.wse"
    os.link(original, hardlink)

    for unsafe in (symlink, hardlink):
        with pytest.raises(tombstones.ReportRestoreTombstoneError) as rejected:
            subject._sha256_file(unsafe)
        assert rejected.value.code == "restore_inventory_incomplete"
