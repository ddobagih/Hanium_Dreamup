#!/usr/bin/env python3
"""Build the canonical r023 FP-048/GAP-057 Gap and Backlog successor.

Revision 022 is intentionally skipped because that number is occupied by a
noncanonical legacy candidate.  No revision-022 file is read by this builder.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from copy import deepcopy
import ctypes
import dataclasses
from datetime import datetime
import errno
import fcntl
import hashlib
import inspect
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import types
from typing import Any, Callable, Mapping

if __package__:
    from . import (
        build_walksafe_fp048_encryption_connection_security_incident_trace_20260802
        as fp048_trace,
    )
else:
    import build_walksafe_fp048_encryption_connection_security_incident_trace_20260802 as fp048_trace


_ARGPARSE_ARGUMENT_PARSER = argparse.ArgumentParser
_CTYPES_CDLL = ctypes.CDLL
_CTYPES_GET_ERRNO = ctypes.get_errno
_DATACLASSES_FIELDS = dataclasses.fields
_DATACLASSES_IS_DATACLASS = dataclasses.is_dataclass
_FCNTL_FLOCK = fcntl.flock
_HASHLIB_SHA256 = hashlib.sha256
_INSPECT_ISBUILTIN = inspect.isbuiltin
_INSPECT_ISCLASS = inspect.isclass
_INSPECT_ISFUNCTION = inspect.isfunction
_JSON_DUMPS = json.dumps
_JSON_LOADS = json.loads
_OS_CLOSE = os.close
_OS_FCHMOD = os.fchmod
_OS_FSENCODE = os.fsencode
_OS_FSPATH = os.fspath
_OS_FSTAT = os.fstat
_OS_FSYNC = os.fsync
_OS_GETEUID = os.geteuid
_OS_GETPID = os.getpid
_OS_LSEEK = os.lseek
_OS_MKDIR = os.mkdir
_OS_OPEN = os.open
_OS_PATH_ABSPATH = os.path.abspath
_OS_READ = os.read
_OS_STAT = os.stat
_OS_STRERROR = os.strerror
_OS_UMASK = os.umask
_OS_UNLINK = os.unlink
_OS_WRITE = os.write
_RE_COMPILE = re.compile
_RE_SUB = re.sub
_STAT_S_IMODE = stat.S_IMODE
_STAT_S_ISDIR = stat.S_ISDIR
_STAT_S_ISLNK = stat.S_ISLNK
_STAT_S_ISREG = stat.S_ISREG
_TEMPORARY_DIRECTORY = tempfile.TemporaryDirectory
_TYPES_MODULE_TYPE = types.ModuleType


def _current_runtime_dependency_attribute_bindings() -> tuple[tuple[str, Any], ...]:
    return (
        ("argparse.ArgumentParser", argparse.ArgumentParser),
        ("ctypes.CDLL", ctypes.CDLL),
        ("ctypes.get_errno", ctypes.get_errno),
        ("dataclasses.fields", dataclasses.fields),
        ("dataclasses.is_dataclass", dataclasses.is_dataclass),
        ("fcntl.flock", fcntl.flock),
        ("hashlib.sha256", hashlib.sha256),
        ("inspect.isbuiltin", inspect.isbuiltin),
        ("inspect.isclass", inspect.isclass),
        ("inspect.isfunction", inspect.isfunction),
        ("json.dumps", json.dumps),
        ("json.loads", json.loads),
        ("os.close", os.close),
        ("os.fchmod", os.fchmod),
        ("os.fsencode", os.fsencode),
        ("os.fspath", os.fspath),
        ("os.fstat", os.fstat),
        ("os.fsync", os.fsync),
        ("os.geteuid", os.geteuid),
        ("os.getpid", os.getpid),
        ("os.getuid", os.getuid),
        ("os.lseek", os.lseek),
        ("os.mkdir", os.mkdir),
        ("os.open", os.open),
        ("os.path.abspath", os.path.abspath),
        ("os.path.lexists", os.path.lexists),
        ("os.read", os.read),
        ("os.stat", os.stat),
        ("os.strerror", os.strerror),
        ("os.umask", os.umask),
        ("os.unlink", os.unlink),
        ("os.write", os.write),
        ("re.compile", re.compile),
        ("re.escape", re.escape),
        ("re.findall", re.findall),
        ("re.fullmatch", re.fullmatch),
        ("re.search", re.search),
        ("re.sub", re.sub),
        ("stat.S_IMODE", stat.S_IMODE),
        ("stat.S_ISDIR", stat.S_ISDIR),
        ("stat.S_ISLNK", stat.S_ISLNK),
        ("stat.S_ISREG", stat.S_ISREG),
        ("tempfile.TemporaryDirectory", tempfile.TemporaryDirectory),
        ("types.ModuleType", types.ModuleType),
        ("fp048_trace.base64.b64decode", fp048_trace.base64.b64decode),
        ("fp048_trace.base64.b64encode", fp048_trace.base64.b64encode),
        ("fp048_trace.ElementTree.fromstring", fp048_trace.ElementTree.fromstring),
        ("fp048_trace.subprocess.run", fp048_trace.subprocess.run),
    )


RUNTIME_DEPENDENCY_ATTRIBUTE_BINDINGS = (
    _current_runtime_dependency_attribute_bindings()
)


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
GAP_ID = "GAP-057"
POLICY_ID = "FP-048"
NEXT_POLICY_ID = "FP-008"
NEXT_GAP_ID = "GAP-017"
REVISION_022_DISPOSITION = (
    "RESERVED_BY_NONCANONICAL_LEGACY_CANDIDATE_NOT_USED_AS_INPUT"
)
PUBLICATION_CONCURRENCY_MODEL = (
    "COOPERATIVE_SINGLE_PUBLISHER_WITH_EXCLUSIVE_PARENT_DIRECTORY_FLOCK"
)

R021_GAP_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260726-r021.json"
)
R021_BACKLOG_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260726-r021.json"
)
R023_GAP_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260802-r023.json"
)
R023_GAP_MD_REL = R023_GAP_JSON_REL.with_suffix(".md")
R023_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260802-r023.json"
)
R023_BACKLOG_MD_REL = R023_BACKLOG_JSON_REL.with_suffix(".md")
GOAL_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp048-encryption-connection-security-incident-r001.md"
)
START_GATE_DIR_REL = Path(
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-20260802-005"
)
START_GATE_RECEIPT_REL = START_GATE_DIR_REL / "implementation-start-gate-receipt.json"
START_GATE_REPOSITORY_STATE_REL = START_GATE_DIR_REL / "19-REPOSITORY_STATE.log"
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
RESULT_DIR_REL = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-048-R001"
)
IMPLEMENTATION_REL = RESULT_DIR_REL / "implementation-record.json"
VERIFICATION_REL = RESULT_DIR_REL / "verification-result.json"
TRACE_RECEIPT_RELS = tuple(spec.receipt_rel for spec in fp048_trace.LANES)
BUILDER_REL = Path("scripts/build_walksafe_fp048_gap_backlog_r023_20260802.py")
BUILDER_TEST_REL = Path("tests/test_walksafe_fp048_gap_backlog_r023_20260802.py")
TRACE_BUILDER_REL = Path(
    "scripts/"
    "build_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py"
)
TRACE_BUILDER_TEST_REL = Path(
    "tests/"
    "test_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py"
)
RUNTIME_TOOLING_SOURCE_RELATIVES = (
    BUILDER_REL,
    BUILDER_TEST_REL,
    TRACE_BUILDER_REL,
    TRACE_BUILDER_TEST_REL,
)

EXPECTED_R021_FILE_SHA256 = {
    R021_GAP_REL: "f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a",
    R021_BACKLOG_REL: "bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0",
}
EXPECTED_GOAL_SHA256 = (
    "043b5a463914015a5918b885c3f69c242ece190358bd18ab1f884de46f553740"
)
EXPECTED_START_GATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-20260802-005"
)
EXPECTED_FORMAL_TEST_IDS = [f"TC-FP-048-{number:02d}" for number in range(1, 8)]
EXPECTED_VERIFICATION_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "external_tls_status": "NOT_RUN",
    "external_kms_status": "NOT_RUN",
    "external_backup_restore_status": "NOT_RUN",
    "external_security_review_status": "NOT_RUN",
    "external_legal_review_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "external_independence_claimed": False,
    "release_status": "NOT_ELIGIBLE",
}
EXPECTED_IMPLEMENTATION_BEFORE_PROJECTION_SHA256 = (
    "b9e7edaaeb47b7deb23b7af12cb52c0e468f4b53a0830505376b943c0f5cc706"
)
APPENDED_SOURCE_BINDING_NAMES = (
    "implementation_gap_r021_predecessor",
    "implementation_backlog_r021_predecessor",
    "fp048_goal",
    "fp048_start_gate",
    "fp048_gate_repository_state",
    "fp048_implementation",
    "fp048_verification",
    "fp048_r023_builder",
    "fp048_r023_builder_test",
)
OUTPUT_PATHS = (
    R023_GAP_JSON_REL,
    R023_GAP_MD_REL,
    R023_BACKLOG_JSON_REL,
    R023_BACKLOG_MD_REL,
)
TRANSACTION_JOURNAL_REL = R023_GAP_JSON_REL.with_name(
    ".walksafe-fp048-r023-publication-transaction.json"
)
TRANSACTION_JOURNAL_STAGE_REL = TRANSACTION_JOURNAL_REL.with_name(
    f".{TRANSACTION_JOURNAL_REL.name}.stage"
)
MAXIMUM_SOURCE_BYTES = 4 * 1024 * 1024
MAXIMUM_TOTAL_SOURCE_BYTES = 32 * 1024 * 1024
MAXIMUM_OUTPUT_BYTES = 4 * 1024 * 1024
MAXIMUM_JOURNAL_BYTES = 64 * 1024
SHA256_RE = _RE_COMPILE(r"^[0-9a-f]{64}$")


class BuildError(RuntimeError):
    """Raised when a source or deterministic output contract differs."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def _validate_runtime_dependency_attribute_bindings() -> None:
    current = _current_runtime_dependency_attribute_bindings()
    require(
        len(current) == len(RUNTIME_DEPENDENCY_ATTRIBUTE_BINDINGS)
        and all(
            current_name == expected_name and current_value is expected_value
            for (current_name, current_value), (expected_name, expected_value) in zip(
                current,
                RUNTIME_DEPENDENCY_ATTRIBUTE_BINDINGS,
                strict=True,
            )
        ),
        "runtime dependency attribute bindings differ",
    )


def _append_owned_descriptor(rows: list[Any], row: Any, descriptor: int) -> None:
    try:
        rows.append(row)
    except BaseException:
        _close_descriptor_preserving_primary(descriptor)
        raise


def _close_descriptor_sequence(descriptors: Any) -> BaseException | None:
    first_error: BaseException | None = None
    for descriptor in descriptors:
        try:
            _OS_CLOSE(descriptor)
        except BaseException as exc:
            if first_error is None:
                first_error = exc
    return first_error


def _close_descriptor_preserving_primary(descriptor: int) -> None:
    close_error = _close_descriptor_sequence((descriptor,))
    if close_error is not None and sys.exception() is None:
        raise close_error


def _acquire_publication_lease(parent_fd: int) -> None:
    try:
        _FCNTL_FLOCK(parent_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        raise BuildError("another cooperative r023 publisher holds the lease") from exc


def _required_open_flag(name: str) -> int:
    value = getattr(os, name, None)
    require(isinstance(value, int) and value != 0, f"required open flag is unavailable: {name}")
    return value


def object_sha256(value: Any) -> str:
    encoded = _JSON_DUMPS(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return _HASHLIB_SHA256(encoded).hexdigest()


def bytes_sha256(content: bytes) -> str:
    return _HASHLIB_SHA256(content).hexdigest()


def _identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _directory_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
    )


def _publication_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
    )


def _validate_relative(relative: Path) -> None:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and "." not in relative.parts
        and ".." not in relative.parts
        and "\\" not in relative.as_posix(),
        f"unsafe repository-relative path: {relative}",
    )


def _validate_directory_authority(info: os.stat_result, label: str) -> None:
    require(
        _STAT_S_ISDIR(info.st_mode)
        and info.st_uid == _OS_GETEUID()
        and not (_STAT_S_IMODE(info.st_mode) & 0o002),
        f"unsafe repository directory: {label}",
    )


def _validate_file_authority(
    info: os.stat_result,
    relative: Path,
    *,
    require_single_link: bool,
    expected_mode: int | None,
    maximum_bytes: int,
) -> None:
    require(
        _STAT_S_ISREG(info.st_mode)
        and info.st_uid == _OS_GETEUID()
        and not (_STAT_S_IMODE(info.st_mode) & 0o002),
        f"unsafe regular file: {relative}",
    )
    if require_single_link:
        require(info.st_nlink == 1, f"hard-linked file is forbidden: {relative}")
    if expected_mode is not None:
        require(
            _STAT_S_IMODE(info.st_mode) == expected_mode,
            f"file mode differs: {relative}",
        )
    require(
        0 <= info.st_size <= maximum_bytes,
        f"file exceeds byte limit: {relative}",
    )


class RepositorySnapshot:
    """Retain one root authority and an exact multi-file byte/identity cohort."""

    def __init__(self, root: Path):
        self.root = Path(_OS_PATH_ABSPATH(root))
        self._directory_flags = (
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_DIRECTORY")
            | _required_open_flag("O_NOFOLLOW")
        )
        self._file_flags = (
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW")
        )
        outer_descriptors: list[int] = []
        outer_links: list[tuple[int, str, tuple[int, ...], int]] = []
        try:
            descriptor = _OS_OPEN(Path(self.root.anchor), self._directory_flags)
            try:
                outer_descriptors.append(descriptor)
            except BaseException:
                _close_descriptor_preserving_primary(descriptor)
                raise
            for part in self.root.parts[1:]:
                parent_fd = descriptor
                before = _OS_STAT(part, dir_fd=parent_fd, follow_symlinks=False)
                require(
                    _STAT_S_ISDIR(before.st_mode) and not _STAT_S_ISLNK(before.st_mode),
                    f"repository root ancestor is not a directory: {self.root}",
                )
                child = _OS_OPEN(part, self._directory_flags, dir_fd=parent_fd)
                try:
                    outer_descriptors.append(child)
                except BaseException:
                    _close_descriptor_preserving_primary(child)
                    raise
                opened = _OS_FSTAT(child)
                require(
                    _directory_identity(opened) == _directory_identity(before),
                    f"repository root ancestor changed while opening: {self.root}",
                )
                outer_links.append(
                    (parent_fd, part, _directory_identity(before), child)
                )
                descriptor = child
        except BaseException:
            _close_descriptor_sequence(reversed(outer_descriptors))
            raise
        root_fd = descriptor
        try:
            opened_root = _OS_FSTAT(root_fd)
            _validate_directory_authority(opened_root, str(self.root))
        except BaseException:
            _close_descriptor_sequence(reversed(outer_descriptors))
            raise
        try:
            self._directories: dict[tuple[str, ...], int] = {(): root_fd}
            self._directory_links: dict[
                tuple[str, ...], tuple[int, str, tuple[int, ...]]
            ] = {}
            self._outer_descriptors = outer_descriptors[:-1]
            self._outer_links = outer_links
            self._root_identity = _directory_identity(opened_root)
            self._files: dict[
                Path,
                tuple[
                    int,
                    str,
                    tuple[int, ...],
                    bytes,
                    int,
                    bool,
                    int | None,
                    int,
                ],
            ] = {}
            self._total_source_bytes = 0
            self._closed = False
            self._suppress_close_errors = False
        except BaseException:
            _close_descriptor_sequence(reversed(outer_descriptors))
            raise

    def __enter__(self) -> "RepositorySnapshot":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        try:
            self.close()
        except BaseException:
            if exc_type is None:
                raise

    def _relative(self, path: Path, *, allow_empty: bool = False) -> Path:
        if not path.is_absolute():
            relative = path
        else:
            try:
                relative = Path(_OS_PATH_ABSPATH(path)).relative_to(self.root)
            except ValueError as exc:
                raise BuildError(f"path escapes repository snapshot: {path}") from exc
        if allow_empty and not relative.parts:
            return relative
        _validate_relative(relative)
        return relative

    def directory_fd(self, path: Path) -> int:
        relative = self._relative(path, allow_empty=True)
        parts: tuple[str, ...] = ()
        for part in relative.parts:
            parent_parts = parts
            parts = (*parts, part)
            if parts in self._directories:
                continue
            parent_fd = self._directories[parent_parts]
            before = _OS_STAT(part, dir_fd=parent_fd, follow_symlinks=False)
            _validate_directory_authority(before, relative.as_posix())
            child = _OS_OPEN(part, self._directory_flags, dir_fd=parent_fd)
            try:
                opened = _OS_FSTAT(child)
                require(
                    _directory_identity(opened) == _directory_identity(before),
                    f"directory changed while opening: {relative}",
                )
            except BaseException:
                _close_descriptor_preserving_primary(child)
                raise
            try:
                self._directories[parts] = child
                self._directory_links[parts] = (
                    parent_fd,
                    part,
                    _directory_identity(before),
                )
            except BaseException:
                self._directories.pop(parts, None)
                _close_descriptor_preserving_primary(child)
                raise
        return self._directories[parts]

    @property
    def process_root(self) -> Path:
        require(not self._closed, "repository snapshot is closed")
        return Path(f"/proc/{_OS_GETPID()}/fd/{self._directories[()]}")

    def lexists(self, path: Path) -> bool:
        relative = self._relative(path)
        parent_fd = self.directory_fd(relative.parent)
        try:
            _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            return False
        return True

    def read(
        self,
        path: Path,
        *,
        require_single_link: bool = True,
        expected_mode: int | None = None,
        maximum_bytes: int = MAXIMUM_SOURCE_BYTES,
    ) -> bytes:
        require(not self._closed, "repository snapshot is closed")
        relative = self._relative(path)
        cached = self._files.get(relative)
        if cached is not None:
            _parent_fd, _name, identity, content, _fd, single, mode, limit = cached
            require(
                (not require_single_link or identity[5] == 1)
                and (expected_mode is None or _STAT_S_IMODE(identity[2]) == expected_mode)
                and len(content) <= maximum_bytes
                and (not single or identity[5] == 1)
                and (mode is None or _STAT_S_IMODE(identity[2]) == mode)
                and len(content) <= limit,
                f"snapshot authority request differs: {relative}",
            )
            return content
        parent_fd = self.directory_fd(relative.parent)
        before = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        _validate_file_authority(
            before,
            relative,
            require_single_link=require_single_link,
            expected_mode=expected_mode,
            maximum_bytes=maximum_bytes,
        )
        updated_total = self._total_source_bytes + before.st_size
        require(
            updated_total <= MAXIMUM_TOTAL_SOURCE_BYTES,
            "source cohort exceeds total byte limit",
        )
        descriptor = _OS_OPEN(relative.name, self._file_flags, dir_fd=parent_fd)
        try:
            opened = _OS_FSTAT(descriptor)
            require(
                _identity(opened) == _identity(before),
                f"file changed while opening: {relative}",
            )
            content = self._read_descriptor(
                descriptor,
                relative,
                maximum_bytes=maximum_bytes,
            )
            require(
                _identity(_OS_FSTAT(descriptor)) == _identity(opened),
                f"file changed while reading: {relative}",
            )
            require(
                _identity(
                    _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
                )
                == _identity(opened),
                f"file path changed while reading: {relative}",
            )
            require(
                len(content) == before.st_size,
                f"source byte length differs: {relative}",
            )
        except BaseException:
            _close_descriptor_preserving_primary(descriptor)
            raise
        try:
            entry = (
                parent_fd,
                relative.name,
                _identity(opened),
                content,
                descriptor,
                require_single_link,
                expected_mode,
                maximum_bytes,
            )
            self._files[relative] = entry
        except BaseException:
            _close_descriptor_preserving_primary(descriptor)
            raise
        self._total_source_bytes = updated_total
        return content

    @staticmethod
    def _read_descriptor(
        descriptor: int,
        relative: Path,
        *,
        maximum_bytes: int,
    ) -> bytes:
        _OS_LSEEK(descriptor, 0, os.SEEK_SET)
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = _OS_READ(descriptor, min(1024 * 1024, maximum_bytes + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            require(total <= maximum_bytes, f"file exceeds byte limit: {relative}")
        return b"".join(chunks)

    def file_mode(self, path: Path) -> int:
        relative = self._relative(path)
        entry = self._files.get(relative)
        require(entry is not None, f"snapshot file was not captured: {relative}")
        return _STAT_S_IMODE(entry[2][2])

    def content_set_sha256(self) -> str:
        require(self._files, "source snapshot is empty")
        rows = [
            {
                "path": relative.as_posix(),
                "byte_count": len(entry[3]),
                "sha256": bytes_sha256(entry[3]),
            }
            for relative, entry in sorted(
                self._files.items(),
                key=lambda item: item[0].as_posix(),
            )
        ]
        return object_sha256(rows)

    def verify(self) -> None:
        require(not self._closed, "repository snapshot is closed")
        for relative, (
            parent_fd,
            name,
            identity,
            content,
            descriptor,
            single,
            mode,
            limit,
        ) in self._files.items():
            descriptor_info = _OS_FSTAT(descriptor)
            current = _OS_STAT(name, dir_fd=parent_fd, follow_symlinks=False)
            _validate_file_authority(
                current,
                relative,
                require_single_link=single,
                expected_mode=mode,
                maximum_bytes=limit,
            )
            require(
                _identity(descriptor_info) == identity == _identity(current),
                f"source changed after snapshot: {relative}",
            )
            require(
                self._read_descriptor(descriptor, relative, maximum_bytes=limit)
                == content,
                f"source bytes changed after snapshot: {relative}",
            )
            require(
                _identity(_OS_FSTAT(descriptor)) == identity,
                f"source changed during final verification: {relative}",
            )
        for parts, (parent_fd, name, identity) in self._directory_links.items():
            current = _OS_STAT(name, dir_fd=parent_fd, follow_symlinks=False)
            _validate_directory_authority(current, "/".join(parts))
            require(
                _directory_identity(current) == identity
                == _directory_identity(_OS_FSTAT(self._directories[parts])),
                f"retained repository directory changed: {'/'.join(parts)}",
            )
        require(
            _directory_identity(_OS_FSTAT(self._directories[()])) == self._root_identity,
            "retained repository root changed",
        )
        current_root = _OS_STAT(self.root, follow_symlinks=False)
        require(
            _directory_identity(current_root) == self._root_identity,
            "repository root path changed",
        )
        for parent_fd, name, identity, descriptor in self._outer_links:
            current = _OS_STAT(name, dir_fd=parent_fd, follow_symlinks=False)
            require(
                _directory_identity(current) == identity
                == _directory_identity(_OS_FSTAT(descriptor)),
                f"repository root ancestor changed: {name}",
            )

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        errors = (
            _close_descriptor_sequence(entry[4] for entry in self._files.values()),
            _close_descriptor_sequence(
                self._directories[parts]
                for parts in sorted(self._directories, key=len, reverse=True)
            ),
            _close_descriptor_sequence(reversed(self._outer_descriptors)),
        )
        first_error = next((error for error in errors if error is not None), None)
        if first_error is not None and not self._suppress_close_errors:
            raise first_error

    def suppress_close_errors_after_commit(self) -> None:
        self._suppress_close_errors = True


@contextmanager
def _opened_directory(
    root: Path,
    relative: Path,
    *,
    create: bool = False,
) -> Any:
    _validate_relative(relative / "placeholder" if not relative.parts else relative)
    resolved_root = root.resolve(strict=True)
    flags = (
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_DIRECTORY")
        | _required_open_flag("O_NOFOLLOW")
    )
    descriptors: list[int] = []
    root_descriptor = _OS_OPEN(resolved_root, flags)
    try:
        descriptors.append(root_descriptor)
    except BaseException:
        _close_descriptor_preserving_primary(root_descriptor)
        raise
    links: list[tuple[int, str, tuple[int, ...]]] = []
    try:
        root_info = _OS_FSTAT(descriptors[0])
        require(
            _STAT_S_ISDIR(root_info.st_mode)
            and root_info.st_uid == _OS_GETEUID()
            and not (_STAT_S_IMODE(root_info.st_mode) & 0o002),
            "repository root authority differs",
        )
        for part in relative.parts:
            parent_fd = descriptors[-1]
            try:
                before = _OS_STAT(part, dir_fd=parent_fd, follow_symlinks=False)
            except FileNotFoundError:
                require(create, f"source directory is missing: {relative}")
                _OS_MKDIR(part, 0o700, dir_fd=parent_fd)
                _OS_FSYNC(parent_fd)
                before = _OS_STAT(part, dir_fd=parent_fd, follow_symlinks=False)
            require(
                _STAT_S_ISDIR(before.st_mode)
                and not _STAT_S_ISLNK(before.st_mode)
                and before.st_uid == _OS_GETEUID()
                and not (_STAT_S_IMODE(before.st_mode) & 0o002),
                f"unsafe repository directory: {relative}",
            )
            child_fd = _OS_OPEN(part, flags, dir_fd=parent_fd)
            try:
                descriptors.append(child_fd)
            except BaseException:
                _close_descriptor_preserving_primary(child_fd)
                raise
            require(
                _directory_identity(_OS_FSTAT(child_fd)) == _directory_identity(before),
                f"directory changed while opening: {relative}",
            )
            links.append((parent_fd, part, _directory_identity(before)))
        yield descriptors[-1]
        for parent_fd, part, expected in links:
            require(
                _directory_identity(_OS_STAT(part, dir_fd=parent_fd, follow_symlinks=False))
                == expected,
                f"directory changed while in use: {relative}",
            )
        require(
            _directory_identity(_OS_FSTAT(descriptors[0])) == _directory_identity(root_info),
            "repository root changed while in use",
        )
    finally:
        close_error = _close_descriptor_sequence(reversed(descriptors))
        if close_error is not None and sys.exception() is None:
            raise close_error


def _read_relative_regular(
    root: Path,
    relative: Path,
    *,
    require_single_link: bool = True,
    expected_mode: int | None = None,
    maximum_bytes: int = MAXIMUM_SOURCE_BYTES,
) -> bytes:
    _validate_relative(relative)
    with _opened_directory(root, relative.parent) as parent_fd:
        before = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        require(
            _STAT_S_ISREG(before.st_mode)
            and not _STAT_S_ISLNK(before.st_mode)
            and before.st_uid == _OS_GETEUID()
            and not (_STAT_S_IMODE(before.st_mode) & 0o002),
            f"unsafe regular file: {relative}",
        )
        if require_single_link:
            require(before.st_nlink == 1, f"hard-linked file is forbidden: {relative}")
        if expected_mode is not None:
            require(
                _STAT_S_IMODE(before.st_mode) == expected_mode,
                f"file mode differs: {relative}",
            )
        require(
            0 <= before.st_size <= maximum_bytes,
            f"file exceeds byte limit: {relative}",
        )
        descriptor = _OS_OPEN(
            relative.name,
            os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
            dir_fd=parent_fd,
        )
        try:
            opened = _OS_FSTAT(descriptor)
            require(
                _identity(opened) == _identity(before),
                f"file changed while opening: {relative}",
            )
            chunks: list[bytes] = []
            total = 0
            while chunk := _OS_READ(
                descriptor,
                min(1024 * 1024, maximum_bytes + 1 - total),
            ):
                chunks.append(chunk)
                total += len(chunk)
                require(
                    total <= maximum_bytes,
                    f"file exceeds byte limit: {relative}",
                )
            require(
                _identity(_OS_FSTAT(descriptor)) == _identity(opened),
                f"file changed while reading: {relative}",
            )
        finally:
            _close_descriptor_preserving_primary(descriptor)
        require(
            _identity(_OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(before),
            f"file path changed while reading: {relative}",
        )
    return b"".join(chunks)


def json_text(value: Any) -> str:
    return _JSON_DUMPS(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BuildError(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise BuildError(f"non-finite JSON number is forbidden: {value}")


def json_from_bytes(content: bytes, label: str) -> dict[str, Any]:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"source is not UTF-8: {label}") from exc
    value = _JSON_LOADS(
        text,
        object_pairs_hook=_reject_duplicate_pairs,
        parse_constant=_reject_nonfinite,
    )
    require(isinstance(value, dict), f"JSON root is not an object: {label}")
    return value
def verify_seal(value: dict[str, Any], field: str, label: str) -> None:
    claimed = value.get(field)
    require(isinstance(claimed, str) and SHA256_RE.fullmatch(claimed) is not None,
            f"{label} seal is missing")
    payload = deepcopy(value)
    payload.pop(field)
    require(object_sha256(payload) == claimed, f"{label} seal differs")


def file_binding(name: str, relative: Path, content: bytes) -> dict[str, Any]:
    return {
        "name": name,
        "path": relative.as_posix(),
        "bytes": len(content),
        "sha256": bytes_sha256(content),
    }


def validate_predecessors(
    gap: dict[str, Any],
    backlog: dict[str, Any],
    source_bytes: dict[Path, bytes],
) -> None:
    for relative, expected in EXPECTED_R021_FILE_SHA256.items():
        require(
            bytes_sha256(source_bytes[relative]) == expected,
            f"canonical r021 file differs: {relative}",
        )
    verify_seal(gap, "report_content_sha256", "r021 Gap")
    verify_seal(backlog, "backlog_content_sha256", "r021 Backlog")
    require(gap.get("metadata", {}).get("version") == "0.21.0", "r021 Gap version differs")
    require(
        backlog.get("metadata", {}).get("version") == "0.21.0",
        "r021 Backlog version differs",
    )
    require(len(gap.get("source_bindings", [])) == 85, "r021 source binding count differs")
    require(
        object_sha256(gap["source_bindings"]) == gap.get("source_binding_sha256"),
        "r021 source binding seal differs",
    )
    require(len(gap.get("assessments", [])) == 68, "r021 assessment count differs")
    require(
        backlog.get("gap_report_content_sha256") == gap.get("report_content_sha256"),
        "r021 Gap/Backlog pair differs",
    )


def validate_goal_and_gate(source_bytes: dict[Path, bytes]) -> tuple[dict[str, Any], dict[str, Any]]:
    goal_bytes = source_bytes[GOAL_REL]
    require(bytes_sha256(goal_bytes) == EXPECTED_GOAL_SHA256, "FP-048 Goal hash differs")
    try:
        goal_text = goal_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError("FP-048 Goal is not UTF-8") from exc
    require(f'goal_id = "{GOAL_ID}"' in goal_text, "FP-048 Goal ID differs")
    require('source_policy_ids = ["FP-048"]' in goal_text, "FP-048 Goal policy differs")
    require('gap_ids = ["GAP-057"]' in goal_text, "FP-048 Goal Gap differs")

    receipt = json_from_bytes(
        source_bytes[START_GATE_RECEIPT_REL], START_GATE_RECEIPT_REL.as_posix()
    )
    require(receipt.get("status") == "PASS", "FP-048 start gate did not pass")
    require(receipt.get("target_goal_id") == GOAL_ID, "start gate Goal differs")
    require(
        receipt.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256,
        "start gate Goal hash differs",
    )
    require(
        receipt.get("target_transition_event_id") == EXPECTED_START_GATE_EVENT_ID,
        "start gate event differs",
    )
    checks = receipt.get("check_runs")
    require(isinstance(checks, list) and len(checks) == 19, "start gate check count differs")
    require(all(item.get("exit_code") == 0 for item in checks), "start gate contains failure")
    require(checks[-1].get("check_id") == "REPOSITORY_STATE", "start gate tail differs")

    repository_state_bytes = source_bytes[START_GATE_REPOSITORY_STATE_REL]
    repository_state_hash = bytes_sha256(repository_state_bytes)
    require(
        checks[-1].get("output_sha256") == repository_state_hash,
        "start gate repository-state log hash differs",
    )
    require(
        receipt.get("repository_snapshot", {}).get(
            "gate_repository_state_output_sha256"
        ) == repository_state_hash,
        "start gate repository snapshot binding differs",
    )
    repository_state = json_from_bytes(
        repository_state_bytes,
        START_GATE_REPOSITORY_STATE_REL.as_posix(),
    )
    require(
        repository_state.get("gate_event_id") == EXPECTED_START_GATE_EVENT_ID,
        "repository-state event differs",
    )
    return receipt, repository_state


def _parse_time(value: Any, label: str) -> datetime:
    require(isinstance(value, str), f"{label} time is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"{label} time is invalid") from exc
    require(parsed.tzinfo is not None, f"{label} time lacks timezone")
    return parsed


def _validate_exact_fp048_trace_rebuild(
    trace_module: Any,
    producer_root: Path,
    implementation: dict[str, Any],
    verification: dict[str, Any],
    source_bytes: dict[Path, bytes],
    captured_bytes: Mapping[Path, bytes],
) -> None:
    require(
        trace_module.GOAL_ID == GOAL_ID
        and trace_module.IMPLEMENTATION_REL == IMPLEMENTATION_REL
        and trace_module.VERIFICATION_REL == VERIFICATION_REL,
        "FP-048 trace producer identity differs",
    )
    expected_paths = list(trace_module.IMPLEMENTATION_PATHS)
    require(
        bool(expected_paths) and len(set(expected_paths)) == len(expected_paths),
        "FP-048 trace implementation path contract differs",
    )
    require(
        [item.get("path") for item in implementation.get("changed_artifacts", [])]
        == expected_paths,
        "FP-048 trace implementation path order differs",
    )
    expected_lane_ids = [spec.lane_id for spec in trace_module.LANES]
    checks = verification.get("checks")
    require(
        isinstance(checks, list)
        and len(checks) == 5
        and [item.get("lane_id") for item in checks] == expected_lane_ids,
        "FP-048 trace lane order differs",
    )
    for spec, check in zip(trace_module.LANES, checks, strict=True):
        require(
            check.get("command") == spec.command
            and check.get("command_sha256") == bytes_sha256(spec.command.encode())
            and check.get("output_path") == spec.log_rel.as_posix()
            and check.get("execution_event_sequence") == trace_module.EXPECTED_EXECUTION_EVENT_SEQUENCE
            and check.get("execution_event_id") == trace_module.EXPECTED_EXECUTION_EVENT_ID
            and check.get("execution_event_sha256") == trace_module.EXPECTED_EXECUTION_EVENT_SHA256,
            f"FP-048 trace lane binding differs: {spec.lane_id}",
        )
    try:
        authority = trace_module.validate_authority(producer_root)
        derived_rows = deepcopy(implementation["changed_artifacts"])
        rebuilt = trace_module.build_pre_review_outputs(
            root=producer_root,
            authority=authority,
            implementation_rows=derived_rows,
            log_snapshots={
                spec.lane_id: captured_bytes[spec.log_rel]
                for spec in trace_module.LANES
            },
        )
    except (trace_module.BuildError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        raise BuildError(f"FP-048 trace producer reconstruction failed: {exc}") from exc
    require(
        rebuilt.get(IMPLEMENTATION_REL, "").encode() == source_bytes[IMPLEMENTATION_REL],
        "FP-048 implementation differs from exact producer reconstruction",
    )
    require(
        rebuilt.get(VERIFICATION_REL, "").encode() == source_bytes[VERIFICATION_REL],
        "FP-048 verification differs from exact producer reconstruction",
    )
    manifest = verification.get("lane_receipts")
    require(
        isinstance(manifest, list)
        and [item.get("lane_id") for item in manifest] == expected_lane_ids,
        "FP-048 lane receipt manifest differs",
    )
    for spec, check, binding in zip(trace_module.LANES, checks, manifest, strict=True):
        receipt_bytes = source_bytes[spec.receipt_rel]
        expected_text = rebuilt.get(spec.receipt_rel)
        require(
            isinstance(expected_text, str) and expected_text.encode() == receipt_bytes,
            f"FP-048 lane receipt differs from exact producer reconstruction: {spec.lane_id}",
        )
        receipt_value = json_from_bytes(receipt_bytes, spec.receipt_rel.as_posix())
        verify_seal(receipt_value, "receipt_content_sha256", f"{spec.lane_id} receipt")
        require(
            receipt_value.get("lane_id") == spec.lane_id
            and receipt_value.get("status") == "PASS"
            and receipt_value.get("command_execution") == check
            and receipt_value.get("start_gate_binding")
            == implementation.get("start_gate_binding")
            and binding.get("path") == spec.receipt_rel.as_posix()
            and binding.get("sha256") == bytes_sha256(receipt_bytes)
            and binding.get("log_path") == spec.log_rel.as_posix()
            and binding.get("log_sha256") == check.get("output_sha256"),
            f"FP-048 lane receipt binding differs: {spec.lane_id}",
        )


def validate_results(
    trace_module: Any,
    producer_root: Path,
    receipt: dict[str, Any],
    source_bytes: dict[Path, bytes],
    captured_bytes: Mapping[Path, bytes],
) -> tuple[dict[str, Any], dict[str, Any]]:
    implementation = json_from_bytes(
        source_bytes[IMPLEMENTATION_REL], IMPLEMENTATION_REL.as_posix()
    )
    verification = json_from_bytes(
        source_bytes[VERIFICATION_REL], VERIFICATION_REL.as_posix()
    )
    require(implementation.get("goal_id") == GOAL_ID, "implementation Goal differs")
    require(implementation.get("kind") == "IMPLEMENTATION_RECORD", "implementation kind differs")
    require(implementation.get("status") == "PASS", "implementation did not pass")
    require(verification.get("goal_id") == GOAL_ID, "verification Goal differs")
    require(verification.get("kind") == "VERIFICATION_RESULT", "verification kind differs")
    require(verification.get("status") == "PASS", "verification did not pass")

    changed = implementation.get("changed_artifacts")
    require(isinstance(changed, list) and changed, "implementation artifacts are empty")
    paths = [item.get("path") for item in changed if isinstance(item, dict)]
    require(len(paths) == len(changed), "implementation artifact row differs")
    require(len(paths) == len(set(paths)), "implementation artifact path is duplicated")
    files: list[dict[str, Any]] = []
    forbidden_fragments = (
        "r022-candidate",
        "apps/web/",
        "/pwa/",
        "legacy",
        "submission",
        "/independent-review",
        "/review-attestation",
        "/review-subject",
        "/completion-receipt",
        "successor-r012",
    )
    for item in changed:
        relative_text = item.get("path")
        after_sha256 = item.get("after_sha256")
        require(isinstance(relative_text, str), "implementation path is invalid")
        normalized = relative_text.casefold()
        require(
            not any(fragment in normalized for fragment in forbidden_fragments),
            f"forbidden implementation source: {relative_text}",
        )
        require(
            isinstance(after_sha256, str) and SHA256_RE.fullmatch(after_sha256) is not None,
            f"implementation hash is invalid: {relative_text}",
        )
        relative = Path(relative_text)
        _validate_relative(relative)
        require(relative.as_posix() == relative_text, f"implementation path is not normalized: {relative_text}")
        require(
            relative in captured_bytes,
            f"implementation file was not captured: {relative_text}",
        )
        product_bytes = captured_bytes[relative]
        require(bytes_sha256(product_bytes) == after_sha256, f"implementation file differs: {relative_text}")
        files.append(
            {
                "path": relative_text,
                "sha256": after_sha256,
                "before_sha256": item.get("before_sha256"),
                "before_source": item.get("before_source"),
                "change_kind": item.get("change_kind"),
            }
        )
    require(
        paths == list(fp048_trace.IMPLEMENTATION_PATHS),
        "FP-048 trace implementation path order differs",
    )
    before_projection = [
        {
            "path": item.get("path"),
            "before_sha256": item.get("before_sha256"),
            "before_source": item.get("before_source"),
            "change_kind": item.get("change_kind"),
        }
        for item in changed
    ]
    require(
        object_sha256(before_projection)
        == EXPECTED_IMPLEMENTATION_BEFORE_PROJECTION_SHA256,
        "FP-048 implementation before projection differs",
    )
    content_set = object_sha256(
        [{"path": item["path"], "sha256": item["sha256"]} for item in files]
    )
    require(
        implementation.get("implementation_content_set_sha256") == content_set,
        "implementation content set differs",
    )

    checks = verification.get("checks")
    require(isinstance(checks, list) and checks, "verification checks are empty")
    for item in checks:
        require(item.get("exit_code") == 0, "verification contains failed check")
        require(
            item.get("implementation_content_set_sha256") == content_set,
            "verification check implementation binding differs",
        )
        output_text = item.get("output_path")
        output_hash = item.get("output_sha256")
        require(isinstance(output_text, str), "verification output path is invalid")
        output_normalized = output_text.casefold()
        require(
            not any(fragment in output_normalized for fragment in forbidden_fragments),
            f"forbidden verification source: {output_text}",
        )
        require(
            output_text.startswith((RESULT_DIR_REL / "logs").as_posix() + "/"),
            f"verification output is outside the FP-048 log directory: {output_text}",
        )
        output_relative = Path(output_text)
        _validate_relative(output_relative)
        require(output_relative.as_posix() == output_text, f"verification output path is not normalized: {output_text}")
        require(
            output_relative in captured_bytes,
            f"verification output was not captured: {output_text}",
        )
        output_bytes = captured_bytes[output_relative]
        require(
            isinstance(output_hash, str) and bytes_sha256(output_bytes) == output_hash,
            f"verification output differs: {output_text}",
        )

    boundary = verification.get("evidence_boundary")
    require(isinstance(boundary, dict), "verification evidence boundary is missing")
    require(
        boundary.get("formal_test_ids") == EXPECTED_FORMAL_TEST_IDS,
        "formal FP-048 test IDs differ",
    )
    for key, expected in EXPECTED_VERIFICATION_BOUNDARY.items():
        require(boundary.get(key) == expected, f"verification boundary differs: {key}")

    gate_end = _parse_time(receipt.get("execution_window", {}).get("ended_at"), "gate end")
    implementation_time = _parse_time(implementation.get("observed_at"), "implementation")
    verification_time = _parse_time(verification.get("observed_at"), "verification")
    require(gate_end <= implementation_time <= verification_time, "result time order differs")
    _validate_exact_fp048_trace_rebuild(
        trace_module,
        producer_root,
        implementation,
        verification,
        source_bytes,
        captured_bytes,
    )
    return implementation, verification


def build_snapshot(
    implementation: dict[str, Any],
    receipt: dict[str, Any],
) -> dict[str, Any]:
    files = [
        {
            "path": item["path"],
            "sha256": item["after_sha256"],
            "before_sha256": item.get("before_sha256"),
            "before_source": item.get("before_source"),
            "change_kind": item.get("change_kind"),
        }
        for item in implementation["changed_artifacts"]
    ]
    snapshot = {
        "scope_kind": "EPIC_03_FP048_GOAL_IMPLEMENTATION_PATH_SET",
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "before_state_authority": "SEQ42_START_GATE_DIRTY_SNAPSHOT_THEN_PINNED_HEAD",
        "pinned_head": receipt["repository_snapshot"]["head_commit"],
        "file_count": len(files),
        "path_set_sha256": object_sha256([item["path"] for item in files]),
        "content_set_sha256": object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in files]
        ),
        "files": files,
    }
    snapshot["snapshot_sha256"] = object_sha256(snapshot)
    return snapshot


def build_gap(
    root: Path,
    predecessor: dict[str, Any],
    predecessor_backlog: dict[str, Any],
    receipt: dict[str, Any],
    implementation: dict[str, Any],
    verification: dict[str, Any],
    source_bytes: dict[Path, bytes],
) -> dict[str, Any]:
    report = deepcopy(predecessor)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023",
        "version": "0.23.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": verification["observed_at"],
        "baseline_id": predecessor["metadata"]["baseline_id"],
        "baseline_version": predecessor["metadata"]["baseline_version"],
        "predecessor_report_id": predecessor["metadata"]["report_id"],
        "revision_022_disposition": REVISION_022_DISPOSITION,
    }
    report["purpose"] = (
        "FP-048 repository-internal encryption, key separation, rotation, access-audit "
        "and incident controls are implemented and verified. GAP-057 alone is "
        "reassessed while the other 67 r021 assessments are preserved exactly."
    )
    report["publication_concurrency_boundary"] = {
        "model": PUBLICATION_CONCURRENCY_MODEL,
        "enforcement": "held parent-directory FLOCK_EX from preflight through cleanup",
        "supported_publishers": "cooperative WalkSafe automation only",
        "unsupported_boundary": (
            "same-UID actors that ignore the held advisory lease and mutate publication "
            "basenames concurrently"
        ),
    }
    appended = [
        file_binding(name, relative, source_bytes[relative])
        for name, relative in zip(
            APPENDED_SOURCE_BINDING_NAMES,
            (
                R021_GAP_REL,
                R021_BACKLOG_REL,
                GOAL_REL,
                START_GATE_RECEIPT_REL,
                START_GATE_REPOSITORY_STATE_REL,
                IMPLEMENTATION_REL,
                VERIFICATION_REL,
                BUILDER_REL,
                BUILDER_TEST_REL,
            ),
            strict=True,
        )
    ]
    report["source_bindings"] = deepcopy(predecessor["source_bindings"]) + appended
    report["source_binding_sha256"] = object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = build_snapshot(implementation, receipt)

    evidence_id = "EVD-FP048-INTERNAL-ENCRYPTION-SECURITY-20260802"
    implementation_hash = bytes_sha256(source_bytes[IMPLEMENTATION_REL])
    verification_hash = bytes_sha256(source_bytes[VERIFICATION_REL])
    report["evidence_catalog"] = deepcopy(predecessor["evidence_catalog"]) + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": (
                "FP-048 repository-internal Android, Gateway, backend and backup "
                "encryption/key-lifecycle/access-audit/incident controls and regressions"
            ),
            "producer_goal_id": GOAL_ID,
            "result_evidence_sha256": {
                "IMPLEMENTATION_RECORD": implementation_hash,
                "VERIFICATION_RESULT": verification_hash,
            },
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_verification_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        }
    ]

    predecessor_rows = {item["gap_id"]: deepcopy(item) for item in predecessor["assessments"]}
    matches = [item for item in report["assessments"] if item.get("gap_id") == GAP_ID]
    require(len(matches) == 1, "GAP-057 assessment count differs")
    assessment = matches[0]
    require(
        assessment.get("source_policy_id") == POLICY_ID
        and assessment.get("status") == "PARTIAL",
        "GAP-057 predecessor assessment differs",
    )
    assessment["status"] = "PARTIAL"
    assessment["formal_test_status"] = "NOT_RUN"
    assessment["current_implementation_in_plain_language"] = (
        "Android 민감 로컬 상태, Gateway 영속 JSON, 서버 원본과 백업 "
        "경계에 저장 암호화·외부 열쇠 분리·회전/폐기·실패 닫힘·접근 감사를 "
        "적용하고 저장소 내부 자동 검증을 통과했다. 실제 기기·외부 "
        "TLS/KMS·분리 복원·"
        "외부 검토·운영 배포·정식 시험은 실행하지 않았다."
    )
    assessment["rationale"] = (
        "FP-048 내부 구현과 회귀는 PASS지만 TC-FP-048-01~07, 실제 기기, "
        "외부 TLS/KMS, 분리 백업 복원, 외부 보안·법률 검토, 운영 배포와 "
        "출시 Gate가 NOT_RUN이므로 PARTIAL을 유지한다."
    )
    assessment["evidence_ids"] = list(
        dict.fromkeys([*assessment.get("evidence_ids", []), evidence_id])
    )
    assessment["fp048_reassessment"] = {
        "goal_id": GOAL_ID,
        "start_gate_event_id": EXPECTED_START_GATE_EVENT_ID,
        "implementation_record_sha256": implementation_hash,
        "verification_result_sha256": verification_hash,
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        **EXPECTED_VERIFICATION_BOUNDARY,
    }
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = object_sha256(assessment)

    for row in report["assessments"]:
        if row["gap_id"] != GAP_ID:
            require(
                row == predecessor_rows[row["gap_id"]],
                f"non-target assessment changed: {row['gap_id']}",
            )
    carried_ids = [item["gap_id"] for item in report["assessments"] if item["gap_id"] != GAP_ID]
    require(len(carried_ids) == 67, "r021 carry-forward count differs")

    report["summary"] = deepcopy(predecessor["summary"])
    report["summary"]["headline"] = (
        "GAP-057 repository-internal encryption, key lifecycle, privileged access "
        "audit and incident controls are verified; formal, device, external, "
        "production and release evidence remain NOT_RUN, so PARTIAL is unchanged."
    )
    report["summary"]["release_status"] = "NOT_ELIGIBLE"
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC03_FP048_GAP057_REASSESSMENT_WITH_R021_CARRY_FORWARD",
        "directly_reassessed_gap_ids": [GAP_ID],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": [GAP_ID],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": carried_ids,
        "carry_forward_warning": "The other 67 assessments are preserved exactly from r021.",
        "next_adjacent_gap": {
            "gap_id": NEXT_GAP_ID,
            "source_policy_id": NEXT_POLICY_ID,
            "reason": (
                "This successor becomes canonical only after the separate FP-048 "
                "review and completion transaction; the next repository-internal "
                "runnable policy is then FP-008/GAP-017."
            ),
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": R021_GAP_REL.as_posix(),
            "file_sha256": EXPECTED_R021_FILE_SHA256[R021_GAP_REL],
            "backlog_id": predecessor_backlog["metadata"]["backlog_id"],
            "backlog_path": R021_BACKLOG_REL.as_posix(),
            "backlog_file_sha256": EXPECTED_R021_FILE_SHA256[R021_BACKLOG_REL],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r023": False,
        },
    }
    report["ad_hoc_validation"] = {
        **deepcopy(predecessor["ad_hoc_validation"]),
        "formal_evidence": False,
        "legal_review_evidence": False,
        "actual_device_evidence": False,
        "actual_network_evidence": False,
        "production_deployment_evidence": False,
        "source": VERIFICATION_REL.as_posix(),
        "verification_result_sha256": verification_hash,
        "interpretation": (
            "저장소 내부 회귀이며 정식·실기기·외부 TLS/KMS·분리 복원·"
            "외부 보안/법률 검토·운영 배포·출시 증거가 아니다."
        ),
    }
    report["authorization_boundary"] = {
        **deepcopy(predecessor["authorization_boundary"]),
        "diagnosis_only": True,
        "implementation_modified": False,
        "approved_baseline_modified": False,
        "formal_test_completion_claimed": False,
        "artifact_approval_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
        "implementation_modified_by_this_report": False,
        "implementation_change_observed": True,
    }
    report["limitations"] = [
        "Only GAP-057 is directly reassessed; 67 assessments are exact r021 carry-forward.",
        "TC-FP-048-01 through TC-FP-048-07 are NOT_RUN.",
        "Actual Android device and external TLS/KMS verification are NOT_RUN.",
        "Separated backup restore and key-loss recovery drills are NOT_RUN.",
        "External security and legal reviews are NOT_RUN.",
        "Production deployment and release gates are NOT_RUN and unwaived.",
        "Independent review and completion receipts are excluded to prevent an evidence cycle.",
    ]
    report["report_content_sha256"] = object_sha256(report)
    return report


def build_backlog(
    predecessor: dict[str, Any],
    gap: dict[str, Any],
    observed_at: str,
) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260802-023",
        "version": "0.23.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": observed_at,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
        "revision_022_disposition": REVISION_022_DISPOSITION,
    }
    backlog["source_predecessor"] = {
        "path": R021_BACKLOG_REL.as_posix(),
        "file_sha256": EXPECTED_R021_FILE_SHA256[R021_BACKLOG_REL],
        "preserved_unchanged": False,
    }
    backlog["publication_concurrency_boundary"] = deepcopy(
        gap["publication_concurrency_boundary"]
    )
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    backlog["fp048_verification_boundary"] = {
        "formal_test_ids": EXPECTED_FORMAL_TEST_IDS,
        **EXPECTED_VERIFICATION_BOUNDARY,
    }
    rows = [
        item
        for item in backlog["next_action_sequence"]
        if item.get("source_policy_id") == POLICY_ID
    ]
    require(len(rows) == 1, "FP-048 backlog action count differs")
    require(rows[0].get("status") == "PARTIAL", "FP-048 predecessor backlog status differs")
    rows[0]["status"] = "PARTIAL"
    rows[0]["action"] = (
        "저장소 내부 암호화·열쇠 분리·회전·접근감사·사고대응 구현과 "
        "회귀는 "
        "검증됐다. 정식 시험, 실기기, 외부 TLS/KMS, 분리 복원, 외부 검토, "
        "운영 배포와 출시 Gate를 별도 검증한다."
    )
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-03")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-048 internal implementation and verification are recorded and remain "
        "subject to the separate review/completion transaction; FP-008/GAP-017 is "
        "the next repository-internal runnable work item after activation."
    )
    next_rows = [
        item
        for item in predecessor["next_action_sequence"]
        if item.get("source_policy_id") == NEXT_POLICY_ID
    ]
    require(len(next_rows) == 1, "FP-008 predecessor action count differs")
    next_action_text = next_rows[0].get("action")
    require(isinstance(next_action_text, str) and next_action_text, "FP-008 predecessor action differs")
    backlog["next_single_action"] = {
        "epic_id": "EPIC-03",
        "source_policy_id": NEXT_POLICY_ID,
        "gap_id": NEXT_GAP_ID,
        "status": "PLANNED_NEXT",
        "action": next_action_text,
    }
    backlog["backlog_content_sha256"] = object_sha256(backlog)
    return backlog


def gap_markdown(gap: dict[str, Any]) -> str:
    row = next(item for item in gap["assessments"] if item["gap_id"] == GAP_ID)
    return (
        "# WalkSafe 구현 Gap 분석 r023\n\n"
        "- canonical predecessor: `r021`\n"
        f"- revision 022: `{REVISION_022_DISPOSITION}`\n"
        "- 직접 재평가: `FP-048 / GAP-057`\n"
        f"- 판정: `{row['status']}` (`PARTIAL → PARTIAL`)\n"
        "- 저장소 내부 구현·자동 검증: `PASS`\n"
        "- 정식 시험·실기기·외부 TLS/KMS·분리 복원·외부 검토·운영 배포: "
        "`NOT_RUN`\n"
        "- 출시: `NOT_ELIGIBLE`\n"
        "- 나머지 assessment: `67개 r021 deep-equal carry-forward`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(backlog: dict[str, Any]) -> str:
    action = backlog["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r023\n\n"
        "- canonical predecessor: `r021`\n"
        f"- revision 022: `{REVISION_022_DISPOSITION}`\n"
        "- EPIC-03: `IN_PROGRESS`\n"
        f"- 현재 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        f"- 상태: `{action['status']}`\n"
        f"- 다음 작업: {action['action']}\n"
        "- 정식·실기기·외부·운영·출시 검증: `NOT_RUN` / `NOT_ELIGIBLE`\n"
    )


def _validate_serialized_outputs(
    outputs: Mapping[Path, str],
    gap: dict[str, Any],
    backlog: dict[str, Any],
) -> None:
    require(tuple(outputs) == OUTPUT_PATHS, "output path order differs")
    serialized_gap = json_from_bytes(
        outputs[R023_GAP_JSON_REL].encode(),
        R023_GAP_JSON_REL.as_posix(),
    )
    serialized_backlog = json_from_bytes(
        outputs[R023_BACKLOG_JSON_REL].encode(),
        R023_BACKLOG_JSON_REL.as_posix(),
    )
    require(serialized_gap == gap, "serialized r023 Gap differs")
    require(serialized_backlog == backlog, "serialized r023 Backlog differs")
    verify_seal(serialized_gap, "report_content_sha256", "serialized r023 Gap")
    verify_seal(
        serialized_backlog,
        "backlog_content_sha256",
        "serialized r023 Backlog",
    )
    require(
        object_sha256(serialized_gap.get("source_bindings"))
        == serialized_gap.get("source_binding_sha256"),
        "serialized r023 source binding hash differs",
    )
    assessment = next(
        (
            item
            for item in serialized_gap.get("assessments", [])
            if isinstance(item, dict) and item.get("gap_id") == GAP_ID
        ),
        None,
    )
    require(isinstance(assessment, dict), "serialized GAP-057 assessment is missing")
    assessment_projection = deepcopy(assessment)
    assessment_seal = assessment_projection.pop("assessment_sha256", None)
    require(
        isinstance(assessment_seal, str)
        and object_sha256(assessment_projection) == assessment_seal,
        "serialized GAP-057 assessment hash differs",
    )
    require(
        serialized_backlog.get("gap_report_content_sha256")
        == serialized_gap.get("report_content_sha256"),
        "serialized r023 Gap/Backlog hash binding differs",
    )
    require(
        outputs[R023_GAP_JSON_REL] == json_text(serialized_gap)
        and outputs[R023_BACKLOG_JSON_REL] == json_text(serialized_backlog)
        and outputs[R023_GAP_MD_REL] == gap_markdown(serialized_gap)
        and outputs[R023_BACKLOG_MD_REL]
        == backlog_markdown(serialized_backlog),
        "serialized r023 output derivation differs",
    )


def _direct_source_relatives() -> tuple[Path, ...]:
    return (
        R021_GAP_REL,
        R021_BACKLOG_REL,
        GOAL_REL,
        START_GATE_RECEIPT_REL,
        START_GATE_REPOSITORY_STATE_REL,
        IMPLEMENTATION_REL,
        VERIFICATION_REL,
        *TRACE_RECEIPT_RELS,
        BUILDER_REL,
        BUILDER_TEST_REL,
    )


def _capture_input_cohort(snapshot: RepositorySnapshot) -> dict[Path, bytes]:
    relatives = tuple(
        dict.fromkeys(
            (
                *_direct_source_relatives(),
                CHECKPOINT_REL,
                *map(Path, fp048_trace.IMPLEMENTATION_PATHS),
                *fp048_trace.VERIFICATION_INPUT_PATHS,
                *(spec.log_rel for spec in fp048_trace.LANES),
            )
        )
    )
    require(len(relatives) == 127, "FP-048 r023 source cohort differs")
    private_mode_paths = {
        IMPLEMENTATION_REL,
        VERIFICATION_REL,
        *TRACE_RECEIPT_RELS,
        *(spec.log_rel for spec in fp048_trace.LANES),
    }
    captured: dict[Path, bytes] = {}
    for relative in relatives:
        captured[relative] = snapshot.read(
            relative,
            require_single_link=True,
            expected_mode=0o600 if relative in private_mode_paths else None,
            maximum_bytes=MAXIMUM_SOURCE_BYTES,
        )
    return captured


def _materialize_private_producer_view(
    snapshot: RepositorySnapshot,
    captured: Mapping[Path, bytes],
    destination_root: Path,
) -> None:
    require(destination_root.is_dir(), "private producer root is missing")
    for relative, content in sorted(captured.items(), key=lambda item: item[0].as_posix()):
        _validate_relative(relative)
        destination = destination_root / relative
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor = _OS_OPEN(
            destination,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            snapshot.file_mode(relative),
        )
        try:
            _OS_FCHMOD(descriptor, snapshot.file_mode(relative))
            remaining = memoryview(content)
            while remaining:
                written = _OS_WRITE(descriptor, remaining)
                require(written > 0, f"short private-view write: {relative}")
                remaining = remaining[written:]
            _OS_FSYNC(descriptor)
            opened = _OS_FSTAT(descriptor)
            require(
                opened.st_nlink == 1
                and opened.st_size == len(content)
                and _STAT_S_IMODE(opened.st_mode) == snapshot.file_mode(relative),
                f"private-view file authority differs: {relative}",
            )
        finally:
            _close_descriptor_preserving_primary(descriptor)
        require(
            _read_relative_regular(
                destination_root,
                relative,
                expected_mode=snapshot.file_mode(relative),
                maximum_bytes=max(len(content), 1),
            )
            == content,
            f"private-view bytes differ: {relative}",
        )


def _code_signature(code: types.CodeType) -> tuple[Any, ...]:
    def constant_signature(value: Any) -> Any:
        if isinstance(value, types.CodeType):
            return ("code", _code_signature(value))
        if isinstance(value, tuple):
            return ("tuple", tuple(constant_signature(item) for item in value))
        if isinstance(value, frozenset):
            return (
                "frozenset",
                tuple(sorted(repr(constant_signature(item)) for item in value)),
            )
        return (type(value).__name__, value)

    return (
        code.co_argcount,
        code.co_posonlyargcount,
        code.co_kwonlyargcount,
        code.co_nlocals,
        code.co_stacksize,
        code.co_flags,
        code.co_code,
        tuple(constant_signature(item) for item in code.co_consts),
        code.co_names,
        code.co_varnames,
        code.co_freevars,
        code.co_cellvars,
        code.co_name,
        code.co_qualname,
        code.co_firstlineno,
        code.co_linetable,
        code.co_exceptiontable,
    )


def _function_code_map_from_code(
    module_code: types.CodeType,
    origin: str,
) -> dict[tuple[str, int, str], tuple[Any, ...]]:
    result: dict[tuple[str, int, str], tuple[Any, ...]] = {}

    def visit(code: types.CodeType) -> None:
        if code.co_name == "__annotate__":
            return
        if (
            code.co_filename == origin
            and code.co_flags & inspect.CO_NEWLOCALS
            and not code.co_name.startswith("<")
        ):
            result[(code.co_qualname, code.co_firstlineno, code.co_name)] = (
                _code_signature(code)
            )
        for constant in code.co_consts:
            if isinstance(constant, types.CodeType):
                visit(constant)

    visit(module_code)
    return result


def _runtime_function_code_map(
    module: types.ModuleType,
    origin: str,
) -> dict[tuple[str, int, str], tuple[Any, ...]]:
    result: dict[tuple[str, int, str], tuple[Any, ...]] = {}
    visited: set[int] = set()

    def visit_code(code: types.CodeType) -> None:
        if code.co_name == "__annotate__":
            return
        if id(code) in visited:
            return
        visited.add(id(code))
        if (
            code.co_filename == origin
            and code.co_flags & inspect.CO_NEWLOCALS
            and not code.co_name.startswith("<")
        ):
            result[(code.co_qualname, code.co_firstlineno, code.co_name)] = (
                _code_signature(code)
            )
        for constant in code.co_consts:
            if isinstance(constant, types.CodeType):
                visit_code(constant)

    def visit_object(value: Any) -> None:
        if isinstance(value, (staticmethod, classmethod)):
            value = value.__func__
        if isinstance(value, property):
            for accessor in (value.fget, value.fset, value.fdel):
                if accessor is not None:
                    visit_object(accessor)
        elif _INSPECT_ISFUNCTION(value):
            if value.__code__.co_filename == origin:
                visit_code(value.__code__)
            wrapped = getattr(value, "__wrapped__", None)
            if wrapped is not None:
                visit_object(wrapped)
        elif _INSPECT_ISCLASS(value) and value.__module__ == module.__name__:
            for member in vars(value).values():
                visit_object(member)

    for value in vars(module).values():
        visit_object(value)
    return result


def _validate_runtime_module_code(
    module: types.ModuleType,
    source: bytes,
    origin: Path,
    label: str,
) -> None:
    origin_text = _OS_FSPATH(origin)
    expected = _function_code_map_from_code(
        compile(
            source,
            origin_text,
            "exec",
            dont_inherit=True,
            optimize=sys.flags.optimize,
        ),
        origin_text,
    )
    actual = _runtime_function_code_map(module, origin_text)
    require(expected and actual == expected, f"runtime {label} code differs")


def _configuration_value_signature(value: Any) -> Any:
    if isinstance(value, Path):
        return ("path", value.as_posix())
    if isinstance(value, re.Pattern):
        return ("pattern", value.pattern, value.flags)
    if _INSPECT_ISCLASS(value):
        return ("class", value.__module__, value.__qualname__)
    if _DATACLASSES_IS_DATACLASS(value) and not _INSPECT_ISCLASS(value):
        return (
            "dataclass",
            type(value).__name__,
            tuple(
                (
                    field.name,
                    _configuration_value_signature(getattr(value, field.name)),
                )
                for field in _DATACLASSES_FIELDS(value)
            ),
        )
    if isinstance(value, dict):
        rows = [
            (
                _configuration_value_signature(key),
                _configuration_value_signature(item),
            )
            for key, item in value.items()
        ]
        return ("dict", tuple(sorted(rows, key=repr)))
    if isinstance(value, tuple):
        return ("tuple", tuple(_configuration_value_signature(item) for item in value))
    if isinstance(value, list):
        return ("list", tuple(_configuration_value_signature(item) for item in value))
    if isinstance(value, (set, frozenset)):
        return (
            type(value).__name__,
            tuple(
                sorted(
                    (
                        _configuration_value_signature(item)
                        for item in value
                    ),
                    key=repr,
                )
            ),
        )
    if _INSPECT_ISFUNCTION(value):
        return ("function", _function_binding_signature(value))
    if _INSPECT_ISBUILTIN(value):
        return (
            "builtin",
            getattr(value, "__module__", None),
            getattr(value, "__qualname__", getattr(value, "__name__", None)),
        )
    require(
        value is None or isinstance(value, (bool, int, float, str, bytes)),
        f"unsupported runtime configuration value: {type(value).__name__}",
    )
    return (type(value).__name__, value)


def _configuration_global_map(module: types.ModuleType) -> dict[str, Any]:
    return {
        name: _configuration_value_signature(value)
        for name, value in vars(module).items()
        if name.isupper()
    }


def _function_binding_signature(function: types.FunctionType) -> tuple[Any, ...]:
    return (
        _code_signature(function.__code__),
        _configuration_value_signature(function.__defaults__),
        _configuration_value_signature(function.__kwdefaults__),
        _configuration_value_signature(function.__annotations__),
        tuple(
            _configuration_value_signature(cell.cell_contents)
            for cell in (function.__closure__ or ())
        ),
    )


def _defined_binding_map(
    module: types.ModuleType,
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    module_file = _OS_FSPATH(module.__file__)

    def validate_global_provenance(
        function: types.FunctionType,
        visited: set[int] | None = None,
    ) -> None:
        seen = visited if visited is not None else set()
        if id(function) in seen:
            return
        seen.add(id(function))
        if function.__code__.co_filename == module_file:
            require(
                function.__globals__ is module.__dict__,
                f"runtime function globals differ: {function.__qualname__}",
            )
        else:
            provider_name = function.__globals__.get("__name__")
            provider = (
                sys.modules.get(provider_name)
                if isinstance(provider_name, str)
                else None
            )
            require(
                isinstance(provider, _TYPES_MODULE_TYPE)
                and function.__globals__ is provider.__dict__,
                f"runtime wrapper globals differ: {function.__qualname__}",
            )
        for cell in function.__closure__ or ():
            value = cell.cell_contents
            if _INSPECT_ISFUNCTION(value):
                validate_global_provenance(value, seen)

    def binding_signature(function: types.FunctionType) -> tuple[Any, ...]:
        validate_global_provenance(function)
        return _function_binding_signature(function)

    def class_signature(value: type[Any]) -> tuple[Any, ...]:
        members: dict[str, Any] = {}
        for name, member in vars(value).items():
            candidates: tuple[Any, ...]
            if isinstance(member, (staticmethod, classmethod)):
                candidates = (member.__func__,)
            elif isinstance(member, property):
                candidates = tuple(
                    item
                    for item in (member.fget, member.fset, member.fdel)
                    if item is not None
                )
            else:
                candidates = (member,)
            signatures = [
                binding_signature(candidate)
                for candidate in candidates
                if _INSPECT_ISFUNCTION(candidate)
                and candidate.__module__ == module.__name__
                and candidate.__code__.co_filename == module_file
            ]
            if signatures:
                members[name] = tuple(signatures)
        return (
            value.__qualname__,
            tuple((base.__module__, base.__qualname__) for base in value.__bases__),
            members,
        )

    for name, value in vars(module).items():
        if _INSPECT_ISFUNCTION(value) and value.__module__ == module.__name__:
            result[name] = ("function", binding_signature(value))
        elif _INSPECT_ISCLASS(value) and value.__module__ == module.__name__:
            result[name] = ("class", class_signature(value))
    return result


def _validate_runtime_dependency_bindings(
    current: types.ModuleType,
    pristine: types.ModuleType,
    label: str,
) -> None:
    current_defined = set(_defined_binding_map(current))
    pristine_defined = set(_defined_binding_map(pristine))
    current_dependencies = {
        name: value
        for name, value in vars(current).items()
        if not name.startswith("__")
        and not name.isupper()
        and name not in current_defined
    }
    pristine_dependencies = {
        name: value
        for name, value in vars(pristine).items()
        if not name.startswith("__")
        and not name.isupper()
        and name not in pristine_defined
    }
    require(
        set(current_dependencies) == set(pristine_dependencies),
        f"runtime {label} dependency binding names differ",
    )
    require(
        all(
            current_dependencies[name] is pristine_dependencies[name]
            for name in current_dependencies
        ),
        f"runtime {label} dependency bindings differ",
    )


def _execute_source_module(
    source: bytes,
    origin: Path,
    *,
    package: str,
    label: str,
) -> types.ModuleType:
    safe_label = _RE_SUB(r"[^a-z0-9_]", "_", label.casefold())
    module_name = (
        f"{package + '.' if package else ''}"
        f"_walksafe_{safe_label}_{_OS_GETPID()}_{id(source)}"
    )
    module = _TYPES_MODULE_TYPE(module_name)
    module.__file__ = _OS_FSPATH(origin)
    module.__package__ = package
    module.__spec__ = None
    sys.modules[module_name] = module
    try:
        exec(
            compile(
                source,
                _OS_FSPATH(origin),
                "exec",
                dont_inherit=True,
                optimize=sys.flags.optimize,
            ),
            module.__dict__,
        )
    finally:
        sys.modules.pop(module_name, None)
    return module


def _validate_runtime_tooling_source_mirror(captured: Mapping[Path, bytes]) -> None:
    expected_builder = Path(_OS_PATH_ABSPATH(ROOT / BUILDER_REL))
    expected_trace_builder = Path(_OS_PATH_ABSPATH(ROOT / TRACE_BUILDER_REL))
    require(
        Path(_OS_PATH_ABSPATH(__file__)) == expected_builder,
        "runtime r023 builder origin differs",
    )
    trace_file = getattr(fp048_trace, "__file__", None)
    require(
        isinstance(trace_file, str)
        and Path(_OS_PATH_ABSPATH(trace_file)) == expected_trace_builder,
        "runtime FP-048 trace builder origin differs",
    )
    require(
        set(RUNTIME_TOOLING_SOURCE_RELATIVES).issubset(captured),
        "runtime tooling source cohort differs",
    )
    with RepositorySnapshot(ROOT) as runtime_snapshot:
        runtime_sources = {
            relative: runtime_snapshot.read(
                relative,
                require_single_link=True,
                maximum_bytes=MAXIMUM_SOURCE_BYTES,
            )
            for relative in RUNTIME_TOOLING_SOURCE_RELATIVES
        }
        runtime_snapshot.verify()
    for relative in RUNTIME_TOOLING_SOURCE_RELATIVES:
        require(
            captured[relative] == runtime_sources[relative],
            f"runtime tooling source mirror differs: {relative}",
        )
    current_module = sys.modules.get(__name__)
    require(
        isinstance(current_module, _TYPES_MODULE_TYPE),
        "runtime r023 module is missing",
    )
    _validate_runtime_module_code(
        current_module,
        runtime_sources[BUILDER_REL],
        expected_builder,
        "r023 builder",
    )
    _validate_runtime_module_code(
        fp048_trace,
        runtime_sources[TRACE_BUILDER_REL],
        expected_trace_builder,
        "FP-048 trace builder",
    )
    pristine_trace = _execute_source_module(
        runtime_sources[TRACE_BUILDER_REL],
        expected_trace_builder,
        package="",
        label="fp048_trace_configuration",
    )
    require(
        _configuration_global_map(fp048_trace)
        == _configuration_global_map(pristine_trace),
        "runtime FP-048 trace configuration differs",
    )
    require(
        _defined_binding_map(fp048_trace)
        == _defined_binding_map(pristine_trace),
        "runtime FP-048 trace function bindings differ",
    )
    _validate_runtime_dependency_bindings(
        fp048_trace,
        pristine_trace,
        "FP-048 trace",
    )
    pristine_r023 = _execute_source_module(
        runtime_sources[BUILDER_REL],
        expected_builder,
        package=__package__ or "",
        label="r023_configuration",
    )
    require(
        _configuration_global_map(current_module)
        == _configuration_global_map(pristine_r023),
        "runtime r023 configuration differs",
    )
    require(
        _defined_binding_map(current_module)
        == _defined_binding_map(pristine_r023),
        "runtime r023 function bindings differ",
    )
    _validate_runtime_dependency_bindings(
        current_module,
        pristine_r023,
        "r023",
    )


def _load_sealed_trace_module(
    producer_root: Path,
    captured: Mapping[Path, bytes],
) -> types.ModuleType:
    source = captured[TRACE_BUILDER_REL]
    origin = producer_root / TRACE_BUILDER_REL
    module = _execute_source_module(
        source,
        origin,
        package="",
        label="fp048_trace_sealed",
    )
    require(
        module.ROOT == producer_root
        and module.GOAL_ID == GOAL_ID
        and module.IMPLEMENTATION_REL == IMPLEMENTATION_REL
        and module.VERIFICATION_REL == VERIFICATION_REL,
        "sealed FP-048 trace producer identity differs",
    )
    return module


def _build_outputs_from_snapshot(snapshot: RepositorySnapshot) -> dict[Path, str]:
    captured = _capture_input_cohort(snapshot)
    _validate_runtime_tooling_source_mirror(captured)
    source_bytes = {
        relative: captured[relative] for relative in _direct_source_relatives()
    }
    predecessor_gap = json_from_bytes(source_bytes[R021_GAP_REL], R021_GAP_REL.as_posix())
    predecessor_backlog = json_from_bytes(
        source_bytes[R021_BACKLOG_REL], R021_BACKLOG_REL.as_posix()
    )
    validate_predecessors(predecessor_gap, predecessor_backlog, source_bytes)
    receipt, _repository_state = validate_goal_and_gate(source_bytes)
    with _TEMPORARY_DIRECTORY(
        prefix="walksafe-fp048-r023-producer-",
        dir="/dev/shm",
    ) as temporary:
        producer_root = Path(temporary)
        _materialize_private_producer_view(snapshot, captured, producer_root)
        trace_module = _load_sealed_trace_module(producer_root, captured)
        implementation, verification = validate_results(
            trace_module,
            producer_root,
            receipt,
            source_bytes,
            captured,
        )
        gap = build_gap(
            producer_root,
            predecessor_gap,
            predecessor_backlog,
            receipt,
            implementation,
            verification,
            source_bytes,
        )
        backlog = build_backlog(predecessor_backlog, gap, verification["observed_at"])
        outputs = {
            R023_GAP_JSON_REL: json_text(gap),
            R023_GAP_MD_REL: gap_markdown(gap),
            R023_BACKLOG_JSON_REL: json_text(backlog),
            R023_BACKLOG_MD_REL: backlog_markdown(backlog),
        }
        _validate_serialized_outputs(outputs, gap, backlog)
    snapshot.verify()
    return outputs


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    _validate_runtime_dependency_attribute_bindings()
    with RepositorySnapshot(root) as snapshot:
        outputs = _build_outputs_from_snapshot(snapshot)
        snapshot.verify()
        return outputs


def _ensure_parent_directories(root: Path, relative: Path) -> None:
    _validate_relative(relative)
    with _opened_directory(root, relative.parent, create=True):
        pass


def _rename_noreplace_at(parent_fd: int, source: str, destination: str) -> None:
    libc = _CTYPES_CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    require(renameat2 is not None, "renameat2(RENAME_NOREPLACE) is unavailable")
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    renameat2.restype = ctypes.c_int
    if renameat2(parent_fd, _OS_FSENCODE(source), parent_fd, _OS_FSENCODE(destination), 1) == 0:
        return
    error = _CTYPES_GET_ERRNO()
    if error == errno.EEXIST:
        raise BuildError(f"output already exists: {destination}")
    raise OSError(error, _OS_STRERROR(error), destination)


def _relative_exists(root: Path, relative: Path) -> bool:
    try:
        with _opened_directory(root, relative.parent) as parent_fd:
            _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        return True
    except FileNotFoundError:
        return False


def _read_exact_regular(root: Path, relative: Path, expected: bytes) -> None:
    require(
        _read_relative_regular(root, relative, expected_mode=0o600) == expected,
        f"output differs: {relative}",
    )


def _validate_stage_authority(info: os.stat_result, relative: Path) -> None:
    require(
        _STAT_S_ISREG(info.st_mode)
        and not _STAT_S_ISLNK(info.st_mode)
        and info.st_uid == _OS_GETEUID()
        and info.st_nlink == 1
        and _STAT_S_IMODE(info.st_mode) == 0o600,
        f"unsafe stage file: {relative}",
    )


def _entry_exists_at(parent_fd: int, name: str) -> bool:
    try:
        _OS_STAT(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        return False
    return True


def _validate_output_authority(info: os.stat_result, relative: Path) -> None:
    require(
        _STAT_S_ISREG(info.st_mode)
        and not _STAT_S_ISLNK(info.st_mode)
        and info.st_uid == _OS_GETEUID()
        and not (_STAT_S_IMODE(info.st_mode) & 0o002),
        f"unsafe output file: {relative}",
    )
    require(info.st_nlink == 1, f"hard-linked file is forbidden: {relative}")
    require(
        _STAT_S_IMODE(info.st_mode) == 0o600,
        f"file mode differs: {relative}",
    )


def _read_exact_descriptor_at(
    parent_fd: int,
    descriptor: int,
    relative: Path,
    expected_info: os.stat_result,
    *,
    maximum_bytes: int,
    authority_validator: Callable[[os.stat_result, Path], None],
) -> bytes:
    authority_validator(expected_info, relative)
    require(
        expected_info.st_size <= maximum_bytes,
        f"file exceeds byte limit: {relative}",
    )
    require(
        _identity(_OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False))
        == _identity(expected_info),
        f"file path identity differs: {relative}",
    )
    _OS_LSEEK(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = _OS_READ(
            descriptor,
            min(1024 * 1024, maximum_bytes + 1 - total),
        )
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
        require(total <= maximum_bytes, f"file exceeds byte limit: {relative}")
    require(
        _identity(_OS_FSTAT(descriptor)) == _identity(expected_info),
        f"file changed while reading: {relative}",
    )
    require(
        _identity(_OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False))
        == _identity(expected_info),
        f"file path changed while reading: {relative}",
    )
    return b"".join(chunks)


def _open_exact_output_at(
    parent_fd: int,
    relative: Path,
    expected: bytes,
) -> tuple[int, os.stat_result]:
    require(
        len(expected) <= MAXIMUM_OUTPUT_BYTES,
        f"output exceeds byte limit: {relative}",
    )
    before = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
    _validate_output_authority(before, relative)
    require(before.st_size == len(expected), f"output byte length differs: {relative}")
    descriptor = _OS_OPEN(
        relative.name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        opened = _OS_FSTAT(descriptor)
        require(
            _identity(opened) == _identity(before),
            f"output changed while opening: {relative}",
        )
        require(
            _read_exact_descriptor_at(
                parent_fd,
                descriptor,
                relative,
                opened,
                maximum_bytes=MAXIMUM_OUTPUT_BYTES,
                authority_validator=_validate_output_authority,
            )
            == expected,
            f"output differs: {relative}",
        )
        return descriptor, opened
    except BaseException:
        _close_descriptor_preserving_primary(descriptor)
        raise


def _revalidate_output_cohort_at(
    parent_fd: int,
    cohort: list[tuple[Path, bytes, int, os.stat_result, bool]],
) -> None:
    require(
        [relative for relative, _content, _fd, _info, _new in cohort]
        == list(OUTPUT_PATHS),
        "output cohort path order differs",
    )
    for relative, expected, descriptor, identity, _newly_published in cohort:
        require(
            _identity(_OS_FSTAT(descriptor)) == _identity(identity),
            f"output descriptor identity changed: {relative}",
        )
        require(
            _read_exact_descriptor_at(
                parent_fd,
                descriptor,
                relative,
                identity,
                maximum_bytes=MAXIMUM_OUTPUT_BYTES,
                authority_validator=_validate_output_authority,
            )
            == expected,
            f"output differs: {relative}",
        )


def _remove_mutated_owned_output_at(
    parent_fd: int,
    relative: Path,
    descriptor: int,
) -> None:
    opened = _OS_FSTAT(descriptor)
    try:
        current = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        return
    if (current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino):
        return
    before_links = opened.st_nlink
    _OS_UNLINK(relative.name, dir_fd=parent_fd)
    _OS_FSYNC(parent_fd)
    require(
        _OS_FSTAT(descriptor).st_nlink == before_links - 1,
        f"mutated output cleanup link count differs: {relative}",
    )


def _read_staged_descriptor(
    parent_fd: int,
    descriptor: int,
    relative: Path,
    expected_info: os.stat_result,
    *,
    maximum_bytes: int,
) -> bytes:
    return _read_exact_descriptor_at(
        parent_fd,
        descriptor,
        relative,
        expected_info,
        maximum_bytes=maximum_bytes,
        authority_validator=_validate_stage_authority,
    )


def _open_exact_stage_at(
    parent_fd: int,
    relative: Path,
    expected: bytes,
) -> tuple[int, os.stat_result]:
    before = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
    _validate_stage_authority(before, relative)
    require(before.st_size == len(expected), f"stage byte length differs: {relative}")
    descriptor = _OS_OPEN(
        relative.name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        opened = _OS_FSTAT(descriptor)
        require(_identity(opened) == _identity(before), f"stage changed while opening: {relative}")
        require(
            _read_staged_descriptor(
                parent_fd,
                descriptor,
                relative,
                opened,
                maximum_bytes=len(expected),
            )
            == expected,
            f"stage bytes differ: {relative}",
        )
        return descriptor, opened
    except BaseException:
        _close_descriptor_preserving_primary(descriptor)
        raise


def _revalidate_stage_cohort_at(
    parent_fd: int,
    cohort: list[tuple[Path, bytes, int, os.stat_result]],
) -> None:
    require(
        [relative for relative, _content, _fd, _info in cohort]
        == [_output_stage_relative(relative) for relative in OUTPUT_PATHS],
        "stage cohort path order differs",
    )
    for relative, expected, descriptor, identity in cohort:
        require(
            _identity(_OS_FSTAT(descriptor)) == _identity(identity),
            f"stage descriptor identity changed: {relative}",
        )
        require(
            _read_staged_descriptor(
                parent_fd,
                descriptor,
                relative,
                identity,
                maximum_bytes=MAXIMUM_OUTPUT_BYTES,
            )
            == expected,
            f"stage differs: {relative}",
        )


def _validate_or_remove_partial_stage(root: Path, relative: Path, expected: bytes) -> bool:
    with _opened_directory(root, relative.parent) as parent_fd:
        return _validate_or_remove_partial_stage_at(parent_fd, relative, expected)


def _validate_or_remove_partial_stage_at(
    parent_fd: int,
    relative: Path,
    expected: bytes,
) -> bool:
    before = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
    _validate_stage_authority(before, relative)
    descriptor = _OS_OPEN(
        relative.name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        opened = _OS_FSTAT(descriptor)
        require(_identity(opened) == _identity(before), f"stage changed while opening: {relative}")
        content = (
            _read_staged_descriptor(
                parent_fd,
                descriptor,
                relative,
                opened,
                maximum_bytes=len(expected),
            )
            if before.st_size <= len(expected)
            else b""
        )
        if before.st_size == len(expected) and content == expected:
            return True
        require(
            _identity(_OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(opened),
            f"partial stage path changed before recovery: {relative}",
        )
        _OS_UNLINK(relative.name, dir_fd=parent_fd)
        _OS_FSYNC(parent_fd)
        require(_OS_FSTAT(descriptor).st_nlink == 0, f"partial stage unlink differs: {relative}")
        return False
    finally:
        _close_descriptor_preserving_primary(descriptor)


def _remove_exact_stage(root: Path, relative: Path, expected: bytes) -> None:
    with _opened_directory(root, relative.parent) as parent_fd:
        _remove_exact_stage_at(parent_fd, relative, expected)


def _remove_exact_stage_at(parent_fd: int, relative: Path, expected: bytes) -> None:
    descriptor, opened = _open_exact_stage_at(parent_fd, relative, expected)
    try:
        require(
            _identity(_OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(opened),
            f"stage path changed before cleanup: {relative}",
        )
        _OS_UNLINK(relative.name, dir_fd=parent_fd)
        _OS_FSYNC(parent_fd)
        require(_OS_FSTAT(descriptor).st_nlink == 0, f"stage cleanup differs: {relative}")
    finally:
        _close_descriptor_preserving_primary(descriptor)


def _remove_just_published_inode(
    parent_fd: int,
    descriptor: int,
    relative: Path,
) -> None:
    opened = _OS_FSTAT(descriptor)
    current = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
    require(
        _STAT_S_ISREG(current.st_mode)
        and current.st_uid == _OS_GETEUID()
        and (current.st_dev, current.st_ino) == (opened.st_dev, opened.st_ino),
        f"mutated published inode cannot be safely removed: {relative}",
    )
    _OS_UNLINK(relative.name, dir_fd=parent_fd)
    _OS_FSYNC(parent_fd)
    require(_OS_FSTAT(descriptor).st_nlink == 0, f"mutated published inode cleanup differs: {relative}")


def _write_stage(
    root: Path,
    relative: Path,
    content: bytes,
    *,
    stage_index: int,
    stage_write_hook: Callable[[int, Path], None] | None = None,
) -> None:
    with _opened_directory(root, relative.parent) as parent_fd:
        _write_stage_at(
            parent_fd,
            relative,
            content,
            stage_index=stage_index,
            stage_write_hook=stage_write_hook,
        )


def _open_private_exclusive_at(parent_fd: int, name: str) -> int:
    previous_umask = _OS_UMASK(0)
    descriptor: int | None = None
    try:
        descriptor = _OS_OPEN(
            name,
            os.O_RDWR
            | os.O_CREAT
            | os.O_EXCL
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            0o600,
            dir_fd=parent_fd,
        )
    finally:
        try:
            _OS_UMASK(previous_umask)
        except BaseException:
            if descriptor is not None:
                _close_descriptor_preserving_primary(descriptor)
            raise
    require(descriptor is not None, "exclusive private file descriptor is missing")
    try:
        _OS_FCHMOD(descriptor, 0o600)
    except BaseException:
        _close_descriptor_preserving_primary(descriptor)
        raise
    return descriptor


def _write_stage_at(
    parent_fd: int,
    relative: Path,
    content: bytes,
    *,
    stage_index: int,
    stage_write_hook: Callable[[int, Path], None] | None = None,
) -> None:
    require(len(content) <= MAXIMUM_OUTPUT_BYTES, f"stage exceeds byte limit: {relative}")
    descriptor = _open_private_exclusive_at(parent_fd, relative.name)
    try:
        position = 0
        hook_called = False
        while position < len(content):
            written = _OS_WRITE(descriptor, content[position:])
            require(written > 0, f"short stage write: {relative}")
            position += written
            if stage_write_hook is not None and not hook_called:
                hook_called = True
                stage_write_hook(stage_index, relative)
        _OS_FSYNC(descriptor)
        opened = _OS_FSTAT(descriptor)
        require(opened.st_size == len(content), f"stage byte length differs: {relative}")
        require(
            _read_staged_descriptor(
                parent_fd,
                descriptor,
                relative,
                opened,
                maximum_bytes=len(content),
            )
            == content,
            f"stage bytes differ after write: {relative}",
        )
    finally:
        _close_descriptor_preserving_primary(descriptor)
    _OS_FSYNC(parent_fd)


def _output_stage_relative(relative: Path) -> Path:
    return relative.with_name(f".{relative.name}.r023-stage")


def _require_no_transaction_residue_at(parent_fd: int) -> None:
    residue = (
        TRANSACTION_JOURNAL_REL,
        TRANSACTION_JOURNAL_STAGE_REL,
        *(_output_stage_relative(relative) for relative in OUTPUT_PATHS),
    )
    require(
        not any(_entry_exists_at(parent_fd, relative.name) for relative in residue),
        "unfinished r023 publication transaction exists",
    )


def _transaction_journal_bytes(
    outputs: Mapping[Path, str],
    *,
    source_snapshot_sha256: str,
) -> bytes:
    require(tuple(outputs) == OUTPUT_PATHS, "transaction output set differs")
    require(
        SHA256_RE.fullmatch(source_snapshot_sha256) is not None,
        "source snapshot hash differs",
    )
    rows = []
    for relative, text in outputs.items():
        content = text.encode()
        require(
            len(content) <= MAXIMUM_OUTPUT_BYTES,
            f"output exceeds byte limit: {relative}",
        )
        rows.append(
            {
                "path": relative.as_posix(),
                "byte_count": len(content),
                "sha256": bytes_sha256(content),
            }
        )
    journal = {
        "schema_version": "walksafe.fp048-r023-publication-transaction.v1",
        "document_id": "WS-FP048-R023-PUBLICATION-TRANSACTION-20260802-001",
        "kind": "ADD_ONLY_FORWARD_RECOVERY_TRANSACTION",
        "source_snapshot_sha256": source_snapshot_sha256,
        "output_count": len(rows),
        "outputs": rows,
        "output_content_set_sha256": object_sha256(rows),
    }
    journal["journal_content_sha256"] = object_sha256(journal)
    content = json_text(journal).encode()
    require(len(content) <= MAXIMUM_JOURNAL_BYTES, "transaction journal is oversized")
    return content


def _parse_transaction_journal(
    content: bytes,
    *,
    expected_source_snapshot_sha256: str,
) -> dict[Path, tuple[int, str]]:
    require(len(content) <= MAXIMUM_JOURNAL_BYTES, "transaction journal is oversized")
    journal = json_from_bytes(content, TRANSACTION_JOURNAL_REL.as_posix())
    verify_seal(journal, "journal_content_sha256", "r023 transaction journal")
    require(
        journal.get("schema_version")
        == "walksafe.fp048-r023-publication-transaction.v1"
        and journal.get("document_id")
        == "WS-FP048-R023-PUBLICATION-TRANSACTION-20260802-001"
        and journal.get("kind") == "ADD_ONLY_FORWARD_RECOVERY_TRANSACTION",
        "transaction journal identity differs",
    )
    require(
        isinstance(journal.get("source_snapshot_sha256"), str)
        and SHA256_RE.fullmatch(journal["source_snapshot_sha256"]) is not None,
        "transaction source snapshot hash differs",
    )
    require(
        journal["source_snapshot_sha256"] == expected_source_snapshot_sha256,
        "transaction source snapshot hash differs",
    )
    rows = journal.get("outputs")
    require(
        isinstance(rows, list)
        and len(rows) == len(OUTPUT_PATHS)
        and journal.get("output_count") == len(OUTPUT_PATHS)
        and object_sha256(rows) == journal.get("output_content_set_sha256"),
        "transaction journal output set differs",
    )
    parsed: dict[Path, tuple[int, str]] = {}
    for relative, row in zip(OUTPUT_PATHS, rows, strict=True):
        require(
            isinstance(row, dict)
            and set(row) == {"path", "byte_count", "sha256"}
            and row.get("path") == relative.as_posix()
            and type(row.get("byte_count")) is int
            and 0 <= row["byte_count"] <= MAXIMUM_OUTPUT_BYTES
            and isinstance(row.get("sha256"), str)
            and SHA256_RE.fullmatch(row["sha256"]) is not None,
            f"transaction journal row differs: {relative}",
        )
        parsed[relative] = (row["byte_count"], row["sha256"])
    return parsed


def _read_authorized_entry_at(
    parent_fd: int,
    relative: Path,
    *,
    maximum_bytes: int,
    authority_validator: Callable[[os.stat_result, Path], None],
) -> bytes:
    content, descriptor, _opened = _open_authorized_entry_at(
        parent_fd,
        relative,
        maximum_bytes=maximum_bytes,
        authority_validator=authority_validator,
    )
    _close_descriptor_preserving_primary(descriptor)
    return content


def _open_authorized_entry_at(
    parent_fd: int,
    relative: Path,
    *,
    maximum_bytes: int,
    authority_validator: Callable[[os.stat_result, Path], None],
) -> tuple[bytes, int, os.stat_result]:
    before = _OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False)
    authority_validator(before, relative)
    require(before.st_size <= maximum_bytes, f"file exceeds byte limit: {relative}")
    descriptor = _OS_OPEN(
        relative.name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        opened = _OS_FSTAT(descriptor)
        require(
            _identity(opened) == _identity(before),
            f"file changed while opening: {relative}",
        )
        content = _read_exact_descriptor_at(
            parent_fd,
            descriptor,
            relative,
            opened,
            maximum_bytes=maximum_bytes,
            authority_validator=authority_validator,
        )
        return content, descriptor, opened
    except BaseException:
        _close_descriptor_preserving_primary(descriptor)
        raise


def _remove_exact_output_entry_at(
    parent_fd: int,
    relative: Path,
    expected: bytes,
) -> None:
    descriptor, opened = _open_exact_output_at(parent_fd, relative, expected)
    try:
        require(
            _identity(_OS_STAT(relative.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(opened),
            f"output path changed before cleanup: {relative}",
        )
        _OS_UNLINK(relative.name, dir_fd=parent_fd)
        _OS_FSYNC(parent_fd)
        require(
            _OS_FSTAT(descriptor).st_nlink == 0,
            f"output cleanup link count differs: {relative}",
        )
    finally:
        _close_descriptor_preserving_primary(descriptor)


def _unlink_retained_output_at(
    parent_fd: int,
    relative: Path,
    expected: bytes,
    descriptor: int,
    identity: os.stat_result,
    *,
    maximum_bytes: int,
) -> None:
    require(
        _identity(_OS_FSTAT(descriptor)) == _identity(identity)
        and _read_exact_descriptor_at(
            parent_fd,
            descriptor,
            relative,
            identity,
            maximum_bytes=maximum_bytes,
            authority_validator=_validate_output_authority,
        )
        == expected,
        f"retained output differs before cleanup: {relative}",
    )
    _OS_UNLINK(relative.name, dir_fd=parent_fd)
    _OS_FSYNC(parent_fd)


def _unlink_retained_stage_at(
    parent_fd: int,
    relative: Path,
    expected: bytes,
    descriptor: int,
    identity: os.stat_result,
) -> None:
    require(
        _identity(_OS_FSTAT(descriptor)) == _identity(identity)
        and _read_exact_descriptor_at(
            parent_fd,
            descriptor,
            relative,
            identity,
            maximum_bytes=MAXIMUM_OUTPUT_BYTES,
            authority_validator=_validate_stage_authority,
        )
        == expected,
        f"retained stage differs before cleanup: {relative}",
    )
    _OS_UNLINK(relative.name, dir_fd=parent_fd)
    _OS_FSYNC(parent_fd)


def _commit_retained_journal_at(
    parent_fd: int,
    journal_bytes: bytes,
    journal_descriptor: int,
    journal_identity: os.stat_result,
) -> None:
    try:
        _unlink_retained_output_at(
            parent_fd,
            TRANSACTION_JOURNAL_REL,
            journal_bytes,
            journal_descriptor,
            journal_identity,
            maximum_bytes=MAXIMUM_JOURNAL_BYTES,
        )
    except BaseException:
        if _entry_exists_at(parent_fd, TRANSACTION_JOURNAL_REL.name):
            raise


def _publish_transaction_journal_at(
    parent_fd: int,
    journal_bytes: bytes,
    *,
    rename_noreplace: Callable[[int, str, str], None] = _rename_noreplace_at,
) -> tuple[int, os.stat_result]:
    if _entry_exists_at(parent_fd, TRANSACTION_JOURNAL_REL.name):
        descriptor, identity = _open_exact_output_at(
            parent_fd,
            TRANSACTION_JOURNAL_REL,
            journal_bytes,
        )
        return descriptor, identity
    if _entry_exists_at(parent_fd, TRANSACTION_JOURNAL_STAGE_REL.name):
        stage_complete = _validate_or_remove_partial_stage_at(
            parent_fd,
            TRANSACTION_JOURNAL_STAGE_REL,
            journal_bytes,
        )
    else:
        stage_complete = False
    if not stage_complete:
        _write_stage_at(
            parent_fd,
            TRANSACTION_JOURNAL_STAGE_REL,
            journal_bytes,
            stage_index=-1,
        )
    descriptor, staged_info = _open_exact_stage_at(
        parent_fd,
        TRANSACTION_JOURNAL_STAGE_REL,
        journal_bytes,
    )
    try:
        rename_noreplace(
            parent_fd,
            TRANSACTION_JOURNAL_STAGE_REL.name,
            TRANSACTION_JOURNAL_REL.name,
        )
        published = _OS_FSTAT(descriptor)
        require(
            _publication_identity(published) == _publication_identity(staged_info)
            and _identity(
                _OS_STAT(
                    TRANSACTION_JOURNAL_REL.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
            )
            == _identity(published),
            "transaction journal publication identity differs",
        )
        _OS_FSYNC(parent_fd)
        require(
            _read_exact_descriptor_at(
                parent_fd,
                descriptor,
                TRANSACTION_JOURNAL_REL,
                published,
                maximum_bytes=MAXIMUM_JOURNAL_BYTES,
                authority_validator=_validate_output_authority,
            )
            == journal_bytes,
            "transaction journal publication differs",
        )
        return descriptor, published
    except BaseException:
        _close_descriptor_preserving_primary(descriptor)
        raise


def _validate_serialized_output_bytes(outputs: Mapping[Path, bytes]) -> None:
    require(tuple(outputs) == OUTPUT_PATHS, "serialized output byte set differs")
    texts = {relative: content.decode("utf-8") for relative, content in outputs.items()}
    gap = json_from_bytes(outputs[R023_GAP_JSON_REL], R023_GAP_JSON_REL.as_posix())
    backlog = json_from_bytes(
        outputs[R023_BACKLOG_JSON_REL],
        R023_BACKLOG_JSON_REL.as_posix(),
    )
    _validate_serialized_outputs(texts, gap, backlog)


def _recover_transaction_at(
    parent_fd: int,
    expected_outputs: Mapping[Path, bytes],
    *,
    expected_source_snapshot_sha256: str,
    precommit_validator: Callable[[], None],
    rename_noreplace: Callable[[int, str, str], None] = _rename_noreplace_at,
) -> bool | None:
    if not _entry_exists_at(parent_fd, TRANSACTION_JOURNAL_REL.name):
        return None
    require(tuple(expected_outputs) == OUTPUT_PATHS, "recovery output set differs")
    _validate_serialized_output_bytes(expected_outputs)
    journal_bytes, journal_descriptor, journal_identity = _open_authorized_entry_at(
        parent_fd,
        TRANSACTION_JOURNAL_REL,
        maximum_bytes=MAXIMUM_JOURNAL_BYTES,
        authority_validator=_validate_output_authority,
    )
    committed = False
    try:
        committed = _recover_transaction_payload_at(
            parent_fd,
            journal_bytes,
            journal_descriptor,
            journal_identity,
            expected_outputs,
            expected_source_snapshot_sha256=expected_source_snapshot_sha256,
            precommit_validator=precommit_validator,
            rename_noreplace=rename_noreplace,
        )
        return committed
    finally:
        close_error = _close_descriptor_sequence((journal_descriptor,))
        if close_error is not None and not committed and sys.exception() is None:
            raise close_error


def _recover_transaction_payload_at(
    parent_fd: int,
    journal_bytes: bytes,
    journal_descriptor: int,
    journal_identity: os.stat_result,
    expected: Mapping[Path, bytes],
    *,
    expected_source_snapshot_sha256: str,
    precommit_validator: Callable[[], None],
    rename_noreplace: Callable[[int, str, str], None],
) -> bool:
    rows = _parse_transaction_journal(
        journal_bytes,
        expected_source_snapshot_sha256=expected_source_snapshot_sha256,
    )
    require(tuple(expected) == OUTPUT_PATHS, "recovery output set differs")
    for relative, content in expected.items():
        byte_count, digest = rows[relative]
        require(
            len(content) == byte_count and bytes_sha256(content) == digest,
            f"transaction output binding differs: {relative}",
        )

    cohort: list[tuple[Path, bytes, int, os.stat_result, bool]] = []
    retained_stages: list[tuple[Path, bytes, int, os.stat_result]] = []
    committed = False
    try:
        for relative, content in expected.items():
            staged = _output_stage_relative(relative)
            final_exists = _entry_exists_at(parent_fd, relative.name)
            stage_exists = _entry_exists_at(parent_fd, staged.name)
            require(
                final_exists or stage_exists,
                f"transaction payload is missing: {relative}",
            )
            if final_exists:
                descriptor, identity = _open_exact_output_at(
                    parent_fd,
                    relative,
                    content,
                )
                try:
                    cohort_row = (
                        relative,
                        content,
                        descriptor,
                        identity,
                        False,
                    )
                except BaseException:
                    _close_descriptor_preserving_primary(descriptor)
                    raise
                _append_owned_descriptor(
                    cohort,
                    cohort_row,
                    descriptor,
                )
                if stage_exists:
                    stage_descriptor, stage_identity = _open_exact_stage_at(
                        parent_fd,
                        staged,
                        content,
                    )
                    try:
                        stage_row = (
                            staged,
                            content,
                            stage_descriptor,
                            stage_identity,
                        )
                    except BaseException:
                        _close_descriptor_preserving_primary(stage_descriptor)
                        raise
                    _append_owned_descriptor(
                        retained_stages,
                        stage_row,
                        stage_descriptor,
                    )
                continue

            stage_descriptor, stage_identity = _open_exact_stage_at(
                parent_fd,
                staged,
                content,
            )
            try:
                retained_record = (
                    staged,
                    content,
                    stage_descriptor,
                    stage_identity,
                )
            except BaseException:
                _close_descriptor_preserving_primary(stage_descriptor)
                raise
            _append_owned_descriptor(
                retained_stages,
                retained_record,
                stage_descriptor,
            )
            try:
                rename_noreplace(parent_fd, staged.name, relative.name)
            except BuildError as exc:
                if not _entry_exists_at(parent_fd, relative.name):
                    raise exc
                descriptor, identity = _open_exact_output_at(
                    parent_fd,
                    relative,
                    content,
                )
                try:
                    cohort_row = (
                        relative,
                        content,
                        descriptor,
                        identity,
                        False,
                    )
                except BaseException:
                    _close_descriptor_preserving_primary(descriptor)
                    raise
                _append_owned_descriptor(
                    cohort,
                    cohort_row,
                    descriptor,
                )
            else:
                published = _OS_FSTAT(stage_descriptor)
                _validate_output_authority(published, relative)
                require(
                    _publication_identity(published)
                    == _publication_identity(stage_identity)
                    and _identity(
                        _OS_STAT(
                            relative.name,
                            dir_fd=parent_fd,
                            follow_symlinks=False,
                        )
                    )
                    == _identity(published)
                    and _read_exact_descriptor_at(
                        parent_fd,
                        stage_descriptor,
                        relative,
                        published,
                        maximum_bytes=MAXIMUM_OUTPUT_BYTES,
                        authority_validator=_validate_output_authority,
                    )
                    == content,
                    f"recovered output differs: {relative}",
                )
                _OS_FSYNC(parent_fd)
                cohort.append(
                    (relative, content, stage_descriptor, published, True)
                )
                retained_stages.remove(retained_record)

        _revalidate_output_cohort_at(parent_fd, cohort)
        for staged, content, descriptor, identity in retained_stages:
            _revalidate_output_cohort_at(parent_fd, cohort)
            _unlink_retained_stage_at(
                parent_fd,
                staged,
                content,
                descriptor,
                identity,
            )
        if _entry_exists_at(parent_fd, TRANSACTION_JOURNAL_STAGE_REL.name):
            _remove_exact_stage_at(
                parent_fd,
                TRANSACTION_JOURNAL_STAGE_REL,
                journal_bytes,
            )
        _revalidate_output_cohort_at(parent_fd, cohort)
        precommit_validator()
        _revalidate_output_cohort_at(parent_fd, cohort)
        _commit_retained_journal_at(
            parent_fd,
            journal_bytes,
            journal_descriptor,
            journal_identity,
        )
        committed = True
        return committed
    finally:
        close_errors = (
            _close_descriptor_sequence(
                descriptor
                for _relative, _content, descriptor, _identity_value, _new in cohort
            ),
            _close_descriptor_sequence(
                descriptor
                for _relative, _content, descriptor, _identity_value in retained_stages
                if not any(descriptor == row[2] for row in cohort)
            ),
        )
        close_error = next((error for error in close_errors if error is not None), None)
        if close_error is not None and not committed and sys.exception() is None:
            raise close_error


def write_or_check(
    root: Path,
    outputs: dict[Path, str] | None,
    *,
    write: bool,
    publish_hook: Callable[[int, Path], None] | None = None,
    stage_write_hook: Callable[[int, Path], None] | None = None,
    before_journal_commit_hook: Callable[[], None] | None = None,
    journal_commit_hook: Callable[[], None] | None = None,
    before_cohort_cleanup_hook: Callable[[], None] | None = None,
    test_rename_noreplace_hook: Callable[[int, str, str], None] | None = None,
) -> dict[Path, str]:
    _validate_runtime_dependency_attribute_bindings()
    if outputs is not None:
        require(tuple(outputs) == OUTPUT_PATHS, "output set differs")
    if test_rename_noreplace_hook is not None:
        require(
            Path(_OS_PATH_ABSPATH(root)) != ROOT,
            "test rename hook is forbidden for the production root",
        )
    if before_cohort_cleanup_hook is not None:
        require(
            Path(_OS_PATH_ABSPATH(root)) != ROOT,
            "test cleanup hook is forbidden for the production root",
        )
    rename_noreplace = test_rename_noreplace_hook or _rename_noreplace_at
    with RepositorySnapshot(root) as snapshot:
        parent = OUTPUT_PATHS[0].parent
        require(
            all(relative.parent == parent for relative in OUTPUT_PATHS)
            and TRANSACTION_JOURNAL_REL.parent == parent,
            "publication parent set differs",
        )
        parent_fd = snapshot.directory_fd(parent)
        _acquire_publication_lease(parent_fd)

        if not write:
            _require_no_transaction_residue_at(parent_fd)

        rebuilt = _build_outputs_from_snapshot(snapshot)
        if outputs is None:
            outputs = rebuilt
        else:
            require(rebuilt == outputs, "outputs differ from retained source snapshot")
        expected_bytes = {
            relative: text.encode() for relative, text in outputs.items()
        }
        source_snapshot_sha256 = snapshot.content_set_sha256()
        journal_bytes = _transaction_journal_bytes(
            outputs,
            source_snapshot_sha256=source_snapshot_sha256,
        )

        if write:
            snapshot.verify()
            recovered = _recover_transaction_at(
                parent_fd,
                expected_bytes,
                expected_source_snapshot_sha256=source_snapshot_sha256,
                precommit_validator=snapshot.verify,
                rename_noreplace=rename_noreplace,
            )
            if recovered is not None:
                snapshot.suppress_close_errors_after_commit()
                return outputs

        if not write:
            cohort: list[tuple[Path, bytes, int, os.stat_result, bool]] = []
            try:
                for relative, content in expected_bytes.items():
                    descriptor, identity = _open_exact_output_at(
                        parent_fd,
                        relative,
                        content,
                    )
                    try:
                        cohort_row = (
                            relative,
                            content,
                            descriptor,
                            identity,
                            False,
                        )
                    except BaseException:
                        _close_descriptor_preserving_primary(descriptor)
                        raise
                    _append_owned_descriptor(
                        cohort,
                        cohort_row,
                        descriptor,
                    )
                _revalidate_output_cohort_at(parent_fd, cohort)
                snapshot.verify()
                _revalidate_output_cohort_at(parent_fd, cohort)
                _require_no_transaction_residue_at(parent_fd)
                if before_cohort_cleanup_hook is not None:
                    before_cohort_cleanup_hook()
            finally:
                close_error = _close_descriptor_sequence(
                    descriptor
                    for _relative, _content, descriptor, _identity_value, _new in cohort
                )
                if close_error is not None and sys.exception() is None:
                    raise close_error
            return outputs

        final_presence = [
            _entry_exists_at(parent_fd, relative.name) for relative in OUTPUT_PATHS
        ]
        if any(final_presence):
            require(all(final_presence), "unjournaled partial r023 output set exists")
            cohort = []
            try:
                for relative, content in expected_bytes.items():
                    descriptor, identity = _open_exact_output_at(
                        parent_fd,
                        relative,
                        content,
                    )
                    try:
                        cohort_row = (
                            relative,
                            content,
                            descriptor,
                            identity,
                            False,
                        )
                    except BaseException:
                        _close_descriptor_preserving_primary(descriptor)
                        raise
                    _append_owned_descriptor(
                        cohort,
                        cohort_row,
                        descriptor,
                    )
                _revalidate_output_cohort_at(parent_fd, cohort)
                for relative, content in expected_bytes.items():
                    staged = _output_stage_relative(relative)
                    if _entry_exists_at(parent_fd, staged.name):
                        _remove_exact_stage_at(parent_fd, staged, content)
                if _entry_exists_at(parent_fd, TRANSACTION_JOURNAL_STAGE_REL.name):
                    journal_stage_complete = _validate_or_remove_partial_stage_at(
                        parent_fd,
                        TRANSACTION_JOURNAL_STAGE_REL,
                        journal_bytes,
                    )
                    if journal_stage_complete:
                        _remove_exact_stage_at(
                            parent_fd,
                            TRANSACTION_JOURNAL_STAGE_REL,
                            journal_bytes,
                        )
                snapshot.verify()
                _revalidate_output_cohort_at(parent_fd, cohort)
                _require_no_transaction_residue_at(parent_fd)
                if before_cohort_cleanup_hook is not None:
                    before_cohort_cleanup_hook()
            finally:
                close_error = _close_descriptor_sequence(
                    descriptor
                    for _relative, _content, descriptor, _identity_value, _new in cohort
                )
                if close_error is not None and sys.exception() is None:
                    raise close_error
            return outputs

        for stage_index, (relative, content) in enumerate(expected_bytes.items()):
            staged = _output_stage_relative(relative)
            if _entry_exists_at(parent_fd, staged.name):
                stage_complete = _validate_or_remove_partial_stage_at(
                    parent_fd,
                    staged,
                    content,
                )
            else:
                stage_complete = False
            if not stage_complete:
                _write_stage_at(
                    parent_fd,
                    staged,
                    content,
                    stage_index=stage_index,
                    stage_write_hook=stage_write_hook,
                )

        stage_cohort: list[tuple[Path, bytes, int, os.stat_result]] = []
        journal_descriptor: int | None = None
        journal_identity: os.stat_result | None = None
        cohort = []
        committed = False
        try:
            for relative, content in expected_bytes.items():
                staged = _output_stage_relative(relative)
                descriptor, identity = _open_exact_stage_at(
                    parent_fd,
                    staged,
                    content,
                )
                try:
                    stage_row = (staged, content, descriptor, identity)
                except BaseException:
                    _close_descriptor_preserving_primary(descriptor)
                    raise
                _append_owned_descriptor(
                    stage_cohort,
                    stage_row,
                    descriptor,
                )
            _revalidate_stage_cohort_at(parent_fd, stage_cohort)
            if before_journal_commit_hook is not None:
                before_journal_commit_hook()
            snapshot.verify()
            _revalidate_stage_cohort_at(parent_fd, stage_cohort)
            journal_descriptor, journal_identity = _publish_transaction_journal_at(
                parent_fd,
                journal_bytes,
                rename_noreplace=rename_noreplace,
            )
            if journal_commit_hook is not None:
                journal_commit_hook()

            for index, (relative, content) in enumerate(expected_bytes.items()):
                staged = _output_stage_relative(relative)
                newly_published = False
                if not _entry_exists_at(parent_fd, relative.name):
                    staged_relative, staged_content, descriptor, staged_info = (
                        stage_cohort[index]
                    )
                    require(
                        staged_relative == staged and staged_content == content,
                        f"held stage selection differs: {relative}",
                    )
                    try:
                        rename_noreplace(parent_fd, staged.name, relative.name)
                    except BuildError as exc:
                        if not _entry_exists_at(parent_fd, relative.name):
                            raise exc
                        descriptor, published_info = _open_exact_output_at(
                            parent_fd,
                            relative,
                            content,
                        )
                    else:
                        newly_published = True
                        published_info = _OS_FSTAT(descriptor)
                        _validate_output_authority(published_info, relative)
                        require(
                            _publication_identity(published_info)
                            == _publication_identity(staged_info)
                            and _identity(
                                _OS_STAT(
                                    relative.name,
                                    dir_fd=parent_fd,
                                    follow_symlinks=False,
                                )
                            )
                            == _identity(published_info)
                            and _read_exact_descriptor_at(
                                parent_fd,
                                descriptor,
                                relative,
                                published_info,
                                maximum_bytes=MAXIMUM_OUTPUT_BYTES,
                                authority_validator=_validate_output_authority,
                            )
                            == content,
                            f"published stage identity or bytes differ: {relative}",
                        )
                        _OS_FSYNC(parent_fd)
                else:
                    descriptor, published_info = _open_exact_output_at(
                        parent_fd,
                        relative,
                        content,
                    )
                if newly_published:
                    cohort.append(
                        (
                            relative,
                            content,
                            descriptor,
                            published_info,
                            True,
                        )
                    )
                else:
                    try:
                        cohort_row = (
                            relative,
                            content,
                            descriptor,
                            published_info,
                            False,
                        )
                    except BaseException:
                        _close_descriptor_preserving_primary(descriptor)
                        raise
                    _append_owned_descriptor(cohort, cohort_row, descriptor)
                if publish_hook is not None:
                    publish_hook(index, relative)

            _revalidate_output_cohort_at(parent_fd, cohort)
            for relative, content in expected_bytes.items():
                staged = _output_stage_relative(relative)
                if _entry_exists_at(parent_fd, staged.name):
                    _remove_exact_stage_at(parent_fd, staged, content)
            _revalidate_output_cohort_at(parent_fd, cohort)
            snapshot.verify()
            _revalidate_output_cohort_at(parent_fd, cohort)
            require(
                journal_descriptor is not None and journal_identity is not None,
                "transaction journal descriptor is missing",
            )
            _commit_retained_journal_at(
                parent_fd,
                journal_bytes,
                journal_descriptor,
                journal_identity,
            )
            committed = True
            snapshot.suppress_close_errors_after_commit()
        finally:
            close_errors = (
                _close_descriptor_sequence(
                    descriptor
                    for _relative, _content, descriptor, _identity_value, _new in cohort
                ),
                _close_descriptor_sequence(
                    () if journal_descriptor is None else (journal_descriptor,)
                ),
                _close_descriptor_sequence(
                    descriptor
                    for _relative, _content, descriptor, _identity_value in stage_cohort
                    if not any(descriptor == row[2] for row in cohort)
                ),
            )
            close_error = next(
                (error for error in close_errors if error is not None),
                None,
            )
            if close_error is not None and not committed and sys.exception() is None:
                raise close_error
        return outputs


def main(argv: list[str] | None = None) -> int:
    _validate_runtime_dependency_attribute_bindings()
    parser = _ARGPARSE_ARGUMENT_PARSER()
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    try:
        outputs = write_or_check(args.root, None, write=args.write)
    except (BuildError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"FP-048 canonical r023 Gap/Backlog: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        "FP-048 canonical r023 Gap/Backlog: PASS "
        f"outputs={len(outputs)} reassessed={GAP_ID} carried=67 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
