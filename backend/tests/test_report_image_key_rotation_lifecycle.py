from __future__ import annotations

import base64
from contextlib import contextmanager
from copy import deepcopy
import json
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest

from backend.app.models import ReportImageKeyringEvent
from backend.app.services.report_image_crypto import (
    decrypt_report_image,
    encrypt_report_image,
)
from backend.app.services.report_image_keys import (
    KEYRING_SCHEMA,
    REPORT_IMAGE_KEYRING_LOCK_KEY,
    ReportImageKeyError,
    ReportImageKeyManager,
    ReportImageKeyUnavailable,
    ReportImageKeyringInvalid,
    SecretFileReportImageKeyProvider,
    parse_report_image_keyring,
)


class _CommitObservationLost(RuntimeError):
    pass


class _History:
    """Persist public ORM events only; the real service validates all transitions."""

    def __init__(self) -> None:
        self.events = []
        self.statements = []
        self.commits = 0
        self.rollbacks = 0
        self.closes = 0

    def snapshot(self):
        return deepcopy([
            {
                "generation": event.generation,
                "manifest_sha256": event.manifest_sha256,
                "previous_manifest_sha256": event.previous_manifest_sha256,
                "active_key_id": event.active_key_id,
                "key_states": event.key_states,
            }
            for event in self.events
        ])

    @contextmanager
    def transaction(self, fault=None):
        db = _Session(self)
        try:
            yield db
            if fault == "before_commit":
                raise _CommitObservationLost("commit did not happen")
            self.events.extend(db.pending)
            db.pending.clear()
            self.commits += 1
            if fault == "after_commit":
                raise _CommitObservationLost("commit response was not observed")
        except BaseException:
            db.pending.clear()
            self.rollbacks += 1
            raise
        finally:
            self.closes += 1


class _Session:
    def __init__(self, history: _History) -> None:
        self.history = history
        self.pending = []
        self.flushes = 0
        self.expected_manifest_sha256: str | None = None

    def execute(self, statement, parameters=None):
        self.history.statements.append((str(statement), parameters))
        if parameters is not None:
            latest = self.history.events[-1] if self.history.events else None
            same_manifest = (
                latest is not None
                and latest.manifest_sha256 == self.expected_manifest_sha256
            )
            lock_function = (
                "pg_advisory_xact_lock_shared" if same_manifest else "pg_advisory_xact_lock"
            )
            assert str(statement) == f"SELECT {lock_function}(hashtextextended(:lock_key, 0))"
            assert parameters == {"lock_key": REPORT_IMAGE_KEYRING_LOCK_KEY}
            return SimpleNamespace()
        assert statement.column_descriptions[0]["entity"] is ReportImageKeyringEvent
        latest = self.history.events[-1] if self.history.events else None
        return SimpleNamespace(scalar_one_or_none=lambda: latest)

    def add(self, event) -> None:
        assert isinstance(event, ReportImageKeyringEvent)
        self.pending.append(event)

    def flush(self) -> None:
        self.flushes += 1


def _manifest(generation: int, previous: str | None, active_version: int) -> bytes:
    return json.dumps({
        "schema": KEYRING_SCHEMA,
        "generation": generation,
        "previous_manifest_sha256": previous,
        "keys": [
            {
                "id": f"key-v{version}",
                "material": base64.urlsafe_b64encode(bytes([version]) * 32).decode("ascii").rstrip("="),
                "state": "active" if version == active_version else "decrypt-only",
            }
            for version in range(1, active_version + 1)
        ],
    }, sort_keys=True, separators=(",", ":")).encode("ascii")


def _publish(path: Path, raw: bytes) -> None:
    # Test-only material, at a private temporary path; no production key is read.
    staged = path.with_name(f"keyring-{uuid.uuid4().hex}.next")
    descriptor = os.open(staged, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    staged.chmod(0o400)
    os.replace(staged, path)


def _manager(path: Path) -> ReportImageKeyManager:
    return ReportImageKeyManager(SecretFileReportImageKeyProvider(path))


def _synchronize(manager, history, fault=None) -> None:
    with history.transaction(fault) as db:
        db.expected_manifest_sha256 = manager.keyring.manifest_sha256
        manager.synchronize(db)


def _initial_generation(tmp_path: Path, workers: int = 1):
    tmp_path.chmod(0o700)
    path = tmp_path / "keyring.json"
    raw = _manifest(1, None, 1)
    _publish(path, raw)
    history = _History()
    managers = [_manager(path) for _ in range(workers)]
    for manager in managers:
        _synchronize(manager, history)
    assert len(history.events) == 1
    assert history.events[0].manifest_sha256 == managers[0].keyring.manifest_sha256
    return path, raw, history, managers


def _encrypt(manager, content=b"\xff\xd8\xffrotation-check"):
    slot = manager.encryption_slot()
    return encrypt_report_image(
        content,
        report_id=uuid.uuid4(),
        content_type="image/jpeg",
        key_id=slot.key_id,
        key=slot.material,
    )


def _decrypt(manager, encrypted):
    return decrypt_report_image(
        encrypted.envelope,
        expected_report_id=encrypted.report_id,
        key=manager.decryption_key(encrypted.key_id),
    ).content


@pytest.mark.parametrize("worker_count", (1, 3))
def test_published_rotation_rejects_all_old_managers_and_new_workers_keep_old_decryption(
    tmp_path: Path, worker_count: int,
) -> None:
    path, _, history, old_workers = _initial_generation(tmp_path, worker_count)
    first = old_workers[0].keyring
    encrypted_before = _encrypt(old_workers[0])
    second_raw = _manifest(2, first.manifest_sha256, 2)
    second = parse_report_image_keyring(second_raw)
    before = history.snapshot()

    _publish(path, second_raw)

    for old in old_workers:
        assert old.readiness() == {
            "ready": False, "reason": "report_image_keyring_restart_required",
        }
        assert old.keyring is first if old is old_workers[0] else old.keyring == first
        with pytest.raises(ReportImageKeyUnavailable, match="restart"):
            old.encryption_slot()
        with pytest.raises(ReportImageKeyUnavailable, match="restart"):
            old.decryption_key(encrypted_before.key_id)
        query_count = len(history.statements)
        with pytest.raises(ReportImageKeyUnavailable, match="restart"):
            _synchronize(old, history)
        assert len(history.statements) == query_count
        assert history.snapshot() == before

    replacements = [_manager(path) for _ in range(worker_count)]
    # Local provider readiness is not proof that startup's DB transaction succeeded.
    assert all(manager.readiness()["generation"] == 2 for manager in replacements)
    assert history.snapshot() == before
    for replacement in replacements:
        _synchronize(replacement, history)
        assert replacement.readiness()["ready"] is True
        assert _decrypt(replacement, encrypted_before) == b"\xff\xd8\xffrotation-check"
        encrypted_after = _encrypt(replacement, b"new-generation")
        assert encrypted_after.key_id == "key-v2"
        assert _decrypt(replacement, encrypted_after) == b"new-generation"
        assert replacement.keyring.slot("key-v1").state == "decrypt-only"

    assert [event.generation for event in history.events] == [1, 2]
    assert history.events[-1].manifest_sha256 == second.manifest_sha256
    assert history.events[-1].previous_manifest_sha256 == first.manifest_sha256
    assert history.events[-1].active_key_id == "key-v2"
    assert all("material" not in state for event in history.events for state in event.key_states)
    assert all(old.readiness()["ready"] is False for old in old_workers)


@pytest.mark.parametrize("invalid", ("reused_generation", "skipped_generation", "wrong_previous"))
def test_provider_ready_cannot_substitute_for_successful_generation_synchronization(
    tmp_path: Path, invalid: str,
) -> None:
    path, _, history, workers = _initial_generation(tmp_path)
    first = workers[0].keyring
    generation = {"reused_generation": 1, "skipped_generation": 3, "wrong_previous": 2}[invalid]
    previous = "f" * 64 if invalid == "wrong_previous" else first.manifest_sha256
    before = history.snapshot()
    _publish(path, _manifest(generation, previous, 2))
    candidate = _manager(path)
    assert candidate.readiness()["ready"] is True

    with pytest.raises(ReportImageKeyringInvalid, match="generation chain"):
        _synchronize(candidate, history)

    assert history.snapshot() == before
    assert history.rollbacks == 1
    assert workers[0].readiness()["ready"] is False
    _publish(path, _manifest(2, first.manifest_sha256, 2))
    replacement = _manager(path)
    _synchronize(replacement, history)
    assert [event.generation for event in history.events] == [1, 2]
    assert replacement.readiness()["ready"] is True
    assert replacement.encryption_slot().key_id == "key-v2"


@pytest.mark.parametrize("fault", ("before_commit", "after_commit"))
def test_startup_retry_keeps_the_same_manifest_when_commit_outcome_is_uncertain(
    tmp_path: Path, fault: str,
) -> None:
    path, _, history, workers = _initial_generation(tmp_path)
    first = workers[0].keyring
    _publish(path, _manifest(2, first.manifest_sha256, 2))
    candidate = _manager(path)

    with pytest.raises(_CommitObservationLost):
        _synchronize(candidate, history, fault)

    assert [event.generation for event in history.events] == (
        [1] if fault == "before_commit" else [1, 2]
    )
    assert workers[0].readiness()["ready"] is False
    # A new startup reads the actual stored history instead of guessing rollback.
    replacement = _manager(path)
    assert replacement.keyring.manifest_sha256 == candidate.keyring.manifest_sha256
    _synchronize(replacement, history)
    assert [event.generation for event in history.events] == [1, 2]
    assert history.events[-1].manifest_sha256 == candidate.keyring.manifest_sha256
    assert history.rollbacks == 1
    assert history.closes == 3
    assert _decrypt(replacement, _encrypt(replacement)) == b"\xff\xd8\xffrotation-check"


def test_old_manifest_rollback_cannot_pass_startup_after_a_committed_rotation(
    tmp_path: Path,
) -> None:
    path, first_raw, history, workers = _initial_generation(tmp_path)
    first = workers[0].keyring
    old_image = _encrypt(workers[0], b"first-generation")
    _publish(path, _manifest(2, first.manifest_sha256, 2))
    second_worker = _manager(path)
    _synchronize(second_worker, history)
    second_image = _encrypt(second_worker, b"second-generation")
    second = second_worker.keyring
    before = history.snapshot()

    _publish(path, first_raw)
    reverted = _manager(path)
    assert reverted.readiness()["ready"] is True
    with pytest.raises(ReportImageKeyringInvalid, match="generation chain"):
        _synchronize(reverted, history)
    assert history.snapshot() == before
    assert second_worker.readiness()["ready"] is False

    _publish(path, _manifest(3, second.manifest_sha256, 3))
    forward = _manager(path)
    _synchronize(forward, history)
    assert [event.generation for event in history.events] == [1, 2, 3]
    assert forward.readiness()["generation"] == 3
    assert forward.encryption_slot().key_id == "key-v3"
    assert _decrypt(forward, old_image) == b"first-generation"
    assert _decrypt(forward, second_image) == b"second-generation"


@pytest.mark.parametrize("fault", ("missing", "malformed"))
def test_unavailable_provider_never_admits_a_worker_and_unchanged_history_can_restart(
    tmp_path: Path, fault: str,
) -> None:
    path, first_raw, history, workers = _initial_generation(tmp_path)
    original = _encrypt(workers[0])
    before = history.snapshot()
    if fault == "missing":
        path.unlink()
    else:
        _publish(path, b"not-json")

    assert workers[0].readiness() == {
        "ready": False, "reason": "report_image_key_provider_unavailable",
    }
    with pytest.raises(ReportImageKeyError):
        workers[0].encryption_slot()
    with pytest.raises(ReportImageKeyError):
        _manager(path)
    assert history.snapshot() == before

    # This fixture proves no candidate reached DB synchronization or encryption.
    _publish(path, first_raw)
    restarted = _manager(path)
    _synchronize(restarted, history)
    assert history.snapshot() == before
    assert restarted.readiness()["ready"] is True
    assert _decrypt(restarted, original) == b"\xff\xd8\xffrotation-check"
