from __future__ import annotations

import errno
import fcntl
import hashlib
import importlib
import json
import os
from pathlib import Path
import stat
from types import SimpleNamespace

import pytest

import scripts.account_deletion_worker as account_deletion_worker
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    complete_server_deletion_inventory_from_manifest,
    transition_deletion_item,
)
from scripts.account_deletion_worker import (
    AccountDeletionWorkerError,
    _atomic_write,
    _maintenance_lock,
    _prepare_roots,
    _publish_manifest,
    _remove_committed_objects,
    _restore_precommit,
    _safe_environment_file_metadata,
    _secure_directory,
    _validated_worker_database_url,
)


def test_worker_role_migration_is_least_privilege_and_guards_terminal_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202608250002_account_deletion_worker_role"
    )
    statements: list[str] = []
    monkeypatch.setattr(migration.op, "execute", statements.append)

    migration.upgrade()

    sql = "\n".join(statements)
    assert migration.revision == "202608250002"
    assert migration.down_revision == "202608250001"
    assert "CREATE ROLE walksafe_account_deletion_worker" in sql
    assert "NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE" in sql
    assert "GRANT DELETE ON TABLE public.reports" in sql
    assert "GRANT UPDATE ON TABLE public.account_deletion_requests" in sql
    assert "GRANT INSERT ON TABLE public.account_deletion_events" in sql
    assert "public.account_deletion_device_targets" in sql
    assert "REVOKE DELETE, TRUNCATE ON TABLE public.account_deletion_tombstones" in sql
    assert "server-owned deletion completion requires the dedicated worker role" in sql
    assert "current_user = session_user" in sql
    assert "worker_membership.admin_option IS FALSE" in sql
    assert "worker_membership.inherit_option IS TRUE" in sql
    assert "worker_membership.set_option IS FALSE" in sql

    statements.clear()
    migration.downgrade()
    downgrade_sql = "\n".join(statements)
    assert "DROP TRIGGER IF EXISTS account_deletion_items_server_terminal_worker_only" in downgrade_sql
    assert "REVOKE USAGE, CREATE ON SCHEMA public" in downgrade_sql
    assert "DROP ROLE walksafe_account_deletion_worker" not in downgrade_sql


def test_general_transition_cannot_claim_server_deletion_completion() -> None:
    with pytest.raises(PrivacyLifecycleError) as rejected:
        transition_deletion_item(
            object(),  # type: ignore[arg-type]
            request_id="delete_request_0001",
            item_key="server_originals",
            operation_id="terminal-without-manifest",
            expected_status_revision=1,
            next_state="COMPLETED",
            evidence_sha256="a" * 64,
        )

    assert rejected.value.code == "account_deletion_server_manifest_required"
    assert rejected.value.status_code == 409


def test_server_manifest_digest_is_validated_before_database_access() -> None:
    with pytest.raises(ValueError, match="lowercase SHA-256"):
        complete_server_deletion_inventory_from_manifest(
            object(),  # type: ignore[arg-type]
            request_id="delete_request_0001",
            manifest_sha256="A" * 64,
            terminal_at=None,  # type: ignore[arg-type]
        )


def test_worker_database_url_is_local_and_distinct_from_runtime() -> None:
    worker = "postgresql+psycopg://worker:secret@127.0.0.1/walksafe_test"
    assert _validated_worker_database_url(worker, None) == worker

    with pytest.raises(AccountDeletionWorkerError, match="must differ"):
        _validated_worker_database_url(worker, worker)
    with pytest.raises(AccountDeletionWorkerError, match="loopback"):
        _validated_worker_database_url(
            "postgresql+psycopg://worker:secret@db.example.invalid/walksafe_test",
            None,
        )
    with pytest.raises(AccountDeletionWorkerError, match=r"postgresql\+psycopg"):
        _validated_worker_database_url(
            "postgresql://worker:secret@127.0.0.1/walksafe_test",
            None,
        )
    with pytest.raises(AccountDeletionWorkerError, match="invalid"):
        _validated_worker_database_url("not a database URL", None)


def test_manual_worker_database_allows_only_verified_remote_transport() -> None:
    verified = (
        "postgresql+psycopg://worker:secret@db.example.invalid/walksafe"
        "?sslmode=verify-full&gssencmode=disable"
    )
    assert _validated_worker_database_url(verified, None, deployment=True) == verified

    with pytest.raises(AccountDeletionWorkerError, match="transport is unsafe"):
        _validated_worker_database_url(
            "postgresql+psycopg://worker:secret@db.example.invalid/walksafe",
            None,
            deployment=True,
        )
    with pytest.raises(AccountDeletionWorkerError, match="transport is unsafe"):
        _validated_worker_database_url(
            (
                "postgresql+psycopg://worker:secret@db.example.invalid/walksafe"
                "?sslmode=verify-full&gssencmode=require"
            ),
            None,
            deployment=True,
        )
    with pytest.raises(AccountDeletionWorkerError, match="loopback"):
        _validated_worker_database_url(verified, None)


def test_operational_environment_file_metadata_is_exact() -> None:
    safe = SimpleNamespace(
        st_mode=stat.S_IFREG | 0o600,
        st_uid=0,
        st_gid=0,
        st_nlink=1,
    )
    assert _safe_environment_file_metadata(safe) is True

    for field, value in (
        ("st_mode", stat.S_IFREG | 0o640),
        ("st_mode", stat.S_IFDIR | 0o600),
        ("st_uid", 1000),
        ("st_gid", 1000),
        ("st_nlink", 2),
    ):
        changed = SimpleNamespace(**vars(safe))
        setattr(changed, field, value)
        assert _safe_environment_file_metadata(changed) is False


def test_preexisting_worker_directory_permissions_are_not_repaired(
    tmp_path: Path,
) -> None:
    unsafe = tmp_path / "unsafe"
    unsafe.mkdir(mode=0o700)
    unsafe.chmod(0o755)

    with pytest.raises(AccountDeletionWorkerError, match="mode 0700"):
        _secure_directory(unsafe.resolve(), create=True)

    assert stat.S_IMODE(unsafe.stat().st_mode) == 0o755


def test_deployment_upload_contract_and_private_quarantine_round_trip(
    tmp_path: Path,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    upload_dir.chmod(0o2750)
    group_gid = os.getgid()
    account_deletion_worker._upload_directory(
        upload_dir.resolve(),
        expected_group_gid=group_gid,
    )

    quarantine_dir = tmp_path / "quarantine"
    quarantine_dir.mkdir(mode=0o700)
    source = upload_dir / "object.wse"
    quarantine = quarantine_dir / source.name
    payload = b"encrypted-envelope"
    source.write_bytes(payload)
    source.chmod(0o640)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
        "source_mode": 0o640,
        "source_gid": group_gid,
        "source_parent_mode": 0o2750,
    }

    account_deletion_worker._move_to_quarantine([item])
    assert stat.S_IMODE(quarantine.stat().st_mode) == 0o600
    account_deletion_worker._restore_precommit([item])
    assert stat.S_IMODE(source.stat().st_mode) == 0o640

    with pytest.raises(AccountDeletionWorkerError, match="must not be root"):
        account_deletion_worker._upload_directory(
            upload_dir.resolve(),
            expected_group_gid=0,
        )


def test_quarantine_mode_change_failure_is_recovered_without_unsafe_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    upload_dir.mkdir(mode=0o700)
    upload_dir.chmod(0o2750)
    quarantine_dir.mkdir(mode=0o700)
    source = upload_dir / "object.wse"
    quarantine = quarantine_dir / source.name
    payload = b"encrypted-envelope"
    source.write_bytes(payload)
    source.chmod(0o640)
    group_gid = os.getgid()
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
        "source_mode": 0o640,
        "source_gid": group_gid,
        "source_parent_mode": 0o2750,
    }
    real_validate = account_deletion_worker._validated_object
    validation_calls = 0

    def fail_after_fchmod(*args, **kwargs):
        nonlocal validation_calls
        validation_calls += 1
        if validation_calls == 3:
            raise AccountDeletionWorkerError("injected final validation failure")
        return real_validate(*args, **kwargs)

    monkeypatch.setattr(
        account_deletion_worker,
        "_validated_object",
        fail_after_fchmod,
    )
    with pytest.raises(AccountDeletionWorkerError, match="injected"):
        account_deletion_worker._move_to_quarantine([item])
    assert not source.exists()
    assert quarantine.exists()
    assert stat.S_IMODE(quarantine.stat().st_mode) == 0o600

    account_deletion_worker._restore_precommit([item])
    assert source.read_bytes() == payload
    assert stat.S_IMODE(source.stat().st_mode) == 0o640
    assert not quarantine.exists()


def test_precommit_restore_revalidates_already_restored_source(tmp_path: Path) -> None:
    source_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    source_dir.mkdir(mode=0o700)
    quarantine_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    source.write_bytes(b"encrypted-envelope")
    source.chmod(0o644)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine_dir / source.name),
        "envelope_size": len(b"encrypted-envelope"),
        "envelope_sha256": hashlib.sha256(b"encrypted-envelope").hexdigest(),
    }

    with pytest.raises(AccountDeletionWorkerError, match="identity differs"):
        account_deletion_worker._restore_precommit([item])
    assert source.exists()


def test_deletion_worker_rejects_upload_root_and_object_acls(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    upload_dir.chmod(0o2750)
    group_gid = os.getgid()
    root_identity = (upload_dir.stat().st_dev, upload_dir.stat().st_ino)

    monkeypatch.setattr(
        account_deletion_worker.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_default"]
            if (os.fstat(descriptor).st_dev, os.fstat(descriptor).st_ino)
            == root_identity
            else []
        ),
    )
    with pytest.raises(AccountDeletionWorkerError, match="ACL"):
        account_deletion_worker._upload_directory(
            upload_dir.resolve(),
            expected_group_gid=group_gid,
        )

    source = upload_dir / "object.wse"
    payload = b"encrypted-envelope"
    source.write_bytes(payload)
    source.chmod(0o640)
    source_identity = (source.stat().st_dev, source.stat().st_ino)
    monkeypatch.setattr(
        account_deletion_worker.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_access"]
            if (os.fstat(descriptor).st_dev, os.fstat(descriptor).st_ino)
            == source_identity
            else []
        ),
    )
    with pytest.raises(AccountDeletionWorkerError, match="identity differs"):
        account_deletion_worker._validated_object(
            source,
            size=len(payload),
            sha256=hashlib.sha256(payload).hexdigest(),
            expected_mode=0o640,
            expected_gid=group_gid,
            expected_parent_mode=0o2750,
            expected_parent_gid=group_gid,
        )
    assert source.read_bytes() == payload


def test_operational_roots_are_not_created_or_repaired(tmp_path: Path) -> None:
    root = tmp_path / "worker"
    root.mkdir(mode=0o700)

    with pytest.raises(FileNotFoundError):
        _prepare_roots(root.resolve(), create=False)

    assert list(root.iterdir()) == []


def test_operational_environment_file_rejects_non_root_owner_and_symlink(
    tmp_path: Path,
) -> None:
    environment = tmp_path / "worker.env"
    environment.write_text("WALKSAFE_ENVIRONMENT=staging\n", encoding="utf-8")
    environment.chmod(0o600)
    if os.geteuid() == 0:
        os.chown(environment, 1, 1)

    with pytest.raises(AccountDeletionWorkerError, match="root-owned"):
        account_deletion_worker._validated_operational_environment_file(
            environment.resolve()
        )

    link = tmp_path / "worker-link.env"
    link.symlink_to(environment)
    with pytest.raises(AccountDeletionWorkerError, match="root-owned"):
        account_deletion_worker._validated_operational_environment_file(link.absolute())


def test_maintenance_lock_contention_has_a_bounded_timeout(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    lock_path = tmp_path / "maintenance.lock"
    lock_path.write_bytes(b"")
    lock_path.chmod(0o600)
    moments = iter((0.0, 2.0))
    monkeypatch.setattr(account_deletion_worker.time, "monotonic", lambda: next(moments))
    monkeypatch.setattr(account_deletion_worker.time, "sleep", lambda _seconds: None)
    competing_descriptor = os.open(lock_path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(competing_descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with pytest.raises(AccountDeletionWorkerError, match="timed out"):
            with _maintenance_lock(
                lock_path.resolve(),
                timeout_seconds=1,
            ):
                pytest.fail("contended lock must not be entered")
    finally:
        fcntl.flock(competing_descriptor, fcntl.LOCK_UN)
        os.close(competing_descriptor)


def test_maintenance_lock_requires_preprovisioned_single_link_leaf(
    tmp_path: Path,
) -> None:
    tmp_path.chmod(0o700)
    lock_path = tmp_path / "maintenance.lock"

    with pytest.raises(AccountDeletionWorkerError, match="must already exist"):
        with _maintenance_lock(lock_path.resolve(), timeout_seconds=1):
            pytest.fail("missing maintenance lock must not be entered")
    assert not lock_path.exists()

    lock_path.touch(mode=0o600)
    os.link(lock_path, tmp_path / "maintenance.alias")
    with pytest.raises(AccountDeletionWorkerError, match="unsafe"):
        with _maintenance_lock(lock_path.resolve(), timeout_seconds=1):
            pytest.fail("hard-linked maintenance lock must not be entered")


def test_maintenance_lock_rejects_acl_inspection_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    lock_path = tmp_path / "maintenance.lock"
    lock_path.touch(mode=0o600)
    monkeypatch.setattr(
        account_deletion_worker.os,
        "listxattr",
        lambda _descriptor: (_ for _ in ()).throw(OSError(errno.EACCES, "denied")),
    )

    with pytest.raises(AccountDeletionWorkerError, match="unsafe"):
        with _maintenance_lock(lock_path.resolve(), timeout_seconds=1):
            pytest.fail("uninspectable ACL state must not be entered")


def _worker_main_args(tmp_path: Path, mode: str) -> list[str]:
    return [
        mode,
        "--upload-dir",
        str(tmp_path / "uploads"),
        "--journal-root",
        str(tmp_path / "worker"),
        "--maintenance-lock-path",
        str(tmp_path / "runtime" / "maintenance.lock"),
        "--batch-size",
        "7",
        "--lock-timeout-seconds",
        "12",
    ]


def test_local_isolated_cli_mode_is_preserved(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, object]] = []
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv(
        "WALKSAFE_ACCOUNT_DELETION_DATABASE_URL",
        "postgresql+psycopg://worker:secret@127.0.0.1/walksafe_test",
    )
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setattr(
        account_deletion_worker,
        "run_once",
        lambda **kwargs: calls.append(kwargs) or [],
    )

    assert account_deletion_worker.main(_worker_main_args(tmp_path, "--local-isolated")) == 0
    assert calls[0]["create_runtime_paths"] is True
    assert calls[0]["maintenance_lock_group_gid"] is None
    assert calls[0]["batch_size"] == 7
    assert calls[0]["lock_timeout_seconds"] == 12


def test_manual_one_shot_requires_exact_authority_and_keeps_failures_secret_free(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    env_file = tmp_path / "account-deletion-worker.env"
    args = [
        *_worker_main_args(tmp_path, "--manual-one-shot"),
        "--environment-file",
        str(env_file),
    ]
    for name in tuple(os.environ):
        if account_deletion_worker._forbidden_manual_environment_name(name):
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_MAINTENANCE_LOCK_GROUP", "walksafe-lock")
    monkeypatch.setenv(
        "WALKSAFE_UPLOAD_BACKUP_READER_GROUP",
        "walksafe-backup-readers",
    )
    monkeypatch.setenv("INVOCATION_ID", "a" * 32)
    monkeypatch.setenv(
        "WALKSAFE_ACCOUNT_DELETION_TOPOLOGY",
        account_deletion_worker.SINGLE_HOST_MARKER,
    )
    monkeypatch.setenv(
        "WALKSAFE_ACCOUNT_DELETION_DATABASE_URL",
        (
            "postgresql+psycopg://worker:secret@db.example.invalid/walksafe"
            "?sslmode=verify-full&gssencmode=disable"
        ),
    )
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("WALKSAFE_MIGRATION_DATABASE_URL", raising=False)
    monkeypatch.setattr(account_deletion_worker.os, "geteuid", lambda: 1000)
    monkeypatch.setattr(
        account_deletion_worker.grp,
        "getgrnam",
        lambda _name: SimpleNamespace(gr_gid=1234),
    )
    monkeypatch.setattr(
        account_deletion_worker,
        "_validated_operational_environment_file",
        lambda path: path,
    )

    calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        account_deletion_worker,
        "run_once",
        lambda **kwargs: calls.append(kwargs) or [],
    )
    assert account_deletion_worker.main(args) == 0
    assert calls[0]["create_runtime_paths"] is False

    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://runtime:do-not-print@localhost/db")
    assert account_deletion_worker.main(args) == 1
    captured = capsys.readouterr()
    assert "do-not-print" not in captured.err
    assert json.loads(captured.err.splitlines()[-1]) == {
        "processed": 0,
        "status": "failed",
    }

    monkeypatch.delenv("DATABASE_URL")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOTP_SECRET", "admin-secret-must-not-print")
    assert account_deletion_worker.main(args) == 1
    captured = capsys.readouterr()
    assert "admin-secret-must-not-print" not in captured.err
    assert calls == [{
        "database_url": (
            "postgresql+psycopg://worker:secret@db.example.invalid/walksafe"
            "?sslmode=verify-full&gssencmode=disable"
        ),
        "upload_dir": tmp_path / "uploads",
        "journal_root": tmp_path / "worker",
        "maintenance_lock_path": tmp_path / "runtime" / "maintenance.lock",
        "maintenance_lock_group_gid": 1234,
        "upload_backup_reader_group_gid": 1234,
        "batch_size": 7,
        "lock_timeout_seconds": 12,
        "create_runtime_paths": False,
    }]


def test_manual_one_shot_rejects_root_and_non_systemd_invocation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    args = [
        *_worker_main_args(tmp_path, "--manual-one-shot"),
        "--environment-file",
        str(tmp_path / "worker.env"),
    ]
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "staging")
    monkeypatch.setenv("WALKSAFE_ACCOUNT_DELETION_TOPOLOGY", account_deletion_worker.SINGLE_HOST_MARKER)
    monkeypatch.setenv("WALKSAFE_ACCOUNT_DELETION_DATABASE_URL", "postgresql+psycopg://worker:secret@localhost/db")
    monkeypatch.delenv("INVOCATION_ID", raising=False)
    monkeypatch.setattr(account_deletion_worker.os, "geteuid", lambda: 1000)
    assert account_deletion_worker.main(args) == 1

    monkeypatch.setenv("INVOCATION_ID", "b" * 32)
    monkeypatch.setattr(account_deletion_worker.os, "geteuid", lambda: 0)
    assert account_deletion_worker.main(args) == 1


def test_manifest_is_create_only_idempotent_and_rejects_unsafe_existing_file(
    tmp_path: Path,
) -> None:
    tmp_path.chmod(0o700)
    manifest_path = tmp_path / "request.json"
    payload = {"request_id": "delete_request_0001", "schema_version": "test.v1"}

    first_bytes, first_digest = _publish_manifest(manifest_path, payload)
    second_bytes, second_digest = _publish_manifest(manifest_path, payload)

    assert first_bytes == second_bytes
    assert first_digest == second_digest
    assert os.stat(manifest_path).st_mode & 0o777 == 0o600
    with pytest.raises(AccountDeletionWorkerError, match="differs"):
        _publish_manifest(manifest_path, {**payload, "request_id": "delete_request_0002"})

    manifest_path.unlink()
    target = tmp_path / "target.json"
    target.write_text("{}", encoding="utf-8")
    target.chmod(0o600)
    manifest_path.symlink_to(target)
    with pytest.raises(AccountDeletionWorkerError, match="unsafe"):
        _publish_manifest(manifest_path, payload)


def test_manifest_publish_failure_never_exposes_a_partial_final_path(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    manifest_path = tmp_path / "request.json"
    payload = {"request_id": "delete_request_0001", "schema_version": "test.v1"}
    real_rename = account_deletion_worker._rename_noreplace

    def fail_before_publish(_source: Path, _target: Path) -> None:
        raise OSError(errno.ENOSPC, "simulated full filesystem")

    monkeypatch.setattr(
        account_deletion_worker,
        "_rename_noreplace",
        fail_before_publish,
    )
    with pytest.raises(OSError) as failed:
        _publish_manifest(manifest_path, payload)
    assert failed.value.errno == errno.ENOSPC
    assert not manifest_path.exists()
    assert list(tmp_path.glob("*.publish")) == []

    monkeypatch.setattr(account_deletion_worker, "_rename_noreplace", real_rename)
    published, _digest = _publish_manifest(manifest_path, payload)
    assert published.endswith(b"\n")


def test_atomic_journal_write_handles_partial_os_writes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    journal_path = tmp_path / "journal.json"
    payload = {"request_id": "delete_request_0001", "state": "PREPARED"}
    real_write = os.write

    def partial_write(descriptor: int, data: object) -> int:
        chunk = bytes(data)[:7]
        return real_write(descriptor, chunk)

    monkeypatch.setattr(account_deletion_worker.os, "write", partial_write)
    _atomic_write(journal_path, payload)

    assert json.loads(journal_path.read_text(encoding="utf-8")) == payload
    assert os.stat(journal_path).st_mode & 0o777 == 0o600
    assert list(tmp_path.glob("*.tmp")) == []


def test_precommit_restore_and_postcommit_removal_are_idempotent(tmp_path: Path) -> None:
    source_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    source_dir.mkdir(mode=0o700)
    quarantine_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    quarantine = quarantine_dir / "object.wse"
    payload = b"encrypted-envelope"
    quarantine.write_bytes(payload)
    quarantine.chmod(0o600)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
    }

    _restore_precommit([item])
    assert source.read_bytes() == payload
    assert not quarantine.exists()

    os.replace(source, quarantine)
    _remove_committed_objects([item])
    _remove_committed_objects([item])
    assert not source.exists()
    assert not quarantine.exists()


@pytest.mark.parametrize("quarantine_mode", (0o640, 0o600))
def test_precommit_restore_accepts_only_prepared_source_or_private_mode(
    tmp_path: Path,
    quarantine_mode: int,
) -> None:
    source_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    source_dir.mkdir(mode=0o700)
    source_dir.chmod(0o2750)
    quarantine_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    quarantine = quarantine_dir / "object.wse"
    payload = b"encrypted-envelope"
    quarantine.write_bytes(payload)
    quarantine.chmod(quarantine_mode)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
        "source_mode": 0o640,
        "source_gid": os.getgid(),
        "source_parent_mode": 0o2750,
    }

    _restore_precommit([item])

    assert source.read_bytes() == payload
    assert stat.S_IMODE(source.stat().st_mode) == 0o640
    assert not quarantine.exists()


def test_precommit_restore_rejects_non_contract_quarantine_mode(
    tmp_path: Path,
) -> None:
    source_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    source_dir.mkdir(mode=0o700)
    source_dir.chmod(0o2750)
    quarantine_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    quarantine = quarantine_dir / "object.wse"
    payload = b"encrypted-envelope"
    quarantine.write_bytes(payload)
    quarantine.chmod(0o620)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
        "source_mode": 0o640,
        "source_gid": os.getgid(),
        "source_parent_mode": 0o2750,
    }

    with pytest.raises(AccountDeletionWorkerError, match="mode or identity"):
        _restore_precommit([item])

    assert not source.exists()
    assert quarantine.read_bytes() == payload
    assert stat.S_IMODE(quarantine.stat().st_mode) == 0o620


def test_precommit_restore_retries_parent_fsync_after_rename(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    source_dir.mkdir(mode=0o700)
    quarantine_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    quarantine = quarantine_dir / "object.wse"
    payload = b"encrypted-envelope"
    quarantine.write_bytes(payload)
    quarantine.chmod(0o600)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
    }
    fsync_calls: list[Path] = []
    fail_fsync = True

    def fail_first_fsync(path: Path) -> None:
        nonlocal fail_fsync
        fsync_calls.append(path)
        if fail_fsync:
            fail_fsync = False
            raise OSError(errno.EIO, "injected directory fsync failure")

    monkeypatch.setattr(
        account_deletion_worker,
        "_fsync_directory",
        fail_first_fsync,
    )

    with pytest.raises(OSError, match="injected directory fsync failure"):
        _restore_precommit([item])
    assert source.read_bytes() == payload
    assert not quarantine.exists()

    fsync_calls.clear()
    _restore_precommit([item])

    assert fsync_calls == [source_dir, quarantine_dir]


def test_postcommit_retry_fsyncs_both_parents_after_unlink(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    source_dir.mkdir(mode=0o700)
    quarantine_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    quarantine = quarantine_dir / "object.wse"
    payload = b"encrypted-envelope"
    quarantine.write_bytes(payload)
    quarantine.chmod(0o600)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
    }
    fsync_calls: list[Path] = []
    unlink_dir_fds: list[int | None] = []
    fail_fsync = True
    real_unlink = os.unlink

    def capture_unlink(
        path: str | bytes,
        *,
        dir_fd: int | None = None,
    ) -> None:
        unlink_dir_fds.append(dir_fd)
        real_unlink(path, dir_fd=dir_fd)

    def fail_first_fsync(path: Path) -> None:
        nonlocal fail_fsync
        fsync_calls.append(path)
        if fail_fsync:
            fail_fsync = False
            raise OSError(errno.EIO, "injected directory fsync failure")

    monkeypatch.setattr(account_deletion_worker.os, "unlink", capture_unlink)
    monkeypatch.setattr(account_deletion_worker, "_fsync_directory", fail_first_fsync)

    with pytest.raises(OSError, match="injected directory fsync failure"):
        _remove_committed_objects([item])
    assert not quarantine.exists()
    assert unlink_dir_fds and unlink_dir_fds[0] is not None

    fsync_calls.clear()
    _remove_committed_objects([item])

    assert fsync_calls == [source_dir, quarantine_dir]


def test_bound_unlink_rejects_inode_swap_after_descriptor_validation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    target = tmp_path / "object.wse"
    held = tmp_path / "held.wse"
    payload = b"encrypted-envelope"
    target.write_bytes(payload)
    target.chmod(0o600)
    real_assert = account_deletion_worker._assert_bound_object
    validation_calls = 0

    def swap_before_final_binding_check(*args, **kwargs) -> None:
        nonlocal validation_calls
        validation_calls += 1
        if validation_calls == 2:
            os.replace(target, held)
            target.write_bytes(payload)
            target.chmod(0o600)
        real_assert(*args, **kwargs)

    monkeypatch.setattr(
        account_deletion_worker,
        "_assert_bound_object",
        swap_before_final_binding_check,
    )

    with pytest.raises(AccountDeletionWorkerError, match="identity differs"):
        account_deletion_worker._unlink_validated_object(
            target,
            size=len(payload),
            sha256=hashlib.sha256(payload).hexdigest(),
            expected_mode=0o600,
            expected_gid=None,
            expected_parent_mode=0o700,
            expected_parent_gid=None,
        )

    assert target.read_bytes() == payload
    assert held.read_bytes() == payload


def test_bound_rename_rejects_target_inode_swap_after_rename(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_dir = tmp_path / "source"
    target_dir = tmp_path / "target"
    source_dir.mkdir(mode=0o700)
    target_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    target = target_dir / "object.wse"
    held = target_dir / "held.wse"
    payload = b"encrypted-envelope"
    source.write_bytes(payload)
    source.chmod(0o600)
    real_rename = account_deletion_worker._rename_noreplace_at

    def swap_target_after_rename(
        source_name: str | bytes | Path,
        target_name: str | bytes | Path,
        *,
        source_dir_fd: int = account_deletion_worker._AT_FDCWD,
        target_dir_fd: int = account_deletion_worker._AT_FDCWD,
    ) -> None:
        real_rename(
            source_name,
            target_name,
            source_dir_fd=source_dir_fd,
            target_dir_fd=target_dir_fd,
        )
        os.rename(
            target_name,
            held.name,
            src_dir_fd=target_dir_fd,
            dst_dir_fd=target_dir_fd,
        )
        descriptor = os.open(
            target_name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=target_dir_fd,
        )
        try:
            os.write(descriptor, payload)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    monkeypatch.setattr(
        account_deletion_worker,
        "_rename_noreplace_at",
        swap_target_after_rename,
    )

    with pytest.raises(AccountDeletionWorkerError, match="rename binding differs"):
        account_deletion_worker._rename_validated_object_noreplace(
            source,
            target,
            size=len(payload),
            sha256=hashlib.sha256(payload).hexdigest(),
            expected_mode=0o600,
            expected_gid=None,
            expected_parent_mode=0o700,
            expected_parent_gid=None,
            target_parent_mode=0o700,
            target_parent_gid=None,
        )

    assert not source.exists()
    assert target.read_bytes() == payload
    assert held.read_bytes() == payload


def test_restore_create_only_rename_never_overwrites_new_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_dir = tmp_path / "uploads"
    quarantine_dir = tmp_path / "quarantine"
    source_dir.mkdir(mode=0o700)
    quarantine_dir.mkdir(mode=0o700)
    source = source_dir / "object.wse"
    quarantine = quarantine_dir / "object.wse"
    payload = b"encrypted-envelope"
    new_source = b"new-source-must-survive"
    quarantine.write_bytes(payload)
    quarantine.chmod(0o600)
    item = {
        "source_path": str(source),
        "quarantine_path": str(quarantine),
        "envelope_size": len(payload),
        "envelope_sha256": hashlib.sha256(payload).hexdigest(),
    }
    real_rename = account_deletion_worker._rename_noreplace_at

    def create_source_before_rename(
        source_name: str | bytes | Path,
        target_name: str | bytes | Path,
        *,
        source_dir_fd: int = account_deletion_worker._AT_FDCWD,
        target_dir_fd: int = account_deletion_worker._AT_FDCWD,
    ) -> None:
        assert source_dir_fd != account_deletion_worker._AT_FDCWD
        assert target_dir_fd != account_deletion_worker._AT_FDCWD
        descriptor = os.open(
            target_name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=target_dir_fd,
        )
        try:
            os.write(descriptor, new_source)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        real_rename(
            source_name,
            target_name,
            source_dir_fd=source_dir_fd,
            target_dir_fd=target_dir_fd,
        )

    monkeypatch.setattr(
        account_deletion_worker,
        "_rename_noreplace_at",
        create_source_before_rename,
    )

    with pytest.raises(AccountDeletionWorkerError, match="destination is not empty"):
        _restore_precommit([item])

    assert source.read_bytes() == new_source
    assert quarantine.read_bytes() == payload
