from __future__ import annotations

import errno
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


def _run_open_race(root: Path, operation: str, replacement: str) -> dict:
    from backend.app.services import raw_collection_storage as storage
    from backend.app.services.raw_collection_ingest import RawCollectionStorageError
    from backend.tests.test_raw_collection_storage_reconciliation import (
        _InventorySession, _StaticKeyManager, _database_rows, _persist, _stage,
    )

    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    metadata = pending.metadata
    chunk_path = root / metadata.storage_name
    destination = chunk_path
    lookups = []

    def committed(*identity):
        lookups.append(identity)
        return storage.RawChunkCommitState(True, metadata)

    if operation in {"journal", "temporary"}:
        destination = pending.journal_path
        if operation == "temporary":
            destination = destination.with_name(f".{destination.name}.{'1' * 32}.tmp")
            os.link(pending.journal_path, destination)

        def invoke():
            return storage.reconcile_pending_raw_chunk_writes(root, committed)
    else:
        storage.complete_raw_chunk_write(pending)
        if operation == "chunk":
            def invoke():
                return storage.read_raw_chunk_envelope(
                    root, metadata.storage_name,
                    expected_size=metadata.envelope_size,
                    expected_sha256=metadata.envelope_sha256,
                )
        elif operation == "probe":
            def invoke():
                return storage.probe_raw_chunk_envelope(
                    root, metadata.storage_name, expected_size=metadata.envelope_size,
                )
        else:
            assert operation == "inventory"
            database = _InventorySession(*_database_rows(pending))

            def invoke():
                return storage.validate_raw_storage_database_inventory(
                    database, root, key_manager=_StaticKeyManager(),
                )

    original = destination.read_bytes()
    saved = root.parent / "saved-owned-object"
    real_open, real_close, real_read = os.open, os.close, os.read
    opened, closed, reads = [], [], []
    swapped = False

    def open_with_swap(path, flags, *args, **kwargs):
        nonlocal swapped
        if not swapped and kwargs.get("dir_fd") is not None and os.fspath(path) == destination.name:
            swapped = True
            destination.rename(saved)
            if replacement == "fifo":
                os.mkfifo(destination, 0o600)
            elif replacement == "symlink":
                destination.symlink_to(saved)
            else:
                destination.mkdir(mode=0o700)
            print("RAW_OPEN_SWAP_REACHED", flush=True)
            descriptor = real_open(path, flags, *args, **kwargs)
            opened.append(descriptor)
            return descriptor
        return real_open(path, flags, *args, **kwargs)

    def close_observed(descriptor):
        if descriptor in opened:
            closed.append(descriptor)
        return real_close(descriptor)

    def read_observed(descriptor, size):
        if descriptor in opened:
            reads.append(descriptor)
        return real_read(descriptor, size)

    os.open, os.close, os.read = open_with_swap, close_observed, read_observed
    try:
        with pytest.raises((RuntimeError, OSError, RawCollectionStorageError)):
            invoke()
        assert swapped
        assert len(opened) == (0 if replacement == "symlink" else 1)
        assert reads == []
        assert closed == opened
        for descriptor in opened:
            with pytest.raises(OSError) as error:
                os.fstat(descriptor)
            assert error.value.errno == errno.EBADF
        assert lookups == []
    finally:
        os.open, os.close, os.read = real_open, real_close, real_read

    assert saved.read_bytes() == original
    if replacement == "directory":
        destination.rmdir()
    else:
        destination.unlink()
    saved.rename(destination)
    result = invoke()
    if operation in {"journal", "temporary"}:
        assert result.retained_committed == 1 and result.removed_orphans == 0
        assert len(lookups) == 1
        assert not pending.journal_path.exists()
    elif operation == "chunk":
        assert result == encrypted.envelope
    elif operation == "probe":
        assert result == storage._raw_file_fingerprint(chunk_path.stat())
    assert chunk_path.read_bytes() == encrypted.envelope
    return {"operation": operation, "replacement": replacement, "rejected": True, "retry_ok": True}


_CHILD = r"""
import json, os, socket, sys
if sys.flags.optimize:
    raise RuntimeError("raw storage I/O checks require assertions enabled")
os.environ["WALKSAFE_ENVIRONMENT"] = "development"
os.environ["DATABASE_URL"] = "postgresql+psycopg://test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured"
attempts = {"database": 0, "network": 0}
def deny_network(*args, **kwargs):
    attempts["network"] += 1
    raise AssertionError("network forbidden in owned RAW I/O child")
def deny_database(*args, **kwargs):
    attempts["database"] += 1
    raise AssertionError("database forbidden in owned RAW I/O child")
socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
socket.getaddrinfo = deny_network
from sqlalchemy.engine import Engine
Engine.connect = deny_database
Engine.raw_connection = deny_database
from pathlib import Path
from backend.tests.test_raw_collection_storage_readiness_io import _run_open_race
observed = _run_open_race(Path(sys.argv[1]), sys.argv[2], sys.argv[3])
observed["guards"] = attempts
print("RAW_IO_RESULT " + json.dumps(observed, sort_keys=True))
raise SystemExit(1 if any(attempts.values()) else 0)
"""


@pytest.mark.parametrize("operation", ("journal", "temporary", "chunk", "probe", "inventory"))
@pytest.mark.parametrize("replacement", ("fifo", "symlink", "directory"))
def test_raw_open_swap_rejects_without_hanging_and_allows_recovery(
    tmp_path: Path, operation: str, replacement: str,
) -> None:
    environment = dict(os.environ)
    for name in ("WALKSAFE_TEST_DATABASE_URL", "WALKSAFE_MIGRATION_DATABASE_URL", "PYTEST_ADDOPTS", "PYTHONOPTIMIZE"):
        environment.pop(name, None)
    # A timeout kills only this owned child; it is not a production I/O deadline.
    try:
        result = subprocess.run(
            [sys.executable, "-B", "-c", _CHILD, str(tmp_path / "raw"), operation, replacement],
            cwd=Path(__file__).resolve().parents[2], env=environment,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=5,
        )
    except subprocess.TimeoutExpired as error:
        pytest.fail(f"RAW read did not finish in owned child: {error.stdout!r}")
    assert result.returncode == 0, result.stdout
    assert "RAW_OPEN_SWAP_REACHED" in result.stdout
    lines = [line for line in result.stdout.splitlines() if line.startswith("RAW_IO_RESULT ")]
    assert len(lines) == 1, result.stdout
    assert json.loads(lines[0].removeprefix("RAW_IO_RESULT ")) == {
        "operation": operation, "replacement": replacement, "rejected": True, "retry_ok": True,
        "guards": {"database": 0, "network": 0},
    }
