#!/usr/bin/env python3
"""Restore Git-unrepresentable modes for the three active private evidence sets."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import stat


PRIVATE_EVENT_DIRECTORIES = (
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
)


class ModeRestoreError(RuntimeError):
    pass


def restore_modes(root: Path) -> tuple[int, int]:
    root = root.resolve(strict=True)
    directory_count = 0
    file_count = 0
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    file_flags = os.O_RDONLY | os.O_NOFOLLOW
    for relative in PRIVATE_EVENT_DIRECTORIES:
        directory = root / relative
        try:
            directory_fd = os.open(directory, directory_flags)
        except OSError as exc:
            raise ModeRestoreError(f"cannot open private directory {relative}: {exc}") from exc
        file_fds: list[tuple[str, int]] = []
        try:
            directory_stat = os.fstat(directory_fd)
            if not stat.S_ISDIR(directory_stat.st_mode):
                raise ModeRestoreError(f"private path is not a directory: {relative}")
            names = sorted(os.listdir(directory_fd))
            if not names:
                raise ModeRestoreError(f"private directory is empty: {relative}")
            for name in names:
                if not name or name in {".", ".."} or "/" in name:
                    raise ModeRestoreError(f"invalid private evidence entry in {relative}")
                try:
                    file_fd = os.open(name, file_flags, dir_fd=directory_fd)
                except OSError as exc:
                    raise ModeRestoreError(
                        f"cannot open private evidence file {relative / name}: {exc}"
                    ) from exc
                file_stat = os.fstat(file_fd)
                if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_nlink != 1:
                    os.close(file_fd)
                    raise ModeRestoreError(
                        f"private evidence entry must be a single-link regular file: {relative / name}"
                    )
                file_fds.append((name, file_fd))

            os.fchmod(directory_fd, 0o700)
            if stat.S_IMODE(os.fstat(directory_fd).st_mode) != 0o700:
                raise ModeRestoreError(f"private directory mode restore failed: {relative}")
            for name, file_fd in file_fds:
                os.fchmod(file_fd, 0o600)
                after = os.fstat(file_fd)
                if stat.S_IMODE(after.st_mode) != 0o600 or after.st_nlink != 1:
                    raise ModeRestoreError(
                        f"private evidence file mode restore failed: {relative / name}"
                    )
                file_count += 1
            directory_count += 1
        finally:
            for _, file_fd in file_fds:
                os.close(file_fd)
            os.close(directory_fd)
    return directory_count, file_count


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        directories, files = restore_modes(args.root)
    except (ModeRestoreError, OSError) as exc:
        print(f"ERROR: {exc}")
        return 1
    print(f"PASS: private evidence modes restored (directories={directories}, files={files})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
