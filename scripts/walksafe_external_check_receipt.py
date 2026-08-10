#!/usr/bin/env python3
"""Validate structured receipts for release checks that require real external observation."""
from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


SCHEMA_VERSION = "walksafe.external-check-receipt.v1"
COMMIT = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
PLACEHOLDERS = ("todo", "tbd", "placeholder", "example", "replace", "미정", "예시")
BASE_RECEIPT_FIELDS = {
    "schema_version",
    "check_id",
    "result_status",
    "source_commit",
    "release_artifacts_sha256",
    "operator",
    "started_at",
    "completed_at",
    "environment",
    "observations",
    "pass_criteria",
    "result",
}
OBSERVATION_FIELDS = {"step_id", "observed_at", "latency_ms", "status", "note"}
CRITERION_FIELDS = {"criterion_id", "passed", "observation_step_ids"}
ARTIFACT_RECORD_FIELDS = {"path", "bytes", "sha256"}
ANDROID_CURRENT_DEVICE_INPUT_FORMAT = "current_device_records_v1"
UNATTESTED_ANDROID_PHYSICAL_CHECKS = {
    "android_arcore_unsupported_camera_field",
    "android_signed_release_physical_smoke",
}
UNATTESTED_ANDROID_REASON = (
    "has no authenticated on-device acquisition/export attestation chain; "
    "eligibility is UNATTESTED_NOT_ELIGIBLE and the physical Android check remains NOT_RUN"
)
ANDROID_FIELD_FORBIDDEN_KEYS = {
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
    "token",
    "api_key",
    "client_secret",
    "private_key",
    "credential",
}
ANDROID_FIELD_SENSITIVE_KEY_SUFFIXES = (
    "_token",
    "_secret",
    "_password",
    "_credential",
    "_authorization",
)
ARCORE_SUPPORTED_STATES = {
    "SUPPORTED_INSTALLED",
    "SUPPORTED_APK_TOO_OLD",
    "SUPPORTED_NOT_INSTALLED",
}
ARCORE_UNSUPPORTED_ORIGINS = {
    ("arcore_availability_unsupported", "UNSUPPORTED_DEVICE_NOT_CAPABLE")
} | {("arcore_session_incompatible", state) for state in ARCORE_SUPPORTED_STATES}
ANDROID_SAMPLE_FIELDS = {
    "elapsed_realtime_ms",
    "inference_ms",
    "detection_count",
    "capability_tier",
    "camera_permission_granted",
    "camera_fallback_running",
    "detector_available",
    "imu_fresh",
    "tmap_route_active",
    "metric",
    "reports_allowed",
    "state",
}


class ExternalCheckReceiptFailure(ValueError):
    pass


CHECK_CONTRACTS: dict[str, dict[str, Any]] = {
    "pwa_mobile_install_update": {
        "environment": ("device", "os", "browser", "deployed_url"),
        "steps": (
            "page_loaded",
            "install_prompt_accepted",
            "standalone_launched",
            "update_applied",
        ),
        "criteria": ("installable", "standalone_launch", "updated_release_active"),
        "result": (
            "installed",
            "standalone_launch",
            "service_worker_update_observed",
            "initial_source_commit",
            "updated_source_commit",
        ),
    },
    "voice_report_microphone_e2e": {
        "environment": ("device", "os", "browser", "microphone", "deployed_url"),
        "steps": (
            "microphone_permission_granted",
            "command_spoken",
            "transcript_confirmed",
            "report_submitted",
            "report_verified",
        ),
        "criteria": ("live_microphone_capture", "voice_intent_recognized", "report_persisted"),
        "result": ("report_created", "report_id", "recognized_intent"),
    },
    "web_phone_outdoor_navigation": {
        "environment": (
            "device",
            "os",
            "browser",
            "deployed_url",
            "route_label",
            "gps_accuracy_m",
            "weather",
        ),
        "steps": (
            "geolocation_permission_granted",
            "route_requested",
            "navigation_started",
            "guidance_received",
            "destination_arrived",
        ),
        "criteria": ("physical_outdoor_gps", "tmap_route_followed", "guidance_reached_destination"),
        "result": (
            "navigation_session_id",
            "route_provider",
            "guidance_event_count",
            "destination_outcome",
        ),
    },
    "web_non_metric_advisory_field": {
        "environment": (
            "device",
            "os",
            "browser",
            "deployed_url",
            "device_kind",
            "camera_facing",
            "route_provider",
        ),
        "steps": (
            "rear_camera_started",
            "tmap_navigation_active",
            "distinct_frames_confirmed",
            "stabilization_completed",
            "low_directional_advisory_observed",
            "authority_boundaries_verified",
            "no_haptic_verified",
            "gate_loss_triggered",
            "tmap_only_fallback_observed",
        ),
        "criteria": (
            "physical_rear_camera",
            "active_tmap_authoritative",
            "three_distinct_frames_and_700ms",
            "low_directional_advisory_only",
            "no_metric_distance_steps_report_or_route_change_authority",
            "no_haptic",
            "gate_loss_falls_back_to_tmap_only",
        ),
        "result": (
            "physical_device",
            "camera_facing",
            "route_provider",
            "tmap_navigation_active",
            "distinct_frame_count",
            "stabilization_ms",
            "advisory_tier",
            "advisory_level",
            "advisory_direction",
            "metric_authority",
            "distance_authority",
            "step_authority",
            "report_authority",
            "route_change_authority",
            "haptic_observed",
            "fallback_after_gate_loss",
        ),
    },
    "web_accessibility_tts_haptic": {
        "environment": (
            "device",
            "os",
            "browser",
            "screen_reader",
            "deployed_url",
            "device_kind",
        ),
        "steps": (
            "screen_reader_enabled",
            "tts_guidance_observed",
            "haptic_alert_observed",
            "risk_priority_observed",
            "unavailable_state_observed",
        ),
        "criteria": (
            "screen_reader_access",
            "tts_delivery",
            "haptic_delivery",
            "risk_priority",
            "unavailable_state_announced",
        ),
        "result": (
            "tts_verified",
            "haptic_verified",
            "risk_priority_verified",
            "unavailable_state_verified",
        ),
    },
    "android_arcore_unsupported_camera_field": {
        "environment": (
            "device",
            "os",
            "application_id",
            "device_kind",
            "camera_api",
            "imu_source",
            "route_provider",
        ),
        "steps": (
            "apk_installed",
            "cold_start_completed",
            "arcore_unsupported_confirmed",
            "camerax_started",
            "fresh_imu_confirmed",
            "tmap_navigation_active",
            "continuity_15m_completed",
            "low_directional_advisory_observed",
            "tts_or_talkback_observed",
            "no_vibration_verified",
            "gate_loss_triggered",
            "tmap_only_fallback_observed",
        ),
        "criteria": (
            "tested_bound_field_apk",
            "physical_arcore_unsupported_origin",
            "camerax_fresh_imu_active_tmap",
            "fifteen_minute_continuity",
            "low_directional_advisory_delivery",
            "tts_or_talkback_delivery",
            "no_vibration",
            "gate_loss_falls_back_to_tmap_only",
        ),
        "result": (
            "field_apk_sha256",
            "release_apk_sha256",
            "field_apk_build_type",
            "physical_evidence_scope",
            "release_apk_physically_executed",
            "sampling_contract",
            "installed",
            "cold_start",
            "physical_device",
            "arcore_unsupported_origin",
            "arcore_availability_state",
            "camera_api",
            "imu_fresh",
            "route_provider",
            "tmap_navigation_active",
            "continuity_seconds",
            "advisory_level",
            "advisory_direction",
            "accessibility_delivery",
            "vibration_observed",
            "fallback_after_gate_loss",
        ),
    },
    "android_signed_release_physical_smoke": {
        "environment": (
            "device",
            "os",
            "application_id",
            "device_kind",
            "camera_api",
            "route_provider",
            "deployment",
        ),
        "steps": (
            "signed_release_apk_installed",
            "installed_apk_hash_verified",
            "cold_start_completed",
            "arcore_hardware_unsupported_confirmed",
            "camerax_fallback_started",
            "detector_frame_observed",
            "tmap_navigation_active",
            "low_directional_advisory_observed",
            "tts_or_talkback_observed",
            "no_vibration_verified",
            "gate_loss_triggered",
            "tmap_only_fallback_observed",
        ),
        "criteria": (
            "verified_signed_release_artifact",
            "physical_arcore_hardware_unsupported_device",
            "signed_release_cold_start",
            "camerax_detector_fallback_operational",
            "active_tmap_authoritative",
            "low_non_metric_advisory_only",
            "tts_or_talkback_delivery",
            "no_vibration",
            "gate_loss_falls_back_to_tmap_only",
        ),
        "result": (
            "release_apk_sha256",
            "installed_apk_sha256",
            "release_apk_build_type",
            "release_apk_physically_executed",
            "installed",
            "cold_start",
            "physical_device",
            "arcore_unsupported_origin",
            "arcore_availability_state",
            "camera_api",
            "camera_fallback_running",
            "detector_available",
            "route_provider",
            "tmap_navigation_active",
            "advisory_level",
            "advisory_direction",
            "metric",
            "tmap_authoritative",
            "distance_authority",
            "step_authority",
            "route_change_authority",
            "reports_allowed",
            "accessibility_delivery",
            "vibration_observed",
            "fallback_after_gate_loss",
        ),
    },
    "tmap_live_route_smoke": {
        "environment": ("provider", "endpoint", "deployment"),
        "steps": ("request_sent", "response_received", "route_contract_checked"),
        "criteria": ("provider_success", "pedestrian_geometry_present", "guide_instructions_present"),
        "result": ("provider", "http_status", "route_id", "coordinate_count", "instruction_count"),
    },
}


def _meaningful(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    lowered = value.casefold()
    return not any(token in lowered for token in PLACEHOLDERS)


def _parse_timestamp(value: Any, context: str) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise ExternalCheckReceiptFailure(f"{context} is required")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ExternalCheckReceiptFailure(f"{context} must be ISO-8601") from exc
    if parsed.tzinfo is None:
        raise ExternalCheckReceiptFailure(f"{context} must include a timezone")
    return parsed.astimezone(UTC)


def _decode_unique_json(payload: bytes, context: str) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ExternalCheckReceiptFailure(f"{context} contains duplicate JSON key {key!r}")
            result[key] = value
        return result

    try:
        decoded = json.loads(payload.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ExternalCheckReceiptFailure(f"{context} must be a UTF-8 JSON object") from exc
    if not isinstance(decoded, dict):
        raise ExternalCheckReceiptFailure(f"{context} must be a JSON object")
    return decoded


def _load_unique_json(path: Path, context: str, *, max_bytes: int = 512 * 1024) -> dict[str, Any]:
    payload, _metadata, _absolute = _read_regular_single_link(
        path.expanduser(),
        context,
        max_bytes=max_bytes,
    )
    return _decode_unique_json(payload, context)


def _open_directory_chain_no_symlinks(directory: Path, context: str) -> tuple[int, Path]:
    absolute = Path(os.path.abspath(directory))
    no_follow = getattr(os, "O_NOFOLLOW", None)
    if not isinstance(no_follow, int) or no_follow == 0:
        raise ExternalCheckReceiptFailure(
            f"{context} cannot be read because this platform lacks no-follow path enforcement"
        )
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
        raise ExternalCheckReceiptFailure(
            f"{context} ancestor chain must contain only real directories"
        ) from exc


def _read_regular_single_link(
    path: Path,
    context: str,
    *,
    max_bytes: int,
) -> tuple[bytes, os.stat_result, Path]:
    absolute = Path(os.path.abspath(path))
    parent_descriptor, _ = _open_directory_chain_no_symlinks(absolute.parent, context)
    no_follow = getattr(os, "O_NOFOLLOW", None)
    if not isinstance(no_follow, int) or no_follow == 0:
        os.close(parent_descriptor)
        raise ExternalCheckReceiptFailure(
            f"{context} cannot be read because this platform lacks no-follow path enforcement"
        )
    descriptor = -1
    try:
        descriptor = os.open(
            absolute.name,
            os.O_RDONLY | no_follow,
            dir_fd=parent_descriptor,
        )
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_nlink != 1
            or metadata.st_size <= 0
            or metadata.st_size > max_bytes
        ):
            raise ExternalCheckReceiptFailure(
                f"{context} must be a bounded regular non-symlink single-link file"
            )
        chunks: list[bytes] = []
        remaining = metadata.st_size
        while remaining:
            chunk = os.read(descriptor, min(remaining, 64 * 1024))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        payload = b"".join(chunks)
        after = os.fstat(descriptor)
        path_after = os.stat(
            absolute.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
    except OSError as exc:
        raise ExternalCheckReceiptFailure(
            f"{context} must be a readable regular non-symlink single-link file"
        ) from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        os.close(parent_descriptor)
    before_identity = (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
    )
    after_identity = (
        after.st_dev,
        after.st_ino,
        after.st_nlink,
        after.st_size,
        after.st_mtime_ns,
    )
    path_identity = (
        path_after.st_dev,
        path_after.st_ino,
        path_after.st_nlink,
        path_after.st_size,
        path_after.st_mtime_ns,
    )
    if (
        before_identity != after_identity
        or before_identity != path_identity
        or len(payload) != metadata.st_size
    ):
        raise ExternalCheckReceiptFailure(f"{context} changed while it was read")
    return payload, metadata, absolute


def _bounded_int(value: Any, context: str, *, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise ExternalCheckReceiptFailure(
            f"{context} must be an integer from {minimum} to {maximum}"
        )
    return value


def _require_exact_fields(value: dict[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    if actual == expected:
        return
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    parts = []
    if missing:
        parts.append("missing " + ", ".join(missing))
    if unexpected:
        parts.append("unexpected " + ", ".join(unexpected))
    raise ExternalCheckReceiptFailure(
        f"{context} must contain the exact required field set ({'; '.join(parts)})"
    )


def _android_sensitive_field_path(value: Any, path: str = "") -> str | None:
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}" if path else key
            normalized = key.casefold()
            if normalized in ANDROID_FIELD_FORBIDDEN_KEYS or normalized.endswith(
                ANDROID_FIELD_SENSITIVE_KEY_SUFFIXES
            ):
                return child_path
            nested = _android_sensitive_field_path(child, child_path)
            if nested is not None:
                return nested
    elif isinstance(value, list):
        for index, child in enumerate(value):
            nested = _android_sensitive_field_path(child, f"{path}[{index}]")
            if nested is not None:
                return nested
    return None


def _bound_relative_artifact(
    receipt_path: Path,
    record: Any,
    context: str,
    *,
    max_bytes: int = 8 * 1024 * 1024,
) -> tuple[Path, bytes]:
    if not isinstance(record, dict):
        raise ExternalCheckReceiptFailure(
            f"{context} must contain exactly path, bytes and sha256"
        )
    _require_exact_fields(record, ARTIFACT_RECORD_FIELDS, context)
    raw_path = record.get("path")
    if not isinstance(raw_path, str) or not raw_path or len(raw_path) > 512:
        raise ExternalCheckReceiptFailure(f"{context}.path must be a safe relative path")
    relative = Path(raw_path)
    if (
        relative.is_absolute()
        or relative.as_posix() != raw_path
        or not relative.parts
        or any(part in {"", ".", ".."} for part in relative.parts)
    ):
        raise ExternalCheckReceiptFailure(f"{context}.path must be a safe relative path")
    expected_bytes = _bounded_int(
        record.get("bytes"),
        f"{context}.bytes",
        minimum=1,
        maximum=max_bytes,
    )
    expected_sha256 = record.get("sha256")
    if not isinstance(expected_sha256, str) or SHA256.fullmatch(expected_sha256) is None:
        raise ExternalCheckReceiptFailure(f"{context}.sha256 must be 64 lowercase hexadecimal characters")
    absolute = Path(os.path.abspath(receipt_path.parent / relative))
    receipt_parent = receipt_path.parent.absolute()
    try:
        if os.path.commonpath((str(receipt_parent), str(absolute))) != str(receipt_parent):
            raise ExternalCheckReceiptFailure(f"{context}.path escapes the receipt directory")
        payload, metadata, absolute = _read_regular_single_link(
            absolute,
            f"{context}.path",
            max_bytes=max_bytes,
        )
    except OSError as exc:
        raise ExternalCheckReceiptFailure(f"{context}.path must identify a readable regular file") from exc
    if metadata.st_size != expected_bytes:
        raise ExternalCheckReceiptFailure(f"{context}.bytes does not match the file")
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise ExternalCheckReceiptFailure(f"{context}.sha256 does not match the file")
    return absolute, payload


def _bound_relative_json_artifact(
    receipt_path: Path,
    record: Any,
    context: str,
    *,
    max_bytes: int = 8 * 1024 * 1024,
) -> tuple[Path, dict[str, Any]]:
    path, payload = _bound_relative_artifact(
        receipt_path,
        record,
        context,
        max_bytes=max_bytes,
    )
    return path, _decode_unique_json(payload, context)


def _https_url(value: Any, context: str) -> Any:
    if not _meaningful(value):
        raise ExternalCheckReceiptFailure(f"{context} is required")
    try:
        parsed = urlparse(value)
        parsed.port
    except ValueError as exc:
        raise ExternalCheckReceiptFailure(f"{context} must be a valid absolute HTTPS URL") from exc
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ExternalCheckReceiptFailure(f"{context} must be an absolute HTTPS URL without credentials")
    return parsed


def _validate_environment(check_id: str, environment: Any, fields: tuple[str, ...]) -> None:
    context = f"checks.{check_id}.receipt.environment"
    if not isinstance(environment, dict):
        raise ExternalCheckReceiptFailure(f"{context} must be an object")
    _require_exact_fields(environment, set(fields), context)
    for field in fields:
        if field == "gps_accuracy_m":
            accuracy = environment.get(field)
            if isinstance(accuracy, bool) or not isinstance(accuracy, (int, float)) or not 0 < accuracy <= 50:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be greater than 0 and at most 50")
        elif not _meaningful(environment.get(field)):
            raise ExternalCheckReceiptFailure(f"{context}.{field} is required")
    if "deployed_url" in fields:
        _https_url(environment.get("deployed_url"), f"{context}.deployed_url")
    if check_id == "tmap_live_route_smoke":
        if environment.get("provider") != "tmap_pedestrian":
            raise ExternalCheckReceiptFailure(f"{context}.provider must be tmap_pedestrian")
        endpoint = _https_url(environment.get("endpoint"), f"{context}.endpoint")
        if (
            endpoint.hostname != "apis.openapi.sk.com"
            or endpoint.port not in {None, 443}
            or endpoint.path != "/tmap/routes/pedestrian"
            or endpoint.params
            or endpoint.fragment
            or endpoint.query not in {"", "version=1"}
        ):
            raise ExternalCheckReceiptFailure(
                f"{context}.endpoint must be the exact HTTPS TMAP pedestrian endpoint"
            )
    elif check_id == "web_non_metric_advisory_field":
        expected = {
            "device_kind": "physical_phone",
            "camera_facing": "rear",
            "route_provider": "tmap_pedestrian",
        }
        for field, value in expected.items():
            if environment.get(field) != value:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be {value}")
    elif check_id == "web_accessibility_tts_haptic":
        if environment.get("device_kind") != "physical_phone":
            raise ExternalCheckReceiptFailure(f"{context}.device_kind must be physical_phone")
    elif check_id == "android_arcore_unsupported_camera_field":
        expected = {
            "application_id": "kr.co.hanium.dreamup.walksafe",
            "device_kind": "physical_phone",
            "camera_api": "CameraX",
            "imu_source": "physical_sensors",
            "route_provider": "tmap_pedestrian",
        }
        for field, value in expected.items():
            if environment.get(field) != value:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be {value}")
    elif check_id == "android_signed_release_physical_smoke":
        expected = {
            "application_id": "kr.co.hanium.dreamup.walksafe",
            "device_kind": "physical_phone",
            "camera_api": "CameraX",
            "route_provider": "tmap_pedestrian",
            "deployment": "signed_release",
        }
        for field, value in expected.items():
            if environment.get(field) != value:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be {value}")


def _validate_result(
    check_id: str,
    result: Any,
    fields: tuple[str, ...],
    source_commit: str,
    android_apk_sha256: str | None,
) -> None:
    context = f"checks.{check_id}.receipt.result"
    if not isinstance(result, dict):
        raise ExternalCheckReceiptFailure(f"{context} must be an object")
    _require_exact_fields(result, set(fields), context)

    if check_id == "pwa_mobile_install_update":
        for field in ("installed", "standalone_launch", "service_worker_update_observed"):
            if result.get(field) is not True:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be true")
        initial = result.get("initial_source_commit")
        if not isinstance(initial, str) or COMMIT.fullmatch(initial.lower()) is None:
            raise ExternalCheckReceiptFailure(f"{context}.initial_source_commit must be a full Git commit")
        if initial.lower() == source_commit or result.get("updated_source_commit") != source_commit:
            raise ExternalCheckReceiptFailure(f"{context} must prove an update from an earlier build to source_commit")
    elif check_id == "voice_report_microphone_e2e":
        if result.get("report_created") is not True:
            raise ExternalCheckReceiptFailure(f"{context}.report_created must be true")
        for field in ("report_id", "recognized_intent"):
            if not _meaningful(result.get(field)):
                raise ExternalCheckReceiptFailure(f"{context}.{field} is required")
        if result.get("recognized_intent") != "report_hazard":
            raise ExternalCheckReceiptFailure(f"{context}.recognized_intent must be report_hazard")
    elif check_id == "web_phone_outdoor_navigation":
        if not _meaningful(result.get("navigation_session_id")):
            raise ExternalCheckReceiptFailure(f"{context}.navigation_session_id is required")
        if result.get("route_provider") != "tmap_pedestrian":
            raise ExternalCheckReceiptFailure(f"{context}.route_provider must be tmap_pedestrian")
        count = result.get("guidance_event_count")
        if isinstance(count, bool) or not isinstance(count, int) or count < 2:
            raise ExternalCheckReceiptFailure(f"{context}.guidance_event_count must be at least 2")
        if result.get("destination_outcome") != "arrived":
            raise ExternalCheckReceiptFailure(f"{context}.destination_outcome must be arrived")
    elif check_id == "web_non_metric_advisory_field":
        expected = {
            "physical_device": True,
            "camera_facing": "rear",
            "route_provider": "tmap_pedestrian",
            "tmap_navigation_active": True,
            "advisory_tier": "CAMERA_NON_METRIC_ADVISORY",
            "advisory_level": "low",
            "metric_authority": False,
            "distance_authority": False,
            "step_authority": False,
            "report_authority": False,
            "route_change_authority": False,
            "haptic_observed": False,
            "fallback_after_gate_loss": "TMAP_ONLY",
        }
        for field, value in expected.items():
            matches = result.get(field) is value if isinstance(value, bool) else result.get(field) == value
            if not matches:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be {value!r}")
        _bounded_int(result.get("distinct_frame_count"), f"{context}.distinct_frame_count", minimum=3, maximum=1_000_000)
        _bounded_int(result.get("stabilization_ms"), f"{context}.stabilization_ms", minimum=700, maximum=600_000)
        if result.get("advisory_direction") not in {"left", "front", "right"}:
            raise ExternalCheckReceiptFailure(f"{context}.advisory_direction must be left, front or right")
    elif check_id == "web_accessibility_tts_haptic":
        for field in fields:
            if result.get(field) is not True:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be true")
    elif check_id == "android_arcore_unsupported_camera_field":
        if android_apk_sha256 is None or SHA256.fullmatch(android_apk_sha256) is None:
            raise ExternalCheckReceiptFailure(f"{context} requires the verified Android APK SHA-256")
        field_apk_sha256 = result.get("field_apk_sha256")
        if not isinstance(field_apk_sha256, str) or SHA256.fullmatch(field_apk_sha256) is None:
            raise ExternalCheckReceiptFailure(f"{context}.field_apk_sha256 must be a lowercase SHA-256")
        if field_apk_sha256 == android_apk_sha256:
            raise ExternalCheckReceiptFailure(
                f"{context}.field_apk_sha256 must differ from the signed release APK SHA-256"
            )
        expected = {
            "release_apk_sha256": android_apk_sha256,
            "field_apk_build_type": "debug",
            "physical_evidence_scope": "SAME_COMMIT_DEBUG_FIELD_APK",
            "release_apk_physically_executed": False,
            "sampling_contract": "AT_MOST_1HZ_MIN_60_OVER_15M_MAX_GAP_30S",
            "installed": True,
            "cold_start": True,
            "physical_device": True,
            "camera_api": "CameraX",
            "imu_fresh": True,
            "route_provider": "tmap_pedestrian",
            "tmap_navigation_active": True,
            "advisory_level": "low",
            "vibration_observed": False,
            "fallback_after_gate_loss": "TMAP_ONLY",
        }
        for field, value in expected.items():
            matches = result.get(field) is value if isinstance(value, bool) else result.get(field) == value
            if not matches:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be {value!r}")
        origin = result.get("arcore_unsupported_origin")
        availability = result.get("arcore_availability_state")
        if (origin, availability) not in ARCORE_UNSUPPORTED_ORIGINS:
            raise ExternalCheckReceiptFailure(f"{context} must record a definitive physical ARCore unsupported origin")
        _bounded_int(result.get("continuity_seconds"), f"{context}.continuity_seconds", minimum=900, maximum=28_800)
        if result.get("advisory_direction") not in {"LEFT", "CENTER", "RIGHT"}:
            raise ExternalCheckReceiptFailure(f"{context}.advisory_direction must be LEFT, CENTER or RIGHT")
        if result.get("accessibility_delivery") not in {"tts", "talkback"}:
            raise ExternalCheckReceiptFailure(f"{context}.accessibility_delivery must be tts or talkback")
    elif check_id == "android_signed_release_physical_smoke":
        if android_apk_sha256 is None or SHA256.fullmatch(android_apk_sha256) is None:
            raise ExternalCheckReceiptFailure(f"{context} requires the verified Android APK SHA-256")
        expected = {
            "release_apk_sha256": android_apk_sha256,
            "installed_apk_sha256": android_apk_sha256,
            "release_apk_build_type": "release",
            "release_apk_physically_executed": True,
            "installed": True,
            "cold_start": True,
            "physical_device": True,
            "arcore_unsupported_origin": "arcore_availability_unsupported",
            "arcore_availability_state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
            "camera_api": "CameraX",
            "camera_fallback_running": True,
            "detector_available": True,
            "route_provider": "tmap_pedestrian",
            "tmap_navigation_active": True,
            "advisory_level": "low",
            "metric": False,
            "tmap_authoritative": True,
            "distance_authority": False,
            "step_authority": False,
            "route_change_authority": False,
            "reports_allowed": False,
            "vibration_observed": False,
            "fallback_after_gate_loss": "TMAP_ONLY",
        }
        for field, value in expected.items():
            matches = result.get(field) is value if isinstance(value, bool) else result.get(field) == value
            if not matches:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be {value!r}")
        if result.get("advisory_direction") not in {"LEFT", "CENTER", "RIGHT"}:
            raise ExternalCheckReceiptFailure(f"{context}.advisory_direction must be LEFT, CENTER or RIGHT")
        if result.get("accessibility_delivery") not in {"tts", "talkback"}:
            raise ExternalCheckReceiptFailure(f"{context}.accessibility_delivery must be tts or talkback")
    elif check_id == "tmap_live_route_smoke":
        if result.get("provider") != "tmap_pedestrian" or result.get("http_status") != 200:
            raise ExternalCheckReceiptFailure(f"{context} must record a successful TMAP pedestrian response")
        if not _meaningful(result.get("route_id")):
            raise ExternalCheckReceiptFailure(f"{context}.route_id is required")
        for field, minimum in (("coordinate_count", 2), ("instruction_count", 1)):
            count = result.get(field)
            if isinstance(count, bool) or not isinstance(count, int) or count < minimum:
                raise ExternalCheckReceiptFailure(f"{context}.{field} must be at least {minimum}")


def _validate_android_field_summary(
    receipt_path: Path,
    record: Any,
    *,
    source_commit: str,
    field_apk_sha256: str,
    result: dict[str, Any],
) -> dict[str, Any]:
    context = "checks.android_arcore_unsupported_camera_field.receipt.field_session_summary"
    path, summary = _bound_relative_json_artifact(receipt_path, record, context)
    if summary.get("schema_version") != "android.field_summary.v1":
        raise ExternalCheckReceiptFailure(f"{context}.schema_version must be android.field_summary.v1")
    sensitive_path = _android_sensitive_field_path(summary)
    if sensitive_path is not None:
        raise ExternalCheckReceiptFailure(
            f"{context} contains forbidden sensitive field {sensitive_path!r}"
        )
    if summary.get("summary_available") is not True:
        raise ExternalCheckReceiptFailure(f"{context}.summary_available must be True")
    if summary.get("input_format") != ANDROID_CURRENT_DEVICE_INPUT_FORMAT:
        raise ExternalCheckReceiptFailure(
            f"{context}.input_format must be {ANDROID_CURRENT_DEVICE_INPUT_FORMAT!r}"
        )

    integrity = summary.get("integrity")
    if not isinstance(integrity, dict):
        raise ExternalCheckReceiptFailure(f"{context}.integrity must be an object")
    expected_integrity = {
        "summary_available": True,
        "legacy_plaintext_history_only": False,
        "encrypted_aead_envelope_detected": False,
        "strict_mode": "camera-non-metric",
        "camera_advisory_required": True,
        "arcore_unsupported_required": True,
        "evidence_scope": "ARCORE_UNSUPPORTED_FIELD",
        "arcore_unsupported_verified": True,
        "expected_source_commit": source_commit,
        "expected_apk_sha256": field_apk_sha256,
    }
    for field, expected in expected_integrity.items():
        actual = integrity.get(field)
        matches = actual is expected if isinstance(expected, bool) else actual == expected
        if not matches:
            raise ExternalCheckReceiptFailure(f"{context}.integrity.{field} must be {expected!r}")
    if integrity.get("strict_failure_reasons") != []:
        raise ExternalCheckReceiptFailure(f"{context}.integrity.strict_failure_reasons must be empty")
    for count_field, list_field in (
        ("malformed_or_unknown_record_count", "errors"),
        ("privacy_violation_count", "privacy_violations"),
        ("missing_provenance_count", "missing_provenance_sessions"),
        ("duplicate_session_id_count", "duplicate_session_ids"),
    ):
        if integrity.get(count_field) != 0 or integrity.get(list_field) != []:
            raise ExternalCheckReceiptFailure(
                f"{context}.integrity must have no {count_field.removesuffix('_count')} failures"
            )

    selected_id = integrity.get("strict_selected_session_id")
    sessions = summary.get("sessions")
    if not _meaningful(selected_id) or not isinstance(sessions, list) or not sessions:
        raise ExternalCheckReceiptFailure(f"{context} must identify a selected field session")
    session_ids: set[str] = set()
    for index, candidate in enumerate(sessions):
        if not isinstance(candidate, dict):
            raise ExternalCheckReceiptFailure(f"{context}.sessions[{index}] must be an object")
        session_id = candidate.get("session_id")
        if (
            not _meaningful(session_id)
            or session_id != session_id.strip()
            or len(session_id) > 256
        ):
            raise ExternalCheckReceiptFailure(
                f"{context}.sessions[{index}].session_id must be a canonical identifier"
            )
        if session_id in session_ids:
            raise ExternalCheckReceiptFailure(f"{context} contains duplicate session_id {session_id!r}")
        session_ids.add(session_id)
    selected = [
        session
        for session in sessions
        if session.get("session_id") == selected_id
    ]
    if len(selected) != 1:
        raise ExternalCheckReceiptFailure(f"{context} selected field session must be unique")
    session = selected[0]
    if session.get("status") != "completed":
        raise ExternalCheckReceiptFailure(f"{context} selected field session status must be completed")
    provenance = session.get("provenance")
    if not isinstance(provenance, dict):
        raise ExternalCheckReceiptFailure(f"{context} selected field session provenance is required")
    if provenance.get("source_commit") != source_commit:
        raise ExternalCheckReceiptFailure(f"{context} selected field session source_commit does not match")
    if provenance.get("apk_sha256") != field_apk_sha256:
        raise ExternalCheckReceiptFailure(f"{context} selected field session apk_sha256 does not match")

    strict = session.get("strict_evidence")
    if not isinstance(strict, dict):
        raise ExternalCheckReceiptFailure(f"{context} selected strict_evidence is required")
    if (
        strict.get("malformed_or_unknown_record_count") != 0
        or strict.get("errors") != []
        or strict.get("privacy_violation_count") != 0
        or strict.get("privacy_violations") != []
    ):
        raise ExternalCheckReceiptFailure(f"{context} selected field session contains strict failures")
    camera = strict.get("camera_non_metric")
    if not isinstance(camera, dict):
        raise ExternalCheckReceiptFailure(f"{context} selected camera_non_metric evidence is required")

    starts = camera.get("session_started_contracts")
    expected_origin = result["arcore_unsupported_origin"]
    expected_state = result["arcore_availability_state"]
    if not isinstance(starts, list) or not starts:
        raise ExternalCheckReceiptFailure(f"{context} must contain an ARCore unsupported start contract")
    start_pairs: set[tuple[Any, Any]] = set()
    for start in starts:
        if not isinstance(start, dict):
            raise ExternalCheckReceiptFailure(f"{context} ARCore unsupported start contract is invalid")
        pair = (start.get("reason"), start.get("state"))
        if pair not in ARCORE_UNSUPPORTED_ORIGINS:
            raise ExternalCheckReceiptFailure(f"{context} contains a non-physical ARCore unsupported origin")
        if (
            not _meaningful(start.get("loaded_model"))
            or type(start.get("model_fallback_used")) is not bool
            or start.get("metric") is not False
            or start.get("reports_allowed") is not False
        ):
            raise ExternalCheckReceiptFailure(f"{context} ARCore unsupported start contract is unsafe")
        start_pairs.add(pair)
    if (expected_origin, expected_state) not in start_pairs:
        raise ExternalCheckReceiptFailure(f"{context} does not contain the receipt's ARCore unsupported origin")

    frames = camera.get("frame_analyzed_contracts")
    if not isinstance(frames, list) or not frames:
        raise ExternalCheckReceiptFailure(f"{context} must contain a CameraX analyzed frame contract")
    for frame in frames:
        if (
            not isinstance(frame, dict)
            or not _meaningful(frame.get("loaded_model"))
            or type(frame.get("model_fallback_used")) is not bool
            or frame.get("metric") is not False
            or frame.get("reports_allowed") is not False
            or frame.get("state") != "detector_succeeded"
        ):
            raise ExternalCheckReceiptFailure(f"{context} CameraX analyzed frame contract is unsafe")

    advisories = camera.get("advisory_emitted_contracts")
    if not isinstance(advisories, list) or not advisories:
        raise ExternalCheckReceiptFailure(f"{context} must contain a delivered low advisory contract")
    matching_direction = False
    for advisory in advisories:
        if (
            not isinstance(advisory, dict)
            or not _meaningful(advisory.get("loaded_model"))
            or type(advisory.get("model_fallback_used")) is not bool
            or advisory.get("direction") not in {"LEFT", "CENTER", "RIGHT"}
            or advisory.get("metric") is not False
            or advisory.get("tmap_authoritative") is not True
            or advisory.get("reports_allowed") is not False
        ):
            raise ExternalCheckReceiptFailure(f"{context} delivered advisory contract is unsafe")
        matching_direction |= advisory["direction"] == result["advisory_direction"]
    if not matching_direction:
        raise ExternalCheckReceiptFailure(f"{context} does not contain the receipt advisory direction")

    sample_container = summary.get("camera_non_metric")
    sample_summary = (
        sample_container.get("strict_selected_session_samples")
        if isinstance(sample_container, dict)
        else None
    )
    if not isinstance(sample_summary, dict):
        raise ExternalCheckReceiptFailure(f"{context} strict selected sample summary is required")
    sample_count = _bounded_int(
        sample_summary.get("sample_count"),
        f"{context}.sample_count",
        minimum=60,
        maximum=1_000_000,
    )
    sample_span_ms = _bounded_int(
        sample_summary.get("sample_span_ms"),
        f"{context}.sample_span_ms",
        minimum=900_000,
        maximum=28_800_000,
    )
    min_gap_ms = _bounded_int(
        sample_summary.get("min_sample_gap_ms"),
        f"{context}.min_sample_gap_ms",
        minimum=1_000,
        maximum=30_000,
    )
    max_gap_ms = _bounded_int(
        sample_summary.get("max_sample_gap_ms"),
        f"{context}.max_sample_gap_ms",
        minimum=min_gap_ms,
        maximum=30_000,
    )
    if (
        sample_summary.get("invalid_sample_count") != 0
        or sample_summary.get("elapsed_contract_valid") is not True
        or sample_summary.get("elapsed_sequence_valid") is not True
        or sample_summary.get("recorded_at_contract_valid") is not True
    ):
        raise ExternalCheckReceiptFailure(f"{context} strict selected sample contracts must all be valid")
    if result["continuity_seconds"] * 1_000 > sample_span_ms:
        raise ExternalCheckReceiptFailure(f"{context} sample span is shorter than the observed continuity")

    samples = camera.get("inference_sample_contracts")
    if not isinstance(samples, list) or len(samples) != sample_count:
        raise ExternalCheckReceiptFailure(f"{context} sample count differs from the selected session samples")
    active_sample_indices: list[int] = []
    active_elapsed_values: list[int] = []
    fallback_sample_indices: list[int] = []
    elapsed_values: list[int] = []
    for index, sample in enumerate(samples):
        if not isinstance(sample, dict) or set(sample) != ANDROID_SAMPLE_FIELDS:
            raise ExternalCheckReceiptFailure(f"{context} contains an invalid inference sample field set")
        elapsed_values.append(
            _bounded_int(
                sample.get("elapsed_realtime_ms"),
                f"{context}.elapsed_realtime_ms",
                minimum=0,
                maximum=(1 << 63) - 1,
            )
        )
        _bounded_int(
            sample.get("inference_ms"),
            f"{context}.inference_ms",
            minimum=0,
            maximum=(1 << 63) - 1,
        )
        _bounded_int(
            sample.get("detection_count"),
            f"{context}.detection_count",
            minimum=0,
            maximum=(1 << 31) - 1,
        )
        boolean_context_fields = (
            "camera_permission_granted",
            "camera_fallback_running",
            "detector_available",
            "imu_fresh",
            "tmap_route_active",
        )
        if any(type(sample.get(field)) is not bool for field in boolean_context_fields):
            raise ExternalCheckReceiptFailure(f"{context} inference sample gate fields must be booleans")
        gate_fields = (
            "camera_permission_granted",
            "camera_fallback_running",
            "detector_available",
            "imu_fresh",
        )
        gates_active = all(sample[field] for field in gate_fields)
        if sample.get("capability_tier") == "CAMERA_IMU_NON_METRIC" and gates_active:
            active_sample_indices.append(index)
            active_elapsed_values.append(elapsed_values[-1])
        elif sample.get("capability_tier") == "TMAP_ONLY" and not gates_active:
            fallback_sample_indices.append(index)
        else:
            raise ExternalCheckReceiptFailure(f"{context} inference sample capability tier is inconsistent")
        if (
            sample.get("metric") is not False
            or sample.get("reports_allowed") is not False
            or sample.get("state") != "detector_succeeded"
        ):
            raise ExternalCheckReceiptFailure(f"{context} inference sample authority contract is unsafe")
    active_gaps = [
        current - previous
        for previous, current in zip(active_elapsed_values, active_elapsed_values[1:])
    ]
    if (
        len(active_elapsed_values) < 60
        or active_elapsed_values[-1] - active_elapsed_values[0] < 900_000
        or any(not 1_000 <= gap <= 30_000 for gap in active_gaps)
    ):
        raise ExternalCheckReceiptFailure(
            f"{context} must contain 60 CameraX + fresh IMU samples spanning 15 minutes"
        )
    if not fallback_sample_indices or fallback_sample_indices[-1] <= active_sample_indices[-1]:
        raise ExternalCheckReceiptFailure(
            f"{context} must contain a later required-gate-loss TMAP_ONLY fallback"
        )
    elapsed_gaps = [
        current - previous
        for previous, current in zip(elapsed_values, elapsed_values[1:])
    ]
    if (
        not elapsed_gaps
        or any(not 1_000 <= gap <= 30_000 for gap in elapsed_gaps)
        or elapsed_values[-1] - elapsed_values[0] != sample_span_ms
        or min(elapsed_gaps) != min_gap_ms
        or max(elapsed_gaps) != max_gap_ms
    ):
        raise ExternalCheckReceiptFailure(
            f"{context} sample count/span/gap aggregates do not match the raw elapsed sequence"
        )
    return {
        "path": str(path),
        "sha256": record["sha256"],
        "bytes": record["bytes"],
        "sample_count": sample_count,
        "sample_span_ms": sample_span_ms,
        "max_sample_gap_ms": max_gap_ms,
    }


def validate_external_check_receipt(
    check_id: str,
    path: Path,
    *,
    source_commit: str,
    release_artifacts_sha256: str,
    observed_at: datetime,
    android_apk_sha256: str | None = None,
) -> dict[str, Any]:
    """Return a normalized summary or fail when a real-world check receipt is incomplete."""
    contract = CHECK_CONTRACTS.get(check_id)
    if contract is None:
        raise ExternalCheckReceiptFailure(f"checks.{check_id} has no external receipt contract")
    context = f"checks.{check_id}.receipt"
    if path.suffix.casefold() != ".json":
        raise ExternalCheckReceiptFailure(f"{context} must be a JSON file")
    payload = _load_unique_json(path, context)
    if payload.get("schema_version") != SCHEMA_VERSION:
        raise ExternalCheckReceiptFailure(f"{context}.schema_version must be {SCHEMA_VERSION}")
    if payload.get("check_id") != check_id:
        raise ExternalCheckReceiptFailure(f"{context}.check_id must identify {check_id}")
    if check_id in UNATTESTED_ANDROID_PHYSICAL_CHECKS:
        raise ExternalCheckReceiptFailure(f"{context} {UNATTESTED_ANDROID_REASON}")
    expected_fields = set(BASE_RECEIPT_FIELDS)
    if check_id == "android_arcore_unsupported_camera_field":
        expected_fields.update(("field_apk", "field_session_summary"))
    _require_exact_fields(payload, expected_fields, context)
    if payload.get("check_id") != check_id or payload.get("result_status") != "passed":
        raise ExternalCheckReceiptFailure(f"{context} must identify a passed {check_id} observation")
    if payload.get("source_commit") != source_commit:
        raise ExternalCheckReceiptFailure(f"{context}.source_commit does not match the tested release source")
    if payload.get("release_artifacts_sha256") != release_artifacts_sha256:
        raise ExternalCheckReceiptFailure(f"{context}.release_artifacts_sha256 does not match the tested artifacts")
    if not _meaningful(payload.get("operator")):
        raise ExternalCheckReceiptFailure(f"{context}.operator is required")

    started_at = _parse_timestamp(payload.get("started_at"), f"{context}.started_at")
    completed_at = _parse_timestamp(payload.get("completed_at"), f"{context}.completed_at")
    if completed_at != observed_at:
        raise ExternalCheckReceiptFailure(f"{context}.completed_at must equal the enclosing check observed_at")
    if started_at > completed_at or completed_at - started_at > timedelta(hours=8):
        raise ExternalCheckReceiptFailure(f"{context} timestamps are out of order or span more than 8 hours")
    if (
        check_id == "android_arcore_unsupported_camera_field"
        and completed_at - started_at < timedelta(minutes=15)
    ):
        raise ExternalCheckReceiptFailure(f"{context} must span at least 15 minutes")

    _validate_environment(check_id, payload.get("environment"), contract["environment"])
    observations = payload.get("observations")
    if not isinstance(observations, list) or not observations:
        raise ExternalCheckReceiptFailure(f"{context}.observations must be a non-empty array")
    observation_ids: set[str] = set()
    previous_at = started_at
    observation_times: dict[str, datetime] = {}
    for index, observation in enumerate(observations):
        item_context = f"{context}.observations[{index}]"
        if not isinstance(observation, dict):
            raise ExternalCheckReceiptFailure(f"{item_context} must be an object")
        _require_exact_fields(observation, OBSERVATION_FIELDS, item_context)
        step_id = observation.get("step_id")
        if not isinstance(step_id, str) or step_id not in contract["steps"] or step_id in observation_ids:
            raise ExternalCheckReceiptFailure(f"{item_context}.step_id is invalid or duplicated")
        step_at = _parse_timestamp(observation.get("observed_at"), f"{item_context}.observed_at")
        if not started_at <= step_at <= completed_at or (index > 0 and step_at <= previous_at):
            raise ExternalCheckReceiptFailure(f"{item_context}.observed_at is outside the ordered receipt window")
        latency = observation.get("latency_ms")
        if isinstance(latency, bool) or not isinstance(latency, int) or not 0 <= latency <= 600_000:
            raise ExternalCheckReceiptFailure(f"{item_context}.latency_ms must be an integer from 0 to 600000")
        if observation.get("status") != "passed" or not _meaningful(observation.get("note")):
            raise ExternalCheckReceiptFailure(f"{item_context} must record passed status and a non-placeholder note")
        observation_ids.add(step_id)
        observation_times[step_id] = step_at
        previous_at = step_at
    if observation_ids != set(contract["steps"]):
        raise ExternalCheckReceiptFailure(f"{context}.observations must cover the exact required step set")
    if tuple(observation["step_id"] for observation in observations) != contract["steps"]:
        raise ExternalCheckReceiptFailure(f"{context}.observations must use the required step order")
    if (
        check_id == "android_arcore_unsupported_camera_field"
        and observation_times["continuity_15m_completed"] - started_at < timedelta(minutes=15)
    ):
        raise ExternalCheckReceiptFailure(
            f"{context}.continuity_15m_completed must occur at least 15 minutes after started_at"
        )

    criteria = payload.get("pass_criteria")
    if not isinstance(criteria, list) or not criteria:
        raise ExternalCheckReceiptFailure(f"{context}.pass_criteria must be a non-empty array")
    criterion_ids: set[str] = set()
    referenced_steps: set[str] = set()
    for index, criterion in enumerate(criteria):
        item_context = f"{context}.pass_criteria[{index}]"
        if not isinstance(criterion, dict):
            raise ExternalCheckReceiptFailure(f"{item_context} must be an object")
        _require_exact_fields(criterion, CRITERION_FIELDS, item_context)
        criterion_id = criterion.get("criterion_id")
        refs = criterion.get("observation_step_ids")
        if (
            not isinstance(criterion_id, str)
            or criterion_id not in contract["criteria"]
            or criterion_id in criterion_ids
            or criterion.get("passed") is not True
            or not isinstance(refs, list)
            or not refs
            or any(not isinstance(ref, str) or ref not in observation_ids for ref in refs)
            or len(refs) != len(set(refs))
        ):
            raise ExternalCheckReceiptFailure(f"{item_context} is invalid")
        criterion_ids.add(criterion_id)
        referenced_steps.update(refs)
    if criterion_ids != set(contract["criteria"]) or referenced_steps != observation_ids:
        raise ExternalCheckReceiptFailure(f"{context}.pass_criteria must cover the exact criteria and all observations")
    if tuple(criterion["criterion_id"] for criterion in criteria) != contract["criteria"]:
        raise ExternalCheckReceiptFailure(f"{context}.pass_criteria must use the required criterion order")

    _validate_result(
        check_id,
        payload.get("result"),
        contract["result"],
        source_commit,
        android_apk_sha256,
    )
    normalized = {
        "schema_version": SCHEMA_VERSION,
        "check_id": check_id,
        "completed_at": completed_at.isoformat(),
        "observation_count": len(observations),
        "criterion_count": len(criteria),
        "environment": payload["environment"],
        "result": payload["result"],
    }
    if check_id == "android_arcore_unsupported_camera_field":
        if android_apk_sha256 is None:
            raise ExternalCheckReceiptFailure(
                f"{context} requires the verified Android APK SHA-256"
            )
        field_apk_path, _field_apk_payload = _bound_relative_artifact(
            path,
            payload.get("field_apk"),
            f"{context}.field_apk",
            max_bytes=512 * 1024 * 1024,
        )
        if field_apk_path.suffix != ".apk":
            raise ExternalCheckReceiptFailure(f"{context}.field_apk.path must end in .apk")
        field_apk_record = payload["field_apk"]
        field_apk_sha256 = field_apk_record["sha256"]
        if payload["result"].get("field_apk_sha256") != field_apk_sha256:
            raise ExternalCheckReceiptFailure(
                f"{context}.result.field_apk_sha256 does not match field_apk"
            )
        normalized["field_apk"] = {
            "path": str(field_apk_path),
            "sha256": field_apk_sha256,
            "bytes": field_apk_record["bytes"],
        }
        normalized["field_session_summary"] = _validate_android_field_summary(
            path,
            payload.get("field_session_summary"),
            source_commit=source_commit,
            field_apk_sha256=field_apk_sha256,
            result=payload["result"],
        )
    return normalized
