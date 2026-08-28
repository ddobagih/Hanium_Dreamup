#!/usr/bin/env python3
"""Run the goal-scoped internal start gate for the READY FP-008 Goal."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import copy
import ctypes
from dataclasses import dataclass
from datetime import datetime, timedelta
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
from typing import Any, BinaryIO, Callable, Mapping, Sequence
from zoneinfo import ZoneInfo


sys.dont_write_bytecode = True


_LIBC = ctypes.CDLL(None, use_errno=True)
_RENAMEAT2 = getattr(_LIBC, "renameat2", None)
if _RENAMEAT2 is not None:
    _RENAMEAT2.argtypes = (
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    )
    _RENAMEAT2.restype = ctypes.c_int


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
ROOT_CONTROL_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp008_goal_seq45_46_20260803.py"
)
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-008-R001/"
    "initial-start-gate-contract-r001.json"
)
GOAL_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp008-admin-review-delivery-r001.md"
)
RUNTIME_BINDING_RELATIVES = (
    Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
    Path("apps/android/gradle/wrapper/gradle-wrapper.jar"),
    Path("apps/android/gradle/verification-metadata.xml"),
    Path("apps/android/app/gradle.lockfile"),
    Path("apps/android/adminapp/gradle.lockfile"),
)
SOURCE_GUARD_RELATIVES = (
    CHECKPOINT_RELATIVE,
    CONTRACT_RELATIVE,
    GOAL_RELATIVE,
    MANIFEST_RELATIVE,
    ROOT_CONTROL_TEST_RELATIVE,
    *RUNTIME_BINDING_RELATIVES,
)

PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
TARGET_GOAL_SHA256 = (
    "2654fe5f595aefaecf974a4de70bad7946c2385cba9716200f2d2f49d849dd8e"
)
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
WORK_ITEM_ID = "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY"
SOURCE_SEQUENCE = 46
MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP008-20260803-001"
)
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP008-20260803-001"
SOURCE_READY_EVENT_SHA256 = (
    "c34c4ab98cba1ff9a26ddb0d86b65f20b8f5cf53f9082543d4fa05881597c07d"
)
READY_FRONTIER = (
    TARGET_GOAL_ID,
    PARENT_GOAL_ID,
    "WS-GOAL-EPIC-12",
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
CONTRACT_DOCUMENT_ID = "WS-FP008-INITIAL-START-GATE-CONTRACT-20260803-001"
CONTRACT_ID = "WS-FP008-INTERNAL-START-GATE-R001"
CONTRACT_VERSION = "2026-08-03.1"
CONTRACT_FILE_SHA256 = (
    "ef6a9a555bbc46375d655c1d1d6a79ada715b02b164d01b80d5d5a5823d15599"
)
CONTRACT_CANONICAL_SHA256 = (
    "b3498acc49f388b9b17f3739ce7e895d33e040c1482386144c0bf33b5241f220"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_ADMIN_REPORTS_OPENAPI_POSTGRES",
    "ANDROID_USER_INTERNAL",
    "ANDROID_ADMIN_INTERNAL",
    "ROOT_FP008_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
FORBIDDEN_COMMAND_FRAGMENTS = (
    "apps/web",
    "android-gateway",
    "npm",
    "pwa",
    "legacy",
    "connecteddebugandroidtest",
    "device",
    "external",
    "release",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-(\d{8})-(\d{3})$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
CHECKPOINT_MAX_BYTES = 16 * 1024 * 1024
LOG_MAX_BYTES = 64 * 1024 * 1024
BOUND_GATE_EVENT_MAX_COUNT = 128
BOUND_GATE_FILE_MAX_COUNT = 4096
BOUND_GATE_TOTAL_MAX_BYTES = 64 * 1024 * 1024
SNAPSHOT_SCOPE = "AFTER_EXACT_TRANSACTION_EXCLUSIONS"
RECEIPT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "gate_purpose",
    "status",
    "package_id",
    "target_transition_event_id",
    "target_goal_id",
    "target_goal_content_sha256",
    "static_plan_manifest_sha256",
    "source_activation_event_sha256",
    "source_checkpoint_sha256",
    "source_ready_event_sha256",
    "check_command_contract_version",
    "check_command_contract_sha256",
    "implementation_start_gate_contract_binding",
    "runtime_bindings",
    "execution_window",
    "check_runs",
    "repository_snapshot",
    "generated_at",
}


class GateError(RuntimeError):
    """The gate cannot safely produce a PASS receipt."""


class _RepositoryAuthorityChanged(GateError):
    """The retained repository namespace or authority no longer matches."""


class GateCheckFailed(GateError):
    def __init__(self, check_id: str, exit_code: int, output_path: str) -> None:
        super().__init__(
            f"{check_id} failed with exit code {exit_code}; see {output_path}"
        )
        self.exit_code = exit_code


class GatePostCommitUncertain(GateError):
    """A complete final receipt was published but parent durability is uncertain."""


@dataclass(frozen=True)
class GateContext:
    checks: tuple[tuple[str, str], ...]
    checkpoint_sha256: str
    target_goal_sha256: str
    source_activation_event_sha256: str
    source_ready_event_sha256: str
    source_ready_occurred_at: datetime
    contract_binding: dict[str, Any]
    runtime_bindings: tuple[tuple[str, str], ...]


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_sha256(value: Any) -> str:
    return sha256_bytes(canonical_json_bytes(value))


def event_sha256(event: dict[str, Any]) -> str:
    return canonical_sha256(
        {key: value for key, value in event.items() if key != "event_sha256"}
    )


def expected_contract_binding() -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": CONTRACT_DOCUMENT_ID,
        "path": CONTRACT_RELATIVE.as_posix(),
        "file_sha256": CONTRACT_FILE_SHA256,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "canonical_contract_sha256": CONTRACT_CANONICAL_SHA256,
    }


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    content = (
        retained_content
        if retained_content is not None
        else repo_file(root, CONTRACT_RELATIVE).read_bytes()
    )
    if sha256_bytes(content) != CONTRACT_FILE_SHA256:
        raise GateError("FP008 initial-start contract file SHA-256 differs")
    try:
        contract = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP008 initial-start contract is not valid JSON") from exc
    if not isinstance(contract, dict):
        raise GateError("FP008 initial-start contract root must be an object")
    if canonical_sha256(contract) != CONTRACT_CANONICAL_SHA256:
        raise GateError("FP008 initial-start canonical contract SHA-256 differs")
    if set(contract) != {
        "schema_version",
        "document_id",
        "contract_id",
        "contract_version",
        "target_goal_id",
        "target_goal_content_sha256",
        "gate_purpose",
        "ordered_checks",
    }:
        raise GateError("FP008 initial-start contract field set differs")
    if (
        contract.get("schema_version") != "1.0"
        or contract.get("document_id") != CONTRACT_DOCUMENT_ID
        or contract.get("contract_id") != CONTRACT_ID
        or contract.get("contract_version") != CONTRACT_VERSION
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or contract.get("target_goal_content_sha256") != TARGET_GOAL_SHA256
        or contract.get("gate_purpose") != GATE_PURPOSE
    ):
        raise GateError("FP008 initial-start contract identity differs")
    ordered_checks = contract.get("ordered_checks")
    if not isinstance(ordered_checks, list):
        raise GateError("FP008 initial-start ordered checks are missing")
    checks: list[tuple[str, str]] = []
    for item in ordered_checks:
        if (
            not isinstance(item, dict)
            or set(item) != {"check_id", "command"}
            or not isinstance(item.get("check_id"), str)
            or not isinstance(item.get("command"), str)
            or not item["command"]
        ):
            raise GateError("FP008 initial-start ordered check differs")
        checks.append((item["check_id"], item["command"]))
    frozen_checks = tuple(checks)
    if tuple(check_id for check_id, _ in frozen_checks) != EXPECTED_CHECK_IDS:
        raise GateError("FP008 initial-start check order differs")
    if len(set(EXPECTED_CHECK_IDS)) != len(EXPECTED_CHECK_IDS):
        raise GateError("FP008 initial-start check IDs are not unique")
    for check_id, command in frozen_checks:
        lowered = command.lower()
        forbidden = [
            fragment
            for fragment in FORBIDDEN_COMMAND_FRAGMENTS
            if fragment in lowered
        ]
        if forbidden:
            raise GateError(
                f"{check_id} contains forbidden command scope: {forbidden[0]}"
            )
    return frozen_checks, contract


def _contains_symlink(root: Path, relative: Path) -> bool:
    current = root
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            return True
    return False


def repo_file(root: Path, relative: str | Path) -> Path:
    relative_path = Path(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        raise GateError(f"unsafe repository path: {relative_path}")
    root = root.resolve(strict=True)
    if _contains_symlink(root, relative_path):
        raise GateError(f"repository path contains a symlink: {relative_path}")
    candidate = (root / relative_path).resolve(strict=True)
    if not candidate.is_relative_to(root) or not candidate.is_file():
        raise GateError(f"repository file is missing or unsafe: {relative_path}")
    return candidate


def repo_directory(root: Path, relative: str | Path) -> Path:
    relative_path = Path(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        raise GateError(f"unsafe repository path: {relative_path}")
    root = root.resolve(strict=True)
    if _contains_symlink(root, relative_path):
        raise GateError(f"repository path contains a symlink: {relative_path}")
    candidate = (root / relative_path).resolve(strict=True)
    if not candidate.is_relative_to(root) or not candidate.is_dir():
        raise GateError(f"repository directory is missing or unsafe: {relative_path}")
    return candidate


def _private_file_bytes(
    path: Path,
    *,
    allow_empty: bool,
    maximum_bytes: int,
) -> bytes:
    before = path.lstat()
    if (
        not stat.S_ISREG(before.st_mode)
        or stat.S_IMODE(before.st_mode) != 0o600
        or before.st_uid != os.geteuid()
        or before.st_nlink != 1
        or before.st_size > maximum_bytes
    ):
        raise GateError(f"private add-only file metadata differs: {path}")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(
        os, "O_NOFOLLOW", 0
    )
    descriptor = os.open(path, flags)
    try:
        opened = os.fstat(descriptor)
        if _stable_file_metadata(opened) != _stable_file_metadata(before):
            raise GateError(f"private add-only file identity changed: {path}")
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(descriptor, min(1024 * 1024, maximum_bytes + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > maximum_bytes:
                raise GateError(f"private add-only file is too large: {path}")
    finally:
        os.close(descriptor)
    after = path.lstat()
    if _stable_file_metadata(after) != _stable_file_metadata(before):
        raise GateError(f"private add-only file changed while reading: {path}")
    content = b"".join(chunks)
    if not allow_empty and not content:
        raise GateError(f"private add-only file is empty: {path}")
    return content


RECEIPT_NAME = "implementation-start-gate-receipt.json"
RECEIPT_STAGE_NAME = ".implementation-start-gate-receipt.json.staging"


def _safe_entry_name(name: str) -> str:
    if not name or name in {".", ".."} or Path(name).name != name:
        raise GateError(f"unsafe gate entry name: {name!r}")
    return name


def _stable_file_metadata(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _stable_directory_metadata(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _directory_object_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
    )


def _published_file_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
    )


def _retained_tree_directory_metadata(
    relative: Path,
    metadata: os.stat_result,
) -> tuple[int, ...]:
    if relative == Path("."):
        return _event_directory_identity(metadata)
    return _stable_directory_metadata(metadata)


def _pread_all(descriptor: int, size: int) -> bytes:
    chunks: list[bytes] = []
    offset = 0
    while offset < size:
        chunk = os.pread(descriptor, min(1024 * 1024, size - offset), offset)
        if not chunk:
            raise GateError("retained private file became short")
        chunks.append(chunk)
        offset += len(chunk)
    return b"".join(chunks)


@dataclass
class _RetainedSourceFile:
    relative: Path
    descriptor: int
    metadata: tuple[int, ...]
    content: bytes


class _RetainedDirectoryTree:
    def __init__(
        self,
        root: Path,
        descriptors: dict[Path, int],
        metadata: dict[Path, tuple[int, ...]],
    ) -> None:
        self.root = root
        self.descriptors = descriptors
        self.metadata = metadata

    @classmethod
    def capture(
        cls,
        root: Path,
        relatives: Sequence[Path],
    ) -> "_RetainedDirectoryTree":
        directories = {Path(".")}
        for relative in relatives:
            if relative.is_absolute() or ".." in relative.parts:
                raise GateError(f"retained source path is unsafe: {relative}")
            current = Path(".")
            for part in relative.parts[:-1]:
                current /= part
                directories.add(current)
        flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        descriptors: dict[Path, int] = {}
        metadata_by_path: dict[Path, tuple[int, ...]] = {}
        try:
            for relative in sorted(
                directories,
                key=lambda value: (len(value.parts), value.as_posix()),
            ):
                descriptor = (
                    os.open(root, flags)
                    if relative == Path(".")
                    else os.open(
                        relative.name,
                        flags,
                        dir_fd=descriptors[relative.parent],
                    )
                )
                descriptors[relative] = descriptor
                opened = os.fstat(descriptor)
                if not stat.S_ISDIR(opened.st_mode) or opened.st_uid != os.geteuid():
                    raise GateError(
                        f"retained source ancestor is unsafe: {relative}"
                    )
                metadata_by_path[relative] = _retained_tree_directory_metadata(
                    relative,
                    opened,
                )
            tree = cls(root, descriptors, metadata_by_path)
            tree.verify()
            return tree
        except BaseException as primary:
            for descriptor in reversed(tuple(descriptors.values())):
                try:
                    os.close(descriptor)
                except BaseException:
                    pass
            raise

    def verify(self) -> None:
        root_relative = Path(".")
        root_named = self.root.lstat()
        expected_root = self.metadata[root_relative]
        if (
            not stat.S_ISDIR(root_named.st_mode)
            or self.root.is_symlink()
            or _retained_tree_directory_metadata(root_relative, root_named)
            != expected_root
            or _retained_tree_directory_metadata(
                root_relative,
                os.fstat(self.descriptors[root_relative]),
            )
            != expected_root
        ):
            raise GateError("retained source ancestor identity changed: .")
        for relative in sorted(
            (value for value in self.descriptors if value != root_relative),
            key=lambda value: (len(value.parts), value.as_posix()),
        ):
            named = os.stat(
                relative.name,
                dir_fd=self.descriptors[relative.parent],
                follow_symlinks=False,
            )
            opened = os.fstat(self.descriptors[relative])
            expected = self.metadata[relative]
            if (
                not stat.S_ISDIR(named.st_mode)
                or _stable_directory_metadata(named) != expected
                or _stable_directory_metadata(opened) != expected
            ):
                raise GateError(
                    f"retained source ancestor identity changed: {relative}"
                )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        for relative in sorted(
            self.descriptors,
            key=lambda value: (len(value.parts), value.as_posix()),
            reverse=True,
        ):
            descriptor = self.descriptors.pop(relative)
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


class _RetainedCheckpointParentLock:
    def __init__(self, tree: _RetainedDirectoryTree) -> None:
        self.tree = tree
        self.parent_relative = CHECKPOINT_RELATIVE.parent
        self.descriptor = tree.descriptors[self.parent_relative]
        self.identity = tree.metadata[self.parent_relative]
        self.locked = True

    @classmethod
    def capture(cls, root: Path) -> "_RetainedCheckpointParentLock":
        tree = _RetainedDirectoryTree.capture(root, [CHECKPOINT_RELATIVE])
        descriptor = tree.descriptors[CHECKPOINT_RELATIVE.parent]
        try:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise GateError("checkpoint parent lock unavailable") from exc
            retained = cls(tree)
            retained.verify()
            return retained
        except BaseException as exc:
            try:
                tree.close(exc)
            finally:
                raise

    def verify(self) -> None:
        self.tree.verify()
        opened = os.fstat(self.descriptor)
        if (
            not self.locked
            or _stable_directory_metadata(opened) != self.identity
            or self.tree.descriptors.get(self.parent_relative) != self.descriptor
        ):
            raise GateError("checkpoint parent shared-lock authority changed")
        self.tree.verify()

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        if self.locked:
            try:
                self.verify()
            except BaseException as exc:
                first = exc
            try:
                fcntl.flock(self.descriptor, fcntl.LOCK_UN)
            except BaseException as exc:
                if first is None:
                    first = exc
            self.locked = False
        try:
            self.tree.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


class _RetainedSourceGuard:
    def __init__(
        self,
        root: Path,
        pins: list[_RetainedSourceFile],
        directory_tree: _RetainedDirectoryTree,
        checkpoint_lock: _RetainedCheckpointParentLock | None,
    ) -> None:
        self.root = root
        self.pins = pins
        self.directory_tree = directory_tree
        self.checkpoint_lock = checkpoint_lock

    @classmethod
    def capture(
        cls,
        root: Path,
        relatives: Sequence[Path],
        *,
        checkpoint_lock: _RetainedCheckpointParentLock | None = None,
        maximum_bytes: int = CHECKPOINT_MAX_BYTES,
        total_maximum_bytes: int | None = None,
    ) -> "_RetainedSourceGuard":
        if type(maximum_bytes) is not int or maximum_bytes <= 0:
            raise GateError("retained source byte limit is invalid")
        if (
            total_maximum_bytes is not None
            and (
                type(total_maximum_bytes) is not int
                or total_maximum_bytes <= 0
            )
        ):
            raise GateError("retained source total byte limit is invalid")
        pins: list[_RetainedSourceFile] = []
        retained_bytes = 0
        directory_tree = _RetainedDirectoryTree.capture(root, relatives)
        try:
            if checkpoint_lock is not None:
                checkpoint_lock.verify()
                if (
                    directory_tree.metadata.get(CHECKPOINT_RELATIVE.parent)
                    != checkpoint_lock.identity
                ):
                    raise GateError(
                        "source guard checkpoint parent differs from shared lock"
                    )
            for relative in relatives:
                parent_fd = (
                    checkpoint_lock.descriptor
                    if checkpoint_lock is not None
                    and relative.parent == CHECKPOINT_RELATIVE.parent
                    else directory_tree.descriptors[relative.parent]
                )
                descriptor: int | None = None
                try:
                    descriptor = os.open(
                        relative.name,
                        os.O_RDONLY
                        | getattr(os, "O_CLOEXEC", 0)
                        | getattr(os, "O_NOFOLLOW", 0),
                        dir_fd=parent_fd,
                    )
                    metadata = os.fstat(descriptor)
                    stable = _stable_file_metadata(metadata)
                    current_metadata = os.stat(
                        relative.name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    checkpoint_metadata_is_private = (
                        relative != CHECKPOINT_RELATIVE
                        or (
                            stat.S_IMODE(metadata.st_mode) == 0o600
                            and metadata.st_uid == os.geteuid()
                            and metadata.st_gid == os.getegid()
                            and metadata.st_nlink == 1
                        )
                    )
                    total_exceeded = (
                        total_maximum_bytes is not None
                        and metadata.st_size
                        > total_maximum_bytes - retained_bytes
                    )
                    if (
                        not stat.S_ISREG(metadata.st_mode)
                        or metadata.st_uid != os.geteuid()
                        or metadata.st_size > maximum_bytes
                        or total_exceeded
                        or not checkpoint_metadata_is_private
                        or _stable_file_metadata(current_metadata) != stable
                    ):
                        raise GateError(
                            f"retained source authority differs: {relative}"
                        )
                    content = _pread_all(descriptor, metadata.st_size)
                    pins.append(
                        _RetainedSourceFile(
                            relative=relative,
                            descriptor=descriptor,
                            metadata=stable,
                            content=content,
                        )
                    )
                    descriptor = None
                    retained_bytes += metadata.st_size
                except BaseException:
                    if descriptor is not None:
                        try:
                            os.close(descriptor)
                        except BaseException:
                            pass
                    raise
            guard = cls(root, pins, directory_tree, checkpoint_lock)
            guard.verify()
            return guard
        except BaseException as primary:
            for pin in pins:
                try:
                    os.close(pin.descriptor)
                except BaseException:
                    pass
            directory_tree.close(primary)
            raise

    def verify(self) -> None:
        if self.checkpoint_lock is not None:
            self.checkpoint_lock.verify()
        self.directory_tree.verify()
        for pin in self.pins:
            parent_fd = (
                self.checkpoint_lock.descriptor
                if self.checkpoint_lock is not None
                and pin.relative.parent == CHECKPOINT_RELATIVE.parent
                else self.directory_tree.descriptors[pin.relative.parent]
            )
            opened = os.fstat(pin.descriptor)
            named = os.stat(
                pin.relative.name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if (
                _stable_file_metadata(opened) != pin.metadata
                or _stable_file_metadata(named) != pin.metadata
                or _pread_all(pin.descriptor, opened.st_size) != pin.content
            ):
                raise GateError(f"retained source changed: {pin.relative}")
        self.directory_tree.verify()
        if self.checkpoint_lock is not None:
            self.checkpoint_lock.verify()

    def content(self, relative: Path) -> bytes:
        for pin in self.pins:
            if pin.relative == relative:
                return pin.content
        raise GateError(f"retained source path is not pinned: {relative}")

    @property
    def contents(self) -> dict[Path, bytes]:
        return {pin.relative: pin.content for pin in self.pins}

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        pins, self.pins = self.pins, []
        for pin in pins:
            try:
                os.close(pin.descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        try:
            self.directory_tree.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


class _RetainedEventEvidenceGuard:
    def __init__(
        self,
        event_dir: Path,
        event_dir_fd: int,
        event_identity: tuple[int, ...],
        expected_names: frozenset[str],
        files: dict[str, tuple[int, tuple[int, ...], bytes]],
        directory_tree: _RetainedDirectoryTree,
    ) -> None:
        self.event_dir = event_dir
        self.event_dir_fd = event_dir_fd
        self.event_identity = event_identity
        self.expected_names = expected_names
        self.files = files
        self.directory_tree = directory_tree

    @classmethod
    def capture(
        cls,
        root: Path,
        event_relative: Path,
        expected_names: set[str],
    ) -> "_RetainedEventEvidenceGuard":
        directory_tree = _RetainedDirectoryTree.capture(
            root,
            [event_relative / name for name in sorted(expected_names)],
        )
        event_dir = root / event_relative
        event_dir_fd = directory_tree.descriptors[event_relative]
        opened_event = os.fstat(event_dir_fd)
        named_event = os.stat(
            event_relative.name,
            dir_fd=directory_tree.descriptors[event_relative.parent],
            follow_symlinks=False,
        )
        event_identity = _event_directory_identity(opened_event)
        files: dict[str, tuple[int, tuple[int, ...], bytes]] = {}
        try:
            if (
                not stat.S_ISDIR(opened_event.st_mode)
                or stat.S_IMODE(opened_event.st_mode) != 0o700
                or opened_event.st_uid != os.geteuid()
                or _event_directory_identity(named_event) != event_identity
            ):
                raise GateError("retained gate evidence directory differs")
            if frozenset(os.listdir(event_dir_fd)) != frozenset(expected_names):
                raise GateError("retained gate evidence inventory differs")
            for name in sorted(expected_names):
                descriptor = os.open(
                    _safe_entry_name(name),
                    os.O_RDONLY
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                    dir_fd=event_dir_fd,
                )
                try:
                    metadata = os.fstat(descriptor)
                    stable = _stable_file_metadata(metadata)
                    named = os.stat(
                        name,
                        dir_fd=event_dir_fd,
                        follow_symlinks=False,
                    )
                    if (
                        not stat.S_ISREG(metadata.st_mode)
                        or stat.S_IMODE(metadata.st_mode) != 0o600
                        or metadata.st_uid != os.geteuid()
                        or metadata.st_nlink != 1
                        or metadata.st_size > LOG_MAX_BYTES
                        or _stable_file_metadata(named) != stable
                    ):
                        raise GateError(
                            f"retained gate evidence authority differs: {name}"
                        )
                    files[name] = (
                        descriptor,
                        stable,
                        _pread_all(descriptor, metadata.st_size),
                    )
                except BaseException:
                    os.close(descriptor)
                    raise
            guard = cls(
                event_dir,
                event_dir_fd,
                event_identity,
                frozenset(expected_names),
                files,
                directory_tree,
            )
            guard.verify()
            return guard
        except BaseException as exc:
            for descriptor, _, _ in files.values():
                os.close(descriptor)
            directory_tree.close()
            raise exc

    def content(self, name: str) -> bytes:
        return self.files[_safe_entry_name(name)][2]

    def verify(self) -> None:
        self.directory_tree.verify()
        opened_event = os.fstat(self.event_dir_fd)
        named_event = os.stat(
            self.event_dir.name,
            dir_fd=self.directory_tree.descriptors[
                self.event_dir.relative_to(self.directory_tree.root).parent
            ],
            follow_symlinks=False,
        )
        if (
            not stat.S_ISDIR(opened_event.st_mode)
            or stat.S_IMODE(opened_event.st_mode) != 0o700
            or opened_event.st_uid != os.geteuid()
            or _event_directory_identity(opened_event) != self.event_identity
            or _event_directory_identity(named_event) != self.event_identity
        ):
            raise GateError("retained gate event directory identity changed")
        if frozenset(os.listdir(self.event_dir_fd)) != self.expected_names:
            raise GateError("retained gate evidence inventory changed")
        for name, (descriptor, expected_metadata, expected_content) in (
            self.files.items()
        ):
            opened = os.fstat(descriptor)
            named = os.stat(
                name,
                dir_fd=self.event_dir_fd,
                follow_symlinks=False,
            )
            if (
                _stable_file_metadata(opened) != expected_metadata
                or _stable_file_metadata(named) != expected_metadata
                or _pread_all(descriptor, opened.st_size) != expected_content
            ):
                raise GateError(f"retained gate evidence changed: {name}")
        self.directory_tree.verify()

    def fsync_event_and_parent(self) -> None:
        event_relative = self.event_dir.relative_to(self.directory_tree.root)
        self.verify()
        os.fsync(self.event_dir_fd)
        os.fsync(self.directory_tree.descriptors[event_relative.parent])
        self.verify()

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        for descriptor, _, _ in self.files.values():
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        self.files.clear()
        try:
            self.directory_tree.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


class _RetainedFreshEventDirectory:
    def __init__(
        self,
        root: Path,
        event_id: str,
        tree: _RetainedDirectoryTree,
        event_fd: int,
        event_identity: tuple[int, ...],
    ) -> None:
        self.root = root
        self.event_id = event_id
        self.tree = tree
        self.event_fd = event_fd
        self.event_identity = event_identity
        self.event_dir = root / GATE_ROOT_RELATIVE / event_id

    @property
    def gate_root_fd(self) -> int:
        return self.tree.descriptors[GATE_ROOT_RELATIVE]

    @classmethod
    def create(
        cls,
        root: Path,
        event_id: str,
    ) -> "_RetainedFreshEventDirectory":
        _safe_entry_name(event_id)
        tree = _RetainedDirectoryTree.capture(
            root,
            [GATE_ROOT_RELATIVE / ".event-boundary"],
        )
        event_fd: int | None = None
        try:
            gate_root_fd = tree.descriptors[GATE_ROOT_RELATIVE]
            gate_root_before = tree.metadata[GATE_ROOT_RELATIVE]
            inventory_before = frozenset(os.listdir(gate_root_fd))
            try:
                os.stat(event_id, dir_fd=gate_root_fd, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise GateError(
                    f"gate event directory already exists: "
                    f"{root / GATE_ROOT_RELATIVE / event_id}"
                )
            os.mkdir(event_id, mode=0o700, dir_fd=gate_root_fd)
            created = os.stat(
                event_id,
                dir_fd=gate_root_fd,
                follow_symlinks=False,
            )
            if not stat.S_ISDIR(created.st_mode) or created.st_uid != os.geteuid():
                raise GateError("owned gate event directory authority differs")
            os.chmod(
                event_id,
                0o700,
                dir_fd=gate_root_fd,
                follow_symlinks=False,
            )
            gate_root_after = _stable_directory_metadata(os.fstat(gate_root_fd))
            gate_root_named_after = _stable_directory_metadata(
                os.stat(
                    GATE_ROOT_RELATIVE.name,
                    dir_fd=tree.descriptors[GATE_ROOT_RELATIVE.parent],
                    follow_symlinks=False,
                )
            )
            expected_gate_root_after = gate_root_after
            if (
                gate_root_after[:5] != gate_root_before[:5]
                or gate_root_after[5] != gate_root_before[5] + 1
                or gate_root_named_after != gate_root_after
                or frozenset(os.listdir(gate_root_fd))
                != inventory_before | {event_id}
            ):
                raise GateError(
                    "gate root changed outside the owned event-directory addition"
                )
            tree.metadata[GATE_ROOT_RELATIVE] = expected_gate_root_after
            tree.verify()
            event_fd = os.open(
                event_id,
                os.O_RDONLY
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=gate_root_fd,
            )
            os.fchmod(event_fd, 0o700)
            opened = os.fstat(event_fd)
            identity = _event_directory_identity(opened)
            retained = cls(root, event_id, tree, event_fd, identity)
            retained.verify()
            os.fsync(gate_root_fd)
            retained.verify()
            return retained
        except BaseException as exc:
            if event_fd is not None:
                try:
                    os.close(event_fd)
                except BaseException:
                    pass
            tree.close(exc)
            raise

    def verify(self) -> None:
        self.tree.verify()
        opened = os.fstat(self.event_fd)
        named = os.stat(
            self.event_id,
            dir_fd=self.gate_root_fd,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISDIR(opened.st_mode)
            or stat.S_IMODE(opened.st_mode) != 0o700
            or opened.st_uid != os.geteuid()
            or _event_directory_identity(opened) != self.event_identity
            or _event_directory_identity(named) != self.event_identity
        ):
            raise GateError("retained fresh gate event directory changed")
        self.tree.verify()

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        try:
            self.verify()
        except BaseException as exc:
            first = exc
        try:
            os.close(self.event_fd)
        except BaseException as exc:
            if first is None:
                first = exc
        try:
            self.tree.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


@dataclass
class _RetainedGateLog:
    name: str
    stream: BinaryIO
    metadata: tuple[int, ...]
    content: bytes

    @classmethod
    def capture(
        cls,
        event: _RetainedFreshEventDirectory,
        name: str,
        stream: BinaryIO,
        *,
        allow_empty: bool,
    ) -> "_RetainedGateLog":
        descriptor = stream.fileno()
        opened = os.fstat(descriptor)
        named = os.stat(
            _safe_entry_name(name),
            dir_fd=event.event_fd,
            follow_symlinks=False,
        )
        metadata = _stable_file_metadata(opened)
        if opened.st_size > LOG_MAX_BYTES:
            raise GateError(f"retained gate log is too large: {name}")
        content = _pread_all(descriptor, opened.st_size)
        if not allow_empty and not content:
            raise GateError(f"retained gate log is empty: {name}")
        if (
            not stat.S_ISREG(opened.st_mode)
            or stat.S_IMODE(opened.st_mode) != 0o600
            or opened.st_uid != os.geteuid()
            or opened.st_nlink != 1
            or _stable_file_metadata(named) != metadata
        ):
            raise GateError(f"retained gate log authority differs: {name}")
        retained = cls(name, stream, metadata, content)
        retained.verify(event)
        return retained

    def verify(self, event: _RetainedFreshEventDirectory) -> None:
        event.verify()
        descriptor = self.stream.fileno()
        opened = os.fstat(descriptor)
        named = os.stat(
            self.name,
            dir_fd=event.event_fd,
            follow_symlinks=False,
        )
        if (
            _stable_file_metadata(opened) != self.metadata
            or _stable_file_metadata(named) != self.metadata
            or _pread_all(descriptor, opened.st_size) != self.content
        ):
            raise GateError(f"retained gate log changed: {self.name}")
        event.verify()

    def close(self, primary: BaseException | None = None) -> None:
        try:
            self.stream.close()
        except BaseException:
            if primary is None:
                raise


_REPOSITORY_AUTHORITY_ENVIRONMENT_LOCK = threading.RLock()


@dataclass
class _RetainedImmutableNamespaceEntry:
    path: Path
    parent_descriptor: int
    parent_metadata: tuple[int, ...]
    descriptor: int | None
    metadata: tuple[int, ...] | None
    content: bytes | None
    directory: bool
    seal_directory_inventory: bool

    @classmethod
    def capture(
        cls,
        path: Path,
        *,
        allow_missing: bool = False,
        seal_directory_inventory: bool = True,
    ) -> "_RetainedImmutableNamespaceEntry":
        path = path.absolute()
        if path == Path(path.anchor):
            raise GateError("repository namespace cannot pin the filesystem root")
        parent_flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        parent_descriptor = os.open(path.parent, parent_flags)
        descriptor: int | None = None
        try:
            parent_metadata = _directory_object_identity(
                os.fstat(parent_descriptor)
            )
            try:
                named = os.stat(
                    path.name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                )
            except FileNotFoundError:
                if not allow_missing:
                    raise GateError(
                        f"repository namespace path is missing: {path}"
                    )
                retained = cls(
                    path,
                    parent_descriptor,
                    parent_metadata,
                    None,
                    None,
                    None,
                    False,
                    seal_directory_inventory,
                )
                retained.verify()
                return retained
            if stat.S_ISDIR(named.st_mode):
                flags = parent_flags
                directory = True
            elif stat.S_ISREG(named.st_mode):
                flags = (
                    os.O_RDONLY
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0)
                )
                directory = False
            else:
                raise GateError(
                    f"repository namespace path has an unsafe type: {path}"
                )
            descriptor = os.open(path.name, flags, dir_fd=parent_descriptor)
            opened = os.fstat(descriptor)
            metadata = (
                (
                    _stable_directory_metadata(opened)
                    if seal_directory_inventory
                    else _directory_object_identity(opened)
                )
                if directory
                else _stable_file_metadata(opened)
            )
            named_metadata = (
                (
                    _stable_directory_metadata(named)
                    if seal_directory_inventory
                    else _directory_object_identity(named)
                )
                if directory
                else _stable_file_metadata(named)
            )
            if metadata != named_metadata:
                raise GateError(
                    f"repository namespace path changed during capture: {path}"
                )
            content = None if directory else _pread_all(
                descriptor,
                opened.st_size,
            )
            retained = cls(
                path,
                parent_descriptor,
                parent_metadata,
                descriptor,
                metadata,
                content,
                directory,
                seal_directory_inventory,
            )
            retained.verify()
            return retained
        except BaseException:
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except BaseException:
                    pass
            try:
                os.close(parent_descriptor)
            except BaseException:
                pass
            raise

    def verify(self) -> None:
        parent_opened = os.fstat(self.parent_descriptor)
        parent_named = self.path.parent.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(parent_opened.st_mode)
            or _directory_object_identity(parent_opened)
            != self.parent_metadata
            or _directory_object_identity(parent_named)
            != self.parent_metadata
        ):
            raise GateError(
                f"repository namespace parent changed: {self.path.parent}"
            )
        try:
            named = os.stat(
                self.path.name,
                dir_fd=self.parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            if self.descriptor is None and self.metadata is None:
                return
            raise GateError(
                f"repository namespace path disappeared: {self.path}"
            )
        if self.descriptor is None or self.metadata is None:
            raise GateError(
                f"repository namespace missing path appeared: {self.path}"
            )
        opened = os.fstat(self.descriptor)
        current_opened = (
            (
                _stable_directory_metadata(opened)
                if self.seal_directory_inventory
                else _directory_object_identity(opened)
            )
            if self.directory
            else _stable_file_metadata(opened)
        )
        current_named = (
            (
                _stable_directory_metadata(named)
                if self.seal_directory_inventory
                else _directory_object_identity(named)
            )
            if self.directory
            else _stable_file_metadata(named)
        )
        if current_opened != self.metadata or current_named != self.metadata:
            raise GateError(
                f"repository namespace path identity changed: {self.path}"
            )
        if not self.directory and _pread_all(
            self.descriptor,
            opened.st_size,
        ) != self.content:
            raise GateError(
                f"repository namespace path bytes changed: {self.path}"
            )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        for descriptor in (self.descriptor, self.parent_descriptor):
            if descriptor is None:
                continue
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        self.descriptor = None
        self.parent_descriptor = -1
        if primary is None and first is not None:
            raise first


def _namespace_recursive_paths(root: Path) -> tuple[Path, ...]:
    if not root.exists():
        return ()
    pending = [root]
    paths: list[Path] = []
    while pending:
        current = pending.pop()
        paths.append(current)
        if not current.is_dir() or current.is_symlink():
            continue
        children = sorted(current.iterdir(), key=lambda value: value.name)
        for child in reversed(children):
            metadata = child.lstat()
            if not (stat.S_ISDIR(metadata.st_mode) or stat.S_ISREG(metadata.st_mode)):
                raise GateError(
                    f"repository namespace contains an unsafe entry: {child}"
                )
            pending.append(child)
    return tuple(paths)


class RetainedRepositoryAuthorityGuard:
    """Retain the immutable live Git execution namespace across a gate."""

    def __init__(
        self,
        root: Path,
        entries: list[_RetainedImmutableNamespaceEntry],
        workspace: Path,
        workspace_descriptor: int,
        workspace_identity: tuple[int, ...],
        git_command: Path,
        discovery: tuple[Path, Path, Path, Path],
        git_repository: bool = True,
    ) -> None:
        self.root = root
        self.entries = entries
        self.workspace = workspace
        self.workspace_descriptor = workspace_descriptor
        self.workspace_identity = workspace_identity
        self.git_command = git_command
        self.discovery = discovery
        self.git_repository = git_repository

    @staticmethod
    def _command_environment(workspace: Path) -> dict[str, str]:
        return {
            "PATH": os.fspath(workspace / "commands"),
            "HOME": os.fspath(workspace / "home"),
            "LANG": "C.UTF-8",
            "LC_ALL": "C",
            "GIT_TERMINAL_PROMPT": "0",
        }

    @staticmethod
    def _run_git(
        root: Path,
        git_command: Path,
        workspace: Path,
        arguments: Sequence[str],
    ) -> bytes:
        completed = subprocess.run(
            [os.fspath(git_command), *arguments],
            cwd=root,
            env=RetainedRepositoryAuthorityGuard._command_environment(
                workspace
            ),
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if completed.returncode != 0:
            raise GateError(
                "repository authority Git discovery failed safely with "
                f"exit code {completed.returncode}"
            )
        return completed.stdout

    @classmethod
    def capture(cls, root: Path) -> "RetainedRepositoryAuthorityGuard":
        root = root.resolve(strict=True)
        workspace = Path(
            tempfile.mkdtemp(prefix="walksafe-fp008-authority-", dir="/tmp")
        )
        os.chmod(workspace, 0o700)
        workspace_descriptor: int | None = None
        entries: list[_RetainedImmutableNamespaceEntry] = []
        try:
            resolved_workspace = workspace.resolve(strict=True)
            try:
                resolved_workspace.relative_to(root)
            except ValueError:
                pass
            else:
                raise GateError(
                    "repository authority workspace is inside the live root"
                )
            try:
                root.relative_to(resolved_workspace)
            except ValueError:
                pass
            else:
                raise GateError(
                    "live root is inside the repository authority workspace"
                )
            command_directory = workspace / "commands"
            home_directory = workspace / "home"
            snapshots_directory = workspace / "snapshots"
            runtime_directory = workspace / "runtime"
            runtime_tmp_directory = runtime_directory / "tmp"
            command_directory.mkdir(mode=0o700)
            home_directory.mkdir(mode=0o700)
            snapshots_directory.mkdir(mode=0o700)
            runtime_directory.mkdir(mode=0o700)
            os.chmod(runtime_directory, 0o700)
            runtime_tmp_directory.mkdir(mode=0o700)
            for owned_directory in (
                command_directory,
                home_directory,
                snapshots_directory,
                runtime_directory,
                runtime_tmp_directory,
            ):
                os.chmod(owned_directory, 0o700)
            host_git_text = shutil.which("git")
            if host_git_text is None:
                raise GateError("Git executable is unavailable")
            host_git = Path(host_git_text).resolve(strict=True)
            host_git_entry = _RetainedImmutableNamespaceEntry.capture(host_git)
            entries.append(host_git_entry)
            git_command = command_directory / "git"
            with git_command.open("xb") as destination:
                assert host_git_entry.descriptor is not None
                offset = 0
                source_size = os.fstat(host_git_entry.descriptor).st_size
                while offset < source_size:
                    chunk = os.pread(
                        host_git_entry.descriptor,
                        min(1024 * 1024, source_size - offset),
                        offset,
                    )
                    if not chunk:
                        raise GateError("retained Git executable became short")
                    destination.write(chunk)
                    offset += len(chunk)
                destination.flush()
                os.fsync(destination.fileno())
            os.chmod(git_command, 0o500)
            for helper_name in ("git-upload-pack", "git-receive-pack"):
                os.link(git_command, command_directory / helper_name)
            entries.extend(
                _RetainedImmutableNamespaceEntry.capture(path)
                for path in (
                    command_directory,
                    git_command,
                    command_directory / "git-upload-pack",
                    command_directory / "git-receive-pack",
                    home_directory,
                )
            )
            root_parent_entry = _RetainedImmutableNamespaceEntry.capture(
                root.parent,
                seal_directory_inventory=False,
            )
            entries.append(root_parent_entry)
            entries.append(_RetainedImmutableNamespaceEntry.capture(root))
            git_entry = _RetainedImmutableNamespaceEntry.capture(
                root / ".git",
                allow_missing=True,
            )
            entries.append(git_entry)
            if git_entry.descriptor is None:
                workspace_descriptor = os.open(
                    workspace,
                    os.O_RDONLY
                    | getattr(os, "O_DIRECTORY", 0)
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                )
                workspace_metadata = os.fstat(workspace_descriptor)
                retained = cls(
                    root,
                    entries,
                    workspace,
                    workspace_descriptor,
                    _event_directory_identity(workspace_metadata),
                    git_command,
                    (Path(), Path(), Path(), Path()),
                    False,
                )
                retained.verify()
                return retained
            git_dir = Path(
                os.fsdecode(
                    cls._run_git(
                        root,
                        git_command,
                        workspace,
                        ("rev-parse", "--path-format=absolute", "--git-dir"),
                    ).strip()
                )
            ).resolve(strict=True)
            common_dir = Path(
                os.fsdecode(
                    cls._run_git(
                        root,
                        git_command,
                        workspace,
                        (
                            "rev-parse",
                            "--path-format=absolute",
                            "--git-common-dir",
                        ),
                    ).strip()
                )
            ).resolve(strict=True)
            index_path = Path(
                os.fsdecode(
                    cls._run_git(
                        root,
                        git_command,
                        workspace,
                        (
                            "rev-parse",
                            "--path-format=absolute",
                            "--git-path",
                            "index",
                        ),
                    ).strip()
                )
            ).resolve(strict=True)
            exclude_path_text = os.fsdecode(
                cls._run_git(
                    root,
                    git_command,
                    workspace,
                    (
                        "rev-parse",
                        "--path-format=absolute",
                        "--git-path",
                        "info/exclude",
                    ),
                ).strip()
            )
            exclude_path = Path(exclude_path_text).absolute()
            paths: set[Path] = {
                git_dir.parent,
                git_dir,
                common_dir.parent,
                common_dir,
                index_path,
                exclude_path.parent,
                exclude_path,
                Path("/etc/gitconfig"),
            }
            paths.update(_namespace_recursive_paths(Path("/etc/gitconfig.d")))
            for directory in {git_dir, common_dir}:
                for name in (
                    "HEAD",
                    "commondir",
                    "gitdir",
                    "config",
                    "config.worktree",
                    "packed-refs",
                ):
                    paths.add(directory / name)
                paths.update(_namespace_recursive_paths(directory / "refs"))
            for path in sorted(paths, key=lambda value: (len(value.parts), os.fspath(value))):
                if path in {root.parent, root, root / ".git", host_git}:
                    continue
                entries.append(
                    _RetainedImmutableNamespaceEntry.capture(
                        path,
                        allow_missing=path.name
                        in {
                            "commondir",
                            "gitdir",
                            "config",
                            "config.worktree",
                            "packed-refs",
                            "refs",
                            "exclude",
                            "gitconfig",
                        },
                    )
                )
            workspace_descriptor = os.open(
                workspace,
                os.O_RDONLY
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
            )
            workspace_metadata = os.fstat(workspace_descriptor)
            if (
                stat.S_IMODE(workspace_metadata.st_mode) != 0o700
                or workspace_metadata.st_uid != os.geteuid()
                or workspace_metadata.st_gid != os.getegid()
            ):
                raise GateError("repository authority workspace is unsafe")
            retained = cls(
                root,
                entries,
                workspace,
                workspace_descriptor,
                _event_directory_identity(workspace_metadata),
                git_command,
                (git_dir, common_dir, index_path, exclude_path),
            )
            retained.verify()
            return retained
        except BaseException as primary:
            for entry in reversed(entries):
                try:
                    entry.close(primary)
                except BaseException:
                    pass
            if workspace_descriptor is not None:
                try:
                    os.close(workspace_descriptor)
                except BaseException:
                    pass
            try:
                if workspace.is_dir() and not workspace.is_symlink():
                    shutil.rmtree(workspace)
            except BaseException:
                pass
            raise

    def _verify_workspace(self) -> None:
        opened = os.fstat(self.workspace_descriptor)
        named = self.workspace.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(opened.st_mode)
            or stat.S_IMODE(opened.st_mode) != 0o700
            or _event_directory_identity(opened) != self.workspace_identity
            or _event_directory_identity(named) != self.workspace_identity
            or opened.st_gid != os.getegid()
            or self.workspace.is_symlink()
        ):
            raise GateError("repository authority workspace identity changed")

    def verify(self) -> None:
        self._verify_workspace()
        for entry in self.entries:
            entry.verify()
        if not self.git_repository:
            return
        discovered = (
            Path(
                os.fsdecode(
                    self._run_git(
                        self.root,
                        self.git_command,
                        self.workspace,
                        ("rev-parse", "--path-format=absolute", "--git-dir"),
                    ).strip()
                )
            ).resolve(strict=True),
            Path(
                os.fsdecode(
                    self._run_git(
                        self.root,
                        self.git_command,
                        self.workspace,
                        (
                            "rev-parse",
                            "--path-format=absolute",
                            "--git-common-dir",
                        ),
                    ).strip()
                )
            ).resolve(strict=True),
            Path(
                os.fsdecode(
                    self._run_git(
                        self.root,
                        self.git_command,
                        self.workspace,
                        (
                            "rev-parse",
                            "--path-format=absolute",
                            "--git-path",
                            "index",
                        ),
                    ).strip()
                )
            ).resolve(strict=True),
            Path(
                os.fsdecode(
                    self._run_git(
                        self.root,
                        self.git_command,
                        self.workspace,
                        (
                            "rev-parse",
                            "--path-format=absolute",
                            "--git-path",
                            "info/exclude",
                        ),
                    ).strip()
                )
            ).absolute(),
        )
        if discovered != self.discovery:
            raise GateError("repository authority Git namespace changed")
        for entry in reversed(self.entries):
            entry.verify()
        self._verify_workspace()

    @contextmanager
    def command_environment(self):
        with _REPOSITORY_AUTHORITY_ENVIRONMENT_LOCK:
            replacements = self._command_environment(self.workspace)
            previous = {name: os.environ.get(name) for name in replacements}
            os.environ.update(replacements)
            try:
                self.verify()
                yield
                self.verify()
            finally:
                for name, value in previous.items():
                    if value is None:
                        os.environ.pop(name, None)
                    else:
                        os.environ[name] = value

    def capture_state(
        self,
        checkpoint_path: Path,
        event_id: str,
        *,
        capture: Callable[[Path, Path, str], dict[str, Any]],
    ) -> dict[str, Any]:
        if not self.git_repository:
            payload = capture(self.root, checkpoint_path, event_id)
            try:
                self.verify()
            except (GateError, OSError, ValueError) as exc:
                raise _RepositoryAuthorityChanged(
                    "retained repository authority changed during test capture"
                ) from exc
            if not isinstance(payload, dict):
                raise GateError("repository authority capture is not an object")
            return payload
        self.verify()
        with self.command_environment():
            payload = capture(self.root, checkpoint_path, event_id)
        self.verify()
        if not isinstance(payload, dict):
            raise GateError("repository authority capture is not an object")
        return payload

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        if primary is None:
            try:
                self.verify()
            except BaseException as exc:
                first = exc
        for entry in reversed(self.entries):
            try:
                entry.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
        self.entries.clear()
        try:
            os.close(self.workspace_descriptor)
        except BaseException as exc:
            if first is None:
                first = exc
        try:
            metadata = self.workspace.lstat()
            if (
                not stat.S_ISDIR(metadata.st_mode)
                or self.workspace.is_symlink()
                or _event_directory_identity(metadata) != self.workspace_identity
            ):
                raise GateError(
                    "repository authority workspace cannot be cleaned safely"
                )
            shutil.rmtree(self.workspace)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


@dataclass(frozen=True)
class _RepositoryStateAuthority:
    payload: dict[str, Any]
    canonical_payload: bytes
    cli_output: bytes
    strict_cli_output: bool
    authorized_controlled_paths: tuple[Path, ...] = ()
    authorized_gate_evidence: "_RetainedCheckpointGateEvidence | None" = None

    @classmethod
    def capture(
        cls,
        repository_guard: RetainedRepositoryAuthorityGuard,
        checkpoint_path: Path,
        event_id: str,
        capture: Callable[[Path, Path, str], dict[str, Any]],
    ) -> "_RepositoryStateAuthority":
        checkpoint_bytes = _private_file_bytes(
            checkpoint_path,
            allow_empty=False,
            maximum_bytes=CHECKPOINT_MAX_BYTES,
        )
        gate_evidence: _RetainedCheckpointGateEvidence | None = None
        try:
            gate_evidence = _RetainedCheckpointGateEvidence.capture(
                repository_guard.root,
                checkpoint_bytes,
            )
            payload = repository_guard.capture_state(
                checkpoint_path,
                event_id,
                capture=capture,
            )
            gate_evidence.verify()
            if _private_file_bytes(
                checkpoint_path,
                allow_empty=False,
                maximum_bytes=CHECKPOINT_MAX_BYTES,
            ) != checkpoint_bytes:
                raise GateError(
                    "checkpoint changed during repository authority capture"
                )
            authorized_controlled_paths = (
                _authorized_checkpoint_controlled_paths(
                    checkpoint_bytes,
                    payload,
                )
            )
            canonical_payload = canonical_json_bytes(payload)
            authority = cls(
                copy.deepcopy(payload),
                canonical_payload,
                canonical_payload + b"\n",
                True,
                authorized_controlled_paths,
                gate_evidence,
            )
            authority.verify()
            return authority
        except BaseException as primary:
            if gate_evidence is not None:
                gate_evidence.close(primary)
            raise

    @classmethod
    def from_payload(
        cls,
        payload: dict[str, Any],
        *,
        strict_cli_output: bool,
    ) -> "_RepositoryStateAuthority":
        canonical_payload = canonical_json_bytes(payload)
        return cls(
            copy.deepcopy(payload),
            canonical_payload,
            canonical_payload + b"\n",
            strict_cli_output,
        )

    def require_exact(self, payload: object, *, label: str) -> None:
        if not isinstance(payload, dict) or canonical_json_bytes(
            payload
        ) != self.canonical_payload:
            raise GateError(f"{label} differs from captured repository authority")

    def verify(self) -> None:
        if self.authorized_gate_evidence is not None:
            self.authorized_gate_evidence.verify()

    def close(self, primary: BaseException | None = None) -> None:
        if self.authorized_gate_evidence is not None:
            self.authorized_gate_evidence.close(primary)


def _snapshot_root_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
    )


_SNAPSHOT_PUBLIC_PARENT_MODES = frozenset({0o755, 0o775})


def _materialize_snapshot_parent_modes(
    live_tree: _RetainedDirectoryTree,
    snapshot_root: Path,
    private_directories: frozenset[Path],
) -> None:
    live_tree.verify()
    for relative in sorted(
        live_tree.metadata,
        key=lambda value: (len(value.parts), value.as_posix()),
    ):
        metadata = live_tree.metadata[relative]
        mode = stat.S_IMODE(metadata[2])
        if metadata[3] != os.geteuid():
            raise GateError(f"isolated snapshot parent owner is unsafe: {relative}")
        if relative in private_directories:
            if mode != 0o700:
                raise GateError(
                    f"isolated snapshot private parent mode is unsafe: {relative}"
                )
            if any(
                candidate != relative and relative in candidate.parents
                for candidate in live_tree.metadata
            ):
                raise GateError(
                    f"isolated snapshot private parent is not terminal: {relative}"
                )
            continue
        if mode not in _SNAPSHOT_PUBLIC_PARENT_MODES:
            raise GateError(f"isolated snapshot parent mode is unsafe: {relative}")
        if relative == Path("."):
            continue
        destination = snapshot_root / relative
        destination.mkdir(mode=0o700, exist_ok=True)
        before = destination.lstat()
        if (
            destination.is_symlink()
            or not stat.S_ISDIR(before.st_mode)
            or before.st_uid != os.geteuid()
        ):
            raise GateError(f"isolated snapshot parent is unsafe: {relative}")
        os.chmod(destination, mode, follow_symlinks=False)
        after = destination.lstat()
        if (
            not stat.S_ISDIR(after.st_mode)
            or after.st_dev != before.st_dev
            or after.st_ino != before.st_ino
            or stat.S_IMODE(after.st_mode) != mode
            or after.st_uid != os.geteuid()
        ):
            raise GateError(f"isolated snapshot parent mode differs: {relative}")
    live_tree.verify()


def _require_snapshot_parent_modes(
    live_tree: _RetainedDirectoryTree,
    snapshot_tree: _RetainedDirectoryTree,
    private_directories: frozenset[Path],
) -> None:
    live_tree.verify()
    snapshot_tree.verify()
    if live_tree.metadata.keys() != snapshot_tree.metadata.keys():
        raise GateError("isolated snapshot parent inventory differs")
    for relative, live_metadata in live_tree.metadata.items():
        snapshot_metadata = snapshot_tree.metadata[relative]
        expected_mode = (
            0o700
            if relative == Path(".") or relative in private_directories
            else stat.S_IMODE(live_metadata[2])
        )
        if (
            stat.S_IMODE(snapshot_metadata[2]) != expected_mode
            or snapshot_metadata[3] != os.geteuid()
        ):
            raise GateError(f"isolated snapshot parent mode differs: {relative}")
    live_tree.verify()
    snapshot_tree.verify()


def _write_descriptor_all(descriptor: int, content: bytes) -> None:
    offset = 0
    while offset < len(content):
        written = os.write(descriptor, content[offset:])
        if written <= 0:
            raise GateError("isolated repository snapshot write made no progress")
        offset += written


def _copy_snapshot_regular_file(source: Path, destination: Path) -> None:
    source_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    source_descriptor = os.open(source, source_flags)
    destination_descriptor: int | None = None
    try:
        before = os.fstat(source_descriptor)
        named_before = source.lstat()
        if (
            not stat.S_ISREG(before.st_mode)
            or _stable_file_metadata(before)
            != _stable_file_metadata(named_before)
        ):
            raise GateError(
                f"isolated snapshot source authority differs: {source}"
            )
        destination_descriptor = os.open(
            destination,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        offset = 0
        while offset < before.st_size:
            chunk = os.pread(
                source_descriptor,
                min(1024 * 1024, before.st_size - offset),
                offset,
            )
            if not chunk:
                raise GateError(
                    f"isolated snapshot source became short: {source}"
                )
            _write_descriptor_all(destination_descriptor, chunk)
            offset += len(chunk)
        os.fchmod(destination_descriptor, stat.S_IMODE(before.st_mode))
        after = os.fstat(source_descriptor)
        named_after = source.lstat()
        if (
            _stable_file_metadata(after) != _stable_file_metadata(before)
            or _stable_file_metadata(named_after)
            != _stable_file_metadata(before)
        ):
            raise GateError(
                f"isolated snapshot source changed while copying: {source}"
            )
    finally:
        if destination_descriptor is not None:
            os.close(destination_descriptor)
        os.close(source_descriptor)


def _snapshot_relative_path(raw: bytes) -> Path:
    try:
        text_value = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise GateError("isolated snapshot Git path is not UTF-8") from exc
    relative = Path(text_value)
    if (
        not text_value
        or "\0" in text_value
        or relative.is_absolute()
        or any(part in {"", ".", ".."} for part in relative.parts)
        or relative.parts[0] == ".git"
        or relative.as_posix() != text_value
    ):
        raise GateError("isolated snapshot Git path is unsafe")
    return relative


def _authorized_checkpoint_controlled_paths(
    checkpoint_bytes: bytes,
    payload: dict[str, Any],
) -> tuple[Path, ...]:
    try:
        checkpoint = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("repository authority checkpoint is invalid JSON") from exc
    snapshot = (
        checkpoint.get("working_tree_snapshot")
        if isinstance(checkpoint, dict)
        else None
    )
    controlled = payload.get("checkpoint_controlled_working_snapshot")
    raw_paths = (
        snapshot.get("managed_changed_paths")
        if isinstance(snapshot, dict)
        else None
    )
    if (
        not isinstance(raw_paths, list)
        or not all(isinstance(value, str) for value in raw_paths)
        or raw_paths != sorted(set(raw_paths))
    ):
        raise GateError("checkpoint controlled path list is malformed")
    try:
        relatives = tuple(
            _snapshot_relative_path(value.encode("utf-8", errors="strict"))
            for value in raw_paths
        )
    except UnicodeEncodeError as exc:
        raise GateError("checkpoint controlled path is not UTF-8") from exc
    path_set_sha256 = hashlib.sha256(
        ("\n".join(raw_paths) + "\n").encode("utf-8")
    ).hexdigest()
    if (
        not isinstance(snapshot, dict)
        or type(snapshot.get("managed_changed_path_count")) is not int
        or snapshot["managed_changed_path_count"] != len(relatives)
        or snapshot.get("path_set_sha256") != path_set_sha256
        or not isinstance(controlled, dict)
        or type(controlled.get("managed_changed_path_count")) is not int
        or controlled["managed_changed_path_count"] != len(relatives)
        or controlled.get("path_set_sha256") != path_set_sha256
    ):
        raise GateError("checkpoint controlled path authority differs")
    return relatives


def _checkpoint_bound_gate_event_directories(
    checkpoint_bytes: bytes,
) -> tuple[tuple[Path, ...], tuple[Path, ...]]:
    try:
        checkpoint = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("repository authority checkpoint is invalid JSON") from exc
    gate_prefix = GATE_ROOT_RELATIVE.as_posix() + "/"
    bound_paths: set[Path] = set()

    def collect(value: object) -> None:
        if isinstance(value, dict):
            for key, nested in value.items():
                if isinstance(nested, str) and gate_prefix in nested:
                    if key != "path" or not nested.startswith(gate_prefix):
                        raise GateError(
                            "checkpoint gate evidence reference is malformed"
                        )
                    try:
                        relative = _snapshot_relative_path(
                            nested.encode("utf-8", errors="strict")
                        )
                    except UnicodeEncodeError as exc:
                        raise GateError(
                            "checkpoint gate evidence path is not UTF-8"
                        ) from exc
                    expected_parts = len(GATE_ROOT_RELATIVE.parts) + 2
                    if (
                        len(relative.parts) != expected_parts
                        or relative.parts[: len(GATE_ROOT_RELATIVE.parts)]
                        != GATE_ROOT_RELATIVE.parts
                        or not relative.parent.name.startswith(
                            "WS-GOAL-GRAPH-V2-4-"
                        )
                    ):
                        raise GateError(
                            "checkpoint gate evidence path is outside an event"
                        )
                    bound_paths.add(relative)
                else:
                    collect(nested)
        elif isinstance(value, list):
            for nested in value:
                collect(nested)
        elif isinstance(value, str) and gate_prefix in value:
            raise GateError("checkpoint gate evidence reference is malformed")

    collect(checkpoint)
    event_directories = tuple(
        sorted(
            {relative.parent for relative in bound_paths},
            key=lambda value: value.as_posix(),
        )
    )
    if len(event_directories) > BOUND_GATE_EVENT_MAX_COUNT:
        raise GateError("checkpoint binds too many gate evidence events")
    return event_directories, tuple(
        sorted(bound_paths, key=lambda value: value.as_posix())
    )


def _copy_snapshot_retained_regular_file(
    pin: _RetainedSourceFile,
    destination: Path,
) -> None:
    descriptor = os.open(
        destination,
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    try:
        _write_descriptor_all(descriptor, pin.content)
        os.fchmod(descriptor, stat.S_IMODE(pin.metadata[2]))
        os.fsync(descriptor)
        opened = os.fstat(descriptor)
        if opened.st_size != len(pin.content):
            raise GateError(
                f"isolated retained evidence write differs: {pin.relative}"
            )
    finally:
        os.close(descriptor)


class _RetainedCheckpointGateEvidence:
    def __init__(
        self,
        event_inventories: dict[Path, tuple[str, ...]],
        source_guard: _RetainedSourceGuard | None,
    ) -> None:
        self.event_inventories = event_inventories
        self.source_guard = source_guard

    @classmethod
    def capture(
        cls,
        root: Path,
        checkpoint_bytes: bytes,
    ) -> "_RetainedCheckpointGateEvidence":
        event_directories, bound_paths = _checkpoint_bound_gate_event_directories(
            checkpoint_bytes
        )
        if not event_directories:
            return cls({}, None)
        inventories: dict[Path, tuple[str, ...]] = {}
        relatives: list[Path] = []
        observed_bytes = 0
        for event_relative in event_directories:
            event_directory = root / event_relative
            try:
                event_metadata = event_directory.lstat()
                if (
                    not stat.S_ISDIR(event_metadata.st_mode)
                    or event_directory.is_symlink()
                    or event_metadata.st_uid != os.geteuid()
                ):
                    raise GateError(
                        f"checkpoint-bound gate event is unsafe: "
                        f"{event_relative}"
                    )
                names: list[str] = []
                with os.scandir(event_directory) as entries:
                    for entry in entries:
                        if len(relatives) + len(names) >= (
                            BOUND_GATE_FILE_MAX_COUNT
                        ):
                            raise GateError(
                                "checkpoint binds too many gate evidence files"
                            )
                        _safe_entry_name(entry.name)
                        metadata = entry.stat(follow_symlinks=False)
                        if (
                            not stat.S_ISREG(metadata.st_mode)
                            or metadata.st_uid != os.geteuid()
                            or metadata.st_size > LOG_MAX_BYTES
                            or metadata.st_size
                            > BOUND_GATE_TOTAL_MAX_BYTES - observed_bytes
                        ):
                            raise GateError(
                                f"checkpoint-bound gate evidence is unsafe: "
                                f"{event_relative / entry.name}"
                            )
                        observed_bytes += metadata.st_size
                        names.append(entry.name)
            except OSError as exc:
                raise GateError(
                    f"checkpoint-bound gate event is unavailable: "
                    f"{event_relative}"
                ) from exc
            if not names:
                raise GateError(
                    f"checkpoint-bound gate event is empty: {event_relative}"
                )
            names.sort()
            relatives.extend(event_relative / name for name in names)
            inventories[event_relative] = tuple(names)
        if not set(bound_paths).issubset(relatives):
            raise GateError("checkpoint-bound gate evidence file is missing")
        source_guard: _RetainedSourceGuard | None = None
        try:
            source_guard = _RetainedSourceGuard.capture(
                root,
                relatives,
                maximum_bytes=LOG_MAX_BYTES,
                total_maximum_bytes=BOUND_GATE_TOTAL_MAX_BYTES,
            )
            retained = cls(inventories, source_guard)
            retained.verify()
            return retained
        except BaseException as primary:
            if source_guard is not None:
                source_guard.close(primary)
            raise

    @property
    def paths(self) -> tuple[Path, ...]:
        if self.source_guard is None:
            return ()
        return tuple(pin.relative for pin in self.source_guard.pins)

    def verify(self) -> None:
        if self.source_guard is None:
            if self.event_inventories:
                raise GateError("checkpoint gate evidence authority is incomplete")
            return
        self.source_guard.verify()
        for event_relative, expected_names in self.event_inventories.items():
            descriptor = self.source_guard.directory_tree.descriptors.get(
                event_relative
            )
            if descriptor is None:
                raise GateError(
                    "checkpoint gate evidence directory is not retained"
                )
            current_names = tuple(sorted(os.listdir(descriptor)))
            if current_names != expected_names:
                raise GateError(
                    f"checkpoint gate evidence inventory changed: "
                    f"{event_relative}"
                )
        self.source_guard.verify()

    def copy_to(self, snapshot_root: Path) -> None:
        self.verify()
        if self.source_guard is None:
            return
        for event_relative in sorted(
            self.event_inventories,
            key=lambda value: value.as_posix(),
        ):
            destination = snapshot_root / event_relative
            destination.mkdir(mode=0o700, parents=True, exist_ok=False)
            source_descriptor = (
                self.source_guard.directory_tree.descriptors[event_relative]
            )
            os.chmod(
                destination,
                stat.S_IMODE(os.fstat(source_descriptor).st_mode),
            )
        for pin in sorted(
            self.source_guard.pins,
            key=lambda value: value.relative.as_posix(),
        ):
            _copy_snapshot_retained_regular_file(
                pin,
                snapshot_root / pin.relative,
            )
        self.verify()

    def require_snapshot_exact(
        self,
        snapshot_guard: _RetainedSourceGuard,
    ) -> None:
        self.verify()
        snapshot_guard.verify()
        if self.source_guard is None:
            if snapshot_guard.pins:
                raise GateError("isolated gate evidence snapshot differs")
            return
        source_pins = {pin.relative: pin for pin in self.source_guard.pins}
        snapshot_pins = {pin.relative: pin for pin in snapshot_guard.pins}
        if set(snapshot_pins) != set(source_pins):
            raise GateError("isolated gate evidence path set differs")
        for relative, source_pin in source_pins.items():
            snapshot_pin = snapshot_pins[relative]
            if (
                snapshot_pin.content != source_pin.content
                or stat.S_IMODE(snapshot_pin.metadata[2])
                != stat.S_IMODE(source_pin.metadata[2])
            ):
                raise GateError(
                    f"isolated gate evidence file differs: {relative}"
                )
        for event_relative, expected_names in self.event_inventories.items():
            source_descriptor = (
                self.source_guard.directory_tree.descriptors[event_relative]
            )
            snapshot_descriptor = (
                snapshot_guard.directory_tree.descriptors[event_relative]
            )
            if (
                tuple(sorted(os.listdir(snapshot_descriptor)))
                != expected_names
                or stat.S_IMODE(os.fstat(snapshot_descriptor).st_mode)
                != stat.S_IMODE(os.fstat(source_descriptor).st_mode)
            ):
                raise GateError(
                    f"isolated gate evidence inventory differs: "
                    f"{event_relative}"
                )
        snapshot_guard.verify()
        self.verify()

    def close(self, primary: BaseException | None = None) -> None:
        if self.source_guard is not None:
            self.source_guard.close(primary)


class _RetainedIsolatedRepositorySnapshot:
    def __init__(
        self,
        live_root: Path,
        container: Path,
        root: Path,
        container_identity: tuple[int, ...],
        root_descriptor: int,
        root_identity: tuple[int, ...],
        git_identity: tuple[int, ...],
        authority: _RepositoryStateAuthority | None,
        repository_guard: RetainedRepositoryAuthorityGuard,
        event_id: str,
        repository_state_guard: Callable[[Path, Path, str], dict[str, Any]],
        namespace_guard: RetainedRepositoryAuthorityGuard | None = None,
        snapshot_evidence_guard: _RetainedSourceGuard | None = None,
        snapshot_parent_guard: _RetainedDirectoryTree | None = None,
    ) -> None:
        self.live_root = live_root
        self.container = container
        self.root = root
        self.container_identity = container_identity
        self.root_descriptor = root_descriptor
        self.root_identity = root_identity
        self.git_identity = git_identity
        self.authority = authority
        self.repository_guard = repository_guard
        self.event_id = event_id
        self.repository_state_guard = repository_state_guard
        self.namespace_guard = namespace_guard
        self.snapshot_evidence_guard = snapshot_evidence_guard
        self.snapshot_parent_guard = snapshot_parent_guard

    @staticmethod
    def _run_git(
        repository_guard: RetainedRepositoryAuthorityGuard,
        cwd: Path,
        arguments: Sequence[str],
        *,
        accepted_returncodes: tuple[int, ...] = (0,),
        stdin: BinaryIO | None = None,
        stdout: BinaryIO | int | None = subprocess.PIPE,
    ) -> subprocess.CompletedProcess[bytes]:
        completed = subprocess.run(
            [os.fspath(repository_guard.git_command), *arguments],
            cwd=cwd,
            env=repository_guard._command_environment(
                repository_guard.workspace
            ),
            check=False,
            stdin=stdin,
            stdout=stdout,
            stderr=subprocess.PIPE,
            umask=0o077,
        )
        if completed.returncode not in accepted_returncodes:
            raise GateError(
                "isolated repository Git command failed safely with exit code "
                f"{completed.returncode}"
            )
        return completed

    @classmethod
    def bind(
        cls,
        live_root: Path,
        container: Path,
        root: Path,
        authority: _RepositoryStateAuthority | None,
        repository_guard: RetainedRepositoryAuthorityGuard,
        event_id: str,
        repository_state_guard: Callable[[Path, Path, str], dict[str, Any]],
        snapshot_parent_guard: _RetainedDirectoryTree | None = None,
    ) -> "_RetainedIsolatedRepositorySnapshot":
        live_root = live_root.resolve(strict=True)
        container = container.resolve(strict=True)
        root = root.resolve(strict=True)
        try:
            root.relative_to(live_root)
        except ValueError:
            pass
        else:
            raise GateError("isolated repository snapshot is inside the live root")
        try:
            live_root.relative_to(root)
        except ValueError:
            pass
        else:
            raise GateError("live repository is inside the isolated snapshot")
        if container.parent != repository_guard.workspace / "snapshots":
            raise GateError("isolated snapshot container authority differs")
        container_metadata = container.lstat()
        root_metadata = root.lstat()
        if (
            container.is_symlink()
            or root.is_symlink()
            or not stat.S_ISDIR(container_metadata.st_mode)
            or not stat.S_ISDIR(root_metadata.st_mode)
            or stat.S_IMODE(container_metadata.st_mode) != 0o700
            or stat.S_IMODE(root_metadata.st_mode) != 0o700
            or container_metadata.st_uid != os.geteuid()
            or container_metadata.st_gid != os.getegid()
            or root_metadata.st_uid != os.geteuid()
            or root_metadata.st_gid != os.getegid()
        ):
            raise GateError("isolated repository snapshot authority is unsafe")
        git_metadata = (root / ".git").lstat()
        if (
            not stat.S_ISDIR(git_metadata.st_mode)
            or (root / ".git").is_symlink()
            or stat.S_IMODE(git_metadata.st_mode) != 0o700
            or git_metadata.st_uid != os.geteuid()
            or git_metadata.st_gid != os.getegid()
        ):
            raise GateError("isolated repository does not own an independent .git")
        if snapshot_parent_guard is not None and snapshot_parent_guard.root != root:
            raise GateError("isolated snapshot parent authority differs")
        root_descriptor = os.open(
            root,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        namespace_guard: RetainedRepositoryAuthorityGuard | None = None
        snapshot_evidence_guard: _RetainedSourceGuard | None = None
        try:
            if authority is not None and authority.strict_cli_output:
                evidence = authority.authorized_gate_evidence
                if evidence is not None and evidence.paths:
                    snapshot_evidence_guard = _RetainedSourceGuard.capture(
                        root,
                        evidence.paths,
                        maximum_bytes=LOG_MAX_BYTES,
                        total_maximum_bytes=BOUND_GATE_TOTAL_MAX_BYTES,
                    )
                    evidence.require_snapshot_exact(snapshot_evidence_guard)
                namespace_guard = RetainedRepositoryAuthorityGuard.capture(root)
            retained = cls(
                live_root,
                container,
                root,
                _event_directory_identity(container_metadata),
                root_descriptor,
                _snapshot_root_identity(root_metadata),
                _snapshot_root_identity(git_metadata),
                authority,
                repository_guard,
                event_id,
                repository_state_guard,
                namespace_guard,
                snapshot_evidence_guard,
                snapshot_parent_guard,
            )
            retained.verify()
            if authority is not None:
                retained.verify_repository()
            return retained
        except BaseException as primary:
            if namespace_guard is not None:
                namespace_guard.close(primary)
            if snapshot_evidence_guard is not None:
                snapshot_evidence_guard.close(primary)
            if snapshot_parent_guard is not None:
                snapshot_parent_guard.close(primary)
            try:
                os.close(root_descriptor)
            except BaseException:
                pass
            raise

    @classmethod
    def capture(
        cls,
        live_root: Path,
        event_id: str,
        authority: _RepositoryStateAuthority,
        repository_guard: RetainedRepositoryAuthorityGuard,
        repository_state_guard: Callable[[Path, Path, str], dict[str, Any]],
    ) -> "_RetainedIsolatedRepositorySnapshot":
        repository_guard.verify()
        snapshots_parent = repository_guard.workspace / "snapshots"
        container = Path(
            tempfile.mkdtemp(prefix="repository-", dir=snapshots_parent)
        )
        os.chmod(container, 0o700)
        snapshot_root = container / "repository"
        live_parent_guard: _RetainedDirectoryTree | None = None
        snapshot_parent_guard: _RetainedDirectoryTree | None = None
        try:
            clone = cls._run_git(
                repository_guard,
                container,
                (
                    "clone",
                    "--no-local",
                    "--no-hardlinks",
                    "--no-checkout",
                    "--quiet",
                    f"--upload-pack={repository_guard.workspace / 'commands' / 'git-upload-pack'}",
                    "--",
                    os.fspath(live_root),
                    os.fspath(snapshot_root),
                ),
            )
            del clone
            os.chmod(snapshot_root, 0o700)
            git_directory = snapshot_root / ".git"
            if not git_directory.is_dir() or git_directory.is_symlink():
                raise GateError("isolated clone did not create an independent .git")
            os.chmod(git_directory, 0o700)
            live_index = repository_guard.discovery[2]
            snapshot_index = git_directory / "index"
            if snapshot_index.exists() or snapshot_index.is_symlink():
                snapshot_index.unlink()
            _copy_snapshot_regular_file(live_index, snapshot_index)

            visible_result = cls._run_git(
                repository_guard,
                live_root,
                ("ls-files", "--cached", "--others", "--exclude-standard", "-z"),
            )
            visible_raw = visible_result.stdout
            assert isinstance(visible_raw, bytes)
            if visible_raw and not visible_raw.endswith(b"\0"):
                raise GateError("isolated snapshot path list is not NUL terminated")
            raw_paths = visible_raw[:-1].split(b"\0") if visible_raw else []
            visible_relatives = [
                _snapshot_relative_path(value) for value in raw_paths
            ]
            if len(visible_relatives) != len(set(visible_relatives)):
                raise GateError("isolated snapshot Git path list is ambiguous")
            relatives = set(visible_relatives)
            relatives.update(authority.authorized_controlled_paths)
            evidence = authority.authorized_gate_evidence
            parent_sources = set(relatives)
            private_directories: frozenset[Path] = frozenset()
            if evidence is not None:
                relatives.difference_update(evidence.paths)
                parent_sources.update(evidence.paths)
                private_directories = frozenset(evidence.event_inventories)
            try:
                live_parent_guard = _RetainedDirectoryTree.capture(
                    live_root,
                    tuple(parent_sources),
                )
            except OSError as exc:
                raise GateError(
                    "isolated snapshot live parent authority is unsafe"
                ) from exc
            _materialize_snapshot_parent_modes(
                live_parent_guard,
                snapshot_root,
                private_directories,
            )
            for relative in sorted(
                relatives,
                key=lambda value: (len(value.parts), value.as_posix()),
            ):
                source = live_root / relative
                destination = snapshot_root / relative
                try:
                    before = source.lstat()
                except FileNotFoundError:
                    continue
                if stat.S_ISREG(before.st_mode):
                    _copy_snapshot_regular_file(source, destination)
                elif stat.S_ISLNK(before.st_mode):
                    target = os.readlink(source)
                    after = source.lstat()
                    if _stable_file_metadata(after) != _stable_file_metadata(before):
                        raise GateError(
                            f"isolated snapshot symlink changed: {relative}"
                        )
                    if os.path.isabs(target):
                        raise GateError(
                            f"isolated snapshot symlink escapes the repository: {relative}"
                        )
                    projected = (destination.parent / target).resolve(strict=False)
                    try:
                        projected.relative_to(snapshot_root)
                    except ValueError as exc:
                        raise GateError(
                            f"isolated snapshot symlink escapes the repository: {relative}"
                        ) from exc
                    os.symlink(target, destination)
                else:
                    raise GateError(
                        f"isolated snapshot source has an unsafe type: {relative}"
                    )
            if evidence is not None:
                evidence.copy_to(snapshot_root)
            live_parent_guard.verify()
            snapshot_parent_guard = _RetainedDirectoryTree.capture(
                snapshot_root,
                tuple(parent_sources),
            )
            _require_snapshot_parent_modes(
                live_parent_guard,
                snapshot_parent_guard,
                private_directories,
            )

            stage_result = cls._run_git(
                repository_guard,
                live_root,
                ("ls-files", "--stage", "-z"),
            )
            stage_raw = stage_result.stdout
            assert isinstance(stage_raw, bytes)
            if stage_raw and not stage_raw.endswith(b"\0"):
                raise GateError("isolated snapshot index list is not NUL terminated")
            object_ids: set[str] = set()
            for record in stage_raw[:-1].split(b"\0") if stage_raw else ():
                try:
                    raw_identity, _raw_path = record.split(b"\t", 1)
                    raw_mode, raw_object_id, _raw_stage = raw_identity.split(b" ")
                    mode = raw_mode.decode("ascii")
                    object_id = raw_object_id.decode("ascii")
                except (ValueError, UnicodeDecodeError) as exc:
                    raise GateError("isolated snapshot index entry is malformed") from exc
                if mode == "160000":
                    raise GateError("isolated repository snapshot rejects submodules")
                object_ids.add(object_id)
            for object_id in sorted(object_ids):
                exists = cls._run_git(
                    repository_guard,
                    snapshot_root,
                    ("cat-file", "-e", f"{object_id}^{{blob}}"),
                    accepted_returncodes=(0, 1, 128),
                )
                if exists.returncode == 0:
                    continue
                with tempfile.TemporaryFile(dir=container) as staged_object:
                    cls._run_git(
                        repository_guard,
                        live_root,
                        ("cat-file", "blob", object_id),
                        stdout=staged_object,
                    )
                    staged_object.flush()
                    staged_object.seek(0)
                    written = cls._run_git(
                        repository_guard,
                        snapshot_root,
                        ("hash-object", "-w", "--stdin"),
                        stdin=staged_object,
                    ).stdout
                    if not isinstance(written, bytes) or written.strip().decode("ascii") != object_id:
                        raise GateError("isolated snapshot staged object differs")

            final_visible = cls._run_git(
                repository_guard,
                live_root,
                ("ls-files", "--cached", "--others", "--exclude-standard", "-z"),
            ).stdout
            if final_visible != visible_raw:
                raise GateError("live repository paths changed during snapshot capture")
            live_parent_guard.verify()
            repository_guard.verify()
            live_parent_guard.close()
            live_parent_guard = None
            try:
                retained = cls.bind(
                    live_root,
                    container,
                    snapshot_root,
                    authority,
                    repository_guard,
                    event_id,
                    repository_state_guard,
                    snapshot_parent_guard=snapshot_parent_guard,
                )
            except BaseException as primary:
                snapshot_parent_guard.close(primary)
                snapshot_parent_guard = None
                raise
            snapshot_parent_guard = None
            return retained
        except BaseException as primary:
            if live_parent_guard is not None:
                live_parent_guard.close(primary)
            if snapshot_parent_guard is not None:
                snapshot_parent_guard.close(primary)
            try:
                if container.is_dir() and not container.is_symlink():
                    shutil.rmtree(container)
            except BaseException:
                pass
            raise

    @classmethod
    def capture_injected_test_snapshot(
        cls,
        live_root: Path,
        event_id: str,
        authority: _RepositoryStateAuthority | None,
        repository_guard: RetainedRepositoryAuthorityGuard,
        repository_state_guard: Callable[[Path, Path, str], dict[str, Any]],
    ) -> "_RetainedIsolatedRepositorySnapshot":
        snapshots_parent = repository_guard.workspace / "snapshots"
        container = Path(
            tempfile.mkdtemp(prefix="injected-repository-", dir=snapshots_parent)
        )
        os.chmod(container, 0o700)
        snapshot_root = container / "repository"
        try:
            snapshot_root.mkdir(mode=0o700)
            os.chmod(snapshot_root, 0o700)
            for source_text, directory_names, _ in os.walk(
                live_root,
                topdown=True,
                followlinks=False,
            ):
                source_directory = Path(source_text)
                directory_names[:] = sorted(
                    name
                    for name in directory_names
                    if name != ".git"
                    and not (source_directory / name).is_symlink()
                )
                destination_parent = (
                    snapshot_root / source_directory.relative_to(live_root)
                )
                for name in directory_names:
                    destination_directory = destination_parent / name
                    destination_directory.mkdir(mode=0o700)
                    os.chmod(destination_directory, 0o700)
            shutil.copytree(
                live_root,
                snapshot_root,
                symlinks=True,
                dirs_exist_ok=True,
                ignore=lambda _directory, names: [
                    name for name in names if name == ".git"
                ],
            )
            os.chmod(snapshot_root, 0o700)
            (snapshot_root / ".git").mkdir(mode=0o700)
            os.chmod(snapshot_root / ".git", 0o700)
            return cls.bind(
                live_root,
                container,
                snapshot_root,
                authority,
                repository_guard,
                event_id,
                repository_state_guard,
            )
        except BaseException:
            try:
                if container.is_dir() and not container.is_symlink():
                    shutil.rmtree(container)
            except BaseException:
                pass
            raise

    def verify(self) -> None:
        self.repository_guard.verify()
        if self.snapshot_parent_guard is not None:
            self.snapshot_parent_guard.verify()
        if self.snapshot_evidence_guard is not None:
            self.snapshot_evidence_guard.verify()
        if self.namespace_guard is not None:
            self.namespace_guard.verify()
        container_named = self.container.lstat()
        root_named = self.root.lstat()
        root_opened = os.fstat(self.root_descriptor)
        if (
            self.container.is_symlink()
            or self.root.is_symlink()
            or _event_directory_identity(container_named)
            != self.container_identity
            or _snapshot_root_identity(root_named) != self.root_identity
            or _snapshot_root_identity(root_opened) != self.root_identity
            or stat.S_IMODE(root_opened.st_mode) != 0o700
        ):
            raise GateError("isolated repository snapshot identity changed")
        git_named = (self.root / ".git").lstat()
        if (
            not stat.S_ISDIR(git_named.st_mode)
            or (self.root / ".git").is_symlink()
            or _snapshot_root_identity(git_named) != self.git_identity
            or stat.S_IMODE(git_named.st_mode) != 0o700
        ):
            raise GateError("isolated repository .git authority changed")
        if self.namespace_guard is not None:
            self.namespace_guard.verify()
        if self.snapshot_evidence_guard is not None:
            self.snapshot_evidence_guard.verify()
        if self.snapshot_parent_guard is not None:
            self.snapshot_parent_guard.verify()
        self.repository_guard.verify()

    def verify_repository(self) -> None:
        self.verify()
        if self.authority is None:
            raise GateError("isolated repository authority is not bound")
        with self.repository_guard.command_environment():
            payload = self.repository_state_guard(
                self.root,
                self.root / CHECKPOINT_RELATIVE,
                self.event_id,
            )
        self.authority.require_exact(payload, label="isolated repository snapshot")
        self.verify()

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        if primary is None:
            try:
                self.verify()
            except BaseException as exc:
                first = exc
        if self.namespace_guard is not None:
            try:
                self.namespace_guard.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
            self.namespace_guard = None
        if self.snapshot_evidence_guard is not None:
            try:
                self.snapshot_evidence_guard.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
            self.snapshot_evidence_guard = None
        if self.snapshot_parent_guard is not None:
            try:
                self.snapshot_parent_guard.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
            self.snapshot_parent_guard = None
        try:
            os.close(self.root_descriptor)
        except BaseException as exc:
            if first is None:
                first = exc
        try:
            container_named = self.container.lstat()
            if (
                self.container.is_symlink()
                or _event_directory_identity(container_named)
                != self.container_identity
            ):
                raise GateError("isolated snapshot cannot be cleaned safely")
            shutil.rmtree(self.container)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


class _GateRunResources:
    def __init__(
        self,
        checkpoint_lock: _RetainedCheckpointParentLock,
        source_guard: _RetainedSourceGuard,
        repository_guard: RetainedRepositoryAuthorityGuard,
    ) -> None:
        self.checkpoint_lock = checkpoint_lock
        self.source_guard = source_guard
        self.repository_guard = repository_guard
        self.repository_authority: _RepositoryStateAuthority | None = None
        self.snapshot: _RetainedIsolatedRepositorySnapshot | None = None
        self.event: _RetainedFreshEventDirectory | None = None
        self.logs: dict[str, _RetainedGateLog] = {}
        self.receipt_published = False

    @classmethod
    def capture(cls, root: Path) -> "_GateRunResources":
        checkpoint_lock = _RetainedCheckpointParentLock.capture(root)
        source_guard: _RetainedSourceGuard | None = None
        repository_guard: RetainedRepositoryAuthorityGuard | None = None
        try:
            repository_guard = RetainedRepositoryAuthorityGuard.capture(root)
            source_guard = _RetainedSourceGuard.capture(
                root,
                SOURCE_GUARD_RELATIVES,
                checkpoint_lock=checkpoint_lock,
            )
            resources = cls(
                checkpoint_lock,
                source_guard,
                repository_guard,
            )
            resources.verify_source()
            return resources
        except BaseException as exc:
            if source_guard is not None:
                source_guard.close(exc)
            if repository_guard is not None:
                repository_guard.close(exc)
            checkpoint_lock.close(exc)
            raise

    def verify_source(self) -> None:
        self.checkpoint_lock.verify()
        if self.repository_authority is not None:
            self.repository_authority.verify()
        try:
            self.repository_guard.verify()
        except (GateError, OSError, ValueError) as exc:
            raise _RepositoryAuthorityChanged(
                "retained repository authority changed"
            ) from exc
        self.source_guard.verify()
        try:
            self.repository_guard.verify()
        except (GateError, OSError, ValueError) as exc:
            raise _RepositoryAuthorityChanged(
                "retained repository authority changed"
            ) from exc
        if self.repository_authority is not None:
            self.repository_authority.verify()
        self.checkpoint_lock.verify()

    def verify_all(self) -> None:
        self.verify_source()
        if self.snapshot is not None:
            self.snapshot.verify()
            if self.snapshot.authority is not None:
                self.snapshot.verify_repository()
        if self.event is not None:
            self.event.verify()
            for retained in self.logs.values():
                retained.verify(self.event)
            self.event.verify()
        self.verify_source()

    def mark_receipt_published(self) -> None:
        self.receipt_published = True

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        if primary is None or self.receipt_published:
            try:
                self.verify_all()
            except BaseException as exc:
                first = exc
        for retained in self.logs.values():
            try:
                retained.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
        self.logs.clear()
        if self.event is not None:
            try:
                self.event.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
            self.event = None
        if self.snapshot is not None:
            try:
                self.snapshot.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
            self.snapshot = None
        if self.repository_authority is not None:
            try:
                self.repository_authority.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
            self.repository_authority = None
        try:
            self.source_guard.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        try:
            self.repository_guard.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        try:
            self.checkpoint_lock.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        if first is not None and (primary is None or self.receipt_published):
            if self.receipt_published:
                raise GatePostCommitUncertain(
                    "published receipt retained boundary cleanup failed"
                ) from first
            raise GateError("gate retained boundary cleanup failed") from first
        if (
            self.receipt_published
            and primary is not None
            and not isinstance(primary, GatePostCommitUncertain)
        ):
            raise GatePostCommitUncertain(
                "published receipt terminal boundary failed"
            ) from primary


def _open_bound_directory(
    path: Path,
    identity: tuple[int, ...],
) -> int:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(
        os, "O_CLOEXEC", 0
    ) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        _require_directory_fd(descriptor, path, identity)
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor


def _require_directory_fd(
    descriptor: int,
    path: Path,
    identity: tuple[int, ...],
) -> None:
    metadata = os.fstat(descriptor)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()
        or _event_directory_identity(metadata) != identity
    ):
        raise GateError("retained gate event directory identity changed")
    _require_directory_identity(path, identity)


def _open_private_exclusive_at(
    directory_fd: int,
    name: str,
) -> BinaryIO:
    name = _safe_entry_name(name)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    cloexec = getattr(os, "O_CLOEXEC", 0)
    descriptor = os.open(
        name,
        os.O_RDWR | os.O_CREAT | os.O_EXCL | nofollow | cloexec,
        0o600,
        dir_fd=directory_fd,
    )
    try:
        os.fchmod(descriptor, 0o600)
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or metadata.st_uid != os.geteuid()
            or metadata.st_nlink != 1
        ):
            raise GateError(f"private add-only file metadata differs: {name}")
        return os.fdopen(descriptor, "wb", closefd=True)
    except BaseException:
        os.close(descriptor)
        raise


def _private_file_bytes_at(
    directory_fd: int,
    name: str,
    *,
    allow_empty: bool,
    maximum_bytes: int,
) -> bytes:
    name = _safe_entry_name(name)
    before = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(before.st_mode)
        or stat.S_IMODE(before.st_mode) != 0o600
        or before.st_uid != os.geteuid()
        or before.st_nlink != 1
        or before.st_size > maximum_bytes
    ):
        raise GateError(f"private add-only file metadata differs: {name}")
    descriptor = os.open(
        name,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
        dir_fd=directory_fd,
    )
    try:
        opened = os.fstat(descriptor)
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise GateError(f"private add-only file identity changed: {name}")
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(
                descriptor,
                min(1024 * 1024, maximum_bytes + 1 - total),
            )
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > maximum_bytes:
                raise GateError(f"private add-only file is too large: {name}")
    finally:
        os.close(descriptor)
    after = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    if _stable_file_metadata(after) != _stable_file_metadata(before):
        raise GateError(f"private add-only file changed while reading: {name}")
    content = b"".join(chunks)
    if not allow_empty and not content:
        raise GateError(f"private add-only file is empty: {name}")
    return content


def _read_event_file(
    event_dir: Path,
    event_identity: tuple[int, ...],
    name: str,
    *,
    allow_empty: bool,
    maximum_bytes: int,
) -> bytes:
    directory_fd = _open_bound_directory(event_dir, event_identity)
    try:
        content = _private_file_bytes_at(
            directory_fd,
            name,
            allow_empty=allow_empty,
            maximum_bytes=maximum_bytes,
        )
        _require_directory_fd(directory_fd, event_dir, event_identity)
        return content
    finally:
        os.close(directory_fd)


def _fsync_directory(path: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(
        os, "O_CLOEXEC", 0
    ) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _absolute_path_contains_symlink(path: Path) -> bool:
    if not path.is_absolute():
        raise GateError(f"gate path must be absolute: {path}")
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        if current.is_symlink():
            return True
    return False


def _event_directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


def _directory_identity(path: Path) -> tuple[int, ...]:
    if _absolute_path_contains_symlink(path):
        raise GateError(f"gate event directory contains a symlink: {path}")
    metadata = path.lstat()
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or path.is_symlink()
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()
    ):
        raise GateError(f"gate event directory is unsafe: {path}")
    return _event_directory_identity(metadata)


def _require_directory_identity(path: Path, identity: tuple[int, ...]) -> None:
    if _directory_identity(path) != identity:
        raise GateError("gate event directory identity changed")


def _write_all(
    descriptor: int,
    content: bytes,
    writer: Callable[[int, bytes], int],
) -> None:
    offset = 0
    while offset < len(content):
        try:
            written = writer(descriptor, content[offset:])
        except Exception as exc:
            raise GateError("receipt staging write failed") from exc
        if (
            isinstance(written, bool)
            or not isinstance(written, int)
            or written <= 0
            or written > len(content) - offset
        ):
            raise GateError("receipt staging write made invalid progress")
        offset += written


def _rename_noreplace_at(
    directory_fd: int,
    source_name: str,
    destination_name: str,
) -> None:
    source_name = _safe_entry_name(source_name)
    destination_name = _safe_entry_name(destination_name)
    if _RENAMEAT2 is None:
        raise GateError("renameat2(RENAME_NOREPLACE) is unavailable")
    result = _RENAMEAT2(
        directory_fd,
        os.fsencode(source_name),
        directory_fd,
        os.fsencode(destination_name),
        1,  # RENAME_NOREPLACE
    )
    if result != 0:
        error_number = ctypes.get_errno()
        raise OSError(
            error_number,
            os.strerror(error_number),
            destination_name,
        )


def _publish_private_receipt(
    event: _RetainedFreshEventDirectory,
    content: bytes,
    *,
    retained_logs: dict[str, _RetainedGateLog],
    writer: Callable[[int, bytes], int],
    file_fsync: Callable[[int], None],
    directory_fsync: Callable[[int], None],
    before_publish: Callable[[], None],
    final_cas: Callable[[], None] = lambda: None,
    on_published: Callable[[], None] = lambda: None,
    after_publish: Callable[[], None] = lambda: None,
) -> None:
    if not content or len(content) > LOG_MAX_BYTES:
        raise GateError("implementation-start receipt byte size differs")
    directory_fd = event.event_fd
    expected_log_names = set(retained_logs)
    staging: BinaryIO | None = None
    staging_identity: tuple[int, ...] | None = None
    receipt_published = False
    primary: BaseException | None = None

    def require_exact_prepublish_evidence() -> None:
        if staging is None or staging_identity is None:
            raise GateError("receipt staging authority is not retained")
        event.verify()
        for retained in retained_logs.values():
            retained.verify(event)
        staging_metadata = os.fstat(staging.fileno())
        staging_named = os.stat(
            RECEIPT_STAGE_NAME,
            dir_fd=directory_fd,
            follow_symlinks=False,
        )
        inventory = set(os.listdir(directory_fd))
        if RECEIPT_NAME in inventory:
            raise FileExistsError(
                errno.EEXIST,
                os.strerror(errno.EEXIST),
                RECEIPT_NAME,
            )
        if (
            inventory != expected_log_names | {RECEIPT_STAGE_NAME}
            or not stat.S_ISREG(staging_metadata.st_mode)
            or not stat.S_ISREG(staging_named.st_mode)
            or stat.S_IMODE(staging_metadata.st_mode) != 0o600
            or stat.S_IMODE(staging_named.st_mode) != 0o600
            or staging_metadata.st_uid != os.geteuid()
            or staging_named.st_uid != os.geteuid()
            or staging_metadata.st_gid != os.getegid()
            or staging_named.st_gid != os.getegid()
            or staging_metadata.st_nlink != 1
            or staging_named.st_nlink != 1
            or _published_file_identity(staging_metadata)
            != staging_identity
            or _published_file_identity(staging_named) != staging_identity
            or _pread_all(staging.fileno(), staging_metadata.st_size)
            != content
        ):
            raise GateError("receipt staging authority changed before publish")
        for retained in retained_logs.values():
            retained.verify(event)
        event.verify()

    try:
        event.verify()
        if set(os.listdir(directory_fd)) != expected_log_names:
            raise GateError("gate event directory inventory differs before receipt")
        for retained in retained_logs.values():
            retained.verify(event)
        staging = _open_private_exclusive_at(
            directory_fd,
            RECEIPT_STAGE_NAME,
        )
        _write_all(staging.fileno(), content, writer)
        try:
            file_fsync(staging.fileno())
        except Exception as exc:
            raise GateError("receipt staging fsync failed") from exc
        staging_metadata = os.fstat(staging.fileno())
        staging_identity = _published_file_identity(staging_metadata)
        staged_named = os.stat(
            RECEIPT_STAGE_NAME,
            dir_fd=directory_fd,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISREG(staging_metadata.st_mode)
            or stat.S_IMODE(staging_metadata.st_mode) != 0o600
            or staging_metadata.st_uid != os.geteuid()
            or staging_metadata.st_nlink != 1
            or _published_file_identity(staged_named) != staging_identity
            or _pread_all(staging.fileno(), staging_metadata.st_size) != content
        ):
            raise GateError("receipt staging bytes differ")
        prepublish_inventory = set(os.listdir(directory_fd))
        expected_prepublish_inventory = expected_log_names | {
            RECEIPT_STAGE_NAME
        }
        if (
            not expected_prepublish_inventory.issubset(prepublish_inventory)
            or prepublish_inventory
            - expected_prepublish_inventory
            - {RECEIPT_NAME}
        ):
            raise GateError("gate event directory inventory differs before publish")
        before_publish()
        require_exact_prepublish_evidence()
        final_cas()
        require_exact_prepublish_evidence()
        try:
            _rename_noreplace_at(
                directory_fd,
                RECEIPT_STAGE_NAME,
                RECEIPT_NAME,
            )
            receipt_published = True
            on_published()
        except BaseException as exc:
            if not receipt_published and staging_identity is not None:
                try:
                    possibly_published = os.stat(
                        RECEIPT_NAME,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                except OSError:
                    receipt_published = False
                else:
                    try:
                        os.stat(
                            RECEIPT_STAGE_NAME,
                            dir_fd=directory_fd,
                            follow_symlinks=False,
                        )
                    except FileNotFoundError:
                        receipt_published = (
                            _published_file_identity(possibly_published)
                            == staging_identity
                        )
                    except OSError:
                        receipt_published = False
                    else:
                        receipt_published = False
            if receipt_published:
                try:
                    on_published()
                except BaseException:
                    pass
                raise GatePostCommitUncertain(
                    "receipt rename completed but publication return is uncertain"
                ) from exc
            raise
        try:
            directory_fsync(directory_fd)
            published_metadata = os.fstat(staging.fileno())
            published_named = os.stat(
                RECEIPT_NAME,
                dir_fd=directory_fd,
                follow_symlinks=False,
            )
            if (
                staging_identity is None
                or _published_file_identity(published_metadata)
                != staging_identity
                or _published_file_identity(published_named)
                != staging_identity
                or published_metadata.st_nlink != 1
                or _pread_all(staging.fileno(), published_metadata.st_size)
                != content
            ):
                raise GateError("published receipt authority or bytes differ")
            if (
                set(os.listdir(directory_fd))
                != expected_log_names | {RECEIPT_NAME}
            ):
                raise GateError(
                    "gate event directory inventory differs after receipt"
                )
            after_publish()
            final_receipt_metadata = os.fstat(staging.fileno())
            final_receipt_named = os.stat(
                RECEIPT_NAME,
                dir_fd=directory_fd,
                follow_symlinks=False,
            )
            if (
                _published_file_identity(final_receipt_metadata)
                != staging_identity
                or _published_file_identity(final_receipt_named)
                != staging_identity
                or final_receipt_metadata.st_nlink != 1
                or _pread_all(
                    staging.fileno(),
                    final_receipt_metadata.st_size,
                )
                != content
                or set(os.listdir(directory_fd))
                != expected_log_names | {RECEIPT_NAME}
            ):
                raise GateError("retained published receipt changed")
            for retained in retained_logs.values():
                retained.verify(event)
            event.verify()
        except BaseException as exc:
            raise GatePostCommitUncertain(
                "receipt published but directory durability or verification "
                "is uncertain"
            ) from exc
    except BaseException as exc:
        primary = exc
        raise
    finally:
        close_error: BaseException | None = None
        if staging is not None:
            try:
                staging.close()
            except BaseException as exc:
                if close_error is None:
                    close_error = exc
        if primary is None and close_error is not None:
            if receipt_published:
                raise GatePostCommitUncertain(
                    "published receipt retained descriptor cleanup failed"
                ) from close_error
            raise GateError("receipt retained descriptor cleanup failed") from close_error


def _parse_timestamp(value: Any, *, label: str) -> datetime:
    if not isinstance(value, str):
        raise GateError(f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise GateError(f"{label} is not valid ISO-8601") from exc
    if parsed.utcoffset() is None:
        raise GateError(f"{label} must include a timezone")
    return parsed


def _load_checkpoint(
    root: Path,
    *,
    retained_content: bytes | None = None,
) -> tuple[bytes, dict[str, Any]]:
    content = retained_content
    if content is None:
        path = repo_file(root, CHECKPOINT_RELATIVE)
        content = _private_file_bytes(
            path,
            allow_empty=False,
            maximum_bytes=CHECKPOINT_MAX_BYTES,
        )
    elif not content or len(content) > CHECKPOINT_MAX_BYTES:
        raise GateError("retained checkpoint byte size differs")
    try:
        checkpoint = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("checkpoint is not valid JSON") from exc
    if not isinstance(checkpoint, dict):
        raise GateError("checkpoint root must be an object")
    return content, checkpoint


def _validate_ready_source(
    checkpoint: dict[str, Any],
    *,
    contract_binding: dict[str, Any],
) -> tuple[str, datetime]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(state, dict) or not isinstance(history, list):
        raise GateError("checkpoint goal execution is missing")
    if len(history) != SOURCE_SEQUENCE:
        raise GateError("FP008 gate requires the exact seq46 source")
    materialized = history[-2] if len(history) >= 2 else None
    ready = history[-1] if history else None
    if not isinstance(materialized, dict) or not isinstance(ready, dict):
        raise GateError("FP008 seq45/46 events are missing")
    if (
        materialized.get("sequence") != 45
        or materialized.get("event_id") != MATERIALIZED_EVENT_ID
        or materialized.get("event_type") != "GOAL_MATERIALIZED"
        or materialized.get("materialized_goal_id") != TARGET_GOAL_ID
        or materialized.get("to_status") != "PLANNED"
        or materialized.get("event_sha256") != event_sha256(materialized)
    ):
        raise GateError("FP008 seq45 materialization event differs")
    if (
        ready.get("sequence") != SOURCE_SEQUENCE
        or ready.get("event_id") != READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or ready.get("from_status") != "PLANNED"
        or ready.get("to_status") != "READY"
        or ready.get("previous_event_sha256") != materialized.get("event_sha256")
        or ready.get("event_sha256") != event_sha256(ready)
    ):
        raise GateError("FP008 seq46 readiness event differs")
    ready_sha256 = ready["event_sha256"]
    if ready_sha256 != SOURCE_READY_EVENT_SHA256:
        raise GateError("FP008 seq46 READY event SHA-256 trust anchor differs")
    if state.get("transition_history_anchor_sha256") != ready_sha256:
        raise GateError("FP008 seq46 history anchor differs")
    if (
        state.get("package_id") != PACKAGE_ID
        or state.get("package_status") != "ACTIVE"
        or state.get("activation_status") != "ACTIVE"
        or state.get("focus_goal_id") != TARGET_GOAL_ID
        or state.get("focus_goal_path") != GOAL_RELATIVE.as_posix()
        or state.get("focus_work_item_id") != WORK_ITEM_ID
        or state.get("focus_source") != "IMPLEMENTATION_BACKLOG"
        or state.get("ready_frontier_goal_ids") != list(READY_FRONTIER)
    ):
        raise GateError("FP008 seq46 active focus differs")
    statuses = state.get("status_by_goal")
    if (
        not isinstance(statuses, dict)
        or statuses.get(TARGET_GOAL_ID) != "READY"
        or statuses.get(PREDECESSOR_GOAL_ID) != "COMPLETE_AT_TARGET"
        or "IN_PROGRESS" in statuses.values()
    ):
        raise GateError("FP008 seq46 Goal statuses differ")
    blockers = state.get("blockers_by_goal")
    if (
        state.get("blocked_goal_ids") != []
        or state.get("pending_questions") != []
        or state.get("open_question_count") != 0
        or (isinstance(blockers, dict) and blockers.get(TARGET_GOAL_ID))
    ):
        raise GateError("FP008 seq46 has an unresolved blocker or question")
    inventory = state.get("dynamic_goal_inventory")
    goal = inventory.get(TARGET_GOAL_ID) if isinstance(inventory, dict) else None
    if (
        not isinstance(goal, dict)
        or goal.get("goal_id") != TARGET_GOAL_ID
        or goal.get("path") != GOAL_RELATIVE.as_posix()
        or goal.get("sha256") != TARGET_GOAL_SHA256
        or goal.get("materialized_event_sha256")
        != materialized.get("event_sha256")
        or goal.get("predecessor_goal_id") != PREDECESSOR_GOAL_ID
    ):
        raise GateError("FP008 dynamic Goal inventory differs")
    children = state.get("materialized_child_goal_ids_by_parent")
    parent_children = children.get(PARENT_GOAL_ID) if isinstance(children, dict) else None
    if not isinstance(parent_children, list) or parent_children.count(TARGET_GOAL_ID) != 1:
        raise GateError("FP008 parent/child materialization differs")
    if any(
        isinstance(event, dict)
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        for event in history
    ):
        raise GateError("FP008 has already been started")
    current = checkpoint.get("current_work")
    if (
        not isinstance(current, dict)
        or current.get("work_item_id") != WORK_ITEM_ID
        or current.get("current_focus")
        != "FP008/GAP-017 Goal READY; active internal start gate not run"
        or current.get("release_completion_claimed") is not False
    ):
        raise GateError("FP008 current-work READY pointer differs")
    binding = ready.get("implementation_start_gate_contract_binding")
    if binding != contract_binding:
        raise GateError("FP008 seq46 implementation-start contract binding differs")
    occurred_at = _parse_timestamp(
        ready.get("occurred_at"), label="FP008 seq46 occurred_at"
    )
    return ready_sha256, occurred_at


def load_gate_context(
    root: Path,
    retained_contents: Mapping[Path, bytes] | None = None,
    *,
    require_live_snapshot: bool = True,
) -> GateContext:
    del require_live_snapshot
    root = root.resolve(strict=True)
    if retained_contents is not None:
        if set(retained_contents) != set(SOURCE_GUARD_RELATIVES):
            raise GateError("retained source membership differs")
    checks, contract = _load_gate_contract(
        root,
        retained_content=(
            retained_contents[CONTRACT_RELATIVE]
            if retained_contents is not None
            else None
        ),
    )
    contract_binding = expected_contract_binding()
    if contract_binding["canonical_contract_sha256"] != canonical_sha256(contract):
        raise GateError("FP008 event contract binding canonical SHA-256 differs")
    checkpoint_bytes, checkpoint = _load_checkpoint(
        root,
        retained_content=(
            retained_contents[CHECKPOINT_RELATIVE]
            if retained_contents is not None
            else None
        ),
    )
    ready_sha256, ready_occurred_at = _validate_ready_source(
        checkpoint,
        contract_binding=contract_binding,
    )

    manifest_path_value = checkpoint["goal_execution"].get(
        "static_plan_manifest_path"
    )
    if manifest_path_value != MANIFEST_RELATIVE.as_posix():
        raise GateError("checkpoint v2.4 manifest path differs")
    if (
        sha256_bytes(
            retained_contents[MANIFEST_RELATIVE]
            if retained_contents is not None
            else repo_file(root, MANIFEST_RELATIVE).read_bytes()
        )
        != MANIFEST_SHA256
        or checkpoint["goal_execution"].get("static_plan_manifest_sha256")
        != MANIFEST_SHA256
    ):
        raise GateError("v2.4 manifest SHA-256 differs")

    goal_sha256 = sha256_bytes(
        retained_contents[GOAL_RELATIVE]
        if retained_contents is not None
        else repo_file(root, GOAL_RELATIVE).read_bytes()
    )
    if goal_sha256 != TARGET_GOAL_SHA256:
        raise GateError("FP008 Goal SHA-256 differs")
    if retained_contents is None:
        repo_file(root, ROOT_CONTROL_TEST_RELATIVE)
    else:
        retained_contents[ROOT_CONTROL_TEST_RELATIVE]

    history = checkpoint["goal_execution"]["transition_history"]
    activations = [
        event
        for event in history
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("static_plan_manifest_sha256") == MANIFEST_SHA256
    ]
    if len(activations) != 1:
        raise GateError("v2.4 source activation event is missing or ambiguous")
    activation_sha256 = activations[0].get("event_sha256")
    if not isinstance(activation_sha256, str) or not SHA256_RE.fullmatch(
        activation_sha256
    ):
        raise GateError("v2.4 source activation event SHA-256 is invalid")

    runtime_bindings = tuple(
        (
            relative.as_posix(),
            sha256_bytes(
                retained_contents[relative]
                if retained_contents is not None
                else repo_file(root, relative).read_bytes()
            ),
        )
        for relative in RUNTIME_BINDING_RELATIVES
    )
    return GateContext(
        checks=checks,
        checkpoint_sha256=sha256_bytes(checkpoint_bytes),
        target_goal_sha256=goal_sha256,
        source_activation_event_sha256=activation_sha256,
        source_ready_event_sha256=ready_sha256,
        source_ready_occurred_at=ready_occurred_at,
        contract_binding=contract_binding,
        runtime_bindings=runtime_bindings,
    )


def repository_snapshot_from_payload(
    payload: dict[str, Any],
    *,
    event_id: str,
    output_sha256: str,
) -> dict[str, Any]:
    if (
        payload.get("schema_version") != "1.0.0"
        or payload.get("evidence_type") != "GATE_REPOSITORY_STATE"
        or payload.get("gate_event_id") != event_id
    ):
        raise GateError("REPOSITORY_STATE output identity differs")
    repository = payload.get("repository")
    raw = payload.get("git_status_raw")
    dirty = payload.get("dirty_snapshot")
    controlled = payload.get("checkpoint_controlled_working_snapshot")
    exclusions = payload.get("transaction_exclusions")
    if not all(
        isinstance(value, dict)
        for value in (repository, raw, dirty, controlled, exclusions)
    ):
        raise GateError("REPOSITORY_STATE output structure differs")
    assert isinstance(repository, dict)
    assert isinstance(raw, dict)
    assert isinstance(dirty, dict)
    assert isinstance(controlled, dict)
    assert isinstance(exclusions, dict)
    expected_prefix = (
        GATE_ROOT_RELATIVE / event_id
    ).as_posix() + "/"
    if (
        raw.get("scope") != SNAPSHOT_SCOPE
        or exclusions.get("allowed_rule_count") != 2
        or exclusions.get("checkpoint_exact_path")
        != CHECKPOINT_RELATIVE.as_posix()
        or exclusions.get("gate_event_exact_prefix") != expected_prefix
    ):
        raise GateError("REPOSITORY_STATE transaction exclusions differ")
    hash_values = (
        raw.get("sha256"),
        dirty.get("path_set_sha256"),
        dirty.get("content_set_sha256"),
        dirty.get("index_state_sha256"),
        controlled.get("path_set_sha256"),
        controlled.get("content_set_sha256"),
    )
    if any(not isinstance(value, str) or not SHA256_RE.fullmatch(value) for value in hash_values):
        raise GateError("REPOSITORY_STATE SHA-256 field differs")
    count_values = (
        raw.get("byte_count"),
        raw.get("record_count"),
        dirty.get("dirty_path_count"),
        controlled.get("managed_changed_path_count"),
    )
    if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in count_values):
        raise GateError("REPOSITORY_STATE count field differs")
    for field in ("head_commit", "branch", "object_format"):
        if not isinstance(repository.get(field), str) or not repository[field]:
            raise GateError(f"REPOSITORY_STATE repository {field} differs")
    return {
        "gate_event_id": event_id,
        "snapshot_scope": SNAPSHOT_SCOPE,
        "head_commit": repository["head_commit"],
        "branch": repository["branch"],
        "object_format": repository["object_format"],
        "git_status_raw_sha256": raw["sha256"],
        "git_status_raw_byte_count": raw["byte_count"],
        "git_status_raw_record_count": raw["record_count"],
        "dirty_path_count": dirty["dirty_path_count"],
        "path_set_sha256": dirty["path_set_sha256"],
        "content_set_sha256": dirty["content_set_sha256"],
        "index_state_sha256": dirty["index_state_sha256"],
        "checkpoint_base_head": controlled.get("base_head"),
        "checkpoint_managed_path_count": controlled[
            "managed_changed_path_count"
        ],
        "checkpoint_path_set_sha256": controlled["path_set_sha256"],
        "checkpoint_content_set_sha256": controlled["content_set_sha256"],
        "gate_repository_state_output_sha256": output_sha256,
    }


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None:
        raise GateError(
            "event ID must match WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
            "FP008-YYYYMMDD-NNN"
        )
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP008-"
        f"{match.group(1)}-{match.group(2)}"
    )


def _sanitized_environment(event_id: str) -> dict[str, str]:
    environment = {
        "PATH": "/usr/bin:/bin",
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PYTHONDONTWRITEBYTECODE": "1",
        "WALKSAFE_GATE_EVENT_ID": event_id,
    }
    for name in (
        "HOME",
        "TMPDIR",
        "JAVA_HOME",
        "ANDROID_HOME",
        "ANDROID_SDK_ROOT",
        "GRADLE_USER_HOME",
        "WALKSAFE_TEST_DATABASE_URL",
    ):
        value = os.environ.get(name)
        if value:
            environment[name] = value
    return environment


def capture_repository_state(
    root: Path,
    checkpoint_path: Path,
    event_id: str,
) -> dict[str, Any]:
    from scripts import check_walksafe_project_continuation_v2_4 as continuation

    return continuation.capture_gate_repository_state(
        root,
        checkpoint_path,
        event_id,
    )


@contextmanager
def _checkpoint_parent_shared_lock(root: Path):
    retained = _RetainedCheckpointParentLock.capture(root.resolve(strict=True))
    primary: BaseException | None = None
    try:
        yield retained
    except BaseException as exc:
        primary = exc
        raise
    finally:
        retained.close(primary)


def _require_prepublish_source_exact(resources: _GateRunResources) -> None:
    try:
        resources.verify_source()
    except _RepositoryAuthorityChanged as exc:
        raise GateError(
            "FP008 repository changed before receipt publication"
        ) from exc
    except (GateError, OSError, ValueError) as exc:
        raise GateError(
            "FP008 seq46 source changed before receipt publication"
        ) from exc


def _require_prepublish_control_source_exact(
    resources: _GateRunResources,
) -> None:
    try:
        resources.checkpoint_lock.verify()
        resources.source_guard.verify()
        resources.checkpoint_lock.verify()
    except (GateError, OSError, ValueError) as exc:
        raise GateError(
            "FP008 seq46 source changed before receipt publication"
        ) from exc


def _require_prepublish_repository_exact(
    resources: _GateRunResources,
    root: Path,
    event_id: str,
    repository_state_guard: Callable[[Path, Path, str], dict[str, Any]],
    authority: _RepositoryStateAuthority,
) -> None:
    try:
        prepublish_repository_payload = resources.repository_guard.capture_state(
            root / CHECKPOINT_RELATIVE,
            event_id,
            capture=repository_state_guard,
        )
    except _RepositoryAuthorityChanged as exc:
        raise GateError(
            "FP008 repository changed before receipt publication"
        ) from exc
    except Exception as exc:
        raise GateError("FP008 repository prepublish guard failed") from exc
    try:
        authority.require_exact(
            prepublish_repository_payload,
            label="FP008 repository before receipt publication",
        )
    except GateError as exc:
        raise GateError(
            "FP008 repository changed before receipt publication"
        ) from exc
    _require_prepublish_source_exact(resources)
    try:
        resources.repository_guard.verify()
    except (GateError, OSError, ValueError) as exc:
        raise GateError(
            "FP008 repository authority changed before receipt publication"
        ) from exc


def _run_gate_locked(
    root: Path,
    event_id: str,
    *,
    resources: _GateRunResources,
    process_runner: Callable[..., subprocess.CompletedProcess[Any]] = (
        subprocess.run
    ),
    repository_state_guard: Callable[
        [Path, Path, str], dict[str, Any]
    ] = capture_repository_state,
    isolated_snapshot_factory: Callable[
        [
            Path,
            str,
            _RepositoryStateAuthority | None,
            RetainedRepositoryAuthorityGuard,
            Callable[[Path, Path, str], dict[str, Any]],
        ],
        _RetainedIsolatedRepositorySnapshot,
    ]
    | None = None,
    receipt_writer: Callable[[int, bytes], int] = os.write,
    receipt_file_fsync: Callable[[int], None] = os.fsync,
    receipt_directory_fsync: Callable[[int], None] = os.fsync,
    clock: Callable[[], datetime] = lambda: datetime.now(
        ZoneInfo("Asia/Seoul")
    ),
) -> Path:
    document_id = _document_id(event_id)
    root = root.resolve(strict=True)
    resources.verify_source()
    context = load_gate_context(root, resources.source_guard.contents)
    resources.verify_source()
    resources.event = _RetainedFreshEventDirectory.create(root, event_id)
    event = resources.event
    event_dir = event.event_dir
    event_identity = event.event_identity

    environment = _sanitized_environment(event_id)
    environment.update(
        {
            "PATH": (
                os.fspath(resources.repository_guard.workspace / "commands")
                + ":/usr/bin:/bin"
            ),
            "HOME": os.fspath(resources.repository_guard.workspace / "home"),
            "LANG": "C.UTF-8",
            "LC_ALL": "C",
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_OPTIONAL_LOCKS": "0",
            "TMPDIR": os.fspath(
                resources.repository_guard.workspace / "runtime" / "tmp"
            ),
        }
    )
    previous_time: datetime | None = None

    def timestamp(*, second_precision: bool = False) -> str:
        nonlocal previous_time
        value = clock()
        if value.tzinfo is None or value.utcoffset() is None:
            raise GateError("gate clock must return a timezone-aware datetime")
        if second_precision:
            value = value.replace(microsecond=0)
            if previous_time is not None and value <= previous_time:
                value = previous_time.replace(microsecond=0)
                if value <= previous_time:
                    value += timedelta(seconds=1)
        elif previous_time is not None and value <= previous_time:
            value = previous_time + timedelta(microseconds=1)
        previous_time = value
        return value.isoformat()

    started_at = timestamp(second_precision=True)
    if datetime.fromisoformat(started_at) < context.source_ready_occurred_at:
        raise GateError("FP008 gate execution precedes seq46 readiness")
    production_repository_authority = (
        repository_state_guard is capture_repository_state
    )
    authority: _RepositoryStateAuthority | None = None
    if production_repository_authority:
        authority = _RepositoryStateAuthority.capture(
            resources.repository_guard,
            root / CHECKPOINT_RELATIVE,
            event_id,
            repository_state_guard,
        )
        resources.repository_authority = authority
    snapshot_factory = isolated_snapshot_factory
    if snapshot_factory is None:
        snapshot_factory = (
            _RetainedIsolatedRepositorySnapshot.capture
            if production_repository_authority
            else _RetainedIsolatedRepositorySnapshot.capture_injected_test_snapshot
        )
    resources.snapshot = snapshot_factory(
        root,
        event_id,
        authority,
        resources.repository_guard,
        repository_state_guard,
    )
    snapshot = resources.snapshot
    resources.verify_all()
    check_runs: list[dict[str, Any]] = []
    for index, (check_id, command) in enumerate(context.checks, start=1):
        resources.verify_source()
        snapshot.verify()
        event.verify()
        output_name = f"{index:02d}-{check_id}.log"
        output_relative = (
            GATE_ROOT_RELATIVE / event_id / output_name
        )
        executed_at = timestamp()
        runner_error: Exception | None = None
        output = _open_private_exclusive_at(event.event_fd, output_name)
        try:
            try:
                result = process_runner(
                    command,
                    shell=True,
                    executable="/bin/bash",
                    cwd=Path(f"/proc/self/fd/{snapshot.root_descriptor}"),
                    pass_fds=(snapshot.root_descriptor,),
                    env=environment,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                    check=False,
                )
            except Exception as exc:  # preserve the unique attempt and its log
                runner_error = exc
                result = subprocess.CompletedProcess(command, 1)
            if (
                int(result.returncode) == 0
                and output.tell() == 0
                and check_id == "TEST_LAYER_REGISTRY_VALIDATE"
            ):
                output.write(b"TEST_LAYER_REGISTRY_VALIDATE: PASS\n")
            output.flush()
            os.fsync(output.fileno())
            event.verify()
            os.fsync(event.event_fd)
            retained = _RetainedGateLog.capture(
                event,
                output_name,
                output,
                allow_empty=(
                    int(result.returncode) != 0 or runner_error is not None
                ),
            )
            resources.logs[output_name] = retained
            content = retained.content
            output = None
        finally:
            if output is not None:
                output.close()
        resources.verify_source()
        snapshot.verify()
        event.verify()
        if runner_error is not None:
            raise GateError(f"{check_id} runner raised an exception") from runner_error
        exit_code = int(result.returncode)
        if exit_code != 0:
            raise GateCheckFailed(
                check_id,
                exit_code,
                output_relative.as_posix(),
            )
        check_runs.append(
            {
                "check_id": check_id,
                "command": command,
                "output_path": output_relative.as_posix(),
                "output_sha256": sha256_bytes(content),
                "exit_code": 0,
                "executed_at": executed_at,
            }
        )

    ended_at = timestamp(second_precision=True)
    repository_run = check_runs[-1]
    if repository_run["check_id"] != "REPOSITORY_STATE":
        raise GateError("REPOSITORY_STATE must be the final check")
    repository_name = Path(repository_run["output_path"]).name
    resources.logs[repository_name].verify(event)
    repository_bytes = resources.logs[repository_name].content
    try:
        repository_payload = json.loads(repository_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("REPOSITORY_STATE output is not valid JSON") from exc
    if not isinstance(repository_payload, dict):
        raise GateError("REPOSITORY_STATE output root is not an object")
    if authority is None:
        authority = _RepositoryStateAuthority.from_payload(
            repository_payload,
            strict_cli_output=False,
        )
        resources.repository_authority = authority
        snapshot.authority = authority
    authority.require_exact(
        repository_payload,
        label="REPOSITORY_STATE output",
    )
    if authority.strict_cli_output and repository_bytes != authority.cli_output:
        raise GateError(
            "REPOSITORY_STATE output bytes differ from captured repository authority"
        )
    if production_repository_authority:
        snapshot.verify_repository()
    repository_snapshot = repository_snapshot_from_payload(
        repository_payload,
        event_id=event_id,
        output_sha256=repository_run["output_sha256"],
    )

    resources.verify_source()
    if (
        load_gate_context(
            root,
            resources.source_guard.contents,
            require_live_snapshot=False,
        )
        != context
    ):
        raise GateError("FP008 seq46 source changed during gate execution")
    resources.verify_source()
    for run in check_runs:
        retained = resources.logs[Path(run["output_path"]).name]
        retained.verify(event)
        content = retained.content
        if sha256_bytes(content) != run["output_sha256"]:
            raise GateError(f"{run['check_id']} output changed before receipt")
    event.verify()
    try:
        guarded_repository_payload = resources.repository_guard.capture_state(
            root / CHECKPOINT_RELATIVE,
            event_id,
            capture=repository_state_guard,
        )
    except Exception as exc:
        raise GateError("FP008 repository commit guard failed") from exc
    try:
        authority.require_exact(
            guarded_repository_payload,
            label="FP008 repository after REPOSITORY_STATE",
        )
    except GateError as exc:
        raise GateError("FP008 repository changed after REPOSITORY_STATE") from exc
    try:
        guarded_context = load_gate_context(
            root,
            resources.source_guard.contents,
            require_live_snapshot=False,
        )
    except (GateError, OSError, ValueError) as exc:
        raise GateError(
            "FP008 seq46 source changed during repository commit guard"
        ) from exc
    if guarded_context != context:
        raise GateError(
            "FP008 seq46 source changed during repository commit guard"
        )
    for run in check_runs:
        retained = resources.logs[Path(run["output_path"]).name]
        retained.verify(event)
        content = retained.content
        if sha256_bytes(content) != run["output_sha256"]:
            raise GateError(f"{run['check_id']} output changed during commit guard")
    try:
        resources.verify_source()
    except (GateError, OSError, ValueError) as exc:
        raise GateError(
            "FP008 seq46 source changed during repository commit guard"
        ) from exc
    event.verify()

    generated_at = timestamp(second_precision=True)
    receipt = {
        "schema_version": "1.1",
        "document_id": document_id,
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": GATE_PURPOSE,
        "status": "PASS",
        "package_id": PACKAGE_ID,
        "target_transition_event_id": event_id,
        "target_goal_id": TARGET_GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "source_activation_event_sha256": (
            context.source_activation_event_sha256
        ),
        "source_checkpoint_sha256": context.checkpoint_sha256,
        "source_ready_event_sha256": context.source_ready_event_sha256,
        "check_command_contract_version": CONTRACT_VERSION,
        "check_command_contract_sha256": CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": copy.deepcopy(
            context.contract_binding
        ),
        "runtime_bindings": [
            {"path": path, "file_sha256": digest}
            for path, digest in context.runtime_bindings
        ],
        "execution_window": {
            "started_at": started_at,
            "ended_at": ended_at,
        },
        "check_runs": check_runs,
        "repository_snapshot": repository_snapshot,
        "generated_at": generated_at,
    }
    if set(receipt) != RECEIPT_FIELDS:
        raise GateError("implementation-start receipt field set differs")
    receipt_bytes = (
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")
    expected_log_names = {
        Path(run["output_path"]).name for run in check_runs
    }
    if set(resources.logs) != expected_log_names:
        raise GateError("retained gate log membership differs")

    def require_prepublish_commit_guard() -> None:
        if production_repository_authority:
            snapshot.verify_repository()
        _require_prepublish_control_source_exact(resources)
        event.verify()
        try:
            prepublish_context = load_gate_context(
                root,
                resources.source_guard.contents,
                require_live_snapshot=False,
            )
        except (GateError, OSError, ValueError) as exc:
            raise GateError(
                "FP008 seq46 source changed before receipt publication"
            ) from exc
        if prepublish_context != context:
            raise GateError(
                "FP008 seq46 source changed before receipt publication"
            )
        for run in check_runs:
            retained = resources.logs[Path(run["output_path"]).name]
            retained.verify(event)
            content = retained.content
            if sha256_bytes(content) != run["output_sha256"]:
                raise GateError(
                    f"{run['check_id']} output changed before receipt publication"
                )
        event.verify()
        _require_prepublish_control_source_exact(resources)
        _require_prepublish_repository_exact(
            resources,
            root,
            event_id,
            repository_state_guard,
            authority,
        )

    _publish_private_receipt(
        event,
        receipt_bytes,
        retained_logs=resources.logs,
        writer=receipt_writer,
        file_fsync=receipt_file_fsync,
        directory_fsync=receipt_directory_fsync,
        before_publish=lambda: None,
        final_cas=require_prepublish_commit_guard,
        on_published=resources.mark_receipt_published,
        after_publish=require_prepublish_commit_guard,
    )
    try:
        resources.verify_source()
        event.verify()
        for retained in resources.logs.values():
            retained.verify(event)
    except GatePostCommitUncertain:
        raise
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published receipt terminal verification failed"
        ) from exc
    receipt_path = event_dir / RECEIPT_NAME
    return receipt_path


def run_gate(
    root: Path,
    event_id: str,
    *,
    process_runner: Callable[..., subprocess.CompletedProcess[Any]] = (
        subprocess.run
    ),
    repository_state_guard: Callable[
        [Path, Path, str], dict[str, Any]
    ] = capture_repository_state,
    isolated_snapshot_factory: Callable[
        [
            Path,
            str,
            _RepositoryStateAuthority | None,
            RetainedRepositoryAuthorityGuard,
            Callable[[Path, Path, str], dict[str, Any]],
        ],
        _RetainedIsolatedRepositorySnapshot,
    ]
    | None = None,
    receipt_writer: Callable[[int, bytes], int] = os.write,
    receipt_file_fsync: Callable[[int], None] = os.fsync,
    receipt_directory_fsync: Callable[[int], None] = os.fsync,
    clock: Callable[[], datetime] = lambda: datetime.now(
        ZoneInfo("Asia/Seoul")
    ),
) -> Path:
    _document_id(event_id)
    root = root.resolve(strict=True)
    resources = _GateRunResources.capture(root)
    primary: BaseException | None = None
    try:
        return _run_gate_locked(
            root,
            event_id,
            resources=resources,
            process_runner=process_runner,
            repository_state_guard=repository_state_guard,
            isolated_snapshot_factory=isolated_snapshot_factory,
            receipt_writer=receipt_writer,
            receipt_file_fsync=receipt_file_fsync,
            receipt_directory_fsync=receipt_directory_fsync,
            clock=clock,
        )
    except BaseException as exc:
        primary = exc
        raise
    finally:
        resources.close(primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def _write_raw_exact(stream: Any, content: bytes) -> None:
    descriptor = stream.fileno()
    if (
        isinstance(descriptor, bool)
        or not isinstance(descriptor, int)
        or descriptor < 0
    ):
        raise OSError("output stream descriptor is invalid")
    offset = 0
    while offset < len(content):
        written = os.write(descriptor, content[offset:])
        if (
            isinstance(written, bool)
            or not isinstance(written, int)
            or written <= 0
            or written > len(content) - offset
        ):
            raise OSError("raw output write made invalid progress")
        offset += written


def _write_pass_result(receipt_path: Path) -> None:
    try:
        output = f"FP-008 initial-start gate: PASS: {receipt_path}\n".encode(
            "utf-8"
        )
        _write_raw_exact(sys.stdout, output)
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published receipt PASS output delivery is uncertain"
        ) from exc


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        _write_raw_exact(sys.stderr, f"{message}\n".encode("utf-8"))
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        receipt_path = run_gate(args.root, args.event_id)
        _write_pass_result(receipt_path)
    except GateCheckFailed as exc:
        print(f"FP-008 initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        _write_postcommit_diagnostic(
            f"FP-008 initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (GateError, OSError, ValueError) as exc:
        print(f"FP-008 initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
