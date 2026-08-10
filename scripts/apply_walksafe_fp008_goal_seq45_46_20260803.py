#!/usr/bin/env python3
"""Atomically publish the byte-exact FP-008 seq45/46 projection."""

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
from scripts import materialize_walksafe_fp008_goal_seq45_46_20260803 as materialize


class PublicationError(RuntimeError):
    """The exact projection cannot safely replace the seq44 checkpoint."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise PublicationError(message)


def _safe_file(root: Path, relative: str) -> Path:
    value = Path(relative)
    _require(not value.is_absolute() and ".." not in value.parts, f"unsafe pin path: {relative}")
    current = root
    for part in value.parts:
        current /= part
        _require(not current.is_symlink(), f"pin path contains a symlink: {relative}")
    _require(current.is_file(), f"pin path is missing or not a file: {relative}")
    return current


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


@dataclass
class _PinnedFile:
    relative: str
    path: Path
    descriptor: int
    identity: tuple[int, ...]
    sha256: str

    def verify(self) -> None:
        opened = os.fstat(self.descriptor)
        _require(_identity(opened) == self.identity, f"retained pin metadata changed: {self.relative}")
        named = self.path.lstat()
        _require(
            stat.S_ISREG(named.st_mode)
            and _identity(named) == self.identity
            and not self.path.is_symlink(),
            f"named pin identity changed: {self.relative}",
        )
        _require(_digest_fd(self.descriptor) == self.sha256, f"retained pin bytes changed: {self.relative}")


class PinnedCohort:
    def __init__(self, pins: list[_PinnedFile]) -> None:
        self._pins = pins

    @classmethod
    def capture(cls, root: Path, relative_paths: list[str]) -> "PinnedCohort":
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
                    _require(stat.S_ISREG(metadata.st_mode), f"pin is not regular: {relative}")
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

    def content_set_sha256(self) -> str:
        digest = hashlib.sha256()
        for pin in sorted(self._pins, key=lambda item: item.relative):
            digest.update(pin.relative.encode("utf-8"))
            digest.update(b"\0")
            digest.update(pin.sha256.encode("ascii"))
            digest.update(b"\n")
        return digest.hexdigest()

    def verify(self) -> None:
        for pin in self._pins:
            pin.verify()

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
    source_bytes: bytes
    source: dict[str, Any]
    projected: dict[str, Any]
    projected_bytes: bytes
    cohort: PinnedCohort


def prepare(root: Path) -> PreparedPublication:
    root = root.resolve(strict=True)
    source_bytes, source = materialize.load_exact_source(root)
    projected, _, _ = materialize.project(root, source)
    errors = materialize.validate_projection(root, projected)
    _require(not errors, "projected checkpoint differs: " + "; ".join(errors))
    materialize.require_ready_checkpoint(root, projected)
    paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    _require(isinstance(paths, list), "projected managed paths are missing")
    cohort = PinnedCohort.capture(root, paths)
    try:
        _require(
            cohort.content_set_sha256()
            == projected["working_tree_snapshot"]["content_set_sha256"],
            "retained cohort content authority differs",
        )
        refreshed, _, _ = materialize.project(root, source)
        cohort.verify()
        _require(refreshed == projected, "projection changed while retaining inputs")
        _require(
            (root / materialize.CHECKPOINT).read_bytes() == source_bytes,
            "live checkpoint changed during publication preparation",
        )
        projected_bytes = (
            json.dumps(projected, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")
        return PreparedPublication(
            root=root,
            source_bytes=source_bytes,
            source=source,
            projected=projected,
            projected_bytes=projected_bytes,
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
            expected_source=prepared.source_bytes,
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
        materialize.require_ready_checkpoint(prepared.root, json.loads(prepared.projected_bytes))
    except BaseException as exc:
        primary = exc
        raise
    finally:
        prepared.cohort.close(primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = prepare(args.root)
        if args.write:
            publish(prepared)
        else:
            prepared.cohort.close()
    except (
        OSError,
        ValueError,
        PublicationError,
        materialize.ProjectionError,
        atomic.CompletionApplyError,
        atomic.CompletionPostCommitError,
    ) as exc:
        print(f"WalkSafe FP008 seq45/46 publication: FAIL: {exc}", file=sys.stderr)
        return 1
    mode = "WRITE" if args.write else "PREFLIGHT"
    print(
        "WalkSafe FP008 seq45/46 publication: PASS "
        f"mode={mode} seq45={materialize.EXPECTED_MATERIALIZED_EVENT_SHA256} "
        f"seq46={materialize.EXPECTED_READY_EVENT_SHA256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
