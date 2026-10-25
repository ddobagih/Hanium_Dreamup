from __future__ import annotations

import errno
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

import pytest

from backend.app.services import report_storage
from backend.app.services.report_image_crypto import encrypt_report_image


def _inventory(root: Path):
    root.chmod(0o700)
    report_id = uuid.uuid4()
    encrypted = encrypt_report_image(
        b"\xff\xd8\xffbounded-inventory",
        report_id=report_id,
        content_type="image/jpeg",
        key_id="test-key",
        key=bytes(range(32)),
    )
    path = root / f"{report_id}.wse"
    path.write_bytes(encrypted.envelope)
    path.chmod(0o600)
    metadata = report_storage.ReportImageCommitMetadata(
        storage_name=path.name,
        envelope_version=1,
        algorithm="AES-256-GCM",
        aad_version=1,
        key_id=encrypted.key_id,
        nonce=encrypted.nonce,
        envelope_sha256=encrypted.envelope_sha256,
        envelope_size=len(encrypted.envelope),
        plaintext_sha256=encrypted.plaintext_sha256,
        plaintext_size=encrypted.plaintext_length,
        content_type=encrypted.content_type,
    )
    entry = report_storage.ReportStorageInventoryEntry(
        report_id=report_id,
        logical_image_path=f"/uploads/{report_id}.jpg",
        report_content_type="image/jpeg",
        image_object=metadata,
    )
    return encrypted, path, entry


def _run_reopen_race(root: Path, replacement: str) -> dict[str, object]:
    """Run the real readiness thread and filesystem fault inside an owned child."""
    import asyncio

    from backend.app.services import health_readiness as health

    encrypted, destination, entry = _inventory(root)
    real_open, real_close, real_read = os.open, os.close, os.read
    real_thread = health.Thread
    workers = []
    opened = []
    closed = []
    flags_seen = []
    reads = []
    swapped = False

    def open_with_race(path, flags, *args, **kwargs):
        nonlocal swapped
        if (
            not swapped
            and kwargs.get("dir_fd") is None
            and os.fspath(path).startswith("/proc/self/fd/")
            and Path(path).name == destination.name
        ):
            swapped = True
            flags_seen.append(flags)
            destination.unlink()
            if replacement == "fifo":
                os.mkfifo(destination, 0o600)
            elif replacement == "symlink":
                destination.symlink_to(root / "absent-target")
            else:
                destination.mkdir(mode=0o700)
            print("S20_48_COMMITTED_REOPEN_REACHED", flush=True)
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

    def thread_observed(*args, **kwargs):
        worker = real_thread(*args, **kwargs)
        workers.append(worker)
        return worker

    def check():
        report_storage.validate_report_storage_inventory(
            root, [entry], known_key_states={"test-key": "active"},
        )
        return {"ready": True, "inventory": "checked"}

    probe = health._OffloadedReadinessProbe(check)
    os.open, os.close, os.read = open_with_race, close_observed, read_observed
    health.Thread = thread_observed
    try:
        rejected = asyncio.run(probe.check())
        assert rejected == {"ready": False, "reason": "RuntimeError"}
        assert swapped and len(flags_seen) == 1
        assert flags_seen[0] & os.O_NONBLOCK
        assert flags_seen[0] & os.O_NOFOLLOW
        assert flags_seen[0] & os.O_CLOEXEC
        assert reads == []
        assert closed == opened
        assert len(opened) == (0 if replacement == "symlink" else 1)
        assert len(workers) == 1
        workers[0].join(timeout=1)
        assert not workers[0].is_alive()
        for descriptor in opened:
            with pytest.raises(OSError) as error:
                os.fstat(descriptor)
            assert error.value.errno == errno.EBADF
    finally:
        os.open, os.close, os.read = real_open, real_close, real_read
        health.Thread = real_thread

    if replacement == "directory":
        destination.rmdir()
    else:
        destination.unlink()
    destination.write_bytes(encrypted.envelope)
    destination.chmod(0o600)
    first_worker = workers[0]
    health.Thread = thread_observed
    try:
        accepted = asyncio.run(probe.check())
        assert accepted == {"ready": True, "inventory": "checked"}
        assert len(workers) == 2
        assert workers[1] is not first_worker
        workers[1].join(timeout=1)
        assert not workers[1].is_alive()
        assert not first_worker.is_alive()
    finally:
        health.Thread = real_thread

    return {
        "replacement": replacement,
        "reopen_reached": True,
        "nonregular_rejected": True,
        "content_reads_before_rejection": len(reads),
        "committed_descriptors_closed": closed == opened,
        "first_worker_exited_before_retry": True,
        "replacement_worker_exited": True,
        "recovered_ready": accepted["ready"],
    }


_CHILD = r"""
import json, os, socket, sys
os.environ["WALKSAFE_ENVIRONMENT"] = "development"
os.environ["DATABASE_URL"] = "postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured"
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
attempts = {"database": 0, "network": 0}
def deny_network(*args, **kwargs):
    attempts["network"] += 1
    raise AssertionError("network forbidden in the owned readiness I/O child")
def deny_database(*args, **kwargs):
    attempts["database"] += 1
    raise AssertionError("database forbidden in the owned readiness I/O child")
socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
socket.getaddrinfo = deny_network
from sqlalchemy.engine import Engine
Engine.connect = deny_database
Engine.raw_connection = deny_database
from pathlib import Path
from backend.tests.test_report_storage_readiness_io import _run_reopen_race
observed = _run_reopen_race(Path(sys.argv[1]), sys.argv[2])
observed["guards"] = attempts
print("S20_48_IO_CHILD " + json.dumps(observed, sort_keys=True))
raise SystemExit(1 if any(attempts.values()) else 0)
"""


@pytest.mark.parametrize("replacement", ("fifo", "symlink", "directory"))
def test_nonregular_swap_at_committed_reopen_finishes_worker_and_allows_fresh_check(
    tmp_path: Path, replacement: str,
) -> None:
    environment = dict(os.environ)
    for name in ("WALKSAFE_TEST_DATABASE_URL", "WALKSAFE_MIGRATION_DATABASE_URL", "PYTEST_ADDOPTS"):
        environment.pop(name, None)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    # run(timeout=...) kills and waits for its owned child on a regression.
    # This contains a blocked FIFO open; it is not a production I/O supervisor.
    try:
        result = subprocess.run(
            [sys.executable, "-B", "-c", _CHILD, str(tmp_path), replacement],
            cwd=Path(__file__).resolve().parents[2],
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=5,
        )
    except subprocess.TimeoutExpired as error:
        pytest.fail(f"committed reopen did not finish in the owned child: {error.stdout!r}")
    assert result.returncode == 0, result.stdout
    assert "S20_48_COMMITTED_REOPEN_REACHED" in result.stdout
    lines = [line for line in result.stdout.splitlines() if line.startswith("S20_48_IO_CHILD ")]
    assert len(lines) == 1, result.stdout
    observed = json.loads(lines[0].removeprefix("S20_48_IO_CHILD "))
    assert observed == {
        "replacement": replacement,
        "reopen_reached": True,
        "nonregular_rejected": True,
        "content_reads_before_rejection": 0,
        "committed_descriptors_closed": True,
        "first_worker_exited_before_retry": True,
        "replacement_worker_exited": True,
        "recovered_ready": True,
        "guards": {"database": 0, "network": 0},
    }


@pytest.mark.parametrize("invalid", ("digest", "size", "key_state"))
def test_nonblocking_regular_files_keep_digest_size_and_key_state_validation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, invalid: str,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "development")
    encrypted, destination, entry = _inventory(tmp_path)
    keys = {"test-key": "active"}
    report_storage.validate_report_storage_inventory(tmp_path, [entry], known_key_states=keys)
    if invalid == "digest":
        changed = bytearray(encrypted.envelope)
        changed[-1] ^= 1
        destination.write_bytes(changed)
        message = "digest does not match"
    elif invalid == "size":
        destination.write_bytes(encrypted.envelope[:-1])
        message = "file metadata changed"
    else:
        keys = {"test-key": "compromised"}
        message = "unavailable key lifecycle state"

    with pytest.raises(RuntimeError, match=message):
        report_storage.validate_report_storage_inventory(tmp_path, [entry], known_key_states=keys)

    destination.write_bytes(encrypted.envelope)
    destination.chmod(0o600)
    report_storage.validate_report_storage_inventory(
        tmp_path, [entry], known_key_states={"test-key": "active"},
    )
