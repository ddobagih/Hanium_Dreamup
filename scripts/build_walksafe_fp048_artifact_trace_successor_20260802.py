#!/usr/bin/env python3
"""Build and atomically publish the FP-048 six-document trace successor.

The successor records repository-internal implementation and verification only.
It deliberately excludes review/completion evidence and grants no formal,
device, external, approval, deployment, or release credit.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from contextvars import ContextVar
from copy import deepcopy
import ctypes
from dataclasses import dataclass
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import posixpath
import re
import stat
import struct
import sys
from typing import Any, Callable, Iterator


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
SUCCESSOR_ID = "WS-FP048-ARTIFACT-TRACE-SUCCESSOR-20260802-001"
CHANGE_ID = "CHG-DOC-0014"
PREPARED_ON = "2026-08-02"
GAP_REPORT_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023"
R021_GAP_REPORT_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-021"
EXPECTED_R021_GAP_SHA256 = (
    "f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a"
)
EXPECTED_R023_CURRENT_SOURCE_BINDING_NAMES = (
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

DOC05_REL = Path("docs/deliverables/00-control/artifact-change-log.json")
DOC01_REL = Path("docs/deliverables/00-control/artifact-register.json")
RTM_REL = Path("docs/deliverables/03-requirements/rtm.json")
DESIGN_REL = Path("docs/deliverables/04-design/design-traceability-register.json")
IMPLEMENTATION_MANIFEST_REL = Path(
    "docs/deliverables/05-implementation/implementation-manifest.json"
)
MODULE_REGISTER_REL = Path(
    "docs/deliverables/05-implementation/module-register.json"
)
IMPLEMENTATION_REL = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-048-R001/"
    "implementation-record.json"
)
VERIFICATION_REL = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-048-R001/"
    "verification-result.json"
)
GAP_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260802-r023.json"
)
R021_GAP_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260726-r021.json"
)
BUILDER_REL = Path("scripts/build_walksafe_fp048_artifact_trace_successor_20260802.py")

OUTPUT_PATHS = (
    DOC05_REL,
    RTM_REL,
    DESIGN_REL,
    IMPLEMENTATION_MANIFEST_REL,
    MODULE_REGISTER_REL,
    DOC01_REL,
)
INPUT_PATHS = (IMPLEMENTATION_REL, VERIFICATION_REL, GAP_REL)

PINNED_PREDECESSOR_SHA256 = {
    DOC05_REL: "cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67",
    DOC01_REL: "a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f",
    RTM_REL: "1d73d1e6e0a47ea3833df779c945397d799b14e9b26fdac4bb577197a660a7bd",
    DESIGN_REL: "18775a3f4d5d1faf0ae692b27888613c3d43f0b276d5a71dfbb08aad47c6b4ae",
    IMPLEMENTATION_MANIFEST_REL: "df1ac6dbbbd9f24e2fef08c58b4de8def742830c3ae260d930dd09ec0cf5b6dc",
    MODULE_REGISTER_REL: "c0727ae24e53ce3142aa5c55db7d6273628655069c3ac0821fa7d03ad8f08b48",
}

# Patched to exact physical identities after the three canonical producers run.
# Production invocation fails closed while any value remains PENDING.
PINNED_INPUT_SHA256 = {
    IMPLEMENTATION_REL: "1990e550ffaab170dded19d4db9ac35fcff13594efd9c553e563fc76ee35fb39",
    VERIFICATION_REL: "499b2dd4651b7efa73a2d8904daa4aaea0eca6ac94e6734d4c6660fe0dc3160c",
    GAP_REL: "34d4b8a4a05ac346e48293344dbf17e10cb65a792e85c5df42f69a7d63c09f82",
}
PINNED_INPUT_DOCUMENT_IDS = {
    IMPLEMENTATION_REL: "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTATION-20260802-001",
    VERIFICATION_REL: "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-VERIFICATION-20260802-001",
}

TARGET_ARTIFACT_PATHS = {
    "DOC-05": DOC05_REL,
    "REQ-16": RTM_REL,
    "DES-06": DESIGN_REL,
    "DEV-01": IMPLEMENTATION_MANIFEST_REL,
    "DEV-18": MODULE_REGISTER_REL,
    "DOC-01": DOC01_REL,
}
APPENDED_BINDING_NAMES = (
    "fp048_implementation_result",
    "fp048_verification_result",
    "fp048_gap057_r023_successor",
)
EXPECTED_FORMAL_TEST_IDS = [f"TC-FP-048-{number:02d}" for number in range(1, 8)]
EXPECTED_BOUNDARY = {
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
FORBIDDEN_SOURCE_FRAGMENTS = (
    "apps/web/",
    "pwa/",
    "legacy",
    "r022",
    "plan-rebaseline",
    "submission",
    "independent-review",
    "review-attestation",
    "review-subject",
    "completion-receipt",
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
TRANSACTION_TOKEN = hashlib.sha256(SUCCESSOR_ID.encode("utf-8")).hexdigest()[:16]
TRANSACTION_STAGE_SUFFIX = f".fp048-{TRANSACTION_TOKEN}.stage"
TRANSACTION_BACKUP_SUFFIX = f".fp048-{TRANSACTION_TOKEN}.predecessor"
TRANSACTION_QUARANTINE_SUFFIX = f".fp048-{TRANSACTION_TOKEN}.foreign-collision"
TRANSACTION_JOURNAL_REL = Path(
    f".walksafe-fp048-artifact-trace-{TRANSACTION_TOKEN}.transaction.json"
)
PUBLICATION_CONCURRENCY_CONTRACT = {
    "contract_id": "WALKSAFE-FP048-COOPERATIVE-SINGLE-PUBLISHER-V1",
    "supported_concurrency_model": (
        "ONE_AUTHORIZED_PUBLISHER_HOLDING_THE_REPOSITORY_INODE_EXCLUSIVE_LOCK"
    ),
    "lock_primitive": "FLOCK_EX_ON_HELD_REPOSITORY_ROOT_FD",
    "lock_scope": "PREFLIGHT_THROUGH_POST_CLEANUP_SUCCESSOR_CHECK",
    "required_identity_checks": [
        "HELD_LOCK_FD",
        "REPOSITORY_ROOT_PATH",
        "HELD_OUTPUT_DIRECTORY_FDS",
        "TRANSACTION_MEMBER_RETAINED_FD_AND_PATH_BEFORE_AND_AFTER_MUTATION",
    ],
    "cooperative_contention_behavior": "SERIALIZE_UNDER_THE_SAME_REPOSITORY_INODE_LOCK",
    "unexpected_identity_change_behavior": "FAIL_CLOSED_WITHOUT_FOREIGN_OVERWRITE",
    "unsupported_threat_model": [
        "SAME_UID_ACTOR_IGNORING_THE_COOPERATIVE_LOCK",
        "HOSTILE_BASENAME_REPLACEMENT_INSIDE_A_TRUSTED_SYSCALL_BOUNDARY",
    ],
}


class BuildError(RuntimeError):
    """Raised when a pinned source or successor invariant differs."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def object_sha256(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    ).encode("utf-8")


def _reject_constant(value: str) -> None:
    raise BuildError(f"non-finite JSON value is forbidden: {value}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BuildError(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def _required_open_flag(name: str) -> int:
    value = getattr(os, name, None)
    require(isinstance(value, int) and value != 0, f"required open flag is unavailable: {name}")
    return value


def _validate_relative(relative: Path) -> None:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and "." not in relative.parts
        and ".." not in relative.parts
        and "\\" not in relative.as_posix(),
        f"unsafe repository-relative path: {relative}",
    )


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
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid)


def _read_descriptor(descriptor: int) -> bytes:
    chunks: list[bytes] = []
    offset = 0
    while chunk := os.pread(descriptor, 1024 * 1024, offset):
        chunks.append(chunk)
        offset += len(chunk)
    return b"".join(chunks)


class HeldFileWriteMonitor:
    """Detect byte writes to a held inode across metadata-changing syscalls."""

    _FORBIDDEN_MASK = 0x00000002 | 0x00000008 | 0x00004000
    _WATCH_MASK = _FORBIDDEN_MASK | 0x00000004 | 0x00000400 | 0x00000800

    def __init__(self, descriptor: int, label: str):
        libc = ctypes.CDLL(None, use_errno=True)
        init = getattr(libc, "inotify_init1", None)
        add_watch = getattr(libc, "inotify_add_watch", None)
        require(init is not None and add_watch is not None, "inotify inode monitoring is unavailable")
        init.argtypes = [ctypes.c_int]
        init.restype = ctypes.c_int
        add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
        add_watch.restype = ctypes.c_int
        monitor_fd = init(os.O_NONBLOCK | _required_open_flag("O_CLOEXEC"))
        if monitor_fd < 0:
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error), label)
        watch = add_watch(
            monitor_fd,
            os.fsencode(f"/proc/self/fd/{descriptor}"),
            self._WATCH_MASK,
        )
        if watch < 0:
            error = ctypes.get_errno()
            os.close(monitor_fd)
            raise OSError(error, os.strerror(error), label)
        self._descriptor = monitor_fd
        self._label = label
        self._closed = False
        self._drain(reject=False)

    def _drain(self, *, reject: bool) -> None:
        forbidden = 0
        while True:
            try:
                raw = os.read(self._descriptor, 64 * 1024)
            except BlockingIOError:
                break
            offset = 0
            while offset < len(raw):
                require(len(raw) - offset >= 16, f"inotify event is truncated: {self._label}")
                _watch, mask, _cookie, name_length = struct.unpack_from("iIII", raw, offset)
                offset += 16 + name_length
                require(offset <= len(raw), f"inotify event name is truncated: {self._label}")
                forbidden |= mask & self._FORBIDDEN_MASK
        if reject:
            require(forbidden == 0, f"held inode write activity detected: {self._label}")

    def verify(self) -> None:
        self._drain(reject=True)

    def close(self) -> None:
        if self._closed:
            return
        os.close(self._descriptor)
        self._closed = True


@dataclass
class _SnapshotEntry:
    relative: Path
    descriptor: int
    parent_parts: tuple[str, ...]
    info: os.stat_result
    raw: bytes
    predecessor: bool
    expected_mode: int | None


@dataclass
class PublicationLease:
    root: Path
    descriptor: int
    identity: tuple[int, ...]
    active: bool = True

    def verify(self) -> None:
        require(self.active, "publication lock lease is inactive")
        try:
            held = os.fstat(self.descriptor)
            fcntl.flock(self.descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            current = os.stat(self.root, follow_symlinks=False)
        except (OSError, ValueError) as exc:
            raise BuildError("publication lock FD or root path changed") from exc
        require(
            _directory_identity(held) == self.identity
            and _directory_identity(current) == self.identity,
            "publication lock FD or root path changed",
        )


_ACTIVE_PUBLICATION_LEASE: ContextVar[PublicationLease | None] = ContextVar(
    "walksafe_fp048_active_publication_lease",
    default=None,
)


class RepositorySnapshot:
    """One held-FD repository snapshot with path CAS verification."""

    def __init__(self, root: Path):
        self.root = root.resolve(strict=True)
        flags = (
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_DIRECTORY")
            | _required_open_flag("O_NOFOLLOW")
        )
        descriptor = os.open(self.root, flags)
        root_info = os.fstat(descriptor)
        require(
            stat.S_ISDIR(root_info.st_mode)
            and root_info.st_uid == os.geteuid()
            and not (stat.S_IMODE(root_info.st_mode) & 0o002),
            "repository root authority differs",
        )
        self._flags = flags
        self._directories: dict[tuple[str, ...], tuple[int, os.stat_result]] = {
            (): (descriptor, root_info)
        }
        self._directory_links: dict[
            tuple[str, ...], tuple[tuple[str, ...], str, tuple[int, ...]]
        ] = {}
        self._entries: dict[Path, _SnapshotEntry] = {}
        self._publication_lease: PublicationLease | None = None
        self._closed = False

    def __enter__(self) -> "RepositorySnapshot":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    def close(self) -> None:
        if self._closed:
            return
        for entry in self._entries.values():
            os.close(entry.descriptor)
        for descriptor, _info in reversed(list(self._directories.values())):
            os.close(descriptor)
        self._closed = True

    def directory_fd(self, relative: Path) -> int:
        parts = tuple(relative.parts)
        current: tuple[str, ...] = ()
        for part in parts:
            next_parts = (*current, part)
            if next_parts in self._directories:
                current = next_parts
                continue
            parent_fd = self._directories[current][0]
            try:
                before = os.stat(part, dir_fd=parent_fd, follow_symlinks=False)
            except FileNotFoundError as exc:
                raise BuildError(f"required directory is missing: {relative}") from exc
            require(
                stat.S_ISDIR(before.st_mode)
                and not stat.S_ISLNK(before.st_mode)
                and before.st_uid == os.geteuid()
                and not (stat.S_IMODE(before.st_mode) & 0o002),
                f"unsafe repository directory: {relative}",
            )
            child_fd = os.open(part, self._flags, dir_fd=parent_fd)
            opened = os.fstat(child_fd)
            require(
                _directory_identity(opened) == _directory_identity(before),
                f"directory changed while opening: {relative}",
            )
            self._directories[next_parts] = (child_fd, opened)
            self._directory_links[next_parts] = (
                current,
                part,
                _directory_identity(before),
            )
            current = next_parts
        return self._directories[parts][0]

    def bind_publication_lease(self, lease: PublicationLease) -> None:
        require(self._publication_lease is None, "publication lease is already bound")
        require(
            self.root == lease.root
            and _directory_identity(os.fstat(self.directory_fd(Path())))
            == lease.identity,
            "publication lease repository differs",
        )
        lease.verify()
        self._publication_lease = lease

    def capture(
        self,
        relative: Path,
        *,
        predecessor: bool = False,
        expected_mode: int | None = None,
    ) -> _SnapshotEntry:
        _validate_relative(relative)
        if relative in self._entries:
            entry = self._entries[relative]
            require(
                predecessor or not entry.predecessor,
                f"predecessor cannot be reused as strict source: {relative}",
            )
            if expected_mode is not None:
                require(
                    stat.S_IMODE(entry.info.st_mode) == expected_mode,
                    f"file mode differs: {relative}",
                )
            return entry
        parent_parts = tuple(relative.parent.parts)
        parent_fd = self.directory_fd(relative.parent)
        try:
            before = os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError as exc:
            raise BuildError(f"required file is missing: {relative}") from exc
        require(
            stat.S_ISREG(before.st_mode)
            and not stat.S_ISLNK(before.st_mode)
            and before.st_uid == os.geteuid(),
            f"unsafe regular file: {relative}",
        )
        if predecessor:
            require(before.st_nlink >= 1, f"invalid predecessor link count: {relative}")
            require(
                not (stat.S_IMODE(before.st_mode) & 0o002),
                f"world-writable predecessor is forbidden: {relative}",
            )
        else:
            require(before.st_nlink == 1, f"hard-linked source is forbidden: {relative}")
            require(
                not (stat.S_IMODE(before.st_mode) & 0o002),
                f"world-writable source is forbidden: {relative}",
            )
        if expected_mode is not None:
            require(
                stat.S_IMODE(before.st_mode) == expected_mode,
                f"file mode differs: {relative}",
            )
        descriptor = os.open(
            relative.name,
            os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
            dir_fd=parent_fd,
        )
        try:
            opened = os.fstat(descriptor)
            require(_identity(opened) == _identity(before), f"file changed while opening: {relative}")
            raw = _read_descriptor(descriptor)
            require(_identity(os.fstat(descriptor)) == _identity(opened), f"file changed while reading: {relative}")
            require(
                _identity(os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False))
                == _identity(before),
                f"file path changed while reading: {relative}",
            )
        except BaseException:
            os.close(descriptor)
            raise
        entry = _SnapshotEntry(
            relative,
            descriptor,
            parent_parts,
            opened,
            raw,
            predecessor,
            expected_mode,
        )
        self._entries[relative] = entry
        return entry

    def entry(self, relative: Path) -> _SnapshotEntry:
        require(relative in self._entries, f"file is outside held snapshot: {relative}")
        return self._entries[relative]

    def directory_path_is_current(self, relative: Path) -> bool:
        parts = tuple(relative.parts)
        if parts not in self._directories:
            return False
        try:
            current = os.stat(
                self.root / relative,
                follow_symlinks=False,
            )
        except OSError:
            return False
        return (
            stat.S_ISDIR(current.st_mode)
            and _directory_identity(current)
            == _directory_identity(self._directories[parts][1])
        )

    def verify_directory_paths(self) -> None:
        if self._publication_lease is not None:
            self._publication_lease.verify()
        require(
            self.directory_path_is_current(Path()),
            "repository root path changed",
        )
        for parts, (descriptor, info) in self._directories.items():
            require(
                _directory_identity(os.fstat(descriptor)) == _directory_identity(info),
                f"held directory changed: {'/'.join(parts) or '.'}",
            )
        for parts, (parent_parts, name, expected) in self._directory_links.items():
            parent_fd = self._directories[parent_parts][0]
            require(
                _directory_identity(os.stat(name, dir_fd=parent_fd, follow_symlinks=False))
                == expected,
                f"directory path changed: {'/'.join(parts)}",
            )
        if self._publication_lease is not None:
            self._publication_lease.verify()

    def verify(self, *, exclude_paths: set[Path] | None = None) -> None:
        excluded = set() if exclude_paths is None else exclude_paths
        self.verify_directory_paths()
        for relative, entry in self._entries.items():
            if relative in excluded:
                continue
            parent_fd = self._directories[entry.parent_parts][0]
            require(
                _identity(os.fstat(entry.descriptor)) == _identity(entry.info),
                f"held source changed: {relative}",
            )
            require(
                _read_descriptor(entry.descriptor) == entry.raw,
                f"held source bytes changed: {relative}",
            )
            require(
                _identity(os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False))
                == _identity(entry.info),
                f"source path changed: {relative}",
            )


def _json_from_bytes(raw: bytes, relative: Path) -> dict[str, Any]:
    require(not raw.startswith(b"\xef\xbb\xbf"), f"JSON BOM is forbidden: {relative}")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BuildError(f"invalid strict JSON: {relative}") from exc
    require(isinstance(value, dict), f"JSON root must be an object: {relative}")
    return value


def _snapshot_json(snapshot: RepositorySnapshot, relative: Path) -> dict[str, Any]:
    return _json_from_bytes(snapshot.entry(relative).raw, relative)


def _projection_sha(value: dict[str, Any], seal_key: str) -> str:
    return object_sha256({key: item for key, item in value.items() if key != seal_key})


def _set_projection_seal(value: dict[str, Any], seal_key: str) -> None:
    value.pop(seal_key, None)
    value[seal_key] = object_sha256(value)


def _verify_projection_seal(value: dict[str, Any], seal_key: str, label: str) -> None:
    require(value.get(seal_key) == _projection_sha(value, seal_key), f"{label} seal differs")


def _binding(
    snapshot: RepositorySnapshot,
    name: str,
    relative: Path,
    relation: str,
) -> dict[str, Any]:
    entry = snapshot.entry(relative)
    return {
        "name": name,
        "path": relative.as_posix(),
        "sha256": bytes_sha256(entry.raw),
        "byte_length": len(entry.raw),
        "relation": relation,
    }


def _input_bindings(
    snapshot: RepositorySnapshot,
    inputs: dict[Path, dict[str, Any]],
) -> list[dict[str, Any]]:
    bindings = [
        _binding(snapshot, APPENDED_BINDING_NAMES[0], IMPLEMENTATION_REL, "FP048_INTERNAL_IMPLEMENTATION_RESULT"),
        _binding(snapshot, APPENDED_BINDING_NAMES[1], VERIFICATION_REL, "FP048_INTERNAL_VERIFICATION_RESULT"),
        _binding(snapshot, APPENDED_BINDING_NAMES[2], GAP_REL, "GAP057_R023_SUCCESSOR_RESULT"),
    ]
    bindings[0]["document_id"] = inputs[IMPLEMENTATION_REL]["document_id"]
    bindings[1]["document_id"] = inputs[VERIFICATION_REL]["document_id"]
    bindings[2]["report_id"] = inputs[GAP_REL]["metadata"]["report_id"]
    return bindings


def _compact_bindings(bindings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {"name": item["name"], "path": item["path"], "sha256": item["sha256"]}
        for item in bindings
    ]


def _rtm_bindings(bindings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "path": item["path"],
            "sha256": item["sha256"],
            "byte_length": item["byte_length"],
            "relation": item["relation"],
        }
        for item in bindings
    ]


def _effective_input_pins(
    supplied: dict[Path, str] | None,
) -> dict[Path, str]:
    pins = dict(PINNED_INPUT_SHA256 if supplied is None else supplied)
    require(set(pins) == set(INPUT_PATHS), "input pin path set differs")
    for relative, digest in pins.items():
        require(
            isinstance(digest, str) and SHA256_RE.fullmatch(digest) is not None,
            f"input SHA is not pinned: {relative}",
        )
    return pins


def _forbidden_path(path: str) -> bool:
    normalized = posixpath.normpath(
        re.sub(r"/+", "/", path.replace("\\", "/").lower())
    )
    return any(fragment in normalized for fragment in FORBIDDEN_SOURCE_FRAGMENTS)


def _canonical_relative_path(path: str, label: str) -> Path:
    require(isinstance(path, str) and path, f"{label} path differs")
    require("\\" not in path, f"{label} path is noncanonical: {path}")
    relative = Path(path)
    _validate_relative(relative)
    require(relative.as_posix() == path, f"{label} path is noncanonical: {path}")
    return relative


def _validate_inputs_snapshot(
    snapshot: RepositorySnapshot,
    expected_input_sha256: dict[Path, str] | None = None,
    expected_document_ids: dict[Path, str] | None = None,
) -> dict[Path, dict[str, Any]]:
    pins = _effective_input_pins(expected_input_sha256)
    values: dict[Path, dict[str, Any]] = {}
    for relative in INPUT_PATHS:
        entry = snapshot.capture(relative)
        require(bytes_sha256(entry.raw) == pins[relative], f"input SHA differs: {relative}")
        values[relative] = _snapshot_json(snapshot, relative)

    implementation = values[IMPLEMENTATION_REL]
    verification = values[VERIFICATION_REL]
    gap = values[GAP_REL]
    ids = PINNED_INPUT_DOCUMENT_IDS if expected_document_ids is None else expected_document_ids
    for relative, value in ((IMPLEMENTATION_REL, implementation), (VERIFICATION_REL, verification)):
        expected_id = ids.get(relative)
        require(isinstance(value.get("document_id"), str), f"document ID is missing: {relative}")
        if expected_id and not expected_id.startswith("PENDING_"):
            require(value["document_id"] == expected_id, f"document ID differs: {relative}")

    require(implementation.get("goal_id") == GOAL_ID, "implementation Goal differs")
    require(implementation.get("kind") == "IMPLEMENTATION_RECORD", "implementation kind differs")
    require(implementation.get("status") == "PASS", "implementation status differs")
    changed = implementation.get("changed_artifacts")
    require(isinstance(changed, list) and changed, "implementation artifact set is empty")
    paths: list[str] = []
    content_rows: list[dict[str, str]] = []
    for row in changed:
        require(isinstance(row, dict), "implementation artifact row differs")
        relative_text = row.get("path")
        digest = row.get("after_sha256")
        require(isinstance(relative_text, str) and relative_text, "implementation path differs")
        relative = _canonical_relative_path(relative_text, "implementation")
        require(not _forbidden_path(relative_text), f"forbidden implementation scope: {relative_text}")
        before_source = row.get("before_source")
        require(
            before_source is None
            or (
                isinstance(before_source, str)
                and not _forbidden_path(before_source)
            ),
            f"forbidden implementation provenance: {before_source}",
        )
        require(relative_text not in {item.as_posix() for item in OUTPUT_PATHS}, "output is cyclic input")
        require(isinstance(digest, str) and SHA256_RE.fullmatch(digest) is not None, "implementation hash differs")
        entry = snapshot.capture(relative)
        require(bytes_sha256(entry.raw) == digest, f"implementation source differs: {relative_text}")
        paths.append(relative_text)
        content_rows.append({"path": relative_text, "sha256": digest})
    require(len(paths) == len(set(paths)), "implementation path is duplicated")
    require(
        implementation.get("implementation_content_set_sha256") == object_sha256(content_rows),
        "implementation content set differs",
    )

    require(verification.get("goal_id") == GOAL_ID, "verification Goal differs")
    require(verification.get("kind") == "VERIFICATION_RESULT", "verification kind differs")
    require(verification.get("status") == "PASS", "verification status differs")
    checks = verification.get("checks")
    require(isinstance(checks, list) and checks, "verification checks are empty")
    for row in checks:
        require(isinstance(row, dict) and row.get("exit_code") == 0, "verification check failed")
        require(
            row.get("implementation_content_set_sha256")
            == implementation["implementation_content_set_sha256"],
            "verification implementation binding differs",
        )
        output_text = row.get("output_path")
        output_hash = row.get("output_sha256")
        require(isinstance(output_text, str) and output_text, "verification output path differs")
        output_relative = _canonical_relative_path(output_text, "verification output")
        require(not _forbidden_path(output_text), f"forbidden verification scope: {output_text}")
        output = snapshot.capture(output_relative)
        require(
            isinstance(output_hash, str) and bytes_sha256(output.raw) == output_hash,
            f"verification evidence differs: {output_text}",
        )
    boundary = verification.get("evidence_boundary")
    require(isinstance(boundary, dict), "verification evidence boundary is missing")
    require(boundary.get("formal_test_ids") == EXPECTED_FORMAL_TEST_IDS, "formal test IDs differ")
    for key, expected in EXPECTED_BOUNDARY.items():
        require(boundary.get(key) == expected, f"verification boundary differs: {key}")

    require(gap.get("metadata", {}).get("report_id") == GAP_REPORT_ID, "r023 report ID differs")
    _verify_projection_seal(gap, "report_content_sha256", "r023 Gap")
    source_bindings = gap.get("source_bindings")
    require(isinstance(source_bindings, list), "r023 source bindings must be a list")
    r021_entry = snapshot.capture(R021_GAP_REL, predecessor=True)
    require(
        bytes_sha256(r021_entry.raw) == EXPECTED_R021_GAP_SHA256,
        "r021 Gap predecessor SHA differs",
    )
    r021_gap = _snapshot_json(snapshot, R021_GAP_REL)
    require(
        r021_gap.get("metadata", {}).get("report_id") == R021_GAP_REPORT_ID,
        "r021 Gap report ID differs",
    )
    _verify_projection_seal(r021_gap, "report_content_sha256", "r021 Gap predecessor")
    historical_bindings = r021_gap.get("source_bindings")
    require(
        isinstance(historical_bindings, list) and len(historical_bindings) == 85,
        "r021 historical source binding count differs",
    )
    require(
        r021_gap.get("source_binding_sha256") == object_sha256(historical_bindings),
        "r021 historical source binding seal differs",
    )
    current_count = len(EXPECTED_R023_CURRENT_SOURCE_BINDING_NAMES)
    require(
        len(source_bindings) == len(historical_bindings) + current_count
        and source_bindings[: len(historical_bindings)] == historical_bindings,
        "r023 historical source binding carry-forward differs",
    )
    require(
        all(isinstance(source, dict) for source in source_bindings),
        "r023 source binding row differs",
    )
    current_bindings = source_bindings[-current_count:]
    require(
        tuple(source.get("name") for source in current_bindings)
        == EXPECTED_R023_CURRENT_SOURCE_BINDING_NAMES,
        "r023 current source binding names differ",
    )
    require(
        gap.get("source_binding_sha256") == object_sha256(source_bindings),
        "r023 source binding seal differs",
    )
    source_paths: list[str] = []
    for source in current_bindings:
        require(isinstance(source, dict), "r023 source binding row differs")
        source_path = source.get("path")
        source_relative = _canonical_relative_path(source_path, "r023 source binding")
        require(not _forbidden_path(source_path), "r023 contains forbidden source")
        source_paths.append(source_path)
        source_entry = snapshot.capture(
            source_relative,
            predecessor=source.get("name")
            in EXPECTED_R023_CURRENT_SOURCE_BINDING_NAMES[:2],
        )
        source_sha256 = source.get("sha256")
        require(
            isinstance(source_sha256, str)
            and SHA256_RE.fullmatch(source_sha256) is not None
            and bytes_sha256(source_entry.raw) == source_sha256,
            f"r023 source SHA differs: {source_path}",
        )
        require(
            source.get("bytes") == len(source_entry.raw),
            f"r023 source byte length differs: {source_path}",
        )
        if source.get("binding_kind") == "CANONICAL_JSON_OBJECT_EXCLUDING_OWN_HASH_FIELD":
            content_sha256 = source.get("content_sha256")
            require(
                isinstance(content_sha256, str)
                and SHA256_RE.fullmatch(content_sha256) is not None,
                f"r023 source content hash differs: {source_path}",
            )
            source_value = _snapshot_json(snapshot, source_relative)
            require(
                source_value.get("record_content_sha256") == content_sha256
                and _projection_sha(source_value, "record_content_sha256") == content_sha256,
                f"r023 source projection differs: {source_path}",
            )
            require(
                source_entry.raw == json_bytes(source_value),
                f"r023 source JSON is noncanonical: {source_path}",
            )
    require(len(source_paths) == len(set(source_paths)), "r023 source binding path is duplicated")
    rows = [item for item in gap.get("assessments", []) if item.get("gap_id") == "GAP-057"]
    require(len(rows) == 1 and rows[0].get("status") == "PARTIAL", "GAP-057 status differs")
    reassessment = rows[0].get("fp048_reassessment")
    require(isinstance(reassessment, dict), "GAP-057 reassessment is missing")
    require(
        reassessment.get("implementation_record_sha256") == pins[IMPLEMENTATION_REL]
        and reassessment.get("verification_result_sha256") == pins[VERIFICATION_REL],
        "GAP-057 result binding differs",
    )
    require(
        reassessment.get("internal_implementation_status") == "PASS"
        and reassessment.get("internal_verification_status") == "PASS",
        "GAP-057 internal status differs",
    )
    for key, expected in EXPECTED_BOUNDARY.items():
        require(reassessment.get(key) == expected, f"GAP-057 boundary differs: {key}")
    return values


def validate_inputs(
    root: Path,
    expected_input_sha256: dict[Path, str] | None = None,
    expected_document_ids: dict[Path, str] | None = None,
) -> dict[Path, dict[str, Any]]:
    with RepositorySnapshot(root) as snapshot:
        values = _validate_inputs_snapshot(
            snapshot,
            expected_input_sha256,
            expected_document_ids,
        )
        snapshot.verify()
        return values


def _load_predecessors(snapshot: RepositorySnapshot) -> dict[Path, dict[str, Any]]:
    predecessors: dict[Path, dict[str, Any]] = {}
    for relative in OUTPUT_PATHS:
        entry = snapshot.capture(relative, predecessor=True)
        require(
            bytes_sha256(entry.raw) == PINNED_PREDECESSOR_SHA256[relative],
            f"predecessor SHA differs: {relative}",
        )
        predecessors[relative] = _snapshot_json(snapshot, relative)
    _verify_projection_seal(predecessors[DOC05_REL], "content_sha256", "DOC-05 predecessor")
    _verify_projection_seal(predecessors[DOC01_REL], "content_sha256", "DOC-01 predecessor")
    _verify_projection_seal(predecessors[RTM_REL], "document_content_sha256", "REQ-16 predecessor")
    _verify_projection_seal(predecessors[DESIGN_REL], "register_content_sha256", "DES-06 predecessor")
    return predecessors


def _trace(
    snapshot: RepositorySnapshot,
    relative: Path,
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    builder = snapshot.entry(BUILDER_REL)
    return {
        "successor_id": SUCCESSOR_ID,
        "prepared_on": PREPARED_ON,
        "predecessor": {
            "path": relative.as_posix(),
            "sha256": PINNED_PREDECESSOR_SHA256[relative],
        },
        "input_bindings": deepcopy(input_bindings),
        "builder": {
            "path": BUILDER_REL.as_posix(),
            "sha256": bytes_sha256(builder.raw),
            "byte_length": len(builder.raw),
        },
        "publication_concurrency_contract": deepcopy(
            PUBLICATION_CONCURRENCY_CONTRACT
        ),
        "claim_boundary": {
            "scope": "REPOSITORY_INTERNAL_FP048_SOURCE_AND_AUTOMATED_VERIFICATION_ONLY",
            "internal_implementation_status": "PASS",
            "internal_verification_status": "PASS",
            "formal_test_status": "NOT_RUN",
            "formal_test_credit_count": 0,
            "actual_device_status": "NOT_RUN",
            "actual_device_credit_count": 0,
            "external_verification_status": "NOT_RUN",
            "external_evidence_credit_count": 0,
            "production_deployment_status": "NOT_RUN",
            "production_credit_count": 0,
            "artifact_approval_claimed": False,
            "release_status": "NOT_ELIGIBLE",
            "release_credit_count": 0,
            "review_or_completion_inputs_included": False,
        },
    }


def _implementation_files(
    snapshot: RepositorySnapshot,
    implementation: dict[str, Any],
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for item in implementation["changed_artifacts"]:
        entry = snapshot.entry(Path(item["path"]))
        rows.append(
            {
                "path": item["path"],
                "sha256": item["after_sha256"],
                "byte_length": len(entry.raw),
                "before_sha256": item.get("before_sha256"),
                "before_source": item.get("before_source"),
                "change_kind": item.get("change_kind"),
            }
        )
    return rows


def _verification_files(
    snapshot: RepositorySnapshot,
    verification: dict[str, Any],
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for item in verification["checks"]:
        entry = snapshot.entry(Path(item["output_path"]))
        rows.append(
            {
                "name": item.get("name"),
                "path": item["output_path"],
                "sha256": item["output_sha256"],
                "byte_length": len(entry.raw),
                "exit_code": 0,
            }
        )
    return rows


def _build_doc05(
    snapshot: RepositorySnapshot,
    predecessor: dict[str, Any],
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(predecessor)
    require(value["summary"]["last_change_id"] == "CHG-DOC-0013", "DOC-05 predecessor event differs")
    value.pop("content_sha256", None)
    value["metadata"]["document_version"] = "1.0.3"
    value["metadata"]["as_of"] = PREPARED_ON
    value["metadata"]["updated_at"] = PREPARED_ON
    value["source_bindings"].extend(_compact_bindings(input_bindings))
    value["changes"].append(
        {
            "change_id": CHANGE_ID,
            "date": PREPARED_ON,
            "change_type": "FP048_INTERNAL_ARTIFACT_TRACE_SUCCESSOR",
            "title": "FP-048 내부 구현·검증을 exact257 요구·설계·구현 추적에 결속",
            "reason": (
                "FP-048 저장·전송 보안 구현과 저장소 내부 자동 검증 결과를 현재 "
                "물리 산출물에 연결하되 정식·실기기·외부·운영·출시 증거로 과장하지 않기 위함"
            ),
            "before_summary": (
                "RQ-FP-048-001의 코드·증거 연결이 비어 있고 DES-06 구현 적합성은 "
                "미평가이며 현행 DEV-18에는 독립 Android Gateway 경계가 없다."
            ),
            "after_summary": (
                "DOC-05를 먼저 self-seal하고 REQ-16, DES-06, DEV-01, DEV-18을 "
                "내부-only로 갱신한 뒤 DOC-01이 다섯 physical successor를 재결속한다. "
                "정식·실기기·외부·운영·승인·출시 credit은 모두 0이다."
            ),
            "affected_artifact_codes": ["DOC-05", "DOC-01", "REQ-16", "DES-06", "DEV-01", "DEV-18"],
            "affected_paths": [item.as_posix() for item in OUTPUT_PATHS],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
            "requested_by": "PROJECT_SCOPE_OWNER",
            "affected_requirement_ids": ["RQ-FP-048-001"],
            "affected_test_ids": EXPECTED_FORMAL_TEST_IDS,
            "review": {
                "review_status": "PENDING",
                "reviewer": None,
                "approval_status": "NOT_APPROVED",
                "approval_record": None,
            },
            "application": {
                "document_version": "1.0.3",
                "source_commit": None,
                "baseline_id": None,
                "successor_id": SUCCESSOR_ID,
                "predecessor_file_sha256_by_path": {
                    path.as_posix(): PINNED_PREDECESSOR_SHA256[path] for path in OUTPUT_PATHS
                },
                "input_bindings": deepcopy(input_bindings),
                "publication_order": [item.as_posix() for item in OUTPUT_PATHS],
                "internal_implementation_status": "PASS",
                "internal_verification_status": "PASS",
                "acceptance_credit_count": 0,
                "approval_credit_count": 0,
                "execution_credit_count": 0,
                "actual_event_credit_count": 0,
                "actual_device_credit_count": 0,
                "external_evidence_credit_count": 0,
                "formal_evidence_credit_count": 0,
                "release_credit_count": 0,
            },
            "rollback_or_supersedes": None,
        }
    )
    value["summary"] = {
        **value["summary"],
        "change_count": value["summary"]["change_count"] + 1,
        "last_change_id": CHANGE_ID,
    }
    value["fp048_artifact_trace_successor"] = _trace(snapshot, DOC05_REL, input_bindings)
    _set_projection_seal(value, "content_sha256")
    return value


def _build_rtm(
    snapshot: RepositorySnapshot,
    predecessor: dict[str, Any],
    inputs: dict[Path, dict[str, Any]],
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(predecessor)
    value.pop("document_content_sha256", None)
    value["source_bindings"].extend(_rtm_bindings(input_bindings))
    value["source_binding_sha256"] = object_sha256(value["source_bindings"])
    rows = [item for item in value["requirements"] if item.get("requirement_id") == "RQ-FP-048-001"]
    require(len(rows) == 1, "RQ-FP-048-001 row count differs")
    row = rows[0]
    predecessor_fields = {
        "code_trace": deepcopy(row["code_trace"]),
        "evidence_trace": deepcopy(row["evidence_trace"]),
        "implementation_observation": row["implementation_observation"],
        "verification_status": row["verification_status"],
    }
    implementation_files = _implementation_files(snapshot, inputs[IMPLEMENTATION_REL])
    verification_files = _verification_files(snapshot, inputs[VERIFICATION_REL])
    row["code_trace"] = {
        "status": "INTERNAL_EXACT_SOURCE_LINKED_FORMAL_NOT_RUN",
        "links": [
            {
                "path": item["path"],
                "sha256": item["sha256"],
                "byte_length": item["byte_length"],
                "change_kind": item["change_kind"],
                "trace_status": "INTERNAL_IMPLEMENTATION_SOURCE_ONLY",
            }
            for item in implementation_files
        ],
    }
    row["evidence_trace"] = {
        "status": "INTERNAL_AUTOMATED_EVIDENCE_LINKED_FORMAL_NOT_RUN",
        "links": [
            *deepcopy(input_bindings),
            *[
                {**item, "trace_status": "INTERNAL_AUTOMATED_VERIFICATION_ONLY"}
                for item in verification_files
            ],
        ],
    }
    row["implementation_observation"] = (
        "FP-048의 비-Web Android 사용자 앱, 독립 Android Gateway, backend와 백업 "
        "경계에 대한 exact source 및 저장소 내부 자동 검증 PASS를 결속했다. "
        "TC-FP-048-01~07, 실제 기기, 외부 TLS/KMS·분리 복원·보안/법률 검토, "
        "운영 배포와 출시 Gate는 NOT_RUN이다."
    )
    row["verification_status"] = "INTERNAL_VERIFICATION_PASS_FORMAL_NOT_RUN"
    row["verification_completion_claimed"] = False
    row["fp048_internal_trace"] = {
        "successor_id": SUCCESSOR_ID,
        "predecessor_fields": predecessor_fields,
        "formal_test_ids": EXPECTED_FORMAL_TEST_IDS,
        **EXPECTED_BOUNDARY,
    }
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    value["requirement_binding_sha256"] = object_sha256(value["requirements"])
    value["fp048_artifact_trace_successor"] = _trace(snapshot, RTM_REL, input_bindings)
    _set_projection_seal(value, "document_content_sha256")
    return value


def _responsibility_bindings(files: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    groups = {
        "MOD-ANDROID-USER": [],
        "MOD-ANDROID-GATEWAY": [],
        "MOD-BACKEND": [],
        "BACKUP_AND_ENGINEERING_EVIDENCE": [],
    }
    for item in files:
        path = item["path"]
        binding = {"path": path, "sha256": item["sha256"], "byte_length": item["byte_length"]}
        if path.startswith("apps/android/app/") or path.startswith("apps/android/"):
            groups["MOD-ANDROID-USER"].append(binding)
        elif path.startswith("apps/android-gateway/"):
            groups["MOD-ANDROID-GATEWAY"].append(binding)
        elif path.startswith("backend/") or path.startswith("contracts/"):
            groups["MOD-BACKEND"].append(binding)
        else:
            groups["BACKUP_AND_ENGINEERING_EVIDENCE"].append(binding)
    return groups


def _build_design(
    snapshot: RepositorySnapshot,
    predecessor: dict[str, Any],
    inputs: dict[Path, dict[str, Any]],
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(predecessor)
    value.pop("register_content_sha256", None)
    for binding in input_bindings:
        value["source_bindings"][binding["name"]] = {
            "path": binding["path"],
            "sha256": binding["sha256"],
            "byte_length": binding["byte_length"],
            "relation": binding["relation"],
        }
    rows = [item for item in value["records"] if item.get("design_id") == "DES-06"]
    require(len(rows) == 1, "DES-06 row count differs")
    row = rows[0]
    predecessor_fields = {
        "implementation_conformance_status": row["implementation_conformance_status"],
    }
    files = _implementation_files(snapshot, inputs[IMPLEMENTATION_REL])
    row["implementation_conformance_status"] = (
        "INTERNAL_FP048_CONFORMANCE_VERIFIED_FORMAL_NOT_RUN"
    )
    row["verification_status"] = "NOT_RUN"
    row["test_completion_claimed"] = False
    row["fp048_internal_conformance"] = {
        "successor_id": SUCCESSOR_ID,
        "predecessor_fields": predecessor_fields,
        "responsibility_boundaries": {
            "MOD-ANDROID-USER": "AndroidKeyStore 기반 단말 민감상태 암호화와 cleartext fail-close",
            "MOD-ANDROID-GATEWAY": "Gateway 영속상태 암호화·열쇠 회전/폐기·접근감사 경계",
            "MOD-BACKEND": "서버 원본·감사·사고대응과 외부 key material 분리 경계",
            "BACKUP_AND_ENGINEERING_EVIDENCE": "백업 암호화·복원 절차와 내부 검증 증거 경계",
        },
        "source_bindings_by_responsibility": _responsibility_bindings(files),
        "internal_verification_result": deepcopy(input_bindings[1]),
        "formal_test_ids": EXPECTED_FORMAL_TEST_IDS,
        **EXPECTED_BOUNDARY,
        "test_completion_claimed": False,
    }
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    value["fp048_artifact_trace_successor"] = _trace(snapshot, DESIGN_REL, input_bindings)
    _set_projection_seal(value, "register_content_sha256")
    return value


def _build_implementation_manifest(
    snapshot: RepositorySnapshot,
    predecessor: dict[str, Any],
    inputs: dict[Path, dict[str, Any]],
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(predecessor)
    files = _implementation_files(snapshot, inputs[IMPLEMENTATION_REL])
    evidence = _verification_files(snapshot, inputs[VERIFICATION_REL])
    exact_snapshot = {
        "binding_kind": "FP048_NON_WEB_EXACT_SOURCE_AND_EVIDENCE_SNAPSHOT",
        "scope": "REPOSITORY_INTERNAL_ONLY",
        "source_file_count": len(files),
        "source_path_set_sha256": object_sha256([item["path"] for item in files]),
        "source_content_set_sha256": object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in files]
        ),
        "source_files": files,
        "evidence_file_count": len(evidence),
        "evidence_content_set_sha256": object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in evidence]
        ),
        "evidence_files": evidence,
        "producer_inputs": deepcopy(input_bindings),
        "excluded_scopes": [
            "Web/PWA product or evidence",
            "submission artifacts",
            "independent review or attestation",
            "Goal completion receipt",
        ],
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_verification_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
    }
    exact_snapshot["snapshot_sha256"] = object_sha256(exact_snapshot)
    value["source_snapshot"]["fp048_internal_exact_snapshot"] = exact_snapshot
    value["fp048_artifact_trace_successor"] = _trace(
        snapshot, IMPLEMENTATION_MANIFEST_REL, input_bindings
    )
    _set_projection_seal(value, "fp048_successor_content_sha256")
    return value


def _module_trace(
    module_id: str,
    files: list[dict[str, Any]],
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    if module_id == "MOD-ANDROID-USER":
        selected = [item for item in files if item["path"].startswith("apps/android/")]
    elif module_id == "MOD-ANDROID-GATEWAY":
        selected = [item for item in files if item["path"].startswith("apps/android-gateway/")]
    else:
        selected = [
            item
            for item in files
            if item["path"].startswith(("backend/", "contracts/"))
        ]
    return {
        "successor_id": SUCCESSOR_ID,
        "module_id": module_id,
        "source_file_count": len(selected),
        "source_content_set_sha256": object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in selected]
        ),
        "source_files": selected,
        "implementation_result": deepcopy(input_bindings[0]),
        "verification_result": deepcopy(input_bindings[1]),
        "internal_verification_status": "PASS",
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_verification_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
    }


def _link(requirement_id: str) -> dict[str, str]:
    path = "docs/deliverables/03-requirements/system-requirements.md"
    return {
        "requirement_id": requirement_id,
        "path": path,
        "anchor": requirement_id,
        "target": f"{path}#{requirement_id}",
        "trace_status": "DRAFT_NOT_APPROVED",
    }


def _design_link(design_id: str, path: str) -> dict[str, str]:
    anchor = design_id.lower()
    return {
        "design_id": design_id,
        "path": path,
        "anchor": anchor,
        "target": f"{path}#{anchor}",
        "trace_status": "DRAFT_NOT_APPROVED",
    }


def _gateway_module(
    files: list[dict[str, Any]],
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    trace = _module_trace("MOD-ANDROID-GATEWAY", files, input_bindings)
    design_ids = ["DES-01", "DES-03", "DES-05", "DES-06", "DES-09", "DES-19"]
    architecture = "docs/deliverables/04-design/software-architecture.md"
    interface = "docs/deliverables/04-design/interface-and-data-design.md"
    security = "docs/deliverables/04-design/security-and-operations-design.md"
    design_paths = {
        "DES-01": architecture,
        "DES-03": architecture,
        "DES-05": architecture,
        "DES-06": architecture,
        "DES-09": interface,
        "DES-19": security,
    }
    return {
        "module_id": "MOD-ANDROID-GATEWAY",
        "name": "독립 Android API Gateway",
        "paths": ["apps/android-gateway"],
        "policy_role": "Android 사용자 앱과 backend 사이의 독립 보안·세션·개인정보 경계",
        "current_fact": "Node.js/TypeScript Gateway 소스와 FP-048 내부 자동 검증 결과가 존재한다.",
        "alignment_status": "INTERNAL_FP048_VERIFIED_FORMAL_NOT_RUN",
        "languages": ["TypeScript", "Node.js"],
        "responsibility": "세션·동의·보행·신고·삭제 요청의 암호화 영속상태와 fail-close 경계를 처리한다.",
        "inputs": ["Android HTTPS 요청", "외부 keyring", "backend 응답"],
        "outputs": ["인증·동의·보행·신고·삭제 중계", "암호화 영속상태", "감사 이벤트"],
        "owner_role": "Android Gateway 개발책임자",
        "policy_refs": ["FP-048"],
        "design_refs": design_ids,
        "build_artifacts": ["Node.js/TypeScript build 후보 — 정식 build·배포 hash 아님"],
        "deployment_artifacts": ["Gateway 서비스 후보 — NOT_DEPLOYED"],
        "deprecation_status": "ACTIVE_SERVICE_CANDIDATE_NOT_DEPLOYED",
        "replacement_module_id": None,
        "requirement_refs": ["RQ-FP-048-001"],
        "requirement_links": [_link("RQ-FP-048-001")],
        "requirement_trace_status": "INTERNAL_SOURCE_LINKED_DRAFT_REQUIREMENT",
        "design_links": [_design_link(item, design_paths[item]) for item in design_ids],
        "design_candidate_refs": design_ids,
        "tracked_file_count": trace["source_file_count"],
        "sample_paths": [item["path"] for item in trace["source_files"][:20]],
        "design_trace_status": "INTERNAL_CONFORMANCE_LINKED_DRAFT_DESIGN",
        "link_validation_status": "INTERNAL_ONLY_FORMAL_NOT_RUN",
        "integration_report_path": "docs/deliverables/traceability/req-des-tst-integration-report-20260721-r001.json",
        "source_issue_ids": [],
        "change_tracking_refs": [],
        "policy_correction_candidate_refs": [],
        "direct_policy_correction_candidate_refs": [],
        "related_policy_correction_candidate_refs": [],
        "fp035_dependency_relation": None,
        "correction_candidate_binding": None,
        "bundled_approval_dependency_refs": [],
        "required_activation_event": None,
        "implementation_blockers": [],
        "mobile_data_branch_status": "NOT_APPLICABLE",
        "mobile_network_branch_implementation_status": None,
        "mobile_network_branch_formal_test_status": None,
        "formal_branch_implementation_and_test_frozen": False,
        "normalized_mobile_data_policy": None,
        "inventory_status": "FP048_INTERNAL_CHANGED_SOURCE_SNAPSHOT_ONLY",
        "fp048_internal_trace": trace,
    }


def _build_module_register(
    snapshot: RepositorySnapshot,
    predecessor: dict[str, Any],
    inputs: dict[Path, dict[str, Any]],
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(predecessor)
    files = _implementation_files(snapshot, inputs[IMPLEMENTATION_REL])
    by_id = {item["module_id"]: item for item in value["modules"]}
    require("MOD-ANDROID-GATEWAY" not in by_id, "Gateway predecessor unexpectedly exists")
    for module_id in ("MOD-ANDROID-USER", "MOD-BACKEND"):
        require(module_id in by_id, f"module predecessor is missing: {module_id}")
        by_id[module_id]["fp048_internal_trace"] = _module_trace(
            module_id, files, input_bindings
        )
    value["modules"].append(_gateway_module(files, input_bindings))
    value["fp048_artifact_trace_successor"] = _trace(snapshot, MODULE_REGISTER_REL, input_bindings)
    _set_projection_seal(value, "fp048_successor_content_sha256")
    return value


def _build_doc01(
    snapshot: RepositorySnapshot,
    predecessor: dict[str, Any],
    input_bindings: list[dict[str, Any]],
    already_built: dict[Path, bytes],
) -> dict[str, Any]:
    value = deepcopy(predecessor)
    value.pop("content_sha256", None)
    value["metadata"]["document_version"] = "1.0.3"
    value["metadata"]["as_of"] = PREPARED_ON
    value["metadata"]["updated_at"] = PREPARED_ON
    value["source_bindings"].extend(_compact_bindings(input_bindings))
    output_bindings = {
        relative.as_posix(): {
            "sha256": bytes_sha256(already_built[relative]),
            "byte_length": len(already_built[relative]),
        }
        for relative in OUTPUT_PATHS[:-1]
    }
    trace = _trace(snapshot, DOC01_REL, input_bindings)
    trace["bound_successor_outputs"] = output_bindings
    trace["self_physical_sha256_excluded"] = True
    value["fp048_artifact_trace_successor"] = trace
    rows = {item["display_code"]: item for item in value["artifacts"]}
    builder = snapshot.entry(BUILDER_REL)
    for code, physical_path in TARGET_ARTIFACT_PATHS.items():
        require(code in rows, f"artifact row is missing: {code}")
        row = rows[code]
        predecessor_integrity = deepcopy(row["integrity"])
        if physical_path == DOC01_REL:
            successor_hash = None
            byte_length = None
            status = "SELF_PHYSICAL_SHA256_EXCLUDED_TO_AVOID_CYCLE"
        else:
            successor_hash = output_bindings[physical_path.as_posix()]["sha256"]
            byte_length = output_bindings[physical_path.as_posix()]["byte_length"]
            status = "BOUND_TO_FP048_PHYSICAL_SUCCESSOR"
        row["integrity"] = {
            "sha256": successor_hash,
            "generator": BUILDER_REL.as_posix(),
            "generator_sha256": bytes_sha256(builder.raw),
            "last_verified_at": PREPARED_ON,
        }
        row["fp048_internal_rebinding"] = {
            "successor_id": SUCCESSOR_ID,
            "physical_path": physical_path.as_posix(),
            "physical_sha256": successor_hash,
            "byte_length": byte_length,
            "binding_status": status,
            "predecessor_integrity": predecessor_integrity,
            "formal_evidence_credit_count": 0,
            "release_credit_count": 0,
        }
    _set_projection_seal(value, "content_sha256")
    return value


def _build_from_predecessors(
    snapshot: RepositorySnapshot,
    predecessors: dict[Path, dict[str, Any]],
    inputs: dict[Path, dict[str, Any]],
) -> dict[Path, bytes]:
    bindings = _input_bindings(snapshot, inputs)
    values: dict[Path, dict[str, Any]] = {
        DOC05_REL: _build_doc05(snapshot, predecessors[DOC05_REL], bindings),
        RTM_REL: _build_rtm(snapshot, predecessors[RTM_REL], inputs, bindings),
        DESIGN_REL: _build_design(snapshot, predecessors[DESIGN_REL], inputs, bindings),
        IMPLEMENTATION_MANIFEST_REL: _build_implementation_manifest(
            snapshot, predecessors[IMPLEMENTATION_MANIFEST_REL], inputs, bindings
        ),
        MODULE_REGISTER_REL: _build_module_register(
            snapshot, predecessors[MODULE_REGISTER_REL], inputs, bindings
        ),
    }
    outputs = {relative: json_bytes(values[relative]) for relative in OUTPUT_PATHS[:-1]}
    values[DOC01_REL] = _build_doc01(
        snapshot, predecessors[DOC01_REL], bindings, outputs
    )
    outputs[DOC01_REL] = json_bytes(values[DOC01_REL])
    require(tuple(outputs) == OUTPUT_PATHS, "output order differs")
    return outputs


def build_outputs(
    root: Path = ROOT,
    *,
    expected_input_sha256: dict[Path, str] | None = None,
    expected_document_ids: dict[Path, str] | None = None,
) -> dict[Path, bytes]:
    outputs, snapshot = _prepare_predecessor_build(
        root,
        expected_input_sha256,
        expected_document_ids,
    )
    snapshot.close()
    return outputs


def _prepare_predecessor_build(
    root: Path,
    expected_input_sha256: dict[Path, str] | None,
    expected_document_ids: dict[Path, str] | None,
) -> tuple[dict[Path, bytes], RepositorySnapshot]:
    snapshot = RepositorySnapshot(root)
    try:
        snapshot.capture(BUILDER_REL)
        for relative in INPUT_PATHS:
            snapshot.capture(relative)
        for relative in OUTPUT_PATHS:
            snapshot.capture(relative, predecessor=True)
        inputs = _validate_inputs_snapshot(
            snapshot,
            expected_input_sha256,
            expected_document_ids,
        )
        predecessors = _load_predecessors(snapshot)
        outputs = _build_from_predecessors(snapshot, predecessors, inputs)
        _validate_built_outputs(snapshot.root, predecessors, inputs, outputs)
        snapshot.verify()
        return outputs, snapshot
    except BaseException:
        snapshot.close()
        raise


def _decode_output(relative: Path, raw: bytes) -> dict[str, Any]:
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BuildError(f"generated JSON is invalid: {relative}") from exc
    require(isinstance(value, dict), f"generated root differs: {relative}")
    require(raw == json_bytes(value), f"generated JSON serialization differs: {relative}")
    return value


def _validate_built_outputs(
    root: Path,
    predecessors: dict[Path, dict[str, Any]],
    inputs: dict[Path, dict[str, Any]],
    outputs: dict[Path, bytes],
) -> None:
    require(tuple(outputs) == OUTPUT_PATHS, "generated output set/order differs")
    values = {relative: _decode_output(relative, outputs[relative]) for relative in OUTPUT_PATHS}
    _verify_projection_seal(values[DOC05_REL], "content_sha256", "DOC-05")
    _verify_projection_seal(values[DOC01_REL], "content_sha256", "DOC-01")
    _verify_projection_seal(values[RTM_REL], "document_content_sha256", "REQ-16")
    _verify_projection_seal(values[DESIGN_REL], "register_content_sha256", "DES-06")
    _verify_projection_seal(
        values[IMPLEMENTATION_MANIFEST_REL],
        "fp048_successor_content_sha256",
        "DEV-01",
    )
    _verify_projection_seal(
        values[MODULE_REGISTER_REL], "fp048_successor_content_sha256", "DEV-18"
    )
    require(values[DOC05_REL]["changes"][:-1] == predecessors[DOC05_REL]["changes"], "DOC-05 history changed")
    require(values[DOC05_REL]["changes"][-1]["change_id"] == CHANGE_ID, "DOC-05 append differs")

    old_rtm = {item["requirement_id"]: item for item in predecessors[RTM_REL]["requirements"]}
    new_rtm = {item["requirement_id"]: item for item in values[RTM_REL]["requirements"]}
    require(set(old_rtm) == set(new_rtm), "RTM row set differs")
    require(
        all(old_rtm[key] == new_rtm[key] for key in old_rtm if key != "RQ-FP-048-001"),
        "non-FP048 RTM row changed",
    )
    fp048 = new_rtm["RQ-FP-048-001"]
    require(fp048["verification_completion_claimed"] is False, "formal completion was claimed")
    require(
        all(
            item["test_execution_status"] == "NOT_RUN" and item["pass_claimed"] is False
            for item in fp048["acceptance_conditions"]
        ),
        "formal FP048 test state changed",
    )

    old_design = {item["design_id"]: item for item in predecessors[DESIGN_REL]["records"]}
    new_design = {item["design_id"]: item for item in values[DESIGN_REL]["records"]}
    require(set(old_design) == set(new_design), "design row set differs")
    require(
        all(old_design[key] == new_design[key] for key in old_design if key != "DES-06"),
        "non-DES06 record changed",
    )
    des06 = new_design["DES-06"]
    require(des06["verification_status"] == "NOT_RUN", "DES-06 formal status changed")
    require(des06["test_completion_claimed"] is False, "DES-06 completion was claimed")

    old_modules = predecessors[MODULE_REGISTER_REL]["modules"]
    new_modules = values[MODULE_REGISTER_REL]["modules"]
    old_by_id = {item["module_id"]: item for item in old_modules}
    new_by_id = {item["module_id"]: item for item in new_modules}
    require(set(new_by_id) == {*old_by_id, "MOD-ANDROID-GATEWAY"}, "module set differs")
    require(
        all(
            old_by_id[key] == new_by_id[key]
            for key in old_by_id
            if key not in {"MOD-ANDROID-USER", "MOD-BACKEND"}
        ),
        "unrelated module changed",
    )
    require(new_by_id["MOD-WEB-LEGACY"] == old_by_id["MOD-WEB-LEGACY"], "Web row changed")

    old_artifacts = {item["display_code"]: item for item in predecessors[DOC01_REL]["artifacts"]}
    new_artifacts = {item["display_code"]: item for item in values[DOC01_REL]["artifacts"]}
    require(set(old_artifacts) == set(new_artifacts), "exact257 row set differs")
    require(
        all(
            old_artifacts[key] == new_artifacts[key]
            for key in old_artifacts
            if key not in TARGET_ARTIFACT_PATHS
        ),
        "non-target exact257 row changed",
    )
    for code, relative in TARGET_ARTIFACT_PATHS.items():
        row = new_artifacts[code]
        if relative == DOC01_REL:
            require(row["integrity"]["sha256"] is None, "DOC-01 self hash must be excluded")
        else:
            require(
                row["integrity"]["sha256"] == bytes_sha256(outputs[relative]),
                f"DOC-01 physical binding differs: {code}",
            )

    for value in values.values():
        trace = value["fp048_artifact_trace_successor"]
        require(
            trace.get("publication_concurrency_contract")
            == PUBLICATION_CONCURRENCY_CONTRACT,
            "publication concurrency contract differs",
        )
        boundary = trace["claim_boundary"]
        require(boundary["formal_test_credit_count"] == 0, "formal credit differs")
        require(boundary["actual_device_credit_count"] == 0, "device credit differs")
        require(boundary["external_evidence_credit_count"] == 0, "external credit differs")
        require(boundary["release_credit_count"] == 0, "release credit differs")
        require(boundary["review_or_completion_inputs_included"] is False, "cyclic input included")


def _recover_predecessors(successors: dict[Path, dict[str, Any]]) -> dict[Path, dict[str, Any]]:
    recovered = deepcopy(successors)

    doc05 = recovered[DOC05_REL]
    require(doc05.get("fp048_artifact_trace_successor", {}).get("successor_id") == SUCCESSOR_ID, "DOC-05 successor marker differs")
    require(doc05["changes"][-1].get("change_id") == CHANGE_ID, "DOC-05 terminal change differs")
    doc05.pop("content_sha256", None)
    doc05.pop("fp048_artifact_trace_successor")
    del doc05["source_bindings"][-len(APPENDED_BINDING_NAMES):]
    doc05["changes"].pop()
    doc05["metadata"]["document_version"] = "1.0.2"
    doc05["metadata"]["as_of"] = "2026-07-28"
    doc05["metadata"]["updated_at"] = "2026-07-28"
    doc05["summary"]["change_count"] -= 1
    doc05["summary"]["last_change_id"] = "CHG-DOC-0013"
    _set_projection_seal(doc05, "content_sha256")

    doc01 = recovered[DOC01_REL]
    require(doc01.get("fp048_artifact_trace_successor", {}).get("successor_id") == SUCCESSOR_ID, "DOC-01 successor marker differs")
    doc01.pop("content_sha256", None)
    doc01.pop("fp048_artifact_trace_successor")
    del doc01["source_bindings"][-len(APPENDED_BINDING_NAMES):]
    doc01["metadata"]["document_version"] = "1.0.2"
    doc01["metadata"]["as_of"] = "2026-07-28"
    doc01["metadata"]["updated_at"] = "2026-07-28"
    for row in doc01["artifacts"]:
        if row.get("display_code") in TARGET_ARTIFACT_PATHS:
            binding = row.pop("fp048_internal_rebinding")
            row["integrity"] = binding["predecessor_integrity"]
    _set_projection_seal(doc01, "content_sha256")

    rtm = recovered[RTM_REL]
    require(rtm.get("fp048_artifact_trace_successor", {}).get("successor_id") == SUCCESSOR_ID, "REQ-16 successor marker differs")
    rtm.pop("document_content_sha256", None)
    rtm.pop("fp048_artifact_trace_successor")
    del rtm["source_bindings"][-len(APPENDED_BINDING_NAMES):]
    rtm["source_binding_sha256"] = object_sha256(rtm["source_bindings"])
    row = next(item for item in rtm["requirements"] if item.get("requirement_id") == "RQ-FP-048-001")
    trace = row.pop("fp048_internal_trace")
    for key, value in trace["predecessor_fields"].items():
        row[key] = value
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    rtm["requirement_binding_sha256"] = object_sha256(rtm["requirements"])
    _set_projection_seal(rtm, "document_content_sha256")

    design = recovered[DESIGN_REL]
    require(design.get("fp048_artifact_trace_successor", {}).get("successor_id") == SUCCESSOR_ID, "DES-06 successor marker differs")
    design.pop("register_content_sha256", None)
    design.pop("fp048_artifact_trace_successor")
    for name in APPENDED_BINDING_NAMES:
        design["source_bindings"].pop(name)
    row = next(item for item in design["records"] if item.get("design_id") == "DES-06")
    trace = row.pop("fp048_internal_conformance")
    for key, value in trace["predecessor_fields"].items():
        row[key] = value
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    _set_projection_seal(design, "register_content_sha256")
    foundation = design.pop("phase1_ready_foundation_binding")
    seal = design.pop("register_content_sha256")
    design["register_content_sha256"] = seal
    design["phase1_ready_foundation_binding"] = foundation

    manifest = recovered[IMPLEMENTATION_MANIFEST_REL]
    manifest.pop("fp048_successor_content_sha256")
    manifest.pop("fp048_artifact_trace_successor")
    manifest["source_snapshot"].pop("fp048_internal_exact_snapshot")

    modules = recovered[MODULE_REGISTER_REL]
    modules.pop("fp048_successor_content_sha256")
    modules.pop("fp048_artifact_trace_successor")
    require(modules["modules"][-1].get("module_id") == "MOD-ANDROID-GATEWAY", "Gateway append order differs")
    modules["modules"].pop()
    for row in modules["modules"]:
        if row.get("module_id") in {"MOD-ANDROID-USER", "MOD-BACKEND"}:
            row.pop("fp048_internal_trace")

    for relative, value in recovered.items():
        require(
            bytes_sha256(json_bytes(value)) == PINNED_PREDECESSOR_SHA256[relative],
            f"recovered predecessor differs: {relative}",
        )
    return recovered


def check_successor(
    root: Path = ROOT,
    *,
    expected_input_sha256: dict[Path, str] | None = None,
    expected_document_ids: dict[Path, str] | None = None,
) -> dict[Path, bytes]:
    snapshot = RepositorySnapshot(root)
    try:
        snapshot.capture(BUILDER_REL)
        for relative in INPUT_PATHS:
            snapshot.capture(relative)
        actual: dict[Path, bytes] = {}
        values: dict[Path, dict[str, Any]] = {}
        for relative in OUTPUT_PATHS:
            entry = snapshot.capture(relative, expected_mode=0o600)
            actual[relative] = entry.raw
            values[relative] = _snapshot_json(snapshot, relative)
            require(
                actual[relative] == json_bytes(values[relative]),
                f"noncanonical successor JSON: {relative}",
            )
        inputs = _validate_inputs_snapshot(
            snapshot,
            expected_input_sha256,
            expected_document_ids,
        )
        predecessors = _recover_predecessors(values)
        expected = _build_from_predecessors(snapshot, predecessors, inputs)
        _validate_built_outputs(snapshot.root, predecessors, inputs, expected)
        for relative in OUTPUT_PATHS:
            require(actual[relative] == expected[relative], f"successor bytes differ: {relative}")
        snapshot.verify()
        return actual
    finally:
        snapshot.close()


def _transaction_member_relative(relative: Path, kind: str) -> Path:
    require(
        kind in {"stage", "backup", "authority", "quarantine"},
        f"transaction member kind differs: {kind}",
    )
    if kind == "stage":
        suffix = TRANSACTION_STAGE_SUFFIX
    elif kind == "backup":
        suffix = TRANSACTION_BACKUP_SUFFIX
    elif kind == "quarantine":
        suffix = TRANSACTION_QUARANTINE_SUFFIX
    else:
        suffix = TRANSACTION_STAGE_SUFFIX + ".authority.json"
    return relative.with_name(f".{relative.name}{suffix}")


def _rename_noreplace_at(parent_fd: int, source: str, destination: str) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    require(renameat2 is not None, "renameat2(RENAME_NOREPLACE) is unavailable")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    if renameat2(parent_fd, os.fsencode(source), parent_fd, os.fsencode(destination), 1) == 0:
        return
    error = ctypes.get_errno()
    if error == errno.EEXIST:
        raise BuildError(f"destination already exists: {destination}")
    raise OSError(error, os.strerror(error), destination)


def _link_fd_at_raw(descriptor: int, parent_fd: int, destination: str) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    linkat = getattr(libc, "linkat", None)
    require(linkat is not None, "linkat(AT_EMPTY_PATH) is unavailable")
    linkat.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
    ]
    linkat.restype = ctypes.c_int
    if linkat(descriptor, b"", parent_fd, os.fsencode(destination), 0x1000) == 0:
        return
    error = ctypes.get_errno()
    if error == errno.EEXIST:
        raise BuildError(f"destination already exists: {destination}")
    raise OSError(error, os.strerror(error), destination)


def _link_fd_noreplace_at(descriptor: int, parent_fd: int, destination: str) -> None:
    _link_fd_at_raw(descriptor, parent_fd, destination)


def _unlink_capture_name(name: str) -> str:
    digest = hashlib.sha256(name.encode("utf-8")).hexdigest()
    return f".walksafe-fp048-{TRANSACTION_TOKEN}.{digest}.unlink-capture"


def _unlink_exact_at(
    parent_fd: int,
    name: str,
    expected_info: os.stat_result,
    label: str,
) -> os.stat_result:
    publication_lease = _ACTIVE_PUBLICATION_LEASE.get()
    require(publication_lease is not None, f"unlink lacks publication lock: {label}")
    publication_lease.verify()
    require(_entry_exists(parent_fd, name), f"unlink target is missing: {label}")
    capture_name = _unlink_capture_name(name)
    require(
        _path_info_if_exists_at(parent_fd, capture_name) is None,
        f"unlink capture collision: {label}",
    )
    libc = ctypes.CDLL(None, use_errno=True)
    unlinkat = getattr(libc, "unlinkat", None)
    require(unlinkat is not None, "unlinkat is unavailable")
    unlinkat.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    unlinkat.restype = ctypes.c_int
    descriptor = os.open(
        name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        held_before = os.fstat(descriptor)
        require(
            _identity(held_before) == _identity(expected_info)
            and _identity(
                os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            )
            == _identity(held_before),
            f"unlink authority changed: {label}",
        )
        publication_lease.verify()
        _rename_noreplace_at(parent_fd, name, capture_name)
        os.fsync(parent_fd)
        captured = os.stat(
            capture_name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        require(
            _identity(captured)[:-1] == _identity(held_before)[:-1]
            and _identity(os.fstat(descriptor))[:-1] == _identity(held_before)[:-1]
            and _path_info_if_exists_at(parent_fd, name) is None,
            f"unlink capture authority changed: {label}",
        )
        publication_lease.verify()
        if unlinkat(parent_fd, os.fsencode(capture_name), 0) != 0:
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error), capture_name)
        os.fsync(parent_fd)
        held_after = os.fstat(descriptor)
        require(
            (held_after.st_dev, held_after.st_ino, held_after.st_mode)
            == (held_before.st_dev, held_before.st_ino, held_before.st_mode)
            and held_after.st_uid == held_before.st_uid
            and held_after.st_gid == held_before.st_gid
            and held_after.st_nlink == held_before.st_nlink - 1
            and held_after.st_size == held_before.st_size
            and held_after.st_mtime_ns == held_before.st_mtime_ns,
            f"unlinked inode differs: {label}",
        )
        require(
            _path_info_if_exists_at(parent_fd, name) is None
            and _path_info_if_exists_at(parent_fd, capture_name) is None,
            f"unlinked path remains: {label}",
        )
        publication_lease.verify()
        return held_after
    finally:
        os.close(descriptor)


def _entry_exists(parent_fd: int, name: str) -> bool:
    current = _path_info_if_exists_at(parent_fd, name)
    capture_name = _unlink_capture_name(name)
    captured = _path_info_if_exists_at(parent_fd, capture_name)
    require(
        current is None or captured is None,
        f"unlink capture collides with restored path: {name}",
    )
    if current is not None:
        return True
    if captured is None:
        return False
    _rename_noreplace_at(parent_fd, capture_name, name)
    os.fsync(parent_fd)
    restored = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    require(
        _identity(restored)[:-1] == _identity(captured)[:-1]
        and _path_info_if_exists_at(parent_fd, capture_name) is None,
        f"unlink capture recovery differs: {name}",
    )
    return True


def _path_info_if_exists_at(parent_fd: int, name: str) -> os.stat_result | None:
    try:
        descriptor = os.open(
            name,
            _required_open_flag("O_PATH")
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            dir_fd=parent_fd,
        )
    except FileNotFoundError:
        return None
    try:
        return os.fstat(descriptor)
    finally:
        os.close(descriptor)


def _anonymous_capability_name(name: str) -> str:
    digest = hashlib.sha256(name.encode("utf-8")).hexdigest()
    return f".walksafe-fp048-{TRANSACTION_TOKEN}.{digest}.anonymous-read-only"


def _read_at(
    parent_fd: int,
    name: str,
    label: str,
    *,
    predecessor: bool = False,
    expected_mode: int | None = None,
) -> tuple[bytes, os.stat_result]:
    _finish_atomic_publication_if_present_at(parent_fd, name, label)
    try:
        before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError as exc:
        raise BuildError(f"required transaction file is missing: {label}") from exc
    require(
        stat.S_ISREG(before.st_mode)
        and not stat.S_ISLNK(before.st_mode)
        and before.st_uid == os.geteuid(),
        f"unsafe transaction file: {label}",
    )
    if predecessor:
        require(before.st_nlink >= 1, f"invalid predecessor link count: {label}")
        require(not (stat.S_IMODE(before.st_mode) & 0o002), f"world-writable predecessor: {label}")
    else:
        require(before.st_nlink == 1, f"hard-linked transaction file: {label}")
        require(not (stat.S_IMODE(before.st_mode) & 0o022), f"writable transaction file: {label}")
    if expected_mode is not None:
        require(stat.S_IMODE(before.st_mode) == expected_mode, f"transaction mode differs: {label}")
    descriptor = os.open(
        name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        opened = os.fstat(descriptor)
        require(_identity(opened) == _identity(before), f"transaction file changed while opening: {label}")
        raw = _read_descriptor(descriptor)
        require(_identity(os.fstat(descriptor)) == _identity(opened), f"transaction file changed while reading: {label}")
    finally:
        os.close(descriptor)
    require(
        _identity(os.stat(name, dir_fd=parent_fd, follow_symlinks=False)) == _identity(before),
        f"transaction path changed while reading: {label}",
    )
    return raw, before


def _write_exclusive_at(parent_fd: int, name: str, raw: bytes) -> os.stat_result:
    descriptor = os.open(
        name,
        os.O_RDWR
        | os.O_CREAT
        | os.O_EXCL
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW"),
        0o600,
        dir_fd=parent_fd,
    )
    try:
        os.fchmod(descriptor, 0o600)
        position = 0
        while position < len(raw):
            written = os.write(descriptor, raw[position : position + 1024 * 1024])
            require(written > 0, f"short exclusive write: {name}")
            position += written
        info = os.fstat(descriptor)
        require(
            stat.S_ISREG(info.st_mode)
            and stat.S_IMODE(info.st_mode) == 0o600
            and info.st_uid == os.geteuid()
            and info.st_nlink == 1
            and info.st_size == len(raw)
            and _read_descriptor(descriptor) == raw
            and _identity(os.fstat(descriptor)) == _identity(info),
            f"exclusive output authority differs: {name}",
        )
        os.fsync(descriptor)
        durable = os.fstat(descriptor)
        require(
            _identity(durable) == _identity(info)
            and _read_descriptor(descriptor) == raw
            and _identity(os.fstat(descriptor)) == _identity(durable),
            f"durable exclusive output differs: {name}",
        )
        info = durable
    finally:
        os.close(descriptor)
    os.fsync(parent_fd)
    return info


def _finish_atomic_publication_if_present_at(
    parent_fd: int,
    name: str,
    label: str,
    *,
    expected_raw: bytes | None = None,
) -> tuple[bytes, os.stat_result] | None:
    capability_name = _anonymous_capability_name(name)
    if not _entry_exists(parent_fd, capability_name):
        return None
    capability_before = os.stat(
        capability_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    require(
        stat.S_ISREG(capability_before.st_mode)
        and not stat.S_ISLNK(capability_before.st_mode)
        and capability_before.st_uid == os.geteuid()
        and stat.S_IMODE(capability_before.st_mode) in {0o400, 0o600}
        and capability_before.st_nlink in {1, 2},
        f"anonymous capability differs: {label}",
    )
    descriptor = os.open(
        capability_name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    monitor: HeldFileWriteMonitor | None = None
    try:
        opened = os.fstat(descriptor)
        raw = _read_descriptor(descriptor)
        require(
            _identity(opened) == _identity(capability_before)
            and _identity(os.fstat(descriptor)) == _identity(opened)
            and (expected_raw is None or raw == expected_raw),
            f"anonymous capability content differs: {label}",
        )
        monitor = HeldFileWriteMonitor(descriptor, label)
        target_info = _path_info_if_exists_at(parent_fd, name)
        if target_info is not None:
            require(
                (target_info.st_dev, target_info.st_ino)
                == (opened.st_dev, opened.st_ino)
                and target_info.st_nlink == 2
                and opened.st_nlink == 2,
                f"anonymous capability target differs: {label}",
            )
        else:
            require(
                stat.S_IMODE(opened.st_mode) == 0o400 and opened.st_nlink == 1,
                f"anonymous capability cannot resume: {label}",
            )
            _link_fd_noreplace_at(descriptor, parent_fd, name)
            os.fsync(parent_fd)
            monitor.verify()
        linked = os.fstat(descriptor)
        require(
            linked.st_nlink == 2
            and _read_descriptor(descriptor) == raw
            and _identity(os.fstat(descriptor)) == _identity(linked)
            and _identity(
                os.stat(
                    capability_name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
            )
            == _identity(linked),
            f"anonymous capability link differs: {label}",
        )
        if stat.S_IMODE(linked.st_mode) == 0o400:
            os.fchmod(descriptor, 0o600)
            os.fsync(descriptor)
            linked = os.fstat(descriptor)
        require(
            stat.S_IMODE(linked.st_mode) == 0o600
            and linked.st_nlink == 2
            and linked.st_uid == os.geteuid()
            and linked.st_size == len(raw)
            and _read_descriptor(descriptor) == raw,
            f"anonymous capability mode recovery differs: {label}",
        )
        require(
            _identity(os.stat(name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(linked)
            and _identity(os.fstat(descriptor)) == _identity(linked)
            and _read_descriptor(descriptor) == raw,
            f"anonymous capability target changed after mode recovery: {label}",
        )
        monitor.verify()
        capability_info = os.stat(
            capability_name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        require(
            _identity(capability_info) == _identity(linked),
            f"anonymous capability path changed before release: {label}",
        )
        _unlink_exact_at(
            parent_fd,
            capability_name,
            capability_info,
            f"{label} anonymous capability",
        )
        published = os.fstat(descriptor)
        require(
            published.st_nlink == 1
            and stat.S_IMODE(published.st_mode) == 0o600
            and published.st_size == len(raw)
            and _read_descriptor(descriptor) == raw
            and _identity(os.fstat(descriptor)) == _identity(published)
            and _identity(
                os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            )
            == _identity(published),
            f"anonymous capability release differs: {label}",
        )
        monitor.verify()
        return raw, published
    finally:
        if monitor is not None:
            monitor.close()
        os.close(descriptor)


def _write_atomic_exclusive_at(parent_fd: int, name: str, raw: bytes) -> os.stat_result:
    recovered = _finish_atomic_publication_if_present_at(
        parent_fd,
        name,
        name,
        expected_raw=raw,
    )
    if recovered is not None:
        return recovered[1]
    require(not _entry_exists(parent_fd, name), f"destination already exists: {name}")
    capability_name = _anonymous_capability_name(name)
    descriptor = os.open(
        ".",
        os.O_RDWR
        | _required_open_flag("O_TMPFILE")
        | _required_open_flag("O_CLOEXEC"),
        0o600,
        dir_fd=parent_fd,
    )
    try:
        position = 0
        while position < len(raw):
            written = os.write(descriptor, raw[position : position + 1024 * 1024])
            require(written > 0, f"short anonymous write: {name}")
            position += written
        os.fsync(descriptor)
        os.fchmod(descriptor, 0o400)
        os.fsync(descriptor)
        anonymous_info = os.fstat(descriptor)
        require(
            stat.S_ISREG(anonymous_info.st_mode)
            and stat.S_IMODE(anonymous_info.st_mode) == 0o400
            and anonymous_info.st_uid == os.geteuid()
            and anonymous_info.st_nlink == 0
            and anonymous_info.st_size == len(raw)
            and _read_descriptor(descriptor) == raw
            and _identity(os.fstat(descriptor)) == _identity(anonymous_info),
            f"anonymous output authority differs: {name}",
        )
        _link_fd_at_raw(descriptor, parent_fd, capability_name)
        os.fsync(parent_fd)
        capability_info = os.stat(
            capability_name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        require(
            _identity(capability_info) == _identity(os.fstat(descriptor))
            and capability_info.st_nlink == 1,
            f"anonymous capability creation differs: {name}",
        )
    finally:
        os.close(descriptor)
    recovered = _finish_atomic_publication_if_present_at(
        parent_fd,
        name,
        name,
        expected_raw=raw,
    )
    require(recovered is not None, f"anonymous publication recovery is missing: {name}")
    return recovered[1]


def _transaction_manifest(
    outputs: dict[Path, bytes],
    pins: dict[Path, str],
) -> tuple[dict[str, Any], bytes]:
    rows = []
    for index, relative in enumerate(OUTPUT_PATHS):
        raw = outputs[relative]
        rows.append(
            {
                "index": index,
                "path": relative.as_posix(),
                "predecessor_sha256": PINNED_PREDECESSOR_SHA256[relative],
                "successor_sha256": bytes_sha256(raw),
                "byte_length": len(raw),
                "stage_path": _transaction_member_relative(relative, "stage").as_posix(),
                "backup_path": _transaction_member_relative(relative, "backup").as_posix(),
                "authority_path": _transaction_member_relative(relative, "authority").as_posix(),
                "quarantine_path": _transaction_member_relative(relative, "quarantine").as_posix(),
            }
        )
    value = {
        "schema_version": "walksafe.fp048-six-document-forward-transaction.v1",
        "successor_id": SUCCESSOR_ID,
        "transaction_token": TRANSACTION_TOKEN,
        "policy": "ALL_STAGE_THEN_ADD_ONLY_FORWARD_RECOVERY",
        "publication_concurrency_contract": deepcopy(
            PUBLICATION_CONCURRENCY_CONTRACT
        ),
        "input_sha256_by_path": {
            relative.as_posix(): pins[relative] for relative in INPUT_PATHS
        },
        "outputs": rows,
    }
    value["journal_content_sha256"] = object_sha256(value)
    return value, json_bytes(value)


def _validate_transaction_manifest(
    value: dict[str, Any],
    raw: bytes,
    pins: dict[Path, str],
) -> None:
    require(
        set(value)
        == {
            "schema_version",
            "successor_id",
            "transaction_token",
            "policy",
            "publication_concurrency_contract",
            "input_sha256_by_path",
            "outputs",
            "journal_content_sha256",
        },
        "transaction journal field set differs",
    )
    require(raw == json_bytes(value), "transaction journal serialization differs")
    require(
        value.get("journal_content_sha256")
        == _projection_sha(value, "journal_content_sha256"),
        "transaction journal seal differs",
    )
    require(
        value.get("schema_version") == "walksafe.fp048-six-document-forward-transaction.v1"
        and value.get("successor_id") == SUCCESSOR_ID
        and value.get("transaction_token") == TRANSACTION_TOKEN
        and value.get("policy") == "ALL_STAGE_THEN_ADD_ONLY_FORWARD_RECOVERY",
        "transaction journal identity differs",
    )
    require(
        value.get("publication_concurrency_contract")
        == PUBLICATION_CONCURRENCY_CONTRACT,
        "transaction publication concurrency contract differs",
    )
    require(
        value.get("input_sha256_by_path")
        == {relative.as_posix(): pins[relative] for relative in INPUT_PATHS},
        "transaction input pins differ",
    )
    rows = value.get("outputs")
    require(isinstance(rows, list) and len(rows) == len(OUTPUT_PATHS), "transaction output count differs")
    for index, (row, relative) in enumerate(zip(rows, OUTPUT_PATHS, strict=True)):
        require(isinstance(row, dict), "transaction output row differs")
        require(
            set(row)
            == {
                "index",
                "path",
                "predecessor_sha256",
                "successor_sha256",
                "byte_length",
                "stage_path",
                "backup_path",
                "authority_path",
                "quarantine_path",
            },
            f"transaction output field set differs: {relative}",
        )
        require(
            row.get("index") == index
            and row.get("path") == relative.as_posix()
            and row.get("predecessor_sha256") == PINNED_PREDECESSOR_SHA256[relative]
            and isinstance(row.get("successor_sha256"), str)
            and SHA256_RE.fullmatch(row["successor_sha256"]) is not None
            and isinstance(row.get("byte_length"), int)
            and row["byte_length"] > 0
            and row.get("stage_path") == _transaction_member_relative(relative, "stage").as_posix()
            and row.get("backup_path") == _transaction_member_relative(relative, "backup").as_posix()
            and row.get("authority_path") == _transaction_member_relative(relative, "authority").as_posix()
            and row.get("quarantine_path") == _transaction_member_relative(relative, "quarantine").as_posix(),
            f"transaction output binding differs: {relative}",
        )


def _journal(
    authority: RepositorySnapshot,
    pins: dict[Path, str],
) -> tuple[dict[str, Any], bytes] | None:
    root_fd = authority.directory_fd(Path())
    if not _entry_exists(root_fd, TRANSACTION_JOURNAL_REL.name):
        return None
    raw, _info = _read_at(
        root_fd,
        TRANSACTION_JOURNAL_REL.name,
        TRANSACTION_JOURNAL_REL.as_posix(),
        expected_mode=0o600,
    )
    value = _json_from_bytes(raw, TRANSACTION_JOURNAL_REL)
    _validate_transaction_manifest(value, raw, pins)
    return value, raw


def _stable_file_identity(info: os.stat_result) -> dict[str, int]:
    return {
        "device": info.st_dev,
        "inode": info.st_ino,
        "mode": stat.S_IMODE(info.st_mode),
        "uid": info.st_uid,
        "gid": info.st_gid,
        "link_count": info.st_nlink,
    }


def _stage_authority_value(row: dict[str, Any], info: os.stat_result) -> dict[str, Any]:
    value = {
        "schema_version": "walksafe.fp048-stage-authority.v1",
        "successor_id": SUCCESSOR_ID,
        "transaction_token": TRANSACTION_TOKEN,
        "index": row["index"],
        "path": row["path"],
        "stage_path": row["stage_path"],
        "successor_sha256": row["successor_sha256"],
        "byte_length": row["byte_length"],
        "stage_file_identity": _stable_file_identity(info),
    }
    value["authority_content_sha256"] = object_sha256(value)
    return value


def _validated_stage(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    *,
    allow_prefix: bytes | None = None,
) -> tuple[bytes, os.stat_result]:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    sidecar = Path(row["authority_path"])
    raw, info = _read_at(parent_fd, stage.name, stage.as_posix(), expected_mode=0o600)
    authority_raw, _authority_info = _read_at(
        parent_fd,
        sidecar.name,
        sidecar.as_posix(),
        expected_mode=0o600,
    )
    authority_value = _json_from_bytes(authority_raw, sidecar)
    require(authority_raw == json_bytes(authority_value), f"stage authority serialization differs: {relative}")
    require(
        authority_value.get("authority_content_sha256")
        == _projection_sha(authority_value, "authority_content_sha256"),
        f"stage authority seal differs: {relative}",
    )
    expected_authority = _stage_authority_value(row, info)
    require(authority_value == expected_authority, f"staged successor identity differs: {relative}")
    if allow_prefix is None:
        require(
            len(raw) == row["byte_length"] and bytes_sha256(raw) == row["successor_sha256"],
            f"staged successor differs: {relative}",
        )
    else:
        require(
            len(raw) <= len(allow_prefix) and allow_prefix.startswith(raw),
            f"partial staged successor differs: {relative}",
        )
    return raw, info


def _open_validated_stage(
    authority: RepositorySnapshot,
    row: dict[str, Any],
) -> tuple[int, bytes, os.stat_result]:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    raw, info = _validated_stage(authority, row)
    descriptor = os.open(
        stage.name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        require(
            _identity(os.fstat(descriptor)) == _identity(info),
            f"staged successor changed before held open: {relative}",
        )
        require(
            _read_descriptor(descriptor) == raw
            and _identity(os.fstat(descriptor)) == _identity(info),
            f"staged successor changed during held read: {relative}",
        )
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor, raw, info


def _normalize_unbound_empty_stage_mode(
    authority: RepositorySnapshot,
    row: dict[str, Any],
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    before = os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)
    mode = stat.S_IMODE(before.st_mode)
    if mode == 0o600:
        return
    require(
        stat.S_ISREG(before.st_mode)
        and not stat.S_ISLNK(before.st_mode)
        and before.st_uid == os.geteuid()
        and before.st_nlink == 1
        and before.st_size == 0
        and mode & ~0o600 == 0,
        f"unsafe unbound stage mode residual: {relative}",
    )
    authority.verify_directory_paths()
    held_descriptor = os.open(
        stage.name,
        _required_open_flag("O_PATH")
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        require(
            _identity(os.fstat(held_descriptor)) == _identity(before),
            f"unbound stage changed before mode recovery: {relative}",
        )
        os.chmod(
            stage.name,
            0o600,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        descriptor = os.open(
            stage.name,
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            dir_fd=parent_fd,
        )
        try:
            recovered = os.fstat(descriptor)
            held = os.fstat(held_descriptor)
            require(
                stat.S_ISREG(recovered.st_mode)
                and stat.S_IMODE(recovered.st_mode) == 0o600
                and recovered.st_uid == before.st_uid
                and recovered.st_gid == before.st_gid
                and recovered.st_nlink == before.st_nlink == 1
                and recovered.st_size == before.st_size == 0
                and recovered.st_mtime_ns == before.st_mtime_ns
                and (recovered.st_dev, recovered.st_ino)
                == (before.st_dev, before.st_ino)
                and _identity(held) == _identity(recovered)
                and _read_descriptor(descriptor) == b""
                and _identity(os.fstat(descriptor)) == _identity(recovered),
                f"unbound stage mode recovery differs: {relative}",
            )
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.fsync(parent_fd)
        require(
            _identity(
                os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)
            )
            == _identity(os.fstat(held_descriptor)),
            f"unbound stage path changed during mode recovery: {relative}",
        )
        authority.verify_directory_paths()
    finally:
        os.close(held_descriptor)


def _move_to_available_quarantine(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    source: Path,
    source_info: os.stat_result,
    label: str,
) -> Path:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    base = Path(row["quarantine_path"])
    for sequence in range(10_000):
        quarantine = (
            base
            if sequence == 0
            else base.with_name(f"{base.name}.retry-{sequence:06d}")
        )
        if _entry_exists(parent_fd, quarantine.name):
            continue
        _rename_noreplace_at(parent_fd, source.name, quarantine.name)
        os.fsync(parent_fd)
        quarantined = os.stat(
            quarantine.name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        require(
            _identity(quarantined)[:-1] == _identity(source_info)[:-1],
            f"{label} quarantine differs: {relative}",
        )
        return quarantine
    raise BuildError(f"quarantine namespace is exhausted: {relative}")


def _rename_foreign_stage_to_quarantine(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    held_info: os.stat_result,
) -> bool:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    if not _entry_exists(parent_fd, stage.name):
        return False
    current = os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)
    if (current.st_dev, current.st_ino) == (held_info.st_dev, held_info.st_ino):
        return False
    require(
        stat.S_ISREG(current.st_mode)
        and not stat.S_ISLNK(current.st_mode)
        and current.st_uid == os.geteuid(),
        f"foreign staged path is unsafe: {relative}",
    )
    _move_to_available_quarantine(
        authority,
        row,
        stage,
        current,
        "foreign staged collision",
    )
    return True


def _rebuild_stage_after_link_race(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    raw: bytes,
    held_info: os.stat_result,
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    sidecar = Path(row["authority_path"])
    _rename_foreign_stage_to_quarantine(authority, row, held_info)
    expected_sidecar = json_bytes(_stage_authority_value(row, held_info))
    sidecar_raw, sidecar_info = _read_at(
        parent_fd,
        sidecar.name,
        sidecar.as_posix(),
        expected_mode=0o600,
    )
    require(sidecar_raw == expected_sidecar, f"stage authority changed before rebuild: {relative}")
    require(
        _identity(os.stat(sidecar.name, dir_fd=parent_fd, follow_symlinks=False))
        == _identity(sidecar_info),
        f"stage authority path changed before rebuild: {relative}",
    )
    if _entry_exists(parent_fd, stage.name):
        current_raw, current_info = _read_at(
            parent_fd,
            stage.name,
            stage.as_posix(),
            expected_mode=0o600,
        )
        if (
            (current_info.st_dev, current_info.st_ino)
            == (held_info.st_dev, held_info.st_ino)
            and (
                current_raw != raw
                or _stable_file_identity(current_info)
                != _stable_file_identity(held_info)
            )
        ):
            _unlink_exact_at(
                parent_fd,
                stage.name,
                current_info,
                f"stage rebuild {relative}",
            )
    _unlink_exact_at(
        parent_fd,
        sidecar.name,
        sidecar_info,
        f"stage authority rebuild {relative}",
    )
    if not _entry_exists(parent_fd, stage.name):
        new_info = _write_atomic_exclusive_at(parent_fd, stage.name, raw)
    else:
        current_raw, new_info = _read_at(
            parent_fd,
            stage.name,
            stage.as_posix(),
            expected_mode=0o600,
        )
        require(current_raw == raw, f"stage rebuild source differs: {relative}")
    _write_atomic_exclusive_at(
        parent_fd,
        sidecar.name,
        json_bytes(_stage_authority_value(row, new_info)),
    )
    _validated_stage(authority, row)


def _copy_held_predecessor_to_backup(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    source_descriptor: int,
    source_raw: bytes,
    source_info: os.stat_result,
) -> tuple[int, bytes, os.stat_result]:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    backup = Path(row["backup_path"])
    require(
        _identity(os.fstat(source_descriptor)) == _identity(source_info)
        and _read_descriptor(source_descriptor) == source_raw
        and bytes_sha256(source_raw) == row["predecessor_sha256"],
        f"predecessor changed before private backup copy: {relative}",
    )
    created_backup_info = _write_atomic_exclusive_at(
        parent_fd,
        backup.name,
        source_raw,
    )
    authority.verify_directory_paths()
    require(
        _identity(os.fstat(source_descriptor)) == _identity(source_info)
        and _read_descriptor(source_descriptor) == source_raw,
        f"predecessor changed during private backup copy: {relative}",
    )
    require(
        _identity(
            os.stat(
                backup.name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
        )
        == _identity(created_backup_info),
        f"predecessor changed after backup move: {relative}",
    )
    backup_descriptor, backup_raw, backup_info = _open_held_predecessor_backup(
        authority,
        row,
    )
    try:
        require(
            _identity(
                os.stat(
                    relative.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
            )
            == _identity(source_info),
            f"predecessor path changed before unlink: {relative}",
        )
        _unlink_exact_at(
            parent_fd,
            relative.name,
            source_info,
            f"canonical predecessor {relative}",
        )
        after_unlink = os.fstat(source_descriptor)
        require(
            _identity(after_unlink)[:5] == _identity(source_info)[:5]
            and after_unlink.st_nlink == source_info.st_nlink - 1
            and after_unlink.st_size == source_info.st_size
            and after_unlink.st_mtime_ns == source_info.st_mtime_ns
            and _read_descriptor(source_descriptor) == source_raw,
            f"predecessor changed during canonical unlink: {relative}",
        )
        authority.verify_directory_paths()
    except BaseException:
        os.close(backup_descriptor)
        raise
    return backup_descriptor, backup_raw, backup_info


def _restore_predecessor_target(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    descriptor: int,
    raw: bytes,
    held_info: os.stat_result,
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    backup = Path(row["backup_path"])
    _verify_held_predecessor_backup(
        authority,
        row,
        descriptor,
        raw,
        held_info,
    )
    require(not _entry_exists(parent_fd, relative.name), f"cannot restore over target collision: {relative}")
    try:
        restored_info = _write_atomic_exclusive_at(
            parent_fd,
            relative.name,
            raw,
        )
    except BuildError as exc:
        if "destination already exists" not in str(exc):
            raise
        foreign_info = os.stat(
            relative.name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        _move_to_available_quarantine(
            authority,
            row,
            relative,
            foreign_info,
            "foreign predecessor restore",
        )
        restored_info = _write_atomic_exclusive_at(
            parent_fd,
            relative.name,
            raw,
        )
    held_current = os.fstat(descriptor)
    require(
        _read_descriptor(descriptor) == raw
        and bytes_sha256(raw) == row["predecessor_sha256"],
        f"held predecessor changed during private restore: {relative}",
    )
    backup_path_info = os.stat(
        backup.name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    if (backup_path_info.st_dev, backup_path_info.st_ino) == (
        held_current.st_dev,
        held_current.st_ino,
    ):
        _unlink_exact_at(
            parent_fd,
            backup.name,
            backup_path_info,
            f"restored predecessor backup {relative}",
        )
    else:
        _move_to_available_quarantine(
            authority,
            row,
            backup,
            backup_path_info,
            "foreign predecessor backup",
        )
    restored_raw, restored_path_info = _read_at(
        parent_fd,
        relative.name,
        relative.as_posix(),
        predecessor=True,
    )
    require(
        restored_raw == raw
        and bytes_sha256(restored_raw) == row["predecessor_sha256"],
        f"restored predecessor differs: {relative}",
    )
    require(
        _identity(restored_path_info) == _identity(restored_info),
        f"restored predecessor differs: {relative}",
    )


def _open_held_predecessor_backup(
    authority: RepositorySnapshot,
    row: dict[str, Any],
) -> tuple[int, bytes, os.stat_result]:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    backup = Path(row["backup_path"])
    raw, info = _read_at(
        parent_fd,
        backup.name,
        backup.as_posix(),
        predecessor=True,
    )
    descriptor = os.open(
        backup.name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        require(
            _identity(os.fstat(descriptor)) == _identity(info),
            f"predecessor backup changed before held open: {relative}",
        )
        _verify_held_predecessor_backup(authority, row, descriptor, raw, info)
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor, raw, info


def _verify_held_predecessor_backup(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    descriptor: int,
    raw: bytes,
    info: os.stat_result,
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    backup = Path(row["backup_path"])
    require(
        bytes_sha256(raw) == row["predecessor_sha256"]
        and _identity(os.fstat(descriptor)) == _identity(info)
        and _read_descriptor(descriptor) == raw
        and _identity(os.fstat(descriptor)) == _identity(info),
        f"held predecessor backup changed: {relative}",
    )
    require(
        _identity(os.stat(backup.name, dir_fd=parent_fd, follow_symlinks=False))
        == _identity(info),
        f"predecessor backup path changed: {relative}",
    )


def _link_held_stage_to_target(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    descriptor: int,
    raw: bytes,
    held_info: os.stat_result,
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    require(
        _identity(os.fstat(descriptor)) == _identity(held_info)
        and _read_descriptor(descriptor) == raw
        and _identity(os.fstat(descriptor)) == _identity(held_info),
        f"staged successor changed before FD publication: {relative}",
    )
    _write_atomic_exclusive_at(parent_fd, relative.name, raw)
    require(
        _identity(os.fstat(descriptor)) == _identity(held_info)
        and _read_descriptor(descriptor) == raw
        and _identity(os.fstat(descriptor)) == _identity(held_info),
        f"named stage changed during private publication: {relative}",
    )
    stage_info = os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)
    require(
        _identity(stage_info) == _identity(held_info),
        f"stage path differs before unlink: {relative}",
    )
    _unlink_exact_at(
        parent_fd,
        stage.name,
        stage_info,
        f"published private stage {relative}",
    )
    published_raw, published_info = _read_at(
        parent_fd,
        relative.name,
        relative.as_posix(),
        expected_mode=0o600,
    )
    require(
        published_info.st_nlink == 1
        and published_raw == raw
        and bytes_sha256(published_raw) == row["successor_sha256"],
        f"private-copy-published successor differs: {relative}",
    )


def _unlink_held_stage_target_for_rollback(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    descriptor: int,
    raw: bytes,
) -> bool:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    if not _entry_exists(parent_fd, relative.name):
        return False
    target_info = os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
    held_info = os.fstat(descriptor)
    if (target_info.st_dev, target_info.st_ino) != (held_info.st_dev, held_info.st_ino):
        return False
    require(
        stat.S_ISREG(target_info.st_mode)
        and target_info.st_uid == os.geteuid()
        and bytes_sha256(raw) == row["successor_sha256"]
        and len(raw) == row["byte_length"],
        f"held successor rollback authority differs: {relative}",
    )
    _unlink_exact_at(
        parent_fd,
        relative.name,
        target_info,
        f"rollback held successor {relative}",
    )
    require(
        not _entry_exists(parent_fd, relative.name),
        f"held successor rollback target remains: {relative}",
    )
    return True


def _normalize_crashed_link_publication(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    target_raw: bytes,
    target_info: os.stat_result,
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    sidecar = Path(row["authority_path"])
    backup = Path(row["backup_path"])
    require(
        stat.S_IMODE(target_info.st_mode) == 0o600
        and target_info.st_nlink == 2
        and len(target_raw) == row["byte_length"]
        and bytes_sha256(target_raw) == row["successor_sha256"],
        f"linked successor recovery target differs: {relative}",
    )
    require(
        _entry_exists(parent_fd, stage.name)
        and _entry_exists(parent_fd, backup.name),
        f"linked successor recovery members differ: {relative}",
    )
    stage_info = os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)
    require(
        _identity(stage_info) == _identity(target_info),
        f"linked successor recovery stage differs: {relative}",
    )
    descriptor = os.open(
        stage.name,
        os.O_RDONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_fd,
    )
    try:
        held_info = os.fstat(descriptor)
        require(
            _identity(held_info) == _identity(target_info)
            and _read_descriptor(descriptor) == target_raw
            and _identity(os.fstat(descriptor)) == _identity(held_info),
            f"linked successor recovery held stage differs: {relative}",
        )
        if _entry_exists(parent_fd, sidecar.name):
            try:
                sidecar_raw, _sidecar_info = _read_at(
                    parent_fd,
                    sidecar.name,
                    sidecar.as_posix(),
                    expected_mode=0o600,
                )
                sidecar_value = _json_from_bytes(sidecar_raw, sidecar)
                expected_sidecar = _stage_authority_value(row, held_info)
                expected_sidecar["stage_file_identity"]["link_count"] = 1
                expected_sidecar["authority_content_sha256"] = _projection_sha(
                    expected_sidecar,
                    "authority_content_sha256",
                )
                require(
                    sidecar_raw == json_bytes(sidecar_value)
                    and sidecar_value == expected_sidecar,
                    f"linked successor recovery authority differs: {relative}",
                )
            except (BuildError, ValueError, KeyError):
                sidecar_info = os.stat(
                    sidecar.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                require(
                    stat.S_ISREG(sidecar_info.st_mode)
                    and not stat.S_ISLNK(sidecar_info.st_mode)
                    and sidecar_info.st_uid == os.geteuid(),
                    f"linked successor recovery authority is unsafe: {relative}",
                )
                _move_to_available_quarantine(
                    authority,
                    row,
                    sidecar,
                    sidecar_info,
                    "linked successor recovery authority",
                )
        backup_raw, _backup_info = _read_at(
            parent_fd,
            backup.name,
            backup.as_posix(),
            predecessor=True,
        )
        require(
            bytes_sha256(backup_raw) == row["predecessor_sha256"],
            f"linked successor recovery backup differs: {relative}",
        )
        require(
            _identity(os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(held_info)
            and _identity(os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(held_info),
            f"linked successor recovery paths changed: {relative}",
        )
        _unlink_exact_at(
            parent_fd,
            stage.name,
            stage_info,
            f"crashed linked stage {relative}",
        )
        normalized_info = os.fstat(descriptor)
        require(
            _identity(normalized_info)[:5] == _identity(held_info)[:5]
            and normalized_info.st_nlink == 1
            and normalized_info.st_size == held_info.st_size
            and normalized_info.st_mtime_ns == held_info.st_mtime_ns
            and _read_descriptor(descriptor) == target_raw
            and _identity(os.fstat(descriptor)) == _identity(normalized_info),
            f"linked successor recovery normalization differs: {relative}",
        )
        require(
            _identity(os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(normalized_info),
            f"linked successor recovery target changed: {relative}",
        )
    finally:
        os.close(descriptor)


def _normalize_private_copy_publication(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    target_info: os.stat_result,
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    if not _entry_exists(parent_fd, stage.name):
        return
    stage_raw, stage_info = _read_at(
        parent_fd,
        stage.name,
        stage.as_posix(),
        expected_mode=0o600,
    )
    require(
        (stage_info.st_dev, stage_info.st_ino)
        != (target_info.st_dev, target_info.st_ino),
        f"private publication stage unexpectedly aliases target: {relative}",
    )
    if (
        len(stage_raw) == row["byte_length"]
        and bytes_sha256(stage_raw) == row["successor_sha256"]
    ):
        _unlink_exact_at(
            parent_fd,
            stage.name,
            stage_info,
            f"normalized private stage {relative}",
        )
    else:
        _move_to_available_quarantine(
            authority,
            row,
            stage,
            stage_info,
            "damaged private publication stage",
        )


def _ensure_stages(
    authority: RepositorySnapshot,
    manifest: dict[str, Any],
    outputs: dict[Path, bytes],
    stage_write_hook: Callable[[int, Path, int, int], None] | None,
    stage_created_hook: Callable[[int, Path], None] | None,
) -> None:
    for row, relative in zip(manifest["outputs"], OUTPUT_PATHS, strict=True):
        expected = outputs[relative]
        require(
            len(expected) == row["byte_length"]
            and bytes_sha256(expected) == row["successor_sha256"],
            f"transaction output bytes differ: {relative}",
        )
        parent_fd = authority.directory_fd(relative.parent)
        stage = Path(row["stage_path"])
        sidecar = Path(row["authority_path"])
        stage_exists = _entry_exists(parent_fd, stage.name)
        sidecar_exists = _entry_exists(parent_fd, sidecar.name)
        if not stage_exists:
            require(not sidecar_exists, f"stage authority exists without stage: {relative}")
            info = _write_exclusive_at(parent_fd, stage.name, b"")
            if stage_created_hook is not None:
                stage_created_hook(row["index"], relative)
            sidecar_raw = json_bytes(_stage_authority_value(row, info))
            try:
                _write_atomic_exclusive_at(parent_fd, sidecar.name, sidecar_raw)
            except BaseException:
                raise BuildError(f"stage authority creation failed without cleanup: {relative}")
        elif not sidecar_exists:
            _normalize_unbound_empty_stage_mode(authority, row)
            unbound, info = _read_at(
                parent_fd,
                stage.name,
                stage.as_posix(),
                expected_mode=0o600,
            )
            quarantine = _move_to_available_quarantine(
                authority,
                row,
                stage,
                info,
                "unbound stage",
            )
            quarantined_raw, quarantined = _read_at(
                parent_fd,
                quarantine.name,
                quarantine.as_posix(),
                expected_mode=0o600,
            )
            require(
                quarantined_raw == unbound
                and _identity(quarantined)[:-1] == _identity(info)[:-1],
                f"unbound stage quarantine differs: {relative}",
            )
            info = _write_exclusive_at(parent_fd, stage.name, b"")
            if stage_created_hook is not None:
                stage_created_hook(row["index"], relative)
            _write_atomic_exclusive_at(
                parent_fd,
                sidecar.name,
                json_bytes(_stage_authority_value(row, info)),
            )
        current, expected_info = _validated_stage(authority, row, allow_prefix=expected)
        if len(current) == len(expected):
            continue
        descriptor = os.open(
            stage.name,
            os.O_WRONLY | _required_open_flag("O_CLOEXEC") | _required_open_flag("O_NOFOLLOW"),
            dir_fd=parent_fd,
        )
        try:
            require(
                _stable_file_identity(os.fstat(descriptor)) == _stable_file_identity(expected_info),
                f"staged successor identity changed before resume: {relative}",
            )
            position = len(current)
            os.lseek(descriptor, position, os.SEEK_SET)
            chunk_size = 4096 if stage_write_hook is not None else 1024 * 1024
            while position < len(expected):
                written = os.write(descriptor, expected[position : position + chunk_size])
                require(written > 0, f"short stage write: {relative}")
                position += written
                if stage_write_hook is not None:
                    os.fsync(descriptor)
                    stage_write_hook(row["index"], relative, position, len(expected))
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.fsync(parent_fd)
        _validated_stage(authority, row)


def _snapshot_staged_successor_identities(
    authority: RepositorySnapshot,
    manifest: dict[str, Any],
) -> dict[Path, tuple[int, ...]]:
    identities: dict[Path, tuple[int, ...]] = {}
    for row, relative in zip(manifest["outputs"], OUTPUT_PATHS, strict=True):
        parent_fd = authority.directory_fd(relative.parent)
        if _entry_exists(parent_fd, relative.name):
            target_raw, _target_info = _read_at(
                parent_fd,
                relative.name,
                relative.as_posix(),
                predecessor=True,
            )
            target_sha256 = bytes_sha256(target_raw)
            if target_sha256 == row["successor_sha256"]:
                continue
            require(
                target_sha256 == row["predecessor_sha256"],
                f"foreign target collision before publication: {relative}",
            )
        else:
            require(
                _entry_exists(parent_fd, Path(row["backup_path"]).name),
                f"missing target lacks backup before publication: {relative}",
            )
        _stage_raw, stage_info = _validated_stage(authority, row)
        identities[relative] = _identity(stage_info)
    return identities


def _publish_transaction(
    authority: RepositorySnapshot,
    manifest: dict[str, Any],
    publish_hook: Callable[[int, Path], None] | None,
    source_snapshot: RepositorySnapshot | None,
    expected_stage_identities: dict[Path, tuple[int, ...]],
) -> None:
    for row, relative in zip(manifest["outputs"], OUTPUT_PATHS, strict=True):
        authority.verify_directory_paths()
        parent_fd = authority.directory_fd(relative.parent)
        backup = Path(row["backup_path"])
        target_exists = _entry_exists(parent_fd, relative.name)
        backup_exists = _entry_exists(parent_fd, backup.name)
        target_kind: str | None = None
        target_info: os.stat_result | None = None
        if target_exists:
            raw, target_info = _read_at(
                parent_fd,
                relative.name,
                relative.as_posix(),
                predecessor=True,
            )
            digest = bytes_sha256(raw)
            if digest == row["predecessor_sha256"]:
                target_kind = "predecessor"
            elif digest == row["successor_sha256"]:
                require(
                    stat.S_IMODE(os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False).st_mode)
                    == 0o600,
                    f"published successor authority differs: {relative}",
                )
                if target_info.st_nlink == 2:
                    _normalize_crashed_link_publication(
                        authority,
                        row,
                        raw,
                        target_info,
                    )
                    target_info = os.stat(
                        relative.name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                _normalize_private_copy_publication(
                    authority,
                    row,
                    target_info,
                )
                require(
                    target_info.st_nlink == 1,
                    f"published successor authority differs: {relative}",
                )
                target_kind = "successor"
            else:
                raise BuildError(f"foreign target collision is not overwritten: {relative}")

        stage_descriptor: int | None = None
        stage_raw = b""
        stage_info: os.stat_result | None = None
        predecessor_descriptor: int | None = None
        predecessor_raw = b""
        predecessor_info: os.stat_result | None = None
        if target_kind != "successor":
            require(
                relative in expected_stage_identities,
                f"staged successor baseline is missing: {relative}",
            )
            stage_descriptor, stage_raw, stage_info = _open_validated_stage(authority, row)
            require(
                _identity(stage_info) == expected_stage_identities[relative],
                f"staged successor changed after publication baseline: {relative}",
            )
        else:
            require(
                relative not in expected_stage_identities,
                f"published successor appeared after publication baseline: {relative}",
            )
        try:
            if target_kind == "predecessor":
                require(not backup_exists, f"predecessor backup collision: {relative}")
                require(target_info is not None, f"predecessor identity is missing: {relative}")
                if source_snapshot is not None:
                    require(
                        _identity(target_info) == _identity(source_snapshot.entry(relative).info),
                        f"predecessor differs from held source snapshot: {relative}",
                    )
                source_predecessor_descriptor = os.open(
                    relative.name,
                    os.O_RDONLY
                    | _required_open_flag("O_CLOEXEC")
                    | _required_open_flag("O_NOFOLLOW"),
                    dir_fd=parent_fd,
                )
                try:
                    held_before = os.fstat(source_predecessor_descriptor)
                    require(
                        _identity(held_before) == _identity(target_info),
                        f"predecessor changed before backup copy: {relative}",
                    )
                    held_raw = _read_descriptor(source_predecessor_descriptor)
                    require(
                        bytes_sha256(held_raw) == row["predecessor_sha256"]
                        and _identity(os.fstat(source_predecessor_descriptor))
                        == _identity(held_before),
                        f"predecessor bytes changed before backup copy: {relative}",
                    )
                    (
                        predecessor_descriptor,
                        predecessor_raw,
                        predecessor_info,
                    ) = _copy_held_predecessor_to_backup(
                        authority,
                        row,
                        source_predecessor_descriptor,
                        held_raw,
                        held_before,
                    )
                finally:
                    os.close(source_predecessor_descriptor)
                _verify_held_predecessor_backup(
                    authority,
                    row,
                    predecessor_descriptor,
                    predecessor_raw,
                    predecessor_info,
                )
                backup_exists = True
                target_kind = None
            elif target_kind is None:
                require(backup_exists, f"missing target lacks predecessor backup: {relative}")

            if target_kind != "successor":
                require(
                    stage_descriptor is not None and stage_info is not None,
                    f"held stage is missing: {relative}",
                )
                if predecessor_descriptor is None:
                    (
                        predecessor_descriptor,
                        predecessor_raw,
                        predecessor_info,
                    ) = _open_held_predecessor_backup(authority, row)
                require(
                    predecessor_info is not None,
                    f"held predecessor backup is missing: {relative}",
                )
                _verify_held_predecessor_backup(
                    authority,
                    row,
                    predecessor_descriptor,
                    predecessor_raw,
                    predecessor_info,
                )
                _link_held_stage_to_target(
                    authority,
                    row,
                    stage_descriptor,
                    stage_raw,
                    stage_info,
                )
                authority.verify_directory_paths()
                _verify_held_predecessor_backup(
                    authority,
                    row,
                    predecessor_descriptor,
                    predecessor_raw,
                    predecessor_info,
                )
        except BaseException:
            if stage_descriptor is not None:
                _unlink_held_stage_target_for_rollback(
                    authority,
                    row,
                    stage_descriptor,
                    stage_raw,
                )
            if (
                stage_descriptor is not None
                and stage_info is not None
                and not _entry_exists(parent_fd, relative.name)
                and _entry_exists(parent_fd, backup.name)
            ):
                _rebuild_stage_after_link_race(
                    authority,
                    row,
                    stage_raw,
                    stage_info,
                )
                require(
                    predecessor_descriptor is not None,
                    f"held predecessor is missing during rollback: {relative}",
                )
                predecessor_raw = _read_descriptor(predecessor_descriptor)
                predecessor_info = os.fstat(predecessor_descriptor)
                require(
                    bytes_sha256(predecessor_raw) == row["predecessor_sha256"]
                    and stat.S_ISREG(predecessor_info.st_mode)
                    and predecessor_info.st_uid == os.geteuid()
                    and not (stat.S_IMODE(predecessor_info.st_mode) & 0o002),
                    f"held predecessor cannot be refreshed for rollback: {relative}",
                )
                _restore_predecessor_target(
                    authority,
                    row,
                    predecessor_descriptor,
                    predecessor_raw,
                    predecessor_info,
                )
            raise
        finally:
            if stage_descriptor is not None:
                os.close(stage_descriptor)
            if predecessor_descriptor is not None:
                os.close(predecessor_descriptor)

        published_raw, published_info = _read_at(
            parent_fd,
            relative.name,
            relative.as_posix(),
            expected_mode=0o600,
        )
        require(
            published_info.st_nlink == 1
            and len(published_raw) == row["byte_length"]
            and bytes_sha256(published_raw) == row["successor_sha256"],
            f"published successor differs: {relative}",
        )
        if publish_hook is not None:
            publish_hook(row["index"], relative)
        authority.verify_directory_paths()


def _ensure_rollback_stage_from_successor(
    authority: RepositorySnapshot,
    row: dict[str, Any],
    successor_descriptor: int,
    successor_raw: bytes,
    successor_info: os.stat_result,
) -> os.stat_result:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    sidecar = Path(row["authority_path"])
    require(
        _identity(os.fstat(successor_descriptor)) == _identity(successor_info)
        and successor_raw == _read_descriptor(successor_descriptor)
        and len(successor_raw) == row["byte_length"]
        and bytes_sha256(successor_raw) == row["successor_sha256"],
        f"rollback held successor differs: {relative}",
    )
    stage_info: os.stat_result | None = None
    if _entry_exists(parent_fd, stage.name):
        current = os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)
        if (current.st_dev, current.st_ino) == (
            successor_info.st_dev,
            successor_info.st_ino,
        ):
            stage_info = current
        elif (
            stat.S_ISREG(current.st_mode)
            and not stat.S_ISLNK(current.st_mode)
            and current.st_uid == os.geteuid()
            and stat.S_IMODE(current.st_mode) == 0o600
            and current.st_nlink == 1
        ):
            current_raw, verified = _read_at(
                parent_fd,
                stage.name,
                stage.as_posix(),
                expected_mode=0o600,
            )
            if (
                len(current_raw) == row["byte_length"]
                and bytes_sha256(current_raw) == row["successor_sha256"]
            ):
                stage_info = verified
            else:
                _move_to_available_quarantine(
                    authority,
                    row,
                    stage,
                    verified,
                    "damaged rollback stage",
                )
        else:
            require(
                stat.S_ISREG(current.st_mode)
                and not stat.S_ISLNK(current.st_mode)
                and current.st_uid == os.geteuid(),
                f"rollback stage is unsafe: {relative}",
            )
            _move_to_available_quarantine(
                authority,
                row,
                stage,
                current,
                "foreign rollback stage",
            )
    if stage_info is None:
        _link_fd_noreplace_at(successor_descriptor, parent_fd, stage.name)
        os.fsync(parent_fd)
        stage_info = os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)
        require(
            (stage_info.st_dev, stage_info.st_ino)
            == (successor_info.st_dev, successor_info.st_ino),
            f"rollback successor stage link differs: {relative}",
        )

    anticipated = _stage_authority_value(row, stage_info)
    if (stage_info.st_dev, stage_info.st_ino) == (
        successor_info.st_dev,
        successor_info.st_ino,
    ):
        anticipated["stage_file_identity"]["link_count"] = 1
        anticipated["authority_content_sha256"] = _projection_sha(
            anticipated,
            "authority_content_sha256",
        )
    anticipated_raw = json_bytes(anticipated)
    if _entry_exists(parent_fd, sidecar.name):
        existing_matches = False
        try:
            existing_raw, existing_info = _read_at(
                parent_fd,
                sidecar.name,
                sidecar.as_posix(),
                expected_mode=0o600,
            )
            existing_matches = existing_raw == anticipated_raw
        except (BuildError, ValueError, KeyError):
            existing_info = os.stat(
                sidecar.name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            require(
                stat.S_ISREG(existing_info.st_mode)
                and not stat.S_ISLNK(existing_info.st_mode)
                and existing_info.st_uid == os.geteuid(),
                f"rollback stage authority is unsafe: {relative}",
            )
        if not existing_matches:
            _move_to_available_quarantine(
                authority,
                row,
                sidecar,
                existing_info,
                "obsolete rollback stage authority",
            )
    if not _entry_exists(parent_fd, sidecar.name):
        _write_atomic_exclusive_at(parent_fd, sidecar.name, anticipated_raw)
    sidecar_raw, _sidecar_info = _read_at(
        parent_fd,
        sidecar.name,
        sidecar.as_posix(),
        expected_mode=0o600,
    )
    require(
        sidecar_raw == anticipated_raw,
        f"rollback stage authority differs: {relative}",
    )
    return os.stat(stage.name, dir_fd=parent_fd, follow_symlinks=False)


def _rollback_published_row_to_predecessor(
    authority: RepositorySnapshot,
    row: dict[str, Any],
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    backup = Path(row["backup_path"])
    target_exists = _entry_exists(parent_fd, relative.name)
    backup_exists = _entry_exists(parent_fd, backup.name)
    if target_exists:
        target_raw, target_info = _read_at(
            parent_fd,
            relative.name,
            relative.as_posix(),
            predecessor=True,
        )
        target_sha256 = bytes_sha256(target_raw)
        if target_sha256 == row["successor_sha256"]:
            require(backup_exists, f"rollback successor lacks predecessor backup: {relative}")
            predecessor_fd, predecessor_raw, predecessor_info = (
                _open_held_predecessor_backup(authority, row)
            )
            successor_fd = os.open(
                relative.name,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_NOFOLLOW"),
                dir_fd=parent_fd,
            )
            try:
                require(
                    _identity(os.fstat(successor_fd)) == _identity(target_info)
                    and _read_descriptor(successor_fd) == target_raw,
                    f"rollback successor changed while opening: {relative}",
                )
                _ensure_rollback_stage_from_successor(
                    authority,
                    row,
                    successor_fd,
                    target_raw,
                    target_info,
                )
                current_target_info = os.stat(
                    relative.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                require(
                    (current_target_info.st_dev, current_target_info.st_ino)
                    == (target_info.st_dev, target_info.st_ino),
                    f"rollback successor target changed: {relative}",
                )
                _unlink_exact_at(
                    parent_fd,
                    relative.name,
                    current_target_info,
                    f"rollback successor target {relative}",
                )
                _validated_stage(authority, row)
                _restore_predecessor_target(
                    authority,
                    row,
                    predecessor_fd,
                    predecessor_raw,
                    predecessor_info,
                )
            finally:
                os.close(successor_fd)
                os.close(predecessor_fd)
        else:
            if target_sha256 == row["predecessor_sha256"] and backup_exists:
                backup_raw, backup_info = _read_at(
                    parent_fd,
                    backup.name,
                    backup.as_posix(),
                    expected_mode=0o600,
                )
                require(
                    bytes_sha256(backup_raw) == row["predecessor_sha256"]
                    and _identity(
                        os.stat(
                            backup.name,
                            dir_fd=parent_fd,
                            follow_symlinks=False,
                        )
                    )
                    == _identity(backup_info),
                    f"rollback private predecessor backup differs: {relative}",
                )
                _unlink_exact_at(
                    parent_fd,
                    backup.name,
                    backup_info,
                    f"rollback redundant backup {relative}",
                )
                backup_exists = False
            require(
                target_sha256 == row["predecessor_sha256"] and not backup_exists,
                f"rollback target differs: {relative}",
            )
    else:
        require(backup_exists, f"rollback target and backup are missing: {relative}")
        predecessor_fd, predecessor_raw, predecessor_info = (
            _open_held_predecessor_backup(authority, row)
        )
        try:
            _restore_predecessor_target(
                authority,
                row,
                predecessor_fd,
                predecessor_raw,
                predecessor_info,
            )
        finally:
            os.close(predecessor_fd)
    restored_raw, _restored_info = _read_at(
        parent_fd,
        relative.name,
        relative.as_posix(),
        predecessor=True,
    )
    require(
        bytes_sha256(restored_raw) == row["predecessor_sha256"]
        and not _entry_exists(parent_fd, backup.name),
        f"rollback predecessor differs: {relative}",
    )
    _validated_stage(authority, row)


def _discard_validated_stage(
    authority: RepositorySnapshot,
    row: dict[str, Any],
) -> None:
    relative = Path(row["path"])
    parent_fd = authority.directory_fd(relative.parent)
    stage = Path(row["stage_path"])
    sidecar = Path(row["authority_path"])
    _stage_raw, stage_info = _validated_stage(authority, row)
    _sidecar_raw, sidecar_info = _read_at(
        parent_fd,
        sidecar.name,
        sidecar.as_posix(),
        expected_mode=0o600,
    )
    _unlink_exact_at(
        parent_fd,
        sidecar.name,
        sidecar_info,
        f"discarded stage authority {relative}",
    )
    _unlink_exact_at(
        parent_fd,
        stage.name,
        stage_info,
        f"discarded stage {relative}",
    )


def _rollback_transaction_to_predecessors(
    authority: RepositorySnapshot,
    manifest: dict[str, Any],
    journal_raw: bytes,
) -> None:
    for row in reversed(manifest["outputs"]):
        _rollback_published_row_to_predecessor(authority, row)
    root_is_current = authority.directory_path_is_current(Path())
    for row, relative in zip(manifest["outputs"], OUTPUT_PATHS, strict=True):
        if not root_is_current or not authority.directory_path_is_current(relative.parent):
            _discard_validated_stage(authority, row)
    if not root_is_current:
        root_fd = authority.directory_fd(Path())
        current, journal_info = _read_at(
            root_fd,
            TRANSACTION_JOURNAL_REL.name,
            TRANSACTION_JOURNAL_REL.as_posix(),
            expected_mode=0o600,
        )
        require(current == journal_raw, "rollback transaction journal differs")
        require(
            _identity(
                os.stat(
                    TRANSACTION_JOURNAL_REL.name,
                    dir_fd=root_fd,
                    follow_symlinks=False,
                )
            )
            == _identity(journal_info),
            "rollback transaction journal path differs",
        )
        _unlink_exact_at(
            root_fd,
            TRANSACTION_JOURNAL_REL.name,
            journal_info,
            "rollback transaction journal",
        )


def _all_targets_are_predecessors(
    authority: RepositorySnapshot,
    manifest: dict[str, Any],
) -> bool:
    for row, relative in zip(manifest["outputs"], OUTPUT_PATHS, strict=True):
        parent_fd = authority.directory_fd(relative.parent)
        if not _entry_exists(parent_fd, relative.name):
            return False
        raw, _info = _read_at(
            parent_fd,
            relative.name,
            relative.as_posix(),
            predecessor=True,
        )
        if bytes_sha256(raw) != row["predecessor_sha256"]:
            return False
        if _entry_exists(parent_fd, Path(row["backup_path"]).name):
            return False
    return True


def _normalize_private_backup_before_unlink_crash(
    authority: RepositorySnapshot,
    manifest: dict[str, Any],
) -> None:
    for row, relative in zip(manifest["outputs"], OUTPUT_PATHS, strict=True):
        parent_fd = authority.directory_fd(relative.parent)
        backup = Path(row["backup_path"])
        if not (
            _entry_exists(parent_fd, relative.name)
            and _entry_exists(parent_fd, backup.name)
        ):
            continue
        target_raw, _target_info = _read_at(
            parent_fd,
            relative.name,
            relative.as_posix(),
            predecessor=True,
        )
        if bytes_sha256(target_raw) != row["predecessor_sha256"]:
            continue
        backup_raw, backup_info = _read_at(
            parent_fd,
            backup.name,
            backup.as_posix(),
            expected_mode=0o600,
        )
        require(
            bytes_sha256(backup_raw) == row["predecessor_sha256"]
            and _identity(
                os.stat(
                    backup.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
            )
            == _identity(backup_info),
            f"private predecessor crash backup differs: {relative}",
        )
        _unlink_exact_at(
            parent_fd,
            backup.name,
            backup_info,
            f"private predecessor crash backup {relative}",
        )


def _cleanup_transaction(
    authority: RepositorySnapshot,
    manifest: dict[str, Any],
    journal_raw: bytes,
) -> None:
    authority.verify_directory_paths()
    for row, relative in zip(manifest["outputs"], OUTPUT_PATHS, strict=True):
        authority.verify_directory_paths()
        parent_fd = authority.directory_fd(relative.parent)
        backup = Path(row["backup_path"])
        sidecar = Path(row["authority_path"])
        require(not _entry_exists(parent_fd, Path(row["stage_path"]).name), f"stage remains after publish: {relative}")
        if _entry_exists(parent_fd, backup.name):
            raw, info = _read_at(parent_fd, backup.name, backup.as_posix(), predecessor=True)
            require(bytes_sha256(raw) == row["predecessor_sha256"], f"cleanup backup differs: {relative}")
            require(_identity(os.stat(backup.name, dir_fd=parent_fd, follow_symlinks=False)) == _identity(info), f"cleanup backup identity differs: {relative}")
            _unlink_exact_at(
                parent_fd,
                backup.name,
                info,
                f"cleanup backup {relative}",
            )
            authority.verify_directory_paths()
        if _entry_exists(parent_fd, sidecar.name):
            raw, info = _read_at(parent_fd, sidecar.name, sidecar.as_posix(), expected_mode=0o600)
            value = _json_from_bytes(raw, sidecar)
            require(
                value.get("authority_content_sha256") == _projection_sha(value, "authority_content_sha256")
                and value.get("schema_version") == "walksafe.fp048-stage-authority.v1"
                and value.get("successor_id") == SUCCESSOR_ID
                and value.get("transaction_token") == TRANSACTION_TOKEN
                and value.get("index") == row["index"]
                and value.get("path") == relative.as_posix()
                and value.get("stage_path") == row["stage_path"]
                and value.get("successor_sha256") == row["successor_sha256"],
                f"cleanup stage authority differs: {relative}",
            )
            require(_identity(os.stat(sidecar.name, dir_fd=parent_fd, follow_symlinks=False)) == _identity(info), f"cleanup sidecar identity differs: {relative}")
            _unlink_exact_at(
                parent_fd,
                sidecar.name,
                info,
                f"cleanup stage authority {relative}",
            )
            authority.verify_directory_paths()
    authority.verify_directory_paths()
    root_fd = authority.directory_fd(Path())
    current, info = _read_at(
        root_fd,
        TRANSACTION_JOURNAL_REL.name,
        TRANSACTION_JOURNAL_REL.as_posix(),
        expected_mode=0o600,
    )
    require(current == journal_raw, "transaction journal changed before cleanup")
    require(
        _identity(os.stat(TRANSACTION_JOURNAL_REL.name, dir_fd=root_fd, follow_symlinks=False))
        == _identity(info),
        "transaction journal identity changed before cleanup",
    )
    authority.verify_directory_paths()
    _unlink_exact_at(
        root_fd,
        TRANSACTION_JOURNAL_REL.name,
        info,
        "cleanup transaction journal",
    )
    authority.verify_directory_paths()


@contextmanager
def _publication_lock(root: Path) -> Iterator[PublicationLease]:
    resolved = root.resolve(strict=True)
    descriptor = os.open(
        resolved,
        os.O_RDONLY
        | _required_open_flag("O_DIRECTORY")
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW"),
    )
    try:
        info = os.fstat(descriptor)
        require(
            stat.S_ISDIR(info.st_mode)
            and info.st_uid == os.geteuid()
            and not (stat.S_IMODE(info.st_mode) & 0o002),
            "publication lock authority differs",
        )
        require(
            _directory_identity(os.stat(resolved, follow_symlinks=False))
            == _directory_identity(info),
            "publication lock root path differs",
        )
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        require(
            _directory_identity(os.stat(resolved, follow_symlinks=False))
            == _directory_identity(os.fstat(descriptor)),
            "publication lock root changed while waiting",
        )
        lease = PublicationLease(
            root=resolved,
            descriptor=descriptor,
            identity=_directory_identity(os.fstat(descriptor)),
        )
        lease.verify()
        lease_token = _ACTIVE_PUBLICATION_LEASE.set(lease)
        try:
            yield lease
        finally:
            try:
                lease.verify()
            finally:
                lease.active = False
                _ACTIVE_PUBLICATION_LEASE.reset(lease_token)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _preflight_transaction_members(authority: RepositorySnapshot) -> None:
    root_fd = authority.directory_fd(Path())
    require(
        not _entry_exists(root_fd, TRANSACTION_JOURNAL_REL.name),
        "transaction journal unexpectedly exists",
    )
    for relative in OUTPUT_PATHS:
        parent_fd = authority.directory_fd(relative.parent)
        for kind in ("stage", "backup", "authority", "quarantine"):
            member = _transaction_member_relative(relative, kind)
            require(
                not _entry_exists(parent_fd, member.name),
                f"transaction member collision: {member}",
            )


def _bind_snapshot_authorities(
    authority: RepositorySnapshot,
    source_snapshot: RepositorySnapshot,
) -> None:
    directories = {Path(), *(relative.parent for relative in OUTPUT_PATHS)}
    for relative in directories:
        require(
            _directory_identity(os.fstat(authority.directory_fd(relative)))
            == _directory_identity(os.fstat(source_snapshot.directory_fd(relative))),
            f"publication/source directory authority differs: {relative}",
        )
    for relative in OUTPUT_PATHS:
        parent_fd = authority.directory_fd(relative.parent)
        require(
            _identity(os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False))
            == _identity(source_snapshot.entry(relative).info),
            f"publication/source predecessor identity differs: {relative}",
        )


def write_successor(
    root: Path = ROOT,
    *,
    expected_input_sha256: dict[Path, str] | None = None,
    expected_document_ids: dict[Path, str] | None = None,
    replace_func: Callable[[Path, Path], None] | None = None,
    publish_hook: Callable[[int, Path], None] | None = None,
    stage_write_hook: Callable[[int, Path, int, int], None] | None = None,
    stage_created_hook: Callable[[int, Path], None] | None = None,
    before_commit_hook: Callable[[], None] | None = None,
    before_publish_hook: Callable[[], None] | None = None,
) -> str:
    pins = _effective_input_pins(expected_input_sha256)
    require(replace_func is None, "custom replace function is forbidden")
    root = root.resolve(strict=True)
    with _publication_lock(root) as publication_lease:
        authority = RepositorySnapshot(root)
        authority.bind_publication_lease(publication_lease)
        require(
            _directory_identity(os.fstat(authority.directory_fd(Path())))
            == publication_lease.identity,
            "publication snapshot differs from locked repository inode",
        )
        source_snapshot: RepositorySnapshot | None = None
        transaction_authority = authority
        try:
            for relative in OUTPUT_PATHS:
                authority.directory_fd(relative.parent)
            authority.verify_directory_paths()
            existing = _journal(authority, pins)
            authority.verify_directory_paths()
            recovered = existing is not None
            if existing is None:
                predecessor_flags: list[bool] = []
                for relative in OUTPUT_PATHS:
                    parent_fd = authority.directory_fd(relative.parent)
                    raw, _info = _read_at(
                        parent_fd,
                        relative.name,
                        relative.as_posix(),
                        predecessor=True,
                    )
                    predecessor_flags.append(
                        bytes_sha256(raw) == PINNED_PREDECESSOR_SHA256[relative]
                    )
                if not all(predecessor_flags):
                    require(
                        not any(predecessor_flags),
                        "output set is mixed without a durable transaction journal",
                    )
                    check_successor(
                        root,
                        expected_input_sha256=pins,
                        expected_document_ids=expected_document_ids,
                    )
                    return "ALREADY_CURRENT"

                outputs, source_snapshot = _prepare_predecessor_build(
                    root,
                    pins,
                    expected_document_ids,
                )
                source_snapshot.bind_publication_lease(publication_lease)
                _bind_snapshot_authorities(authority, source_snapshot)
                transaction_authority = source_snapshot
                manifest, journal_raw = _transaction_manifest(outputs, pins)
                _preflight_transaction_members(transaction_authority)
                _write_atomic_exclusive_at(
                    transaction_authority.directory_fd(Path()),
                    TRANSACTION_JOURNAL_REL.name,
                    journal_raw,
                )
                _ensure_stages(
                    transaction_authority,
                    manifest,
                    outputs,
                    stage_write_hook,
                    stage_created_hook,
                )
            else:
                manifest, journal_raw = existing
                _normalize_private_backup_before_unlink_crash(
                    authority,
                    manifest,
                )
                if _all_targets_are_predecessors(authority, manifest):
                    outputs, source_snapshot = _prepare_predecessor_build(
                        root,
                        pins,
                        expected_document_ids,
                    )
                    source_snapshot.bind_publication_lease(publication_lease)
                    _bind_snapshot_authorities(authority, source_snapshot)
                    transaction_authority = source_snapshot
                    expected_manifest, expected_journal_raw = _transaction_manifest(
                        outputs,
                        pins,
                    )
                    require(
                        manifest == expected_manifest and journal_raw == expected_journal_raw,
                        "recovery transaction differs from current held source snapshot",
                    )
                    _ensure_stages(
                        transaction_authority,
                        manifest,
                        outputs,
                        stage_write_hook,
                        stage_created_hook,
                    )

            try:
                if before_commit_hook is not None:
                    before_commit_hook()
                if source_snapshot is not None:
                    source_snapshot.verify()
                else:
                    authority.verify()
                expected_stage_identities = _snapshot_staged_successor_identities(
                    transaction_authority,
                    manifest,
                )
                if before_publish_hook is not None:
                    before_publish_hook()
                if source_snapshot is not None:
                    source_snapshot.verify()
                else:
                    authority.verify()
                _publish_transaction(
                    transaction_authority,
                    manifest,
                    publish_hook,
                    source_snapshot,
                    expected_stage_identities,
                )
                if source_snapshot is not None:
                    source_snapshot.verify(exclude_paths=set(OUTPUT_PATHS))
                else:
                    authority.verify_directory_paths()
                check_successor(
                    root,
                    expected_input_sha256=pins,
                    expected_document_ids=expected_document_ids,
                )
            except BaseException as publication_error:
                try:
                    _rollback_transaction_to_predecessors(
                        transaction_authority,
                        manifest,
                        journal_raw,
                    )
                except BaseException:
                    raise publication_error
                raise
            _cleanup_transaction(transaction_authority, manifest, journal_raw)
            transaction_authority.verify_directory_paths()
            check_successor(
                root,
                expected_input_sha256=pins,
                expected_document_ids=expected_document_ids,
            )
            transaction_authority.verify_directory_paths()
            if recovered:
                return "RECOVERED_AND_PUBLISHED_SUCCESSOR"
            return "PUBLISHED_SUCCESSOR"
        finally:
            if source_snapshot is not None:
                source_snapshot.close()
            authority.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the already-published exact successor without writing",
    )
    args = parser.parse_args(argv)
    try:
        if args.check:
            check_successor(ROOT)
            print(f"PASS {SUCCESSOR_ID} exact six-document successor")
        else:
            status = write_successor(ROOT)
            print(f"PASS {SUCCESSOR_ID} {status}")
    except (BuildError, OSError, ValueError, KeyError) as exc:
        print(f"FAIL {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
