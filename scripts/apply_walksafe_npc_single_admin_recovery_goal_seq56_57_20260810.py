#!/usr/bin/env python3
"""Atomically publish the exact NPC single-admin-recovery seq56/57 projection."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import materialize_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810 as materialize


class PublicationError(RuntimeError):
    """The sealed seq56/57 projection cannot be safely published."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise PublicationError(message)


def _safe_file(root: Path, relative: str) -> Path:
    value = Path(relative)
    _require(
        not value.is_absolute() and ".." not in value.parts,
        f"unsafe retained path: {relative}",
    )
    current = root
    for part in value.parts:
        current /= part
        _require(not current.is_symlink(), f"retained path contains a symlink: {relative}")
    metadata = current.lstat()
    _require(
        stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1,
        f"retained path is not a single-link regular file: {relative}",
    )
    return current


def _identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
    )


def _digest_fd(descriptor: int) -> str:
    digest = hashlib.sha256()
    os.lseek(descriptor, 0, os.SEEK_SET)
    while True:
        chunk = os.read(descriptor, 1024 * 1024)
        if not chunk:
            break
        digest.update(chunk)
    os.lseek(descriptor, 0, os.SEEK_SET)
    return digest.hexdigest()


@dataclass
class _PinnedFile:
    relative: str
    path: Path
    descriptor: int
    identity: tuple[int, ...]
    sha256: str

    def verify(self) -> None:
        retained = os.fstat(self.descriptor)
        _require(
            _identity(retained) == self.identity,
            f"retained metadata changed: {self.relative}",
        )
        named = self.path.lstat()
        _require(
            stat.S_ISREG(named.st_mode)
            and not self.path.is_symlink()
            and _identity(named) == self.identity,
            f"named retained identity changed: {self.relative}",
        )
        _require(
            _digest_fd(self.descriptor) == self.sha256,
            f"retained bytes changed: {self.relative}",
        )


class PinnedCohort:
    def __init__(self, pins: list[_PinnedFile]) -> None:
        self._pins = pins

    @classmethod
    def capture(cls, root: Path, relative_paths: list[str]) -> "PinnedCohort":
        _require(
            relative_paths == sorted(set(relative_paths)),
            "retained path authority is not a sorted unique set",
        )
        pins: list[_PinnedFile] = []
        try:
            for relative in relative_paths:
                path = _safe_file(root, relative)
                descriptor = os.open(
                    path,
                    os.O_RDONLY
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                )
                try:
                    metadata = os.fstat(descriptor)
                    _require(
                        stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1,
                        f"retained descriptor differs: {relative}",
                    )
                    pins.append(
                        _PinnedFile(
                            relative=relative,
                            path=path,
                            descriptor=descriptor,
                            identity=_identity(metadata),
                            sha256=_digest_fd(descriptor),
                        )
                    )
                except BaseException:
                    os.close(descriptor)
                    raise
            cohort = cls(pins)
            cohort.verify()
            return cohort
        except BaseException:
            for pin in pins:
                os.close(pin.descriptor)
            raise

    def verify(self) -> None:
        for pin in self._pins:
            pin.verify()

    def content_set_sha256(self) -> str:
        digest = hashlib.sha256()
        for pin in sorted(self._pins, key=lambda item: item.relative):
            digest.update(pin.relative.encode("utf-8"))
            digest.update(b"\0")
            digest.update(pin.sha256.encode("ascii"))
            digest.update(b"\n")
        return digest.hexdigest()

    def close(self, primary: BaseException | None = None) -> None:
        first_error: BaseException | None = None
        while self._pins:
            pin = self._pins.pop()
            try:
                os.close(pin.descriptor)
            except BaseException as exc:
                if first_error is None:
                    first_error = exc
        if primary is None and first_error is not None:
            raise first_error


@dataclass
class PreparedPublication:
    root: Path
    source: materialize.SourceCheckpoint
    projected: dict[str, Any]
    projected_bytes: bytes
    cohort: PinnedCohort


def _serialize(checkpoint: dict[str, Any]) -> bytes:
    return (json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def prepare(root: Path) -> PreparedPublication:
    root = root.resolve(strict=True)
    source = materialize.load_exact_source(root)
    projected, _, _ = materialize.project(root, source)
    materialize.require_ready_checkpoint(root, projected)
    paths = projected.get("working_tree_snapshot", {}).get("managed_changed_paths")
    _require(isinstance(paths, list), "projected managed paths are missing")
    cohort = PinnedCohort.capture(root, paths)
    try:
        _require(
            cohort.content_set_sha256()
            == projected["working_tree_snapshot"]["content_set_sha256"],
            "retained content authority differs",
        )
        refreshed_source = materialize.load_exact_source(root)
        _require(
            refreshed_source.raw == source.raw
            and refreshed_source.raw_sha256 == source.raw_sha256
            and refreshed_source.byte_count == source.byte_count,
            "seq55 checkpoint changed during preparation",
        )
        refreshed, _, _ = materialize.project(root, refreshed_source)
        cohort.verify()
        _require(refreshed == projected, "projection changed while retaining inputs")
        return PreparedPublication(
            root=root,
            source=source,
            projected=projected,
            projected_bytes=_serialize(projected),
            cohort=cohort,
        )
    except BaseException as exc:
        cohort.close(exc)
        raise


def publish(
    prepared: PreparedPublication,
    *,
    atomic_writer: Callable[..., None] = atomic.atomic_write,
) -> None:
    primary: BaseException | None = None
    try:
        prepared.cohort.verify()
        atomic_writer(
            prepared.root / materialize.CHECKPOINT,
            prepared.projected_bytes,
            expected_source=prepared.source.raw,
            commit_guard=prepared.cohort.verify,
        )
        prepared.cohort.verify()
        path = prepared.root / materialize.CHECKPOINT
        metadata = path.lstat()
        _require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_nlink == 1
            and path.read_bytes() == prepared.projected_bytes,
            "published checkpoint bytes or authority differ",
        )
        published = materialize._strict_json(
            prepared.projected_bytes,
            "published checkpoint",
        )
        materialize.require_ready_checkpoint(prepared.root, published)
    except BaseException as exc:
        primary = exc
        raise
    finally:
        prepared.cohort.close(primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    prepared: PreparedPublication | None = None
    try:
        prepared = prepare(args.root)
        if args.write:
            publish(prepared)
        else:
            prepared.cohort.close()
    except (
        OSError,
        TypeError,
        ValueError,
        PublicationError,
        materialize.ProjectionError,
        atomic.CompletionApplyError,
        atomic.CompletionPostCommitError,
    ) as exc:
        print(
            "WalkSafe NPC single-admin-recovery seq56/57 publication: "
            f"FAIL: {exc}",
            file=sys.stderr,
        )
        return 1
    mode = "WRITE" if args.write else "PREFLIGHT"
    history = prepared.projected["goal_execution"]["transition_history"]
    print(
        "WalkSafe NPC single-admin-recovery seq56/57 publication: PASS "
        f"mode={mode} seq56={history[-2]['event_sha256']} "
        f"seq57={history[-1]['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
