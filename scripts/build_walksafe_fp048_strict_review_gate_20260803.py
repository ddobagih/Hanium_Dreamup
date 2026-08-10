#!/usr/bin/env python3
"""Strict, add-only FP-048 independent-review gate outside the exact106 scope."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from copy import deepcopy
import ctypes
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import errno
import fcntl
import os
from pathlib import Path
import stat
import sys
from types import FunctionType
from typing import Any, Callable, Iterator, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    build_walksafe_fp048_encryption_connection_security_incident_trace_20260802
    as trace,
)


KST = timezone(timedelta(hours=9))
POST_TRANSACTION_REL = trace.RESULT_DIR_REL / ".post-review-transaction"
AT_EMPTY_PATH = 0x1000

KNOWN_LOG_SHA256_BY_REL = {
    trace.RESULT_DIR_REL / "logs/android-protected-storage.log": (
        "bdb1dba28f3cf72219e4f8590433d4a7cb5ba01f6805828a0887c60103d38280"
    ),
    trace.RESULT_DIR_REL / "logs/https-no-downgrade.log": (
        "b9585755c2a0c6c0b53b7f9b05d75a778946d8241776cead4172a85f24e4def3"
    ),
    trace.RESULT_DIR_REL / "logs/incident-state-notice-decision.log": (
        "6825802268a54c9934c592bac15465a7acc50fe7c4573726b6685fbb80b6ec3a"
    ),
    trace.RESULT_DIR_REL / "logs/key-lifecycle-original-access.log": (
        "9a0e9847bfdad5b3a613b48b40a978fe4394940d5d668559ff23103f246328c9"
    ),
    trace.RESULT_DIR_REL / "logs/server-original-db-backup-boundary.log": (
        "a708576421c7d1faa2418bac4eaf90be9cab9eb022bafe4d648dbbcfdbe042f3"
    ),
}
RETIREMENT_DIR_REL = (
    trace.RESULT_DIR_REL / "r023-provisional-retirement-20260803"
)
RETIREMENT_MANIFEST_REL = RETIREMENT_DIR_REL / "retirement-manifest.json"
RETIREMENT_MANIFEST_SHA256 = (
    "e4fbb3e99e6cd3f3967877d0c3ed11e8796decd2b96c555accbf66d2e78c8f73"
)

ATTESTATION_FIELDS = (
    "schema_version",
    "evidence_type",
    "goal_id",
    "reviewer_id",
    "reviewer_task",
    "reviewed_at",
    "review_subject_sha256",
    "reviewed_result_sha256_by_kind",
    "reviewed_consumer_bindings",
    "decision",
    "findings",
    "review_boundary",
)
RESULT_HASH_FIELDS = (
    "IMPLEMENTATION_RECORD",
    "VERIFICATION_RESULT",
    "SUCCESSOR_TRACE",
)
FINDING_FIELDS = ("blocking", "major_open", "minor_open")
CONSUMER_BINDING_FIELDS = ("role", "path", "schema_version", "sha256")


class GateError(RuntimeError):
    """Raised before malformed review evidence can be consumed or published."""


def _directory_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
    )


@dataclass(frozen=True)
class ReviewContext:
    pre_review_outputs: dict[Path, str]
    result_hashes: dict[str, str]
    consumer_bindings: list[dict[str, Any]]
    review_subject_sha256: str
    verification_observed_at: str


@dataclass(frozen=True)
class StrictAttestation:
    raw: bytes
    value: dict[str, Any]
    sha256: str


class _AttestationLease:
    __slots__ = (
        "root",
        "result_directory_fd",
        "result_directory_identity",
        "descriptor",
        "identity",
        "attestation",
    )

    def __init__(
        self,
        *,
        root: Path,
        result_directory_fd: int,
        result_directory_identity: tuple[int, ...],
        descriptor: int,
        identity: tuple[int, ...],
        attestation: StrictAttestation,
    ) -> None:
        self.root = root
        self.result_directory_fd = result_directory_fd
        self.result_directory_identity = result_directory_identity
        self.descriptor: int | None = descriptor
        self.identity = identity
        self.attestation = attestation

    def __copy__(self):
        raise TypeError("attestation lease cannot be copied")

    def __deepcopy__(self, _memo):
        raise TypeError("attestation lease cannot be copied")

    def __reduce_ex__(self, _protocol):
        raise TypeError("attestation lease cannot be serialized")

    def __enter__(self):
        return self

    def __exit__(self, _exc_type, exc_value, _traceback) -> bool:
        self.close(primary=exc_value)
        return False

    def check(self) -> None:
        descriptor = self.descriptor
        _require(descriptor is not None, "review attestation lease is closed")
        _require(
            _directory_identity(os.fstat(self.result_directory_fd))
            == self.result_directory_identity,
            "review result directory changed while leased",
        )
        current_directory = (self.root / trace.RESULT_DIR_REL).lstat()
        _require(
            _directory_identity(current_directory) == self.result_directory_identity,
            "review result directory path changed while leased",
        )
        _require(
            trace._file_identity(os.fstat(descriptor)) == self.identity,
            "review attestation inode changed while leased",
        )
        entry = os.stat(
            trace.REVIEW_ATTESTATION_REL.name,
            dir_fd=self.result_directory_fd,
            follow_symlinks=False,
        )
        _require(
            trace._file_identity(entry) == self.identity,
            "review attestation path changed while leased",
        )
        _require(
            _read_descriptor(descriptor) == self.attestation.raw,
            "review attestation bytes changed while leased",
        )

    def close(self, *, primary: BaseException | None = None) -> None:
        descriptor = self.descriptor
        self.descriptor = None
        if descriptor is not None:
            _close_descriptor(descriptor, primary=primary)


class _PostTransactionLease:
    __slots__ = (
        "result_directory_fd",
        "descriptor",
        "identity",
    )

    def __init__(
        self,
        *,
        result_directory_fd: int,
        descriptor: int,
        identity: tuple[int, ...],
    ) -> None:
        self.result_directory_fd = result_directory_fd
        self.descriptor: int | None = descriptor
        self.identity = identity

    def __copy__(self):
        raise TypeError("post-review transaction lease cannot be copied")

    def __deepcopy__(self, _memo):
        raise TypeError("post-review transaction lease cannot be copied")

    def __reduce_ex__(self, _protocol):
        raise TypeError("post-review transaction lease cannot be serialized")

    def __enter__(self):
        return self

    def __exit__(self, _exc_type, exc_value, _traceback) -> bool:
        self.close(primary=exc_value)
        return False

    def _descriptor(self) -> int:
        descriptor = self.descriptor
        _require(descriptor is not None, "post-review transaction lease is closed")
        return descriptor

    def check_path(self) -> None:
        descriptor = self._descriptor()
        current_fd = os.fstat(descriptor)
        _require(
            _directory_identity(current_fd) == self.identity,
            "post-review transaction inode changed while leased",
        )
        current_path = os.stat(
            POST_TRANSACTION_REL.name,
            dir_fd=self.result_directory_fd,
            follow_symlinks=False,
        )
        _require(
            _directory_identity(current_path) == self.identity,
            "post-review transaction path changed while leased",
        )

    def check_removed(self) -> None:
        descriptor = self._descriptor()
        current_fd = os.fstat(descriptor)
        _require(
            _directory_identity(current_fd) == self.identity
            and current_fd.st_nlink == 0,
            "post-review transaction inode remains linked after publication",
        )
        try:
            os.stat(
                POST_TRANSACTION_REL.name,
                dir_fd=self.result_directory_fd,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            return
        raise GateError("post-review transaction path remains after publication")

    def close(self, *, primary: BaseException | None = None) -> None:
        descriptor = self.descriptor
        self.descriptor = None
        if descriptor is not None:
            _close_descriptor(descriptor, primary=primary)


class _AttestationBoundOutputs(Mapping[Path, str]):
    """Recheck the attestation before every writer observation or mutation."""

    def __init__(
        self,
        outputs: Mapping[Path, str],
        lease: _AttestationLease,
        transaction_lease: _PostTransactionLease | None = None,
    ) -> None:
        self._outputs = dict(outputs)
        self._lease = lease
        self._transaction_lease = transaction_lease

    def _check(self) -> None:
        self._lease.check()
        if self._transaction_lease is not None:
            self._transaction_lease.check_path()

    def check(self) -> None:
        self._check()

    def __getitem__(self, key: Path) -> str:
        self._check()
        return self._outputs[key]

    def __iter__(self):
        self._check()
        for key in self._outputs:
            self._check()
            yield key

    def __len__(self) -> int:
        return len(self._outputs)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise GateError(message)


def _close_descriptor(
    descriptor: int,
    *,
    primary: BaseException | None = None,
) -> None:
    try:
        os.close(descriptor)
    except OSError:
        if primary is None:
            raise


def _now_kst() -> datetime:
    return datetime.now(KST)


def _parse_canonical_review_time(value: Any, *, label: str) -> datetime:
    _require(type(value) is str, f"{label} type differs")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise GateError(f"{label} is not ISO-8601") from exc
    _require(
        parsed.tzinfo is not None and parsed.utcoffset() is not None,
        f"{label} lacks timezone",
    )
    _require(parsed.utcoffset() == timedelta(hours=9), f"{label} is not +09:00")
    _require(parsed.microsecond == 0, f"{label} must use second precision")
    _require(parsed.isoformat() == value, f"{label} is not canonical")
    return parsed


def _strict_equal(actual: Any, expected: Any, *, label: str) -> None:
    _require(type(actual) is type(expected), f"{label} type differs")
    if isinstance(expected, dict):
        _require(tuple(actual) == tuple(expected), f"{label} fields or order differ")
        for key in expected:
            _strict_equal(actual[key], expected[key], label=f"{label}.{key}")
        return
    if isinstance(expected, list):
        _require(len(actual) == len(expected), f"{label} length differs")
        for index, (actual_item, expected_item) in enumerate(
            zip(actual, expected, strict=True)
        ):
            _strict_equal(
                actual_item,
                expected_item,
                label=f"{label}[{index}]",
            )
        return
    _require(actual == expected, f"{label} value differs")


def _run_full_artifact_successor_validation_compatible(
    root: Path,
    snapshots: Mapping[Path, tuple[bytes, dict[str, Any]]],
    source_pins: Mapping[Path, str],
) -> None:
    """Run the frozen successor validator with its current snapshot contract."""
    from scripts import (
        build_walksafe_fp048_artifact_trace_successor_20260802
        as artifact_builder,
    )

    try:
        expected_paths = {
            relative for _, relative, _ in trace.FP048_ARTIFACT_CONSUMERS
        }
        trace.require(
            artifact_builder.SUCCESSOR_ID == trace.FP048_ARTIFACT_SUCCESSOR_ID,
            "artifact validator successor ID differs",
        )
        trace.require(
            set(artifact_builder.OUTPUT_PATHS) == expected_paths,
            "artifact validator output path set differs",
        )
        trace.require(
            set(artifact_builder.INPUT_PATHS) == set(source_pins),
            "artifact validator input path set differs",
        )
        document_ids = {
            trace.IMPLEMENTATION_REL: (
                "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTATION-20260802-001"
            ),
            trace.VERIFICATION_REL: (
                "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-VERIFICATION-20260802-001"
            ),
        }
        observed = {
            relative: snapshots[relative][0]
            for relative in artifact_builder.OUTPUT_PATHS
        }
        validated = artifact_builder.check_successor(
            root,
            expected_input_sha256=dict(source_pins),
            expected_document_ids=document_ids,
        )
        trace.require(
            validated == observed,
            "artifact successor bytes differ from frozen consumer snapshots",
        )
    except artifact_builder.BuildError as exc:
        raise trace.BuildError(
            f"artifact successor validation failed: {exc}"
        ) from exc


def _run_full_r012_validation_compatible(
    root: Path,
    snapshots: Mapping[Path, tuple[bytes, dict[str, Any]]],
    source_pins: Mapping[Path, str],
) -> None:
    """Validate R012 without forbidden production-root pin injection."""
    from scripts import (
        build_walksafe_phase1_exact257_successor_r012_20260802 as r012_builder,
    )

    try:
        built = None
        operation_failed = False
        try:
            trace.require(
                r012_builder.R012_PACKET_DIR_REL == trace.R012_DIR_REL,
                "r012 validator packet path differs",
            )
            trace.require(
                dict(r012_builder.PINNED_SOURCE_SHA256_BY_RELATIVE_PATH)
                == dict(source_pins),
                "r012 validator source pins differ",
            )
            observed = {
                root / r012_builder.R012_LEDGER_REL: snapshots[
                    r012_builder.R012_LEDGER_REL
                ][0],
                root / r012_builder.R012_EVIDENCE_REL: snapshots[
                    r012_builder.R012_EVIDENCE_REL
                ][0],
                root / r012_builder.R012_RECEIPT_REL: snapshots[
                    r012_builder.R012_RECEIPT_REL
                ][0],
            }
            built = r012_builder.build_outputs(root)
            trace.require(
                dict(built) == observed,
                "r012 bytes differ from frozen consumer snapshots",
            )
            r012_builder.check_outputs(built, allowed_root=root)
        except BaseException:
            operation_failed = True
            raise
        finally:
            if built is not None:
                try:
                    built.close()
                except BaseException:
                    if not operation_failed:
                        raise
    except r012_builder.ValidationError as exc:
        raise trace.BuildError(f"r012 validation failed: {exc}") from exc


def _validate_consumer_bindings_compatible(
    root: Path,
    implementation_sha256: str,
    verification_sha256: str,
) -> list[dict[str, Any]]:
    """Execute the frozen consumer contract with one isolated API adapter."""
    frozen = trace.validate_consumer_bindings
    _require(
        frozen.__globals__ is vars(trace) and frozen.__closure__ is None,
        "frozen consumer validator identity differs",
    )
    validator_globals = dict(frozen.__globals__)
    validator_globals["_run_full_artifact_successor_validation"] = (
        _run_full_artifact_successor_validation_compatible
    )
    validator_globals["_run_full_r012_validation"] = (
        _run_full_r012_validation_compatible
    )
    validator = FunctionType(
        frozen.__code__,
        validator_globals,
        frozen.__name__,
        frozen.__defaults__,
        frozen.__closure__,
    )
    validator.__kwdefaults__ = dict(frozen.__kwdefaults__ or {})
    return validator(
        root,
        implementation_sha256,
        verification_sha256,
    )


def _build_post_review_outputs_compatible(
    *,
    root: Path,
    pre_review_kwargs: Mapping[str, Any] | None,
) -> dict[Path, str]:
    """Execute the frozen post-review builder with the compatible validator."""
    frozen = trace.build_post_review_outputs
    _require(
        frozen.__globals__ is vars(trace) and frozen.__closure__ is None,
        "frozen post-review builder identity differs",
    )
    builder_globals = dict(frozen.__globals__)
    builder_globals["validate_consumer_bindings"] = (
        _validate_consumer_bindings_compatible
    )
    builder = FunctionType(
        frozen.__code__,
        builder_globals,
        frozen.__name__,
        frozen.__defaults__,
        frozen.__closure__,
    )
    builder.__kwdefaults__ = dict(frozen.__kwdefaults__ or {})
    return builder(
        root=root,
        pre_review_kwargs=pre_review_kwargs,
    )


def prepare_review_context(
    root: Path = ROOT,
    *,
    pre_review_kwargs: Mapping[str, Any] | None = None,
) -> ReviewContext:
    root = root.resolve(strict=True)
    outputs = trace.build_pre_review_outputs(
        root=root,
        **dict(pre_review_kwargs or {}),
    )
    trace._assert_disk_outputs(root, outputs)
    result_hashes = {
        kind: trace.bytes_sha256(outputs[relative].encode())
        for kind, relative in (
            ("IMPLEMENTATION_RECORD", trace.IMPLEMENTATION_REL),
            ("VERIFICATION_RESULT", trace.VERIFICATION_REL),
            ("SUCCESSOR_TRACE", trace.SUCCESSOR_REL),
        )
    }
    _require(
        tuple(result_hashes) == RESULT_HASH_FIELDS,
        "review result hash field order differs",
    )
    consumers = _validate_consumer_bindings_compatible(
        root,
        result_hashes["IMPLEMENTATION_RECORD"],
        result_hashes["VERIFICATION_RESULT"],
    )
    _require(
        len(consumers) == len(trace.CONSUMER_CONTRACTS) == 11,
        "review consumer count differs",
    )
    for index, (binding, contract) in enumerate(
        zip(consumers, trace.CONSUMER_CONTRACTS, strict=True)
    ):
        role, relative, schema = contract
        expected_identity = {
            "role": role,
            "path": relative.as_posix(),
            "schema_version": schema,
            "sha256": binding.get("sha256"),
        }
        _strict_equal(
            binding,
            expected_identity,
            label=f"review consumer binding[{index}]",
        )
        _require(
            type(binding["sha256"]) is str
            and trace.SHA256_RE.fullmatch(binding["sha256"]) is not None,
            f"review consumer binding[{index}] SHA-256 differs",
        )
    verification = trace.json_from_bytes(
        outputs[trace.VERIFICATION_REL].encode(),
        "verification result",
    )
    if os.path.lexists(root / trace.RESULT_DIR_REL / "logs"):
        _require(
            set(KNOWN_LOG_SHA256_BY_REL)
            == {lane.log_rel for lane in trace.LANES},
            "known log auxiliary path contract differs",
        )
        checks = verification.get("checks")
        _require(
            isinstance(checks, list) and len(checks) == len(trace.LANES),
            "known log verification check count differs",
        )
        for index, (check, lane) in enumerate(
            zip(checks, trace.LANES, strict=True)
        ):
            _require(
                isinstance(check, dict)
                and check.get("lane_id") == lane.lane_id
                and check.get("output_path") == lane.log_rel.as_posix()
                and check.get("output_sha256")
                == KNOWN_LOG_SHA256_BY_REL.get(lane.log_rel),
                f"known log verification binding[{index}] differs",
            )
    observed_at = verification.get("observed_at")
    _require(type(observed_at) is str, "verification observed_at differs")
    return ReviewContext(
        pre_review_outputs=dict(outputs),
        result_hashes=result_hashes,
        consumer_bindings=deepcopy(consumers),
        review_subject_sha256=trace.bytes_sha256(
            outputs[trace.REVIEW_SUBJECT_REL].encode()
        ),
        verification_observed_at=observed_at,
    )


def build_attestation(context: ReviewContext, reviewed_at: str) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "evidence_type": "INTERNAL_REVIEW_ATTESTATION",
        "goal_id": trace.GOAL_ID,
        "reviewer_id": trace.EXPECTED_REVIEWER_ID,
        "reviewer_task": trace.EXPECTED_REVIEWER_TASK,
        "reviewed_at": reviewed_at,
        "review_subject_sha256": context.review_subject_sha256,
        "reviewed_result_sha256_by_kind": deepcopy(context.result_hashes),
        "reviewed_consumer_bindings": deepcopy(context.consumer_bindings),
        "decision": "APPROVED",
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "minor_open": 0,
        },
        "review_boundary": trace.expected_review_boundary(),
    }


def validate_attestation_bytes(
    raw: bytes,
    context: ReviewContext,
    *,
    now: datetime | None = None,
    label: str = "review attestation",
) -> StrictAttestation:
    value = trace.json_from_bytes(raw, label)
    _require(tuple(value) == ATTESTATION_FIELDS, f"{label} exact fields differ")
    reviewed_at = _parse_canonical_review_time(
        value.get("reviewed_at"),
        label=f"{label} reviewed_at",
    )
    verification_at = trace.parse_time(
        context.verification_observed_at,
        "verification observed_at",
    )
    observed_now = now or _now_kst()
    _require(
        observed_now.tzinfo is not None and observed_now.utcoffset() is not None,
        "gate clock lacks timezone",
    )
    current = observed_now.astimezone(KST)
    _require(reviewed_at >= verification_at, f"{label} predates verification")
    _require(reviewed_at <= current, f"{label} is future-dated")

    expected = build_attestation(context, value["reviewed_at"])
    _strict_equal(value, expected, label=label)
    _require(
        tuple(value["reviewed_result_sha256_by_kind"]) == RESULT_HASH_FIELDS,
        f"{label} result hash fields differ",
    )
    _require(
        tuple(value["findings"]) == FINDING_FIELDS,
        f"{label} finding fields differ",
    )
    _require(
        all(type(value["findings"][field]) is int for field in FINDING_FIELDS),
        f"{label} finding types differ",
    )
    _require(
        all(
            tuple(binding) == CONSUMER_BINDING_FIELDS
            for binding in value["reviewed_consumer_bindings"]
        ),
        f"{label} consumer binding fields differ",
    )
    _require(raw == trace.json_text(value).encode(), f"{label} JSON is noncanonical")
    return StrictAttestation(raw=raw, value=value, sha256=trace.bytes_sha256(raw))


def _read_descriptor(descriptor: int) -> bytes:
    before = os.fstat(descriptor)
    chunks: list[bytes] = []
    offset = 0
    while chunk := os.pread(descriptor, 1024 * 1024, offset):
        chunks.append(chunk)
        offset += len(chunk)
    _require(
        trace._file_identity(os.fstat(descriptor))
        == trace._file_identity(before),
        "open file changed while reading",
    )
    return b"".join(chunks)


def _snapshot_openat_sealed(
    directory_fd: int,
    name: str,
    *,
    label: str,
) -> tuple[bytes, tuple[int, ...]]:
    before = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    trace._validate_regular_authority(
        before,
        label,
        require_single_link=True,
        required_mode=0o600,
        required_uid=os.getuid(),
    )
    descriptor = os.open(
        name,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | trace._required_os_flag("O_NOFOLLOW"),
        dir_fd=directory_fd,
    )
    primary: BaseException | None = None
    try:
        opened = os.fstat(descriptor)
        _require(
            trace._file_identity(opened) == trace._file_identity(before),
            f"file FD/path identity differs: {label}",
        )
        raw = _read_descriptor(descriptor)
        after = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        _require(
            trace._file_identity(after) == trace._file_identity(before),
            f"file path changed while reading: {label}",
        )
        return raw, trace._file_identity(before)
    except BaseException as exc:
        primary = exc
        raise
    finally:
        _close_descriptor(descriptor, primary=primary)


def _snapshot_openat(directory_fd: int, name: str, *, label: str) -> bytes:
    return _snapshot_openat_sealed(directory_fd, name, label=label)[0]


@contextmanager
def _result_lock(root: Path) -> Iterator[tuple[int, tuple[int, ...]]]:
    directory = root / trace.RESULT_DIR_REL
    before = directory.lstat()
    _require(
        stat.S_ISDIR(before.st_mode)
        and not stat.S_ISLNK(before.st_mode)
        and before.st_uid == os.getuid()
        and stat.S_IMODE(before.st_mode) == 0o700,
        "review result directory authority differs",
    )
    descriptor = os.open(
        directory,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | trace._required_os_flag("O_DIRECTORY")
        | trace._required_os_flag("O_NOFOLLOW"),
    )
    primary: BaseException | None = None
    try:
        identity = _directory_identity(os.fstat(descriptor))
        _require(
            identity == _directory_identity(before),
            "review result directory FD/path identity differs",
        )
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        _require(
            _directory_identity(directory.lstat()) == identity,
            "review result directory changed before lock",
        )
        yield descriptor, identity
    except BaseException as exc:
        primary = exc
        raise
    finally:
        _close_descriptor(descriptor, primary=primary)


def _write_descriptor(descriptor: int, raw: bytes, *, label: str) -> None:
    position = 0
    while position < len(raw):
        written = os.write(descriptor, raw[position:])
        _require(written > 0, f"short unnamed write: {label}")
        position += written
    os.fsync(descriptor)


def _link_unnamed_noreplace(
    descriptor: int,
    directory_fd: int,
    name: str,
) -> bool:
    libc = ctypes.CDLL(None, use_errno=True)
    linkat = getattr(libc, "linkat", None)
    _require(linkat is not None, "linkat(AT_EMPTY_PATH) is unavailable")
    linkat.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
    ]
    linkat.restype = ctypes.c_int
    result = linkat(
        descriptor,
        b"",
        directory_fd,
        os.fsencode(name),
        AT_EMPTY_PATH,
    )
    if result == 0:
        return True
    error = ctypes.get_errno()
    if error == errno.EEXIST:
        return False
    raise OSError(error, os.strerror(error), name)


def _publish_unnamed_add_only(
    directory_fd: int,
    name: str,
    raw: bytes,
    *,
    label: str,
) -> tuple[int, ...]:
    _require(name not in {"", ".", ".."} and "/" not in name, f"unsafe name: {name}")
    try:
        existing = _snapshot_openat(directory_fd, name, label=label)
    except FileNotFoundError:
        existing = None
    if existing is not None:
        _require(existing == raw, f"existing add-only file differs: {label}")
        os.fsync(directory_fd)
        recovered, identity = _snapshot_openat_sealed(
            directory_fd,
            name,
            label=label,
        )
        _require(recovered == raw, f"existing add-only file changed: {label}")
        return identity

    flags = (
        os.O_RDWR
        | getattr(os, "O_CLOEXEC", 0)
        | trace._required_os_flag("O_TMPFILE")
    )
    descriptor = os.open(".", flags, 0o600, dir_fd=directory_fd)
    primary: BaseException | None = None
    try:
        _write_descriptor(descriptor, raw, label=label)
        staged = os.fstat(descriptor)
        _require(
            stat.S_ISREG(staged.st_mode)
            and stat.S_IMODE(staged.st_mode) == 0o600
            and staged.st_uid == os.getuid()
            and staged.st_nlink == 0,
            f"unnamed stage authority differs: {label}",
        )
        linked = _link_unnamed_noreplace(descriptor, directory_fd, name)
        if not linked:
            os.fsync(directory_fd)
            recovered, identity = _snapshot_openat_sealed(
                directory_fd,
                name,
                label=label,
            )
            _require(recovered == raw, f"existing add-only file differs: {label}")
            return identity
        os.fsync(directory_fd)
        published = os.fstat(descriptor)
        _require(
            published.st_nlink == 1
            and trace._file_identity(
                os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            )
            == trace._file_identity(published),
            f"published file identity differs: {label}",
        )
        _require(
            _read_descriptor(descriptor) == raw,
            f"published file bytes differ: {label}",
        )
        return trace._file_identity(published)
    except BaseException as exc:
        primary = exc
        raise
    finally:
        _close_descriptor(descriptor, primary=primary)


def _open_attestation_lease(
    root: Path,
    result_directory_fd: int,
    result_directory_identity: tuple[int, ...],
    context: ReviewContext,
    *,
    now: datetime | None,
    label: str = "review attestation",
    expected_identity: tuple[int, ...] | None = None,
) -> _AttestationLease:
    name = trace.REVIEW_ATTESTATION_REL.name
    descriptor = os.open(
        name,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | trace._required_os_flag("O_NOFOLLOW"),
        dir_fd=result_directory_fd,
    )
    try:
        info = os.fstat(descriptor)
        trace._validate_regular_authority(
            info,
            label,
            require_single_link=True,
            required_mode=0o600,
            required_uid=os.getuid(),
        )
        entry = os.stat(name, dir_fd=result_directory_fd, follow_symlinks=False)
        identity = trace._file_identity(info)
        _require(
            trace._file_identity(entry) == identity,
            f"{label} FD/path identity differs",
        )
        if expected_identity is not None:
            _require(
                identity == expected_identity,
                f"{label} published identity changed",
            )
        attestation = validate_attestation_bytes(
            _read_descriptor(descriptor),
            context,
            now=now,
            label=label,
        )
        lease = _AttestationLease(
            root=root,
            result_directory_fd=result_directory_fd,
            result_directory_identity=result_directory_identity,
            descriptor=descriptor,
            identity=identity,
            attestation=attestation,
        )
        lease.check()
        return lease
    except BaseException as exc:
        _close_descriptor(descriptor, primary=exc)
        raise


def load_strict_attestation(
    root: Path = ROOT,
    *,
    pre_review_kwargs: Mapping[str, Any] | None = None,
    now: datetime | None = None,
) -> StrictAttestation:
    root = root.resolve(strict=True)
    with _result_lock(root) as (directory_fd, directory_identity):
        _result_inventory(
            root,
            allow_transaction=False,
            require_attestation=True,
            allow_post=True,
        )
        review_context = prepare_review_context(
            root,
            pre_review_kwargs=pre_review_kwargs,
        )
        lease = _open_attestation_lease(
            root,
            directory_fd,
            directory_identity,
            review_context,
            now=now,
        )
        with lease:
            lease.check()
            return lease.attestation


def write_attestation(
    root: Path = ROOT,
    *,
    pre_review_kwargs: Mapping[str, Any] | None = None,
    clock: Callable[[], datetime] = _now_kst,
    publish_hook: Callable[[], None] | None = None,
) -> StrictAttestation:
    root = root.resolve(strict=True)
    with _result_lock(root) as (directory_fd, directory_identity):
        initial_inventory, auxiliary_inventory = _result_inventory(
            root,
            allow_transaction=False,
            require_attestation=False,
            allow_post=True,
        )
        attestation_name = trace.REVIEW_ATTESTATION_REL.relative_to(
            trace.RESULT_DIR_REL
        ).as_posix()
        expected_final_inventory = initial_inventory | {attestation_name}
        context = prepare_review_context(root, pre_review_kwargs=pre_review_kwargs)
        observed_now = clock()
        _require(
            observed_now.tzinfo is not None
            and observed_now.utcoffset() is not None,
            "gate clock lacks timezone",
        )
        current = observed_now.astimezone(KST)
        try:
            raw = _snapshot_openat(
                directory_fd,
                trace.REVIEW_ATTESTATION_REL.name,
                label="review attestation",
            )
        except FileNotFoundError:
            reviewed_at = current.replace(microsecond=0).isoformat()
            raw = trace.json_text(build_attestation(context, reviewed_at)).encode()
        attestation = validate_attestation_bytes(raw, context, now=current)
        published_identity = _publish_unnamed_add_only(
            directory_fd,
            trace.REVIEW_ATTESTATION_REL.name,
            attestation.raw,
            label="review attestation",
        )
        if publish_hook is not None:
            publish_hook()
        # A second full snapshot binds the published bytes to producers as they
        # exist after the add-only link, rather than trusting cached context.
        refreshed_context = prepare_review_context(
            root,
            pre_review_kwargs=pre_review_kwargs,
        )
        lease = _open_attestation_lease(
            root,
            directory_fd,
            directory_identity,
            refreshed_context,
            now=current,
            expected_identity=published_identity,
        )
        with lease:
            _require(
                lease.attestation.raw == attestation.raw,
                "review attestation changed during add-only publication",
            )
            lease.check()
            final_inventory, final_auxiliary = _result_inventory(
                root,
                allow_transaction=False,
                require_attestation=True,
                allow_post=True,
            )
            _require(
                final_inventory == expected_final_inventory
                and final_auxiliary == auxiliary_inventory,
                "review attestation final exact inventory differs",
            )
            lease.check()
            return lease.attestation


def _expected_post_outputs(
    context: ReviewContext,
    attestation: StrictAttestation,
) -> dict[Path, str]:
    reviewed_at = attestation.value["reviewed_at"]
    review = {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-"
            "INTERNAL-REVIEW-20260802-001"
        ),
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": trace.GOAL_ID,
        "status": "PASS",
        "reviewer_id": trace.EXPECTED_REVIEWER_ID,
        "reviewer_task": trace.EXPECTED_REVIEWER_TASK,
        "reviewed_at": reviewed_at,
        "review_subject_sha256": context.review_subject_sha256,
        "reviewed_result_sha256_by_kind": deepcopy(context.result_hashes),
        "reviewed_consumer_bindings": deepcopy(context.consumer_bindings),
        "attestation_provenance": {
            "path": trace.REVIEW_ATTESTATION_REL.as_posix(),
            "sha256": attestation.sha256,
        },
        "findings": deepcopy(attestation.value["findings"]),
        "review_boundary": trace.expected_review_boundary(),
    }
    review_text = trace.json_text(review)
    source = {
        **context.pre_review_outputs,
        trace.INDEPENDENT_REVIEW_REL: review_text,
    }
    manifest = [
        {
            "path": relative.as_posix(),
            "sha256": trace.bytes_sha256(source[relative].encode()),
        }
        for relative in (*trace.PRE_REVIEW_OUTPUTS, trace.INDEPENDENT_REVIEW_REL)
    ]
    implementation = trace.json_from_bytes(
        context.pre_review_outputs[trace.IMPLEMENTATION_REL].encode(),
        "implementation",
    )
    verification = trace.json_from_bytes(
        context.pre_review_outputs[trace.VERIFICATION_REL].encode(),
        "verification",
    )
    authority_event = implementation["execution_session_event"]
    receipt = {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-"
            "WORK-ITEM-COMPLETION-20260802-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "target_goal_id": trace.GOAL_ID,
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_content_sha256": trace.EXPECTED_GOAL_SHA256,
        "work_item_id": trace.EXPECTED_WORK_ITEM_ID,
        "source_policy_ids": ["FP-048"],
        "gap_ids": ["GAP-057"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": trace.EXPECTED_EXECUTION_EVENT_SHA256,
        "execution_session_event": {
            "sequence": authority_event["sequence"],
            "event_id": authority_event["event_id"],
            "event_type": authority_event["event_type"],
            "event_sha256": authority_event["event_sha256"],
        },
        "implementation_start_gate_binding": deepcopy(
            implementation["implementation_start_gate_binding"]
        ),
        "execution_window": {
            "started_at": implementation["observed_at"],
            "ended_at": verification["observed_at"],
        },
        "completed_at": reviewed_at,
        "executor": {
            "id": trace.EXECUTOR_ID,
            "task": trace.EXECUTOR_TASK,
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": trace.EXPECTED_REVIEWER_ID,
            "task": trace.EXPECTED_REVIEWER_TASK,
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "authority": "INTERNAL_REPOSITORY_CONTROL",
            "decision": "APPROVED",
            "decided_at": reviewed_at,
        },
        "reviewer_provenance": {
            "path": trace.INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": trace.bytes_sha256(review_text.encode()),
        },
        "result_evidence": [
            {
                "kind": kind,
                "path": relative.as_posix(),
                "sha256": trace.bytes_sha256(
                    context.pre_review_outputs[relative].encode()
                ),
            }
            for kind, relative in (
                ("IMPLEMENTATION_RECORD", trace.IMPLEMENTATION_REL),
                ("VERIFICATION_RESULT", trace.VERIFICATION_REL),
                ("SUCCESSOR_TRACE", trace.SUCCESSOR_REL),
            )
        ],
        "downstream_consumer_bindings": deepcopy(context.consumer_bindings),
        "output_evidence_manifest": manifest,
        "output_evidence_manifest_sha256": trace.object_sha256(manifest),
        "self_excluded_from_output_manifest": True,
        "completion_boundary": trace.completion_boundary(),
        "generated_at": reviewed_at,
    }
    return {
        trace.INDEPENDENT_REVIEW_REL: review_text,
        trace.COMPLETION_RECEIPT_REL: trace.json_text(receipt),
    }


def _validate_post_outputs(
    outputs: Mapping[Path, str],
    context: ReviewContext,
    attestation: StrictAttestation,
) -> None:
    _strict_equal(
        dict(outputs),
        _expected_post_outputs(context, attestation),
        label="post-review outputs",
    )


def _preflight_post_destinations(
    result_directory_fd: int,
    outputs: Mapping[Path, str],
    lease: _AttestationLease,
) -> bool:
    _require(
        tuple(outputs) == trace.POST_REVIEW_OUTPUTS,
        "post-review output set differs before publication",
    )
    all_exact = True
    for relative, content in outputs.items():
        _require(
            relative.parent == trace.RESULT_DIR_REL,
            f"post-review output parent differs: {relative}",
        )
        lease.check()
        try:
            existing = _snapshot_openat(
                result_directory_fd,
                relative.name,
                label=f"existing post-review output {relative.name}",
            )
        except FileNotFoundError:
            all_exact = False
            continue
        _require(
            existing == content.encode(),
            f"existing post-review output differs: {relative}",
        )
    lease.check()
    return all_exact


def _canonical_result_inventory(
    *,
    include_attestation: bool,
    include_post: bool,
) -> set[str]:
    relatives = [
        *trace.PRE_REVIEW_OUTPUTS,
    ]
    if include_attestation:
        relatives.append(trace.REVIEW_ATTESTATION_REL)
    if include_post:
        relatives.extend(trace.POST_REVIEW_OUTPUTS)
    result: set[str] = set()
    for relative in relatives:
        local = relative.relative_to(trace.RESULT_DIR_REL)
        result.add(local.as_posix())
        result.update(
            parent.as_posix()
            for parent in local.parents
            if parent != Path(".")
        )
    return result


def _inventory_for_files(relatives: Sequence[Path]) -> set[str]:
    inventory: set[str] = set()
    for relative in relatives:
        local = relative.relative_to(trace.RESULT_DIR_REL)
        inventory.add(local.as_posix())
        inventory.update(
            parent.as_posix()
            for parent in local.parents
            if parent != Path(".")
        )
    return inventory


def _snapshot_known_file(
    root: Path,
    relative: Path,
    *,
    expected_sha256: str,
    expected_byte_count: int | None = None,
) -> bytes:
    raw = trace.snapshot_file(
        root,
        relative,
        require_single_link=True,
        required_mode=0o600,
        required_uid=os.getuid(),
    )
    _require(
        trace.bytes_sha256(raw) == expected_sha256,
        f"known auxiliary SHA-256 differs: {relative}",
    )
    if expected_byte_count is not None:
        _require(
            len(raw) == expected_byte_count,
            f"known auxiliary byte count differs: {relative}",
        )
    return raw


def _retirement_file_contract(
    root: Path,
    inventory: set[str],
) -> dict[Path, tuple[str, int]]:
    local_root = RETIREMENT_DIR_REL.relative_to(trace.RESULT_DIR_REL).as_posix()
    observed = {
        entry
        for entry in inventory
        if entry == local_root or entry.startswith(f"{local_root}/")
    }
    if not observed:
        return {}
    local_manifest = RETIREMENT_MANIFEST_REL.relative_to(
        trace.RESULT_DIR_REL
    ).as_posix()
    _require(
        local_manifest in observed,
        "retirement auxiliary lacks pinned manifest",
    )
    manifest_raw = _snapshot_known_file(
        root,
        RETIREMENT_MANIFEST_REL,
        expected_sha256=RETIREMENT_MANIFEST_SHA256,
    )
    manifest = trace.json_from_bytes(manifest_raw, "retirement manifest")
    _require(
        manifest.get("schema_version")
        == "walksafe.fp048-r023-provisional-retirement.v1"
        and manifest.get("document_id")
        == "WS-FP048-R023-PROVISIONAL-RETIREMENT-20260803-001",
        "retirement manifest identity differs",
    )
    collections = (
        ("archived_producer_sources", "producer-sources", 2),
        ("archived_outputs", "canonical-outputs", 4),
    )
    contract: dict[Path, tuple[str, int]] = {
        RETIREMENT_MANIFEST_REL: (
            RETIREMENT_MANIFEST_SHA256,
            len(manifest_raw),
        )
    }
    for field, expected_parent, expected_count in collections:
        rows = manifest.get(field)
        _require(
            isinstance(rows, list) and len(rows) == expected_count,
            f"retirement manifest {field} count differs",
        )
        for row in rows:
            _require(isinstance(row, dict), f"retirement manifest {field} row differs")
            archive_path = row.get("archive_path")
            sha256 = row.get("sha256")
            byte_count = row.get("byte_count")
            _require(
                type(archive_path) is str
                and Path(archive_path).parent == Path(expected_parent)
                and type(sha256) is str
                and trace.SHA256_RE.fullmatch(sha256) is not None
                and type(byte_count) is int
                and byte_count >= 0
                and row.get("mode") == "0600"
                and row.get("link_count") == 1,
                f"retirement manifest {field} row authority differs",
            )
            relative = RETIREMENT_DIR_REL / archive_path
            _require(
                relative not in contract,
                f"duplicate retirement archive path: {relative}",
            )
            contract[relative] = (sha256, byte_count)
    _require(len(contract) == 7, "retirement auxiliary file count differs")
    return contract


def _validate_known_auxiliary_inventory(
    root: Path,
    inventory: set[str],
) -> set[str]:
    auxiliary: set[str] = set()
    log_inventory = _inventory_for_files(tuple(KNOWN_LOG_SHA256_BY_REL))
    observed_logs = {
        entry
        for entry in inventory
        if entry == "logs" or entry.startswith("logs/")
    }
    _require(
        observed_logs == log_inventory,
        "known log auxiliary exact inventory differs",
    )
    for relative, digest in KNOWN_LOG_SHA256_BY_REL.items():
        _snapshot_known_file(root, relative, expected_sha256=digest)
    auxiliary.update(log_inventory)

    retirement_contract = _retirement_file_contract(root, inventory)
    _require(retirement_contract, "retirement auxiliary is missing")
    retirement_inventory = _inventory_for_files(tuple(retirement_contract))
    local_root = RETIREMENT_DIR_REL.relative_to(
        trace.RESULT_DIR_REL
    ).as_posix()
    observed_retirement = {
        entry
        for entry in inventory
        if entry == local_root or entry.startswith(f"{local_root}/")
    }
    _require(
        observed_retirement == retirement_inventory,
        "retirement auxiliary exact inventory differs",
    )
    for relative, (digest, byte_count) in retirement_contract.items():
        if relative != RETIREMENT_MANIFEST_REL:
            _snapshot_known_file(
                root,
                relative,
                expected_sha256=digest,
                expected_byte_count=byte_count,
            )
    auxiliary.update(retirement_inventory)
    return auxiliary


def _post_transaction_inventory() -> set[str]:
    transaction_name = POST_TRANSACTION_REL.name
    return {
        transaction_name,
        f"{transaction_name}/manifest.json",
        *(
            f"{transaction_name}/{index:02d}.stage"
            for index in range(len(trace.POST_REVIEW_OUTPUTS))
        ),
    }


def _result_inventory(
    root: Path,
    *,
    allow_transaction: bool,
    require_attestation: bool,
    allow_post: bool,
) -> tuple[set[str], set[str]]:
    result = root / trace.RESULT_DIR_REL
    transaction_name = POST_TRANSACTION_REL.name
    inventory: set[str] = set()
    for current, directory_names, file_names in os.walk(result, followlinks=False):
        current_path = Path(current)
        for name in (*directory_names, *file_names):
            path = current_path / name
            relative = path.relative_to(result)
            info = path.lstat()
            if stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode):
                _require(
                    stat.S_IMODE(info.st_mode) == 0o700
                    and info.st_uid == os.getuid(),
                    f"review result directory authority differs: {relative}",
                )
            else:
                _require(
                    stat.S_ISREG(info.st_mode)
                    and not stat.S_ISLNK(info.st_mode)
                    and stat.S_IMODE(info.st_mode) == 0o600
                    and info.st_uid == os.getuid()
                    and info.st_nlink == 1,
                    f"review result file authority differs: {relative}",
                )
            if any(part.startswith(".") for part in relative.parts):
                _require(
                    allow_transaction and relative.parts[0] == transaction_name,
                    f"unexpected hidden review result entry: {relative}",
                )
            inventory.add(relative.as_posix())
    canonical_initial = _canonical_result_inventory(
        include_attestation=require_attestation,
        include_post=False,
    )
    allowed = _canonical_result_inventory(
        include_attestation=True,
        include_post=allow_post,
    )
    auxiliary = _validate_known_auxiliary_inventory(root, inventory)
    allowed.update(auxiliary)
    if allow_transaction:
        allowed.update(_post_transaction_inventory())
    unexpected = inventory - allowed
    _require(
        not unexpected,
        f"unexpected review result inventory entries: {sorted(unexpected)}",
    )
    missing = canonical_initial - inventory
    _require(
        not missing,
        f"required review result inventory entries missing: {sorted(missing)}",
    )
    post_inventory = _inventory_for_files(trace.POST_REVIEW_OUTPUTS)
    observed_post = inventory & post_inventory
    if observed_post:
        attestation_name = trace.REVIEW_ATTESTATION_REL.relative_to(
            trace.RESULT_DIR_REL
        ).as_posix()
        _require(
            attestation_name in inventory,
            "post-review output exists without review attestation",
        )
    post_files = {
        relative.relative_to(trace.RESULT_DIR_REL).as_posix()
        for relative in trace.POST_REVIEW_OUTPUTS
    }
    if observed_post and observed_post != post_files:
        required_transaction = {
            transaction_name,
            f"{transaction_name}/manifest.json",
        }
        for index, relative in enumerate(trace.POST_REVIEW_OUTPUTS):
            local = relative.relative_to(trace.RESULT_DIR_REL).as_posix()
            if local not in observed_post:
                required_transaction.add(
                    f"{transaction_name}/{index:02d}.stage"
                )
        _require(
            allow_transaction and required_transaction <= inventory,
            "partial post-review output lacks durable transaction evidence",
        )
    return inventory, auxiliary


def _expected_final_result_inventory(auxiliary: set[str]) -> set[str]:
    return (
        _canonical_result_inventory(
            include_attestation=True,
            include_post=True,
        )
        | auxiliary
    )


def _open_or_create_post_transaction(
    result_directory_fd: int,
) -> tuple[int, tuple[int, ...]]:
    name = POST_TRANSACTION_REL.name
    try:
        os.mkdir(name, 0o700, dir_fd=result_directory_fd)
    except FileExistsError:
        pass
    else:
        os.fsync(result_directory_fd)
    before = os.stat(name, dir_fd=result_directory_fd, follow_symlinks=False)
    _require(
        stat.S_ISDIR(before.st_mode)
        and not stat.S_ISLNK(before.st_mode)
        and before.st_uid == os.getuid()
        and stat.S_IMODE(before.st_mode) == 0o700,
        "post-review transaction authority differs",
    )
    descriptor = os.open(
        name,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | trace._required_os_flag("O_DIRECTORY")
        | trace._required_os_flag("O_NOFOLLOW"),
        dir_fd=result_directory_fd,
    )
    try:
        identity = _directory_identity(os.fstat(descriptor))
        _require(
            identity == _directory_identity(before),
            "post-review transaction FD/path identity differs",
        )
    except BaseException as exc:
        _close_descriptor(descriptor, primary=exc)
        raise
    return descriptor, identity


def _prepare_post_transaction_atomic(
    result_directory_fd: int,
    outputs: Mapping[Path, str],
    lease: _AttestationLease,
) -> _PostTransactionLease:
    lease.check()
    manifest, manifest_raw = trace._forward_transaction_manifest(
        outputs,
        "post-review",
    )
    expected_files = {"manifest.json": manifest_raw}
    for row, (relative, content) in zip(
        manifest["outputs"],
        outputs.items(),
        strict=True,
    ):
        lease.check()
        raw = content.encode()
        _require(
            row["path"] == relative.as_posix()
            and row["byte_count"] == len(raw)
            and row["sha256"] == trace.bytes_sha256(raw),
            "post-review transaction row differs",
        )
        expected_files[row["stage_name"]] = raw
    transaction_fd, transaction_identity = _open_or_create_post_transaction(
        result_directory_fd
    )
    primary: BaseException | None = None
    transferred = False
    try:
        expected_names = set(expected_files)
        observed_names = set(os.listdir(transaction_fd))
        _require(
            observed_names <= expected_names,
            "post-review transaction contains foreign entries",
        )
        if observed_names & (expected_names - {"manifest.json"}):
            _require(
                "manifest.json" in observed_names,
                "post-review transaction stage lacks durable manifest",
            )
        for name in observed_names:
            _require(
                _snapshot_openat(
                    transaction_fd,
                    name,
                    label=f"post-review transaction file {name}",
                )
                == expected_files[name],
                f"post-review transaction file differs: {name}",
            )
        for name, raw in expected_files.items():
            lease.check()
            _publish_unnamed_add_only(
                transaction_fd,
                name,
                raw,
                label=f"post-review transaction file {name}",
            )
        _require(
            set(os.listdir(transaction_fd)) == expected_names,
            "post-review transaction exact inventory differs",
        )
        _require(
            _directory_identity(os.fstat(transaction_fd))
            == transaction_identity,
            "post-review transaction changed while staging",
        )
        current = os.stat(
            POST_TRANSACTION_REL.name,
            dir_fd=result_directory_fd,
            follow_symlinks=False,
        )
        _require(
            _directory_identity(current) == transaction_identity,
            "post-review transaction path changed while staging",
        )
        os.fsync(transaction_fd)
        lease.check()
        transaction_lease = _PostTransactionLease(
            result_directory_fd=result_directory_fd,
            descriptor=transaction_fd,
            identity=transaction_identity,
        )
        transaction_lease.check_path()
        transferred = True
        return transaction_lease
    except BaseException as exc:
        primary = exc
        raise
    finally:
        if not transferred:
            _close_descriptor(transaction_fd, primary=primary)


def _derive_strict_post_review_outputs(
    root: Path,
    lease: _AttestationLease,
    *,
    pre_review_kwargs: Mapping[str, Any] | None,
    now: datetime | None,
) -> dict[Path, str]:
    lease.check()
    outputs = _build_post_review_outputs_compatible(
        root=root,
        pre_review_kwargs=pre_review_kwargs,
    )
    lease.check()
    _validate_current_context_binding(
        root,
        outputs,
        lease,
        pre_review_kwargs=pre_review_kwargs,
        now=now,
        label="pre-publication review attestation",
    )
    return dict(outputs)


def _validate_current_context_binding(
    root: Path,
    outputs: Mapping[Path, str],
    lease: _AttestationLease,
    *,
    pre_review_kwargs: Mapping[str, Any] | None,
    now: datetime | None,
    label: str,
) -> None:
    # Rebuild at each boundary so producer/consumer drift cannot hide behind
    # the context used when the retained attestation descriptor was opened.
    refreshed_context = prepare_review_context(
        root,
        pre_review_kwargs=pre_review_kwargs,
    )
    refreshed_attestation = validate_attestation_bytes(
        lease.attestation.raw,
        refreshed_context,
        now=now,
        label=label,
    )
    _require(
        refreshed_attestation.sha256 == lease.attestation.sha256,
        "review attestation digest changed while deriving outputs",
    )
    _validate_post_outputs(outputs, refreshed_context, lease.attestation)
    lease.check()


def build_strict_post_review_outputs(
    root: Path = ROOT,
    *,
    pre_review_kwargs: Mapping[str, Any] | None = None,
    now: datetime | None = None,
) -> tuple[dict[Path, str], StrictAttestation]:
    root = root.resolve(strict=True)
    with _result_lock(root) as (directory_fd, directory_identity):
        _result_inventory(
            root,
            allow_transaction=False,
            require_attestation=True,
            allow_post=True,
        )
        context = prepare_review_context(root, pre_review_kwargs=pre_review_kwargs)
        lease = _open_attestation_lease(
            root,
            directory_fd,
            directory_identity,
            context,
            now=now,
        )
        with lease:
            outputs = _derive_strict_post_review_outputs(
                root,
                lease,
                pre_review_kwargs=pre_review_kwargs,
                now=now,
            )
            return outputs, lease.attestation


def write_or_check_post_review(
    root: Path = ROOT,
    *,
    write: bool,
    pre_review_kwargs: Mapping[str, Any] | None = None,
    now: datetime | None = None,
) -> dict[Path, str]:
    root = root.resolve(strict=True)
    with _result_lock(root) as (directory_fd, directory_identity):
        _, auxiliary_inventory = _result_inventory(
            root,
            allow_transaction=True,
            require_attestation=True,
            allow_post=True,
        )
        expected_final_inventory = _expected_final_result_inventory(
            auxiliary_inventory
        )
        context = prepare_review_context(root, pre_review_kwargs=pre_review_kwargs)
        lease = _open_attestation_lease(
            root,
            directory_fd,
            directory_identity,
            context,
            now=now,
        )
        with lease:
            outputs = _derive_strict_post_review_outputs(
                root,
                lease,
                pre_review_kwargs=pre_review_kwargs,
                now=now,
            )
            guarded_outputs = _AttestationBoundOutputs(outputs, lease)
            all_outputs_exact = _preflight_post_destinations(
                directory_fd,
                guarded_outputs,
                lease,
            )
            transaction_present = os.path.lexists(root / POST_TRANSACTION_REL)
            perform_write = write and not (
                all_outputs_exact and not transaction_present
            )
            transaction_lease: _PostTransactionLease | None = None
            primary: BaseException | None = None
            try:
                if perform_write:
                    transaction_lease = _prepare_post_transaction_atomic(
                        directory_fd,
                        guarded_outputs,
                        lease,
                    )
                    guarded_outputs = _AttestationBoundOutputs(
                        outputs,
                        lease,
                        transaction_lease,
                    )
                trace.write_or_check_outputs(
                    root,
                    guarded_outputs,
                    write=perform_write,
                    publish_hook=(
                        (lambda _index, _relative: guarded_outputs.check())
                        if perform_write
                        else None
                    ),
                )
                if write and not perform_write:
                    lease.check()
                    os.fsync(directory_fd)
                    lease.check()
                if transaction_lease is not None:
                    transaction_lease.check_removed()
                lease.check()
                _require(
                    not os.path.lexists(root / POST_TRANSACTION_REL),
                    "post-review transaction residue remains",
                )
                trace.write_or_check_outputs(root, outputs, write=False)
                _validate_current_context_binding(
                    root,
                    outputs,
                    lease,
                    pre_review_kwargs=pre_review_kwargs,
                    now=now,
                    label="post-publication review attestation",
                )
                _require(
                    _result_inventory(
                        root,
                        allow_transaction=False,
                        require_attestation=True,
                        allow_post=True,
                    )[0]
                    == expected_final_inventory,
                    "final review result exact inventory differs",
                )
                lease.check()
                return outputs
            except BaseException as exc:
                primary = exc
                raise
            finally:
                if transaction_lease is not None:
                    transaction_lease.close(primary=primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-attestation", action="store_true")
    mode.add_argument("--check-attestation", action="store_true")
    mode.add_argument("--write-post-review", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.write_attestation:
            attestation = write_attestation(args.root)
            mode = "WRITE_ATTESTATION"
            detail = f"attestation_sha256={attestation.sha256}"
        elif args.check_attestation:
            attestation = load_strict_attestation(args.root)
            mode = "CHECK_ATTESTATION"
            detail = f"attestation_sha256={attestation.sha256}"
        else:
            outputs = write_or_check_post_review(
                args.root,
                write=args.write_post_review,
            )
            mode = "WRITE_POST_REVIEW" if args.write_post_review else "CHECK_POST_REVIEW"
            detail = f"outputs={len(outputs)}"
    except (GateError, trace.BuildError, OSError, TypeError, ValueError) as exc:
        print(f"FP-048 strict review gate: FAIL: {exc}")
        return 1
    print(f"FP-048 strict review gate: PASS mode={mode} {detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
