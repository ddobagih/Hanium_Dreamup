"""S1617-55: independent storage roots survive a zero-chunk RAW inventory and restart."""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path

import pytest

import scripts.account_deletion_worker as worker

COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174001"
REPORT_ID = "123e4567-e89b-42d3-a456-426614174002"


def journal(tmp_path: Path) -> dict:
    roots = {}
    for name in ("uploads", "raw"):
        directory = tmp_path / name
        directory.mkdir(mode=0o700)
        roots[name] = worker._capture_storage_root(directory, group_gid=None)
    return {
        "schema_version": worker.JOURNAL_SCHEMA,
        "request_id": "delete_request_roots_0001",
        "privacy_subject_hmac": "a" * 64,
        "account_generation": 1,
        "state": "DATABASE_COMMITTED",
        "prepared_at": "2026-10-07T00:00:00Z",
        "deleted_at": "2026-10-07T00:01:00Z",
        "report_count": 0, "objects": [],
        "raw_collection_count": 0, "raw_collections": [], "raw_objects": [],
        "credential_account_count": 0, "account_enrollment_count": 0,
        "account_enrollment_ids": [], "retained_signup_consent_receipt_count": 0,
        "quarantined_raw_collection_count": 0, "external_copy_count": 0,
        "storage_roots": roots,
    }


def test_zero_chunks_still_searches_raw_root_and_preserves_unrelated_files(tmp_path):
    value = journal(tmp_path)
    value["raw_collections"] = [{"collection_id": COLLECTION_ID}]
    leftover = tmp_path / "raw" / f"{COLLECTION_ID}.orphan"
    unrelated = tmp_path / "raw" / "other-account-object.wsrc"
    leftover.write_bytes(b"synthetic stale data")
    unrelated.write_bytes(b"unrelated data")
    # No raw_objects source paths exist: this is the original missed-root case.
    assert value["raw_objects"] == []
    assert worker._leftover_server_objects(value) == [str(leftover)]
    assert leftover.read_bytes() == b"synthetic stale data"
    leftover.unlink()
    assert worker._leftover_server_objects(value) == []
    assert unrelated.read_bytes() == b"unrelated data"


def test_storage_binding_survives_journal_reload_but_is_not_exported(tmp_path):
    value = journal(tmp_path)
    path = tmp_path / "journal.json"
    worker._atomic_write(path, value)
    restored = worker._read_journal(path)
    assert restored == value
    assert worker._leftover_server_objects(restored) == []
    manifest = json.dumps(worker._final_manifest(restored))
    assert "storage_roots" not in manifest
    assert str(tmp_path) not in manifest


@pytest.mark.parametrize("change", ["missing", "replacement", "symlink", "permissions"])
def test_empty_inventory_does_not_hide_an_unverifiable_storage_root(tmp_path, change):
    value = journal(tmp_path)
    root = tmp_path / "raw"
    if change == "missing":
        root.rmdir()
    elif change in {"replacement", "symlink"}:
        saved = tmp_path / "original-raw"
        root.rename(saved)
        if change == "replacement":
            root.mkdir(mode=0o700)
        else:
            root.symlink_to(saved, target_is_directory=True)
    else:
        root.chmod(0o755)
    with pytest.raises(worker.AccountDeletionWorkerError):
        worker._leftover_server_objects(value)


@pytest.mark.parametrize("quarantine", [worker.RETENTION_QUARANTINE_DIRECTORY_NAME,
                                         worker.REPORT_DELETION_QUARANTINE_DIRECTORY_NAME])
def test_report_quarantines_are_scanned_without_removing_files(tmp_path, quarantine):
    value = journal(tmp_path)
    value["objects"] = [{"report_id": REPORT_ID, "source_path": str(tmp_path / "uploads" / f"{REPORT_ID}.wse")}]
    directory = tmp_path / "uploads" / quarantine
    directory.mkdir(mode=0o700)
    leftover = directory / f"{REPORT_ID}.jpg"
    leftover.write_bytes(b"synthetic")
    assert worker._leftover_server_objects(value) == [str(leftover)]
    assert leftover.exists()


def test_quarantine_symlink_is_not_treated_as_an_absent_directory(tmp_path):
    value = journal(tmp_path)
    (tmp_path / "uploads" / worker.RETENTION_QUARANTINE_DIRECTORY_NAME).symlink_to(
        tmp_path / "absent", target_is_directory=True,
    )
    with pytest.raises(worker.AccountDeletionWorkerError):
        worker._leftover_server_objects(value)


def test_directory_replacement_during_scan_is_detected(tmp_path, monkeypatch):
    value = journal(tmp_path)
    root = tmp_path / "raw"
    original_listdir = os.listdir

    def replace_after_list(descriptor):
        names = original_listdir(descriptor)
        if isinstance(descriptor, int) and os.fstat(descriptor).st_ino == value["storage_roots"]["raw"]["inode"]:
            root.rename(tmp_path / "old-raw")
            root.mkdir(mode=0o700)
        return names

    monkeypatch.setattr(worker.os, "listdir", replace_after_list)
    with pytest.raises(worker.AccountDeletionWorkerError):
        worker._leftover_server_objects(value)


@pytest.mark.parametrize("bad", ["missing", "relative", "boolean_inode", "source_mismatch", "duplicate"])
def test_v5_journal_rejects_missing_or_inconsistent_storage_bindings(tmp_path, bad):
    value = journal(tmp_path)
    if bad == "missing":
        del value["storage_roots"]
    elif bad == "relative":
        value["storage_roots"]["raw"]["path"] = "relative/raw"
    elif bad == "boolean_inode":
        value["storage_roots"]["raw"]["inode"] = True
    elif bad == "source_mismatch":
        value["objects"] = [{"source_path": "/wrong-root/report.wse"}]
    else:
        value["storage_roots"]["raw"] = copy.deepcopy(value["storage_roots"]["uploads"])
    path = tmp_path / "journal.json"
    worker._atomic_write(path, value)
    with pytest.raises(worker.AccountDeletionWorkerError, match="storage root"):
        worker._read_journal(path)


def test_legacy_manifest_only_inventory_cannot_claim_an_empty_scan():
    value = {"schema_version": worker.FROZEN_ENROLLMENT_JOURNAL_SCHEMA,
             "objects": [], "raw_collections": [{"collection_id": COLLECTION_ID}], "raw_objects": []}
    with pytest.raises(worker.AccountDeletionWorkerError, match="legacy raw inventory lacks"):
        worker._leftover_server_objects(value)
