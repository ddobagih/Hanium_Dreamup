from __future__ import annotations

import hashlib
import os
from pathlib import Path

import pytest

from scripts import restore_walksafe_private_evidence_modes as subject


EXPECTED_PRIVATE_EVENT_DIRECTORIES = (
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-20260812-006"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
    ),
)
EXPECTED_PRIVATE_LOGS = {
    Path(
        "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/"
        "logs/android-user-internal.log"
    ): (3701, "cdf1a98a74eb416ed30689d71e509f44155596ceb8d05a3df9c211014c904838"),
    Path(
        "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/"
        "logs/backend-navigation-internal.log"
    ): (531, "249031160869dc49edbec23a88808d17dadda9e174a1d5e97a0568361123d951"),
    Path(
        "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/"
        "logs/test-layer-registry-validate.log"
    ): (421, "e6502409d2de507ce23ef8dff787545a5b835d3862f3f1f362a326df71190e02"),
}


def make_private_tree(root: Path) -> None:
    for relative in EXPECTED_PRIVATE_EVENT_DIRECTORIES:
        directory = root / relative
        directory.mkdir(parents=True)
        directory.chmod(0o755)
        evidence = directory / "receipt.json"
        evidence.write_text("{}\n", encoding="utf-8")
        evidence.chmod(0o644)
    (root / next(iter(EXPECTED_PRIVATE_LOGS))).parent.parent.mkdir(parents=True)


def test_restore_modes_is_exact_and_idempotent(tmp_path: Path) -> None:
    make_private_tree(tmp_path)

    assert subject.PRIVATE_EVENT_DIRECTORIES == EXPECTED_PRIVATE_EVENT_DIRECTORIES
    assert subject.restore_modes(tmp_path) == (6, 8)
    assert subject.restore_modes(tmp_path) == (6, 8)

    for relative in EXPECTED_PRIVATE_EVENT_DIRECTORIES:
        directory = tmp_path / relative
        assert directory.stat().st_mode & 0o777 == 0o700
        assert (directory / "receipt.json").stat().st_mode & 0o777 == 0o600
    for relative, (byte_count, sha256) in EXPECTED_PRIVATE_LOGS.items():
        log = tmp_path / relative
        raw = log.read_bytes()
        assert len(raw) == byte_count
        assert hashlib.sha256(raw).hexdigest() == sha256
        assert log.stat().st_mode & 0o777 == 0o600
        assert log.stat().st_nlink == 1
        assert log.parent.stat().st_mode & 0o777 == 0o700


def test_restore_modes_rejects_symlink_entry(tmp_path: Path) -> None:
    make_private_tree(tmp_path)
    directory = tmp_path / subject.PRIVATE_EVENT_DIRECTORIES[0]
    (directory / "receipt.json").unlink()
    target = tmp_path / "outside.json"
    target.write_text("{}\n", encoding="utf-8")
    (directory / "receipt.json").symlink_to(target)

    with pytest.raises(subject.ModeRestoreError, match="cannot open private evidence file"):
        subject.restore_modes(tmp_path)


def test_restore_modes_rejects_hardlinked_entry(tmp_path: Path) -> None:
    make_private_tree(tmp_path)
    directory = tmp_path / subject.PRIVATE_EVENT_DIRECTORIES[0]
    os.link(directory / "receipt.json", directory / "second.json")

    with pytest.raises(subject.ModeRestoreError, match="single-link regular file"):
        subject.restore_modes(tmp_path)


def test_restore_modes_rejects_existing_wrong_log_without_overwriting(tmp_path: Path) -> None:
    make_private_tree(tmp_path)
    relative = next(iter(EXPECTED_PRIVATE_LOGS))
    log = tmp_path / relative
    log.parent.mkdir(parents=True)
    log.write_bytes(b"tampered\n")

    with pytest.raises(subject.ModeRestoreError, match="private evidence content differs"):
        subject.restore_modes(tmp_path)

    assert log.read_bytes() == b"tampered\n"


def test_restore_modes_rejects_symlinked_log_directory(tmp_path: Path) -> None:
    make_private_tree(tmp_path)
    relative = next(iter(EXPECTED_PRIVATE_LOGS))
    outside = tmp_path / "outside"
    outside.mkdir()
    log_directory = (tmp_path / relative).parent
    log_directory.symlink_to(outside, target_is_directory=True)

    with pytest.raises(subject.ModeRestoreError, match="cannot open private log directory"):
        subject.restore_modes(tmp_path)

    assert list(outside.iterdir()) == []
