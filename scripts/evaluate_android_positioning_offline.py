#!/usr/bin/env python3
"""Validate, split, tune, and evaluate sealed Android positioning traces."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Any, Iterable, Sequence


TRACE_SCHEMA = "walksafe.positioning_trace.v2"
LEGACY_TRACE_SCHEMA = "walksafe.positioning_trace.v1"
TRUTH_SCHEMA = "walksafe.survey_checkpoints.v1"
SPLIT_SCHEMA = "walksafe.positioning_split.v1"
SEARCH_SCHEMA = "walksafe.android_positioning_search.v1"
TUNED_SCHEMA = "walksafe.android_positioning_tuned.v1"
ATTESTATION_SCHEMA = "walksafe.positioning_field_attestation.v1"
REPORT_SCHEMA = "walksafe.android_positioning_report.v1"

TRACE_SOURCE = "ANDROID_DEBUG_RECORDER"
TRACE_TIMEBASE = "ANDROID_ELAPSED_REALTIME_NANOS"
FIELD_TRUTH_SOURCE = "FIELD_SURVEY"
SYNTHETIC_TRUTH_SOURCE = "SYNTHETIC_CONTRACT"
MOUNT = "PORTRAIT_BACK_OUT_TOP_UP_CHEST_CENTER"
SPLIT_ALGORITHM = "SHA256_SEED_NUL_SESSION_ID_ASC_V1"
MAX_TARGET_TRUTH_UNCERTAINTY_M = 0.5

EXIT_OK = 0
EXIT_TARGET_NOT_MET = 1
EXIT_INVALID = 2
EXIT_INSUFFICIENT_EVIDENCE = 3

OPAQUE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

HEADER_KEYS = frozenset(
    {
        "schema_version",
        "record_type",
        "session_id",
        "route_id",
        "scenario",
        "environment",
        "direction",
        "device_model",
        "android_api",
        "mount",
        "source_kind",
        "synthetic_contract_only",
        "timebase",
    }
)
POSITION_KEYS = frozenset(
    {
        "record_type",
        "seq",
        "elapsed_realtime_ns",
        "measurement_elapsed_realtime_ns",
        "measurement_utc_epoch_ms",
        "source",
        "raw_position",
        "filtered_position",
        "matched_position",
        "accuracy_m",
        "speed_mps",
        "bearing_deg",
        "gnss",
        "step_profile",
        "heading",
        "stationary",
        "route_match_evaluated",
    }
)
POSITION_REQUIRED_KEYS = frozenset(
    {"record_type", "seq", "elapsed_realtime_ns", "measurement_elapsed_realtime_ns", "source"}
)
CHECKPOINT_KEYS = frozenset(
    {
        "record_type",
        "seq",
        "elapsed_realtime_ns",
        "measurement_elapsed_realtime_ns",
        "measurement_utc_epoch_ms",
        "source",
        "checkpoint_id",
        "ordinal",
        "stationary_state",
        "stationary_duration_ms",
    }
)
FOOTER_KEYS = frozenset({"schema_version", "record_type", "record_count", "content_sha256"})
POSITION_POINT_KEYS = frozenset({"latitude_deg", "longitude_deg"})
GNSS_KEYS = frozenset(
    {"l5_available", "l5_used_satellite_count", "mean_cn0_db_hz", "risk", "measurement_noise_m"}
)
STEP_PROFILE_KEYS = frozenset(
    {"step_count", "step_detected", "current_step_length_m", "profile_step_length_m", "profile_sample_count"}
)
HEADING_KEYS = frozenset({"selected_deg", "source"})
STATIONARY_KEYS = frozenset({"stationary", "state", "zupt_applied"})
POSITION_SOURCES = frozenset({"gnss", "sensor_gnss_anchored", "sensor_monotonic_only"})
CHECKPOINT_SOURCES = frozenset({"checkpoint_gnss_anchored", "checkpoint_monotonic_only"})
UTC_ANCHORED_SOURCES = frozenset({"gnss", "sensor_gnss_anchored", "checkpoint_gnss_anchored"})


class ContractError(ValueError):
    """Raised when a versioned evidence contract is invalid."""


def _reject_constant(value: str) -> None:
    raise ContractError(f"non-finite JSON number is forbidden: {value}")


def _without_duplicate_members(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _decode_json(text: str, source: str) -> Any:
    try:
        return json.loads(
            text,
            object_pairs_hook=_without_duplicate_members,
            parse_constant=_reject_constant,
        )
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise ContractError(f"invalid JSON in {source}: {exc}") from exc


def _read_bytes(path: Path) -> bytes:
    try:
        return path.read_bytes()
    except OSError as exc:
        raise ContractError(f"cannot read {path}: {exc}") from exc


def _read_json(path: Path, context: str) -> dict[str, Any]:
    try:
        text = _read_bytes(path).decode("utf-8")
    except UnicodeError as exc:
        raise ContractError(f"{path} is not UTF-8: {exc}") from exc
    return _object(_decode_json(text, str(path)), context)


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_bytes(_read_bytes(path))


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=True,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _object(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ContractError(f"{context} must be an object")
    return value


def _exact_keys(value: dict[str, Any], keys: frozenset[str], context: str) -> None:
    actual = frozenset(value)
    if actual != keys:
        raise ContractError(
            f"{context} keys mismatch; unknown={sorted(actual - keys)}, missing={sorted(keys - actual)}"
        )


def _optional_keys(value: dict[str, Any], keys: frozenset[str], context: str) -> None:
    unknown = frozenset(value) - keys
    if unknown:
        raise ContractError(f"{context} has unknown keys: {sorted(unknown)}")
    if any(item is None for item in value.values()):
        raise ContractError(f"{context} must omit null fields")


def _required_optional_keys(
    value: dict[str, Any],
    required: frozenset[str],
    allowed: frozenset[str],
    context: str,
) -> None:
    actual = frozenset(value)
    if not required <= actual or not actual <= allowed:
        raise ContractError(
            f"{context} keys mismatch; unknown={sorted(actual - allowed)}, missing={sorted(required - actual)}"
        )
    if any(value[key] is None for key in actual - required):
        raise ContractError(f"{context} must omit optional fields whose value is null")


def _assert_recursively_sorted_keys(value: Any, context: str) -> None:
    if isinstance(value, dict):
        if list(value) != sorted(value):
            raise ContractError(f"{context} object keys are not in canonical sorted order")
        for key, child in value.items():
            _assert_recursively_sorted_keys(child, f"{context}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _assert_recursively_sorted_keys(child, f"{context}[{index}]")


def _has_unquoted_whitespace(text: str) -> bool:
    inside_string = False
    escaped = False
    for character in text:
        if inside_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                inside_string = False
        elif character == '"':
            inside_string = True
        elif character in " \t\r\n":
            return True
    return False


def _text(value: Any, context: str, maximum_length: int = 256) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > maximum_length:
        raise ContractError(f"{context} must be nonblank text of at most {maximum_length} characters")
    if any(ord(character) < 0x20 for character in value):
        raise ContractError(f"{context} contains a control character")
    return value


def _opaque(value: Any, context: str) -> str:
    text = _text(value, context, 96)
    if OPAQUE_RE.fullmatch(text) is None:
        raise ContractError(f"{context} must be an opaque ASCII token")
    return text


def _canonical_uuid(value: Any, context: str) -> str:
    text = _text(value, context, 36)
    try:
        parsed = uuid.UUID(text)
    except (ValueError, AttributeError) as exc:
        raise ContractError(f"{context} must be a canonical UUID") from exc
    if str(parsed) != text:
        raise ContractError(f"{context} must be a lowercase canonical UUID")
    return text


def _integer(value: Any, context: str, minimum: int = 0, maximum: int | None = None) -> int:
    if type(value) is not int or value < minimum or (maximum is not None and value > maximum):
        raise ContractError(f"{context} must be an integer in the allowed range")
    return value


def _number(
    value: Any,
    context: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
) -> float:
    if type(value) not in (int, float) or not math.isfinite(float(value)):
        raise ContractError(f"{context} must be a finite number")
    number = float(value)
    if minimum is not None and number < minimum:
        raise ContractError(f"{context} must be >= {minimum}")
    if maximum is not None and number > maximum:
        raise ContractError(f"{context} must be <= {maximum}")
    return number


def _boolean(value: Any, context: str) -> bool:
    if type(value) is not bool:
        raise ContractError(f"{context} must be a boolean")
    return value


def _nullable_number(
    value: Any,
    context: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
) -> float | None:
    if value is None:
        return None
    return _number(value, context, minimum=minimum, maximum=maximum)


def _point(value: Any, context: str) -> tuple[float, float] | None:
    if value is None:
        return None
    point = _object(value, context)
    _exact_keys(point, POSITION_POINT_KEYS, context)
    latitude = _number(point["latitude_deg"], f"{context}.latitude_deg", minimum=-90.0, maximum=90.0)
    longitude = _number(
        point["longitude_deg"], f"{context}.longitude_deg", minimum=-180.0, maximum=180.0
    )
    return longitude, latitude


def _validate_optional_telemetry(record: dict[str, Any], context: str) -> None:
    if "route_match_evaluated" in record:
        _boolean(record["route_match_evaluated"], f"{context}.route_match_evaluated")
    gnss = record.get("gnss")
    if gnss is not None:
        gnss = _object(gnss, f"{context}.gnss")
        _optional_keys(gnss, GNSS_KEYS, f"{context}.gnss")
        if "l5_available" in gnss:
            _boolean(gnss["l5_available"], f"{context}.gnss.l5_available")
        if "l5_used_satellite_count" in gnss:
            _integer(gnss["l5_used_satellite_count"], f"{context}.gnss.l5_used_satellite_count")
        if "mean_cn0_db_hz" in gnss:
            _number(gnss["mean_cn0_db_hz"], f"{context}.gnss.mean_cn0_db_hz", minimum=0.0)
        if "risk" in gnss:
            _opaque(gnss["risk"], f"{context}.gnss.risk")
        if "measurement_noise_m" in gnss:
            _number(gnss["measurement_noise_m"], f"{context}.gnss.measurement_noise_m", minimum=0.0)

    profile = record.get("step_profile")
    if profile is not None:
        profile = _object(profile, f"{context}.step_profile")
        _optional_keys(profile, STEP_PROFILE_KEYS, f"{context}.step_profile")
        for key in ("step_count", "profile_sample_count"):
            if key in profile:
                _integer(profile[key], f"{context}.step_profile.{key}")
        if "step_detected" in profile:
            _boolean(profile["step_detected"], f"{context}.step_profile.step_detected")
        for key in ("current_step_length_m", "profile_step_length_m"):
            if key in profile:
                _number(profile[key], f"{context}.step_profile.{key}", minimum=0.0)

    heading = record.get("heading")
    if heading is not None:
        heading = _object(heading, f"{context}.heading")
        _optional_keys(heading, HEADING_KEYS, f"{context}.heading")
        if "selected_deg" in heading:
            _number(heading["selected_deg"], f"{context}.heading.selected_deg", minimum=0.0, maximum=360.0)
        if "source" in heading:
            _opaque(heading["source"], f"{context}.heading.source")

    stationary = record.get("stationary")
    if stationary is not None:
        stationary = _object(stationary, f"{context}.stationary")
        _optional_keys(stationary, STATIONARY_KEYS, f"{context}.stationary")
        for key in ("stationary", "zupt_applied"):
            if key in stationary:
                _boolean(stationary[key], f"{context}.stationary.{key}")
        if "state" in stationary:
            _opaque(stationary["state"], f"{context}.stationary.state")


def _validate_measurement_time(
    record: dict[str, Any],
    context: str,
    allowed_sources: frozenset[str],
) -> None:
    elapsed_ns = _integer(record["elapsed_realtime_ns"], f"{context}.elapsed_realtime_ns")
    measurement_ns = _integer(
        record["measurement_elapsed_realtime_ns"],
        f"{context}.measurement_elapsed_realtime_ns",
    )
    if measurement_ns > elapsed_ns:
        raise ContractError(f"{context}.measurement_elapsed_realtime_ns must not follow record time")
    source = _text(record["source"], f"{context}.source", 32)
    if source not in allowed_sources:
        raise ContractError(f"{context}.source is unsupported for {record['record_type']}")
    has_utc = "measurement_utc_epoch_ms" in record
    if has_utc:
        _integer(record["measurement_utc_epoch_ms"], f"{context}.measurement_utc_epoch_ms")
    if has_utc != (source in UTC_ANCHORED_SOURCES):
        raise ContractError(f"{context}: source and measurement_utc_epoch_ms disagree")


def load_session_trace(path: Path) -> dict[str, Any]:
    raw = _read_bytes(path)
    if not raw or not raw.endswith(b"\n") or b"\r" in raw:
        raise ContractError(f"{path}: trace must use LF-terminated UTF-8 JSONL")
    raw_lines = raw.splitlines(keepends=True)
    if len(raw_lines) < 3:
        raise ContractError(f"{path}: trace needs header, record, and footer")
    records: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(raw_lines, start=1):
        if raw_line == b"\n":
            raise ContractError(f"{path}:{line_number}: blank lines are forbidden")
        try:
            text = raw_line[:-1].decode("utf-8")
        except UnicodeError as exc:
            raise ContractError(f"{path}:{line_number}: invalid UTF-8") from exc
        if _has_unquoted_whitespace(text):
            raise ContractError(f"{path}:{line_number}: JSON line is not compact canonical JSON")
        parsed = _object(_decode_json(text, f"{path}:{line_number}"), f"{path}:{line_number}")
        _assert_recursively_sorted_keys(parsed, f"{path}:{line_number}")
        records.append(parsed)

    header = records[0]
    _exact_keys(header, HEADER_KEYS, "trace header")
    if header["schema_version"] == LEGACY_TRACE_SCHEMA:
        raise ContractError("trace schema v1 is unsupported; export a new trace v2 session")
    if header["schema_version"] != TRACE_SCHEMA or header["record_type"] != "header":
        raise ContractError("trace header schema/record_type mismatch")
    session_id = _canonical_uuid(header["session_id"], "trace header.session_id")
    route_id = _canonical_uuid(header["route_id"], "trace header.route_id")
    for key in ("scenario", "environment", "direction", "device_model"):
        _opaque(header[key], f"trace header.{key}")
    _integer(header["android_api"], "trace header.android_api", 1, 100)
    if header["mount"] != MOUNT:
        raise ContractError(f"trace mount must be {MOUNT}")
    if header["source_kind"] != TRACE_SOURCE:
        raise ContractError(f"trace source_kind must be {TRACE_SOURCE}")
    if _boolean(header["synthetic_contract_only"], "trace header.synthetic_contract_only"):
        raise ContractError("Android recorder traces must set synthetic_contract_only=false")
    if header["timebase"] != TRACE_TIMEBASE:
        raise ContractError(f"trace timebase must be {TRACE_TIMEBASE}")

    footer = records[-1]
    _exact_keys(footer, FOOTER_KEYS, "trace footer")
    if footer["schema_version"] == LEGACY_TRACE_SCHEMA:
        raise ContractError("trace footer schema v1 is unsupported; mixed trace versions are forbidden")
    if footer["schema_version"] != TRACE_SCHEMA or footer["record_type"] != "footer":
        raise ContractError("trace footer schema/record_type mismatch")
    if _integer(footer["record_count"], "trace footer.record_count", 1) != len(records) - 2:
        raise ContractError("trace footer record_count mismatch")
    content_sha256 = footer["content_sha256"]
    if not isinstance(content_sha256, str) or SHA256_RE.fullmatch(content_sha256) is None:
        raise ContractError("trace footer.content_sha256 must be lowercase SHA-256")
    calculated_content_sha256 = _sha256_bytes(b"".join(raw_lines[:-1]))
    if content_sha256 != calculated_content_sha256:
        raise ContractError("trace footer content SHA-256 mismatch")

    body: list[dict[str, Any]] = []
    previous_seq = 0
    previous_time = -1
    for offset, record in enumerate(records[1:-1], start=2):
        context = f"{path}:{offset}"
        record_type = record.get("record_type")
        if record_type == "position_sample":
            _required_optional_keys(record, POSITION_REQUIRED_KEYS, POSITION_KEYS, context)
        elif record_type == "checkpoint_mark":
            _required_optional_keys(
                record,
                CHECKPOINT_KEYS - {"measurement_utc_epoch_ms"},
                CHECKPOINT_KEYS,
                context,
            )
        else:
            raise ContractError(f"{context}: unsupported record_type {record_type!r}")
        seq = _integer(record["seq"], f"{context}.seq", 1)
        elapsed_ns = _integer(record["elapsed_realtime_ns"], f"{context}.elapsed_realtime_ns")
        if seq != previous_seq + 1:
            raise ContractError("trace seq must be contiguous from 1")
        if elapsed_ns <= previous_time:
            raise ContractError("trace elapsed_realtime_ns must be strictly increasing")
        previous_seq = seq
        previous_time = elapsed_ns
        if record_type == "position_sample":
            _validate_measurement_time(record, context, POSITION_SOURCES)
            record["_raw_position"] = _point(record.get("raw_position"), f"{context}.raw_position")
            record["_filtered_position"] = _point(
                record.get("filtered_position"), f"{context}.filtered_position"
            )
            record["_matched_position"] = _point(
                record.get("matched_position"), f"{context}.matched_position"
            )
            _nullable_number(record.get("accuracy_m"), f"{context}.accuracy_m", minimum=0.0)
            _nullable_number(record.get("speed_mps"), f"{context}.speed_mps", minimum=0.0)
            bearing = _nullable_number(record.get("bearing_deg"), f"{context}.bearing_deg")
            if bearing is not None and not 0.0 <= bearing < 360.0:
                raise ContractError(f"{context}.bearing_deg must be in [0, 360)")
            _validate_optional_telemetry(record, context)
        else:
            _validate_measurement_time(record, context, CHECKPOINT_SOURCES)
            _opaque(record["checkpoint_id"], f"{context}.checkpoint_id")
            _integer(record["ordinal"], f"{context}.ordinal", 1)
            if record["stationary_state"] != "stationary":
                raise ContractError(f"{context}: checkpoint marker requires stationary state")
            if _integer(record["stationary_duration_ms"], f"{context}.stationary_duration_ms") < 1_500:
                raise ContractError(f"{context}: checkpoint marker requires 1500 ms stationary")
        body.append(record)
    if not any(record["record_type"] == "position_sample" for record in body):
        raise ContractError("trace needs at least one position_sample")
    if not any(record["record_type"] == "checkpoint_mark" for record in body):
        raise ContractError("trace needs at least one checkpoint_mark")
    return {
        "path": path,
        "sha256": _sha256_bytes(raw),
        "session_id": session_id,
        "route_id": route_id,
        "header": header,
        "records": body,
    }


def _inventory_digest(sessions: dict[str, dict[str, Any]]) -> str:
    value = [
        {"session_id": session_id, "trace_sha256": sessions[session_id]["sha256"]}
        for session_id in sorted(sessions)
    ]
    return _sha256_bytes(_canonical_bytes(value))


def _inventory_entries(sessions: dict[str, dict[str, Any]]) -> list[dict[str, str]]:
    return [
        {"session_id": session_id, "trace_sha256": sessions[session_id]["sha256"]}
        for session_id in sorted(sessions)
    ]


def load_trace_inventory(path: Path) -> dict[str, Any]:
    if path.is_file():
        paths = [path]
    elif path.is_dir():
        paths = sorted(item for item in path.iterdir() if item.is_file() and item.suffix == ".jsonl")
    else:
        raise ContractError(f"trace input is neither a file nor directory: {path}")
    if not paths:
        raise ContractError("trace inventory contains no .jsonl files")
    sessions: dict[str, dict[str, Any]] = {}
    for session_path in paths:
        session = load_session_trace(session_path)
        if session["session_id"] in sessions:
            raise ContractError(f"duplicate trace session_id: {session['session_id']}")
        sessions[session["session_id"]] = session
    return {"sessions": sessions, "sha256": _inventory_digest(sessions)}


def load_truth(path: Path) -> dict[str, Any]:
    root = _read_json(path, "truth")
    _exact_keys(
        root,
        frozenset(
            {"type", "schema_version", "source_kind", "coordinate_reference_system", "features"}
        ),
        "truth",
    )
    if root["type"] != "FeatureCollection" or root["schema_version"] != TRUTH_SCHEMA:
        raise ContractError("truth type/schema_version mismatch")
    if root["source_kind"] not in (FIELD_TRUTH_SOURCE, SYNTHETIC_TRUTH_SOURCE):
        raise ContractError("truth source_kind is unsupported")
    if root["coordinate_reference_system"] != "WGS84":
        raise ContractError("truth coordinate_reference_system must be WGS84")
    if not isinstance(root["features"], list) or not root["features"]:
        raise ContractError("truth.features must be a non-empty array")
    routes: dict[str, list[dict[str, Any]]] = {}
    by_id: dict[str, dict[str, Any]] = {}
    for index, value in enumerate(root["features"]):
        context = f"truth.features[{index}]"
        feature = _object(value, context)
        _exact_keys(feature, frozenset({"type", "id", "properties", "geometry"}), context)
        if feature["type"] != "Feature":
            raise ContractError(f"{context}.type must be Feature")
        checkpoint_id = _opaque(feature["id"], f"{context}.id")
        properties = _object(feature["properties"], f"{context}.properties")
        _exact_keys(
            properties,
            frozenset({"checkpoint_id", "route_id", "ordinal", "uncertainty_m"}),
            f"{context}.properties",
        )
        if properties["checkpoint_id"] != checkpoint_id:
            raise ContractError(f"{context}: id/checkpoint_id mismatch")
        if checkpoint_id in by_id:
            raise ContractError(f"duplicate checkpoint_id: {checkpoint_id}")
        route_id = _canonical_uuid(properties["route_id"], f"{context}.route_id")
        ordinal = _integer(properties["ordinal"], f"{context}.ordinal", 1)
        uncertainty_m = _number(properties["uncertainty_m"], f"{context}.uncertainty_m", minimum=0.0)
        geometry = _object(feature["geometry"], f"{context}.geometry")
        _exact_keys(geometry, frozenset({"type", "coordinates"}), f"{context}.geometry")
        if geometry["type"] != "Point":
            raise ContractError(f"{context}: geometry must be Point")
        coordinates = geometry["coordinates"]
        if not isinstance(coordinates, list) or len(coordinates) != 2:
            raise ContractError(f"{context}: coordinates must be [longitude, latitude]")
        longitude = _number(coordinates[0], f"{context}.longitude", minimum=-180.0, maximum=180.0)
        latitude = _number(coordinates[1], f"{context}.latitude", minimum=-90.0, maximum=90.0)
        item = {
            "checkpoint_id": checkpoint_id,
            "route_id": route_id,
            "ordinal": ordinal,
            "uncertainty_m": uncertainty_m,
            "longitude": longitude,
            "latitude": latitude,
        }
        routes.setdefault(route_id, []).append(item)
        by_id[checkpoint_id] = item
    for route_id, checkpoints in routes.items():
        checkpoints.sort(key=lambda item: item["ordinal"])
        if [item["ordinal"] for item in checkpoints] != list(range(1, len(checkpoints) + 1)):
            raise ContractError(f"route {route_id} checkpoint ordinals must be contiguous from 1")
    return {
        "source_kind": root["source_kind"],
        "routes": routes,
        "by_id": by_id,
        "sha256": _sha256_file(path),
    }


def validate_inventory_truth(inventory: dict[str, Any], truth: dict[str, Any]) -> None:
    for session_id, session in inventory["sessions"].items():
        if session["route_id"] not in truth["routes"]:
            raise ContractError(f"session {session_id} references an unknown route")
        expected = truth["routes"][session["route_id"]]
        marks = [record for record in session["records"] if record["record_type"] == "checkpoint_mark"]
        if len(marks) != len(expected):
            raise ContractError(f"session {session_id} must mark every route checkpoint exactly once")
        for mark, checkpoint in zip(marks, expected):
            if mark["checkpoint_id"] != checkpoint["checkpoint_id"]:
                raise ContractError(f"session {session_id} checkpoint id/order mismatch")
            if mark["ordinal"] != checkpoint["ordinal"]:
                raise ContractError(f"session {session_id} checkpoint ordinal mismatch")


def _load_inputs(trace_path: Path, truth_path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    inventory = load_trace_inventory(trace_path)
    truth = load_truth(truth_path)
    validate_inventory_truth(inventory, truth)
    return inventory, truth


def _bounds(value: Any, context: str, safe_min: float, safe_max: float) -> tuple[float, float]:
    if not isinstance(value, list) or len(value) != 2:
        raise ContractError(f"{context} must be [minimum, maximum]")
    lower = _number(value[0], f"{context}[0]", minimum=safe_min, maximum=safe_max)
    upper = _number(value[1], f"{context}[1]", minimum=safe_min, maximum=safe_max)
    if lower > upper:
        raise ContractError(f"{context} minimum exceeds maximum")
    return lower, upper


def load_search_config(path: Path) -> dict[str, Any]:
    config = _read_json(path, "search config")
    _exact_keys(
        config,
        frozenset(
            {
                "schema_version",
                "expected_device_model",
                "expected_mount",
                "bounds",
                "candidates",
                "safety_constraints",
                "target_thresholds",
            }
        ),
        "search config",
    )
    if config["schema_version"] != SEARCH_SCHEMA:
        raise ContractError("search config schema_version mismatch")
    _opaque(config["expected_device_model"], "search.expected_device_model")
    if config["expected_mount"] != MOUNT:
        raise ContractError(f"search expected_mount must be {MOUNT}")
    bounds = _object(config["bounds"], "search.bounds")
    _exact_keys(bounds, frozenset({"max_sample_age_ms", "max_accuracy_m"}), "search.bounds")
    age_bounds = _bounds(bounds["max_sample_age_ms"], "age bounds", 100.0, 5_000.0)
    accuracy_bounds = _bounds(bounds["max_accuracy_m"], "accuracy bounds", 0.5, 25.0)
    values = config["candidates"]
    if not isinstance(values, list) or not 1 <= len(values) <= 64:
        raise ContractError("search.candidates must contain 1..64 items")
    ids: set[str] = set()
    candidate_keys = frozenset({"id", "max_sample_age_ms", "max_accuracy_m"})
    for index, candidate_value in enumerate(values):
        candidate = _object(candidate_value, f"candidate[{index}]")
        _exact_keys(candidate, candidate_keys, f"candidate[{index}]")
        candidate_id = _opaque(candidate["id"], f"candidate[{index}].id")
        if candidate_id in ids:
            raise ContractError(f"duplicate candidate id: {candidate_id}")
        ids.add(candidate_id)
        age = _integer(candidate["max_sample_age_ms"], f"candidate[{index}].max_sample_age_ms", 1)
        accuracy = _number(candidate["max_accuracy_m"], f"candidate[{index}].max_accuracy_m", minimum=0.0)
        if not age_bounds[0] <= age <= age_bounds[1] or not accuracy_bounds[0] <= accuracy <= accuracy_bounds[1]:
            raise ContractError(f"candidate {candidate_id} is outside declared bounds")

    safety = _object(config["safety_constraints"], "safety_constraints")
    _exact_keys(
        safety,
        frozenset(
            {
                "max_checkpoint_uncertainty_m",
                "min_train_sessions",
                "min_train_checkpoint_marks",
                "min_train_availability",
                "min_holdout_sessions",
                "min_holdout_checkpoint_marks",
            }
        ),
        "safety_constraints",
    )
    _number(
        safety["max_checkpoint_uncertainty_m"],
        "safety.max_checkpoint_uncertainty_m",
        minimum=0.01,
        maximum=MAX_TARGET_TRUTH_UNCERTAINTY_M,
    )
    for key in (
        "min_train_sessions",
        "min_train_checkpoint_marks",
        "min_holdout_sessions",
        "min_holdout_checkpoint_marks",
    ):
        _integer(safety[key], f"safety.{key}", 1)
    _number(safety["min_train_availability"], "safety.min_train_availability", minimum=0.5, maximum=1.0)

    target = _object(config["target_thresholds"], "target_thresholds")
    _exact_keys(
        target,
        frozenset({"p50_max_m", "p95_max_m", "within_2m_coverage_min", "availability_min"}),
        "target_thresholds",
    )
    p50 = _number(target["p50_max_m"], "target.p50_max_m", minimum=0.0, maximum=2.0)
    p95 = _number(target["p95_max_m"], "target.p95_max_m", minimum=0.0, maximum=10.0)
    if p95 < p50:
        raise ContractError("target p95 limit must not be below p50 limit")
    _number(target["within_2m_coverage_min"], "target.coverage", minimum=0.5, maximum=1.0)
    _number(target["availability_min"], "target.availability", minimum=0.5, maximum=1.0)
    return config


def create_split(
    inventory: dict[str, Any],
    truth: dict[str, Any],
    *,
    seed: str,
    holdout_fraction: float,
) -> dict[str, Any]:
    _opaque(seed, "split seed")
    fraction = _number(holdout_fraction, "holdout_fraction", minimum=0.1, maximum=0.5)
    session_ids = sorted(inventory["sessions"])
    if len(session_ids) < 2:
        raise ContractError("at least two whole sessions are required for splitting")
    ranked = sorted(
        session_ids,
        key=lambda item: hashlib.sha256(f"{seed}\0{item}".encode("ascii")).hexdigest(),
    )
    holdout_count = max(1, min(len(ranked) - 1, math.ceil(len(ranked) * fraction)))
    return {
        "schema_version": SPLIT_SCHEMA,
        "algorithm": SPLIT_ALGORITHM,
        "seed": seed,
        "holdout_fraction": fraction,
        "trace_sha256": inventory["sha256"],
        "truth_sha256": truth["sha256"],
        "input_session_hashes": _inventory_entries(inventory["sessions"]),
        "train_session_ids": sorted(ranked[holdout_count:]),
        "holdout_session_ids": sorted(ranked[:holdout_count]),
    }


def load_split(path: Path, inventory: dict[str, Any], truth: dict[str, Any]) -> dict[str, Any]:
    split = _read_json(path, "split manifest")
    expected_keys = frozenset(
        {
            "schema_version",
            "algorithm",
            "seed",
            "holdout_fraction",
            "trace_sha256",
            "truth_sha256",
            "input_session_hashes",
            "train_session_ids",
            "holdout_session_ids",
        }
    )
    _exact_keys(split, expected_keys, "split manifest")
    if split["schema_version"] != SPLIT_SCHEMA or split["algorithm"] != SPLIT_ALGORITHM:
        raise ContractError("split schema/algorithm mismatch")
    expected = create_split(
        inventory,
        truth,
        seed=_opaque(split["seed"], "split.seed"),
        holdout_fraction=_number(split["holdout_fraction"], "split.holdout_fraction"),
    )
    if split != expected:
        raise ContractError("split manifest does not match deterministic recomputation")
    if set(split["train_session_ids"]) & set(split["holdout_session_ids"]):
        raise ContractError("train/holdout session leakage detected")
    return split


def _percentile(values: list[float], ratio: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * ratio
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def _distance_m(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    radius_m = 6_371_008.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = phi2 - phi1
    delta_lambda = math.radians(lon2 - lon1)
    value = math.sin(delta_phi / 2.0) ** 2 + (
        math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    return 2.0 * radius_m * math.asin(min(1.0, math.sqrt(value)))


def checkpoint_metrics(
    inventory: dict[str, Any],
    truth: dict[str, Any],
    session_ids: Iterable[str],
    candidate: dict[str, Any],
    max_checkpoint_uncertainty_m: float,
) -> dict[str, Any]:
    errors: list[float] = []
    marks_total = 0
    eligible_marks = 0
    available = 0
    for session_id in sorted(session_ids):
        latest: dict[str, Any] | None = None
        for record in inventory["sessions"][session_id]["records"]:
            if record["record_type"] == "position_sample":
                latest = record
                continue
            marks_total += 1
            checkpoint = truth["by_id"][record["checkpoint_id"]]
            if checkpoint["uncertainty_m"] > max_checkpoint_uncertainty_m:
                continue
            eligible_marks += 1
            if latest is None or latest["_filtered_position"] is None or latest.get("accuracy_m") is None:
                continue
            if latest["seq"] >= record["seq"] or latest["elapsed_realtime_ns"] >= record["elapsed_realtime_ns"]:
                raise ContractError("checkpoint causal join attempted a non-prior sample")
            age_ns = record["elapsed_realtime_ns"] - latest["elapsed_realtime_ns"]
            if age_ns > candidate["max_sample_age_ms"] * 1_000_000:
                continue
            if latest["accuracy_m"] > candidate["max_accuracy_m"]:
                continue
            available += 1
            filtered_lon, filtered_lat = latest["_filtered_position"]
            errors.append(
                _distance_m(
                    filtered_lon,
                    filtered_lat,
                    checkpoint["longitude"],
                    checkpoint["latitude"],
                )
            )
    within_2m = sum(error <= 2.0 for error in errors)
    return {
        "checkpoint_marks_total": marks_total,
        "checkpoint_marks_target_eligible": eligible_marks,
        "positions_available": available,
        "availability": available / eligible_marks if eligible_marks else 0.0,
        "error_sample_count": len(errors),
        "error_p50_m": _percentile(errors, 0.50),
        "error_p95_m": _percentile(errors, 0.95),
        "within_2m_count": within_2m,
        "within_2m_coverage": within_2m / len(errors) if errors else 0.0,
    }


def _partition_inventory_sha256(inventory: dict[str, Any], session_ids: Iterable[str]) -> str:
    entries = [
        {"session_id": session_id, "trace_sha256": inventory["sessions"][session_id]["sha256"]}
        for session_id in sorted(session_ids)
    ]
    return _sha256_bytes(_canonical_bytes(entries))


def _truth_uncertainty_reason(truth: dict[str, Any], maximum: float) -> list[str]:
    if any(item["uncertainty_m"] > maximum for item in truth["by_id"].values()):
        return ["CHECKPOINT_UNCERTAINTY_EXCEEDS_0_5_M"]
    return []


def tune_candidates(
    inventory: dict[str, Any],
    truth: dict[str, Any],
    split: dict[str, Any],
    search: dict[str, Any],
    *,
    search_sha256: str,
) -> dict[str, Any]:
    train_ids = split["train_session_ids"]
    safety = search["safety_constraints"]
    reasons = _truth_uncertainty_reason(truth, safety["max_checkpoint_uncertainty_m"])
    if len(train_ids) < safety["min_train_sessions"]:
        reasons.append("INSUFFICIENT_TRAIN_SESSIONS")
    train_marks = sum(
        record["record_type"] == "checkpoint_mark"
        for session_id in train_ids
        for record in inventory["sessions"][session_id]["records"]
    )
    if train_marks < safety["min_train_checkpoint_marks"]:
        reasons.append("INSUFFICIENT_TRAIN_CHECKPOINTS")
    result = {
        "schema_version": TUNED_SCHEMA,
        "status": "INSUFFICIENT_EVIDENCE" if reasons else "TUNED",
        "reason_codes": sorted(set(reasons)),
        "trace_sha256": inventory["sha256"],
        "truth_sha256": truth["sha256"],
        "search_config_sha256": search_sha256,
        "training_partition": "train",
        "training_session_ids": list(train_ids),
        "training_inventory_sha256": _partition_inventory_sha256(inventory, train_ids),
        "holdout_referenced": False,
        "metric_position_source": "FILTERED_UNSNAPPED",
        "selected_candidate": None,
        "train_metrics": None,
    }
    if reasons:
        return result
    ranked: list[tuple[tuple[Any, ...], dict[str, Any], dict[str, Any]]] = []
    for candidate in search["candidates"]:
        metrics = checkpoint_metrics(
            inventory,
            truth,
            train_ids,
            candidate,
            safety["max_checkpoint_uncertainty_m"],
        )
        if metrics["availability"] < safety["min_train_availability"]:
            continue
        if metrics["error_p50_m"] is None or metrics["error_p95_m"] is None:
            continue
        score = (
            metrics["error_p95_m"],
            metrics["error_p50_m"],
            -metrics["within_2m_coverage"],
            -metrics["availability"],
            candidate["id"],
        )
        ranked.append((score, candidate, metrics))
    if not ranked:
        result["status"] = "INSUFFICIENT_EVIDENCE"
        result["reason_codes"] = ["NO_SAFE_CANDIDATE_WITH_MINIMUM_AVAILABILITY"]
        return result
    _, selected, metrics = min(ranked, key=lambda item: item[0])
    result["selected_candidate"] = selected
    result["train_metrics"] = metrics
    return result


def load_tuned_config(
    path: Path,
    inventory: dict[str, Any],
    truth: dict[str, Any],
    split: dict[str, Any],
    search: dict[str, Any],
    search_sha256: str,
) -> dict[str, Any]:
    tuned = _read_json(path, "tuned config")
    expected = tune_candidates(
        inventory,
        truth,
        split,
        search,
        search_sha256=search_sha256,
    )
    _exact_keys(tuned, frozenset(expected), "tuned config")
    if tuned != expected:
        raise ContractError("tuned config does not match train-only deterministic recomputation")
    if tuned["holdout_referenced"] is not False:
        raise ContractError("tuned config references holdout")
    if set(tuned["training_session_ids"]) & set(split["holdout_session_ids"]):
        raise ContractError("tuned config contains holdout leakage")
    return tuned


def load_attestation(
    path: Path,
    inventory: dict[str, Any],
    truth: dict[str, Any],
    search: dict[str, Any],
) -> dict[str, Any]:
    attestation = _read_json(path, "field attestation")
    _exact_keys(
        attestation,
        frozenset(
            {
                "schema_version",
                "trace_sha256",
                "truth_sha256",
                "surveyed_source",
                "survey_method",
                "maximum_checkpoint_uncertainty_m",
                "expected_device_model",
                "expected_mount",
                "operator_confirmed",
            }
        ),
        "field attestation",
    )
    if attestation["schema_version"] != ATTESTATION_SCHEMA:
        raise ContractError("field attestation schema_version mismatch")
    for key, expected in (("trace_sha256", inventory["sha256"]), ("truth_sha256", truth["sha256"])):
        value = attestation[key]
        if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None or value != expected:
            raise ContractError(f"field attestation {key} mismatch")
    _text(attestation["surveyed_source"], "attestation.surveyed_source")
    _text(attestation["survey_method"], "attestation.survey_method")
    stated_uncertainty = _number(
        attestation["maximum_checkpoint_uncertainty_m"],
        "attestation.maximum_checkpoint_uncertainty_m",
        minimum=0.0,
    )
    actual_uncertainty = max(item["uncertainty_m"] for item in truth["by_id"].values())
    if stated_uncertainty != actual_uncertainty:
        raise ContractError("field attestation uncertainty does not match hashed truth")
    if attestation["expected_device_model"] != search["expected_device_model"]:
        raise ContractError("field attestation expected_device_model mismatch")
    if attestation["expected_mount"] != search["expected_mount"]:
        raise ContractError("field attestation expected_mount mismatch")
    _boolean(attestation["operator_confirmed"], "attestation.operator_confirmed")
    return attestation


def evaluate_holdout(
    inventory: dict[str, Any],
    truth: dict[str, Any],
    split: dict[str, Any],
    search: dict[str, Any],
    tuned: dict[str, Any],
    attestation: dict[str, Any] | None,
) -> dict[str, Any]:
    safety = search["safety_constraints"]
    reasons: list[str] = []
    if attestation is None:
        reasons.append("FIELD_ATTESTATION_REQUIRED")
    else:
        _exact_keys(
            attestation,
            frozenset(
                {
                    "schema_version",
                    "trace_sha256",
                    "truth_sha256",
                    "surveyed_source",
                    "survey_method",
                    "maximum_checkpoint_uncertainty_m",
                    "expected_device_model",
                    "expected_mount",
                    "operator_confirmed",
                }
            ),
            "field attestation",
        )
        if attestation["schema_version"] != ATTESTATION_SCHEMA:
            raise ContractError("field attestation schema_version mismatch")
        if attestation["trace_sha256"] != inventory["sha256"]:
            raise ContractError("field attestation trace_sha256 mismatch")
        if attestation["truth_sha256"] != truth["sha256"]:
            raise ContractError("field attestation truth_sha256 mismatch")
        _text(attestation["surveyed_source"], "attestation.surveyed_source")
        _text(attestation["survey_method"], "attestation.survey_method")
        actual_uncertainty = max(item["uncertainty_m"] for item in truth["by_id"].values())
        if attestation["maximum_checkpoint_uncertainty_m"] != actual_uncertainty:
            raise ContractError("field attestation uncertainty mismatch")
        if attestation["expected_device_model"] != search["expected_device_model"]:
            raise ContractError("field attestation expected_device_model mismatch")
        if attestation["expected_mount"] != search["expected_mount"]:
            raise ContractError("field attestation expected_mount mismatch")
        if not _boolean(attestation["operator_confirmed"], "attestation.operator_confirmed"):
            reasons.append("OPERATOR_CONFIRMATION_REQUIRED")
    if truth["source_kind"] != FIELD_TRUTH_SOURCE:
        reasons.append("TRUTH_NOT_FIELD_SURVEY")
    reasons.extend(_truth_uncertainty_reason(truth, safety["max_checkpoint_uncertainty_m"]))
    for session in inventory["sessions"].values():
        if session["header"]["device_model"] != search["expected_device_model"]:
            reasons.append("DEVICE_MODEL_MISMATCH")
        if session["header"]["mount"] != search["expected_mount"]:
            reasons.append("MOUNT_MISMATCH")
    if tuned["status"] != "TUNED" or tuned["selected_candidate"] is None:
        reasons.append("NO_TUNED_TRAIN_CANDIDATE")
    holdout_ids = split["holdout_session_ids"]
    if len(holdout_ids) < safety["min_holdout_sessions"]:
        reasons.append("INSUFFICIENT_HOLDOUT_SESSIONS")
    holdout_marks = sum(
        record["record_type"] == "checkpoint_mark"
        for session_id in holdout_ids
        for record in inventory["sessions"][session_id]["records"]
    )
    if holdout_marks < safety["min_holdout_checkpoint_marks"]:
        reasons.append("INSUFFICIENT_HOLDOUT_CHECKPOINTS")
    metrics = None
    if tuned["selected_candidate"] is not None:
        metrics = checkpoint_metrics(
            inventory,
            truth,
            holdout_ids,
            tuned["selected_candidate"],
            safety["max_checkpoint_uncertainty_m"],
        )
    reason_codes = sorted(set(reasons))
    target_eligible = not reason_codes
    status = "INSUFFICIENT_EVIDENCE"
    if target_eligible and metrics is not None:
        target = search["target_thresholds"]
        met = (
            metrics["error_p50_m"] is not None
            and metrics["error_p95_m"] is not None
            and metrics["error_p50_m"] <= target["p50_max_m"]
            and metrics["error_p95_m"] <= target["p95_max_m"]
            and metrics["within_2m_coverage"] >= target["within_2m_coverage_min"]
            and metrics["availability"] >= target["availability_min"]
        )
        status = "TARGET_MET" if met else "TARGET_NOT_MET"
    return {
        "schema_version": REPORT_SCHEMA,
        "status": status,
        "target_eligible": target_eligible,
        "reason_codes": reason_codes,
        "trace_sha256": inventory["sha256"],
        "truth_sha256": truth["sha256"],
        "partition": "holdout",
        "holdout_session_ids": list(holdout_ids),
        "metric_position_source": "FILTERED_UNSNAPPED",
        "causal_join": "LATEST_PRIOR_SAMPLE_ONLY",
        "interpolation": "FORBIDDEN",
        "metrics": metrics,
    }


def _write_json(path: Path | None, value: dict[str, Any]) -> None:
    payload = json.dumps(value, ensure_ascii=True, allow_nan=False, indent=2, sort_keys=True) + "\n"
    if path is None:
        sys.stdout.write(payload)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_name: str | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    except OSError as exc:
        raise ContractError(f"cannot write {path}: {exc}") from exc
    finally:
        if temporary_name is not None:
            try:
                Path(temporary_name).unlink(missing_ok=True)
            except OSError:
                pass


def _add_inputs(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--trace", type=Path, required=True, help="sealed session JSONL or directory")
    parser.add_argument("--truth", type=Path, required=True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    _add_inputs(validate)
    validate.add_argument("--split", type=Path)
    validate.add_argument("--search-config", type=Path)
    validate.add_argument("--attestation", type=Path)
    validate.add_argument("--output", type=Path)
    split = commands.add_parser("split")
    _add_inputs(split)
    split.add_argument("--seed", default="walksafe-positioning-v1")
    split.add_argument("--holdout-fraction", type=float, default=0.25)
    split.add_argument("--output", type=Path, required=True)
    tune = commands.add_parser("tune")
    _add_inputs(tune)
    tune.add_argument("--split", type=Path, required=True)
    tune.add_argument("--search-config", type=Path, required=True)
    tune.add_argument("--output", type=Path, required=True)
    evaluate = commands.add_parser("evaluate")
    _add_inputs(evaluate)
    evaluate.add_argument("--split", type=Path, required=True)
    evaluate.add_argument("--search-config", type=Path, required=True)
    evaluate.add_argument("--tuned-config", type=Path, required=True)
    evaluate.add_argument("--attestation", type=Path)
    evaluate.add_argument("--output", type=Path, required=True)
    return parser


def run(arguments: argparse.Namespace) -> int:
    inventory, truth = _load_inputs(arguments.trace, arguments.truth)
    if arguments.command == "validate":
        search = load_search_config(arguments.search_config) if arguments.search_config else None
        if arguments.split:
            load_split(arguments.split, inventory, truth)
        if arguments.attestation:
            if search is None:
                raise ContractError("--attestation validation also requires --search-config")
            load_attestation(arguments.attestation, inventory, truth, search)
        _write_json(
            arguments.output,
            {
                "schema_version": REPORT_SCHEMA,
                "status": "VALID",
                "trace_sha256": inventory["sha256"],
                "truth_sha256": truth["sha256"],
                "session_count": len(inventory["sessions"]),
                "checkpoint_count": len(truth["by_id"]),
            },
        )
        return EXIT_OK
    if arguments.command == "split":
        result = create_split(
            inventory,
            truth,
            seed=arguments.seed,
            holdout_fraction=arguments.holdout_fraction,
        )
        _write_json(arguments.output, result)
        return EXIT_OK
    split = load_split(arguments.split, inventory, truth)
    search = load_search_config(arguments.search_config)
    search_sha256 = _sha256_file(arguments.search_config)
    if arguments.command == "tune":
        result = tune_candidates(
            inventory,
            truth,
            split,
            search,
            search_sha256=search_sha256,
        )
        _write_json(arguments.output, result)
        return EXIT_OK if result["status"] == "TUNED" else EXIT_INSUFFICIENT_EVIDENCE
    tuned = load_tuned_config(
        arguments.tuned_config,
        inventory,
        truth,
        split,
        search,
        search_sha256,
    )
    attestation = (
        load_attestation(arguments.attestation, inventory, truth, search)
        if arguments.attestation is not None
        else None
    )
    result = evaluate_holdout(inventory, truth, split, search, tuned, attestation)
    _write_json(arguments.output, result)
    if result["status"] == "TARGET_MET":
        return EXIT_OK
    if result["status"] == "TARGET_NOT_MET":
        return EXIT_TARGET_NOT_MET
    return EXIT_INSUFFICIENT_EVIDENCE


def main(argv: Sequence[str] | None = None) -> int:
    try:
        return run(build_parser().parse_args(argv))
    except ContractError as exc:
        sys.stderr.write(
            json.dumps(
                {"schema_version": REPORT_SCHEMA, "status": "INVALID_INPUT", "error": str(exc)},
                ensure_ascii=True,
                sort_keys=True,
            )
            + "\n"
        )
        return EXIT_INVALID


if __name__ == "__main__":
    raise SystemExit(main())
