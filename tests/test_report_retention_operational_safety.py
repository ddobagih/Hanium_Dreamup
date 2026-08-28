from __future__ import annotations

from datetime import UTC, datetime, timedelta
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

import scripts.check_report_retention_dry_run as retention

from scripts.check_report_retention_dry_run import (
    exclusive_maintenance_lock,
    validate_predelete_backup,
    validate_predelete_restore_receipt,
)


TRUSTED_BACKUP_SIGNER = "a" * 40
TRUSTED_RESTORE_SIGNER = "b" * 40


@pytest.fixture(autouse=True)
def trusted_test_signatures(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        retention,
        "verify_signed_backup_manifest",
        lambda path, **_kwargs: json.loads(path.read_text(encoding="utf-8")),
    )
    monkeypatch.setattr(
        retention,
        "verify_signed_json_document_with_digest",
        lambda document, _signature, trusted: (
            json.loads(document.read_text(encoding="utf-8")),
            trusted.lower(),
            hashlib.sha256(document.read_bytes()).hexdigest(),
        ),
    )


def _write_backup(tmp_path: Path, *, now: datetime, lock_identity: str) -> tuple[Path, dict[str, str]]:
    artifacts: dict[str, str] = {}
    for name, content in (
        ("reports.dump.gpg", b"database"),
        ("uploads.tar.gz.gpg", b"uploads"),
    ):
        artifact = tmp_path / name
        artifact.write_bytes(content)
        artifacts[name] = hashlib.sha256(content).hexdigest()
    manifest = tmp_path / "manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.backup.v1",
                "run_id": "backup-current",
                "created_at": now.isoformat(),
                "database_identity_sha256": "1" * 64,
                "upload_root_identity_sha256": "2" * 64,
                "signer_fingerprint": TRUSTED_BACKUP_SIGNER,
                "encryption_at_rest": "openpgp",
                "snapshot_boundary": {
                    "writes_quiesced_by_operator": True,
                    "maintenance_lock_identity_sha256": lock_identity,
                    "maintenance_lock_device_inode": "1:2",
                    "lock_acquired_at": (now - timedelta(minutes=3)).isoformat(),
                    "started_at": (now - timedelta(minutes=2)).isoformat(),
                    "finished_at": (now - timedelta(minutes=1)).isoformat(),
                },
                "source_consistency": {
                    "ready": True,
                    "report_image_count": 2,
                    "upload_file_count": 2,
                    "missing_count": 0,
                    "orphan_count": 0,
                    "missing_image_hash_count": 0,
                    "image_hash_mismatch_count": 0,
                    "snapshot_content_sha256": "7" * 64,
                },
                "artifacts_sha256": artifacts,
            }
        ),
        encoding="utf-8",
    )
    (tmp_path / "manifest.json.sig").write_bytes(b"signature")
    return manifest, artifacts


def _write_restore(
    path: Path,
    *,
    restored_at: datetime,
    artifacts: dict[str, str],
    matched: int = 2,
    orphan: int = 0,
) -> Path:
    path.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.restore-drill.v1",
                "restored_at": restored_at.isoformat(),
                "backup_run_id": "backup-current",
                "source_database_identity_sha256": "1" * 64,
                "source_upload_root_identity_sha256": "2" * 64,
                "target_database_identity_sha256": "8" * 64,
                "target_upload_root_identity_sha256": "9" * 64,
                "receipt_signer_fingerprint": TRUSTED_RESTORE_SIGNER,
                "trusted_signer_fingerprint": TRUSTED_BACKUP_SIGNER,
                "artifacts_sha256": artifacts,
                "hashes_verified": True,
                "encrypted_backup_verified": True,
                "manifest_signature_verified": True,
                "database_restore_completed": True,
                "uploads_restore_completed": True,
                "restore_upload_snapshot_sha256": "8" * 64,
                "restore_tree_device_inode": "10:20",
                "target_was_explicit": True,
                "report_upload_consistency_verified": True,
                "consistency_counts": {
                    "restored_report_count": 2,
                    "image_reference_count": 2,
                    "matched_image_count": matched,
                    "missing_image_count": 0 if matched == 2 else 1,
                    "missing_image_hash_count": 0,
                    "image_hash_mismatch_count": 0,
                    "unsafe_image_path_count": 0,
                    "restored_upload_file_count": matched + orphan,
                    "orphan_upload_file_count": orphan,
                },
            }
        ),
        encoding="utf-8",
    )
    path.with_name(f"{path.name}.sig").write_bytes(b"signature")
    return path


def test_retention_requires_same_lock_backup_and_consistent_restore(tmp_path: Path) -> None:
    now = datetime.now(UTC).replace(microsecond=0)
    lock_identity = "3" * 64
    manifest, artifacts = _write_backup(tmp_path, now=now - timedelta(minutes=20), lock_identity=lock_identity)
    backup = validate_predelete_backup(
        manifest,
        trusted_signer_fingerprint=TRUSTED_BACKUP_SIGNER,
        database_identity="1" * 64,
        upload_root_identity="2" * 64,
        maintenance_lock_identity=lock_identity,
        maintenance_lock_device_inode="1:2",
        started_at=now,
    )
    receipt = _write_restore(
        tmp_path / "restore.json",
        restored_at=now - timedelta(minutes=10),
        artifacts=artifacts,
    )

    result = validate_predelete_restore_receipt(
        receipt,
        trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
        backup=backup,
        database_identity="1" * 64,
        upload_root_identity="2" * 64,
        started_at=now,
    )
    assert result["artifacts_sha256"] == artifacts
    assert result["restore_upload_snapshot_sha256"] == "8" * 64
    assert result["restore_tree_device_inode"] == "10:20"
    assert result["target_database_identity_sha256"] == "8" * 64

    payload = json.loads(receipt.read_text(encoding="utf-8"))
    payload.pop("restore_upload_snapshot_sha256")
    receipt.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="upload snapshot hash"):
        validate_predelete_restore_receipt(
            receipt,
            trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            backup=backup,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            started_at=now,
        )

    _write_restore(
        receipt,
        restored_at=now - timedelta(minutes=10),
        artifacts=artifacts,
    )
    payload = json.loads(receipt.read_text(encoding="utf-8"))
    payload["restore_tree_device_inode"] = "invalid"
    receipt.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="upload tree identity"):
        validate_predelete_restore_receipt(
            receipt,
            trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            backup=backup,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            started_at=now,
        )

    _write_restore(
        receipt,
        restored_at=now - timedelta(minutes=10),
        artifacts=artifacts,
    )
    payload = json.loads(receipt.read_text(encoding="utf-8"))
    payload["target_database_identity_sha256"] = payload["source_database_identity_sha256"]
    receipt.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="target database must differ"):
        validate_predelete_restore_receipt(
            receipt,
            trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            backup=backup,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            started_at=now,
        )

    _write_restore(
        receipt,
        restored_at=now - timedelta(minutes=10),
        artifacts=artifacts,
        matched=1,
    )
    with pytest.raises(ValueError, match="report/upload consistency"):
        validate_predelete_restore_receipt(
            receipt,
            trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            backup=backup,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            started_at=now,
        )

    _write_restore(
        receipt,
        restored_at=now - timedelta(minutes=10),
        artifacts=artifacts,
        orphan=1,
    )
    with pytest.raises(ValueError, match="report/upload consistency"):
        validate_predelete_restore_receipt(
            receipt,
            trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            backup=backup,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            started_at=now,
        )


def test_retention_uses_restore_payload_bound_to_detached_signature(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    now = datetime.now(UTC).replace(microsecond=0)
    manifest, artifacts = _write_backup(
        tmp_path,
        now=now - timedelta(minutes=20),
        lock_identity="3" * 64,
    )
    backup = validate_predelete_backup(
        manifest,
        trusted_signer_fingerprint=TRUSTED_BACKUP_SIGNER,
        database_identity="1" * 64,
        upload_root_identity="2" * 64,
        maintenance_lock_identity="3" * 64,
        maintenance_lock_device_inode="1:2",
        started_at=now,
    )
    receipt = _write_restore(
        tmp_path / "restore.json",
        restored_at=now - timedelta(minutes=10),
        artifacts=artifacts,
    )
    replacement_payload = json.loads(receipt.read_text(encoding="utf-8"))
    signed_payload = dict(replacement_payload)
    signed_payload.pop("restore_upload_snapshot_sha256")
    receipt.write_text(json.dumps(signed_payload), encoding="utf-8")

    def verify_then_replace(
        document: Path,
        _signature: Path,
        trusted: str,
    ) -> tuple[dict[str, object], str, str]:
        signed_sha256 = hashlib.sha256(document.read_bytes()).hexdigest()
        document.write_text(json.dumps(replacement_payload), encoding="utf-8")
        return signed_payload, trusted.lower(), signed_sha256

    monkeypatch.setattr(
        retention,
        "verify_signed_json_document_with_digest",
        verify_then_replace,
    )
    with pytest.raises(ValueError, match="upload snapshot hash"):
        validate_predelete_restore_receipt(
            receipt,
            trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            backup=backup,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            started_at=now,
        )

    signed_bytes = json.dumps(replacement_payload).encode()
    replacement_bytes = b'{"unsigned":true}'
    receipt.write_bytes(signed_bytes)

    def verify_valid_then_replace(
        document: Path,
        _signature: Path,
        trusted: str,
    ) -> tuple[dict[str, object], str, str]:
        signed_sha256 = hashlib.sha256(document.read_bytes()).hexdigest()
        document.write_bytes(replacement_bytes)
        return replacement_payload, trusted.lower(), signed_sha256

    monkeypatch.setattr(
        retention,
        "verify_signed_json_document_with_digest",
        verify_valid_then_replace,
    )
    result = validate_predelete_restore_receipt(
        receipt,
        trusted_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
        backup=backup,
        database_identity="1" * 64,
        upload_root_identity="2" * 64,
        started_at=now,
    )
    assert result["receipt_sha256"] == hashlib.sha256(signed_bytes).hexdigest()
    assert result["receipt_sha256"] != hashlib.sha256(replacement_bytes).hexdigest()


def test_maintenance_lock_rejects_unsafe_existing_permissions(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(retention, "_validate_trusted_maintenance_lock_parent", lambda *_args: None)
    lock_path = tmp_path / "maintenance" / "walksafe.lock"
    lock_parent = lock_path.parent
    lock_parent.mkdir(mode=0o700)
    lock_path.touch(mode=0o600)
    with exclusive_maintenance_lock(lock_path) as lock:
        assert lock["identity_sha256"]
        assert lock["device_inode"] == (
            f"{lock_path.stat().st_dev}:{lock_path.stat().st_ino}"
        )
        assert lock_path.stat().st_mode & 0o777 == 0o600

    lock_path.chmod(0o644)
    with pytest.raises(ValueError, match="metadata is unsafe"):
        with exclusive_maintenance_lock(lock_path):
            pass


def test_maintenance_lock_requires_preprovisioned_single_link_leaf(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(retention, "_validate_trusted_maintenance_lock_parent", lambda *_args: None)
    lock_parent = tmp_path / "maintenance"
    lock_parent.mkdir(mode=0o700)
    lock_path = lock_parent / "walksafe.lock"

    with pytest.raises(ValueError, match="must already exist"):
        with exclusive_maintenance_lock(lock_path):
            pytest.fail("missing maintenance lock must not be entered")
    assert not lock_path.exists()

    lock_path.touch(mode=0o600)
    os.link(lock_path, lock_parent / "walksafe.alias")
    with pytest.raises(ValueError, match="metadata is unsafe"):
        with exclusive_maintenance_lock(lock_path):
            pytest.fail("hard-linked maintenance lock must not be entered")


def test_exclusive_maintenance_lock_contends_on_leaf_inode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(retention, "_validate_trusted_maintenance_lock_parent", lambda *_args: None)
    lock_parent = tmp_path / "maintenance"
    lock_parent.mkdir(mode=0o700)
    lock_path = lock_parent / "walksafe.lock"
    lock_path.touch(mode=0o600)
    descriptor = os.open(lock_path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_SH | fcntl.LOCK_NB)
        with pytest.raises(TimeoutError, match="timed out acquiring maintenance lock"):
            with exclusive_maintenance_lock(lock_path, timeout_seconds=0):
                pytest.fail("shared leaf lock must block retention")
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def test_maintenance_lock_rejects_acl_inspection_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(retention, "_validate_trusted_maintenance_lock_parent", lambda *_args: None)
    lock_parent = tmp_path / "maintenance"
    lock_parent.mkdir(mode=0o700)
    lock_path = lock_parent / "walksafe.lock"
    lock_path.touch(mode=0o600)
    monkeypatch.setattr(
        retention.os,
        "listxattr",
        lambda _descriptor: (_ for _ in ()).throw(OSError(errno.EACCES, "denied")),
    )

    with pytest.raises(ValueError, match="metadata is unsafe"):
        with exclusive_maintenance_lock(lock_path):
            pytest.fail("uninspectable ACL state must not be entered")


def test_maintenance_lock_group_name_resolves_to_gid(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_MAINTENANCE_LOCK_GROUP", "walksafe-lock")
    monkeypatch.setattr(
        retention.grp,
        "getgrnam",
        lambda name: SimpleNamespace(gr_gid=1234) if name == "walksafe-lock" else None,
    )

    assert retention._maintenance_lock_group_gid_from_environment() == 1234


def test_deployment_requires_maintenance_lock_group(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.delenv("WALKSAFE_MAINTENANCE_LOCK_GROUP", raising=False)

    with pytest.raises(ValueError, match="WALKSAFE_MAINTENANCE_LOCK_GROUP is required"):
        retention._maintenance_lock_group_gid_from_environment()


def test_deployment_upload_group_resolves_by_name_and_rejects_root(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv(
        "WALKSAFE_UPLOAD_BACKUP_READER_GROUP",
        "walksafe-backup-readers",
    )
    monkeypatch.setattr(
        retention.grp,
        "getgrnam",
        lambda name: SimpleNamespace(gr_gid=1234)
        if name == "walksafe-backup-readers"
        else None,
    )
    assert retention._upload_backup_reader_group_gid_from_environment() == 1234

    monkeypatch.setattr(
        retention.grp,
        "getgrnam",
        lambda _name: SimpleNamespace(gr_gid=0),
    )
    with pytest.raises(ValueError, match="must not be root"):
        retention._upload_backup_reader_group_gid_from_environment()


def test_maintenance_lock_closes_parent_descriptor_when_initial_fstat_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    lock_parent = tmp_path / "maintenance"
    lock_parent.mkdir(mode=0o700)
    opened_descriptors: list[int] = []
    real_fstat = os.fstat

    def failed_initial_fstat(descriptor: int) -> os.stat_result:
        opened_descriptors.append(descriptor)
        raise OSError(errno.EIO, "injected fstat failure")

    monkeypatch.setattr(retention.os, "fstat", failed_initial_fstat)

    with pytest.raises(OSError, match="injected fstat failure"):
        with exclusive_maintenance_lock(lock_parent / "walksafe.lock"):
            pass

    assert len(opened_descriptors) == 1
    with pytest.raises(OSError) as exc_info:
        real_fstat(opened_descriptors[0])
    assert exc_info.value.errno == errno.EBADF


def test_maintenance_lock_detects_path_swap_after_original_is_restored(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(retention, "_validate_trusted_maintenance_lock_parent", lambda *_args: None)
    lock_path = tmp_path / "maintenance" / "walksafe.lock"
    lock_path.parent.mkdir(mode=0o700)
    lock_path.touch(mode=0o600)
    displaced = lock_path.with_suffix(".original")
    second_entered = False

    with pytest.raises(ValueError, match="path or parent changed"):
        with exclusive_maintenance_lock(lock_path):
            lock_path.rename(displaced)
            lock_path.touch(mode=0o600)
            with exclusive_maintenance_lock(lock_path, timeout_seconds=0):
                second_entered = True
            lock_path.unlink()
            displaced.rename(lock_path)
    assert second_entered is True


def test_maintenance_lock_rejects_replaceable_parent(tmp_path: Path) -> None:
    lock_parent = tmp_path / "maintenance"
    lock_parent.mkdir(mode=0o700)
    with pytest.raises(ValueError, match="maintenance lock authority"):
        with exclusive_maintenance_lock(lock_parent / "walksafe.lock"):
            pass


def test_maintenance_lock_rejects_user_owned_higher_ancestor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service_directory = tmp_path / "service"
    trusted_immediate_ancestor = service_directory / "trusted"
    lock_parent = trusted_immediate_ancestor / "maintenance"
    lock_parent.mkdir(parents=True, mode=0o700)
    service_directory.chmod(0o700)
    trusted_immediate_ancestor.chmod(0o755)
    higher_ancestor_identity = (service_directory.stat().st_dev, service_directory.stat().st_ino)
    authority_paths = [Path("/")]
    for component in trusted_immediate_ancestor.relative_to("/").parts:
        authority_paths.append(authority_paths[-1] / component)
    simulated_safe_paths = set(authority_paths) - {service_directory}
    simulated_safe_identities = {
        (path.stat().st_dev, path.stat().st_ino) for path in simulated_safe_paths
    }
    visited_identities: list[tuple[int, int]] = []
    real_fstat = os.fstat
    real_access = os.access

    def only_higher_ancestor_remains_user_owned(descriptor: int) -> os.stat_result:
        metadata = real_fstat(descriptor)
        identity = (metadata.st_dev, metadata.st_ino)
        visited_identities.append(identity)
        if identity not in simulated_safe_identities:
            return metadata
        fields = list(metadata)
        fields[0] = (fields[0] & ~0o7777) | 0o755
        fields[4] = 0
        return os.stat_result(fields)

    def simulated_safe_ancestors_appear_non_writable(
        path: str,
        mode: int,
        *,
        dir_fd: int | None = None,
        effective_ids: bool = False,
        follow_symlinks: bool = True,
    ) -> bool:
        if dir_fd is not None:
            metadata = real_fstat(dir_fd)
            if (metadata.st_dev, metadata.st_ino) in simulated_safe_identities:
                return False
        if dir_fd is None and Path(path) in simulated_safe_paths:
            return False
        return real_access(
            path,
            mode,
            dir_fd=dir_fd,
            effective_ids=effective_ids,
            follow_symlinks=follow_symlinks,
        )

    descriptor = os.open(lock_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(retention.os, "fstat", only_higher_ancestor_remains_user_owned)
    monkeypatch.setattr(retention.os, "access", simulated_safe_ancestors_appear_non_writable)
    try:
        with pytest.raises(ValueError, match="authority ancestors"):
            retention._validate_trusted_maintenance_lock_parent(lock_parent, descriptor)
        assert higher_ancestor_identity in visited_identities
    finally:
        os.close(descriptor)


def test_backup_rejects_different_maintenance_lock(tmp_path: Path) -> None:
    now = datetime.now(UTC).replace(microsecond=0)
    manifest, _artifacts = _write_backup(tmp_path, now=now - timedelta(minutes=5), lock_identity="3" * 64)
    with pytest.raises(ValueError, match="did not use the retention maintenance lock"):
        validate_predelete_backup(
            manifest,
            trusted_signer_fingerprint=TRUSTED_BACKUP_SIGNER,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            maintenance_lock_identity="4" * 64,
            maintenance_lock_device_inode="1:2",
            started_at=now,
        )


def test_backup_rejects_different_maintenance_lock_authority(tmp_path: Path) -> None:
    now = datetime.now(UTC).replace(microsecond=0)
    manifest, _artifacts = _write_backup(
        tmp_path,
        now=now - timedelta(minutes=5),
        lock_identity="3" * 64,
    )
    with pytest.raises(ValueError, match="maintenance lock authority"):
        validate_predelete_backup(
            manifest,
            trusted_signer_fingerprint=TRUSTED_BACKUP_SIGNER,
            database_identity="1" * 64,
            upload_root_identity="2" * 64,
            maintenance_lock_identity="3" * 64,
            maintenance_lock_device_inode="9:9",
            started_at=now,
        )
