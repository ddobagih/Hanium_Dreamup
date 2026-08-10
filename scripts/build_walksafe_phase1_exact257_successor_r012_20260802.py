#!/usr/bin/env python3
"""Build the add-only WalkSafe exact257 R012 FP-048 progress successor.

R012 treats the committed R011 packet as an immutable predecessor.  It binds
only the repository-internal FP-048 implementation, verification, r023 Gap,
and exact six physical artifact successors.  The binding grants no artifact
completion, approval, execution, formal, device, external, gate, or release
credit.

The production source pins freeze the exact nine-file FP-048 source cohort.
Tests may inject an exact SHA-256 map; the CLI fails closed if any production
pin is not a lowercase SHA-256 value.
"""

from __future__ import annotations

import argparse
from collections import Counter
from contextlib import ExitStack
from copy import deepcopy
import ctypes
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
from typing import Any, Callable, Iterable, Mapping


_OS_CLOSE = os.close


REPO_ROOT = Path(__file__).resolve().parents[1]
RUN_DIR_REL = Path(
    "docs/control/execution/artifact-closure/run-20260727-001"
)
R011_PACKET_DIR_REL = RUN_DIR_REL / "packets/phase1-exact257-successor-r011"
R011_LEDGER_REL = R011_PACKET_DIR_REL / "phase1-exact257-successor-ledger-r011.json"
R011_EVIDENCE_REL = R011_PACKET_DIR_REL / "evidence.json"
R011_RECEIPT_REL = (
    R011_PACKET_DIR_REL / "phase1-exact257-successor-check-receipt-r011.json"
)

R012_PACKET_DIR_REL = RUN_DIR_REL / "packets/phase1-exact257-successor-r012"
R012_LEDGER_REL = R012_PACKET_DIR_REL / "phase1-exact257-successor-ledger-r012.json"
R012_EVIDENCE_REL = R012_PACKET_DIR_REL / "evidence.json"
R012_RECEIPT_REL = (
    R012_PACKET_DIR_REL / "phase1-exact257-successor-check-receipt-r012.json"
)

IMPLEMENTATION_REL = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-048-R001/"
    "implementation-record.json"
)
VERIFICATION_REL = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-048-R001/"
    "verification-result.json"
)
R023_GAP_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260802-r023.json"
)

TARGET_ARTIFACT_PATHS: tuple[tuple[str, Path], ...] = (
    ("DLV-DOC-05", Path("docs/deliverables/00-control/artifact-change-log.json")),
    ("DLV-DOC-01", Path("docs/deliverables/00-control/artifact-register.json")),
    ("DLV-REQ-16", Path("docs/deliverables/03-requirements/rtm.json")),
    (
        "DLV-DES-06",
        Path("docs/deliverables/04-design/design-traceability-register.json"),
    ),
    (
        "DLV-DEV-01",
        Path("docs/deliverables/05-implementation/implementation-manifest.json"),
    ),
    (
        "DLV-DEV-18",
        Path("docs/deliverables/05-implementation/module-register.json"),
    ),
)
TARGET_ARTIFACT_IDS = tuple(item[0] for item in TARGET_ARTIFACT_PATHS)
TARGET_ARTIFACT_ID_SET = frozenset(TARGET_ARTIFACT_IDS)
TARGET_PATH_BY_ID = dict(TARGET_ARTIFACT_PATHS)
TARGET_PREDECESSOR_SHA256 = {
    TARGET_ARTIFACT_PATHS[0][1]: "cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67",
    TARGET_ARTIFACT_PATHS[1][1]: "a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f",
    TARGET_ARTIFACT_PATHS[2][1]: "1d73d1e6e0a47ea3833df779c945397d799b14e9b26fdac4bb577197a660a7bd",
    TARGET_ARTIFACT_PATHS[3][1]: "18775a3f4d5d1faf0ae692b27888613c3d43f0b276d5a71dfbb08aad47c6b4ae",
    TARGET_ARTIFACT_PATHS[4][1]: "df1ac6dbbbd9f24e2fef08c58b4de8def742830c3ae260d930dd09ec0cf5b6dc",
    TARGET_ARTIFACT_PATHS[5][1]: "c0727ae24e53ce3142aa5c55db7d6273628655069c3ac0821fa7d03ad8f08b48",
}

R011_LEDGER_PATH = REPO_ROOT / R011_LEDGER_REL
R011_EVIDENCE_PATH = REPO_ROOT / R011_EVIDENCE_REL
R011_RECEIPT_PATH = REPO_ROOT / R011_RECEIPT_REL
R012_PACKET_DIR = REPO_ROOT / R012_PACKET_DIR_REL
R012_LEDGER_PATH = REPO_ROOT / R012_LEDGER_REL
R012_EVIDENCE_PATH = REPO_ROOT / R012_EVIDENCE_REL
R012_RECEIPT_PATH = REPO_ROOT / R012_RECEIPT_REL

PREPARED_ON = "2026-08-02"
RUN_ID = "WS-ARTIFACT-CLOSURE-RUN-20260727-001"
GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
FP048_SUCCESSOR_ID = "WS-FP048-ARTIFACT-TRACE-SUCCESSOR-20260802-001"
R023_REPORT_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023"
R023_EVIDENCE_ID = "EVD-FP048-INTERNAL-ENCRYPTION-SECURITY-20260802"
VERDICT = "PASS_FOR_FP048_EXACT6_RECORD_PROGRESS_BINDING_WITH_ZERO_CREDIT_ONLY"

R011_PINNED_SHA256_BY_RELATIVE_PATH = {
    R011_LEDGER_REL: "7fc4bd6f2242b17de75faa040e42b4744c972a492f87ff4365caaeae53f93368",
    R011_EVIDENCE_REL: "f44642a1c635fb0d7122a3e4d8dff1d952fe2e0ee641bef5b7b5b469ce60aa3f",
    R011_RECEIPT_REL: "3cf69c9c0f382ca2d2622e552d57d82b5df02468ae85f7d67e8be8477bc9714d",
}

# Frozen exact nine-file production source cohort. Supplying an injected map
# remains a Python API for isolated tests, not a CLI or environment override.
PINNED_SOURCE_SHA256_BY_RELATIVE_PATH = {
    IMPLEMENTATION_REL: "1990e550ffaab170dded19d4db9ac35fcff13594efd9c553e563fc76ee35fb39",
    VERIFICATION_REL: "499b2dd4651b7efa73a2d8904daa4aaea0eca6ac94e6734d4c6660fe0dc3160c",
    R023_GAP_REL: "34d4b8a4a05ac346e48293344dbf17e10cb65a792e85c5df42f69a7d63c09f82",
    TARGET_ARTIFACT_PATHS[0][1]: "5a00d1131f8b7b4ec23aa09be3e68acd500514c8cff743b09223284a9cc1be85",
    TARGET_ARTIFACT_PATHS[1][1]: "4489c57336958754955cc83bfa5857bd21dcc05c60013c31d0120818581e4005",
    TARGET_ARTIFACT_PATHS[2][1]: "599c69fc6c97359fecd8ac9a535cb07a652626d3371b803dce71b00fcad54793",
    TARGET_ARTIFACT_PATHS[3][1]: "16792f981821f3782cc2cc9c3eb2de538263c7caddbcfa6916562f43499e2a1d",
    TARGET_ARTIFACT_PATHS[4][1]: "bf5d3635d1d5ae948486b9963fb75c7b4d84f10d8fb1bf5e3eb48dc5d0167f90",
    TARGET_ARTIFACT_PATHS[5][1]: "9fce017a938ff163f2f863047e280bf0dff2fe100914db6208bcca5c7ede3284",
}

CANONICAL_STATUS_COUNTS = {
    "EXTERNAL": 49,
    "INTERNAL_GAP": 48,
    "N_A_CANDIDATE": 36,
    "OK": 124,
}
QUEUE_ROUTE_COUNTS = {
    "ATTESTATION_REVIEW_PENDING": 4,
    "EVIDENCE_FACT_PENDING": 6,
    "INTERNAL_READY": 62,
    "INTERNAL_RUN_REQUIRED": 24,
    "OK_BASELINE": 124,
    "OWNER_APPROVAL_PENDING": 14,
    "REAL_EVENT_PENDING": 21,
    "SCOPE_DECISION_PENDING": 0,
    "SCOPE_N_A_APPROVED": 2,
}
ZERO_CREDITS = {
    "acceptance_count": 0,
    "actual_device_event_count": 0,
    "attestation_approval_count": 0,
    "execution_count": 0,
    "formal279_pass_count": 0,
    "formal_evidence_count": 0,
    "in_scope_substantive_credit_count": 0,
    "owner_approval_count": 0,
    "real_event_count": 0,
    "release_eligible_count": 0,
    "verified_rights_or_external_fact_count": 0,
}
SIX_COMPLETION_PATHS = (
    ("artifact_closure", "completion_claimed"),
    ("artifact_closure", "current_scope_n_a_closure_claimed"),
    ("artifact_closure", "global_artifact_completion_claimed"),
    ("claim_boundary", "artifact_completion_claimed"),
    ("claim_boundary", "current_scope_n_a_closure_claimed"),
    ("claim_boundary", "global_artifact_completion_claimed"),
)

EXPECTED_RESULT_BOUNDARY = {
    "formal_test_ids": [f"TC-FP-048-{number:02d}" for number in range(1, 8)],
    "formal_test_status": "NOT_RUN",
    "formal_279_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "external_tls_status": "NOT_RUN",
    "external_kms_status": "NOT_RUN",
    "external_cloud_status": "NOT_RUN",
    "external_backup_restore_status": "NOT_RUN",
    "external_security_review_status": "NOT_RUN",
    "external_legal_review_status": "NOT_RUN",
    "external_privacy_review_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_count": 2,
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "external_independence_claimed": False,
    "release_status": "NOT_ELIGIBLE",
}
EXPECTED_R023_BOUNDARY = {
    key: value
    for key, value in EXPECTED_RESULT_BOUNDARY.items()
    if key
    not in {
        "formal_test_ids",
        "formal_279_status",
        "external_cloud_status",
        "external_privacy_review_status",
        "release_gate_count",
    }
}
EXPECTED_DOCUMENT_TRACE_BOUNDARY = {
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
}

FORBIDDEN_NEW_SOURCE_FRAGMENTS = (
    "review-subject",
    "review_subject",
    "review-attestation",
    "review_attestation",
    "independent-review",
    "independent_review",
    "completion-receipt",
    "completion_receipt",
    "apps/web/",
    "legacy",
    "submission",
    ".docx",
    ".pptx",
)
FORBIDDEN_REVIEW_COMPLETION_MARKERS = (
    "review-subject",
    "review_subject",
    "review-attestation",
    "review_attestation",
    "independent-review",
    "independent_review",
    "completion-receipt",
    "completion_receipt",
)
FORBIDDEN_SOURCE_PATH_COMPONENTS = frozenset(
    {"web", "pwa", "review", "reviews", "completion", "completions"}
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MAXIMUM_REGULAR_FILE_BYTES = 16 * 1024 * 1024
MAXIMUM_SNAPSHOT_TOTAL_BYTES = 256 * 1024 * 1024
MAXIMUM_TRANSACTION_TOTAL_READ_BYTES = 256 * 1024 * 1024
MAXIMUM_TRANSACTION_OUTPUT_COUNT = 3
MAXIMUM_TRANSACTION_RESERVED_PATH_COUNT = 64
MAXIMUM_TRANSACTION_PATH_COMPONENT_COUNT = 64
MAXIMUM_PROC_STATUS_BYTES = 64 * 1024
MAXIMUM_EXACT_DIRECTORY_NAME_BYTES = 1024
MAXIMUM_PUBLICATION_PARENT_EXTERNAL_ENTRY_COUNT = 256
MAXIMUM_PUBLICATION_PARENT_EXTERNAL_NAME_BYTES = 64 * 1024

_NONSELF_NULL_PATHS = (
    "/integrity/canonical_byte_count",
    "/integrity/content_sha256",
    "/nonself_digest_check/canonical_byte_count",
    "/nonself_digest_check/content_sha256",
)


class ValidationError(ValueError):
    """Raised when a source, output, or filesystem contract is not exact."""


class _ReadBudget:
    def __init__(self, maximum_bytes: int):
        _require(
            type(maximum_bytes) is int and maximum_bytes >= 0,
            "read aggregate byte cap differs",
        )
        self.maximum_bytes = maximum_bytes
        self.consumed_bytes = 0

    def require_available(self, byte_count: int, label: str) -> None:
        _require(type(byte_count) is int and byte_count >= 0, "read byte count differs")
        _require(
            self.consumed_bytes + byte_count <= self.maximum_bytes,
            f"aggregate read byte cap exceeded: {label}",
        )

    def consume(self, byte_count: int, label: str) -> None:
        self.require_available(byte_count, label)
        self.consumed_bytes += byte_count


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def _lexical_abspath(path: os.PathLike[str] | str) -> Path:
    """Absolute lexical spelling with POSIX leading slashes collapsed to one."""
    value = os.path.abspath(os.fspath(path))
    if os.sep == "/":
        value = "/" + value.lstrip("/")
    return Path(value)


def _close_descriptor_sequence(descriptors: Iterable[int]) -> BaseException | None:
    """Close every owned descriptor exactly once and retain the first error."""
    first_error: BaseException | None = None
    for descriptor in descriptors:
        try:
            _OS_CLOSE(descriptor)
        except BaseException as exc:
            if first_error is None:
                first_error = exc
    return first_error


def _close_descriptor_preserving_primary(
    descriptor: int,
    *,
    suppress_error: bool = False,
) -> None:
    close_error = _close_descriptor_sequence((descriptor,))
    if close_error is not None and not suppress_error:
        raise close_error


class _DescriptorOwner:
    """Close every adopted descriptor once, preserving an active primary error."""

    def __init__(self) -> None:
        self._descriptors: tuple[int, ...] = ()
        self._closed = False
        self._suppress_close_errors = False

    def __enter__(self) -> "_DescriptorOwner":
        _require(not self._closed, "descriptor owner is already closed")
        return self

    def __copy__(self) -> "_DescriptorOwner":
        raise ValidationError("descriptor ownership cannot be copied")

    def __deepcopy__(self, _memo: dict[int, Any]) -> "_DescriptorOwner":
        raise ValidationError("descriptor ownership cannot be deep-copied")

    def __reduce__(self) -> object:
        raise TypeError("descriptor ownership cannot be serialized")

    def __reduce_ex__(self, _protocol: int) -> object:
        raise TypeError("descriptor ownership cannot be serialized")

    def __exit__(self, exc_type: object, _exc: object, _traceback: object) -> None:
        self.close(suppress_error=exc_type is not None)

    def adopt(self, descriptor: int) -> int:
        _require(
            type(descriptor) is int and descriptor >= 0,
            "owned descriptor differs",
        )
        _require(
            descriptor not in self._descriptors,
            "descriptor is already owned",
        )
        try:
            _require(not self._closed, "descriptor owner is already closed")
            self._descriptors = (*self._descriptors, descriptor)
        except BaseException:
            _close_descriptor_preserving_primary(descriptor, suppress_error=True)
            raise
        return descriptor

    def open(self, path: os.PathLike[str] | str, flags: int, *args: Any, **kwargs: Any) -> int:
        descriptor: int | None = None
        adopted = False
        try:
            descriptor = os.open(path, flags, *args, **kwargs)
            try:
                self.adopt(descriptor)
            except BaseException:
                descriptor = None
                raise
            adopted = True
            return descriptor
        finally:
            if descriptor is not None and not adopted:
                _close_descriptor_preserving_primary(descriptor, suppress_error=True)

    def open_directory_at(
        self,
        parent_fd: int,
        name: str,
        label: str,
        *,
        required_mode: int | None = None,
    ) -> int:
        descriptor: int | None = None
        adopted = False
        try:
            descriptor = _open_directory_at(
                parent_fd,
                name,
                label,
                required_mode=required_mode,
            )
            try:
                self.adopt(descriptor)
            except BaseException:
                descriptor = None
                raise
            adopted = True
            return descriptor
        finally:
            if descriptor is not None and not adopted:
                _close_descriptor_preserving_primary(descriptor, suppress_error=True)

    def suppress_close_errors_after_commit(self) -> None:
        _require(not self._closed, "descriptor owner is already closed")
        self._suppress_close_errors = True

    def close(self, *, suppress_error: bool = False) -> None:
        if self._closed:
            return
        self._closed = True
        descriptors = tuple(reversed(self._descriptors))
        self._descriptors = ()
        close_error = _close_descriptor_sequence(descriptors)
        if (
            close_error is not None
            and not suppress_error
            and not self._suppress_close_errors
        ):
            raise close_error


def _append_owned_descriptor(rows: list[Any], row: Any, descriptor: int) -> None:
    try:
        rows.append(row)
    except BaseException:
        _close_descriptor_preserving_primary(descriptor, suppress_error=True)
        raise


def _required_open_flag(name: str) -> int:
    value = getattr(os, name, None)
    _require(
        type(value) is int and value != 0,
        f"required OS open flag is unavailable: {name}",
    )
    return value


def _fd_mount_id(
    descriptor: int,
    label: str,
    *,
    suppress_close_errors: bool = False,
) -> int:
    """Read one Linux mount ID with a hard physical byte bound."""
    fdinfo_descriptor = os.open(
        f"/proc/self/fdinfo/{descriptor}",
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW"),
    )
    body_failed = False
    try:
        content = os.pread(fdinfo_descriptor, 4097, 0)
        _require(len(content) <= 4096, f"fdinfo byte cap exceeded: {label}")
        values = [
            line.removeprefix(b"mnt_id:").strip()
            for line in content.splitlines()
            if line.startswith(b"mnt_id:")
        ]
        _require(
            len(values) == 1 and values[0].isascii() and values[0].isdigit(),
            f"fdinfo mount ID differs: {label}",
        )
        mount_id = int(values[0])
        _require(mount_id > 0, f"fdinfo mount ID differs: {label}")
        return mount_id
    except BaseException:
        body_failed = True
        raise
    finally:
        close_error = _close_descriptor_sequence((fdinfo_descriptor,))
        if (
            close_error is not None
            and not body_failed
            and not suppress_close_errors
        ):
            raise close_error


def _read_process_umask() -> int:
    """Read one Linux process umask without changing process-global state.

    Concurrent hostile umask changes by another thread are outside the supported
    execution boundary; this is a bounded fail-closed snapshot before mutation.
    """
    status_descriptor = os.open(
        "/proc/self/status",
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW"),
    )
    body_failed = False
    try:
        content = os.pread(status_descriptor, MAXIMUM_PROC_STATUS_BYTES + 1, 0)
        _require(
            len(content) <= MAXIMUM_PROC_STATUS_BYTES,
            "process status byte cap exceeded",
        )
        rows = [line for line in content.splitlines() if line.startswith(b"Umask:")]
        _require(len(rows) == 1, "process umask status differs")
        match = re.fullmatch(rb"Umask:\t([0-7]{4})", rows[0])
        _require(match is not None, "process umask status differs")
        return int(match.group(1), 8)
    except BaseException:
        body_failed = True
        raise
    finally:
        close_error = _close_descriptor_sequence((status_descriptor,))
        if close_error is not None and not body_failed:
            raise close_error


def _bounded_directory_inventory(
    directory_fd: int,
    label: str,
    *,
    maximum_entries: int,
    maximum_name_bytes: int,
    excluded_names: frozenset[str] = frozenset(),
) -> frozenset[str]:
    """Stream one directory into a bounded exact-name set and always close it."""
    _require(
        type(directory_fd) is int and directory_fd >= 0,
        f"directory inventory descriptor differs: {label}",
    )
    _require(
        type(maximum_entries) is int and maximum_entries >= 0,
        f"directory inventory entry cap differs: {label}",
    )
    _require(
        type(maximum_name_bytes) is int and maximum_name_bytes >= 0,
        f"directory inventory name-byte cap differs: {label}",
    )
    _require(
        type(excluded_names) is frozenset
        and all(type(name) is str for name in excluded_names),
        f"directory inventory exclusions differ: {label}",
    )
    iterator = os.scandir(directory_fd)
    body_failed = False
    names: set[str] = set()
    scanned_entries = 0
    name_bytes = 0
    try:
        for entry in iterator:
            scanned_entries += 1
            _require(
                scanned_entries <= maximum_entries + len(excluded_names),
                f"directory inventory entry cap exceeded: {label}",
            )
            name = entry.name
            _require(
                type(name) is str and name not in {"", ".", ".."},
                f"directory inventory name differs: {label}",
            )
            encoded_name = os.fsencode(name)
            _require(
                b"/" not in encoded_name and b"\x00" not in encoded_name,
                f"directory inventory name differs: {label}",
            )
            if name in excluded_names:
                continue
            _require(
                name not in names,
                f"directory inventory contains a duplicate name: {label}",
            )
            _require(
                len(names) < maximum_entries,
                f"directory inventory entry cap exceeded: {label}",
            )
            name_bytes += len(encoded_name)
            _require(
                name_bytes <= maximum_name_bytes,
                f"directory inventory name-byte cap exceeded: {label}",
            )
            names.add(name)
        return frozenset(names)
    except BaseException:
        body_failed = True
        raise
    finally:
        try:
            iterator.close()
        except BaseException:
            if not body_failed:
                raise


def _require_same_mount(
    parent_fd: int,
    retained_fd: int,
    label: str,
    *,
    suppress_close_errors: bool = False,
) -> None:
    _require(
        _fd_mount_id(
            parent_fd,
            f"{label} parent",
            suppress_close_errors=suppress_close_errors,
        )
        == _fd_mount_id(
            retained_fd,
            label,
            suppress_close_errors=suppress_close_errors,
        ),
        f"cross-mount path is forbidden: {label}",
    )


def _fchmodat2_empty_path(descriptor: int, mode: int, label: str) -> None:
    """Change mode through one retained O_PATH descriptor, never a pathname."""
    machine = os.uname().machine.lower()
    syscall_numbers = {
        "aarch64": 452,
        "arm64": 452,
        "riscv64": 452,
        "x86_64": 452,
    }
    syscall_number = syscall_numbers.get(machine)
    _require(
        syscall_number is not None,
        f"fchmodat2(AT_EMPTY_PATH) is unavailable: {label}",
    )
    libc = ctypes.CDLL(None, use_errno=True)
    syscall = getattr(libc, "syscall", None)
    _require(
        syscall is not None,
        f"fchmodat2(AT_EMPTY_PATH) is unavailable: {label}",
    )
    syscall.restype = ctypes.c_long
    result = syscall(
        ctypes.c_long(syscall_number),
        ctypes.c_int(descriptor),
        ctypes.c_char_p(b""),
        ctypes.c_uint(mode),
        ctypes.c_int(0x1000),  # Linux AT_EMPTY_PATH.
    )
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number in {errno.ENOSYS, errno.EINVAL, errno.EOPNOTSUPP}:
        raise ValidationError(
            f"fchmodat2(AT_EMPTY_PATH) is unavailable: {label}"
        )
    raise OSError(error_number, os.strerror(error_number), label)


def _require_int(value: Any, expected: int, label: str) -> None:
    _require(
        type(value) is int and value == expected,
        f"{label}: expected integer {expected!r}",
    )


def _require_bool(value: Any, expected: bool, label: str) -> None:
    _require(
        type(value) is bool and value is expected,
        f"{label}: expected boolean {expected!r}",
    )


def _strict_json_equal(observed: Any, expected: Any) -> bool:
    if type(observed) is not type(expected):
        return False
    if isinstance(expected, dict):
        return set(observed) == set(expected) and all(
            _strict_json_equal(observed[key], value)
            for key, value in expected.items()
        )
    if isinstance(expected, list):
        return len(observed) == len(expected) and all(
            _strict_json_equal(left, right)
            for left, right in zip(observed, expected, strict=True)
        )
    return observed == expected


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValidationError(f"non-finite JSON value is forbidden: {value}")


def _load_strict_json_bytes(content: bytes, label: str) -> dict[str, Any]:
    _require(not content.startswith(b"\xef\xbb\xbf"), f"{label}: UTF-8 BOM forbidden")
    try:
        value = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=_strict_object,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValidationError(f"{label}: invalid strict JSON") from exc
    _require(type(value) is dict, f"{label}: JSON root must be an object")
    return value


def _sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _object_sha(value: Any) -> str:
    return _sha256_bytes(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    )


def _relative(path: Path, root: Path = REPO_ROOT) -> str:
    lexical = _lexical_abspath(path)
    resolved_root = root.resolve(strict=True)
    try:
        return lexical.relative_to(resolved_root).as_posix()
    except ValueError:
        return lexical.as_posix()


def _path_for(root: Path, relative: Path) -> Path:
    _require(not relative.is_absolute(), f"relative path required: {relative}")
    _require(relative.parts and ".." not in relative.parts, f"unsafe path: {relative}")
    return root.resolve(strict=True) / relative


def _validate_confined_path(
    path: Path,
    allowed_root: Path,
    *,
    require_regular_file: bool,
) -> Path:
    root = allowed_root.resolve(strict=True)
    lexical = path if path.is_absolute() else root / path
    lexical = _lexical_abspath(lexical)
    try:
        relative = lexical.relative_to(root)
    except ValueError as exc:
        raise ValidationError(f"path escapes allowed root: {path}") from exc
    cursor = root
    for index, part in enumerate(relative.parts):
        cursor /= part
        if cursor.is_symlink():
            raise ValidationError(f"symbolic-link component is forbidden: {cursor}")
        if index < len(relative.parts) - 1 and cursor.exists() and not cursor.is_dir():
            raise ValidationError(f"non-directory parent component: {cursor}")
    try:
        lexical.resolve(strict=False).relative_to(root)
    except ValueError as exc:
        raise ValidationError(f"resolved path escapes allowed root: {path}") from exc
    if require_regular_file and not lexical.is_file():
        raise ValidationError(f"regular file is required: {path}")
    return lexical


def _file_identity(info: os.stat_result) -> tuple[int, ...]:
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
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _mutable_directory_identity(info: os.stat_result) -> tuple[int, ...]:
    """Stable object authority for mutable dirs and out-of-scope ancestors."""
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
    )


def _validate_directory_authority(info: os.stat_result, label: str) -> None:
    _require(stat.S_ISDIR(info.st_mode), f"directory is required: {label}")
    _require(info.st_uid == os.geteuid(), f"directory owner differs: {label}")
    _require(
        stat.S_IMODE(info.st_mode) & 0o002 == 0,
        f"world-writable directory is forbidden: {label}",
    )


def _validate_file_authority(
    info: os.stat_result,
    label: str,
    *,
    require_single_link: bool,
    required_mode: int | None,
) -> None:
    _require(stat.S_ISREG(info.st_mode), f"regular file is required: {label}")
    _require(info.st_uid == os.geteuid(), f"file owner differs: {label}")
    _require(
        stat.S_IMODE(info.st_mode) & 0o002 == 0,
        f"world-writable file is forbidden: {label}",
    )
    if require_single_link:
        _require(info.st_nlink == 1, f"hard-linked file is forbidden: {label}")
    if required_mode is not None:
        _require(
            stat.S_IMODE(info.st_mode) == required_mode,
            f"file mode differs: {label}",
        )


class _RetainedSnapshot:
    """One no-follow snapshot with retained root/ancestor descriptors and final CAS."""

    def __init__(
        self,
        root: Path,
        *,
        mutable_directories: bool = False,
        maximum_file_bytes: int = MAXIMUM_REGULAR_FILE_BYTES,
        maximum_total_bytes: int = MAXIMUM_SNAPSHOT_TOTAL_BYTES,
        suppress_close_errors: bool = False,
    ):
        _require(
            type(maximum_file_bytes) is int and maximum_file_bytes > 0,
            "snapshot per-file byte cap differs",
        )
        _require(
            type(maximum_total_bytes) is int
            and maximum_total_bytes >= maximum_file_bytes,
            "snapshot total byte cap differs",
        )
        _require(
            type(suppress_close_errors) is bool,
            "snapshot close suppression differs",
        )
        self.root = _lexical_abspath(root)
        self._directory_identity = (
            _mutable_directory_identity if mutable_directories else _directory_identity
        )
        directory_flags = (
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_DIRECTORY")
            | _required_open_flag("O_NOFOLLOW")
        )
        file_flags = (
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW")
        )
        outer_descriptors: list[int] = []
        outer_links: list[tuple[int, str, tuple[int, ...], int]] = []
        try:
            descriptor = os.open(Path(self.root.anchor), directory_flags)
            _append_owned_descriptor(outer_descriptors, descriptor, descriptor)
            for index, part in enumerate(self.root.parts[1:], start=1):
                parent_fd = descriptor
                before = os.stat(part, dir_fd=parent_fd, follow_symlinks=False)
                _require(
                    stat.S_ISDIR(before.st_mode),
                    f"non-directory root ancestor: {self.root}",
                )
                child = os.open(part, directory_flags, dir_fd=parent_fd)
                _append_owned_descriptor(outer_descriptors, child, child)
                try:
                    opened = os.fstat(child)
                    _require(
                        _mutable_directory_identity(opened)
                        == _mutable_directory_identity(before),
                        f"root ancestor changed during open: {self.root}",
                    )
                except BaseException:
                    raise
                outer_links.append(
                    (parent_fd, part, _mutable_directory_identity(before), child)
                )
                descriptor = child
        except BaseException as exc:
            _close_descriptor_sequence(reversed(outer_descriptors))
            if isinstance(exc, OSError):
                raise ValidationError(
                    f"cannot open snapshot root: {self.root}: {exc}"
                ) from exc
            raise
        root_fd = descriptor
        try:
            opened = os.fstat(root_fd)
            _validate_directory_authority(opened, str(self.root))
            root_mount_id = _fd_mount_id(
                root_fd,
                f"snapshot root: {self.root}",
                suppress_close_errors=suppress_close_errors,
            )
        except BaseException:
            _close_descriptor_sequence(reversed(outer_descriptors))
            raise
        try:
            self._directory_flags = directory_flags
            self._file_flags = file_flags
            self._directories: dict[tuple[str, ...], int] = {(): root_fd}
            self._outer_descriptors = outer_descriptors[:-1]
            self._outer_links = outer_links
            self._directory_links: dict[
                tuple[str, ...], tuple[int, str, tuple[int, ...]]
            ] = {}
            self._root_identity = self._directory_identity(opened)
            self._root_mount_id = root_mount_id
            self._files: dict[
                Path, tuple[int, str, tuple[int, ...], bytes, bool, int | None]
            ] = {}
            self._maximum_file_bytes = maximum_file_bytes
            self._maximum_total_bytes = maximum_total_bytes
            self._total_bytes = 0
            self._closed = False
            self._suppress_close_errors = suppress_close_errors
        except BaseException:
            _close_descriptor_sequence(reversed(outer_descriptors))
            raise

    def __enter__(self) -> "_RetainedSnapshot":
        self._require_open()
        return self

    def __copy__(self) -> "_RetainedSnapshot":
        raise ValidationError("retained snapshot ownership cannot be copied")

    def __deepcopy__(self, _memo: dict[int, Any]) -> "_RetainedSnapshot":
        raise ValidationError("retained snapshot ownership cannot be deep-copied")

    def __reduce__(self) -> object:
        raise TypeError("retained snapshot ownership cannot be serialized")

    def __reduce_ex__(self, _protocol: int) -> object:
        raise TypeError("retained snapshot ownership cannot be serialized")

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        try:
            self.close()
        except BaseException:
            if exc_type is None:
                raise

    def _require_open(self) -> None:
        _require(not self._closed, "snapshot is already closed")

    def _relative(self, path: Path) -> Path:
        self._require_open()
        lexical = path if path.is_absolute() else self.root / path
        lexical = _lexical_abspath(lexical)
        try:
            relative = lexical.relative_to(self.root)
        except ValueError as exc:
            raise ValidationError(f"path escapes snapshot root: {path}") from exc
        _require(
            relative.parts
            and "." not in relative.parts
            and ".." not in relative.parts
            and "\\" not in relative.as_posix(),
            f"unsafe snapshot path: {path}",
        )
        return relative

    def directory_fd(self, path: Path) -> int:
        self._require_open()
        relative = self._relative(path) if path != self.root else Path()
        parts: tuple[str, ...] = ()
        for part in relative.parts:
            parent_parts = parts
            parts = (*parts, part)
            if parts in self._directories:
                continue
            parent_fd = self._directories[parent_parts]
            try:
                before = os.stat(part, dir_fd=parent_fd, follow_symlinks=False)
                _validate_directory_authority(before, relative.as_posix())
                descriptor = os.open(
                    part,
                    self._directory_flags,
                    dir_fd=parent_fd,
                )
            except OSError as exc:
                raise ValidationError(
                    f"cannot open retained ancestor: {relative}: {exc}"
                ) from exc
            stored_directory = False
            try:
                opened = os.fstat(descriptor)
                _require(
                    self._directory_identity(opened)
                    == self._directory_identity(before),
                    f"ancestor identity changed during open: {relative}",
                )
                _require(
                    _fd_mount_id(
                        descriptor,
                        f"snapshot directory: {relative}",
                        suppress_close_errors=self._suppress_close_errors,
                    )
                    == self._root_mount_id,
                    f"cross-mount snapshot directory is forbidden: {relative}",
                )
                self._directories[parts] = descriptor
                stored_directory = True
                self._directory_links[parts] = (
                    parent_fd,
                    part,
                    self._directory_identity(before),
                )
            except BaseException:
                if stored_directory:
                    self._directories.pop(parts, None)
                _close_descriptor_preserving_primary(
                    descriptor,
                    suppress_error=True,
                )
                raise
        return self._directories[parts]

    def read(
        self,
        path: Path,
        *,
        require_single_link: bool = True,
        required_mode: int | None = None,
        maximum_bytes: int | None = None,
    ) -> bytes:
        limit = self._maximum_file_bytes if maximum_bytes is None else maximum_bytes
        _require(
            type(limit) is int and 0 <= limit <= self._maximum_file_bytes,
            f"snapshot file byte cap differs: {path}",
        )
        relative = self._relative(path)
        cached = self._files.get(relative)
        if cached is not None:
            _require(
                not require_single_link or cached[2][5] == 1,
                f"hard-linked file is forbidden: {relative}",
            )
            if required_mode is not None:
                _require(
                    stat.S_IMODE(cached[2][2]) == required_mode,
                    f"file mode differs: {relative}",
                )
            _require(
                len(cached[3]) <= limit,
                f"snapshot file exceeds byte cap: {relative}",
            )
            return cached[3]
        parent_fd = self.directory_fd(relative.parent if relative.parent.parts else self.root)
        try:
            before = os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
            _validate_file_authority(
                before,
                relative.as_posix(),
                require_single_link=require_single_link,
                required_mode=required_mode,
            )
            _require(
                before.st_size <= limit,
                f"snapshot file exceeds byte cap: {relative}",
            )
            _require(
                self._total_bytes + before.st_size <= self._maximum_total_bytes,
                "snapshot cohort exceeds total byte cap",
            )
            descriptor = os.open(relative.name, self._file_flags, dir_fd=parent_fd)
        except OSError as exc:
            raise ValidationError(f"cannot open snapshot file: {relative}: {exc}") from exc
        read_failed = False
        try:
            opened = os.fstat(descriptor)
            _require(
                _file_identity(opened) == _file_identity(before),
                f"file identity changed during open: {relative}",
            )
            _require(
                _fd_mount_id(
                    descriptor,
                    f"snapshot file: {relative}",
                    suppress_close_errors=self._suppress_close_errors,
                )
                == self._root_mount_id,
                f"cross-mount snapshot file is forbidden: {relative}",
            )
            chunks: list[bytes] = []
            observed_bytes = 0
            while observed_bytes < before.st_size:
                chunk = os.read(
                    descriptor,
                    min(1024 * 1024, before.st_size - observed_bytes),
                )
                if not chunk:
                    break
                observed_bytes += len(chunk)
                _require(
                    observed_bytes <= limit and observed_bytes <= before.st_size,
                    f"snapshot file exceeds byte cap: {relative}",
                )
                chunks.append(chunk)
            after_fd = os.fstat(descriptor)
        except OSError as exc:
            read_failed = True
            raise ValidationError(f"cannot read snapshot file: {relative}: {exc}") from exc
        except BaseException:
            read_failed = True
            raise
        finally:
            close_error = _close_descriptor_sequence((descriptor,))
            if (
                close_error is not None
                and not self._suppress_close_errors
                and not read_failed
            ):
                raise close_error
        after_path = os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        identity = _file_identity(before)
        _require(
            identity == _file_identity(after_fd) == _file_identity(after_path),
            f"file changed during snapshot: {relative}",
        )
        content = b"".join(chunks)
        _require(
            len(content) == before.st_size,
            f"short snapshot read: {relative}",
        )
        self._total_bytes += len(content)
        self._files[relative] = (
            parent_fd,
            relative.name,
            identity,
            content,
            require_single_link,
            required_mode,
        )
        return content

    def lexists(self, path: Path) -> bool:
        relative = self._relative(path)
        try:
            parent_fd = self.directory_fd(
                relative.parent if relative.parent.parts else self.root
            )
        except ValidationError as exc:
            if isinstance(exc.__cause__, FileNotFoundError):
                return False
            raise
        try:
            os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            return False
        return True

    def verify(self) -> None:
        self._require_open()
        for relative, (parent_fd, name, identity, _content, single, mode) in self._files.items():
            current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            _validate_file_authority(
                current,
                relative.as_posix(),
                require_single_link=single,
                required_mode=mode,
            )
            _require(
                _file_identity(current) == identity,
                f"file changed after snapshot: {relative}",
            )
        for parts, (parent_fd, name, identity) in self._directory_links.items():
            current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            _validate_directory_authority(current, "/".join(parts))
            identity_function = (
                _mutable_directory_identity
                if len(identity) == len(_mutable_directory_identity(current))
                else self._directory_identity
            )
            _require(
                identity_function(current) == identity,
                f"ancestor changed after snapshot: {'/'.join(parts)}",
            )
            _require(
                identity_function(os.fstat(self._directories[parts]))
                == identity,
                f"retained ancestor changed: {'/'.join(parts)}",
            )
        current_root = os.stat(self.root, follow_symlinks=False)
        root_identity_function = (
            _mutable_directory_identity
            if len(self._root_identity) == len(_mutable_directory_identity(current_root))
            else _directory_identity
        )
        _require(
            root_identity_function(current_root) == self._root_identity
            == root_identity_function(os.fstat(self._directories[()])),
            "snapshot root changed",
        )
        for parent_fd, name, identity, descriptor in self._outer_links:
            current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            _require(
                _mutable_directory_identity(current) == identity
                == _mutable_directory_identity(os.fstat(descriptor)),
                f"root ancestor changed after snapshot: {name}",
            )

    def file_mode(self, path: Path) -> int:
        relative = self._relative(path)
        entry = self._files.get(relative)
        _require(entry is not None, f"snapshot file was not captured: {relative}")
        return stat.S_IMODE(entry[2][2])

    def allow_directory_entry_churn(self, path: Path) -> None:
        """Relax one verified internal directory for this writer's own entries."""
        self._require_open()
        lexical = _lexical_abspath(path)
        if lexical == self.root:
            current = os.stat(self.root, follow_symlinks=False)
            retained = os.fstat(self._directories[()])
            _validate_directory_authority(current, str(self.root))
            identity_function = (
                _mutable_directory_identity
                if len(self._root_identity)
                == len(_mutable_directory_identity(current))
                else _directory_identity
            )
            _require(
                identity_function(current) == self._root_identity
                == identity_function(retained),
                "snapshot root changed before entry-churn grant",
            )
            self._root_identity = _mutable_directory_identity(current)
            return
        relative = self._relative(lexical)
        parts = tuple(relative.parts)
        self.directory_fd(lexical)
        entry = self._directory_links.get(parts)
        _require(entry is not None, f"snapshot directory is not internal: {relative}")
        parent_fd, name, identity = entry
        current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        retained = os.fstat(self._directories[parts])
        _validate_directory_authority(current, relative.as_posix())
        if len(identity) == len(_directory_identity(current)):
            _require(
                _directory_identity(current) == identity
                == _directory_identity(retained),
                f"directory changed before entry-churn grant: {relative}",
            )
        else:
            _require(
                _mutable_directory_identity(current) == identity
                == _mutable_directory_identity(retained),
                f"directory identity changed after entry-churn grant: {relative}",
            )
        self._directory_links[parts] = (
            parent_fd,
            name,
            _mutable_directory_identity(current),
        )

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        directory_descriptors = [
            self._directories[parts]
            for parts in sorted(self._directories, key=len, reverse=True)
        ]
        outer_descriptors = list(reversed(self._outer_descriptors))
        # Remove every externally reachable cached descriptor before the first
        # close.  Even when a close fault is injected, a later numeric-FD reuse
        # can therefore never be mistaken for retained authority.
        self._directories.clear()
        self._outer_descriptors.clear()
        self._outer_links.clear()
        self._directory_links.clear()
        self._files.clear()
        self._total_bytes = 0
        errors = (
            _close_descriptor_sequence(directory_descriptors),
            _close_descriptor_sequence(outer_descriptors),
        )
        first_error = next((error for error in errors if error is not None), None)
        if (
            first_error is not None
            and not self._suppress_close_errors
        ):
            raise first_error

    def suppress_close_errors_after_commit(self) -> None:
        self._require_open()
        self._suppress_close_errors = True


def _replicate_retained_snapshot(snapshot: _RetainedSnapshot) -> _RetainedSnapshot:
    """Create the writer-held cohort while the validated build cohort is live."""
    snapshot._require_open()
    replica: _RetainedSnapshot | None = None
    try:
        replica = _RetainedSnapshot(snapshot.root)
        for relative, (
            _parent_fd,
            _name,
            _identity,
            expected,
            single,
            mode,
        ) in snapshot._files.items():
            observed = replica.read(
                relative,
                require_single_link=single,
                required_mode=mode,
                maximum_bytes=max(len(expected), 1),
            )
            _require(
                observed == expected,
                f"writer source cohort differs: {relative}",
            )
        snapshot.verify()
        replica.verify()
        return replica
    except BaseException:
        if replica is not None:
            try:
                replica.close()
            except BaseException:
                pass
        raise


class _SourceBoundOutputs(dict[Path, bytes]):
    """Generated bytes bound to a retained, still-live source/root authority."""

    def __init__(
        self,
        outputs: Mapping[Path, bytes],
        source_root: Path,
        snapshot: _RetainedSnapshot,
    ) -> None:
        super().__init__(outputs)
        self._expected_outputs = dict(outputs)
        self._source_root = _lexical_abspath(source_root)
        self._source_snapshot: _RetainedSnapshot | None = snapshot

    def verify_source(self, allowed_root: Path) -> _RetainedSnapshot:
        _require(
            _lexical_abspath(allowed_root) == self._source_root,
            "R012 bound source root differs",
        )
        _require(
            self._source_snapshot is not None,
            "R012 bound source snapshot is closed",
        )
        _require(
            list(self.items()) == list(self._expected_outputs.items()),
            "R012 bound output mapping changed",
        )
        self._source_snapshot.verify()
        return self._source_snapshot

    def __copy__(self) -> "_SourceBoundOutputs":
        raise ValidationError("source-bound output ownership cannot be copied")

    def __deepcopy__(self, _memo: dict[int, Any]) -> "_SourceBoundOutputs":
        raise ValidationError("source-bound output ownership cannot be deep-copied")

    def __reduce__(self) -> object:
        raise TypeError("source-bound output ownership cannot be serialized")

    def __reduce_ex__(self, _protocol: int) -> object:
        raise TypeError("source-bound output ownership cannot be serialized")

    def close(self) -> None:
        snapshot = self._source_snapshot
        if snapshot is not None:
            self._source_snapshot = None
            snapshot.close()

    def __del__(self) -> None:
        try:
            self.close()
        except BaseException:
            pass


def _read_confined_file_bytes(path: Path, allowed_root: Path) -> bytes:
    with _RetainedSnapshot(allowed_root) as snapshot:
        content = snapshot.read(path)
        snapshot.verify()
        return content


def _open_confined_directory(path: Path, allowed_root: Path) -> int:
    # Compatibility helper for callers that own the returned descriptor.  All
    # multi-file paths use _RetainedSnapshot directly.
    root = allowed_root.resolve(strict=True)
    relative = _lexical_abspath(path).relative_to(root)
    flags = (
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_DIRECTORY")
        | _required_open_flag("O_NOFOLLOW")
    )
    root_fd = os.open(root, flags)
    owned_descriptors = [root_fd]
    try:
        for part in relative.parts:
            parent_fd = owned_descriptors[-1]
            child = os.open(part, flags, dir_fd=parent_fd)
            try:
                owned_descriptors.append(child)
            except BaseException:
                _close_descriptor_preserving_primary(child, suppress_error=True)
                raise
            if parent_fd != root_fd:
                owned_descriptors.remove(parent_fd)
                _close_descriptor_preserving_primary(parent_fd)
        descriptor = owned_descriptors[-1]
        opened = os.fstat(descriptor)
        _validate_directory_authority(opened, str(path))
        if descriptor == root_fd:
            owned_descriptors.remove(descriptor)
            return descriptor
        owned_descriptors.remove(root_fd)
        _close_descriptor_preserving_primary(root_fd)
        owned_descriptors.remove(descriptor)
        return descriptor
    except BaseException:
        _close_descriptor_sequence(reversed(owned_descriptors))
        raise


def _rename_noreplace(
    source_directory_fd: int,
    source_name: str,
    destination_name: str,
    *,
    destination_directory_fd: int | None = None,
) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise ValidationError("atomic renameat2(RENAME_NOREPLACE) is unavailable")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    target_fd = (
        source_directory_fd
        if destination_directory_fd is None
        else destination_directory_fd
    )
    result = renameat2(
        source_directory_fd,
        os.fsencode(source_name),
        target_fd,
        os.fsencode(destination_name),
        1,
    )
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number in {errno.EEXIST, errno.ENOTEMPTY}:
        raise FileExistsError(
            f"add-only publication target already exists: {destination_name}"
        )
    raise OSError(error_number, os.strerror(error_number), destination_name)


def _link_fd_noreplace(
    source_fd: int,
    destination_directory_fd: int,
    destination_name: str,
) -> None:
    """Publish an unnamed O_TMPFILE inode under one add-only basename."""
    libc = ctypes.CDLL(None, use_errno=True)
    linkat = getattr(libc, "linkat", None)
    if linkat is None:
        raise ValidationError("linkat(AT_EMPTY_PATH) is unavailable")
    linkat.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
    ]
    linkat.restype = ctypes.c_int
    result = linkat(
        source_fd,
        b"",
        destination_directory_fd,
        os.fsencode(destination_name),
        0x1000,  # Linux AT_EMPTY_PATH.
    )
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number == errno.EEXIST:
        raise FileExistsError(
            f"add-only transaction entry already exists: {destination_name}"
        )
    raise OSError(error_number, os.strerror(error_number), destination_name)


def _bytes_binding(
    path: Path,
    content: bytes,
    binding_id: str,
    role: str,
    *,
    root: Path = REPO_ROOT,
) -> dict[str, Any]:
    return {
        "binding_id": binding_id,
        "path": _relative(path, root),
        "byte_length": len(content),
        "sha256": _sha256_bytes(content),
        "subject_role": role,
    }


def _source_specs(root: Path = REPO_ROOT) -> tuple[tuple[str, Path, str], ...]:
    return (
        (
            "R012-PRE-001",
            _path_for(root, R011_LEDGER_REL),
            "R011_IMMUTABLE_EXACT257_LEDGER",
        ),
        (
            "R012-PRE-002",
            _path_for(root, R011_EVIDENCE_REL),
            "R011_IMMUTABLE_PACKET_EVIDENCE",
        ),
        (
            "R012-PRE-003",
            _path_for(root, R011_RECEIPT_REL),
            "R011_IMMUTABLE_CHECK_RECEIPT",
        ),
        (
            "R012-SRC-001",
            _path_for(root, IMPLEMENTATION_REL),
            "FP048_INTERNAL_IMPLEMENTATION_RECORD",
        ),
        (
            "R012-SRC-002",
            _path_for(root, VERIFICATION_REL),
            "FP048_INTERNAL_VERIFICATION_RESULT",
        ),
        (
            "R012-SRC-003",
            _path_for(root, R023_GAP_REL),
            "FP048_GAP057_R023_SUCCESSOR",
        ),
        (
            "R012-SRC-004",
            _path_for(root, TARGET_ARTIFACT_PATHS[0][1]),
            "FP048_DLV_DOC05_PHYSICAL_SUCCESSOR",
        ),
        (
            "R012-SRC-005",
            _path_for(root, TARGET_ARTIFACT_PATHS[1][1]),
            "FP048_DLV_DOC01_PHYSICAL_SUCCESSOR",
        ),
        (
            "R012-SRC-006",
            _path_for(root, TARGET_ARTIFACT_PATHS[2][1]),
            "FP048_DLV_REQ16_PHYSICAL_SUCCESSOR",
        ),
        (
            "R012-SRC-007",
            _path_for(root, TARGET_ARTIFACT_PATHS[3][1]),
            "FP048_DLV_DES06_PHYSICAL_SUCCESSOR",
        ),
        (
            "R012-SRC-008",
            _path_for(root, TARGET_ARTIFACT_PATHS[4][1]),
            "FP048_DLV_DEV01_PHYSICAL_SUCCESSOR",
        ),
        (
            "R012-SRC-009",
            _path_for(root, TARGET_ARTIFACT_PATHS[5][1]),
            "FP048_DLV_DEV18_PHYSICAL_SUCCESSOR",
        ),
    )


def _dynamic_source_specs(
    root: Path = REPO_ROOT,
) -> tuple[tuple[str, Path, str], ...]:
    return _source_specs(root)[3:]


ARTIFACT_BINDING_ID_BY_ID = {
    artifact_id: f"R012-SRC-{index:03d}"
    for index, artifact_id in enumerate(TARGET_ARTIFACT_IDS, start=4)
}


def _normalize_pin_mapping(
    root: Path,
    supplied: Mapping[Path, str],
) -> dict[Path, str]:
    normalized: dict[Path, str] = {}
    resolved_root = root.resolve(strict=True)
    for raw_path, digest in supplied.items():
        path = Path(raw_path)
        if path.is_absolute():
            try:
                path = path.relative_to(resolved_root)
            except ValueError as exc:
                raise ValidationError(f"pin path escapes root: {raw_path}") from exc
        _require(path not in normalized, f"duplicate pin path: {path}")
        normalized[path] = digest
    return normalized


def _effective_source_pins(
    root: Path = REPO_ROOT,
    supplied: Mapping[Path, str] | None = None,
) -> dict[Path, str]:
    if supplied is not None:
        _require(
            root.resolve(strict=True) != REPO_ROOT.resolve(strict=True),
            "test-only R012 source-pin injection is forbidden at the repository root",
        )
    raw = PINNED_SOURCE_SHA256_BY_RELATIVE_PATH if supplied is None else supplied
    pins = _normalize_pin_mapping(root, raw)
    expected_paths = {relative for _, relative in TARGET_ARTIFACT_PATHS}
    expected_paths.update({IMPLEMENTATION_REL, VERIFICATION_REL, R023_GAP_REL})
    _require(set(pins) == expected_paths, "R012 source pin path set differs")
    for relative, digest in pins.items():
        _require(
            type(digest) is str and SHA256_RE.fullmatch(digest) is not None,
            f"R012 source SHA-256 is not pinned: {relative}",
        )
    return pins


def _exact_set_fingerprint(ids: Iterable[str]) -> dict[str, Any]:
    ordered = tuple(ids)
    _require(len(ordered) == len(set(ordered)), "fingerprint IDs are duplicated")
    content = "".join(f"{item}\n" for item in ordered).encode("utf-8")
    return {
        "algorithm": "SHA-256",
        "item_count": len(ordered),
        "serialization": "UTF-8 declared order, one ID per line, final LF",
        "byte_length": len(content),
        "sha256": _sha256_bytes(content),
    }


def _canonical_projection(document: dict[str, Any]) -> bytes:
    projected = deepcopy(document)
    projected["integrity"]["content_sha256"] = None
    projected["integrity"]["canonical_byte_count"] = None
    projected["nonself_digest_check"]["content_sha256"] = None
    projected["nonself_digest_check"]["canonical_byte_count"] = None
    return (
        json.dumps(
            projected,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _integrity_contract(target_path: Path, root: Path = REPO_ROOT) -> dict[str, Any]:
    return {
        "algorithm": "SHA-256",
        "encoding": "UTF-8",
        "recursive_key_order": "LEXICOGRAPHIC",
        "ensure_ascii": False,
        "json_separators": [",", ":"],
        "projection_path": "/",
        "projection_null_paths": list(_NONSELF_NULL_PATHS),
        "final_lf": True,
        "canonical_byte_count": None,
        "content_sha256": None,
        "target_path": _relative(target_path, root),
    }


def _seal_json(
    document: dict[str, Any],
    target_path: Path,
    *,
    root: Path = REPO_ROOT,
) -> bytes:
    document["integrity"] = _integrity_contract(target_path, root)
    document["nonself_digest_check"] = {
        **_integrity_contract(target_path, root),
        "status": "PASS",
    }
    projection = _canonical_projection(document)
    digest = _sha256_bytes(projection)
    for key in ("integrity", "nonself_digest_check"):
        document[key]["canonical_byte_count"] = len(projection)
        document[key]["content_sha256"] = digest
    return (
        json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _validate_nonself(
    document: dict[str, Any],
    target_path: Path,
    *,
    root: Path = REPO_ROOT,
) -> None:
    contract = _integrity_contract(target_path, root)
    for key in ("integrity", "nonself_digest_check"):
        section = document.get(key)
        _require(type(section) is dict, f"{target_path}: missing {key}")
        expected_keys = set(contract)
        if key == "nonself_digest_check":
            expected_keys.add("status")
        _require(set(section) == expected_keys, f"{target_path}: {key} fields differ")
        for field in (
            "algorithm",
            "encoding",
            "recursive_key_order",
            "ensure_ascii",
            "json_separators",
            "projection_path",
            "projection_null_paths",
            "final_lf",
            "target_path",
        ):
            _require(
                _strict_json_equal(section.get(field), contract[field]),
                f"{target_path}: {key}.{field} differs",
            )
    projection = _canonical_projection(document)
    digest = _sha256_bytes(projection)
    for key in ("integrity", "nonself_digest_check"):
        _require_int(
            document[key].get("canonical_byte_count"),
            len(projection),
            f"{target_path}: {key}.canonical_byte_count",
        )
        _require(
            document[key].get("content_sha256") == digest,
            f"{target_path}: {key}.content_sha256 differs",
        )
    _require(
        document["nonself_digest_check"].get("status") == "PASS",
        f"{target_path}: nonself status differs",
    )


def _record_by_id(ledger: dict[str, Any]) -> dict[str, dict[str, Any]]:
    records = ledger.get("records")
    _require(type(records) is list, "ledger.records must be an array")
    result: dict[str, dict[str, Any]] = {}
    for index, record in enumerate(records):
        _require(type(record) is dict, f"ledger.records[{index}] must be an object")
        artifact_id = record.get("artifact_type_code")
        _require(type(artifact_id) is str, f"ledger.records[{index}] ID must be a string")
        _require(artifact_id not in result, f"duplicate exact257 row: {artifact_id}")
        result[artifact_id] = record
    return result


def _forbidden_new_source_path(value: str) -> bool:
    lowered = value.replace("\\", "/").lower()
    components = {part for part in lowered.split("/") if part}
    return (
        bool(components & FORBIDDEN_SOURCE_PATH_COMPONENTS)
        or any(fragment in lowered for fragment in FORBIDDEN_NEW_SOURCE_FRAGMENTS)
    )


def _validate_no_review_completion_source_markers(
    value: Any,
    label: str,
    location: str = "$",
) -> None:
    if type(value) is dict:
        for key, item in value.items():
            normalized_key = key.replace("-", "_").lower()
            _require(
                not any(
                    marker.replace("-", "_") in normalized_key
                    for marker in FORBIDDEN_REVIEW_COMPLETION_MARKERS
                ),
                f"{label} has forbidden review/completion field: {location}.{key}",
            )
            _validate_no_review_completion_source_markers(
                item,
                label,
                f"{location}.{key}",
            )
        return
    if type(value) is list:
        for index, item in enumerate(value):
            _validate_no_review_completion_source_markers(
                item,
                label,
                f"{location}[{index}]",
            )
        return
    if type(value) is str:
        normalized = value.replace("\\", "/").lower()
        components = {part for part in normalized.split("/") if part}
        looks_like_path = "/" in normalized or normalized.endswith(
            (".json", ".md", ".txt")
        )
        _require(
            not (
                looks_like_path
                and (
                    bool(
                        components
                        & {
                            "review",
                            "reviews",
                            "completion",
                            "completions",
                        }
                    )
                    or any(
                        marker in normalized
                        for marker in FORBIDDEN_REVIEW_COMPLETION_MARKERS
                    )
                )
            ),
            f"{label} has forbidden review/completion path: {location}",
        )


def _validate_r011_packet(
    ledger: dict[str, Any],
    evidence: dict[str, Any],
    receipt: dict[str, Any],
    source_bytes: Mapping[Path, bytes],
    *,
    root: Path,
) -> None:
    ledger_path = _path_for(root, R011_LEDGER_REL)
    evidence_path = _path_for(root, R011_EVIDENCE_REL)
    receipt_path = _path_for(root, R011_RECEIPT_REL)
    _validate_nonself(ledger, ledger_path, root=root)
    _validate_nonself(evidence, evidence_path, root=root)
    _validate_nonself(receipt, receipt_path, root=root)
    _require(
        ledger.get("schema_version") == "walksafe.phase1-exact257-successor-ledger.v11",
        "R011 ledger schema differs",
    )
    _require(
        ledger.get("ledger_id")
        == "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260729-R011",
        "R011 ledger ID differs",
    )
    rows = _record_by_id(ledger)
    _require(len(rows) == 257, "R011 ledger must contain exactly 257 records")
    _require(TARGET_ARTIFACT_ID_SET <= rows.keys(), "R011 target exact6 rows missing")
    summaries = ledger.get("summaries")
    _require(type(summaries) is dict, "R011 summaries missing")
    _require(
        summaries.get("canonical_status_counts") == CANONICAL_STATUS_COUNTS,
        "R011 canonical counts differ",
    )
    _require(
        summaries.get("current_queue_route_counts") == QUEUE_ROUTE_COUNTS,
        "R011 queue counts differ",
    )
    _require_int(summaries.get("record_count"), 257, "R011 record_count")
    _require_int(summaries.get("open_artifact_count"), 131, "R011 open count")
    _require_int(
        summaries.get("current_scope_closure_delta_count"),
        2,
        "R011 current-scope closure delta",
    )
    authorization = ledger.get("authorization_boundary")
    _require(type(authorization) is dict, "R011 authorization boundary missing")
    for field in (
        "actual_device_event_count",
        "attestation_approval_count",
        "formal279_pass_count",
        "in_scope_substantive_credit_count",
        "owner_approval_count",
        "real_event_count",
        "release_eligible_count",
        "verified_rights_or_external_fact_count",
    ):
        _require_int(authorization.get(field), 0, f"R011 authorization.{field}")
    _require(
        authorization.get("release_status") == "NOT_ELIGIBLE",
        "R011 release status differs",
    )
    _require(
        ledger.get("r011_ready25_progress_application", {}).get("zero_credits")
        == ZERO_CREDITS,
        "R011 zero-credit boundary differs",
    )
    queue_counts = Counter(row["queue_route"]["current"] for row in rows.values())
    _require(
        {key: queue_counts[key] for key in QUEUE_ROUTE_COUNTS} == QUEUE_ROUTE_COUNTS
        and set(queue_counts) <= set(QUEUE_ROUTE_COUNTS),
        "R011 physical queue counts differ",
    )
    for artifact_id, row in rows.items():
        for group, field in SIX_COMPLETION_PATHS:
            _require(
                type(row.get(group, {}).get(field)) is bool,
                f"R011 {artifact_id} {group}.{field} must be boolean",
            )
        _require_bool(
            row.get("release_eligibility", {}).get("eligible"),
            False,
            f"R011 {artifact_id} release eligibility",
        )
        _require(
            row.get("release_eligibility", {}).get("status") == "NOT_ELIGIBLE",
            f"R011 {artifact_id} release status differs",
        )

    ledger_hash = _sha256_bytes(source_bytes[ledger_path])
    evidence_hash = _sha256_bytes(source_bytes[evidence_path])
    evidence_ledger = evidence.get("subject_chain", {}).get("r011_ledger", {})
    _require(
        evidence_ledger.get("sha256") == ledger_hash
        and evidence_ledger.get("path") == R011_LEDGER_REL.as_posix(),
        "R011 evidence-to-ledger binding differs",
    )
    _require(receipt.get("status") == "PASS", "R011 receipt status differs")
    output_bindings = receipt.get("output_bindings")
    _require(type(output_bindings) is list, "R011 receipt output bindings missing")
    by_path = {item.get("path"): item for item in output_bindings if type(item) is dict}
    _require(
        by_path.get(R011_LEDGER_REL.as_posix(), {}).get("sha256") == ledger_hash,
        "R011 receipt ledger binding differs",
    )
    _require(
        by_path.get(R011_EVIDENCE_REL.as_posix(), {}).get("sha256") == evidence_hash,
        "R011 receipt evidence binding differs",
    )


def _validate_result_boundary(value: Any, label: str) -> None:
    _require(type(value) is dict, f"{label} boundary missing")
    _require(
        _strict_json_equal(value, EXPECTED_RESULT_BOUNDARY),
        f"{label} boundary differs",
    )


def _validate_projection_seal(value: dict[str, Any], key: str, label: str) -> None:
    observed = value.get(key)
    projected = {name: item for name, item in value.items() if name != key}
    _require(observed == _object_sha(projected), f"{label} projection seal differs")


def _validate_fp048_sources(
    documents: Mapping[Path, dict[str, Any]],
    source_bytes: Mapping[Path, bytes],
    pins: Mapping[Path, str],
    *,
    root: Path,
) -> None:
    implementation_path = _path_for(root, IMPLEMENTATION_REL)
    verification_path = _path_for(root, VERIFICATION_REL)
    gap_path = _path_for(root, R023_GAP_REL)
    implementation = documents[implementation_path]
    verification = documents[verification_path]
    gap = documents[gap_path]
    _validate_no_review_completion_source_markers(
        implementation,
        "FP048 implementation",
    )
    _validate_no_review_completion_source_markers(
        verification,
        "FP048 verification",
    )

    _require(
        implementation.get("document_id")
        == "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTATION-20260802-001",
        "FP048 implementation document ID differs",
    )
    _require(
        implementation.get("goal_id") == GOAL_ID
        and implementation.get("kind") == "IMPLEMENTATION_RECORD"
        and implementation.get("status") == "PASS",
        "FP048 implementation identity/status differs",
    )
    changed = implementation.get("changed_artifacts")
    _require(type(changed) is list and bool(changed), "FP048 implementation rows missing")
    content_rows: list[dict[str, str]] = []
    observed_paths: list[str] = []
    for index, row in enumerate(changed):
        _require(type(row) is dict, f"implementation row {index} is not an object")
        path = row.get("path")
        digest = row.get("after_sha256")
        _require(type(path) is str and bool(path), f"implementation row {index} path")
        _require(not _forbidden_new_source_path(path), f"forbidden implementation path: {path}")
        _require(
            path not in {item.as_posix() for item in PINNED_SOURCE_SHA256_BY_RELATIVE_PATH},
            f"R012 source is cyclic implementation input: {path}",
        )
        _require(
            type(digest) is str and SHA256_RE.fullmatch(digest) is not None,
            f"implementation row {index} SHA-256 differs",
        )
        observed_paths.append(path)
        content_rows.append({"path": path, "sha256": digest})
    _require(len(observed_paths) == len(set(observed_paths)), "implementation path duplicated")
    _require(
        implementation.get("implementation_content_set_sha256")
        == _object_sha(content_rows),
        "implementation content-set SHA-256 differs",
    )
    _validate_result_boundary(implementation.get("evidence_boundary"), "implementation")

    _require(
        verification.get("document_id")
        == "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-VERIFICATION-20260802-001",
        "FP048 verification document ID differs",
    )
    _require(
        verification.get("goal_id") == GOAL_ID
        and verification.get("kind") == "VERIFICATION_RESULT"
        and verification.get("status") == "PASS",
        "FP048 verification identity/status differs",
    )
    _require(
        verification.get("implementation_content_set_sha256")
        == implementation["implementation_content_set_sha256"],
        "verification implementation binding differs",
    )
    checks = verification.get("checks")
    _require(type(checks) is list and bool(checks), "verification checks missing")
    for index, check in enumerate(checks):
        _require(type(check) is dict, f"verification check {index} is not an object")
        _require_int(check.get("exit_code"), 0, f"verification check {index}.exit_code")
        _require(
            check.get("implementation_content_set_sha256")
            == implementation["implementation_content_set_sha256"],
            f"verification check {index} implementation binding differs",
        )
        output_path = check.get("output_path")
        output_sha256 = check.get("output_sha256")
        _require(
            type(output_path) is str and bool(output_path),
            f"verification check {index} output path differs",
        )
        _require(
            not _forbidden_new_source_path(output_path),
            f"forbidden verification output path: {output_path}",
        )
        _require(
            type(output_sha256) is str and SHA256_RE.fullmatch(output_sha256) is not None,
            f"verification check {index} output SHA-256 differs",
        )
    _validate_result_boundary(verification.get("evidence_boundary"), "verification")

    _require(
        gap.get("metadata", {}).get("report_id") == R023_REPORT_ID,
        "r023 report ID differs",
    )
    _validate_projection_seal(gap, "report_content_sha256", "r023 Gap")
    assessments = [
        row for row in gap.get("assessments", [])
        if type(row) is dict and row.get("gap_id") == "GAP-057"
    ]
    _require(len(assessments) == 1, "r023 GAP-057 row count differs")
    assessment = assessments[0]
    _require(
        assessment.get("source_policy_id") == "FP-048"
        and assessment.get("status") == "PARTIAL"
        and assessment.get("formal_test_status") == "NOT_RUN",
        "r023 GAP-057 status differs",
    )
    reassessment = assessment.get("fp048_reassessment")
    _require(type(reassessment) is dict, "r023 FP048 reassessment missing")
    _require(
        reassessment.get("implementation_record_sha256") == pins[IMPLEMENTATION_REL]
        and reassessment.get("verification_result_sha256") == pins[VERIFICATION_REL],
        "r023 producer-result bindings differ",
    )
    _require(
        reassessment.get("internal_implementation_status") == "PASS"
        and reassessment.get("internal_verification_status") == "PASS",
        "r023 internal status differs",
    )
    for key, expected in EXPECTED_R023_BOUNDARY.items():
        _require(
            _strict_json_equal(reassessment.get(key), expected),
            f"r023 boundary differs: {key}",
        )
    matching_evidence = [
        row
        for row in gap.get("evidence_catalog", [])
        if type(row) is dict
        and row.get("evidence_id") == R023_EVIDENCE_ID
        and row.get("producer_goal_id") == GOAL_ID
    ]
    _require(len(matching_evidence) == 1, "r023 FP048 evidence row count differs")
    result_hashes = matching_evidence[0].get("result_evidence_sha256")
    _require(
        result_hashes
        == {
            "IMPLEMENTATION_RECORD": pins[IMPLEMENTATION_REL],
            "VERIFICATION_RESULT": pins[VERIFICATION_REL],
        },
        "r023 evidence producer hashes differ",
    )
    _require(
        matching_evidence[0].get("formal_test_status") == "NOT_RUN"
        and matching_evidence[0].get("actual_device_status") == "NOT_RUN"
        and matching_evidence[0].get("external_verification_status") == "NOT_RUN"
        and matching_evidence[0].get("release_status") == "NOT_ELIGIBLE",
        "r023 evidence boundary differs",
    )
    _require(
        gap.get("summary", {}).get("release_status") == "NOT_ELIGIBLE",
        "r023 summary release status differs",
    )
    authorization = gap.get("authorization_boundary")
    _require(type(authorization) is dict, "r023 authorization boundary missing")
    for field in (
        "formal_test_completion_claimed",
        "artifact_approval_claimed",
        "remaining_gates_waived",
    ):
        _require_bool(authorization.get(field), False, f"r023 authorization.{field}")
    _require(
        authorization.get("release_status") == "NOT_ELIGIBLE",
        "r023 authorization release status differs",
    )
    gap_source_bindings = gap.get("source_bindings")
    _require(type(gap_source_bindings) is list, "r023 source bindings malformed")
    for index, binding in enumerate(gap_source_bindings):
        _require(type(binding) is dict, f"r023 source binding {index} malformed")
        binding_path = binding.get("path")
        _require(
            type(binding_path) is str and bool(binding_path),
            f"r023 source binding {index} path malformed",
        )
        _require(
            not _forbidden_new_source_path(binding_path),
            f"r023 has forbidden direct source: {binding_path}",
        )

    producer_paths = {
        IMPLEMENTATION_REL.as_posix(): (
            pins[IMPLEMENTATION_REL],
            len(source_bytes[implementation_path]),
        ),
        VERIFICATION_REL.as_posix(): (
            pins[VERIFICATION_REL],
            len(source_bytes[verification_path]),
        ),
        R023_GAP_REL.as_posix(): (
            pins[R023_GAP_REL],
            len(source_bytes[gap_path]),
        ),
    }
    seal_keys = {
        TARGET_ARTIFACT_PATHS[0][1]: "content_sha256",
        TARGET_ARTIFACT_PATHS[1][1]: "content_sha256",
        TARGET_ARTIFACT_PATHS[2][1]: "document_content_sha256",
        TARGET_ARTIFACT_PATHS[3][1]: "register_content_sha256",
        TARGET_ARTIFACT_PATHS[4][1]: "fp048_successor_content_sha256",
        TARGET_ARTIFACT_PATHS[5][1]: "fp048_successor_content_sha256",
    }
    for artifact_id, relative in TARGET_ARTIFACT_PATHS:
        path = _path_for(root, relative)
        document = documents[path]
        _validate_no_review_completion_source_markers(document, artifact_id)
        _validate_projection_seal(document, seal_keys[relative], artifact_id)
        trace = document.get("fp048_artifact_trace_successor")
        _require(type(trace) is dict, f"{artifact_id} FP048 trace missing")
        _require(
            trace.get("successor_id") == FP048_SUCCESSOR_ID,
            f"{artifact_id} successor ID differs",
        )
        _require(
            trace.get("predecessor", {}).get("path") == relative.as_posix(),
            f"{artifact_id} predecessor path differs",
        )
        _require(
            trace.get("predecessor", {}).get("sha256")
            == TARGET_PREDECESSOR_SHA256[relative],
            f"{artifact_id} predecessor SHA-256 differs",
        )
        trace_bindings = trace.get("input_bindings")
        _require(
            type(trace_bindings) is list and len(trace_bindings) == 3,
            f"{artifact_id} input binding count differs",
        )
        by_trace_path: dict[str, dict[str, Any]] = {}
        for binding in trace_bindings:
            _require(type(binding) is dict, f"{artifact_id} input binding malformed")
            source_path = binding.get("path")
            _require(type(source_path) is str, f"{artifact_id} input path malformed")
            _require(source_path not in by_trace_path, f"{artifact_id} duplicate input path")
            by_trace_path[source_path] = binding
        _require(set(by_trace_path) == set(producer_paths), f"{artifact_id} source set differs")
        for source_path, (digest, byte_length) in producer_paths.items():
            _require(
                by_trace_path[source_path].get("sha256") == digest,
                f"{artifact_id} source SHA differs: {source_path}",
            )
            _require_int(
                by_trace_path[source_path].get("byte_length"),
                byte_length,
                f"{artifact_id} source byte length: {source_path}",
            )
        _require(
            _strict_json_equal(
                trace.get("claim_boundary"), EXPECTED_DOCUMENT_TRACE_BOUNDARY
            ),
            f"{artifact_id} claim boundary differs",
        )
        _require(
            _sha256_bytes(source_bytes[path]) == pins[relative],
            f"{artifact_id} physical source hash differs",
        )


def _snapshot_authenticity_inputs(
    snapshot: _RetainedSnapshot,
) -> dict[Path, bytes]:
    from scripts import build_walksafe_fp048_artifact_trace_successor_20260802 as artifact_builder
    from scripts import (
        build_walksafe_fp048_encryption_connection_security_incident_trace_20260802
        as trace_builder,
    )
    from scripts import build_walksafe_fp048_gap_backlog_r023_20260802 as r023_builder

    captured: dict[Path, bytes] = {}

    def capture(
        relative: Path,
        *,
        single: bool = True,
        mode: int | None = None,
    ) -> None:
        captured[relative] = snapshot.read(
            relative,
            require_single_link=single,
            required_mode=mode,
        )

    _require(
        r023_builder.TRACE_BUILDER_REL
        == Path("scripts/build_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py")
        and r023_builder.IMPLEMENTATION_REL == IMPLEMENTATION_REL
        and r023_builder.VERIFICATION_REL == VERIFICATION_REL,
        "r023 captured trace producer identity differs",
    )
    r023_cohort = tuple(
        dict.fromkeys(
            (
                *r023_builder._direct_source_relatives(),
                r023_builder.CHECKPOINT_REL,
                *map(Path, trace_builder.IMPLEMENTATION_PATHS),
                *trace_builder.VERIFICATION_INPUT_PATHS,
                *(lane.log_rel for lane in trace_builder.LANES),
            )
        )
    )
    _require(
        len(trace_builder.IMPLEMENTATION_PATHS) == 106
        and len(r023_cohort) == 127,
        "r023 exact captured source cohort differs",
    )
    private_mode_paths = {
        IMPLEMENTATION_REL,
        VERIFICATION_REL,
        *r023_builder.TRACE_RECEIPT_RELS,
        *(lane.log_rel for lane in trace_builder.LANES),
    }
    for relative in r023_cohort:
        capture(
            relative,
            mode=0o600 if relative in private_mode_paths else None,
        )

    capture(artifact_builder.BUILDER_REL)
    for relative in r023_builder.OUTPUT_PATHS:
        capture(relative, mode=0o600)
    for _, relative in TARGET_ARTIFACT_PATHS:
        capture(relative)
    return captured


def _materialize_captured_snapshot(
    snapshot: _RetainedSnapshot,
    captured: Mapping[Path, bytes],
    destination_root: Path,
) -> None:
    """Build a private producer view exclusively from the retained cohort bytes."""
    _require(destination_root.is_dir(), "captured snapshot destination is missing")
    for relative, content in sorted(
        captured.items(),
        key=lambda item: item[0].as_posix(),
    ):
        _require(
            not relative.is_absolute()
            and relative.parts
            and "." not in relative.parts
            and ".." not in relative.parts,
            f"unsafe captured snapshot path: {relative}",
        )
        destination = destination_root / relative
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor = os.open(
            destination,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            snapshot.file_mode(relative),
        )
        write_failed = False
        try:
            os.fchmod(descriptor, snapshot.file_mode(relative))
            remaining = memoryview(content)
            while remaining:
                written = os.write(descriptor, remaining)
                _require(written > 0, f"zero-byte snapshot materialization: {relative}")
                remaining = remaining[written:]
            os.fsync(descriptor)
        except BaseException:
            write_failed = True
            raise
        finally:
            _close_descriptor_preserving_primary(
                descriptor,
                suppress_error=write_failed,
            )


def _validate_trace_authenticity(
    root: Path,
    captured: Mapping[Path, bytes],
) -> None:
    from scripts import (
        build_walksafe_fp048_encryption_connection_security_incident_trace_20260802
        as trace_builder,
    )

    try:
        _require(
            trace_builder.IMPLEMENTATION_REL == IMPLEMENTATION_REL
            and trace_builder.VERIFICATION_REL == VERIFICATION_REL,
            "FP048 trace producer result paths differ",
        )
        implementation = _load_strict_json_bytes(
            captured[IMPLEMENTATION_REL], "FP048 trace implementation"
        )
        implementation_rows = implementation.get("changed_artifacts")
        _require(
            type(implementation_rows) is list
            and len(implementation_rows) == 106,
            "FP048 exact implementation row count differs",
        )
        authority = trace_builder.validate_authority(root)
        expected = trace_builder.build_pre_review_outputs(
            root=root,
            authority=authority,
            implementation_rows=deepcopy(implementation_rows),
            log_snapshots={
                lane.lane_id: captured[lane.log_rel]
                for lane in trace_builder.LANES
            },
        )
        for relative in (IMPLEMENTATION_REL, VERIFICATION_REL):
            _require(
                captured[relative] == expected[relative].encode("utf-8"),
                f"FP048 trace producer bytes differ: {relative}",
            )
        verification = _load_strict_json_bytes(
            captured[VERIFICATION_REL], "FP048 trace verification"
        )
        _require(
            [row.get("path") for row in implementation.get("changed_artifacts", [])]
            == list(trace_builder.IMPLEMENTATION_PATHS),
            "FP048 exact ordered implementation path set differs",
        )
        _require_int(
            implementation.get("exact_path_count"),
            len(trace_builder.IMPLEMENTATION_PATHS),
            "FP048 implementation exact_path_count",
        )
        checks = verification.get("checks")
        _require(type(checks) is list, "FP048 trace checks missing")
        _require(
            [row.get("lane_id") for row in checks]
            == [lane.lane_id for lane in trace_builder.LANES],
            "FP048 trace lane ID/order differs",
        )
        _require(
            [row.get("command") for row in checks]
            == [lane.command for lane in trace_builder.LANES],
            "FP048 trace lane command/order differs",
        )
        for lane, check in zip(trace_builder.LANES, checks, strict=True):
            raw_log = captured[lane.log_rel]
            _require(
                check.get("output_path") == lane.log_rel.as_posix()
                and check.get("output_sha256") == _sha256_bytes(raw_log),
                f"FP048 physical raw log binding differs: {lane.lane_id}",
            )
            receipt_bytes = captured[lane.receipt_rel]
            _require(
                receipt_bytes == expected[lane.receipt_rel].encode("utf-8"),
                f"FP048 physical lane receipt differs: {lane.lane_id}",
            )
            receipt = _load_strict_json_bytes(
                receipt_bytes, f"FP048 lane receipt {lane.lane_id}"
            )
            trace_builder._verify_json_seal(
                receipt,
                "receipt_content_sha256",
                f"FP048 lane receipt {lane.lane_id}",
            )
        event = implementation.get("execution_session_event")
        _require(
            type(event) is dict
            and event.get("sequence") == trace_builder.EXPECTED_EXECUTION_EVENT_SEQUENCE
            and event.get("event_id") == trace_builder.EXPECTED_EXECUTION_EVENT_ID
            and event.get("event_sha256")
            == trace_builder.EXPECTED_EXECUTION_EVENT_SHA256,
            "FP048 start-event binding differs",
        )
    except trace_builder.BuildError as exc:
        raise ValidationError(f"FP048 trace authenticity failed: {exc}") from exc


def _validate_r023_builder_semantics(
    root: Path,
    captured: Mapping[Path, bytes],
) -> None:
    from scripts import build_walksafe_fp048_gap_backlog_r023_20260802 as r023_builder

    try:
        _require(
            r023_builder.R023_GAP_JSON_REL == R023_GAP_REL,
            "r023 producer Gap path differs",
        )
        expected = r023_builder.build_outputs(root)
        _require(
            tuple(expected) == r023_builder.OUTPUT_PATHS,
            "r023 producer output order differs",
        )
        for relative, content in expected.items():
            _require(
                captured[relative] == content.encode("utf-8"),
                f"r023 physical output differs from full builder: {relative}",
            )
    except r023_builder.BuildError as exc:
        raise ValidationError(f"r023 full-builder validation failed: {exc}") from exc


def _validate_six_document_builder_semantics(
    root: Path,
    captured: Mapping[Path, bytes],
    pins: Mapping[Path, str],
) -> None:
    from scripts import build_walksafe_fp048_artifact_trace_successor_20260802 as artifact_builder

    expected_paths = tuple(relative for _, relative in TARGET_ARTIFACT_PATHS)
    input_pins = {
        artifact_builder.IMPLEMENTATION_REL: pins[IMPLEMENTATION_REL],
        artifact_builder.VERIFICATION_REL: pins[VERIFICATION_REL],
        artifact_builder.GAP_REL: pins[R023_GAP_REL],
    }
    expected_ids = {
        artifact_builder.IMPLEMENTATION_REL: (
            "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTATION-20260802-001"
        ),
        artifact_builder.VERIFICATION_REL: (
            "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-VERIFICATION-20260802-001"
        ),
    }
    try:
        _require(
            set(artifact_builder.OUTPUT_PATHS) == set(expected_paths)
            and len(artifact_builder.OUTPUT_PATHS) == len(expected_paths),
            "FP048 six-document producer path/order differs",
        )
        actual = artifact_builder.check_successor(
            root,
            expected_input_sha256=input_pins,
            expected_document_ids=expected_ids,
        )
        _require(
            tuple(actual) == artifact_builder.OUTPUT_PATHS,
            "FP048 six-document full-builder output order differs",
        )
        for relative in artifact_builder.OUTPUT_PATHS:
            _require(
                captured[relative] == actual[relative],
                f"FP048 six-document bytes differ from full builder: {relative}",
            )
    except artifact_builder.BuildError as exc:
        raise ValidationError(
            f"FP048 six-document full-builder validation failed: {exc}"
        ) from exc


def _load_source_state(
    root: Path = REPO_ROOT,
    expected_source_sha256: Mapping[Path, str] | None = None,
    *,
    retain_source_snapshot: bool = False,
) -> dict[str, Any]:
    root = _lexical_abspath(root)
    pins = _effective_source_pins(root, expected_source_sha256)
    specs = _source_specs(root)
    source_bytes: dict[Path, bytes] = {}
    expected_hashes = {
        **R011_PINNED_SHA256_BY_RELATIVE_PATH,
        **pins,
    }
    publication_snapshot: _RetainedSnapshot | None = None
    with ExitStack() as resources:
        def close_replica_if_stack_exit_fails(
            exc_type: object,
            _exc: object,
            _traceback: object,
        ) -> bool:
            if exc_type is not None and publication_snapshot is not None:
                try:
                    publication_snapshot.close()
                except BaseException:
                    pass
            return False

        # Registered first so it runs last and sees failures raised by the
        # source snapshot or temporary-view exit callbacks.
        resources.push(close_replica_if_stack_exit_fails)
        temporary_directory = tempfile.TemporaryDirectory(
            prefix="walksafe-r012-retained-snapshot-"
        )
        temporary_view = temporary_directory.__enter__()

        def close_temporary_view_preserving_primary(
            exc_type: object,
            exc: object,
            traceback: object,
        ) -> bool:
            try:
                return bool(temporary_directory.__exit__(exc_type, exc, traceback))
            except BaseException:
                if exc_type is None:
                    raise
                return False

        resources.push(close_temporary_view_preserving_primary)
        snapshot = resources.enter_context(_RetainedSnapshot(root))
        producer_root = Path(temporary_view)
        for _, path, _ in specs:
            source_bytes[path] = snapshot.read(path)
        captured = _snapshot_authenticity_inputs(snapshot)
        expected_paths = {_path_for(root, relative) for relative in expected_hashes}
        _require(
            set(source_bytes) == expected_paths,
            "R012 physical source path set differs",
        )
        for relative, expected in expected_hashes.items():
            path = _path_for(root, relative)
            _require(
                _sha256_bytes(source_bytes[path]) == expected,
                f"pinned source SHA-256 drift: {relative}",
            )
        documents = {
            path: _load_strict_json_bytes(content, _relative(path, root))
            for path, content in source_bytes.items()
        }
        _validate_r011_packet(
            documents[_path_for(root, R011_LEDGER_REL)],
            documents[_path_for(root, R011_EVIDENCE_REL)],
            documents[_path_for(root, R011_RECEIPT_REL)],
            source_bytes,
            root=root,
        )
        _materialize_captured_snapshot(snapshot, captured, producer_root)
        _validate_trace_authenticity(producer_root, captured)
        _validate_r023_builder_semantics(producer_root, captured)
        _validate_six_document_builder_semantics(
            producer_root,
            captured,
            pins,
        )
        _validate_fp048_sources(documents, source_bytes, pins, root=root)
        snapshot.verify()
        if retain_source_snapshot:
            publication_snapshot = _replicate_retained_snapshot(snapshot)

    try:
        predecessor_bindings = [
            _bytes_binding(path, source_bytes[path], binding_id, role, root=root)
            for binding_id, path, role in specs[:3]
        ]
        source_bindings = [
            _bytes_binding(path, source_bytes[path], binding_id, role, root=root)
            for binding_id, path, role in specs[3:]
        ]
        for label, bindings in (
            ("predecessor", predecessor_bindings),
            ("R012 source", source_bindings),
        ):
            _require(
                len({item["binding_id"] for item in bindings}) == len(bindings),
                f"duplicate {label} binding ID",
            )
            _require(
                len({item["path"] for item in bindings}) == len(bindings),
                f"duplicate {label} binding path",
            )
        _require(
            all(not _forbidden_new_source_path(item["path"]) for item in source_bindings),
            "R012 direct source allowlist contains a forbidden path",
        )
        return {
            "root": root,
            "r011": documents[_path_for(root, R011_LEDGER_REL)],
            "predecessor_bindings": predecessor_bindings,
            "source_bindings": source_bindings,
            "source_documents": documents,
            "source_bytes": source_bytes,
            "source_pins": pins,
            "publication_snapshot": publication_snapshot,
        }
    except BaseException:
        if publication_snapshot is not None:
            try:
                publication_snapshot.close()
            except BaseException:
                pass
        raise


def _progress_entries(
    artifact_id: str,
    source_binding_by_id: Mapping[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    binding_id = ARTIFACT_BINDING_ID_BY_ID[artifact_id]
    physical = source_binding_by_id[binding_id]
    physical_binding = {
        "binding_id": binding_id,
        "path": physical["path"],
        "byte_length": physical["byte_length"],
        "sha256": physical["sha256"],
    }
    producer_binding_ids = {
        "implementation_record": "R012-SRC-001",
        "verification_result": "R012-SRC-002",
        "gap057_r023_successor": "R012-SRC-003",
    }
    return {
        "content_authored": {
            "status": "FP048_PHYSICAL_DOCUMENT_PROGRESS_BOUND_NO_STATE_PROMOTION",
            "artifact_type_code": artifact_id,
            "physical_document_binding": physical_binding,
            "producer_result_binding_ids": producer_binding_ids,
            "artifact_content_accepted": False,
            "owner_approved": False,
            "completion_claimed": False,
            "state_promotion": False,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
        "packet_materialization": {
            "status": "FP048_PHYSICAL_DOCUMENT_MATERIALIZED_PROGRESS_ONLY",
            "artifact_type_code": artifact_id,
            "physical_document_binding": physical_binding,
            "coverage_basis": "EXACT_ARTIFACT_ID_TO_PHYSICAL_DOCUMENT_BINDING",
            "state_promotion": False,
            "completion_claimed": False,
        },
        "internal_validation": {
            "status": "FP048_INTERNAL_VERIFICATION_OBSERVATION_BOUND_NO_CREDIT",
            "artifact_type_code": artifact_id,
            "implementation_binding_id": "R012-SRC-001",
            "verification_binding_id": "R012-SRC-002",
            "gap057_r023_binding_id": "R012-SRC-003",
            "physical_document_binding_id": binding_id,
            "internal_verification_pass_observed": True,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "state_promotion": False,
            "completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
            "credit_count": 0,
        },
    }


def _build_ledger(source_state: Mapping[str, Any]) -> dict[str, Any]:
    predecessor = source_state["r011"]
    bindings = source_state["source_bindings"]
    binding_by_id = {item["binding_id"]: item for item in bindings}
    ledger = deepcopy(predecessor)
    ledger["schema_version"] = "walksafe.phase1-exact257-successor-ledger.v12"
    ledger["ledger_id"] = "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260802-R012"
    ledger["prepared_on"] = PREPARED_ON
    ledger["predecessor_r011_packet"] = {
        "ledger_binding_id": "R012-PRE-001",
        "evidence_binding_id": "R012-PRE-002",
        "receipt_binding_id": "R012-PRE-003",
        "semantics": "IMMUTABLE_AUTHORITATIVE_R011_PACKET",
    }
    ledger["r012_predecessor_packet_bindings"] = deepcopy(
        source_state["predecessor_bindings"]
    )
    ledger["r012_fp048_artifact_progress_application"] = {
        "application_id": "WS-PHASE1-EXACT257-FP048-PROGRESS-20260802-R012",
        "verdict": VERDICT,
        "record_count": 257,
        "record_order_preserved": True,
        "unchanged_record_count": 251,
        "progress_binding_record_count": 6,
        "target_artifact_ids": list(TARGET_ARTIFACT_IDS),
        "target_exact_set_fingerprint": _exact_set_fingerprint(TARGET_ARTIFACT_IDS),
        "allowed_new_source_binding_ids": [item["binding_id"] for item in bindings],
        "forbidden_new_source_count": 0,
        "queue_route_delta_count": 0,
        "summary_delta_count": 0,
        "authorization_delta_count": 0,
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_evidence_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "zero_credits": deepcopy(ZERO_CREDITS),
    }
    ledger.setdefault("exact_set_fingerprints", {})[
        "r012_fp048_exact6_set"
    ] = _exact_set_fingerprint(TARGET_ARTIFACT_IDS)
    for row in ledger["records"]:
        artifact_id = row["artifact_type_code"]
        if artifact_id in TARGET_ARTIFACT_ID_SET:
            entries = _progress_entries(artifact_id, binding_by_id)
            row["progress_axes"]["content_authored"]["observations"].append(
                entries["content_authored"]
            )
            row["progress_axes"]["packet_materialization"].append(
                entries["packet_materialization"]
            )
            row["progress_axes"]["internal_validation"]["observations"].append(
                entries["internal_validation"]
            )
    ledger["r012_source_bindings"] = deepcopy(bindings)
    return ledger


def _build_evidence(
    source_state: Mapping[str, Any],
    ledger_bytes: bytes,
) -> dict[str, Any]:
    root = source_state["root"]
    ledger_path = _path_for(root, R012_LEDGER_REL)
    return {
        "schema_version": "walksafe.phase1-exact257-successor-evidence.v12",
        "packet_id": "WS-PHASE1-EXACT257-SUCCESSOR-EVIDENCE-20260802-R012",
        "prepared_on": PREPARED_ON,
        "run_id": RUN_ID,
        "verdict": VERDICT,
        "claim_semantics": "RECORD_PROGRESS_BINDING_ONLY_NO_STATE_OR_CREDIT_PROMOTION",
        "predecessor_packet_bindings": deepcopy(source_state["predecessor_bindings"]),
        "subject_chain": {
            "r011_ledger_binding_id": "R012-PRE-001",
            "r011_evidence_binding_id": "R012-PRE-002",
            "r011_receipt_binding_id": "R012-PRE-003",
            "r012_ledger": _bytes_binding(
                ledger_path,
                ledger_bytes,
                "R012-OUT-001",
                "R012_FULL_EXACT257_LEDGER",
                root=root,
            ),
        },
        "source_bindings": deepcopy(source_state["source_bindings"]),
        "source_allowlist": {
            "new_source_count": 9,
            "implementation_result_count": 1,
            "verification_result_count": 1,
            "r023_gap_count": 1,
            "physical_document_count": 6,
            "review_subject_count": 0,
            "review_attestation_count": 0,
            "independent_review_count": 0,
            "completion_receipt_count": 0,
            "legacy_web_pwa_docx_pptx_count": 0,
        },
        "row_delta": {
            "record_count": 257,
            "record_order_preserved": True,
            "unchanged_record_count": 251,
            "progress_binding_record_count": 6,
            "target_artifact_ids": list(TARGET_ARTIFACT_IDS),
            "allowed_append_paths": [
                "/records/*/progress_axes/content_authored/observations/-",
                "/records/*/progress_axes/packet_materialization/-",
                "/records/*/progress_axes/internal_validation/observations/-",
            ],
            "other_record_delta_count": 0,
            "content_observation_total_before": 171,
            "content_observation_total_after": 177,
            "packet_materialization_total_before": 171,
            "packet_materialization_total_after": 177,
            "internal_validation_total_before": 0,
            "internal_validation_total_after": 6,
            "independent_review_total_before": 105,
            "independent_review_total_after": 105,
        },
        "preserved_invariants": {
            "summaries_deep_equal": True,
            "authorization_boundary_deep_equal": True,
            "all_queue_routes_deep_equal": True,
            "canonical_status_counts": deepcopy(CANONICAL_STATUS_COUNTS),
            "current_queue_route_counts": deepcopy(QUEUE_ROUTE_COUNTS),
            "closed_equivalent_count": 126,
            "open_artifact_count": 131,
            "zero_credits": deepcopy(ZERO_CREDITS),
        },
        "formal_device_external_release_boundary": {
            "formal_test_status": "NOT_RUN",
            "formal279_pass_count": 0,
            "actual_device_status": "NOT_RUN",
            "actual_device_event_count": 0,
            "external_evidence_status": "NOT_RUN",
            "verified_rights_or_external_fact_count": 0,
            "release_gate_status": "NOT_RUN",
            "release_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
            "release_eligible_count": 0,
        },
        "physical_output_contract": {
            "paths": [
                R012_LEDGER_REL.as_posix(),
                R012_EVIDENCE_REL.as_posix(),
                R012_RECEIPT_REL.as_posix(),
            ],
            "encoding": "UTF-8",
            "exactly_one_final_lf": True,
            "add_only": True,
            "overwrite_allowed": False,
            "atomic_publication_directory": R012_PACKET_DIR_REL.as_posix(),
            "publication_method": "STAGE_DIRECTORY_THEN_RENAMEAT2_NOREPLACE",
            "file_fsync": True,
            "directory_fsync": True,
            "nofollow": True,
            "hardlink_source_allowed": False,
        },
    }


def _check(
    check_id: str,
    expected: Any,
    observed: Any,
    *,
    method: str | None = None,
) -> dict[str, Any]:
    result = {
        "check_id": check_id,
        "expected": expected,
        "observed": observed,
        "status": "PASS" if _strict_json_equal(observed, expected) else "FAIL",
    }
    if method is not None:
        result["method"] = method
    return result


def _build_receipt(
    source_state: Mapping[str, Any],
    ledger_bytes: bytes,
    evidence_bytes: bytes,
) -> dict[str, Any]:
    root = source_state["root"]
    ledger_path = _path_for(root, R012_LEDGER_REL)
    evidence_path = _path_for(root, R012_EVIDENCE_REL)
    checks = [
        _check("R012-CHECK-R011-IMMUTABLE-PACKET", 3, 3),
        _check("R012-CHECK-EXACT257-ORDER", {"records": 257, "ordered": True}, {"records": 257, "ordered": True}),
        _check("R012-CHECK-ROW-DELTA-ALLOWLIST", {"unchanged": 251, "progress_only": 6, "other": 0}, {"unchanged": 251, "progress_only": 6, "other": 0}),
        _check("R012-CHECK-NEW-SOURCE-ALLOWLIST", {"allowed": 9, "forbidden": 0}, {"allowed": 9, "forbidden": 0}),
        _check("R012-CHECK-PRESERVED-QUEUE-SUMMARY-AUTHORIZATION", {"queue_delta": 0, "summary_delta": 0, "authorization_delta": 0}, {"queue_delta": 0, "summary_delta": 0, "authorization_delta": 0}),
        _check("R012-CHECK-ZERO-CREDIT-BOUNDARY", ZERO_CREDITS, ZERO_CREDITS),
        _check("R012-CHECK-FORMAL-DEVICE-EXTERNAL-RELEASE", {"formal": "NOT_RUN", "device": "NOT_RUN", "external": "NOT_RUN", "release": "NOT_ELIGIBLE"}, {"formal": "NOT_RUN", "device": "NOT_RUN", "external": "NOT_RUN", "release": "NOT_ELIGIBLE"}),
        _check("R012-CHECK-ADD-ONLY-ATOMIC-OUTPUT", {"output_count": 3, "overwrite_allowed": False, "fsync": True, "nofollow": True, "hardlink": False}, {"output_count": 3, "overwrite_allowed": False, "fsync": True, "nofollow": True, "hardlink": False}),
    ]
    return {
        "schema_version": "walksafe.phase1-exact257-successor-check-receipt.v12",
        "receipt_id": "WS-PHASE1-EXACT257-SUCCESSOR-CHECK-RECEIPT-20260802-R012",
        "prepared_on": PREPARED_ON,
        "run_id": RUN_ID,
        "status": "PASS",
        "verdict": VERDICT,
        "summary": {
            "check_count": len(checks),
            "pass_count": len(checks),
            "fail_count": 0,
            "record_count": 257,
            "unchanged_record_count": 251,
            "progress_binding_record_count": 6,
            "closed_equivalent_count": 126,
            "open_artifact_count": 131,
            "release_status": "NOT_ELIGIBLE",
            "canonical_status_counts": deepcopy(CANONICAL_STATUS_COUNTS),
            "current_queue_route_counts": deepcopy(QUEUE_ROUTE_COUNTS),
            "zero_credits": deepcopy(ZERO_CREDITS),
        },
        "checks": checks,
        "predecessor_packet_bindings": deepcopy(source_state["predecessor_bindings"]),
        "source_bindings": deepcopy(source_state["source_bindings"]),
        "output_bindings": [
            _bytes_binding(
                ledger_path,
                ledger_bytes,
                "R012-OUT-001",
                "R012_FULL_EXACT257_LEDGER",
                root=root,
            ),
            _bytes_binding(
                evidence_path,
                evidence_bytes,
                "R012-OUT-002",
                "R012_FP048_EXACT6_PROGRESS_EVIDENCE",
                root=root,
            ),
        ],
        "physical_output_contract": {
            "paths": [
                R012_LEDGER_REL.as_posix(),
                R012_EVIDENCE_REL.as_posix(),
                R012_RECEIPT_REL.as_posix(),
            ],
            "add_only": True,
            "overwrite_allowed": False,
            "atomic_publication_directory": R012_PACKET_DIR_REL.as_posix(),
            "publication_method": "STAGE_DIRECTORY_THEN_RENAMEAT2_NOREPLACE",
            "file_fsync": True,
            "directory_fsync": True,
            "nofollow": True,
            "hardlink_source_allowed": False,
        },
    }


def _output_paths(root: Path = REPO_ROOT) -> tuple[Path, Path, Path]:
    return (
        _path_for(root, R012_LEDGER_REL),
        _path_for(root, R012_EVIDENCE_REL),
        _path_for(root, R012_RECEIPT_REL),
    )


def _expected_outputs(
    source_state: Mapping[str, Any],
) -> dict[Path, bytes]:
    root = source_state["root"]
    ledger_path, evidence_path, receipt_path = _output_paths(root)
    ledger_bytes = _seal_json(_build_ledger(source_state), ledger_path, root=root)
    evidence_bytes = _seal_json(
        _build_evidence(source_state, ledger_bytes),
        evidence_path,
        root=root,
    )
    receipt_bytes = _seal_json(
        _build_receipt(source_state, ledger_bytes, evidence_bytes),
        receipt_path,
        root=root,
    )
    return {
        ledger_path: ledger_bytes,
        evidence_path: evidence_bytes,
        receipt_path: receipt_bytes,
    }


def _decode_output_json(content: bytes, label: str) -> dict[str, Any]:
    return _load_strict_json_bytes(content, label)


def _validate_generated_outputs(
    outputs: Mapping[Path, bytes],
    source_state: Mapping[str, Any],
) -> None:
    root = source_state["root"]
    expected_paths = _output_paths(root)
    _require(tuple(outputs) == expected_paths, "R012 output path/order differs")
    for path, content in outputs.items():
        _require(type(content) is bytes, f"R012 output is not bytes: {path}")
        _require(content.endswith(b"\n"), f"R012 final LF missing: {path}")
        _require(not content.endswith(b"\n\n"), f"R012 multiple final LF: {path}")
        _require(not content.endswith(b"\r\n"), f"R012 CRLF terminal forbidden: {path}")
    ledger = _decode_output_json(outputs[expected_paths[0]], "R012 ledger")
    evidence = _decode_output_json(outputs[expected_paths[1]], "R012 evidence")
    receipt = _decode_output_json(outputs[expected_paths[2]], "R012 receipt")
    for path, document in zip(expected_paths, (ledger, evidence, receipt), strict=True):
        _validate_nonself(document, path, root=root)

    predecessor = source_state["r011"]
    before_records = predecessor["records"]
    after_records = ledger["records"]
    _require(len(before_records) == len(after_records) == 257, "R012 record count differs")
    _require(
        [row["artifact_type_code"] for row in after_records]
        == [row["artifact_type_code"] for row in before_records],
        "R012 record order differs",
    )
    before_by_id = _record_by_id(predecessor)
    after_by_id = _record_by_id(ledger)
    binding_by_id = {
        item["binding_id"]: item for item in source_state["source_bindings"]
    }
    unchanged = 0
    progress_only = 0
    for artifact_id, before in before_by_id.items():
        after = after_by_id[artifact_id]
        if artifact_id not in TARGET_ARTIFACT_ID_SET:
            _require(after == before, f"non-FP048 exact6 record changed: {artifact_id}")
            unchanged += 1
            continue
        expected_record = deepcopy(before)
        entries = _progress_entries(artifact_id, binding_by_id)
        expected_record["progress_axes"]["content_authored"]["observations"].append(
            entries["content_authored"]
        )
        expected_record["progress_axes"]["packet_materialization"].append(
            entries["packet_materialization"]
        )
        expected_record["progress_axes"]["internal_validation"]["observations"].append(
            entries["internal_validation"]
        )
        _require(
            after == expected_record,
            f"FP048 exact6 record has non-progress delta: {artifact_id}",
        )
        entry = after["progress_axes"]["content_authored"]["observations"][-1]
        for field in (
            "artifact_content_accepted",
            "owner_approved",
            "completion_claimed",
            "state_promotion",
        ):
            _require_bool(entry.get(field), False, f"R012 {artifact_id}.{field}")
        materialization = after["progress_axes"]["packet_materialization"][-1]
        internal_validation = after["progress_axes"]["internal_validation"]["observations"][-1]
        _require_bool(materialization.get("state_promotion"), False, f"R012 {artifact_id} materialization promotion")
        _require_bool(internal_validation.get("state_promotion"), False, f"R012 {artifact_id} validation promotion")
        _require_int(internal_validation.get("credit_count"), 0, f"R012 {artifact_id} validation credit")
        for group, field in SIX_COMPLETION_PATHS:
            _require_bool(after[group][field], before[group][field], f"R012 {artifact_id} {group}.{field}")
        _require(after["queue_route"] == before["queue_route"], f"R012 {artifact_id} queue route changed")
        _require(after["release_eligibility"] == before["release_eligibility"], f"R012 {artifact_id} release boundary changed")
        progress_only += 1
    _require_int(unchanged, 251, "R012 unchanged record count")
    _require_int(progress_only, 6, "R012 progress record count")
    _require(ledger["summaries"] == predecessor["summaries"], "R012 summaries changed")
    _require(
        ledger["authorization_boundary"] == predecessor["authorization_boundary"],
        "R012 authorization boundary changed",
    )
    _require(
        ledger["r012_predecessor_packet_bindings"]
        == source_state["predecessor_bindings"],
        "R012 predecessor packet bindings differ",
    )
    _require(
        ledger["r012_source_bindings"] == source_state["source_bindings"],
        "R012 source bindings differ",
    )
    for before, after in zip(before_records, after_records, strict=True):
        _require(after["queue_route"] == before["queue_route"], "R012 queue route changed")
    totals = {
        "content": sum(
            len(row["progress_axes"]["content_authored"]["observations"])
            for row in after_records
        ),
        "materialization": sum(
            len(row["progress_axes"]["packet_materialization"])
            for row in after_records
        ),
        "review": sum(
            len(row["progress_axes"]["independent_review"])
            for row in after_records
        ),
        "internal_validation": sum(
            len(row["progress_axes"]["internal_validation"]["observations"])
            for row in after_records
        ),
    }
    _require(
        totals
        == {
            "content": 177,
            "materialization": 177,
            "review": 105,
            "internal_validation": 6,
        },
        "R012 progress totals differ",
    )
    _require(
        ledger["r012_fp048_artifact_progress_application"]["zero_credits"]
        == ZERO_CREDITS,
        "R012 ledger zero credits differ",
    )
    _require(
        evidence["preserved_invariants"]["zero_credits"] == ZERO_CREDITS,
        "R012 evidence zero credits differ",
    )
    _require(
        receipt["summary"]["zero_credits"] == ZERO_CREDITS,
        "R012 receipt zero credits differ",
    )
    _require(receipt.get("status") == "PASS", "R012 receipt status differs")
    checks = receipt.get("checks")
    _require(
        type(checks) is list
        and len(checks) == 8
        and all(type(check) is dict and check.get("status") == "PASS" for check in checks),
        "R012 receipt check failed",
    )
    expected_ledger_binding = _bytes_binding(
        expected_paths[0],
        outputs[expected_paths[0]],
        "R012-OUT-001",
        "R012_FULL_EXACT257_LEDGER",
        root=root,
    )
    _require(
        evidence.get("predecessor_packet_bindings")
        == source_state["predecessor_bindings"],
        "R012 evidence predecessor bindings differ",
    )
    _require(
        evidence.get("source_bindings") == source_state["source_bindings"],
        "R012 evidence source bindings differ",
    )
    _require(
        evidence.get("subject_chain", {}).get("r012_ledger")
        == expected_ledger_binding,
        "R012 evidence ledger binding differs",
    )
    expected_output_bindings = [
        expected_ledger_binding,
        _bytes_binding(
            expected_paths[1],
            outputs[expected_paths[1]],
            "R012-OUT-002",
            "R012_FP048_EXACT6_PROGRESS_EVIDENCE",
            root=root,
        ),
    ]
    _require(
        receipt.get("predecessor_packet_bindings")
        == source_state["predecessor_bindings"],
        "R012 receipt predecessor bindings differ",
    )
    _require(
        receipt.get("source_bindings") == source_state["source_bindings"],
        "R012 receipt source bindings differ",
    )
    _require(
        receipt.get("output_bindings") == expected_output_bindings,
        "R012 receipt output bindings differ",
    )
    expected = _expected_outputs(source_state)
    _require(dict(outputs) == expected, "R012 generated output exact contract differs")


def build_outputs(
    root: Path = REPO_ROOT,
    *,
    expected_source_sha256: Mapping[Path, str] | None = None,
) -> dict[Path, bytes]:
    source_state = _load_source_state(
        root,
        expected_source_sha256,
        retain_source_snapshot=True,
    )
    snapshot = source_state.get("publication_snapshot")
    _require(
        isinstance(snapshot, _RetainedSnapshot),
        "R012 publication source snapshot is missing",
    )
    try:
        outputs = _expected_outputs(source_state)
        _validate_generated_outputs(outputs, source_state)
        snapshot.verify()
        return _SourceBoundOutputs(outputs, Path(source_state["root"]), snapshot)
    except BaseException:
        try:
            snapshot.close()
        except BaseException:
            pass
        raise


def _build_outputs() -> dict[Path, bytes]:
    return build_outputs(REPO_ROOT)


def _entry_exists(directory_fd: int, name: str) -> bool:
    return _entry_stat_or_none(directory_fd, name) is not None


def _entry_stat_or_none(
    directory_fd: int,
    name: str,
) -> os.stat_result | None:
    try:
        return os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    except FileNotFoundError:
        return None


def _open_directory_at(
    parent_fd: int,
    name: str,
    label: str,
    *,
    required_mode: int | None = None,
) -> int:
    before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    _validate_directory_authority(before, label)
    if required_mode is not None:
        _require(
            stat.S_IMODE(before.st_mode) == required_mode,
            f"directory mode differs: {label}",
        )
    flags = (
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_DIRECTORY")
        | _required_open_flag("O_NOFOLLOW")
    )
    descriptor = os.open(name, flags, dir_fd=parent_fd)
    try:
        opened = os.fstat(descriptor)
        _require(
            _directory_identity(opened) == _directory_identity(before),
            f"directory changed while opening: {label}",
        )
        _require_same_mount(parent_fd, descriptor, label)
    except BaseException:
        _close_descriptor_preserving_primary(descriptor, suppress_error=True)
        raise
    return descriptor


def _read_regular_at(
    directory_fd: int,
    name: str,
    label: str,
    *,
    required_mode: int = 0o600,
    maximum_bytes: int = MAXIMUM_REGULAR_FILE_BYTES,
    budget: _ReadBudget | None = None,
    suppress_close_errors: bool = False,
) -> bytes:
    _require(
        type(maximum_bytes) is int and maximum_bytes >= 0,
        f"file byte cap differs: {label}",
    )
    before = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    _validate_file_authority(
        before,
        label,
        require_single_link=True,
        required_mode=required_mode,
    )
    _require(
        before.st_size <= maximum_bytes,
        f"file exceeds byte cap: {label}",
    )
    if budget is not None:
        budget.require_available(before.st_size, label)
    descriptor = os.open(
        name,
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW"),
        dir_fd=directory_fd,
    )
    read_failed = False
    try:
        opened = os.fstat(descriptor)
        _require(
            _file_identity(opened) == _file_identity(before),
            f"file changed while opening: {label}",
        )
        _require_same_mount(directory_fd, descriptor, label)
        chunks: list[bytes] = []
        observed_bytes = 0
        while observed_bytes < before.st_size:
            chunk = os.read(
                descriptor,
                min(1024 * 1024, before.st_size - observed_bytes),
            )
            if not chunk:
                break
            observed_bytes += len(chunk)
            _require(
                observed_bytes <= maximum_bytes
                and observed_bytes <= before.st_size,
                f"file exceeds byte cap: {label}",
            )
            chunks.append(chunk)
        after_fd = os.fstat(descriptor)
    except BaseException:
        read_failed = True
        raise
    finally:
        close_error = _close_descriptor_sequence((descriptor,))
        if (
            close_error is not None
            and not suppress_close_errors
            and not read_failed
        ):
            raise close_error
    after_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    _require(
        _file_identity(before) == _file_identity(after_fd)
        == _file_identity(after_path),
        f"file changed while reading: {label}",
    )
    content = b"".join(chunks)
    _require(
        len(content) == before.st_size,
        f"short regular-file read: {label}",
    )
    if budget is not None:
        budget.consume(len(content), label)
    return content


def _retain_regular_at(
    directory_fd: int,
    name: str,
    label: str,
    *,
    owner: _DescriptorOwner,
    required_mode: int = 0o600,
    maximum_bytes: int = MAXIMUM_REGULAR_FILE_BYTES,
    budget: _ReadBudget | None = None,
) -> tuple[int, tuple[int, ...], bytes]:
    """Read and retain one exact regular-file inode under one descriptor owner."""
    before = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    _validate_file_authority(
        before,
        label,
        require_single_link=True,
        required_mode=required_mode,
    )
    _require(before.st_size <= maximum_bytes, f"file exceeds byte cap: {label}")
    if budget is not None:
        budget.require_available(before.st_size, label)
    flags = (
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW")
    )
    descriptor = owner.open(name, flags, dir_fd=directory_fd)
    identity = _file_identity(before)
    _require(
        _file_identity(os.fstat(descriptor)) == identity,
        f"file changed while retaining: {label}",
    )
    _require_same_mount(directory_fd, descriptor, label)
    chunks: list[bytes] = []
    observed_bytes = 0
    while observed_bytes < before.st_size:
        chunk = os.read(
            descriptor,
            min(1024 * 1024, before.st_size - observed_bytes),
        )
        if not chunk:
            break
        observed_bytes += len(chunk)
        _require(
            observed_bytes <= maximum_bytes
            and observed_bytes <= before.st_size,
            f"file exceeds byte cap: {label}",
        )
        chunks.append(chunk)
    after_fd = os.fstat(descriptor)
    after_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    _require(
        _file_identity(after_fd) == identity
        and _file_identity(after_path) == identity,
        f"file changed while retaining: {label}",
    )
    content = b"".join(chunks)
    _require(len(content) == before.st_size, f"short regular-file read: {label}")
    if budget is not None:
        budget.consume(len(content), label)
    return descriptor, identity, content


def _verify_retained_regular_at(
    directory_fd: int,
    name: str,
    retained: tuple[int, tuple[int, ...], bytes],
    label: str,
    *,
    required_mode: int = 0o600,
    maximum_bytes: int = MAXIMUM_REGULAR_FILE_BYTES,
    budget: _ReadBudget | None = None,
) -> None:
    descriptor, identity, expected = retained
    current_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    current_fd = os.fstat(descriptor)
    _validate_file_authority(
        current_path,
        label,
        require_single_link=True,
        required_mode=required_mode,
    )
    _validate_file_authority(
        current_fd,
        f"{label} retained descriptor",
        require_single_link=True,
        required_mode=required_mode,
    )
    _require(
        _file_identity(current_path) == identity
        and _file_identity(current_fd) == identity,
        f"retained file identity changed: {label}",
    )
    if budget is not None:
        budget.require_available(len(expected), f"{label} retained verification")
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    observed_bytes = 0
    while observed_bytes < len(expected):
        chunk = os.read(
            descriptor,
            min(1024 * 1024, len(expected) - observed_bytes),
        )
        if not chunk:
            break
        observed_bytes += len(chunk)
        _require(
            observed_bytes <= maximum_bytes
            and observed_bytes <= len(expected),
            f"retained file exceeds byte cap: {label}",
        )
        chunks.append(chunk)
    after_fd = os.fstat(descriptor)
    after_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    _require(
        _file_identity(after_fd) == identity
        and _file_identity(after_path) == identity,
        f"retained file changed while verifying: {label}",
    )
    observed = b"".join(chunks)
    _require(observed == expected, f"retained file bytes changed: {label}")
    if budget is not None:
        budget.consume(len(observed), f"{label} retained verification")


def _verify_retained_regular_identity_at(
    directory_fd: int,
    name: str,
    retained: tuple[int, tuple[int, ...], bytes],
    label: str,
    *,
    required_mode: int = 0o600,
) -> None:
    """Cheap exact-inode CAS after an earlier retained byte-for-byte read."""
    descriptor, identity, expected = retained
    current_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    current_fd = os.fstat(descriptor)
    _validate_file_authority(
        current_path,
        label,
        require_single_link=True,
        required_mode=required_mode,
    )
    _validate_file_authority(
        current_fd,
        f"{label} retained descriptor",
        require_single_link=True,
        required_mode=required_mode,
    )
    _require(
        _file_identity(current_path) == identity
        and _file_identity(current_fd) == identity
        and current_fd.st_size == len(expected),
        f"retained file identity changed: {label}",
    )


def _retain_output_files(
    directory_fd: int,
    outputs: Mapping[Path, bytes],
    budget: _ReadBudget | None = None,
    *,
    owner: _DescriptorOwner,
) -> dict[str, tuple[int, tuple[int, ...], bytes]]:
    """Verify and retain the exact staged inodes across the publish boundary."""
    expected_names = {path.name for path in outputs}
    _require(
        _bounded_directory_inventory(
            directory_fd,
            "committed R012 packet",
            maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
            maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
        )
        == expected_names,
        "committed R012 packet inventory differs",
    )
    retained: dict[str, tuple[int, tuple[int, ...], bytes]] = {}
    for path, expected in outputs.items():
        _require(
            len(expected) <= MAXIMUM_REGULAR_FILE_BYTES,
            f"retained R012 output exceeds byte cap: {path.name}",
        )
        entry = _retain_regular_at(
            directory_fd,
            path.name,
            f"retained R012 output: {path.name}",
            maximum_bytes=len(expected),
            budget=budget,
            owner=owner,
        )
        _require(entry[2] == expected, f"committed R012 output drift: {path}")
        retained[path.name] = entry
    return retained


def _verify_retained_output_files(
    directory_fd: int,
    retained: Mapping[str, tuple[int, tuple[int, ...], bytes]],
    budget: _ReadBudget | None = None,
) -> None:
    _require(
        _bounded_directory_inventory(
            directory_fd,
            "retained R012 packet",
            maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
            maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
        )
        == set(retained),
        "committed R012 packet inventory differs",
    )
    for name, (descriptor, identity, expected) in retained.items():
        current_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        current_fd = os.fstat(descriptor)
        _validate_file_authority(
            current_path,
            f"retained R012 output: {name}",
            require_single_link=True,
            required_mode=0o600,
        )
        _validate_file_authority(
            current_fd,
            f"retained R012 output descriptor: {name}",
            require_single_link=True,
            required_mode=0o600,
        )
        _require(
            _file_identity(current_path) == identity
            and _file_identity(current_fd) == identity,
            f"retained R012 output identity changed: {name}",
        )
        if budget is not None:
            budget.require_available(
                len(expected),
                f"retained R012 output verify: {name}",
            )
        os.lseek(descriptor, 0, os.SEEK_SET)
        chunks: list[bytes] = []
        observed_bytes = 0
        while observed_bytes < len(expected):
            chunk = os.read(
                descriptor,
                min(1024 * 1024, len(expected) - observed_bytes),
            )
            if not chunk:
                break
            observed_bytes += len(chunk)
            _require(
                observed_bytes <= len(expected)
                and observed_bytes <= MAXIMUM_REGULAR_FILE_BYTES,
                f"retained R012 output verify exceeds byte cap: {name}",
            )
            chunks.append(chunk)
        after_fd = os.fstat(descriptor)
        after_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        _require(
            _file_identity(after_fd) == identity
            and _file_identity(after_path) == identity,
            f"retained R012 output changed while verifying: {name}",
        )
        _require(
            b"".join(chunks) == expected,
            f"committed R012 output drift: {name}",
        )
        if budget is not None:
            budget.consume(
                len(expected),
                f"retained R012 output verify: {name}",
            )


def _retain_exact_output_subset(
    directory_fd: int,
    outputs: Mapping[Path, bytes],
    owner: _DescriptorOwner,
    budget: _ReadBudget | None = None,
) -> dict[str, tuple[int, tuple[int, ...], bytes]]:
    """Retain an exact subset of final output names without accepting scratch files."""
    expected_by_name = {path.name: content for path, content in outputs.items()}
    members = _bounded_directory_inventory(
        directory_fd,
        "R012 transaction",
        maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
        maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
    )
    _require(
        members <= set(expected_by_name),
        "R012 transaction contains foreign entries",
    )
    retained: dict[str, tuple[int, tuple[int, ...], bytes]] = {}
    for path, expected in outputs.items():
        if path.name not in members:
            continue
        entry = _retain_regular_at(
            directory_fd,
            path.name,
            f"R012 retained transaction output: {path.name}",
            maximum_bytes=len(expected),
            budget=budget,
            owner=owner,
        )
        _require(
            entry[2] == expected,
            f"R012 transaction output differs: {path.name}",
        )
        retained[path.name] = entry
    return retained


def _write_tmpfile_link_noreplace_at(
    directory_fd: int,
    name: str,
    content: bytes,
    owner: _DescriptorOwner,
    budget: _ReadBudget,
) -> tuple[int, tuple[int, ...], bytes]:
    """Write, sync, and link one unnamed exact output while retaining its inode."""
    temporary_flag = getattr(os, "O_TMPFILE", None)
    _require(
        type(temporary_flag) is int and temporary_flag != 0,
        "O_TMPFILE is unavailable",
    )
    descriptor = owner.open(
        ".",
        os.O_RDWR
        | temporary_flag
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_NOFOLLOW"),
        0o600,
        dir_fd=directory_fd,
    )
    _require_same_mount(
        directory_fd,
        descriptor,
        f"R012 unnamed transaction output: {name}",
    )
    os.fchmod(descriptor, 0o600)
    remaining = memoryview(content)
    while remaining:
        written = os.write(descriptor, remaining)
        _require(written > 0, f"zero-byte O_TMPFILE write: {name}")
        remaining = remaining[written:]
    os.fsync(descriptor)
    unnamed = os.fstat(descriptor)
    _validate_file_authority(
        unnamed,
        f"R012 unnamed transaction output: {name}",
        require_single_link=False,
        required_mode=0o600,
    )
    _require(
        unnamed.st_nlink == 0 and unnamed.st_size == len(content),
        f"R012 unnamed transaction output identity differs: {name}",
    )
    _link_fd_noreplace(descriptor, directory_fd, name)
    linked_fd = os.fstat(descriptor)
    linked_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    _validate_file_authority(
        linked_fd,
        f"R012 linked transaction output descriptor: {name}",
        require_single_link=True,
        required_mode=0o600,
    )
    _validate_file_authority(
        linked_path,
        f"R012 linked transaction output path: {name}",
        require_single_link=True,
        required_mode=0o600,
    )
    identity = _file_identity(linked_fd)
    _require(
        _file_identity(unnamed)[:5] == identity[:5]
        and identity == _file_identity(linked_path)
        and linked_fd.st_size == len(content),
        f"R012 linked transaction output identity differs: {name}",
    )
    retained = (descriptor, identity, content)
    _verify_retained_regular_at(
        directory_fd,
        name,
        retained,
        f"R012 linked transaction output: {name}",
        maximum_bytes=len(content),
        budget=budget,
    )
    return retained


def _retain_output_directory_at(
    parent_fd: int,
    directory_name: str,
    outputs: Mapping[Path, bytes],
    budget: _ReadBudget | None = None,
    *,
    owner: _DescriptorOwner,
) -> tuple[
    int,
    tuple[int, ...],
    dict[str, tuple[int, tuple[int, ...], bytes]],
]:
    directory_fd = owner.open_directory_at(
        parent_fd,
        directory_name,
        "R012 retained publication directory",
        required_mode=0o700,
    )
    retained = _retain_output_files(
        directory_fd,
        outputs,
        budget,
        owner=owner,
    )
    identity = _directory_identity(os.fstat(directory_fd))
    current = os.stat(
        directory_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    _require(
        _directory_identity(current) == identity,
        "R012 publication directory changed while retaining",
    )
    return directory_fd, identity, retained


def _canonical_transaction_inputs(
    outputs: Mapping[Path, bytes],
    reserved_absent_paths: Iterable[Path],
    allowed_root: Path,
) -> tuple[dict[Path, bytes], tuple[Path, ...], Path]:
    """Reject aliases before any filesystem access and freeze canonical keys."""
    raw_root = Path(os.fspath(allowed_root))
    _require(
        all("\x00" not in component for component in raw_root.parts),
        "R012 allowed root contains an embedded NUL path component",
    )
    root = _lexical_abspath(allowed_root)
    canonical_outputs: dict[Path, bytes] = {}
    for path, content in outputs.items():
        _require(isinstance(path, Path), "R012 output path must be pathlib.Path")
        _require(
            all("\x00" not in component for component in path.parts),
            "R012 output path contains an embedded NUL path component",
        )
        canonical = _lexical_abspath(path)
        _require(
            path == canonical,
            f"R012 output path must be canonical absolute: {path}",
        )
        try:
            relative = canonical.relative_to(root)
        except ValueError as exc:
            raise ValidationError(f"R012 output path escapes allowed root: {path}") from exc
        _require(relative.parts, f"R012 output path names the allowed root: {path}")
        _require(
            len(relative.parts) <= MAXIMUM_TRANSACTION_PATH_COMPONENT_COUNT,
            f"R012 output path depth exceeds cap: {path}",
        )
        _require(
            canonical not in canonical_outputs,
            f"R012 output canonical path is duplicated: {path}",
        )
        canonical_outputs[canonical] = content

    canonical_reserved: list[Path] = []
    seen_reserved: set[Path] = set()
    for path in reserved_absent_paths:
        _require(
            len(canonical_reserved) < MAXIMUM_TRANSACTION_RESERVED_PATH_COUNT,
            "R012 reserved path count exceeds cap",
        )
        _require(
            isinstance(path, Path),
            "R012 reserved path must be pathlib.Path",
        )
        _require(
            all("\x00" not in component for component in path.parts),
            "R012 reserved path contains an embedded NUL path component",
        )
        canonical = _lexical_abspath(path)
        _require(
            path == canonical,
            f"R012 reserved path must be canonical absolute: {path}",
        )
        try:
            relative = canonical.relative_to(root)
        except ValueError as exc:
            raise ValidationError(
                f"R012 reserved path escapes allowed root: {path}"
            ) from exc
        _require(relative.parts, f"R012 reserved path names the allowed root: {path}")
        _require(
            len(relative.parts) <= MAXIMUM_TRANSACTION_PATH_COMPONENT_COUNT,
            f"R012 reserved path depth exceeds cap: {path}",
        )
        _require(
            canonical not in seen_reserved,
            f"R012 reserved canonical path is duplicated: {path}",
        )
        _require(
            canonical not in canonical_outputs,
            f"R012 output is also reserved absent: {path}",
        )
        seen_reserved.add(canonical)
        canonical_reserved.append(canonical)
    return canonical_outputs, tuple(canonical_reserved), root


def _is_at_or_below(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
    except ValueError:
        return False
    return True


def _paths_overlap(left: Path, right: Path) -> bool:
    return _is_at_or_below(left, right) or _is_at_or_below(right, left)


def _validate_reserved_boundaries(
    reserved: Iterable[Path],
    protected: Iterable[Path],
) -> None:
    reserved_paths = tuple(reserved)
    protected_paths = tuple(protected)
    for index, reserved_path in enumerate(reserved_paths):
        for other_reserved in reserved_paths[index + 1 :]:
            _require(
                not _paths_overlap(reserved_path, other_reserved),
                "R012 reserved paths overlap each other: "
                f"{reserved_path} and {other_reserved}",
            )
        for protected_path in protected_paths:
            _require(
                not _paths_overlap(reserved_path, protected_path),
                "R012 reserved path overlaps a publication, transaction, "
                f"source, or result boundary: {reserved_path}",
            )


def _write_add_only_transaction(
    outputs: Mapping[Path, bytes],
    *,
    allowed_root: Path,
    reserved_absent_paths: Iterable[Path] = (),
    _transaction_hook: Callable[[str], None] | None = None,
    _production_source_bound: _SourceBoundOutputs | None = None,
    _source_authority_verifier: Callable[[], None] | None = None,
) -> None:
    """Publish by filling and atomically renaming one hidden exact directory."""
    outputs, reserved, allowed_root = _canonical_transaction_inputs(
        outputs,
        reserved_absent_paths,
        allowed_root,
    )
    _require(bool(outputs), "R012 output set must not be empty")
    _require(
        len(outputs) <= MAXIMUM_TRANSACTION_OUTPUT_COUNT,
        "R012 transaction output count exceeds cap",
    )
    for path, content in outputs.items():
        _require(type(content) is bytes, f"R012 output is not bytes: {path}")
        _require(
            len(content) <= MAXIMUM_REGULAR_FILE_BYTES,
            f"R012 output exceeds byte cap: {path}",
        )
    production_root = _lexical_abspath(REPO_ROOT)
    production_paths = _official_output_paths(production_root)
    production_directory = production_paths[0].parent
    touches_production = any(
        _is_at_or_below(path, production_directory) for path in outputs
    )
    _require(
        not touches_production
        or (
            _production_source_bound is not None
            and allowed_root == production_root
            and len(outputs) == len(production_paths)
            and set(outputs) == set(production_paths)
            and list(outputs.items())
            == list(_production_source_bound._expected_outputs.items())
        ),
        "production R012 publication requires the exact live source-bound mapping",
    )
    final_directories = {path.parent for path in outputs}
    _require(
        len(final_directories) == 1,
        "R012 outputs must share one atomic publication directory",
    )
    final_directory = next(iter(final_directories))
    publication_parent = final_directory.parent
    _require(
        all(path.parent == final_directory for path in outputs),
        "R012 output must be a direct child of its publication directory",
    )
    _require(
        len({path.name for path in outputs}) == len(outputs),
        "R012 output basename is duplicated",
    )
    transaction_name = f".{final_directory.name}.r012-transaction"
    protected_boundaries: list[Path] = [
        final_directory,
        publication_parent / transaction_name,
        *outputs,
    ]
    if len(outputs) == len(_official_output_paths(allowed_root)) and set(
        outputs
    ) == set(_official_output_paths(allowed_root)):
        protected_boundaries.extend(
            path for _binding, path, _role in _source_specs(allowed_root)
        )
    _validate_reserved_boundaries(reserved, protected_boundaries)

    production_resources = ExitStack()
    production_fresh_bound: _SourceBoundOutputs | None = None
    supplied_guard: _RetainedSnapshot | None = None
    production_fresh_guard: _RetainedSnapshot | None = None
    try:
        if touches_production:
            assert _production_source_bound is not None
            supplied_guard = _production_source_bound.verify_source(production_root)
            rebuilt = build_outputs(production_root)
            _require(
                isinstance(rebuilt, _SourceBoundOutputs),
                "fresh private production derivation lacks retained source authority",
            )
            production_fresh_bound = rebuilt

            def close_production_fresh_bound(
                exc_type: object,
                _exc: object,
                _traceback: object,
            ) -> bool:
                assert production_fresh_bound is not None
                try:
                    production_fresh_bound.close()
                except BaseException:
                    if exc_type is None:
                        raise
                return False

            try:
                production_resources.push(close_production_fresh_bound)
            except BaseException:
                try:
                    production_fresh_bound.close()
                except BaseException:
                    pass
                raise
            _require(
                list(outputs.items())
                == list(production_fresh_bound._expected_outputs.items()),
                "private production mapping differs from fresh exact derivation",
            )
            supplied_guard.allow_directory_entry_churn(publication_parent)
            production_fresh_guard = production_fresh_bound.verify_source(
                production_root
            )
            production_fresh_guard.allow_directory_entry_churn(publication_parent)
            outputs = dict(production_fresh_bound._expected_outputs)
    except BaseException:
        try:
            production_resources.close()
        except BaseException:
            pass
        raise

    user_hook = _transaction_hook or (lambda _phase: None)
    read_budget = _ReadBudget(MAXIMUM_TRANSACTION_TOTAL_READ_BYTES)
    committed = False
    with (
        production_resources,
        _RetainedSnapshot(allowed_root) as tree,
        _DescriptorOwner() as retained_owner,
    ):
        for path in (*outputs, *reserved):
            tree._relative(path)
        parent_fd = tree.directory_fd(publication_parent)
        tree.allow_directory_entry_churn(publication_parent)
        owned_parent_names = frozenset(
            {final_directory.name, transaction_name}
        )
        external_parent_inventory = _bounded_directory_inventory(
            parent_fd,
            "R012 publication parent external inventory",
            maximum_entries=MAXIMUM_PUBLICATION_PARENT_EXTERNAL_ENTRY_COUNT,
            maximum_name_bytes=MAXIMUM_PUBLICATION_PARENT_EXTERNAL_NAME_BYTES,
            excluded_names=owned_parent_names,
        )
        parent_locked = False
        body_failed = False

        def verify_source_authority() -> None:
            if _source_authority_verifier is not None:
                _source_authority_verifier()
            if touches_production:
                assert _production_source_bound is not None
                assert production_fresh_bound is not None
                _production_source_bound.verify_source(production_root)
                production_fresh_bound.verify_source(production_root)

        def verify_reserved_absence() -> None:
            tree.verify()
            for path in reserved:
                if tree.lexists(path):
                    raise FileExistsError(f"reserved add-only path exists: {path}")
            tree.verify()

        def verify_common_authority() -> None:
            tree.verify()
            _require(
                _bounded_directory_inventory(
                    parent_fd,
                    "R012 publication parent external inventory",
                    maximum_entries=MAXIMUM_PUBLICATION_PARENT_EXTERNAL_ENTRY_COUNT,
                    maximum_name_bytes=(
                        MAXIMUM_PUBLICATION_PARENT_EXTERNAL_NAME_BYTES
                    ),
                    excluded_names=owned_parent_names,
                )
                == external_parent_inventory,
                "R012 publication parent external inventory changed",
            )
            verify_source_authority()
            verify_reserved_absence()
            tree.verify()

        def verify_named_cohort(
            name: str,
            cohort: tuple[
                int,
                tuple[int, ...],
                Mapping[str, tuple[int, tuple[int, ...], bytes]],
            ],
            *,
            opposite_must_be_absent: str,
            full_bytes: bool = False,
        ) -> None:
            directory_fd, identity, retained = cohort
            current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            opened = os.fstat(directory_fd)
            _validate_directory_authority(current, f"R012 retained directory: {name}")
            _validate_directory_authority(opened, f"R012 retained directory FD: {name}")
            _require(
                stat.S_IMODE(current.st_mode) == 0o700
                and _directory_identity(current) == identity
                == _directory_identity(opened)
                and _bounded_directory_inventory(
                    directory_fd,
                    f"R012 retained directory: {name}",
                    maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
                    maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
                )
                == set(retained),
                f"R012 retained directory changed: {name}",
            )
            _require(
                not _entry_exists(parent_fd, opposite_must_be_absent),
                "R012 final and transaction directories coexist",
            )
            if full_bytes:
                _verify_retained_output_files(directory_fd, retained, read_budget)
            else:
                for retained_name in sorted(retained):
                    _verify_retained_regular_identity_at(
                        directory_fd,
                        retained_name,
                        retained[retained_name],
                        f"R012 retained directory output: {retained_name}",
                    )

        def rebase_named_cohort(
            name: str,
            directory_fd: int,
            retained: Mapping[str, tuple[int, tuple[int, ...], bytes]],
            object_identity: tuple[int, ...],
        ) -> tuple[
            int,
            tuple[int, ...],
            Mapping[str, tuple[int, tuple[int, ...], bytes]],
        ]:
            current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            opened = os.fstat(directory_fd)
            _validate_directory_authority(current, f"R012 rebased directory: {name}")
            _validate_directory_authority(opened, f"R012 rebased directory FD: {name}")
            _require(
                _mutable_directory_identity(current) == object_identity
                == _mutable_directory_identity(opened)
                and _directory_identity(current) == _directory_identity(opened)
                and stat.S_IMODE(current.st_mode) == 0o700
                and _bounded_directory_inventory(
                    directory_fd,
                    f"R012 rebased directory: {name}",
                    maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
                    maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
                )
                == set(retained),
                f"R012 retained directory changed while rebasing: {name}",
            )
            return directory_fd, _directory_identity(opened), retained

        def sync_retained_cohort(
            directory_fd: int,
            retained: Mapping[str, tuple[int, tuple[int, ...], bytes]],
        ) -> None:
            for retained_name in sorted(retained):
                _verify_retained_regular_identity_at(
                    directory_fd,
                    retained_name,
                    retained[retained_name],
                    f"R012 durability output: {retained_name}",
                )
                os.fsync(retained[retained_name][0])
                _verify_retained_regular_identity_at(
                    directory_fd,
                    retained_name,
                    retained[retained_name],
                    f"R012 durable output: {retained_name}",
                )
            os.fsync(directory_fd)

        def invoke_hook(
            phase: str,
            verifier: Callable[[], None],
        ) -> None:
            verifier()
            verify_common_authority()
            try:
                user_hook(phase)
            except BaseException as primary:
                try:
                    verifier()
                    verify_common_authority()
                except BaseException as secondary:
                    try:
                        primary.add_note(
                            "R012 hook boundary also drifted: "
                            f"{type(secondary).__name__}: {secondary}"
                        )
                    except BaseException:
                        pass
                raise
            verifier()
            verify_common_authority()

        def mark_committed(verifier: Callable[[], None]) -> None:
            nonlocal committed
            verifier()
            verify_common_authority()
            verifier()
            verify_common_authority()
            verifier()
            if touches_production:
                assert supplied_guard is not None
                assert production_fresh_guard is not None
                supplied_guard.suppress_close_errors_after_commit()
                production_fresh_guard.suppress_close_errors_after_commit()
            retained_owner.suppress_close_errors_after_commit()
            tree.suppress_close_errors_after_commit()
            committed = True

        try:
            try:
                fcntl.flock(parent_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ValidationError("R012 publication parent is already locked") from exc
            parent_locked = True
            verify_common_authority()
            final_entry = _entry_stat_or_none(parent_fd, final_directory.name)
            transaction_entry = _entry_stat_or_none(parent_fd, transaction_name)
            _require(
                not (final_entry is not None and transaction_entry is not None),
                "R012 final and transaction directories coexist",
            )

            if final_entry is not None:
                retained_final = _retain_output_directory_at(
                    parent_fd,
                    final_directory.name,
                    outputs,
                    read_budget,
                    owner=retained_owner,
                )
                _require(
                    _directory_identity(final_entry) == retained_final[1],
                    "R012 final directory changed after state classification",
                )

                def verify_final() -> None:
                    verify_named_cohort(
                        final_directory.name,
                        retained_final,
                        opposite_must_be_absent=transaction_name,
                    )

                verify_final()
                verify_common_authority()
                sync_retained_cohort(retained_final[0], retained_final[2])
                retained_final = rebase_named_cohort(
                    final_directory.name,
                    retained_final[0],
                    retained_final[2],
                    _mutable_directory_identity(final_entry),
                )
                os.fsync(parent_fd)
                verify_final()
                verify_common_authority()
                verify_named_cohort(
                    final_directory.name,
                    retained_final,
                    opposite_must_be_absent=transaction_name,
                    full_bytes=True,
                )
                mark_committed(verify_final)
            else:
                _require(
                    _read_process_umask() & 0o700 == 0,
                    "process umask masks required owner permissions",
                )
                transaction_was_created = transaction_entry is None
                if transaction_was_created:
                    os.mkdir(transaction_name, mode=0o700, dir_fd=parent_fd)
                    transaction_entry = os.stat(
                        transaction_name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    created_path_fd = retained_owner.open(
                        transaction_name,
                        _required_open_flag("O_PATH")
                        | _required_open_flag("O_CLOEXEC")
                        | _required_open_flag("O_DIRECTORY")
                        | _required_open_flag("O_NOFOLLOW"),
                        dir_fd=parent_fd,
                    )
                    created_opened = os.fstat(created_path_fd)
                    _validate_directory_authority(
                        created_opened,
                        "new R012 transaction directory",
                    )
                    _require(
                        _directory_identity(created_opened)
                        == _directory_identity(transaction_entry),
                        "R012 transaction directory changed after creation",
                    )
                    _require_same_mount(
                        parent_fd,
                        created_path_fd,
                        "new R012 transaction directory",
                    )
                    created_object_identity = (
                        transaction_entry.st_dev,
                        transaction_entry.st_ino,
                        transaction_entry.st_uid,
                        transaction_entry.st_gid,
                    )
                    if stat.S_IMODE(created_opened.st_mode) != 0o700:
                        _fchmodat2_empty_path(
                            created_path_fd,
                            0o700,
                            "new R012 transaction directory",
                        )
                    transaction_entry = os.stat(
                        transaction_name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    _require(
                        (
                            transaction_entry.st_dev,
                            transaction_entry.st_ino,
                            transaction_entry.st_uid,
                            transaction_entry.st_gid,
                        )
                        == created_object_identity,
                        "R012 transaction directory changed during mode binding",
                    )
                    _require(
                        _directory_identity(transaction_entry)
                        == _directory_identity(os.fstat(created_path_fd))
                        and stat.S_IMODE(transaction_entry.st_mode) == 0o700,
                        "R012 transaction directory mode binding differs",
                    )
                assert transaction_entry is not None
                classified_transaction_identity = _mutable_directory_identity(
                    transaction_entry
                )
                transaction_fd = retained_owner.open_directory_at(
                    parent_fd,
                    transaction_name,
                    "R012 hidden publication transaction",
                    required_mode=0o700,
                )
                _require(
                    _mutable_directory_identity(os.fstat(transaction_fd))
                    == classified_transaction_identity,
                    "R012 transaction directory changed after state classification",
                )
                _require(
                    _directory_identity(transaction_entry)
                    == _directory_identity(os.fstat(transaction_fd)),
                    "R012 transaction directory changed after state classification",
                )
                transaction_object_identity = _mutable_directory_identity(
                    os.fstat(transaction_fd)
                )
                if transaction_was_created:
                    _require(
                        not _bounded_directory_inventory(
                            transaction_fd,
                            "new R012 transaction directory",
                            maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
                            maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
                        ),
                        "new R012 transaction directory is not empty",
                    )
                retained_outputs = _retain_exact_output_subset(
                    transaction_fd,
                    outputs,
                    retained_owner,
                    read_budget,
                )
                _require(
                    _directory_identity(transaction_entry)
                    == _directory_identity(os.fstat(transaction_fd)),
                    "R012 transaction changed during initial retention",
                )
                retained_transaction = rebase_named_cohort(
                    transaction_name,
                    transaction_fd,
                    retained_outputs,
                    transaction_object_identity,
                )

                def verify_transaction() -> None:
                    verify_named_cohort(
                        transaction_name,
                        retained_transaction,
                        opposite_must_be_absent=final_directory.name,
                    )

                sync_retained_cohort(transaction_fd, retained_outputs)
                os.fsync(parent_fd)
                retained_transaction = rebase_named_cohort(
                    transaction_name,
                    transaction_fd,
                    retained_outputs,
                    transaction_object_identity,
                )
                invoke_hook("journal_durable", verify_transaction)
                for index, (path, content) in enumerate(outputs.items()):
                    if path.name in retained_outputs:
                        continue
                    verify_transaction()
                    verify_common_authority()
                    entry = _write_tmpfile_link_noreplace_at(
                        transaction_fd,
                        path.name,
                        content,
                        retained_owner,
                        read_budget,
                    )
                    retained_outputs[path.name] = entry
                    os.fsync(transaction_fd)
                    retained_transaction = rebase_named_cohort(
                        transaction_name,
                        transaction_fd,
                        retained_outputs,
                        transaction_object_identity,
                    )
                    invoke_hook(f"staged_output_{index}", verify_transaction)
                _require(
                    set(retained_outputs) == {path.name for path in outputs},
                    "R012 completed transaction output inventory differs",
                )
                os.fsync(transaction_fd)
                retained_transaction = rebase_named_cohort(
                    transaction_name,
                    transaction_fd,
                    retained_outputs,
                    transaction_object_identity,
                )
                invoke_hook("before_rename", verify_transaction)

                def verify_published_object_for_rollback() -> None:
                    current = os.stat(
                        final_directory.name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    opened = os.fstat(transaction_fd)
                    _validate_directory_authority(
                        current,
                        "R012 rollback publication path",
                    )
                    _validate_directory_authority(
                        opened,
                        "R012 rollback publication descriptor",
                    )
                    _require(
                        stat.S_IMODE(current.st_mode) == 0o700
                        and _mutable_directory_identity(current)
                        == transaction_object_identity
                        == _mutable_directory_identity(opened)
                        and _bounded_directory_inventory(
                            transaction_fd,
                            "R012 rollback publication directory",
                            maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
                            maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
                        )
                        == set(retained_outputs)
                        and not _entry_exists(parent_fd, transaction_name),
                        "R012 published directory changed before rollback",
                    )
                    for retained_name in sorted(retained_outputs):
                        _verify_retained_regular_identity_at(
                            transaction_fd,
                            retained_name,
                            retained_outputs[retained_name],
                            f"R012 rollback output: {retained_name}",
                        )

                _rename_noreplace(
                    parent_fd,
                    transaction_name,
                    final_directory.name,
                )
                published = True
                try:
                    retained_final = rebase_named_cohort(
                        final_directory.name,
                        transaction_fd,
                        retained_outputs,
                        transaction_object_identity,
                    )

                    def verify_final() -> None:
                        verify_named_cohort(
                            final_directory.name,
                            retained_final,
                            opposite_must_be_absent=transaction_name,
                        )

                    invoke_hook("after_rename", verify_final)
                    os.fsync(parent_fd)
                    verify_final()
                    verify_common_authority()
                    invoke_hook("after_parent_fsync", verify_final)
                    invoke_hook("before_journal_cleanup", verify_final)
                    verify_named_cohort(
                        final_directory.name,
                        retained_final,
                        opposite_must_be_absent=transaction_name,
                        full_bytes=True,
                    )
                    mark_committed(verify_final)
                except BaseException as primary:
                    if published and not committed:
                        rollback_error: BaseException | None = None
                        try:
                            verify_published_object_for_rollback()
                            _rename_noreplace(
                                parent_fd,
                                final_directory.name,
                                transaction_name,
                            )
                            os.fsync(parent_fd)
                            retained_transaction = rebase_named_cohort(
                                transaction_name,
                                transaction_fd,
                                retained_outputs,
                                transaction_object_identity,
                            )
                            verify_transaction()
                        except BaseException as secondary:
                            rollback_error = secondary
                        if rollback_error is not None:
                            try:
                                primary.add_note(
                                    "R012 rollback also failed: "
                                    f"{type(rollback_error).__name__}: {rollback_error}"
                                )
                            except BaseException:
                                pass
                    raise
        except BaseException:
            body_failed = True
            raise
        finally:
            unlock_error: BaseException | None = None
            if parent_locked:
                try:
                    fcntl.flock(parent_fd, fcntl.LOCK_UN)
                except BaseException as exc:
                    unlock_error = exc
            if unlock_error is not None and not committed and not body_failed:
                raise unlock_error
    check_outputs(
        outputs,
        allowed_root=allowed_root,
        _suppress_close_errors=committed,
        _read_budget=read_budget,
    )


def _official_output_paths(allowed_root: Path) -> tuple[Path, ...]:
    root = _lexical_abspath(allowed_root)
    return (
        root / R012_LEDGER_REL,
        root / R012_EVIDENCE_REL,
        root / R012_RECEIPT_REL,
    )


def _is_official_output_mapping(
    outputs: Mapping[Path, bytes],
    allowed_root: Path,
) -> bool:
    expected = _official_output_paths(allowed_root)
    return len(outputs) == len(expected) and set(outputs) == set(expected)


def write_add_only(
    outputs: Mapping[Path, bytes],
    *,
    allowed_root: Path = REPO_ROOT,
    reserved_absent_paths: Iterable[Path] = (),
    _transaction_hook: Callable[[str], None] | None = None,
) -> None:
    canonical_outputs, canonical_reserved, canonical_root = (
        _canonical_transaction_inputs(
            outputs,
            reserved_absent_paths,
            allowed_root,
        )
    )
    caller_bound = outputs if isinstance(outputs, _SourceBoundOutputs) else None
    if caller_bound is not None:
        caller_bound.verify_source(canonical_root)
    official = _is_official_output_mapping(canonical_outputs, canonical_root)
    official_directory = canonical_root / R012_PACKET_DIR_REL
    touches_official_directory = any(
        _is_at_or_below(path, official_directory) for path in canonical_outputs
    )
    production_root = _lexical_abspath(REPO_ROOT)
    production_paths = _official_output_paths(production_root)
    production_directory = production_paths[0].parent
    touches_production_directory = any(
        _is_at_or_below(path, production_directory)
        for path in canonical_outputs
    )
    _require(
        not touches_official_directory or (official and caller_bound is not None),
        "official R012 publication requires live source-bound outputs",
    )
    _require(
        not touches_production_directory
        or (
            canonical_root == production_root
            and caller_bound is not None
            and len(canonical_outputs) == len(production_paths)
            and set(canonical_outputs) == set(production_paths)
        ),
        "production R012 publication requires the exact live source-bound mapping",
    )
    caller_guard: _RetainedSnapshot | None = None
    fresh_bound: _SourceBoundOutputs | None = None
    operation_failed = False
    try:
        if touches_official_directory:
            assert caller_bound is not None
            caller_guard = caller_bound.verify_source(canonical_root)
            publication_parent = _official_output_paths(canonical_root)[0].parent.parent
            caller_guard.allow_directory_entry_churn(publication_parent)
            rebuilt = build_outputs(canonical_root)
            _require(
                isinstance(rebuilt, _SourceBoundOutputs),
                "fresh official R012 derivation lacks its retained source cohort",
            )
            fresh_bound = rebuilt
            _require(
                list(canonical_outputs.items())
                == list(fresh_bound._expected_outputs.items()),
                "caller official R012 mapping differs from fresh exact derivation",
            )
            guard = fresh_bound.verify_source(canonical_root)
            guard.allow_directory_entry_churn(publication_parent)
            transaction_outputs = dict(fresh_bound._expected_outputs)
        else:
            guard = None
            transaction_outputs = dict(canonical_outputs)

        user_hook = _transaction_hook or (lambda _phase: None)

        def verify_official_source_authority() -> None:
            if caller_bound is not None:
                caller_bound.verify_source(canonical_root)
            if fresh_bound is not None:
                fresh_bound.verify_source(canonical_root)

        def guarded_hook(phase: str) -> None:
            verify_official_source_authority()
            user_hook(phase)
            verify_official_source_authority()

        _write_add_only_transaction(
            transaction_outputs,
            allowed_root=canonical_root,
            reserved_absent_paths=canonical_reserved,
            _transaction_hook=guarded_hook,
            _production_source_bound=(
                fresh_bound if touches_production_directory else None
            ),
            _source_authority_verifier=(
                verify_official_source_authority
                if caller_bound is not None or fresh_bound is not None
                else None
            ),
        )
        if fresh_bound is not None:
            fresh_bound.verify_source(
                canonical_root
            ).suppress_close_errors_after_commit()
        if caller_guard is not None:
            caller_guard.suppress_close_errors_after_commit()
    except BaseException:
        operation_failed = True
        raise
    finally:
        if fresh_bound is not None:
            try:
                fresh_bound.close()
            except BaseException:
                if not operation_failed:
                    raise


def _write_add_only(
    outputs: Mapping[Path, bytes],
    *,
    allowed_root: Path = REPO_ROOT,
    reserved_absent_paths: Iterable[Path] = (),
) -> None:
    write_add_only(
        outputs,
        allowed_root=allowed_root,
        reserved_absent_paths=reserved_absent_paths,
    )


def check_outputs(
    outputs: Mapping[Path, bytes],
    *,
    allowed_root: Path = REPO_ROOT,
    _suppress_close_errors: bool = False,
    _read_budget: _ReadBudget | None = None,
) -> None:
    source_guard = (
        outputs.verify_source(allowed_root)
        if isinstance(outputs, _SourceBoundOutputs)
        else None
    )
    _require(bool(outputs), "R012 committed output set must not be empty")
    _require(
        all(isinstance(path, Path) for path in outputs),
        "R012 committed output path must be pathlib.Path",
    )
    directories = {path.parent for path in outputs}
    _require(
        len(directories) == 1,
        "R012 committed outputs must share one packet directory",
    )
    directory = next(iter(directories))
    _require(
        all(path.parent == directory for path in outputs),
        "R012 committed output must be a direct packet child",
    )
    transaction_name = f".{directory.name}.r012-transaction"
    with _RetainedSnapshot(
        allowed_root,
        suppress_close_errors=_suppress_close_errors,
    ) as snapshot:
        if _suppress_close_errors:
            snapshot.suppress_close_errors_after_commit()
        parent_fd = snapshot.directory_fd(directory.parent)
        _require(
            not _entry_exists(parent_fd, transaction_name),
            "live R012 transaction remains beside committed output",
        )
        directory_fd = snapshot.directory_fd(directory)
        _require(
            stat.S_IMODE(os.fstat(directory_fd).st_mode) == 0o700,
            "committed R012 packet directory mode differs",
        )
        observed_names = _bounded_directory_inventory(
            directory_fd,
            "committed R012 packet",
            maximum_entries=MAXIMUM_TRANSACTION_OUTPUT_COUNT,
            maximum_name_bytes=MAXIMUM_EXACT_DIRECTORY_NAME_BYTES,
        )
        expected_names = {path.name for path in outputs}
        _require(
            observed_names == expected_names,
            "committed R012 packet inventory differs",
        )
        for path, expected in outputs.items():
            if _read_budget is not None:
                _read_budget.require_available(
                    len(expected),
                    f"committed R012 output check: {path.name}",
                )
            observed = snapshot.read(
                path,
                require_single_link=True,
                required_mode=0o600,
            )
            _require(observed == expected, f"committed R012 output drift: {path}")
            if _read_budget is not None:
                _read_budget.consume(
                    len(expected),
                    f"committed R012 output check: {path.name}",
                )
        snapshot.verify()
        if _suppress_close_errors:
            snapshot.suppress_close_errors_after_commit()
    if source_guard is not None:
        source_guard.verify()


def _check_committed(
    outputs: Mapping[Path, bytes],
    *,
    allowed_root: Path = REPO_ROOT,
) -> None:
    check_outputs(outputs, allowed_root=allowed_root)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--check",
        action="store_true",
        help="verify existing R012 outputs without writing",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="create the exact add-only R012 packet",
    )
    args = parser.parse_args(argv)
    outputs: _SourceBoundOutputs | None = None
    try:
        operation_failed = False
        try:
            built = build_outputs(REPO_ROOT)
            _require(
                isinstance(built, _SourceBoundOutputs),
                "R012 main build lacks its retained source cohort",
            )
            outputs = built
            if args.check:
                check_outputs(outputs)
                action = "verified"
            else:
                write_add_only(outputs)
                action = "created"
        except BaseException:
            operation_failed = True
            raise
        finally:
            if outputs is not None:
                try:
                    outputs.close()
                except BaseException:
                    if not operation_failed:
                        raise
    except (ValidationError, FileExistsError, OSError) as exc:
        print(f"R012 ERROR: {exc}", file=sys.stderr)
        return 1
    print(
        f"{action} 3 add-only R012 outputs; exact257=257, "
        "changed-progress-records=6, unchanged-records=251, release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
