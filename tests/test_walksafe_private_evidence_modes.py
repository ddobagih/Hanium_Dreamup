from __future__ import annotations

import os
from pathlib import Path

import pytest

from scripts import restore_walksafe_private_evidence_modes as subject


def make_private_tree(root: Path) -> None:
    for relative in subject.PRIVATE_EVENT_DIRECTORIES:
        directory = root / relative
        directory.mkdir(parents=True)
        directory.chmod(0o755)
        evidence = directory / "receipt.json"
        evidence.write_text("{}\n", encoding="utf-8")
        evidence.chmod(0o644)


def test_restore_modes_is_exact_and_idempotent(tmp_path: Path) -> None:
    make_private_tree(tmp_path)

    assert subject.restore_modes(tmp_path) == (3, 3)
    assert subject.restore_modes(tmp_path) == (3, 3)

    for relative in subject.PRIVATE_EVENT_DIRECTORIES:
        directory = tmp_path / relative
        assert directory.stat().st_mode & 0o777 == 0o700
        assert (directory / "receipt.json").stat().st_mode & 0o777 == 0o600


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
