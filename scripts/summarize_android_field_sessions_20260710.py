#!/usr/bin/env python3
"""Summarize legacy plaintext Android field-session fixtures for history only.

Current Android field logs are AEAD envelopes. This host tool detects those envelopes,
does not decrypt or export them, and returns a nonzero unavailable result.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import errno
import hashlib
import json
import math
import os
import re
import secrets
import stat
import statistics
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


FORBIDDEN_RECORD_KEYS = {
    "latitude",
    "longitude",
    "lat",
    "lng",
    "reporter_user_id",
    "destination",
    "search_query",
    "recognized_text",
    "image_bytes",
    "image_base64",
    "audio_bytes",
    "access_token",
    "refresh_token",
    "id_token",
    "authorization",
    "cookie",
    "password",
    "secret",
    "api_key",
    "client_secret",
    "private_key",
    "credential",
}
STRICT_MODE_ARCORE_METRIC = "arcore-metric"
STRICT_MODE_CAMERA_NON_METRIC = "camera-non-metric"
STRICT_MODES = (STRICT_MODE_ARCORE_METRIC, STRICT_MODE_CAMERA_NON_METRIC)
CAMERA_NON_METRIC_DIRECTIONS = {"LEFT", "CENTER", "RIGHT"}
CAMERA_NON_METRIC_FRAME_STATE = "detector_succeeded"
CAMERA_NON_METRIC_SAMPLE_TIERS = {"CAMERA_IMU_NON_METRIC", "TMAP_ONLY"}
CAMERA_NON_METRIC_FIELD_MIN_SAMPLE_COUNT = 60
CAMERA_NON_METRIC_FIELD_MIN_SAMPLE_SPAN_MS = 900_000
CAMERA_NON_METRIC_FIELD_MIN_SAMPLE_GAP_MS = 1_000
CAMERA_NON_METRIC_FIELD_MAX_SAMPLE_GAP_MS = 30_000
FIELD_SESSION_SCHEMA_VERSION = "android.field_session.v1"
FIELD_RECORD_SCHEMA_VERSION = "android.field_record.v1"
FIELD_SESSION_STATUS_ACTIVE = "active"
FIELD_SESSION_STATUS_COMPLETED = "completed"
JAVA_LONG_MAX = (1 << 63) - 1
JAVA_INT_MAX = (1 << 31) - 1
ARCORE_SUPPORTED_STATES = {
    "SUPPORTED_INSTALLED",
    "SUPPORTED_APK_TOO_OLD",
    "SUPPORTED_NOT_INSTALLED",
}
ARCORE_UNSUPPORTED_REASON = "arcore_availability_unsupported"
ARCORE_DEPTH_UNSUPPORTED_REASON = "arcore_depth_unsupported"
ARCORE_SESSION_INCOMPATIBLE_REASON = "arcore_session_incompatible"
DEBUG_FORCED_REASON = "debug_forced_supported"
EVIDENCE_SCOPE_ARCORE_METRIC = "ARCORE_METRIC"
EVIDENCE_SCOPE_ARCORE_UNSUPPORTED_FIELD = "ARCORE_UNSUPPORTED_FIELD"
EVIDENCE_SCOPE_FORCED_SUPPORTED_FUNCTIONAL = "FORCED_SUPPORTED_FUNCTIONAL"
EVIDENCE_SCOPE_DEPTH_UNSUPPORTED_FUNCTIONAL = "DEPTH_UNSUPPORTED_FUNCTIONAL"
EVIDENCE_SCOPE_CAMERA_NON_METRIC_UNKNOWN = "CAMERA_NON_METRIC_UNKNOWN"
EVIDENCE_SCOPE_CAMERA_NON_METRIC_MIXED = "CAMERA_NON_METRIC_MIXED"
EVIDENCE_SCOPE_NO_RUNTIME_EVIDENCE = "NO_RUNTIME_EVIDENCE"
EVIDENCE_SCOPE_NO_SESSION = "NO_SESSION"
SOURCE_COMMIT_PATTERN = re.compile(r"^[0-9a-fA-F]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
AEAD_ENVELOPE_VERSION = 1
AEAD_ENVELOPE_KEYS = frozenset({"envelope_version", "key_version", "iv", "ciphertext"})
AEAD_ENVELOPE_KEYS_CASEFOLD = frozenset(key.casefold() for key in AEAD_ENVELOPE_KEYS)
FIELD_RECORD_FILE_PATTERN = re.compile(r"^records-[0-9]{4}\.jsonl$")
INPUT_FORMAT_AEAD_ENVELOPE = "aead_envelope_v1"
INPUT_FORMAT_AEAD_ENVELOPE_MALFORMED = "encrypted_current_malformed"
INPUT_FORMAT_LEGACY_PLAINTEXT_HISTORY_ONLY = "legacy_plaintext_history_only"
ENCRYPTED_SUMMARY_UNAVAILABLE_REASON = "encrypted_field_log_summary_unavailable"
ENCRYPTED_CURRENT_MALFORMED_REASON = "encrypted_current_malformed"
LEGACY_HISTORY_ONLY_NOT_ELIGIBLE = "LEGACY_HISTORY_ONLY_NOT_ELIGIBLE"
MAX_MANIFEST_BYTES = 128 * 1024
MAX_RECORD_SEGMENT_BYTES = 5 * 1024 * 1024
MAX_MARKER_BYTES = 128 * 1024
MAX_TOTAL_INPUT_BYTES = 64 * 1024 * 1024
MAX_INPUT_MEMBERS = 5_000
MAX_SESSION_COUNT = 100
MAX_RECORD_LINE_BYTES = 512 * 1024
SAFE_SESSION_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,96}$")
ROOT_MARKER_FILES = frozenset(
    {
        "active_session.txt",
        "active_session_restore_blocked.txt",
        "account_deletion_blocked.txt",
        "raw_source_collection_blocked.txt",
        "field_log_crypto_blocked.txt",
        "field_key_purge_verified.txt",
    }
)
KNOWN_EVENT_NAMES = frozenset(
    {
        "arcore_session_started",
        "camera_non_metric_session_started",
        "camera_non_metric_frame_analyzed",
        "camera_non_metric_advisory_emitted",
        "camera_non_metric_inference_sample",
    }
)
MANIFEST_KEYS = frozenset(
    {
        "schema_version",
        "session_id",
        "status",
        "started_at_epoch_ms",
        "ended_at_epoch_ms",
        "record_segment_count",
        "record_count",
        "record_tail_sha256",
        "termination_reason",
        "device",
        "privacy",
        "provenance",
    }
)
EVENT_RECORD_KEYS = frozenset(
    {"schema_version", "record_type", "recorded_at_epoch_ms", "event_name", "fields"}
)
TELEMETRY_RECORD_KEYS = frozenset(
    {"schema_version", "record_type", "recorded_at_epoch_ms", "runtime", "depth_debug"}
)
SENSITIVE_KEY_SUFFIXES = (
    "_token",
    "_secret",
    "_password",
    "_credential",
    "_authorization",
)


class DuplicateJsonMember(ValueError):
    """Raised when JSON contains a duplicate member at any nesting depth."""


class UnsafeEvidencePath(ValueError):
    """Raised when evidence input/output uses a link or shared inode."""


class EvidenceInputError(ValueError):
    """Raised when bounded field evidence cannot be read as an exact layout."""


def percentile(values: list[float], ratio: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * ratio) - 1))
    return ordered[index]


def numeric_summary(values: Iterable[Any]) -> dict[str, float | int | None]:
    numbers = [float(value) for value in values if isinstance(value, (int, float)) and math.isfinite(float(value))]
    if not numbers:
        return {"count": 0, "min": None, "mean": None, "p50": None, "p95": None, "max": None}
    return {
        "count": len(numbers),
        "min": min(numbers),
        "mean": statistics.fmean(numbers),
        "p50": percentile(numbers, 0.50),
        "p95": percentile(numbers, 0.95),
        "max": max(numbers),
    }


def _exact_nonnegative_long(value: Any) -> bool:
    return type(value) is int and 0 <= value <= JAVA_LONG_MAX


def _exact_nonnegative_int(value: Any) -> bool:
    return type(value) is int and 0 <= value <= JAVA_INT_MAX


def _valid_camera_non_metric_sample(fields: Any) -> bool:
    if not isinstance(fields, dict):
        return False
    elapsed_realtime_ms = fields.get("elapsed_realtime_ms")
    inference_ms = fields.get("inference_ms")
    detection_count = fields.get("detection_count")
    if not _exact_nonnegative_long(elapsed_realtime_ms) or not _exact_nonnegative_long(inference_ms):
        return False
    if not _exact_nonnegative_int(detection_count):
        return False
    capability_tier = fields.get("capability_tier")
    if capability_tier not in CAMERA_NON_METRIC_SAMPLE_TIERS:
        return False
    for key in (
        "camera_permission_granted",
        "camera_fallback_running",
        "detector_available",
        "imu_fresh",
        "tmap_route_active",
    ):
        if not isinstance(fields.get(key), bool):
            return False
    camera_gate_allowed = all(
        fields[key]
        for key in (
            "camera_permission_granted",
            "camera_fallback_running",
            "detector_available",
            "imu_fresh",
        )
    )
    if (capability_tier == "CAMERA_IMU_NON_METRIC") != camera_gate_allowed:
        return False
    return (
        fields.get("metric") is False
        and fields.get("reports_allowed") is False
        and fields.get("state") == CAMERA_NON_METRIC_FRAME_STATE
    )


def _valid_recorded_at_contract(
    recorded_at_values: list[Any],
    session_status: Any,
    session_started_at: Any,
    session_ended_at: Any,
) -> bool:
    if session_status not in (FIELD_SESSION_STATUS_ACTIVE, FIELD_SESSION_STATUS_COMPLETED):
        return False
    if not _exact_nonnegative_long(session_started_at):
        return False
    if any(not _exact_nonnegative_long(value) for value in recorded_at_values):
        return False
    if any(current < previous for previous, current in zip(recorded_at_values, recorded_at_values[1:])):
        return False
    if any(value < session_started_at for value in recorded_at_values):
        return False
    if session_status == FIELD_SESSION_STATUS_ACTIVE:
        return True
    if not _exact_nonnegative_long(session_ended_at):
        return False
    if session_started_at > session_ended_at:
        return False
    return all(session_started_at <= value <= session_ended_at for value in recorded_at_values)


def camera_non_metric_sample_summary(
    samples: list[tuple[Any, Any]],
    *,
    recorded_at_values: list[Any] | None = None,
    session_status: Any = None,
    session_started_at: Any = None,
    session_ended_at: Any = None,
) -> dict[str, Any]:
    valid_samples = [fields for _, fields in samples if _valid_camera_non_metric_sample(fields)]
    elapsed_values = [
        fields.get("elapsed_realtime_ms") if isinstance(fields, dict) else None
        for _, fields in samples
    ]
    elapsed_contract_valid = all(_exact_nonnegative_long(value) for value in elapsed_values)
    elapsed_gaps = (
        [current - previous for previous, current in zip(elapsed_values, elapsed_values[1:])]
        if elapsed_contract_valid
        else []
    )
    elapsed_sequence_valid = elapsed_contract_valid and all(
        gap >= CAMERA_NON_METRIC_FIELD_MIN_SAMPLE_GAP_MS for gap in elapsed_gaps
    )
    sample_span_ms = (
        elapsed_values[-1] - elapsed_values[0]
        if elapsed_contract_valid and elapsed_values
        else 0
    )
    return {
        "sample_count": len(valid_samples),
        "sample_span_ms": sample_span_ms,
        "min_sample_gap_ms": min(elapsed_gaps) if elapsed_gaps else None,
        "max_sample_gap_ms": max(elapsed_gaps) if elapsed_gaps else None,
        "inference_ms": numeric_summary(fields["inference_ms"] for fields in valid_samples),
        "invalid_sample_count": len(samples) - len(valid_samples),
        "elapsed_contract_valid": elapsed_contract_valid,
        "elapsed_sequence_valid": elapsed_sequence_valid,
        "recorded_at_contract_valid": _valid_recorded_at_contract(
            recorded_at_values or [],
            session_status,
            session_started_at,
            session_ended_at,
        ),
    }


def counter_dict(counter: Counter[str]) -> dict[str, int]:
    return dict(sorted(counter.items(), key=lambda item: (-item[1], item[0])))


def iter_keys(value: Any, path: str = "") -> Iterable[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}" if path else key
            yield child_path
            yield from iter_keys(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from iter_keys(child, f"{path}[{index}]")


def is_sensitive_key(key: str) -> bool:
    normalized = key.lower()
    return normalized in FORBIDDEN_RECORD_KEYS or normalized.endswith(SENSITIVE_KEY_SUFFIXES)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, child in pairs:
        if key in value:
            raise DuplicateJsonMember(f"duplicate JSON member: {key}")
        value[key] = child
    return value


def strict_json_loads(text: str) -> Any:
    return json.loads(
        text,
        object_pairs_hook=_unique_object,
        parse_constant=lambda value: (_ for _ in ()).throw(
            ValueError(f"non-finite JSON value: {value}")
        ),
    )


def has_exact_aead_envelope_shape(value: Any) -> bool:
    return isinstance(value, dict) and frozenset(value) == AEAD_ENVELOPE_KEYS


def has_aead_envelope_signature(value: Any) -> bool:
    """Treat any current-envelope member as a fail-close encrypted signature."""

    if isinstance(value, dict):
        return any(key.casefold() in AEAD_ENVELOPE_KEYS_CASEFOLD for key in value) or any(
            has_aead_envelope_signature(child) for child in value.values()
        )
    if isinstance(value, list):
        return any(has_aead_envelope_signature(child) for child in value)
    return False


def is_versioned_aead_envelope(value: Any) -> bool:
    """Recognize the exact v1 envelope emitted by Android without opening it."""

    return (
        has_exact_aead_envelope_shape(value)
        and type(value["envelope_version"]) is int
        and value["envelope_version"] == AEAD_ENVELOPE_VERSION
        and type(value["key_version"]) is int
        and value["key_version"] > 0
        and isinstance(value["iv"], str)
        and bool(value["iv"])
        and isinstance(value["ciphertext"], str)
        and bool(value["ciphertext"])
    )


def _text_mentions_aead_member(text: str) -> bool:
    for key in AEAD_ENVELOPE_KEYS:
        encoded_key = "".join(
            "(?:"
            + "|".join(
                option
                for variant in {character.lower(), character.upper()}
                for option in (re.escape(variant), rf"\\u{ord(variant):04x}")
            )
            + ")"
            for character in key
        )
        if re.search(rf'"{encoded_key}"\s*:', text, flags=re.IGNORECASE):
            return True
    return False


def inspect_aead_envelopes(text: str) -> tuple[int, int, int]:
    """Return signed-current, supported-v1, and malformed-current counts.

    A partially written, extra-field, unsupported, duplicate-member, or otherwise
    malformed object that carries a current AEAD member is still encrypted-current
    evidence. It must never fall through to legacy plaintext parsing.
    """

    values: list[Any] = []
    parse_failed = False
    try:
        values = [strict_json_loads(text)]
    except (json.JSONDecodeError, UnicodeError, ValueError):
        values = []
        for line in text.splitlines():
            if not line.strip():
                continue
            try:
                values.append(strict_json_loads(line))
            except (json.JSONDecodeError, UnicodeError, ValueError):
                parse_failed = True
    signed = [value for value in values if has_aead_envelope_signature(value)]
    supported = sum(is_versioned_aead_envelope(value) for value in signed)
    malformed = sum(not is_versioned_aead_envelope(value) for value in signed)
    if parse_failed and _text_mentions_aead_member(text):
        malformed += 1
    return len(signed) + int(malformed > len(signed)), supported, malformed


def count_aead_envelopes(text: str) -> tuple[int, int]:
    """Compatibility view: return current-signature and supported-v1 counts."""

    signed, supported, _ = inspect_aead_envelopes(text)
    return signed, supported


def _required_no_follow_flag() -> int:
    flag = getattr(os, "O_NOFOLLOW", None)
    if not isinstance(flag, int) or flag == 0:
        raise UnsafeEvidencePath("platform cannot enforce no-follow evidence paths")
    return flag


def _file_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _open_real_directory(path: Path, label: str) -> tuple[int, Path]:
    absolute = Path(os.path.abspath(path))
    no_follow = _required_no_follow_flag()
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | no_follow)
    try:
        for component in absolute.parts[1:]:
            next_descriptor = os.open(
                component,
                os.O_RDONLY | os.O_DIRECTORY | no_follow,
                dir_fd=descriptor,
            )
            os.close(descriptor)
            descriptor = next_descriptor
        return descriptor, absolute
    except OSError as exc:
        os.close(descriptor)
        raise UnsafeEvidencePath(f"{label} ancestor symlink/non-directory is forbidden") from exc


def _open_child_directory(parent_fd: int, name: str, label: str) -> int:
    try:
        descriptor = os.open(
            name,
            os.O_RDONLY | os.O_DIRECTORY | _required_no_follow_flag(),
            dir_fd=parent_fd,
        )
        opened = os.fstat(descriptor)
        anchored = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (
            not stat.S_ISDIR(opened.st_mode)
            or _file_identity(opened) != _file_identity(anchored)
        ):
            os.close(descriptor)
            raise UnsafeEvidencePath(f"{label} directory identity changed")
        return descriptor
    except OSError as exc:
        raise UnsafeEvidencePath(f"{label} must be a real directory") from exc


def _read_bounded_file(
    parent_fd: int,
    name: str,
    *,
    max_bytes: int,
    label: str,
    final_cas: list[
        tuple[int, int, str, tuple[int, int, int, int, int, int, int]]
    ]
    | None = None,
) -> bytes:
    descriptor = -1
    try:
        anchored_before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if not stat.S_ISREG(anchored_before.st_mode) or anchored_before.st_nlink != 1:
            raise UnsafeEvidencePath(f"{label} must be a regular single-link file")
        if anchored_before.st_size <= 0 or anchored_before.st_size > max_bytes:
            raise EvidenceInputError(f"{label} exceeds its bounded size limit")
        descriptor = os.open(
            name,
            os.O_RDONLY | _required_no_follow_flag() | getattr(os, "O_CLOEXEC", 0),
            dir_fd=parent_fd,
        )
        before = os.fstat(descriptor)
        if _file_identity(before) != _file_identity(anchored_before):
            raise UnsafeEvidencePath(f"{label} changed before it was read")
        chunks: list[bytes] = []
        remaining = before.st_size + 1
        while remaining:
            chunk = os.read(descriptor, min(remaining, 1024 * 1024))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        payload = b"".join(chunks)
        after = os.fstat(descriptor)
        anchored_after = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (
            len(payload) != before.st_size
            or _file_identity(before) != _file_identity(after)
            or _file_identity(before) != _file_identity(anchored_after)
        ):
            raise UnsafeEvidencePath(f"{label} changed while it was read")
        if final_cas is not None:
            final_cas.append(
                (descriptor, os.dup(parent_fd), name, _file_identity(after))
            )
            descriptor = -1
        return payload
    except FileNotFoundError as exc:
        raise UnsafeEvidencePath(f"{label} is missing") from exc
    except OSError as exc:
        raise UnsafeEvidencePath(f"{label} is unsafe or unreadable") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _verify_snapshot_cas(
    entries: list[
        tuple[int, int, str, tuple[int, int, int, int, int, int, int]]
    ],
) -> None:
    for descriptor, parent_fd, name, expected_identity in entries:
        try:
            held = os.fstat(descriptor)
            anchored = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        except OSError as exc:
            raise UnsafeEvidencePath("evidence changed after snapshot") from exc
        if (
            not stat.S_ISREG(held.st_mode)
            or held.st_nlink != 1
            or _file_identity(held) != expected_identity
            or _file_identity(anchored) != expected_identity
        ):
            raise UnsafeEvidencePath("evidence changed after snapshot")


def _close_snapshot_cas(
    entries: list[
        tuple[int, int, str, tuple[int, int, int, int, int, int, int]]
    ],
) -> None:
    for descriptor, parent_fd, _name, _expected_identity in entries:
        os.close(descriptor)
        os.close(parent_fd)


def _snapshot_session(
    parent_fd: int,
    name: str,
    final_cas: list[
        tuple[int, int, str, tuple[int, int, int, int, int, int, int]]
    ],
) -> dict[str, Any]:
    session_fd = _open_child_directory(parent_fd, name, "session")
    try:
        names = sorted(os.listdir(session_fd))
        if "manifest.json" not in names:
            raise UnsafeEvidencePath("session is missing manifest.json")
        manifest_metadata = os.stat(
            "manifest.json", dir_fd=session_fd, follow_symlinks=False
        )
        if not stat.S_ISREG(manifest_metadata.st_mode) or manifest_metadata.st_nlink != 1:
            raise UnsafeEvidencePath("manifest must be a regular single-link file")
        records: list[tuple[str, bytes]] = []
        indices: list[int] = []
        for entry in names:
            metadata = os.stat(entry, dir_fd=session_fd, follow_symlinks=False)
            if entry == "manifest.json":
                continue
            match = FIELD_RECORD_FILE_PATTERN.fullmatch(entry)
            if match is None:
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
                    raise UnsafeEvidencePath(
                        "session input entries must be regular single-link files"
                    )
                raise UnsafeEvidencePath("unexpected input entry in field session")
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
                raise UnsafeEvidencePath(
                    "record input must be a regular single-link file"
                )
            indices.append(int(match.group(0)[8:12]))
            records.append(
                (
                    entry,
                    _read_bounded_file(
                        session_fd,
                        entry,
                        max_bytes=MAX_RECORD_SEGMENT_BYTES,
                        label="record segment",
                        final_cas=final_cas,
                    ),
                )
            )
        if indices and sorted(indices) != list(range(1, max(indices) + 1)):
            raise UnsafeEvidencePath("record segment sequence is not contiguous")
        return {
            "directory_name": name,
            "manifest": _read_bounded_file(
                session_fd,
                "manifest.json",
                max_bytes=MAX_MANIFEST_BYTES,
                label="manifest",
                final_cas=final_cas,
            ),
            "records": records,
        }
    finally:
        os.close(session_fd)


def _snapshot_evidence(source: Path) -> dict[str, Any]:
    source_fd, absolute_source = _open_real_directory(source, "source")
    field_fd = -1
    sessions: list[dict[str, Any]] = []
    marker_payloads: list[bytes] = []
    final_cas: list[
        tuple[int, int, str, tuple[int, int, int, int, int, int, int]]
    ] = []
    try:
        source_names = set(os.listdir(source_fd))
        direct_session = "manifest.json" in source_names
        if direct_session:
            evidence_root = absolute_source
            sessions.append(_snapshot_session(source_fd, ".", final_cas))
        elif absolute_source.name == "field_sessions":
            evidence_root = absolute_source
            field_fd = os.dup(source_fd)
        elif "field_sessions" in source_names:
            if source_names != {"field_sessions"}:
                raise UnsafeEvidencePath(
                    "source does not contain an exact field_sessions layout"
                )
            field_fd = _open_child_directory(source_fd, "field_sessions", "field_sessions")
            evidence_root = absolute_source / "field_sessions"
        else:
            raise UnsafeEvidencePath("source does not contain an exact field_sessions layout")

        if field_fd >= 0:
            field_names = sorted(os.listdir(field_fd))
            if not field_names:
                raise UnsafeEvidencePath(
                    "source does not contain an exact field_sessions layout"
                )
            for name in field_names:
                metadata = os.stat(name, dir_fd=field_fd, follow_symlinks=False)
                if name in ROOT_MARKER_FILES:
                    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
                        raise UnsafeEvidencePath(
                            "root markers must be regular single-link files"
                        )
                    marker_payloads.append(
                        _read_bounded_file(
                            field_fd,
                            name,
                            max_bytes=MAX_MARKER_BYTES,
                            label="root marker",
                            final_cas=final_cas,
                        )
                    )
                    continue
                if not SAFE_SESSION_ID_PATTERN.fullmatch(name):
                    raise UnsafeEvidencePath("unexpected input entry in field_sessions")
                if not stat.S_ISDIR(metadata.st_mode):
                    raise UnsafeEvidencePath("field session entry must be a real directory")
                sessions.append(_snapshot_session(field_fd, name, final_cas))

        if len(sessions) > MAX_SESSION_COUNT:
            raise EvidenceInputError("field session count exceeds the bounded limit")
        members = len(marker_payloads) + sum(
            1 + len(session["records"]) for session in sessions
        )
        if members > MAX_INPUT_MEMBERS:
            raise EvidenceInputError("field input member count exceeds the bounded limit")
        total_bytes = sum(map(len, marker_payloads)) + sum(
            len(session["manifest"])
            + sum(len(payload) for _name, payload in session["records"])
            for session in sessions
        )
        if total_bytes > MAX_TOTAL_INPUT_BYTES:
            raise EvidenceInputError("field input exceeds the bounded total size limit")
        _verify_snapshot_cas(final_cas)
        return {
            "evidence_root": evidence_root,
            "sessions": sessions,
            "markers": marker_payloads,
        }
    finally:
        _close_snapshot_cas(final_cas)
        if field_fd >= 0:
            os.close(field_fd)
        os.close(source_fd)


def _snapshot_envelope_counts(snapshot: dict[str, Any]) -> tuple[int, int, int]:
    shaped_count = 0
    supported_count = 0
    malformed_count = 0
    payloads = list(snapshot["markers"])
    for session in snapshot["sessions"]:
        payloads.append(session["manifest"])
        payloads.extend(payload for _name, payload in session["records"])
    for payload in payloads:
        try:
            text = payload.decode("utf-8")
        except UnicodeDecodeError:
            shaped_count += 1
            malformed_count += 1
            continue
        shaped, supported, malformed = inspect_aead_envelopes(text)
        shaped_count += shaped
        supported_count += supported
        malformed_count += malformed
    return shaped_count, supported_count, malformed_count


def encrypted_envelope_counts(source: Path) -> tuple[int, int, int]:
    return _snapshot_envelope_counts(_snapshot_evidence(source))


def encrypted_summary_unavailable(
    envelope_count: int,
    supported_envelope_count: int,
    malformed_envelope_count: int,
) -> dict[str, Any]:
    malformed = malformed_envelope_count > 0
    reason = (
        ENCRYPTED_CURRENT_MALFORMED_REASON
        if malformed
        else ENCRYPTED_SUMMARY_UNAVAILABLE_REASON
    )
    return {
        "schema_version": "android.field_summary_unavailable.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary_available": False,
        "input_format": (
            INPUT_FORMAT_AEAD_ENVELOPE_MALFORMED
            if malformed
            else INPUT_FORMAT_AEAD_ENVELOPE
        ),
        "integrity": {
            "summary_available": False,
            "legacy_plaintext_history_only": False,
            "encrypted_aead_envelope_detected": True,
            "encrypted_envelope_count": envelope_count,
            "supported_envelope_count": supported_envelope_count,
            "malformed_encrypted_envelope_count": malformed_envelope_count,
            "encrypted_current_malformed": malformed,
            "summary_unavailable_reason": reason,
            "strict_failure_reasons": [reason],
        },
    }


def _load_json_bytes(payload: bytes) -> tuple[dict[str, Any] | None, str | None]:
    try:
        value = strict_json_loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        return None, type(error).__name__
    if not isinstance(value, dict):
        return None, "root_not_object"
    return value, None


def _summarize_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    envelope_count, supported_envelope_count, malformed_envelope_count = (
        _snapshot_envelope_counts(snapshot)
    )
    if envelope_count:
        return encrypted_summary_unavailable(
            envelope_count,
            supported_envelope_count,
            malformed_envelope_count,
        )

    sessions: list[dict[str, Any]] = []
    errors: list[str] = []
    privacy_violations: list[str] = []
    missing_provenance_sessions: list[str] = []
    event_counts: Counter[str] = Counter()
    camera_non_metric_start_contracts: list[dict[str, Any] | None] = []
    camera_non_metric_frame_contracts: list[dict[str, Any] | None] = []
    camera_non_metric_advisory_contracts: list[dict[str, Any] | None] = []
    camera_non_metric_sample_contracts: list[dict[str, Any] | None] = []
    top_class_counts: Counter[str] = Counter()
    depth_class_counts: Counter[str] = Counter()
    depth_source_counts: Counter[str] = Counter()
    stale_reason_counts: Counter[str] = Counter()
    model_key_counts: Counter[str] = Counter()
    loaded_model_key_counts: Counter[str] = Counter()
    navigation_state_counts: Counter[str] = Counter()
    report_candidate_counts: Counter[str] = Counter()
    detect_duration_ms: list[float] = []
    unified_inference_ms: list[float] = []
    coco_inference_ms: list[float] = []
    custom_inference_ms: list[float] = []
    detection_count: list[float] = []
    risk_distance_m: list[float] = []
    depth_confidence: list[float] = []
    location_accuracy_m: list[float] = []
    location_age_ms: list[float] = []
    step_counts: list[float] = []
    telemetry_count = 0
    event_count = 0
    detection_positive_count = 0
    metric_depth_count = 0
    alert_gate_pass_count = 0
    report_gate_pass_count = 0
    trusted_location_count = 0
    fallback_count = 0
    seen_session_ids: set[str] = set()
    duplicate_session_ids: list[str] = []

    for frozen_session in snapshot["sessions"]:
        manifest, error = _load_json_bytes(frozen_session["manifest"])
        if error:
            errors.append("invalid_manifest")
            continue
        assert manifest is not None
        raw_session_id = manifest.get("session_id")
        session_id = raw_session_id if isinstance(raw_session_id, str) else ""
        if session_id in seen_session_ids:
            duplicate_session_ids.append(session_id)
            errors.append("duplicate_session_id")
            continue
        seen_session_ids.add(session_id)
        manifest_privacy_violations: list[str] = []
        for key_path in iter_keys(manifest):
            leaf = key_path.rsplit(".", 1)[-1].split("[", 1)[0]
            if is_sensitive_key(leaf):
                message = "forbidden_field"
                privacy_violations.append(message)
                manifest_privacy_violations.append(message)
        provenance = manifest.get("provenance") if isinstance(manifest.get("provenance"), dict) else {}
        if not all(provenance.get(key) for key in ("source_commit", "apk_sha256", "model_config_sha256")):
            missing_provenance_sessions.append(session_id)
        session_errors: list[str] = []
        session_privacy_violations: list[str] = manifest_privacy_violations
        session_event_counts: Counter[str] = Counter()
        session_camera_starts: list[dict[str, Any] | None] = []
        session_camera_frames: list[dict[str, Any] | None] = []
        session_camera_advisories: list[dict[str, Any] | None] = []
        session_camera_samples: list[tuple[Any, Any]] = []
        session_recorded_at_values: list[Any] = []
        session_loaded_model_keys: Counter[str] = Counter()
        session_telemetry_count = 0
        session_event_count = 0
        session_record_count = 0
        session_first_ms: int | None = None
        session_last_ms: int | None = None
        if manifest.get("schema_version") != FIELD_SESSION_SCHEMA_VERSION:
            message = "unsupported_manifest_schema"
            errors.append(message)
            session_errors.append(message)
        for _record_name, record_payload in frozen_session["records"]:
            try:
                lines = record_payload.decode("utf-8").splitlines()
            except UnicodeDecodeError:
                message = "invalid_record_encoding"
                errors.append(message)
                session_errors.append(message)
                continue
            for line_number, line in enumerate(lines, start=1):
                if not line.strip():
                    continue
                if len(line.encode("utf-8")) > MAX_RECORD_LINE_BYTES:
                    message = "record_line_size_limit"
                    errors.append(message)
                    session_errors.append(message)
                    continue
                try:
                    record = strict_json_loads(line)
                except (json.JSONDecodeError, ValueError):
                    message = "invalid_record_json"
                    errors.append(message)
                    session_errors.append(message)
                    continue
                if not isinstance(record, dict):
                    message = "record_root_not_object"
                    errors.append(message)
                    session_errors.append(message)
                    continue
                for key_path in iter_keys(record):
                    leaf = key_path.rsplit(".", 1)[-1].split("[", 1)[0].lower()
                    if is_sensitive_key(leaf):
                        message = "forbidden_field"
                        privacy_violations.append(message)
                        session_privacy_violations.append(message)
                if record.get("schema_version") != FIELD_RECORD_SCHEMA_VERSION:
                    message = "unsupported_record_schema"
                    errors.append(message)
                    session_errors.append(message)
                    continue
                recorded_at = record.get("recorded_at_epoch_ms")
                session_recorded_at_values.append(recorded_at)
                if type(recorded_at) is int:
                    session_first_ms = recorded_at if session_first_ms is None else min(session_first_ms, recorded_at)
                    session_last_ms = recorded_at if session_last_ms is None else max(session_last_ms, recorded_at)
                session_record_count += 1
                if record.get("record_type") == "event":
                    event_count += 1
                    session_event_count += 1
                    event_name = str(record.get("event_name", "unknown"))
                    event_counts[event_name] += 1
                    session_event_counts[event_name] += 1
                    fields = record.get("fields")
                    contract = fields if isinstance(fields, dict) else None
                    if event_name == "camera_non_metric_session_started":
                        camera_non_metric_start_contracts.append(contract)
                        session_camera_starts.append(contract)
                    elif event_name == "camera_non_metric_frame_analyzed":
                        camera_non_metric_frame_contracts.append(contract)
                        session_camera_frames.append(contract)
                    elif event_name == "camera_non_metric_advisory_emitted":
                        camera_non_metric_advisory_contracts.append(contract)
                        session_camera_advisories.append(contract)
                    elif event_name == "camera_non_metric_inference_sample":
                        camera_non_metric_sample_contracts.append(contract)
                        session_camera_samples.append((recorded_at, contract))
                    continue
                if record.get("record_type") != "telemetry":
                    message = "unknown_record_type"
                    errors.append(message)
                    session_errors.append(message)
                    continue
                telemetry_count += 1
                session_telemetry_count += 1
                runtime = record.get("runtime") if isinstance(record.get("runtime"), dict) else {}
                depth = record.get("depth_debug") if isinstance(record.get("depth_debug"), dict) else {}
                count = depth.get("detection_count")
                if isinstance(count, (int, float)):
                    detection_count.append(float(count))
                    detection_positive_count += int(count > 0)
                for field, counter in (
                    ("top_detection_class_name", top_class_counts),
                    ("best_depth_class_name", depth_class_counts),
                    ("best_depth_source", depth_source_counts),
                    ("stale_reason", stale_reason_counts),
                    ("detector_model_key", model_key_counts),
                    ("detector_loaded_model_key", loaded_model_key_counts),
                ):
                    value = depth.get(field)
                    if value not in (None, ""):
                        counter[str(value)] += 1
                        if field == "detector_loaded_model_key":
                            session_loaded_model_keys[str(value)] += 1
                source_name = str(depth.get("best_depth_source", ""))
                metric_depth_count += int(source_name.startswith("ARCORE_"))
                fallback_count += int(depth.get("detector_model_fallback_used") is True)
                detect_duration_ms.append(depth.get("detect_duration_ms"))
                unified_inference_ms.append(depth.get("detector_model_inference_ms"))
                coco_inference_ms.append(depth.get("detector_coco_inference_ms"))
                custom_inference_ms.append(depth.get("detector_custom_inference_ms"))
                risk_distance_m.append(depth.get("best_depth_risk_distance_m"))
                depth_confidence.append(depth.get("best_depth_confidence_score"))
                navigation_state_counts[str(runtime.get("navigation_state", "unknown"))] += 1
                report_candidate_counts[str(runtime.get("report_candidate_state", "unknown"))] += 1
                alert_gate_pass_count += int(runtime.get("device_gate_allows_alerts") is True)
                report_gate_pass_count += int(runtime.get("device_gate_allows_reports") is True)
                trusted_location_count += int(runtime.get("trusted_location_available") is True)
                location_accuracy_m.append(runtime.get("location_accuracy_m"))
                location_age_ms.append(runtime.get("location_age_ms"))
                step_counts.append(runtime.get("step_count"))
        sessions.append(
            {
                "session_id": session_id,
                "status": manifest.get("status", "unknown"),
                "started_at_epoch_ms": manifest.get("started_at_epoch_ms"),
                "ended_at_epoch_ms": manifest.get("ended_at_epoch_ms"),
                "first_record_at_epoch_ms": session_first_ms,
                "last_record_at_epoch_ms": session_last_ms,
                "record_count": session_record_count,
                "record_segment_count": len(frozen_session["records"]),
                "device": manifest.get("device", {}),
                "provenance": provenance,
                "strict_evidence": {
                    "malformed_or_unknown_record_count": len(session_errors),
                    "errors": session_errors,
                    "privacy_violation_count": len(session_privacy_violations),
                    "privacy_violations": session_privacy_violations,
                    "records": {"telemetry": session_telemetry_count, "events": session_event_count},
                    "events": counter_dict(session_event_counts),
                    "camera_non_metric": {
                        "session_started_contracts": session_camera_starts,
                        "frame_analyzed_contracts": session_camera_frames,
                        "advisory_emitted_contracts": session_camera_advisories,
                        "inference_sample_contracts": [fields for _, fields in session_camera_samples],
                        "sample_summary": camera_non_metric_sample_summary(
                            session_camera_samples,
                            recorded_at_values=session_recorded_at_values,
                            session_status=manifest.get("status"),
                            session_started_at=manifest.get("started_at_epoch_ms"),
                            session_ended_at=manifest.get("ended_at_epoch_ms"),
                        ),
                    },
                    "loaded_model_keys": counter_dict(session_loaded_model_keys),
                },
            }
        )

    return {
        "schema_version": "android.field_summary.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary_available": True,
        "input_format": INPUT_FORMAT_LEGACY_PLAINTEXT_HISTORY_ONLY,
        "integrity": {
            "summary_available": True,
            "legacy_plaintext_history_only": True,
            "encrypted_aead_envelope_detected": False,
            "session_count": len(sessions),
            "malformed_or_unknown_record_count": len(errors),
            "errors": errors,
            "privacy_violation_count": len(privacy_violations),
            "privacy_violations": privacy_violations,
            "missing_provenance_count": len(missing_provenance_sessions),
            "missing_provenance_sessions": missing_provenance_sessions,
            "duplicate_session_id_count": len(duplicate_session_ids),
            "duplicate_session_ids": sorted(set(duplicate_session_ids)),
        },
        "sessions": sessions,
        "records": {"telemetry": telemetry_count, "events": event_count},
        "events": counter_dict(event_counts),
        "camera_non_metric": {
            "session_started_contracts": camera_non_metric_start_contracts,
            "frame_analyzed_contracts": camera_non_metric_frame_contracts,
            "advisory_emitted_contracts": camera_non_metric_advisory_contracts,
            "inference_sample_contracts": camera_non_metric_sample_contracts,
        },
        "detector": {
            "frames_with_detection": detection_positive_count,
            "detection_count": numeric_summary(detection_count),
            "top_classes": counter_dict(top_class_counts),
            "model_keys": counter_dict(model_key_counts),
            "loaded_model_keys": counter_dict(loaded_model_key_counts),
            "fallback_frame_count": fallback_count,
            "detect_duration_ms": numeric_summary(detect_duration_ms),
            "unified_inference_ms": numeric_summary(unified_inference_ms),
            "legacy_coco_inference_ms": numeric_summary(coco_inference_ms),
            "legacy_custom_inference_ms": numeric_summary(custom_inference_ms),
            "stale_reasons": counter_dict(stale_reason_counts),
        },
        "depth": {
            "frames_with_metric_arcore_depth": metric_depth_count,
            "classes": counter_dict(depth_class_counts),
            "sources": counter_dict(depth_source_counts),
            "risk_distance_m": numeric_summary(risk_distance_m),
            "confidence_score": numeric_summary(depth_confidence),
        },
        "runtime": {
            "alert_gate_pass_frames": alert_gate_pass_count,
            "report_gate_pass_frames": report_gate_pass_count,
            "trusted_location_frames": trusted_location_count,
            "location_accuracy_m": numeric_summary(location_accuracy_m),
            "location_age_ms": numeric_summary(location_age_ms),
            "step_count": numeric_summary(step_counts),
            "navigation_states": counter_dict(navigation_state_counts),
            "report_candidate_states": counter_dict(report_candidate_counts),
        },
    }


SAFE_EVENT_NAMES = frozenset({*KNOWN_EVENT_NAMES, "UNKNOWN"})
SAFE_CLASS_NAMES = frozenset(
    {"person", "bicycle", "car", "motorcycle", "bus", "truck", "dog", "cat", "UNKNOWN"}
)
SAFE_MODEL_KEYS = frozenset(
    {"unified_walksafe", "legacy_two_model", "coco", "custom", "UNKNOWN"}
)
SAFE_DEPTH_SOURCES = frozenset(
    {
        "ARCORE_RAW_DEPTH",
        "ARCORE_DEPTH_IMAGE",
        "CAMERA_NON_METRIC",
        "NONE",
        "UNKNOWN",
    }
)
SAFE_NAVIGATION_STATES = frozenset(
    {"navigation=gps_trusted", "navigation=gps_untrusted", "inactive", "unknown", "UNKNOWN"}
)
SAFE_REPORT_STATES = frozenset({"eligible", "blocked", "unknown", "UNKNOWN"})
SAFE_CAMERA_STRING_VALUES: dict[str, frozenset[str]] = {
    "loaded_model": SAFE_MODEL_KEYS,
    "reason": frozenset(
        {
            ARCORE_UNSUPPORTED_REASON,
            ARCORE_DEPTH_UNSUPPORTED_REASON,
            ARCORE_SESSION_INCOMPATIBLE_REASON,
            DEBUG_FORCED_REASON,
            "UNKNOWN",
        }
    ),
    "state": frozenset(
        {
            *ARCORE_SUPPORTED_STATES,
            "UNSUPPORTED_DEVICE_NOT_CAPABLE",
            "UNKNOWN_CHECKING",
            "UNKNOWN_ERROR",
            CAMERA_NON_METRIC_FRAME_STATE,
            "detector_failed",
            "UNKNOWN",
        }
    ),
    "direction": frozenset({*CAMERA_NON_METRIC_DIRECTIONS, "UNKNOWN"}),
    "capability_tier": frozenset({*CAMERA_NON_METRIC_SAMPLE_TIERS, "UNKNOWN"}),
}
SAFE_CAMERA_FIELDS = frozenset(
    {
        "loaded_model",
        "model_fallback_used",
        "metric",
        "reports_allowed",
        "reason",
        "state",
        "direction",
        "tmap_authoritative",
        "elapsed_realtime_ms",
        "inference_ms",
        "detection_count",
        "capability_tier",
        "camera_permission_granted",
        "camera_fallback_running",
        "detector_available",
        "imu_fresh",
        "tmap_route_active",
    }
)


def _domain_hash(domain: str, value: str) -> str:
    return hashlib.sha256(
        f"walksafe:{domain}:v1\0{value}".encode("utf-8", errors="replace")
    ).hexdigest()


def _closed_counter(values: Any, allowed: frozenset[str]) -> dict[str, int]:
    result: Counter[str] = Counter()
    if not isinstance(values, dict):
        return {}
    for raw_key, raw_count in values.items():
        if type(raw_count) is not int or raw_count < 0:
            continue
        key = raw_key if isinstance(raw_key, str) and raw_key in allowed else "UNKNOWN"
        result[key] += raw_count
    return counter_dict(result)


def _closed_camera_contract(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    result: dict[str, Any] = {}
    for key in SAFE_CAMERA_FIELDS:
        child = value.get(key)
        if key in SAFE_CAMERA_STRING_VALUES:
            if isinstance(child, str):
                allowed = SAFE_CAMERA_STRING_VALUES[key]
                result[key] = child if child in allowed else "UNKNOWN"
        elif isinstance(child, bool):
            result[key] = child
        elif type(child) is int and 0 <= child <= JAVA_LONG_MAX:
            result[key] = child
    return result


def _public_summary(summary: dict[str, Any]) -> dict[str, Any]:
    public = json.loads(json.dumps(summary, ensure_ascii=False))
    public.pop("source", None)
    integrity = public.get("integrity")
    if not isinstance(integrity, dict):
        return public
    if public.get("summary_available") is False:
        integrity["evidence_eligibility"] = "NOT_RUN"
        integrity["release_credit"] = 0
        integrity["field_evidence_credit"] = 0
        return public

    integrity["evidence_eligibility"] = LEGACY_HISTORY_ONLY_NOT_ELIGIBLE
    integrity["release_credit"] = 0
    integrity["field_evidence_credit"] = 0
    integrity["errors"] = ["input_error"] * int(
        integrity.get("malformed_or_unknown_record_count", 0)
    )
    integrity["privacy_violations"] = ["forbidden_field"] * int(
        integrity.get("privacy_violation_count", 0)
    )
    integrity["missing_provenance_sessions"] = []
    integrity["duplicate_session_ids"] = []
    selected_id = integrity.pop("strict_selected_session_id", None)
    integrity["strict_selected_session_sha256"] = (
        _domain_hash("session", selected_id) if isinstance(selected_id, str) else None
    )
    integrity.pop("expected_source_commit", None)
    integrity.pop("expected_apk_sha256", None)

    closed_sessions: list[dict[str, Any]] = []
    for session in public.get("sessions", []):
        if not isinstance(session, dict):
            continue
        session_id = session.get("session_id")
        closed: dict[str, Any] = {
            "session_ref_sha256": _domain_hash(
                "session", session_id if isinstance(session_id, str) else "invalid"
            ),
            "status": (
                session.get("status")
                if session.get("status")
                in {FIELD_SESSION_STATUS_ACTIVE, FIELD_SESSION_STATUS_COMPLETED}
                else "UNKNOWN"
            ),
        }
        for key in (
            "started_at_epoch_ms",
            "ended_at_epoch_ms",
            "first_record_at_epoch_ms",
            "last_record_at_epoch_ms",
            "record_count",
            "record_segment_count",
        ):
            value = session.get(key)
            closed[key] = value if type(value) is int and 0 <= value <= JAVA_LONG_MAX else None
        evidence = session.get("strict_evidence")
        if not isinstance(evidence, dict):
            evidence = {}
        camera = evidence.get("camera_non_metric")
        if not isinstance(camera, dict):
            camera = {}
        closed["strict_evidence"] = {
            "malformed_or_unknown_record_count": int(
                evidence.get("malformed_or_unknown_record_count", 0)
            ),
            "errors": ["input_error"]
            * int(evidence.get("malformed_or_unknown_record_count", 0)),
            "privacy_violation_count": int(evidence.get("privacy_violation_count", 0)),
            "privacy_violations": ["forbidden_field"]
            * int(evidence.get("privacy_violation_count", 0)),
            "records": evidence.get("records", {"telemetry": 0, "events": 0}),
            "events": _closed_counter(evidence.get("events"), SAFE_EVENT_NAMES),
            "camera_non_metric": {
                "session_started_contracts": [
                    _closed_camera_contract(item)
                    for item in camera.get("session_started_contracts", [])
                ],
                "frame_analyzed_contracts": [
                    _closed_camera_contract(item)
                    for item in camera.get("frame_analyzed_contracts", [])
                ],
                "advisory_emitted_contracts": [
                    _closed_camera_contract(item)
                    for item in camera.get("advisory_emitted_contracts", [])
                ],
                "inference_sample_contracts": [
                    _closed_camera_contract(item)
                    for item in camera.get("inference_sample_contracts", [])
                ],
                "sample_summary": camera.get(
                    "sample_summary", camera_non_metric_sample_summary([])
                ),
            },
            "loaded_model_keys": _closed_counter(
                evidence.get("loaded_model_keys"), SAFE_MODEL_KEYS
            ),
        }
        closed_sessions.append(closed)
    public["sessions"] = closed_sessions

    public["events"] = _closed_counter(public.get("events"), SAFE_EVENT_NAMES)
    camera = public.get("camera_non_metric")
    if isinstance(camera, dict):
        for key in (
            "session_started_contracts",
            "frame_analyzed_contracts",
            "advisory_emitted_contracts",
            "inference_sample_contracts",
        ):
            camera[key] = [_closed_camera_contract(item) for item in camera.get(key, [])]
    detector = public.get("detector")
    if isinstance(detector, dict):
        detector["top_classes"] = _closed_counter(
            detector.get("top_classes"), SAFE_CLASS_NAMES
        )
        detector["model_keys"] = _closed_counter(
            detector.get("model_keys"), SAFE_MODEL_KEYS
        )
        detector["loaded_model_keys"] = _closed_counter(
            detector.get("loaded_model_keys"), SAFE_MODEL_KEYS
        )
        detector["stale_reasons"] = _closed_counter(
            detector.get("stale_reasons"), frozenset({"none", "unknown", "UNKNOWN"})
        )
    depth = public.get("depth")
    if isinstance(depth, dict):
        depth["classes"] = _closed_counter(depth.get("classes"), SAFE_CLASS_NAMES)
        depth["sources"] = _closed_counter(depth.get("sources"), SAFE_DEPTH_SOURCES)
    runtime = public.get("runtime")
    if isinstance(runtime, dict):
        runtime["navigation_states"] = _closed_counter(
            runtime.get("navigation_states"), SAFE_NAVIGATION_STATES
        )
        runtime["report_candidate_states"] = _closed_counter(
            runtime.get("report_candidate_states"), SAFE_REPORT_STATES
        )
    return public


def summarize(source: Path) -> dict[str, Any]:
    return _public_summary(_summarize_snapshot(_snapshot_evidence(source)))


def fmt_number(value: Any, digits: int = 2) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def counter_lines(title: str, values: dict[str, int]) -> list[str]:
    lines = [f"### {title}"]
    if not values:
        return lines + ["- 기록 없음"]
    return lines + [f"- `{key}`: {count}" for key, count in list(values.items())[:20]]


def render_markdown(summary: dict[str, Any]) -> str:
    integrity = summary["integrity"]
    records = summary["records"]
    detector = summary["detector"]
    depth = summary["depth"]
    runtime = summary["runtime"]
    detect_duration = detector["detect_duration_ms"]
    unified_inference = detector["unified_inference_ms"]
    coco_inference = detector["legacy_coco_inference_ms"]
    custom_inference = detector["legacy_custom_inference_ms"]
    accuracy = runtime["location_accuracy_m"]
    camera_samples = summary["camera_non_metric"].get(
        "strict_selected_session_samples",
        camera_non_metric_sample_summary([]),
    )
    camera_inference = camera_samples["inference_ms"]
    lines = [
        "# Android 현장 세션 자동 요약",
        "",
        "> **LEGACY/HISTORY ONLY:** 이 요약은 과거 평문 fixture 호환용이며 현재 암호화 field log의 운영 요약이 아니다.",
        "",
        "> **NO EVIDENCE CREDIT:** production/release/field evidence credit은 모두 0이다.",
        "",
        "> 이 문서는 앱이 저장한 metadata-only 기록의 자동 집계다. 실제 장애물 안전성이나 제품 완료를 단독으로 증명하지 않는다.",
        "",
        "## 무결성",
        "",
        f"- 세션: {integrity['session_count']}",
        f"- telemetry/event: {records['telemetry']} / {records['events']}",
        f"- 손상되거나 알 수 없는 레코드: {integrity['malformed_or_unknown_record_count']}",
        f"- 금지 개인정보 키 검출: {integrity['privacy_violation_count']}",
        f"- source/APK/model-config provenance 누락 세션: {integrity['missing_provenance_count']}",
        f"- strict 모드: {integrity.get('strict_mode', 'none')}",
        f"- strict 판정 세션 해시: {integrity.get('strict_selected_session_sha256') or '없음'}",
        f"- CameraX advisory 필수: {integrity.get('camera_advisory_required', False)}",
        f"- ARCore 실제 미지원 근거 필수: {integrity.get('arcore_unsupported_required', False)}",
        f"- evidence scope: {integrity.get('evidence_scope', EVIDENCE_SCOPE_NO_SESSION)}",
        f"- ARCore 실제 미지원 검증: {integrity.get('arcore_unsupported_verified', False)}",
        f"- strict 실패 사유: {', '.join(integrity.get('strict_failure_reasons', [])) or '없음'}",
        "",
        "## 핵심 집계",
        "",
        f"- 탐지 1개 이상 frame: {detector['frames_with_detection']}",
        f"- ARCore metric depth frame: {depth['frames_with_metric_arcore_depth']}",
        f"- detector fallback frame: {detector['fallback_frame_count']}",
        f"- 전체 detector 시간 p50/p95/max: {fmt_number(detect_duration['p50'])} / {fmt_number(detect_duration['p95'])} / {fmt_number(detect_duration['max'])} ms",
        f"- unified 추론 시간 p50/p95/max: {fmt_number(unified_inference['p50'])} / {fmt_number(unified_inference['p95'])} / {fmt_number(unified_inference['max'])} ms",
        f"- legacy COCO 추론 시간 p50/p95/max: {fmt_number(coco_inference['p50'])} / {fmt_number(coco_inference['p95'])} / {fmt_number(coco_inference['max'])} ms",
        f"- legacy custom 추론 시간 p50/p95/max: {fmt_number(custom_inference['p50'])} / {fmt_number(custom_inference['p95'])} / {fmt_number(custom_inference['max'])} ms",
        f"- alert/report gate 통과 frame: {runtime['alert_gate_pass_frames']} / {runtime['report_gate_pass_frames']}",
        f"- trusted location frame: {runtime['trusted_location_frames']}",
        f"- GPS 정확도 p50/p95: {fmt_number(accuracy['p50'])} / {fmt_number(accuracy['p95'])} m",
        f"- 선택 CameraX sample 수/구간/max gap: {camera_samples['sample_count']} / "
        f"{camera_samples['sample_span_ms']} / {fmt_number(camera_samples['max_sample_gap_ms'])} ms",
        f"- 선택 CameraX 추론 시간 p50/p95: {fmt_number(camera_inference['p50'])} / "
        f"{fmt_number(camera_inference['p95'])} ms",
        "",
    ]
    lines.extend(counter_lines("탐지 상위 클래스", detector["top_classes"]))
    lines.append("")
    lines.extend(counter_lines("Depth source", depth["sources"]))
    lines.append("")
    lines.extend(counter_lines("Stale 사유", detector["stale_reasons"]))
    lines.append("")
    lines.extend(counter_lines("Navigation 상태", runtime["navigation_states"]))
    lines.append("")
    lines.extend(counter_lines("이벤트", summary["events"]))
    lines.extend(["", "## 세션", ""])
    for session in summary["sessions"]:
        session_ref = str(session.get("session_ref_sha256", "missing"))
        lines.append(
            f"- `{session_ref[:12]}`: status={session['status']}, records={session['record_count']}, "
            f"segments={session['record_segment_count']}"
        )
    if integrity["errors"]:
        lines.extend(["", "## 파싱 오류", ""] + [f"- {item}" for item in integrity["errors"]])
    if integrity["privacy_violations"]:
        lines.extend(["", "## 개인정보 금지 키 경고", ""] + [f"- {item}" for item in integrity["privacy_violations"]])
    return "\n".join(lines) + "\n"


def _latest_session(summary: dict[str, Any]) -> dict[str, Any] | None:
    sessions = summary["sessions"]
    if not sessions:
        return None

    def sort_key(session: dict[str, Any]) -> tuple[int, str]:
        for key in (
            "started_at_epoch_ms",
            "first_record_at_epoch_ms",
            "last_record_at_epoch_ms",
            "ended_at_epoch_ms",
        ):
            value = session.get(key)
            if type(value) is int:
                return value, str(session.get("session_id", ""))
        return -1, str(session.get("session_id", ""))

    return max(sessions, key=sort_key)


def _non_empty_model_key(fields: Any) -> bool:
    return (
        isinstance(fields, dict)
        and isinstance(fields.get("loaded_model"), str)
        and bool(fields["loaded_model"].strip())
        and fields["loaded_model"].strip().lower() != "none"
    )


def _is_actual_arcore_unsupported_start(fields: Any) -> bool:
    if not isinstance(fields, dict):
        return False
    reason = fields.get("reason")
    state = fields.get("state")
    return (
        reason == ARCORE_UNSUPPORTED_REASON and state == "UNSUPPORTED_DEVICE_NOT_CAPABLE"
    ) or (
        reason == ARCORE_SESSION_INCOMPATIBLE_REASON and state in ARCORE_SUPPORTED_STATES
    )


def evidence_classification(session: dict[str, Any] | None) -> tuple[str, bool]:
    if session is None:
        return EVIDENCE_SCOPE_NO_SESSION, False
    evidence = session["strict_evidence"]
    starts = evidence["camera_non_metric"]["session_started_contracts"]
    if starts:
        if all(_is_actual_arcore_unsupported_start(fields) for fields in starts):
            return (
                EVIDENCE_SCOPE_ARCORE_UNSUPPORTED_FIELD,
                session.get("status") == FIELD_SESSION_STATUS_COMPLETED,
            )
        reasons = {
            fields.get("reason") if isinstance(fields, dict) else None
            for fields in starts
        }
        states = {
            fields.get("state") if isinstance(fields, dict) else None
            for fields in starts
        }
        if reasons == {DEBUG_FORCED_REASON} and states and states <= ARCORE_SUPPORTED_STATES:
            return EVIDENCE_SCOPE_FORCED_SUPPORTED_FUNCTIONAL, False
        if reasons == {ARCORE_DEPTH_UNSUPPORTED_REASON} and states and states <= ARCORE_SUPPORTED_STATES:
            return EVIDENCE_SCOPE_DEPTH_UNSUPPORTED_FUNCTIONAL, False
        if len(reasons) > 1:
            return EVIDENCE_SCOPE_CAMERA_NON_METRIC_MIXED, False
        return EVIDENCE_SCOPE_CAMERA_NON_METRIC_UNKNOWN, False
    if evidence["events"].get("arcore_session_started", 0) > 0:
        return EVIDENCE_SCOPE_ARCORE_METRIC, False
    return EVIDENCE_SCOPE_NO_RUNTIME_EVIDENCE, False


def _camera_non_metric_contract_failure_reasons(
    evidence: dict[str, Any],
    require_camera_advisory: bool,
    require_arcore_unsupported: bool,
) -> list[str]:
    reasons: list[str] = []
    camera_evidence = evidence["camera_non_metric"]
    starts = camera_evidence["session_started_contracts"]
    if not starts:
        reasons.append("missing_camera_non_metric_session_started")
    else:
        if any(not _non_empty_model_key(fields) for fields in starts):
            reasons.append("invalid_camera_non_metric_loaded_model_contract")
        if any(
            not isinstance(fields, dict) or not isinstance(fields.get("model_fallback_used"), bool)
            for fields in starts
        ):
            reasons.append("invalid_camera_non_metric_model_fallback_contract")
        if any(not isinstance(fields, dict) or fields.get("metric") is not False for fields in starts):
            reasons.append("invalid_camera_non_metric_metric_contract")
        if any(not isinstance(fields, dict) or fields.get("reports_allowed") is not False for fields in starts):
            reasons.append("invalid_camera_non_metric_reports_allowed_contract")
    actual_arcore_unsupported = bool(starts) and all(
        _is_actual_arcore_unsupported_start(fields) for fields in starts
    )
    if require_arcore_unsupported:
        if not actual_arcore_unsupported:
            reasons.append("arcore_unsupported_not_verified")
        else:
            sample_summary = camera_evidence["sample_summary"]
            if sample_summary["invalid_sample_count"] > 0:
                reasons.append("invalid_camera_non_metric_inference_sample_contract")
            if not sample_summary["elapsed_contract_valid"]:
                reasons.append("invalid_camera_non_metric_sample_elapsed_contract")
            elif not sample_summary["elapsed_sequence_valid"]:
                reasons.append("invalid_camera_non_metric_sample_elapsed_sequence")
            if not sample_summary["recorded_at_contract_valid"]:
                reasons.append("invalid_camera_non_metric_sample_recorded_at_contract")
            if sample_summary["sample_count"] < CAMERA_NON_METRIC_FIELD_MIN_SAMPLE_COUNT:
                reasons.append("camera_non_metric_sample_count_below_field_minimum")
            if sample_summary["sample_span_ms"] < CAMERA_NON_METRIC_FIELD_MIN_SAMPLE_SPAN_MS:
                reasons.append("camera_non_metric_sample_span_below_field_minimum")
            max_gap_ms = sample_summary["max_sample_gap_ms"]
            if max_gap_ms is None or max_gap_ms > CAMERA_NON_METRIC_FIELD_MAX_SAMPLE_GAP_MS:
                reasons.append("camera_non_metric_sample_gap_above_field_maximum")

    frames = camera_evidence.get("frame_analyzed_contracts", [])
    if not frames:
        reasons.append("missing_camera_non_metric_frame_analyzed")
    else:
        if any(not _non_empty_model_key(fields) for fields in frames):
            reasons.append("invalid_camera_non_metric_frame_loaded_model_contract")
        if any(
            not isinstance(fields, dict) or not isinstance(fields.get("model_fallback_used"), bool)
            for fields in frames
        ):
            reasons.append("invalid_camera_non_metric_frame_model_fallback_contract")
        if any(not isinstance(fields, dict) or fields.get("metric") is not False for fields in frames):
            reasons.append("invalid_camera_non_metric_frame_metric_contract")
        if any(not isinstance(fields, dict) or fields.get("reports_allowed") is not False for fields in frames):
            reasons.append("invalid_camera_non_metric_frame_reports_allowed_contract")
        if any(
            not isinstance(fields, dict) or fields.get("state") != CAMERA_NON_METRIC_FRAME_STATE
            for fields in frames
        ):
            reasons.append("invalid_camera_non_metric_frame_state_contract")

    advisories = camera_evidence["advisory_emitted_contracts"]
    if not advisories:
        if require_camera_advisory:
            reasons.append("missing_camera_non_metric_advisory_emitted")
        return reasons
    if any(not _non_empty_model_key(fields) for fields in advisories):
        reasons.append("invalid_camera_non_metric_advisory_loaded_model_contract")
    if any(
        not isinstance(fields, dict) or not isinstance(fields.get("model_fallback_used"), bool)
        for fields in advisories
    ):
        reasons.append("invalid_camera_non_metric_advisory_model_fallback_contract")
    if any(
        not isinstance(fields, dict) or fields.get("direction") not in CAMERA_NON_METRIC_DIRECTIONS
        for fields in advisories
    ):
        reasons.append("invalid_camera_non_metric_advisory_direction_contract")
    if any(not isinstance(fields, dict) or fields.get("metric") is not False for fields in advisories):
        reasons.append("invalid_camera_non_metric_advisory_metric_contract")
    if any(not isinstance(fields, dict) or fields.get("tmap_authoritative") is not True for fields in advisories):
        reasons.append("invalid_camera_non_metric_advisory_tmap_contract")
    if any(not isinstance(fields, dict) or fields.get("reports_allowed") is not False for fields in advisories):
        reasons.append("invalid_camera_non_metric_advisory_reports_allowed_contract")
    return reasons


def strict_failure_reasons(
    summary: dict[str, Any],
    strict_mode: str = STRICT_MODE_ARCORE_METRIC,
    require_camera_advisory: bool = False,
    expected_source_commit: str | None = None,
    expected_apk_sha256: str | None = None,
    require_arcore_unsupported: bool = False,
) -> list[str]:
    if strict_mode not in STRICT_MODES:
        raise ValueError(f"unsupported strict mode: {strict_mode}")
    if require_camera_advisory and strict_mode != STRICT_MODE_CAMERA_NON_METRIC:
        raise ValueError("camera advisory evidence is only valid in camera-non-metric strict mode")
    if require_arcore_unsupported and strict_mode != STRICT_MODE_CAMERA_NON_METRIC:
        raise ValueError("ARCore unsupported evidence is only valid in camera-non-metric strict mode")
    if expected_source_commit is not None and not SOURCE_COMMIT_PATTERN.fullmatch(expected_source_commit):
        raise ValueError("expected source commit must be exactly 40 hexadecimal characters")
    if expected_apk_sha256 is not None and not SHA256_PATTERN.fullmatch(expected_apk_sha256):
        raise ValueError("expected APK SHA-256 must be exactly 64 hexadecimal characters")
    if summary.get("summary_available") is False:
        return [
            summary.get("integrity", {}).get(
                "summary_unavailable_reason", ENCRYPTED_SUMMARY_UNAVAILABLE_REASON
            )
        ]
    reasons: list[str] = []
    integrity = summary["integrity"]
    if integrity["privacy_violation_count"] > 0:
        reasons.append("privacy_violation")
    if integrity["malformed_or_unknown_record_count"] > 0:
        reasons.append("malformed_or_unknown_record")
    if integrity.get("duplicate_session_id_count", 0) > 0:
        reasons.append("duplicate_session_id")
    session = _latest_session(summary)
    if session is None:
        reasons.append("no_session")
        return reasons
    evidence = session["strict_evidence"]
    if evidence["privacy_violation_count"] > 0 and "privacy_violation" not in reasons:
        reasons.append("privacy_violation")
    if evidence["malformed_or_unknown_record_count"] > 0 and "malformed_or_unknown_record" not in reasons:
        reasons.append("malformed_or_unknown_record")
    provenance = session["provenance"]
    source_commit = provenance.get("source_commit")
    apk_sha256 = provenance.get("apk_sha256")
    model_config_sha256 = provenance.get("model_config_sha256")
    if not isinstance(source_commit, str) or not SOURCE_COMMIT_PATTERN.fullmatch(source_commit):
        reasons.append("invalid_source_commit_provenance")
    if not isinstance(apk_sha256, str) or not SHA256_PATTERN.fullmatch(apk_sha256):
        reasons.append("invalid_apk_sha256_provenance")
    if not isinstance(model_config_sha256, str) or not SHA256_PATTERN.fullmatch(model_config_sha256):
        reasons.append("invalid_model_config_sha256_provenance")
    if (
        expected_source_commit is not None
        and isinstance(source_commit, str)
        and source_commit.lower() != expected_source_commit.lower()
    ):
        reasons.append("source_commit_mismatch")
    if (
        expected_apk_sha256 is not None
        and isinstance(apk_sha256, str)
        and apk_sha256.lower() != expected_apk_sha256.lower()
    ):
        reasons.append("apk_sha256_mismatch")
    if strict_mode == STRICT_MODE_ARCORE_METRIC:
        if evidence["records"]["telemetry"] == 0:
            reasons.append("zero_telemetry")
        if evidence["events"].get("arcore_session_started", 0) == 0:
            reasons.append("missing_arcore_session_started")
        if not evidence["loaded_model_keys"]:
            reasons.append("missing_loaded_model_evidence")
    else:
        if require_arcore_unsupported and session.get("status") != FIELD_SESSION_STATUS_COMPLETED:
            reasons.append("arcore_unsupported_session_not_completed")
        reasons.extend(
            _camera_non_metric_contract_failure_reasons(
                evidence,
                require_camera_advisory,
                require_arcore_unsupported,
            )
        )
    return reasons


def _open_output_directory(path: Path) -> tuple[int, Path]:
    absolute = Path(os.path.abspath(path))
    descriptor = os.open(
        "/",
        os.O_RDONLY | os.O_DIRECTORY | _required_no_follow_flag(),
    )
    try:
        for component in absolute.parts[1:]:
            try:
                os.mkdir(component, 0o700, dir_fd=descriptor)
            except FileExistsError:
                pass
            next_descriptor = os.open(
                component,
                os.O_RDONLY | os.O_DIRECTORY | _required_no_follow_flag(),
                dir_fd=descriptor,
            )
            os.close(descriptor)
            descriptor = next_descriptor
        os.fchmod(descriptor, 0o700)
        return descriptor, absolute
    except OSError as exc:
        os.close(descriptor)
        raise UnsafeEvidencePath(
            "output symlink or output ancestor symlink/non-directory is forbidden"
        ) from exc


def _prepare_output_directory(path: Path) -> None:
    descriptor, _absolute = _open_output_directory(path)
    os.close(descriptor)


def _assert_output_disjoint(evidence_root: Path, output: Path) -> None:
    absolute_evidence = Path(os.path.abspath(evidence_root))
    absolute_output = Path(os.path.abspath(output))
    if (
        absolute_evidence == absolute_output
        or absolute_evidence in absolute_output.parents
        or absolute_output in absolute_evidence.parents
    ):
        raise UnsafeEvidencePath("summary output must not overlap the evidence root")


def _atomic_write_summary_file(path: Path, content: str) -> None:
    directory_fd, _absolute = _open_output_directory(path.parent)
    descriptor = -1
    temporary_name = f".{path.name}.{secrets.token_hex(16)}.tmp"
    created_identity: tuple[int, int] | None = None
    try:
        try:
            current = os.stat(path.name, dir_fd=directory_fd, follow_symlinks=False)
        except FileNotFoundError:
            current = None
        if current is not None and (
            not stat.S_ISREG(current.st_mode) or current.st_nlink != 1
        ):
            raise UnsafeEvidencePath(
                "summary output must be a regular single-link file"
            )
        descriptor = os.open(
            temporary_name,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | _required_no_follow_flag()
            | getattr(os, "O_CLOEXEC", 0),
            0o600,
            dir_fd=directory_fd,
        )
        created = os.fstat(descriptor)
        created_identity = (created.st_dev, created.st_ino)
        if not stat.S_ISREG(created.st_mode) or created.st_nlink != 1:
            raise UnsafeEvidencePath("summary temporary output is unsafe")
        payload = content.encode("utf-8")
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise OSError("summary output made no write progress")
            view = view[written:]
        os.fchmod(descriptor, 0o600)
        os.fsync(descriptor)
        staged = os.fstat(descriptor)
        anchored = os.stat(
            temporary_name,
            dir_fd=directory_fd,
            follow_symlinks=False,
        )
        if (
            _file_identity(staged) != _file_identity(anchored)
            or (staged.st_dev, staged.st_ino) != created_identity
            or staged.st_nlink != 1
            or staged.st_size != len(payload)
            or stat.S_IMODE(staged.st_mode) != 0o600
        ):
            raise UnsafeEvidencePath("summary temporary output identity changed")
        os.replace(
            temporary_name,
            path.name,
            src_dir_fd=directory_fd,
            dst_dir_fd=directory_fd,
        )
        published = os.fstat(descriptor)
        final = os.stat(path.name, dir_fd=directory_fd, follow_symlinks=False)
        if (
            _file_identity(final) != _file_identity(published)
            or final.st_nlink != 1
            or stat.S_IMODE(final.st_mode) != 0o600
        ):
            raise UnsafeEvidencePath("summary output identity changed after publish")
        os.fsync(directory_fd)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        if created_identity is not None:
            try:
                temporary = os.stat(
                    temporary_name,
                    dir_fd=directory_fd,
                    follow_symlinks=False,
                )
                if (temporary.st_dev, temporary.st_ino) == created_identity:
                    os.unlink(temporary_name, dir_fd=directory_fd)
                    os.fsync(directory_fd)
            except FileNotFoundError:
                pass
        os.close(directory_fd)


def _replace_stale_outputs_with_unavailable(
    output_directory: Path,
    summary: dict[str, Any],
) -> None:
    """Replace only pre-existing summaries; absent encrypted runs write nothing."""

    if not output_directory.exists() and not output_directory.is_symlink():
        return
    _prepare_output_directory(output_directory)
    json_path = output_directory / "field_session_summary.json"
    markdown_path = output_directory / "field_session_summary.md"
    if json_path.exists() or json_path.is_symlink():
        _atomic_write_summary_file(
            json_path,
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        )
    if markdown_path.exists() or markdown_path.is_symlink():
        reason = summary["integrity"]["summary_unavailable_reason"]
        _atomic_write_summary_file(
            markdown_path,
            "# Android field summary unavailable\n\n"
            f"- status: `NOT_RUN`\n- reason: `{reason}`\n"
            "- current encrypted evidence was not decrypted or summarized on the host.\n",
        )


def write_summary(
    source: Path,
    output_directory: Path,
    strict: bool = False,
    strict_mode: str = STRICT_MODE_ARCORE_METRIC,
    require_camera_advisory: bool = False,
    expected_source_commit: str | None = None,
    expected_apk_sha256: str | None = None,
    require_arcore_unsupported: bool = False,
) -> tuple[dict[str, Any], int]:
    snapshot = _snapshot_evidence(source)
    _assert_output_disjoint(snapshot["evidence_root"], output_directory)
    internal_summary = _summarize_snapshot(snapshot)
    if internal_summary.get("summary_available") is False:
        reasons = strict_failure_reasons(
            internal_summary,
            strict_mode=strict_mode,
            require_camera_advisory=require_camera_advisory,
            expected_source_commit=expected_source_commit,
            expected_apk_sha256=expected_apk_sha256,
            require_arcore_unsupported=require_arcore_unsupported,
        )
        integrity = internal_summary["integrity"]
        integrity["strict_mode"] = strict_mode if strict else "none"
        integrity["camera_advisory_required"] = require_camera_advisory
        integrity["arcore_unsupported_required"] = require_arcore_unsupported
        integrity["strict_failure_reasons"] = reasons
        summary = _public_summary(internal_summary)
        _replace_stale_outputs_with_unavailable(output_directory, summary)
        return summary, 1
    selected_session = _latest_session(internal_summary)
    reasons = strict_failure_reasons(
        internal_summary,
        strict_mode=strict_mode,
        require_camera_advisory=require_camera_advisory,
        expected_source_commit=expected_source_commit,
        expected_apk_sha256=expected_apk_sha256,
        require_arcore_unsupported=require_arcore_unsupported,
    )
    evidence_scope, arcore_unsupported_verified = evidence_classification(selected_session)
    selected_sample_summary = (
        selected_session["strict_evidence"]["camera_non_metric"]["sample_summary"]
        if selected_session is not None
        else camera_non_metric_sample_summary([])
    )
    internal_summary["camera_non_metric"][
        "strict_selected_session_samples"
    ] = selected_sample_summary
    integrity = internal_summary["integrity"]
    integrity["strict_mode"] = strict_mode if strict else "none"
    integrity["camera_advisory_required"] = require_camera_advisory
    integrity["arcore_unsupported_required"] = require_arcore_unsupported
    integrity["evidence_scope"] = evidence_scope
    integrity["arcore_unsupported_verified"] = arcore_unsupported_verified
    integrity["strict_selected_session_id"] = (
        selected_session["session_id"] if selected_session is not None else None
    )
    integrity["strict_failure_reasons"] = reasons
    summary = _public_summary(internal_summary)
    _prepare_output_directory(output_directory)
    _atomic_write_summary_file(
        output_directory / "field_session_summary.json",
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
    )
    _atomic_write_summary_file(
        output_directory / "field_session_summary.md", render_markdown(summary)
    )
    # Legacy plaintext summaries are diagnostic history only. They never confer
    # release or field-evidence credit, even when their semantic checks pass.
    return summary, 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Pulled directory, field_sessions directory, or one session directory")
    parser.add_argument(
        "--output",
        type=Path,
        help="Summary output directory (default: a sibling legacy-history directory)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help=(
            "Fail on malformed records, missing provenance, or missing evidence for the selected "
            "mode; the default ARCore mode requires telemetry, ARCore start, and loaded-model evidence"
        ),
    )
    parser.add_argument(
        "--strict-mode",
        choices=STRICT_MODES,
        default=STRICT_MODE_ARCORE_METRIC,
        help="Strict evidence contract (default keeps the existing ARCore metric contract)",
    )
    parser.add_argument(
        "--require-camera-advisory",
        action="store_true",
        help="In camera-non-metric strict mode, also require a valid emitted advisory event",
    )
    parser.add_argument(
        "--require-arcore-unsupported",
        action="store_true",
        help="In camera-non-metric strict mode, require a definitive runtime ARCore incompatibility origin",
    )
    parser.add_argument(
        "--expected-source-commit",
        help="Require the selected session provenance to match this 40-hex commit",
    )
    parser.add_argument(
        "--expected-apk-sha256",
        help="Require the selected session provenance to match this 64-hex APK SHA-256",
    )
    args = parser.parse_args()
    if args.strict_mode != STRICT_MODE_ARCORE_METRIC and not args.strict:
        parser.error("--strict-mode camera-non-metric requires --strict")
    if args.require_camera_advisory and (
        not args.strict or args.strict_mode != STRICT_MODE_CAMERA_NON_METRIC
    ):
        parser.error("--require-camera-advisory requires --strict --strict-mode camera-non-metric")
    if args.require_arcore_unsupported and (
        not args.strict or args.strict_mode != STRICT_MODE_CAMERA_NON_METRIC
    ):
        parser.error("--require-arcore-unsupported requires --strict --strict-mode camera-non-metric")
    if (args.expected_source_commit or args.expected_apk_sha256) and not args.strict:
        parser.error("expected provenance options require --strict")
    if args.expected_source_commit and not SOURCE_COMMIT_PATTERN.fullmatch(args.expected_source_commit):
        parser.error("--expected-source-commit must be exactly 40 hexadecimal characters")
    if args.expected_apk_sha256 and not SHA256_PATTERN.fullmatch(args.expected_apk_sha256):
        parser.error("--expected-apk-sha256 must be exactly 64 hexadecimal characters")
    output = args.output or args.source.parent / f"{args.source.name}-legacy-history-summary"
    summary, exit_code = write_summary(
        args.source,
        output,
        strict=args.strict,
        strict_mode=args.strict_mode,
        require_camera_advisory=args.require_camera_advisory,
        expected_source_commit=args.expected_source_commit,
        expected_apk_sha256=args.expected_apk_sha256,
        require_arcore_unsupported=args.require_arcore_unsupported,
    )
    integrity = summary["integrity"]
    if summary.get("summary_available") is False:
        print(
            "Android field summary unavailable: encrypted AEAD envelopes were preserved; "
            "no host plaintext summary, cache, or export was written."
        )
        return max(exit_code, 1)
    print(
        "LEGACY_HISTORY_ONLY_NOT_ELIGIBLE: "
        f"sessions={integrity['session_count']} "
        f"telemetry={summary['records']['telemetry']} "
        f"events={summary['records']['events']} "
        "release_credit=0 field_evidence_credit=0"
    )
    return max(exit_code, 1)


if __name__ == "__main__":
    raise SystemExit(main())
