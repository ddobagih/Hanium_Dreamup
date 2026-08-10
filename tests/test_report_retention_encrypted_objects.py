from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, datetime
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
from types import SimpleNamespace
from typing import Any
import uuid

import pytest

import scripts.check_report_retention_dry_run as retention
from backend.app.services.report_image_crypto import encrypt_report_image


_FAULT_WORKER = r"""
from contextlib import contextmanager
from datetime import UTC, datetime
import json
import os
from pathlib import Path
import signal
import sys
from types import SimpleNamespace
import uuid

import sqlalchemy.orm

import scripts.check_report_retention_dry_run as retention

fixture_path = Path(sys.argv[1])
state_path = Path(sys.argv[2])
upload_dir = Path(sys.argv[3])
manifest_path = Path(sys.argv[4])
target = sys.argv[5]
fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
bindings = fixture["bindings"] if "bindings" in fixture else [fixture["binding"]]
bindings_by_id = {binding["report_id"]: binding for binding in bindings}
reports = []
image_objects = []
for binding in bindings:
    report_id = uuid.UUID(binding["report_id"])
    reports.append(
        SimpleNamespace(
            id=report_id,
            created_at=retention.parse_datetime(binding["report_created_at"]),
            image_path=f"/uploads/{report_id}.jpg",
        )
    )
    image_objects.append(
        SimpleNamespace(
            report_id=report_id,
            storage_name=binding["storage_name"],
            envelope_version=binding["envelope_version"],
            algorithm=binding["algorithm"],
            aad_version=binding["aad_version"],
            key_id=binding["key_id"],
            nonce=retention.base64.urlsafe_b64decode(
                binding["nonce_b64url"] + "=" * (-len(binding["nonce_b64url"]) % 4)
            ),
            plaintext_sha256=binding["plaintext_sha256"],
            plaintext_size=binding["plaintext_size"],
            envelope_sha256=binding["envelope_sha256"],
            envelope_size=binding["envelope_size"],
            content_type=binding["content_type"],
        )
    )


def read_state():
    return set(
        json.loads(state_path.read_text(encoding="utf-8"))["present_report_ids"]
    )


def write_state(present_report_ids):
    temporary = state_path.with_name(f".{state_path.name}.tmp")
    descriptor = os.open(
        temporary,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
        0o600,
    )
    try:
        payload = (
            json.dumps(
                {"present_report_ids": sorted(present_report_ids)},
                sort_keys=True,
            )
            + "\n"
        ).encode()
        os.write(descriptor, payload)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    os.replace(temporary, state_path)
    parent_descriptor = os.open(state_path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(parent_descriptor)
    finally:
        os.close(parent_descriptor)


class Result:
    def __init__(self, values):
        self.values = values

    def all(self):
        return self.values


class FakeSession:
    def __init__(self, _engine):
        self.scalar_calls = 0
        self.pending_deletes = set()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, _statement, _parameters=None):
        return None

    def scalars(self, _statement):
        self.scalar_calls += 1
        return Result(reports if self.scalar_calls == 1 else image_objects)

    def delete(self, report):
        self.pending_deletes.add(str(report.id))

    def commit(self):
        write_state(read_state() - self.pending_deletes)


class FakeEngine:
    def dispose(self):
        return None


class Publisher:
    def __init__(self, path, **_kwargs):
        self.path = path

    def write(self, payload):
        temporary = self.path.with_name(f".{self.path.name}.tmp")
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
            0o600,
        )
        try:
            encoded = (json.dumps(payload, sort_keys=True) + "\n").encode()
            os.write(descriptor, encoded)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.replace(temporary, self.path)
        parent_descriptor = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(parent_descriptor)
        finally:
            os.close(parent_descriptor)


class Guard:
    @contextmanager
    def blocked(self):
        yield

    def begin_cleanup(self):
        return None


def load_state(report_ids):
    present_report_ids = read_state()
    return {
        item: (
            {
                "report_exists": True,
                "image_object": {
                    field: bindings_by_id[item][field]
                    for field in retention.RETENTION_RECOVERY_DATABASE_FIELDS
                },
            }
            if item in present_report_ids
            else {"report_exists": False, "image_object": None}
        )
        for item in report_ids
    }


def fault(point):
    if point == target:
        os.kill(os.getpid(), signal.SIGKILL)


sqlalchemy.orm.Session = FakeSession
retention.create_retention_database_engine = lambda _url: FakeEngine()
retention.database_identity_sha256 = lambda _url: "d" * 64
retention.path_identity_sha256 = lambda _path: "u" * 64
retention.validate_predelete_backup = lambda *_args, **_kwargs: {
    "run_id": "backup-run",
    "created_at": datetime(2026, 8, 1, tzinfo=UTC),
    "artifacts_sha256": "a" * 64,
    "verified_signer_fingerprint": "b" * 40,
}
retention.validate_predelete_restore_receipt = lambda *_args, **_kwargs: {
    "restored_at": datetime(2026, 8, 1, tzinfo=UTC),
    "receipt_sha256": "c" * 64,
    "verified_signer_fingerprint": "e" * 40,
}
retention.RetentionManifestPublisher = Publisher
retention.database_expired_statement = lambda *_args, **_kwargs: object()
retention.report_row = lambda report: {"id": str(report.id)}
retention.candidate_for_row = lambda row, **_kwargs: {"id": row["id"]}
retention._load_recovery_database_state = lambda _engine, report_ids: load_state(report_ids)

retention._apply_database_retention_locked(
    database_url="postgresql+psycopg://fixture.invalid/walksafe_test",
    upload_dir=upload_dir,
    as_of=datetime(2026, 8, 2, tzinfo=UTC),
    manifest_path=manifest_path,
    backup_manifest_path=fixture_path.parent / "backup-manifest.json",
    restore_receipt_path=fixture_path.parent / "restore-receipt.json",
    trusted_backup_signer_fingerprint="b" * 40,
    trusted_restore_signer_fingerprint="e" * 40,
    maintenance_lock_path=fixture_path.parent / "maintenance.lock",
    maintenance_lock={
        "identity_sha256": "f" * 64,
        "device_inode": "1:2",
        "acquired_at": datetime(2026, 8, 2, tzinfo=UTC),
    },
    signal_guard=Guard(),
    fault_hook=fault,
)
raise SystemExit(90)
"""


class _SignalGuard:
    @contextmanager
    def blocked(self) -> Any:
        yield


def _private_directory(path: Path) -> None:
    path.mkdir(parents=True, mode=0o700)
    path.chmod(0o700)


def _source_entry(upload_dir: Path, report_id: uuid.UUID, content: bytes) -> dict[str, Any]:
    storage_name = f"{report_id}.wse"
    source = upload_dir / storage_name
    encrypted = encrypt_report_image(
        content,
        report_id=report_id,
        content_type="image/jpeg",
        key_id="report-key-2026-08",
        key=b"k" * 32,
        nonce=b"n" * 12,
    )
    source.write_bytes(encrypted.envelope)
    source.chmod(0o600)
    report = SimpleNamespace(
        id=report_id,
        created_at=datetime(2026, 1, 1, tzinfo=UTC),
        image_path=f"/uploads/{report_id}.jpg",
    )
    image_object = SimpleNamespace(
        report_id=report_id,
        storage_name=storage_name,
        envelope_version=1,
        algorithm="AES-256-GCM",
        aad_version=1,
        key_id=encrypted.key_id,
        nonce=encrypted.nonce,
        plaintext_sha256=encrypted.plaintext_sha256,
        plaintext_size=encrypted.plaintext_length,
        envelope_sha256=encrypted.envelope_sha256,
        envelope_size=len(encrypted.envelope),
        content_type="image/jpeg",
    )
    entry = retention._database_binding(report, image_object)
    upload_fd = retention._open_canonical_directory(upload_dir)
    try:
        source_identity = retention._inspect_encrypted_object(
            upload_fd,
            storage_name,
            expected_size=len(encrypted.envelope),
            expected_sha256=entry["envelope_sha256"],
        )
    finally:
        os.close(upload_fd)
    entry.update(
        {
            "source_identity": dict(zip(retention.FILE_IDENTITY_FIELDS, source_identity)),
            "recovery_name": f"{storage_name}.recovery",
            "recovery_identity": None,
            "recovery_sha256": None,
        }
    )
    return entry


def _database_state(
    entries: list[dict[str, Any]],
    present_report_ids: set[str],
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for entry in entries:
        report_id = entry["report_id"]
        if report_id in present_report_ids:
            result[report_id] = {
                "report_exists": True,
                "image_object": {
                    field: entry[field]
                    for field in retention.RETENTION_RECOVERY_DATABASE_FIELDS
                },
            }
        else:
            result[report_id] = {"report_exists": False, "image_object": None}
    return result


def _recovery_run(
    upload_dir: Path,
    *,
    run_id: str,
    state: str,
    entries: list[dict[str, Any]],
    recovery: str,
) -> Path:
    quarantine_root = upload_dir / ".retention-quarantine"
    _private_directory(quarantine_root)
    run_dir = quarantine_root / run_id
    _private_directory(run_dir)
    for entry in entries:
        recovery_path = run_dir / entry["recovery_name"]
        if recovery == "partial":
            recovery_path.write_bytes(b"partial")
            recovery_path.chmod(0o600)
        elif recovery == "complete":
            source = upload_dir / entry["storage_name"]
            recovery_path.write_bytes(source.read_bytes())
            recovery_path.chmod(0o600)
            metadata = recovery_path.stat(follow_symlinks=False)
            entry["recovery_identity"] = retention._identity_payload(metadata)
            entry["recovery_sha256"] = entry["envelope_sha256"]
        elif recovery != "none":
            raise AssertionError(f"unknown recovery fixture: {recovery}")
    run_fd = retention._open_canonical_directory(run_dir)
    try:
        retention._write_recovery_manifest(
            run_fd,
            retention._recovery_manifest(run_id, state, entries),
            signal_guard=_SignalGuard(),  # type: ignore[arg-type]
        )
    finally:
        os.close(run_fd)
    return run_dir


@pytest.mark.parametrize(
    ("fault_point", "database_present", "expected_status", "manifest_status"),
    [
        ("P0", True, None, "planning"),
        ("P1", True, None, "planned"),
        ("P2", True, "RECONCILED_PRECOMMIT_ABORTED", "planned"),
        ("P3", True, "RECONCILED_PRECOMMIT_ABORTED", "planned"),
        ("P4", True, "RECONCILED_PRECOMMIT_ABORTED", "planned"),
        ("P5", True, "RECONCILED_PRECOMMIT_ABORTED", "recovery_copied"),
        ("P6", False, "RECONCILED_POSTCOMMIT_COMPLETED", "recovery_copied"),
        ("P7", False, "RECONCILED_POSTCOMMIT_COMPLETED", "database_committed"),
        ("P7_AFTER_RUN_REMOVAL", False, None, "database_committed"),
    ],
)
def test_sigkill_at_durable_boundaries_reconciles_on_process_restart(
    tmp_path: Path,
    fault_point: str,
    database_present: bool,
    expected_status: str | None,
    manifest_status: str,
) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    report_id = uuid.UUID("77777777-7777-4777-8777-777777777777")
    content = (
        b"p" * (1024 * 1024 + 4096)
        if fault_point == "P2"
        else b"encrypted-envelope" * 4
    )
    entry = _source_entry(upload_dir, report_id, content)
    fixture_path = tmp_path / "fixture.json"
    fixture_path.write_text(
        json.dumps(
            {
                "binding": {
                    field: entry[field]
                    for field in retention.RETENTION_RECOVERY_DATABASE_FIELDS
                }
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    fixture_path.chmod(0o600)
    state_path = tmp_path / "database-state.json"
    state_path.write_text(
        json.dumps({"present_report_ids": [entry["report_id"]]}) + "\n",
        encoding="utf-8",
    )
    state_path.chmod(0o600)
    manifest_path = tmp_path / "retention-manifest.json"
    repository_root = Path(__file__).resolve().parents[1]

    completed = subprocess.run(
        [
            sys.executable,
            "-c",
            _FAULT_WORKER,
            str(fixture_path),
            str(state_path),
            str(upload_dir),
            str(manifest_path),
            fault_point,
        ],
        cwd=repository_root,
        env={**os.environ, "PYTHONPATH": str(repository_root)},
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )

    assert completed.returncode == -signal.SIGKILL, completed.stderr
    if fault_point == "P2":
        recovery_files = list(
            (upload_dir / ".retention-quarantine").glob(
                f"*/{entry['recovery_name']}"
            )
        )
        assert len(recovery_files) == 1
        assert 0 < recovery_files[0].stat().st_size < entry["envelope_size"]
    observed_present_ids = set(
        json.loads(state_path.read_text(encoding="utf-8"))["present_report_ids"]
    )
    observed_present = entry["report_id"] in observed_present_ids
    assert observed_present is database_present
    state = _database_state(
        [entry],
        {entry["report_id"]} if observed_present else set(),
    )
    reconciled = retention.reconcile_retention_quarantine(
        upload_dir,
        database_state_loader=lambda _report_ids: state,
        signal_guard=_SignalGuard(),  # type: ignore[arg-type]
    )

    assert [item["status"] for item in reconciled] == (
        [expected_status] if expected_status is not None else []
    )
    assert (upload_dir / entry["storage_name"]).exists() is database_present
    assert not (upload_dir / ".retention-quarantine").exists()
    durable_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert durable_manifest["status"] == manifest_status
    assert durable_manifest["status"] != "completed"


@pytest.mark.parametrize(
    (
        "fault_point",
        "database_present",
        "expected_status",
        "journal_state",
        "first_copy",
        "second_copy",
        "manifest_status",
    ),
    [
        (
            "P2",
            True,
            "RECONCILED_PRECOMMIT_ABORTED",
            "PREPARING",
            "partial",
            "absent",
            "planned",
        ),
        (
            "P3",
            True,
            "RECONCILED_PRECOMMIT_ABORTED",
            "PREPARING",
            "complete",
            "absent",
            "planned",
        ),
        (
            "P4",
            True,
            "RECONCILED_PRECOMMIT_ABORTED",
            "PRECOMMIT_READY",
            "complete",
            "complete",
            "planned",
        ),
        (
            "P5",
            True,
            "RECONCILED_PRECOMMIT_ABORTED",
            "DATABASE_COMMITTING",
            "complete",
            "complete",
            "recovery_copied",
        ),
        (
            "P6",
            False,
            "RECONCILED_POSTCOMMIT_COMPLETED",
            "DATABASE_COMMITTING",
            "complete",
            "complete",
            "recovery_copied",
        ),
    ],
)
def test_multi_candidate_sigkill_keeps_database_atomic_and_reconciles_files(
    tmp_path: Path,
    fault_point: str,
    database_present: bool,
    expected_status: str,
    journal_state: str,
    first_copy: str,
    second_copy: str,
    manifest_status: str,
) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    entries = [
        _source_entry(
            upload_dir,
            uuid.UUID("77777777-7777-4777-8777-777777777771"),
            b"p" * (1024 * 1024 + 4096),
        ),
        _source_entry(
            upload_dir,
            uuid.UUID("88888888-8888-4888-8888-888888888882"),
            b"second-encrypted-envelope" * 4,
        ),
    ]
    fixture_path = tmp_path / "fixture.json"
    fixture_path.write_text(
        json.dumps(
            {
                "bindings": [
                    {
                        field: entry[field]
                        for field in retention.RETENTION_RECOVERY_DATABASE_FIELDS
                    }
                    for entry in entries
                ]
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    fixture_path.chmod(0o600)
    all_report_ids = {entry["report_id"] for entry in entries}
    state_path = tmp_path / "database-state.json"
    state_path.write_text(
        json.dumps({"present_report_ids": sorted(all_report_ids)}) + "\n",
        encoding="utf-8",
    )
    state_path.chmod(0o600)
    manifest_path = tmp_path / "retention-manifest.json"
    repository_root = Path(__file__).resolve().parents[1]

    completed = subprocess.run(
        [
            sys.executable,
            "-c",
            _FAULT_WORKER,
            str(fixture_path),
            str(state_path),
            str(upload_dir),
            str(manifest_path),
            fault_point,
        ],
        cwd=repository_root,
        env={**os.environ, "PYTHONPATH": str(repository_root)},
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )

    assert completed.returncode == -signal.SIGKILL, completed.stderr
    present_report_ids = set(
        json.loads(state_path.read_text(encoding="utf-8"))["present_report_ids"]
    )
    assert present_report_ids == (all_report_ids if database_present else set())

    run_dirs = list((upload_dir / ".retention-quarantine").iterdir())
    assert len(run_dirs) == 1
    recovery_manifest = json.loads(
        (run_dirs[0] / retention.RETENTION_RECOVERY_MANIFEST_NAME).read_text(
            encoding="utf-8"
        )
    )
    assert recovery_manifest["state"] == journal_state
    for entry, expected_copy in zip(entries, (first_copy, second_copy), strict=True):
        recovery_path = run_dirs[0] / entry["recovery_name"]
        if expected_copy == "absent":
            assert not recovery_path.exists()
        elif expected_copy == "partial":
            assert 0 < recovery_path.stat().st_size < entry["envelope_size"]
        else:
            assert recovery_path.stat().st_size == entry["envelope_size"]
            assert retention.sha256_file(recovery_path) == entry["envelope_sha256"]

    state = _database_state(entries, present_report_ids)
    reconciled = retention.reconcile_retention_quarantine(
        upload_dir,
        database_state_loader=lambda _report_ids: state,
        signal_guard=_SignalGuard(),  # type: ignore[arg-type]
    )

    assert [item["status"] for item in reconciled] == [expected_status]
    assert all(
        (upload_dir / entry["storage_name"]).exists() is database_present
        for entry in entries
    )
    assert not (upload_dir / ".retention-quarantine").exists()
    durable_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert durable_manifest["status"] == manifest_status
    assert durable_manifest["status"] != "completed"


@pytest.mark.parametrize(
    ("fault_point", "journal_state", "recovery", "database_present", "expected_status"),
    [
        ("P2", "PREPARING", "partial", True, "RECONCILED_PRECOMMIT_ABORTED"),
        ("P3", "PREPARING", "complete", True, "RECONCILED_PRECOMMIT_ABORTED"),
        ("P4", "PRECOMMIT_READY", "complete", True, "RECONCILED_PRECOMMIT_ABORTED"),
        ("P5", "DATABASE_COMMITTING", "complete", True, "RECONCILED_PRECOMMIT_ABORTED"),
        ("P6", "DATABASE_COMMITTING", "complete", False, "RECONCILED_POSTCOMMIT_COMPLETED"),
        (
            "P7",
            "RECONCILED_POSTCOMMIT_COMPLETED",
            "none",
            False,
            "RECONCILED_POSTCOMMIT_COMPLETED",
        ),
    ],
    ids=lambda value: value if isinstance(value, str) and value.startswith("P") else None,
)
def test_p2_through_p7_faults_reconcile_from_database_truth(
    tmp_path: Path,
    fault_point: str,
    journal_state: str,
    recovery: str,
    database_present: bool,
    expected_status: str,
) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    entry = _source_entry(upload_dir, report_id, b"encrypted-envelope" * 4)
    run_id = "a" * 32
    _recovery_run(
        upload_dir,
        run_id=run_id,
        state=journal_state,
        entries=[entry],
        recovery=recovery,
    )
    if fault_point == "P7":
        (upload_dir / entry["storage_name"]).unlink()

    state = _database_state(
        [entry],
        {entry["report_id"]} if database_present else set(),
    )
    results = retention.reconcile_retention_quarantine(
        upload_dir,
        database_state_loader=lambda _report_ids: state,
        signal_guard=_SignalGuard(),  # type: ignore[arg-type]
    )

    assert results == [
        {
            "run_id": run_id,
            "status": expected_status,
            "report_ids": [str(report_id)],
        }
    ]
    assert (upload_dir / entry["storage_name"]).exists() is database_present
    assert not (upload_dir / ".retention-quarantine").exists()


def test_p0_and_p1_leave_no_recovery_artifacts(tmp_path: Path) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    loader_calls: list[list[str]] = []

    assert retention.reconcile_retention_quarantine(
        upload_dir,
        database_state_loader=lambda report_ids: loader_calls.append(report_ids),
        signal_guard=_SignalGuard(),  # type: ignore[arg-type]
    ) == []

    _private_directory(upload_dir / ".retention-quarantine")
    run_dir = upload_dir / ".retention-quarantine" / ("b" * 32)
    _private_directory(run_dir)
    assert retention.reconcile_retention_quarantine(
        upload_dir,
        database_state_loader=lambda report_ids: loader_calls.append(report_ids),
        signal_guard=_SignalGuard(),  # type: ignore[arg-type]
    ) == []
    assert loader_calls == []
    assert not (upload_dir / ".retention-quarantine").exists()


def test_multiple_candidates_mixed_database_state_fails_closed(tmp_path: Path) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    entries = [
        _source_entry(
            upload_dir,
            uuid.UUID("11111111-1111-4111-8111-111111111111"),
            b"encrypted-envelope-one" * 3,
        ),
        _source_entry(
            upload_dir,
            uuid.UUID("22222222-2222-4222-8222-222222222222"),
            b"encrypted-envelope-two" * 3,
        ),
    ]
    _recovery_run(
        upload_dir,
        run_id="c" * 32,
        state="DATABASE_COMMITTING",
        entries=entries,
        recovery="complete",
    )
    state = _database_state(entries, {entries[0]["report_id"]})

    with pytest.raises(RuntimeError, match="mixed"):
        retention.reconcile_retention_quarantine(
            upload_dir,
            database_state_loader=lambda _report_ids: state,
            signal_guard=_SignalGuard(),  # type: ignore[arg-type]
        )

    assert all((upload_dir / entry["storage_name"]).exists() for entry in entries)
    assert (upload_dir / ".retention-quarantine" / ("c" * 32) / "manifest.json").exists()


@pytest.mark.parametrize("drift_field", ["key_id", "envelope_sha256", "envelope_size", "report_created_at"])
def test_reused_or_drifted_database_binding_fails_closed(
    tmp_path: Path,
    drift_field: str,
) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    entry = _source_entry(
        upload_dir,
        uuid.UUID("33333333-3333-4333-8333-333333333333"),
        b"encrypted-envelope" * 4,
    )
    _recovery_run(
        upload_dir,
        run_id="d" * 32,
        state="DATABASE_COMMITTING",
        entries=[entry],
        recovery="complete",
    )
    state = _database_state([entry], {entry["report_id"]})
    observed = state[entry["report_id"]]["image_object"]
    assert isinstance(observed, dict)
    observed[drift_field] = "changed" if drift_field != "envelope_size" else entry[drift_field] + 1

    with pytest.raises(RuntimeError, match="drifted"):
        retention.reconcile_retention_quarantine(
            upload_dir,
            database_state_loader=lambda _report_ids: state,
            signal_guard=_SignalGuard(),  # type: ignore[arg-type]
        )
    assert (upload_dir / entry["storage_name"]).exists()


def test_recovery_manifest_binds_encrypted_object_and_fsyncs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    entry = _source_entry(
        upload_dir,
        uuid.UUID("44444444-4444-4444-8444-444444444444"),
        b"encrypted-envelope" * 4,
    )
    run_dir = upload_dir / ".retention-quarantine" / ("e" * 32)
    _private_directory(run_dir)
    original_fsync = retention.os.fsync
    fsynced: list[int] = []

    def record_fsync(descriptor: int) -> None:
        fsynced.append(descriptor)
        original_fsync(descriptor)

    monkeypatch.setattr(retention.os, "fsync", record_fsync)
    run_fd = retention._open_canonical_directory(run_dir)
    try:
        retention._write_recovery_manifest(
            run_fd,
            retention._recovery_manifest("e" * 32, "PREPARING", [entry]),
            signal_guard=_SignalGuard(),  # type: ignore[arg-type]
        )
        assert run_fd in fsynced
    finally:
        os.close(run_fd)
    payload = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
    bound = payload["objects"][0]
    assert {
        "report_id",
        "storage_name",
        "envelope_sha256",
        "envelope_size",
        "key_id",
        "source_identity",
        "recovery_name",
        "recovery_identity",
        "recovery_sha256",
    }.issubset(bound)


def test_legacy_plaintext_missing_metadata_and_unbound_journals_fail_closed(tmp_path: Path) -> None:
    report_id = uuid.UUID("55555555-5555-4555-8555-555555555555")
    report = SimpleNamespace(id=report_id, created_at=datetime(2026, 1, 1, tzinfo=UTC))
    legacy = SimpleNamespace(
        report_id=report_id,
        storage_name=f"{report_id}.jpg",
        envelope_version=1,
        algorithm="AES-256-GCM",
        aad_version=1,
        key_id="key-1",
        nonce=b"n" * 12,
        plaintext_sha256="1" * 64,
        plaintext_size=1,
        envelope_sha256="2" * 64,
        envelope_size=64,
        content_type="image/jpeg",
    )
    with pytest.raises(RuntimeError, match="invalid encrypted"):
        retention._database_binding(report, legacy)

    entry = {
        field: value
        for field, value in retention._database_binding(
            report,
            SimpleNamespace(**{**vars(legacy), "storage_name": f"{report_id}.wse"}),
        ).items()
    }
    missing_metadata = {
        entry["report_id"]: {"report_exists": True, "image_object": None}
    }
    with pytest.raises(RuntimeError, match="drifted"):
        retention._classify_recovery_database_state(
            [
                {
                    **entry,
                    "source_identity": {},
                    "recovery_name": f"{report_id}.wse.recovery",
                    "recovery_identity": None,
                    "recovery_sha256": None,
                }
            ],
            missing_metadata,
        )

    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    _private_directory(upload_dir / ".retention-quarantine")
    legacy_run = upload_dir / ".retention-quarantine" / ("f" * 32)
    _private_directory(legacy_run)
    (legacy_run / "old-copy.jpg").write_bytes(b"plaintext")
    with pytest.raises(RuntimeError, match="legacy retention quarantine"):
        retention.reconcile_retention_quarantine(
            upload_dir,
            database_state_loader=lambda _report_ids: {},
            signal_guard=_SignalGuard(),  # type: ignore[arg-type]
        )


def test_recovery_symlink_and_source_digest_drift_are_never_deleted(tmp_path: Path) -> None:
    upload_dir = tmp_path / "uploads"
    _private_directory(upload_dir)
    entry = _source_entry(
        upload_dir,
        uuid.UUID("66666666-6666-4666-8666-666666666666"),
        b"encrypted-envelope" * 4,
    )
    run_dir = _recovery_run(
        upload_dir,
        run_id="1" * 32,
        state="PREPARING",
        entries=[entry],
        recovery="none",
    )
    outside = tmp_path / "outside"
    outside.write_bytes(b"outside")
    (run_dir / entry["recovery_name"]).symlink_to(outside)
    state = _database_state([entry], {entry["report_id"]})
    with pytest.raises(RuntimeError, match="identity drifted"):
        retention.reconcile_retention_quarantine(
            upload_dir,
            database_state_loader=lambda _report_ids: state,
            signal_guard=_SignalGuard(),  # type: ignore[arg-type]
        )
    assert outside.read_bytes() == b"outside"

    (run_dir / entry["recovery_name"]).unlink()
    source = upload_dir / entry["storage_name"]
    source.write_bytes(b"tampered-envelope" * 4)
    source.chmod(0o600)
    with pytest.raises(RuntimeError, match="encrypted report object"):
        retention.reconcile_retention_quarantine(
            upload_dir,
            database_state_loader=lambda _report_ids: state,
            signal_guard=_SignalGuard(),  # type: ignore[arg-type]
        )
    assert source.exists()
