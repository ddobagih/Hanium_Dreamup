#!/usr/bin/env python3
"""Atomically publish the byte-exact FP-046 seq51/52 projection."""

from __future__ import annotations

import argparse
import copy
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
from scripts import materialize_walksafe_fp046_goal_seq51_52_20260809 as materialize


class PublicationError(RuntimeError):
    """The exact projection cannot safely replace the seq50 checkpoint."""


READY_RECONCILIATION_SOURCE_SHA256 = (
    "92e62d23edf9820c98ec09bc70fce7e33204d2f91b8cb0cf92a1606d3c55ddca"
)
READY_RECONCILIATION_SOURCE_BYTE_COUNT = 1_645_894
READY_MANAGED_PATH_COUNT = 722
READY_MANAGED_PATH_SET_SHA256 = (
    "32d880ed4c5bc2561af7d18fa1d89b913abd143abb4ba324e442d53af13e3aa3"
)
SAFE_LAST_VERIFICATION_STATUS = (
    "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


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


def prepare_ready_reconciliation(root: Path) -> PreparedPublication:
    """Normalize the sealed seq52 operational pointers after publication."""

    root = root.resolve(strict=True)
    checkpoint_path = root / materialize.CHECKPOINT
    metadata = checkpoint_path.lstat()
    source_bytes = checkpoint_path.read_bytes()
    _require(
        stat.S_ISREG(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o600
        and metadata.st_nlink == 1
        and len(source_bytes) == READY_RECONCILIATION_SOURCE_BYTE_COUNT
        and hashlib.sha256(source_bytes).hexdigest()
        == READY_RECONCILIATION_SOURCE_SHA256,
        "live checkpoint is not the exact reconcilable seq52 publication",
    )
    source = json.loads(source_bytes)
    _require(isinstance(source, dict), "reconciliation source root differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    snapshot = source.get("working_tree_snapshot")
    handoff = source.get("session_handoff")
    current = source.get("current_work")
    _require(
        isinstance(state, dict)
        and isinstance(history, list)
        and len(history) == 52
        and history[-2].get("event_id") == materialize.MATERIALIZED_EVENT_ID
        and history[-2].get("event_sha256")
        == materialize.EXPECTED_MATERIALIZED_EVENT_SHA256
        and history[-1].get("event_id") == materialize.READY_EVENT_ID
        and history[-1].get("event_sha256")
        == materialize.EXPECTED_READY_EVENT_SHA256
        and history[-1].get("previous_event_sha256")
        == history[-2].get("event_sha256")
        and state.get("transition_history_anchor_sha256")
        == history[-1].get("event_sha256")
        and state.get("status_by_goal", {}).get(materialize.GOAL_ID) == "READY",
        "reconciliation source seq51/52 seal differs",
    )
    _require(
        isinstance(snapshot, dict)
        and isinstance(snapshot.get("managed_changed_paths"), list)
        and snapshot.get("managed_changed_path_count") == READY_MANAGED_PATH_COUNT
        and snapshot.get("path_set_sha256") == READY_MANAGED_PATH_SET_SHA256
        and isinstance(handoff, dict)
        and isinstance(current, dict)
        and current.get("status") == "READY"
        and handoff.get("last_verification_status")
        == SAFE_LAST_VERIFICATION_STATUS,
        "reconciliation source operational boundary differs",
    )

    projected = copy.deepcopy(source)
    projected["current_work"]["status"] = "READY"
    projected["session_handoff"]["last_verification_status"] = (
        SAFE_LAST_VERIFICATION_STATUS
    )
    projected_snapshot = projected["working_tree_snapshot"]
    paths = projected_snapshot["managed_changed_paths"]
    path_sha256, content_sha256 = materialize.contract.working_snapshot_hashes(
        root,
        paths,
    )
    _require(
        path_sha256 == READY_MANAGED_PATH_SET_SHA256,
        "reconciled managed path authority differs",
    )
    projected_snapshot["path_set_sha256"] = path_sha256
    projected_snapshot["content_set_sha256"] = content_sha256
    projected_handoff_snapshot = projected["session_handoff"][
        "source_commit_or_snapshot"
    ]
    projected_handoff_snapshot["file_count"] = len(paths)
    projected_handoff_snapshot["path_set_sha256"] = path_sha256
    projected_handoff_snapshot["content_set_sha256"] = content_sha256
    projected["session_handoff"]["changed_files"] = copy.deepcopy(paths)
    materialize.require_ready_checkpoint(root, projected)

    cohort = PinnedCohort.capture(root, paths)
    try:
        _require(
            cohort.content_set_sha256() == content_sha256,
            "reconciled retained cohort content authority differs",
        )
        cohort.verify()
        _require(
            checkpoint_path.read_bytes() == source_bytes,
            "live seq52 changed during reconciliation preparation",
        )
        return PreparedPublication(
            root=root,
            source_bytes=source_bytes,
            source=source,
            projected=projected,
            projected_bytes=(
                json.dumps(projected, ensure_ascii=False, indent=2) + "\n"
            ).encode("utf-8"),
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
        materialize.require_ready_checkpoint(
            prepared.root,
            json.loads(prepared.projected_bytes),
        )
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
    mode.add_argument("--reconcile-ready", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = (
            prepare_ready_reconciliation(args.root)
            if args.reconcile_ready
            else prepare(args.root)
        )
        if args.write or args.reconcile_ready:
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
        print(f"WalkSafe FP046 seq51/52 publication: FAIL: {exc}", file=sys.stderr)
        return 1
    mode = (
        "RECONCILE_READY"
        if args.reconcile_ready
        else "WRITE" if args.write else "PREFLIGHT"
    )
    print(
        "WalkSafe FP046 seq51/52 publication: PASS "
        f"mode={mode} seq51={prepared.projected['goal_execution']['transition_history'][-2]['event_sha256']} "
        f"seq52={prepared.projected['goal_execution']['transition_history'][-1]['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
