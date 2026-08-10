from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import uuid

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from backend.app.models import Report, ReportImageObject  # noqa: E402
from backend.app.services.report_image_crypto import encrypt_report_image  # noqa: E402
from sqlalchemy.dialects import postgresql  # noqa: E402


SCRIPT_PATH = ROOT / "scripts" / "check_report_retention_dry_run.py"
SPEC = importlib.util.spec_from_file_location("check_report_retention_dry_run", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
retention = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(retention)

TRUSTED_BACKUP_SIGNER = "a" * 40
TRUSTED_RESTORE_SIGNER = "b" * 40
TEST_DATABASE_URL = (
    "postgresql+psycopg://walksafe_test:test-only@127.0.0.1:5432/"
    "walksafe_test?sslmode=disable&gssencmode=disable"
)


def encrypted_report_fixture(
    upload_dir: Path,
    *,
    plaintext: bytes,
) -> tuple[Report, ReportImageObject, Path]:
    report_id = uuid.uuid4()
    encrypted = encrypt_report_image(
        plaintext,
        report_id=report_id,
        content_type="image/jpeg",
        key_id="test-report-key-v1",
        key=b"k" * 32,
    )
    report = Report(
        id=report_id,
        status="resolved",
        class_id=0,
        class_name="damaged_tactile_block",
        confidence=0.9,
        bbox_x=0.1,
        bbox_y=0.1,
        bbox_width=0.2,
        bbox_height=0.2,
        captured_at=datetime(2025, 1, 1, tzinfo=UTC),
        source="server",
        image_path=f"/uploads/{report_id}.jpg",
        image_content_type="image/jpeg",
        payload={"image_sha256": encrypted.plaintext_sha256},
        created_at=datetime(2025, 1, 1, tzinfo=UTC),
        updated_at=datetime(2025, 1, 1, tzinfo=UTC),
    )
    image_object = ReportImageObject(
        report_id=report_id,
        storage_name=f"{report_id}.wse",
        envelope_version=1,
        algorithm="AES-256-GCM",
        aad_version=1,
        key_id=encrypted.key_id,
        nonce=encrypted.nonce,
        plaintext_sha256=encrypted.plaintext_sha256,
        plaintext_size=encrypted.plaintext_length,
        envelope_sha256=encrypted.envelope_sha256,
        envelope_size=len(encrypted.envelope),
        content_type=encrypted.content_type,
    )
    source = upload_dir / image_object.storage_name
    source.write_bytes(encrypted.envelope)
    source.chmod(0o600)
    return report, image_object, source


def scalar_values(
    statement: object,
    *,
    report: Report,
    image_object: ReportImageObject,
    database_present: bool,
) -> list[object]:
    entity = statement.column_descriptions[0].get("entity")  # type: ignore[attr-defined]
    if not database_present:
        return []
    if entity is Report:
        return [report]
    if entity is ReportImageObject:
        return [image_object]
    raise AssertionError(f"unexpected retention query entity: {entity!r}")


def test_retention_dependency_imports_without_python_exposed_seal_constants() -> None:
    backup_integrity = ROOT / "scripts" / "walksafe_backup_integrity.py"
    probe = f"""
import fcntl
import runpy
for name in (
    'F_ADD_SEALS', 'F_GET_SEALS', 'F_SEAL_WRITE',
    'F_SEAL_GROW', 'F_SEAL_SHRINK', 'F_SEAL_SEAL',
):
    if hasattr(fcntl, name):
        delattr(fcntl, name)
namespace = runpy.run_path({str(backup_integrity)!r}, run_name='_walksafe_seal_probe')
assert namespace['F_ADD_SEALS'] == 1033
assert namespace['F_GET_SEALS'] == 1034
assert namespace['REQUIRED_SNAPSHOT_SEALS'] == 15
"""

    completed = subprocess.run(
        [sys.executable, "-I", "-S", "-B", "-c", probe],
        check=False,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr


def test_retention_dependency_rejects_non_linux_even_with_seal_constants() -> None:
    backup_integrity = ROOT / "scripts" / "walksafe_backup_integrity.py"
    probe = f"""
import fcntl
import runpy
import sys
for name, value in {{
    'F_ADD_SEALS': 1033,
    'F_GET_SEALS': 1034,
    'F_SEAL_WRITE': 8,
    'F_SEAL_GROW': 4,
    'F_SEAL_SHRINK': 2,
    'F_SEAL_SEAL': 1,
}}.items():
    setattr(fcntl, name, value)
sys.platform = 'darwin'
try:
    runpy.run_path({str(backup_integrity)!r}, run_name='_walksafe_non_linux_probe')
except RuntimeError as exc:
    assert 'requires Linux F_ADD_SEALS' in str(exc)
else:
    raise AssertionError('non-Linux backup integrity import unexpectedly succeeded')
"""

    completed = subprocess.run(
        [sys.executable, "-I", "-S", "-B", "-c", probe],
        check=False,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr


@pytest.fixture(autouse=True)
def trusted_test_signatures(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_REPORT_RETENTION_ALLOW_TEST_LOOPBACK", "true")
    monkeypatch.setenv("DATABASE_CONNECT_TIMEOUT_SECONDS", "5")
    monkeypatch.setenv("DATABASE_STATEMENT_TIMEOUT_MS", "10000")
    monkeypatch.setattr(
        retention,
        "_validate_trusted_maintenance_lock_parent",
        lambda _parent, _descriptor: None,
    )
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


def old_row(**overrides: object) -> dict[str, object]:
    row: dict[str, object] = {
        "id": str(uuid.uuid4()),
        "status": "new",
        "source": "server",
        "created_at": "2025-01-01T00:00:00Z",
        "image_path": "/uploads/report.jpg",
        "metadata": {},
    }
    row.update(overrides)
    return row


def test_database_retention_treats_missing_fake_metadata_as_non_demo() -> None:
    statement = retention.database_expired_statement(
        Report,
        as_of=datetime(2026, 7, 11, tzinfo=UTC),
        limit=100,
        lock_rows=False,
    )

    sql = str(statement.compile(dialect=postgresql.dialect())).lower()
    assert "coalesce" in sql
    assert "not coalesce" in sql


def write_backup_manifest(tmp_path: Path, *, database_url: str, upload_dir: Path) -> tuple[Path, Path, Path]:
    backup_dir = tmp_path / f"backup-{uuid.uuid4().hex}"
    backup_dir.mkdir()
    lock_dir = tmp_path / f"maintenance-{uuid.uuid4().hex}"
    lock_dir.mkdir(mode=0o700)
    lock_path = lock_dir / "walksafe.lock"
    lock_identity = retention.path_identity_sha256(lock_path)
    lock_device_inode = f"{lock_dir.stat().st_dev}:{lock_dir.stat().st_ino}"
    artifacts: dict[str, str] = {}
    for name, content in {
        "reports.dump.gpg": b"encrypted-database",
        "uploads.tar.gz.gpg": b"encrypted-uploads",
    }.items():
        (backup_dir / name).write_bytes(content)
        artifacts[name] = hashlib.sha256(content).hexdigest()
    manifest = backup_dir / "manifest.json"
    backup_created_at = datetime.now(UTC)
    manifest.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.backup.v1",
                "run_id": "backup-before-retention",
                "created_at": backup_created_at.isoformat(),
                "database_identity_sha256": retention.database_identity_sha256(database_url),
                "upload_root_identity_sha256": retention.path_identity_sha256(upload_dir),
                "signer_fingerprint": TRUSTED_BACKUP_SIGNER,
                "encryption_at_rest": "openpgp",
                "snapshot_boundary": {
                    "writes_quiesced_by_operator": True,
                    "maintenance_lock_identity_sha256": lock_identity,
                    "maintenance_lock_device_inode": lock_device_inode,
                    "lock_acquired_at": (backup_created_at - timedelta(seconds=3)).isoformat(),
                    "started_at": (backup_created_at - timedelta(seconds=2)).isoformat(),
                    "finished_at": (backup_created_at - timedelta(seconds=1)).isoformat(),
                },
                "source_consistency": {
                    "ready": True,
                    "report_image_count": 1,
                    "upload_file_count": 1,
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
    (backup_dir / "manifest.json.sig").write_bytes(b"signature")
    restore_receipt = backup_dir / "restore-receipt.json"
    restore_receipt.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.restore-drill.v1",
                "restored_at": datetime.now(UTC).isoformat(),
                "backup_run_id": "backup-before-retention",
                "source_database_identity_sha256": retention.database_identity_sha256(database_url),
                "source_upload_root_identity_sha256": retention.path_identity_sha256(upload_dir),
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
                    "restored_report_count": 1,
                    "image_reference_count": 1,
                    "matched_image_count": 1,
                    "missing_image_count": 0,
                    "missing_image_hash_count": 0,
                    "image_hash_mismatch_count": 0,
                    "unsafe_image_path_count": 0,
                    "restored_upload_file_count": 1,
                    "orphan_upload_file_count": 0,
                },
            }
        ),
        encoding="utf-8",
    )
    restore_receipt.with_name(f"{restore_receipt.name}.sig").write_bytes(b"signature")
    return manifest, restore_receipt, lock_path


def test_performance_exclusion_does_not_change_demo_retention_bucket() -> None:
    row = old_row(metadata={"performance_excluded": True, "coordinate_gate_status": "pending"})

    assert retention.is_fake_demo(row) is False
    assert retention.retention_bucket(row) == ("active", 180)


def test_fake_demo_retention_stays_shorter_than_field_candidate() -> None:
    fake = old_row(source="fake", created_at="2026-01-01T00:00:00Z")
    field = old_row(source="server", created_at="2026-01-01T00:00:00Z")
    as_of = datetime(2026, 2, 15, tzinfo=UTC)

    assert retention.candidate_for_row(fake, as_of=as_of) is not None
    assert retention.candidate_for_row(field, as_of=as_of) is None


@pytest.mark.parametrize(
    "image_path",
    ["relative.jpg", "/private/report.jpg", "/uploads/../private.jpg", "/uploads/nested/report.jpg"],
)
def test_retention_rejects_image_paths_outside_flat_upload_root(tmp_path: Path, image_path: str) -> None:
    with pytest.raises(ValueError, match="unsafe"):
        retention.image_file_for_report(tmp_path, image_path)


def test_recovery_copy_is_durable_without_removing_the_database_referenced_original(tmp_path: Path) -> None:
    source = tmp_path / "report.jpg"
    recovery = tmp_path / ".retention-quarantine" / "run" / "report.jpg"
    source.write_bytes(b"original-image")
    recovery.parent.mkdir(parents=True, mode=0o700)

    source_identity, recovery_identity = retention.create_recovery_copy(source, recovery)

    assert source.read_bytes() == b"original-image"
    assert recovery.read_bytes() == b"original-image"
    assert source_identity == retention.file_identity(source.stat(follow_symlinks=False))
    assert recovery_identity == retention.file_identity(recovery.stat(follow_symlinks=False))


def test_recovery_copy_removes_its_exact_partial_file_when_copy_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = tmp_path / "report.jpg"
    source.write_bytes(b"original-image")
    recovery = tmp_path / "quarantine" / "report.jpg"
    recovery.parent.mkdir(mode=0o700)

    def fail_after_prefix(
        _source_fd: int,
        target_fd: int,
        **_kwargs: object,
    ) -> None:
        os.write(target_fd, b"partial")
        raise OSError("injected copy failure")

    monkeypatch.setattr(retention, "_copy_recovery_file_descriptors", fail_after_prefix)

    with pytest.raises(OSError, match="injected copy failure"):
        retention.create_recovery_copy(source, recovery)

    assert source.read_bytes() == b"original-image"
    assert not recovery.exists()
    assert not list(recovery.parent.glob(".walksafe-retention-delete-*"))


def test_recovery_copy_surfaces_partial_cleanup_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = tmp_path / "report.jpg"
    source.write_bytes(b"original-image")
    recovery = tmp_path / "quarantine" / "report.jpg"
    recovery.parent.mkdir(mode=0o700)

    def fail_copy(*_args: object, **_kwargs: object) -> None:
        raise OSError("injected copy failure")

    monkeypatch.setattr(retention, "_copy_recovery_file_descriptors", fail_copy)
    monkeypatch.setattr(
        retention,
        "unlink_file_with_binding_identity",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(ValueError("cleanup failed")),
    )

    with pytest.raises(RuntimeError, match="partial retention recovery cleanup failed"):
        retention.create_recovery_copy(source, recovery)

    assert recovery.exists()


def test_recovery_copy_removes_created_file_when_initial_target_fstat_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = tmp_path / "report.jpg"
    source.write_bytes(b"original-image")
    recovery = tmp_path / "quarantine" / "report.jpg"
    recovery.parent.mkdir(mode=0o700)
    real_open = retention.os.open
    real_fstat = retention.os.fstat
    target_fd = -1
    failed = False

    def track_target_open(path: object, flags: int, *args: object, **kwargs: object) -> int:
        nonlocal target_fd
        descriptor = real_open(path, flags, *args, **kwargs)
        if flags & os.O_EXCL:
            target_fd = descriptor
        return descriptor

    def fail_first_target_fstat(descriptor: int) -> os.stat_result:
        nonlocal failed
        if descriptor == target_fd and not failed:
            failed = True
            raise OSError("injected target fstat failure")
        return real_fstat(descriptor)

    monkeypatch.setattr(retention.os, "open", track_target_open)
    monkeypatch.setattr(retention.os, "fstat", fail_first_target_fstat)

    with pytest.raises(OSError, match="injected target fstat failure"):
        retention.create_recovery_copy(source, recovery)

    assert failed is True
    assert source.read_bytes() == b"original-image"
    assert not recovery.exists()


def test_retention_rejects_upload_leaf_symlink_without_touching_its_target(
    tmp_path: Path,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    target = tmp_path / "outside.jpg"
    target.write_bytes(b"outside-image")
    source = upload_dir / "linked.jpg"
    source.symlink_to(target)
    recovery = tmp_path / "quarantine" / "linked.jpg"
    recovery.parent.mkdir(mode=0o700)

    selected = retention.image_file_for_report(upload_dir, "/uploads/linked.jpg")

    assert selected == source
    assert selected.is_symlink()
    with pytest.raises(ValueError, match="regular non-symlink"):
        retention.create_recovery_copy(selected, recovery)
    assert target.read_bytes() == b"outside-image"
    assert source.is_symlink()
    assert not recovery.exists()


def test_retention_rejects_hardlinked_source_image(
    tmp_path: Path,
) -> None:
    source = tmp_path / "report.jpg"
    source.write_bytes(b"original-image")
    other_link = tmp_path / "other-link.jpg"
    os.link(source, other_link)
    recovery = tmp_path / "quarantine" / "report.jpg"
    recovery.parent.mkdir(mode=0o700)

    with pytest.raises(ValueError, match="regular non-symlink"):
        retention.create_recovery_copy(source, recovery)

    assert source.read_bytes() == b"original-image"
    assert other_link.read_bytes() == b"original-image"
    assert not recovery.exists()


def test_quarantine_symlink_component_is_rejected_without_external_write(
    tmp_path: Path,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    outside = tmp_path / "outside"
    outside.mkdir(mode=0o700)
    (upload_dir / ".retention-quarantine").symlink_to(outside, target_is_directory=True)
    metadata = upload_dir.stat(follow_symlinks=False)

    with pytest.raises((OSError, ValueError)):
        retention.prepare_private_quarantine(
            upload_dir,
            "a" * 32,
            expected_upload_identity=(
                metadata.st_dev,
                metadata.st_ino,
                metadata.st_uid,
                metadata.st_mode,
            ),
        )

    assert not list(outside.iterdir())


def test_partial_quarantine_acquisition_removes_directories_it_created(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    run_id = "a" * 32
    metadata = upload_dir.stat(follow_symlinks=False)
    real_validate = retention._validate_private_child_directory

    def fail_run_validation(
        parent_fd: int,
        name: str,
        descriptor: int,
        **kwargs: object,
    ) -> os.stat_result:
        if name == run_id:
            raise RuntimeError("run directory open failed")
        return real_validate(parent_fd, name, descriptor, **kwargs)

    monkeypatch.setattr(retention, "_validate_private_child_directory", fail_run_validation)

    with pytest.raises(RuntimeError, match="run directory open failed"):
        retention.prepare_private_quarantine(
            upload_dir,
            run_id,
            expected_upload_identity=(
                metadata.st_dev,
                metadata.st_ino,
                metadata.st_uid,
                metadata.st_mode,
            ),
        )

    assert not (upload_dir / ".retention-quarantine").exists()


def test_quarantine_root_path_stat_failure_removes_created_directory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    metadata = upload_dir.stat(follow_symlinks=False)
    real_stat = retention.os.stat
    failed = False

    def fail_first_root_stat(path: object, *args: object, **kwargs: object) -> os.stat_result:
        nonlocal failed
        if path == ".retention-quarantine" and kwargs.get("dir_fd") is not None and not failed:
            failed = True
            raise OSError("injected quarantine root stat failure")
        return real_stat(path, *args, **kwargs)

    monkeypatch.setattr(retention.os, "stat", fail_first_root_stat)

    with pytest.raises(OSError, match="injected quarantine root stat failure"):
        retention.prepare_private_quarantine(
            upload_dir,
            "a" * 32,
            expected_upload_identity=(
                metadata.st_dev,
                metadata.st_ino,
                metadata.st_uid,
                metadata.st_mode,
            ),
        )

    assert failed is True
    assert not (upload_dir / ".retention-quarantine").exists()


def test_upload_root_with_symlink_ancestor_is_rejected(
    tmp_path: Path,
) -> None:
    real_parent = tmp_path / "real"
    upload_dir = real_parent / "uploads"
    upload_dir.mkdir(parents=True, mode=0o700)
    alias = tmp_path / "alias"
    alias.symlink_to(real_parent, target_is_directory=True)
    configured = alias / "uploads"
    metadata = upload_dir.stat(follow_symlinks=False)

    with pytest.raises(ValueError, match="canonical real path|canonical real directory"):
        retention.prepare_private_quarantine(
            configured,
            "a" * 32,
            expected_upload_identity=(
                metadata.st_dev,
                metadata.st_ino,
                metadata.st_uid,
                metadata.st_mode,
            ),
        )

    assert not (upload_dir / ".retention-quarantine").exists()


def test_relative_upload_root_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="absolute"):
        retention.prepare_private_quarantine(
            Path("relative-uploads"),
            "a" * 32,
            expected_upload_identity=(0, 0, 0, 0),
        )


def test_upload_root_replacement_is_rejected_against_initial_identity(
    tmp_path: Path,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    initial = upload_dir.stat(follow_symlinks=False)
    displaced = tmp_path / "uploads.displaced"
    upload_dir.rename(displaced)
    upload_dir.mkdir(mode=0o700)

    with pytest.raises(ValueError, match="private real directory"):
        retention.prepare_private_quarantine(
            upload_dir,
            "a" * 32,
            expected_upload_identity=(
                initial.st_dev,
                initial.st_ino,
                initial.st_uid,
                initial.st_mode,
            ),
        )

    assert not (upload_dir / ".retention-quarantine").exists()


@pytest.mark.parametrize("alias_kind", ["hardlink", "symlink"])
def test_manifest_publisher_rejects_existing_leaf_alias(
    tmp_path: Path,
    alias_kind: str,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    manifest_dir = tmp_path / "retention-audit"
    manifest_dir.mkdir(mode=0o700)
    outside = tmp_path / "outside.json"
    outside.write_text("do-not-overwrite", encoding="utf-8")
    manifest_path = manifest_dir / "manifest.json"
    if alias_kind == "hardlink":
        os.link(outside, manifest_path)
    else:
        manifest_path.symlink_to(outside)
    backup = tmp_path / "backup.json"
    restore = tmp_path / "restore.json"
    lock = tmp_path / "maintenance.lock"
    for artifact in (backup, restore, lock):
        artifact.write_text("fixture", encoding="utf-8")

    with pytest.raises(ValueError, match="new non-alias"):
        retention.RetentionManifestPublisher(
            manifest_path,
            upload_dir=upload_dir,
            backup_manifest_path=backup,
            restore_receipt_path=restore,
            maintenance_lock_path=lock,
            signal_guard=retention.RetentionSignalGuard(),
        )

    assert outside.read_text(encoding="utf-8") == "do-not-overwrite"


def test_manifest_publisher_rejects_non_private_parent(tmp_path: Path) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    manifest_dir = tmp_path / "retention-audit"
    manifest_dir.mkdir(mode=0o755)
    backup = tmp_path / "backup.json"
    restore = tmp_path / "restore.json"
    lock = tmp_path / "maintenance.lock"
    for artifact in (backup, restore, lock):
        artifact.write_text("fixture", encoding="utf-8")

    with pytest.raises(ValueError, match="0700"):
        retention.RetentionManifestPublisher(
            manifest_dir / "manifest.json",
            upload_dir=upload_dir,
            backup_manifest_path=backup,
            restore_receipt_path=restore,
            maintenance_lock_path=lock,
            signal_guard=retention.RetentionSignalGuard(),
        )


def test_manifest_exchange_fsync_failure_restores_previous_canonical_manifest(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    manifest_dir = tmp_path / "retention-audit"
    manifest_dir.mkdir(mode=0o700)
    backup = tmp_path / "backup.json"
    restore = tmp_path / "restore.json"
    lock = tmp_path / "maintenance.lock"
    for artifact in (backup, restore, lock):
        artifact.write_text("fixture", encoding="utf-8")
    manifest_path = manifest_dir / "manifest.json"
    guard = retention.RetentionSignalGuard()
    guard.install()
    try:
        publisher = retention.RetentionManifestPublisher(
            manifest_path,
            upload_dir=upload_dir,
            backup_manifest_path=backup,
            restore_receipt_path=restore,
            maintenance_lock_path=lock,
            signal_guard=guard,
        )
        publisher.write({"version": 1})
        real_fsync = retention.os.fsync
        failed = False

        def fail_first_directory_fsync(descriptor: int) -> None:
            nonlocal failed
            if stat.S_ISDIR(os.fstat(descriptor).st_mode) and not failed:
                failed = True
                raise OSError("injected manifest directory fsync failure")
            real_fsync(descriptor)

        monkeypatch.setattr(retention.os, "fsync", fail_first_directory_fsync)

        with pytest.raises(OSError, match="injected manifest directory fsync failure"):
            publisher.write({"version": 2})

        assert json.loads(manifest_path.read_text(encoding="utf-8")) == {"version": 1}
        assert [path.name for path in manifest_dir.iterdir()] == ["manifest.json"]
        publisher.write({"version": 3})
        assert json.loads(manifest_path.read_text(encoding="utf-8")) == {"version": 3}
    finally:
        guard.close()


def test_manifest_initial_descriptor_stat_failure_removes_owned_temporary_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    manifest_dir = tmp_path / "retention-audit"
    manifest_dir.mkdir(mode=0o700)
    backup = tmp_path / "backup.json"
    restore = tmp_path / "restore.json"
    lock = tmp_path / "maintenance.lock"
    for artifact in (backup, restore, lock):
        artifact.write_text("fixture", encoding="utf-8")
    guard = retention.RetentionSignalGuard()
    guard.install()
    try:
        publisher = retention.RetentionManifestPublisher(
            manifest_dir / "manifest.json",
            upload_dir=upload_dir,
            backup_manifest_path=backup,
            restore_receipt_path=restore,
            maintenance_lock_path=lock,
            signal_guard=guard,
        )
        real_stat = retention.os.stat
        failed = False

        def fail_first_descriptor_stat(
            path: object,
            *args: object,
            **kwargs: object,
        ) -> os.stat_result:
            nonlocal failed
            if isinstance(path, int) and not failed:
                failed = True
                raise OSError("injected manifest descriptor stat failure")
            return real_stat(path, *args, **kwargs)

        monkeypatch.setattr(retention.os, "stat", fail_first_descriptor_stat)

        with pytest.raises(OSError, match="injected manifest descriptor stat failure"):
            publisher.write({"version": 1})

        assert failed is True
        assert list(manifest_dir.iterdir()) == []
    finally:
        guard.close()


def test_quarantine_is_prepared_only_after_database_planning() -> None:
    source = SCRIPT_PATH.read_text(encoding="utf-8")

    assert source.index("reports = list(session.scalars") < source.index(
        ") = prepare_private_quarantine("
    )


def test_atomic_delete_removes_held_inode_but_preserves_new_original_name(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = tmp_path / "report.jpg"
    source.write_bytes(b"original-image")
    expected = retention.file_identity(source.stat(follow_symlinks=False))
    real_rename = retention._rename_noreplace
    replacement_created = False

    def replace_after_holding(directory_fd: int, source_name: str, target_name: str) -> None:
        nonlocal replacement_created
        real_rename(directory_fd, source_name, target_name)
        if source_name == source.name and target_name.startswith(".walksafe-retention-delete-"):
            source.write_bytes(b"replacement-image")
            replacement_created = True

    monkeypatch.setattr(retention, "_rename_noreplace", replace_after_holding)

    retention.unlink_file_with_identity(source, expected)

    assert replacement_created is True
    assert source.read_bytes() == b"replacement-image"
    assert not list(tmp_path.glob(".walksafe-retention-delete-*"))


def test_atomic_delete_never_deletes_replacement_installed_after_full_stat(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = tmp_path / "report.jpg"
    source.write_bytes(b"original-image")
    displaced = tmp_path / "report.displaced.jpg"
    expected = retention.file_identity(source.stat(follow_symlinks=False))
    real_rename = retention._rename_noreplace
    swapped = False

    def replace_before_holding(directory_fd: int, source_name: str, target_name: str) -> None:
        nonlocal swapped
        if source_name == source.name and target_name.startswith(".walksafe-retention-delete-"):
            source.rename(displaced)
            source.write_bytes(b"replacement-image")
            swapped = True
        real_rename(directory_fd, source_name, target_name)

    monkeypatch.setattr(retention, "_rename_noreplace", replace_before_holding)

    with pytest.raises(ValueError, match="identity changed"):
        retention.unlink_file_with_identity(source, expected)

    assert swapped is True
    assert source.read_bytes() == b"replacement-image"
    assert displaced.read_bytes() == b"original-image"
    assert not list(tmp_path.glob(".walksafe-retention-delete-*"))


def test_future_report_row_is_not_a_retention_candidate() -> None:
    as_of = datetime(2026, 7, 17, tzinfo=UTC)

    assert retention.candidate_for_row(
        old_row(created_at=datetime(2026, 7, 18, tzinfo=UTC)),
        as_of=as_of,
    ) is None


@pytest.mark.parametrize(
    "as_of",
    [
        datetime(2026, 7, 17),
        datetime(2026, 7, 17, tzinfo=timezone(timedelta(hours=9))),
    ],
    ids=["naive", "nonzero-offset"],
)
def test_apply_rejects_non_utc_as_of_at_api_boundary(
    as_of: datetime,
    tmp_path: Path,
) -> None:
    with pytest.raises(ValueError, match="timezone-aware UTC"):
        retention.apply_database_retention(
            database_url=TEST_DATABASE_URL,
            upload_dir=tmp_path / "uploads",
            as_of=as_of,
            manifest_path=tmp_path / "manifest.json",
            backup_manifest_path=tmp_path / "backup.json",
        )


@pytest.mark.parametrize("as_of", ["2026-07-17T00:00:00", "2026-07-17T09:00:00+09:00"])
def test_cli_as_of_parser_rejects_naive_and_non_utc_offsets(as_of: str) -> None:
    with pytest.raises(ValueError, match="timezone-aware UTC"):
        retention.parse_as_of(as_of)


def test_apply_requires_every_safety_argument(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setattr(sys, "argv", [str(SCRIPT_PATH), "--apply"])

    assert retention.main() == 2
    assert "--database-url" in json.loads(capsys.readouterr().out)["error"]


def test_apply_rejects_output_markdown_before_any_destructive_work(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [str(SCRIPT_PATH), "--apply", "--output-md", str(tmp_path / "report.jpg")],
    )
    monkeypatch.setattr(
        retention,
        "apply_database_retention",
        lambda **_kwargs: (_ for _ in ()).throw(AssertionError("apply must not run")),
    )

    assert retention.main() == 2
    assert "dry-run only" in json.loads(capsys.readouterr().out)["error"]


def test_retention_rejects_invalid_actor_id(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(sys, "argv", [str(SCRIPT_PATH), "--actor-id", "invalid actor"])

    assert retention.main() == 2
    assert "actor id" in json.loads(capsys.readouterr().out)["error"]


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("DATABASE_CONNECT_TIMEOUT_SECONDS", "0"),
        ("DATABASE_CONNECT_TIMEOUT_SECONDS", "31"),
        ("DATABASE_CONNECT_TIMEOUT_SECONDS", "1.5"),
        ("DATABASE_STATEMENT_TIMEOUT_MS", "0"),
        ("DATABASE_STATEMENT_TIMEOUT_MS", "120001"),
        ("DATABASE_STATEMENT_TIMEOUT_MS", "ten-seconds"),
    ],
)
def test_retention_database_timeouts_are_bounded_positive_integers(
    monkeypatch: pytest.MonkeyPatch,
    name: str,
    value: str,
) -> None:
    monkeypatch.setenv(name, value)

    with pytest.raises(ValueError, match=name):
        retention.retention_database_timeouts()


@pytest.mark.parametrize(
    "database_url",
    [
        "postgresql://user:secret@db.example.com:5432/walksafe"
        "?sslmode=verify-full&gssencmode=disable",
        "postgresql+psycopg://user:secret@db.example.com:5432/walksafe"
        "?sslmode=require&gssencmode=disable",
        "postgresql+psycopg://user:secret@db.example.com:5432/walksafe"
        "?sslmode=verify-full&gssencmode=require",
        "postgresql+psycopg://user:CHANGE_ME@db.example.com:5432/walksafe"
        "?sslmode=verify-full&gssencmode=disable",
    ],
)
def test_production_retention_database_url_is_exact_and_tls_verified(
    monkeypatch: pytest.MonkeyPatch,
    database_url: str,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")

    with pytest.raises(ValueError, match="DATABASE_URL"):
        retention.validated_retention_database_url(database_url)


def test_apply_quarantines_image_commits_database_and_writes_manifest(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    report, image_object, image = encrypted_report_fixture(
        upload_dir,
        plaintext=b"image",
    )
    manifest_dir = tmp_path / "retention-audit"
    manifest_dir.mkdir(mode=0o700)
    manifest_path = manifest_dir / "retention-manifest.json"
    database_url = TEST_DATABASE_URL
    backup_manifest, restore_receipt, lock_path = write_backup_manifest(
        tmp_path, database_url=database_url, upload_dir=upload_dir
    )

    class ScalarResult:
        def __init__(self, values: list[object]) -> None:
            self.values = values

        def all(self) -> list[object]:
            return self.values

    class FakeSession:
        deleted: list[Report] = []
        committed = False
        database_present = True

        def __enter__(self) -> "FakeSession":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def execute(self, _statement: object, _parameters: object = None) -> None:
            return None

        def scalars(self, statement: object) -> ScalarResult:
            return ScalarResult(
                scalar_values(
                    statement,
                    report=report,
                    image_object=image_object,
                    database_present=self.database_present,
                )
            )

        def delete(self, value: Report) -> None:
            self.deleted.append(value)

        def commit(self) -> None:
            self.committed = True
            self.database_present = False

        def rollback(self) -> None:
            raise AssertionError("successful apply must not roll back")

    class FakeEngine:
        disposed = False

        def dispose(self) -> None:
            self.disposed = True

    session = FakeSession()
    engine = FakeEngine()
    engine_arguments: dict[str, object] = {}

    def create_engine(url: str, **kwargs: object) -> FakeEngine:
        engine_arguments["url"] = url
        engine_arguments.update(kwargs)
        return engine

    monkeypatch.setattr("sqlalchemy.create_engine", create_engine)
    monkeypatch.setattr("sqlalchemy.orm.Session", lambda _engine: session)

    result = retention.apply_database_retention(
        database_url=database_url,
        upload_dir=upload_dir,
        as_of=datetime(2026, 7, 11, tzinfo=UTC),
        manifest_path=manifest_path,
        backup_manifest_path=backup_manifest,
        restore_receipt_path=restore_receipt,
        trusted_backup_signer_fingerprint=TRUSTED_BACKUP_SIGNER,
        trusted_restore_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
        maintenance_lock_path=lock_path,
    )

    assert result["status"] == "completed"
    assert result["deleted_count"] == 1
    assert result["batch_size"] == retention.DEFAULT_APPLY_BATCH_SIZE
    assert result["batch_limit_reached"] is False
    assert session.committed is True
    assert session.deleted == [report]
    assert engine.disposed is True
    assert engine_arguments == {
        "url": TEST_DATABASE_URL,
        "pool_pre_ping": True,
        "pool_timeout": 5,
        "connect_args": {
            "connect_timeout": 5,
            "options": "-c statement_timeout=10000",
        },
    }
    assert not image.exists()
    assert not (upload_dir / ".retention-quarantine").exists()
    persisted_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert stat.S_IMODE(manifest_path.stat().st_mode) == 0o600
    assert persisted_manifest["candidate_ids_sha256"] == result["candidate_ids_sha256"]
    assert persisted_manifest["actor_id"] == "system"
    assert persisted_manifest["predelete_backup_run_id"] == "backup-before-retention"
    assert persisted_manifest["maintenance_lock_identity_sha256"] == retention.path_identity_sha256(lock_path)
    assert persisted_manifest["maintenance_lock_device_inode"] == (
        f"{lock_path.parent.stat().st_dev}:{lock_path.parent.stat().st_ino}"
    )
    assert persisted_manifest["predelete_restore_receipt_sha256"] == retention.sha256_file(restore_receipt)
    assert persisted_manifest["images"] == [
        {
            "report_id": str(report.id),
            "storage_name": image_object.storage_name,
            "envelope_sha256": image_object.envelope_sha256,
            "envelope_size": image_object.envelope_size,
            "key_id": image_object.key_id,
            "state": "recovery_copy_created",
        }
    ]


def test_apply_preserves_original_image_when_database_commit_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    report, image_object, image = encrypted_report_fixture(
        upload_dir,
        plaintext=b"original-image",
    )
    original_envelope = image.read_bytes()
    manifest_dir = tmp_path / "retention-audit"
    manifest_dir.mkdir(mode=0o700)
    manifest_path = manifest_dir / "failed-manifest.json"
    database_url = TEST_DATABASE_URL
    backup_manifest, restore_receipt, lock_path = write_backup_manifest(
        tmp_path, database_url=database_url, upload_dir=upload_dir
    )

    class ScalarResult:
        def __init__(self, values: list[object]) -> None:
            self.values = values

        def all(self) -> list[object]:
            return self.values

    class FailingSession:
        rolled_back = False

        def __enter__(self) -> "FailingSession":
            return self

        def __exit__(self, exc_type: object, *_args: object) -> None:
            if exc_type is not None:
                self.rollback()
            return None

        def execute(self, _statement: object, _parameters: object = None) -> None:
            return None

        def scalars(self, statement: object) -> ScalarResult:
            return ScalarResult(
                scalar_values(
                    statement,
                    report=report,
                    image_object=image_object,
                    database_present=True,
                )
            )

        def delete(self, _value: Report) -> None:
            return None

        def commit(self) -> None:
            raise RuntimeError("commit failed")

        def rollback(self) -> None:
            self.rolled_back = True

    class FakeEngine:
        def dispose(self) -> None:
            return None

    session = FailingSession()
    monkeypatch.setattr("sqlalchemy.create_engine", lambda _url, **_kwargs: FakeEngine())
    monkeypatch.setattr("sqlalchemy.orm.Session", lambda _engine: session)

    with pytest.raises(RuntimeError, match="commit failed"):
        retention.apply_database_retention(
            database_url=database_url,
            upload_dir=upload_dir,
            as_of=datetime(2026, 7, 11, tzinfo=UTC),
            manifest_path=manifest_path,
            backup_manifest_path=backup_manifest,
            restore_receipt_path=restore_receipt,
            trusted_backup_signer_fingerprint=TRUSTED_BACKUP_SIGNER,
            trusted_restore_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            maintenance_lock_path=lock_path,
            actor_id="retention.operator",
        )

    assert session.rolled_back is True
    assert image.read_bytes() == original_envelope
    assert not (upload_dir / ".retention-quarantine").exists()
    failed_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert failed_manifest["status"] == "failed_before_commit"
    assert failed_manifest["actor_id"] == "retention.operator"


def _run_apply_with_commit_hook(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    commit_hook: object,
    *,
    manifest_aliases_source: bool = False,
    restore_reuses_source_upload: bool = False,
) -> tuple[dict[str, object] | None, Path, Path, Path, BaseException | None]:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    report, image_object, source = encrypted_report_fixture(
        upload_dir,
        plaintext=b"original-image",
    )
    manifest_dir = tmp_path / "retention-audit"
    manifest_dir.mkdir(mode=0o700)
    manifest_path = source if manifest_aliases_source else manifest_dir / "retention-manifest.json"
    backup_manifest, restore_receipt, lock_path = write_backup_manifest(
        tmp_path,
        database_url=TEST_DATABASE_URL,
        upload_dir=upload_dir,
    )
    if restore_reuses_source_upload:
        restore_payload = json.loads(restore_receipt.read_text(encoding="utf-8"))
        restore_payload["target_upload_root_identity_sha256"] = restore_payload[
            "source_upload_root_identity_sha256"
        ]
        restore_receipt.write_text(json.dumps(restore_payload), encoding="utf-8")

    database_present = True

    class ScalarResult:
        def __init__(self, values: list[object]) -> None:
            self.values = values

        def all(self) -> list[object]:
            return self.values

    class HookedSession:
        def __enter__(self) -> "HookedSession":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def execute(self, _statement: object, _parameters: object = None) -> None:
            return None

        def scalars(self, statement: object) -> ScalarResult:
            return ScalarResult(
                scalar_values(
                    statement,
                    report=report,
                    image_object=image_object,
                    database_present=database_present,
                )
            )

        def delete(self, _value: Report) -> None:
            return None

        def commit(self) -> None:
            nonlocal database_present
            assert callable(commit_hook)
            commit_hook(source, upload_dir)
            database_present = False

        def rollback(self) -> None:
            return None

    class FakeEngine:
        def dispose(self) -> None:
            return None

    monkeypatch.setattr("sqlalchemy.create_engine", lambda _url, **_kwargs: FakeEngine())
    monkeypatch.setattr("sqlalchemy.orm.Session", lambda _engine: HookedSession())
    error: BaseException | None = None
    result: dict[str, object] | None = None
    try:
        result = retention.apply_database_retention(
            database_url=TEST_DATABASE_URL,
            upload_dir=upload_dir,
            as_of=datetime(2026, 7, 11, tzinfo=UTC),
            manifest_path=manifest_path,
            backup_manifest_path=backup_manifest,
            restore_receipt_path=restore_receipt,
            trusted_backup_signer_fingerprint=TRUSTED_BACKUP_SIGNER,
            trusted_restore_signer_fingerprint=TRUSTED_RESTORE_SIGNER,
            maintenance_lock_path=lock_path,
        )
    except BaseException as exc:  # Failure and deferred-signal cases are intentional.
        error = exc
    return result, source, upload_dir, manifest_path, error


def test_apply_rejects_manifest_path_that_aliases_selected_upload_image(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    commit_called = False

    def observe_commit(_source: Path, _upload_dir: Path) -> None:
        nonlocal commit_called
        commit_called = True

    result, source, upload_dir, _manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        observe_commit,
        manifest_aliases_source=True,
    )

    assert result is None
    assert isinstance(error, ValueError)
    assert "disjoint from the upload root" in str(error)
    assert commit_called is False
    assert source.read_bytes().startswith(b"WSRI")
    assert not (upload_dir / ".retention-quarantine").exists()


def test_apply_rejects_restore_receipt_that_reuses_source_upload_identity(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, source, upload_dir, _manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        lambda _source, _upload_dir: None,
        restore_reuses_source_upload=True,
    )

    assert result is None
    assert isinstance(error, ValueError)
    assert "target upload root must differ" in str(error)
    assert source.read_bytes().startswith(b"WSRI")
    assert not (upload_dir / ".retention-quarantine").exists()


def test_postcommit_source_replacement_is_not_deleted(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def replace_source(source: Path, _upload_dir: Path) -> None:
        source.rename(source.with_name(f"{source.name}.original"))
        source.write_bytes(b"replacement-image")
        source.chmod(0o600)

    result, source, upload_dir, _manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        replace_source,
    )

    assert isinstance(error, RuntimeError)
    assert result is None
    assert source.read_bytes() == b"replacement-image"
    assert (upload_dir / f"{source.name}.original").read_bytes().startswith(b"WSRI")
    assert list((upload_dir / ".retention-quarantine").rglob("*.wse.recovery"))


def test_postcommit_quarantine_replacement_preserves_source_and_replacement(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    replacement_path: list[Path] = []

    def replace_quarantine(_source: Path, upload_dir: Path) -> None:
        quarantine = next(
            (upload_dir / ".retention-quarantine").rglob("*.wse.recovery")
        )
        quarantine.rename(quarantine.with_name(f"{quarantine.name}.original"))
        quarantine.write_bytes(b"replacement-quarantine")
        quarantine.chmod(0o600)
        replacement_path.append(quarantine)

    result, source, _upload_dir, _manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        replace_quarantine,
    )

    assert isinstance(error, RuntimeError)
    assert result is None
    assert replacement_path[0].read_bytes() == b"replacement-quarantine"
    assert replacement_path[0].with_name(
        f"{replacement_path[0].name}.original"
    ).read_bytes().startswith(b"WSRI")


def test_rollback_quarantine_replacement_is_not_deleted_and_is_a_restore_error(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    replacement_path: list[Path] = []

    def replace_quarantine_then_fail(_source: Path, upload_dir: Path) -> None:
        quarantine = next(
            (upload_dir / ".retention-quarantine").rglob("*.wse.recovery")
        )
        quarantine.rename(quarantine.with_name(f"{quarantine.name}.original"))
        quarantine.write_bytes(b"replacement-quarantine")
        quarantine.chmod(0o600)
        replacement_path.append(quarantine)
        raise RuntimeError("commit failed after quarantine replacement")

    result, source, _upload_dir, manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        replace_quarantine_then_fail,
    )

    assert result is None
    assert isinstance(error, RuntimeError)
    assert source.read_bytes().startswith(b"WSRI")
    assert replacement_path[0].read_bytes() == b"replacement-quarantine"
    failed_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert failed_manifest["status"] == "failed"
    assert failed_manifest["restore_errors"]


def test_termination_after_commit_is_recorded_as_failed_after_database_commit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    handlers: dict[signal.Signals, object] = {}
    delivered = False
    second_delivered = False
    commit_completed = False
    real_write_json_atomic = retention.write_json_atomic

    def fake_signal(signal_number: signal.Signals, handler: object) -> object:
        previous = handlers.get(signal_number, signal.SIG_DFL)
        handlers[signal_number] = handler
        return previous

    def fake_pthread_sigmask(how: int, _signals: object) -> set[signal.Signals]:
        nonlocal delivered
        if how == signal.SIG_SETMASK and commit_completed and not delivered:
            delivered = True
            handler = handlers[signal.SIGTERM]
            assert callable(handler)
            handler(signal.SIGTERM, None)
        return set()

    def write_json_with_second_signal(
        path: Path,
        payload: dict[str, object],
        **kwargs: object,
    ) -> None:
        nonlocal second_delivered
        if (
            payload.get("status") == "failed_after_database_commit"
            and not second_delivered
        ):
            handler = handlers[signal.SIGTERM]
            assert callable(handler)
            handler(signal.SIGTERM, None)
            second_delivered = True
        real_write_json_atomic(path, payload, **kwargs)

    def complete_commit(_source: Path, _upload_dir: Path) -> None:
        nonlocal commit_completed
        commit_completed = True

    monkeypatch.setattr(retention.signal, "signal", fake_signal)
    monkeypatch.setattr(retention.signal, "pthread_sigmask", fake_pthread_sigmask)
    monkeypatch.setattr(retention, "write_json_atomic", write_json_with_second_signal)

    result, source, upload_dir, manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        complete_commit,
    )

    assert result is None
    assert isinstance(error, retention.DeferredTerminationSignal)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["status"] == "failed_after_database_commit"
    assert manifest["deleted_count"] == 1
    assert not source.exists()
    assert not (upload_dir / ".retention-quarantine").exists()
    assert second_delivered is True


def test_signal_guard_restores_all_handlers_while_termination_is_blocked(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_handlers = {signal_number: object() for signal_number in retention.TERMINATION_SIGNALS}
    handlers: dict[signal.Signals, object] = dict(original_handlers)
    masked = False
    restored_before_unmask = False

    def fake_signal(signal_number: signal.Signals, handler: object) -> object:
        assert masked is True
        previous = handlers[signal_number]
        handlers[signal_number] = handler
        return previous

    def fake_pthread_sigmask(how: int, signals: object) -> set[signal.Signals]:
        nonlocal masked, restored_before_unmask
        previous = set(retention.TERMINATION_SIGNALS) if masked else set()
        if how == signal.SIG_BLOCK:
            masked = True
        elif how == signal.SIG_SETMASK:
            if handlers == original_handlers:
                restored_before_unmask = True
            masked = bool(set(signals))  # type: ignore[arg-type]
        return previous

    monkeypatch.setattr(retention.signal, "signal", fake_signal)
    monkeypatch.setattr(retention.signal, "pthread_sigmask", fake_pthread_sigmask)

    guard = retention.RetentionSignalGuard()
    guard.install()
    with pytest.raises(retention.DeferredTerminationSignal):
        guard._defer(signal.SIGTERM, None)
    guard._defer(signal.SIGTERM, None)
    guard.close()

    assert handlers == original_handlers
    assert restored_before_unmask is True
    assert masked is False


def test_recovery_manifest_error_then_termination_preserves_error_and_completes_rollback(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    handlers: dict[signal.Signals, object] = {}
    write_failed = False
    delivered = False
    real_write_json_atomic = retention.write_json_atomic
    real_begin_cleanup = retention.RetentionSignalGuard.begin_cleanup

    def fake_signal(signal_number: signal.Signals, handler: object) -> object:
        previous = handlers.get(signal_number, signal.SIG_DFL)
        handlers[signal_number] = handler
        return previous

    def fake_pthread_sigmask(_how: int, _signals: object) -> set[signal.Signals]:
        return set()

    def fail_recovery_manifest(
        path: Path,
        payload: dict[str, object],
        **kwargs: object,
    ) -> None:
        nonlocal write_failed
        if payload.get("status") == "recovery_copied":
            write_failed = True
            raise OSError("injected recovery manifest failure")
        real_write_json_atomic(path, payload, **kwargs)

    def begin_cleanup_after_termination(
        guard: retention.RetentionSignalGuard,
    ) -> None:
        nonlocal delivered
        if write_failed and not delivered:
            delivered = True
            handler = handlers[signal.SIGTERM]
            assert callable(handler)
            handler(signal.SIGTERM, None)
        real_begin_cleanup(guard)

    monkeypatch.setattr(retention.signal, "signal", fake_signal)
    monkeypatch.setattr(retention.signal, "pthread_sigmask", fake_pthread_sigmask)
    monkeypatch.setattr(retention, "write_json_atomic", fail_recovery_manifest)
    monkeypatch.setattr(
        retention.RetentionSignalGuard,
        "begin_cleanup",
        begin_cleanup_after_termination,
    )

    result, source, upload_dir, manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        lambda _source, _upload_dir: None,
    )

    assert result is None
    assert isinstance(error, OSError)
    assert str(error) == "injected recovery manifest failure"
    assert delivered is True
    assert source.read_bytes().startswith(b"WSRI")
    assert not (upload_dir / ".retention-quarantine").exists()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["status"] == "failed_before_commit"
    assert manifest["restore_errors"] == []


def test_non_signal_failure_then_two_termination_signals_do_not_interrupt_rollback(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    handlers: dict[signal.Signals, object] = {}
    delivered_signals = 0
    real_unlink = retention._unlink_recovery_file

    def fake_signal(signal_number: signal.Signals, handler: object) -> object:
        previous = handlers.get(signal_number, signal.SIG_DFL)
        handlers[signal_number] = handler
        return previous

    def fake_pthread_sigmask(_how: int, _signals: object) -> set[signal.Signals]:
        return set()

    def unlink_after_two_signals(directory_fd: int, name: str, **kwargs: object) -> bool:
        nonlocal delivered_signals
        if delivered_signals == 0 and name.endswith(".wse.recovery"):
            handler = handlers[signal.SIGTERM]
            assert callable(handler)
            handler(signal.SIGTERM, None)
            delivered_signals += 1
            handler(signal.SIGTERM, None)
            delivered_signals += 1
        return real_unlink(directory_fd, name, **kwargs)

    def fail_commit(_source: Path, _upload_dir: Path) -> None:
        raise RuntimeError("non-signal commit failure")

    monkeypatch.setattr(retention.signal, "signal", fake_signal)
    monkeypatch.setattr(retention.signal, "pthread_sigmask", fake_pthread_sigmask)
    monkeypatch.setattr(retention, "_unlink_recovery_file", unlink_after_two_signals)

    result, source, upload_dir, manifest_path, error = _run_apply_with_commit_hook(
        tmp_path,
        monkeypatch,
        fail_commit,
    )

    assert result is None
    assert isinstance(error, RuntimeError)
    assert str(error) == "non-signal commit failure"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["status"] == "failed_before_commit"
    assert manifest["restore_errors"] == []
    assert source.read_bytes().startswith(b"WSRI")
    assert not (upload_dir / ".retention-quarantine").exists()
    assert delivered_signals == 2
